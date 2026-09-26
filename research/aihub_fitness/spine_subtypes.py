#!/usr/bin/env python
"""'척추의 중립' 라벨의 하위유형을 description 에서 파생.

AIHub 라벨의 '척추의 중립=false'는 종목마다 다른 편차로 연기됐다 (description 확인):
  flexion      척추 말린상태 (스쿼트·데드·로우·컬·OHP, 스티프데드 '어깨 앞으로 말린')
  lateral      옆으로 갸우뚱 / 오른쪽·왼쪽으로 기울고 (굿모닝·런지류·사이드런지·스티프데드)
  extension    과도하게 뒤로 (스티프데드)
  lumbar_swing 허리 반동 (라잉 트라이셉스)
  lumbar_sag   허리 힘빼고 (푸시업·니푸쉬업)
  forward_lean 앞으로 숙이고 (스탠딩 니업·스탠딩 사이드 크런치)
  cervical     왼쪽/오른쪽/하늘/바닥 보고 — 시선 조건이 없는 종목(런지류)에서 경추 편차를 척추 비중립으로 코딩한 경우
               (런지 미특정 클립의 89~100%가 해당, 중립 클립은 4.6%만 해당 → 라벨 관행으로 확인)
  unspecified  척추=false 인데 description 에 척추 문구 없음 (소수)
  neutral      척추=true

출력: outputs/spine_subtype.parquet (clip_id, exercise, spine_neutral, subtype, matched), outputs/spine_subtype_report.md
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# (하위유형, 정규식) — 앞에 있는 것이 우선
SUBTYPE_RULES = [
    ("lateral", r"갸우뚱|옆으로|(오른쪽|왼쪽)\s*으로\s*기울|기울고|기울어"),
    ("flexion", r"말린|말리고|말림"),
    ("extension", r"과도하게\s*뒤로"),
    ("lumbar_swing", r"허리\s*반동"),
    ("lumbar_sag", r"허리\s*힘\s*[빼배]"),
    ("forward_lean", r"앞으로\s*숙이"),
]
SPINE_COND_RE = re.compile(r"척추")
GAZE_RE = re.compile(r"(?:왼쪽|오른쪽|하늘|바닥)\s*보(?:고|며)")
GAZE_COND_RE = re.compile(r"시선|고개")


def classify(desc: str, exercise_has_gaze_condition: bool = True) -> tuple[str, str]:
    d = " ".join(str(desc).split())
    for name, pat in SUBTYPE_RULES:
        m = re.search(pat, d)
        if m:
            return name, m.group(0)
    if not exercise_has_gaze_condition:
        m = GAZE_RE.search(d)
        if m:
            return "cervical", m.group(0)
    return "unspecified", ""


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / "outputs"
    clips = pd.read_parquet(out / "clips.parquet")
    conds = pd.read_parquet(out / "conditions.parquet")
    sp = conds[conds["condition"].str.contains(SPINE_COND_RE)][["clip_id", "exercise", "condition", "value"]].drop_duplicates("clip_id")
    df = sp.merge(clips[["clip_id", "description", "type_key", "performer"]], on="clip_id", how="left")
    df["spine_neutral"] = df["value"].astype(bool)
    gaze_ex = set(conds[conds["condition"].str.contains(GAZE_COND_RE)]["exercise"].unique())
    sub, matched = [], []
    for neutral, desc, ex in zip(df["spine_neutral"], df["description"], df["exercise"]):
        if neutral:
            sub.append("neutral"); matched.append("")
        else:
            s, m = classify(desc, exercise_has_gaze_condition=(ex in gaze_ex))
            sub.append(s); matched.append(m)
    df["subtype"] = sub
    df["matched"] = matched
    df[["clip_id", "exercise", "condition", "spine_neutral", "subtype", "matched", "type_key", "performer"]].to_parquet(out / "spine_subtype.parquet", index=False)

    # 리포트
    tab = df.pivot_table(index="exercise", columns="subtype", values="clip_id", aggfunc="count", fill_value=0)
    order = ["neutral", "flexion", "lateral", "extension", "lumbar_swing", "lumbar_sag", "forward_lean", "cervical", "unspecified"]
    for c in order:
        if c not in tab.columns:
            tab[c] = 0
    tab = tab[order]
    tab["n"] = tab.sum(axis=1)
    tab = tab.sort_values("n", ascending=False)
    lines = ["# '척추의 중립' 하위유형 (description 기반 파생)\n",
             f"- 척추 조건 보유 클립 {len(df):,}개 / 종목 {df.exercise.nunique()}개",
             f"- 비중립 {int((~df.spine_neutral).sum()):,}개 중 하위유형 특정 {int(((~df.spine_neutral) & (df.subtype != 'unspecified')).sum()):,}개, 미특정 {int((df.subtype == 'unspecified').sum()):,}개\n",
             "| 종목 | neutral | flexion | lateral | extension | lumbar_swing | lumbar_sag | forward_lean | cervical | unspecified | 계 |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    for ex, r in tab.iterrows():
        lines.append(f"| {ex} | " + " | ".join(str(int(r[c])) for c in tab.columns) + " |")
    lines.append("")
    uns = df[df.subtype == "unspecified"]
    if len(uns):
        lines.append("## 미특정(unspecified) description 예시 (종목별 상위 3)\n")
        for ex, g in uns.groupby("exercise"):
            vc = g["description"].str.replace(r"\s+", " ", regex=True).value_counts().head(3)
            lines.append(f"- **{ex}** ({len(g)}): " + " / ".join(f"'{d[:40]}'×{n}" for d, n in vc.items()))
    (out / "spine_subtype_report.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
