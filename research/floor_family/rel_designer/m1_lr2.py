# -*- coding: utf-8 -*-
import warnings; warnings.filterwarnings("ignore")
import glob
import numpy as np, pandas as pd
from pathlib import Path
f = pd.read_parquet("m1_frames.parquet")
L = f[(f.exercise == "라잉 레그 레이즈") & (f.view == "C") & (f.vis_sh_hp >= .5) & (f.vis_kn >= .5)]
KN = "허벅지와 종아리 각도 고정"
rows = []
for cid, s in L.groupby("clip_id"):
    if len(s) < 8: continue
    top = s[s.lr_thigh >= np.quantile(s.lr_thigh, .8)]
    kt = top[top.vis_an >= .5].lr_knee
    rows.append(dict(kn=bool(s[KN].iloc[0]), tk=s.type_key.iloc[0], kt=kt.median() if len(kt) else np.nan, top=s.lr_thigh.max(), bot=np.quantile(s.lr_thigh, .1),
                     trunk=s.lr_trunk_h.max() - np.quantile(s.lr_trunk_h, .1)))
g = pd.DataFrame(rows)
for t in (90, 100, 105, 110):
    print(f"상단 무릎 < {t}: 무릎조건 충족 기각 {(g.kt[g.kn] < t).mean():.3f} (n={g.kn.sum()}) · 473 {(g.kt[g.tk=='473'] < t).mean():.3f} · 위반 검출 {(g.kt[~g.kn] < t).mean():.3f}")
for t in (30, 45, 60):
    print(f"허벅지 상단 < {t}°: 무릎충족 {(g.top[g.kn] < t).mean():.3f} · 473 {(g.top[g.tk=='473'] < t).mean():.3f}")
print("하단(p10) 허벅지 분포 p10/50/90", np.round(np.quantile(g.bot, [.1, .5, .9]), 1).tolist())
print("몸통 들림(최대−p10) p50/p90/p99", np.round(np.quantile(g.trunk, [.5, .9, .99]), 1).tolist(), "  >20°:", round((g.trunk > 20).mean(), 3))
