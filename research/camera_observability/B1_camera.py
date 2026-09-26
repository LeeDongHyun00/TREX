#!/usr/bin/env python
"""B1 보조 — 덤벨 컬 클립의 (클립, 뷰)마다 GT 3D↔2D DLT 로 카메라를 복원해 **실제 촬영 방위각**을 구한다.

왜: AIHub 카메라 코드(A~E)는 실제 촬영 방향이 아닐 수 있다(서서 종목 13.7 % 가 C 를 등지고 찍힘, CLAUDE.md).
A2_camera.py 의 DLT·분해 함수를 그대로 쓰되 종목만 바꾼다(A2_camera 는 바벨 스쿼트 고정).

출력: outputs/B1/cameras.parquet — A2 와 같은 컬럼 + view_actual(방위각으로 판정한 실제 뷰: C 정면 / B 사용자 오른쪽 앞 / D 왼쪽 앞 / A·E 뒤 / X 그 밖)
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
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
from parse_labels import JOINTS_SHORT, KP2D_COLS, KP3D_COLS  # noqa: E402
from A2_camera import dlt, decompose  # noqa: E402

SRC = HERE.parent / "aihub_fitness" / "outputs"
OUT = HERE / "outputs" / "B1"
J = {n: i for i, n in enumerate(JOINTS_SHORT)}
EXERCISE = "덤벨 컬"


def view_actual(az: float) -> str:
    """몸 기준 방위각(0 = 정면, + = 사람 왼쪽) → 실제 뷰 등급. 스쿼트 실측 중앙값 B −35°, D +41°, A −133°, E +133°."""
    if not np.isfinite(az):
        return "X"
    if abs(az) <= 20:
        return "C"
    if -65 <= az < -20:
        return "B"
    if 20 < az <= 65:
        return "D"
    if -160 <= az < -105:
        return "A"
    if 105 < az <= 160:
        return "E"
    return "X"


def main():
    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "day", "performer", "type_key"])
    sq = clips[clips.exercise == EXERCISE]
    ids = sq.clip_id.tolist()
    k3 = pd.read_parquet(SRC / "kp3d.parquet", filters=[("clip_id", "in", ids)])
    qc = pd.read_parquet(SRC / "kp3d_frame_ok.parquet", filters=[("clip_id", "in", ids)])
    k3 = k3.merge(qc[["clip_id", "frame_idx", "ok"]], on=["clip_id", "frame_idx"], how="left")
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", ids)])
    rows = []
    g3 = {c: g for c, g in k3.groupby("clip_id")}
    use = [J[n] for n in JOINTS_SHORT if n not in ("LPalm", "RPalm", "Back", "Waist")]
    for (cid, vl), g2 in k2.groupby(["clip_id", "view_letter"]):
        g = g3[cid].merge(g2, on="frame_idx", suffixes=("_3", "_2"))
        g = g[g.ok.fillna(False)]
        if len(g) < 4:
            continue
        X = g[[f"{c}_3" if f"{c}_3" in g.columns else c for c in KP3D_COLS]].to_numpy(float).reshape(-1, 24, 3)
        x = g[[f"{c}_2" if f"{c}_2" in g.columns else c for c in KP2D_COLS]].to_numpy(float).reshape(-1, 24, 2)
        Xp, xp = X[:, use].reshape(-1, 3), x[:, use].reshape(-1, 2)
        ok = np.isfinite(Xp).all(1) & np.isfinite(xp).all(1)
        Xp, xp = Xp[ok], xp[ok]
        if len(Xp) < 30:
            continue
        P = dlt(Xp, xp)
        pr = (P @ np.c_[Xp, np.ones(len(Xp))].T).T
        err = np.linalg.norm(pr[:, :2] / pr[:, 2:3] - xp, axis=1)
        keep = err <= np.quantile(err, 0.9)
        P = dlt(Xp[keep], xp[keep])
        pr = (P @ np.c_[Xp, np.ones(len(Xp))].T).T
        err = np.linalg.norm(pr[:, :2] / pr[:, 2:3] - xp, axis=1)
        K, R, C = decompose(P)
        LH, RH = X[:, J["LHip"]], X[:, J["RHip"]]
        hip = (LH + RH) / 2
        xb_m = (LH - RH).mean(0)
        xb_m[1] = 0
        xb_m /= np.linalg.norm(xb_m)
        up = np.array([0.0, 1.0, 0.0])
        zb = np.cross(xb_m, up)
        hip_m = hip.mean(0)
        d = C - hip_m
        dh = d.copy()
        dh[1] = 0
        az = np.degrees(np.arctan2(dh @ xb_m, dh @ zb))
        elev = np.degrees(np.arctan2(d[1], np.linalg.norm(dh)))
        ank_y = np.nanmedian((X[:, J["LAnkle"], 1] + X[:, J["RAnkle"], 1]) / 2)
        axis = R[2]
        pitch = np.degrees(np.arcsin(np.clip(-axis[1], -1, 1)))
        up_cam = R @ up
        roll = np.degrees(np.arctan2(up_cam[0], -up_cam[1]))
        # 2D 어깨 순서(카메라 쪽에서 본 좌우) — GT 2D 로 뒤집힘 교차 확인용
        sh_order = float(np.nanmedian(np.sign(x[:, J["LShoulder"], 0] - x[:, J["RShoulder"], 0])))
        rows.append(dict(clip_id=cid, view_letter=vl, n_pts=int(len(Xp)), reproj_px=float(np.median(err)), reproj_p90=float(np.quantile(err, 0.9)),
                         az_deg=float(az), elev_deg=float(elev), cam_h_cm=float(C[1] - ank_y), dist_cm=float(np.linalg.norm(dh)),
                         pitch_deg=float(pitch), roll_deg=float(roll), fx=float(K[0, 0]), fy=float(K[1, 1]), sh_order_2d=sh_order,
                         up_cam_x=float(up_cam[0]), up_cam_y=float(up_cam[1]), up_cam_z=float(up_cam[2]),
                         **{f"R{i}{j}": float(R[i, j]) for i in range(3) for j in range(3)}))
    cam = pd.DataFrame(rows).merge(sq[["clip_id", "day", "performer", "type_key"]], on="clip_id", how="left")
    cam["view_actual"] = cam.az_deg.map(view_actual)
    cam["cam_ok"] = cam.reproj_px < 8
    OUT.mkdir(parents=True, exist_ok=True)
    cam.to_parquet(OUT / "cameras.parquet", index=False)
    pd.set_option("display.width", 220)
    print(f"[cam] {len(cam)} (클립,뷰) | 재투영 중앙값 {cam.reproj_px.median():.1f}px, p90 {cam.reproj_px.quantile(0.9):.1f}px | reproj<8px {cam.cam_ok.mean()*100:.1f}%")
    print(cam.groupby("view_letter")[["az_deg", "elev_deg", "cam_h_cm", "dist_cm", "pitch_deg", "roll_deg", "reproj_px"]].median().round(1))
    print("코드 × 실제 뷰:")
    print(pd.crosstab(cam.view_letter, cam.view_actual))
    print("날짜 × 코드 방위각 중앙값:")
    print(cam.groupby(["day", "view_letter"]).az_deg.median().unstack().round(0))
    flipped = cam[(cam.view_letter == "C") & (cam.az_deg.abs() > 90)]
    print(f"C 코드가 뒤를 보는 클립: {len(flipped)} / {int((cam.view_letter == 'C').sum())}")
    print(flipped.groupby("day").size())


if __name__ == "__main__":
    main()
