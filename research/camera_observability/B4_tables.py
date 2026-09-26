#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""B4 표 — outputs/B4/*.csv 를 모아 outputs/B4/tables.md 를 만든다 (보고서 B4_REPORT.md 의 근거 표 전체)."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs" / "B4"


def f(v, nd=3):
    if v is None or (isinstance(v, float) and not np.isfinite(v)):
        return "—"
    return f"{v:.{nd}f}"


def md(df: pd.DataFrame, cols: list[str], nd: int = 3, rename: dict | None = None) -> str:
    rename = rename or {}
    hdr = [rename.get(c, c) for c in cols]
    lines = ["| " + " | ".join(hdr) + " |", "|" + "---|" * len(cols)]
    for r in df[cols].itertuples(index=False):
        lines.append("| " + " | ".join(f(v, nd) if isinstance(v, float) else str(v) for v in r) + " |")
    return "\n".join(lines)


def main():
    T = []
    P = T.append
    P("# B4 표 (자동 생성) — 덤벨 컬 정면(C) 2D 단서\n")
    # 1. 정상 분포
    P("## 1. 정상 분포 (반복 단위 = 두 팔 중 큰 값; p5/p50/p95/p99; 그룹 = MM-Fit 사람 11 · AIHub 수행자 44)\n")
    d = pd.concat([pd.read_csv(OUT / "mmfit_dist.csv"), pd.read_csv(OUT / "aihub_dist.csv")], ignore_index=True)
    d = d[(d.unit == "rep_max") & d.source.isin(["mmfit_cap300", "aihub_C:elbow_shrug_spine_true", "aihub_C:elbow_true", "aihub_C:elbow_false(팔꿈치 내밀기)"])]
    keys = ["lat_s", "lat_c", "lat_d", "lat_max", "lat_dmax", "rise_s", "rise_c", "rise_d", "rise_max", "rise_dmax", "wrist_h_s", "wrist_h_c", "wrist_h_max", "wrist_h_min", "wrist_h_d",
            "fa_ua_c", "fa_ua_min", "ua_len_c"]
    d = d[d.cand.isin(keys)]
    d["cand"] = pd.Categorical(d.cand, keys)
    d = d.sort_values(["source", "cand"])
    P(md(d, ["source", "cand", "n", "p5", "p50", "p95", "p99", "n_groups", "group_p95_min", "group_p95_med", "group_p95_max"]))
    # 2. 띠별 정상 초과율
    P("\n## 2. 띠별 정상 초과율 (반복 단위; group_max = 초과율이 가장 높은 사람/수행자와 그 값)\n")
    b = pd.concat([pd.read_csv(OUT / "mmfit_bands.csv"), pd.read_csv(OUT / "aihub_bands.csv")], ignore_index=True)
    b = b[b.source.isin(["mmfit_cap300", "aihub_C:elbow_shrug_spine_true", "aihub_C:elbow_true", "aihub_C:elbow_false(팔꿈치 내밀기)"])]
    piv = b.pivot_table(index="band", columns="source", values="rate", aggfunc="first")
    gm = b[b.source == "mmfit_cap300"].set_index("band")[["group_max", "group_argmax"]]
    piv = piv.join(gm)
    order = [x for x in b[b.source == "mmfit_cap300"].band.tolist()]
    piv = piv.reindex(order).reset_index()
    P(md(piv, ["band", "mmfit_cap300", "aihub_C:elbow_shrug_spine_true", "aihub_C:elbow_true", "aihub_C:elbow_false(팔꿈치 내밀기)", "group_max", "group_argmax"],
         rename={"mmfit_cap300": "MM-Fit 598회", "aihub_C:elbow_shrug_spine_true": "AIHub 정상 170", "aihub_C:elbow_true": "AIHub 팔꿈치 고정 677",
                 "aihub_C:elbow_false(팔꿈치 내밀기)": "AIHub 내밀기 679", "group_max": "MM-Fit 사람 최대", "group_argmax": "누구"}))
    # 3. AUC
    P("\n## 3. AIHub '팔꿈치 위치 고정' 위반(앞으로 내밀기)이 정면 2D 에 새는 정도 — AUC (원값 / 세션 중심화), 오탐 5 % 고정 검출률\n")
    a = pd.read_csv(OUT / "aihub_auc.csv")
    a = a[a.arms.isin(["max", "-"])].head(45)
    P(md(a, ["cand", "arms", "n", "auc", "auc_within", "direction", "tpr_at_fpr05", "normal_p50", "error_p50"]))
    P("\n### '으쓱' 라벨(어깨 개입 대용)을 정면 2D 반동 단서로 — 팔꿈치 고정·척추 중립 충족 340클립(으쓱 170/170)\n")
    s = pd.read_csv(OUT / "aihub_shrug_auc.csv")
    P(md(s, ["cand", "n", "auc", "auc_within", "direction", "tpr_at_fpr05", "normal_p50", "normal_p95", "error_p50", "tpr_rise_dmax≥0.15", "fpr_rise_dmax≥0.15"]))
    P("\n### 다른 조건 위반이 반동·벌림 단서를 움직이는 정도 (팔꿈치 고정 충족 안에서 조건별 True/False)\n")
    c = pd.read_csv(OUT / "aihub_cond_effect.csv")
    c = c[c.cand.isin(["rise_dmax", "lat_c"])]
    P(md(c, ["condition", "value", "n", "cand", "p50", "p95", "rate_rise_d≥0.10", "rate_rise_d≥0.15", "rate_flare_0.15_0.30"]))
    # 4. ROM
    P("\n## 4. 가동 범위 — MM-Fit 59세트 598회 게이트 유지율 (분할 = 정답 가정의 상한; 사람별 최소)\n")
    g = pd.read_csv(OUT / "mmfit_rom_grid.csv")
    P(md(g, ["gate", "recall", "set_all_kept", "kept", "subject_min", "subject_argmin"]))
    P("\n### 2D 대리 ↔ 진실 (MM-Fit 데이터셋 3D 는 영상 기반 3D 추정이라 약한 진실; AIHub 는 GT 3D)\n")
    P(md(pd.read_csv(OUT / "mmfit_rom_fidelity.csv"), ["truth", "cand", "n", "rho", "rho_within", "subject_rho_med", "subject_rho_min", "p5", "p50", "p95"]))
    rr = pd.read_csv(OUT / "aihub_rom.csv")
    rr = rr[(rr.arms.isin(["mean", "-"]))]
    P("\n(AIHub C 뷰, 정상 = 팔꿈치 고정·긴장 유지 충족 337 / 전체 1356)\n")
    P(md(rr, ["normset", "cand", "truth", "n", "rho", "rho_within", "p5", "p50", "p95", "between_sd", "within_sd"]))
    # 5. 폰
    P("\n## 5. 폰 세트 — 반복 단위 값과 판정 (truth13: 1~4 정상 · 5~8 옆 벌림 · 9~11 반동 · 12~13 짧게; free14: 정답 없음)\n")
    ph = pd.read_csv(OUT / "phone_gates.csv")
    P(md(ph, ["set", "rep", "truth", "lat_c", "lat_d", "lat_max", "lat_dmax", "rise_c", "rise_d", "rise_max", "rise_dmax", "wrist_h_max", "wrist_h_min",
              "ratio_world_L", "ratio_world_R", "ratio_wrist_L", "ratio_wrist_R"], nd=2))
    flags = [c_ for c_ in ph.columns if c_.startswith(("flare(", "swing(", "wrist_above", "rom_", "bottom_fail"))]
    P("\n### 판정 플래그 (True = 띠 초과 / 게이트 탈락)\n")
    P(md(ph, ["set", "rep", "truth"] + flags))
    P("\n### 시작(개인 기준) 값\n")
    P(md(pd.read_csv(OUT / "phone_start.csv"), ["set", "arm", "n_start", "t_lo", "t_hi", "lat", "rise", "wrist_h", "pre_lat", "pre_rise", "pre_wrist_h"]))
    (OUT / "tables.md").write_text("\n".join(T), encoding="utf-8")
    print("[done]", OUT / "tables.md")


if __name__ == "__main__":
    main()
