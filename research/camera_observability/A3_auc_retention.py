# -*- coding: utf-8 -*-
"""A3 — REHAB 정상/오류 분리(사람 안 AUC)가 추론 주기를 늘려도 남는가.

A3_analyze.py 의 ref_stats.csv(30 fps)와 cad_stats.csv(진짜 앱 주기, 위상 0)를 읽어, 뷰별·통계별 사람 안 AUC 를 주기마다 다시 낸다.
오류 효과가 실제로 있는 통계에서 30 fps 대비 AUC 가 얼마나 줄어드는지 = '그 주기로 그 오류를 볼 수 있는가' 의 실용 기준.
출력: outputs/A3/auc_retention.csv
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs" / "A3"
sys.path.insert(0, str(HERE))
from A3_analyze import auc  # noqa: E402

VIEWS = ["rehab17_front", "rehab18_side", "rehab17_half", "rehab18_obl"]


def within_auc(df: pd.DataFrame, sid: str) -> tuple[float, int]:
    wa = ws = 0.0
    persons = 0
    for _, g in df.groupby("person"):
        a, npos, nneg = auc(g.loc[g.correct == 0, sid], g.loc[g.correct == 1, sid])
        if np.isfinite(a) and npos >= 2 and nneg >= 2:
            wa += a * npos * nneg
            ws += npos * nneg
            persons += 1
    return (wa / ws if ws else np.nan), persons


def main() -> int:
    ref = pd.read_csv(OUT / "ref_stats.csv")
    cad = pd.read_csv(OUT / "cad_stats.csv")
    meta = ref[["key", "person", "correct"]]
    cad = cad.merge(meta, on="key")
    stats = [c for c in ref.columns if c[:2] in ("R_", "F_", "S_", "T_")]
    rows = []
    for vw in VIEWS:
        r0 = ref[ref.view == vw]
        for sid in stats:
            a30, p30 = within_auc(r0, sid)
            rec = {"view": vw, "stat": sid, "persons": p30, "auc_f30": a30}
            for I in sorted(cad.interval_ms.unique()):
                c0 = cad[(cad.view == vw) & (cad.interval_ms == I)]
                rec[f"auc_c{I}"] = within_auc(c0, sid)[0]
            rows.append(rec)
    out = pd.DataFrame(rows)
    out.to_csv(OUT / "auc_retention.csv", index=False, encoding="utf-8-sig", float_format="%.4g")
    strong = out[(out.persons >= 3) & ((out.auc_f30 - 0.5).abs() >= 0.3)]
    pd.set_option("display.width", 250)
    print(strong.round(3).to_string(index=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
