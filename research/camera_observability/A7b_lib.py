# -*- coding: utf-8 -*-
"""A7b 공용 — 발 너비·발끝 후보 측정법을 앱과 같은 사이클 창(RepCounter + RepFormEvaluator 에뮬레이션) 안에서 잰다.

앱 코드를 그대로 옮긴 부분(파리티가 목적, 앱은 수정하지 않는다):
    * RepCounter.onFrame  — median3 평활(간격 ≤ 350 ms), 1.5 s 끊김 리셋, ReturnRepTracker(복귀형), 판별 게이트(knee_maxside 스윙 ≥ 35°).
    * RepFormEvaluator    — 사이클 창(직전 끝 ~ 이번 끝), 서 있음 판정(시작 무릎각 − 10.5° / 사이클 최대 − 7.7°), 상단 ≤ 5 프레임 + 이월(carry),
                            바닥(최소 + 진폭/3), 꼬리 서 있음(≤ 5), 시작 자세 = 첫 상단 창(≥ 2 프레임) 중앙값, 극값(EXTREME) 통계.
    * 피처 — knee_mean / knee_maxside(월드 3점 각), stance_2d(Stance2d.kt), toe_out_L/R/maxside(PostureCore 신체 좌표계, up 포함).
후보 측정(프레임 변수):
    ank_gap  = 2D 발목 x 간격(px)          sh_gap = 2D 어깨 x 간격(px)         stance_2d = ank_gap ÷ sh_gap(정규화 x, 어깨 ≥ 0.04)
    toe_ank2d_s  = 발목→발끝 2D 각(°, 바깥 +)     toe_heel2d_s = 뒤꿈치→발끝 2D 각(°)     toe_lat_s = (발끝x − 뒤꿈치x)·바깥 ÷ 정강이 px
    toe_w_s      = 앱 toe_out(월드, 신체 좌표계)
좌표 규약: 이미지 = 정규화 x,y (픽셀은 ×W, ×H, y 아래 +). 월드 = MediaPipe 원값(m) → 앱 규약 (x, −y, −z)·100 cm. up = 앱 규약(cap30 은 (0,1,0)).
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CAP30 = HERE / "outputs" / "A3" / "cap30"
OUT = HERE / "outputs" / "A7b"
MIN_VIS = 0.5
MIN_AMP = 35.0
NOSE, LSH, RSH, LHIP, RHIP, LKNE, RKNE, LANK, RANK, LHEEL, RHEEL, LFOOT, RFOOT = 0, 11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
SIDES = {"L": (LHIP, LKNE, LANK, LHEEL, LFOOT, 1.0), "R": (RHIP, RKNE, RANK, RHEEL, RFOOT, -1.0)}
FRONT_MAX_DEG, OBLIQUE_MAX_DEG, REAR_MIN_DEG = 16.4, 46.2, 81.7
TOE_MEASURES = ("ank2d", "heel2d", "lat", "w")
TOP_NS = (1, 2, 3, 5)


@dataclass
class Frames:
    t: np.ndarray                 # ms
    img: np.ndarray               # (N,33,4) 정규화 x, y, vis, pres
    wld: np.ndarray               # (N,33,3) MediaPipe 원값(m)
    det: np.ndarray               # (N,) bool
    up: np.ndarray | None         # (N,3) 앱 규약 up, None = (0,1,0)
    W: int
    H: int
    frame_no: np.ndarray | None = None   # 영상 프레임 번호(cap30)


# ----------------------------------------------------------------------------------------------- 읽기
def load_cap30(name: str) -> tuple[dict, Frames, Frames]:
    """cap30 npz → (meta, 30 fps 스트림, 진짜 300 ms 스트림)."""
    z = np.load(CAP30 / f"{name}.npz")
    meta = json.loads(str(z["meta"]))
    s = 640.0 / max(meta["width"], meta["height"])
    W, H = int(round(meta["width"] * s)), int(round(meta["height"] * s))
    fps = float(meta["fps"])

    def mk(prefix):
        fr = z[prefix + "frames"]
        return Frames(np.round(fr * 1000.0 / fps).astype(np.int64), z[prefix + "img"].astype(np.float64),
                      z[prefix + "wld"].astype(np.float64), z[prefix + "det"].astype(bool), None, W, H, fr.astype(np.int64))
    return meta, mk(""), mk("c300_")


def load_cap_file(path: Path) -> tuple[dict, Frames]:
    """재생 캡처(.cap, capture_format.py) → Frames. 긴 변 640 = MM-Fit 1280×720 → 640×360."""
    meta, ts, img, wld, det, ups = {}, [], [], [], [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if parts[0] == "H":
            for kv in parts[1:]:
                k, _, v = kv.partition("=")
                meta[k] = v
        elif parts[0] == "U":
            ups.append((int(parts[1]), [float(q) for q in parts[2].split(",")]))
        elif parts[0] == "F":
            t = int(parts[1])
            ts.append(t)
            if int(parts[2]) == 0:
                img.append(np.full((33, 4), np.nan)); wld.append(np.full((33, 3), np.nan)); det.append(False)
                continue
            a = np.full((33, 4), np.nan); b = np.full((33, 3), np.nan)
            for cell in parts[3:]:
                i, _, rest = cell.partition(":")
                vals = [float(q) for q in rest.split(",")]
                a[int(i)] = vals[:4]; b[int(i)] = vals[4:7]
            img.append(a); wld.append(b); det.append(True)
    up = None
    if ups:
        m = dict(ups)
        up = np.array([m.get(t, [np.nan] * 3) for t in ts], float)
    W, H = (640, 360) if meta.get("source", "").startswith("mmfit") else (480, 640)
    return meta, Frames(np.asarray(ts, np.int64), np.asarray(img, float), np.asarray(wld, float), np.asarray(det, bool), up, W, H)


def load_phone_set(set_id: str) -> tuple[dict, Frames]:
    """data/phone/*/sets-2026092*.jsonl 에서 set_id 하나(처음 본 파일). 좌표가 없는 세트는 img/wld 가 NaN(피처만)."""
    for p in sorted((REPO / "data" / "phone").glob("*/sets-2026092*.jsonl")):
        with p.open(encoding="utf-8") as f:
            for line in f:
                if set_id not in line[:300]:
                    continue
                d = json.loads(line)
                if d["set_id"] != set_id:
                    continue
                fr = d["frames"]
                n = len(fr)
                img = np.full((n, 33, 4), np.nan); wld = np.full((n, 33, 3), np.nan); up = np.full((n, 3), np.nan); det = np.zeros(n, bool)
                for i, f in enumerate(fr):
                    v = f.get("vis")
                    if v is not None:
                        img[i, :, 2] = [np.nan if q is None else q for q in v]; img[i, :, 3] = img[i, :, 2]
                    xy = f.get("xy")
                    if xy is not None:
                        a = np.array([np.nan if q is None else q for q in xy], float).reshape(33, 2)
                        img[i, :, :2] = a
                    w = f.get("w")
                    if w is not None:
                        wld[i] = np.array([np.nan if q is None else q for q in w], float).reshape(33, 3)
                    u = f.get("up")
                    if u is not None:
                        up[i] = u
                    det[i] = bool(f.get("features"))
                image = d.get("image") or {"w": 480, "h": 640}
                return d, Frames(np.array([f["t_ms"] for f in fr], np.int64), img, wld, det, up if np.isfinite(up).any() else None,
                                 int(image["w"]), int(image["h"]))
    raise SystemExit(f"세트 없음: {set_id}")


