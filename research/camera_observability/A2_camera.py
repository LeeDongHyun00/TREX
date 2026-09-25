#!/usr/bin/env python
"""A2 보조 — GT 3D(cm) ↔ GT 2D(px) 대응으로 (클립, 뷰)마다 카메라 투영행렬을 DLT 로 추정한다.

왜: AIHub 카메라 코드(A~E)는 실제 촬영 방향이 아닐 수 있다(CLAUDE.md 함정 — 서서 종목 13.7 % 가 C 를 등지고 찍힘).
코드 대신 **몸 기준 실제 방위각**(골반 전방 z_b 와 골반→카메라 수평 방향 사이 각)으로 뷰를 판정한다.
부산물: 카메라 높이·내려다보는 각, 그리고 월드 '위'(중력)를 카메라 좌표로 옮긴 벡터 — MediaPipe 월드 좌표가 카메라 축 정렬이므로
'완벽한 IMU' 를 흉내 낼 수 있다(앱은 IMU 중력축을 up 으로 쓴다).

출력: outputs/A2/cameras.parquet
  clip_id, view_letter, n_pts, reproj_px(중앙값), az_deg(0=정면, +=사람 왼쪽, ±180=뒤), elev_deg(골반에서 본 카메라 올려본각),
  cam_h_cm(카메라 높이 − 발목 높이 중앙값 + 발목 높이 8 cm 가정 없이 그대로 '발목 기준'), dist_cm(골반–카메라 수평거리),
  pitch_deg(광축이 수평 아래로 숙인 각), roll_deg, up_cam_{x,y,z}(월드 위 방향의 OpenCV 카메라 좌표: x 오른쪽, y 아래, z 앞)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
from parse_labels import JOINTS_SHORT, KP2D_COLS, KP3D_COLS  # noqa: E402

SRC = HERE.parent / "aihub_fitness" / "outputs"
OUT = HERE / "outputs" / "A2"
J = {n: i for i, n in enumerate(JOINTS_SHORT)}


def dlt(X: np.ndarray, x: np.ndarray):
    """X (n,3), x (n,2) → P (3,4). Hartley 정규화."""
    def norm_t(p):
        c = p.mean(0)
        s = np.sqrt(p.shape[1]) / np.mean(np.linalg.norm(p - c, axis=1))
        T = np.eye(p.shape[1] + 1)
        T[:-1, :-1] *= s
        T[:-1, -1] = -s * c
        return T
    T3, T2 = norm_t(X), norm_t(x)
    Xh = (T3 @ np.c_[X, np.ones(len(X))].T).T
    xh = (T2 @ np.c_[x, np.ones(len(x))].T).T
    A = np.zeros((2 * len(X), 12))
    A[0::2, 0:4] = Xh
    A[0::2, 8:12] = -xh[:, [0]] * Xh
    A[1::2, 4:8] = Xh
    A[1::2, 8:12] = -xh[:, [1]] * Xh
    _, _, Vt = np.linalg.svd(A)
    Pn = Vt[-1].reshape(3, 4)
    P = np.linalg.inv(T2) @ Pn @ T3
    return P / np.linalg.norm(P[2, :3])


def decompose(P: np.ndarray):
    """P = K [R | t] (OpenCV 관례: 카메라 앞 z>0). → K, R, C"""
    M = P[:, :3]
    if np.linalg.det(M) < 0:
        P = -P
        M = -M
    # RQ 분해
    Q, Rr = np.linalg.qr(np.flipud(M).T)
    R_ = np.flipud(Rr.T)
    R_ = np.fliplr(R_)
    Q = np.flipud(Q.T)
    K, R = R_, Q
    S = np.diag(np.sign(np.diag(K)))
    K, R = K @ S, S @ R
    if np.linalg.det(R) < 0:
        R = -R
        P = -P
    C = -np.linalg.solve(P[:, :3], P[:, 3])
    return K / K[2, 2], R, C


def main():
    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "day", "performer", "type_key"])
    sq = clips[clips.exercise == "바벨 스쿼트"]
    ids = sq.clip_id.tolist()
    k3 = pd.read_parquet(SRC / "kp3d.parquet", filters=[("clip_id", "in", ids)])
    qc = pd.read_parquet(SRC / "kp3d_frame_ok.parquet", filters=[("clip_id", "in", ids)])
    k3 = k3.merge(qc[["clip_id", "frame_idx", "ok"]], on=["clip_id", "frame_idx"], how="left")
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", ids)])
    rows = []
    g3 = {c: g for c, g in k3.groupby("clip_id")}
    for (cid, vl), g2 in k2.groupby(["clip_id", "view_letter"]):
        g = g3[cid].merge(g2, on="frame_idx", suffixes=("_3", "_2"))
        g = g[g.ok.fillna(False)]
        if len(g) < 4:
            continue
        X = g[[f"{c}_3" if f"{c}_3" in g.columns else c for c in KP3D_COLS]].to_numpy(float).reshape(-1, 24, 3)
        x = g[[f"{c}_2" if f"{c}_2" in g.columns else c for c in KP2D_COLS]].to_numpy(float).reshape(-1, 24, 2)
        # 손바닥·Back·Waist 제외(주석 모호), 나머지 21관절
        use = [J[n] for n in JOINTS_SHORT if n not in ("LPalm", "RPalm", "Back", "Waist")]
        Xp, xp = X[:, use].reshape(-1, 3), x[:, use].reshape(-1, 2)
        ok = np.isfinite(Xp).all(1) & np.isfinite(xp).all(1)
        Xp, xp = Xp[ok], xp[ok]
        if len(Xp) < 30:
            continue
        P = dlt(Xp, xp)
        # 한 번 재추정: 재투영 오차 상위 10 % 제외
        pr = (P @ np.c_[Xp, np.ones(len(Xp))].T).T
        err = np.linalg.norm(pr[:, :2] / pr[:, 2:3] - xp, axis=1)
        keep = err <= np.quantile(err, 0.9)
        P = dlt(Xp[keep], xp[keep])
        pr = (P @ np.c_[Xp, np.ones(len(Xp))].T).T
        err = np.linalg.norm(pr[:, :2] / pr[:, 2:3] - xp, axis=1)
        K, R, C = decompose(P)
        # 몸 기준: 서 있는 프레임(무릎각 최대 근처 3프레임) 평균의 골반 좌표계
        LH, RH = X[:, J["LHip"]], X[:, J["RHip"]]
        hip = (LH + RH) / 2
        xb = LH - RH
        xb[:, 1] = 0
        xb = xb / np.linalg.norm(xb, axis=1, keepdims=True)
        xb_m = xb.mean(0)
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
        axis = R[2]                      # 광축(월드)
        pitch = np.degrees(np.arcsin(np.clip(-axis[1], -1, 1)))   # + = 아래를 봄
        up_cam = R @ up                  # 월드 위 → 카메라 좌표 (OpenCV: y 아래)
        roll = np.degrees(np.arctan2(up_cam[0], -up_cam[1]))      # 화면 기울기(+ = 위가 화면 오른쪽으로)
        # 거울상 여부: 오른손 좌표 유지 확인(det R=+1)과 무관하게, 이미지에서 사람 왼쪽이 오른쪽에 오는가(정면이면 True)
        rows.append(dict(clip_id=cid, view_letter=vl, n_pts=int(len(Xp)), reproj_px=float(np.median(err)), reproj_p90=float(np.quantile(err, 0.9)),
                         az_deg=float(az), elev_deg=float(elev), cam_h_cm=float(C[1] - ank_y), dist_cm=float(np.linalg.norm(dh)),
                         pitch_deg=float(pitch), roll_deg=float(roll), fx=float(K[0, 0]), fy=float(K[1, 1]),
                         up_cam_x=float(up_cam[0]), up_cam_y=float(up_cam[1]), up_cam_z=float(up_cam[2]),
                         **{f"R{i}{j}": float(R[i, j]) for i in range(3) for j in range(3)}))
    cam = pd.DataFrame(rows).merge(sq[["clip_id", "day", "performer", "type_key"]], on="clip_id", how="left")
    OUT.mkdir(parents=True, exist_ok=True)
    cam.to_parquet(OUT / "cameras.parquet", index=False)
    pd.set_option("display.width", 200)
    print(f"[cam] {len(cam)} (클립,뷰) | 재투영 중앙값 {cam.reproj_px.median():.1f}px, p90 {cam.reproj_px.quantile(0.9):.1f}px")
    print(cam.groupby("view_letter")[["az_deg", "elev_deg", "cam_h_cm", "dist_cm", "pitch_deg", "roll_deg", "fx", "reproj_px"]].median().round(1))
    print(cam.groupby(["day", "view_letter"]).az_deg.median().unstack().round(0))


if __name__ == "__main__":
    main()
