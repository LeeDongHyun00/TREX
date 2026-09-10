#!/usr/bin/env python
"""실기기 검증 채점 — 회수한 세트 로그를 device_validation_plan.csv 와 짝지어 추정기·게이팅·재적합 임계값을 대조한다 (spec §33).

짝짓기: 같은 AIHub 종목의 로그를 시각 순서로 계획의 같은 종목 행과 차례로 맞춘다 (프로토콜을 순서대로 찍었다는 전제).
  --since  YYYY-MM-DD  이 날짜(UTC) 이후 로그만 (기본: 오늘)
  --course min|full|any
  --skip   set_id 콤마 목록 — 망친 세트 제외
출력: outputs/DEVICE_VALIDATION_RESULT.md + 표준출력
"""
from __future__ import annotations

import argparse
import csv
import glob
import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
HEMI = {"C": "F", "B": "F", "D": "F", "SIDE_B": "S", "SIDE_D": "S", "A": "R", "E": "R", "R": "R"}
SIDE = {"B": "b", "SIDE_B": "b", "A": "b", "D": "d", "SIDE_D": "d", "E": "d", "C": "-", "R": "-"}


def load_logs(logs_dir: Path, since: str, skip: set[str]) -> list[dict]:
    rows = []
    for f in sorted(glob.glob(str(logs_dir / "sets-*.jsonl"))):
        for line in open(f, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if d.get("created_at", "") < since or d.get("set_id") in skip:
                continue
            if not str(d.get("note", "")).startswith("session:"):
                continue
            rows.append(d)
    rows.sort(key=lambda d: d["created_at"])
    return rows


def names_of(s: str) -> list[str]:
    """계획의 규칙 표기("조건[하위유형](beta)(기준선 필요)") → 로그 rule_id 의 조건 부분."""
    out = []
    for x in s.split(";"):
        x = x.strip()
        for tag in ("(beta)", "(기준선 필요)", "(뷰 무관)"):
            x = x.replace(tag, "")
        if x:
            out.append(x)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", default=str(OUT / "device_validation_plan.csv"))
    ap.add_argument("--logs", default=str(OUT / "logs"))
    ap.add_argument("--since", default=date.today().isoformat())
    ap.add_argument("--course", default="any")
    ap.add_argument("--skip", default="")
    a = ap.parse_args()
    plan = list(csv.DictReader(open(a.plan, encoding="utf-8-sig")))
    if a.course != "any":
        plan = [p for p in plan if p["course"] == a.course]
    logs = load_logs(Path(a.logs), a.since, set(x for x in a.skip.split(",") if x))
    by_ex = defaultdict(list)
    for d in logs:
        by_ex[d["exercise"]].append(d)
    used = defaultdict(int)

    L = [f"# 실기기 검증 결과 — {a.since} 이후 로그 {len(logs)}세트 vs 계획 {len(plan)}세트\n",
         "| # | 종목 | 계획 배치 | 로그 view | 등급 | 반구 | 좌우 | 프레임 | 판정/유보 일치 | 대상 규칙 | 비고 |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    stats = dict(paired=0, cls_ok=0, hemi_ok=0, side_ok=0, gate_ok=0, gate_n=0, viol_ok=0, viol_n=0, no_view=0)
    for p in plan:
        ex = p["aihub_name"]
        k = used[ex]
        logs_ex = by_ex.get(ex, [])
        if k >= len(logs_ex):
            L.append(f"| {p['order']} | {p['app_name']} | {p['placement']} | (로그 없음) | | | | | | {p['target_rule'] or '-'} | 아직 안 찍음 |")
            continue
        d = logs_ex[k]
        used[ex] += 1
        stats["paired"] += 1
        view = d.get("view")
        exp = p["expected_class"]
        note = []
        if view is None:
            stats["no_view"] += 1
            cls_s = hemi_s = side_s = "-"
            got = "(없음 — 구버전 APK?)"
        else:
            got_cls = view.get("class", "UNKNOWN")
            got = f"{got_cls} ({view.get('yaw_deg', float('nan')):.0f}°, R {view.get('r', 0):.2f}, {view.get('frames', 0)}f)"
            cls_ok = got_cls == exp
            hemi_ok = HEMI.get(got_cls) == HEMI.get(exp)
            side_ok = SIDE.get(got_cls) == SIDE.get(exp)
            stats["cls_ok"] += cls_ok; stats["hemi_ok"] += hemi_ok; stats["side_ok"] += side_ok
            cls_s, hemi_s, side_s = ("✓" if cls_ok else "✗"), ("✓" if hemi_ok else "✗"), ("✓" if side_ok else "✗")
            if got_cls == "UNKNOWN":
                note.append("방향 불일관(R 낮음)")
        # 게이팅: 로그 results 의 verdict 와 기대 판정/유보 목록
        verdict = {r["rule_id"].split("|", 1)[1]: r["verdict"] for r in d.get("results", [])}
        exp_j, exp_a = set(names_of(p["expected_judged"])), set(names_of(p["expected_abstained"]))
        judged = {n for n, v in verdict.items() if v in ("OK", "VIOLATION")}
        abst = {n for n, v in verdict.items() if v == "ABSTAIN"}
        gate_ok = exp_j <= judged and exp_a <= abst if verdict else None
        if gate_ok is not None:
            stats["gate_n"] += 1; stats["gate_ok"] += bool(gate_ok)
        if verdict and not gate_ok:
            wrong = [f"{n}:{verdict.get(n, '없음')}" for n in exp_j if n not in judged] + [f"{n}:{verdict.get(n, '없음')}" for n in exp_a if n not in abst]
            note.append("게이팅 불일치 " + ", ".join(wrong))
        tgt = "-"
        if p["target_rule"]:
            tn = p["target_rule"].split("|", 1)[1]
            v = verdict.get(tn, "없음")
            stats["viol_n"] += 1; stats["viol_ok"] += (v == "VIOLATION")
            tgt = f"{tn}: {v} {'✓' if v == 'VIOLATION' else '✗'}"
        frames = len(d.get("frames", []))
        L.append(f"| {p['order']} | {p['app_name']} | {p['placement']} | {got} | {cls_s} | {hemi_s} | {side_s} | {frames} | "
                 f"{'✓' if gate_ok else ('✗' if gate_ok is not None else '-')} | {tgt} | {'; '.join(note)} |")
    extra = [d for ex, ds in by_ex.items() for d in ds[used[ex]:]]
    if extra:
        L += ["", "계획에 없는 로그 (짝 안 됨): " + ", ".join(f"{d['exercise']}@{d['created_at']}" for d in extra)]
    n = stats["paired"] - stats["no_view"]
    L += ["", "## 요약\n",
          f"- 짝지은 세트 {stats['paired']} / 계획 {len(plan)} · view 없는 로그 {stats['no_view']}",
          f"- 추정기: 등급 일치 {stats['cls_ok']}/{n} · 전/후/옆 반구 {stats['hemi_ok']}/{n} · 좌우 {stats['side_ok']}/{n}" if n else "- 추정기: 대조할 view 없음",
          f"- 게이팅: 기대 판정/유보와 일치 {stats['gate_ok']}/{stats['gate_n']}",
          f"- 의도적 위반 세트: 대상 규칙 VIOLATION {stats['viol_ok']}/{stats['viol_n']}"]
    (OUT / "DEVICE_VALIDATION_RESULT.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
