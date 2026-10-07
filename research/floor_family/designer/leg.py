# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
from sklearn.metrics import roc_auc_score
F = pd.read_parquet("near_frames.parquet")
c = F[F.exercise == "라잉 레그 레이즈"].copy()
K = "허벅지와 종아리 각도 고정"; HD = "고개 숙임 여부"; DROP = "이완 시 다리 긴장유지"; WAIST = "허리 지면 고정"
def q(x, qs=(.02, .05, .1, .5, .9, .95, .98)):
    x = np.asarray(x, float); x = x[np.isfinite(x)]
    return [round(float(np.quantile(x, a)), 1) for a in qs] + [len(x)]
for v in "CE":
    a = c[c.view == v]
    d = a[(a.chain_vis >= .5)].sort_values(["clip_id", "frame_idx"])
    print(f"\n== 뷰 {v} 사슬 통과 {len(d)}/{len(a)} | 양무릎 {a.both_knee.mean():.3f} 양발목 {a.both_ankle.mean():.3f} | 가까운 발목≥.5 {(a.vis_an>=.5).mean():.3f}")
    reps = []
    for cid, g in d.groupby("clip_id"):
        x = g.lr_thigh.to_numpy()
        if len(x) < 8: continue
        rng = x.max() - x.min(); rest = np.quantile(x, .1)
        pk, _ = find_peaks(np.r_[x.min(), x, x.min()], prominence=max(10.0, .4 * rng))
        kn = g.knee.to_numpy(); hl = g.lr_head_lift.to_numpy(); leg = g.lr_leg.to_numpy()
        for i in pk - 1:
            if 0 <= i < len(x):
                lo, hi = max(0, i - 1), min(len(x), i + 2)
                reps.append(dict(clip=cid, perf=g.performer.iloc[0], t=g.type_key.iloc[0], kok=bool(g[K].iloc[0]), hok=bool(g[HD].iloc[0]),
                                 amp=x[i] - rest, top=x[i], knee_top=np.median(kn[lo:hi]), knee_min=kn.min(), leg_top=leg[i], bottom=np.quantile(leg, .1)))
    R = pd.DataFrame(reps)
    print(f"  회 {len(R)} 클립당 {R.groupby('clip').size().value_counts().sort_index().to_dict()}")
    nk = R.kok
    print(f"  회별 허벅지 진폭 무릎충족 {q(R.amp[nk])} | 473 {q(R.amp[R.t=='473'])}")
    for th in (25, 35, 45):
        print(f"   진폭<{th}: 473 회 기각 {(R.amp[R.t=='473'] < th).mean():.3f} 충족 {(R.amp[nk] < th).mean():.3f}")
    print(f"  회 정점 무릎각 충족 {q(R.knee_top[nk])} | 위반(무릎 90도) {q(R.knee_top[~nk])}  AUC {roc_auc_score(~nk, -R.knee_top):.3f}")
    for th in (100, 110, 120, 130, 140):
        print(f"   정점 무릎<{th}: 충족 기각 {(R.knee_top[nk] < th).mean():.3f} 473 {(R.knee_top[R.t=='473'] < th).mean():.3f} | 위반 검출 {(R.knee_top[~nk] < th).mean():.3f}")
    # 수행자 안: 같은 수행자 충족 회 중앙값 대비
    pm = R[nk].groupby("perf").knee_top.median()
    print(f"  수행자별 충족 정점 무릎 중앙값 p10/p50/p90 {np.quantile(pm,[.1,.5,.9]).round(1).tolist()}")
    print(f"  회 정점 허벅지각(절대) 473 {q(R.top[R.t=='473'])} 위반(무릎) {q(R.top[~nk])}")
    print(f"  회 정점 다리각 473 {q(R.leg_top[R.t=='473'])} | 클립 하단 다리각 p10: 473 {q(R.groupby('clip').bottom.first()[R.groupby('clip').t.first()=='473'])}")
    # 고개: 클립 중앙 머리 들림
    cl = d.groupby("clip_id").agg(hl=("lr_head_lift", "median"), ht=("head_trunk", "median"), ok=(HD, "first"), t=("type_key", "first"), perf=("performer", "first"))
    cl["ok"] = cl.ok.astype(bool)
    print(f"  클립 머리 들림 중앙값 충족 {q(cl.hl[cl.ok])} 위반 {q(cl.hl[~cl.ok])} AUC {roc_auc_score(~cl.ok, cl.hl):.3f}")
    for th in (20, 22, 25, 28):
        print(f"   머리들림>{th}: 충족 클립 {(cl.hl[cl.ok] > th).mean():.3f} 473 {(cl.hl[cl.t=='473'] > th).mean():.3f} | 위반 검출 {(cl.hl[~cl.ok] > th).mean():.3f}")
    # 프레임 단위(창 대신)
    fr = d.copy(); fr["ok"] = fr[HD].astype(bool)
    print(f"  프레임 머리 들림 충족 p95/p98/p99 {np.quantile(fr.lr_head_lift[fr.ok],[.95,.98,.99]).round(1).tolist()}")
    print(f"  현행 규칙 head_trunk(가까운 귀) 중앙<73.08: 충족 {(cl.ht[cl.ok] < 73.08).mean():.3f} 위반 {(cl.ht[~cl.ok] < 73.08).mean():.3f}")
    pmh = cl[cl.ok].groupby("perf").hl.median()
    print(f"  수행자별 정상 머리 들림 중앙 p10/p50/p90 {np.quantile(pmh,[.1,.5,.9]).round(1).tolist()}")
