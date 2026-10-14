# -*- coding: utf-8 -*-
"""설계자 측정: AIHub 바닥 3종목 MP 전 클립(C·E)에서 '가까운 쪽 한 사슬' 피처를 만들고 프레임 표를 저장한다. 저장소는 읽기만."""
import glob
from pathlib import Path
import numpy as np, pandas as pd
import pyarrow.dataset as ds

OUT = Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
S = Path(__file__).resolve().parent
A = S.parent / "aihub_local_agent"
EX = ["플랭크", "크런치", "라잉 레그 레이즈"]

clips = pd.read_parquet(OUT / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key"])
clips = clips[clips.exercise.isin(EX)]
k = ds.dataset(OUT / "kp2d.parquet").to_table(columns=["clip_id", "frame_idx", "view_letter", "img_key"],
        filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
lm = pd.concat([pd.read_parquet(f) for f in glob.glob(str(A / "mp_full" / "landmarks_*.parquet"))], ignore_index=True)
m = k.merge(lm, on="img_key").merge(clips, on="clip_id").sort_values(["clip_id", "view_letter", "frame_idx"]).reset_index(drop=True)
m = m[m.detected].reset_index(drop=True)

def P(i):
    return np.stack([m[f"l{i}_x"].to_numpy(float), m[f"l{i}_y"].to_numpy(float)], 1)
def V(i):
    return np.minimum(m[f"l{i}_v"].to_numpy(float), m[f"l{i}_p"].to_numpy(float))

L = dict(ear=7, sh=11, el=13, wr=15, hip=23, kn=25, an=27, heel=29, toe=31)
R = dict(ear=8, sh=12, el=14, wr=16, hip=24, kn=26, an=28, heel=30, toe=32)
vL = np.min(np.stack([V(L[j]) for j in ("sh", "hip", "kn", "an")]), 0)
vR = np.min(np.stack([V(R[j]) for j in ("sh", "hip", "kn", "an")]), 0)
useL = vL >= vR
near = {j: np.where(useL[:, None], P(L[j]), P(R[j])) for j in L}
nvis = {j: np.where(useL, V(L[j]), V(R[j])) for j in L}
far_sh = np.where(useL[:, None], P(12), P(11))
nose = P(0)
chain_vis = np.where(useL, vL, vR)

def ang(a, b, c):
    u, w = a - b, c - b
    nu = np.maximum(np.hypot(u[:, 0], u[:, 1]) * np.hypot(w[:, 0], w[:, 1]), 1e-9)
    return np.degrees(np.arccos(np.clip((u * w).sum(1) / nu, -1, 1)))

def dev_up(p, a, b):  # 앱 FloorFeatureExtractor.devUp: 선 a→b, 화면 위 = +, 선 길이 정규화
    u = b - a; Ln = np.maximum(np.hypot(u[:, 0], u[:, 1]), 1e-9)
    ux, uy = u[:, 0] / Ln, u[:, 1] / Ln
    nx, ny = -uy, ux
    flip = ny > 0
    nx = np.where(flip, -nx, nx); ny = np.where(flip, -ny, ny)
    return ((p[:, 0] - a[:, 0]) * nx + (p[:, 1] - a[:, 1]) * ny) / Ln

def elev(v, g):  # 방향 v 가 기준선 g(방향) 대비 화면 위로 든 각(°), 부호 = 위 +
    Lg = np.maximum(np.hypot(g[:, 0], g[:, 1]), 1e-9)
    gx, gy = g[:, 0] / Lg, g[:, 1] / Lg
    nx, ny = -gy, gx
    flip = ny > 0
    nx = np.where(flip, -nx, nx); ny = np.where(flip, -ny, ny)
    return np.degrees(np.arctan2(v[:, 0] * nx + v[:, 1] * ny, v[:, 0] * gx + v[:, 1] * gy))

sh, hp, kn, an, el, wr, ear = (near[j] for j in ("sh", "hip", "kn", "an", "el", "wr", "ear"))
torso = np.hypot(*(sh - hp).T)
length = np.hypot(*(an - sh).T)
shw = np.maximum(np.hypot(*(sh - far_sh).T), 1.0)
W = m.w.to_numpy(float); H = m.h.to_numpy(float)
horiz = np.abs(an[:, 0] - sh[:, 0]) / np.maximum(length, 1e-9)
F = pd.DataFrame(dict(clip_id=m.clip_id, view=m.view_letter, frame_idx=m.frame_idx, exercise=m.exercise, performer=m.performer,
                      type_key=m.type_key, chain_vis=chain_vis, side=np.where(useL, "L", "R")))
F["torso_px"] = torso
F["ratio"] = length / shw
F["horiz"] = horiz
F["len_ok"] = (torso >= 20) & (length >= torso * 1.6) & (length >= np.minimum(W, H) * .25)
F["knee"] = ang(hp, kn, an)
F["elbow"] = ang(sh, el, wr)
F["hip_off"] = dev_up(hp, sh, an)                         # = plank_hip_offset (가까운 쪽)
F["hip_floor"] = dev_up(hp, el, an) * np.hypot(*(an - el).T) / np.maximum(torso, 1e-9)  # 팔꿈치–발목 접지선 위 골반 높이 ÷ 몸통
F["upperarm"] = ang(sh, el, an)                           # 상완–(팔꿈치→발목) 각, 90° = 어깨가 팔꿈치 바로 위
# 크런치: 지면 = 발목→골반 선(무릎 굽힘 + 발·골반 바닥)
g = hp - an
F["cr_trunk"] = elev(sh - hp, g)                          # 몸통 현(골반→어깨) 들림각
F["cr_ear"] = elev(ear - hp, g)
F["cr_neck"] = 180 - ang(ear, sh, hp)
# 레그 레이즈: 지면 = 어깨→골반 선(몸통이 바닥)
gt = hp - sh
F["lr_thigh"] = elev(kn - hp, gt)                         # 허벅지 들림(몸통 연장선 대비)
F["lr_leg"] = elev(an - hp, gt)                           # 다리(골반→발목) 들림
F["lr_hip_ang"] = ang(sh, hp, kn)                         # 가까운 쪽 hip_ang
F["lr_head_lift"] = elev(ear - sh, sh - hp)               # 귀가 몸통 연장선에서 든 각(+ = 화면 위)
F["head_trunk"] = ang(nose, ear, hp)
F["vis_ear"] = nvis["ear"]; F["vis_an"] = nvis["an"]; F["vis_kn"] = nvis["kn"]; F["vis_el"] = nvis["el"]; F["vis_wr"] = nvis["wr"]
# 양쪽 요구(앱 중점 피처) 생존 여부 비교용
F["both_knee"] = (V(25) >= .35) & (V(26) >= .35)
F["both_ankle"] = (V(27) >= .35) & (V(28) >= .35)
F["both_ear"] = (V(7) >= .35) & (V(8) >= .35) & (V(0) >= .35)
lab = pd.read_parquet(OUT / "conditions.parquet").pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
F = F.join(lab, on="clip_id")
F.to_parquet(S / "near_frames.parquet", index=False)
print(F.groupby(["exercise", "view"]).size())
