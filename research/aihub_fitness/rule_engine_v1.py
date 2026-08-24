#!/usr/bin/env python
"""룰엔진 v1 탐색 — (A) 2-피처 규칙(깊이 2 결정트리 = AND/OR 임계값 조합) (B) 개인 기준선 오프셋.

배경: v0 단일 규칙 대비 GBM 이 크게 앞서는 조건(예: 니업 시선 정면 0.70 vs 0.97)은 피처 2개 조합이 필요하고,
체형 차이는 "인구 임계값"이 아니라 "본인 기준선 대비 편차"로 봐야 한다는 것이 초기 논의의 결론이었다. 둘 다 GT 3D 피처(QC 후)로 검증.

(A) 2-피처: 조건별 화이트리스트(MediaPipe 가능 + 미러 불변) 안에서 폴드별 단변량 상위 K 피처의 모든 쌍에 깊이-2 결정트리를
    적합, 학습폴드 AUC 최고 쌍을 골라 테스트폴드 AUC. 단일 규칙(fit_rule_cv, 같은 화이트리스트)과 비교. 최종 규칙은 전체 데이터 트리에서 추출.
(B) 개인 기준선: 수행자 p 의 '조건 충족' 클립 중 앞 k개(촬영 순)의 피처 중앙값을 p 의 기준선으로 쓰고, 값−기준선 으로 판정.
    평가는 기준선에 쓰지 않은 클립만, 임계값은 다른 수행자(GroupKFold)에서 학습. raw(원값) AUC 와 비교.
출력: outputs/rule_engine_v1.csv, outputs/rule_engine_v1_summary.md
"""
from __future__ import annotations

import itertools
import re
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold
from sklearn.tree import DecisionTreeClassifier

from experiment_b import univariate_auc
from export_rules_mp import mirror_safe
from features import build_or_load_features, mediapipe_computable
from rule_engine_v0 import MIN_SUBTYPE_N, SPINE_FAMILIES, SPINE_RE, columns_for, families_for, fit_rule_cv

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

OUT = Path(__file__).resolve().parent / "outputs"
TOP_K = 6
BASELINE_K = 3


def usable_cols(names: list[str]) -> list[str]:
    out = []
    for c in names:
        if c.startswith("ts_"):
            out.append(c)
            continue
        b, s = c.rsplit("__", 1)
        if mediapipe_computable(b) and mirror_safe(b, s)[0]:
            out.append(c)
    return out


def tree_rule_text(tree: DecisionTreeClassifier, names: list[str]) -> str:
    t = tree.tree_
    if t.node_count == 1:
        return "(분할 없음)"
    def node(i, depth=0):
        if t.children_left[i] == -1:
            p = t.value[i][0]
            cls = "정상" if p[1] >= p[0] else "위반"
            return f"→ {cls}"
        f = names[t.feature[i]]
        thr = t.threshold[i]
        l = node(t.children_left[i], depth + 1)
        r = node(t.children_right[i], depth + 1)
        return f"[{f} ≤ {thr:.4g} ? {l} : {r}]"
    return node(0)


def pair_rule_cv(X: np.ndarray, y: np.ndarray, groups: np.ndarray, names: list[str], n_splits: int = 5) -> dict:
    n_groups = len(np.unique(groups))
    if min(int(y.sum()), int(len(y) - y.sum())) < 15 or n_groups < 2 or X.shape[1] < 2:
        return dict(cv_auc=np.nan, pair="", rule="")
    gkf = GroupKFold(n_splits=min(n_splits, n_groups))
    aucs, chosen = [], []
    for tr, te in gkf.split(X, y, groups):
        Xtr, Xte, ytr, yte = X[tr], X[te], y[tr], y[te]
        if len(np.unique(ytr)) < 2 or len(np.unique(yte)) < 2:
            continue
        med = np.nanmedian(Xtr, axis=0)
        med = np.where(np.isnan(med), 0.0, med)
        Xtr_i = np.where(np.isnan(Xtr), med, Xtr)
        Xte_i = np.where(np.isnan(Xte), med, Xte)
        keep = np.array([np.unique(c).size >= 2 for c in Xtr_i.T])
        if keep.sum() < 2:
            continue
        idx_keep = np.flatnonzero(keep)
        a = univariate_auc(Xtr_i[:, keep], ytr)
        order = idx_keep[np.argsort(-np.abs(a - 0.5))[:TOP_K]]
        best, best_auc = None, -1.0
        msl = int(np.clip(0.02 * len(ytr), 5, 30))
        for i, j in itertools.combinations(order, 2):
            clf = DecisionTreeClassifier(max_depth=2, min_samples_leaf=msl, random_state=0)
            clf.fit(Xtr_i[:, [i, j]], ytr)
            s = roc_auc_score(ytr, clf.predict_proba(Xtr_i[:, [i, j]])[:, 1])
            if s > best_auc:
                best, best_auc = (i, j), s
        i, j = best
        clf = DecisionTreeClassifier(max_depth=2, min_samples_leaf=msl, random_state=0).fit(Xtr_i[:, [i, j]], ytr)
        aucs.append(roc_auc_score(yte, clf.predict_proba(Xte_i[:, [i, j]])[:, 1]))
        chosen.append((i, j))
    if not aucs:
        return dict(cv_auc=np.nan, pair="", rule="")
    pair = max(set(chosen), key=chosen.count)
    med = np.nanmedian(X, axis=0)
    med = np.where(np.isnan(med), 0.0, med)
    Xi = np.where(np.isnan(X), med, X)
    msl = int(np.clip(0.02 * len(y), 5, 30))
    clf = DecisionTreeClassifier(max_depth=2, min_samples_leaf=msl, random_state=0).fit(Xi[:, list(pair)], y)
    return dict(cv_auc=float(np.mean(aucs)), cv_auc_std=float(np.std(aucs)), pair=f"{names[pair[0]]} + {names[pair[1]]}",
                rule=tree_rule_text(clf, [names[pair[0]], names[pair[1]]]), n_folds=len(aucs))


