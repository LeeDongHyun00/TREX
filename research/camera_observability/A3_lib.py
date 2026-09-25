# -*- coding: utf-8 -*-
"""A3 공용 — 30 fps 캡처 읽기, 프레임 변수, 반복 창, 국면 분할, 반복별 통계(모든 샘플링에 같은 알고리즘).

원칙
    * 기준값(30 fps)과 서브샘플은 **같은 알고리즘**으로 통계를 낸다 — 차이는 샘플링만의 몫이다.
    * 국면은 각 스트림이 **자기 샘플로** 나눈다(앱의 RepFormEvaluator 처럼 사이클 안에서 스스로 바닥을 찾는다).
      단, 블록(연속 반복 묶음)의 서 있음 기준(골반 높이·다리 길이·어깨 폭 등 정규화 분모)은 30 fps 국면 라벨로 고른
      그 스트림의 서 있는 샘플 중앙값이다 — 앱의 '시작 자세' 에 해당하는 느린 기준이라 샘플링 영향이 작다.
    * 랜드마크는 앱과 같이 min(visibility, presence) ≥ 0.5 일 때만 쓴다.
좌표
    이미지: 분석 해상도(긴 변 640) 픽셀, y 아래 +.   월드: 앱 규약(cm, y 위 +, z 카메라 쪽 +), up = (0,1,0)(IMU 없음 = 앱 폴백).
"""
from __future__ import annotations

import csv
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CAP = HERE / "outputs" / "A3" / "cap30"
OUT = HERE / "outputs" / "A3"
MIN_VIS = 0.5
FPS = 30.0

NOSE, LSH, RSH, LHIP, RHIP, LKNE, RKNE, LANK, RANK, LHEEL, RHEEL, LFOOT, RFOOT = 0, 11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
SIDES = (("L", LHIP, LKNE, LANK, LHEEL, LFOOT, 1.0), ("R", RHIP, RKNE, RANK, RHEEL, RFOOT, -1.0))
DEG = 180.0 / np.pi
UP = np.array([0.0, 1.0, 0.0])


# ----------------------------------------------------------------------------------------------- 읽기
@dataclass
class Stream:
    frames: np.ndarray          # 영상 프레임 번호
    img: np.ndarray             # (N,33,4) 정규화 x,y,vis,pres
    wld: np.ndarray             # (N,33,3) MediaPipe 월드(m)
    det: np.ndarray             # (N,)


@dataclass
class Capture:
    name: str
    meta: dict
    wa: int
    ha: int
    f30: Stream
    cad: dict = field(default_factory=dict)   # {interval_ms(int): Stream}  진짜 앱 주기(위상 0)


def load_capture(name: str) -> Capture:
    z = np.load(CAP / f"{name}.npz")
    meta = json.loads(str(z["meta"]))
    s = 640.0 / max(meta["width"], meta["height"])
    wa, ha = int(round(meta["width"] * s)), int(round(meta["height"] * s))
    f30 = Stream(z["frames"], z["img"], z["wld"], z["det"])
    cad = {}
    for k in z.files:
        if k.startswith("c") and k.endswith("_frames"):
            key = k[:-7]
            cad[int(key[1:])] = Stream(z[key + "_frames"], z[key + "_img"], z[key + "_wld"], z[key + "_det"])
    return Capture(name, meta, wa, ha, f30, cad)


