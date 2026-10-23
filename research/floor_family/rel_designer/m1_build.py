# -*- coding: utf-8 -*-
"""M1: AIHub 바닥 3종목 MP(C·E) 전 클립 — '보이는 쪽 한 사슬' 피처를 프레임마다 계산해 parquet 로 남긴다(설계 검증용, 저장소 읽기만)."""
import glob
from pathlib import Path
import numpy as np, pandas as pd
import pyarrow.dataset as ds

OUT = Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
S = Path(__file__).resolve().parent
LM = S.parent / "aihub_local_agent" / "mp_full"
EX = ["플랭크", "크런치", "라잉 레그 레이즈"]


def selev(v, g):
    """벡터 v 의 g 방향 대비 부호 각(°) — 법선은 화면 위(−y)가 + (gt_features.signed_elev 와 같다)."""
    L = np.maximum(np.hypot(g[..., 0], g[..., 1]), 1e-9)
    gx, gy = g[..., 0] / L, g[..., 1] / L
    nx, ny = -gy, gx
    flip = ny > 0
    nx = np.where(flip, -nx, nx); ny = np.where(flip, -ny, ny)
    return np.degrees(np.arctan2(v[..., 0] * nx + v[..., 1] * ny, v[..., 0] * gx + v[..., 1] * gy))


def ang(a, b, c):
    u, w = a - b, c - b
    nu = np.maximum(np.hypot(u[..., 0], u[..., 1]) * np.hypot(w[..., 0], w[..., 1]), 1e-9)
    return np.degrees(np.arccos(np.clip((u * w).sum(-1) / nu, -1, 1)))


def devup(p, a, b):
    u = b - a
    L = np.maximum(np.hypot(u[..., 0], u[..., 1]), 1e-9)
    ux, uy = u[..., 0] / L, u[..., 1] / L
    nx, ny = -uy, ux
    flip = ny > 0
    nx = np.where(flip, -nx, nx); ny = np.where(flip, -ny, ny)
    return ((p[..., 0] - a[..., 0]) * nx + (p[..., 1] - a[..., 1]) * ny) / L