def baseline_cv(values: np.ndarray, y: np.ndarray, groups: np.ndarray, order_key: np.ndarray, k: int = BASELINE_K) -> dict:
    """개인 기준선: 수행자별 '조건 충족' 앞 k개 클립의 중앙값을 빼고 판정. 기준선 클립은 평가에서 제외."""
    adj = np.full_like(values, np.nan, dtype=np.float64)
    eval_mask = np.zeros(len(y), dtype=bool)
    for p in np.unique(groups):
        idx = np.flatnonzero(groups == p)
        idx = idx[np.argsort(order_key[idx])]
        pos = [i for i in idx if y[i] == 1 and np.isfinite(values[i])]
        if len(pos) < k:
            continue
        base_idx = pos[:k]
        b = float(np.median(values[base_idx]))
        for i in idx:
            if i in base_idx:
                continue
            adj[i] = values[i] - b
            eval_mask[i] = True
    if eval_mask.sum() < 30 or len(np.unique(y[eval_mask])) < 2:
        return dict(raw_auc=np.nan, adj_auc=np.nan, n_eval=int(eval_mask.sum()))
    ve, ae, ye, ge = values[eval_mask], adj[eval_mask], y[eval_mask], groups[eval_mask]
    ok = np.isfinite(ve) & np.isfinite(ae)
    ve, ae, ye, ge = ve[ok], ae[ok], ye[ok], ge[ok]
    n_groups = len(np.unique(ge))
    if n_groups < 2 or min(int(ye.sum()), int(len(ye) - ye.sum())) < 10:
        return dict(raw_auc=np.nan, adj_auc=np.nan, n_eval=int(ok.sum()))
    # 방향은 전체 데이터에서 raw 의 AUC 부호로 고정 (임계값 자체는 CV 에서 학습되지만 AUC 는 임계값 무관)
    sign = 1.0 if roc_auc_score(ye, ve) >= 0.5 else -1.0
    gkf = GroupKFold(n_splits=min(5, n_groups))
    raw_aucs, adj_aucs = [], []
    for tr, te in gkf.split(ve, ye, ge):
        if len(np.unique(ye[te])) < 2:
            continue
        raw_aucs.append(roc_auc_score(ye[te], sign * ve[te]))
        adj_aucs.append(roc_auc_score(ye[te], sign * ae[te]))
    return dict(raw_auc=float(np.mean(raw_aucs)), adj_auc=float(np.mean(adj_aucs)), n_eval=int(len(ye)),
                n_subjects_eval=int(n_groups))


