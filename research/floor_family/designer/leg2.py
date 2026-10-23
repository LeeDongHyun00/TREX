# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
F = pd.read_parquet("near_frames.parquet")
c = F[F.exercise == "라잉 레그 레이즈"].copy()
K = "허벅지와 종아리 각도 고정"; HD = "고개 숙임 여부"
d = c[(c.view == "C") & (c.chain_vis >= .5)].sort_values(["clip_id", "frame_idx"])
reps = []
for cid, g in d.groupby("clip_id"):
    x = g.lr_thigh.to_numpy(); kn = g.knee.to_numpy(); va = g.vis_an.to_numpy()
    if len(x) < 8: continue
    rest = np.quantile(x, .1); rng = x.max() - x.min()
    up = x >= rest + 15
    # 회 창 = 연속 up 구간
    i = 0
    while i < len(x):
        if up[i]:
            j = i
            while j < len(x) and up[j]: j += 1
            w = slice(i, j)
            kk = kn[w][va[w] >= .5]
            if len(kk):
                reps.append(dict(perf=g.performer.iloc[0], t=g.type_key.iloc[0], ok=bool(g[K].iloc[0]), kmed=np.median(kk), kmin=kk.min(), n=j - i))
            i = j
        else:
            i += 1
R = pd.DataFrame(reps)
print("회 창", len(R), "충족", R.ok.sum())
for th in (90, 100, 110, 120):
    print(f" 창 중앙 무릎<{th}: 충족 {(R.kmed[R.ok] < th).mean():.3f} 473 {(R.kmed[R.t=='473'] < th).mean():.3f} 위반 {(R.kmed[~R.ok] < th).mean():.3f}")
print(" 충족 창 중앙 무릎 p1/p2/p5/p10", np.quantile(R.kmed[R.ok], [.01, .02, .05, .1]).round(1).tolist())
low = R[R.ok & (R.kmed < 100)]
print(" 충족인데 <100 인 회의 수행자", low.perf.value_counts().head(8).to_dict())
cl = d.groupby("clip_id").agg(hl=("lr_head_lift", "median"), ok=(HD, "first"), t=("type_key", "first"))
cl["ok"] = cl.ok.astype(bool)
for th in (30, 33, 35, 38, 40):
    print(f" 머리들림 클립 중앙 >{th}: 충족 {(cl.hl[cl.ok] > th).mean():.3f} 473 {(cl.hl[cl.t=='473'] > th).mean():.3f} 위반 검출 {(cl.hl[~cl.ok] > th).mean():.3f}")
# 2.4 s 창(8프레임 @300ms 를 AIHub 4프레임 이동창으로 근사) 평균 머리 들림의 최대 — 창 단위 오탐
def win_max(s, k=4):
    s = s.to_numpy();
    return max(np.mean(s[i:i + k]) for i in range(0, max(1, len(s) - k + 1)))
wm = d.groupby("clip_id").lr_head_lift.apply(win_max)
cl["wm"] = wm
for th in (35, 38, 40, 45):
    print(f" 창(4프레임) 평균 최대 >{th}: 충족 {(cl.wm[cl.ok] > th).mean():.3f} 473 {(cl.wm[cl.t=='473'] > th).mean():.3f} 위반 {(cl.wm[~cl.ok] > th).mean():.3f}")
