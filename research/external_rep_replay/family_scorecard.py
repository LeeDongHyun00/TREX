# -*- coding: utf-8 -*-
"""계열 채점표 — 한 종목의 반복 검사(RepFormSpecs)를 세 근거로 한 번에 재서 표 하나로 낸다(docs/EXERCISE_TIERS.md 의 온보딩 절차).

근거 셋(원칙 #2 — 근거는 AIHub 에 한정하지 않는다):
 1. AIHub 조건 라벨(aihub_captures.py → 재생기 --clip-eval): 클립(반복 하나)마다 새 검사기로 판정한다. 조건 충족 클립의 위반율(참고 오탐),
    위반 클립의 위반율(검출), 원값 AUC(원값이 조건을 가르는가). 스튜디오·연기자·키프레임이고 사람마다 클립이 1~2개라
    본인 기준 검사(FIRST_REPS·SET_LOW)는 판정이 유보된다 — 그 검사는 원값 AUC 만 본다. **1차 거름**이다.
 2. 모집단 연속 영상(MM-Fit·REHAB, run_replay.replay_index): 정상 반복의 오탐·차단 오탐·연속 위반 세트 — **입장 조건(차단 오탐 ≤ 2 %)의 근거**.
    같은 종목 영상이 없으면 가까운 종목(PROXIES)을 **그 종목의 검증된 검사기**로 재생하고 같은 이름의 검사끼리 견준다. 대상 종목의 카운터로
    재생하면 카운트 차이가 섞인다 — 바벨 컬 카운터(두 팔 평균)는 MM-Fit 교대 컬을 거의 못 세고 헛사이클을 세어, 검사 오탐이 아니라
    카운트 오류를 쟀다(2026-09-27). 카운트는 Gate A 의 질문이다.
 3. 폰 지정 오류 세트(--phone 로그 폴더 + --protocol CSV): 블록(정상:5 팔꿈치앞:4 …)마다 기대 검사의 검출과 정상 블록 오탐, 세트 횟수.
    프로토콜 CSV = exercise,order,blocks — order 는 로그에서 그 종목의 몇 번째 세트인가(시각 순, --since 이후). 반복은 순서대로 블록에 붙인다 —
    센 반복 수가 계획과 다르면 그 세트는 '정렬 불확실' 로 표시하고 검출·오탐에서 뺀다.

사용: python family_scorecard.py --exercise "바벨 컬" --out <폴더> [--phone <로그 폴더> --protocol <csv> --since 2026-09-27] [--no-aihub] [--no-pop]
출력: <out>/scorecard.md · scorecard.json. 사용자는 이 표만 보내면 된다 — 원본 로그를 대화로 읽지 않는다(토큰).
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import aihub_captures
import run_replay
import setlog_captures

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
DATASETS = {"mmfit": REPO / "data" / "mmfit_mp_captures", "rehab": REPO / "data" / "rehab_mp_captures"}
ENTRY_FA = 0.02        # 원칙 #7 입장 조건 — 모집단 정상 반복의 차단 오탐
DETECT_MIN = 0.80      # 폰 지정 오류 세트 검출 기준(설계 §6 승격 기준)

# 종목 → 모집단 정상 영상(데이터셋, 그 데이터셋의 종목). 이 종목 영상이 없으면 같은 피처를 쓰는 가까운 종목
PROXIES: dict[str, list[tuple[str, str]]] = {
    "바벨 스쿼트": [("mmfit", "바벨 스쿼트"), ("rehab", "바벨 스쿼트")],
    "덤벨 컬": [("mmfit", "덤벨 컬")],
    "바벨 컬": [("mmfit", "덤벨 컬")],
    "스텝 포워드 다이나믹 런지": [("mmfit", "스텝 포워드 다이나믹 런지"), ("rehab", "스텝 포워드 다이나믹 런지")],
    "바벨 런지": [("mmfit", "스텝 포워드 다이나믹 런지"), ("rehab", "스텝 포워드 다이나믹 런지")],
}

# 검사 이름 → AIHub 조건(클립 라벨). 없으면 AIHub 검출을 재지 않는다. 대응은 근사다 — AUC 가 낮으면 대응이 틀렸거나 못 가르는 것
_CURL = {"상체 숙임": "척추의 중립", "몸통 반동": "척추의 중립",
         **{n: "팔꿈치 위치 고정" for n in ("팔꿈치 뜸", "팔꿈치 옆 벌림", "팔꿈치 높이 상승", "팔꿈치 앞 이탈", "팔꿈치 몸에서 떨어짐",
                                        "팔꿈치 벌어짐", "팔꿈치 앞뒤(옆)")}}
_LUNGE = {"앞무릎 깊이": "앞다리 무릎 각도 90도", "상체 숙임": "상체의 과조한 숙임/젖힘 여부", "어깨 기울기": "척추의 중립"}
CONDITIONS: dict[str, dict[str, str]] = {"덤벨 컬": _CURL, "바벨 컬": _CURL, "스텝 포워드 다이나믹 런지": _LUNGE, "바벨 런지": _LUNGE}

# 폰 프로토콜 블록 이름 → 그 블록에서 걸려야 할 검사. '정상' 은 아무것도 걸리면 안 된다
_CURL_BLOCKS = {"정상": [], "팔꿈치앞": ["팔꿈치 앞 이탈", "팔꿈치 뜸"], "팔꿈치벌림": ["팔꿈치 옆 벌림", "팔꿈치 몸에서 떨어짐", "팔꿈치 벌어짐"],
                "숙임": ["상체 숙임", "몸통 반동"]}
_LUNGE_BLOCKS = {"정상": [], "얕게": ["앞무릎 깊이"], "숙임": ["상체 숙임"], "무릎쏠림": ["무릎 쏠림"], "어깨기울임": ["어깨 기울기"]}
PHONE_BLOCKS: dict[str, dict[str, list[str]]] = {"덤벨 컬": _CURL_BLOCKS, "바벨 컬": _CURL_BLOCKS,
                                                 "스텝 포워드 다이나믹 런지": _LUNGE_BLOCKS, "바벨 런지": _LUNGE_BLOCKS}

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def specs(exercise: str) -> list[dict]:
    """재생기 --checks — 검사 명세(방향·임계·상태·뷰)는 코드(RepFormSpecs)가 정본."""
    r = subprocess.run([str(run_replay.REPLAY_BIN), "--checks", exercise], capture_output=True, check=True)
    return [json.loads(line) for line in r.stdout.decode("utf-8").splitlines() if line.strip()]


def outcome(rep: dict, check_id: str) -> dict | None:
    return next((c for c in rep.get("checks", []) if c["id"] == check_id), None)


def outcome_by_name(rep: dict, name: str) -> dict | None:
    """대리 종목의 같은 이름 검사(검사 id = repform|종목|이름)."""
    return next((c for c in rep.get("checks", []) if c["id"].rsplit("|", 1)[-1] == name), None)


def pct(n: int, d: int) -> str:
    return "—" if d == 0 else f"{n}/{d} ({100.0 * n / d:.1f} %)"


def auc(bad: list[float], ok: list[float], spec: dict) -> float | None:
    """원값이 조건 위반 클립에서 더 '위반 쪽' 인 확률(Mann-Whitney). 양쪽 띠 검사는 절대값으로."""
    if len(bad) < 3 or len(ok) < 3:
        return None
    two_sided = spec["lo"] is not None and spec["hi"] is not None
    sign = -1.0 if (spec["hi"] is None and spec["lo"] is not None) else 1.0
    f = (lambda v: abs(v)) if two_sided else (lambda v: sign * v)
    b, o = [f(v) for v in bad], [f(v) for v in ok]
    wins = sum((x > y) + 0.5 * (x == y) for x in b for y in o)
    return wins / (len(b) * len(o))


# ---------------------------------------------------------------- 1. AIHub
def aihub_rows(exercise: str, work: Path, refresh: bool) -> list[dict]:
    d = work / "aihub_caps" / exercise
    if refresh or not any(d.glob("*.cap")):
        aihub_captures.export([exercise], work / "aihub_caps")
    rows = []
    for cap in sorted(d.glob("*.cap")):
        meta = json.loads((d / f"{cap.stem}.clips.json").read_text(encoding="utf-8"))
        out = work / "aihub_eval" / exercise / f"{cap.stem}.jsonl"
        out.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(run_replay.REPLAY_BIN), "--clip-eval", str(cap), str(out)], check=True, capture_output=True)
        res = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines() if line.strip()]
        if len(res) != len(meta["clips"]):
            raise SystemExit(f"{cap}: 클립 {len(meta['clips'])} 인데 재생기 {len(res)} — 클립 틈 규약이 어긋났다")
        for r, clip in zip(res, meta["clips"]):
            reps = (r.get("repForm") or {}).get("reps") or []
            rows.append({"camera": cap.stem, "clip": clip, "rep": reps[0] if reps else None})
    return rows


def score_aihub(exercise: str, rows: list[dict], sp: list[dict]) -> list[dict]:
    cmap = CONDITIONS.get(exercise, {})
    out = []
    for c in sp:
        cond = cmap.get(c["name"])
        if cond is None:
            out.append({"check": c["name"], "condition": None})
            continue
        n = {True: [0, 0], False: [0, 0]}       # 충족/위반 클립 → [판정, 위반]
        raws = {True: [], False: []}
        abstain = 0
        cams = defaultdict(lambda: {True: [0, 0], False: [0, 0]})
        for r in rows:
            if r["rep"] is None or cond not in r["clip"]["conditions"]:
                continue
            o = outcome(r["rep"], c["id"])
            if o is None:
                continue
            sat = r["clip"]["conditions"][cond]
            if o["raw"] is not None:
                raws[sat].append(o["raw"])
            if o["v"] == "ABSTAIN":
                abstain += 1
                continue
            n[sat][0] += 1
            n[sat][1] += o["v"] == "VIOLATION"
            cams[r["camera"]][sat][0] += 1
            cams[r["camera"]][sat][1] += o["v"] == "VIOLATION"
        out.append({"check": c["name"], "condition": cond, "okJudged": n[True][0], "okViol": n[True][1],
                    "badJudged": n[False][0], "badViol": n[False][1], "abstain": abstain, "auc": auc(raws[False], raws[True], c),
                    "byCamera": {k: {"ok": v[True], "bad": v[False]} for k, v in sorted(cams.items())}})
    return out


# ---------------------------------------------------------------- 2. 모집단
def population(exercise: str, work: Path) -> list[dict]:
    sets_out = []
    for ds, src in PROXIES.get(exercise, []):
        base = DATASETS[ds]
        ix = json.loads((base / "index.json").read_text(encoding="utf-8"))
        sets = [dict(s, capture=str((base / s["capture"]).resolve())) for s in ix["sets"] if s["exercise"] == src]
        if not sets:
            continue
        d = work / f"pop_{ds}_{src}".replace(" ", "_")
        d.mkdir(parents=True, exist_ok=True)
        (d / "index.json").write_text(json.dumps({"source": ix.get("source"), "sets": sets}, ensure_ascii=False), encoding="utf-8")
        index, results, _ = run_replay.replay_index(d, d / "replay", {"live"})
        for s in index["sets"]:
            rf = (results.get(f"{run_replay.set_key(s)}|live") or {}).get("repForm")
            if not rf:
                continue
            windows = s.get("reps")        # REHAB: [[a, b, 올바름]] — 올바른 반복만 정상
            reps = []
            for rep in rf["reps"]:
                if rep.get("not_step"):
                    continue
                if windows is not None:
                    w = next((x for x in windows if x[0] - 1500 <= rep["t_ms"] <= x[1] + 1500), None)
                    if w is None or not w[2]:
                        continue
                reps.append(rep)
            sets_out.append({"dataset": ds, "proxy": src, "reps": reps})
    return sets_out


def score_population(sets: list[dict], sp: list[dict]) -> list[dict]:
    out = []
    for c in sp:
        for ds in sorted({s["dataset"] for s in sets}):
            judged = viol = gate = abstain = sets_any = sets_two = 0
            ss = [s for s in sets if s["dataset"] == ds]
            for s in ss:
                prev = False
                any_v = two = False
                for rep in s["reps"]:
                    o = outcome_by_name(rep, c["name"])
                    if o is None:
                        continue
                    if o["v"] == "ABSTAIN":
                        abstain += 1
                        prev = False
                        continue
                    judged += 1
                    bad = o["v"] == "VIOLATION"
                    viol += bad
                    gate += bad and bool(o.get("gate"))
                    any_v |= bad
                    two |= bad and prev
                    prev = bad
                sets_any += any_v
                sets_two += two
            out.append({"check": c["name"], "dataset": ds, "proxy": ss[0]["proxy"] if ss else None, "sets": len(ss),
                        "judged": judged, "viol": viol, "gate": gate, "abstain": abstain, "setsAny": sets_any, "setsTwo": sets_two})
    return out


# ---------------------------------------------------------------- 3. 폰 지정 오류 세트
def parse_blocks(text: str) -> list[tuple[str, int]]:
    out = []
    for tok in text.split():
        name, _, n = tok.partition(":")
        out.append((name, int(n)))
    return out


def phone(exercise: str, work: Path, logs: Path, protocol: Path, since: str | None) -> list[dict]:
    rows = [r for r in csv.DictReader(protocol.open(encoding="utf-8-sig")) if r["exercise"].strip() == exercise]
    if not rows:
        return []
    capdir = work / "phone_caps"
    index = setlog_captures.build([logs], capdir, [], since=since, exercises={exercise}, session_only=True)
    sets = sorted((s for s in index["sets"] if s["exercise"] == exercise), key=lambda s: s.get("createdAt") or "")
    use = []
    for row in rows:
        k = int(row["order"]) - 1
        if not 0 <= k < len(sets):
            print(f"프로토콜 {exercise} {row['order']}번째 세트가 로그에 없다(세트 {len(sets)}개)", file=sys.stderr)
            continue
        s = sets[k]
        cap = s.get("landmarkCapture") or s["capture"]    # 좌표가 있으면 지금 엔진으로 피처를 다시 계산한다
        use.append((row, dict(s, capture=str((capdir / cap).resolve()))))
    if not use:
        return []
    d = work / f"phone_{exercise}".replace(" ", "_")
    d.mkdir(parents=True, exist_ok=True)
    (d / "index.json").write_text(json.dumps({"source": "phone", "sets": [s for _, s in use]}, ensure_ascii=False), encoding="utf-8")
    index2, results, _ = run_replay.replay_index(d, d / "replay", {"live"})
    out = []
    for (row, _), s in zip(use, index2["sets"]):
        rf = (results.get(f"{run_replay.set_key(s)}|live") or {}).get("repForm") or {"reps": []}
        reps = [r for r in rf["reps"] if not r.get("not_step")]
        blocks = parse_blocks(row["blocks"])
        planned = sum(n for _, n in blocks)
        labels = [name for name, n in blocks for _ in range(n)]
        out.append({"order": int(row["order"]), "setId": s.get("setId"), "blocks": row["blocks"], "planned": planned,
                    "counted": len(reps), "aligned": len(reps) == planned,
                    "reps": [{"label": lab, "rep": rep} for lab, rep in zip(labels, reps)] if len(reps) == planned else []})
    return out


def score_phone(exercise: str, sets: list[dict], sp: list[dict]) -> list[dict]:
    expect = PHONE_BLOCKS.get(exercise, {})
    out = []
    for c in sp:
        n_ok = v_ok = n_exp = v_exp = 0
        for s in sets:
            for r in s["reps"]:
                o = outcome(r["rep"], c["id"])
                if o is None or o["v"] == "ABSTAIN":
                    continue
                bad = o["v"] == "VIOLATION"
                if r["label"] == "정상":
                    n_ok += 1
                    v_ok += bad
                elif c["name"] in expect.get(r["label"], []):
                    n_exp += 1
                    v_exp += bad
        out.append({"check": c["name"], "normalJudged": n_ok, "normalViol": v_ok, "expectedJudged": n_exp, "expectedViol": v_exp})
    return out


# ---------------------------------------------------------------- 표
def suggestion(c: dict, pop: list[dict], ph: dict | None) -> tuple[str, str, str]:
    """(입장, 폰 검출, 제안). 차단 검사는 차단 오탐, 코칭 전용·beta 는 위반 오탐으로 입장을 본다."""
    rows = [p for p in pop if p["check"] == c["name"] and p["judged"] > 0]
    if rows:
        key = "gate" if c["gates"] and c["status"] == "SHIP" else "viol"
        worst = max(p[key] / p["judged"] for p in rows)
        entry = ("✓" if worst <= ENTRY_FA else "✗") + f" {100 * worst:.1f} %"
    else:
        entry = "— (모집단 영상 없음)"
    det = "—"
    if ph and ph["expectedJudged"] > 0:
        rate = ph["expectedViol"] / ph["expectedJudged"]
        det = ("✓" if rate >= DETECT_MIN else "✗") + f" {100 * rate:.0f} %"
    # 제안일 뿐이다 — 이미 폰으로 확정한 검사(§62a~§63)를 자동으로 내리지 않는다. 결정과 근거는 spec 에 남긴다
    if entry.startswith("✗"):
        sug = "재검토(오탐 > 2 %)"
    elif det.startswith("✗"):
        sug = "재검토(검출 < 80 %)"
    elif entry.startswith("✓") and det.startswith("✓"):
        sug = "ship 가능"
    elif entry.startswith("✓"):
        sug = "폰 검증 대기"
    else:
        sug = "근거 부족"
    return entry, det, sug


def markdown(exercise: str, sp: list[dict], ai: list[dict], pop: list[dict], ph_sets: list[dict], ph: list[dict]) -> str:
    L = [f"# 계열 채점표 — {exercise}", "",
         "근거: ① AIHub 조건 라벨(클립=반복, 1차 거름) ② 모집단 정상 영상(입장 조건 ≤ 2 %) ③ 폰 지정 오류 세트(검출 ≥ 80 %). "
         "판정은 앱과 같은 RepFormEvaluator(재생기) 결과다.", "",
         "## 검사", "", "| 검사 | 상태 | 차단 | 뷰 | 피처·위상 | 띠 |", "|---|---|---|---|---|---|"]
    for c in sp:
        band = " · ".join(x for x in (f"< {c['lo']}" if c["lo"] is not None else "", f"> {c['hi']}" if c["hi"] is not None else "",
                                       f"차단 > {c['gateHi']}" if c["gateHi"] is not None else "") if x)
        L.append(f"| {c['name']} | {c['status']} | {'예' if c['gates'] else '코칭만'} | {'·'.join(c['views'])} | "
                 f"`{c['feature']}` {c['phase']}/{c['stat']}/{c['ref']} | {band} |")
    if ai:
        L += ["", "## ① AIHub 조건 라벨 (클립 = 반복 하나 · 참고)", "",
              "본인 기준 검사는 클립 하나로 기준이 서지 않아 판정이 유보된다 — 원값 AUC(0.5 = 못 가름)만 본다.", "",
              "| 검사 | AIHub 조건 | 충족 클립 위반(참고 오탐) | 위반 클립 위반(검출) | 원값 AUC | 유보 |", "|---|---|---|---|---|---|"]
        for a in ai:
            if a["condition"] is None:
                L.append(f"| {a['check']} | (대응 조건 없음) | — | — | — | — |")
                continue
            au = "—" if a["auc"] is None else f"{a['auc']:.2f}"
            L.append(f"| {a['check']} | {a['condition']} | {pct(a['okViol'], a['okJudged'])} | {pct(a['badViol'], a['badJudged'])} | {au} | {a['abstain']} |")
    if pop:
        L += ["", "## ② 모집단 정상 반복 (입장 조건: 차단 오탐 ≤ 2 %)", "",
              "대리 종목이면 그 종목의 검증된 검사기로 재생해 같은 이름의 검사끼리 견준다(카운트 차이를 섞지 않으려고).", "",
              "| 검사 | 데이터(대리 종목) | 세트 | 위반(오탐) | 차단 오탐 | 유보 | 위반 있는 세트 | 연속 위반 세트 |", "|---|---|---|---|---|---|---|---|"]
        for p in pop:
            L.append(f"| {p['check']} | {p['dataset']}({p['proxy']}) | {p['sets']} | {pct(p['viol'], p['judged'])} | {pct(p['gate'], p['judged'])} | "
                     f"{p['abstain']} | {p['setsAny']} | {p['setsTwo']} |")
    if ph_sets:
        L += ["", "## ③ 폰 지정 오류 세트", "", "| 순서 | 블록 | 계획 | 센 반복 | 정렬 |", "|---|---|---|---|---|"]
        for s in ph_sets:
            L.append(f"| {s['order']} | {s['blocks']} | {s['planned']} | {s['counted']} | {'✓' if s['aligned'] else '✗ 불확실(뺌)'} |")
        L += ["", "| 검사 | 정상 블록 위반(오탐) | 기대 블록 위반(검출) |", "|---|---|---|"]
        for p in ph:
            L.append(f"| {p['check']} | {pct(p['normalViol'], p['normalJudged'])} | {pct(p['expectedViol'], p['expectedJudged'])} |")
    L += ["", "## 판정 요약", "", "| 검사 | 지금 상태 | 입장(모집단) | 폰 검출 | 제안 |", "|---|---|---|---|---|"]
    phd = {p["check"]: p for p in ph}
    for c in sp:
        entry, det, sug = suggestion(c, pop, phd.get(c["name"]))
        L.append(f"| {c['name']} | {c['status']} | {entry} | {det} | {sug} |")
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--exercise", required=True, help="AIHub 종목 이름(규칙·세트 로그의 exercise)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--phone", type=Path, help="폰 세트 로그 폴더(pull_logs.py 결과)")
    ap.add_argument("--protocol", type=Path, help="폰 지정 오류 세트 프로토콜 CSV(exercise,order,blocks)")
    ap.add_argument("--since", help="이 시각 이후 로그만(ISO)")
    ap.add_argument("--no-aihub", action="store_true")
    ap.add_argument("--no-pop", action="store_true")
    ap.add_argument("--refresh", action="store_true", help="AIHub 캡처를 다시 만든다")
    a = ap.parse_args()
    run_replay.require_fresh_replay() if hasattr(run_replay, "require_fresh_replay") else None
    work = a.out
    work.mkdir(parents=True, exist_ok=True)
    sp = specs(a.exercise)
    if not sp:
        raise SystemExit(f"{a.exercise}: 반복 검사가 없다(RepFormSpecs.byExercise)")
    ai = [] if a.no_aihub else score_aihub(a.exercise, aihub_rows(a.exercise, work, a.refresh), sp)
    pop = [] if a.no_pop else score_population(population(a.exercise, work), sp)
    ph_sets = phone(a.exercise, work, a.phone, a.protocol, a.since) if a.phone and a.protocol else []
    ph = score_phone(a.exercise, ph_sets, sp) if ph_sets else []
    md = markdown(a.exercise, sp, ai, pop, ph_sets, ph)
    (work / "scorecard.md").write_text(md, encoding="utf-8")
    (work / "scorecard.json").write_text(json.dumps({"exercise": a.exercise, "checks": sp, "aihub": ai, "population": pop,
                                                     "phoneSets": [{k: v for k, v in s.items() if k != "reps"} for s in ph_sets],
                                                     "phone": ph}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
