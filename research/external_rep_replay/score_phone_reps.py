# -*- coding: utf-8 -*-
"""휴대폰 세트 로그 채점 — 설계 §1 지표를 종목 × 조건 × 사람으로, Clopper-Pearson 단측 95% 경계와 함께 (Gate A/B, 설계 §7).

흐름
    세트 로그(sets-*.jsonl) ─ setlog_captures.build ─→ 피처 캡처 ─ run_replay(JVM) ─→ 구성별 카운트
                             └ 계획표(rep_validation_plan.py 가 만든 CSV, 집계 칸을 채운 것)와 짝짓기
짝짓기   같은 AIHub 종목의 세션 로그를 created_at 순서로, 계획표의 같은 종목 행과 CSV 순서대로 맞춘다(DEVICE_VALIDATION 과 같은 규약).
         계획 행에 set_id 가 적혀 있으면 그것이 우선이다. --skip 으로 망친 세트를 뺀다(계획대로 바로 다시 찍었다는 전제).
정답 등급 (섞지 않는다)
    tally      독립 집계표(다른 사람의 손 집계 또는 두 번째 폰 영상) — 헤드라인
    edited     완료 화면에서 사용자가 스테퍼로 고친 값(자가 라벨, 앱 숫자를 본 뒤라 완전히 독립은 아니다)
    confirmed  앱 숫자를 보고 "맞아요" 한 값 — **순환**(앱 카운트와 같다). 참고 표에만 싣고 헤드라인·판정에 넣지 않는다
    음성 세트(condition=negative)는 집계가 비어 있으면 계획대로 0 을 정답으로 쓴다(plan-negative)
예측
    app          로그의 reps.count — 그 세트를 찍은 빌드가 실제로 보여 준 수
    <구성>       지금 빌드의 카운터로 다시 센 수(live = 레거시, hysteresis = 새 코어, 신호 후보)
    always       계획 횟수(planned_reps)를 그대로 부르는 기준선(설계 §1 "항상-목표치")
    세트 카운트는 **세트 전체의 발표 수**(앱이 세트 끝에 보여 주는 수)다 — 세트 앞 헛카운트도 그 안에 들어 있다.
세트 경계 (설계 §14, 앞·뒤 헛카운트)
    오프라인 복귀 사이클(확정 없는 히스테리시스, h = 게이트)과 정지 표지(범위 ≤ 0.25×게이트, ≥ 2.5 s)로 실제 반복 구간을 찾는다:
    끝 표지(세트 끝 2 s 안에서 끝나는 정지) 앞의 사이클 중 **마지막 정답 수만큼**이 실제 반복이다 — 준비 동작의 반복 크기 움직임은
    그 앞에 있으므로 자연히 빠진다. 첫 실제 반복의 하강 시작 − 0.5 s 이전에 발화한 발표 = 앞 헛카운트, 마지막 실제 반복의 복귀
    + 1.5 s 이후 = 뒤 헛카운트. 오프라인 사이클이 정답보다 적으면 경계를 모른다 → 그 세트의 앞·뒤는 null(판정하지 않음).
판정 (설계 §7 Gate B — 고정 빌드·새 사람 데이터에서만 뜻이 있다)
    정확 일치 하한 ≥ 0.90 · 과다 카운트 상한(관측 수와 함께) ≤ 0.02 · 세트 앞 헛카운트가 있는 세트 상한 ≤ 0.05 ·
    사람별 정확 일치 점추정 ≥ 0.80 · 한 사람 ≤ 세트의 30%. 세트가 모자라 0개 틀려도 하한이 기준에 못 닿으면 '판정 불가(세트 부족)'.
    Gate A 데이터에 이 판정을 붙이지 않는다(--gate A 면 점추정만).

사용법
    python score_phone_reps.py <sets-*.jsonl|로그 폴더 ...> --plan plan_gateA.csv --out <결과 폴더> [--since 2026-10-01]
                               [--labels <폴더>] [--skip set_id,...] [--configs live,hysteresis] [--headline hysteresis] [--gate A|B]
    python score_phone_reps.py --self-test [--out <임시 폴더>]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

import run_replay
import setlog_captures

TARGETS = ("바벨 스쿼트", "스텝 포워드 다이나믹 런지", "덤벨 컬")
GATE = {"stat": "hysteresis", "exactLB": 0.90, "overUB": 0.02, "leadUB": 0.05, "personExact": 0.80, "personShare": 0.30}
EXCLUDED_FROM_VERDICT = {"negative", "veryslow"}      # 음성 세트는 따로, 아주 느린 세트는 알려진 한계(설계 §13.4) — 판정 밖
ALPHA = 0.05
DEFAULT_CONFIGS = {"live", "hysteresis", "hysteresis+knee_minside", "hysteresis+elbow_minside_both"}


# ---------------------------------------------------------------- Clopper-Pearson (표준 라이브러리, 정확 이항)

def _binom_cdf(k: int, n: int, p: float) -> float:
    """P(X ≤ k), X ~ Bin(n, p). 로그 감마로 큰 n 에서도 넘치지 않게."""
    if k < 0:
        return 0.0
    if k >= n:
        return 1.0
    if p <= 0:
        return 1.0
    if p >= 1:
        return 0.0
    lp, lq = math.log(p), math.log1p(-p)
    total = 0.0
    for i in range(k + 1):
        total += math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + i * lp + (n - i) * lq)
    return min(1.0, total)


def _bisect(f, lo: float = 0.0, hi: float = 1.0, it: int = 80) -> float:
    for _ in range(it):
        mid = (lo + hi) / 2
        if f(mid):
            hi = mid
        else:
            lo = mid
    return (lo + hi) / 2


def cp_lower(x: int, n: int, alpha: float = ALPHA) -> float:
    """단측 (1−α) 하한: P(X ≥ x | p) = α 인 p. x = 0 이면 0."""
    if n == 0:
        return float("nan")
    if x <= 0:
        return 0.0
    return _bisect(lambda p: 1.0 - _binom_cdf(x - 1, n, p) >= alpha)


def cp_upper(x: int, n: int, alpha: float = ALPHA) -> float:
    """단측 (1−α) 상한: P(X ≤ x | p) = α 인 p. x = n 이면 1."""
    if n == 0:
        return float("nan")
    if x >= n:
        return 1.0
    return _bisect(lambda p: _binom_cdf(x, n, p) <= alpha)


def sets_needed_for_lower(bound: float, misses: int, alpha: float = ALPHA) -> int:
    """틀린 세트가 misses 개일 때 정확 일치 하한이 bound 이상이 되는 최소 세트 수."""
    n = misses + 1
    while cp_lower(n - misses, n, alpha) < bound:
        n += 1
    return n


def sets_needed_for_upper(bound: float, events: int, alpha: float = ALPHA) -> int:
    n = events + 1
    while cp_upper(events, n, alpha) > bound:
        n += 1
    return n


# ---------------------------------------------------------------- 오프라인 경계

def offline_cycles(t: list[int], v: list[float], h: float, f: float = 0.25, refractory: int = 800) -> list[dict]:
    """확정 없는 복귀 히스테리시스(휴식 = 큰 값, prototype_counter.Counter 와 같은 규약, 평활 없음, 1.5 s 끊김 리셋).
    카운터가 아니라 **경계 찾기용 기준**이다 — 채점 대상 카운터와 같은 입력을 쓰지만 시작 확정을 거치지 않는다."""
    out, state, r, r0, m, amax, last_t, last_fire, desc = [], "REST", None, None, None, None, None, -10**9, 0
    for ti, vi in zip(t, v):
        if vi is None or not math.isfinite(vi):
            continue
        if last_t is not None and ti - last_t > run_replay.MAX_GAP_MS:
            state, r = "REST", None
        last_t = ti
        if r is None:
            r = vi
        if state == "REST":
            r = max(r, vi)
            if vi <= r - h:
                state, r0, m, desc = "DESC", r, vi, ti
            continue
        if state == "DESC":
            if vi < m:
                m = vi
            elif vi >= m + h:
                state, amax = "ASC", vi
            continue
        amax = max(amax, vi)
        if vi >= m + (1 - f) * (r0 - m) or vi <= amax - h:
            if ti - last_fire >= refractory:
                out.append({"start": desc, "fire": ti, "min": m, "rest": r0})
                last_fire = ti
            redesc = vi <= amax - h
            state, r = ("DESC", amax) if redesc else ("REST", vi)
            if redesc:
                r0, m, desc = amax, vi, ti
    return out


def still_segments(t: list[int], v: list[float], band: float, min_ms: int = 2500) -> list[tuple[int, int]]:
    segs, i, n = [], 0, len(t)
    while i < n:
        if v[i] is None or not math.isfinite(v[i]):
            i += 1
            continue
        lo = hi = v[i]
        j = i + 1
        while j < n and v[j] is not None and math.isfinite(v[j]) and t[j] - t[j - 1] <= run_replay.MAX_GAP_MS:
            lo, hi = min(lo, v[j]), max(hi, v[j])
            if hi - lo > band:
                break
            j += 1
        if t[j - 1] - t[i] >= min_ms:
            segs.append((t[i], t[j - 1]))
            i = j
        else:
            i += 1
    return segs


def boundaries(frames: list[tuple[int, dict]], signal: str, gate: float, truth: int | None) -> dict:
    t = [f[0] for f in frames]
    v = [run_replay_value(f[1], signal) for f in frames]
    if truth is None or truth <= 0 or not t:
        return {"method": "none", "lead": None, "after": None}
    cyc = offline_cycles(t, v, gate)
    stills = still_segments(t, v, 0.25 * gate)
    end_marker = next((s for s in reversed(stills) if t[-1] - s[1] <= 2000), None)
    cand = [c for c in cyc if end_marker is None or c["fire"] <= end_marker[0] + 500]
    if len(cand) < truth:
        return {"method": "none", "lead": None, "after": None, "offlineCycles": len(cyc)}
    real = cand[-truth:]
    lead_b = real[0]["start"] - 500
    after_b = real[-1]["fire"] + 1500
    start_marker = any(s[1] <= real[0]["start"] + 300 and real[0]["start"] - s[1] <= 3000 for s in stills)
    return {"method": "offline+end-marker" if end_marker else "offline", "lead": lead_b, "after": after_b,
            "startMarker": start_marker, "offlineCycles": len(cyc), "extraBefore": len(cand) - truth}


def boundary_signal(entry: dict, live: dict) -> tuple[str | None, float | None]:
    """세트 경계를 찾을 (신호, 게이트). 신호는 로그가 센 신호(없으면 지금 등록부 신호), 게이트는 **그 신호와 같은 출처**:
    로그의 reps.config.min_amp → 지금 등록부 신호면 그 게이트 → §58 이전 로그의 옛 신호면 run_replay.HISTORICAL_MIN_AMP.
    옛 런지 로그(knee_out_mean, 스윙 0.05~0.2)에 지금 knee_mean 의 35° 를 붙이면 사이클이 하나도 안 잡혀 앞·뒤 헛카운트가
    조용히 '판정 안 함' 이 된다. 게이트를 모르면 None — 추측한 게이트로 경계를 만들지 않는다."""
    signal = entry.get("loggedSignal") or live.get("feature")
    if signal is None:
        return None, None
    gate = entry.get("loggedMinAmp") or (live.get("minAmp") if signal == live.get("feature") else run_replay.HISTORICAL_MIN_AMP.get(signal))
    return signal, gate


def run_replay_value(features: dict, name: str) -> float | None:
    """Replay.kt signalValue 와 같은 규칙(연구용 파생 신호 _minside_both 포함)."""
    if name in features:
        return features[name]
    if name.endswith("_minside_both"):
        base = name[: -len("_minside_both")]
        l, r = features.get(f"{base}_L"), features.get(f"{base}_R")
        return min(l, r) if l is not None and r is not None else None
    return None


# ---------------------------------------------------------------- 계획 짝짓기 · 정답

def load_plan(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as h:
        return [dict(r) for r in csv.DictReader(h)]


def match_plan(entries: list[dict], plan: list[dict]) -> tuple[dict[str, dict], list[dict], list[dict]]:
    """set_id → 계획 행. (짝, 짝 없는 계획 행, 짝 없는 로그)."""
    by_id = {e["setId"]: e for e in entries}
    matched: dict[str, dict] = {}
    used = set()
    for row in plan:
        sid = (row.get("set_id") or "").strip()
        if sid and sid in by_id:
            matched[sid] = row
            used.add(id(row))
    logs_by_ex = defaultdict(list)
    for e in sorted(entries, key=lambda e: e.get("createdAt") or ""):
        if e["setId"] not in matched:
            logs_by_ex[e["exercise"]].append(e)
    plan_by_ex = defaultdict(list)
    for row in plan:
        if id(row) not in used and (row.get("skip") or "").strip() == "":
            plan_by_ex[row["exercise"]].append(row)
    left_plan, left_logs = [], []
    for ex in set(logs_by_ex) | set(plan_by_ex):
        L, P = logs_by_ex[ex], plan_by_ex[ex]
        for e, row in zip(L, P):
            matched[e["setId"]] = row
        left_logs += L[len(P):]
        left_plan += P[len(L):]
    return matched, left_plan, left_logs


def truth_of(e: dict, row: dict | None) -> tuple[int | None, str | None]:
    if row is not None:
        tally = (row.get("tally_reps") or "").strip()
        if tally:
            return int(tally), "tally"
    if e.get("truthReps") is not None and e.get("truthSource") == "edited":
        return e["truthReps"], "edited"
    if e.get("confirmedReps") is not None:
        return e["confirmedReps"], "confirmed"
    if row is not None and row.get("condition") == "negative":
        return 0, "plan-negative"
    return None, None


# ---------------------------------------------------------------- 지표

def group_metrics(rows: list[dict], pred: str) -> dict:
    rs = [r for r in rows if r["pred"].get(pred) is not None]
    n = len(rs)
    if n == 0:
        return {"n": 0}
    err = [r["pred"][pred] - r["truth"] for r in rs]
    exact = sum(e == 0 for e in err)
    obo = sum(abs(e) <= 1 for e in err)
    over = sum(e > 0 for e in err)
    out = {"n": n, "exact": exact, "exactRate": exact / n, "exactLB": cp_lower(exact, n),
           "obo": obo, "oboRate": obo / n, "oboLB": cp_lower(obo, n),
           "over": over, "overRate": over / n, "overUB": cp_upper(over, n), "under": sum(e < 0 for e in err),
           "MAE": sum(abs(e) for e in err) / n, "zero": sum(r["pred"][pred] == 0 for r in rs)}
    leads = [r["lead"][pred] for r in rs if r["lead"].get(pred) is not None]
    if leads:
        k = sum(x > 0 for x in leads)
        out.update({"leadN": len(leads), "leadSets": k, "leadUB": cp_upper(k, len(leads)), "leadPerSet": sum(leads) / len(leads)})
    afters = [r["after"][pred] for r in rs if r["after"].get(pred) is not None]
    if afters:
        out.update({"afterN": len(afters), "afterPerSet": sum(afters) / len(afters)})
    return out


def negative_metrics(rows: list[dict], pred: str) -> dict:
    rs = [r for r in rows if r["pred"].get(pred) is not None]
    if not rs:
        return {"n": 0}
    counts = [r["pred"][pred] for r in rs]
    minutes = sum(r["durationS"] for r in rs) / 60.0
    k = sum(c > 0 for c in counts)
    return {"n": len(rs), "falseCounts": sum(counts), "perSet": sum(counts) / len(rs), "setsWithCount": k,
            "setsWithCountUB": cp_upper(k, len(rs)), "perMinute": sum(counts) / minutes if minutes > 0 else float("nan")}


def verdict(rows: list[dict], pred: str) -> dict:
    main = [r for r in rows if r["condition"] not in EXCLUDED_FROM_VERDICT]
    m = group_metrics(main, pred)
    n = m.get("n", 0)
    if n == 0:
        return {"verdict": "데이터 없음"}
    need0 = sets_needed_for_lower(GATE["exactLB"], 0)
    per_person = defaultdict(list)
    for r in main:
        per_person[r["person"]].append(r)
    persons = {p: group_metrics(rs, pred) for p, rs in per_person.items()}
    share = max(len(rs) for rs in per_person.values()) / n
    checks = {
        "exactLB": (m["exactLB"] >= GATE["exactLB"], f"{m['exact']}/{n} → 하한 {m['exactLB']:.3f}"),
        "overUB": (m["overUB"] <= GATE["overUB"], f"관측 {m['over']}/{n} → 상한 {m['overUB']:.3f}"),
        "leadUB": ((m.get("leadUB", 1.0) <= GATE["leadUB"]) if m.get("leadN") else False,
                   f"{m.get('leadSets', '—')}/{m.get('leadN', 0)} → 상한 {m.get('leadUB', float('nan')):.3f}"),
        "personExact": (all(x.get("exactRate", 0) >= GATE["personExact"] for x in persons.values()),
                        ", ".join(f"{p} {x.get('exactRate', 0):.2f}" for p, x in sorted(persons.items()))),
        "personShare": (share <= GATE["personShare"], f"최대 {share:.2f}"),
    }
    if n < need0:
        v = f"판정 불가(세트 부족: {n} < {need0} — 0개 틀려도 하한 < {GATE['exactLB']})"
    elif all(ok for ok, _ in checks.values()):
        v = "통과"
    else:
        v = "미달: " + ", ".join(k for k, (ok, _) in checks.items() if not ok)
    return {"verdict": v, "n": n, "checks": {k: {"ok": ok, "detail": d} for k, (ok, d) in checks.items()}}


# ---------------------------------------------------------------- 실행

def score(inputs: list[Path], out: Path, plan_path: Path | None, label_paths: list[Path], since: str | None, until: str | None,
          skip: set[str], configs: set[str], always_n: int, headline: str, gate: str) -> dict:
    cap_dir = out / "captures"
    index = setlog_captures.build(inputs, cap_dir, label_paths, since, until, set(TARGETS), session_only=True)
    index["sets"] = [e for e in index["sets"] if e["setId"] not in skip]
    (cap_dir / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    plan = load_plan(plan_path) if plan_path else []
    matched, left_plan, left_logs = match_plan(index["sets"], plan) if plan else ({}, [], [])
    _, results, names = run_replay.replay_index(cap_dir, out / "replay", configs)
    names = [c for c in names if not c.endswith("@log")]
    rows = []
    for e in index["sets"]:
        row = matched.get(e["setId"])
        truth, source = truth_of(e, row)
        key = run_replay.set_key(e)
        _, frames = setlog_captures.read_feature_capture(cap_dir / e["capture"])
        signal, gate_amp = boundary_signal(e, results.get(f"{key}|live", {}))
        bnd = (boundaries(frames, signal, gate_amp, truth) if signal and gate_amp
               else {"method": "none", "lead": None, "after": None})
        preds, fires = {}, {}
        if e.get("loggedReps") is not None:
            preds["app"] = e["loggedReps"]
            fires["app"] = e["loggedRepTimesMs"] if e.get("loggedTimesRelative") is not False else None
        for c in names:
            r = results.get(f"{key}|{c}")
            if r and "error" not in r:
                preds[c] = r["reps"]
                fires[c] = run_replay.fire_times(r)
        planned = int(row["planned_reps"]) if row and (row.get("planned_reps") or "").strip() else always_n
        preds["always"] = planned
        lead, after = {}, {}
        for p, ts in fires.items():
            if ts is None or bnd["lead"] is None:
                continue
            lead[p] = sum(t < bnd["lead"] for t in ts)
            after[p] = sum(t > bnd["after"] for t in ts)
        rows.append({"setId": e["setId"], "exercise": e["exercise"], "createdAt": e.get("createdAt"),
                     "person": (row or {}).get("person") or e.get("subject") or "unknown",
                     "condition": (row or {}).get("condition") or "unplanned", "planId": (row or {}).get("plan_id"),
                     "truth": truth, "truthSource": source, "pred": preds, "lead": lead, "after": after, "boundary": bnd,
                     "durationS": ((e.get("lastMs") or 0) - (e.get("firstMs") or 0)) / 1000.0, "mode": e.get("mode"),
                     "view": e.get("view")})
    preds_all = ["app", *names, "always"]
    tiers = {"independent": ("tally", "plan-negative"), "edited": ("edited",), "confirmed(순환)": ("confirmed",)}
    summary = {"gate": gate, "headline": headline, "sets": len(rows), "planRows": len(plan),
               "unmatchedPlan": [r.get("plan_id") for r in left_plan], "unmatchedLogs": [e["setId"] for e in left_logs],
               "truthSources": {s: sum(r["truthSource"] == s for r in rows) for s in ("tally", "edited", "confirmed", "plan-negative", None)},
               "tiers": {}, "negative": {}, "verdict": {}, "rows": rows}
    for tier, sources in tiers.items():
        rs = [r for r in rows if r["truthSource"] in sources and r["condition"] != "negative"]
        if not rs:
            continue
        t = summary["tiers"][tier] = {}
        by_ex = defaultdict(list)
        for r in rs:
            by_ex[r["exercise"]].append(r)
        for ex, xs in sorted(by_ex.items()):
            main = [r for r in xs if r["condition"] not in EXCLUDED_FROM_VERDICT]
            by_cond, by_person = defaultdict(list), defaultdict(list)
            for r in xs:
                by_cond[r["condition"]].append(r)
                by_person[r["person"]].append(r)
            t[ex] = {"all": {p: group_metrics(main, p) for p in preds_all},
                     "byCondition": {c: {p: group_metrics(v, p) for p in preds_all} for c, v in sorted(by_cond.items())},
                     "byPerson": {q: {p: group_metrics(v, p) for p in preds_all} for q, v in sorted(by_person.items())}}
    neg = [r for r in rows if r["condition"] == "negative"]
    by_ex = defaultdict(list)
    for r in neg:
        by_ex[r["exercise"]].append(r)
    summary["negative"] = {ex: {p: negative_metrics(xs, p) for p in preds_all if p != "always"} for ex, xs in sorted(by_ex.items())}
    head_rows = [r for r in rows if r["truthSource"] in tiers["independent"] and r["condition"] != "negative"]
    if gate == "B":
        by_ex = defaultdict(list)
        for r in head_rows:
            by_ex[r["exercise"]].append(r)
        summary["verdict"] = {ex: verdict(xs, headline) for ex, xs in sorted(by_ex.items())}
    return summary


def _p(x, fmt=".2f"):
    return "—" if x is None or (isinstance(x, float) and x != x) else format(x, fmt)


def markdown(s: dict) -> str:
    L = [f"# 휴대폰 렙 카운트 채점 — Gate {s['gate']} (헤드라인 구성: {s['headline']})", "",
         f"세트 {s['sets']} · 계획 행 {s['planRows']} · 짝 없는 계획 행 {len(s['unmatchedPlan'])} · 짝 없는 로그 {len(s['unmatchedLogs'])} · "
         f"정답 출처 {', '.join(f'{k}={v}' for k, v in s['truthSources'].items() if v)}", "",
         "하한·상한은 세트 단위 Clopper-Pearson 단측 95%. 세트는 사람 안에서 독립이 아니다 — 사람별 표를 함께 본다.", ""]
    if s["gate"] != "B":
        L += ["Gate A 는 개발 데이터다(상수·정책을 바꿀 수 있다). 성능 주장이 아니라 튜닝·귀속용 점추정으로 읽는다.", ""]
    for tier, by_ex in s["tiers"].items():
        L += [f"## 정답: {tier}" + (" — 참고만 (앱 카운트와 같은 값이라 헤드라인·판정에 넣지 않는다)" if "순환" in tier else ""), "",
              "| 종목 | 예측 | 세트 | 정확 (하한) | ±1 (하한) | 과다 세트 (상한) | 미달 | MAE | 앞 헛카운트 세트 (상한) · /세트 | 뒤 /세트 |",
              "|---|---|---:|---|---|---|---:|---:|---|---:|"]
        for ex, e in by_ex.items():
            for p, m in e["all"].items():
                if not m.get("n"):
                    continue
                lead = f"{m['leadSets']}/{m['leadN']} ({_p(m['leadUB'], '.3f')}) · {m['leadPerSet']:.2f}" if m.get("leadN") else "—"
                L.append(f"| {ex} | {p} | {m['n']} | {m['exactRate']:.2f} ({m['exactLB']:.3f}) | {m['oboRate']:.2f} ({m['oboLB']:.3f}) | "
                         f"{m['over']} ({m['overUB']:.3f}) | {m['under']} | {m['MAE']:.2f} | {lead} | {_p(m.get('afterPerSet'))} |")
        L += ["", "조건별 정확 일치 (세트 수)", "", "| 종목 | 조건 | " + " | ".join(next(iter(by_ex.values()))["all"].keys()) + " |",
              "|---|---|" + "---:|" * len(next(iter(by_ex.values()))["all"])]
        for ex, e in by_ex.items():
            for c, ms in e["byCondition"].items():
                L.append(f"| {ex} | {c} | " + " | ".join(f"{m['exactRate']:.2f} ({m['n']})" if m.get("n") else "—" for m in ms.values()) + " |")
        L += ["", "사람별 정확 일치 (세트 수)", "", "| 종목 | 사람 | " + " | ".join(next(iter(by_ex.values()))["all"].keys()) + " |",
              "|---|---|" + "---:|" * len(next(iter(by_ex.values()))["all"])]
        for ex, e in by_ex.items():
            for q, ms in e["byPerson"].items():
                L.append(f"| {ex} | {q} | " + " | ".join(f"{m['exactRate']:.2f} ({m['n']})" if m.get("n") else "—" for m in ms.values()) + " |")
        L.append("")
    if s["negative"]:
        L += ["## 음성 세트 (반복 없음 — 모든 카운트가 헛카운트)", "", "| 종목 | 예측 | 세트 | 헛카운트 | /세트 | /분 | 1회 이상 센 세트 (상한) |",
              "|---|---|---:|---:|---:|---:|---|"]
        for ex, by in s["negative"].items():
            for p, m in by.items():
                if m.get("n"):
                    L.append(f"| {ex} | {p} | {m['n']} | {m['falseCounts']} | {m['perSet']:.2f} | {_p(m['perMinute'])} | "
                             f"{m['setsWithCount']} ({m['setsWithCountUB']:.3f}) |")
        L.append("")
    if s["verdict"]:
        L += ["## Gate B 판정 (고정 빌드·새 사람일 때만 유효)", "", "| 종목 | 판정 | 정확 하한 | 과다 상한 | 앞 헛카운트 상한 | 사람별 정확 | 사람 비중 |",
              "|---|---|---|---|---|---|---|"]
        for ex, v in s["verdict"].items():
            if "checks" not in v:
                L.append(f"| {ex} | {v['verdict']} | | | | | |")
                continue
            c = v["checks"]
            L.append(f"| {ex} | {v['verdict']} | " + " | ".join(("✓ " if c[k]["ok"] else "✗ ") + c[k]["detail"]
                                                                for k in ("exactLB", "overUB", "leadUB", "personExact", "personShare")) + " |")
    bad = [r for r in s["rows"] if r["boundary"]["method"] == "none" and r["truth"] and r["condition"] != "negative"]
    L += ["", f"경계를 찾지 못한 세트(앞·뒤 헛카운트 판정 안 함): {len(bad)}"]
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------- 자가 검증

def _synthetic_set(rng, exercise: str, condition: str, planned: int) -> list[dict]:
    """계획 조건을 흉내 낸 세트 로그 프레임(피처 사전). 무릎 종목은 knee_L/R, 컬은 교대 팔꿈치."""
    import random  # noqa: PLC0415
    t_cursor = [0]
    frames = []

    def emit(duration_s: float, fn):
        steps = setlog_captures._cadence(int(duration_s / 0.3) + 2, rng)
        base = t_cursor[0]
        for dt in steps:
            if dt > duration_s * 1000:
                break
            u = dt / 1000.0
            l, r = fn(u)
            l += rng.gauss(0, 0.5)
            r += rng.gauss(0, 0.5)
            f = {f"{kind}_L": l, f"{kind}_R": r, f"{kind}_mean": (l + r) / 2, f"{kind}_minside": min(l, r)}
            frames.append({"t_ms": base + dt, "infer_ms": 60, "visible": 33, "vis": None, "features": f})
        t_cursor[0] = base + int(duration_s * 1000) + 300

    kind = "elbow" if exercise == "덤벨 컬" else "knee"
    rest, bottom = (165.0, 45.0) if kind == "elbow" else (170.0, 90.0)
    still = lambda u: (rest, rest - 1.0)

    def reps(n: int, period: float, hold_before_last: float = 0.0):
        for k in range(n):
            if hold_before_last and k == n - 1:
                emit(hold_before_last, still)
            if kind == "elbow":   # 교대: 짝수 반복은 왼팔, 홀수는 오른팔
                side = k % 2
                emit(period, lambda u, s=side: (rest - (rest - bottom) * math.sin(math.pi * u / period) ** 2 if s == 0 else rest,
                                                rest - (rest - bottom) * math.sin(math.pi * u / period) ** 2 if s == 1 else rest))
            else:
                emit(period, lambda u: (rest - (rest - bottom) * math.sin(math.pi * u / period) ** 2,) * 2)
            emit(0.4, still)

    emit(1.5, lambda u: (rest - 10 * math.sin(u * 3), rest - 12 * math.sin(u * 3)))     # WORK 시작 직후 자리 잡기
    if condition == "negative":
        # 반복 없이 20 s: 걷기(작은 흔들림) + 물건 집기 한 번(반복 크기 움직임 1회) — 시작 확정이 짝을 못 만든다
        emit(8.0, lambda u: (rest - 12 * abs(math.sin(u * 2.5)), rest - 12 * abs(math.cos(u * 2.5))))
        emit(3.0, lambda u: (rest - 70 * math.sin(math.pi * u / 3.0) ** 2,) * 2)
        emit(8.0, still)
        return frames
    if condition == "prep":
        # 준비 동작: 반복 크기 굽힘 두 번(자세 확인·덤벨 들어 올리기) 3.5 s 간격 — 시작 확정 창(8 s) 안에서 짝을 이룬다
        for _ in range(2):
            emit(2.0, lambda u: (rest - 55 * math.sin(math.pi * u / 2.0) ** 2,) * 2)
            emit(1.5, still)
    emit(3.0, still)                                                                        # 시작 표지
    period = {"fast": 1.8, "slow": 6.0, "veryslow": 9.0}.get(condition, 3.0)
    reps(planned, period, 5.0 if condition == "pause" else 0.0)
    emit(3.0, still)                                                                        # 끝 표지
    emit(0.6, still)
    return frames


def self_test(work: Path) -> int:
    import random  # noqa: PLC0415
    import rep_validation_plan  # noqa: PLC0415

    checks = []

    def check(name, ok, detail=""):
        checks.append((name, bool(ok), detail))

    # Clopper-Pearson — 설계 §7 표의 세트 수를 다시 낸다
    need = [sets_needed_for_lower(0.90, k) for k in range(4)]
    check("CP: 정확 하한 ≥ 0.90 에 필요한 세트 (틀림 0·1·2·3) = 29·46·61·76", need == [29, 46, 61, 76], str(need))
    check("CP: 과다 0/149 → 상한 ≤ 0.02, 0/148 는 초과", cp_upper(0, 149) <= 0.02 < cp_upper(0, 148),
          f"{cp_upper(0, 149):.4f} / {cp_upper(0, 148):.4f}")
    check("CP: 0/60 → 상한 4.9%", abs(cp_upper(0, 60) - 0.0487) < 0.0005, f"{cp_upper(0, 60):.4f}")
    check("CP: 앞 헛카운트 세트 상한 ≤ 5% — 0개 59세트, 1개 93세트",
          sets_needed_for_upper(0.05, 0) == 59 and sets_needed_for_upper(0.05, 1) == 93,
          f"{sets_needed_for_upper(0.05, 0)}, {sets_needed_for_upper(0.05, 1)}")
    check("CP: 양 끝 (x=0 하한 0, x=n 상한 1)", cp_lower(0, 10) == 0.0 and cp_upper(10, 10) == 1.0)
    # 경계 게이트는 신호와 같은 출처 — 옛 런지 로그(knee_out_mean)는 0.10, 지금 신호는 등록부 게이트, 모르는 옛 신호는 판정 안 함
    live_now = {"feature": "knee_mean", "minAmp": 35.0}
    gates = [boundary_signal({"loggedSignal": "knee_out_mean"}, live_now), boundary_signal({}, live_now),
             boundary_signal({"loggedSignal": "knee_mean", "loggedMinAmp": 35.0}, live_now),
             boundary_signal({"loggedSignal": "unknown_sig"}, live_now)]
    check("경계 게이트: 옛 신호 0.10 · 지금 신호 35 · 로그 min_amp 우선 · 모르는 신호 None",
          gates == [("knee_out_mean", 0.10), ("knee_mean", 35.0), ("knee_mean", 35.0), ("unknown_sig", None)], str(gates))

    # 합성 파일럿: 계획표 → 조건별 세트 로그(순서대로) → 집계표 채움
    rng = random.Random(11)
    plan = rep_validation_plan.make_plan("pilot", persons=1, seed=5)
    logs_dir = work / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    lines, truth_rows = [], ["set_id,reps_min,reps_max,exercise,form,source,created_at"]
    for k, row in enumerate(plan):
        planned = int(row["planned_reps"])
        frames = _synthetic_set(rng, row["exercise"], row["condition"], planned)
        sid = f"20261001T10{k:02d}00-pilot{k:03d}"
        log = {"set_id": sid, "created_at": f"2026-10-01T10:{k:02d}:30Z", "subject_id": "s-pilot01", "exercise": row["exercise"],
               "note": f"session:{row['exercise_app']} assessment_end_ms=0 ", "mode": "coach", "frames": frames}
        if k == 0:
            log["reps"] = {"count": planned, "invalid": 0, "signal": "knee_mean", "t_ms": [], "min": [], "max": [], "valid": []}
        lines.append(setlog_captures.encode_setlog(log))
        row["tally_reps"] = str(planned)
    # 정답 등급 시험: 한 행은 집계가 비고 edited 라벨만, 한 행은 confirmed 라벨만
    plan[1]["tally_reps"] = ""
    truth_rows.append(f"20261001T100100-pilot001,{plan[1]['planned_reps']},{plan[1]['planned_reps']},{plan[1]['exercise']},,edited,2026-10-01T11:00:00Z")
    plan[2]["tally_reps"] = ""
    truth_rows.append(f"20261001T100200-pilot002,{plan[2]['planned_reps']},{plan[2]['planned_reps']},{plan[2]['exercise']},,confirmed,2026-10-01T11:00:01Z")
    (logs_dir / "sets-20261001.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (logs_dir / "rep_truth.csv").write_text("\n".join(truth_rows) + "\n", encoding="utf-8")
    plan_path = work / "plan_pilot_filled.csv"
    rep_validation_plan.write_csv(plan, plan_path)
    s = score([logs_dir], work / "score", plan_path, [logs_dir], None, None, set(), {"live", "hysteresis"}, 10, "hysteresis", "A")
    (work / "score" / "summary.md").write_text(markdown(s), encoding="utf-8")
    rows = {r["planId"]: r for r in s["rows"]}
    check("짝짓기: 계획 15행 = 로그 15세트, 남는 것 없음", s["sets"] == 15 and not s["unmatchedPlan"] and not s["unmatchedLogs"],
          f"{s['sets']} {s['unmatchedPlan']} {s['unmatchedLogs']}")
    check("정답 등급: tally 13 · edited 1 · confirmed 1", s["truthSources"].get("tally") == 13 and s["truthSources"].get("edited") == 1
          and s["truthSources"].get("confirmed") == 1, str(s["truthSources"]))
    conf = [r for r in s["rows"] if r["truthSource"] == "confirmed"]
    check("confirmed 세트는 독립 등급에 들어가지 않는다", all(r["setId"] not in
          {x["setId"] for x in s["rows"] if x["truthSource"] in ("tally", "plan-negative")} for r in conf) and "confirmed(순환)" in s["tiers"])
    found = [r for r in s["rows"] if r["truth"] and r["boundary"]["method"] != "none"]
    check("경계: 반복 세트 전부에서 실제 반복 구간을 찾는다", len(found) == sum(1 for r in s["rows"] if r["truth"]),
          f"{len(found)}/{sum(1 for r in s['rows'] if r['truth'])}")
    normal = [r for r in s["rows"] if r["condition"] == "normal"]
    check("normal 세트: 새 코어 앞 헛카운트 0 · 정확", all(r["lead"].get("hysteresis") == 0 and r["pred"]["hysteresis"] == r["truth"] for r in normal),
          str([(r["lead"].get("hysteresis"), r["pred"].get("hysteresis"), r["truth"]) for r in normal]))
    prep = [r for r in s["rows"] if r["condition"] == "prep"]
    check("prep 세트: 준비 동작의 반복 크기 굽힘 두 번이 새 코어의 앞 헛카운트로 잡힌다 (§14)",
          all((r["lead"].get("hysteresis") or 0) >= 1 for r in prep), str([r["lead"].get("hysteresis") for r in prep]))
    slow = [r for r in s["rows"] if r["condition"] == "slow"]
    check("slow(6 s/반복) 세트: 새 코어 정확 (8 s 절벽 안쪽)", all(r["pred"]["hysteresis"] == r["truth"] for r in slow),
          str([(r["pred"]["hysteresis"], r["truth"]) for r in slow]))
    check("app 예측: reps 블록이 있는 1세트만", sum("app" in r["pred"] for r in s["rows"]) == 1)
    tier = s["tiers"]["independent"]
    anyex = next(iter(tier.values()))["all"]["hysteresis"]
    check("지표: 정확 하한은 점추정 이하, 과다 상한은 점추정 이상", anyex["exactLB"] <= anyex["exactRate"] and anyex["overUB"] >= anyex["overRate"])
    s_b = score([logs_dir], work / "score_b", plan_path, [logs_dir], None, None, set(), {"live", "hysteresis"}, 10, "hysteresis", "B")
    check("Gate B 판정: 5세트면 '판정 불가(세트 부족)'", all(v["verdict"].startswith("판정 불가") for v in s_b["verdict"].values()),
          str({k: v["verdict"] for k, v in s_b["verdict"].items()}))
    width = max(len(n) for n, _, _ in checks)
    for n, ok, d in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {n.ljust(width)}  {d}")
    failed = sum(not ok for _, ok, _ in checks)
    print(f"\n{len(checks) - failed}/{len(checks)} 통과 — 작업 폴더 {work}")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="*", type=Path)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--plan", type=Path, default=None)
    ap.add_argument("--labels", nargs="*", type=Path, default=None)
    ap.add_argument("--since", default=None)
    ap.add_argument("--until", default=None)
    ap.add_argument("--skip", default="", help="set_id 콤마 목록 — 망친 세트")
    ap.add_argument("--configs", default=None, help="콤마 목록 (기본 live,hysteresis,신호 후보)")
    ap.add_argument("--always", type=int, default=10, help="계획표가 없을 때 항상-N 의 N")
    ap.add_argument("--headline", default=GATE["stat"], help="판정에 쓸 예측 (기본 hysteresis)")
    ap.add_argument("--gate", choices=("A", "B"), default="A")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test(args.out or Path(tempfile.mkdtemp(prefix="score_phone_selftest_")))
    if not args.inputs or args.out is None:
        ap.error("inputs 와 --out 이 필요하다")
    labels = args.labels if args.labels is not None else [p if p.is_dir() else p.parent for p in args.inputs]
    configs = set(args.configs.split(",")) if args.configs else DEFAULT_CONFIGS
    s = score(args.inputs, args.out, args.plan, labels, args.since, args.until, {x for x in args.skip.split(",") if x},
              configs, args.always, args.headline, args.gate)
    (args.out / "summary.json").write_text(json.dumps(s, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    md = markdown(s)
    (args.out / "summary.md").write_text(md, encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
