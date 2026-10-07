# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from sklearn.metrics import roc_auc_score
F = pd.read_parquet("near_frames.parquet")
p = F[F.exercise == "플랭크"].copy()
C = "몸통과 엉덩이의 정렬 유지"
p["ok"] = p[C].astype(bool)
base = (p.chain_vis >= .5) & p.len_ok & (p.horiz >= .7) & (p.knee >= 145)
p["strict"] = base & (p.ratio >= 8)
p["relax"] = base
p["relax3"] = base & (p.ratio >= 3)
def q(x, qs=(.01, .02, .05, .5, .95, .98, .99)):
    x = np.asarray(x, float); x = x[np.isfinite(x)]
    return [round(float(np.quantile(x, a)), 3) for a in qs] + [len(x)]
for v in "CE":
    d = p[p.view == v]
    print(f"\n== 뷰 {v}  프레임 {len(d)}  ratio p10/p50/p90 {np.quantile(d.ratio,[.1,.5,.9]).round(1).tolist()}")
    for g in ("strict", "relax3", "relax"):
        dd = d[d[g]]
        print(f" 게이트 {g:7s} 통과 {d[g].mean():.3f} | 충족 hip_off q01..q99,n {q(dd.hip_off[dd.ok])} | 553 {q(dd.hip_off[dd.type_key=='553'])} | 위반 p50 {np.median(dd.hip_off[~dd.ok]):.3f}")
        cl = dd.groupby("clip_id").agg(med=("hip_off", "median"), ok=("ok", "first"), perf=("performer", "first"), n=("hip_off", "size"))
        cl = cl[cl.n >= 3]
        print(f"   클립 중앙값 AUC {roc_auc_score(~cl.ok, cl.med):.3f} (클립 {len(cl)})")
    # 띠 후보별 프레임 띠 밖 비율(정상) / 위반 검출(클립 중앙값 기준)
    dd = d[d.relax3]
    for lo, hi in ((-.06, .124), (-.06, .15), (-.08, .16), (-.08, .18), (-.10, .20)):
        out = lambda s: float(((s < lo) | (s > hi)).mean())
        cl = dd.groupby("clip_id").agg(med=("hip_off", "median"), ok=("ok", "first"))
        # 연속 2프레임 이상 띠 밖 클립 비율(지속의 대리 — AIHub 프레임 간격 미상)
        def run2(g):
            o = ((g.hip_off < lo) | (g.hip_off > hi)).to_numpy()
            return bool(np.any(o[1:] & o[:-1]))
        rr = dd.sort_values("frame_idx").groupby("clip_id").apply(run2)
        okc = dd.groupby("clip_id").ok.first()
        a553 = dd.groupby("clip_id").type_key.first() == "553"
        print(f"  띠[{lo:+.2f},{hi:+.3f}] 정상프레임 밖 {out(dd.hip_off[dd.ok]):.3f} 553 {out(dd.hip_off[dd.type_key=='553']):.3f} | 연속2 클립: 충족 {rr[okc].mean():.3f} 553 {rr[a553].mean():.3f} 위반 {rr[~okc].mean():.3f} | 클립중앙 위반검출 {out(cl.med[~cl.ok]):.3f}")
    # 수행자별 정상 중앙값 분산 (사람 사이 vs 사람 안)
    dn = dd[dd.ok]
    pm = dn.groupby("performer").hip_off.median()
    within = dn.groupby("clip_id").hip_off.apply(lambda s: s.quantile(.9) - s.quantile(.1)).median()
    print(f"  정상 수행자 중앙값 p10/p50/p90 {np.quantile(pm,[.1,.5,.9]).round(3).tolist()} (n={len(pm)}) | 클립 안 p90-p10 중앙 {within:.3f}")
    # 무릎·팔꿈치·상완·골반 높이
    for f in ("knee", "elbow", "upperarm", "hip_floor"):
        print(f"  {f:9s} 게이트통과·충족 {q(dd[f][dd.ok], (.02,.1,.5,.9,.98))} | 위반 {q(dd[f][~dd.ok], (.02,.1,.5,.9,.98))}")
    # 진입/이탈(무릎 바닥) 프레임의 골반 높이 vs 플랭크 프레임
    rest = d[(d.chain_vis >= .5) & (d.knee < 120)]
    print(f"  무릎<120 프레임 {len(rest)} hip_floor {q(rest.hip_floor,(.1,.5,.9))} elbow {q(rest.elbow,(.1,.5,.9))}")
    # 고개
    print(f"  ratio 실패 사유: base 통과 중 ratio<8 {(~(d.ratio>=8))[base[d.index]].mean():.3f}")
