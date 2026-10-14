# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from sklearn.metrics import roc_auc_score
f = pd.read_parquet("m1_frames.parquet")
L = f[f.exercise == "라잉 레그 레이즈"].copy()
q = lambda s, qs=(.1,.5,.9): np.round(np.quantile(np.asarray(s,float)[np.isfinite(np.asarray(s,float))], qs), 1).tolist()
KN = "허벅지와 종아리 각도 고정"; HD = "고개 숙임 여부"
for v in "CE":
    d = L[L.view == v]
    thighok = (d.vis_sh_hp >= .5) & (d.vis_kn >= .5)
    kneeok = thighok & (d.vis_an >= .5)
    print(f"== 레그레이즈 {v}: 프레임 {len(d)} 보이는쪽 허벅지(어깨·골반·무릎≥.5) {thighok.mean():.3f} +발목 {kneeok.mean():.3f} | 먼무릎≥.35 {(d.vis_far_kn>=.35).mean():.3f} 먼발목≥.35 {(d.vis_far_an>=.35).mean():.3f}")
    dd = d[thighok].copy()
    rows = []
    for cid, s in dd.groupby("clip_id"):
        s = s.sort_values("frame_idx")
        if len(s) < 8: continue
        th = s.lr_thigh.to_numpy()
        top = s[s.lr_thigh >= np.quantile(th, .8)]
        kt = top[top.vis_an >= .5].lr_knee
        rows.append(dict(clip=cid, mx=th.max(), p10=np.quantile(th,.1), amp=th.max()-np.quantile(th,.1), knee_top=kt.median() if len(kt) else np.nan,
                         knee_med=s[s.vis_an>=.5].lr_knee.median(), trunk_max=s.lr_trunk_h.max()-np.quantile(s.lr_trunk_h,.1), headv=s.lr_head.median(), ht=s.lr_head_trunk.median(),
                         kn=bool(s[KN].iloc[0]), hd=bool(s[HD].iloc[0]), tk=s.type_key.iloc[0]))
    g = pd.DataFrame(rows)
    n473 = g.tk == "473"
    print("  허벅지 들림 max: 무릎조건 충족", q(g.mx[g.kn]), "위반(무릎90)", q(g.mx[~g.kn]), "473", q(g.mx[n473]))
    print("  허벅지 p10(하단):", q(g.p10), " 진폭 max-p10 473", q(g.amp[n473]), "전체", q(g.amp))
    for th in (25, 30, 35, 40, 45):
        print(f"   진폭 ≥{th}°: 473 통과 {(g.amp[n473]>=th).mean():.3f} · 무릎충족 {(g.amp[g.kn]>=th).mean():.3f} · 전체 {(g.amp>=th).mean():.3f}")
    print("  상단 무릎각(보이는 쪽): 충족", q(g.knee_top[g.kn]), "위반", q(g.knee_top[~g.kn]), "473", q(g.knee_top[n473]), "AUC(위반=작음)", round(roc_auc_score(~g.kn[g.knee_top.notna()], -g.knee_top.dropna()),3))
    for t in (110, 120, 130, 140):
        print(f"   상단 무릎 < {t}: 충족 기각 {(g.knee_top[g.kn]<t).mean():.3f} · 473 기각 {(g.knee_top[n473]<t).mean():.3f} · 위반 기각 {(g.knee_top[~g.kn]<t).mean():.3f}")
    print("  몸통 들림 진폭(V-up?) p50/p90/max", q(g.trunk_max,(.5,.9,1.0)))
    print("  머리 들림 중앙(+굽힘): 고개조건 충족", q(g.headv[g.hd]), "위반(젖힘)", q(g.headv[~g.hd]), "AUC(위반=작음)", round(roc_auc_score(~g.hd, -g.headv),3), "| head_trunk 충족", q(g.ht[g.hd]), "위반", q(g.ht[~g.hd]), "<73.08 정상오탐", round((g.ht[g.hd]<73.08).mean(),3), "473", round((g.ht[n473]<73.08).mean(),3))
