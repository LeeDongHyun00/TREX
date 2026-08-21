#!/usr/bin/env python
"""3D GT 품질 검사(QC): 불량 프레임 탐지.

검사 1 — 뼈 길이: 프레임별 뼈 길이(몸통/어깨폭/골반폭/허벅지/정강이/상완/전완/목-등/등-허리)가
         (a) 절대 범위를 벗어나거나 (b) 같은 클립 중앙값 대비 ±30% 이탈하면 불량.
검사 2 — L/R 일관성(미러): 골반 좌우축(LHip-RHip 수평성분) 기준으로 어깨 쌍·귀 쌍의 좌우가 뒤집혀 있으면 불량.
         (좌/우 관절 스왑 또는 골반 스왑 → 신체좌표계의 전방축이 뒤집혀 모든 방향 피처가 오염됨)

※ 시간 평활도(프레임 간 점프) 검사는 쓰지 않는다: 클립의 16프레임이 여러 렙에 걸친 성긴 샘플링이라
   (스쿼트 클립 골반 높이 95→38→96→37… 처럼) 정상 동작도 프레임 간 이동이 수십 cm라 구분 불가.

출력:
  outputs/kp3d_frame_ok.parquet  (clip_id, frame_idx, ok, n_bad_bones, lr_swap)
  outputs/qc_per_clip.csv        클립별 불량 비율 / 제외 여부
  outputs/qc_report.md           종목별 불량 프레임/클립 비율
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

from features import J, load_kp3d

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# (이름, 관절A, 관절B, 절대 하한cm, 절대 상한cm)  — 성인 기준 넉넉한 범위
BONES = [
    ("shoulder_w", "LShoulder", "RShoulder", 22, 60),
    ("hip_w", "LHip", "RHip", 10, 45),
    ("thigh_L", "LHip", "LKnee", 25, 65), ("thigh_R", "RHip", "RKnee", 25, 65),
    ("shin_L", "LKnee", "LAnkle", 25, 60), ("shin_R", "RKnee", "RAnkle", 25, 60),
    ("uarm_L", "LShoulder", "LElbow", 18, 45), ("uarm_R", "RShoulder", "RElbow", 18, 45),
    ("farm_L", "LElbow", "LWrist", 15, 40), ("farm_R", "RElbow", "RWrist", 15, 40),
    ("neck_back", "Neck", "Back", 3, 45), ("back_waist", "Back", "Waist", 3, 45),
]
TORSO_LO, TORSO_HI = 28.0, 80.0
REL_TOL = 0.30
SWAP_TOL_CM = 3.0          # 어깨/귀 쌍의 좌우 투영이 -3cm 보다 작으면(반대편) 스왑
CLIP_BAD_FRAC_DROP = 0.5


def bone_lengths(arr: np.ndarray) -> tuple[np.ndarray, list[str]]:
    """arr (N,T,24,3) → (N,T,B) 길이 + 이름. 몸통(Neck–골반중점)도 포함."""
    out, names = [], []
    for name, a, b, lo, hi in BONES:
        out.append(np.linalg.norm(arr[:, :, J[a]] - arr[:, :, J[b]], axis=-1))
        names.append(name)
    hip_mid = (arr[:, :, J["LHip"]] + arr[:, :, J["RHip"]]) / 2
    out.append(np.linalg.norm(arr[:, :, J["Neck"]] - hip_mid, axis=-1))
    names.append("torso")
    return np.stack(out, axis=-1), names


def lr_swap_mask(arr: np.ndarray) -> np.ndarray:
    """(N,T) — 골반 좌우축 기준으로 어깨 또는 귀 쌍이 뒤집힌 프레임."""
    xb = arr[:, :, J["LHip"]] - arr[:, :, J["RHip"]]
    xb = xb.copy()
    xb[..., 1] = 0.0
    n = np.linalg.norm(xb, axis=-1, keepdims=True)
    xb = xb / np.where(n < 1e-6, np.nan, n)
    proj_sh = ((arr[:, :, J["LShoulder"]] - arr[:, :, J["RShoulder"]]) * xb).sum(-1)
    proj_ear = ((arr[:, :, J["LEar"]] - arr[:, :, J["REar"]]) * xb).sum(-1)
    return (proj_sh < -SWAP_TOL_CM) | (proj_ear < -SWAP_TOL_CM)


def frame_ok_mask(arr: np.ndarray):
    L, names = bone_lengths(arr)                      # (N,T,B)
    lo = np.array([b[3] for b in BONES] + [TORSO_LO])
    hi = np.array([b[4] for b in BONES] + [TORSO_HI])
    abs_bad = (L < lo) | (L > hi)
    med = np.nanmedian(L, axis=1, keepdims=True)
    rel_bad = np.abs(L / np.where(med < 1e-6, np.nan, med) - 1.0) > REL_TOL
    valid = ~np.isnan(L)
    bad = (abs_bad | rel_bad) & valid
    n_bad = bad.sum(axis=-1)                          # (N,T)
    frame_valid = valid.any(axis=-1)
    swap = lr_swap_mask(arr) & frame_valid
    ok = (n_bad == 0) & ~swap & frame_valid
    return ok, n_bad, swap, frame_valid, L, names, abs_bad & valid


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / "outputs"
    clip_ids, arr = load_kp3d(out)
    ok, n_bad, swap, frame_valid, L, names, abs_bad = frame_ok_mask(arr)
    N, T = ok.shape
    ii, tt = np.nonzero(frame_valid)
    mask_df = pd.DataFrame({
        "clip_id": np.array(clip_ids, dtype=object)[ii],
        "frame_idx": tt.astype(np.int16),
        "ok": ok[ii, tt],
        "n_bad_bones": n_bad[ii, tt].astype(np.int8),
        "lr_swap": swap[ii, tt],
    })
    mask_df.to_parquet(out / "kp3d_frame_ok.parquet", index=False)

    clips = pd.read_parquet(out / "clips.parquet").set_index("clip_id")
    per_clip = pd.DataFrame({
        "clip_id": clip_ids,
        "n_frames": frame_valid.sum(axis=1),
        "n_bad": (~ok & frame_valid).sum(axis=1),
        "n_bad_bone": ((n_bad > 0) & frame_valid).sum(axis=1),
        "n_swap": swap.sum(axis=1),
    }).set_index("clip_id")
    per_clip["bad_frac"] = per_clip["n_bad"] / per_clip["n_frames"].clip(lower=1)
    per_clip["swap_frac"] = per_clip["n_swap"] / per_clip["n_frames"].clip(lower=1)
    per_clip["exercise"] = clips.loc[per_clip.index, "exercise"]
    per_clip["drop_clip"] = per_clip["bad_frac"] > CLIP_BAD_FRAC_DROP

    bone_abs_rate = {n: float(abs_bad[..., k][frame_valid].mean()) for k, n in enumerate(names)}

    g = per_clip.groupby("exercise").agg(n_clips=("n_bad", "size"), frames=("n_frames", "sum"), bad_frames=("n_bad", "sum"),
                                          bone_frames=("n_bad_bone", "sum"), swap_frames=("n_swap", "sum"),
                                          clips_with_bad=("n_bad", lambda s: int((s > 0).sum())), clips_drop=("drop_clip", "sum"))
    g["bad_frame_pct"] = 100 * g["bad_frames"] / g["frames"]
    g["bone_pct"] = 100 * g["bone_frames"] / g["frames"]
    g["swap_pct"] = 100 * g["swap_frames"] / g["frames"]
    g["clips_with_bad_pct"] = 100 * g["clips_with_bad"] / g["n_clips"]
    g = g.sort_values("bad_frame_pct", ascending=False)

    tot_frames = int(frame_valid.sum())
    tot_bad = int((~ok & frame_valid).sum())
    tot_bone = int(((n_bad > 0) & frame_valid).sum())
    tot_swap = int(swap.sum())
    only_swap = int((swap & (n_bad == 0) & frame_valid).sum())
    lines = ["# 3D GT 품질 검사 (뼈 길이 + L/R 일관성)\n",
             f"- 프레임 {tot_frames:,}개 중 불량 {tot_bad:,}개 ({100*tot_bad/tot_frames:.2f}%) = 뼈길이 위반 {tot_bone:,} ({100*tot_bone/tot_frames:.2f}%) ∪ L/R 스왑 {tot_swap:,} ({100*tot_swap/tot_frames:.2f}%, 뼈검사 통과한 스왑 {only_swap:,})",
             f"- 불량 프레임 ≥1 클립 {(per_clip.n_bad>0).sum():,}/{N:,} ({100*(per_clip.n_bad>0).mean():.1f}%) | 불량 >50% 클립(제외 권고) {int(per_clip.drop_clip.sum()):,}",
             f"- 기준: 절대 범위 위반 또는 클립 중앙값 대비 ±{int(REL_TOL*100)}% 이탈 (뼈 {len(names)}종); 어깨/귀 쌍 좌우 투영 < -{SWAP_TOL_CM:.0f}cm",
             "- 시간 평활도 검사는 미사용 (16프레임이 여러 렙에 걸친 성긴 샘플링)\n",
             "## 뼈별 절대범위 위반 프레임 비율", "",
             "| 뼈 | 위반 % |", "|---|---|"]
    for n, v in sorted(bone_abs_rate.items(), key=lambda kv: -kv[1]):
        lines.append(f"| {n} | {100*v:.2f} |")
    lines += ["", "## 종목별", "", "| 종목 | 클립 | 불량 프레임 % | 뼈길이 % | L/R 스왑 % | 불량 포함 클립 % | 제외 권고 클립 |", "|---|---|---|---|---|---|---|"]
    for ex, r in g.iterrows():
        lines.append(f"| {ex} | {int(r.n_clips)} | {r.bad_frame_pct:.2f} | {r.bone_pct:.2f} | {r.swap_pct:.2f} | {r.clips_with_bad_pct:.1f} | {int(r.clips_drop)} |")
    (out / "qc_report.md").write_text("\n".join(lines), encoding="utf-8")
    per_clip.to_csv(out / "qc_per_clip.csv", encoding="utf-8-sig")
    print("\n".join(lines[:6]))
    print("...")
    print("\n".join(lines[-45:]))


if __name__ == "__main__":
    main()
