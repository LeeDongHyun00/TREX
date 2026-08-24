#!/usr/bin/env python
"""opposite_guards.csv 를 rules_mp_v0.json 에 주입 — 반대 방향(예: 스쿼트 무릎 '바깥' 벌어짐) 가드.

가드는 같은 피처의 반대측 경계다: 위반 if (기존 op) 또는 (guard_op 기준 초과).
스키마: rule.opposite_guard = {op, threshold, desc, method, validated}
검증되지 않은 방향(라벨 없음)은 validated=false — 오탐률만 통제(정상 분포 밖), 검출률은 미보증.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
RULES = HERE / "rules" / "rules_mp_v0.json"


def main():
    g = pd.read_csv(HERE / "outputs" / "opposite_guards.csv")
    doc = json.loads(RULES.read_text(encoding="utf-8"))
    by_id = {r.rule_id: r for r in g.itertuples()}
    n = 0
    for r in doc["rules"]:
        row = by_id.get(r["id"])
        if row is None:
            r.pop("opposite_guard", None)
            continue
        if row.feature != r["feature"]:
            print(f"[warn] 피처 불일치로 스킵: {r['id']} ({row.feature} != {r['feature']})")
            continue
        r["opposite_guard"] = dict(op=row.guard_op, threshold=float(row.guard_threshold), desc=row.opposite_desc,
                                   method=row.method, n_norm=int(row.n_norm), validated=bool(row.validated))
        n += 1
    note = f"opposite_guard {n}개 주입(양방향 검출, bidirectional_analysis.py) — 반대측은 정상 분포 밖 보수 경계, validated=false 는 검출률 미보증"
    doc["revision_note"] = (doc.get("revision_note") or "") + " | " + note
    RULES.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[done] {note}")


if __name__ == "__main__":
    main()
