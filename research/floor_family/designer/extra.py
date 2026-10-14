# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
F = pd.read_parquet("near_frames.parquet")
# 크런치: 귀 들림 봉우리로 사이클을 잡고, 그 회의 몸통 들림을 판별 → 정상 기각률 / 고개만 기각률
c = F[(F.exercise == "크런치") & (F.view == "C") & (F.chain_vis >= .5)].sort_values(["clip_id", "frame_idx"])
S = "견갑골이 지면으로부터 충분히 올라옴"
rows = []
for cid, g in c.groupby("clip_id"):
    e = g.cr_ear.to_numpy(); x = g.cr_trunk.to_numpy()
    if len(e) < 8: continue
    er, xr = np.quantile(e, .1), np.quantile(x, .1)
    pk, _ = find_peaks(np.r_[e.min(), e, e.min()], prominence=max(6.0, .4 * (e.max() - e.min())))
    for i in pk - 1:
        if 0 <= i < len(e):
            lo, hi = max(0, i - 1), min(len(e), i + 2)
            rows.append(dict(ok=bool(g[S].iloc[0]), t=g.type_key.iloc[0], ear=e[i] - er, trunk=x[lo:hi].max() - xr))
R = pd.DataFrame(rows)
print("크런치 귀 봉우리 회", len(R), "충족", int(R.ok.sum()))
print(" 귀 들림 충족 p2/p5/p10/p50", np.quantile(R.ear[R.ok], [.02, .05, .1, .5]).round(1).tolist(), "| 고개만 p10/p50", np.quantile(R.ear[~R.ok], [.1, .5]).round(1).tolist())
for th in (3, 4, 5):
    print(f" 귀 봉우리 회 중 몸통<{th}°: 충족 {(R.trunk[R.ok] < th).mean():.3f} 489 {(R.trunk[R.t=='489'] < th).mean():.3f} | 고개만 {(R.trunk[~R.ok] < th).mean():.3f}")
# 레그 레이즈 진폭 30
l = F[(F.exercise == "라잉 레그 레이즈") & (F.view == "C") & (F.chain_vis >= .5)].sort_values(["clip_id", "frame_idx"])
amps = []
for cid, g in l.groupby("clip_id"):
    x = g.lr_thigh.to_numpy()
    if len(x) < 8: continue
    rest = np.quantile(x, .1)
    pk, _ = find_peaks(np.r_[x.min(), x, x.min()], prominence=max(10.0, .4 * (x.max() - x.min())))
    for i in pk - 1:
        if 0 <= i < len(x): amps.append((g.type_key.iloc[0], bool(g["허벅지와 종아리 각도 고정"].iloc[0]), x[i] - rest))
A = pd.DataFrame(amps, columns=["t", "ok", "amp"])
for th in (20, 25, 30, 35):
    print(f" 레그 진폭<{th}: 473 {(A.amp[A.t=='473'] < th).mean():.3f} 전체 {(A.amp < th).mean():.3f} (회 {len(A)})")
# 레그 레이즈 휴식(바닥) 허벅지 각 분포와 잡음
rest = l.groupby("clip_id").lr_thigh.apply(lambda s: s[s <= s.quantile(.25)])
print(" 레그 휴식 허벅지각 p10/p50/p90", np.quantile(rest, [.1, .5, .9]).round(1).tolist())
nz = l.groupby("clip_id").lr_thigh.apply(lambda s: s[s <= s.quantile(.3)].std())
print(" 레그 휴식 잡음 SD 중앙", round(float(nz.median()), 2))
# 플랭크 시간 게이트 E1~E4 (게이트 전 프레임 전체)
p = F[(F.exercise == "플랭크") & (F.view == "C")]
e = (p.chain_vis >= .5) & p.len_ok & (p.horiz >= .7) & (p.knee >= 145) & (p.elbow.between(35, 110) | (p.elbow >= 150)) & (p.hip_floor >= .10)
print(" 플랭크 C 프레임 시간게이트 통과", round(float(e.mean()), 3), "| 무릎만 빼고", round(float(((p.chain_vis >= .5) & p.len_ok & (p.horiz >= .7) & (p.knee >= 145)).mean()), 3))
print(" 무릎≥145 프레임 비율(사슬 통과 중)", round(float((p.knee[p.chain_vis >= .5] >= 145).mean()), 3))
