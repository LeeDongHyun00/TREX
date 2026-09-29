# -*- coding: utf-8 -*-
"""반복 검사의 모집단 기준(사전값) 생성 — spec §90. 앱 파일 `posture/RepFormPriorTable.kt` 를 쓴다(손으로 고치지 않는다).

본인 기준을 쓰는 검사(시작 자세·세트 최소·처음 N회·세트 하한)는 기준이 서기 전의 첫 반복들을 판정하지 못했고(유보 = 통과),
첫 반복이 틀리면 그 틀림이 기준이 됐다. 모집단 정상 반복에서 사람(세트)마다 **수렴한 본인 기준**을 모아 그 분포를 사전값으로 둔다:
  ref       = 사람들 기준의 중앙값          → 첫 반복의 잠정 기준(개인 반복이 들어올수록 개인 쪽으로 옮겨 간다)
  refSpread = 사람 간 퍼짐(1.4826 × MAD)   → 잠정 판정의 여유(2 × 퍼짐 ÷ √(1 + 개인 반복 수) — 처음엔 넓게, 쌓일수록 좁게)
  refLo/Hi  = 기준이 들 수 있는 범위(최소·최대 ± 0.5 퍼짐) → 처음부터 틀린 기준을 이 끝에서 자른다(한쪽만)
  rawLo/Hi  = 정상 반복 원값 범위(0.5~99.5 %) → 이 안의 반복만 본인 기준 모음에 들어간다
데이터: 앱과 같은 검사기로 재생한 MM-Fit·REHAB 정상 반복(`family_scorecard.population`, REHAB 은 '올바름' 반복만). 뷰마다 따로,
세트가 MIN_SETS 보다 적은 뷰는 사전값을 두지 않는다(그 뷰는 종전처럼 기준이 설 때까지 유보). 바벨 변형은 대리 종목의 값을 쓴다(같은 검사).

사용: python rep_priors.py --out <작업 폴더> [--write]   (--write 없으면 표만 출력)
"""
from __future__ import annotations

import argparse
import datetime
import json
import statistics as st
import sys
from pathlib import Path

import family_scorecard as fs

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT_KT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture" / "RepFormPriorTable.kt"
BASE = ["바벨 스쿼트", "덤벨 컬", "스텝 포워드 다이나믹 런지"]
# 같은 검사를 쓰는 변형 — 대리 종목의 사전값을 그 종목의 검사 id 로 복사한다
VARIANTS = {"덤벨 컬": ["바벨 컬"], "스텝 포워드 다이나믹 런지": ["바벨 런지"]}
REL = {"START_DELTA", "START_RATIO", "SET_MIN_DELTA", "FIRST_REPS_DELTA", "SET_LOW_DELTA"}
MIN_SETS = 8
# 거울 뷰 — 사선·옆·후방 사선의 좌우. 가까운 팔·어깨선 요로 정의한 피처는 좌우가 대칭이라, 한쪽 세트가 모자라면 둘을 합쳐 둘 다에 쓴다
# (MM-Fit 덤벨 컬은 D 가 7세트뿐 — 권장 촬영 방향이 D 다)
MIRROR = {"B": "D", "D": "B", "SIDE_B": "SIDE_D", "SIDE_D": "SIDE_B", "A": "E", "E": "A"}
# 좌우 대칭으로 정의된 피처만 거울 뷰를 합친다 — 어깨 높이차(sh_level2d = 오른 − 왼)는 사선 방향에 따라 원근 부호가 뒤집혀 합치면 틀린다
MIRROR_SAFE = {"elbow_fwd2d_near", "elbow_lat2d_near", "torso_pitch", "torso_incl"}

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def quantile(v: list[float], q: float) -> float:
    s = sorted(v)
    if len(s) == 1:
        return s[0]
    x = q * (len(s) - 1)
    lo = int(x)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (x - lo)


def priors_for(exercise: str, work: Path) -> dict[str, dict[str, dict]]:
    sp = fs.specs(exercise)
    sets = fs.population(exercise, work)
    out: dict[str, dict[str, dict]] = {}
    for c in sp:
        if c["ref"] not in REL:
            continue
        for view in c["views"]:
            p = stats(c, sets, {view})
            if p is None and view in MIRROR and MIRROR[view] in c["views"] and c["feature"] in MIRROR_SAFE:
                p = stats(c, sets, {view, MIRROR[view]})
                if p is not None:
                    p["pooled"] = f"{view}+{MIRROR[view]}"
            if p is not None:
                out.setdefault(c["name"], {})[view] = p
    return out


