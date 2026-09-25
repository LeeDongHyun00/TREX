# -*- coding: utf-8 -*-
"""A1 가상 카메라 관측성 — 공통 모듈 (데이터·3D 진실·가상 카메라·2D 후보·지면 역투영 리프팅).

MediaPipe 잡음과 무관한 '기하학적 상한'을 잰다: AIHub 바벨 스쿼트 GT 3D 를 가상 폰 카메라로 투영해
배치(방위·높이·거리·화각·가로 위치)마다 각 변수의 2D 추정이 3D 진실을 얼마나 보존하는지 본다.

좌표 (데이터로 확인, A1_REPORT.md §0)
  - AIHub 3D: cm → m 로 바꿔 쓴다. +y = 위. 수행자는 대략 +z 를 본다(골반 전방축이 +z 에서 중앙값 0°, p1~p99 −9~+12°).
  - 바닥 높이는 촬영일마다 원점이 달라(발목 y 중앙값 2.9~40.3 cm) 클립별로 정한다: 바닥 = 서 있는 프레임 발목 중점 y − 8 cm.
  - 'Foot' 은 발끝이 아니다: 발목에서 수평 6.4 cm 앞·4.6 cm 아래(p5~p95 3.7~8.1 cm) = 발등/중족 부근 점.
    → 발 방향(수평 Foot−Ankle)은 3D 진실로 쓰고, MediaPipe foot_index 에 해당하는 **가상 발끝**을
      발목 수평 위치 + 15 cm × (서 있는 프레임의 발 방향), 바닥 위 2 cm 에 강체 발로 만든다(뒤꿈치가 들리면 발끝 쪽으로 회전).
  - 신체 좌표계: 좌우 x_b = 좌골반−우골반 수평 성분(+ = 사용자 왼쪽), 위 y, 전방 z_b = x_b × y.

카메라
  - 핀홀, 세로 480×640 px, 주점 = 영상 중심, 왜곡 없음(초광각도 직선 투영으로 보정됐다고 가정).
  - 방위 az: 몸 정면 기준, + = 사용자 왼쪽(왼어깨가 카메라에 가까움), − = 오른쪽. 거리 d 는 발목 중점까지 수평 거리.
  - 높이 h 는 바닥 위 렌즈 높이. 요(yaw)는 몸 수직축을 향하고, 피치는 머리 꼭대기~바닥이 세로 중앙에 오게(각의 이등분).
  - 가장자리: 카메라 자체 세로축으로 돌려 몸 중심이 가로 30% 지점에 오게(직선 투영의 가장자리 늘어짐 포함).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.parquet as pq

HERE = Path(__file__).resolve().parent
AIHUB_OUT = HERE.parent / "aihub_fitness" / "outputs"
OUT = HERE / "outputs" / "A1"

JOINTS = ["Nose", "LEye", "REye", "LEar", "REar", "LShoulder", "RShoulder", "LElbow", "RElbow",
          "LWrist", "RWrist", "LHip", "RHip", "LKnee", "RKnee", "LAnkle", "RAnkle",
          "Neck", "LPalm", "RPalm", "Back", "Waist", "LFoot", "RFoot"]
J = {n: i for i, n in enumerate(JOINTS)}
# 가상 점 (투영 대상에 덧붙임)
J["LToe"], J["RToe"], J["HeadTop"] = 24, 25, 26
NP = 27

W, H = 480, 640
ANKLE_H = 0.08      # 발목 중심의 바닥 위 높이(가정) — 바닥 추정과 역투영 둘 다 이 값을 쓴다
TOE_H = 0.02        # 가상 발끝(foot_index 대응) 높이
TOE_LEN_H = 0.15    # 발목→발끝 수평 거리(가정, 성인 발 길이 ~25 cm 중 발목 앞부분)
HEAD_TOP = 0.12     # 귀 중점 위 머리 꼭대기 (프레이밍용)
UP = np.array([0.0, 1.0, 0.0])
S_TOL, B_TOL = 10.0, 15.0   # 서 있음 = 무릎각 ≥ 최대 − 10°, 바닥 구간 = ≤ 최소 + 15°
COND = {"knee": "발과 무릎의 방향 일치", "heel": "발바닥 지면 고정", "spine": "척추의 중립", "head": "고개 정면"}


# ------------------------------------------------------------------ 벡터 유틸
def nrm(v):
    return np.linalg.norm(v, axis=-1)


def unit(v):
    n = nrm(v)[..., None]
    return v / np.where(n < 1e-12, np.nan, n)


def horiz(v):
    h = np.array(v, dtype=float, copy=True)
    h[..., 1] = 0.0
    return h


def ang3(a, b, c):
    """b 꼭짓점 각(도). 2D/3D 공용."""
    u, w = unit(a - b), unit(c - b)
    return np.degrees(np.arccos(np.clip((u * w).sum(-1), -1.0, 1.0)))


def cross2(a, b):
    return a[..., 0] * b[..., 1] - a[..., 1] * b[..., 0]


def mmean(x, mask):
    """마스크된 프레임 평균 (N,T)->(N,)."""
    with np.errstate(invalid="ignore"):
        y = np.where(mask, x, np.nan)
        cnt = np.isfinite(y).sum(1)
        s = np.nansum(y, 1)
        return np.where(cnt > 0, s / np.maximum(cnt, 1), np.nan)


def mmean_vec(x, mask):
    """(N,T,3) 마스크 평균 -> (N,3)."""
    y = np.where(mask[..., None], x, np.nan)
    cnt = np.isfinite(y[..., 0]).sum(1)
    s = np.nansum(y, 1)
    return np.where((cnt > 0)[:, None], s / np.maximum(cnt, 1)[:, None], np.nan)


# ------------------------------------------------------------------ 데이터
def load_squat(min_ok: int = 8):
    clips = pd.read_parquet(AIHUB_OUT / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key", "day"])
    sq = clips[clips.exercise == "바벨 스쿼트"].reset_index(drop=True)
    ids = sq.clip_id.tolist()
    kp = pq.read_table(AIHUB_OUT / "kp3d.parquet", filters=[("clip_id", "in", ids)]).to_pandas()
    ok = pd.read_parquet(AIHUB_OUT / "kp3d_frame_ok.parquet")
    kp = kp.merge(ok[["clip_id", "frame_idx", "ok"]], on=["clip_id", "frame_idx"], how="left")
    T = int(kp.frame_idx.max()) + 1
    arr = np.full((len(ids), T, 24, 3), np.nan)
    code = pd.Categorical(kp.clip_id, categories=ids).codes
    cols = [f"{j}_{a}" for j in JOINTS for a in "xyz"]
    vals = kp[cols].to_numpy(dtype=float).reshape(-1, 24, 3) / 100.0
    good = kp.ok.fillna(False).to_numpy(dtype=bool)
    arr[code[good], kp.frame_idx.to_numpy()[good]] = vals[good]
    n_ok = np.isfinite(arr[..., 0, 0]).sum(1)
    keep = n_ok >= min_ok
    co = pd.read_parquet(AIHUB_OUT / "conditions.parquet")
    co = co[co.clip_id.isin(ids)].pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first").reindex(ids)
    viol = {k: ~co[v].astype(bool).to_numpy()[keep] for k, v in COND.items()}
    meta = sq[keep].reset_index(drop=True)
    return dict(ids=[i for i, k in zip(ids, keep) if k], arr=arr[keep], meta=meta, viol=viol,
                n_total=len(ids), n_frames_ok=int(n_ok[keep].sum()))


# ------------------------------------------------------------------ 장면(클립별 기준) + 3D 진실
@dataclass
class Scene:
    P: np.ndarray        # (N,T,27,3) 관절 + 가상 발끝·머리꼭대기 (m)
    S: np.ndarray        # (N,T) 서 있음 프레임
    B: np.ndarray        # (N,T) 바닥 구간 프레임
    valid: np.ndarray    # (N,T) QC 통과 프레임
    floor: np.ndarray    # (N,)
    center: np.ndarray   # (N,3) 서 있을 때 발목 중점(바닥 높이)
    fwd: np.ndarray      # (N,3) 몸 정면(서 있을 때 골반+어깨)
    left: np.ndarray     # (N,3)
    top: np.ndarray      # (N,) 머리 꼭대기 최고 높이 y
    fdir: dict           # side -> (N,3) 서 있을 때 발 방향(수평 단위)
    km: np.ndarray       # (N,T) 3D 무릎각 평균
    events: np.ndarray   # (E,3) hip-first 사건 (clip, bottom t, next t)


def build_scene(arr: np.ndarray) -> Scene:
    g = lambda n: arr[:, :, J[n]]
    N, T = arr.shape[:2]
    valid = np.isfinite(arr[..., 0, 0])
    kL = ang3(g("LHip"), g("LKnee"), g("LAnkle"))
    kR = ang3(g("RHip"), g("RKnee"), g("RAnkle"))
    km = (kL + kR) / 2
    kmax, kmin = np.nanmax(km, 1), np.nanmin(km, 1)
    with np.errstate(invalid="ignore"):
        S = valid & (km >= (kmax - S_TOL)[:, None])
        B = valid & (km <= (kmin + B_TOL)[:, None])
    an_mid = (g("LAnkle") + g("RAnkle")) / 2
    floor = mmean(an_mid[..., 1], S) - ANKLE_H
    center = mmean_vec(an_mid, S)
    center[:, 1] = floor
    lat = mmean_vec(g("LHip") - g("RHip"), S) + mmean_vec(g("LShoulder") - g("RShoulder"), S)
    left = unit(horiz(lat))
    fwd = unit(np.cross(left, UP))
    ear_mid = (g("LEar") + g("REar")) / 2
    head = ear_mid + HEAD_TOP * UP
    top = np.nanmax(np.fmax(head[..., 1], np.nanmax(arr[..., 1], axis=2)), axis=1)
    fdir, toes = {}, {}
    for s in "LR":
        fd = unit(horiz(mmean_vec(g(s + "Foot") - g(s + "Ankle"), S)))
        fdir[s] = fd
        an = g(s + "Ankle")
        a_h = mmean(an[..., 1], S) - floor                         # 이 쪽 발목의 서 있을 때 높이
        Lf = np.hypot(TOE_LEN_H, a_h - TOE_H)                      # 강체 발: 발목–발끝 거리
        dy = an[..., 1] - (floor + TOE_H)[:, None]
        hd = np.sqrt(np.clip(Lf[:, None] ** 2 - dy ** 2, 0.0, None))
        toe = an.copy()
        toe[..., 1] = (floor + TOE_H)[:, None]
        toe = toe + hd[..., None] * fd[:, None, :]
        toes[s] = toe
    P = np.concatenate([arr, toes["L"][:, :, None], toes["R"][:, :, None], head[:, :, None]], axis=2)
    # hip-first 사건: 바닥(국소 최소, 최대−50° 아래) 다음 프레임이 부분 상승(골반 상승이 전체의 10~80%)
    hip_y = ((g("LHip") + g("RHip")) / 2)[..., 1]
    hipS = mmean(hip_y, S)
    ev = []
    for i in range(N):
        k = km[i]
        for t in range(1, T - 1):
            if not (np.isfinite(k[t - 1]) and np.isfinite(k[t]) and np.isfinite(k[t + 1])):
                continue
            if k[t] <= k[t - 1] and k[t] < k[t + 1] and k[t] < kmax[i] - 50:
                rise = (hip_y[i, t + 1] - hip_y[i, t]) / (hipS[i] - hip_y[i, t])
                if 0.10 <= rise <= 0.80:
                    ev.append((i, t, t + 1))
    return Scene(P=P, S=S, B=B, valid=valid, floor=floor, center=center, fwd=fwd, left=left, top=top,
                 fdir=fdir, km=km, events=np.array(ev, dtype=int).reshape(-1, 3))


def truth(sc: Scene) -> dict:
    """3D 진실 — 반복(클립) 통계와 프레임 신호."""
    P = sc.P
    g = lambda n: P[:, :, J[n]]
    S, B = sc.S, sc.B
    out, frame = {}, {}
    hip = (g("LHip") + g("RHip")) / 2
    sh = (g("LShoulder") + g("RShoulder")) / 2
    an = (g("LAnkle") + g("RAnkle")) / 2
    # R_hip_drop
    hS, aS = mmean(hip[..., 1], S), mmean(an[..., 1], S)
    frame["R_hip_drop"] = (hS[:, None] - hip[..., 1]) / (hS - aS)[:, None]
    # R_depth: 허벅지(고관절→무릎)가 수평면과 이루는 각, + = 무릎이 고관절보다 아래
    th = []
    for s in "LR":
        v = g(s + "Knee") - g(s + "Hip")
        th.append(np.degrees(np.arcsin(np.clip(-v[..., 1] / nrm(v), -1, 1))))
    frame["R_depth"] = (th[0] + th[1]) / 2
    kL = ang3(g("LHip"), g("LKnee"), g("LAnkle"))
    kR = ang3(g("RHip"), g("RKnee"), g("RAnkle"))
    frame["R_knee_min"] = 180.0 - np.fmax(kL, kR)
    for k in ("R_hip_drop", "R_depth", "R_knee_min"):
        out[k] = mmean(frame[k], B)
    # R_foot_drift: 반복 중 발목 이동의 최대 ÷ 어깨 너비 (3D)
    shW = mmean(nrm(horiz(g("LShoulder") - g("RShoulder"))), S)
    legS = mmean((nrm(g("LHip") - g("LAnkle")) + nrm(g("RHip") - g("RAnkle"))) / 2, S)
    dr = []
    for s in "LR":
        a = g(s + "Ankle")
        dr.append(nrm(a - mmean_vec(a, S)[:, None, :]))
    drift = np.nanmax(np.fmax(dr[0], dr[1]), axis=1)
    out["R_foot_drift"] = drift / shW
    # F_knee_foot: 바닥 구간 (무릎−발목) 수평 벡터가 발 방향(서 있을 때)에 대해 돌아간 각, 바깥 +
    kf = []
    for s, sg in (("L", 1.0), ("R", -1.0)):
        a = sc.fdir[s][:, None, :]
        b = horiz(g(s + "Knee") - g(s + "Ankle"))
        cr = a[..., 2] * b[..., 0] - a[..., 0] * b[..., 2]
        val = sg * np.degrees(np.arctan2(cr, (a * b).sum(-1)))
        kf.append(np.where(nrm(b) > 0.08, val, np.nan))
    with np.errstate(invalid="ignore"):
        frame["F_knee_foot"] = np.nanmean(np.stack(kf), 0)
    out["F_knee_foot"] = mmean(frame["F_knee_foot"], B)
    # 참고: 3D knee_out (앱·AIHub 규칙 피처, 신체 좌표계 수평 이탈 ÷ 다리 길이)
    xb = unit(horiz(g("LHip") - g("RHip")))
    fw = unit(np.cross(xb, UP))
    ko = []
    for s, sg in (("L", 1.0), ("R", -1.0)):
        Hp, Kn, An = g(s + "Hip"), g(s + "Knee"), g(s + "Ankle")
        bx = lambda p: ((p - hip) * xb).sum(-1)
        by = lambda p: ((p - hip) * UP).sum(-1)
        t = (by(Kn) - by(An)) / (by(Hp) - by(An))
        ex = bx(An) + t * (bx(Hp) - bx(An))
        ko.append(sg * (bx(Kn) - ex) / nrm(Hp - An))
    out["knee_out3d"] = mmean((ko[0] + ko[1]) / 2, B)
    # F_heel: 바닥 구간 발목 높이 − 서 있을 때 (cm), 양쪽 평균
    dh = []
    for s in "LR":
        ay = g(s + "Ankle")[..., 1]
        dh.append(mmean(ay, B) - mmean(ay, S))
    out["F_heel"] = 100.0 * (dh[0] + dh[1]) / 2
    # F_spine: Waist→Back 과 Back→Neck 사이 시상면 부호각(+ 굴곡), 바닥 − 서 있음
    v1, v2 = g("Back") - g("Waist"), g("Neck") - g("Back")
    kap = np.degrees(np.arctan2((np.cross(v1, v2) * xb).sum(-1), (v1 * v2).sum(-1)))
    out["F_spine"] = mmean(kap, B) - mmean(kap, S)
    # T_lean: 골반→어깨 벡터와 수직축의 각
    lean = ang3(sh, hip, hip + UP)
    frame["T_lean"] = lean
    out["T_lean"] = mmean(lean, B)
    # F_hip_first: 사건 단위
    ev = sc.events
    if len(ev):
        i, tb, ta = ev[:, 0], ev[:, 1], ev[:, 2]
        out["F_hip_first_dlean"] = lean[i, ta] - lean[i, tb]
        out["F_hip_first_ratio"] = (sh[i, ta, 1] - sh[i, tb, 1]) / (hip[i, ta, 1] - hip[i, tb, 1])
    # F_lateral (바닥 구간)
    po = (g("LHip")[..., 1] - g("RHip")[..., 1]) / nrm(g("LHip") - g("RHip"))
    out["F_lat_pelvis_tilt"] = mmean(po, B)
    e3 = unit(horiz(g("LAnkle") - g("RAnkle")))
    ls = ((hip - an) * e3).sum(-1) / nrm(horiz(g("LAnkle") - g("RAnkle")))
    out["F_lat_shift"] = mmean(ls, B)
    out["F_lat_knee_asym"] = mmean(kL - kR, B)
    # S_toe: 서 있을 때 발끝 각(골반 정면 기준, 바깥 +), 양쪽 평균
    to = []
    for s, sg in (("L", 1.0), ("R", -1.0)):
        d = horiz(g(s + "Foot") - g(s + "Ankle"))
        to.append(sg * np.degrees(np.arctan2((d * xb).sum(-1), (d * fw).sum(-1))))
    out["S_toe"] = mmean((to[0] + to[1]) / 2, S)
    # S_stance: 발목 수평 간격 ÷ 어깨 수평 간격
    out["S_stance"] = mmean(nrm(horiz(g("LAnkle") - g("RAnkle"))) / nrm(horiz(g("LShoulder") - g("RShoulder"))), S)
    out["_legS"] = legS
    out["_shW"] = shW
    return dict(rep=out, frame=frame)


# ------------------------------------------------------------------ 카메라
@dataclass
class Cam:
    C: np.ndarray   # (N,3)
    x: np.ndarray   # (N,3) 영상 오른쪽
    y: np.ndarray   # (N,3) 영상 아래
    z: np.ndarray   # (N,3) 광축
    f: float
    floor_belief: np.ndarray | None = None   # 추정기가 믿는 바닥 y (= 렌즈 y − 믿는 높이)


def focal(vfov_deg: float) -> float:
    return (H / 2) / math.tan(math.radians(vfov_deg) / 2)


def make_cam(sc: Scene, az: float, h: float, d: float, vfov: float, edge: bool,
             pitch_err: float = 0.0, height_scale: float = 1.0) -> Cam:
    """pitch_err/height_scale 은 '추정기가 믿는' 카메라를 만들 때만 쓴다(실제 투영은 0/1)."""
    f = focal(vfov)
    th = math.radians(az)
    udir = math.cos(th) * sc.fwd + math.sin(th) * sc.left
    C = sc.center + d * udir
    C[:, 1] = sc.floor + h
    a_top = np.arctan2(sc.top - C[:, 1], d)
    a_bot = np.arctan2(sc.floor - C[:, 1], d)
    phi = (a_top + a_bot) / 2 + math.radians(pitch_err)
    z = np.cos(phi)[:, None] * (-udir) + np.sin(phi)[:, None] * UP
    x = unit(np.cross(z, UP))
    y = np.cross(z, x)
    if edge:
        dl = -math.atan2(0.2 * W, f)
        x, z = math.cos(dl) * x + math.sin(dl) * z, -math.sin(dl) * x + math.cos(dl) * z
    return Cam(C=C, x=x, y=y, z=z, f=f, floor_belief=C[:, 1] - h * height_scale)


def project(cam: Cam, P: np.ndarray):
    rel = P - cam.C[:, None, None, :]
    X = (rel * cam.x[:, None, None, :]).sum(-1)
    Y = (rel * cam.y[:, None, None, :]).sum(-1)
    Z = (rel * cam.z[:, None, None, :]).sum(-1)
    uv = np.stack([W / 2 + cam.f * X / Z, H / 2 + cam.f * Y / Z], -1)
    return uv, Z


def rays(cam: Cam, uv: np.ndarray):
    a = (uv[..., 0] - W / 2) / cam.f
    b = (uv[..., 1] - H / 2) / cam.f
    sh = (slice(None),) + (None,) * (uv.ndim - 2) + (slice(None),)
    r = a[..., None] * cam.x[sh] + b[..., None] * cam.y[sh] + cam.z[sh]
    return unit(r)


def level_cam(cam: Cam) -> Cam:
    """같은 위치·같은 요의 수평(피치 0·롤 0) 가상 카메라."""
    zh = unit(horiz(cam.z))
    x = unit(np.cross(zh, UP))
    return Cam(C=cam.C, x=x, y=np.cross(zh, x), z=zh, f=cam.f, floor_belief=cam.floor_belief)


def rectify(cam: Cam, cam_lvl: Cam, uv: np.ndarray):
    """IMU 로 아는 기울기만큼 영상을 되돌린다(순수 회전 호모그래피) — 카메라 높이·위치는 그대로."""
    r = rays(cam, uv)
    sh = (slice(None),) + (None,) * (uv.ndim - 2) + (slice(None),)
    X = (r * cam_lvl.x[sh]).sum(-1)
    Y = (r * cam_lvl.y[sh]).sum(-1)
    Z = (r * cam_lvl.z[sh]).sum(-1)
    return np.stack([W / 2 + cam.f * X / Z, H / 2 + cam.f * Y / Z], -1)


def up_img(cam: Cam, uv: np.ndarray):
    """IMU(중력) 을 알 때 영상 점 uv 에서의 '위' 방향 단위벡터 (수직 소실점 방향)."""
    sh = (slice(None),) + (None,) * (uv.ndim - 2)
    Xv, Yv, Zv = cam.x[:, 1][sh], cam.y[:, 1][sh], cam.z[:, 1][sh]
    du = cam.f * Xv - (uv[..., 0] - W / 2) * Zv
    dv = cam.f * Yv - (uv[..., 1] - H / 2) * Zv
    return unit(np.stack([du, dv], -1))


# ------------------------------------------------------------------ 2D 후보
def feats2d(sc: Scene, uv: np.ndarray, cam: Cam, k_foot: float) -> dict:
    """투영(+잡음) 2D 좌표 → 반복 통계 후보 {변수: {후보: (N,) 또는 (E,)}} 와 프레임 신호."""
    S, B = sc.S, sc.B
    p = lambda n: uv[:, :, J[n]]
    hipL, hipR, knL, knR = p("LHip"), p("RHip"), p("LKnee"), p("RKnee")
    anL, anR, shL, shR = p("LAnkle"), p("RAnkle"), p("LShoulder"), p("RShoulder")
    toL, toR = p("LToe"), p("RToe")
    hip2, sh2, an2 = (hipL + hipR) / 2, (shL + shR) / 2, (anL + anR) / 2
    side = {"L": (hipL, knL, anL, toL, hipL - hipR), "R": (hipR, knR, anR, toR, hipR - hipL)}
    rep, frame = {}, {}

    # --- R_hip_drop (영상 세로)
    vhS, vaS = mmean(hip2[..., 1], S), mmean(an2[..., 1], S)
    frame["R_hip_drop"] = {"img_v": (hip2[..., 1] - vhS[:, None]) / (vaS - vhS)[:, None]}
    # --- R_depth
    th_img, th_rat = [], []
    for s in "LR":
        Hp, Kn = side[s][0], side[s][1]
        dv = Kn[..., 1] - Hp[..., 1]
        th_img.append(np.degrees(np.arctan2(dv, np.abs(Kn[..., 0] - Hp[..., 0]))))
        Lth = mmean(nrm(Kn - Hp), S)
        th_rat.append(np.degrees(np.arcsin(np.clip(dv / Lth[:, None], -1, 1))))
    frame["R_depth"] = {"img_angle": (th_img[0] + th_img[1]) / 2, "v_ratio": (th_rat[0] + th_rat[1]) / 2}
    # --- R_knee_min
    aL, aR = ang3(hipL, knL, anL), ang3(hipR, knR, anR)
    # 제안 후보 recon2d(정면용 단축 복원): 서 있을 때 영상 허벅지·정강이 길이를 3D 길이의 대리로 두고
    #   세로 성분이 줄어든 만큼 앞으로 기울었다고 본다 → 굽힘 = acos(허벅지 세로/길이) + acos(정강이 세로/길이)
    flex, shin_ang = {}, {}
    for s in "LR":
        Hp, Kn, An, To, lat = side[s]
        Lt, Ls = mmean(nrm(Kn - Hp), S)[:, None], mmean(nrm(Kn - An), S)[:, None]
        dvt, dvs = Kn[..., 1] - Hp[..., 1], An[..., 1] - Kn[..., 1]
        flex[s] = np.degrees(np.arccos(np.clip(dvt / Lt, -1, 1))) + np.degrees(np.arccos(np.clip(dvs / Ls, -1, 1)))
        dx = ((Kn - An) * unit(lat)).sum(-1)                       # 무릎의 바깥쪽 영상 이탈(발목 기준)
        dz = np.sqrt(np.clip(Ls ** 2 - dvs ** 2 - dx ** 2, 0.0, None))  # 보이지 않는 앞쪽 성분 = 남는 길이
        shin_ang[s] = np.where(dz > 0.25 * Ls, np.degrees(np.arctan2(dx, dz)), np.nan)
    frame["R_knee_min"] = {"img_angle": 180.0 - np.fmax(aL, aR), "recon2d": np.fmin(flex["L"], flex["R"])}
    for k in ("R_hip_drop", "R_depth", "R_knee_min"):
        rep[k] = {c: mmean(v, B) for c, v in frame[k].items()}
    # --- R_foot_drift
    shW2 = mmean(nrm(shL - shR), S)
    leg2 = mmean((nrm(hipL - anL) + nrm(hipR - anR)) / 2, S)
    leg_s = {"L": mmean(nrm(hipL - anL), S), "R": mmean(nrm(hipR - anR), S)}
    dr = [nrm(a - mmean_vec(a, S)[:, None, :]) for a in (anL, anR)]
    drift = np.nanmax(np.fmax(dr[0], dr[1]), axis=1)
    rep["R_foot_drift"] = {"sh_norm": drift / shW2, "leg_norm": drift / leg2}
    # --- F_knee_foot
    ko, fp, tr = [], [], []
    for s in "LR":
        Hp, Kn, An, To, lat = side[s]
        e = unit(lat)
        ax = Hp - An
        t = ((Kn - An) * ax).sum(-1) / (ax * ax).sum(-1)
        perp = Kn - (An + t[..., None] * ax)
        off = (perp * e).sum(-1)
        ko.append(off / nrm(ax))
        fp.append(np.sign(off) * (180.0 - ang3(Hp, Kn, An)))
        den = ((To - An) * e).sum(-1)
        r = ((Kn - An) * e).sum(-1) / np.where(np.abs(den) < 1e-6, np.nan, den)
        tr.append(np.clip(r, -10, 10))
    ko_m = (ko[0] + ko[1]) / 2
    kasr = nrm(knL - knR) / nrm(anL - anR)
    # 제안 후보 recon2d: 정강이 수평 방향(바깥 이탈 ÷ 단축으로 추정한 앞쪽 성분) − 발끝 각(체형 비례, 서 있을 때)
    kfr = []
    for s in "LR":
        An, To, lat = side[s][2], side[s][3], side[s][4]
        phi = np.degrees(np.arcsin(np.clip(((To - An) * unit(lat)).sum(-1) / (k_foot * leg_s[s])[:, None], -1, 1)))
        kfr.append(shin_ang[s] - mmean(phi, S)[:, None])
    with np.errstate(invalid="ignore"):
        kfr_m = np.nanmean(np.stack(kfr), 0)
    rep["F_knee_foot"] = {"fppa": mmean((fp[0] + fp[1]) / 2, B), "knee_out2d": mmean(ko_m, B), "kasr": mmean(kasr, B),
                          "toe_ratio": mmean((tr[0] + tr[1]) / 2, B), "d_knee_out2d": mmean(ko_m, B) - mmean(ko_m, S),
                          "recon2d": mmean(kfr_m, B)}
    # --- F_heel (서 있을 때 대비 발목 영상 상승 ÷ 영상 다리 길이)
    ar, tr2 = [], []
    for s in "LR":
        An, To = side[s][2], side[s][3]
        ar.append((mmean(An[..., 1], S) - mmean(An[..., 1], B)) / leg2)
        rel = To[..., 1] - An[..., 1]
        tr2.append((mmean(rel, B) - mmean(rel, S)) / leg2)
    rep["F_heel"] = {"ankle_rise": (ar[0] + ar[1]) / 2, "toe_rel": (tr2[0] + tr2[1]) / 2}
    # --- F_spine: 영상 곡률 (시상면 방향 부호는 정면 방향의 영상 투영으로 정렬 — 실제로는 발끝 방향으로 안다)
    wa, bk, nk = p("Waist"), p("Back"), p("Neck")
    v1, v2 = bk - wa, nk - bk
    f3 = sc.P[:, :, J["Waist"]] + 0.3 * sc.fwd[:, None, :]
    fu, _ = project(cam, f3[:, :, None, :])
    f2 = fu[:, :, 0] - wa
    sgn = np.sign(cross2(v1, f2))
    kap2 = sgn * np.degrees(np.arctan2(cross2(v1, v2), (v1 * v2).sum(-1)))
    rep["F_spine"] = {"img_curv": mmean(kap2, B) - mmean(kap2, S)}
    # --- T_lean
    tv = sh2 - hip2
    lean_img = np.degrees(np.arctan2(np.abs(tv[..., 0]), -tv[..., 1]))
    upi = up_img(cam, hip2)
    lean_imu = np.degrees(np.arccos(np.clip((unit(tv) * upi).sum(-1), -1, 1)))
    LtS = mmean(nrm(tv), S)
    lean_fs = np.degrees(np.arccos(np.clip(-tv[..., 1] / LtS[:, None], -1, 1)))
    frame["T_lean"] = {"img_angle": lean_img, "img_angle_imu": lean_imu, "foreshort": lean_fs}
    rep["T_lean"] = {c: mmean(v, B) for c, v in frame["T_lean"].items()}
    # --- F_hip_first (사건)
    ev = sc.events
    if len(ev):
        i, tb, ta = ev[:, 0], ev[:, 1], ev[:, 2]
        rep["F_hip_first_dlean"] = {c: v[i, ta] - v[i, tb] for c, v in frame["T_lean"].items()}
        rep["F_hip_first_ratio"] = {"img_v": (sh2[i, tb, 1] - sh2[i, ta, 1]) / (hip2[i, tb, 1] - hip2[i, ta, 1])}
    # --- F_lateral
    rep["F_lat_pelvis_tilt"] = {"img": mmean((hipR[..., 1] - hipL[..., 1]) / nrm(hipL - hipR), B)}
    e = unit(anL - anR)
    rep["F_lat_shift"] = {"img": mmean(((hip2 - an2) * e).sum(-1) / nrm(anL - anR), B)}
    # 무릎 굽힘 차(왼−오른, 무릎각 기준): 영상각 / 정면 단축 복원(굽힘 차의 부호를 무릎각 차로 바꿔 씀)
    rep["F_lat_knee_asym"] = {"img": mmean(aL - aR, B), "recon2d": mmean(flex["R"] - flex["L"], B)}
    # --- S_toe
    ti, tp = [], []
    for s in "LR":
        An, To, lat = side[s][2], side[s][3], side[s][4]
        w = To - An
        e = unit(lat)
        ti.append(np.degrees(np.arctan2((w * e).sum(-1), w[..., 1])))
        tp.append(np.degrees(np.arcsin(np.clip((w * e).sum(-1) / (k_foot * leg2)[:, None], -1, 1))))
    rep["S_toe"] = {"img_angle": mmean((ti[0] + ti[1]) / 2, S), "body_prop": mmean((tp[0] + tp[1]) / 2, S)}
    # --- S_stance
    rep["S_stance"] = {"x_ratio": mmean(np.abs(anL[..., 0] - anR[..., 0]) / np.abs(shL[..., 0] - shR[..., 0]), S),
                       "euclid": mmean(nrm(anL - anR) / nrm(shL - shR), S)}
    return dict(rep=rep, frame=frame)


# ------------------------------------------------------------------ 지면 역투영 리프팅 (IMU 피치 + 카메라 높이를 안다고 가정)
def _plane(C, r, y0):
    """광선 C + t r 과 수평면 y = y0 의 교점 (t>0 만)."""
    with np.errstate(divide="ignore", invalid="ignore"):
        t = (y0 - C[..., 1]) / r[..., 1]
    t = np.where(t > 0, t, np.nan)
    return C + t[..., None] * r


def _vline(C, r, A):
    """광선 위에서 A 를 지나는 수직선에 가장 가까운 점."""
    w0 = A - C
    b = r[..., 1]
    d = w0[..., 1]
    e = (r * w0).sum(-1)
    den = 1.0 - b * b
    with np.errstate(divide="ignore", invalid="ignore"):
        t = (e - b * d) / den
    return C + t[..., None] * r


def _sphere(C, r, Q, rad, pref, sign):
    """광선과 구(중심 Q, 반지름 rad)의 교점 중 (P−Q)·pref 에 sign 을 곱한 값이 큰 쪽. 교점이 없으면 최근접점."""
    w = C - Q
    b = (r * w).sum(-1)
    c = (w * w).sum(-1) - rad ** 2
    disc = np.sqrt(np.clip(b * b - c, 0.0, None))
    P1 = C + (-b - disc)[..., None] * r
    P2 = C + (-b + disc)[..., None] * r
    s1 = sign * ((P1 - Q) * pref).sum(-1)
    s2 = sign * ((P2 - Q) * pref).sum(-1)
    return np.where((s1 >= s2)[..., None], P1, P2)


def lift_gpl(sc: Scene, uv: np.ndarray, cam_est: Cam) -> dict:
    """IMU 피치·롤 + 카메라 높이를 알 때: 발목을 바닥 위 8 cm 평면에 역투영하고,
    서 있는 자세(정강이·허벅지·몸통이 발목 위 수직선에 있다고 가정)로 뼈 길이를 잰 뒤,
    발목→무릎→고관절→어깨 순서로 광선–구 교점을 풀어 3D 를 복원한다(해부학 사전분포로 두 해 중 선택)."""
    S, B = sc.S, sc.B
    N, T = S.shape
    Cf = np.broadcast_to(cam_est.C[:, None, :], (N, T, 3))
    R = rays(cam_est, uv)
    r = lambda n: R[:, :, J[n]]
    fb = cam_est.floor_belief            # 추정기가 믿는 바닥 = 렌즈 높이 − 믿는 카메라 높이
    A = {s: _plane(Cf, r(s + "Ankle"), (fb + ANKLE_H)[:, None]) for s in "LR"}
    Tt = {s: _plane(Cf, r(s + "Toe"), (fb + TOE_H)[:, None]) for s in "LR"}
    latv = mmean_vec(horiz(A["L"] - A["R"]), S)
    lat = unit(latv)
    fwd = unit(np.cross(lat, UP))
    pref = np.broadcast_to(fwd[:, None, :], (N, T, 3))
    # 서 있는 자세 보정
    K0 = {s: _vline(Cf, r(s + "Knee"), A[s]) for s in "LR"}
    H0 = {s: _vline(Cf, r(s + "Hip"), A[s]) for s in "LR"}
    shin = {s: mmean(nrm(K0[s] - A[s]), S) for s in "LR"}
    thigh = {s: mmean(nrm(H0[s] - K0[s]), S) for s in "LR"}
    Am0 = (A["L"] + A["R"]) / 2
    uvm = (uv[:, :, J["LShoulder"]] + uv[:, :, J["RShoulder"]]) / 2
    rsh = rays(cam_est, uvm[:, :, None, :])[:, :, 0]
    Sh0 = _vline(Cf, rsh, Am0)
    Hm0 = (H0["L"] + H0["R"]) / 2
    torso = mmean(nrm(Sh0 - Hm0), S)
    # 어깨 너비: 두 어깨를 서 있을 때 어깨 높이 평면에 역투영
    shL0 = _plane(Cf, r("LShoulder"), Sh0[..., 1])
    shR0 = _plane(Cf, r("RShoulder"), Sh0[..., 1])
    # 프레임별 체인
    K = {s: _sphere(Cf, r(s + "Knee"), A[s], shin[s][:, None], pref, +1.0) for s in "LR"}
    Hh = {s: _sphere(Cf, r(s + "Hip"), K[s], thigh[s][:, None], pref, -1.0) for s in "LR"}
    Hm = (Hh["L"] + Hh["R"]) / 2
    Sh = _sphere(Cf, rsh, Hm, torso[:, None], pref, +1.0)
    Am = (A["L"] + A["R"]) / 2
    out, frame = {}, {}
    hS, aS = mmean(Hm[..., 1], S), mmean(Am[..., 1], S)
    frame["R_hip_drop"] = (hS[:, None] - Hm[..., 1]) / (hS - aS)[:, None]
    th = [np.degrees(np.arcsin(np.clip(-(K[s] - Hh[s])[..., 1] / nrm(K[s] - Hh[s]), -1, 1))) for s in "LR"]
    frame["R_depth"] = (th[0] + th[1]) / 2
    kLg, kRg = ang3(Hh["L"], K["L"], A["L"]), ang3(Hh["R"], K["R"], A["R"])
    frame["R_knee_min"] = 180.0 - np.fmax(kLg, kRg)
    for k in ("R_hip_drop", "R_depth", "R_knee_min"):
        out[k] = mmean(frame[k], B)
    shW = mmean(nrm(horiz(shL0 - shR0)), S)
    dr = [nrm(A[s] - mmean_vec(A[s], S)[:, None, :]) for s in "LR"]
    out["R_foot_drift"] = np.nanmax(np.fmax(dr[0], dr[1]), axis=1) / shW
    # 발 방향(서 있을 때 역투영 발끝−발목)
    fd = {s: unit(horiz(mmean_vec(Tt[s] - A[s], S))) for s in "LR"}
    kf, to = [], []
    for s, sg in (("L", 1.0), ("R", -1.0)):
        a = fd[s][:, None, :]
        b = horiz(K[s] - A[s])
        cr = a[..., 2] * b[..., 0] - a[..., 0] * b[..., 2]
        kf.append(sg * np.degrees(np.arctan2(cr, (a * b).sum(-1))))
        to.append(sg * np.degrees(np.arctan2((fd[s] * lat).sum(-1), (fd[s] * fwd).sum(-1))))
    out["F_knee_foot"] = mmean((kf[0] + kf[1]) / 2, B)
    out["S_toe"] = (to[0] + to[1]) / 2
    # F_heel: 발끝 고정(바닥 역투영) + 발 길이 구로 발목 높이
    dh = []
    for s in "LR":
        Lf = mmean(nrm(Tt[s] - A[s]), S)
        A2 = _sphere(Cf, r(s + "Ankle"), Tt[s], Lf[:, None], pref, -1.0)
        dh.append(mmean(A2[..., 1], B) - mmean(A2[..., 1], S))
    out["F_heel"] = 100.0 * (dh[0] + dh[1]) / 2
    lean = ang3(Sh, Hm, Hm + UP)
    out["T_lean"] = mmean(lean, B)
    ev = sc.events
    if len(ev):
        i, tb, ta = ev[:, 0], ev[:, 1], ev[:, 2]
        out["F_hip_first_dlean"] = lean[i, ta] - lean[i, tb]
        out["F_hip_first_ratio"] = (Sh[i, ta, 1] - Sh[i, tb, 1]) / (Hm[i, ta, 1] - Hm[i, tb, 1])
    out["F_lat_pelvis_tilt"] = mmean((Hh["L"][..., 1] - Hh["R"][..., 1]) / nrm(Hh["L"] - Hh["R"]), B)
    e3 = unit(horiz(A["L"] - A["R"]))
    out["F_lat_shift"] = mmean(((Hm - Am) * e3).sum(-1) / nrm(horiz(A["L"] - A["R"])), B)
    out["F_lat_knee_asym"] = mmean(kLg - kRg, B)
    out["S_stance"] = mmean(nrm(horiz(A["L"] - A["R"])), S) / shW
    return dict(rep=out, frame=frame)
