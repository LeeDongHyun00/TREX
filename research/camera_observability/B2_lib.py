# -*- coding: utf-8 -*-
"""B2 공용 — 덤벨 컬 연구의 읽기·팔 변수·팔별 반복 분할·반복별 통계.

원칙(A3_lib 과 같다)
    * 기준값(30 fps)과 300 ms 표본은 **같은 알고리즘**으로 통계를 낸다 — 차이는 샘플링만의 몫이다.
    * 반복 창(어느 팔의 어느 시각이 한 반복인가)은 30 fps 기준 신호가 정하고, 300 ms 스트림은 그 시각 창 안의
      자기 표본만으로 값을 낸다(앱이 그 창을 정확히 잘랐다고 가정한 상한).
    * 랜드마크는 앱과 같이 min(visibility, presence) ≥ 0.5 일 때만 쓴다. 월드 좌표는 앱 규약(cm, y 위 +, z 카메라 쪽 +),
      up = (0,1,0)(IMU 없음 = 앱 폴백). 피처 식은 PostureCore.kt 와 같다(elbow_*, upperarm_vert_*, elbow_torso_*, elbow_h_*,
      shoulder_h_*, torso_incl). 2D 대리는 분석 해상도(긴 변 640) 픽셀.
데이터
    outputs/B2/cap30/*.npz           B2_extract30.py — 30 fps + 진짜 300/333 ms 주기(16세트, 영상이 남은 w06·w14·w18·w19·w20)
    data/mmfit_mp_captures/*.cap     extract_mediapipe.py 의 300 ms 캡처(59세트, 앞 15 s·뒤 10 s)
    data/mm-fit/mm-fit/wNN/*_pose_3d.npy  데이터셋 자체 3D(H36M, 라벨 시간축) — 영상이 없는 43세트의 반복 시각 기준
    data/phone/.../sets-20260912.jsonl   폰 덤벨 컬 2세트(피처만)
MM-Fit 파생물은 측정 전용 — 커밋·공개하지 않는다.
"""
from __future__ import annotations

import csv
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CAP30 = HERE / "outputs" / "B2" / "cap30"
OUT = HERE / "outputs" / "B2"
CAP300_DIR = REPO / "data" / "mmfit_mp_captures"
MMFIT_ROOT = REPO / "data" / "mm-fit" / "mm-fit"
ALIGN = REPO / "research" / "external_rep_replay" / "results" / "mmfit_mp" / "alignment.json"
PHONE_LOG = REPO / "data" / "phone" / "20260925-main" / "sets-20260912.jsonl"
PHONE_SETS = ("20260912T040622-d1ada6b0", "20260912T040730-af95f408")
MIN_VIS = 0.5
FPS = 30.0
UP = np.array([0.0, 1.0, 0.0])
MMFIT_SUBJECTS = {**{w: "P0" for w in "w01 w03 w06 w08 w10 w14".split()},
                  **{w: "P1" for w in "w02 w04 w07 w09 w11 w15".split()}}
LSH, RSH, LEL, REL, LWR, RWR, LHIP, RHIP = 11, 12, 13, 14, 15, 16, 23, 24
H36 = dict(lsho=11, lelb=12, lwri=13, rsho=14, relb=15, rwri=16)
SIDES = ("L", "R")
# 반동·벌어짐 후보(이완→수축 변화와 반복 창 범위를 본다). 접미사 없는 것은 양팔 공통.
CAND_SIDE = ["upperarm_vert", "elbow_torso", "elbow_h", "forearm_vert", "shoulder_h", "sh_h_img"]
CAND_COMMON = ["torso_incl", "elbow_gap_w", "elbow_gap_img", "sh_y_img_n", "hip_y_img_n"]
CONTRACT_DEG = 10.0      # 수축 국면 = 30 fps 평활 각이 최소 + 10° 이하
REST_ANGLE = 140.0       # 서 있음(이완) 기준: 양 팔꿈치 ≥ 140°


