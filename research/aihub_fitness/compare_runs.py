#!/usr/bin/env python
"""두 실험 B 실행 결과 비교 (예: QC1 vs QC2).

사용: python compare_runs.py outputs/experiment_b_results_qc1.csv outputs/experiment_b_results.csv [라벨A] [라벨B]
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def main():
    a_path, b_path = Path(sys.argv[1]), Path(sys.argv[2])
    la = sys.argv[3] if len(sys.argv) > 3 else "A"
    lb = sys.argv[4] if len(sys.argv) > 4 else "B"
    a = pd.read_csv(a_path)
    b = pd.read_csv(b_path)
    key = ["exercise", "condition"]
    cols = ["n", "rule_auc_cv", "lr_auc_cv", "gbm_auc_cv", "rule_feature_mode", "qc_flag"]
    m = a[key + cols].merge(b[key + cols], on=key, suffixes=(f"_{la}", f"_{lb}"), how="outer")
    ok = m[(m[f"qc_flag_{lb}"] != "3D불량") & m[f"gbm_auc_cv_{la}"].notna() & m[f"gbm_auc_cv_{lb}"].notna()].copy()
    for c in ["rule_auc_cv", "lr_auc_cv", "gbm_auc_cv"]:
        ok[f"d_{c}"] = ok[f"{c}_{lb}"] - ok[f"{c}_{la}"]
    print(f"=== {la} → {lb} (3D양호 종목, 공통 {len(ok)}쌍) ===")
    for c in ["rule_auc_cv", "lr_auc_cv", "gbm_auc_cv"]:
        print(f"{c:12s} 중앙값 {ok[f'{c}_{la}'].median():.3f} → {ok[f'{c}_{lb}'].median():.3f} | Δ 평균 {ok[f'd_{c}'].mean():+.4f}, |Δ|>0.02 쌍 {(ok[f'd_{c}'].abs()>0.02).sum()}, 최대 |Δ| {ok[f'd_{c}'].abs().max():.3f}")
    print(f"n 변화 합계: {int(ok[f'n_{lb}'].sum() - ok[f'n_{la}'].sum()):+d} 클립")
    print(f"≥0.90 (gbm): {(ok[f'gbm_auc_cv_{la}']>=0.9).sum()} → {(ok[f'gbm_auc_cv_{lb}']>=0.9).sum()} | rule≥0.85: {(ok[f'rule_auc_cv_{la}']>=0.85).sum()} → {(ok[f'rule_auc_cv_{lb}']>=0.85).sum()}")
    chg = ok[ok[f"rule_feature_mode_{la}"] != ok[f"rule_feature_mode_{lb}"]]
    print(f"최다 선택 단일 피처가 바뀐 쌍: {len(chg)}")
    print("\n--- GBM AUC 변화 큰 순 10 ---")
    top = ok.reindex(ok["d_gbm_auc_cv"].abs().sort_values(ascending=False).index).head(10)
    print(top[key + [f"n_{la}", f"n_{lb}", f"gbm_auc_cv_{la}", f"gbm_auc_cv_{lb}", "d_gbm_auc_cv", f"rule_feature_mode_{la}", f"rule_feature_mode_{lb}"]].round(3).to_string(index=False))


if __name__ == "__main__":
    main()
