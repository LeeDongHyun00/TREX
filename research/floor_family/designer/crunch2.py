# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
F = pd.read_parquet("near_frames.parquet")
c = F[F.exercise == "크런치"].copy()
S = "견갑골이 지면으로부터 충분히 올라옴"
for v in "C":
    d = c[(c.view == v) & (c.chain_vis >= .5)].sort_values(["clip_id", "frame_idx"])
    reps = []
    for cid, g in d.groupby("clip_id"):
        x = g.cr_trunk.to_numpy()
        if len(x) < 8: continue
        rng = x.max() - x.min(); rest = np.quantile(x, .1)
        pk, pr = find_peaks(np.r_[x.min(), x, x.min()], prominence=max(3.0, .4 * rng))
        for i in pk - 1:
            if 0 <= i < len(x):
                reps.append(dict(clip=cid, ok=bool(g[S].iloc[0]), t=g.type_key.iloc[0], perf=g.performer.iloc[0], lift=x[i] - rest, peak=x[i]))
    R = pd.DataFrame(reps)
    n = R.groupby("clip").size()
    print("클립당 회 분포", n.value_counts().sort_index().to_dict())
    for th in (3, 4, 5, 6, 8, 10):
        print(f" θ={th}: 충족 회 기각 {(R.lift[R.ok] < th).mean():.3f} 489 {(R.lift[R.t=='489'] < th).mean():.3f} | 고개만 회 기각 {(R.lift[~R.ok] < th).mean():.3f}")
    print(" 충족 회 들림 p2/p5/p10/p50", np.quantile(R.lift[R.ok], [.02, .05, .1, .5]).round(1).tolist(), "n", int(R.ok.sum()), "| 위반", np.quantile(R.lift[~R.ok], [.1, .5, .9]).round(1).tolist(), "n", int((~R.ok).sum()))
    for th in (45, 60, 75, 90):
        print(f" 정점 절대각 >{th}: 충족 회 {(R.peak[R.ok] > th).mean():.3f} 489 {(R.peak[R.t=='489'] > th).mean():.3f} 위반 {(R.peak[~R.ok] > th).mean():.3f}; 수행자 {R[R.peak>th].perf.nunique()}")
    print(" 정점>45 수행자별 회수", R[R.peak > 45].groupby("perf").size().to_dict())
