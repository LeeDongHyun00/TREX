#!/usr/bin/env python
"""A2 진단 — 손실 원인 확인용 '관절 바꿔 끼우기' 실험.

MediaPipe 2D 에서 한 관절 묶음만 GT 2D 로 바꿔 끼워 변수를 다시 재고, 손실이 얼마나 회복되는지 본다.
회복되면 그 관절이 손실의 원인이다.
  1) 뒤 사선(A/E)에서 knee_out·FPPA 가 무너지는 이유: 골반(엉덩이) 위치? 먼 쪽 무릎?
  2) 뒤 사선에서 골반 강하(R_hip_drop)의 수행자 내 추적이 약한 이유: 골반 세로 위치?
  3) 발끝각(S_toe) 2D: 발끝(31/32) 대신 GT Foot 을 쓰면? (점 정의 차이의 크기)
출력: outputs/A2/A2_diag.csv
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import A2_analysis as AN  # noqa: E402
import A2_vars as V  # noqa: E402

OUT = HERE / "outputs" / "A2"


def main():
    sq, ids, A, G, ok, conds, keymap = AN.load_gt()
    N = len(ids)
    perf = (sq.performer + "@" + sq.day).to_numpy()
    cam, OKV, UPMP, RC, AZ = AN.load_cams(ids, sq)
    Fgt = V.frame_vars_3d(A, np.array([0.0, 1.0, 0.0]), AN.IX_GT)
    k = Fgt["knee"]
    s = np.where(np.isnan(k), -np.inf, k).argmax(1)
    b = np.where(np.isnan(k), np.inf, k).argmin(1)
    valid = (np.nanmax(k, 1) - np.nanmin(k, 1)) >= 40
    s[~valid], b[~valid] = -1, -1
    Rgt = V.rep_vars_3d(Fgt, s, b, absolute_height=True)
    M = AN.load_mp(keymap, N, "full")
    X = M["X"].copy()
    X[~(M["VIS"] >= AN.MIN_VIS)] = np.nan
    # MediaPipe 33 → GT 24 대응 (바꿔 끼울 관절)
    pairs = {"hip": ((23, "LHip"), (24, "RHip")), "knee": ((25, "LKnee"), (26, "RKnee")), "ankle": ((27, "LAnkle"), (28, "RAnkle")),
             "toe←GTFoot": ((31, "LFoot"), (32, "RFoot"))}
    rows = []
    for vi, vl in enumerate(AN.VIEWS):
        base = X[:, vi]
        g2 = G[:, vi]
        R2g = V.rep_vars_2d(V.frame_vars_2d(g2, AN.IX_GT), s, b)
        for swap in ("없음", "hip", "knee", "ankle", "toe←GTFoot", "hip+knee"):
            Q = base.copy()
            for part in swap.split("+"):
                if part in pairs:
                    for mi, gn in pairs[part]:
                        Q[..., mi, :] = g2[..., AN.GJ[gn], :]
            R = V.rep_vars_2d(V.frame_vars_2d(Q, AN.IX_MP), s, b)
            for kk in R:
                R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
            for key, tkey in (("knee_out", "kf"), ("fppa", "kf"), ("kasr", "kf"), ("hip_drop", "hip_drop"),
                              ("depth_ratio", "depth_ratio"), ("toe", "toe"), ("lean_b", "lean_b")):
                m3 = AN.fid_metrics(Rgt[tkey], R[key], perf, False, False)
                m2 = AN.fid_metrics(R2g[key], R[key], perf, True, False)
                rows.append(dict(view=vl, swapped_to_GT=swap, var=key, truth=tkey, n=m3.get("n"),
                                 rho_vs_gt3d=m3.get("rho"), rho_w_vs_gt3d=m3.get("rho_w"),
                                 rho_vs_gt2d=m2.get("rho"), rho_w_vs_gt2d=m2.get("rho_w")))
    df = pd.DataFrame(rows)
    df.to_csv(OUT / "A2_diag.csv", index=False, encoding="utf-8-sig")
    pd.set_option("display.width", 250)
    for var in ("knee_out", "fppa", "kasr", "hip_drop", "toe"):
        print(f"== {var}: ρ vs GT3D (ρw)")
        d = df[df["var"] == var]
        print(d.pivot_table(index="swapped_to_GT", columns="view", values="rho_vs_gt3d").round(2).to_string())
        print(d.pivot_table(index="swapped_to_GT", columns="view", values="rho_w_vs_gt3d").round(2).to_string())


if __name__ == "__main__":
    main()
