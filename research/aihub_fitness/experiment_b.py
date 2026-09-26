#!/usr/bin/env python
"""실험 B: GT 3D 각도 피처 → 조건 라벨 예측력 = '각도 규칙 엔진'의 상한 성능.

포즈 추정 오차가 전혀 없는 GT 3D 스켈레톤에서 뽑은 각도/기하 피처만으로
AIHub 조건 라벨(척추의 중립, 발과 무릎의 방향 일치 …)을 얼마나 맞출 수 있는가.
여기서 안 되는 조건은 포즈 추정기가 완벽해도 각도 규칙으로는 못 잡는 조건이다.

평가 단위: 클립. 누수 방지: 수행자(Z코드) 기준 GroupKFold.
  rule_auc_cv : 학습폴드에서 단변량 AUC 최대 피처 1개 선택 → 테스트폴드 AUC ("각도 하나 + 임계값" 규칙)
  lr_auc_cv   : 표준화 + 로지스틱 회귀 (선형 결합 규칙)
  gbm_auc_cv  : HistGradientBoosting (비선형 상한)

출력: outputs/experiment_b_results.csv, outputs/experiment_b_summary.md
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from joblib import Parallel, delayed
from scipy.stats import rankdata
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from threadpoolctl import threadpool_limits

from features import build_or_load_features

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")


def univariate_auc(X: np.ndarray, y: np.ndarray) -> np.ndarray:
    """각 컬럼의 AUC (Mann-Whitney). X에 NaN 없어야 함."""
    R = rankdata(X, axis=0)
    n_pos = int(y.sum())
    n_neg = len(y) - n_pos
    return (R[y == 1].sum(axis=0) - n_pos * (n_pos + 1) / 2.0) / float(n_pos * n_neg)


def evaluate_pair(exercise: str, condition: str, X: np.ndarray, y: np.ndarray, groups: np.ndarray,
                  feat_names: list[str], n_splits: int, seed: int = 0) -> dict:
    n = len(y)
    n_pos = int(y.sum())
    n_groups = len(np.unique(groups))
    row = dict(exercise=exercise, condition=condition, n=n, pos_rate=n_pos / n, n_performers=n_groups)
    if min(n_pos, n - n_pos) < 15 or n_groups < 2:
        row.update(status="skipped(small)", rule_auc_cv=np.nan, lr_auc_cv=np.nan, gbm_auc_cv=np.nan)
        return row
    k = min(n_splits, n_groups)
    gkf = GroupKFold(n_splits=k)
    rule_aucs, lr_aucs, gbm_aucs, chosen = [], [], [], []
    with threadpool_limits(limits=2):
        for tr, te in gkf.split(X, y, groups):
            Xtr, Xte, ytr, yte = X[tr], X[te], y[tr], y[te]
            if len(np.unique(yte)) < 2 or len(np.unique(ytr)) < 2:
                continue
            # 학습 폴드에서 고유값(비-NaN) 2개 미만인 컬럼 제거 (HistGB 비닝 오류 방지 + 무의미 피처)
            keep = np.array([np.unique(c[~np.isnan(c)]).size >= 2 for c in Xtr.T])
            keep_idx = np.flatnonzero(keep)
            Xtr, Xte = Xtr[:, keep], Xte[:, keep]
            med = np.nanmedian(Xtr, axis=0)
            med = np.where(np.isnan(med), 0.0, med)
            Xtr_i = np.where(np.isnan(Xtr), med, Xtr)
            Xte_i = np.where(np.isnan(Xte), med, Xte)
            # 단변량 규칙
            auc_tr = univariate_auc(Xtr_i, ytr)
            best = int(np.nanargmax(np.abs(auc_tr - 0.5)))
            sign = 1.0 if auc_tr[best] >= 0.5 else -1.0
            rule_aucs.append(roc_auc_score(yte, sign * Xte_i[:, best]))
            chosen.append(feat_names[keep_idx[best]])
            # 로지스틱
            lr = make_pipeline(StandardScaler(), LogisticRegression(C=0.3, max_iter=3000))
            lr.fit(Xtr_i, ytr)
            lr_aucs.append(roc_auc_score(yte, lr.predict_proba(Xte_i)[:, 1]))
            # GBM (NaN 네이티브)
            # 소표본에서도 분할이 가능하도록 leaf 최소 표본을 학습 크기에 비례시킴
            msl = int(np.clip(0.02 * len(ytr), 5, 20))
            gbm = HistGradientBoostingClassifier(max_iter=200, learning_rate=0.06, max_leaf_nodes=15,
                                                 min_samples_leaf=msl, l2_regularization=1.0,
                                                 early_stopping=False, random_state=seed)
            gbm.fit(Xtr, ytr)
            gbm_aucs.append(roc_auc_score(yte, gbm.predict_proba(Xte)[:, 1]))
    # 해석용: 전체 데이터 단변량 상위 3개 (in-sample)
    med_all = np.nanmedian(X, axis=0)
    med_all = np.where(np.isnan(med_all), 0.0, med_all)
    auc_all = univariate_auc(np.where(np.isnan(X), med_all, X), y)
    order = np.argsort(-np.abs(auc_all - 0.5))[:3]
    top = [f"{feat_names[i]}({'+' if auc_all[i] >= 0.5 else '-'}{max(auc_all[i], 1 - auc_all[i]):.2f})" for i in order]
    row.update(
        status="ok",
        n_folds=len(gbm_aucs),
        rule_auc_cv=float(np.mean(rule_aucs)) if rule_aucs else np.nan,
        rule_auc_std=float(np.std(rule_aucs)) if rule_aucs else np.nan,
        rule_feature_mode=(max(set(chosen), key=chosen.count) if chosen else ""),
        lr_auc_cv=float(np.mean(lr_aucs)) if lr_aucs else np.nan,
        lr_auc_std=float(np.std(lr_aucs)) if lr_aucs else np.nan,
        gbm_auc_cv=float(np.mean(gbm_aucs)) if gbm_aucs else np.nan,
        gbm_auc_std=float(np.std(gbm_aucs)) if gbm_aucs else np.nan,
        top_univariate=" | ".join(top),
    )
    return row


def bucket(a: float) -> str:
    if np.isnan(a):
        return "n/a"
    if a >= 0.9:
        return "≥0.90"
    if a >= 0.8:
        return "0.80–0.90"
    if a >= 0.7:
        return "0.70–0.80"
    return "<0.70"


def write_summary(res: pd.DataFrame, out: Path, meta: dict):
    ok_all = res[res["status"] == "ok"].copy()
    bad_ex = sorted(ok_all.loc[ok_all["qc_flag"] == "3D불량", "exercise"].unique().tolist())
    ok = ok_all[ok_all["qc_flag"] != "3D불량"].copy()
    lines = []
    lines.append("# 실험 B — GT 3D 각도 피처 → 조건 라벨 예측력 (룰 엔진 상한)\n")
    lines.append(f"- 클립 {meta['n_clips']:,}개(QC 후), 종목 {meta['n_exercises']}개, (종목×조건) 쌍 {len(res)}개 중 평가 {len(ok_all)}개 (소표본 스킵 {len(res)-len(ok_all)})")
    lines.append(f"- 피처 {meta['n_features']}개 (3점 관절각, 척추 4점 폴리라인, 신체좌표계 기하, 체형 정규화 거리, 시계열 상관) — 클립당 mean/min/max/std/range")
    lines.append(f"- CV: 수행자(Z코드) GroupKFold(≤5) — 같은 사람이 학습/테스트에 동시에 들어가지 않음")
    lines.append(f"- 3D GT QC: 뼈 길이 검사 실패 프레임 마스킹, 양호 프레임 <8 클립 제외. 제외 클립이 50%를 넘는 종목은 '3D불량'으로 표시하고 **1·2절 집계에서 제외** (3절에는 포함)")
    if bad_ex:
        lines.append(f"- 3D불량 종목({len(bad_ex)}): {', '.join(bad_ex)}")
    lines.append(f"- 소요 {meta['elapsed_sec']:.0f}s\n")

    lines.append("## 1. 전체 분포 (GroupKFold AUC, 3D불량 종목 제외)\n")
    lines.append("| 구간 | 단일각도 규칙(rule) | 로지스틱(lr) | GBM |")
    lines.append("|---|---|---|---|")
    for b in ["≥0.90", "0.80–0.90", "0.70–0.80", "<0.70"]:
        r = (ok["rule_auc_cv"].map(bucket) == b).sum()
        l = (ok["lr_auc_cv"].map(bucket) == b).sum()
        g = (ok["gbm_auc_cv"].map(bucket) == b).sum()
        lines.append(f"| {b} | {r} | {l} | {g} |")
    lines.append("")
    lines.append(f"중앙값 — rule {ok['rule_auc_cv'].median():.3f} | lr {ok['lr_auc_cv'].median():.3f} | gbm {ok['gbm_auc_cv'].median():.3f}\n")

    lines.append("## 2. 조건 유형별 (여러 종목에 반복 등장하는 조건의 종목 간 중앙값)\n")
    agg = (ok.groupby("condition").agg(n_ex=("exercise", "nunique"), n=("n", "sum"),
                                        rule=("rule_auc_cv", "median"), lr=("lr_auc_cv", "median"), gbm=("gbm_auc_cv", "median"),
                                        gbm_min=("gbm_auc_cv", "min"))
           .sort_values(["n_ex", "gbm"], ascending=[False, False]).reset_index())
    lines.append("| 조건 | 종목 수 | 클립 | rule | lr | gbm | gbm 최저 |")
    lines.append("|---|---|---|---|---|---|---|")
    for _, r in agg.iterrows():
        lines.append(f"| {r['condition']} | {r['n_ex']} | {int(r['n'])} | {r['rule']:.3f} | {r['lr']:.3f} | {r['gbm']:.3f} | {r['gbm_min']:.3f} |")
    lines.append("")

    lines.append("## 3. 종목 × 조건 전체 (3D불량 종목 포함, QC 컬럼 참고)\n")
    lines.append("| 종목 | QC | 조건 | n | 양성률 | 수행자 | rule | lr | gbm (±) | 최다 선택 단일 피처 | 단변량 상위3 (in-sample) |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for _, r in ok_all.sort_values(["exercise", "gbm_auc_cv"], ascending=[True, False]).iterrows():
        qc = f"{r['qc_flag']} 불량{r['qc_bad_frame_pct']:.0f}%/제외{r['qc_drop_pct']:.0f}%" if not np.isnan(r.get("qc_bad_frame_pct", np.nan)) else ""
        lines.append(f"| {r['exercise']} | {qc} | {r['condition']} | {int(r['n'])} | {r['pos_rate']:.2f} | {int(r['n_performers'])} | "
                     f"{r['rule_auc_cv']:.3f} | {r['lr_auc_cv']:.3f} | {r['gbm_auc_cv']:.3f} (±{r['gbm_auc_std']:.3f}) | "
                     f"{r['rule_feature_mode']} | {r['top_univariate']} |")
    lines.append("")

    lines.append("## 4. 가장 약한 조건 15개 (GBM 기준, 3D불량 종목 제외) — 각도 규칙으로 못 잡는 것들\n")
    lines.append("| 종목 | 조건 | n | rule | lr | gbm | 단변량 상위3 |")
    lines.append("|---|---|---|---|---|---|---|")
    for _, r in ok.nsmallest(15, "gbm_auc_cv").iterrows():
        lines.append(f"| {r['exercise']} | {r['condition']} | {int(r['n'])} | {r['rule_auc_cv']:.3f} | {r['lr_auc_cv']:.3f} | {r['gbm_auc_cv']:.3f} | {r['top_univariate']} |")
    lines.append("")

    skipped = res[res["status"] != "ok"]
    if len(skipped):
        lines.append("## 5. 스킵 (소표본/단일 수행자)\n")
        for _, r in skipped.iterrows():
            lines.append(f"- {r['exercise']} / {r['condition']}: n={int(r['n'])}, 양성률={r['pos_rate']:.2f}, 수행자={int(r['n_performers'])}")
        lines.append("")
    (out / "experiment_b_summary.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "outputs"))
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--splits", type=int, default=5)
    ap.add_argument("--recompute-features", action="store_true")
    ap.add_argument("--exercise", default="", help="특정 종목만 (디버그)")
    args = ap.parse_args()
    out = Path(args.out)
    t0 = time.time()

    clips = pd.read_parquet(out / "clips.parquet")
    conds = pd.read_parquet(out / "conditions.parquet")
    feats = build_or_load_features(out, recompute=args.recompute_features)
    feat_names = list(feats.columns)
    print(f"[load] clips {len(clips)} | conditions {len(conds)} | features {feats.shape} ({time.time()-t0:.0f}s)", flush=True)

    clips = clips.set_index("clip_id")
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))
    clips = clips.loc[clips.index.intersection(feats.index)]

    tasks = []
    for ex_name, g in clips.groupby("exercise"):
        if args.exercise and ex_name != args.exercise:
            continue
        ids = g.index
        sub = conds[conds["clip_id"].isin(ids)]
        piv = sub.pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
        piv = piv.reindex(ids)
        cond_order = (sub.groupby("condition")["cond_idx"].median().sort_values().index.tolist())
        for cname in cond_order:
            yv = piv[cname]
            mask = yv.notna().to_numpy()
            if mask.sum() == 0:
                continue
            X = feats.loc[ids[mask]].to_numpy(dtype=np.float64)
            y = yv[mask].to_numpy().astype(int)
            grp = g.loc[ids[mask], "group"].to_numpy()
            tasks.append((ex_name, cname, X, y, grp))
    print(f"[tasks] {len(tasks)} (종목×조건) 쌍 평가 시작, jobs={args.jobs}", flush=True)

    rows = Parallel(n_jobs=args.jobs, verbose=5)(
        delayed(evaluate_pair)(ex, cn, X, y, grp, feat_names, args.splits) for ex, cn, X, y, grp in tasks
    )
    res = pd.DataFrame(rows)
    # QC 주석: 종목별 3D 불량 프레임 비율 / 제외 클립 비율 (qc_kp3d.py 출력이 있을 때)
    qc_path = out / "qc_per_clip.csv"
    if qc_path.exists():
        qc = pd.read_csv(qc_path)
        qex = qc.groupby("exercise").agg(qc_bad_frame_pct=("bad_frac", lambda s: 100 * s.mean()),
                                         qc_drop_pct=("drop_clip", lambda s: 100 * s.mean())).reset_index()
        res = res.merge(qex, on="exercise", how="left")
    else:
        res["qc_bad_frame_pct"] = np.nan
        res["qc_drop_pct"] = np.nan
    res["qc_flag"] = np.where(res["qc_drop_pct"] > 50, "3D불량", "")
    res.to_csv(out / "experiment_b_results.csv", index=False, encoding="utf-8-sig")
    meta = dict(n_clips=int(len(clips)), n_exercises=int(clips["exercise"].nunique()), n_features=len(feat_names),
                elapsed_sec=time.time() - t0)
    write_summary(res, out, meta)
    ok = res[res["status"] == "ok"]
    print(f"\n[done] {len(ok)}/{len(res)} 쌍 평가 | 중앙값 AUC rule {ok['rule_auc_cv'].median():.3f} / lr {ok['lr_auc_cv'].median():.3f} / gbm {ok['gbm_auc_cv'].median():.3f} | {meta['elapsed_sec']:.0f}s")
    print(f"       결과: {out/'experiment_b_results.csv'} , {out/'experiment_b_summary.md'}")


if __name__ == "__main__":
    main()
