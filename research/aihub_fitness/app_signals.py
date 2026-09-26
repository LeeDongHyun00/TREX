# -*- coding: utf-8 -*-
"""앱 렙 신호 등록부(RepCounter.kt 의 RepSignals) 읽기 — 연구 스크립트의 미러가 앱과 어긋나지 않게 대조한다.

`rep_validity_thresholds.py`(APP_SIGNALS)와 `rule_coupling_audit.py`(REP_SIGNAL)는 "앱 RepSignals 와 반드시 일치" 를 전제로 한다.
앱의 신호를 바꾸면(예: 런지 knee_out_mean → knee_mean, docs/REP_ENGINE_DESIGN.md §4.1) 미러가 옛 신호로 남아, 다시 돌릴 때
앱과 다른 단위의 기준(런지의 옛 ROM −0.0076 처럼)을 조용히 다시 만든다. `check_mirror` 는 미러와 앱 등록부의 **공통 종목**
신호가 같은지 보고 다르면 예외를 낸다 — 두 스크립트의 main 첫 줄에서 부른다.

    python app_signals.py      # 두 미러를 데이터 없이 대조해 결과를 찍는다(표준 라이브러리만)
"""
from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REP_COUNTER = HERE.parents[1] / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture" / "RepCounter.kt"


def app_rep_signals(path: Path = REP_COUNTER) -> dict[str, str]:
    """RepSignals.base() 의 종목 → 카운트 신호(feature). `put("종목", RepSignal("피처"` 와 `for (ex in listOf(...)) put(ex, RepSignal("피처"`."""
    text = path.read_text(encoding="utf-8")
    body = text[text.index("private fun base()"):]
    body = body[:body.index("\n    }\n")]
    body = re.sub(r"//[^\n]*", "", body)          # 주석 안의 예시를 신호로 읽지 않는다
    out: dict[str, str] = {}
    for ex, feat in re.findall(r'put\("([^"]+)",\s*RepSignal\("([^"]+)"', body):
        out[ex] = feat
    for names, feat in re.findall(r'for \(ex in listOf\(([^)]*)\)\)\s*\{?\s*put\(ex,\s*RepSignal\("([^"]+)"', body, flags=re.S):
        for ex in re.findall(r'"([^"]+)"', names):
            out[ex] = feat
    return out


def check_mirror(mirror: dict[str, str], name: str, path: Path = REP_COUNTER) -> None:
    """미러와 앱 등록부의 공통 종목에서 신호가 하나라도 다르면 ValueError. 앱에 없는 종목(연구 전용)은 대조하지 않는다."""
    app = app_rep_signals(path)
    diff = {ex: (sig, app[ex]) for ex, sig in mirror.items() if ex in app and app[ex] != sig}
    if diff:
        lines = ", ".join(f"{ex}: 미러 {a} ≠ 앱 {b}" for ex, (a, b) in sorted(diff.items()))
        raise ValueError(f"{name} 가 앱 RepSignals 와 다르다 — {lines}. 미러를 고치거나, 의도라면 이유를 적고 대조에서 뺀다.")


def _mirror_from_source(path: Path, var: str) -> dict[str, str]:
    """스크립트를 import 하지 않고(데이터 경로·무거운 의존 없이) 모듈 수준 dict 리터럴만 읽는다."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == var for t in node.targets):
            return ast.literal_eval(node.value)
    raise KeyError(f"{path.name}: {var} 없음")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    app = app_rep_signals()
    print(f"앱 RepSignals: {len(app)}종목 (런지 = {app.get('스텝 포워드 다이나믹 런지')})")
    status = 0
    for script, var in (("rep_validity_thresholds.py", "APP_SIGNALS"), ("rule_coupling_audit.py", "REP_SIGNAL")):
        mirror = _mirror_from_source(HERE / script, var)
        try:
            check_mirror(mirror, f"{script}:{var}")
            only = sorted(set(mirror) - set(app))
            print(f"PASS  {script}:{var} — 공통 {len(set(mirror) & set(app))}종목 일치" + (f" (앱에 없는 연구 전용 {only})" if only else ""))
        except ValueError as e:
            status = 1
            print(f"FAIL  {e}")
    raise SystemExit(status)
