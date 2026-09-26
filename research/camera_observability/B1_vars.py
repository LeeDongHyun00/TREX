"""B1 변수 정의 — 덤벨 컬. GT 3D(cm, y 위) · MediaPipe 월드(cm, y 위로 뒤집은 것) · 이미지 2D(px, y 아래) 공통.

모든 함수는 (..., J, D) 점 배열과 관절 이름→인덱스 사전을 받아 (...) 모양의 프레임 값을 낸다.
부호 규약: 사람 기준 **바깥(가쪽) = +**, **앞 = +**, 위 = +. 왼쪽 = +.

3D 신체 좌표계(features.py·PostureCore.kt 와 같음): 원점 골반 중점, x_b = 수평(왼골반−오른골반)(사람 왼쪽 +), up, z_b = x_b × up(전방).
앱 피처와 같은 정의: elbow_{L,R}(어깨–팔꿈치–손목 각), elbow_torso_{L,R}(팔꿈치→같은쪽 골반–어깨 직선 수직거리 ÷ 직선 길이),
upperarm_vert(팔꿈치−어깨 vs up 각, 늘어뜨리면 180), elbow_h(팔꿈치−어깨 높이 ÷ 몸통), torso_incl(목−골반 vs up), ear_shoulder_gap.
새 후보: ua_fwd(상완의 시상면 앞 기울기 °, 늘어뜨리면 0·앞 +), ua_out(관상면 바깥 기울기 °), elbow_fwd(팔꿈치의 어깨 대비 앞 오프셋 ÷ 몸통),
elbow_gap(팔꿈치 간격 ÷ 어깨 간격), elbow_gap_h(수평 간격 ÷ 어깨 수평 간격), torso_pitch(앞 숙임 +), hip_ankle_fwd(골반의 발목 대비 앞 오프셋 ÷ 다리),
wrist_h(손목−어깨 높이 ÷ 몸통), forearm_vert, shoulder_ang(팔꿈치–어깨–골반 각).

2D 는 같은 이름에 뷰 기하가 섞인 정의(§ frame_vars_2d). 앞/뒤 부호는 뷰의 방위각 부호가 필요하다(az_sign: 카메라가 사람 왼쪽 앞이면 +1, D 뷰).
"""
from __future__ import annotations

import numpy as np

SIDES = (("L", 1.0), ("R", -1.0))


