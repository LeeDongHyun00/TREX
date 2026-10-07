# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from sklearn.metrics import roc_auc_score
f = pd.read_parquet("m1_frames.parquet")
c = f[f.exercise == "크런치"].copy()
cond = "견갑골이 지면으로부터 충분히 올라옴"
q = lambda s, qs=(.1,.5,.9): np.round(np.quantile(np.asarray(s,float)[np.isfinite(np.asarray(s,float))], qs), 2).tolist()
for v in "CE":
    d = c[c.view == v]
    near = (d.vis_sh_hp >= .5) & (d.vis_an >= .5)
    mid = (d.vis_far_an >= .35) & (d.vis_an >= .35)  # 대략: 앱 head_ground 는 양 발목·코·양귀 ≥.35
    print(f"== 크런치 {v}: 프레임 {len(d)}, 보이는쪽(어깨·골반·발목 ≥.5) {near.mean():.3f}, 먼 발목 vis≥.35 {(d.vis_far_an>=.35).mean():.3f}, 귀 vis≥.5 {(d.vis_ear>=.5).mean():.3f}")
    dd = d[near]
    g = dd.groupby("clip_id").agg(mx=("cr_chord","max"), p90=("cr_chord", lambda s: np.quantile(s,.9)), p10=("cr_chord", lambda s: np.quantile(s,.1)),
                                   mn=("cr_chord","min"), hmx=("cr_chord_h","max"), hp10=("cr_chord_h", lambda s: np.quantile(s,.1)),
                                   nk=("cr_neck", lambda s: np.quantile(s,.9)-np.quantile(s,.1)), knee=("cr_knee","median"),
                                   hiplift=("cr_hip_lift","max"), ok=(cond,"first"), tk=("type_key","first"), perf=("performer","first"), n=("cr_chord","size"))
    g = g[g.n >= 8]
    ok = g.ok.astype(bool); n489 = g.tk == "489"
    print("  클립 현각 max: 충족", q(g.mx[ok]), "위반(고개만)", q(g.mx[~ok]), "전조건정상489", q(g.mx[n489]), " AUC", round(roc_auc_score(ok, g.mx),3))
    print("  클립 현각 p10(휴식 수준): 충족", q(g.p10[ok]), " 진폭 max-p10 충족", q((g.mx-g.p10)[ok]), "위반", q((g.mx-g.p10)[~ok]))
    print("  화면가로 기준 현각 max 충족", q(g.hmx[ok]), "p10 충족", q(g.hp10[ok]))
    print("  목 굽힘 진폭(p90-p10) 충족", q(g.nk[ok]), "위반", q(g.nk[~ok]))
    print("  무릎각 중앙", q(g.knee), " 골반 들림 max(몸통)", q(g.hiplift))
    for th in (5, 8, 10, 12, 15):
        print(f"   현 진폭(max-p10) ≥{th}°: 충족 통과 {((g.mx-g.p10)[ok]>=th).mean():.3f} · 489 통과 {((g.mx-g.p10)[n489]>=th).mean():.3f} · 고개만 통과 {((g.mx-g.p10)[~ok]>=th).mean():.3f}")
    print("  현각 max > 45° 인 클립 비율(윗몸 배제 오탐):", round((g.mx > 45).mean(),3), " >35:", round((g.mx>35).mean(),3), "최대", round(g.mx.max(),1))
