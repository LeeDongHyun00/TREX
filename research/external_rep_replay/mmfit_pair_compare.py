# -*- coding: utf-8 -*-
"""MM-Fit 영상 MediaPipe 캡처 ↔ 데이터셋 자체 3D 포즈(pose_3d) 세트별 짝짓기 — MediaPipe 가 더하는 몫을 잰다.

왜 있나 (설계 §7 외부 자료 2단계, §15 #9·#10·#12)
    3D 포즈 재생(README §4, 설계 §13)은 MediaPipe 잡음이 없는 입력이다. 같은 세트를 MediaPipe 로 다시 뽑아 **같은 샘플 시각**에서
    두 입력을 나란히 놓으면, 카운트 차이와 각도 오차가 전부 "MediaPipe(+ 영상)" 의 몫이 된다. 결정 표의 보류 항목
    (컬 신호·50° 게이트·튐 필터·먼 팔 가림)이 이 숫자를 기다린다.

무엇을 재나 (종목별)
    지연          워크아웃마다 라벨(pose_3d) 프레임과 영상 프레임의 어긋남을 교차상관으로 추정한다(다른 계열 실측 4~5프레임).
                  lag = 영상 프레임 − 라벨 프레임. 이후 모든 짝짓기는 추정 지연을 적용한다
    좌우          MediaPipe L/R 이 pose_3d 의 l/r 과 같은 쪽인지 컬 세트 팔꿈치 상관으로 확인한다(H36M 좌우 이름은 EDA 와 다를 수 있다)
    각도 오차     knee_L/R·knee_mean·knee_minside·elbow_L/R·elbow_mean·elbow_minside(·_both): MediaPipe − 3D 의 편향·MAE·표준편차·
                  세트 내 잔차 σ(세트별 평균 편향을 뺀 것), 3D 굴곡 깊이 구간별
    휴식 지터     3D 가 휴식 쪽(세트 p95 에서 10° 안)이고 거의 안 움직일 때(|Δ3D| < 3°) 연속 샘플 차이로 잰 σ = std(Δ)/√2
                  (MediaPipe 자체 · MediaPipe−3D 잔차 · 3D 기준선을 함께)
    한 샘플 튐    |v_i − (v_{i−1}+v_{i+1})/2| > T 이고 두 이웃보다 같은 쪽으로 튀었는데 3D 는 조용한(T/2 미만) 샘플, T = 10·20·35°
    검출 끊김     사람 없음(poses=0) · 관절 부족(피처 없음) · 신호 없음(예: 먼 팔 가시성 < 0.5 → elbow_mean 없음) 비율, 신호가 빈 구간의
                  길이 분포와 1.5 s 넘는 틈 수(카운터 사이클 리셋) — 세트 안 / 세트 밖(앞뒤 음성 구간) 따로
    카운트 차이   MediaPipe 캡처와 **같은 샘플 시각의 3D 캡처**(추정 지연 적용, paired3d/)를 같은 JVM 재생기·같은 구성으로 돌려
                  세트별 (MediaPipe − 3D) 카운트 차이와 세트 앞뒤 헛카운트를 비교한다(구성: live, hysteresis, 신호 후보)
앱과 같은 MediaPipe 후처리를 파이썬으로 옮긴 부분(가시성 = min(visibility, presence) ≥ 0.5, 양 엉덩이가 없으면 피처 없음,
mean 은 양쪽 필요·minside 는 한쪽 폴백)은 각도 통계용이다. 카운트는 JVM 재생기(앱 소스)가 센다.

있는 워크아웃만 쓴다 — 영상은 사용자 PC 에서 워크아웃 하나씩 추출돼 브랜치(data/mmfit-mp-captures)로 들어온다.

사용법
    # 본 실험 (MediaPipe 캡처: extract_mediapipe.py mmfit ... --pre-s 15 --post-s 10)
    python mmfit_pair_compare.py compare <MediaPipe 캡처 폴더> ../../data/mm-fit/mm-fit --out <결과 폴더>
    # 합성 MediaPipe 흉내 캡처(pose_3d + 지연·지터·튐·끊김·먼 팔 가림) — 도구 검증용, MediaPipe 결과가 아니다
    python mmfit_pair_compare.py synth ../../data/mm-fit/mm-fit --out <캡처 폴더> --workouts w00 w01 [--lag 4 --jitter-mm 15 ...]
    python mmfit_pair_compare.py selftest ../../data/mm-fit/mm-fit --out <작업 폴더> [--workouts w00 w01 w13]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

import run_replay
from capture_format import APP_SAMPLE_INTERVAL_MS, LANDMARKS, frame_line, write_capture
from mmfit_pose3d_captures import ACTIVITIES, MP_FROM_H36M

FPS_DEFAULT = 30.0
MARGIN_FRAMES = 15                         # extract_mediapipe.py 의 MMFIT_MARGIN_FRAMES (이웃 세트 라벨 ± 0.5 s 는 넘지 않는다)
MIN_VIS = 0.5                              # PostureAnalyzer.MIN_VISIBILITY
MAX_LAG = 15
DEPTH_BINS = [(0, 90), (90, 120), (120, 150), (150, 181)]
SPIKE_T = (10.0, 20.0, 35.0)
REST_BAND = 10.0                           # 휴식 = 세트 3D p95 에서 10° 안
REST_STILL = 3.0                           # 그리고 직전 샘플 대비 3D 변화 < 3°
PAIR_GAP_MS = 400                          # 연속 샘플로 보는 최대 간격(발열 감속 ~440ms 는 제외)

FEATS = ["knee_L", "knee_R", "knee_mean", "knee_minside", "elbow_L", "elbow_R", "elbow_mean", "elbow_minside", "elbow_minside_both"]
PRIMARY = {"바벨 스쿼트": "knee_mean", "스텝 포워드 다이나믹 런지": "knee_mean", "덤벨 컬": "elbow_mean"}
# 종목별로 볼 피처(무릎 종목은 무릎, 컬은 팔꿈치)
EX_FEATS = {"바벨 스쿼트": FEATS[:4], "스텝 포워드 다이나믹 런지": FEATS[:4], "덤벨 컬": FEATS[4:]}
COUNT_CONFIGS = {"live", "hysteresis", "hysteresis+knee_minside", "hysteresis+elbow_minside", "hysteresis+elbow_minside_both"}

# MediaPipe 인덱스 (앱 Joints.SINGLE)
MP = dict(lsho=11, rsho=12, lelb=13, relb=14, lwri=15, rwri=16, lhip=23, rhip=24, lknee=25, rknee=26, lank=27, rank=28)
# H36M 인덱스 (pose_3d 의 1+j 열) — prototype_counter.py 와 같다
H36 = dict(rhip=1, rknee=2, rank=3, lhip=4, lknee=5, lank=6, lsho=11, lelb=12, lwri=13, rsho=14, relb=15, rwri=16)
TRIPLES = {"knee_L": ("lhip", "lknee", "lank"), "knee_R": ("rhip", "rknee", "rank"),
           "elbow_L": ("lsho", "lelb", "lwri"), "elbow_R": ("rsho", "relb", "rwri")}


# ---------------------------------------------------------------- 각도

def angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> np.ndarray:
    """b 꼭짓점 각도(도), 마지막 축이 좌표. 퇴화하면 nan (앱 angle3 이 null)."""
    u, w = a - b, c - b
    nu, nw = np.linalg.norm(u, axis=-1), np.linalg.norm(w, axis=-1)
    with np.errstate(invalid="ignore", divide="ignore"):
        cos = (u * w).sum(-1) / (nu * nw)
    out = np.degrees(np.arccos(np.clip(cos, -1, 1)))
    out[(nu < 1e-9) | (nw < 1e-9)] = np.nan
    return out


def combine(f: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    """앱 PostureCore 규약: mean 은 양쪽 필요, minside 는 한쪽만 있어도 그 값(nanmin), minside_both 는 양쪽 필요(연구용)."""
    for base in ("knee", "elbow"):
        l, r = f[f"{base}_L"], f[f"{base}_R"]
        both = np.isfinite(l) & np.isfinite(r)
        f[f"{base}_mean"] = np.where(both, (l + r) / 2, np.nan)
        with np.errstate(invalid="ignore"):
            f[f"{base}_minside"] = np.where(both, np.minimum(l, r), np.where(np.isfinite(l), l, r))
        f[f"{base}_minside_both"] = np.where(both, np.minimum(l, r), np.nan)
    return f


def gt_features(pose: np.ndarray) -> tuple[int, dict[str, np.ndarray]]:
    """pose_3d (3, N, 18) → (첫 프레임 번호, 프레임 번호 축의 피처 배열). 빠진 프레임은 nan."""
    frame_no = pose[0, :, 0].astype(int)
    f0, f1 = int(frame_no[0]), int(frame_no[-1])
    J = np.full((f1 - f0 + 1, 17, 3), np.nan)
    J[frame_no - f0] = np.transpose(pose[:, :, 1:], (1, 2, 0))
    f = {k: angle(J[:, H36[a]], J[:, H36[b]], J[:, H36[c]]) for k, (a, b, c) in TRIPLES.items()}
    return f0, combine(f)


def read_landmark_capture(path: Path) -> dict:
    """capture_format 의 F 줄 → 시각·검출·가시성·월드 좌표 배열. 33개가 다 없는 줄은 '사람 없음' 과 같게 본다(재생기와 같다)."""
    t, poses, vis, world, meta = [], [], [], [], {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if parts[0] == "H":
            for kv in parts[1:]:
                k, _, v = kv.partition("=")
                meta[k] = v
            continue
        if parts[0] != "F":
            continue
        t.append(int(parts[1]))
        v = np.full(LANDMARKS, np.nan)
        w = np.full((LANDMARKS, 3), np.nan)
        n = 0
        for cell in parts[3:]:
            i, _, rest = cell.partition(":")
            x = [float(q) for q in rest.split(",")]
            vi, pr = (1.0 if math.isnan(x[2]) else x[2]), (1.0 if math.isnan(x[3]) else x[3])
            v[int(i)] = min(vi, pr)
            w[int(i)] = x[4:7]
            n += 1
        poses.append(int(parts[2]) if n == LANDMARKS else 0)
        vis.append(v)
        world.append(w)
    return {"meta": meta, "t": np.array(t, dtype=np.int64), "poses": np.array(poses),
            "vis": np.array(vis).reshape(-1, LANDMARKS), "world": np.array(world).reshape(-1, LANDMARKS, 3)}


def app_features(cap: dict) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    """앱 후처리 흉내 → (상태, 피처). 상태 0 = 피처 있음, 1 = 사람 없음, 2 = 관절 부족(양 엉덩이 없음 → PoseFrame.valid=false)."""
    n = len(cap["t"])
    ok = (cap["vis"] >= MIN_VIS) & (cap["poses"][:, None] >= 1)
    W = np.where(ok[:, :, None], cap["world"], np.nan)
    status = np.where(cap["poses"] >= 1, 0, 1)
    hips = ok[:, MP["lhip"]] & ok[:, MP["rhip"]]
    status[(status == 0) & ~hips] = 2
    f = {}
    for k, (a, b, c) in TRIPLES.items():
        x = angle(W[:, MP[a]], W[:, MP[b]], W[:, MP[c]]) if n else np.zeros(0)
        x[status != 0] = np.nan
        f[k] = x
    return status, combine(f)


# ---------------------------------------------------------------- 입력 정리

def load_labels(path: Path) -> list[tuple[int, int, int, str]]:
    rows = [r for r in csv.reader(path.open(newline="")) if r]
    return sorted(((int(r[0]), int(r[1]), int(r[2]), r[3].strip()) for r in rows), key=lambda r: r[0])


def workout_fps(sets: list[dict], labels: list[tuple]) -> float:
    """index 의 startMs 와 라벨 시작 프레임으로 영상 fps 를 되짚는다(추출기는 fps 를 세트마다 적지 않는다)."""
    # 합의 비 — 시작 프레임이 작은 세트의 ms 반올림 오차가 fps 를 흔들지 않게(프레임 수만 개에서 몇 프레임이 밀린다)
    frames = ms = 0.0
    for s in sets:
        k = s.get("ordinal")
        if k is None or k >= len(labels) or not s.get("startMs") or not s.get("endMs"):
            continue
        frames += labels[k][0] + labels[k][1]
        ms += s["startMs"] + s["endMs"]
    return frames * 1000.0 / ms if ms > 0 else FPS_DEFAULT


def pearson(a: np.ndarray, b: np.ndarray) -> float:
    m = np.isfinite(a) & np.isfinite(b)
    if m.sum() < 10:
        return float("nan")
    a, b = a[m] - a[m].mean(), b[m] - b[m].mean()
    d = math.sqrt(float((a * a).sum() * (b * b).sum()))
    return float((a * b).sum() / d) if d > 0 else float("nan")


def gt_at(gt: dict, f0: int, frames: np.ndarray, key: str) -> np.ndarray:
    arr = gt[key]
    idx = frames - f0
    out = np.full(len(frames), np.nan)
    ok = (idx >= 0) & (idx < len(arr))
    out[ok] = arr[idx[ok]]
    return out


# ---------------------------------------------------------------- 합성 MediaPipe 흉내 캡처

def mp_landmarks(joints_mm: np.ndarray, vis_override: dict[int, float] | None = None) -> list[tuple] | None:
    """mmfit_pose3d_captures.landmarks 와 같은 좌표 변환 + 가시성 덮어쓰기(먼 팔 가림 흉내)."""
    if not np.all(np.isfinite(joints_mm[:, list(MP_FROM_H36M.values())])):
        return None
    out = []
    for i in range(LANDMARKS):
        j = MP_FROM_H36M.get(i)
        if j is None:
            out.append((0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
            continue
        ox, oy, oz = joints_mm[:, j]
        v = (vis_override or {}).get(i, 0.95)
        out.append((0.5, 0.5, v, v, ox / 1000.0, -oz / 1000.0, oy / 1000.0))
    return out


def capture_windows(labels: list[tuple], fps: float, pre_s: float, post_s: float, v_last: int) -> list[tuple[int, int, int]]:
    """extract_mediapipe.mmfit_jobs 와 같은 창: [시작 − max(0.5 s, pre), 끝 + max(0.5 s, post)], 이웃 세트 라벨 ± 0.5 s 를 넘지 않는다."""
    out = []
    for k, (s, e, _, _) in enumerate(labels):
        lo = s - max(MARGIN_FRAMES, int(round(pre_s * fps)))
        hi = e + max(MARGIN_FRAMES, int(round(post_s * fps)))
        if k > 0:
            lo = max(lo, labels[k - 1][1] + MARGIN_FRAMES + 1)
        if k + 1 < len(labels):
            hi = min(hi, labels[k + 1][0] - MARGIN_FRAMES - 1)
        out.append((k, max(0, lo), min(v_last, hi)))
    return out


def synth(root: Path, out: Path, workouts: list[str] | None, pre_s: float, post_s: float, lag: int, jitter_mm: float,
          spike_p: float, spike_mm: float, drop_rate: float, drop_len: float, far_p: float, seed: int) -> dict:
    """pose_3d 에 지연·지터·튐·끊김·먼 팔 가림을 넣은 **MediaPipe 흉내** 캡처. 도구 검증용이며 MediaPipe 결과가 아니다.
    정답 통계(주입량, 휴식 각도 잡음 σ, 20° 넘는 각도 튐)를 synth_truth.json 에 남긴다 — 이 값은 도구와 독립으로(3D 관절에서) 계산한다."""
    rng = np.random.default_rng(seed)
    out.mkdir(parents=True, exist_ok=True)
    clean = lag == 0 and jitter_mm == 0 and spike_p == 0 and drop_rate == 0 and far_p == 0
    # 잡음·지연을 하나도 넣지 않으면 합성이 아니라 "앞뒤 음성 구간을 붙인 MM-Fit 3D 캡처" 다 — 이름을 그렇게 적는다
    source = ("MM-Fit pose_3d with lead-in/after windows (no noise, no lag) — not MediaPipe" if clean else
              "SYNTHETIC MediaPipe-like capture (MM-Fit pose_3d + injected lag/noise) — tool test only, NOT MediaPipe")
    index = {"source": source,
             "cadenceMs": APP_SAMPLE_INTERVAL_MS, "preS": pre_s, "postS": post_s, "sets": [],
             "synthetic": {"lagFrames": lag, "jitterMm": jitter_mm, "spikeP": spike_p, "spikeMm": spike_mm,
                           "dropRate": drop_rate, "dropLenSamples": drop_len, "farArmP": far_p, "farArm": "L", "seed": seed}}
    truth = defaultdict(lambda: {"samples": 0, "nopose": 0, "injectedDrop": 0, "farArm": 0, "poseSamples": 0, "spikes": 0, "angleSpikes20": 0,
                                 "restDiff": [], "restDiffAll": []})
    limb = [H36[k] for k in ("lknee", "rknee", "lank", "rank", "lelb", "relb", "lwri", "rwri")]
    p_start = drop_rate / max(1.0, drop_len) if drop_rate > 0 else 0.0
    for wdir in sorted(p for p in root.iterdir() if p.is_dir()):
        w = wdir.name
        if workouts and w not in workouts:
            continue
        pp, lp = wdir / f"{w}_pose_3d.npy", wdir / f"{w}_labels.csv"
        if not pp.is_file() or not lp.is_file():
            continue
        pose = np.load(pp)
        frame_no = pose[0, :, 0].astype(int)
        f0 = int(frame_no[0])
        J = np.full((3, int(frame_no[-1]) - f0 + 1, 17), np.nan)
        J[:, frame_no - f0, :] = pose[:, :, 1:]
        labels = load_labels(lp)
        v_last = int(frame_no[-1]) + lag
        for k, lo, hi in capture_windows(labels, FPS_DEFAULT, pre_s, post_s, v_last):
            s, e, reps, act = labels[k]
            exercise = ACTIVITIES.get(act)
            if exercise is None or hi <= lo:
                continue
            primary = PRIMARY[exercise]
            a, b, c = TRIPLES[primary.replace("_mean", "_L")]
            lines, last, dropping = [], None, 0
            clean_prev = None
            rest_ref = []
            rows = []
            for v in range(lo, hi + 1):
                t = int(round(v * 1000.0 / FPS_DEFAULT))
                if last is not None and t - last < APP_SAMPLE_INTERVAL_MS:
                    continue
                last = t
                p = v - lag - f0
                tr = truth[exercise]
                tr["samples"] += 1
                if dropping <= 0 and p_start > 0 and rng.random() < p_start:
                    dropping = max(1, int(rng.geometric(1.0 / max(1.0, drop_len))))
                if dropping > 0 or not (0 <= p < J.shape[1]) or not np.all(np.isfinite(J[:, p, list(MP_FROM_H36M.values())])):
                    # 주입한 끊김과 pose_3d 자체의 빈 프레임(예: w00 첫 세트 앞) 모두 캡처에서는 '사람 없음' 이다
                    if dropping > 0:
                        dropping -= 1
                        tr["injectedDrop"] += 1
                    tr["nopose"] += 1
                    lines.append(frame_line(t, None))
                    continue
                clean = J[:, p, :].copy()
                noisy = clean + rng.normal(0, jitter_mm, clean.shape) if jitter_mm > 0 else clean.copy()
                spiked = False
                if spike_p > 0 and rng.random() < spike_p:
                    j = int(rng.choice(limb))
                    d = rng.normal(size=3)
                    noisy[:, j] += spike_mm * d / np.linalg.norm(d)
                    spiked = True
                    tr["spikes"] += 1
                far = far_p > 0 and rng.random() < far_p
                tr["poseSamples"] += 1
                tr["farArm"] += int(far)
                vis = {MP["lelb"]: 0.3, MP["lwri"]: 0.3} if far else None
                lines.append(frame_line(t, mp_landmarks(noisy, vis)))
                # 독립 정답: 3D 관절에서 직접 — 대표 신호(평균)의 깨끗한 값·잡음 섞인 값
                def ang(X):
                    l = angle(X[:, H36[a]][None], X[:, H36[b]][None], X[:, H36[c]][None])[0]
                    ra, rb, rc = (q.replace("l", "r", 1) for q in (a, b, c))
                    r = angle(X[:, H36[ra]][None], X[:, H36[rb]][None], X[:, H36[rc]][None])[0]
                    return (l + r) / 2
                cv, nv = ang(clean), ang(noisy)
                if far and primary.startswith("elbow"):
                    nv = np.nan
                rows.append((cv, nv, spiked, clean_prev))
                clean_prev = cv
                rest_ref.append(cv)
            if not lines:
                continue
            ref = np.nanpercentile(rest_ref, 95) if rest_ref else np.nan
            for cv, nv, spiked, prev in rows:
                if not np.isfinite(nv):
                    continue
                if spiked and abs(nv - cv) > 20:
                    truth[exercise]["angleSpikes20"] += 1
                if not spiked and prev is not None and abs(cv - ref) < REST_BAND and abs(cv - prev) < REST_STILL:
                    truth[exercise]["restDiff"].append(float(nv - cv))
            name = f"{w}/{w}_set{k:02d}_{act}.cap"
            write_capture(out / name, {"source": "synthetic_mediapipe_like", "workout": w, "activity": act}, lines)
            ms = lambda f: int(round(f * 1000.0 / FPS_DEFAULT))
            index["sets"].append({"workout": w, "ordinal": k, "activity": act, "exercise": exercise, "truthReps": reps,
                                  "startMs": ms(s), "endMs": ms(e), "captureStartMs": ms(lo), "captureEndMs": ms(hi), "capture": name})
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    summary = {}
    for ex, tr in truth.items():
        d = np.array(tr["restDiff"])
        summary[ex] = {"samples": tr["samples"], "noposeFraction": tr["nopose"] / max(1, tr["samples"]),
                       "injectedDropFraction": tr["injectedDrop"] / max(1, tr["samples"]),
                       "farArmFraction": tr["farArm"] / max(1, tr["poseSamples"]), "spikesInjected": tr["spikes"],
                       "angleSpikes20": tr["angleSpikes20"], "angleSpike20Rate": tr["angleSpikes20"] / max(1, tr["poseSamples"]),
                       "restNoiseSigma": float(d.std()) if len(d) > 5 else None, "restN": int(len(d))}
    (out / "synth_truth.json").write_text(json.dumps({"params": index["synthetic"], "byExercise": summary},
                                                     ensure_ascii=False, indent=1), encoding="utf-8")
    return index


# ---------------------------------------------------------------- 짝짓기 · 통계

def _stats(d: np.ndarray) -> dict:
    d = d[np.isfinite(d)]
    if len(d) == 0:
        return {"n": 0}
    return {"n": int(len(d)), "bias": float(d.mean()), "MAE": float(np.abs(d).mean()), "sd": float(d.std()),
            "p95abs": float(np.percentile(np.abs(d), 95))}


def _runs(missing: np.ndarray, t: np.ndarray) -> dict:
    """신호가 빈 연속 구간(샘플 수)과, 앞뒤 값 있는 샘플 사이 시간이 1.5 s 를 넘는 틈 수."""
    lengths, gaps, i, n = [], 0, 0, len(missing)
    while i < n:
        if missing[i]:
            j = i
            while j < n and missing[j]:
                j += 1
            lengths.append(j - i)
            if 0 < i and j < n and t[j] - t[i - 1] > run_replay.MAX_GAP_MS:
                gaps += 1
            i = j
        else:
            i += 1
    L = np.array(lengths) if lengths else np.zeros(0, dtype=int)
    return {"runs": int(len(L)), "medianLen": float(np.median(L)) if len(L) else 0.0,
            "p90Len": float(np.percentile(L, 90)) if len(L) else 0.0, "maxLen": int(L.max()) if len(L) else 0,
            "gapsOver1500ms": gaps}


def estimate_lag(samples: list[dict], gt: dict, f0: int, max_lag: int) -> dict:
    """세트 창 안 샘플의 대표 신호로 lag ∈ [−max_lag, max_lag] 의 피어슨 상관을 재고 최대인 lag 를 고른다.
    교차 확인: 차이의 중앙 절대값이 최소인 lag."""
    mp_v, frames, keys = [], [], []
    for s in samples:
        m = s["inSet"]
        mp_v.append(s["feat"][s["primary"]][m])
        frames.append(s["frames"][m])
        keys += [s["primary"]] * int(m.sum())
    if not mp_v:
        return {"lag": 0, "r": float("nan"), "r0": float("nan"), "curve": {}}
    mp_v, frames, keys = np.concatenate(mp_v), np.concatenate(frames), np.array(keys)
    curve, mad = {}, {}
    for L in range(-max_lag, max_lag + 1):
        g = np.full(len(frames), np.nan)
        for key in set(keys):
            sel = keys == key
            g[sel] = gt_at(gt, f0, frames[sel] - L, key)
        curve[L] = pearson(mp_v, g)
        d = mp_v - g
        mad[L] = float(np.nanmedian(np.abs(d))) if np.isfinite(d).any() else float("nan")
    finite = {k: v for k, v in curve.items() if v == v}
    best = max(finite, key=finite.get) if finite else 0
    fm = {k: v for k, v in mad.items() if v == v}
    return {"lag": int(best), "r": curve.get(best), "r0": curve.get(0), "lagByMedianAbs": int(min(fm, key=fm.get)) if fm else None,
            "curve": {str(k): (round(v, 4) if v == v else None) for k, v in curve.items()}}


def side_check(samples: list[dict], gt: dict, f0: int, lag: int) -> dict:
    """컬 세트에서 MediaPipe elbow_L 이 3D elbow_L·elbow_R 중 어느 쪽과 더 닮았나. 교대 컬이라 좌우가 갈린다."""
    mpL, gL, gR = [], [], []
    for s in samples:
        if s["exercise"] != "덤벨 컬":
            continue
        m = s["inSet"]
        mpL.append(s["feat"]["elbow_L"][m])
        gL.append(gt_at(gt, f0, s["frames"][m] - lag, "elbow_L"))
        gR.append(gt_at(gt, f0, s["frames"][m] - lag, "elbow_R"))
    if not mpL:
        return {"checked": False}
    a, l, r = np.concatenate(mpL), np.concatenate(gL), np.concatenate(gR)
    same, cross = pearson(a, l), pearson(a, r)
    return {"checked": True, "rSame": same, "rCross": cross, "swapped": bool(cross == cross and same == same and cross > same)}


def analyze(mp_dir: Path, root: Path, max_lag: int) -> tuple[dict, dict]:
    """(워크아웃별 지연·좌우, 종목별 통계 원자료). 캡처 파일은 index 의 세트만 읽는다."""
    index = json.loads((mp_dir / "index.json").read_text(encoding="utf-8"))
    by_w = defaultdict(list)
    for s in index["sets"]:
        if s.get("exercise") in PRIMARY:
            by_w[s.get("workout")].append(s)
    workouts, per_set = {}, []
    for w, sets in sorted(by_w.items()):
        pp, lp = root / w / f"{w}_pose_3d.npy", root / w / f"{w}_labels.csv"
        if not pp.is_file() or not lp.is_file():
            print(f"skip {w}: pose_3d/labels 없음", file=sys.stderr)
            continue
        labels = load_labels(lp)
        fps = workout_fps(sets, labels)
        f0, gt = gt_features(np.load(pp))
        samples = []
        for s in sets:
            path = mp_dir / s["capture"]
            if not path.is_file():
                print(f"skip {s['capture']}: 캡처 없음", file=sys.stderr)
                continue
            cap = read_landmark_capture(path)
            status, feat = app_features(cap)
            frames = np.rint(cap["t"] * fps / 1000.0).astype(int)
            lo, hi = s["startMs"] - run_replay.SET_MARGIN_MS, s["endMs"] + run_replay.SET_MARGIN_MS
            samples.append({"set": s, "exercise": s["exercise"], "primary": PRIMARY[s["exercise"]], "t": cap["t"],
                            "frames": frames, "status": status, "feat": feat, "inSet": (cap["t"] >= lo) & (cap["t"] <= hi)})
        lag = estimate_lag(samples, gt, f0, max_lag)
        side = side_check(samples, gt, f0, lag["lag"])
        workouts[w] = {"fps": fps, "sets": len(samples), **lag, "side": side}
        swap = side.get("swapped", False)
        for smp in samples:
            g = {}
            for k in FEATS:
                src = k
                if swap and k.endswith(("_L", "_R")):
                    src = k[:-1] + ("R" if k.endswith("L") else "L")
                g[k] = gt_at(gt, f0, smp["frames"] - lag["lag"], src)
            smp["gt"] = g
            smp["workout"] = w
            smp["lag"] = lag["lag"]
            smp["fps"] = fps
            per_set.append(smp)
    return workouts, {"index": index, "samples": per_set}


def angle_stats(samples: list[dict]) -> dict:
    out = {}
    by_ex = defaultdict(list)
    for s in samples:
        by_ex[s["exercise"]].append(s)
    for ex, ss in sorted(by_ex.items()):
        e = {}
        for k in EX_FEATS[ex]:
            diffs, resid, depth = [], [], []
            for s in ss:
                m = s["inSet"]
                d = s["feat"][k][m] - s["gt"][k][m]
                ok = np.isfinite(d)
                diffs.append(d[ok])
                if ok.sum() >= 3:
                    resid.append(d[ok] - d[ok].mean())
                depth.append(s["gt"][k][m][ok])
            d = np.concatenate(diffs) if diffs else np.zeros(0)
            g = np.concatenate(depth) if depth else np.zeros(0)
            st = _stats(d)
            st["residualSigma"] = float(np.concatenate(resid).std()) if resid else None
            st["byDepth"] = {f"{a}-{b}": _stats(d[(g >= a) & (g < b)]) for a, b in DEPTH_BINS}
            e[k] = st
        out[ex] = e
    return out


def noise_stats(samples: list[dict]) -> dict:
    out = {}
    by_ex = defaultdict(list)
    for s in samples:
        by_ex[s["exercise"]].append(s)
    for ex, ss in sorted(by_ex.items()):
        k = PRIMARY[ex]
        d_mp, d_res, d_gt = [], [], []
        spikes = {T: 0 for T in SPIKE_T}
        triplets = 0
        status_all = {"inSet": defaultdict(int), "outside": defaultdict(int)}
        runs_in, runs_all = [], []
        arm = defaultdict(int)
        for s in ss:
            mp, gt, t = s["feat"][k], s["gt"][k], s["t"]
            finite_gt = gt[np.isfinite(gt)]
            ref = np.percentile(finite_gt, 95) if len(finite_gt) else np.nan
            for i in range(1, len(t)):
                if t[i] - t[i - 1] > PAIR_GAP_MS:
                    continue
                if not (np.isfinite(mp[i]) and np.isfinite(mp[i - 1]) and np.isfinite(gt[i]) and np.isfinite(gt[i - 1])):
                    continue
                if abs(gt[i] - ref) < REST_BAND and abs(gt[i - 1] - ref) < REST_BAND and abs(gt[i] - gt[i - 1]) < REST_STILL:
                    d_mp.append(mp[i] - mp[i - 1])
                    d_gt.append(gt[i] - gt[i - 1])
                    d_res.append((mp[i] - gt[i]) - (mp[i - 1] - gt[i - 1]))
            for i in range(1, len(t) - 1):
                if t[i] - t[i - 1] > PAIR_GAP_MS or t[i + 1] - t[i] > PAIR_GAP_MS:
                    continue
                trip = mp[i - 1:i + 2]
                gtrip = gt[i - 1:i + 2]
                if not (np.all(np.isfinite(trip)) and np.all(np.isfinite(gtrip))):
                    continue
                triplets += 1
                dev = trip[1] - (trip[0] + trip[2]) / 2
                gdev = gtrip[1] - (gtrip[0] + gtrip[2]) / 2
                peak = (trip[1] - trip[0]) * (trip[1] - trip[2]) > 0
                for T in SPIKE_T:
                    if peak and abs(dev) > T and abs(gdev) < T / 2:
                        spikes[T] += 1
            st = s["status"]
            val = np.isfinite(mp)
            for scope, m in (("inSet", s["inSet"]), ("outside", ~s["inSet"])):
                cnt = status_all[scope]
                cnt["samples"] += int(m.sum())
                cnt["nopose"] += int((m & (st == 1)).sum())
                cnt["noFeatures"] += int((m & (st == 2)).sum())
                cnt["noSignal"] += int((m & (st == 0) & ~val).sum())
            runs_in.append(_runs(~val[s["inSet"]], t[s["inSet"]]))
            runs_all.append(_runs(~val, t))
            if ex == "덤벨 컬":
                ok = st == 0
                arm["poseOk"] += int(ok.sum())
                arm["L_missing"] += int((ok & ~np.isfinite(s["feat"]["elbow_L"])).sum())
                arm["R_missing"] += int((ok & ~np.isfinite(s["feat"]["elbow_R"])).sum())
                arm["both_missing"] += int((ok & ~np.isfinite(s["feat"]["elbow_L"]) & ~np.isfinite(s["feat"]["elbow_R"])).sum())

        def sig(x):
            x = np.array(x)
            if len(x) < 5:
                return None
            mad = float(np.median(np.abs(x - np.median(x)))) * 1.4826
            return {"sigma": float(x.std() / math.sqrt(2)), "robustSigma": mad / math.sqrt(2), "n": int(len(x))}

        def rates(c):
            n = max(1, c["samples"])
            return {"samples": c["samples"], "nopose": c["nopose"] / n, "noFeatures": c["noFeatures"] / n, "noSignal": c["noSignal"] / n}

        def merge_runs(rs):
            return {"runs": sum(r["runs"] for r in rs), "maxLen": max([r["maxLen"] for r in rs] + [0]),
                    "medianLenOfSetMedians": float(np.median([r["medianLen"] for r in rs if r["runs"]])) if any(r["runs"] for r in rs) else 0.0,
                    "gapsOver1500ms": sum(r["gapsOver1500ms"] for r in rs),
                    "setsWithGap": sum(r["gapsOver1500ms"] > 0 for r in rs)}

        e = {"signal": k, "restJitter": {"mediapipe": sig(d_mp), "residual": sig(d_res), "pose3d": sig(d_gt)},
             "spikes": {f"T{int(T)}": {"count": spikes[T], "rate": spikes[T] / max(1, triplets)} for T in SPIKE_T},
             "triplets": triplets, "dropout": {"inSet": rates(status_all["inSet"]), "outside": rates(status_all["outside"])},
             "signalMissingRuns": {"inSet": merge_runs(runs_in), "whole": merge_runs(runs_all)}}
        if arm:
            n = max(1, arm["poseOk"])
            e["elbowVisibility"] = {"poseOk": arm["poseOk"], "L_missing": arm["L_missing"] / n, "R_missing": arm["R_missing"] / n,
                                    "both_missing": arm["both_missing"] / n}
        out[ex] = e
    return out


def write_paired3d(data: dict, root: Path, out: Path) -> Path:
    """MediaPipe 캡처와 같은 샘플 시각의 3D 캡처(추정 지연 적용). MediaPipe 가 사람을 놓친 샘플도 3D 는 있다 — 끊김의 몫이 카운트 차이에 들어간다."""
    out.mkdir(parents=True, exist_ok=True)
    poses = {}
    sets = []
    for smp in data["samples"]:
        w = smp["workout"]
        if w not in poses:
            pose = np.load(root / w / f"{w}_pose_3d.npy")
            frame_no = pose[0, :, 0].astype(int)
            poses[w] = ({f: i for i, f in enumerate(frame_no)}, pose[:, :, 1:])
        row_of, joints = poses[w]
        lines = []
        for t, v in zip(smp["t"], smp["frames"]):
            p = int(v) - smp["lag"]
            lm = mp_landmarks(joints[:, row_of[p], :]) if p in row_of else None
            lines.append(frame_line(int(t), lm))
        s = dict(smp["set"])
        write_capture(out / s["capture"], {"source": "mmfit_pose3d_paired", "workout": w, "lagFrames": smp["lag"]}, lines)
        sets.append(s)
    (out / "index.json").write_text(json.dumps({"source": "MM-Fit pose_3d at the MediaPipe capture sample times (lag applied)",
                                                "sets": sets}, ensure_ascii=False, indent=1), encoding="utf-8")
    return out


def count_compare(data: dict, mp_dir: Path, paired: Path, out: Path, configs: set[str]) -> dict:
    """같은 구성으로 두 입력을 재생해 세트별 차이를 낸다. 세트 창은 라벨 시각에 추정 지연을 더해 영상 시계로 옮긴다."""
    _, res_mp, names = run_replay.replay_index(mp_dir, out / "replay_mp", configs)
    _, res_3d, _ = run_replay.replay_index(paired, out / "replay_3d", configs)
    shift = {}
    for smp in data["samples"]:
        shift[smp["set"]["capture"]] = int(round(smp["lag"] * 1000.0 / smp["fps"]))
    table = defaultdict(lambda: defaultdict(list))
    for smp in data["samples"]:
        s = dict(smp["set"])
        sh = shift[s["capture"]]
        s["startMs"], s["endMs"] = s["startMs"] + sh, s["endMs"] + sh
        key = run_replay.set_key(s)
        for c in names:
            a, b = res_mp.get(f"{key}|{c}"), res_3d.get(f"{key}|{c}")
            if a is None or b is None or "error" in a or "error" in b:
                continue
            sa, sb = run_replay.split_set(s, a), run_replay.split_set(s, b)
            table[c][s["exercise"]].append({"set": s["capture"], "truth": s["truthReps"], "mp": sa["count"], "pose3d": sb["count"],
                                            "leadMp": sa["lead"], "lead3d": sb["lead"], "afterMp": sa["after"], "after3d": sb["after"]})
    out_sum = {}
    for c, by_ex in table.items():
        out_sum[c] = {}
        for ex, rows in sorted(by_ex.items()):
            diff = [r["mp"] - r["pose3d"] for r in rows]

            def per(key):
                v = [r[key] for r in rows if r[key] is not None]
                return sum(v) / len(v) if v else None
            out_sum[c][ex] = {
                "sets": len(rows), "meanDiff": float(np.mean(diff)), "setsDiffer": sum(d != 0 for d in diff),
                "mpLower": sum(d < 0 for d in diff), "mpHigher": sum(d > 0 for d in diff),
                "exactMp": sum(r["mp"] == r["truth"] for r in rows) / len(rows),
                "exact3d": sum(r["pose3d"] == r["truth"] for r in rows) / len(rows),
                "leadPerSetMp": per("leadMp"), "leadPerSet3d": per("lead3d"),
                "afterPerSetMp": per("afterMp"), "afterPerSet3d": per("after3d"), "perSet": rows}
    return out_sum


def _g(v, fmt=".2f"):
    return "—" if v is None or (isinstance(v, float) and v != v) else format(v, fmt)


def markdown(result: dict) -> str:
    L = [f"# MediaPipe ↔ MM-Fit 3D 짝짓기 — {result['source']}", ""]
    L += ["## 워크아웃별 지연 (lag = 영상 프레임 − 라벨 프레임)", "",
          "| 워크아웃 | fps | 세트 | lag (상관 최대) | r | r(lag 0) | lag (중앙 절대차 최소) | 좌우 확인 (r 같은쪽 / 반대쪽) |",
          "|---|---:|---:|---:|---:|---:|---:|---|"]
    for w, x in result["workouts"].items():
        sd = x["side"]
        side = f"{_g(sd.get('rSame'))} / {_g(sd.get('rCross'))}" + (" **뒤바뀜**" if sd.get("swapped") else "") if sd.get("checked") else "—"
        L.append(f"| {w} | {x['fps']:.2f} | {x['sets']} | {x['lag']} | {_g(x['r'], '.3f')} | {_g(x['r0'], '.3f')} | {x['lagByMedianAbs']} | {side} |")
    L += ["", "## 각도 차이 (MediaPipe − 3D, 세트 ± 0.5 s 안, 도)", "",
          "| 종목 | 피처 | n | 편향 | MAE | sd | 세트 내 잔차 σ | 3D <90 편향/MAE | 90–120 | 120–150 | ≥150 |",
          "|---|---|---:|---:|---:|---:|---:|---|---|---|---|"]
    for ex, feats in result["angles"].items():
        for k, st in feats.items():
            if not st.get("n"):
                continue
            bins = [st["byDepth"][f"{a}-{b}"] for a, b in DEPTH_BINS]
            cells = [f"{_g(x.get('bias'), '+.1f')}/{_g(x.get('MAE'), '.1f')} ({x['n']})" if x.get("n") else "—" for x in bins]
            L.append(f"| {ex} | {k} | {st['n']} | {st['bias']:+.2f} | {st['MAE']:.2f} | {st['sd']:.2f} | {_g(st['residualSigma'])} | " + " | ".join(cells) + " |")
    L += ["", "## 잡음 — 휴식 지터 · 한 샘플 튐 · 끊김 (대표 신호)", "",
          "| 종목 | 신호 | 휴식 지터 σ MediaPipe / 잔차 / 3D (n) | 튐 >10° / >20° / >35° (비율) | 사람 없음 세트 안/밖 | 피처 없음 | 신호 없음 | 빈 구간 최장(샘플) | 1.5 s 넘는 틈 (세트 안 / 전체) |",
          "|---|---|---|---|---|---|---|---:|---|"]
    for ex, e in result["noise"].items():
        rj = e["restJitter"]
        jit = " / ".join(_g((rj[k] or {}).get("sigma")) for k in ("mediapipe", "residual", "pose3d")) + f" ({(rj['residual'] or {}).get('n', 0)})"
        sp = " / ".join(f"{e['spikes'][f'T{int(T)}']['rate']:.4f}" for T in SPIKE_T)
        di, do = e["dropout"]["inSet"], e["dropout"]["outside"]
        runs = e["signalMissingRuns"]
        L.append(f"| {ex} | `{e['signal']}` | {jit} | {sp} | {di['nopose']:.3f} / {do['nopose']:.3f} | {di['noFeatures']:.3f} | "
                 f"{di['noSignal']:.3f} | {runs['whole']['maxLen']} | {runs['inSet']['gapsOver1500ms']} / {runs['whole']['gapsOver1500ms']} |")
        if "elbowVisibility" in e:
            v = e["elbowVisibility"]
            L.append(f"| {ex} | 팔별 가시성 | 사람 검출 {v['poseOk']} 샘플 중 elbow_L 없음 {v['L_missing']:.3f} · elbow_R 없음 {v['R_missing']:.3f} · 둘 다 {v['both_missing']:.3f} | | | | | | |")
    if result.get("counts"):
        L += ["", "## 세트 카운트 — MediaPipe vs 같은 시각의 3D (같은 JVM 재생기·같은 구성)", "",
              "| 구성 | 종목 | 세트 | 평균 차이 (MP−3D) | 다른 세트 (MP 적음/많음) | 정확 일치 MP / 3D | 앞 헛카운트/세트 MP / 3D | 뒤 헛카운트/세트 MP / 3D |",
              "|---|---|---:|---:|---|---|---|---|"]
        for c, by_ex in result["counts"].items():
            for ex, m in by_ex.items():
                L.append(f"| {c} | {ex} | {m['sets']} | {m['meanDiff']:+.2f} | {m['setsDiffer']} ({m['mpLower']}/{m['mpHigher']}) | "
                         f"{m['exactMp']:.2f} / {m['exact3d']:.2f} | {_g(m['leadPerSetMp'])} / {_g(m['leadPerSet3d'])} | "
                         f"{_g(m['afterPerSetMp'])} / {_g(m['afterPerSet3d'])} |")
    return "\n".join(L) + "\n"


def compare(mp_dir: Path, root: Path, out: Path, max_lag: int, configs: set[str] | None, counts: bool = True) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    workouts, data = analyze(mp_dir, root, max_lag)
    result = {"source": data["index"].get("source"), "mediapipeDir": str(mp_dir), "workouts": workouts,
              "angles": angle_stats(data["samples"]), "noise": noise_stats(data["samples"])}
    if counts and data["samples"]:
        paired = write_paired3d(data, root, out / "paired3d")
        result["counts"] = count_compare(data, mp_dir, paired, out, configs or COUNT_CONFIGS)
    (out / "pair_summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=1, default=float), encoding="utf-8")
    md = markdown(result)
    (out / "pair_summary.md").write_text(md, encoding="utf-8")
    return result


# ---------------------------------------------------------------- 자가 검증

def selftest(root: Path, work: Path, workouts: list[str]) -> int:
    checks = []

    def check(name, ok, detail=""):
        checks.append((name, bool(ok), detail))

    # 1) 잡음 없는 합성(지연만): 도구가 아무것도 더하지 않아야 한다 — 지연 복원, 각도 차이 0, 카운트 차이 0
    clean_dir = work / "synth_clean"
    synth(root, clean_dir, workouts, 15.0, 10.0, 4, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1)
    rc = compare(clean_dir, root, work / "cmp_clean", MAX_LAG, {"live", "hysteresis"})
    lags = {w: x["lag"] for w, x in rc["workouts"].items()}
    check("잡음 없음: 워크아웃마다 지연 4 복원", lags and all(v == 4 for v in lags.values()), str(lags))
    worst = max((st["MAE"] for feats in rc["angles"].values() for st in feats.values() if st.get("n")), default=None)
    check("잡음 없음: 각도 MAE < 0.01°", worst is not None and worst < 0.01, f"{worst}")
    differ = sum(m["setsDiffer"] for by in rc["counts"].values() for m in by.values())
    nsets = sum(m["sets"] for by in rc["counts"].values() for m in by.values())
    check("잡음 없음: MediaPipe 흉내 vs 3D 세트 카운트 차이 0 (live·hysteresis)", differ == 0 and nsets > 0, f"{differ}/{nsets}")
    lead_eq = all(m["leadPerSetMp"] == m["leadPerSet3d"] and m["afterPerSetMp"] == m["afterPerSet3d"]
                  for by in rc["counts"].values() for m in by.values())
    check("잡음 없음: 앞·뒤 헛카운트도 같다", lead_eq)
    has_lead = any(m["leadPerSetMp"] is not None for by in rc["counts"].values() for m in by.values())
    check("앞뒤 음성 구간(--pre-s 15 --post-s 10)이 채점된다", has_lead)

    # 2) 잡음 합성: 주입량을 도구가 되찾는가 (정답은 합성기가 3D 관절에서 따로 계산한 값)
    noisy_dir = work / "synth_noisy"
    synth(root, noisy_dir, workouts, 15.0, 10.0, 4, 15.0, 0.02, 150.0, 0.04, 2.0, 0.15, 2)
    truth = json.loads((noisy_dir / "synth_truth.json").read_text(encoding="utf-8"))["byExercise"]
    rn = compare(noisy_dir, root, work / "cmp_noisy", MAX_LAG, None)
    lags = {w: x["lag"] for w, x in rn["workouts"].items()}
    check("잡음 있음: 지연 4 복원", lags and all(v == 4 for v in lags.values()), str(lags))
    for ex, tr in truth.items():
        e = rn["noise"].get(ex)
        if e is None:
            continue
        nop = (e["dropout"]["inSet"]["nopose"] * e["dropout"]["inSet"]["samples"] + e["dropout"]["outside"]["nopose"]
               * e["dropout"]["outside"]["samples"]) / max(1, e["dropout"]["inSet"]["samples"] + e["dropout"]["outside"]["samples"])
        check(f"{ex}: 사람 없음 비율 ≈ 주입", abs(nop - tr["noposeFraction"]) < 0.005, f"도구 {nop:.4f} / 주입 {tr['noposeFraction']:.4f}")
        # 정답 σ 는 튐 없는 휴식 샘플의 가우스 잡음 — 도구의 튐에 강한 σ(MAD) 와 비교한다(표준편차는 튐에 끌린다)
        est = (e["restJitter"]["residual"] or {}).get("robustSigma")
        raw = (e["restJitter"]["residual"] or {}).get("sigma")
        true = tr["restNoiseSigma"]
        ok = est is not None and true is not None and 0.8 < est / true < 1.25
        check(f"{ex}: 휴식 지터 σ(잔차, MAD) ≈ 합성 휴식 각도 잡음 σ", ok, f"도구 {est:.2f} (std {raw:.2f}) / 정답 {true:.2f}" if ok or est else f"{est} / {true}")
        # 튐은 여기서 보고만 한다 — 지터가 섞이면 가우스 꼬리도 20° 를 넘어(샘플당 σ≈5° 면 이웃 평균 대비 σ≈6°) '정답' 이 모호하다.
        # 튐 검출기 자체는 아래 튐-전용 합성으로 검증한다.
        if ex == "덤벨 컬":
            v = e["elbowVisibility"]
            check("컬: elbow_L 없음 비율 ≈ 먼 팔 가림 주입", abs(v["L_missing"] - tr["farArmFraction"]) < 0.01 and v["R_missing"] < 0.005,
                  f"L {v['L_missing']:.4f} / 주입 {tr['farArmFraction']:.4f}, R {v['R_missing']:.4f}")
    # 3) 튐 전용 합성(지터·끊김·가림 없음): 20° 넘는 각도 튐(합성기가 3D 관절에서 계산)을 검출기가 되찾는가
    spike_dir = work / "synth_spikes"
    synth(root, spike_dir, workouts, 15.0, 10.0, 4, 0.0, 0.03, 300.0, 0.0, 1.0, 0.0, 3)
    truth_s = json.loads((spike_dir / "synth_truth.json").read_text(encoding="utf-8"))["byExercise"]
    rs = compare(spike_dir, root, work / "cmp_spikes", MAX_LAG, None, counts=False)
    for ex, tr in truth_s.items():
        e = rs["noise"].get(ex)
        if e is None or tr["angleSpikes20"] < 5:
            continue
        det, tru = e["spikes"]["T20"]["count"], tr["angleSpikes20"]
        check(f"튐 전용 {ex}: 20° 튐 검출 수 ≈ 주입된 20° 넘는 각도 튐", 0.8 <= det / tru <= 1.2, f"도구 {det} / 정답 {tru}")
    zero = all(e["spikes"][f"T{int(T)}"]["count"] == 0 for e in rc["noise"].values() for T in SPIKE_T)
    check("잡음 없음: 튐 0건", zero)
    got = {c for c in rn.get("counts", {})}
    check("카운트 비교 구성: live·hysteresis·신호 후보", {"live", "hysteresis"} <= got, str(sorted(got)))
    width = max(len(n) for n, _, _ in checks)
    for n, ok, d in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {n.ljust(width)}  {d}")
    failed = sum(not ok for _, ok, _ in checks)
    print(f"\n{len(checks) - failed}/{len(checks)} 통과 — 작업 폴더 {work}")
    print("\n--- 잡음 합성 결과 표 (MediaPipe 결과가 아니다) ---\n")
    print(markdown(rn))
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("compare")
    c.add_argument("mediapipe", type=Path)
    c.add_argument("dataset_root", type=Path)
    c.add_argument("--out", type=Path, required=True)
    c.add_argument("--max-lag", type=int, default=MAX_LAG)
    c.add_argument("--configs", default=None, help="콤마 목록 (기본 live,hysteresis 와 신호 후보)")
    c.add_argument("--no-counts", action="store_true", help="각도·잡음만 (JVM 재생 없이)")
    s = sub.add_parser("synth")
    s.add_argument("dataset_root", type=Path)
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--workouts", nargs="*", default=None)
    s.add_argument("--pre-s", type=float, default=15.0)
    s.add_argument("--post-s", type=float, default=10.0)
    s.add_argument("--lag", type=int, default=4)
    s.add_argument("--jitter-mm", type=float, default=15.0)
    s.add_argument("--spike", type=float, default=0.02, help="샘플당 한 관절 튐 확률")
    s.add_argument("--spike-mm", type=float, default=150.0)
    s.add_argument("--dropout", type=float, default=0.04, help="사람 없음 샘플 비율(연속 구간으로)")
    s.add_argument("--dropout-len", type=float, default=2.0, help="끊김 구간 평균 길이(샘플)")
    s.add_argument("--far-arm", type=float, default=0.15, help="샘플당 왼팔(팔꿈치·손목) 가시성 < 0.5 확률")
    s.add_argument("--seed", type=int, default=7)
    t = sub.add_parser("selftest")
    t.add_argument("dataset_root", type=Path)
    t.add_argument("--out", type=Path, required=True)
    t.add_argument("--workouts", nargs="*", default=["w00", "w01", "w13"])
    args = ap.parse_args()
    if args.cmd == "synth":
        idx = synth(args.dataset_root, args.out, args.workouts, args.pre_s, args.post_s, args.lag, args.jitter_mm, args.spike,
                    args.spike_mm, args.dropout, args.dropout_len, args.far_arm, args.seed)
        print(f"{len(idx['sets'])} sets -> {args.out} ({idx['source']})")
        return 0
    if args.cmd == "selftest":
        return selftest(args.dataset_root, args.out, args.workouts)
    configs = set(args.configs.split(",")) if args.configs else None
    result = compare(args.mediapipe, args.dataset_root, args.out, args.max_lag, configs, not args.no_counts)
    print(markdown(result))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
