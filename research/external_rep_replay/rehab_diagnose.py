# -*- coding: utf-8 -*-
"""REHAB24-6 사전 등록 실패의 원인 진단 — 집계만 낸다 (설계 §17 은 조정 금지, 원인 기록은 허용).

§17 판정은 두 종목 모두 "옮겨 가지 않음" 이었다(results/rehab_prereg). 특히 세로 카메라(cam18) 스쿼트 재현율이 0.50 으로
가로(cam17) 0.90 의 절반이다. 휴대폰은 대개 세로로 세우므로 이 차이가 가장 중요한 질문이다. 이 스크립트는 **아무 상수도 바꾸지
않고** 놓친 반복을 원인별로 나눈다:

    신호 없음      반복 구간 샘플의 절반 넘게 knee_mean 이 없다(사람·관절 미검출)
    얕음          구간 안 knee_mean 스윙(최대 − 최소)이 게이트 h = 35° 미만 — 반복 모양 게이트가 버린다
    확정 대기·버림  구간(± 0.5 s) 안에서 발화했지만 시작 확정이 보류하다 버렸거나 세트 끝까지 보류
    시각 어긋남    구간 밖 ± 2 s 안에 짝 없는 발화가 있다(발화는 했으나 정답 구간과 시각이 안 맞음)
    기타          위 어디에도 안 맞음(두 반복이 한 사이클로 합쳐짐 등)

함께 재는 것 (종목 × 카메라, 녹화별 행은 내지 않는다 — CC BY-NC 원자료를 공개 저장소에 싣지 않는다):
    반복 스윙·길이(템포)·값 결측의 분위수, 잡은/놓친 반복의 스윙 비교, 같은 녹화·같은 반복의 세로 − 가로 스윙 차이,
    화면 잘림(엉덩이·무릎·발목 중 하나가 화면 밖이거나 가시성 < 0.5 인 샘플 비율), 화면 속 몸 높이(코 ~ 발목 세로 폭 중앙값).

입력: extract_mediapipe.py rehab 산출(index.json + .cap), run_replay.py 결과(replay.jsonl). numpy 필요.
    python rehab_diagnose.py <captures> <results> --out results/rehab_prereg/diagnose     # rehab_diagnose.md + .json
    python rehab_diagnose.py --self-test
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_replay import fire_times, set_key  # noqa: E402

EXERCISES = ("바벨 스쿼트", "스텝 포워드 다이나믹 런지")
CONFIGS = ("hysteresis", "live")
TOL_MS = 500
NEAR_MS = 2000
GATE = 35.0
# MediaPipe 33 랜드마크: 코 0, 엉덩이 23·24, 무릎 25·26, 발목 27·28
NOSE, LOWER = 0, (23, 24, 25, 26, 27, 28)
MODES = ("신호 없음", "얕음", "확정 대기·버림", "시각 어긋남", "기타")


def read_capture(path: Path) -> dict:
    """capture_format 의 F 줄 → 시각, 정규화 x·y, 가시성(min(vis, pres)), 월드 좌표. 앱 피처는 mmfit_pair_compare 로 계산한다."""
    t, xy, vis = [], [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("F\t"):
            continue
        parts = line.split("\t")
        t.append(int(parts[1]))
        p = np.full((33, 2), np.nan)
        v = np.full(33, np.nan)
        for cell in parts[3:]:
            i, _, rest = cell.partition(":")
            x = [float(q) for q in rest.split(",")]
            p[int(i)] = x[:2]
            a = 1.0 if x[2] != x[2] else x[2]
            b = 1.0 if x[3] != x[3] else x[3]
            v[int(i)] = min(a, b)
        xy.append(p)
        vis.append(v)
    return {"t": np.array(t, dtype=np.int64), "xy": np.array(xy).reshape(-1, 33, 2), "vis": np.array(vis).reshape(-1, 33)}


def knee_mean(path: Path) -> tuple[np.ndarray, np.ndarray]:
    import mmfit_pair_compare as mpc
    cap = mpc.read_landmark_capture(path)
    _, feat = mpc.app_features(cap)
    return cap["t"], feat["knee_mean"]


def match_flags(times: list[int], reps: list[list], tol: int) -> tuple[list[bool], list[int]]:
    """score_rehab_prereg.match 와 같은 시간순 1:1 탐욕 배정. (반복별 일치 여부, 짝 없는 발화 시각)."""
    truth = [(a - tol, b + tol) for a, b, _ in reps]
    used = [False] * len(truth)
    unmatched = []
    for t in sorted(times):
        k = next((i for i, (a, b) in enumerate(truth) if not used[i] and a <= t <= b), None)
        if k is None:
            unmatched.append(t)
        else:
            used[k] = True
    return used, unmatched


def rep_stats(t: np.ndarray, v: np.ndarray, a: int, b: int) -> dict:
    m = (t >= a) & (t <= b)
    n = int(m.sum())
    vals = v[m]
    ok = np.isfinite(vals)
    cover = float(ok.mean()) if n else 0.0
    swing = float(np.nanmax(vals) - np.nanmin(vals)) if ok.sum() >= 2 else float("nan")
    return {"samples": n, "coverage": cover, "swing": swing, "durationMs": b - a}


def classify(st: dict, a: int, b: int, held: list[int], unmatched: list[int]) -> str:
    if st["coverage"] < 0.5:
        return MODES[0]
    if not (st["swing"] >= GATE):
        return MODES[1]
    if any(a - TOL_MS <= h <= b + TOL_MS for h in held):
        return MODES[2]
    if any(a - NEAR_MS <= u <= b + NEAR_MS for u in unmatched):
        return MODES[3]
    return MODES[4]


def framing(cap: dict) -> dict:
    lower = cap["xy"][:, list(LOWER), :]
    vis = cap["vis"][:, list(LOWER)]
    present = np.isfinite(cap["xy"][:, NOSE, 0])
    out_of_frame = ((lower < 0) | (lower > 1)).any(axis=2) | ~(vis >= 0.5)
    cut = out_of_frame.any(axis=1)[present]
    height = (np.nanmax(cap["xy"][:, [27, 28], 1], axis=1) - cap["xy"][:, NOSE, 1])[present]
    return {"cutFraction": float(cut.mean()) if cut.size else None,
            "bodyHeight": float(np.nanmedian(height)) if np.isfinite(height).any() else None}


def q(a, ps=(0.1, 0.5, 0.9)):
    a = np.asarray([x for x in a if x == x], dtype=float)
    return [round(float(np.quantile(a, p)), 3) for p in ps] if a.size else None


def diagnose(captures: Path, results: Path) -> dict:
    index = json.loads((captures / "index.json").read_text(encoding="utf-8"))
    res = {}
    for line in (results / "replay.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            res[r["id"]] = r
    acc = defaultdict(lambda: {"reps": 0, "missed": 0, "modes": defaultdict(int), "swingHit": [], "swingMiss": [],
                               "coverage": [], "durationMs": [], "swingAll": [], "belowGate": 0, "cut": [], "height": [],
                               "missFirst": 0, "missLast": 0})
    per_rep_swing = defaultdict(dict)       # (video, rep#) → {camera: swing} — 세로 − 가로 비교용(집계만 낸다)
    per_rep_hit = defaultdict(dict)
    for s in index["sets"]:
        if s.get("exercise") not in EXERCISES or "reps" not in s:
            continue
        cap_path = Path(s["capture"])
        if not cap_path.is_absolute():
            cap_path = captures / cap_path
        t, v = knee_mean(cap_path)
        fr = framing(read_capture(cap_path))
        reps = sorted(s["reps"], key=lambda r: r[0])
        cam = str(s.get("camera", ""))
        for c in CONFIGS:
            r = res.get(f"{set_key(s)}|{c}")
            if r is None or "error" in r:
                continue
            used, unmatched = match_flags(fire_times(r), reps, TOL_MS)
            held = list(r.get("droppedMs") or []) + ([r["pendingMs"]] if r.get("pendingMs") is not None else [])
            g = acc[(c, s["exercise"], cam)]
            if fr["cutFraction"] is not None:
                g["cut"].append(fr["cutFraction"])
            if fr["bodyHeight"] is not None:
                g["height"].append(fr["bodyHeight"])
            for i, (a, b, correct) in enumerate(reps):
                st = rep_stats(t, v, a, b)
                g["reps"] += 1
                g["coverage"].append(st["coverage"])
                g["durationMs"].append(st["durationMs"])
                g["swingAll"].append(st["swing"])
                g["belowGate"] += int(not (st["swing"] >= GATE))
                if c == CONFIGS[0]:
                    per_rep_swing[(s.get("video"), i)][cam] = st["swing"]
                    per_rep_hit[(s.get("video"), i)][cam] = used[i]
                if used[i]:
                    g["swingHit"].append(st["swing"])
                else:
                    g["missed"] += 1
                    g["swingMiss"].append(st["swing"])
                    g["modes"][classify(st, a, b, held, unmatched)] += 1
                    g["missFirst"] += int(i == 0)
                    g["missLast"] += int(i == len(reps) - 1)
    table = {}
    for (c, ex, cam), g in sorted(acc.items()):
        table[f"{c}|{ex}|cam{cam}"] = {
            "config": c, "exercise": ex, "camera": cam, "reps": g["reps"], "missed": g["missed"],
            "missModes": {m: g["modes"].get(m, 0) for m in MODES}, "missFirstRep": g["missFirst"], "missLastRep": g["missLast"],
            "swingAllP10P50P90": q(g["swingAll"]), "swingHitP10P50P90": q(g["swingHit"]), "swingMissP10P50P90": q(g["swingMiss"]),
            "repsBelowGate": g["belowGate"], "coverageP10P50P90": q(g["coverage"]),
            "durationSP10P50P90": q([d / 1000.0 for d in g["durationMs"]]),
            "repsLongerThan8s": int(sum(d > 8000 for d in g["durationMs"])),
            "cutFractionP50": q(g["cut"], (0.5,)), "bodyHeightP50": q(g["height"], (0.5,)),
        }
    # 같은 녹화의 두 카메라만 짝짓는다(녹화 → 종목)
    paired = {}
    ex_of_video = {s.get("video"): s.get("exercise") for s in index["sets"]}
    for ex in EXERCISES:
        diffs, both, only17, only18, n = [], 0, 0, 0, 0
        for (video, i), sw in per_rep_swing.items():
            if ex_of_video.get(video) != ex or "17" not in sw or "18" not in sw:
                continue
            n += 1
            if sw["17"] == sw["17"] and sw["18"] == sw["18"]:
                diffs.append(sw["18"] - sw["17"])
            h = per_rep_hit[(video, i)]
            both += int(h.get("17", False) and h.get("18", False))
            only17 += int(h.get("17", False) and not h.get("18", False))
            only18 += int(h.get("18", False) and not h.get("17", False))
        paired[ex] = {"reps": n, "swing18minus17P10P50P90": q(diffs), "hitBoth": both, "hitOnly17": only17, "hitOnly18": only18}
    return {"note": "집계만 — 녹화별 행 없음. 조정 없음(설계 §17).", "gateDeg": GATE, "toleranceMs": TOL_MS, "table": table, "pairedCameras": paired}


def _f(x) -> str:
    if x is None:
        return "—"
    if isinstance(x, list):
        return " / ".join(f"{y:g}" for y in x)
    return f"{x:g}" if isinstance(x, (int, float)) else str(x)


def markdown(d: dict) -> str:
    L = ["# REHAB24-6 — 놓친 반복의 원인 (설계 §17 실패 진단, 집계)", "",
         f"게이트 h = {d['gateDeg']:g}°, 일치 허용 ±{d['toleranceMs']} ms(사전 등록 채점과 같다). 녹화별 행은 싣지 않는다. 상수는 바꾸지 않았다.", "",
         "## 놓친 반복의 원인", "",
         "| 구성 | 종목 | 카메라 | 반복 | 놓침 | " + " | ".join(MODES) + " | 첫 반복 놓침 | 마지막 반복 놓침 |",
         "|---|---|---|---:|---:|" + "---:|" * len(MODES) + "---:|---:|"]
    for v in d["table"].values():
        L.append(f"| {v['config']} | {v['exercise']} | cam{v['camera']} | {v['reps']} | {v['missed']} | "
                 + " | ".join(str(v["missModes"][m]) for m in MODES) + f" | {v['missFirstRep']} | {v['missLastRep']} |")
    L += ["", "## 반복의 모양 (knee_mean, 분위수 p10 / p50 / p90)", "",
          "| 구성 | 종목 | 카메라 | 스윙 전체 ° | 잡은 반복 ° | 놓친 반복 ° | 35° 미만 반복 | 값 있는 샘플 비율 | 반복 길이 s | 8 s 넘는 반복 | 화면 잘림 샘플 비율 p50 | 몸 높이(화면 비율) p50 |",
          "|---|---|---|---|---|---|---:|---|---|---:|---|---|"]
    for v in d["table"].values():
        L.append(f"| {v['config']} | {v['exercise']} | cam{v['camera']} | {_f(v['swingAllP10P50P90'])} | {_f(v['swingHitP10P50P90'])} | "
                 f"{_f(v['swingMissP10P50P90'])} | {v['repsBelowGate']} | {_f(v['coverageP10P50P90'])} | {_f(v['durationSP10P50P90'])} | "
                 f"{v['repsLongerThan8s']} | {_f(v['cutFractionP50'])} | {_f(v['bodyHeightP50'])} |")
    L += ["", "## 같은 반복, 두 카메라 (새 코어)", "",
          "| 종목 | 짝지은 반복 | 스윙 세로 − 가로 ° (p10 / p50 / p90) | 둘 다 잡음 | 가로만 | 세로만 |", "|---|---:|---|---:|---:|---:|"]
    for ex, p in d["pairedCameras"].items():
        if p:
            L.append(f"| {ex} | {p['reps']} | {_f(p['swing18minus17P10P50P90'])} | {p['hitBoth']} | {p['hitOnly17']} | {p['hitOnly18']} |")
    return "\n".join(L) + "\n"


def self_test() -> int:
    checks = []
    t = np.arange(0, 20000, 300)
    v = np.full(t.shape, 170.0)
    for c in (3000, 8000, 13000):                         # 깊은 반복 셋
        v[(t > c - 1000) & (t < c + 1000)] = 90.0
    v[(t > 16000) & (t < 18000)] = 150.0                   # 얕은 반복 하나(스윙 20°)
    reps = [[2000, 4000, 1], [7000, 9000, 1], [12000, 14000, 0], [16000, 18000, 1]]
    used, unmatched = match_flags([3900, 13900, 16200 + 2600], reps, TOL_MS)
    checks.append(("일치: 1·3번째만", used == [True, False, True, False]))
    checks.append(("짝 없는 발화 1개", unmatched == [18800]))
    st2 = rep_stats(t, v, 7000, 9000)
    checks.append(("둘째 반복 스윙 80°", abs(st2["swing"] - 80.0) < 1e-6))
    checks.append(("둘째: 보류 발화면 '확정 대기·버림'", classify(st2, 7000, 9000, [8500], unmatched) == MODES[2]))
    checks.append(("둘째: 아무것도 없으면 '기타'", classify(st2, 7000, 9000, [], []) == MODES[4]))
    st4 = rep_stats(t, v, 16000, 18000)
    checks.append(("넷째: 얕음", classify(st4, 16000, 18000, [], unmatched) == MODES[1]))
    v_gap = v.copy(); v_gap[(t >= 7000) & (t <= 9000)] = np.nan
    checks.append(("값이 없으면 '신호 없음'", classify(rep_stats(t, v_gap, 7000, 9000), 7000, 9000, [], []) == MODES[0]))
    checks.append(("근처 짝 없는 발화면 '시각 어긋남'", classify(st2, 7000, 9000, [], [10500]) == MODES[3]))
    cap = {"t": np.array([0, 300]), "xy": np.full((2, 33, 2), 0.5), "vis": np.full((2, 33), 0.9)}
    cap["xy"][1, 27, 1] = 1.2
    fr = framing(cap)
    checks.append(("화면 잘림 비율 0.5", abs(fr["cutFraction"] - 0.5) < 1e-9))
    for name, ok in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    n = sum(ok for _, ok in checks)
    print(f"\n{n}/{len(checks)} 통과")
    return 0 if n == len(checks) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("captures", type=Path, nargs="?")
    ap.add_argument("results", type=Path, nargs="?")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not (a.captures and a.results and a.out):
        ap.error("captures, results, --out 이 필요하다")
    d = diagnose(a.captures, a.results)
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "rehab_diagnose.json").write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")
    (a.out / "rehab_diagnose.md").write_text(markdown(d), encoding="utf-8")
    print(markdown(d))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