# ----------------------------------------------------------------------------------------------- 벡터 도우미
def _unit(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(n > 1e-9, v / n, np.nan)


def _angle3(a, b, c):
    u, w = _unit(a - b), _unit(c - b)
    return np.degrees(np.arccos(np.clip(np.sum(u * w, -1), -1, 1)))


def _flat(v):
    out = v.copy()
    out[..., 1] = 0.0
    return out


def _nanmean2(a, b):
    with np.errstate(invalid="ignore"):
        return np.where(np.isnan(a), b, np.where(np.isnan(b), a, (a + b) / 2))


# ----------------------------------------------------------------------------------------------- 프레임 변수
def frame_vars(st: Stream, wa: int, ha: int) -> dict[str, np.ndarray]:
    """프레임마다 계산하는 모든 양. 계산 불가(관절 가시성 < 0.5 등)는 NaN."""
    img, wld, det = st.img, st.wld, st.det
    vis = np.fmin(img[..., 2], img[..., 3])
    ok = det[:, None] & (vis >= MIN_VIS)
    P = img[..., :2].astype(np.float64) * np.array([wa, ha])
    P[~ok] = np.nan
    W = wld.astype(np.float64) * np.array([100.0, -100.0, -100.0])
    W[~ok] = np.nan
    V: dict[str, np.ndarray] = {}

    def p(j):
        return P[:, j]

    def w(j):
        return W[:, j]

    # 중점은 한쪽만 보이면 그쪽 값(앱 legLen·ankleMid 폴백과 같은 취지) — 옆모습에서 먼 쪽 발목이 자주 가려진다
    # 세로 좌표(높이) 용도에만 쓴다. 몸통 기울기·신체 좌표계 원점은 앱처럼 양쪽이 다 보여야 한다(아래 hipm_w_s·shm_w_s)
    hipm_p, ankm_p, shm_p = _nanmean2(p(LHIP), p(RHIP)), _nanmean2(p(LANK), p(RANK)), _nanmean2(p(LSH), p(RSH))
    hipm_w, ankm_w, shm_w = _nanmean2(w(LHIP), w(RHIP)), _nanmean2(w(LANK), w(RANK)), _nanmean2(w(LSH), w(RSH))
    hipm_w_s, shm_w_s = (w(LHIP) + w(RHIP)) / 2, (w(LSH) + w(RSH)) / 2
    V["pel_y"], V["pel_x"] = hipm_p[:, 1], hipm_p[:, 0]
    V["ank_y"], V["sh_y"] = ankm_p[:, 1], shm_p[:, 1]
    V["leg_px"] = ankm_p[:, 1] - hipm_p[:, 1]                     # 골반–발목 세로 거리(px)
    V["hip_h_w"] = hipm_w[:, 1] - ankm_w[:, 1]                    # 골반의 발목 위 높이(cm)
    V["shw_cm"] = np.linalg.norm(_flat(w(LSH) - w(RSH)), axis=-1)
    V["shw_px"] = np.linalg.norm(p(LSH) - p(RSH), axis=-1)
    for s, H, K, A, _, _, _ in SIDES:
        V[f"ankx_{s}"], V[f"anky_{s}"] = P[:, A, 0], P[:, A, 1]

    # 신체 좌표계(앱 PoseFrame): xb = 수평 골반선(사람 왼쪽), zb = xb × up(전방)
    xb = _unit(_flat(w(LHIP) - w(RHIP)))
    zb = _unit(np.cross(xb, UP))

    def body(pt):
        d = pt - hipm_w_s
        return np.stack([np.sum(d * xb, -1), d[:, 1], np.sum(d * zb, -1)], -1)

    def bodydir(v):
        return np.stack([np.sum(v * xb, -1), v[:, 1], np.sum(v * zb, -1)], -1)

    # 요(앱 ViewEstimator.frameYawDeg): 어깨선·골반선 단위벡터 합의 atan2(z, x), +는 오른어깨가 카메라 쪽
    ux = np.zeros(len(det))
    uz = np.zeros(len(det))
    for l, r in ((LSH, RSH), (LHIP, RHIP)):
        x = -(W[:, r, 0] - W[:, l, 0])
        zz = W[:, r, 2] - W[:, l, 2]
        ln = np.hypot(x, zz)
        good = np.isfinite(ln) & (ln > 1e-3)
        ux = ux + np.where(good, x / np.where(good, ln, 1), 0)
        uz = uz + np.where(good, zz / np.where(good, ln, 1), 0)
    yaw = np.degrees(np.arctan2(uz, ux))
    yaw[(ux == 0) & (uz == 0)] = np.nan
    V["yaw"] = yaw

    knee = {}
    for s, H, K, A, HE, FT, sign in SIDES:
        knee[s] = _angle3(w(H), w(K), w(A))
        V[f"knee_{s}"] = knee[s]
        thigh = w(K) - w(H)
        V[f"thigh_incl_w_{s}"] = np.degrees(np.arctan2(-thigh[:, 1], np.linalg.norm(_flat(thigh), axis=-1)))
        V[f"depth_ratio_w_{s}"] = -thigh[:, 1] / np.linalg.norm(thigh, axis=-1)
        tp = p(K) - p(H)
        V[f"thigh_incl_img_{s}"] = np.degrees(np.arctan2(tp[:, 1], np.abs(tp[:, 0])))
        V[f"hipknee_dy_{s}"] = tp[:, 1]
        V[f"thigh_px_{s}"] = np.linalg.norm(tp, axis=-1)
        # 앱 knee_out (신체 좌표계, 바깥 +, ÷ 3D 다리 길이)
        hb, kb, ab = body(w(H)), body(w(K)), body(w(A))
        denom = hb[:, 1] - ab[:, 1]
        leg3 = np.linalg.norm(w(H) - w(A), axis=-1)
        with np.errstate(invalid="ignore", divide="ignore"):
            t = (kb[:, 1] - ab[:, 1]) / denom
            expx = ab[:, 0] + t * (hb[:, 0] - ab[:, 0])
            ko = sign * (kb[:, 0] - expx) / leg3
        ko[np.abs(denom) < 1e-3] = np.nan
        V[f"kout_w_{s}"] = ko
        # 앱 toe_out (발목→발끝 수평, 신체 좌표계, 바깥 +)
        fd = bodydir(_flat(w(FT) - w(A)))
        toe = np.degrees(np.arctan2(sign * fd[:, 0], fd[:, 2]))
        toe[np.hypot(fd[:, 0], fd[:, 2]) < 3.0] = np.nan
        V[f"toe_{s}"] = toe
        # 수평면 위 (무릎−발목) 대 (발끝−발목) 각, 안쪽 −
        kd = bodydir(_flat(w(K) - w(A)))
        th_k = np.degrees(np.arctan2(sign * kd[:, 0], kd[:, 2]))
        th_k[np.hypot(kd[:, 0], kd[:, 2]) < 5.0] = np.nan
        V[f"kf_ang_{s}"] = (th_k - toe + 180) % 360 - 180
        # FPPA / 이미지 knee_out: 무릎이 엉덩이–발목 선에서 몸 중심 쪽(안쪽)으로 벗어난 양
        other = p(RHIP) if s == "L" else p(LHIP)
        u = _unit(p(A) - p(H))
        n = np.stack([-u[:, 1], u[:, 0]], -1)
        flip = np.sum(n * (other - p(H)), -1) < 0
        n[flip] *= -1
        med = np.sum((p(K) - p(H)) * n, -1)
        thighv, shankv = p(K) - p(H), p(A) - p(K)
        cr = thighv[:, 0] * shankv[:, 1] - thighv[:, 1] * shankv[:, 0]
        ang = np.degrees(np.arctan2(np.abs(cr), np.sum(thighv * shankv, -1)))
        # 몸 중심 방향이 정의되려면 두 엉덩이가 이미지에서 떨어져 있어야 한다(옆모습·겹침이면 부호가 무작위) — 10 px 미만은 계산 안 함
        hipsep = np.linalg.norm(p(LHIP) - p(RHIP), axis=-1)
        bad = ~(hipsep >= 10.0)
        fp = np.sign(med) * ang
        ki = -med / np.linalg.norm(p(A) - p(H), axis=-1)
        fp[bad] = np.nan
        ki[bad] = np.nan
        V[f"fppa_{s}"] = fp
        V[f"kout_img_{s}"] = ki
        # 발: 이미지 뒤꿈치–발끝 세로 차(px, 뒤꿈치가 들리면 +), 월드 heel_lift(앱)
        V[f"heeltoe_img_{s}"] = p(FT)[:, 1] - p(HE)[:, 1]
        hl = w(HE) - w(FT)
        ln = np.linalg.norm(hl, axis=-1)
        V[f"heel_lift_w_{s}"] = np.where(ln > 3.0, hl[:, 1] / ln, np.nan)

    for base in ("thigh_incl_w", "depth_ratio_w", "thigh_incl_img", "hipknee_dy", "thigh_px", "kout_w", "toe",
                 "kf_ang", "fppa", "kout_img", "heeltoe_img", "heel_lift_w", "anky"):
        V[f"{base}_mean"] = _nanmean2(V[f"{base}_L"], V[f"{base}_R"])
    V["toe_max"] = np.fmax(V["toe_L"], V["toe_R"])
    V["knee_mean"] = (knee["L"] + knee["R"]) / 2
    V["flex_less"] = 180.0 - np.maximum(knee["L"], knee["R"])
    V["knee_asym"] = knee["L"] - knee["R"]
    chord = shm_w_s - hipm_w_s
    V["torso_incl"] = np.degrees(np.arccos(np.clip(_unit(chord)[:, 1], -1, 1)))
    # KASR
    with np.errstate(invalid="ignore", divide="ignore"):
        ax = np.abs(P[:, LANK, 0] - P[:, RANK, 0])
        V["kasr_img"] = np.where(ax >= 5, np.abs(P[:, LKNE, 0] - P[:, RKNE, 0]) / ax, np.nan)
        aw = np.linalg.norm(_flat(w(LANK) - w(RANK)), axis=-1)
        V["kasr_w"] = np.where(aw >= 3, np.linalg.norm(_flat(w(LKNE) - w(RKNE)), axis=-1) / aw, np.nan)
        # 골반 기울기(좌우 고관절 높이 차 ÷ 골반 폭): 월드는 asin, 이미지는 atan2(Δy, |Δx|) — 둘 다 도, 왼쪽 고관절이 높으면 +
        hw = np.linalg.norm(w(LHIP) - w(RHIP), axis=-1)
        V["pelvis_tilt_w"] = np.degrees(np.arcsin(np.clip((w(LHIP)[:, 1] - w(RHIP)[:, 1]) / hw, -1, 1)))
        V["pelvis_tilt_img"] = np.degrees(np.arctan2(-(P[:, LHIP, 1] - P[:, RHIP, 1]), np.abs(P[:, LHIP, 0] - P[:, RHIP, 0])))
        # 골반 중심 좌우 이동: 이미지 (골반 x − 발목 중점 x) ÷ 발목 x 간격, 월드 신체 x ÷ 수평 발목 간격
        V["pel_shift_img"] = np.where(ax >= 5, (hipm_p[:, 0] - ankm_p[:, 0]) / ax, np.nan)
        V["pel_shift_w"] = np.where(aw >= 3, -body(ankm_w)[:, 0] / aw, np.nan)
        # 발 너비: 월드 stance_sh(앱), 이미지 stance_2d(앱, 정규화 x — 어깨 x 간격 ≥ 0.04)
        sw = np.linalg.norm(_flat(w(LSH) - w(RSH)), axis=-1)
        V["stance_w"] = np.where(sw >= 15, aw / sw, np.nan)
        shx = np.abs(img[:, LSH, 0] - img[:, RSH, 0]).astype(np.float64)
        s2 = np.abs(img[:, LANK, 0] - img[:, RANK, 0]).astype(np.float64) / shx
        good2 = ok[:, LSH] & ok[:, RSH] & ok[:, LANK] & ok[:, RANK] & (shx >= 0.04)
        V["stance_2d"] = np.where(good2, s2, np.nan)
    V["vis"] = vis
    V["ok"] = ok
    V["P"] = P
    return V


# ----------------------------------------------------------------------------------------------- 반복 창
@dataclass
class Rep:
    key: str            # 캡처 이름#반복
    cap: str
    dataset: str
    view: str           # rehab17_front / rehab17_half / rehab18_side / rehab18_obl / mmfit_front
    person: str
    correct: bool
    a: int              # 창 시작(인덱스, 30 fps 배열 기준)
    b: int              # 창 끝(포함)
    block: int
    extra_person: int = 0
    mocap_err: bool = False


def smooth(x: np.ndarray, k: int = 5) -> np.ndarray:
    """NaN 을 선형 보간한 뒤 중심 이동평균 (분할 전용)."""
    x = np.asarray(x, float)
    idx = np.arange(len(x))
    good = np.isfinite(x)
    if good.sum() < 2:
        return x
    xi = np.interp(idx, idx[good], x[good])
    ker = np.ones(k) / k
    pad = k // 2
    xp = np.pad(xi, pad, mode="edge")
    return np.convolve(xp, ker, mode="valid")


def rehab_reps(cap: Capture, V: dict) -> list[Rep]:
    vid, camn = cap.meta["video_id"], cap.meta["camera"]
    rows = [r for r in csv.DictReader((REPO / "data" / "rehab24-6" / "Segmentation.csv").open(encoding="utf-8"), delimiter=";")
            if r["video_id"] == vid and r["exercise_id"] == "6"]
    rows.sort(key=lambda r: int(r["first_frame"]))
    frames = cap.f30.frames
    reps, block, prev = [], 0, None
    for r in rows:
        a, b = int(r["first_frame"]), int(r["last_frame"])
        orient = r["cam17_orientation"]
        corr = r["correctness"] == "1"
        if prev is not None and (a - prev[0] > 60 or prev[1] != orient or prev[2] != corr):
            block += 1
        prev = (b, orient, corr)
        if camn == "17":
            view = "rehab17_front" if orient == "front" else "rehab17_half"
        else:
            view = "rehab18_side" if orient == "front" else "rehab18_obl"
        ia, ib = int(np.searchsorted(frames, a)), int(np.searchsorted(frames, b))
        reps.append(Rep(f"{cap.name}#{r['repetition_number']}", cap.name, "rehab", view, r["person_id"], corr, ia,
                        min(ib, len(frames) - 1), block,
                        extra_person=int(r[f"extra_person_in_cam{camn}"] or 0), mocap_err=r["mocap_erroneous"] == "1"))
    return reps


def mmfit_reps(cap: Capture, V: dict) -> list[Rep]:
    """라벨 세트 창 안에서 골반 높이(이미지 y) 극대 = 바닥을 찾아 반복을 만든다. 창 = 이웃 바닥 사이 가장 높은 점(상단)~상단."""
    from scipy.signal import find_peaks  # noqa: PLC0415
    frames = cap.f30.frames
    y = smooth(V["pel_y"], 5)
    lo = int(np.searchsorted(frames, cap.meta["labelStart"] - 30))
    hi = int(np.searchsorted(frames, cap.meta["labelEnd"] + 30))
    seg = y[lo:hi]
    amp = np.nanpercentile(seg, 95) - np.nanpercentile(seg, 5)
    pk, _ = find_peaks(seg, prominence=0.4 * amp, distance=int(0.8 * FPS))
    bottoms = [lo + int(q) for q in pk]
    reps = []
    for k, bt in enumerate(bottoms):
        left = bottoms[k - 1] if k > 0 else max(0, bt - int(3 * FPS))
        right = bottoms[k + 1] if k + 1 < len(bottoms) else min(len(y) - 1, bt + int(3 * FPS))
        a = left + int(np.nanargmin(y[left:bt + 1]))
        b = bt + int(np.nanargmin(y[bt:right + 1]))
        reps.append(Rep(f"{cap.name}#{k + 1}", cap.name, "mmfit", "mmfit_front", cap.meta["workout"], True, a, b, 0))
    return reps


# ----------------------------------------------------------------------------------------------- 30 fps 국면 (참값 시간축)
LV_STAND, LV_BOT, LV_EARLY = 0.10, 0.90, 2.0 / 3.0


def true_phases(V: dict, rep: Rep) -> dict | None:
    """30 fps, 5프레임 평활 골반 하강량 d 로 국면 경계(인덱스)를 정한다. d 는 반복 전·후 서 있는 높이로 각각 정규화."""
    y = smooth(V["pel_y"], 5)
    a, b = rep.a, rep.b
    if b - a < 10:
        return None
    ib = a + int(np.nanargmax(y[a:b + 1]))
    lo, hi = max(0, a - 15), min(len(y) - 1, b + 15)
    y0 = np.nanmin(y[lo:ib + 1])
    y1 = np.nanmin(y[ib:hi + 1])
    A0, A1 = y[ib] - y0, y[ib] - y1
    if not (A0 > 5 and A1 > 5):
        return None
    d = np.full(len(y), np.nan)
    d[lo:ib + 1] = (y[lo:ib + 1] - y0) / A0
    d[ib:hi + 1] = (y[ib:hi + 1] - y1) / A1

    def back(level):   # 바닥에서 거꾸로 가며 처음 level 아래로 내려가는 곳 다음 인덱스
        for i in range(ib, lo - 1, -1):
            if d[i] < level:
                return i + 1
        return None

    def fwd(level):
        for i in range(ib, hi + 1):
            if d[i] < level:
                return i
        return None

    ph = {"ib": ib, "d0": back(LV_STAND), "b0": back(LV_BOT), "b1": fwd(LV_BOT), "e1": fwd(LV_EARLY), "a1": fwd(LV_STAND),
          "app0": back(LV_EARLY), "a": a, "b": b, "lo": lo, "hi": hi, "A0": A0, "A1": A1, "d": d}
    if any(ph[k] is None for k in ("d0", "b0", "b1", "e1", "a1", "app0")):
        return None
    return ph


PHASES = [  # (이름, 시작 키, 끝 키) — 구간 [시작, 끝)
    ("서 있음", "a", "d0"), ("하강", "d0", "b0"), ("바닥", "b0", "b1"), ("상승 초반", "b1", "e1"), ("상승", "e1", "a1"),
    ("복귀", "a1", "b_end"),
]


def phase_bounds(ph: dict) -> dict[str, tuple[int, int]]:
    out = {}
    for name, s, e in PHASES:
        s_i = ph[s]
        e_i = ph["b"] + 1 if e == "b_end" else ph[e]
        if name == "서 있음":
            s_i = min(ph["a"], ph["d0"])
        out[name] = (s_i, max(s_i, e_i))
    out["바닥(앱 정의 d≥2/3)"] = (ph["app0"], ph["e1"])
    out["바닥→상승1/3 창"] = (ph["ib"], ph["e1"])
    return out


# ----------------------------------------------------------------------------------------------- 블록 기준(서 있음)
REF_VARS = ["pel_y", "leg_px", "hip_h_w", "shw_cm", "torso_incl", "anky_L", "anky_R", "anky_mean", "heeltoe_img_mean",
            "heel_lift_w_mean", "pelvis_tilt_w", "pelvis_tilt_img", "pel_shift_img", "pel_shift_w", "thigh_px_mean",
            "knee_asym"]


def block_refs(V: dict, sel: np.ndarray, standing_mask: np.ndarray) -> dict[str, float]:
    """sel = 이 스트림의 샘플 인덱스(30 fps 배열 기준), standing_mask = 30 fps 참 국면의 서 있음 프레임."""
    idx = sel[standing_mask[sel]]
    out = {}
    for k in REF_VARS:
        v = V[k][idx]
        v = v[np.isfinite(v)]
        out[k] = float(np.median(v)) if len(v) else np.nan
    out["px_per_cm"] = out["leg_px"] / out["hip_h_w"] if out["hip_h_w"] and np.isfinite(out["hip_h_w"]) else np.nan
    out["n"] = int(len(idx))
    return out


# ----------------------------------------------------------------------------------------------- 반복별 통계 (같은 알고리즘)
STATS = [
    # (id, 그룹, 설명, 단위)
    ("R_hip_drop.img.max", "R", "엉덩이 하강량(이미지 y) 사이클 최대", "다리"),
    ("R_hip_drop.w.max", "R", "엉덩이 하강량(월드) 사이클 최대", "다리"),
    ("R_hip_drop.img.bot", "R", "엉덩이 하강량(이미지) 바닥 평균", "다리"),
    ("R_depth.w.min", "R", "허벅지 기울기(월드) 사이클 최소", "°"),
    ("R_depth.w.bot", "R", "허벅지 기울기(월드) 바닥 평균", "°"),
    ("R_depth.img.min", "R", "허벅지 기울기(이미지) 사이클 최소", "°"),
    ("R_depth.ratio_w.min", "R", "(골반−무릎 높이)÷허벅지(월드) 최소", "비"),
    ("R_depth.ratio_img.min", "R", "(골반−무릎 y)÷서 있는 허벅지 px 최소", "비"),
    ("R_knee_min.max", "R", "덜 굽힌 무릎 굽힘 사이클 최대", "°"),
    ("R_knee_min.bot", "R", "덜 굽힌 무릎 굽힘 바닥 평균", "°"),
    ("R_return", "R", "바닥 이후 회복 비율", "비"),
    ("R_foot_drift.max", "R", "발목 이동 범위 ÷ 어깨 폭(이미지)", "어깨"),
    ("F_knee_foot.fppa.bot", "F", "FPPA(이미지, 안쪽 +) 바닥 평균", "°"),
    ("F_knee_foot.kout_img.bot", "F", "knee_out(이미지, 바깥 +) 바닥 평균", "다리"),
    ("F_knee_foot.kout_w.bot", "F", "knee_out(월드, 앱) 바닥 평균", "다리"),
    ("F_knee_foot.kasr_img.bot", "F", "무릎÷발목 간격(이미지) 바닥 평균", "비"),
    ("F_knee_foot.kasr_w.bot", "F", "무릎÷발목 간격(월드) 바닥 평균", "비"),
    ("F_knee_foot.kf_ang.bot", "F", "수평 (무릎−발목)∠(발끝−발목), 안쪽 − 바닥 평균", "°"),
    ("F_heel.ankle_rise.max", "F", "발목 높이 상승(이미지) 사이클 최대", "다리"),
    ("F_heel.ankle_rise.bot", "F", "발목 높이 상승(이미지) 바닥 평균", "다리"),
    ("F_heel.heeltoe_img.bot", "F", "뒤꿈치–발끝 세로 차 변화(이미지) 바닥 평균", "다리"),
    ("F_heel.heel_lift_w.bot", "F", "heel_lift 변화(월드, 앱) 바닥 평균", "비"),
    ("F_hip_first.dtorso", "F", "상승 초반 몸통 기울기 증가(표본)", "°"),
    ("F_hip_first.dtorso_i", "F", "상승 초반 몸통 기울기 증가(d=2/3 보간)", "°"),
    ("F_hip_first.ratio", "F", "상승 초반 어깨 상승÷엉덩이 상승(표본)", "비"),
    ("F_hip_first.ratio_i", "F", "상승 초반 어깨 상승÷엉덩이 상승(보간)", "비"),
    ("F_lateral.tilt_w.bot", "F", "골반 기울기 변화(월드) 바닥 평균", "°"),
    ("F_lateral.tilt_img.bot", "F", "골반 기울기 변화(이미지) 바닥 평균", "°"),
    ("F_lateral.shift_img.bot", "F", "골반 좌우 이동 변화(이미지) 바닥 평균", "스탠스"),
    ("F_lateral.shift_w.bot", "F", "골반 좌우 이동 변화(월드) 바닥 평균", "스탠스"),
    ("F_lateral.knee_asym.bot", "F", "좌우 무릎 굽힘 차 바닥 평균", "°"),
    ("F_descent.vpeak", "F", "하강 최고 속도", "다리/s"),
    ("F_descent.vmean", "F", "하강 평균 속도(d 0.1→0.9, 보간)", "다리/s"),
    ("F_descent.decel", "F", "후반/전반 하강 속도 비(d 0.5→0.9 ÷ 0.1→0.5)", "비"),
    ("T_torso.dmax", "F", "상체 숙임 사이클 최대 − 서 있음(앱 검사)", "°"),
    ("S_toe.stand", "S", "발끝 각(maxside, 월드) 서 있음 중앙값", "°"),
    ("S_stance.w.stand", "S", "발목÷어깨 간격(월드, stance_sh)", "비"),
    ("S_stance.img.stand", "S", "발목÷어깨 x 간격(이미지, stance_2d)", "비"),
]
STAT_IDS = [s[0] for s in STATS]


def _cross_time(t, d, i_from, i_to, level):
    """샘플 i_from→i_to(시간순) 사이에서 d 가 level 을 처음 넘는 시각(선형 보간). 없으면 None."""
    step = 1 if i_to >= i_from else -1
    for i in range(i_from, i_to, step):
        j = i + step
        if (d[i] - level) * (d[j] - level) <= 0 and d[i] != d[j]:
            return t[i] + (t[j] - t[i]) * (level - d[i]) / (d[j] - d[i])
    return None


def rep_stats(V: dict, sel: np.ndarray, rep: Rep, ref: dict, smooth_vel: bool = False) -> dict[str, float]:
    """sel = 스트림 샘플 인덱스(오름차순, 30 fps 배열 기준). 창 [a−15, b+15] 안 샘플로 스스로 국면을 나눠 통계를 낸다."""
    out = {k: np.nan for k in STAT_IDS}
    lo, hi = rep.a - 15, rep.b + 15
    s = sel[(sel >= lo) & (sel <= hi)]
    y_all = V["pel_y"][s]
    good = np.isfinite(y_all)
    s = s[good]
    if len(s) < 3:
        return out
    t = s / FPS
    y = V["pel_y"][s]
    inwin = (s >= rep.a) & (s <= rep.b)
    if not inwin.any():
        return out
    ib = int(np.flatnonzero(inwin)[np.argmax(y[inwin])])
    y0, y1 = np.min(y[:ib + 1]), np.min(y[ib:])
    A0, A1 = y[ib] - y0, y[ib] - y1
    if not (A0 > 5 and A1 > 5):
        return out
    d = np.where(np.arange(len(s)) <= ib, (y - y0) / A0, (y - y1) / A1)
    n = len(s)
    idx_all = np.arange(n)
    bot = idx_all[inwin & (d >= LV_EARLY)]                     # 앱 정의 바닥: 최소 + 진폭/3 (d ≥ 2/3)
    cyc = idx_all[inwin]
    stand = idx_all[d <= LV_STAND]
    early = idx_all[(idx_all > ib) & (d >= LV_EARLY)]           # 바닥 이후 1/3 회복 전
    leg_px, px_cm = ref["leg_px"], ref["px_per_cm"]

    def g(name, ii):
        return V[name][s[ii]]

    def mean2(name, ii, base=0.0, scale=1.0):   # 평균형 통계 — 앱처럼 표본 2개 이상
        v = g(name, ii)
        v = v[np.isfinite(v)]
        return (float(np.mean(v)) - base) / scale if len(v) >= 2 else np.nan

    def ext(name, ii, fn, base=0.0, scale=1.0):  # 극값형 — 표본 1개 이상
        v = g(name, ii)
        v = v[np.isfinite(v)]
        return (float(fn(v)) - base) / scale if len(v) >= 1 else np.nan

    # R
    out["R_hip_drop.img.max"] = ext("pel_y", cyc, np.max, ref["pel_y"], leg_px)
    hw = V["hip_h_w"][s[cyc]]
    hw = hw[np.isfinite(hw)]
    out["R_hip_drop.w.max"] = float((ref["hip_h_w"] - np.min(hw)) / ref["hip_h_w"]) if len(hw) else np.nan
    out["R_hip_drop.img.bot"] = mean2("pel_y", bot, ref["pel_y"], leg_px)
    out["R_depth.w.min"] = ext("thigh_incl_w_mean", cyc, np.min)
    out["R_depth.w.bot"] = mean2("thigh_incl_w_mean", bot)
    out["R_depth.img.min"] = ext("thigh_incl_img_mean", cyc, np.min)
    out["R_depth.ratio_w.min"] = ext("depth_ratio_w_mean", cyc, np.min)
    out["R_depth.ratio_img.min"] = ext("hipknee_dy_mean", cyc, np.min, 0.0, ref["thigh_px_mean"])
    out["R_knee_min.max"] = ext("flex_less", cyc, np.max)
    out["R_knee_min.bot"] = mean2("flex_less", bot)
    post = idx_all[idx_all > ib]
    if len(post):
        pre_stand = np.median(y[stand[stand < ib]]) if (stand < ib).any() else y0
        out["R_return"] = float((y[ib] - np.min(y[post])) / (y[ib] - pre_stand)) if y[ib] - pre_stand > 5 else np.nan
    shw_px = ref["shw_cm"] * px_cm
    drift = []
    for sd in ("L", "R"):
        x, yy = V[f"ankx_{sd}"][s[cyc]], V[f"anky_{sd}"][s[cyc]]
        m = np.isfinite(x) & np.isfinite(yy)
        if m.sum() >= 2:
            drift.append(np.hypot(np.ptp(x[m]), np.ptp(yy[m])) / shw_px)
    out["R_foot_drift.max"] = float(max(drift)) if drift else np.nan
    # F — 무릎–발
    out["F_knee_foot.fppa.bot"] = mean2("fppa_mean", bot)
    out["F_knee_foot.kout_img.bot"] = mean2("kout_img_mean", bot)
    out["F_knee_foot.kout_w.bot"] = mean2("kout_w_mean", bot)
    out["F_knee_foot.kasr_img.bot"] = mean2("kasr_img", bot)
    out["F_knee_foot.kasr_w.bot"] = mean2("kasr_w", bot)
    out["F_knee_foot.kf_ang.bot"] = mean2("kf_ang_mean", bot)
    # F — 뒤꿈치 (서 있음 대비, 다리 길이 정규화)
    out["F_heel.ankle_rise.max"] = ext("anky_mean", cyc, lambda v: -np.min(v), -ref["anky_mean"], leg_px)
    out["F_heel.ankle_rise.bot"] = -mean2("anky_mean", bot, ref["anky_mean"], leg_px)
    out["F_heel.heeltoe_img.bot"] = mean2("heeltoe_img_mean", bot, ref["heeltoe_img_mean"], leg_px)
    out["F_heel.heel_lift_w.bot"] = mean2("heel_lift_w_mean", bot, ref["heel_lift_w_mean"])
    # F — 엉덩이 먼저 상승: 바닥 표본 → 1/3 회복 전 표본 / d=2/3 교차 보간
    tor = V["torso_incl"][s]
    shy = V["sh_y"][s]
    if len(early) and np.isfinite(tor[ib]):
        te = tor[early]
        te = te[np.isfinite(te)]
        if len(te):
            out["F_hip_first.dtorso"] = float(np.max(te) - tor[ib])
        last = early[-1]
        dh = y[ib] - y[last]
        if dh > 0.05 * A1 and np.isfinite(shy[ib]) and np.isfinite(shy[last]):
            out["F_hip_first.ratio"] = float((shy[ib] - shy[last]) / dh)
    j = next((i for i in range(ib + 1, n) if d[i] < LV_EARLY), None)
    if j is not None and np.isfinite(tor[ib]):
        i0 = j - 1
        fr = (d[i0] - LV_EARLY) / (d[i0] - d[j]) if d[i0] != d[j] else 0.0
        tor_c = tor[i0] + fr * (tor[j] - tor[i0])
        if np.isfinite(tor_c):
            out["F_hip_first.dtorso_i"] = float(tor_c - tor[ib])
        sh_c = shy[i0] + fr * (shy[j] - shy[i0])
        y_c = y[i0] + fr * (y[j] - y[i0])
        if np.isfinite(sh_c) and np.isfinite(shy[ib]) and (y[ib] - y_c) > 0.05 * A1:
            out["F_hip_first.ratio_i"] = float((shy[ib] - sh_c) / (y[ib] - y_c))
    # F — 좌우
    out["F_lateral.tilt_w.bot"] = mean2("pelvis_tilt_w", bot, ref["pelvis_tilt_w"])
    out["F_lateral.tilt_img.bot"] = mean2("pelvis_tilt_img", bot, ref["pelvis_tilt_img"])
    out["F_lateral.shift_img.bot"] = mean2("pel_shift_img", bot, ref["pel_shift_img"])
    out["F_lateral.shift_w.bot"] = mean2("pel_shift_w", bot, ref["pel_shift_w"])
    out["F_lateral.knee_asym.bot"] = mean2("knee_asym", bot)
    # F — 하강 속도: 하강량(다리 길이) = (y − 서 있음 y) ÷ 다리 px
    hd = (y - ref["pel_y"]) / leg_px
    if smooth_vel:  # 30 fps 기준값만: 5프레임 평활 후 차분 (프레임 지터가 미분을 지배하지 않게)
        hd = smooth(hd, 5)
    pre = idx_all[idx_all <= ib]
    start = next((i for i in range(ib, -1, -1) if d[i] <= LV_STAND), None)
    if start is not None and ib - start >= 1:
        seg = np.arange(start, ib + 1)
        v = np.diff(hd[seg]) / np.diff(t[seg])
        out["F_descent.vpeak"] = float(np.max(v)) if len(v) else np.nan
    t1, t9 = _cross_time(t, d, ib, 0, LV_STAND), _cross_time(t, d, ib, 0, LV_BOT)
    t5 = _cross_time(t, d, ib, 0, 0.5)
    drop = A0 / leg_px
    if t1 is not None and t9 is not None and t9 > t1:
        out["F_descent.vmean"] = float(0.8 * drop / (t9 - t1))
        if t5 is not None and t9 > t5 > t1:
            out["F_descent.decel"] = float((0.4 / (t9 - t5)) / (0.4 / (t5 - t1)))
    out["T_torso.dmax"] = ext("torso_incl", cyc, np.max, ref["torso_incl"])
    # S — 서 있음 값(반복 앞뒤 서 있는 표본 중앙값, 앱 MEDIAN 규칙 = 2개 이상)
    for sid, var in (("S_toe.stand", "toe_max"), ("S_stance.w.stand", "stance_w"), ("S_stance.img.stand", "stance_2d")):
        v = V[var][s[stand]]
        v = v[np.isfinite(v)]
        out[sid] = float(np.median(v)) if len(v) >= 2 else np.nan
    return out


def grid(n_total: int, stride: float, phase: int) -> np.ndarray:
    """30 fps 배열 인덱스 격자 — 추출기 grid() 와 같은 규칙(위상 phase 프레임에서 시작, floor(k·stride))."""
    k = np.arange(int(np.ceil((n_total - phase) / stride)) + 1)
    g = phase + np.floor(k * stride + 1e-9).astype(int)
    return g[g < n_total]
