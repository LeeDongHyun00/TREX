# -*- coding: utf-8 -*-
"""시작 확정 정책 스트레스 배터리 — 설계 §12(사전 등록)의 선택 규칙을 MM-Fit 3D 포즈에서 재현 가능하게 잰다.

무엇을 재나
-----------
복귀 히스테리시스 코어(prototype_counter.Counter, 앱 간격 300 ms, 게이트 35°, 평활 없음) 뒤에 시작 확정 정책
(prototype_counter.POLICIES: 기준 none, 사전 등록 후보 4s/4s · first8+amp · first10+amp · tempo, 사후 탐색 first8/later8 ·
first8|tempo — 사후 탐색은 선택 규칙에 들어가지 않는다)을 붙여, 종목·신호 조합마다 아래 조건에서 세트 카운트와
세트 앞뒤 헛카운트를 잰다. 보류 규약은 앱 RepStartConfirmation 과 같다(confirm(drop_unpaired=True)).

  normal        라벨 세트 ± 0.5 s (지금까지의 표와 같은 창)
  stretch1.5/2/2.5  30 fps 포즈 시퀀스를 시간축으로 늘려(관절 선형 보간) 느린 템포를 만든다
  hold5         마지막 반복 직전 상단에서 5 s 멈춤(바닥은 30 fps 신호에서 오프라인 검출, 검출 수 ≠ 정답이면 제외)
  trunc1/trunc2 첫 1·2 반복 뒤 상단에서 끊고 1.5 s 서 있기(정답 1·2)
  lead10/lead15 세트 앞 실제 프레임 10·15 s 를 세트와 이어서 재생(이웃 세트 ± 0.5 s 는 넘지 않는다)
  after10       세트 뒤 실제 프레임 10 s 를 이어서 재생
  mirror        신호 부호를 뒤집어(반복 중 커지는 신호) 극성 가정이 틀렸을 때를 본다
  occl_rand9 / occl_flex30   컬만: 먼 팔의 팔꿈치가 무작위 9% 샘플, 또는 굴곡 구간 샘플의 30% 에서 사라진다
                (먼 팔 = 왼·오른 각각 한 번씩, 세트가 두 번 들어간다). elbow_mean 은 그 샘플이 없어지고,
                elbow_minside 는 보이는 팔 값이 되며(앱 PostureCore 와 같다), 팔별 카운터는 먼 팔 레인만 빈다
  synthetic     코사인 10회 세트(반복당 2/3/4/4.5/6/8/10/12 s, 표본 위상 5개) — 스쿼트형(양 무릎 동시)·교대 컬·동시 컬
  synthetic occlusion  합성 교대·동시 컬(2/3/4 s)에 위 두 가림을 먼 팔(R)에 넣는다 — MM-Fit 컬은 교대뿐이라서

조합: 스쿼트 knee_mean · 런지 knee_mean / knee_minside · 컬 elbow_mean / elbow_minside / 팔별 + 500 ms 병합,
진단용 컬 elbow_minside_both(한쪽 팔이 안 보이는 샘플은 건너뜀).
판정(설계 §12): 스쿼트 knee_mean · 런지 knee_mean · 컬 elbow_mean · 컬 elbow_minside 네 조합 모두에서
(a) 정상 정확 일치 ≥ none − 0.02 (b) ×2 ≥ 0.90 (c) hold5 ≥ 0.95 (d) 정상 과다 0 → 통과 중 세트 앞 헛카운트 최소.
지표: 재현율(Σmin(정답, 카운트)/Σ정답), 정확 일치, ±1, 과다 세트, 0회 세트, 세트 앞·뒤 창의 헛카운트(발표된 발화 중
그 발화 시각이 세트 ± 0.5 s 밖인 것), 끝까지 보류된 후보 수. 모든 표에 "항상 10" 기준선을 싣는다.

주의: MM-Fit 은 이 설계의 개발 데이터다(설계 §12). 결과는 정책 사이의 비교이고 일반화 성능이 아니다.
코어 상수(게이트 35°·f 0.25·불응기 0.8 s·maxGap 1.5 s)는 설계 §4.2 의 값 그대로이고 이 배터리로 바꾸지 않는다 — 배터리가 고르는
것은 §12 에 적어 둔 후보 중 시작 확정 정책 하나뿐이다(평활 없음은 그 전에 MM-Fit 재현율을 보고 정했다, 설계 §4.2).

사용법 (1분 안쪽, numpy·scipy):
    python stress_battery.py ../../data/mm-fit/mm-fit --out results/design_v2_battery.json \
        [--per-set ../../data/mm-fit/exp_design_v2/per_set.json]
결과 JSON 의 preregistrationSha12 는 실행 시점 설계 문서 §12 본문의 해시다 — 본문이 바뀌면 달라진다. 이 해시는 "이 결과가 이
§12 본문으로 판정됐다" 를 묶을 뿐 **§12 가 결과보다 먼저 쓰였다는 증거는 아니다**. 순서의 증거는 §12 만 담은 커밋을 결과보다 먼저
남기는 것이다(설계 §13 머리 참고).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy.signal import find_peaks

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prototype_counter as pc  # noqa: E402

FPS = pc.FPS
CADENCE_MS = 300          # 앱 RECORDING 간격
MARGIN = 15               # 0.5 s — 지금까지의 표와 같은 세트 여유
H_ANGLE = 35.0            # 각도형 최소 스윙(모집단 값, 설계 §4.2)
MERGE_MS = 500            # 교대 병합 창(설계 §4.4)
SEED = 20260924
POLICY_NAMES = list(pc.POLICIES)
CANDIDATES = ["4s/4s", "first8+amp", "first10+amp", "tempo"]   # 설계 §12 에 사전 등록된 후보. 나머지는 기준(none)·사후 탐색

# (조합 키, 종목, 신호, 방식). 방식 single = 신호 하나에 카운터 하나, lanes = 좌·우 카운터 + 병합
COMBOS = [
    ("squat|knee_mean", "squat", "knee_mean", "single"),
    ("lunge|knee_mean", "lunge", "knee_mean", "single"),
    ("lunge|knee_minside", "lunge", "knee_minside", "single"),
    ("curl|elbow_mean", "curl", "elbow_mean", "single"),
    ("curl|elbow_minside", "curl", "elbow_minside", "single"),
    ("curl|lanes", "curl", "elbow", "lanes"),
    # 진단: 한쪽 팔이 안 보이면 minside 샘플을 버린다(앱 PostureCore 는 보이는 쪽 값을 쓴다 — 위 elbow_minside 가 그 동작)
    ("curl|elbow_minside_both", "curl", "elbow_minside_both", "single"),
]
GATING = ["squat|knee_mean", "lunge|knee_mean", "curl|elbow_mean", "curl|elbow_minside"]   # 설계 §12 판정 조합
TROUGH_SIGNAL = {"squat": "knee_mean", "lunge": "knee_mean", "curl": "elbow_minside"}
SET_CONDITIONS = ["normal", "stretch1.5", "stretch2", "stretch2.5", "hold5", "trunc1", "trunc2",
                  "lead10", "lead15", "after10", "mirror", "occl_rand9", "occl_flex30"]
SYN_TEMPI = [2.0, 3.0, 4.0, 4.5, 6.0, 8.0, 10.0, 12.0]   # 과제의 6개 + 앱 페이스 설정 상한(15 s) 쪽 두 개


# ---------------------------------------------------------------- 카운터 실행

def run_stream(t_ms, streams, mode, sign=1.0):
    """t_ms: 샘플 시각(ms). streams: single → {"v": 배열}, lanes → {"L":, "R":}. NaN = 그 샘플에 값 없음.
    반환: (발화 시각, 진폭, 사이클 길이) — 병합 뒤."""
    if mode == "single":
        c = pc.Counter(H_ANGLE)
        v = streams["v"]
        for k in range(len(t_ms)):
            x = v[k]
            c.on_frame(int(t_ms[k]), None if not np.isfinite(x) else sign * float(x))
        return c.fires, c.amps, c.durs
    cs = {s: pc.Counter(H_ANGLE) for s in "LR"}
    fires, amps, durs, last = [], [], [], {"L": -10**9, "R": -10**9}
    for k in range(len(t_ms)):
        t = int(t_ms[k])
        for s in "LR":
            x = streams[s][k]
            if cs[s].on_frame(t, None if not np.isfinite(x) else sign * float(x)):
                other = "R" if s == "L" else "L"
                if t - last[other] > MERGE_MS:
                    fires.append(t); amps.append(cs[s].amps[-1]); durs.append(cs[s].durs[-1])
                last[s] = t
    return fires, amps, durs


def streams_for(F, signal, mode):
    if mode == "single":
        return {"v": F[signal]}
    return {"L": F["elbow_L"], "R": F["elbow_R"]}


# ---------------------------------------------------------------- 시간축 조작

def interp_joints(J, frame0, pos):
    """J: (3, N, 17), frame0 = J 의 0번 행 프레임 번호, pos: 원래 프레임 위치(실수) → (3, M, 17) 선형 보간."""
    x = np.clip(np.asarray(pos, dtype=float) - frame0, 0, J.shape[1] - 1)
    i0 = np.floor(x).astype(int); i1 = np.minimum(i0 + 1, J.shape[1] - 1); w = (x - i0)[None, :, None]
    return J[:, i0, :] * (1 - w) + J[:, i1, :] * w


def timeline(segments):
    """segments: [("play", a, b, speed) | ("hold", p, seconds)] → 300 ms 샘플의 원래 프레임 위치 배열.
    play 는 원래 프레임 a→b 를 speed 배 느리게(speed=2 면 두 배 길게), hold 는 프레임 p 에 멈춘다."""
    out, t = [], 0.0
    step = CADENCE_MS / 1000.0
    # 세그먼트를 시간 구간으로 펼치고 0, 0.3, 0.6 … 에서 위치를 읽는다
    spans, cur = [], 0.0
    for seg in segments:
        if seg[0] == "play":
            _, a, b, k = seg
            dur = (b - a) / FPS * k
            spans.append((cur, cur + dur, "play", a, k))
        else:
            _, p, sec = seg
            dur = sec
            spans.append((cur, cur + dur, "hold", p, None))
        cur += dur
    while t <= cur + 1e-9:
        for s0, s1, kind, a, k in spans:
            if s0 - 1e-9 <= t <= s1 + 1e-9:
                out.append(a + (t - s0) * FPS / k if kind == "play" else a)
                break
        t += step
    return np.array(out)


def troughs(x, n, s, e):
    """30 fps 신호 x(프레임 s..e)에서 반복 바닥 n 개를 찾는다. 못 찾으면 None. 반환: 프레임 번호(오름차순)."""
    seg = x[s:e + 1]
    sm = np.convolve(seg, np.ones(5) / 5, mode="same")
    dist = max(8, int(0.5 * (e - s) / max(1, n)))
    pk, prop = find_peaks(-sm, distance=dist, prominence=20.0)
    if len(pk) < n:
        return None
    if len(pk) > n:
        keep = np.argsort(prop["prominences"])[-n:]
        pk = np.sort(pk[keep])
    return [s + int(p) for p in pk]


def top_between(x, a, b):
    return a + int(np.argmax(x[a:b + 1]))


# ---------------------------------------------------------------- 채점

def empty_metrics():
    return dict(sets=0, truth=0, counted=0, hit=0, exact=0, obo=0, over=0, zero=0,
                leadFalse=0, afterFalse=0, leadWindows=0, afterWindows=0, leadSetsWithFalse=0,
                afterSetsWithFalse=0, pendingAtEnd=0, leadSeconds=0.0, afterSeconds=0.0)


def add(m, truth, count, lead_false=0, after_false=0, lead_win=False, after_win=False, pending=0, lead_s=0.0, after_s=0.0):
    m["sets"] += 1; m["truth"] += truth; m["counted"] += count; m["hit"] += min(truth, count)
    m["exact"] += int(count == truth); m["obo"] += int(abs(count - truth) <= 1)
    m["over"] += int(count > truth); m["zero"] += int(count == 0)
    m["leadFalse"] += lead_false; m["afterFalse"] += after_false
    m["leadWindows"] += int(lead_win); m["afterWindows"] += int(after_win)
    m["leadSetsWithFalse"] += int(lead_false > 0); m["afterSetsWithFalse"] += int(after_false > 0)
    m["pendingAtEnd"] += pending; m["leadSeconds"] += lead_s; m["afterSeconds"] += after_s


def finish(m):
    n = max(1, m["sets"])
    out = dict(m)
    out.update(recall=m["hit"] / max(1, m["truth"]), exactRate=m["exact"] / n, oboRate=m["obo"] / n,
               leadPerWindow=m["leadFalse"] / max(1, m["leadWindows"]), afterPerWindow=m["afterFalse"] / max(1, m["afterWindows"]),
               meanLeadSeconds=m["leadSeconds"] / max(1, m["leadWindows"]), meanAfterSeconds=m["afterSeconds"] / max(1, m["afterWindows"]))
    return out


SAVED = ["sets", "truth", "counted", "recall", "exactRate", "oboRate", "over", "zero", "leadFalse", "leadWindows",
         "afterFalse", "afterWindows", "leadSetsWithFalse", "afterSetsWithFalse", "pendingAtEnd", "meanLeadSeconds", "meanAfterSeconds"]


SYN_SAVED = ["sets", "counted", "exactRate", "pendingAtEnd"]


def slim(m, keys=None):
    """결과 파일에 남기는 지표(소수 4자리). 앞·뒤 창이 없는 조건에서는 창 지표를 뺀다."""
    f = finish(m)
    keys = list(keys or SAVED)
    if not f["leadWindows"]:
        keys = [k for k in keys if k not in ("leadFalse", "leadWindows", "leadSetsWithFalse", "meanLeadSeconds")]
    if not f["afterWindows"]:
        keys = [k for k in keys if k not in ("afterFalse", "afterWindows", "afterSetsWithFalse", "meanAfterSeconds")]
    return {k: (round(f[k], 4) if isinstance(f[k], float) else f[k]) for k in keys}


def dump(obj, level=0):
    """중첩 dict 는 들여 쓰고, 지표 dict(값이 전부 숫자)는 한 줄로 — git diff 가 읽히는 크기로."""
    pad = " " * level
    if isinstance(obj, dict) and obj and not all(isinstance(v, (int, float)) or v is None for v in obj.values()):
        items = [f'{pad} {json.dumps(k, ensure_ascii=False)}: {dump(v, level + 1).lstrip()}' for k, v in obj.items()]
        return pad + "{\n" + ",\n".join(items) + "\n" + pad + "}"
    return pad + json.dumps(obj, ensure_ascii=False)


# ---------------------------------------------------------------- 배터리

def run_battery(root: Path):
    res = defaultdict(lambda: defaultdict(lambda: defaultdict(empty_metrics)))   # cond -> combo -> policy -> metrics
    base = defaultdict(lambda: defaultdict(empty_metrics))                        # cond -> kind -> always-10
    skipped = defaultdict(lambda: defaultdict(int))                              # cond -> kind -> sets without troughs
    per_set = []                                                                  # normal·stretch2·hold5 세트별 카운트(검토용)
    rng_master = np.random.default_rng(SEED)
    for wdir in sorted(p for p in root.iterdir() if p.is_dir()):
        w = wdir.name
        pp, lp = wdir / f"{w}_pose_3d.npy", wdir / f"{w}_labels.csv"
        if not pp.is_file() or not lp.is_file():
            continue
        pose = np.load(pp)
        frame_no = pose[0, :, 0].astype(int)
        f0, f_last = int(frame_no[0]), int(frame_no[-1])
        # 포즈에 빠진 프레임이 있다(w03·w04·w09·w10·w12·w16·w19·w20). 프레임 번호 축에 NaN 으로 두면 그 샘플은
        # '사람 없음' 이 되어 카운터가 건너뛴다(앱과 같다 — 1.5 s 넘게 비면 사이클 리셋).
        J = np.full((3, f_last - f0 + 1, 17), np.nan)
        J[:, frame_no - f0, :] = pose[:, :, 1:]
        F30 = pc.features(J)                       # 30 fps 원 신호(바닥 검출용)
        labels = pc.load_labels(lp)
        for li, (s, e, reps, act) in enumerate(labels):
            kind = pc.ACTS.get(act)
            if kind is None:
                continue
            # 포즈가 세트 여유를 다 덮지 않으면(예: w00 첫 세트는 포즈가 라벨보다 늦게 시작) 있는 프레임만 쓴다
            a0, b0 = max(f0, s - MARGIN), min(f_last, e + MARGIN)
            prev_end = max([ee for (ss, ee, _, _) in labels if ee < s] + [f0 - 10**6])
            next_start = min([ss for (ss, ee, _, _) in labels if ss > e] + [f_last + 10**6])
            x30 = F30[TROUGH_SIGNAL[kind]].copy()
            ok = np.isfinite(x30)
            x30[~ok] = np.interp(np.nonzero(~ok)[0], np.nonzero(ok)[0], x30[ok])   # 바닥·상단 검출에만 쓰는 보간
            tr = troughs(x30, reps, max(0, s - f0), min(len(x30) - 1, e - f0))
            tr = None if tr is None else [f0 + q for q in tr]

            conds = {}
            conds["normal"] = ([("play", a0, b0, 1.0)], reps)
            for k in (1.5, 2.0, 2.5):
                conds[f"stretch{k:g}"] = ([("play", a0, b0, k)], reps)
            if tr is not None and reps >= 2:
                p_last = top_between(x30, tr[-2] - f0, tr[-1] - f0) + f0
                conds["hold5"] = ([("play", a0, p_last, 1.0), ("hold", p_last, 5.0), ("play", p_last, b0, 1.0)], reps)
                for kk in (1, 2):
                    if reps > kk:
                        p_k = top_between(x30, tr[kk - 1] - f0, tr[kk] - f0) + f0
                        conds[f"trunc{kk}"] = ([("play", a0, p_k, 1.0), ("hold", p_k, 1.5)], kk)
            else:
                for c in ("hold5", "trunc1", "trunc2"):
                    skipped[c][kind] += 1
            for L in (10, 15):
                la = max(f0, s - MARGIN - int(L * FPS), prev_end + MARGIN + 1)
                conds[f"lead{L}"] = ([("play", la, b0, 1.0)], reps)
            ab = min(f_last, e + MARGIN + int(10 * FPS), next_start - MARGIN - 1)
            conds["after10"] = ([("play", a0, ab, 1.0)], reps)
            conds["mirror"] = ([("play", a0, b0, 1.0)], reps)
            if kind == "curl":
                conds["occl_rand9"] = ([("play", a0, b0, 1.0)], reps)
                conds["occl_flex30"] = ([("play", a0, b0, 1.0)], reps)

            for cond, (segs, truth) in conds.items():
                pos = timeline(segs)
                t_ms = np.arange(len(pos)) * CADENCE_MS
                F = pc.features(interp_joints(J, f0, pos))
                F["elbow_minside_both"] = F["elbow_minside"]
                region = np.where(pos < s - MARGIN, 0, np.where(pos > e + MARGIN, 2, 1))   # 0 앞, 1 세트, 2 뒤
                reg_of = {int(t): int(r) for t, r in zip(t_ms, region)}
                lead_s = float((region == 0).sum() * CADENCE_MS / 1000.0)
                after_s = float((region == 2).sum() * CADENCE_MS / 1000.0)
                arms = ("L", "R") if cond.startswith("occl") else (None,)
                for far in arms:
                    Fc = F
                    if far is not None:
                        rng = np.random.default_rng(rng_master.integers(1 << 31))
                        ang = F[f"elbow_{far}"]
                        if cond == "occl_rand9":
                            drop = rng.random(len(ang)) < 0.09
                        else:
                            p95, p5 = np.percentile(ang, 95), np.percentile(ang, 5)
                            flex = ang < p95 - 0.4 * (p95 - p5)
                            drop = flex & (rng.random(len(ang)) < 0.30)
                        Fc = dict(F)
                        near = "R" if far == "L" else "L"
                        far_v = np.where(drop, np.nan, ang)
                        Fc[f"elbow_{far}"] = far_v
                        Fc["elbow_mean"] = np.where(drop, np.nan, F["elbow_mean"])
                        Fc["elbow_minside"] = np.where(drop, F[f"elbow_{near}"], F["elbow_minside"])
                        Fc["elbow_minside_both"] = np.where(drop, np.nan, F["elbow_minside"])
                    for combo, ckind, sig, mode in COMBOS:
                        if ckind != kind:
                            continue
                        if cond.startswith("occl") and ckind != "curl":
                            continue
                        sign = -1.0 if cond == "mirror" else 1.0
                        fires, amps, durs = run_stream(t_ms, streams_for(Fc, sig, mode), mode, sign)
                        for pol in POLICY_NAMES:
                            ann, pend = pc.apply_policy(pol, fires, amps, durs)
                            regs = [reg_of[int(t)] for t in ann]
                            lf, af = regs.count(0), regs.count(2)
                            add(res[cond][combo][pol], truth, len(ann), lf, af, lead_s > 0, after_s > 0, len(pend), lead_s, after_s)
                            if cond in ("normal", "stretch2", "hold5", "lead10") and far is None:
                                per_set.append(dict(w=w, label=li, cond=cond, combo=combo, policy=pol, truth=truth, count=len(ann),
                                                    leadFalse=lf, afterFalse=af, pending=len(pend),
                                                    tempo=round((e - s) / FPS / reps, 2)))
                    add(base[cond][kind], truth, 10)
    return res, base, skipped, per_set


def synthetic():
    """코사인 10회 세트. 스쿼트형 = 두 무릎 함께 170→80°. 교대 컬 = 한 반복에 한 팔 160→60°(다른 팔 휴식),
    동시 컬 = 두 팔 함께. 앞뒤 2 s 휴식. 표본 위상 0/60/120/180/240 ms."""
    res = defaultdict(lambda: defaultdict(lambda: defaultdict(empty_metrics)))
    kinds = {
        "squat-like": [("knee_mean", "single"), ("knee_minside", "single")],
        "curl-alternating": [("elbow_mean", "single"), ("elbow_minside", "single"), ("elbow", "lanes")],
        "curl-simultaneous": [("elbow_mean", "single"), ("elbow_minside", "single"), ("elbow", "lanes")],
    }
    for T in SYN_TEMPI:
        for phase in (0, 60, 120, 180, 240):
            dur = 2.0 + 10 * T + 2.0
            t = np.arange(phase, dur * 1000, CADENCE_MS)
            ts = t / 1000.0 - 2.0
            dip = lambda tt: np.where((tt >= 0) & (tt <= T), (1 - np.cos(2 * np.pi * tt / T)) / 2, 0.0)
            rep_idx = np.floor(ts / T)
            in_set = (ts >= 0) & (ts < 10 * T)
            local = ts - rep_idx * T
            d = np.where(in_set, dip(local), 0.0)
            for kname, sigs in kinds.items():
                if kname == "squat-like":
                    L = R = 170 - 90 * d
                elif kname == "curl-alternating":
                    L = 160 - 100 * np.where(rep_idx % 2 == 0, d, 0.0)
                    R = 160 - 100 * np.where(rep_idx % 2 == 1, d, 0.0)
                else:
                    L = R = 160 - 100 * d
                F = {"knee_mean": (L + R) / 2, "knee_minside": np.minimum(L, R), "elbow_mean": (L + R) / 2,
                     "elbow_minside": np.minimum(L, R), "elbow_minside_both": np.minimum(L, R), "elbow_L": L, "elbow_R": R}
                for sig, mode in sigs:
                    fires, amps, durs = run_stream(t, streams_for(F, sig, mode), mode)
                    for pol in POLICY_NAMES:
                        ann, pend = pc.apply_policy(pol, fires, amps, durs)
                        add(res[f"{T:g}s"][f"{kname}|{sig if mode == 'single' else 'lanes'}"][pol], 10, len(ann), pending=len(pend))
    return res


def synthetic_occlusion():
    """합성 컬(교대·동시, 반복당 2/3/4 s)에 먼 팔(R) 가림을 넣는다 — MM-Fit 컬은 교대뿐이라 동시 컬(AIHub·앱 사용자)의
    가림 영향은 합성으로만 본다. 위상 5개 × 난수 4개 = 조건당 20세트."""
    res = defaultdict(lambda: defaultdict(lambda: defaultdict(empty_metrics)))
    sigs = [("elbow_mean", "single"), ("elbow_minside", "single"), ("elbow_minside_both", "single"), ("elbow", "lanes")]
    for T in (2.0, 3.0, 4.0):
        for phase in (0, 60, 120, 180, 240):
            dur = 2.0 + 10 * T + 2.0
            t = np.arange(phase, dur * 1000, CADENCE_MS)
            ts = t / 1000.0 - 2.0
            rep_idx = np.floor(ts / T); local = ts - rep_idx * T
            d = np.where((ts >= 0) & (ts < 10 * T), (1 - np.cos(2 * np.pi * local / T)) / 2, 0.0)
            for kname in ("curl-alternating", "curl-simultaneous"):
                if kname == "curl-alternating":
                    L = 160 - 100 * np.where(rep_idx % 2 == 0, d, 0.0); R = 160 - 100 * np.where(rep_idx % 2 == 1, d, 0.0)
                else:
                    L = R = 160 - 100 * d
                for occl in ("rand9", "flex30"):
                    for seed in range(4):
                        rng = np.random.default_rng([SEED, int(T * 10), phase, seed, 0 if occl == "rand9" else 1])
                        if occl == "rand9":
                            drop = rng.random(len(t)) < 0.09
                        else:
                            drop = (R < 160 - 0.4 * 100) & (rng.random(len(t)) < 0.30)
                        F = {"elbow_L": L, "elbow_R": np.where(drop, np.nan, R),
                             "elbow_mean": np.where(drop, np.nan, (L + R) / 2),
                             "elbow_minside": np.where(drop, L, np.minimum(L, R)),
                             "elbow_minside_both": np.where(drop, np.nan, np.minimum(L, R))}
                        for sig, mode in sigs:
                            fires, amps, durs = run_stream(t, streams_for(F, sig, mode), mode)
                            for pol in POLICY_NAMES:
                                ann, pend = pc.apply_policy(pol, fires, amps, durs)
                                add(res[f"{T:g}s"][f"{kname}+{occl}|{sig if mode == 'single' else 'lanes'}"][pol], 10, len(ann), pending=len(pend))
    return res


def select(res):
    """설계 §12 의 사전 등록 규칙을 기계적으로 적용한다."""
    checks = {}
    for pol in CANDIDATES:
        fails = []
        for combo in GATING:
            ex0 = finish(res["normal"][combo]["none"])["exactRate"]
            m = {c: finish(res[c][combo][pol]) for c in ("normal", "stretch2", "hold5")}
            if m["normal"]["exactRate"] < ex0 - 0.02 - 1e-9:
                fails.append(f"(a) {combo}: {m['normal']['exactRate']:.3f} < none {ex0:.3f} - 0.02")
            if m["stretch2"]["exactRate"] < 0.90 - 1e-9:
                fails.append(f"(b) {combo}: x2 {m['stretch2']['exactRate']:.3f} < 0.90")
            if m["hold5"]["exactRate"] < 0.95 - 1e-9:
                fails.append(f"(c) {combo}: hold5 {m['hold5']['exactRate']:.3f} < 0.95")
            if m["normal"]["over"] > 0:
                fails.append(f"(d) {combo}: {m['normal']['over']} over-counted sets")
        lead = sum(res[c][combo][pol]["leadFalse"] for c in ("lead10", "lead15") for combo in GATING)
        after = sum(res["after10"][combo][pol]["afterFalse"] for combo in GATING)
        checks[pol] = dict(passes=not fails, failures=fails, leadFalseGating=lead, afterFalseGating=after,
                           firstPairMs=pc.POLICIES[pol]["first_ms"])
    passing = [p for p in CANDIDATES if checks[p]["passes"]]
    if passing:
        chosen = min(passing, key=lambda p: (checks[p]["leadFalseGating"], checks[p]["afterFalseGating"], checks[p]["firstPairMs"]))
        status = "adopted"
    else:
        chosen = min(CANDIDATES, key=lambda p: (len(checks[p]["failures"]), checks[p]["leadFalseGating"], checks[p]["afterFalseGating"]))
        status = "provisional (no policy passed)"
    return dict(rule="docs/REP_ENGINE_DESIGN.md §12 (pre-registered)", checks=checks, passing=passing, chosen=chosen, status=status)


# ---------------------------------------------------------------- 출력

def fmt_row(label, m, extra_cols=True):
    m = finish(m)
    s = f"| {label} | {m['sets']} | {m['recall']:.3f} | {m['exactRate']:.2f} | {m['oboRate']:.2f} | {m['over']} | {m['zero']} |"
    if extra_cols:
        s += f" {m['leadFalse']} ({m['leadPerWindow']:.2f}) | {m['afterFalse']} ({m['afterPerWindow']:.2f}) | {m['pendingAtEnd']} |"
    return s


POLICY_NOTE = {
    "none": "기준 — 확정 없음, 모든 발화를 센다",
    "4s/4s": "후보 — 첫 쌍 4 s, 이후 직전 발표로부터 4 s 안 + 진폭 비 (옛 §4.3)",
    "first8+amp": "후보 — 첫 쌍 8 s, 이후 진폭 비만",
    "first10+amp": "후보 — 첫 쌍 10 s, 이후 진폭 비만",
    "tempo": "후보 — 첫 쌍 max(4 s, 2.5 × 첫 사이클 길이), 이후 진폭 비만",
    "first8/later8": "**사후 탐색(선택 대상 아님)** — 첫 쌍 8 s, 이후에도 직전 발표로부터 8 s 안",
    "first8|tempo": "**사후 탐색(선택 대상 아님)** — 첫 쌍 max(8 s, 2.5 × 첫 사이클 길이), 이후 진폭 비만",
}
KEY_CONDS = ["normal", "stretch1.5", "stretch2", "stretch2.5", "hold5", "trunc1", "trunc2", "lead10", "lead15", "after10", "mirror"]


def lab(text):
    """마크다운 표 칸 안의 '|' 는 칸을 나누므로 조합 이름 'squat|knee_mean' 을 'squat/knee_mean' 으로 쓴다."""
    return text.replace("|", "/")


def key_table(res, base, combos, conds, policies):
    """조건 × 조합 행, 정책 열 = 정확 일치(과다 세트). 끝 열 = 항상 10."""
    L = ["| 조건 | 조합 | " + " | ".join(policies) + " | 항상 10 |", "|---|---|" + "---:|" * (len(policies) + 1)]
    for cond in conds:
        for combo in combos:
            if combo not in res.get(cond, {}):
                continue
            cells = []
            for pol in policies:
                m = finish(res[cond][combo][pol])
                cells.append(f"{m['exactRate']:.2f}" + (f" (+{m['over']})" if m["over"] else ""))
            kind = combo.split("|")[0]
            b = finish(base[cond][kind])["exactRate"] if kind in base.get(cond, {}) else float("nan")
            L.append(f"| {cond} | {lab(combo)} | " + " | ".join(cells) + f" | {b:.2f} |")
    return L


def write_md(path: Path, res, base, syn, syn_occ, sel, skipped, prereg_sha):
    combos_all = [c[0] for c in COMBOS]
    L = ["# 시작 확정 정책 스트레스 배터리 (설계 §12 사전 등록 규칙 적용)", "",
         "`research/external_rep_replay/stress_battery.py` 가 생성. MM-Fit 자체 3D 포즈(휴대폰·MediaPipe 아님), 앱 간격 300 ms,",
         "복귀 히스테리시스 코어(게이트 35°, f = 0.25, 불응기 0.8 s, 평활 없음). **MM-Fit 은 개발 데이터다** — 21개 워크아웃 전부를",
         "이전 실험에서 이미 봤다. 정책 사이의 비교이지 일반화 성능이 아니다.",
         f"사전 등록 규칙: `docs/REP_ENGINE_DESIGN.md` §12 (배터리 실행 시 본문 sha256 앞 12자리 `{prereg_sha}`).",
         "해시는 이 결과와 §12 본문을 묶을 뿐, §12 가 먼저 쓰였다는 증거는 아니다 — 순서는 §12 만 담은 커밋으로 남긴다(설계 §13).", "",
         "정책:", ""]
    L += [f"- `{p}` — {POLICY_NOTE.get(p, '')}" for p in POLICY_NAMES]
    L += ["", "조합 `curl|elbow_minside_both` 는 진단용이다 — 한쪽 팔이 안 보이는 샘플을 버리는 minside(앱 PostureCore 는 보이는 쪽 값을 쓴다).",
          "'항상 10' 은 모든 세트를 10회로 적는 기준선이다(정상 조건 정확 일치 0.95 / 0.95 / 0.92).", ""]
    L += ["## 1. 선택 결과 (§12 규칙의 기계적 적용)", "", f"- 선택: **{sel['chosen']}** — {sel['status']}",
          f"- 통과: {', '.join(sel['passing']) or '없음'}", "",
          "| 정책 | 통과 | 세트 앞 헛카운트 (판정 4조합, lead10+lead15) | 세트 뒤 헛카운트 (after10) | 위반 |", "|---|---|---:|---:|---|"]
    for p, c in sel["checks"].items():
        L.append(f"| {p} | {'예' if c['passes'] else '아니오'} | {c['leadFalseGating']} | {c['afterFalseGating']} | {lab('; '.join(c['failures'])) or '—'} |")
    L += ["", "## 2. 핵심 표 — 세트 정확 일치 (괄호 = 과다 카운트 세트 수)", ""]
    L += key_table(res, base, combos_all, KEY_CONDS, POLICY_NAMES)
    L += ["", "hold5·trunc 는 30 fps 신호의 오프라인 바닥 검출이 정답 횟수와 맞은 세트만 쓴다. 제외: "
          + "; ".join(f"{c}: " + ", ".join(f"{k} {v}" for k, v in d.items()) for c, d in skipped.items()) + ".", ""]
    L += ["## 3. 세트 경계 헛카운트 — 발표된 발화 중 세트 ± 0.5 s 밖에 찍힌 것 (합계 / 창 수)", "",
          "| 조합 | 창 | " + " | ".join(POLICY_NAMES) + " |", "|---|---|" + "---:|" * len(POLICY_NAMES)]
    for combo in combos_all:
        for cond, key, wkey in (("lead10", "leadFalse", "leadWindows"), ("lead15", "leadFalse", "leadWindows"), ("after10", "afterFalse", "afterWindows")):
            if combo not in res.get(cond, {}):
                continue
            ms = [finish(res[cond][combo][p]) for p in POLICY_NAMES]
            L.append(f"| {lab(combo)} | {cond} ({ms[0][wkey]}창, 평균 {ms[0]['meanLeadSeconds' if cond != 'after10' else 'meanAfterSeconds']:.1f} s) | "
                     + " | ".join(f"{m[key]} ({m[key] / max(1, m[wkey]):.2f})" for m in ms) + " |")
    L += ["", "'항상 10' 기준선은 창을 보지 않으므로 헛카운트가 정의상 없다 — 같은 조건의 세트 정확 일치(0.95 / 0.95 / 0.92)는 §2 표에 있다.",
          "", "## 4. 먼 팔 가림 (컬, 먼 팔 = 왼·오른 각각 → 118세트)", ""]
    L += key_table(res, base, [c for c in combos_all if c.startswith("curl")], ["normal", "occl_rand9", "occl_flex30"], POLICY_NAMES)
    L += ["", "## 5. 합성 코사인 세트 (10회, 표본 위상 5개) — 정확 일치 (평균 카운트)", "",
          "| 반복당 | 조합 | " + " | ".join(POLICY_NAMES) + " |", "|---|---|" + "---:|" * len(POLICY_NAMES)]
    for T, combos in syn.items():
        for combo, pols in combos.items():
            cells = []
            for pol in POLICY_NAMES:
                m = finish(pols[pol]); cells.append(f"{m['exactRate']:.2f} ({m['counted'] / m['sets']:.1f})")
            L.append(f"| {T} | {lab(combo)} | " + " | ".join(cells) + " |")
    L += ["", "항상 10 기준선은 합성 세트에서 1.00 이다.", "",
          "### 5a. 합성 컬 + 먼 팔(R) 가림 (위상 5 × 난수 4 = 20세트) — 정확 일치 (평균 카운트)", "",
          "| 반복당 | 조합 | " + " | ".join(POLICY_NAMES) + " |", "|---|---|" + "---:|" * len(POLICY_NAMES)]
    for T, combos in syn_occ.items():
        for combo, pols in combos.items():
            cells = []
            for pol in POLICY_NAMES:
                m = finish(pols[pol]); cells.append(f"{m['exactRate']:.2f} ({m['counted'] / m['sets']:.1f})")
            L.append(f"| {T} | {lab(combo)} | " + " | ".join(cells) + " |")
    L += ["", "## 6. 조건별 전체 지표", "",
          "열: 세트 · 재현율 · 정확 일치 · ±1 · 과다 세트 · 0회 세트 · 세트 앞 헛카운트(창당) · 세트 뒤 헛카운트(창당) · 끝까지 보류된 후보(세지 않음).", ""]
    for cond in SET_CONDITIONS:
        if cond not in res:
            continue
        L += [f"### {cond}", "", "| 조합 · 정책 | 세트 | 재현율 | 정확 | ±1 | 과다 | 0회 | 앞 헛카운트 | 뒤 헛카운트 | 보류 |",
              "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
        for combo in combos_all:
            if combo not in res[cond]:
                continue
            for pol in POLICY_NAMES:
                L.append(fmt_row(f"{lab(combo)} · {pol}", res[cond][combo][pol]))
        for kind in ("squat", "lunge", "curl"):
            if kind in base[cond]:
                L.append(fmt_row(f"{kind} · 항상 10", base[cond][kind], extra_cols=False) + " — | — | — |")
        L.append("")
    path.write_text("\n".join(L), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", type=Path)
    ap.add_argument("--out", type=Path, required=True, help="결과 JSON 경로(.md 도 같은 이름으로 쓴다)")
    ap.add_argument("--per-set", type=Path, default=None, help="세트별 카운트 JSON(검토용, data/ 아래 권장)")
    ap.add_argument("--design", type=Path, default=Path(__file__).resolve().parents[2] / "docs" / "REP_ENGINE_DESIGN.md")
    a = ap.parse_args()
    doc = a.design.read_text(encoding="utf-8")
    start = doc.index("## 12. 세트 시작 확정 정책 — 선택 규칙 사전 등록")
    nxt = doc.find("\n## ", start + 5)
    prereg = doc[start: nxt if nxt > 0 else len(doc)].rstrip()   # 사전 등록 본문(뒤 공백 제외)의 해시 — 본문이 바뀌면 달라진다
    sha = hashlib.sha256(prereg.encode("utf-8")).hexdigest()[:12]
    res, base, skipped, per_set = run_battery(a.root)
    syn = synthetic()
    syn_occ = synthetic_occlusion()
    sel = select(res)
    out = {
        "source": "MM-Fit pose_3d (dataset's own 3D pose, not MediaPipe) — development data, all 21 workouts seen before",
        "cadenceMs": CADENCE_MS, "gateDeg": H_ANGLE, "returnFraction": 0.25, "refractoryMs": 800, "smoothing": "none",
        "mergeMs": MERGE_MS, "seed": SEED, "policies": pc.POLICIES, "gating": GATING, "preregistrationSha12": sha,
        "selection": sel,
        "skippedNoTroughs": {c: dict(v) for c, v in skipped.items()},
        "results": {c: {k: {p: slim(m) for p, m in v.items()} for k, v in combos.items()} for c, combos in res.items()},
        "always10": {c: {k: slim(m) for k, m in v.items()} for c, v in base.items()},
        "synthetic": {T: {k: {p: slim(m, SYN_SAVED) for p, m in v.items()} for k, v in combos.items()} for T, combos in syn.items()},
        "syntheticOcclusion": {T: {k: {p: slim(m, SYN_SAVED) for p, m in v.items()} for k, v in combos.items()} for T, combos in syn_occ.items()},
    }
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(dump(out) + "\n", encoding="utf-8")
    write_md(a.out.with_suffix(".md"), res, base, syn, syn_occ, sel, skipped, sha)
    if a.per_set:
        a.per_set.parent.mkdir(parents=True, exist_ok=True)
        a.per_set.write_text(json.dumps(per_set, ensure_ascii=False), encoding="utf-8")
    print(f"chosen: {sel['chosen']} ({sel['status']}); passing: {sel['passing']}")
    for p, c in sel["checks"].items():
        print(f"  {p}: pass={c['passes']} lead={c['leadFalseGating']} after={c['afterFalseGating']} {c['failures'][:4]}")


if __name__ == "__main__":
    main()
