# -*- coding: utf-8 -*-
"""AIHub 바닥 3종목 프레임 피처 정의(GT 2D·MP 공용, 탐색용). 저장소는 읽기만 한다."""
from pathlib import Path
import numpy as np
from scipy.signal import find_peaks

OUT = Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
EX = ["플랭크", "크런치", "라잉 레그 레이즈"]
J = ["Nose", "LEar", "REar", "LShoulder", "RShoulder", "LElbow", "RElbow", "LWrist", "RWrist",
     "LHip", "RHip", "LKnee", "RKnee", "LAnkle", "RAnkle", "LFoot", "RFoot", "Waist", "Back", "Neck"]
MAP = {"Nose": 0, "LEar": 7, "REar": 8, "LShoulder": 11, "RShoulder": 12, "LElbow": 13, "RElbow": 14, "LWrist": 15, "RWrist": 16,
       "LHip": 23, "RHip": 24, "LKnee": 25, "RKnee": 26, "LAnkle": 27, "RAnkle": 28, "LFoot": 31, "RFoot": 32}


def dev_up(p, a, b):
    u = b - a
    L = np.maximum(np.hypot(u[:, 0], u[:, 1]), 1e-6)
    ux, uy = u[:, 0] / L, u[:, 1] / L
    nx, ny = -uy, ux
    flip = ny > 0
    nx = np.where(flip, -nx, nx); ny = np.where(flip, -ny, ny)
    return ((p[:, 0] - a[:, 0]) * nx + (p[:, 1] - a[:, 1]) * ny) / L


def ang(a, b, c):
    u, w = a - b, c - b
    nu = np.maximum(np.hypot(u[:, 0], u[:, 1]) * np.hypot(w[:, 0], w[:, 1]), 1e-6)
    return np.degrees(np.arccos(np.clip((u * w).sum(1) / nu, -1, 1)))


def signed_elev(v, g):
    L = np.maximum(np.hypot(g[:, 0], g[:, 1]), 1e-6)
    gx, gy = g[:, 0] / L, g[:, 1] / L
    nx, ny = -gy, gx
    flip = ny > 0
    nx = np.where(flip, -nx, nx); ny = np.where(flip, -ny, ny)
    return np.degrees(np.arctan2(v[:, 0] * nx + v[:, 1] * ny, v[:, 0] * gx + v[:, 1] * gy))


def features(d):
    P = {j: d[[f"{j}_x", f"{j}_y"]].to_numpy(float) for j in J}
    mid = lambda a, b: (P[a] + P[b]) / 2
    sh, hp, kn, an = mid("LShoulder", "RShoulder"), mid("LHip", "RHip"), mid("LKnee", "RKnee"), mid("LAnkle", "RAnkle")
    el, ear, nose = mid("LElbow", "RElbow"), mid("LEar", "REar"), P["Nose"]
    torso = np.maximum(np.hypot(*(sh - hp).T), 1e-6)
    F = {}
    F["pl_hip_off"] = dev_up(hp, sh, an)
    F["pl_trunk_ankle"] = ang(sh, hp, an)
    g = hp - an                                                       # 크런치 지면 = 골반↔발목
    F["cr_trunk_lift"] = signed_elev(sh - hp, g)
    F["cr_head_lift"] = signed_elev(ear - hp, g)
    F["cr_sh_ground"] = dev_up(sh, an, hp) * np.hypot(*(hp - an).T) / torso
    F["cr_ear_ground"] = dev_up(ear, an, hp) * np.hypot(*(hp - an).T) / torso
    F["cr_neck_flex"] = 180 - ang(ear, sh, hp)
    gt = hp - sh                                                      # 레그 레이즈 지면 = 어깨↔골반
    F["lr_leg_elev"] = signed_elev(an - hp, gt)
    F["lr_hip_ang"] = ang(sh, hp, kn)
    F["lr_knee_ang"] = ang(hp, kn, an)
    F["lr_ankle_h"] = dev_up(an, sh, hp) * np.hypot(*(hp - sh).T) / torso
    F["lr_head_trunk"] = ang(nose, ear, hp)
    F["lr_head_lift"] = signed_elev(ear - sh, sh - hp)
    return F


def n_cycles(x):
    x = np.asarray(x, float); x = x[np.isfinite(x)]
    if len(x) < 4:
        return 0
    r = x.max() - x.min()
    if r <= 0:
        return 0
    pk, _ = find_peaks(np.r_[x.min(), x, x.min()], prominence=0.4 * r)
    return len(pk)
