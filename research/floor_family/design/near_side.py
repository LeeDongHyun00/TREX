# -*- coding: utf-8 -*-
"""설계용: AIHub 바닥 3종목 MP(C·E 전 클립)에서 '가까운 쪽 한 사슬' 피처 → 반복(피크) 단위 분포 → 판별 띠 후보의 정상 기각률·위반 검출률.
저장소는 읽기만. 출력은 이 폴더."""
import glob
from pathlib import Path
import numpy as np, pandas as pd
import pyarrow.dataset as ds
from scipy.signal import find_peaks

S = Path(__file__).resolve().parent
A = S.parent / "aihub_local_agent"
OUT = Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
EX = ["플랭크", "크런치", "라잉 레그 레이즈"]

def elev(v, g):
    L = np.maximum(np.hypot(g[:, 0], g[:, 1]), 1e-6); gx, gy = g[:, 0] / L, g[:, 1] / L
    nx, ny = -gy, gx; fl = ny > 0; nx = np.where(fl, -nx, nx); ny = np.where(fl, -ny, ny)
    return np.degrees(np.arctan2(v[:, 0] * nx + v[:, 1] * ny, v[:, 0] * gx + v[:, 1] * gy))

def devn(p, a, b, scale):
    u = b - a; L = np.maximum(np.hypot(u[:, 0], u[:, 1]), 1e-6); ux, uy = u[:, 0] / L, u[:, 1] / L
    nx, ny = -uy, ux; fl = ny > 0; nx = np.where(fl, -nx, nx); ny = np.where(fl, -ny, ny)
    return ((p[:, 0] - a[:, 0]) * nx + (p[:, 1] - a[:, 1]) * ny) / scale

def ang(a, b, c):
    u, w = a - b, c - b
    nu = np.maximum(np.hypot(u[:, 0], u[:, 1]) * np.hypot(w[:, 0], w[:, 1]), 1e-6)
    return np.degrees(np.arccos(np.clip((u * w).sum(1) / nu, -1, 1)))

def build():
    clips = pd.read_parquet(OUT / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key"])
    clips = clips[clips.exercise.isin(EX)]
    k = ds.dataset(OUT / "kp2d.parquet").to_table(columns=["clip_id", "frame_idx", "view_letter", "img_key"],
        filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
    lm = pd.concat([pd.read_parquet(f) for f in glob.glob(str(A / "mp_full" / "landmarks_*.parquet"))], ignore_index=True)
    m = k.merge(lm, on="img_key").merge(clips, on="clip_id").sort_values(["clip_id", "view_letter", "frame_idx"]).reset_index(drop=True)
    m = m[m.detected].reset_index(drop=True)
    P = lambda i: np.c_[m[f"l{i}_x"].to_numpy(float), m[f"l{i}_y"].to_numpy(float)]
    V = lambda i: m[f"l{i}_v"].to_numpy(float)
    # 가까운 쪽 = (어깨·골반·무릎·발목) 최소 가시성이 큰 쪽, 클립·뷰 단위로 고정(세트 잠금 모사)
    vL = np.min(np.c_[V(11), V(23), V(25), V(27)], 1); vR = np.min(np.c_[V(12), V(24), V(26), V(28)], 1)
    m["vL"], m["vR"] = vL, vR
    side = m.groupby(["clip_id", "view_letter"])[["vL", "vR"]].transform("median")
    left = (side.vL >= side.vR).to_numpy()
    def pick(l, r): return np.where(left[:, None], P(l), P(r))
    def pickv(l, r): return np.where(left, V(l), V(r))
    ear, sh, el, wr, hp, kn, an, ft = pick(7, 8), pick(11, 12), pick(13, 14), pick(15, 16), pick(23, 24), pick(25, 26), pick(27, 28), pick(31, 32)
    nose = P(0)
    near_min = np.where(left, vL, vR)
    torso = np.maximum(np.hypot(*(sh - hp).T), 1e-6)
    F = pd.DataFrame({"clip_id": m.clip_id, "view": m.view_letter, "frame_idx": m.frame_idx, "exercise": m.exercise,
                      "performer": m.performer, "type_key": m.type_key, "near_min": near_min,
                      "v_ear": pickv(7, 8), "v_nose": V(0), "v_wr": pickv(15, 16), "v_el": pickv(13, 14),
                      "sw": np.hypot(*(P(11) - P(12)).T), "w": m.w, "h": m.h})
    g_cr = hp - an                       # 크런치 지면 = 발목→골반(둘 다 바닥)
    F["cr_trunk"] = elev(sh - hp, g_cr)                    # 몸통 현 들림(°)
    F["cr_head_trunk"] = elev(ear - sh, sh - hp)           # 목 굽힘: 귀가 몸통 연장선에서 든 각(°)
    F["cr_nose_trunk"] = elev(nose - sh, sh - hp)
    F["cr_ear_h"] = devn(ear, an, hp, torso)               # 귀 높이 ÷ 몸통 (접지선 = 발목→골반, 프레임마다)
    F["cr_sh_h"] = devn(sh, an, hp, torso)
    F["cr_knee"] = ang(hp, kn, an)
    F["cr_hip_lift"] = devn(hp, an, an + np.c_[np.sign(hp[:, 0]-an[:, 0]), np.zeros(len(hp))], torso)  # 골반 높이(화면 수평 기준, 발목 대비)
    F["cr_wr_ear"] = np.hypot(*(wr - ear).T) / torso
    g_lr = hp - sh                       # 레그 레이즈 지면 = 어깨→골반(누운 몸통)
    F["lr_thigh"] = elev(kn - hp, g_lr)                    # 허벅지 들림(°, 0 = 몸통 연장선)
    F["lr_leg"] = elev(an - hp, g_lr)
    F["lr_knee"] = ang(hp, kn, an)
    F["lr_head"] = elev(ear - sh, sh - hp)                 # 머리 들림(°)
    hor = np.c_[np.sign(sh[:, 0] - hp[:, 0]), np.zeros(len(sh))]
    F["lr_trunk_hor"] = elev(sh - hp, hor)                 # 몸통의 화면 수평 대비 들림(V-up 배제용)
    F["lr_ankle_h"] = devn(an, sh, hp, torso)              # 발목 높이 ÷ 몸통 (지면 = 어깨→골반 선)
    F["pl_hip"] = devn(hp, sh, an, np.hypot(*(an - sh).T))
    F["pl_knee"] = ang(hp, kn, an)
    F["pl_elbow"] = ang(sh, el, wr)
    F["pl_ratio"] = np.hypot(*(an - sh).T) / np.maximum(F.sw.to_numpy(), 1)
    F["pl_horiz"] = np.abs(an[:, 0] - sh[:, 0]) / np.maximum(np.hypot(*(an - sh).T), 1e-6)
    F["pl_upperarm"] = elev(sh - el, an - el)             # 상완과 팔꿈치→발목 선 사이 각
    lab = pd.read_parquet(OUT / "conditions.parquet").pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
    F = F.join(lab, on="clip_id")
    F.to_parquet(S / "near_frames.parquet", index=False)
    return F

if __name__ == "__main__":
    F = build()
    print(F.shape)
    print(F.groupby(["exercise", "view"]).near_min.describe().round(2))