def _unit(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    return v / np.where(n < 1e-9, np.nan, n)


def _dot(a, b):
    return (a * b).sum(-1)


def _flat(v, up):
    return v - up * _dot(v, up)[..., None]


def _ang3(a, b, c):
    u, w = _unit(a - b), _unit(c - b)
    return np.degrees(np.arccos(np.clip(_dot(u, w), -1, 1)))


def _ang_vec(u, w):
    return np.degrees(np.arccos(np.clip(_dot(_unit(u), _unit(w)), -1, 1)))


def _perp_from_line(p, a, b):
    """점 p 에서 직선 a→b 까지의 수직 벡터와 직선 길이 (features.py·PostureCore.kt 와 같음)."""
    ax = b - a
    L = np.linalg.norm(ax, axis=-1, keepdims=True)
    u = ax / np.where(L < 1e-6, np.nan, L)
    d = p - a
    t = _dot(d, u)[..., None]
    return d - t * u, L[..., 0]


def frame_vars_3d(P: np.ndarray, up: np.ndarray, ix: dict) -> dict:
    """P (..., J, 3) cm. up (3,) 또는 (..., 3) 단위벡터. ix: 이름→관절 인덱스(없는 관절은 None). 반환: 프레임 값 사전 (...)."""
    def g(n):
        return P[..., ix[n], :] if ix.get(n) is not None else np.full(P.shape[:-2] + (3,), np.nan, np.float64)
    up = np.broadcast_to(np.asarray(up, np.float64), P.shape[:-2] + (3,))
    LS, RS, LE, RE, LW, RW = g("LShoulder"), g("RShoulder"), g("LElbow"), g("RElbow"), g("LWrist"), g("RWrist")
    LH, RH, LA, RA = g("LHip"), g("RHip"), g("LAnkle"), g("RAnkle")
    LEar, REar, Neck = g("LEar"), g("REar"), g("Neck")
    hip = (LH + RH) / 2
    sh = (LS + RS) / 2
    am = (LA + RA) / 2
    ear = (LEar + REar) / 2
    neck = Neck if ix.get("Neck") is not None else sh
    xb = _unit(_flat(LH - RH, up))
    zb = _unit(np.cross(xb, up))
    h = lambda p: _dot(p, up)
    chord = neck - hip
    torso = np.linalg.norm(chord, axis=-1)
    torso = np.where(torso < 20, np.nan, torso)
    sh_w = np.linalg.norm(LS - RS, axis=-1)
    sh_w = np.where(sh_w < 15, np.nan, sh_w)
    leg = (np.linalg.norm(LH - LA, axis=-1) + np.linalg.norm(RH - RA, axis=-1)) / 2
    leg = np.where(leg < 40, np.nan, leg)
    F = {}
    F["torso_len"] = torso
    F["sh_w"] = sh_w
    F["torso_incl"] = _ang_vec(chord, up)
    tb = np.stack([_dot(chord, xb), h(chord), _dot(chord, zb)], -1)
    F["torso_pitch"] = np.degrees(np.arctan2(tb[..., 2], tb[..., 1]))        # + 앞 숙임
    F["torso_roll"] = np.degrees(np.arctan2(tb[..., 0], tb[..., 1]))
    F["ear_shoulder_gap"] = h(ear - sh) / torso
    F["shoulder_neck_gap"] = h(neck - sh) / torso if ix.get("Neck") is not None else np.full(torso.shape, np.nan)
    F["hip_ankle_fwd"] = _dot(hip - am, zb) / leg                             # 골반이 발목보다 앞 +
    F["hip_h"] = h(hip)
    J = {"L": (LS, LE, LW, LH), "R": (RS, RE, RW, RH)}
    for s, sg in SIDES:
        Sh, El, Wr, Hp = J[s]
        F[f"elbow_{s}"] = _ang3(Sh, El, Wr)
        F[f"shoulder_ang_{s}"] = _ang3(El, Sh, Hp)
        perp, L = _perp_from_line(El, Hp, Sh)
        F[f"elbow_torso_{s}"] = np.linalg.norm(perp, axis=-1) / L
        # 수직거리의 앞/바깥 성분(부호 있음)
        F[f"elbow_torso_fwd_{s}"] = _dot(perp, zb) / L
        F[f"elbow_torso_out_{s}"] = sg * _dot(perp, xb) / L
        d = El - Sh
        db = np.stack([sg * _dot(d, xb), h(d), _dot(d, zb)], -1)               # (바깥, 위, 앞)
        F[f"upperarm_vert_{s}"] = _ang_vec(d, up)                                # 늘어뜨리면 180
        F[f"ua_fwd_{s}"] = np.degrees(np.arctan2(db[..., 2], -db[..., 1]))      # 상완 앞 기울기(°), 앞 +
        F[f"ua_out_{s}"] = np.degrees(np.arctan2(db[..., 0], -db[..., 1]))      # 상완 바깥 기울기(°)
        F[f"ua_dev_{s}"] = 180.0 - F[f"upperarm_vert_{s}"]                       # 수직에서 벗어난 총각
        F[f"elbow_h_{s}"] = db[..., 1] / torso
        F[f"elbow_fwd_{s}"] = db[..., 2] / torso
        F[f"elbow_out_{s}"] = db[..., 0] / torso
        F[f"elbow_fwd_hip_{s}"] = _dot(El - hip, zb) / torso                    # 골반 원점 기준 앞 오프셋(몸통 기울기 포함)
        F[f"forearm_vert_{s}"] = _ang_vec(Wr - El, up)
        F[f"wrist_h_{s}"] = h(Wr - Sh) / torso
        F[f"elbow_wrist_h_{s}"] = h(El - Wr) / torso
        F[f"shoulder_h_{s}"] = h(Sh - hip) / torso
        # 팔꿈치 신체좌표(골반 원점, 몸통 정규화) — 반복 안 이동량 계산용
        eb = El - hip
        F[f"elbow_bx_{s}"], F[f"elbow_by_{s}"], F[f"elbow_bz_{s}"] = _dot(eb, xb) / torso, h(eb) / torso, _dot(eb, zb) / torso
        F[f"elbow_sx_{s}"], F[f"elbow_sy_{s}"], F[f"elbow_sz_{s}"] = db[..., 0] / torso, db[..., 1] / torso, db[..., 2] / torso
    eg = LE - RE
    F["elbow_gap"] = np.linalg.norm(eg, axis=-1) / sh_w
    F["elbow_gap_h"] = np.linalg.norm(_flat(eg, up), axis=-1) / np.where(np.linalg.norm(_flat(LS - RS, up), axis=-1) < 15, np.nan,
                                                                        np.linalg.norm(_flat(LS - RS, up), axis=-1))
    F["elbow_gap_torso"] = np.linalg.norm(eg, axis=-1) / torso
    F["wrist_gap"] = np.linalg.norm(LW - RW, axis=-1) / sh_w
    for k in ("elbow", "shoulder_ang", "elbow_torso", "elbow_torso_fwd", "elbow_torso_out", "upperarm_vert", "ua_fwd", "ua_out", "ua_dev",
              "elbow_h", "elbow_fwd", "elbow_out", "elbow_fwd_hip", "forearm_vert", "wrist_h", "elbow_wrist_h", "shoulder_h"):
        F[f"{k}_mean"] = (F[f"{k}_L"] + F[f"{k}_R"]) / 2
        F[f"{k}_max"] = np.fmax(F[f"{k}_L"], F[f"{k}_R"])
        F[f"{k}_min"] = np.fmin(F[f"{k}_L"], F[f"{k}_R"])
    return F


def frame_vars_2d(Q: np.ndarray, ix: dict, az_sign: np.ndarray | float = 0.0) -> dict:
    """Q (..., J, 2) 이미지 px (y 아래). az_sign: 카메라 방위각의 부호(+ = 사람 왼쪽 앞 D, − = 오른쪽 앞 B, 0 = 정면 C: 앞/뒤 부호 미정의).
    사람 앞(z_b)은 방위각 az 인 카메라의 화면 오른쪽 벡터에 −sin(az) 로 투영된다 → 화면 x 증가 = 사람 앞 방향 × (−az_sign)."""
    def g(n):
        return Q[..., ix[n], :] if ix.get(n) is not None else np.full(Q.shape[:-2] + (2,), np.nan, np.float64)
    LS, RS, LE, RE, LW, RW = g("LShoulder"), g("RShoulder"), g("LElbow"), g("RElbow"), g("LWrist"), g("RWrist")
    LH, RH, LA, RA = g("LHip"), g("RHip"), g("LAnkle"), g("RAnkle")
    LEar, REar, Neck = g("LEar"), g("REar"), g("Neck")
    hip = (LH + RH) / 2
    sh = (LS + RS) / 2
    am = (LA + RA) / 2
    ear = (LEar + REar) / 2
    neck = Neck if ix.get("Neck") is not None else sh
    lat = np.sign(LH[..., 0] - RH[..., 0])
    lat = np.where(lat == 0, np.nan, lat)                 # 사람 왼쪽의 화면 방향(정면 +1)
    fwd_sign = -np.asarray(az_sign, np.float64) * np.ones_like(lat)
    fwd_sign = np.where(fwd_sign == 0, np.nan, fwd_sign)
    chord = neck - hip
    torso = np.linalg.norm(chord, axis=-1)
    torso = np.where(torso < 10, np.nan, torso)
    sh_wx = np.abs(LS[..., 0] - RS[..., 0])
    sh_w = np.linalg.norm(LS - RS, axis=-1)
    leg = (np.linalg.norm(LH - LA, axis=-1) + np.linalg.norm(RH - RA, axis=-1)) / 2
    F = {"lat": lat, "torso_px": torso}
    F["torso_incl"] = np.degrees(np.arctan2(np.abs(chord[..., 0]), -chord[..., 1]))     # 화면 기울기(무부호)
    F["torso_pitch"] = fwd_sign * chord[..., 0] / torso                                   # 앞 숙임 + (사선 뷰만, 비율)
    F["ear_shoulder_gap"] = (sh[..., 1] - ear[..., 1]) / torso
    F["hip_ankle_fwd"] = fwd_sign * (hip[..., 0] - am[..., 0]) / leg
    J = {"L": (LS, LE, LW, LH), "R": (RS, RE, RW, RH)}
    for s, sg in SIDES:
        Sh, El, Wr, Hp = J[s]
        F[f"elbow_{s}"] = _ang3(Sh, El, Wr)
        F[f"shoulder_ang_{s}"] = _ang3(El, Sh, Hp)
        perp, L = _perp_from_line(El, Hp, Sh)
        F[f"elbow_torso_{s}"] = np.linalg.norm(perp, axis=-1) / L
        F[f"elbow_torso_fwd_{s}"] = fwd_sign * perp[..., 0] / L
        F[f"elbow_torso_out_{s}"] = sg * lat * perp[..., 0] / L
        d = El - Sh
        F[f"ua_dev_{s}"] = np.degrees(np.arctan2(np.abs(d[..., 0]), d[..., 1]))          # 화면 수직에서 벗어난 각(무부호)
        F[f"ua_fwd_{s}"] = fwd_sign * np.degrees(np.arctan2(d[..., 0], d[..., 1]))        # 앞 + (사선 뷰)
        F[f"ua_out_{s}"] = sg * lat * np.degrees(np.arctan2(d[..., 0], d[..., 1]))         # 바깥 + (정면 뷰)
        F[f"elbow_h_{s}"] = -d[..., 1] / torso                                              # 위 +
        F[f"elbow_fwd_{s}"] = fwd_sign * d[..., 0] / torso
        F[f"elbow_out_{s}"] = sg * lat * d[..., 0] / torso
        F[f"elbow_x_{s}"] = np.abs(d[..., 0]) / torso                                      # 어깨 대비 가로 오프셋(무부호)
        F[f"forearm_vert_{s}"] = np.degrees(np.arctan2(np.abs(Wr[..., 0] - El[..., 0]), -(Wr[..., 1] - El[..., 1])))
        F[f"forearm_px_{s}"] = np.linalg.norm(Wr - El, axis=-1)
        F[f"wrist_h_{s}"] = (Sh[..., 1] - Wr[..., 1]) / torso
        F[f"wrist_el_h_{s}"] = (El[..., 1] - Wr[..., 1]) / torso                            # 손목이 팔꿈치보다 위 +
        F[f"elbow_bx_{s}"], F[f"elbow_by_{s}"] = (El[..., 0] - hip[..., 0]) / torso, -(El[..., 1] - hip[..., 1]) / torso
        F[f"elbow_sx_{s}"], F[f"elbow_sy_{s}"] = d[..., 0] / torso, -d[..., 1] / torso
    F["elbow_gap"] = np.linalg.norm(LE - RE, axis=-1) / sh_w
    F["elbow_gap_x"] = np.abs(LE[..., 0] - RE[..., 0]) / np.where(sh_wx < 5, np.nan, sh_wx)
    F["elbow_gap_torso"] = np.linalg.norm(LE - RE, axis=-1) / torso
    F["wrist_gap"] = np.linalg.norm(LW - RW, axis=-1) / sh_w
    for k in ("elbow", "shoulder_ang", "elbow_torso", "elbow_torso_fwd", "elbow_torso_out", "ua_dev", "ua_fwd", "ua_out",
              "elbow_h", "elbow_fwd", "elbow_out", "elbow_x", "forearm_vert", "wrist_h", "wrist_el_h"):
        F[f"{k}_mean"] = (F[f"{k}_L"] + F[f"{k}_R"]) / 2
        F[f"{k}_max"] = np.fmax(F[f"{k}_L"], F[f"{k}_R"])
        F[f"{k}_min"] = np.fmin(F[f"{k}_L"], F[f"{k}_R"])
    return F


def _take(a, idx):
    """a (N, T), idx (N,) int (−1 = 없음) → (N,)"""
    out = np.full(a.shape[0], np.nan)
    ok = idx >= 0
    out[ok] = a[np.arange(a.shape[0])[ok], idx[ok]]
    return out


# 반복 통계로 만들 프레임 변수와 통계 종류
#  c = 수축(팔꿈치각 최소) 프레임, e = 이완(최대) 프레임, d = c − e, mean/max/min/range = 유효 프레임 전체(≈ 앱 세트 통계)
REP_KEYS_3D = ["elbow_L", "elbow_R", "elbow_mean", "elbow_min", "elbow_max", "shoulder_ang_L", "shoulder_ang_R", "shoulder_ang_mean", "shoulder_ang_max",
               "elbow_torso_L", "elbow_torso_R", "elbow_torso_mean", "elbow_torso_max", "elbow_torso_fwd_L", "elbow_torso_fwd_R", "elbow_torso_fwd_mean", "elbow_torso_fwd_max",
               "elbow_torso_out_L", "elbow_torso_out_R", "elbow_torso_out_mean", "elbow_torso_out_max",
               "upperarm_vert_L", "upperarm_vert_R", "upperarm_vert_mean", "upperarm_vert_min", "ua_fwd_L", "ua_fwd_R", "ua_fwd_mean", "ua_fwd_max",
               "ua_out_L", "ua_out_R", "ua_out_mean", "ua_out_max", "ua_dev_L", "ua_dev_R", "ua_dev_mean", "ua_dev_max",
               "elbow_h_L", "elbow_h_R", "elbow_h_mean", "elbow_h_max", "elbow_fwd_L", "elbow_fwd_R", "elbow_fwd_mean", "elbow_fwd_max",
               "elbow_out_L", "elbow_out_R", "elbow_out_mean", "elbow_out_max", "elbow_fwd_hip_L", "elbow_fwd_hip_R", "elbow_fwd_hip_mean", "elbow_fwd_hip_max",
               "forearm_vert_L", "forearm_vert_R", "forearm_vert_mean", "wrist_h_L", "wrist_h_R", "wrist_h_mean", "elbow_wrist_h_L", "elbow_wrist_h_R", "elbow_wrist_h_mean",
               "shoulder_h_L", "shoulder_h_R", "shoulder_h_mean", "elbow_gap", "elbow_gap_h", "elbow_gap_torso", "wrist_gap",
               "torso_incl", "torso_pitch", "torso_roll", "ear_shoulder_gap", "shoulder_neck_gap", "hip_ankle_fwd", "hip_h"]
REP_KEYS_2D = ["elbow_L", "elbow_R", "elbow_mean", "elbow_min", "elbow_max", "shoulder_ang_L", "shoulder_ang_R", "shoulder_ang_mean", "shoulder_ang_max",
               "elbow_torso_L", "elbow_torso_R", "elbow_torso_mean", "elbow_torso_max", "elbow_torso_fwd_L", "elbow_torso_fwd_R", "elbow_torso_fwd_mean", "elbow_torso_fwd_max",
               "elbow_torso_out_L", "elbow_torso_out_R", "elbow_torso_out_mean", "elbow_torso_out_max",
               "ua_dev_L", "ua_dev_R", "ua_dev_mean", "ua_dev_max", "ua_fwd_L", "ua_fwd_R", "ua_fwd_mean", "ua_fwd_max", "ua_out_L", "ua_out_R", "ua_out_mean", "ua_out_max",
               "elbow_h_L", "elbow_h_R", "elbow_h_mean", "elbow_h_max", "elbow_fwd_L", "elbow_fwd_R", "elbow_fwd_mean", "elbow_fwd_max",
               "elbow_out_L", "elbow_out_R", "elbow_out_mean", "elbow_out_max", "elbow_x_L", "elbow_x_R", "elbow_x_mean", "elbow_x_max",
               "forearm_vert_L", "forearm_vert_R", "forearm_vert_mean", "wrist_h_L", "wrist_h_R", "wrist_h_mean", "wrist_el_h_L", "wrist_el_h_R", "wrist_el_h_mean",
               "elbow_gap", "elbow_gap_x", "elbow_gap_torso", "wrist_gap", "torso_incl", "torso_pitch", "ear_shoulder_gap", "hip_ankle_fwd"]


def rep_vars(F: dict, c: np.ndarray, e: np.ndarray, keys: list, ok_frames: np.ndarray | None = None, is2d: bool = False) -> dict:
    """프레임 값 (N, T) → 반복 값 (N,) 사전. 통계: _c(수축) _e(이완) _d(c−e) _mean _max _min _range (유효 프레임 전체).
    ok_frames (N, T) bool: 통계에 넣을 프레임(없으면 유한값 전부)."""
    R = {}
    for k in keys:
        if k not in F:
            continue
        a = F[k]
        if ok_frames is not None:
            a = np.where(ok_frames, a, np.nan)
        R[f"{k}_c"] = _take(a, c)
        R[f"{k}_e"] = _take(a, e)
        R[f"{k}_d"] = R[f"{k}_c"] - R[f"{k}_e"]
        with np.errstate(all="ignore"):
            n = np.isfinite(a).sum(1)
            R[f"{k}_mean"] = np.where(n >= 4, np.nanmean(a, 1), np.nan)
            R[f"{k}_max"] = np.where(n >= 4, np.nanmax(a, 1), np.nan)
            R[f"{k}_min"] = np.where(n >= 4, np.nanmin(a, 1), np.nan)
            R[f"{k}_range"] = R[f"{k}_max"] - R[f"{k}_min"]
    # 팔꿈치 이동량(사용자 제안): 수축–이완 사이 팔꿈치 좌표 이동 거리(몸통 정규화) — 골반 기준 / 어깨 기준
    for s in ("L", "R"):
        if is2d:
            db = np.hypot(_take(F[f"elbow_bx_{s}"], c) - _take(F[f"elbow_bx_{s}"], e), _take(F[f"elbow_by_{s}"], c) - _take(F[f"elbow_by_{s}"], e))
            ds = np.hypot(_take(F[f"elbow_sx_{s}"], c) - _take(F[f"elbow_sx_{s}"], e), _take(F[f"elbow_sy_{s}"], c) - _take(F[f"elbow_sy_{s}"], e))
            # 세트 전체 이동 폭: 프레임별 좌표의 강건 범위(p90−p10)
            bx, by = F[f"elbow_bx_{s}"], F[f"elbow_by_{s}"]
            sx, sy = F[f"elbow_sx_{s}"], F[f"elbow_sy_{s}"]
            rb = np.hypot(np.nanpercentile(bx, 90, 1) - np.nanpercentile(bx, 10, 1), np.nanpercentile(by, 90, 1) - np.nanpercentile(by, 10, 1))
            rs = np.hypot(np.nanpercentile(sx, 90, 1) - np.nanpercentile(sx, 10, 1), np.nanpercentile(sy, 90, 1) - np.nanpercentile(sy, 10, 1))
        else:
            db = np.sqrt(sum((_take(F[f"elbow_b{a}_{s}"], c) - _take(F[f"elbow_b{a}_{s}"], e)) ** 2 for a in "xyz"))
            ds = np.sqrt(sum((_take(F[f"elbow_s{a}_{s}"], c) - _take(F[f"elbow_s{a}_{s}"], e)) ** 2 for a in "xyz"))
            with np.errstate(all="ignore"):
                rb = np.sqrt(sum((np.nanpercentile(F[f"elbow_b{a}_{s}"], 90, 1) - np.nanpercentile(F[f"elbow_b{a}_{s}"], 10, 1)) ** 2 for a in "xyz"))
                rs = np.sqrt(sum((np.nanpercentile(F[f"elbow_s{a}_{s}"], 90, 1) - np.nanpercentile(F[f"elbow_s{a}_{s}"], 10, 1)) ** 2 for a in "xyz"))
        R[f"elbow_disp_hip_{s}_d"], R[f"elbow_disp_sh_{s}_d"] = db, ds
        R[f"elbow_disp_hip_{s}_range"], R[f"elbow_disp_sh_{s}_range"] = rb, rs
    for k in ("elbow_disp_hip", "elbow_disp_sh"):
        for st in ("d", "range"):
            R[f"{k}_mean_{st}"] = (R[f"{k}_L_{st}"] + R[f"{k}_R_{st}"]) / 2
            R[f"{k}_max_{st}"] = np.fmax(R[f"{k}_L_{st}"], R[f"{k}_R_{st}"])
    # 가동 범위 2D 대리: 손목 상승(수축 − 이완, 어깨 대비 높이) ÷ 이완 프레임의 전완 px
    if is2d:
        for s in ("L", "R"):
            fe = _take(F[f"forearm_px_{s}"], e)
            R[f"wrist_rise_ratio_{s}"] = (_take(F[f"wrist_h_{s}"], c) - _take(F[f"wrist_h_{s}"], e)) * _take(F["torso_px"], e) / np.where(fe < 5, np.nan, fe)
        R["wrist_rise_ratio_mean"] = (R["wrist_rise_ratio_L"] + R["wrist_rise_ratio_R"]) / 2
    return R
