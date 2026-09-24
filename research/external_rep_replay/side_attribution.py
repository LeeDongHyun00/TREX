# -*- coding: utf-8 -*-
"""좌우 한 쌍 = 1회 — 런지 쌍 카운트·걸음 좌우 판별·컬 팔별 레인의 타당성 조사 (연구용, 앱 코드가 아니다).

사용자 결정 (2026-09-24)
    교대 동작은 "좌우 한 번씩 = 1회" 다. 목표 10회 = 왼 10 + 오른 10(20걸음). 한쪽만 했으면 숫자는 오르지 않고 화면이 '반대쪽 차례' 를
    보이며, 두 쪽이 다 끝나야 1 이 오른다. 런지의 목표 도달 자동 진행(spec §42)은 유지한다(설계 §15 #23).
    MM-Fit 은 **개발 데이터**다(설계 §12) — 아래 숫자는 타당성이지 성능 주장이 아니다. 어떤 카운터 파라미터도 이 데이터로 맞추지 않는다.

Q1 런지 쌍 정확도 — 지금 카운터(한 걸음 = 한 사이클)에 쌍 단위 표시를 씌우면
    표시 = floor(세트 창 안 사이클 / 2), 정답 쌍 = floor(정답 걸음 / 2). MM-Fit 정답은 걸음 수다(왼·오른 5+5 = 10).
    홀수 정답(9·11)은 교대라면 마지막 한 걸음이 짝이 없으므로 floor 로 내린다 — 짝수 세트만의 표도 함께 싣는다.
    세트 창 = run_replay 와 같다: 발화 시각이 [startMs − 0.5 s, endMs + 0.5 s] 안. 자료: MediaPipe 영상(정렬 index,
    기존 재생 data/mm-fit/exp_mp/aligned/replay.jsonl)과 MM-Fit 자체 3D(captures_pose3d 를 live·hysteresis 로 다시 재생).
    조기 도달(자동 진행) = 캡처 시작부터 센 사이클이 목표에 닿은 발화 시각이 3D 기준 걸음(아래 Q2)의 해당 걸음 **바닥보다 앞**인 세트.

Q2 걸음 좌우 판별 — 사이클 하나(한 걸음)가 어느 다리의 걸음인가
    규칙(앞발): 걸음 바닥에서 **앞에 있는 발이 그 걸음의 다리**다. 앞 = 몸 전방 F 쪽. F 는 엉덩이 선(왼 − 오른 엉덩이)을 수평면에 투영해
    위 방향과 외적한 방향 F = lat × up 이다(오른손 좌표계에서 몸이 보는 쪽). d = (왼 발목 − 오른 발목)·F > 0 이면 왼쪽 걸음.
    바닥 구간 = 사이클 knee_mean 최저 + 15° 안(3D 는 최저 프레임 ± 1 s, 연속 구간)의 샘플 전부에서 d 의 중앙값으로 정한다.
    가정 — ① 좌표계가 오른손계이고 L/R 이름이 사람 기준이다(검사: 굽힌 무릎이 F 쪽으로 나간다 = 무릎 전방 오프셋 > 0).
           ② 앞으로 딛든 뒤로 딛든 바닥에서 앞에 있는 다리가 일하는 다리다(전진·후진 런지 모두 같은 관례).
           ③ 3D 기준의 위 방향 = 워크아웃에서 두 무릎이 165° 넘게 펴진 프레임의 (골반 − 두 발목 중점) 평균 방향. MM-Fit 카메라가
              약 14° 기울어 있어 데이터셋 z 축을 그대로 쓰면 발목이 선 자세에서도 골반 뒤로 25 cm 나온다.
    3D 기준(pose_3d, 30 fps)은 그 자체가 단안 영상에서 들어 올린 추정이다(골반 깊이가 항상 0 인 root 상대 좌표) — **참 좌우 정답이 아니다**.
    그래서 걸음 좌우가 번갈아 나오는지(교대율)로 기준을 점검하고, 다른 단서(뒷무릎이 낮다, 2D 에서 앞발이 화면 아래)와의 일치도 싣는다.
    같은 규칙을 MediaPipe 월드 좌표(앱 규약 cm, y 위·z 카메라 쪽 — 재생기와 같은 (x, −y, −z)×100, 위 = 화면 세로축 = 앱의 센서 없는 폴백)에
    적용해 같은 시각의 3D 기준과 비교한다. 3D 기준 걸음은 knee_mean 지그재그(교대 극값 30° 이상, 정답 수를 쓰지 않는다)로 뽑는다.
    엄격 카운트 min(L, R) ('두 쪽 다 끝나야 1') 와 floor(걸음/2) 가 어긋나는 빈도를 3D 기준 걸음과 카운터 사이클 양쪽에서 잰다.

Q3 컬 팔별 레인 (연구만 — 이후 컬 결정의 자료)
    팔마다 새 코어(hysteresis, elbow_L / elbow_R, 35°, DOWN)를 하나씩 두고 완료 = 왼 사이클 하나 + 오른 사이클 하나의 쌍, 즉 min(nL, nR).
    동시 컬은 두 레인이 같이 발화해 1, 교대 컬은 왼 다음 오른으로 1 이다. 병합 창(설계 §4.4 의 500 ms)이 없어 늦은 레인이 두 번 세지 않는다.
    MM-Fit 교대 컬(정답 쌍 = floor(정답/2))과, 3D 에서 한 팔의 움직임을 반대 팔에 거울로 옮긴 **합성 동시 컬**(정답 = 원래 팔의 컬 수,
    3D 지그재그 35° 로 세어 정답/2 와 같을 때만 채점)에서 잰다. 합성은 팔 사이 지연 0 과 300 ms 두 가지.

출력
    <out>/ (기본 data/mm-fit/exp_sidepair — git 제외)   pose3d/ 재생, lanes_mp/·lanes_pose3d/ 레인 재생, synth_simul_curl/ 합성 캡처와 재생,
                                                         lunge_cycles.jsonl(사이클별 좌우), lunge_steps.jsonl(3D 기준 걸음), summary_full.json
    <results>/ (기본 results/side_attribution)          summary.md, summary.json (집계만)

사용법
    python side_attribution.py --self-test                     # 합성 자료, 데이터·JVM 불필요, 정확한 결과를 확인
    python side_attribution.py [--out DIR] [--results DIR] [--skip-replay]
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import run_replay  # noqa: E402
from capture_format import LANDMARKS, app_cadence, frame_line, write_capture  # noqa: E402
from mmfit_pair_compare import H36, MP, angle, app_features, load_labels, mp_landmarks  # noqa: E402

DATA = HERE.parent.parent / "data" / "mm-fit"
FPS = 30.0
LUNGE = "스텝 포워드 다이나믹 런지"
CURL = "덤벨 컬"
SET_MARGIN_MS = run_replay.SET_MARGIN_MS     # 세트 창 = 라벨 ± 0.5 s (run_replay 와 같다)
MARGIN_FRAMES = 15                           # 같은 여유를 30 fps 프레임으로 (mmfit_pose3d_captures.MARGIN_FRAMES)

STEP_ZIGZAG_DEG = 30.0       # 3D 기준 걸음: knee_mean 교대 극값이 30° 이상 벌어질 때만 확정
CURL_ZIGZAG_DEG = 35.0       # 합성 동시 컬의 정답: 원래 팔 elbow 교대 극값 35° 이상 (카운터 게이트와 같은 크기)
SEGMENT_PAD_FRAMES = 90      # 지그재그는 라벨 ± 3 s 에서 돌리고(첫 걸음 앞의 선 자세를 보게), 걸음은 라벨 ± 0.5 s 안의 것만 쓴다
DEEP_BAND_DEG = 15.0         # 바닥 구간 = 사이클 최저 + 15° 안
DEEP_MAX_FRAMES = 30         # 3D 바닥 구간은 최저 프레임 ± 1 s 안의 연속 구간
LIVE_WINDOW_CAP_MS = 4000    # 레거시 카운터는 사이클 시작을 내보내지 않는다 — (직전 발화, 이 발화] 를 최대 4 s 로 자른다
HYST_WINDOW_PAD_MS = 300     # 새 코어 사이클 창 = [하강 시작 − 300 ms, 발화]
REF_SAMPLE_PAD_MS = 150      # 3D 기준 걸음의 바닥 구간 ± 150 ms 안 MediaPipe 샘플로 그 걸음을 판별한다
CONSISTENT = 0.8             # 바닥 구간 안 d 부호의 다수 비율이 이보다 낮으면 '모호' 로 두고 비교에서 뺀다
MARGINS_CM = (0.0, 10.0, 20.0)
MIN_VIS = 0.5                # PostureAnalyzer.MIN_VISIBILITY
APP_SCALE = np.array([100.0, -100.0, -100.0])   # MediaPipe 월드(m, y 아래) → 앱 규약 cm(y 위, z 카메라 쪽)
APP_UP = np.array([0.0, 1.0, 0.0])              # 영상에는 IMU 가 없다 — 앱의 센서 없는 폴백(화면 세로축)
SYNTH_LAGS_MS = (0, 300)
LANE_CONFIGS = ("hysteresis+elbow_L", "hysteresis+elbow_R")
LANE_LOW_COVERAGE = 0.85     # 레인 과소 세트 중 약한 팔이 세트 창 샘플의 85% 미만에서만 보인 세트를 따로 센다
SIDE_CUES = ("front", "kneeH", "imgY")
CUE_LABEL = {"front": "앞발 (월드 F 방향, 주 규칙)", "kneeH": "뒷무릎이 낮다 (월드 높이)",
             "imgY": "앞발이 화면 아래 (2D, 정면 카메라에서만 뜻이 있다)"}


# ---------------------------------------------------------------- 쌍 단위 산술

def pair_display(cycles: int) -> int:
    """좌우 구분 없이 사이클만 세는 카운터에 쌍 단위 표시를 씌운 값 — 두 사이클마다 1."""
    return cycles // 2


def truth_pairs(truth: int) -> int:
    """정답 걸음 → 정답 쌍. 교대라면 홀수 정답의 마지막 걸음은 짝이 없다."""
    return truth // 2


def strict_pairs(sides: list[str | None]) -> int:
    """'두 쪽 다 끝나야 1' — 좌우가 정해진 걸음만 센다(판별 못 한 걸음은 짝을 채우지 않는다)."""
    c = Counter(sides)
    return min(c["L"], c["R"])


def running_mismatch(sides: list[str | None]) -> tuple[int, int]:
    """세트 도중 k 걸음째마다 floor(k/2) 와 min(L_k, R_k) 가 다른 순간의 수, 비교한 순간의 수."""
    nl = nr = diff = 0
    for k, s in enumerate(sides, 1):
        nl += s == "L"
        nr += s == "R"
        diff += (k // 2) != min(nl, nr)
    return diff, len(sides)


def switch_fraction(sides: list[str | None]) -> tuple[int, int]:
    """연속한 두 걸음이 둘 다 판별됐을 때 다리가 바뀐 수, 비교한 쌍의 수."""
    sw = n = 0
    for a, b in zip(sides, sides[1:]):
        if a in ("L", "R") and b in ("L", "R"):
            n += 1
            sw += a != b
    return sw, n


def lane_pair_times(fl: list[int], fr: list[int]) -> list[int]:
    """팔별 레인 쌍: k 번째 완료 = 왼 k 번째와 오른 k 번째가 둘 다 발화한 시각. 동시 컬은 거의 같은 시각, 교대는 뒤 팔의 시각."""
    fl, fr = sorted(fl), sorted(fr)
    return [max(a, b) for a, b in zip(fl, fr)]


def pair_row(cycles: int, truth: int) -> dict:
    """한 세트의 걸음 단위·쌍 단위 오차."""
    return {"cycles": cycles, "truth": truth, "stepErr": cycles - truth,
            "pairs": pair_display(cycles), "truthPairs": truth_pairs(truth),
            "pairErr": pair_display(cycles) - truth_pairs(truth), "odd": truth % 2 == 1}


def pair_scores(rows: list[dict], key_err: str = "pairErr") -> dict:
    """exact / ±1 / 과다 / 과소 세트 비율·수."""
    n = len(rows)
    if n == 0:
        return {"sets": 0}
    e = [r[key_err] for r in rows]
    return {"sets": n, "exact": sum(x == 0 for x in e) / n, "within1": sum(abs(x) <= 1 for x in e) / n,
            "overSets": sum(x > 0 for x in e), "underSets": sum(x < 0 for x in e), "MAE": sum(abs(x) for x in e) / n}


# ---------------------------------------------------------------- 신호·기하

def zigzag_minima(x: np.ndarray, thr: float) -> list[int]:
    """교대 극값이 thr 이상 벌어질 때만 확정하는 지그재그의 최솟값 인덱스(오름차순). NaN 은 건너뛴다.
    최솟값은 앞에 thr 이상 높은 최댓값이 있고 뒤로 thr 이상 올라야 확정된다 — 구간 시작의 낮은 값·끝에서 올라오지 않은 바닥은 세지 않는다."""
    mins: list[int] = []
    have_max = False
    hi = lo = None
    for i in range(len(x)):
        v = float(x[i])
        if not math.isfinite(v):
            continue
        if hi is None:
            hi = lo = i
            continue
        if not have_max:
            if v > x[hi]:
                hi = i
            if x[hi] - v >= thr:
                have_max, lo = True, i
            continue
        if v < x[lo]:
            lo = i
        elif v - x[lo] >= thr:
            mins.append(lo)
            have_max, hi = False, i
    return mins


def forward_dirs(lhip: np.ndarray, rhip: np.ndarray, up: np.ndarray) -> np.ndarray:
    """몸 전방 F = (엉덩이 선의 수평 성분) × up, 단위벡터. 오른손 좌표계에서 몸이 보는 쪽이다(가정 ①)."""
    lat = lhip - rhip
    lat = lat - (lat @ up)[..., None] * up
    f = np.cross(lat, up)
    with np.errstate(invalid="ignore", divide="ignore"):
        return f / np.linalg.norm(f, axis=-1, keepdims=True)


def side_of(value: float, margin: float = 0.0) -> str | None:
    """d > margin → 'L', d < −margin → 'R', 그 사이·NaN → None(판별 안 함)."""
    if value is None or not math.isfinite(value) or abs(value) <= margin:
        return None
    return "L" if value > 0 else "R"


def majority(values: np.ndarray) -> tuple[float, float]:
    """(중앙값, 중앙값과 같은 부호의 비율). 유효값이 없으면 (nan, nan)."""
    v = values[np.isfinite(values)]
    if len(v) == 0:
        return float("nan"), float("nan")
    med = float(np.median(v))
    if med == 0:
        return med, 0.0
    return med, float(np.mean(np.sign(v) == np.sign(med)))


def knee_angles(J: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    return (angle(J[:, H36["lhip"]], J[:, H36["lknee"]], J[:, H36["lank"]]),
            angle(J[:, H36["rhip"]], J[:, H36["rknee"]], J[:, H36["rank"]]))


def workout_up(J: np.ndarray) -> tuple[np.ndarray, int]:
    """(위 방향 단위벡터, 쓴 프레임 수) — 두 무릎이 165° 넘게 펴진 프레임의 (골반 − 두 발목 중점) 평균 방향(가정 ③)."""
    kl, kr = knee_angles(J)
    with np.errstate(invalid="ignore"):
        stand = np.isfinite(kl) & np.isfinite(kr) & (kl > 165) & (kr > 165)
    v = J[stand, 0] - (J[stand, H36["lank"]] + J[stand, H36["rank"]]) / 2
    v = v[np.all(np.isfinite(v), axis=1)]
    if len(v) == 0:
        return np.array([0.0, 0.0, 1.0]), 0
    u = (v / np.linalg.norm(v, axis=1, keepdims=True)).mean(0)
    return u / np.linalg.norm(u), int(len(v))


def front_d(J: np.ndarray, up: np.ndarray) -> np.ndarray:
    """(왼 발목 − 오른 발목)·F, 프레임별. 양수 = 왼발이 앞."""
    F = forward_dirs(J[:, H36["lhip"]], J[:, H36["rhip"]], up)
    return ((J[:, H36["lank"]] - J[:, H36["rank"]]) * F).sum(-1)


def reference_steps(J: np.ndarray, f0: int, up: np.ndarray, start_f: int, end_f: int) -> list[dict]:
    """3D 기준 걸음 — 라벨 ± 3 s 에서 knee_mean 지그재그, 라벨 ± 0.5 s 안의 바닥만. 각 걸음의 앞발 판별과 점검 단서.
    J: 워크아웃 전체 (N, 17, 3), J[i] = 프레임 f0 + i."""
    a = max(0, start_f - SEGMENT_PAD_FRAMES - f0)
    b = min(len(J) - 1, end_f + SEGMENT_PAD_FRAMES - f0)
    if b <= a:
        return []
    seg = J[a:b + 1]
    kl, kr = knee_angles(seg)
    km = (kl + kr) / 2
    d = front_d(seg, up)
    F = forward_dirs(seg[:, H36["lhip"]], seg[:, H36["rhip"]], up)
    knee_h = ((seg[:, H36["lknee"]] - seg[:, H36["rknee"]]) * up).sum(-1)
    steps = []
    for m in zigzag_minima(km, STEP_ZIGZAG_DEG):
        frame = f0 + a + m
        if not (start_f - MARGIN_FRAMES <= frame <= end_f + MARGIN_FRAMES):
            continue
        lo = hi = m
        while lo - 1 >= 0 and m - (lo - 1) <= DEEP_MAX_FRAMES and np.isfinite(km[lo - 1]) and km[lo - 1] <= km[m] + DEEP_BAND_DEG:
            lo -= 1
        while hi + 1 < len(km) and (hi + 1) - m <= DEEP_MAX_FRAMES and np.isfinite(km[hi + 1]) and km[hi + 1] <= km[m] + DEEP_BAND_DEG:
            hi += 1
        med, cons = majority(d[lo:hi + 1])
        kmed, _ = majority(knee_h[lo:hi + 1])
        # 가정 ① 점검: 굽힌 무릎은 엉덩이–발목 선보다 F 쪽으로 나간다(두 다리 모두)
        fwd = []
        for hip, knee, ank in (("lhip", "lknee", "lank"), ("rhip", "rknee", "rank")):
            off = seg[lo:hi + 1, H36[knee]] - (seg[lo:hi + 1, H36[hip]] + seg[lo:hi + 1, H36[ank]]) / 2
            fwd.append(float(np.nanmedian((off * F[lo:hi + 1]).sum(-1))))
        steps.append({"frame": int(frame), "deepFrames": [int(f0 + a + lo), int(f0 + a + hi)], "kneeMin": float(km[m]),
                      "dMm": med, "consistency": cons, "side": side_of(med) if cons >= CONSISTENT else None,
                      "kneeSide": side_of(kmed), "kneeFwdMm": fwd})
    return steps


# ---------------------------------------------------------------- MediaPipe 캡처

def read_capture_full(path: Path) -> dict:
    """capture_format 의 F 줄 → 시각·검출·가시성·월드(m)·이미지 좌표. 33개가 다 없는 줄은 사람 없음(재생기와 같다)."""
    t, poses, vis, world, img = [], [], [], [], []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.startswith("F"):
            continue
        parts = line.split("\t")
        v = np.full(LANDMARKS, np.nan)
        w = np.full((LANDMARKS, 3), np.nan)
        xy = np.full((LANDMARKS, 2), np.nan)
        n = 0
        for cell in parts[3:]:
            i, _, rest = cell.partition(":")
            x = [float(q) for q in rest.split(",")]
            vi = 1.0 if math.isnan(x[2]) else x[2]
            pr = 1.0 if math.isnan(x[3]) else x[3]
            k = int(i)
            v[k] = min(vi, pr)
            w[k] = x[4:7]
            xy[k] = x[0:2]
            n += 1
        t.append(int(parts[1]))
        poses.append(int(parts[2]) if n == LANDMARKS else 0)
        vis.append(v)
        world.append(w)
        img.append(xy)
    return {"t": np.array(t, dtype=np.int64), "poses": np.array(poses, dtype=int),
            "vis": np.array(vis).reshape(-1, LANDMARKS), "world": np.array(world).reshape(-1, LANDMARKS, 3),
            "img": np.array(img).reshape(-1, LANDMARKS, 2)}


def mp_side_cues(cap: dict) -> dict[str, np.ndarray]:
    """샘플별 좌우 단서(양수 = 왼쪽 걸음) — 앱 후처리처럼 가시성 0.5 미만 관절은 없는 것으로 본다.
    front: (왼 발목 − 오른 발목)·F (cm), kneeH: (왼 무릎 − 오른 무릎)·up (cm, 뒷무릎이 낮으므로 앞다리 쪽이 양수),
    imgY: 왼 발목 화면 y − 오른 발목 화면 y (정규화, 가까운 앞발이 화면 아래)."""
    ok = (cap["vis"] >= MIN_VIS) & (cap["poses"][:, None] >= 1)
    W = np.where(ok[:, :, None], cap["world"] * APP_SCALE, np.nan)
    F = forward_dirs(W[:, MP["lhip"]], W[:, MP["rhip"]], APP_UP)
    front = ((W[:, MP["lank"]] - W[:, MP["rank"]]) * F).sum(-1)
    knee_h = (W[:, MP["lknee"]] - W[:, MP["rknee"]]) @ APP_UP
    img_y = np.where(ok[:, MP["lank"]] & ok[:, MP["rank"]], cap["img"][:, MP["lank"], 1] - cap["img"][:, MP["rank"], 1], np.nan)
    return {"front": front, "kneeH": knee_h, "imgY": img_y}


def mp_knee_mean(cap: dict) -> np.ndarray:
    _, feat = app_features(cap)
    return feat["knee_mean"]


def deep_samples(km: np.ndarray, sel: np.ndarray) -> np.ndarray:
    """sel(불리언) 안에서 knee_mean 최저 + 15° 안인 샘플 인덱스."""
    idx = np.where(sel & np.isfinite(km))[0]
    if len(idx) == 0:
        return idx
    kmin = km[idx].min()
    return idx[km[idx] <= kmin + DEEP_BAND_DEG]


def cue_sides(cues: dict[str, np.ndarray], idx: np.ndarray) -> dict:
    out = {}
    for k in SIDE_CUES:
        med, cons = majority(cues[k][idx]) if len(idx) else (float("nan"), float("nan"))
        out[k] = {"d": med, "consistency": cons, "side": side_of(med)}
    return out


# ---------------------------------------------------------------- 3D 포즈 캐시

class PoseCache:
    def __init__(self, root: Path):
        self.root, self.cache = root, {}

    def get(self, w: str) -> dict | None:
        if w not in self.cache:
            pp, lp = self.root / w / f"{w}_pose_3d.npy", self.root / w / f"{w}_labels.csv"
            if not pp.is_file() or not lp.is_file():
                self.cache[w] = None
            else:
                pose = np.load(pp)
                fno = pose[0, :, 0].astype(int)
                f0 = int(fno[0])
                J = np.full((int(fno[-1]) - f0 + 1, 17, 3), np.nan)
                J[fno - f0] = np.transpose(pose[:, :, 1:], (1, 2, 0))
                up, n_up = workout_up(J)
                self.cache[w] = {"J": J, "f0": f0, "up": up, "upFrames": n_up, "labels": load_labels(lp), "d": front_d(J, up)}
        return self.cache[w]


def frames_of(t_ms: np.ndarray, lag: int) -> np.ndarray:
    """영상 시각 → 라벨(3D) 프레임. lag = 영상 프레임 − 라벨 프레임(mmfit_align)."""
    return np.rint(np.asarray(t_ms) * FPS / 1000.0).astype(int) - lag


def video_ms(frame: int, lag: int) -> int:
    return int(round((frame + lag) * 1000.0 / FPS))


def d3_at(pose: dict, frames: np.ndarray) -> np.ndarray:
    idx = frames - pose["f0"]
    out = np.full(len(idx), np.nan)
    ok = (idx >= 0) & (idx < len(pose["d"]))
    out[ok] = pose["d"][idx[ok]]
    return out


# ---------------------------------------------------------------- 재생 결과

def in_window(times: list[int], s: dict) -> list[int]:
    lo, hi = s["startMs"] - SET_MARGIN_MS, s["endMs"] + SET_MARGIN_MS
    return sorted(t for t in times if lo <= t <= hi)


def cycle_windows(r: dict, fires_all: list[int]) -> dict[int, tuple[int, int]]:
    """발화 시각 → 그 사이클의 창. 새 코어는 publishedCycles 의 하강 시작, 레거시는 직전 발화(최대 4 s)."""
    out = {}
    pc = r.get("publishedCycles") or []
    if pc:
        for c in pc:
            out[int(c[0])] = (int(c[1]) - HYST_WINDOW_PAD_MS, int(c[0]))
    prev = None
    for t in sorted(fires_all):
        if t not in out:
            lo = t - LIVE_WINDOW_CAP_MS if prev is None else max(prev, t - LIVE_WINDOW_CAP_MS)
            out[t] = (lo, t)
        prev = t
    return out


def jvm_rows(index: dict, exercise: str, configs: tuple[str, ...]) -> list[tuple]:
    """run_replay 매니페스트 형식의 행(신호 교체 구성). run_replay.manifest_rows 는 등록된 진단 신호만 만든다 — 레인은 여기서 만든다."""
    rows = []
    for s in index["sets"]:
        if s["exercise"] != exercise:
            continue
        key = run_replay.set_key(s)
        for c in configs:
            mode, _, feature = c.partition("+")
            if mode == "hysteresis":
                pol = run_replay.hysteresis_polarity(s)
                if pol is None:
                    continue
            else:
                pol = ""
            amp = "35.0" if feature else ""
            rows.append((f"{key}|{c}", s["capture"], s["exercise"], mode, feature, amp, "0", "", "", pol))
    return rows


def run_rows(rows: list[tuple], base: Path, out: Path, skip: bool) -> dict[str, dict]:
    res = out / "replay.jsonl"
    if skip and res.is_file():
        return run_replay.load_results(res)
    run_replay.write_manifest(rows, out / "manifest.tsv", base)
    return run_replay.run_jvm(out / "manifest.tsv", res)


# ---------------------------------------------------------------- Q1 · Q2 런지

def lunge_sets(index: dict) -> list[dict]:
    return [s for s in index["sets"] if s["exercise"] == LUNGE and s.get("truthReps") is not None]


def label_frames(pose: dict, s: dict) -> tuple[int, int]:
    """index 세트의 라벨 프레임(시작, 끝). ordinal 로 라벨 CSV 를 찾고 활동이 맞는지 확인한다."""
    if s.get("startFrame") is not None:
        return int(s["startFrame"]), int(s["endFrame"])
    st, en, _, act = pose["labels"][s["ordinal"]]
    if act != s["activity"]:
        raise ValueError(f"{s['workout']} ordinal {s['ordinal']}: 라벨 활동 {act} ≠ {s['activity']}")
    return st, en


def analyze_reference(poses: PoseCache, sets: list[dict]) -> dict[str, list[dict]]:
    """세트 캡처 → 3D 기준 걸음 목록."""
    out = {}
    for s in sets:
        pose = poses.get(s["workout"])
        if pose is None:
            continue
        st, en = label_frames(pose, s)
        out[s["capture"]] = reference_steps(pose["J"], pose["f0"], pose["up"], st, en)
    return out


def reference_summary(sets: list[dict], ref: dict[str, list[dict]], poses: PoseCache) -> dict:
    """MM-Fit 교대 규칙성 — 3D 기준 걸음에서."""
    n_sets = match = within1 = 0
    total = decided = 0
    sw = sw_n = 0
    lr_differ = lr_differ2 = floor_vs_min = 0
    run_diff = run_n = 0
    knee_agree = knee_n = 0
    fwd_pos = fwd_n = 0
    margins = []
    odd_sets = 0
    for s in sets:
        steps = ref.get(s["capture"])
        if steps is None:
            continue
        n_sets += 1
        n = len(steps)
        match += n == s["truthReps"]
        within1 += abs(n - s["truthReps"]) <= 1
        odd_sets += s["truthReps"] % 2
        sides = [x["side"] for x in steps]
        total += n
        decided += sum(x is not None for x in sides)
        a, b = switch_fraction(sides)
        sw, sw_n = sw + a, sw_n + b
        c = Counter(sides)
        lr_differ += c["L"] != c["R"]
        lr_differ2 += abs(c["L"] - c["R"]) >= 2
        floor_vs_min += pair_display(n) != strict_pairs(sides)
        d, k = running_mismatch(sides)
        run_diff, run_n = run_diff + d, run_n + k
        for x in steps:
            if x["side"] is not None:
                margins.append(abs(x["dMm"]))
                if x["kneeSide"] is not None:
                    knee_n += 1
                    knee_agree += x["kneeSide"] == x["side"]
            for v in x["kneeFwdMm"]:
                if math.isfinite(v):
                    fwd_n += 1
                    fwd_pos += v > 0
    ups = {w: [round(float(v), 3) for v in p["up"]] for w, p in sorted(poses.cache.items()) if p is not None}
    m = np.array(margins) if margins else np.array([np.nan])
    return {"sets": n_sets, "oddTruthSets": odd_sets, "countMatchesTruth": match, "countWithin1": within1,
            "steps": total, "stepsDecided": decided,
            "switchFraction": sw / sw_n if sw_n else float("nan"), "switchPairs": sw_n,
            "setsLRDiffer": lr_differ, "setsLRDifferBy2": lr_differ2, "setsFloorNeMin": floor_vs_min,
            "runningMismatchFraction": run_diff / run_n if run_n else float("nan"), "runningInstants": run_n,
            "kneeCueAgreement": knee_agree / knee_n if knee_n else float("nan"), "kneeCueCompared": knee_n,
            "kneeForwardPositive": fwd_pos / fwd_n if fwd_n else float("nan"), "kneeForwardChecked": fwd_n,
            "marginMmMedian": float(np.nanmedian(m)), "marginMmP5": float(np.nanpercentile(m, 5)),
            "upByWorkout": ups}


def group_of(w: str) -> str:
    """w16~w20 은 영상 시간축이 라벨과 어긋났던 워크아웃이다(설계 §16.1) — 정렬 뒤에도 따로 본다."""
    return "w00-w15" if int(w[1:]) <= 15 else "w16-w20"


def perfectly_alternating(sides: list[str | None]) -> bool:
    return bool(sides) and None not in sides and all(a != b for a, b in zip(sides, sides[1:]))


def source_disagreement(ref: dict[str, list[dict]], step_rows: list[dict]) -> dict:
    """두 단안 파이프라인(3D 기준 · MediaPipe 앞발 규칙)이 어긋나는 걸음의 성격 — 워크아웃 묶음별.
    세트마다 어느 쪽이 완전 교대인지, MediaPipe 가 틀린 걸음에서 세 단서가 **함께** 뒤집혔는지(다리 이름이 통째로 바뀐 표지 —
    기하 단서가 약해서라면 단서끼리 갈린다), 틀린 방향(기준 R → MP L 등)."""
    by_set = defaultdict(list)
    for r in step_rows:
        by_set[r["set"]].append(r)
    out = {}
    for g in ("w00-w15", "w16-w20"):
        c = Counter()
        for cap, rows in by_set.items():
            if rows[0]["group"] != g:
                continue
            rows = sorted(rows, key=lambda r: r["k"])
            ref_alt = perfectly_alternating([s["side"] for s in ref.get(cap, [])])
            mp_alt = perfectly_alternating([r["mp"]["front"]["side"] for r in rows])
            c["sets"] += 1
            c["refAlternates"] += ref_alt
            c["mpAlternates"] += mp_alt
            c["neitherAlternates"] += not ref_alt and not mp_alt
            first = ref.get(cap, [{}])[0].get("side") if ref.get(cap) else None
            c["refStartsR"] += first == "R"
            for r in rows:
                mp = r["mp"]["front"]["side"]
                if r["ref"] is None or mp is None or mp == r["ref"]:
                    continue
                c["disagreeSteps"] += 1
                c["disagreeAllMpCuesFlipped"] += all(r["mp"][q]["side"] == mp for q in SIDE_CUES)
                c[f"disagree_ref{r['ref']}_mp{mp}"] += 1
        out[g] = dict(c)
    return out


def analyze_lunges_mp(index: dict, results: dict[str, dict], poses: PoseCache, ref: dict[str, list[dict]],
                      configs: tuple[str, ...]) -> tuple[list[dict], list[dict], dict]:
    """MediaPipe 캡처에서 (기준 걸음별 판별 행, 카운터 사이클별 판별 행, 세트별 요약)."""
    step_rows, cycle_rows, per_set = [], [], {}
    for s in lunge_sets(index):
        pose = poses.get(s["workout"])
        path = Path(s["capture"])
        if pose is None or not path.is_file():
            continue
        lag = int(s.get("lagFrames") or 0)
        cap = read_capture_full(path)
        km = mp_knee_mean(cap)
        cues = mp_side_cues(cap)
        t = cap["t"]
        steps = ref.get(s["capture"], [])
        # (a) 3D 기준 걸음의 바닥 구간 시각에서 MediaPipe 규칙
        for k, st in enumerate(steps):
            lo = video_ms(st["deepFrames"][0], lag) - REF_SAMPLE_PAD_MS
            hi = video_ms(st["deepFrames"][1], lag) + REF_SAMPLE_PAD_MS
            idx = np.where((t >= lo) & (t <= hi))[0]
            if len(idx) == 0:
                near = int(np.argmin(np.abs(t - video_ms(st["frame"], lag))))
                idx = np.array([near]) if abs(int(t[near]) - video_ms(st["frame"], lag)) <= 300 else idx
            row = {"set": s["capture"], "workout": s["workout"], "group": group_of(s["workout"]), "k": k,
                   "ref": st["side"], "refMarginMm": abs(st["dMm"]) if math.isfinite(st["dMm"]) else None,
                   "samples": int(len(idx)), "mp": cue_sides(cues, idx)}
            step_rows.append(row)
        ref_ms = [video_ms(st["frame"], lag) for st in steps]
        per_set[s["capture"]] = {"workout": s["workout"], "truth": s["truthReps"], "refSteps": len(steps),
                                 "refSides": "".join(x["side"] or "?" for x in steps), "configs": {}}
        # (b) 카운터 사이클
        for c in configs:
            r = results.get(f"{run_replay.set_key(s)}|{c}")
            if r is None or "error" in r:
                continue
            fires_all = sorted(run_replay.fire_times(r))
            wins = cycle_windows(r, fires_all)
            fires_in = in_window(fires_all, s)
            sides_mp, sides_3d = [], []
            for j, tf in enumerate(fires_in):
                lo, hi = wins[tf]
                sel = (t > lo) & (t <= hi)
                idx = deep_samples(km, sel)
                mp = cue_sides(cues, idx)
                d3, cons3 = majority(d3_at(pose, frames_of(t[idx], lag))) if len(idx) else (float("nan"), float("nan"))
                ref_side = side_of(d3) if cons3 == cons3 and cons3 >= CONSISTENT else None
                n_ref = sum(lo < x <= hi for x in ref_ms)
                cycle_rows.append({"set": s["capture"], "workout": s["workout"], "group": group_of(s["workout"]),
                                   "config": c, "k": j, "fireMs": tf, "windowMs": [lo, hi], "deepSamples": int(len(idx)),
                                   "refStepsInWindow": n_ref, "ref": ref_side, "refConsistency": cons3,
                                   "refDMm": d3, "mp": mp})
                sides_mp.append(mp["front"]["side"])
                sides_3d.append(ref_side)
            # 조기 도달: 캡처 시작부터 센 사이클이 목표에 닿은 발화 < 기준 걸음의 그 걸음 바닥 (기준 걸음 수 = 정답일 때만)
            truth = s["truthReps"]
            early = {}
            if len(steps) == truth:
                for unit, target_cycles in (("step", truth), ("pair", 2 * truth_pairs(truth))):
                    if target_cycles == 0:
                        continue
                    reached = fires_all[target_cycles - 1] if len(fires_all) >= target_cycles else None
                    early[unit] = None if reached is None else bool(reached < ref_ms[target_cycles - 1])
            per_set[s["capture"]]["configs"][c] = {
                "cyclesIn": len(fires_in), "cyclesAll": len(fires_all),
                "lead": sum(x < s["startMs"] - SET_MARGIN_MS for x in fires_all),
                "sidesMp": "".join(x or "?" for x in sides_mp), "sides3d": "".join(x or "?" for x in sides_3d),
                "strictMp": strict_pairs(sides_mp), "strict3d": strict_pairs(sides_3d), "early": early}
    return step_rows, cycle_rows, per_set


def cue_agreement(rows: list[dict], ref_key: str = "ref", margin_cm: float = 0.0) -> dict:
    """단서별 (판별 수, 일치 수, 모호로 뺀 수). margin_cm 은 앞발 단서에만(|d| ≤ margin 이면 판별 안 함)."""
    out = {}
    for cue in SIDE_CUES:
        n = agree = undecided = no_ref = 0
        for r in rows:
            ref = r[ref_key]
            if ref is None:
                no_ref += 1
                continue
            d = r["mp"][cue]["d"]
            side = side_of(d, margin_cm if cue == "front" else 0.0)
            if side is None:
                undecided += 1
                continue
            n += 1
            agree += side == ref
        out[cue] = {"compared": n, "agree": agree, "agreement": agree / n if n else float("nan"),
                    "undecided": undecided, "refAmbiguous": no_ref}
    return out


def cycle_summary(cycle_rows: list[dict], per_set: dict, config: str, sets: list[dict]) -> dict:
    rows = [r for r in cycle_rows if r["config"] == config]
    clean = sum(r["refStepsInWindow"] == 1 for r in rows)
    merged = sum(r["refStepsInWindow"] >= 2 for r in rows)
    spurious = sum(r["refStepsInWindow"] == 0 for r in rows)
    agree = {f"margin{int(m)}cm": cue_agreement(rows, margin_cm=m)["front"] for m in MARGINS_CM}
    agree_all_cues = cue_agreement(rows)
    by_group = {g: cue_agreement([r for r in rows if r["group"] == g])["front"] for g in ("w00-w15", "w16-w20")}
    clean_only = cue_agreement([r for r in rows if r["refStepsInWindow"] == 1])["front"]
    # 세트 단위: floor(c/2) · min(L,R) MediaPipe · min(L,R) 같은 시각 3D  vs 정답 쌍
    disp = {"floor": [], "strictMp": [], "strict3d": []}
    floor_ne_min_mp = floor_ne_min_3d = 0
    early = {"step": [0, 0, 0, 0], "pair": [0, 0, 0, 0]}     # [조기, 판정한 세트, 목표에 못 닿은 세트, 조기 중 세트 앞 헛사이클이 있는 세트]
    for s in sets:
        ps = per_set.get(s["capture"], {}).get("configs", {}).get(config)
        if ps is None:
            continue
        tp = truth_pairs(s["truthReps"])
        disp["floor"].append({"pairErr": pair_display(ps["cyclesIn"]) - tp})
        disp["strictMp"].append({"pairErr": ps["strictMp"] - tp})
        disp["strict3d"].append({"pairErr": ps["strict3d"] - tp})
        floor_ne_min_mp += pair_display(ps["cyclesIn"]) != ps["strictMp"]
        floor_ne_min_3d += pair_display(ps["cyclesIn"]) != ps["strict3d"]
        for unit in ("step", "pair"):
            e = ps["early"].get(unit, "n/a")
            if e == "n/a":
                continue
            if e is None:
                early[unit][2] += 1
            else:
                early[unit][1] += 1
                early[unit][0] += e
                early[unit][3] += bool(e) and ps["lead"] > 0
    # 앞발 판별이 3D 기준과 다른 사이클 — 확신(|d|)이 여유 문턱으로 걸러지는 크기인가, 한 사람에게 몰리는가(설계 §18.2)
    decided = [r for r in rows if r["ref"] is not None and side_of(r["mp"]["front"]["d"]) is not None]
    wrong = [r for r in decided if side_of(r["mp"]["front"]["d"]) != r["ref"]]
    wrong_abs = sorted(abs(r["mp"]["front"]["d"]) for r in wrong)
    right_abs = [abs(r["mp"]["front"]["d"]) for r in decided if side_of(r["mp"]["front"]["d"]) == r["ref"]]
    front_wrong = {"n": len(wrong), "decided": len(decided), "absCmSorted": [round(x, 1) for x in wrong_abs],
                   "absCmMedian": round(float(np.median(wrong_abs)), 1) if wrong_abs else None,
                   "overLargestMarginCm": sum(x > max(MARGINS_CM) for x in wrong_abs),
                   "correctAbsCmMedian": round(float(np.median(right_abs)), 1) if right_abs else None,
                   "bySubject": dict(sorted(Counter(run_replay.subject_of({"workout": r["workout"]}) for r in wrong).items())),
                   "byWorkout": dict(sorted(Counter(r["workout"] for r in wrong).items()))}
    return {"cycles": len(rows), "cleanCycles": clean, "mergedCycles": merged, "spuriousCycles": spurious,
            "frontAgreement": agree, "allCues": agree_all_cues, "frontByGroup": by_group, "frontCleanCyclesOnly": clean_only,
            "frontWrong": front_wrong,
            "display": {k: pair_scores(v) for k, v in disp.items()},
            "setsFloorNeStrictMp": floor_ne_min_mp, "setsFloorNeStrict3d": floor_ne_min_3d,
            "earlyReach": {u: {"early": v[0], "judged": v[1], "notReached": v[2], "earlyWithLeadCycles": v[3]} for u, v in early.items()}}


def q1_table(index: dict, results: dict[str, dict], config: str, windows: bool) -> dict:
    rows, rows_all = [], []
    for s in lunge_sets(index):
        r = results.get(f"{run_replay.set_key(s)}|{config}")
        if r is None or "error" in r:
            continue
        sp = run_replay.split_set(s, r)
        rows.append(pair_row(sp["count"], s["truthReps"]))
        if windows and sp["lead"] is not None:
            total = sp["count"] + (sp["lead"] or 0) + (sp["after"] or 0)
            row = pair_row(total, s["truthReps"])
            row["leadOdd"] = (sp["lead"] or 0) % 2 == 1
            rows_all.append(row)
    even = [r for r in rows if not r["odd"]]
    out = {"sets": len(rows), "oddTruthSets": sum(r["odd"] for r in rows),
           "step": pair_scores(rows, "stepErr"), "pair": pair_scores(rows), "pairEvenTruthOnly": pair_scores(even),
           # 쌍 단위가 가리는 오차: 걸음은 틀렸는데 쌍은 맞은 세트(짝수 정답 + 1 과다, 홀수 정답 − 1 과소)
           "pairExactStepWrong": sum(r["pairErr"] == 0 and r["stepErr"] != 0 for r in rows),
           "stepErrWhenHidden": dict(Counter(r["stepErr"] for r in rows if r["pairErr"] == 0 and r["stepErr"] != 0)),
           "alwaysFivePairs": pair_scores([{"pairErr": 5 - r["truthPairs"]} for r in rows])}
    if rows_all:
        out["withWindows"] = {"pair": pair_scores(rows_all), "step": pair_scores(rows_all, "stepErr"),
                              "setsLeadOdd": sum(r["leadOdd"] for r in rows_all)}
    return out


# ---------------------------------------------------------------- Q3 컬

def mirror_arm(P: np.ndarray, Ps: np.ndarray, src: str) -> np.ndarray:
    """P (17,3) 의 반대 팔을 Ps (17,3, 지연된 원본 프레임) 의 src 팔 움직임을 몸 정중면에 비춘 것으로 바꾼다.
    반사는 직교 변환이라 팔꿈치 각이 원본 팔과 정확히 같다(동시 컬). 어깨는 P 의 반대 어깨 그대로."""
    dst = "r" if src == "l" else "l"
    e = Ps[H36["lsho"]] - Ps[H36["rsho"]]
    e = e / np.linalg.norm(e)
    M = np.eye(3) - 2.0 * np.outer(e, e)
    out = P.copy()
    u = Ps[H36[f"{src}elb"]] - Ps[H36[f"{src}sho"]]
    w = Ps[H36[f"{src}wri"]] - Ps[H36[f"{src}elb"]]
    out[H36[f"{dst}elb"]] = P[H36[f"{dst}sho"]] + M @ u
    out[H36[f"{dst}wri"]] = out[H36[f"{dst}elb"]] + M @ w
    return out


def build_synth_simultaneous(root: Path, index3d: dict, out: Path, lag_ms: int, poses: PoseCache) -> dict:
    """합성 동시 컬 캡처(원래 팔 src 의 움직임을 반대 팔에 거울로, 반대 팔은 lag_ms 늦게). 세트마다 src = l, r 두 개.
    정답 = 원래 팔의 컬 수(3D 지그재그 35°) — 원래 세트 정답이 짝수이고 그 수가 정답/2 와 같을 때만, 아니면 None(채점 제외)."""
    lag_f = int(round(lag_ms * FPS / 1000.0))
    idx = {"source": f"SYNTHETIC simultaneous curl from MM-Fit pose_3d (mirror, lag {lag_ms} ms) — not MediaPipe",
           "lagMs": lag_ms, "sets": []}
    for s in index3d["sets"]:
        if s["exercise"] != CURL:
            continue
        pose = poses.get(s["workout"])
        if pose is None:
            continue
        J, f0 = pose["J"], pose["f0"]
        st, en = int(s["startFrame"]), int(s["endFrame"])
        for src in ("l", "r"):
            a = max(0, st - SEGMENT_PAD_FRAMES - f0)
            b = min(len(J) - 1, en + SEGMENT_PAD_FRAMES - f0)
            el = angle(J[a:b + 1, H36[f"{src}sho"]], J[a:b + 1, H36[f"{src}elb"]], J[a:b + 1, H36[f"{src}wri"]])
            n_src = sum(st - MARGIN_FRAMES <= f0 + a + m <= en + MARGIN_FRAMES for m in zigzag_minima(el, CURL_ZIGZAG_DEG))
            truth = n_src if (s["truthReps"] % 2 == 0 and n_src == s["truthReps"] // 2) else None
            frames = [f for f in range(st - MARGIN_FRAMES, en + MARGIN_FRAMES + 1) if 0 <= f - f0 < len(J)]
            times = [int(round(f * 1000.0 / FPS)) for f in frames]
            lines = []
            for k in app_cadence(times):
                f = frames[k] - f0
                g = max(0, f - lag_f)
                P = J[f]
                if not (np.all(np.isfinite(P)) and np.all(np.isfinite(J[g]))):
                    lines.append(frame_line(times[k], None))
                    continue
                lines.append(frame_line(times[k], mp_landmarks(mirror_arm(P, J[g], src).T)))
            name = f"{s['workout']}/{Path(s['capture']).stem}_{src}2{'r' if src == 'l' else 'l'}.cap"
            write_capture(out / name, {"source": "synthetic_simultaneous_curl", "workout": s["workout"], "src": src,
                                       "lagMs": lag_ms}, lines)
            idx["sets"].append({"workout": s["workout"], "ordinal": s["ordinal"], "activity": s["activity"], "exercise": CURL,
                                "truthReps": truth, "sourceArmCurls": n_src, "originalTruth": s["truthReps"], "src": src,
                                "startMs": s["startMs"], "endMs": s["endMs"], "capture": name})
    (out / "index.json").write_text(json.dumps(idx, ensure_ascii=False, indent=1), encoding="utf-8")
    return idx


def curl_counts(index: dict, results: dict[str, dict], config: str) -> dict[str, int]:
    """세트 캡처 → 세트 창 안 발화 수(run_replay.split_set 과 같은 창)."""
    out = {}
    for s in index["sets"]:
        if s["exercise"] != CURL:
            continue
        r = results.get(f"{run_replay.set_key(s)}|{config}")
        if r is None or "error" in r:
            continue
        out[s["capture"]] = run_replay.split_set(s, r)["count"]
    return out


def lane_coverage(s: dict, base: Path) -> float:
    """세트 창 안 샘플 중 두 팔꿈치 각이 모두 계산되는 비율의 작은 쪽(앱 후처리 흉내 — 가시성 0.5 미만 관절은 없음)."""
    path = Path(s["capture"])
    path = path if path.is_absolute() else base / path
    cap = read_capture_full(path)
    _, feat = app_features(cap)
    sel = (cap["t"] >= s["startMs"] - SET_MARGIN_MS) & (cap["t"] <= s["endMs"] + SET_MARGIN_MS)
    if not sel.any():
        return float("nan")
    return float(min(np.isfinite(feat["elbow_L"][sel]).mean(), np.isfinite(feat["elbow_R"][sel]).mean()))


def lane_summary(index: dict, results: dict[str, dict], truth_fn, singles: tuple[str, ...], base: Path) -> dict:
    """레인 쌍 min(nL, nR) 과 단일 신호(사이클 c, 쌍 표시 floor(c/2))를 정답 쌍 truth_fn(s) 에 대해.
    base = index 의 상대 캡처 경로 기준 폴더(레인 과소 세트의 팔 가시성을 읽는다)."""
    nl = curl_counts(index, results, LANE_CONFIGS[0])
    nr = curl_counts(index, results, LANE_CONFIGS[1])
    rows, unlabeled, lane_diff = [], 0, Counter()
    under_low_cov = 0
    single = {c: curl_counts(index, results, c) for c in singles}
    single_rows = {c: {"cycles": [], "floor": []} for c in singles}
    for s in index["sets"]:
        if s["exercise"] != CURL or s["capture"] not in nl or s["capture"] not in nr:
            continue
        tp = truth_fn(s)
        if tp is None:
            unlabeled += 1
            continue
        a, b = nl[s["capture"]], nr[s["capture"]]
        lane_diff[min(abs(a - b), 3)] += 1
        rows.append({"pairErr": min(a, b) - tp})
        if min(a, b) < tp:
            under_low_cov += lane_coverage(s, base) < LANE_LOW_COVERAGE
        for c in singles:
            if s["capture"] in single[c]:
                v = single[c][s["capture"]]
                single_rows[c]["cycles"].append({"pairErr": v - tp})
                single_rows[c]["floor"].append({"pairErr": pair_display(v) - tp})
    return {"lanes": pair_scores(rows), "unlabeled": unlabeled, "underSetsWeakLaneCoverageBelow": under_low_cov,
            "laneCountDiff": {("≥3" if k == 3 else str(k)): v for k, v in sorted(lane_diff.items())},
            "singles": {c: {"cyclesVsPairs": pair_scores(v["cycles"]), "floorHalfVsPairs": pair_scores(v["floor"])}
                        for c, v in single_rows.items()}}


# ---------------------------------------------------------------- 요약 표

def _p(v, fmt=".2f") -> str:
    if v is None or (isinstance(v, float) and v != v):
        return "—"
    return format(v, fmt)


def _sc(m: dict) -> str:
    if not m.get("sets"):
        return "—"
    return f"{m['exact']:.2f} / {m['within1']:.2f} / {m['overSets']} / {m['underSets']}"


def markdown(S: dict) -> str:
    L = ["# 좌우 한 쌍 = 1회 — 타당성 조사 (MM-Fit, 개발 데이터)", "",
         "`side_attribution.py` 출력. **성능 주장이 아니다** — MM-Fit 은 개발 데이터이고 정면 고정 카메라 한 대다(앱 권장 방향은 런지 B 대각).",
         "3D 기준(pose_3d)도 단안 추정이라 참 좌우 정답이 아니다. 표기: 정확 / ±1 / 과다 세트 / 과소 세트.", ""]
    q1 = S["q1"]
    L += ["## Q1 런지 — 쌍 단위 표시 floor(사이클/2) 대 정답 쌍 floor(정답/2)", "",
          "세트 창 안 발화만(run_replay 와 같은 창). '앞뒤 포함' = 캡처 시작(라벨 15 s 전)부터 끝까지 센 전체 — 앱이 세트 끝에 보일 수에 가깝다.", "",
          "| 자료 · 구성 | 세트 (홀수 정답) | 걸음 단위 (지금) | 쌍 단위 | 쌍 단위, 짝수 정답만 | 쌍은 맞고 걸음은 틀린 세트 (걸음 오차) | 앞뒤 포함: 걸음 단위 · 쌍 단위 (앞 헛카운트 홀수 세트) | 항상 5쌍 |",
          "|---|---|---|---|---|---|---|---|"]
    for corpus, by_c in q1.items():
        for c, m in by_c.items():
            ww = m.get("withWindows")
            wtxt = f"{_sc(ww['step'])} · {_sc(ww['pair'])} ({ww['setsLeadOdd']})" if ww else "—"
            hid = ", ".join(f"{int(k):+d}: {v}" for k, v in sorted(m["stepErrWhenHidden"].items(), key=lambda kv: int(kv[0]))) or "—"
            L.append(f"| {corpus} · {c} | {m['sets']} ({m['oddTruthSets']}) | {_sc(m['step'])} | {_sc(m['pair'])} | "
                     f"{_sc(m['pairEvenTruthOnly'])} | {m['pairExactStepWrong']} ({hid}) | {wtxt} | {_sc(m['alwaysFivePairs'])} |")
    q2 = S["q2"]
    L += ["", "목표 도달 자동 진행이 해당 걸음 **바닥보다 먼저** 걸린 세트 (MediaPipe, 앞 15 s 포함, 3D 기준 걸음 수 = 정답인 세트만):", "",
          "| 구성 | 걸음 단위 목표(정답 걸음) 조기 / 도달 (미도달) | 쌍 단위 목표(floor(정답/2) 쌍) 조기 / 도달 (미도달) | 조기 세트 중 세트 앞 헛사이클이 있는 세트 (걸음 · 쌍) |",
          "|---|---|---|---|"]
    for c, v in q2["counterCycles"].items():
        e = v["earlyReach"]
        L.append(f"| {c} | {e['step']['early']} / {e['step']['judged']} ({e['step']['notReached']}) | "
                 f"{e['pair']['early']} / {e['pair']['judged']} ({e['pair']['notReached']}) | "
                 f"{e['step']['earlyWithLeadCycles']} · {e['pair']['earlyWithLeadCycles']} |")
    groups = ("w00-w15", "w16-w20")
    ref, rg = q2["reference"], q2["referenceByGroup"]

    def rrow(label, key, fmt=None, extra=None):
        cells = []
        for m in (ref, rg[groups[0]], rg[groups[1]]):
            v = m[key]
            cells.append((_p(v, fmt) if fmt else str(v)) + (f" ({m[extra]})" if extra else ""))
        return f"| {label} | " + " | ".join(cells) + " |"
    L += ["", "## Q2 걸음 좌우 판별", "", "### 3D 기준 (MM-Fit pose_3d, 앞발 규칙) — 기준 자체의 점검", "",
          "| 항목 | 전체 | w00–w15 | w16–w20 |", "|---|---|---|---|",
          rrow("세트", "sets"), rrow("기준 걸음 수 = 정답인 세트", "countMatchesTruth"),
          rrow(f"걸음 · 판별한 걸음 (바닥 구간 부호 일치 ≥ {CONSISTENT})", "stepsDecided", extra="steps"),
          rrow("교대율 (연속 두 걸음의 다리가 바뀐 비율)", "switchFraction", ".3f"),
          rrow("좌우 수가 다른 세트", "setsLRDiffer"), rrow("세트 끝 floor(걸음/2) ≠ min(L, R) 인 세트", "setsFloorNeMin"),
          rrow("세트 도중 floor(k/2) ≠ min(L_k, R_k) 인 순간 비율", "runningMismatchFraction", ".3f"),
          rrow("앞발 거리 중앙값 (mm)", "marginMmMedian", ".0f"),
          rrow("가정 ① 굽힌 무릎이 F 쪽 (양수 비율)", "kneeForwardPositive", ".3f"),
          rrow("다른 단서 '뒷무릎이 낮다'(3D) 와 앞발 규칙의 일치", "kneeCueAgreement", ".3f")]
    sd = q2["sourceDisagreement"]
    L += ["", "### 두 파이프라인이 어긋나는 걸음 — 3D 기준 걸음의 바닥 시각에서", "",
          "| 워크아웃 | 세트 | 완전 교대: 3D 기준 · MediaPipe · 둘 다 아님 | 3D 기준이 R 로 시작 | 어긋난 걸음 | 그중 MediaPipe 세 단서가 함께 뒤집힘 | 방향 (기준→MP) |",
          "|---|---|---|---|---|---|---|"]
    for g in groups:
        c = sd.get(g, {})
        dirs = ", ".join(f"{k.split('_')[1][3:]}→{k.split('_')[2][2:]} {v}" for k, v in sorted(c.items()) if k.startswith("disagree_"))
        L.append(f"| {g} | {c.get('sets', 0)} | {c.get('refAlternates', 0)} · {c.get('mpAlternates', 0)} · {c.get('neitherAlternates', 0)} | "
                 f"{c.get('refStartsR', 0)} | {c.get('disagreeSteps', 0)} | {c.get('disagreeAllMpCuesFlipped', 0)} | {dirs or '—'} |")
    at = q2["mpAtReferenceSteps"]
    L += ["", "### MediaPipe 규칙 대 3D 기준 — 3D 기준 걸음의 바닥 시각에서 (카운터와 무관)", "",
          "| 단서 | 전체 일치 (비교 수) | w00–w15 | w16–w20 | 판별 못 함 |", "|---|---|---|---|---|"]
    for cue in SIDE_CUES:
        a, g1, g2 = at["all"][cue], at[groups[0]][cue], at[groups[1]][cue]
        L.append(f"| {CUE_LABEL[cue]} | {_p(a['agreement'], '.3f')} ({a['compared']}) | {_p(g1['agreement'], '.3f')} ({g1['compared']}) | "
                 f"{_p(g2['agreement'], '.3f')} ({g2['compared']}) | {a['undecided']} |")
    L += ["", "### 카운터 사이클에 붙인 좌우 (MediaPipe 영상, 세트 창 안) — 세트 표시 대 정답 쌍", "",
          "| 구성 · 워크아웃 | 사이클 (깨끗 · 병합 · 헛) | 앞발 일치 여유 0 / 10 / 20 cm (판별 못 함) | floor(c/2) | min(L,R) MediaPipe | min(L,R) 같은 시각 3D | floor ≠ min(MP) 세트 |",
          "|---|---|---|---|---|---|---|"]
    for c in q2["counterCycles"]:
        for g, v in [("전체", q2["counterCycles"][c])] + [(g, q2["counterCyclesByGroup"][c][g]) for g in groups]:
            fa = " / ".join(f"{_p(v['frontAgreement'][f'margin{int(m)}cm']['agreement'], '.3f')} ({v['frontAgreement'][f'margin{int(m)}cm']['undecided']})"
                            for m in MARGINS_CM)
            L.append(f"| {c} · {g} | {v['cycles']} ({v['cleanCycles']} · {v['mergedCycles']} · {v['spuriousCycles']}) | {fa} | "
                     f"{_sc(v['display']['floor'])} | {_sc(v['display']['strictMp'])} | {_sc(v['display']['strict3d'])} | {v['setsFloorNeStrictMp']} |")
    L += ["", f"앞발 판별이 3D 기준과 다른 사이클 — 확신(|d|, cm)과 사람. '여유 밖' = 가장 큰 여유 문턱({max(MARGINS_CM):.0f} cm)보다 큰 |d| — 문턱으로 걸러지지 않는다.", "",
          "| 구성 · 워크아웃 | 틀림 / 판별 | 틀림 |d| 중앙값 (맞음 |d| 중앙값) | 여유 밖 | 사람별 | 워크아웃별 |", "|---|---|---|---|---|---|"]
    for c in q2["counterCycles"]:
        for g, v in [("전체", q2["counterCycles"][c])] + [(g, q2["counterCyclesByGroup"][c][g]) for g in groups]:
            w = v["frontWrong"]
            L.append(f"| {c} · {g} | {w['n']} / {w['decided']} | {_p(w['absCmMedian'], '.1f')} ({_p(w['correctAbsCmMedian'], '.1f')}) | "
                     f"{w['overLargestMarginCm']} | {', '.join(f'{k} {n}' for k, n in w['bySubject'].items()) or '—'} | "
                     f"{', '.join(f'{k} {n}' for k, n in w['byWorkout'].items()) or '—'} |")
    q3 = S["q3"]
    L += ["", "## Q3 컬 — 팔별 레인 쌍 min(nL, nR) (연구만)", "",
          f"| 자료 | 정답 쌍 (채점 제외) | 레인 쌍 | 레인 과소 세트 중 약한 팔 가시 < {LANE_LOW_COVERAGE:.0%} | 레인 수 차이 0/1/2/≥3 | 단일 신호 — 사이클 c 대 정답 쌍 · floor(c/2) 대 정답 쌍 |",
          "|---|---|---|---|---|---|"]
    for name, v in q3.items():
        diff = " / ".join(str(v["laneCountDiff"].get(k, 0)) for k in ("0", "1", "2", "≥3"))
        singles = "<br>".join(f"`{c}` {_sc(x['cyclesVsPairs'])} · {_sc(x['floorHalfVsPairs'])}" for c, x in v["singles"].items())
        L.append(f"| {name} | {v['truthNote']} ({v['unlabeled']}) | {_sc(v['lanes'])} | {v['underSetsWeakLaneCoverageBelow']} | {diff} | {singles} |")
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------- 실행

def run_all(args) -> int:
    out, results_dir = args.out, args.results
    out.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    poses = PoseCache(args.root)
    mp_index = json.loads((args.mp_index / "index.json").read_text(encoding="utf-8"))
    mp_results = run_replay.load_results(args.mp_replay)
    idx3d = json.loads((args.pose3d_captures / "index.json").read_text(encoding="utf-8"))

    # Q1 — 3D 재생(live, hysteresis)을 지금 앱 신호로 다시
    _, res3d, _ = run_replay.replay_index(args.pose3d_captures, out / "pose3d", {"live", "hysteresis"}, skip_replay=args.skip_replay)
    q1 = {"MediaPipe 정렬": {c: q1_table(mp_index, mp_results, c, windows=True) for c in ("live", "hysteresis")},
          "3D (pose_3d)": {c: q1_table(idx3d, res3d, c, windows=False) for c in ("live", "hysteresis")}}

    # Q2
    sets = lunge_sets(mp_index)
    ref = analyze_reference(poses, sets)
    step_rows, cycle_rows, per_set = analyze_lunges_mp(mp_index, mp_results, poses, ref, ("live", "hysteresis"))
    ref_summary = reference_summary(sets, ref, poses)
    at_ref = {"all": cue_agreement(step_rows)}
    for g in ("w00-w15", "w16-w20"):
        at_ref[g] = cue_agreement([r for r in step_rows if r["group"] == g])
    at_ref["frontByMargin"] = {f"margin{int(m)}cm": cue_agreement(step_rows, margin_cm=m)["front"] for m in MARGINS_CM}
    groups = ("w00-w15", "w16-w20")
    q2 = {"reference": ref_summary,
          "referenceByGroup": {g: reference_summary([s for s in sets if group_of(s["workout"]) == g], ref, poses) for g in groups},
          "sourceDisagreement": source_disagreement(ref, step_rows),
          "mpAtReferenceSteps": at_ref,
          "counterCycles": {c: cycle_summary(cycle_rows, per_set, c, sets) for c in ("live", "hysteresis")},
          "counterCyclesByGroup": {c: {g: cycle_summary([r for r in cycle_rows if r["group"] == g], per_set, c,
                                                        [s for s in sets if group_of(s["workout"]) == g]) for g in groups}
                                   for c in ("live", "hysteresis")}}
    for g in groups:
        q2["referenceByGroup"][g].pop("upByWorkout", None)
    with (out / "lunge_steps.jsonl").open("w", encoding="utf-8") as fh:
        for cap, steps in ref.items():
            fh.write(json.dumps({"set": cap, "steps": steps}, ensure_ascii=False) + "\n")
    with (out / "lunge_cycles.jsonl").open("w", encoding="utf-8") as fh:
        for r in cycle_rows + [dict(x, config="referenceStep") for x in step_rows]:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    # Q3 — 레인 재생
    base_mp = args.mp_index
    lane_mp = run_rows(jvm_rows(mp_index, CURL, LANE_CONFIGS), base_mp, out / "lanes_mp", args.skip_replay)
    lane_mp.update({k: v for k, v in mp_results.items() if k.endswith(("|hysteresis", "|hysteresis+elbow_minside", "|live"))})
    lane_3d = run_rows(jvm_rows(idx3d, CURL, LANE_CONFIGS + ("hysteresis+elbow_minside",)), args.pose3d_captures,
                       out / "lanes_pose3d", args.skip_replay)
    lane_3d.update({k: v for k, v in res3d.items() if k.endswith(("|hysteresis", "|live"))})
    singles = ("live", "hysteresis", "hysteresis+elbow_minside")
    half = lambda s: truth_pairs(s["truthReps"]) if s.get("truthReps") is not None else None  # noqa: E731
    q3 = {"MediaPipe 정렬 · 교대 컬": dict(lane_summary(mp_index, lane_mp, half, singles, args.mp_index), truthNote="floor(정답/2)"),
          "3D · 교대 컬": dict(lane_summary(idx3d, lane_3d, half, singles, args.pose3d_captures), truthNote="floor(정답/2)")}
    for lag in SYNTH_LAGS_MS:
        sdir = out / "synth_simul_curl" / f"lag{lag}"
        sidx = build_synth_simultaneous(args.root, idx3d, sdir, lag, poses)
        rows = jvm_rows(sidx, CURL, ("live", "hysteresis", "hysteresis+elbow_minside") + LANE_CONFIGS)
        sres = run_rows(rows, sdir, sdir / "replay", args.skip_replay)
        q3[f"합성 동시 컬 (3D 거울, 팔 사이 {lag} ms)"] = dict(
            lane_summary(sidx, sres, lambda s: s.get("truthReps"), singles, sdir), truthNote="원래 팔 컬 수 = 동시 컬 수")

    S = {"note": "MM-Fit 은 개발 데이터 — 타당성이지 성능 주장이 아니다. 3D 기준은 단안 추정이라 참 좌우 정답이 아니다.",
         "constants": {"stepZigzagDeg": STEP_ZIGZAG_DEG, "curlZigzagDeg": CURL_ZIGZAG_DEG, "deepBandDeg": DEEP_BAND_DEG,
                       "consistent": CONSISTENT, "setMarginMs": SET_MARGIN_MS, "liveWindowCapMs": LIVE_WINDOW_CAP_MS,
                       "refSamplePadMs": REF_SAMPLE_PAD_MS, "appUp": APP_UP.tolist()},
         "q1": q1, "q2": q2, "q3": q3}
    (out / "summary_full.json").write_text(json.dumps({**S, "perSetLunge": per_set}, ensure_ascii=False, indent=1), encoding="utf-8")
    (results_dir / "summary.json").write_text(json.dumps(S, ensure_ascii=False, indent=1), encoding="utf-8")
    md = markdown(S)
    (results_dir / "summary.md").write_text(md, encoding="utf-8")
    print(md)
    return 0


# ---------------------------------------------------------------- 자체 검사 (합성 자료, 정확한 결과)

def synth_lunge(sides: str, yaw_deg: float = 0.0, tilt_deg: float = 0.0, step_s: float = 2.0, rest_s: float = 1.0) -> np.ndarray:
    """H36M 17관절(mm) 합성 런지 — sides 순서로 앞발을 딛는다. 몸은 yaw 0 에서 −y 를 본다(MM-Fit pose_3d 와 같은 쪽 → 앱 좌표에서 카메라 쪽),
    z 가 위. 그 뒤 z 축 yaw 회전, x 축 tilt 회전(카메라 기울기 흉내)을 준다. 반환 (N, 17, 3)."""
    fwd, left, up = np.array([0.0, -1.0, 0.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0])

    def pose(w: float, side: str | None) -> np.ndarray:
        P = np.zeros((17, 3))
        pel = up * (950 - 400 * w)
        P[0] = pel
        for s_, hip, knee, ank in (("L", "lhip", "lknee", "lank"), ("R", "rhip", "rknee", "rank")):
            lat = left * (100 if s_ == "L" else -100)
            h = pel + lat
            stand_k, stand_a = lat + up * 500, lat + up * 50
            if side is None:
                k_, a_ = stand_k, stand_a
            elif s_ == side:          # 앞다리: 정강이 수직, 무릎이 앞으로
                k_, a_ = lat + fwd * 400 + up * 500, lat + fwd * 400 + up * 50
            else:                     # 뒷다리: 무릎이 바닥 가까이, 발은 뒤
                k_, a_ = lat - fwd * 200 + up * 120, lat - fwd * 500 + up * 80
            if side is not None:
                k_ = (1 - w) * stand_k + w * k_
                a_ = (1 - w) * stand_a + w * a_
            P[H36[hip]], P[H36[knee]], P[H36[ank]] = h, k_, a_
        P[7], P[8], P[9], P[10] = pel + up * 250, pel + up * 500, pel + up * 600 + fwd * 80, pel + up * 700
        for s_, sho, elb, wri in (("L", "lsho", "lelb", "lwri"), ("R", "rsho", "relb", "rwri")):
            lat = left * (180 if s_ == "L" else -180)
            P[H36[sho]] = pel + up * 500 + lat
            P[H36[elb]] = pel + up * 250 + lat
            P[H36[wri]] = pel + up * 20 + lat
        return P

    frames = [pose(0, None)] * int(rest_s * FPS)
    for side in sides:
        n = int(step_s * FPS)
        for i in range(n):
            w = 0.5 * (1 - math.cos(2 * math.pi * i / n))
            frames.append(pose(w, side))
        frames += [pose(0, None)] * int(rest_s * FPS)
    J = np.array(frames)
    y, tl = math.radians(yaw_deg), math.radians(tilt_deg)
    Rz = np.array([[math.cos(y), -math.sin(y), 0], [math.sin(y), math.cos(y), 0], [0, 0, 1]])
    Rx = np.array([[1, 0, 0], [0, math.cos(tl), -math.sin(tl)], [0, math.sin(tl), math.cos(tl)]])
    return J @ (Rx @ Rz).T


def synth_capture_from_J(J: np.ndarray, t0: int = 0, camera: bool = False) -> dict:
    """합성 3D → 앱 추론 주기의 MediaPipe 형식 캡처 dict(read_capture_full 과 같은 키). camera=True 면 정면 핀홀 카메라(바닥 위 1 m,
    카메라 쪽 3 m)로 이미지 y 를 채운다 — 2D 단서의 부호 규약 검사용."""
    times = [t0 + int(round(i * 1000.0 / FPS)) for i in range(len(J))]
    chosen = app_cadence(times)
    t, poses, vis, world, img = [], [], [], [], []
    for k in chosen:
        lms = mp_landmarks(J[k].T)
        w = np.array([lm[4:7] for lm in lms])
        v = np.array([min(lm[2], lm[3]) for lm in lms])
        xy = np.full((LANDMARKS, 2), 0.5)
        if camera:
            A = w * APP_SCALE                           # 앱 cm (y 위, z 카메라 쪽)
            floor_y = np.nanmin(A[[MP["lank"], MP["rank"]], 1]) - 5
            cam = np.array([0.0, floor_y + 100.0, 300.0])
            for i in range(LANDMARKS):
                if v[i] > 0:
                    dz = cam[2] - A[i, 2]
                    xy[i] = (0.5 + 0.8 * (A[i, 0] - cam[0]) / dz, 0.5 + 0.8 * (cam[1] - A[i, 1]) / dz)
        t.append(times[k]); poses.append(1); vis.append(v); world.append(w); img.append(xy)
    return {"t": np.array(t, dtype=np.int64), "poses": np.array(poses), "vis": np.array(vis),
            "world": np.array(world), "img": np.array(img)}


def self_test() -> int:
    results: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append((name, bool(ok), detail))

    # 1. 쌍 산술
    r = pair_row(11, 10)
    check("A1 11사이클/정답10: 걸음 +1, 쌍 정확", r["stepErr"] == 1 and r["pairErr"] == 0, str(r))
    r = pair_row(9, 10)
    check("A2 9사이클/정답10: 쌍 −1 (쌍 단위가 과소를 드러낸다)", r["pairs"] == 4 and r["pairErr"] == -1, str(r))
    r = pair_row(10, 11)
    check("A3 홀수 정답 11 → 정답 쌍 5, 10사이클은 쌍 정확", r["truthPairs"] == 5 and r["pairErr"] == 0 and r["odd"], str(r))
    sc = pair_scores([pair_row(c, 10) for c in (10, 11, 9, 12, 7)])
    check("A4 pair_scores: 정확 2/5, ±1 4/5, 과다 1, 과소 2", abs(sc["exact"] - 0.4) < 1e-12 and abs(sc["within1"] - 0.8) < 1e-12
          and sc["overSets"] == 1 and sc["underSets"] == 2, str(sc))
    check("A5 엄격 쌍 min(L,R): LRLRLRLRLR=5, LLLLLRRRRR=5, LRL?R=2, LLLL=0",
          (strict_pairs(list("LRLRLRLRLR")), strict_pairs(list("LLLLLRRRRR")), strict_pairs(["L", "R", "L", None, "R"]),
           strict_pairs(list("LLLL"))) == (5, 5, 2, 0))
    d, n = running_mismatch(list("LLLLLRRRRR"))
    # k: 1..10, floor(k/2) = 0,1,1,2,2,3,3,4,4,5 ; min(L_k,R_k) = 0,0,0,0,0,1,2,3,4,5 → 다른 순간 k=2,3,4,5,6,7,8 = 7
    check("A6 막아서 하기(LLLLLRRRRR): 도중 불일치 7/10", (d, n) == (7, 10), f"{d}/{n}")
    check("A7 교대(LRLRLRLRLR): 도중 불일치 0 — 홀수 걸음에서 둘 다 내림", running_mismatch(list("LRLRLRLRLR")) == (0, 10))
    check("A8 교대율: LRLRL=4/4, LLRRL=2/4, L?RL=1/1", (switch_fraction(list("LRLRL")), switch_fraction(list("LLRRL")),
                                                     switch_fraction(["L", None, "R", "L"])) == ((4, 4), (2, 4), (1, 1)))
    # 사이클 요약의 집계 — 틀린 판별의 확신(|d|)·여유 밖·사람, 조기 도달 세트의 세트 앞 헛사이클(설계 §18 이 인용하는 수)
    cue = lambda d: {c: {"d": d if c == "front" else 1.0} for c in SIDE_CUES}  # noqa: E731
    rows9 = [{"config": "hysteresis", "group": "w00-w15", "workout": w, "ref": ref, "mp": cue(d), "refStepsInWindow": 1 if ref else 0}
             for w, ref, d in (("w02", "R", 45.0), ("w01", "L", 60.0), ("w04", "L", -5.0), ("w03", None, 30.0))]
    ps9 = {k: {"configs": {"hysteresis": {"cyclesIn": 10, "strictMp": 5, "strict3d": 5, "lead": lead, "early": {"step": True, "pair": pair}}}}
           for k, lead, pair in (("s1", 1, True), ("s2", 0, False))}
    cs = cycle_summary(rows9, ps9, "hysteresis", [{"capture": "s1", "truthReps": 10}, {"capture": "s2", "truthReps": 10}])
    fw, er = cs["frontWrong"], cs["earlyReach"]
    check("A9 사이클 요약: 틀린 판별 2/3(|d| 중앙값 25 · 맞음 60 · 20 cm 여유 밖 1 · P1 2), 조기 세트 중 세트 앞 헛사이클 걸음 1/2 · 쌍 1/1",
          (fw["n"], fw["decided"], fw["absCmMedian"], fw["correctAbsCmMedian"], fw["overLargestMarginCm"], fw["bySubject"], fw["byWorkout"])
          == (2, 3, 25.0, 60.0, 1, {"P1": 2}, {"w02": 1, "w04": 1})
          and (er["step"]["early"], er["step"]["earlyWithLeadCycles"], er["pair"]["early"], er["pair"]["earlyWithLeadCycles"]) == (2, 1, 1, 1),
          f"{fw} {er}")
    # 2. 레인 쌍
    check("B1 교대 레인: L 1,3,5 s · R 2,4,6 s → 완료 2,4,6 s", lane_pair_times([1000, 3000, 5000], [2000, 4000, 6000]) == [2000, 4000, 6000])
    check("B2 동시 레인(오른 10~20 ms 늦음) → 쌍 2, 늦은 쪽 시각", lane_pair_times([1000, 3000], [1010, 3020]) == [1010, 3020])
    check("B3 한 레인이 하나 놓침 → min", len(lane_pair_times([1000, 3000, 5000], [1000, 5000])) == 2)
    check("B4 동시 컬의 한쪽이 500 ms 넘게 늦어도 두 번 세지 않는다", lane_pair_times([1000, 4000], [1700, 4800]) == [1700, 4800])
    # 3. 지그재그
    x = np.concatenate([np.full(30, 170.0)] + [np.concatenate([170 - 80 * np.sin(np.linspace(0, np.pi, 60)), np.full(20, 170.0)])
                                               for _ in range(10)])
    check("C1 지그재그: 80° 바닥 10개 → 10", len(zigzag_minima(x, STEP_ZIGZAG_DEG)) == 10)
    wob = x + 10 * np.sin(np.arange(len(x)) * 0.9)
    check("C2 지그재그: 10° 흔들림이 더해져도 10", len(zigzag_minima(wob, STEP_ZIGZAG_DEG)) == 10)
    check("C3 지그재그: 끝에서 올라오지 않은 바닥·처음의 낮은 값은 세지 않는다",
          zigzag_minima(np.array([100.0, 170, 90, 170, 80]), 30.0) == [2])
    x2 = x.copy()
    x2[100:105] = np.nan
    check("C4 지그재그: NaN 구간을 건너뛴다", len(zigzag_minima(x2, STEP_ZIGZAG_DEG)) == 10)
    # 4. 3D 기준 걸음 — 방향·기울기와 무관하게 앞발을 찾는다
    seq = "LRLRLRLRLR"
    for yaw, tilt in ((0, 0), (37, 14), (160, -10), (-95, 20)):
        J = synth_lunge(seq, yaw, tilt)
        up, n_up = workout_up(J)
        steps = reference_steps(J, 0, up, 0, len(J) - 1)
        got = "".join(s["side"] or "?" for s in steps)
        fwd_ok = all(v > 0 for s in steps for v in s["kneeFwdMm"])
        check(f"D yaw {yaw}° tilt {tilt}°: 걸음 10, 좌우 {seq}, 굽힌 무릎이 F 쪽", got == seq and fwd_ok, f"{got} fwd={fwd_ok} up={up.round(3)}")
    J = synth_lunge("LLRLRRLRLR", 20, 5)
    steps = reference_steps(J, 0, workout_up(J)[0], 0, len(J) - 1)
    check("D5 불규칙 순서 LLRLRRLRLR 그대로", "".join(s["side"] or "?" for s in steps) == "LLRLRRLRLR")
    check("D6 3D 뒷무릎 단서도 합성에서는 앞발 규칙과 같다", all(s["kneeSide"] == s["side"] for s in steps))
    Jm = J * np.array([-1.0, 1.0, 1.0])          # 거울 좌표계(왼손계) — 가정 ① 이 깨지면 무릎 점검이 음수로 알린다
    sm = reference_steps(Jm, 0, workout_up(Jm)[0], 0, len(Jm) - 1)
    check("D7 왼손 좌표계면 좌우가 뒤집히고 무릎 전방 점검이 음수", "".join(s["side"] or "?" for s in sm) == "RRLRLLRLRL"
          and all(v < 0 for s in sm for v in s["kneeFwdMm"]))
    # 5. MediaPipe 규약 — 같은 합성 몸을 캡처 형식(월드 m, y 아래)으로 만들어 앱 규약으로 되돌려도 같은 좌우
    J = synth_lunge(seq, 0, 0)
    cap = synth_capture_from_J(J, camera=True)
    km = mp_knee_mean(cap)
    cues = mp_side_cues(cap)
    mins = zigzag_minima(km, STEP_ZIGZAG_DEG)
    got = {cue: "".join(side_of(majority(cues[cue][deep_samples(km, (np.arange(len(km)) >= m - 3) & (np.arange(len(km)) <= m + 3))])[0]) or "?"
                        for m in mins) for cue in SIDE_CUES}
    check("E1 MediaPipe 형식(300 ms)에서 바닥 10개", len(mins) == 10, str(len(mins)))
    check("E2 앞발(월드)·뒷무릎·2D 앞발 세 단서 모두 LRLRLRLRLR", all(v == seq for v in got.values()), str(got))
    W = cap["world"][0] * APP_SCALE
    F = forward_dirs(W[MP["lhip"]][None], W[MP["rhip"]][None], APP_UP)[0]
    check("E3 앱 좌표에서 정면 몸의 F = +z(카메라 쪽), 왼 엉덩이 = +x(화면 오른쪽)", F[2] > 0.99 and W[MP["lhip"], 0] > W[MP["rhip"], 0], str(F))
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "x.cap"
        lines = [frame_line(int(cap["t"][i]), [(float(cap["img"][i, j, 0]), float(cap["img"][i, j, 1]), float(cap["vis"][i, j]),
                                                 float(cap["vis"][i, j]), *map(float, cap["world"][i, j])) for j in range(LANDMARKS)])
                 for i in range(len(cap["t"]))]
        write_capture(p, {"source": "selftest"}, lines)
        back = read_capture_full(p)
        cues2 = mp_side_cues(back)
        check("E4 캡처 파일 왕복(소수 5자리) 뒤에도 앞발 단서 부호가 같다",
              np.array_equal(np.sign(np.nan_to_num(cues2["front"])), np.sign(np.nan_to_num(cues["front"]))) and len(back["t"]) == len(cap["t"]))
    # 6. 거울 팔 — 동시 컬
    J = synth_lunge("L", 30, 10)
    rng = np.random.default_rng(1)
    P = J[40].copy()
    P[H36["lelb"]] += rng.normal(0, 60, 3)
    P[H36["lwri"]] += rng.normal(0, 90, 3)
    Q = mirror_arm(P, P, "l")
    ang1 = lambda X, a, b, c: float(angle(X[None, H36[a]], X[None, H36[b]], X[None, H36[c]])[0])  # noqa: E731
    aL = ang1(Q, "lsho", "lelb", "lwri")
    aR = ang1(Q, "rsho", "relb", "rwri")
    lenL = np.linalg.norm(Q[H36["lelb"]] - Q[H36["lsho"]]), np.linalg.norm(Q[H36["lwri"]] - Q[H36["lelb"]])
    lenR = np.linalg.norm(Q[H36["relb"]] - Q[H36["rsho"]]), np.linalg.norm(Q[H36["rwri"]] - Q[H36["relb"]])
    check("F1 거울 팔: 오른 팔꿈치 각 = 왼 팔꿈치 각, 뼈 길이 같음", abs(aL - aR) < 1e-9 and np.allclose(lenL, lenR), f"{aL} {aR}")
    P2 = J[10].copy()
    Q2 = mirror_arm(P2, P, "l")
    aR2 = ang1(Q2, "rsho", "relb", "rwri")
    check("F2 지연 거울: 오른 팔꿈치 각 = 지연된 원본 프레임의 왼 팔꿈치 각", abs(aR2 - aL) < 1e-9)
    # 7. 세트 창 — run_replay 와 같은 경계
    s = {"startMs": 10_000, "endMs": 20_000}
    check("G1 세트 창 [start−500, end+500] 경계 포함", in_window([9_499, 9_500, 15_000, 20_500, 20_501], s) == [9_500, 15_000, 20_500])
    wins = cycle_windows({"publishedCycles": [[5000, 3000, 90, 170, False]]}, [5000, 9000, 9500])
    check("G2 사이클 창: 새 코어는 하강 시작 − 300 ms, 레거시는 직전 발화(최대 4 s)",
          wins == {5000: (2700, 5000), 9000: (5000, 9000), 9500: (9000, 9500)}, str(wins))

    width = max(len(n) for n, _, _ in results)
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {'' if ok else detail}")
    passed = sum(ok for _, ok, _ in results)
    print(f"\n{passed}/{len(results)} passed")
    return 0 if passed == len(results) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", type=Path, default=DATA / "mm-fit", help="wNN/ 폴더(pose_3d·labels)")
    ap.add_argument("--mp-index", type=Path, default=DATA / "mp_captures_aligned", help="정렬된 MediaPipe index 폴더")
    ap.add_argument("--mp-replay", type=Path, default=DATA / "exp_mp" / "aligned" / "replay.jsonl", help="그 index 의 기존 재생 결과")
    ap.add_argument("--pose3d-captures", type=Path, default=DATA / "captures_pose3d")
    ap.add_argument("--out", type=Path, default=DATA / "exp_sidepair")
    ap.add_argument("--results", type=Path, default=HERE / "results" / "side_attribution")
    ap.add_argument("--skip-replay", action="store_true", help="각 출력 폴더에 replay.jsonl 이 있으면 JVM 을 다시 돌리지 않는다")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run_all(args)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