# ----------------------------------------------------------------------------------------------- 벡터
def _unit(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(n > 1e-9, v / n, np.nan)


def _angle3(a, b, c):
    u, w = _unit(a - b), _unit(c - b)
    return np.degrees(np.arccos(np.clip(np.sum(u * w, -1), -1, 1)))


# ----------------------------------------------------------------------------------------------- 프레임 변수
def frame_vars(fr: Frames) -> dict[str, np.ndarray]:
    N = len(fr.t)
    vis = np.fmin(fr.img[..., 2], fr.img[..., 3])
    ok = fr.det[:, None] & (vis >= MIN_VIS)
    X = fr.img[..., :2].copy()                      # 정규화
    X[~ok] = np.nan
    P = X * np.array([fr.W, fr.H])                  # px
    Wc = fr.wld * np.array([100.0, -100.0, -100.0])  # 앱 규약 cm
    Wc[~ok] = np.nan
    if fr.up is None:
        up = np.tile(np.array([0.0, 1.0, 0.0]), (N, 1))
    else:
        up = _unit(np.nan_to_num(fr.up, nan=0.0))
        up = np.where(np.isfinite(up).all(1, keepdims=True), up, np.array([0.0, 1.0, 0.0]))
    V: dict[str, np.ndarray] = {}

    def w(j):
        return Wc[:, j]

    def h(v):
        return np.sum(v * up, -1)

    def flat(v):
        return v - up * h(v)[:, None]

    knee = {}
    for s, (H_, K, A, HE, FT, sign) in SIDES.items():
        knee[s] = _angle3(w(H_), w(K), w(A))
        V[f"knee_{s}"] = knee[s]
    V["knee_mean"] = (knee["L"] + knee["R"]) / 2
    km = np.fmax(knee["L"], knee["R"])
    km[np.isnan(knee["L"]) | np.isnan(knee["R"])] = np.nan
    V["knee_maxside"] = km
    # 신체 좌표계(PoseFrame): xb = 수평 골반선(사람 왼쪽), zb = xb × up
    xb = _unit(flat(w(LHIP) - w(RHIP)))
    zb = _unit(np.cross(xb, up))
    V["valid"] = np.isfinite(xb).all(1) & np.isfinite(zb).all(1)

    def bodydir(v):
        return np.stack([np.sum(v * xb, -1), h(v), np.sum(v * zb, -1)], -1)

    # 요(ViewEstimator): 어깨선·골반선 단위벡터 합의 atan2(z, x)
    ux = np.zeros(N); uz = np.zeros(N)
    for l, r in ((LSH, RSH), (LHIP, RHIP)):
        x = -(Wc[:, r, 0] - Wc[:, l, 0]); zz = Wc[:, r, 2] - Wc[:, l, 2]
        ln = np.hypot(x, zz)
        good = np.isfinite(ln) & (ln > 1e-3)
        ux = ux + np.where(good, x / np.where(good, ln, 1), 0); uz = uz + np.where(good, zz / np.where(good, ln, 1), 0)
    yaw = np.degrees(np.arctan2(uz, ux)); yaw[(ux == 0) & (uz == 0)] = np.nan
    V["yaw"] = yaw
    # 발 너비
    shx = np.abs(X[:, LSH, 0] - X[:, RSH, 0])
    with np.errstate(invalid="ignore", divide="ignore"):
        s2 = np.abs(X[:, LANK, 0] - X[:, RANK, 0]) / shx
    V["stance_2d"] = np.where(shx >= 0.04, s2, np.nan)          # 앱 Stance2d.of (어깨 x < 0.04 → 없음)
    V["ank_gap"] = np.abs(P[:, LANK, 0] - P[:, RANK, 0])          # px
    V["sh_gap"] = np.abs(P[:, LSH, 0] - P[:, RSH, 0])
    V["ank_gap_e"] = np.linalg.norm(P[:, LANK] - P[:, RANK], axis=-1)   # 유클리드(참고)
    # 발끝 — 화면에서 사람 왼쪽이 +x 인지(정면 +1)
    lat = np.sign(X[:, LHIP, 0] - X[:, RHIP, 0])
    lat[lat == 0] = np.nan
    V["lat"] = lat
    for s, (H_, K, A, HE, FT, sign) in SIDES.items():
        out = sign * lat
        at = P[:, FT] - P[:, A]
        V[f"toe_ank2d_{s}"] = np.degrees(np.arctan2(out * at[:, 0], np.abs(at[:, 1]) + 1e-9))
        ht = P[:, FT] - P[:, HE]
        V[f"toe_heel2d_{s}"] = np.degrees(np.arctan2(out * ht[:, 0], np.abs(ht[:, 1]) + 1e-9))
        shin = np.linalg.norm(P[:, A] - P[:, K], axis=-1)
        with np.errstate(invalid="ignore", divide="ignore"):
            V[f"toe_lat_{s}"] = np.where(shin >= 5, out * ht[:, 0] / shin, np.nan)
        fd = bodydir(flat(w(FT) - w(A)))
        toe = np.degrees(np.arctan2(sign * fd[:, 0], fd[:, 2]))
        toe[~(np.hypot(fd[:, 0], fd[:, 2]) >= 3.0)] = np.nan
        toe[~V["valid"]] = np.nan
        V[f"toe_w_{s}"] = toe
    for m in TOE_MEASURES:
        a, b = V[f"toe_{m}_L"], V[f"toe_{m}_R"]
        V[f"toe_{m}_max"] = np.fmax(a, b)                       # 앱 maxside: 한쪽만 있어도 그쪽
        V[f"toe_{m}_mean"] = (a + b) / 2                        # 양쪽 다 있어야
    V["vis"] = vis
    V["ok"] = ok
    return V


# ----------------------------------------------------------------------------------------------- RepCounter 에뮬레이션
@dataclass
class CycleEvent:
    kind: str          # "cycle" | "rejected"
    idx: int           # 끝 프레임 인덱스
    t: int
    cmin: float
    cmax: float
    swing: float | None


def run_counter(t: np.ndarray, signal: np.ndarray, identity: np.ndarray, min_amp: float = MIN_AMP,
                refractory: int = 1200, max_gap: int = 1500) -> list[CycleEvent]:
    """RepCounter(복귀형 ReturnRepTracker + 판별 게이트)를 프레임 순서대로 돌린다."""
    band = min_amp * 0.22
    st = {"anchor": None, "stableAt": None, "stableValue": 0.0, "stableCount": 0, "moving": False, "startAt": 0,
          "low": 0.0, "high": 0.0, "returnAt": None, "returnCount": 0, "lastCountAt": None}

    def reset_cycle():
        st.update(anchor=None, stableAt=None, stableCount=0, moving=False, returnAt=None, returnCount=0)

    def tracker(tm, value):
        origin = st["anchor"]
        if origin is None:
            if st["stableAt"] is None or abs(value - st["stableValue"]) > band:
                st["stableAt"] = tm; st["stableValue"] = value; st["stableCount"] = 1
            else:
                st["stableCount"] += 1
                if st["stableCount"] >= 3 and tm - st["stableAt"] >= 300:
                    st["anchor"] = st["stableValue"]; st["low"] = st["stableValue"]; st["high"] = st["stableValue"]; st["startAt"] = tm
            return None
        st["low"] = min(st["low"], value); st["high"] = max(st["high"], value)
        if not st["moving"]:
            if abs(value - origin) >= min_amp:
                st["moving"] = True
            else:
                return None
        if abs(value - origin) > band:
            st["returnAt"] = None; st["returnCount"] = 0
            return None
        if st["returnAt"] is None:
            st["returnAt"] = tm
        st["returnCount"] += 1
        if (st["returnCount"] < 2 or tm - st["returnAt"] < 150 or tm - st["startAt"] < refractory
                or (st["lastCountAt"] is not None and tm - st["lastCountAt"] < refractory)):
            return None
        cyc = (st["low"], st["high"])
        st["lastCountAt"] = tm; st["startAt"] = tm; st["moving"] = False; st["returnAt"] = None; st["returnCount"] = 0
        st["low"] = origin; st["high"] = origin
        return cyc

    events: list[CycleEvent] = []
    prevT = None; dtMs = None; raw3 = [np.nan] * 3; rawCount = 0
    ident: list[tuple[int, float]] = []
    gateAt = -10 ** 12
    for i in range(len(t)):
        tm = int(t[i]); v = signal[i]; idv = identity[i]
        if np.isfinite(idv):
            ident.append((tm, float(idv)))
        if not np.isfinite(v):
            continue
        if prevT is not None and (tm - prevT > max_gap or tm <= prevT):
            reset_cycle(); rawCount = 0; dtMs = None
            ident = [x for x in ident if x[0] >= tm]
        if prevT is not None:
            d = float(tm - prevT)
            if 0 < d < 2000:
                dtMs = d if dtMs is None else dtMs * 0.7 + d * 0.3
        prevT = tm
        raw3[rawCount % 3] = float(v); rawCount += 1
        vs = float(np.median(raw3)) if (rawCount >= 3 and (dtMs if dtMs is not None else 999.0) <= 350.0) else float(v)
        cyc = tracker(tm, vs)
        if cyc is None:
            continue
        lo, hi, n = np.inf, -np.inf, 0
        for (tt, vv) in ident:
            if gateAt < tt <= tm:
                lo = min(lo, vv); hi = max(hi, vv); n += 1
        gateAt = tm
        ident = [x for x in ident if x[0] > tm]
        swing = (hi - lo) if n >= 2 else None
        kind = "rejected" if (swing is not None and swing < min_amp) else "cycle"
        events.append(CycleEvent(kind, i, tm, cyc[0], cyc[1], swing))
    return events


# ----------------------------------------------------------------------------------------------- RepFormEvaluator 에뮬레이션
@dataclass
class Phases:
    top: list[int]
    bottom: list[int]
    cycle: list[int]
    trailing: list[int]
    ascent: list[int]
    own: list[int]              # 이 창의 신호 프레임(이월 제외)
    window_all: list[int]       # 이 창의 buf 프레임 전부
    top_end: int                # 하강 직전 마지막 서 있는 프레임(전역 인덱스), -1 = 없음
    standing_level: float


@dataclass
class RepWin:
    event: CycleEvent
    phases: Phases
    baseline: dict | None       # 이 사이클 판정 시점의 시작 자세(없으면 None)
    baseline_new: bool          # 이 사이클에서 시작 자세가 잡혔나


def run_evaluator(t: np.ndarray, V: dict, events: list[CycleEvent], in_buf: np.ndarray, base_keys: list[str],
                  min_amp: float = MIN_AMP, top_frames: int = 5) -> list[RepWin]:
    """앱 RepFormEvaluator.onCycle/onRejected/segment 를 그대로. in_buf = 피처가 있는 프레임(앱 onFrame 이 창에 넣는 프레임)."""
    sig = V["knee_mean"]
    buf = [i for i in range(len(t)) if in_buf[i]]
    carry: list[int] = []
    baseline: dict | None = None
    out: list[RepWin] = []
    for ev in events:
        window = [i for i in buf if t[i] <= ev.t]
        buf = [i for i in buf if t[i] > ev.t]
        if ev.kind == "rejected":
            carry = []
            continue
        standing = (baseline["knee_mean"] - 0.3 * min_amp) if (baseline is not None and "knee_mean" in baseline) else (ev.cmax - 0.22 * min_amp)
        pre = [i for i in carry if np.isfinite(sig[i])]
        own = [i for i in window if np.isfinite(sig[i])]
        if not own:
            ph = Phases([], [], window, [], [], [], window, -1, standing)
            carry = []
            out.append(RepWin(ev, ph, dict(baseline) if baseline else None, False))
            continue
        s = pre + own
        v = sig[s]
        n0 = len(pre)
        idx_min = n0
        for i in range(n0, len(v)):
            if v[i] < v[idx_min]:
                idx_min = i
        top_end = -1
        for i in range(idx_min, -1, -1):
            if v[i] >= standing:
                top_end = i; break
        top_all = [] if top_end < 0 else [i for i in range(max(0, top_end - top_frames + 1), top_end + 1) if v[i] >= standing]
        top_own = [i for i in top_all if i >= n0]
        top = top_own if len(top_own) >= 2 else top_all
        bottom_level = ev.cmin + (ev.cmax - ev.cmin) / 3.0
        bottom = [i for i in range(n0, len(v)) if v[i] <= bottom_level]
        cyc = list(range(max(top_end + 1, n0), len(s)))
        tail = len(v)
        while tail > n0 and v[tail - 1] >= standing and len(v) - tail < top_frames:
            tail -= 1
        trailing = list(range(tail, len(v)))
        ascent = list(range(idx_min, len(s)))

        def g(ii):
            return [s[i] for i in ii]

        ph = Phases(g(top), g(bottom), g(cyc), g(trailing), g(ascent), own, window, (s[top_end] if top_end >= 0 else -1), standing)
        carry = ph.trailing
        new = False
        if baseline is None and len(ph.top) >= 2:
            baseline = {}
            for k in base_keys:
                vals = V[k][ph.top]; vals = vals[np.isfinite(vals)]
                if len(vals) >= 2:
                    baseline[k] = float(np.median(vals))
            baseline["_t"] = int(t[ph.top[-1]])
            new = True
        out.append(RepWin(ev, ph, dict(baseline) if baseline else None, new))
    return out


def view_class(yaw_abs_med: float) -> str:
    if not np.isfinite(yaw_abs_med):
        return "UNK"
    if yaw_abs_med <= FRONT_MAX_DEG:
        return "C"
    if yaw_abs_med <= OBLIQUE_MAX_DEG:
        return "BD"
    if yaw_abs_med < REAR_MIN_DEG:
        return "SIDE"
    return "AE"