def subject_of(workout: str) -> str:
    return MMFIT_SUBJECTS.get(workout, f"u:{workout}")


# ----------------------------------------------------------------------------------------------- 읽기
@dataclass
class Stream:
    t_ms: np.ndarray      # (N,) int
    xy: np.ndarray        # (N,33,2) 정규화 이미지 좌표
    vis: np.ndarray       # (N,33) min(visibility, presence)
    wld: np.ndarray       # (N,33,3) MediaPipe 월드(m)
    det: np.ndarray       # (N,) bool
    wa: int = 640
    ha: int = 360


def load_npz(name: str) -> tuple[dict, dict[str, Stream]]:
    z = np.load(CAP30 / f"{name}.npz")
    meta = json.loads(str(z["meta"]))
    s = 640.0 / max(meta["width"], meta["height"])
    wa, ha = int(round(meta["width"] * s)), int(round(meta["height"] * s))
    fps = meta["fps"]

    def mk(pre: str) -> Stream:
        img = z[pre + "img"]
        return Stream(np.round(z[pre + "frames"] * 1000.0 / fps).astype(np.int64), img[..., :2], np.fmin(img[..., 2], img[..., 3]),
                      z[pre + "wld"], z[pre + "det"], wa, ha)

    streams = {"f30": mk("")}
    for k in z.files:
        if k.startswith("c") and k.endswith("_frames"):
            streams[k[:-7]] = mk(k[:-6])
    return meta, streams