def main():
    clips = pd.read_parquet(OUT / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key"])
    clips = clips[clips.exercise.isin(EX)]
    k = ds.dataset(OUT / "kp2d.parquet").to_table(columns=["clip_id", "frame_idx", "view_letter", "img_key"],
                                                  filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
    lm = pd.concat([pd.read_parquet(f) for f in glob.glob(str(LM / "landmarks_*.parquet"))], ignore_index=True)
    m = k.merge(lm, on="img_key").merge(clips, on="clip_id").sort_values(["clip_id", "view_letter", "frame_idx"]).reset_index(drop=True)
    m = m[m.detected].reset_index(drop=True)
    X = np.stack([m[f"l{i}_x"].to_numpy(float) for i in range(33)], 1)
    Y = np.stack([m[f"l{i}_y"].to_numpy(float) for i in range(33)], 1)
    V = np.stack([m[f"l{i}_v"].to_numpy(float) for i in range(33)], 1)
    P = np.stack([X, Y], -1)  # (n,33,2) px
    # 보이는 쪽: 어깨·골반·무릎·발목 최소 가시성이 큰 쪽(프레임별) — 클립 단위로 잠근 쪽도 따로
    chainL, chainR = [11, 23, 25, 27], [12, 24, 26, 28]
    mvL = V[:, chainL].min(1); mvR = V[:, chainR].min(1)
    m["mvL"], m["mvR"] = mvL, mvR
    lockR = m.groupby(["clip_id", "view_letter"])[["mvL", "mvR"]].transform("mean")
    right = (lockR.mvR > lockR.mvL).to_numpy()
    idx = lambda l, r: np.where(right, r, l)
    ear, sh, el, wr, hp, kn, an = (idx(7, 8), idx(11, 12), idx(13, 14), idx(15, 16), idx(23, 24), idx(25, 26), idx(27, 28))
    oth_sh = idx(12, 11); oth_hp = idx(24, 23); oth_kn = idx(26, 25); oth_an = idx(28, 27)
    n = len(m); r = np.arange(n)
    g = lambda j: P[r, j]
    vv = lambda j: V[r, j]
    E, SH, EL, WR, HP, KN, AN = g(ear), g(sh), g(el), g(wr), g(hp), g(kn), g(an)
    NO = P[:, 0]
    f = pd.DataFrame({"clip_id": m.clip_id, "view": m.view_letter, "frame_idx": m.frame_idx, "exercise": m.exercise,
                      "performer": m.performer, "type_key": m.type_key.astype(str), "right": right})
    f["vis_chain"] = np.minimum.reduce([vv(sh), vv(hp), vv(kn), vv(an)])
    f["vis_sh_hp"] = np.minimum(vv(sh), vv(hp))
    f["vis_far_kn"] = V[r, oth_kn]; f["vis_far_an"] = V[r, oth_an]
    f["vis_ear"] = vv(ear); f["vis_nose"] = V[:, 0]; f["vis_el"] = vv(el); f["vis_wr"] = vv(wr)
    f["vis_kn"] = vv(kn); f["vis_an"] = vv(an)
    torso = np.hypot(*(SH - HP).T)
    f["torso_px"] = torso
    horiz_head = np.where((SH[:, 0] - HP[:, 0])[:, None] >= 0, np.array([[1.0, 0.0]]), np.array([[-1.0, 0.0]]))  # 화면 가로, 머리 쪽
    # ---- 크런치
    f["cr_chord"] = selev(SH - HP, HP - AN)            # 어깨–골반 현의 들림(지면 = 발목→골반 연장)
    f["cr_chord_h"] = selev(SH - HP, horiz_head)       # 같은 현, 화면 가로 기준(바닥 수평 가정)
    f["cr_neck"] = selev(E - SH, SH - HP)              # 귀–어깨가 현 연장 대비 들린 각(목 굽힘)
    f["cr_knee"] = ang(HP, KN, AN)
    f["cr_hip_lift"] = devup(HP, AN, AN + horiz_head * 100) / np.maximum(torso, 1e-9)  # 골반이 발목 높이선 위(몸통 단위)
    # ---- 레그 레이즈
    f["lr_thigh"] = selev(KN - HP, HP - SH)            # 허벅지 들림(몸통 연장 대비) 0 = 누움, 90 = 수직
    f["lr_leg"] = selev(AN - HP, HP - SH)
    f["lr_knee"] = ang(HP, KN, AN)
    f["lr_trunk_h"] = selev(SH - HP, horiz_head)       # 몸통이 바닥에서 들리는가(V-up 배제)
    f["lr_head"] = selev(E - SH, SH - HP)              # 머리 들림(+ = 굽힘·턱 당김, − = 젖힘?)
    f["lr_head_trunk"] = ang(NO, E, HP)
    # ---- 플랭크
    L = np.hypot(*(AN - SH).T)
    f["pl_hip"] = devup(HP, SH, AN)
    f["pl_len_torso"] = L / np.maximum(torso, 1e-9)
    f["pl_len_short"] = L / np.minimum(m.w, m.h).to_numpy(float)
    OS = P[r, oth_sh]
    sep = OS - SH
    axis = (AN - SH) / np.maximum(L, 1e-9)[:, None]
    along = np.abs((sep * axis).sum(1)); perp = np.abs(sep[:, 0] * -axis[:, 1] + sep[:, 1] * axis[:, 0])
    f["pl_ratio"] = L / np.maximum(np.hypot(*sep.T), 1.0)
    f["pl_sep_along"] = along / np.maximum(L, 1e-9)
    f["pl_sep_perp"] = perp / np.maximum(L, 1e-9)
    f["pl_horiz"] = np.abs(AN[:, 0] - SH[:, 0]) / np.maximum(L, 1e-9)
    f["pl_knee"] = ang(HP, KN, AN)
    f["pl_elbow"] = ang(SH, EL, WR)
    f["pl_oth_sh_vis"] = V[r, oth_sh]
    f["pl_hip_floor"] = devup(HP, EL, AN)               # 골반이 팔꿈치–발목 선 위(지지선 대비)
    tax = (HP - SH) / np.maximum(torso, 1e-9)[:, None]
    OH = P[r, oth_hp]
    f["side_sh_along_t"] = np.abs((sep * tax).sum(1)) / np.maximum(torso, 1e-9)
    f["side_sh_perp_t"] = np.abs(sep[:, 0] * -tax[:, 1] + sep[:, 1] * tax[:, 0]) / np.maximum(torso, 1e-9)
    hsep = OH - HP
    f["side_hp_along_t"] = np.abs((hsep * tax).sum(1)) / np.maximum(torso, 1e-9)
    f["side_hp_perp_t"] = np.abs(hsep[:, 0] * -tax[:, 1] + hsep[:, 1] * tax[:, 0]) / np.maximum(torso, 1e-9)
    f["vis_far_sh"] = V[r, oth_sh]; f["vis_far_hp"] = V[r, oth_hp]
    f["knee_gap_t"] = np.hypot(*(P[r, oth_kn] - KN).T) / np.maximum(torso, 1e-9)
    f["ankle_gap_t"] = np.hypot(*(P[r, oth_an] - AN).T) / np.maximum(torso, 1e-9)
    f["far_thigh"] = selev(P[r, oth_kn] - P[r, oth_hp], HP - SH)
    f["w"], f["h"] = m.w.to_numpy(), m.h.to_numpy()
    lab = pd.read_parquet(OUT / "conditions.parquet").pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
    keep = ["몸통과 엉덩이의 정렬 유지", "견갑골이 지면으로부터 충분히 올라옴", "허벅지와 종아리 각도 고정", "고개 숙임 여부", "이완 시 다리 긴장유지", "허리 지면 고정"]
    f = f.join(lab[[c for c in keep if c in lab.columns]], on="clip_id")
    f.to_parquet(S / "m1_frames.parquet", index=False)
    print(f.shape, f.groupby(["exercise", "view"]).size().to_dict())


if __name__ == "__main__":
    main()
