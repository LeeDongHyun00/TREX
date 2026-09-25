"""A2 변수 정의 — GT 3D(cm, y 위) · MediaPipe 월드(cm, y 위로 뒤집은 것) · 이미지 2D(px, y 아래) 공통 계산.

모든 함수는 (..., J, D) 점 배열과 관절 이름→인덱스 사전을 받아 (...) 모양의 프레임 값을 낸다.
부호 규약(앱 knee_out·toe_out 과 같음): 사람 기준 **바깥 = +**, 왼쪽 = +.

3D 신체 좌표계(features.py·PostureCore.kt 와 같음): 원점 골반 중점, x_b = 수평(왼골반−오른골반)(사람 왼쪽 +), up, z_b = x_b × up(전방).
2D 에서 '사람 왼쪽' 의 화면 방향 lat = sign(LHip_x − RHip_x) — 정면이면 +1(사람 왼쪽이 화면 오른쪽), 뒤면 −1.
  → MediaPipe 가 좌우를 '일관되게' 뒤바꾸면(뒤 사선에서 사람을 정면으로 착각) 바깥 방향 값은 보존되고, L−R 차이 값은 부호가 뒤집힌다.
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


def _wrap(d):
    return (d + 180.0) % 360.0 - 180.0


def frame_vars_3d(P: np.ndarray, up: np.ndarray, ix: dict) -> dict:
    """P (..., J, 3) cm. up (3,) 또는 (..., 3) 단위벡터. ix: 이름→관절 인덱스(없는 관절은 None).
    반환: 프레임 값 사전 (...)."""
    g = lambda n: P[..., ix[n], :] if ix.get(n) is not None else np.full(P.shape[:-2] + (3,), np.nan, np.float64)
    up = np.broadcast_to(np.asarray(up, np.float64), P.shape[:-2] + (3,))
    LH, RH, LK, RK, LA, RA = g("LHip"), g("RHip"), g("LKnee"), g("RKnee"), g("LAnkle"), g("RAnkle")
    LS, RS, LT, RT, LE, RE = g("LShoulder"), g("RShoulder"), g("LToe"), g("RToe"), g("LHeel"), g("RHeel")
    hip = (LH + RH) / 2
    sh = (LS + RS) / 2
    am = (LA + RA) / 2
    xb = _unit(_flat(LH - RH, up))
    zb = _unit(np.cross(xb, up))
    h = lambda p: _dot(p, up)
    F = {}
    J = {"L": (LH, LK, LA, LT, LE), "R": (RH, RK, RA, RT, RE)}
    for s, sg in SIDES:
        Hp, Kn, An, To, He = J[s]
        th = Hp - Kn
        F[f"thigh_{s}"] = np.degrees(np.arcsin(np.clip(h(th) / np.linalg.norm(th, axis=-1), -1, 1)))
        F[f"knee_{s}"] = _ang3(Hp, Kn, An)
        F[f"ankle_h_{s}"] = h(An)
        F[f"aot_{s}"] = h(An - To)                                  # 발목이 발끝보다 얼마나 높은가(cm)
        ht = He - To
        F[f"heel_lift_{s}"] = h(ht) / np.where(np.linalg.norm(ht, axis=-1) > 3, np.linalg.norm(ht, axis=-1), np.nan)
        # 무릎–발 정렬: 수평면에서 (무릎−발목) 방향이 (발끝−발목) 방향보다 바깥으로 돈 각
        shin_h, foot_h = _flat(Kn - An, up), _flat(To - An, up)
        a_shin = np.degrees(np.arctan2(sg * _dot(shin_h, xb), _dot(shin_h, zb)))
        a_foot = np.degrees(np.arctan2(sg * _dot(foot_h, xb), _dot(foot_h, zb)))
        F[f"kf_{s}"] = np.where(np.linalg.norm(shin_h, axis=-1) >= 8.0, _wrap(a_shin - a_foot), np.nan)
        F[f"toe_{s}"] = np.where(np.linalg.norm(foot_h, axis=-1) >= 3.0, a_foot, np.nan)
        # knee_out (앱·features.py 와 같은 식)
        hb, kb, ab = [np.stack([_dot(p - hip, xb), h(p - hip), _dot(p - hip, zb)], -1) for p in (Hp, Kn, An)]
        den = hb[..., 1] - ab[..., 1]
        t = (kb[..., 1] - ab[..., 1]) / np.where(np.abs(den) < 1e-3, np.nan, den)
        exp_x = ab[..., 0] + t * (hb[..., 0] - ab[..., 0])
        F[f"knee_out_{s}"] = sg * (kb[..., 0] - exp_x) / np.linalg.norm(Hp - An, axis=-1)
    for k in ("thigh", "knee", "ankle_h", "aot", "heel_lift", "kf", "toe", "knee_out"):
        F[k] = np.nanmean(np.stack([F[f"{k}_L"], F[f"{k}_R"]]), axis=0)
    F["thigh_len"] = (np.linalg.norm(LH - LK, axis=-1) + np.linalg.norm(RH - RK, axis=-1)) / 2
    F["hip_h"] = h(hip)
    F["H"] = h(hip - am)                                            # 골반의 발목 위 높이
    F["am_h"] = h(am)
    F["lean"] = np.degrees(np.arccos(np.clip(_dot(_unit(sh - hip), up), -1, 1)))
    F["obl"] = h(LH - RH) / np.linalg.norm(LH - RH, axis=-1)
    stance_w = np.linalg.norm(_flat(LA - RA, up), axis=-1)
    F["shift"] = _dot(hip - am, xb) / np.where(stance_w < 3, np.nan, stance_w)
    F["kasym"] = F["knee_L"] - F["knee_R"]
    lat_a = np.abs(_dot(LA - RA, xb))
    F["kasr"] = np.abs(_dot(LK - RK, xb)) / np.where(lat_a < 3, np.nan, lat_a)
    sw = np.linalg.norm(_flat(LS - RS, up), axis=-1)
    F["stance"] = stance_w / np.where(sw < 15, np.nan, sw)
    return F


def frame_vars_2d(Q: np.ndarray, ix: dict) -> dict:
    """Q (..., J, 2) 이미지 px (y 아래). 3D 와 같은 키 이름을 쓰되 2D 정의."""
    g = lambda n: Q[..., ix[n], :] if ix.get(n) is not None else np.full(Q.shape[:-2] + (2,), np.nan, np.float64)
    LH, RH, LK, RK, LA, RA = g("LHip"), g("RHip"), g("LKnee"), g("RKnee"), g("LAnkle"), g("RAnkle")
    LS, RS, LT, RT, LE, RE = g("LShoulder"), g("RShoulder"), g("LToe"), g("RToe"), g("LHeel"), g("RHeel")
    hip = (LH + RH) / 2
    sh = (LS + RS) / 2
    am = (LA + RA) / 2
    lat = np.sign(LH[..., 0] - RH[..., 0])
    lat = np.where(lat == 0, np.nan, lat)
    F = {"lat": lat}
    J = {"L": (LH, LK, LA, LT, LE), "R": (RH, RK, RA, RT, RE)}
    for s, sg in SIDES:
        Hp, Kn, An, To, He = J[s]
        F[f"thigh_{s}"] = np.degrees(np.arctan2(Kn[..., 1] - Hp[..., 1], np.abs(Kn[..., 0] - Hp[..., 0])))
        F[f"thighpx_{s}"] = np.linalg.norm(Kn - Hp, axis=-1)
        F[f"kyhy_{s}"] = Kn[..., 1] - Hp[..., 1]                     # 무릎이 골반보다 화면 아래(px, +)
        F[f"knee_{s}"] = _ang3(Hp, Kn, An)
        F[f"ankle_y_{s}"] = An[..., 1]
        F[f"aot_{s}"] = To[..., 1] - An[..., 1]                      # 화면에서 발목이 발끝보다 위(px, +)
        F[f"hl_{s}"] = To[..., 1] - He[..., 1]                       # 화면에서 뒤꿈치가 발끝보다 위(px, +)
        leg = np.linalg.norm(Hp - An, axis=-1)
        F[f"leg_{s}"] = leg
        den = Hp[..., 1] - An[..., 1]
        t = (Kn[..., 1] - An[..., 1]) / np.where(np.abs(den) < 1e-3, np.nan, den)
        dev = Kn[..., 0] - (An[..., 0] + t * (Hp[..., 0] - An[..., 0]))
        out = sg * lat * dev                                          # + = 무릎이 바깥
        F[f"knee_out_{s}"] = out / leg
        F[f"fppa_{s}"] = np.sign(out) * (180.0 - F[f"knee_{s}"])
        dx_t = To[..., 0] - An[..., 0]
        F[f"kratio_{s}"] = np.where(np.abs(dx_t) >= 0.03 * leg, (Kn[..., 0] - An[..., 0]) / dx_t, np.nan)
        dy = To[..., 1] - An[..., 1]
        F[f"toe_{s}"] = np.where(np.hypot(dx_t, dy) >= 0.02 * leg, np.degrees(np.arctan2(sg * lat * dx_t, np.abs(dy))), np.nan)
    for k in ("thigh", "thighpx", "knee", "ankle_y", "aot", "hl", "leg", "knee_out", "fppa", "kratio", "toe"):
        F[k] = np.nanmean(np.stack([F[f"{k}_L"], F[f"{k}_R"]]), axis=0)
    F["hip_y"] = hip[..., 1]
    F["am_y"] = am[..., 1]
    d = sh - hip
    F["lean"] = np.degrees(np.arctan2(np.abs(d[..., 0]), -d[..., 1]))
    F["obl"] = (RH[..., 1] - LH[..., 1]) / np.linalg.norm(LH - RH, axis=-1)
    sa = np.abs(LA[..., 0] - RA[..., 0])
    F["shift"] = lat * (hip[..., 0] - am[..., 0]) / np.where(sa < 1, np.nan, sa)
    F["kasym"] = F["knee_L"] - F["knee_R"]
    F["kasr"] = np.abs(LK[..., 0] - RK[..., 0]) / np.where(sa < 1, np.nan, sa)
    ss = np.abs(LS[..., 0] - RS[..., 0])
    F["stance"] = sa / np.where(ss < 1, np.nan, ss)
    return F


def _take(a, idx):
    """a (N, T) , idx (N,) int (−1 = 없음) → (N,)"""
    out = np.full(a.shape[0], np.nan)
    ok = idx >= 0
    out[ok] = a[np.arange(a.shape[0])[ok], idx[ok]]
    return out


def rep_vars_3d(F: dict, s: np.ndarray, b: np.ndarray, absolute_height: bool) -> dict:
    """프레임 값 (N, T) → 반복 값 (N,). s = 서 있음(무릎각 최대) 프레임, b = 바닥(최소) 프레임.
    absolute_height: GT 처럼 바닥 기준 절대 높이가 있으면 True(골반 절대 높이 강하), MediaPipe 월드(골반 원점)는 False(발목 기준)."""
    t = lambda k, i: _take(F[k], i)
    R = {}
    R["depth_deg"] = t("thigh", b)
    R["depth_ratio"] = np.sin(np.radians(t("thigh", b)))
    if absolute_height:
        R["hip_drop"] = (t("hip_h", s) - t("hip_h", b)) / (t("hip_h", s) - t("am_h", s))
    else:
        R["hip_drop"] = (t("H", s) - t("H", b)) / t("H", s)
    R["kf"] = t("kf", b)
    R["knee_out"] = t("knee_out", b)
    R["kasr"] = t("kasr", b)
    # 발목 절대 높이 상승(cm) — GT 진실. MediaPipe 월드는 골반 원점이라 절대 높이가 없다(골반 강하가 섞임) → 계산하지 않음
    R["heel_ankle"] = (t("ankle_h", b) - t("ankle_h", s)) if absolute_height else np.full(len(s), np.nan)
    R["heel_aot"] = t("aot", b) - t("aot", s)                    # 발목−발끝 높이차 변화(cm)
    R["heel_lift"] = t("heel_lift", b) - t("heel_lift", s)
    R["lean_b"] = t("lean", b)
    R["lean_d"] = t("lean", b) - t("lean", s)
    R["obl"] = t("obl", b)
    R["shift"] = t("shift", b)
    R["kasym"] = t("kasym", b)
    R["toe"] = t("toe", s)
    R["stance"] = t("stance", s)
    R["knee_s"] = t("knee", s)
    R["knee_b"] = t("knee", b)
    return R


def rep_vars_2d(F: dict, s: np.ndarray, b: np.ndarray) -> dict:
    t = lambda k, i: _take(F[k], i)
    R = {}
    R["depth_deg"] = t("thigh", b)
    # 2D 깊이 비: (무릎 y − 골반 y)[바닥] ÷ 서 있을 때 화면 허벅지 길이 — 바닥 프레임의 허벅지는 원근으로 줄어들기 때문. 서 있으면 ≈ +1
    R["depth_ratio"] = np.nanmean(np.stack([t(f"kyhy_{sd}", b) / t(f"thighpx_{sd}", s) for sd in ("L", "R")]), axis=0)
    R["hip_drop"] = (t("hip_y", b) - t("hip_y", s)) / (t("am_y", s) - t("hip_y", s))
    R["fppa"] = t("fppa", b)
    R["knee_out"] = t("knee_out", b)
    R["kasr"] = t("kasr", b)
    R["kratio"] = t("kratio", b)
    leg = t("leg", s)
    R["heel_ankle"] = (t("ankle_y", s) - t("ankle_y", b)) / leg
    R["heel_aot"] = (t("aot", b) - t("aot", s)) / leg
    R["heel_lift"] = (t("hl", b) - t("hl", s)) / leg
    R["lean_b"] = t("lean", b)
    R["lean_d"] = t("lean", b) - t("lean", s)
    R["obl"] = t("obl", b)
    R["shift"] = t("shift", b)
    R["kasym"] = t("kasym", b)
    R["toe"] = t("toe", s)
    R["stance"] = t("stance", s)
    R["knee_s"] = t("knee", s)
    R["knee_b"] = t("knee", b)
    return R
