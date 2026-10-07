# -*- coding: utf-8 -*-
import warnings; warnings.filterwarnings("ignore")
import numpy as np, pandas as pd
exec(open("m2_count.py", encoding="utf-8").read().split("rows = []")[0])
for (w, k), d in df.groupby(["w", "set"]):
    d = d.sort_values("t_ms"); sh, hp, kn, an, ear = near_side(d[d.in_set])
    d = d.assign(bin=d.t_ms // GRID).groupby("bin").head(1)
    ch = []
    for r in d.itertuples(index=False):
        rr = r._asdict()
        if not np.isfinite(rr.get(f"x{sh}", np.nan)) or min(rr[f"v{sh}"], rr[f"v{hp}"], rr[f"v{an}"]) < .5: ch.append(np.nan); continue
        P = lambda i: np.array([rr[f"x{i}"] * rr["W"], rr[f"y{i}"] * rr["H"]])
        ch.append(selev(P(sh) - P(hp), P(hp) - P(an)))
    d = d.assign(chord=ch)
    fc = FloorCycle(depart=5, lift=5)
    for r in d.itertuples(index=False): fc.on(r.t_ms, r.chord)
    s0, s1 = d.t_ms[d.in_set].min(), d.t_ms[d.in_set].max()
    outs = [(round((c[0]-s0)/1000,1), round((c[1]-s0)/1000,1), round(c[2])) for c in fc.cycles if not (s0 - 1500 <= c[1] <= s1 + 1500)]
    ins = [round((c[1]-s0)/1000,1) for c in fc.cycles if (s0 - 1500 <= c[1] <= s1 + 1500)]
    if w in ("w06","w18","w19","w20") and k == 0 or outs:
        pre = d[d.t_ms < s0].chord.round(0).tolist(); post = d[d.t_ms > s1].chord.round(0).tolist()
        print(w, k, "세트길이", round((s1-s0)/1000,1), "s | 밖 사이클(시작,끝,진폭)", outs, "| 안 사이클 끝", ins[:3], "...", ins[-2:])
        print("    앞 여유 현각", pre[-20:]); print("    뒤 여유 현각", post[:20])
