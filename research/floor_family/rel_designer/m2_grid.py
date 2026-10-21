# -*- coding: utf-8 -*-
"""300 ms 판정 격자 vs 100 ms(≈85 ms 추론) — 사이클 정점 진폭 손실과 정지 잡음."""
import warnings; warnings.filterwarnings("ignore")
import numpy as np, pandas as pd
exec(open("m2_count.py", encoding="utf-8").read().split("class FloorCycle")[0])
from m2_count4 import FloorCycle4
def chords_all(d, grid):
    sh, hp, kn, an, ear = near_side(d[d.in_set])
    if grid: d = d.assign(bin=d.t_ms // grid).groupby("bin").head(1)
    ch = []
    for r in d.itertuples(index=False):
        rr = r._asdict()
        if not np.isfinite(rr.get(f"x{sh}", np.nan)) or min(rr[f"v{sh}"], rr[f"v{hp}"], rr[f"v{an}"]) < .5: ch.append(np.nan); continue
        P = lambda i: np.array([rr[f"x{i}"] * rr["W"], rr[f"y{i}"] * rr["H"]])
        ch.append(selev(P(sh) - P(hp), P(hp) - P(an)))
    return d.assign(chord=ch)
loss = []; noise = []
for (w, k), d in df.groupby(["w", "set"]):
    d = d.sort_values("t_ms")
    fine = chords_all(d, None); coarse = chords_all(d, 300)
    fc = FloorCycle4(); 
    for r in coarse.itertuples(index=False): fc.on(r.t_ms, r.chord)
    for s0, s1, exc in fc.cycles:
        a = fine[(fine.t_ms >= s0 - 600) & (fine.t_ms <= s1)].chord; b = coarse[(coarse.t_ms >= s0 - 600) & (coarse.t_ms <= s1)].chord
        if a.notna().sum() >= 5: loss.append(dict(w=w, fine=a.max() - a.min(), coarse=b.max() - b.min()))
    # 정지 잡음: 세트 밖 여유에서 1 s 창 표준편차가 가장 작은 창들(누운 정지 추정, 현각 < 35)
    s = fine[~fine.in_set].chord.to_numpy()
    for i in range(0, len(s) - 10, 5):
        win = s[i:i + 10]
        if np.isfinite(win).all() and win.max() < 35 and win.max() - win.min() < 5: noise.append(dict(w=w, sd=win.std()))
L = pd.DataFrame(loss); L["rel"] = (L.fine - L.coarse) / L.fine
print("사이클 진폭 손실(300 vs 100 ms) 상대 p50/p90/max:", np.round(np.quantile(L.rel, [.5, .9, 1]), 3).tolist(), " 절대(°) p50/p90", np.round(np.quantile(L.fine - L.coarse, [.5, .9]), 2).tolist(), "n", len(L))
print("  w20(크런치형)만:", np.round(np.quantile(L[L.w == "w20"].rel, [.5, .9, 1]), 3).tolist(), "절대 p90", round(float(np.quantile((L.fine - L.coarse)[L.w == "w20"], .9)), 2))
N = pd.DataFrame(noise)
print("누운 정지 1 s 창 SD(°) p50/p90:", np.round(np.quantile(N.sd, [.5, .9]), 2).tolist(), "창 수", len(N), N.groupby("w").size().to_dict())
