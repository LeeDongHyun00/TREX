# -*- coding: utf-8 -*-
"""폰 렙 검증 Gate A — 회수한 세트 로그에서 보고서까지 한 번에 (설계 docs/REP_ENGINE_DESIGN.md §7, spec §61, GATE_A_RUNBOOK.md).

순서 (각 단계는 기존 도구의 함수를 그대로 부른다 — 여기서 다시 구현하지 않는다)
    1) setlog_captures.build      세트 로그 → 피처 캡처(.fcap) + 검증 모드 로그의 랜드마크 캡처(.cap) + index      → <out>/captures
    2) score_phone_reps.score     계획표 짝짓기 · 정답 등급 · live / hysteresis / 신호 후보 재생 채점, Gate A(점추정)   → <out>/score
    3) phone_noise.run            추론 간격·발열·휴식 σ·튐·끊김                                                   → <out>/noise
    4) 프레이밍                    종목 × 화면 방향(세로/가로 = 로그 image w·h): 필수 관절이 화면 밖(xy 가 0..1 밖)이거나
                                  가시성 < 0.5 인 검출 프레임의 비율, 가장 긴 연속 잘림, 관절군별 비율, 화면 속 몸 높이      → <out>/framing.json
    5) 피처 무결성                  검증 모드 세트만: .cap 에서 `Replay --dump-features`(앱과 같은 후처리)로 피처를 다시 계산해
                                  로그에 적힌 카운터 신호 피처와 견준다 — 최대 절대 차, 허용 오차 안 비율, 있음/없음 불일치   → <out>/integrity.json
    6) report.md                  Gate A 나가는 조건(설계 §7) 점검표 + 위 표들
왜 프레이밍인가: REHAB24-6(§19)에서 세로 카메라는 엉덩이·무릎·발목 중 하나가 화면 밖이거나 가시성 < 0.5 인 샘플이 녹화 중앙값 45%
(가로 0%)였고, 세로 스쿼트 놓침 94건 중 88건이 그 때문이었다. 휴대폰은 대개 세로로 세운다 — 폰에서 이 빈도를 먼저 잰다(§19.3 A).
왜 무결성인가: 좌표를 오프라인 재분석(후보 카운터·프레이밍 게이트를 새 세션 없이 다시 돌리기)에 쓰려면, 로그 좌표가 앱이 그 프레임에서
실제로 쓴 좌표와 같은 것이어야 한다. 로그 좌표는 소수 4자리(0.1 mm)·가시성 3자리라 비트 단위 일치가 아니라 허용 오차로 본다.

판정하지 않은 것을 판정한 것처럼 적지 않는다(원칙 #1): 데이터가 없는 칸은 '데이터 없음', 원인은 자동 후보만 붙이고 사람이 확인해야 ✓.
Gate A 는 개발 데이터라 성능 주장이 아니다 — 서비스 판정은 Gate B(고정 빌드·새 사람 ≥ 5명)에서만 한다.

사용법
    python gate_a.py run <회수 폴더 ...> --plan <plan.csv> --out <결과 폴더> [--labels <폴더>] [--skip set_id,...] [--headline hysteresis]
    python gate_a.py dry-run --out <임시 폴더>      # 폰·데이터셋 없이: make_phone_fixture.py 픽스처 → 같은 파이프라인 → 기대값 검사
결과 폴더는 개인 측정 기록이다 — 저장소 밖이나 git 이 무시하는 data/ 아래에 둔다.
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import statistics
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import capture_format
import phone_noise
import run_replay
import score_phone_reps
import setlog_captures
from rehab_diagnose import LOWER, NOSE

# 종목별 필수 관절(MediaPipe 33 번호) — 이것이 화면 안·가시성 ≥ 0.5 여야 카운터 신호가 선다(설계 §19.3 A 프레이밍 게이트 후보).
# 스쿼트·런지류는 REHAB 진단(rehab_diagnose.LOWER)과 같은 엉덩이·무릎·발목. 컬은 팔꿈치 각(elbow_*)을 만드는 어깨·팔꿈치·손목.
LOWER_GROUPS = {"엉덩이": LOWER[0:2], "무릎": LOWER[2:4], "발목": LOWER[4:6]}
ARM_GROUPS = {"어깨": (11, 12), "팔꿈치": (13, 14), "손목": (15, 16)}
REQUIRED_JOINTS = {"바벨 스쿼트": LOWER_GROUPS, "덤벨 컬": ARM_GROUPS,
                   **{ex: LOWER_GROUPS for ex in setlog_captures.SIDE_PAIR_AIHUB}}
MIN_VIS = 0.5                 # PostureAnalyzer.MIN_VISIBILITY
LONG_CUT_S = 2.0              # §19.3 A: 세트 중 2 s 넘게 벗어나면 안내 — 그 길이를 넘는 잘림이 있는 세트를 센다
CAUSE_CUT_SHARE = 0.05        # 틀린 세트의 원인 후보로 '화면 밖' 을 붙이는 잘림 비율
# 무결성 허용 오차 — 각도 신호(게이트 ≥ 1, 도)는 0.05°, 정규화 거리 신호는 0.001. 로그 좌표 4자리(0.1 mm) 반올림이 40 cm 분절에서
# 만드는 각도 오차는 약 0.015° 라 그 세 배 남짓이다. 넘으면 로그 좌표가 앱이 쓴 좌표가 아니거나(형식·부호 어긋남) 빌드가 다르다.
TOL_ANGLE, TOL_DISTANCE = 0.05, 0.001
# 둘 다 값이 있는 프레임은 전부 허용 오차 안이어야 한다 — 한 프레임이라도 넘으면 좌표·빌드가 어긋난 것이다. 가시성 3자리 반올림이
# 0.5 경계의 관절을 뒤집는 경우는 값의 차가 아니라 '있음/없음 불일치' 로 나타나므로 그것만 비교 프레임의 1% 까지 허용한다.
INTEGRITY_OK_SHARE = 1.0
PRESENCE_SLACK = 0.01
TARGETS = score_phone_reps.TARGETS
# 설계 §1 지표 — Gate A 나가는 조건은 '세 종목 모두 점추정이 기준을 넘는다'(§7)
CRITERIA = (("exactRate", "정확 일치", ">=", 0.90), ("oboRate", "±1", ">=", 0.98), ("overRate", "과다 세트", "<=", 0.02),
            ("leadPerSet", "앞 헛카운트 /세트", "<=", 0.05))


def orientation(e: dict) -> str:
    w, h = e.get("imageW"), e.get("imageH")
    if not w or not h:
        return "모름"
    return "세로" if h > w else ("가로" if w > h else "정사각")


# ---------------------------------------------------------------- 4) 프레이밍

def framing_set(cap: Path, exercise: str) -> dict:
    groups = REQUIRED_JOINTS.get(exercise)
    meta, frames = capture_format.read_capture(cap)
    det = [f for f in frames if f["poses"] >= 1 and len(f["landmarks"]) == capture_format.LANDMARKS]
    out = {"frames": len(frames), "detected": len(det), "undetected": len(frames) - len(det)}
    if groups is None:
        return {**out, "required": None}
    ts = [f["t"] for f in frames]
    dt = statistics.median([b - a for a, b in zip(ts, ts[1:])]) if len(ts) > 1 else 300
    cut_flags, by_group, heights = [], {g: 0 for g in groups}, []

    def bad(lm) -> bool:
        x, y, vis = lm[0], lm[1], lm[2]
        return not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0) or (not math.isnan(vis) and vis < MIN_VIS)

    for f in det:
        lms = f["landmarks"]
        hit = False
        for g, idx in groups.items():
            if any(bad(lms[i]) for i in idx):
                by_group[g] += 1
                hit = True
        cut_flags.append((f["t"], hit))
        if exercise in REQUIRED_JOINTS and groups is LOWER_GROUPS:
            heights.append(max(lms[27][1], lms[28][1]) - lms[NOSE][1])
    # 가장 긴 연속 잘림(검출 프레임 기준, 한 프레임 = 세트 중앙 간격)
    longest = run_len = 0
    run_start = None
    for t, hit in cut_flags:
        if hit:
            run_start = t if run_start is None else run_start
            run_len = t - run_start + dt
            longest = max(longest, run_len)
        else:
            run_start = None
    n = len(det)
    cut = sum(h for _, h in cut_flags)
    return {**out, "required": {g: list(i) for g, i in groups.items()}, "cutFrames": cut, "cutShare": cut / n if n else None,
            "longestCutS": longest / 1000.0, "groupShare": {g: (k / n if n else None) for g, k in by_group.items()},
            "bodyHeight": statistics.median(heights) if heights else None, "imageW": meta.get("imageW"), "imageH": meta.get("imageH")}


def framing(index: dict, cap_dir: Path) -> dict:
    per_set, groups = {}, defaultdict(list)
    for e in index["sets"]:
        if not e.get("landmarkCapture"):
            continue
        r = framing_set(cap_dir / e["landmarkCapture"], e["exercise"])
        r.update({"exercise": e["exercise"], "orientation": orientation(e)})
        per_set[e["setId"]] = r
        if r.get("required") is not None:
            groups[(e["exercise"], r["orientation"])].append(r)
    table = []
    for (ex, ori), rs in sorted(groups.items()):
        det = sum(r["detected"] for r in rs)
        cut = sum(r["cutFrames"] for r in rs)
        shares = [r["cutShare"] for r in rs if r["cutShare"] is not None]
        gnames = list(rs[0]["groupShare"])
        table.append({"exercise": ex, "orientation": ori, "sets": len(rs), "detectedFrames": det,
                      "undetectedFrames": sum(r["undetected"] for r in rs),
                      "cutShare": cut / det if det else None, "setShareMedian": statistics.median(shares) if shares else None,
                      "setShareMax": max(shares) if shares else None,
                      "setsLongCut": sum(r["longestCutS"] > LONG_CUT_S for r in rs),
                      "groupShare": {g: (sum(r["groupShare"][g] * r["detected"] for r in rs if r["groupShare"][g] is not None) / det
                                         if det else None) for g in gnames},
                      "bodyHeightMedian": statistics.median([r["bodyHeight"] for r in rs if r["bodyHeight"] is not None])
                      if any(r["bodyHeight"] is not None for r in rs) else None})
    return {"perSet": per_set, "table": table, "noLandmarks": [e["setId"] for e in index["sets"] if not e.get("landmarkCapture")]}


# ---------------------------------------------------------------- 5) 피처 무결성

def tolerance(signal: str) -> float:
    return TOL_ANGLE if phone_noise.gate_of(signal) >= 1.0 else TOL_DISTANCE


def dump_features(cap: Path, out: Path) -> list[dict]:
    run_replay.require_fresh_replay()
    subprocess.run([str(run_replay.REPLAY_BIN), "--dump-features", str(cap), str(out)], check=True, capture_output=True)
    return [json.loads(x) for x in out.read_text(encoding="utf-8").splitlines() if x.strip()]


def integrity(index: dict, cap_dir: Path, work: Path) -> dict:
    work.mkdir(parents=True, exist_ok=True)
    per_set = {}
    for e in index["sets"]:
        if not (e.get("validation") and e.get("landmarkCapture")):
            continue
        signal = e.get("loggedSignal")
        dumped = dump_features(cap_dir / e["landmarkCapture"], work / f"{e['setId']}.features.jsonl")
        _, logged = setlog_captures.read_feature_capture(cap_dir / e["capture"])
        by_t = {r["t"]: r["features"] for r in dumped}
        diffs, mismatch, compared_frames, worst = [], 0, 0, {}
        for t, lf in logged:
            rf = by_t.get(t)
            if rf is None:
                mismatch += 1
                continue
            compared_frames += 1
            for k, v in lf.items():
                if rf.get(k) is not None:
                    worst[k] = max(worst.get(k, 0.0), abs(rf[k] - v))
            if signal:
                a, b = lf.get(signal), rf.get(signal)
                if (a is None) != (b is None):
                    mismatch += 1
                elif a is not None:
                    diffs.append(abs(a - b))
        tol = tolerance(signal) if signal else None
        worst_feat = max(worst, key=worst.get) if worst else None
        per_set[e["setId"]] = {
            "exercise": e["exercise"], "signal": signal, "tolerance": tol, "framesLogged": len(logged), "framesCompared": compared_frames,
            "signalCompared": len(diffs), "signalMaxAbsDiff": max(diffs) if diffs else None,
            "signalWithinTol": (sum(d <= tol for d in diffs) / len(diffs)) if diffs and tol is not None else None,
            "presenceMismatch": mismatch, "worstFeature": worst_feat, "worstFeatureDiff": worst.get(worst_feat) if worst_feat else None}
    ok = [s for s in per_set.values() if s["signalCompared"]]
    total = sum(s["signalCompared"] for s in ok)
    summary = {"sets": len(per_set), "signalCompared": total,
               "maxAbsDiff": max((s["signalMaxAbsDiff"] for s in ok), default=None),
               "withinTol": (sum(s["signalWithinTol"] * s["signalCompared"] for s in ok) / total) if total else None,
               "presenceMismatch": sum(s["presenceMismatch"] for s in per_set.values())}
    summary["ok"] = bool(total) and summary["withinTol"] >= INTEGRITY_OK_SHARE and summary["presenceMismatch"] <= PRESENCE_SLACK * max(1, total)
    return {"summary": summary, "perSet": per_set}


# ---------------------------------------------------------------- 6) 보고서

def _p(x, fmt=".2f") -> str:
    return "—" if x is None or (isinstance(x, float) and x != x) else format(x, fmt)


def _criteria(m: dict) -> tuple[bool | None, list[str]]:
    if not m or not m.get("n"):
        return None, ["데이터 없음"]
    ok, parts = True, []
    for key, name, op, bound in CRITERIA:
        v = m.get(key)
        if v is None:
            ok = False
            parts.append(f"{name} 판정 불가(경계 모름)")
            continue
        good = v >= bound if op == ">=" else v <= bound
        ok &= good
        parts.append(f"{'✓' if good else '✗'} {name} {v:.2f} ({op} {bound:g})")
    return ok, parts


def cause_candidates(row: dict, pred: str, fr: dict | None, rep: dict | None) -> list[str]:
    """틀린 세트의 원인 **후보**(자동). 사람이 로그·영상을 보고 확인해야 원인이 '붙은' 것이다."""
    out = []
    if fr and fr.get("cutShare") and fr["cutShare"] >= CAUSE_CUT_SHARE:
        out.append(f"화면 밖 {fr['cutShare']:.0%} (가장 긴 {fr['longestCutS']:.1f} s — §19 원인 1)")
    if row["lead"].get(pred):
        out.append(f"세트 앞 헛카운트 {row['lead'][pred]}")
    if row["after"].get(pred):
        out.append(f"세트 뒤 헛카운트 {row['after'][pred]}")
    if rep and rep.get("gapsOverMaxGap"):
        out.append(f"1.5 s 넘는 끊김 {rep['gapsOverMaxGap']}")
    if row["boundary"]["method"] == "none":
        out.append("반복 경계 모름(오프라인 사이클 < 정답)")
    return out or ["자동 후보 없음 — 로그·영상 확인"]


def report(s: dict, fr: dict, integ: dict, index: dict, results: dict, headline: str) -> str:
    rows = s["rows"]
    by_id = {e["setId"]: e for e in index["sets"]}
    score_ids = {r["setId"] for r in rows}
    preds = [p for p in ("live", headline) if p]
    preds = list(dict.fromkeys(preds))
    L = ["# 폰 렙 검증 Gate A — 보고서", "",
         "Gate A 는 **개발 데이터**다 — 상수·정책을 바꿀 수 있으므로 성능으로 보고하지 않는다(설계 §7). 서비스 판정은 Gate B 에서만.",
         f"세트 로그 {len(index['sets'])}세트(채점 대상 종목 {len(rows)}) · 계획 행 {s['planRows']} · 짝 없는 계획 행 {len(s['unmatchedPlan'])} · "
         f"짝 없는 로그 {len(s['unmatchedLogs'])} · 정답 출처 " + ", ".join(f"{k}={v}" for k, v in s["truthSources"].items() if v), ""]

    # ---- 점검표
    L += ["## Gate A 나가는 조건 (설계 §7) — 점검표", ""]
    checks = []
    non_val = [e["setId"] for e in index["sets"] if not e.get("validation")]
    checks.append(("모든 세트가 검증 모드(숫자 숨김·자동 진행·음성 꺼짐)", not non_val and bool(index["sets"]),
                   "전부" if not non_val else f"검증 모드가 아닌 세트 {len(non_val)}: {', '.join(non_val[:5])}"))
    matched = not s["unmatchedPlan"] and not s["unmatchedLogs"]
    checks.append(("계획표와 로그가 모두 짝지어짐", matched,
                   "짝 없음 없음" if matched else f"계획 {s['unmatchedPlan']} / 로그 {s['unmatchedLogs']}"))
    no_tally = [r["setId"] for r in rows if r["truthSource"] not in ("tally", "plan-negative")]
    checks.append(("모든 세트에 독립 집계(사람의 손 집계) 정답", not no_tally and bool(rows),
                   "전부" if not no_tally else f"집계 없는 세트 {len(no_tally)} (자가 라벨은 앱 숫자를 본 뒤라 독립이 아니다)"))
    for ex in TARGETS:
        oris = {r["orientation"] for r in fr["table"] if r["exercise"] == ex}
        checks.append((f"{ex}: 세로·가로 둘 다 찍음", {"세로", "가로"} <= oris, ", ".join(sorted(oris)) or "데이터 없음"))
    tiers = s["tiers"].get("independent", {})
    dtiers = s["displayTiers"].get("independent", {})
    for p in preds:
        for ex in TARGETS:
            ok, parts = _criteria(tiers.get(ex, {}).get("all", {}).get(p, {}))
            checks.append((f"§1 점추정 — {ex} · `{p}`" + (" · 걸음" if setlog_captures.current_unit(ex, False) in setlog_captures.PAIR_UNITS else ""),
                           ok, " · ".join(parts)))
            if setlog_captures.current_unit(ex, False) in setlog_captures.PAIR_UNITS:
                ok2, parts2 = _criteria(dtiers.get(ex, {}).get("all", {}).get(p, {}))
                checks.append((f"§1 점추정 — {ex} · `{p}` · 쌍(화면)", ok2, " · ".join(parts2)))
    wrong = {p: [r for r in rows if r["truth"] is not None and r["pred"].get(p) is not None and r["pred"][p] != r["truth"]]
             for p in preds}
    for p in preds:
        n = len(wrong[p])
        checks.append((f"놓침·헛카운트마다 원인 — `{p}`", True if n == 0 else None,
                       "틀린 세트 없음" if n == 0 else f"틀린 세트 {n} — 아래 표의 자동 후보를 사람이 확인해야 ✓"))
    checks.append(("피처 무결성(로그 좌표 → 다시 계산한 신호 = 로그 신호)", integ["summary"]["ok"] if integ["summary"]["sets"] else None,
                   f"세트 {integ['summary']['sets']} · 비교 {integ['summary']['signalCompared']} 프레임 · 최대 차 "
                   f"{_p(integ['summary']['maxAbsDiff'], '.2g')} · 허용 오차 안 {_p(integ['summary']['withinTol'], '.3f')} · "
                   f"있음/없음 불일치 {integ['summary']['presenceMismatch']}"))
    L += ["| 조건 | 상태 | 근거 |", "|---|---|---|"]
    for name, ok, detail in checks:
        mark = "✓" if ok is True else ("✗" if ok is False else "확인 필요")
        L.append(f"| {name} | {mark} | {detail} |")
    L += ["", "점추정 기준은 설계 §1(정확 일치 ≥ 0.90, ±1 ≥ 0.98, 과다 세트 ≤ 2%, 세트 앞 헛카운트 ≤ 0.05/세트). 정답은 독립 집계뿐 — "
          "음성·veryslow·sideblock·altcurl 세트는 빠진다(score/summary.md 의 조건별 표에 있다).", ""]

    # ---- 틀린 세트
    L += ["## 틀린 세트와 원인 후보", ""]
    for p in preds:
        if not wrong[p]:
            continue
        L += [f"### `{p}`", "", "| 세트 | 종목 | 방향 | 조건 | 정답 | 예측 | 원인 후보(자동) |", "|---|---|---|---|---:|---:|---|"]
        for r in wrong[p]:
            key = run_replay.set_key(by_id[r["setId"]]) if r["setId"] in by_id else None
            rep = results.get(f"{key}|{p}") if key else None
            fs = fr["perSet"].get(r["setId"])
            L.append(f"| {r['setId']} | {r['exercise']} | {orientation(by_id.get(r['setId'], {}))} | {r['condition']} | {r['truth']} | "
                     f"{r['pred'][p]} | {'; '.join(cause_candidates(r, p, fs, rep))} |")
        L.append("")

    # ---- 프레이밍
    L += ["## 프레이밍 — 필수 관절이 화면 밖이거나 가시성 < 0.5 인 프레임 (§19.3 A)", "",
          "필수 관절: 스쿼트·런지류 = 엉덩이·무릎·발목, 컬 = 어깨·팔꿈치·손목. 비율의 분모 = 검출 프레임. "
          f"긴 잘림 = 연속 {LONG_CUT_S:g} s 초과. 몸 높이 = 코 ~ 낮은 발목 세로 폭(화면 높이 비, 하체 종목만).", "",
          "| 종목 | 방향 | 세트 | 검출 프레임 | 잘림 비율 (합) | 세트 중앙 / 최대 | 긴 잘림 세트 | 관절군별 | 몸 높이 중앙 |",
          "|---|---|---:|---:|---:|---|---:|---|---:|"]
    for t in fr["table"]:
        gs = " · ".join(f"{g} {_p(v, '.2f')}" for g, v in t["groupShare"].items())
        L.append(f"| {t['exercise']} | {t['orientation']} | {t['sets']} | {t['detectedFrames']} | {_p(t['cutShare'], '.3f')} | "
                 f"{_p(t['setShareMedian'], '.3f')} / {_p(t['setShareMax'], '.3f')} | {t['setsLongCut']} | {gs} | {_p(t['bodyHeightMedian'])} |")
    if fr["noLandmarks"]:
        L.append(f"\n좌표 없는 세트(검증 모드가 아니었다) {len(fr['noLandmarks'])} — 프레이밍을 잴 수 없다.")
    L.append("")

    # ---- 무결성
    L += ["## 피처 무결성 — 검증 로그 좌표에서 다시 계산한 카운터 신호 대 로그 신호", "",
          f"허용 오차: 각도 신호 {TOL_ANGLE}°, 거리 신호 {TOL_DISTANCE}. 로그 좌표는 소수 4자리·가시성 3자리라 비트 단위 일치가 아니다.", "",
          "| 세트 | 종목 | 신호 | 비교 프레임 | 최대 차 | 허용 오차 안 | 있음/없음 불일치 | 가장 큰 차의 피처 (차) |", "|---|---|---|---:|---:|---:|---:|---|"]
    for sid, x in integ["perSet"].items():
        L.append(f"| {sid} | {x['exercise']} | `{x['signal']}` | {x['signalCompared']} | {_p(x['signalMaxAbsDiff'], '.2g')} | "
                 f"{_p(x['signalWithinTol'], '.3f')} | {x['presenceMismatch']} | `{x['worstFeature']}` ({_p(x['worstFeatureDiff'], '.2g')}) |")
    L.append("")

    # ---- 세트별
    L += ["## 세트별", "", "| 세트 | 종목 | 방향 | 뷰 | 조건 | 정답 (출처) | app | " + " | ".join(preds) + " | 잘림 | 긴 잘림 s |",
          "|---|---|---|---|---|---|---:|" + "---:|" * len(preds) + "---:|---:|"]
    for r in rows:
        e = by_id.get(r["setId"], {})
        f = fr["perSet"].get(r["setId"], {})
        L.append(f"| {r['setId']} | {r['exercise']} | {orientation(e)} | {e.get('view') or '—'} | {r['condition']} | "
                 f"{_p(r['truth'], 'd') if r['truth'] is not None else '—'} ({r['truthSource'] or '—'}) | {r['pred'].get('app', '—')} | "
                 + " | ".join(str(r["pred"].get(p, "—")) for p in preds)
                 + f" | {_p(f.get('cutShare'), '.2f')} | {_p(f.get('longestCutS'), '.1f')} |")
    others = [e["setId"] for e in index["sets"] if e["setId"] not in score_ids]
    if others:
        L.append(f"\n채점 대상이 아닌 세트(세 종목 밖이거나 세션 로그가 아님) {len(others)}: 프레이밍·무결성·잡음 표에만 들어간다.")
    L += ["", "자세한 표: `score/summary.md`(정답 등급·조건·사람별·화면 단위·음성 세트), `noise/noise_summary.md`(추론 간격·발열·튐·끊김), "
          "`framing.json`, `integrity.json`.", ""]
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------- 파이프라인

def run(inputs: list[Path], plan: Path | None, out: Path, labels: list[Path] | None = None, skip: set[str] | None = None,
        configs: set[str] | None = None, headline: str = score_phone_reps.GATE["stat"], since: str | None = None,
        until: str | None = None) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    label_paths = labels if labels is not None else [p if p.is_dir() else p.parent for p in inputs]
    skip = skip or set()
    # 1) 전 세트(종목 무관) 캡처 — 프레이밍·무결성은 채점 대상 밖 종목도 본다
    index = setlog_captures.build(inputs, out / "captures", label_paths, since, until)
    index["sets"] = [e for e in index["sets"] if e["setId"] not in skip]
    # 2) 채점 (Gate A = 점추정만)
    s = score_phone_reps.score(inputs, out / "score", plan, label_paths, since, until, skip,
                               configs or score_phone_reps.DEFAULT_CONFIGS, 10, headline, "A")
    (out / "score" / "summary.json").write_text(json.dumps(s, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    (out / "score" / "summary.md").write_text(score_phone_reps.markdown(s), encoding="utf-8")
    results = run_replay.load_results(out / "score" / "replay" / "replay.jsonl")
    # 3) 잡음
    phone_noise.run(inputs, out / "noise")
    # 4) 프레이밍 · 5) 무결성
    fr = framing(index, out / "captures")
    (out / "framing.json").write_text(json.dumps(fr, ensure_ascii=False, indent=1), encoding="utf-8")
    integ = integrity(index, out / "captures", out / "integrity")
    (out / "integrity.json").write_text(json.dumps(integ, ensure_ascii=False, indent=1), encoding="utf-8")
    # 6) 보고서
    md = report(s, fr, integ, index, results, headline)
    (out / "report.md").write_text(md, encoding="utf-8")
    return {"score": s, "framing": fr, "integrity": integ, "index": index, "results": results, "report": md}


# ---------------------------------------------------------------- 드라이런

def dry_run(out: Path) -> int:
    import make_phone_fixture  # noqa: PLC0415

    if out.exists():
        shutil.rmtree(out)
    fx = make_phone_fixture.make(out / "fixture")
    phone = Path(fx["phone"])
    res = run([phone], Path(fx["plan"]), out / "gate_a")
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, bool(ok), detail))

    exp = fx["sets"]
    s, fr, integ, index = res["score"], res["framing"], res["integrity"], res["index"]
    rows = {r["setId"]: r for r in s["rows"]}
    drows = {r["setId"]: r for r in s["displayRows"]}
    check("세트 수 · 전부 검증 모드 · 전부 랜드마크 캡처", len(index["sets"]) == len(exp) == len(rows)
          and all(e["validation"] and e["landmarkCapture"] for e in index["sets"]), f"{len(index['sets'])} / {len(exp)}")
    check("계획표 짝짓기(종목별 시각 순서) — 짝 없는 행·로그 없음", not s["unmatchedPlan"] and not s["unmatchedLogs"],
          f"{s['unmatchedPlan']} {s['unmatchedLogs']}")
    tr_ok = all(rows[i]["truthSource"] == "tally" and rows[i]["truth"] == e["cycles"] for i, e in exp.items())
    check("걸음·반복 정답 = 집계(스쿼트 tally_reps, 런지 왼 + 오른)", tr_ok,
          str({i[-4:]: (rows[i]["truth"], rows[i]["truthSource"]) for i in exp}))
    lunges = [i for i, e in exp.items() if e["left"] is not None]
    check("런지 쌍(화면) 정답 = min(왼, 오른) · app 쌍 = 사이클 // 2",
          all(drows[i]["truth"] == min(exp[i]["left"], exp[i]["right"]) and drows[i]["pred"].get("app") == exp[i]["appCount"] // 2
              for i in lunges) and bool(lunges), str({i[-4:]: (drows[i]["truth"], drows[i]["pred"].get("app")) for i in lunges}))
    check("app(로그 reps.count) = 지금 빌드 live 재생 (파리티)", all(rows[i]["pred"].get("app") == rows[i]["pred"].get("live") for i in exp),
          str({i[-4:]: (rows[i]["pred"].get("app"), rows[i]["pred"].get("live")) for i in exp}))
    clean = [i for i, e in exp.items() if not e["drift"]]
    drift = [i for i, e in exp.items() if e["drift"]]
    check("깨끗한 합성 세트는 live 가 정답과 같다(픽스처 동작이 셀 수 있는 반복인지)", all(rows[i]["pred"]["live"] == exp[i]["cycles"] for i in clean),
          str({i[-4:]: rows[i]["pred"]["live"] for i in clean}))
    check("잘림 세트: live 가 정답보다 적다(보이지 않는 관절은 셀 수 없다)", drift and all(rows[i]["pred"]["live"] < exp[i]["cycles"] for i in drift),
          str({i[-4:]: (rows[i]["pred"]["live"], exp[i]["cycles"]) for i in drift}))
    fps = fr["perSet"]
    check("프레이밍: 잘림 세트의 잘림 비율 ≥ 0.25 · 긴 잘림 > 2 s · 무릎·발목 모두 잡힘",
          all(fps[i]["cutShare"] >= 0.25 and fps[i]["longestCutS"] > LONG_CUT_S and fps[i]["groupShare"]["무릎"] > 0
              and fps[i]["groupShare"]["발목"] > 0 for i in drift),
          str({i[-4:]: (round(fps[i]["cutShare"], 3), round(fps[i]["longestCutS"], 1), {g: round(v, 3) for g, v in fps[i]["groupShare"].items()})
               for i in drift}))
    check("프레이밍: 나머지 세트는 잘림 0", all(fps[i]["cutShare"] == 0 for i in clean), str({i[-4:]: fps[i]["cutShare"] for i in clean}))
    cells = {(t["exercise"], t["orientation"]) for t in fr["table"]}
    check("프레이밍 표: 스쿼트·런지 × 세로·가로 네 칸", {(ex, o) for ex in ("바벨 스쿼트", "스텝 포워드 다이나믹 런지") for o in ("세로", "가로")} <= cells,
          str(sorted(cells)))
    sq_p = next(t for t in fr["table"] if t["exercise"] == "바벨 스쿼트" and t["orientation"] == "세로")
    sq_l = next(t for t in fr["table"] if t["exercise"] == "바벨 스쿼트" and t["orientation"] == "가로")
    check("프레이밍 표: 스쿼트 세로 > 0, 가로 = 0", sq_p["cutShare"] > 0 and sq_l["cutShare"] == 0, f"{sq_p['cutShare']:.3f} / {sq_l['cutShare']}")
    su = integ["summary"]
    check("무결성: 전 세트 비교 · 최대 차 ≤ 1e-3 · 허용 오차 안 1.0 · 불일치 0 · ok",
          su["sets"] == len(exp) and su["maxAbsDiff"] is not None and su["maxAbsDiff"] <= 1e-3 and su["withinTol"] == 1.0
          and su["presenceMismatch"] == 0 and su["ok"], json.dumps(su))
    # 음성 대조: 로그 피처 한 프레임을 1° 바꾸면 무결성이 잡아야 한다
    tamper = out / "tamper"
    tamper.mkdir(parents=True, exist_ok=True)
    line = (phone / "sets-20260924.jsonl").read_text(encoding="utf-8").splitlines()[0]
    log = json.loads(line)
    k = len(log["frames"]) // 2
    old = log["frames"][k]["features"]["knee_mean"]
    old_text = setlog_captures._kt_num(old)
    new_text = setlog_captures._kt_num(old + 1.0)
    needle = f'"knee_mean":{old_text},'
    pos = line.find(needle, line.find(f'"t_ms":{log["frames"][k]["t_ms"]},'))
    (tamper / "sets-20260924.jsonl").write_text(line[:pos] + f'"knee_mean":{new_text},' + line[pos + len(needle):] + "\n", encoding="utf-8")
    tidx = setlog_captures.build([tamper], tamper / "captures", [])
    ti = integrity(tidx, tamper / "captures", tamper / "integrity")["summary"]
    check("무결성 음성 대조: 한 프레임 knee_mean +1° → 최대 차 ≈ 1, ok=false", pos > 0 and abs(ti["maxAbsDiff"] - 1.0) < 0.01 and not ti["ok"],
          json.dumps(ti))
    md = res["report"]
    check("report.md: 점검표·프레이밍·무결성·세트별 절", all(h in md for h in ("## Gate A 나가는 조건", "## 프레이밍", "## 피처 무결성", "## 세트별"))
          and (out / "gate_a" / "report.md").is_file())
    check("report.md: 잘림 세트가 틀린 세트 표에 '화면 밖' 원인 후보로", all(f"| {i} |" in md for i in drift) and "화면 밖" in md)
    check("잡음 요약 생성", (out / "gate_a" / "noise" / "noise_summary.md").is_file())
    check("검증 모드 점검 ✓ · 짝짓기 ✓ · 집계 ✓", "| 모든 세트가 검증 모드(숫자 숨김·자동 진행·음성 꺼짐) | ✓ |" in md
          and "| 계획표와 로그가 모두 짝지어짐 | ✓ |" in md and "| 모든 세트에 독립 집계(사람의 손 집계) 정답 | ✓ |" in md)
    check("덤벨 컬은 데이터 없음으로 적힌다(판정하지 않은 것을 통과로 적지 않는다)", "| §1 점추정 — 덤벨 컬 · `live` | 확인 필요 | 데이터 없음 |" in md)

    width = max(len(n) for n, _, _ in checks)
    for name, ok, detail in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {detail}")
    failed = sum(not ok for _, ok, _ in checks)
    print(f"\n{len(checks) - failed}/{len(checks)} 통과 — 보고서 {out / 'gate_a' / 'report.md'}")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="회수한 폴더 → 보고서")
    r.add_argument("inputs", nargs="+", type=Path, help="pull_phone.py pull 이 만든 폴더(들) 또는 sets-*.jsonl")
    r.add_argument("--plan", type=Path, default=None, help="집계를 채운 계획표 CSV (rep_validation_plan.py --csv gateA)")
    r.add_argument("--out", type=Path, required=True)
    r.add_argument("--labels", nargs="*", type=Path, default=None)
    r.add_argument("--skip", default="", help="set_id 콤마 목록 — 망친 세트(계획대로 바로 다시 찍었다는 전제)")
    r.add_argument("--configs", default=None, help="재생 구성 콤마 목록 (기본 live,hysteresis,신호 후보)")
    r.add_argument("--headline", default=score_phone_reps.GATE["stat"], help="점검표에 live 와 함께 싣는 구성 (기본 hysteresis)")
    r.add_argument("--since", default=None)
    r.add_argument("--until", default=None)
    d = sub.add_parser("dry-run", help="폰 없이 합성 픽스처로 전 과정 검사")
    d.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    if args.cmd == "dry-run":
        return dry_run(args.out)
    if args.plan is None:
        print("[주의] 계획표가 없다 — 독립 집계 정답이 없어 점검표의 정확도 칸은 '데이터 없음' 이 된다", file=sys.stderr)
    res = run(args.inputs, args.plan, args.out, args.labels, {x for x in args.skip.split(",") if x},
              set(args.configs.split(",")) if args.configs else None, args.headline, args.since, args.until)
    print(res["report"])
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
