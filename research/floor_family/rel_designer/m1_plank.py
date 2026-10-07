# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from sklearn.metrics import roc_auc_score
f = pd.read_parquet("m1_frames.parquet")
p = f[f.exercise == "플랭크"].copy()
vis = (p.vis_chain >= .5)
basic = vis & (p.pl_len_torso >= 1.6) & (p.pl_len_short >= .25) & (p.pl_oth_sh_vis >= .2)
knee = p.pl_knee >= 145
q = lambda s, qs=(.1,.5,.9): np.round(np.quantile(s.dropna(), qs), 3).tolist()
for v in "CE":
    d = p[p.view == v]; b = basic[p.view == v]; k = knee[p.view == v]
    print(f"== 뷰 {v} 프레임 {len(d)} | 사슬 vis≥.5 {vis[p.view==v].mean():.3f} | 기본(길이·어깨 vis) {b.mean():.3f} | +무릎≥145 {(b&k).mean():.3f}")
    dd = d[b & k]
    print("  ratio p10/50/90", q(dd.pl_ratio), "통과(≥8)", round((dd.pl_ratio >= 8).mean(), 3))
    print("  horiz p10/50/90", q(dd.pl_horiz), "통과(≥.7)", round((dd.pl_horiz >= .7).mean(), 3))
    print("  어깨분리 축방향/길이 p10/50/90", q(dd.pl_sep_along), " 수직성분/길이", q(dd.pl_sep_perp))
    cur = (dd.pl_ratio >= 8) & (dd.pl_horiz >= .7)
    for t in (.06, .08, .10):
        new = (dd.pl_sep_along <= t) & (dd.pl_horiz >= .7)
        print(f"  현행 게이트 통과 {cur.mean():.3f} | 제안(축방향 ≤{t}) 통과 {new.mean():.3f}")
# 뷰별 hip offset (보이는 쪽, 무릎≥145, 기본 관측) 충족/위반 분포
cc = "몸통과 엉덩이의 정렬 유지"
for v in "CE":
    d = p[(p.view == v) & basic & knee]
    ok = d[cc].astype(bool)
    print(v, "pl_hip 충족 p2/10/50/90/98", np.round(np.quantile(d.pl_hip[ok], [.02,.1,.5,.9,.98]),3).tolist(), "위반", np.round(np.quantile(d.pl_hip[~ok], [.02,.1,.5,.9,.98]),3).tolist())
    n553 = d[d.type_key == "553"].pl_hip
    print(v, "  553 p2/50/98", np.round(np.quantile(n553, [.02,.5,.98]),3).tolist(), "n", len(n553))
    # 클립 중앙값 AUC
    cl = d.groupby("clip_id").agg(med=("pl_hip","median"), ok=(cc,"first"), perf=("performer","first"), n=("pl_hip","size"))
    cl = cl[cl.n >= 3]
    print(v, "  클립중앙값 AUC(위반=큼)", round(roc_auc_score(~cl.ok.astype(bool), cl.med),3), "n", len(cl))
    # 프레임간 흔들림(같은 클립 인접 프레임 차) — 정적 홀드 잡음 상한
    d2 = d.sort_values(["clip_id","frame_idx"])
    dif = d2.groupby("clip_id").pl_hip.diff().abs()
    print(v, "  인접 프레임 |Δhip| p50/p90", np.round(np.quantile(dif.dropna(), [.5,.9]),4).tolist())
    # 지지 팔꿈치각 (전완 vs 팔 편)
    print(v, "  팔꿈치각 p10/50/90", q(d[d.vis_el>=.5][d.vis_wr>=.5].pl_elbow), " 골반-지지선(팔꿈치→발목) p10/50/90", q(d.pl_hip_floor))
