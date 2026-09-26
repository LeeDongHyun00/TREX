#!/usr/bin/env python
"""실험 D-lite: '척추의 중립' 라벨에 대해 척추 관련 피처(Neck-Back-Waist-Hip 폴리라인)가 얼마나 판별력이 있는가.

실험 B가 "최고 피처"만 보여준다면, 여기서는 척추 전용 피처 vs 비척추 프록시(목-어깨 간격, 상체 기울기 등)를
종목별로 나란히 비교한다 (수행자 GroupKFold 단변량 AUC).
출력: outputs/spine_check.md
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold

from experiment_b import univariate_auc

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

TARGET = "척추의 중립"
SPINE_PREFIX = ("spine_",)
PROXY_FEATS = ["shoulder_neck_gap", "torso_incl", "torso_pitch", "neck_angle", "shoulder_fwd", "sh_over_hip_fwd", "head_pitch", "face_vs_torso"]


def cv_best_auc(X, y, groups, cand_idx, k=5):
    """후보 컬럼 집합 안에서 학습폴드 최적 1개를 고르고 테스트폴드 AUC (수행자 그룹 CV)."""
    gkf = GroupKFold(n_splits=min(k, len(np.unique(groups))))
    aucs, picks = [], []
    for tr, te in gkf.split(X, y, groups):
        Xtr, Xte = X[tr][:, cand_idx], X[te][:, cand_idx]
        med = np.nanmedian(Xtr, axis=0)
        med = np.where(np.isnan(med), 0.0, med)
        Xtr = np.where(np.isnan(Xtr), med, Xtr)
        Xte = np.where(np.isnan(Xte), med, Xte)
        ok = np.array([np.unique(c).size >= 2 for c in Xtr.T])
        if not ok.any() or len(np.unique(y[te])) < 2:
            continue
        a = univariate_auc(Xtr, y[tr])
        a = np.where(ok, a, 0.5)
        b = int(np.argmax(np.abs(a - 0.5)))
        s = 1.0 if a[b] >= 0.5 else -1.0
        aucs.append(roc_auc_score(y[te], s * Xte[:, b]))
        picks.append(cand_idx[b])
    return (float(np.mean(aucs)) if aucs else np.nan), (max(set(picks), key=picks.count) if picks else -1)


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / "outputs"
    clips = pd.read_parquet(out / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(out / "conditions.parquet")
    feats = pd.read_parquet(out / "features.parquet")
    names = list(feats.columns)
    spine_idx = np.array([i for i, n in enumerate(names) if n.startswith(SPINE_PREFIX)])
    proxy_idx = np.array([i for i, n in enumerate(names) if any(n.startswith(p + "__") for p in PROXY_FEATS)])
    all_idx = np.arange(len(names))
    sub = conds[conds["condition"] == TARGET]
    rows = []
    for ex_name, g in sub.groupby("exercise"):
        ids = [c for c in g["clip_id"] if c in feats.index]
        if len(ids) < 60:
            continue
        X = feats.loc[ids].to_numpy(dtype=np.float64)
        y = g.set_index("clip_id").loc[ids, "value"].to_numpy().astype(int)
        grp = clips.loc[ids, "performer"].to_numpy()
        a_sp, p_sp = cv_best_auc(X, y, grp, spine_idx)
        a_px, p_px = cv_best_auc(X, y, grp, proxy_idx)
        a_all, p_all = cv_best_auc(X, y, grp, all_idx)
        rows.append(dict(exercise=ex_name, n=len(ids), n_perf=len(np.unique(grp)),
                         spine_auc=a_sp, spine_feat=names[p_sp] if p_sp >= 0 else "",
                         proxy_auc=a_px, proxy_feat=names[p_px] if p_px >= 0 else "",
                         any_auc=a_all, any_feat=names[p_all] if p_all >= 0 else ""))
    df = pd.DataFrame(rows).sort_values("spine_auc", ascending=False)
    lines = [f"# 실험 D-lite — '{TARGET}' 라벨 vs 척추 폴리라인 피처 (수행자 GroupKFold, 단일 피처 규칙 AUC)\n",
             "- spine_*: Neck–Back–Waist–HipMid 4점에서 계산한 각도/현 편차 (전용 척추 피처)",
             "- proxy: 목-어깨 간격, 상체 기울기/피치, 목 각도, 어깨 말림, 머리 피치 (척추 비전용 프록시)",
             "- any: 전체 피처 중 최적 1개\n",
             "| 종목 | n | 수행자 | spine AUC | spine 최적 피처 | proxy AUC | proxy 최적 피처 | any AUC | any 최적 피처 |",
             "|---|---|---|---|---|---|---|---|---|"]
    for _, r in df.iterrows():
        lines.append(f"| {r['exercise']} | {r['n']} | {r['n_perf']} | {r['spine_auc']:.3f} | {r['spine_feat']} | {r['proxy_auc']:.3f} | {r['proxy_feat']} | {r['any_auc']:.3f} | {r['any_feat']} |")
    lines.append("")
    lines.append(f"중앙값 — spine {df['spine_auc'].median():.3f} | proxy {df['proxy_auc'].median():.3f} | any {df['any_auc'].median():.3f}")
    (out / "spine_check.md").write_text("\n".join(lines), encoding="utf-8")
    df.to_csv(out / "spine_check.csv", index=False, encoding="utf-8-sig")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