def stats(c: dict, sets: list[dict], views: set[str]) -> dict | None:
    if True:
        if True:
            finals, raws = [], []
            for s in sets:
                refs = []
                for rep in s["reps"]:
                    if rep.get("view") not in views:
                        continue
                    o = fs.outcome_by_name(rep, c["name"])
                    if o is None:
                        continue
                    if o.get("raw") is not None:
                        raws.append(float(o["raw"]))
                    if o["v"] != "ABSTAIN" and o.get("ref") is not None and not o.get("warm"):
                        refs.append(float(o["ref"]))
                if refs:
                    finals.append(refs[-1])       # 세트 끝에 수렴한 본인 기준
            if len(finals) < MIN_SETS or len(raws) < 3 * MIN_SETS:
                return None
            m = st.median(finals)
            mad = st.median([abs(x - m) for x in finals])
            spread = max(1.4826 * mad, 1e-4)
            n = len(raws)
            # 기준 범위는 관측 최소·최대를 중앙값 ± 4 퍼짐 안으로 자른다 — 정면 컬 월드 몸통각처럼 한 세트의 튐(87°)이 범위를 통째로 넓히지 않게
            lo = max(min(finals), m - 4 * spread) - 0.5 * spread
            hi = min(max(finals), m + 4 * spread) + 0.5 * spread
            return {
                "ref": m, "refSpread": spread, "refLo": lo, "refHi": hi,
                "rawLo": quantile(raws, 0.005) if n >= 200 else min(raws), "rawHi": quantile(raws, 0.995) if n >= 200 else max(raws),
                "sets": len(finals), "reps": n,
            }


def kotlin(table: dict[str, dict[str, dict]], sources: str) -> str:
    def f(x: float) -> str:
        return f"{x:.4f}f"
    lines = [
        "package com.example.trex_kotlin.posture",
        "",
        "/**",
        " * 생성 파일 — `research/external_rep_replay/rep_priors.py --write` 가 쓴다. 손으로 고치지 않는다(spec §90).",
        " * 반복 검사의 모집단 사전값: 앱과 같은 검사기로 재생한 정상 반복에서 사람(세트)마다 수렴한 본인 기준의 분포(뷰별).",
        f" * 데이터: {sources}. 생성 {datetime.date.today().isoformat()}.",
        " */",
        "internal object RepFormPriorTable {",
        "    val table: Map<String, Map<String, RepFormPrior>> = mapOf(",
    ]
    for cid in sorted(table):
        views = table[cid]
        lines.append(f'        "{cid}" to mapOf(')
        for v in sorted(views):
            p = views[v]
            lines.append(f'            "{v}" to RepFormPrior({f(p["ref"])}, {f(p["refSpread"])}, {f(p["refLo"])}, {f(p["refHi"])}, '
                         f'{f(p["rawLo"])}, {f(p["rawHi"])}, {p["sets"]}),')
        lines.append("        ),")
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()
    table: dict[str, dict[str, dict]] = {}
    for ex in BASE:
        pri = priors_for(ex, a.out / ex.replace(" ", "_"))
        for name, views in pri.items():
            for target in [ex] + VARIANTS.get(ex, []):
                table[f"repform|{target}|{name}"] = views
            for v, p in sorted(views.items()):
                print(f"{ex} · {name} @{v}{' (' + p['pooled'] + ' 합침)' if p.get('pooled') else ''}: 기준 {p['ref']:.3f} ± {p['refSpread']:.3f} (범위 {p['refLo']:.3f}~{p['refHi']:.3f}) · "
                      f"원값 {p['rawLo']:.3f}~{p['rawHi']:.3f} · 세트 {p['sets']} 반복 {p['reps']}")
    (a.out / "priors.json").write_text(json.dumps(table, ensure_ascii=False, indent=1), encoding="utf-8")
    if a.write:
        text = kotlin(table, "MM-Fit(스쿼트·런지·덤벨 컬) + REHAB24-6('올바름', 스쿼트·런지)")
        OUT_KT.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
        print(f"wrote {OUT_KT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