def load_cap(path: Path, wa: int = 640, ha: int = 360) -> Stream:
    """capture_format 의 F 줄 → Stream. 33개가 다 없는 줄은 사람 없음."""
    t, xy, vis, wld, det = [], [], [], [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line[0] != "F":
            continue
        parts = line.split("\t")
        t.append(int(parts[1]))
        a = np.full((33, 2), np.nan)
        v = np.full(33, np.nan)
        w = np.full((33, 3), np.nan)
        n = 0
        for cell in parts[3:]:
            i, _, rest = cell.partition(":")
            x = [float(q) for q in rest.split(",")]
            i = int(i)
            a[i] = x[0:2]
            vi = 1.0 if np.isnan(x[2]) else x[2]
            pr = 1.0 if np.isnan(x[3]) else x[3]
            v[i] = min(vi, pr)
            w[i] = x[4:7]
            n += 1
        ok = n == 33 and int(parts[2]) >= 1
        det.append(ok)
        xy.append(a)
        vis.append(v)
        wld.append(w)
    return Stream(np.array(t, dtype=np.int64), np.array(xy), np.array(vis), np.array(wld), np.array(det, dtype=bool), wa, ha)


def load_index() -> list[dict]:
    idx = json.loads((CAP300_DIR / "index.json").read_text(encoding="utf-8"))["sets"]
    align = json.loads(ALIGN.read_text(encoding="utf-8"))["sets"]
    out = []
    for s in idx:
        if s["activity"] != "bicep_curls":
            continue
        a = align.get(s["capture"], {})
        d = dict(s)
        d["lagFrames"] = int(a.get("usedLagFrames", 0))
        d["lagHow"] = a.get("how", "none")
        d["shiftMs"] = int(a.get("shiftMs", 0))
        d["subject"] = subject_of(s["workout"])
        d["setIndex"] = int(s["capture"].split("_set")[1][:2])
        out.append(d)
    return out


_POSE3D: dict[str, tuple[int, dict[str, np.ndarray]]] = {}


def pose3d_elbows(workout: str) -> tuple[int, dict[str, np.ndarray]]:
    """데이터셋 3D(H36M) 팔꿈치 각 — (첫 프레임 번호, {elbow_L, elbow_R}) 라벨 프레임 축, 빠진 프레임은 nan."""
    if workout in _POSE3D:
        return _POSE3D[workout]
    pose = np.load(MMFIT_ROOT / workout / f"{workout}_pose_3d.npy")
    fr = pose[0, :, 0].astype(int)
    f0, f1 = int(fr[0]), int(fr[-1])
    J = np.full((f1 - f0 + 1, 17, 3), np.nan)
    J[fr - f0] = np.transpose(pose[:, :, 1:], (1, 2, 0))
    out = {"elbow_L": angle3(J[:, H36["lsho"]], J[:, H36["lelb"]], J[:, H36["lwri"]]),
           "elbow_R": angle3(J[:, H36["rsho"]], J[:, H36["relb"]], J[:, H36["rwri"]])}
    _POSE3D[workout] = (f0, out)
    return _POSE3D[workout]


def load_phone_sets() -> list[dict]:
    out = []
    for line in PHONE_LOG.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        if d["set_id"] in PHONE_SETS:
            out.append(d)
    return out


# ----------------------------------------------------------------------------------------------- 벡터
def _unit(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(n > 1e-9, v / n, np.nan)


def angle3(a, b, c):
    u, w = _unit(a - b), _unit(c - b)
    return np.degrees(np.arccos(np.clip(np.sum(u * w, -1), -1, 1)))


def angle_up(v):
    u = _unit(v)
    return np.degrees(np.arccos(np.clip(u[..., 1], -1, 1)))


def perp_ratio(p, a, b):
    """점 p 에서 직선 a→b 까지의 수직 거리 ÷ 직선 길이 (PostureCore.perpFromLine)."""
    axis = b - a
    ln = np.linalg.norm(axis, axis=-1)
    with np.errstate(invalid="ignore", divide="ignore"):
        u = axis / ln[:, None]
        d = p - a
        perp = d - u * np.sum(d * u, -1)[:, None]
        out = np.linalg.norm(perp, axis=-1) / ln
    out[~(ln > 1e-6)] = np.nan
    return out


def _nanmean2(a, b):
    both = np.isfinite(a) & np.isfinite(b)
    out = np.where(both, (a + b) / 2, np.where(np.isfinite(a), a, b))
    return out


# ----------------------------------------------------------------------------------------------- 팔 변수
def arm_vars(st: Stream) -> dict[str, np.ndarray]:
    """프레임마다 계산하는 팔·몸통 변수. 계산 불가(가시성 < 0.5, 양 엉덩이 없음 등)는 NaN."""
    ok = st.det[:, None] & (st.vis >= MIN_VIS)
    P = st.xy.astype(np.float64) * np.array([st.wa, st.ha])
    P[~ok] = np.nan
    W = st.wld.astype(np.float64) * np.array([100.0, -100.0, -100.0])
    W[~ok] = np.nan
    V: dict[str, np.ndarray] = {}
    p = lambda j: P[:, j]  # noqa: E731
    w = lambda j: W[:, j]  # noqa: E731
    hipm, shm = (w(LHIP) + w(RHIP)) / 2, (w(LSH) + w(RSH)) / 2
    torso = shm - hipm
    torso_len = np.linalg.norm(torso, axis=-1)
    torso_len = np.where(torso_len >= 20, torso_len, np.nan)
    V["torso_len"] = torso_len
    V["torso_incl"] = angle_up(torso)
    hipm_p, shm_p = (p(LHIP) + p(RHIP)) / 2, (p(LSH) + p(RSH)) / 2
    torso_px = np.linalg.norm(shm_p - hipm_p, axis=-1)
    V["torso_px"] = torso_px
    V["sh_y_img_n"] = -shm_p[:, 1] / torso_px       # 어깨 중점 높이(위 +, 몸통 px 단위) — 으쓱·몸 들림
    V["hip_y_img_n"] = -hipm_p[:, 1] / torso_px
    el = {}
    for s, SH, EL, WR, HIP in (("L", LSH, LEL, LWR, LHIP), ("R", RSH, REL, RWR, RHIP)):
        el[s] = angle3(w(SH), w(EL), w(WR))
        V[f"elbow_{s}"] = el[s]
        V[f"upperarm_vert_{s}"] = angle_up(w(EL) - w(SH))
        V[f"forearm_vert_{s}"] = angle_up(w(WR) - w(EL))
        V[f"elbow_torso_{s}"] = perp_ratio(w(EL), w(HIP), w(SH))
        V[f"elbow_h_{s}"] = (w(EL) - w(SH))[:, 1] / torso_len
        V[f"shoulder_h_{s}"] = (w(SH) - hipm)[:, 1] / torso_len
        # 2D 대리
        a2, b2, c2 = (np.concatenate([p(j), np.zeros((len(P), 1))], -1) for j in (SH, EL, WR))
        V[f"elbow2d_{s}"] = angle3(a2, b2, c2)
        V[f"wrist_sh_dy_{s}"] = p(WR)[:, 1] - p(SH)[:, 1]          # px, 아래 + (손목이 어깨 아래면 양수)
        V[f"forearm_px_{s}"] = np.linalg.norm(p(WR) - p(EL), axis=-1)
        V[f"sh_h_img_{s}"] = (hipm_p[:, 1] - p(SH)[:, 1]) / torso_px
    V["elbow_mean"] = (el["L"] + el["R"]) / 2
    V["elbow_minside"] = np.fmin(el["L"], el["R"])
    V["elbow_gap_w"] = np.linalg.norm(w(LEL) - w(REL), axis=-1) / np.linalg.norm(w(LSH) - w(RSH), axis=-1)
    V["elbow_gap_img"] = np.linalg.norm(p(LEL) - p(REL), axis=-1) / np.linalg.norm(p(LSH) - p(RSH), axis=-1)
    V["vis_L"], V["vis_R"] = ok[:, LEL] & ok[:, LSH] & ok[:, LWR], ok[:, REL] & ok[:, RSH] & ok[:, RWR]
    return V


def phone_vars(frames: list[dict]) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    """폰 세트 로그(피처만) → 같은 이름의 변수. 좌표가 없어 2D 대리·팔꿈치 간격은 없다."""
    fr = [f for f in frames if f.get("features")]
    t = np.array([f["t_ms"] for f in fr], dtype=np.int64)
    keys = set()
    for f in fr:
        keys |= set(f["features"].keys())
    V = {k: np.array([f["features"].get(k, np.nan) for f in fr], dtype=float) for k in keys}
    for s in SIDES:
        for k in ("elbow", "upperarm_vert", "forearm_vert", "elbow_torso", "elbow_h", "shoulder_h"):
            V.setdefault(f"{k}_{s}", np.full(len(fr), np.nan))
        V[f"vis_{s}"] = np.isfinite(V[f"elbow_{s}"])
    V.setdefault("torso_incl", np.full(len(fr), np.nan))
    return t, V


# ----------------------------------------------------------------------------------------------- 반복 분할
def smooth(x: np.ndarray, k: int = 5) -> np.ndarray:
    """NaN 을 선형 보간한 뒤 중심 이동평균 (분할 전용). k=1 이면 보간만."""
    x = np.asarray(x, float)
    idx = np.arange(len(x))
    good = np.isfinite(x)
    if good.sum() < 2:
        return x
    xi = np.interp(idx, idx[good], x[good])
    if k <= 1:
        return xi
    pad = k // 2
    xp = np.pad(xi, pad, mode="edge")
    return np.convolve(xp, np.ones(k) / k, mode="valid")


@dataclass
class ArmRep:
    arm: str
    i_min: int            # 기준 스트림 인덱스
    i_top_b: int          # 하강 직전 이완(최대) 인덱스
    i_top_a: int          # 복귀 이완 인덱스
    i_d0: int             # 하강 시작(d ≥ 0.9 마지막)
    i_d1: int             # 복귀(d ≥ 0.9 처음)
    i_c0: int             # 수축 국면 시작(평활각 ≤ 최소 + 10°)
    i_c1: int             # 수축 국면 끝
    smin: float           # 평활 최소
    stop_b: float
    stop_a: float


def find_arm_reps(sig: np.ndarray, t_ms: np.ndarray, lo_ms: int, hi_ms: int, k: int = 5, min_period_ms: int = 800,
                  prom_frac: float = 0.4, min_amp: float = 25.0, contract_deg: float = CONTRACT_DEG) -> list[ArmRep]:
    """한 팔의 팔꿈치 각에서 굴곡 최소(수축)를 찾아 반복 창을 만든다. 창 = 이웃 최소 사이의 최대(이완)~최대."""
    from scipy.signal import find_peaks  # noqa: PLC0415
    s = smooth(sig, k)
    idx = np.flatnonzero((t_ms >= lo_ms) & (t_ms <= hi_ms))
    if len(idx) < 5:
        return []
    seg = s[idx]
    raw = np.asarray(sig, float)[idx]
    if np.isfinite(raw).sum() < 5:
        return []
    amp = np.nanpercentile(raw, 95) - np.nanpercentile(raw, 5)
    if not amp >= min_amp:
        return []
    dt = float(np.median(np.diff(t_ms[idx]))) if len(idx) > 1 else 1000.0
    dist = max(1, int(round(min_period_ms / dt)))
    pk, _ = find_peaks(-seg, prominence=prom_frac * amp, distance=dist)
    mins = idx[pk]
    reps = []
    for j, mi in enumerate(mins):
        left = mins[j - 1] if j > 0 else int(np.searchsorted(t_ms, t_ms[mi] - 3000))
        right = mins[j + 1] if j + 1 < len(mins) else int(np.searchsorted(t_ms, t_ms[mi] + 3000, side="right") - 1)
        left, right = max(0, left), min(len(s) - 1, right)
        tb = left + int(np.argmax(s[left:mi + 1]))
        ta = mi + int(np.argmax(s[mi:right + 1]))
        smin, stb, sta = float(s[mi]), float(s[tb]), float(s[ta])
        # 하강 시작·복귀 (d = (s − min)/(top − min) ≥ 0.9)
        d0 = mi
        for i in range(mi, tb - 1, -1):
            if (s[i] - smin) / max(stb - smin, 1e-6) >= 0.9:
                d0 = i
                break
        d1 = mi
        for i in range(mi, ta + 1):
            if (s[i] - smin) / max(sta - smin, 1e-6) >= 0.9:
                d1 = i
                break
        c0 = mi
        while c0 - 1 >= tb and s[c0 - 1] <= smin + contract_deg:
            c0 -= 1
        c1 = mi
        while c1 + 1 <= ta and s[c1 + 1] <= smin + contract_deg:
            c1 += 1
        reps.append(ArmRep("", int(mi), tb, ta, d0, d1, c0, c1, smin, stb, sta))
    return reps


@dataclass
class Rep:
    """한 반복(팔 하나, 또는 동시 컬이면 두 팔). 시각은 기준 스트림(30 fps)의 ms."""
    key: str
    arm: str              # 'L' | 'R' | 'LR'
    t_min: int
    t_top_b: int
    t_top_a: int
    t_d0: int
    t_d1: int
    t_c0: int
    t_c1: int
    ref_min: float        # 기준 스트림(30 fps 원값) 최소
    ref_top_b: float
    ref_top_a: float
    parts: dict           # 팔별 ArmRep


def pair_reps(repsL: list[ArmRep], repsR: list[ArmRep], t_ms: np.ndarray, sig: dict[str, np.ndarray], key: str,
              tol_ms: int = 400) -> tuple[list[Rep], str]:
    """좌우 최소 시각이 tol 안이면 동시 반복(LR)으로 묶는다. 반환 (반복 목록, 세트 방식 alt|sim|mixed|none)."""
    used_r = set()
    out = []
    for a in repsL:
        best, bd = None, None
        for jr, b in enumerate(repsR):
            if jr in used_r:
                continue
            d = abs(int(t_ms[b.i_min]) - int(t_ms[a.i_min]))
            if d <= tol_ms and (bd is None or d < bd):
                best, bd = jr, d
        if best is not None:
            used_r.add(best)
            out.append(_mk_rep(key, "LR", {"L": a, "R": repsR[best]}, t_ms, sig))
        else:
            out.append(_mk_rep(key, "L", {"L": a}, t_ms, sig))
    for jr, b in enumerate(repsR):
        if jr not in used_r:
            out.append(_mk_rep(key, "R", {"R": b}, t_ms, sig))
    out.sort(key=lambda r: r.t_min)
    for i, r in enumerate(out):
        r.key = f"{key}#{i + 1}"
    n_pair = sum(1 for r in out if r.arm == "LR")
    n_single = len(out) - n_pair
    if not out:
        mode = "none"
    elif n_pair == 0:
        mode = "alt"
    elif n_single <= max(1, 0.2 * len(out)):
        mode = "sim"
    else:
        mode = "mixed"
    return out, mode


def _mk_rep(key: str, arm: str, parts: dict[str, ArmRep], t_ms: np.ndarray, sig: dict[str, np.ndarray]) -> Rep:
    ps = list(parts.values())
    tmin = int(np.mean([t_ms[p.i_min] for p in ps]))
    tb, ta = int(min(t_ms[p.i_top_b] for p in ps)), int(max(t_ms[p.i_top_a] for p in ps))
    d0, d1 = int(min(t_ms[p.i_d0] for p in ps)), int(max(t_ms[p.i_d1] for p in ps))
    c0, c1 = int(min(t_ms[p.i_c0] for p in ps)), int(max(t_ms[p.i_c1] for p in ps))
    # 기준 원값 극값(평활 전) — 동시면 두 팔 평균 신호가 아니라 팔별 값의 평균
    mins, tbs, tas = [], [], []
    for s, p in parts.items():
        raw = sig[f"elbow_{s}"]
        w = raw[p.i_top_b:p.i_top_a + 1]
        mins.append(np.nanmin(w) if np.isfinite(w).any() else np.nan)
        wb = raw[p.i_top_b:p.i_min + 1]
        wa = raw[p.i_min:p.i_top_a + 1]
        tbs.append(np.nanmax(wb) if np.isfinite(wb).any() else np.nan)
        tas.append(np.nanmax(wa) if np.isfinite(wa).any() else np.nan)
    return Rep(key, arm, tmin, tb, ta, d0, d1, c0, c1, float(np.nanmean(mins)), float(np.nanmean(tbs)), float(np.nanmean(tas)), parts)


# ----------------------------------------------------------------------------------------------- 반복별 통계 (임의 스트림)
def rep_values(V: dict[str, np.ndarray], t_ms: np.ndarray, rep: Rep, shift_ms: int = 0) -> dict[str, float]:
    """스트림(V, t_ms) 의 표본 중 반복 창 [t_top_b, t_top_a] 안의 것만으로 극값·후보 변화를 낸다.
    shift_ms: 기준 시각 → 이 스트림 시각으로 옮기는 양(pose_3d 기준일 때 라벨→영상 지연)."""
    out: dict[str, float] = {}
    tb, ta, tm = rep.t_top_b + shift_ms, rep.t_top_a + shift_ms, rep.t_min + shift_ms
    win = (t_ms >= tb) & (t_ms <= ta)
    arms = list(rep.parts.keys())
    e = np.nanmean(np.stack([V[f"elbow_{s}"] for s in arms]), 0) if len(arms) > 1 else V[f"elbow_{arms[0]}"]
    ew = np.where(win, e, np.nan)
    n = int(np.isfinite(ew).sum())
    out["n_win"] = n
    out["n_contract"] = int(((t_ms >= rep.t_c0 + shift_ms) & (t_ms <= rep.t_c1 + shift_ms) & np.isfinite(e)).sum())
    out["n_contract_any"] = int(((t_ms >= rep.t_c0 + shift_ms) & (t_ms <= rep.t_c1 + shift_ms)).sum())
    if n == 0:
        return out
    i_min = int(np.nanargmin(ew))
    before = np.where(win & (t_ms <= tm), e, np.nan)
    after = np.where(win & (t_ms >= tm), e, np.nan)
    out["min"] = float(ew[i_min])
    out["ext_b"] = float(np.nanmax(before)) if np.isfinite(before).any() else np.nan
    out["ext_a"] = float(np.nanmax(after)) if np.isfinite(after).any() else np.nan
    out["amp_b"] = out["ext_b"] - out["min"]
    out["amp_a"] = out["ext_a"] - out["min"]
    out["t_min_err"] = float(t_ms[i_min] - tm)
    # 후보(반동·벌어짐)는 반복 자체의 창 [하강 시작 − 300, 복귀 + 300] 안에서 본다 — 마지막 반복 뒤의 정리 동작이 들어오지 않게.
    # 이완 기준 표본 = 하강 시작(t_d0)에 가장 가까운 창 안 표본(팔꿈치각이 유한한 것).
    cw = (t_ms >= rep.t_d0 + shift_ms - 300) & (t_ms <= rep.t_d1 + shift_ms + 300) & np.isfinite(e)
    if not cw.any():
        cw = win & np.isfinite(e)
    ci = np.flatnonzero(cw)
    i_top = int(ci[np.argmin(np.abs(t_ms[ci] - (rep.t_d0 + shift_ms)))])
    i_cmin = int(ci[np.argmin(e[ci])])
    out["n_cw"] = int(len(ci))
    # 앱 신호(양팔 평균·minside)의 같은 창 극값
    for k in ("elbow_mean", "elbow_minside"):
        if k in V:
            x = np.where(win, V[k], np.nan)
            out[f"{k}_min"] = float(np.nanmin(x)) if np.isfinite(x).any() else np.nan
            out[f"{k}_max"] = float(np.nanmax(x)) if np.isfinite(x).any() else np.nan
    # 2D 대리
    for s in arms:
        for k in ("elbow2d", "wrist_sh_dy"):
            if f"{k}_{s}" in V:
                x = np.where(win, V[f"{k}_{s}"], np.nan)
                if np.isfinite(x).any():
                    out[f"{k}_min"] = np.nanmin([out.get(f"{k}_min", np.nan), float(np.nanmin(x))])
                    out[f"{k}_max"] = np.nanmax([out.get(f"{k}_max", np.nan), float(np.nanmax(x))])
        if f"forearm_px_{s}" in V:
            x = np.where(win & (t_ms <= tm), V[f"forearm_px_{s}"], np.nan)
            out["forearm_px_top"] = float(np.nanmax([out.get("forearm_px_top", np.nan), np.nanmedian(x)])) if np.isfinite(x).any() else out.get("forearm_px_top", np.nan)
    # 후보: 수축 표본 − 이완(하강 직전) 표본, 그리고 창 안 범위
    for base in CAND_SIDE:
        vals_d, vals_r, vals_mx, vals_mn = [], [], [], []
        for s in arms:
            k = f"{base}_{s}"
            if k not in V:
                continue
            x = V[k]
            if np.isfinite(x[i_cmin]) and np.isfinite(x[i_top]):
                vals_d.append(x[i_cmin] - x[i_top])
            xw = np.where(cw, x, np.nan)
            if np.isfinite(xw).sum() >= 2:
                vals_r.append(np.nanmax(xw) - np.nanmin(xw))
                if np.isfinite(x[i_top]):
                    vals_mx.append(np.nanmax(xw) - x[i_top])
                    vals_mn.append(x[i_top] - np.nanmin(xw))
        out[f"d_{base}"] = float(np.nanmax(vals_d)) if vals_d else np.nan       # 동시면 더 큰 팔
        out[f"r_{base}"] = float(np.nanmax(vals_r)) if vals_r else np.nan
        out[f"x_{base}"] = float(np.nanmax(vals_mx)) if vals_mx else np.nan     # 창 최대 − 이완
        out[f"n_{base}"] = float(np.nanmax(vals_mn)) if vals_mn else np.nan     # 이완 − 창 최소(하강)
    for k in CAND_COMMON:
        if k not in V:
            continue
        x = V[k]
        out[f"d_{k}"] = float(x[i_cmin] - x[i_top]) if np.isfinite(x[i_cmin]) and np.isfinite(x[i_top]) else np.nan
        xw = np.where(cw, x, np.nan)
        out[f"r_{k}"] = float(np.nanmax(xw) - np.nanmin(xw)) if np.isfinite(xw).sum() >= 2 else np.nan
        out[f"x_{k}"] = float(np.nanmax(xw) - x[i_top]) if np.isfinite(xw).sum() >= 2 and np.isfinite(x[i_top]) else np.nan
        out[f"n_{k}"] = float(x[i_top] - np.nanmin(xw)) if np.isfinite(xw).sum() >= 2 and np.isfinite(x[i_top]) else np.nan
    return out


# ----------------------------------------------------------------------------------------------- 서 있음(이완) 잡음
def rest_jitter(V: dict[str, np.ndarray], t_ms: np.ndarray, lo_ms: int, hi_ms: int, keys: list[str],
                max_dt_ms: int = 400, window_ms: int = 2000, rest_angle: float = REST_ANGLE) -> dict[str, dict[str, float]]:
    """[lo, hi] 안에서 양 팔꿈치 ≥ rest_angle 인 이완 표본의 연속 표본 차이 σ(= std(Δ)/√2)와 2 s 창 범위 p95 를 잰다.
    호출 쪽이 창을 '첫 반복 하강 직전 3 s'(덤벨을 들고 서 있는 구간)로 주면 서 있음 떨림이 된다."""
    rest = (t_ms >= lo_ms) & (t_ms <= hi_ms) & (V["elbow_L"] >= rest_angle) & (V["elbow_R"] >= rest_angle)
    out = {}
    idx = np.flatnonzero(rest)
    for k in keys:
        if k not in V:
            continue
        x = V[k]
        diffs, ranges = [], []
        for a, b in zip(idx[:-1], idx[1:]):
            if b == a + 1 and t_ms[b] - t_ms[a] <= max_dt_ms and np.isfinite(x[a]) and np.isfinite(x[b]):
                diffs.append(x[b] - x[a])
        # 2 s 창: 창 안 표본이 모두 rest 이고 3개 이상
        for a in idx:
            m = rest & (t_ms >= t_ms[a]) & (t_ms < t_ms[a] + window_ms)
            xs = x[m]
            xs = xs[np.isfinite(xs)]
            if len(xs) >= 3 and m.sum() == ((t_ms >= t_ms[a]) & (t_ms < t_ms[a] + window_ms)).sum():
                ranges.append(np.max(xs) - np.min(xs))
        out[k] = {"n_rest": int(rest.sum()), "n_pairs": len(diffs), "sigma": float(np.std(diffs) / np.sqrt(2)) if len(diffs) >= 3 else np.nan,
                  "range2s_p95": float(np.percentile(ranges, 95)) if len(ranges) >= 3 else np.nan,
                  "range2s_max": float(np.max(ranges)) if ranges else np.nan, "n_windows": len(ranges)}
    return out


# ----------------------------------------------------------------------------------------------- 기타
def pct(x, q=(5, 50, 95)) -> list[float]:
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) == 0:
        return [np.nan] * len(q)
    return [float(v) for v in np.percentile(x, q)]


def write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    keys: list[str] = []
    for r in rows:
        for k in r:
            if k not in keys:
                keys.append(k)
    with path.open("w", encoding="utf-8", newline="") as h:
        wr = csv.DictWriter(h, fieldnames=keys)
        wr.writeheader()
        for r in rows:
            wr.writerow(r)
