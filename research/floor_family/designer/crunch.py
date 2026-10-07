# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
from sklearn.metrics import roc_auc_score
F = pd.read_parquet("near_frames.parquet")
c = F[F.exercise == "크런치"].copy()
S = "견갑골이 지면으로부터 충분히 올라옴"
def q(x, qs=(.02, .05, .1, .5, .9, .95, .98)):
    x = np.asarray(x, float); x = x[np.isfinite(x)]
    return [round(float(np.quantile(x, a)), 2) for a in qs] + [len(x)] if len(x) else ["n=0"]
for v in "CE":
    d = c[(c.view == v) & (c.chain_vis >= .5)].sort_values(["clip_id", "frame_idx"])
    print(f"\n== 뷰 {v} 가까운쪽 사슬 통과 프레임 {len(d)}/{(c.view==v).sum()} | 양발목 요구 {c[c.view==v].both_ankle.mean():.3f} 양무릎 {c[c.view==v].both_knee.mean():.3f} 양귀+코 {c[c.view==v].both_ear.mean():.3f} 가까운귀≥.5 {(c[c.view==v].vis_ear>=.5).mean():.3f}")
    print(f"  무릎각(가까운) {q(d.knee)}")
    reps = []
    for cid, g in d.groupby("clip_id"):
        x = g.cr_trunk.to_numpy(); e = g.cr_ear.to_numpy()
        if len(x) < 8: continue
        rest = np.quantile(x, .1)
        # 반복 = 휴식(p10) 대비 봉우리. 봉우리 prominence ≥ 1° 만 (잡음 바닥)
        pk, pr = find_peaks(np.r_[x.min(), x, x.min()], prominence=1.0)
        for i in pk - 1:
            if 0 <= i < len(x):
                reps.append(dict(clip=cid, ok=bool(g[S].iloc[0]), t=g.type_key.iloc[0], perf=g.performer.iloc[0], lift=x[i] - rest, peak=x[i], ear=e[i] - np.quantile(e, .1),
                                 neck=g.cr_neck.to_numpy()[i] - np.quantile(g.cr_neck, .1)))
    R = pd.DataFrame(reps)
    print(f"  반복(봉우리) {len(R)}: 충족 {R.ok.sum()} 위반 {(~R.ok).sum()}")
    print(f"  회별 몸통 현 들림(휴식 대비) 충족 {q(R.lift[R.ok])} | 전조건정상489 {q(R.lift[R.t=='489'])} | 고개만 까딱 {q(R.lift[~R.ok])}")
    print(f"  회별 정점 절대각 충족 {q(R.peak[R.ok])} 위반 {q(R.peak[~R.ok])}")
    for th in (3, 4, 5, 6, 8):
        print(f"   θ={th}°: 충족 기각 {(R.lift[R.ok] < th).mean():.3f} 489 기각 {(R.lift[R.t=='489'] < th).mean():.3f} | 위반 기각 {(R.lift[~R.ok] < th).mean():.3f}")
    # 클립 단위(최대 회)
    cl = R.groupby("clip").agg(mx=("lift", "max"), ok=("ok", "first"), t=("t", "first"))
    print(f"  클립 최대 회 들림 AUC {roc_auc_score(~cl.ok, -cl.mx):.3f}; 충족 {q(cl.mx[cl.ok])} 위반 {q(cl.mx[~cl.ok])}")
    # 목만: 귀 들림 / 몸통 들림 비, 목 굽힘 변화
    R["ratio"] = R.ear / np.maximum(R.lift, 1.0)
    print(f"  회별 귀 들림 충족 {q(R.ear[R.ok])} 위반 {q(R.ear[~R.ok])} AUC(위반↑ 비율) {roc_auc_score(~R.ok, R.ratio):.3f} 목굽힘변화 AUC {roc_auc_score(~R.ok, R.neck):.3f}")
    # 윗몸일으키기 배제 상한: 정점 절대각 분포 상단
    print(f"  정점 절대각 p99 {np.quantile(R.peak,.99):.1f} max {R.peak.max():.1f}")
    # 사람 사이 vs 안: 수행자별 정상 회 들림 중앙값
    pm = R[R.ok].groupby("perf").lift.median()
    print(f"  수행자별 충족 회 들림 중앙값 p10/p50/p90 {np.quantile(pm,[.1,.5,.9]).round(1).tolist()} n={len(pm)}")
    # 휴식 잡음: 클립 p10 주변 (하위 30% 프레임) 표준편차
    nz = d.groupby("clip_id").cr_trunk.apply(lambda s: s[s <= s.quantile(.3)].std())
    print(f"  휴식 구간 잡음 SD 중앙 {nz.median():.2f}°")