def main():
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    names_all = list(feats.columns)
    names = usable_cols(names_all)
    spine = pd.read_parquet(OUT / "spine_subtype.parquet").set_index("clip_id") if (OUT / "spine_subtype.parquet").exists() else None
    qc = pd.read_csv(OUT / "qc_per_clip.csv")
    bad_ex = set(qc.groupby("exercise")["drop_clip"].mean().pipe(lambda s: s[s > 0.5]).index)
    rules_v0 = pd.read_csv(OUT / "rules_v0.csv")
    rules_v0["subtype"] = rules_v0["subtype"].fillna("")
    clips = clips.loc[clips.index.intersection(feats.index)]
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))
    # 촬영 순서 프록시: clip_id 의 마지막 숫자(타입키) + day — 같은 수행자 내 순서
    clips["order_key"] = clips["day"].astype(str) + "-" + clips.index.str.rsplit("-", n=1).str[-1].str.zfill(4)
    print(f"[load] clips {len(clips)} | 피처 후보 {len(names)} (MP 가능·미러 불변) / 전체 {len(names_all)}")

    rows = []
    for ex_name, g in clips.groupby("exercise"):
        if ex_name in bad_ex:
            continue
        ids_all = g.index
        sub = conds[conds["clip_id"].isin(ids_all)]
        piv = sub.pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first").reindex(ids_all)
        cond_order = sub.groupby("condition")["cond_idx"].median().sort_values().index.tolist()
        for cname in cond_order:
            yv = piv[cname]
            mask = yv.notna().to_numpy()
            base_ids = ids_all[mask]
            y_all = yv[mask].to_numpy().astype(int)
            if SPINE_RE.search(cname) and spine is not None:
                st = spine.reindex(base_ids)["subtype"].fillna("unspecified").to_numpy()
                variants = [("all", np.ones(len(base_ids), dtype=bool))]
                for s in ["flexion", "lateral", "extension", "lumbar_swing", "forward_lean", "cervical"]:
                    if int((st == s).sum()) >= MIN_SUBTYPE_N:
                        variants.append((s, (st == s) | (y_all == 1)))
            else:
                variants = [(None, np.ones(len(base_ids), dtype=bool))]
            for subtype, m in variants:
                ids = base_ids[m]
                y = y_all[m]
                grp = g.loc[ids, "group"].to_numpy()
                fams = SPINE_FAMILIES.get(subtype, SPINE_FAMILIES["all"]) if subtype else families_for(cname)
                cols = columns_for(fams, names, mp_only=True) if fams else names
                if len(cols) < 2:
                    continue
                X = feats.loc[ids, cols].to_numpy(dtype=np.float64)
                single = fit_rule_cv(X, y, grp, cols)
                pair = pair_rule_cv(X, y, grp, cols)
                # (B) 개인 기준선: 단일 규칙 피처에 대해
                bl = dict(raw_auc=np.nan, adj_auc=np.nan, n_eval=0)
                if single["feature"]:
                    v = feats.loc[ids, single["feature"]].to_numpy(dtype=np.float64)
                    bl = baseline_cv(v, y, grp, g.loc[ids, "order_key"].to_numpy())
                ref = rules_v0[(rules_v0.exercise == ex_name) & (rules_v0.base_condition == cname) & (rules_v0.subtype == (subtype or ""))]
                gbm = float(ref["gbm_auc_cv"].iloc[0]) if len(ref) and "gbm_auc_cv" in ref else np.nan
                rows.append(dict(exercise=ex_name, condition=cname, subtype=subtype or "", n=int(len(y)), n_performers=int(len(np.unique(grp))),
                                 single_auc=single["cv_auc"], single_feature=single["feature"],
                                 pair_auc=pair["cv_auc"], pair=pair["pair"], pair_rule=pair["rule"],
                                 pair_gain=(pair["cv_auc"] - single["cv_auc"]) if np.isfinite(pair["cv_auc"]) and np.isfinite(single["cv_auc"]) else np.nan,
                                 gbm_auc=gbm,
                                 bl_raw_auc=bl["raw_auc"], bl_adj_auc=bl["adj_auc"], bl_n_eval=bl["n_eval"],
                                 bl_gain=(bl["adj_auc"] - bl["raw_auc"]) if np.isfinite(bl.get("adj_auc", np.nan)) else np.nan))
        print(f"[{ex_name}] done", flush=True)
    res = pd.DataFrame(rows)
    res.to_csv(OUT / "rule_engine_v1.csv", index=False, encoding="utf-8-sig")

    base = res[(res.subtype == "") & res.single_auc.notna()]
    bp = base[base.pair_auc.notna()]
    bb = base[base.bl_adj_auc.notna()]
    L = ["# 룰엔진 v1 탐색 — 2-피처 규칙 · 개인 기준선 (GT 3D, MP 가능·미러 불변 화이트리스트, 수행자 GroupKFold)\n",
         f"- 평가 조건 {len(base)}개 (3D 양호 종목, 기본 조건) + 척추 하위유형 {int((res.subtype!='').sum())}개\n",
         "## A. 2-피처 규칙 (깊이-2 결정트리) vs 단일 규칙\n",
         f"- 단일 AUC 중앙값 {bp.single_auc.median():.3f} → 2-피처 {bp.pair_auc.median():.3f} (Δ 중앙값 {bp.pair_gain.median():+.3f}, 평균 {bp.pair_gain.mean():+.3f})",
         f"- 개선 ≥ +0.03: {(bp.pair_gain>=0.03).sum()}개 / 악화 ≤ −0.03: {(bp.pair_gain<=-0.03).sum()}개 / ≥0.85 비율: 단일 {(bp.single_auc>=0.85).mean()*100:.0f}% → 2-피처 {(bp.pair_auc>=0.85).mean()*100:.0f}%",
         f"- GBM(비선형 상한) 중앙값 {bp.gbm_auc.median():.3f}; 단일 < 0.80 이면서 GBM ≥ 0.90 인 조건에서 2-피처 Δ 중앙값: {bp[(bp.single_auc<0.8)&(bp.gbm_auc>=0.9)].pair_gain.median():+.3f} (n={int(((bp.single_auc<0.8)&(bp.gbm_auc>=0.9)).sum())})\n",
         "### 개선 큰 조건 15\n", "| 종목 | 조건 | 단일 AUC (피처) | 2-피처 AUC | Δ | GBM | 규칙 |", "|---|---|---|---|---|---|---|"]
    for r in bp.sort_values("pair_gain", ascending=False).head(15).itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.single_auc:.3f} ({r.single_feature}) | {r.pair_auc:.3f} | {r.pair_gain:+.3f} | {r.gbm_auc:.3f} | {r.pair_rule} |")
    L += ["", "## B. 개인 기준선 오프셋 (수행자별 조건충족 앞 3클립 중앙값을 뺀 값으로 판정; 기준선 클립 제외 평가)\n",
          f"- 평가 가능 조건 {len(bb)}개 | raw AUC 중앙값 {bb.bl_raw_auc.median():.3f} → 기준선 보정 {bb.bl_adj_auc.median():.3f} (Δ 중앙값 {bb.bl_gain.median():+.3f}, 평균 {bb.bl_gain.mean():+.3f})",
          f"- 개선 ≥ +0.03: {(bb.bl_gain>=0.03).sum()}개 / 악화 ≤ −0.03: {(bb.bl_gain<=-0.03).sum()}개\n",
          "### 개선 큰 조건 12 / 악화 큰 조건 8\n", "| 종목 | 조건 | 피처 | raw | 보정 | Δ | 평가 n |", "|---|---|---|---|---|---|---|"]
    for r in bb.sort_values("bl_gain", ascending=False).head(12).itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.single_feature} | {r.bl_raw_auc:.3f} | {r.bl_adj_auc:.3f} | {r.bl_gain:+.3f} | {r.bl_n_eval} |")
    L.append("| … | | | | | | |")
    for r in bb.sort_values("bl_gain").head(8).itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.single_feature} | {r.bl_raw_auc:.3f} | {r.bl_adj_auc:.3f} | {r.bl_gain:+.3f} | {r.bl_n_eval} |")
    # 피처 패밀리별 기준선 효과
    bb2 = bb.copy()
    bb2["fam"] = bb2.single_feature.str.rsplit("__", n=1).str[0].str.replace(r"_(L|R|mean|minside|maxside|asym)$", "", regex=True)
    fam = bb2.groupby("fam").agg(n=("exercise", "size"), gain=("bl_gain", "median")).reset_index().sort_values("gain", ascending=False)
    L += ["", "### 피처 패밀리별 기준선 효과(Δ 중앙값, n≥2)\n", "| 패밀리 | n | Δ |", "|---|---|---|"]
    for r in fam[fam.n >= 2].itertuples():
        L.append(f"| {r.fam} | {r.n} | {r.gain:+.3f} |")
    sp = res[(res.subtype != "") & res.pair_auc.notna()]
    if len(sp):
        L += ["", "## C. 척추 하위유형 — 2-피처 / 기준선\n", "| 종목 | 하위유형 | 단일 | 2-피처 | 기준선 보정 |", "|---|---|---|---|---|"]
        for r in sp.sort_values(["subtype", "exercise"]).itertuples():
            L.append(f"| {r.exercise} | {r.subtype} | {r.single_auc:.3f} | {r.pair_auc:.3f} | {('%.3f' % r.bl_adj_auc) if np.isfinite(r.bl_adj_auc) else '-'} |")
    (OUT / "rule_engine_v1_summary.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:12]))


if __name__ == "__main__":
    main()
