#!/usr/bin/env python
"""실험 A-2 — MediaPipe 피처 위에서 규칙을 '재적합'했을 때의 성능 (제품 시나리오).

실험 A 의 규칙 전이는 GT 3D 로 고른 피처·임계값을 MP 피처에 그대로 얹은 것이라 (a) 피처 선택 차이 (b) 임계값 바이어스가 섞여 있다.
여기서는 rule_engine_v0 와 같은 절차(조건별 물리 피처 화이트리스트 안 단일 피처 + Youden 임계값, 수행자 GroupKFold)를
MediaPipe 피처 행렬(뷰별, 그리고 5뷰 평균 FUSED) 위에서 돌린다 → "폰 랜드마크로 학습/검증한 규칙 엔진"의 기대 성능.

출력: outputs/expA_refit.csv, outputs/expA_refit_summary.md
"""
from __future__ import annotations

import re
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

from features import mediapipe_computable
from rule_engine_v0 import COND_RULES, SPINE_FAMILIES, SPINE_RE, MIN_SUBTYPE_N, columns_for, families_for, fit_rule_cv

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

OUT = Path(__file__).resolve().parent / "outputs"
VIEW_DESC = {"A": "후방사선L", "B": "전방사선L", "C": "정면", "D": "전방사선R", "E": "후방사선R", "FUSED": "5뷰평균",
             "GT_SUBSET": "통제군(GT 3D, 동일 표본·동일 화이트리스트)"}


def main():
    rules_gt = pd.read_csv(OUT / "rules_v0.csv")
    rules_gt["subtype"] = rules_gt["subtype"].fillna("")
    if "--summary-only" in sys.argv and (OUT / "expA_refit.csv").exists():
        res = pd.read_csv(OUT / "expA_refit.csv")
        res["subtype"] = res["subtype"].fillna("")
        res["qc_flag"] = res["qc_flag"].fillna("")
        write_summary(res, rules_gt)
        return
    fm = pd.read_parquet(OUT / "expA_features_mp.parquet")
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    spine = pd.read_parquet(OUT / "spine_subtype.parquet").set_index("clip_id") if (OUT / "spine_subtype.parquet").exists() else None
    qc = pd.read_csv(OUT / "qc_per_clip.csv")
    bad_ex = set(qc.groupby("exercise")["drop_clip"].mean().pipe(lambda s: s[s > 0.5]).index)

    feat_cols = [c for c in fm.columns if c not in ("clip_id", "view_letter")]
    # MediaPipe 에서 계산 불가한 피처는 제거 (spine_*, neck_angle, shoulder_neck_gap, shoulder_fwd)
    feat_cols = [c for c in feat_cols if c.startswith("ts_") or mediapipe_computable(c.rsplit("__", 1)[0])]
    # --mirror-safe: 카메라를 사용자의 좌/우 어느 쪽에 두어도 같은 값이 나오는 피처만
    #   (*_L/*_R 한쪽 지정 제외, 반대칭 피처는 std/range 만) → 출력 파일명에 _mirror 접미
    mirror = "--mirror-safe" in sys.argv
    if mirror:
        from export_rules_mp import mirror_safe as _ms
        def _ok(c: str) -> bool:
            if c.startswith("ts_"):
                return True
            b, s = c.rsplit("__", 1)
            return _ms(b, s)[0]
        feat_cols = [c for c in feat_cols if _ok(c)]
        print(f"[mirror-safe] 허용 피처 {len(feat_cols)}개", flush=True)
    out_csv = OUT / ("expA_refit_mirror.csv" if mirror else "expA_refit.csv")
    out_md = OUT / ("expA_refit_mirror_summary.md" if mirror else "expA_refit_summary.md")
    views = {v: g.set_index("clip_id")[feat_cols] for v, g in fm.groupby("view_letter")}
    views["FUSED"] = fm.groupby("clip_id")[feat_cols].mean()
    # 통제군: 같은 표본(샘플 클립) + 같은 MP-가능 화이트리스트 + GT 3D 피처 → 표본 크기 효과와 랜드마크 노이즈 효과 분리
    feats_gt = pd.read_parquet(OUT / "features.parquet")
    views["GT_SUBSET"] = feats_gt.reindex(columns=feat_cols)
    names = feat_cols
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))

    rows = []
    sampled = set(fm["clip_id"])
    for ex_name, g in clips[clips.index.isin(sampled)].groupby("exercise"):
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
                for s in ["flexion", "lateral", "extension", "lumbar_swing", "lumbar_sag", "forward_lean", "cervical", "unspecified"]:
                    if int((st == s).sum()) >= MIN_SUBTYPE_N // 2:   # 샘플이 적으므로 기준 완화
                        variants.append((s, (st == s) | (y_all == 1)))
            else:
                variants = [(None, np.ones(len(base_ids), dtype=bool))]
            for subtype, m in variants:
                ids = base_ids[m]
                y = y_all[m]
                fams = SPINE_FAMILIES.get(subtype, SPINE_FAMILIES["all"]) if subtype else families_for(cname)
                wl_cols = columns_for(fams, names, mp_only=True) if fams else names
                if not wl_cols:
                    continue
                gt_row = rules_gt[(rules_gt.exercise == ex_name) & (rules_gt.base_condition == cname) & (rules_gt.subtype == (subtype or ""))]
                gt_wl = float(gt_row["wl_auc"].iloc[0]) if len(gt_row) else np.nan
                gt_mp = float(gt_row["mp_auc"].iloc[0]) if len(gt_row) else np.nan
                for vname, vf in views.items():
                    X = vf.reindex(ids)[wl_cols].to_numpy(dtype=np.float64)
                    valid = ~np.all(np.isnan(X), axis=1)
                    if valid.sum() < 30:
                        continue
                    Xv, yv2, grp = X[valid], y[valid], g.loc[ids[valid], "group"].to_numpy()
                    if min(int(yv2.sum()), int(len(yv2) - yv2.sum())) < 10:
                        continue
                    r = fit_rule_cv(Xv, yv2, grp, wl_cols, n_splits=5)
                    rows.append(dict(exercise=ex_name, condition=cname, subtype=subtype or "", view=vname, n=int(len(yv2)),
                                     n_performers=int(len(np.unique(grp))), qc_flag=("3D불량" if ex_name in bad_ex else ""),
                                     mp_refit_auc=r["cv_auc"], mp_refit_balacc=r["cv_balacc"], mp_feature=r["feature"],
                                     mp_threshold=r["threshold"], mp_sign=r["sign"], gt_wl_auc=gt_wl, gt_mp_auc=gt_mp))
        print(f"[{ex_name}] done", flush=True)
    res = pd.DataFrame(rows)
    res.to_csv(out_csv, index=False, encoding="utf-8-sig")
    write_summary(res, rules_gt, out_md)


def write_summary(res: pd.DataFrame, rules_gt: pd.DataFrame, out_md: Path = OUT / "expA_refit_summary.md"):
    all_base = res[(res.subtype == "") & (res.qc_flag != "3D불량") & res.mp_refit_auc.notna()]
    ctrl = all_base[all_base.view == "GT_SUBSET"].set_index(["exercise", "condition"])["mp_refit_auc"].rename("gt_sub_auc")
    base = all_base[all_base.view != "GT_SUBSET"].merge(ctrl.reset_index(), on=["exercise", "condition"], how="left")
    L = ["# 실험 A-2 — MediaPipe 피처 위 규칙 재적합 (조건별 화이트리스트, 수행자 GroupKFold)\n",
         f"- 평가 (종목×조건×뷰) {len(base)}행, 3D양호 종목 기본 조건 {base.groupby(['exercise','condition']).ngroups}개 | 종목당 샘플 ≤60클립이라 AUC 표준오차 ≈ ±0.05",
         "- gt_wl = GT 3D 전체 표본으로 학습/검증한 규칙(룰엔진 v0) | **gt_sub = 통제군: 같은 60클립 표본 + 같은 MP-가능 화이트리스트 + GT 3D 피처** | mp_refit = MediaPipe 피처",
         "- Δ(mp − gt_sub) 가 랜드마크 추정 노이즈의 순수 비용, (gt_sub − gt_wl) 은 표본 크기/피처 제한 효과\n",
         "## 1. 뷰별 요약 (기본 조건)\n", "| 뷰 | 설명 | 규칙 수 | GT 전체 | GT 통제군(동일표본) | MP 재적합 | Δ(MP−통제군) | MP≥0.80 | MP≥0.85 | 균형정확도 |", "|---|---|---|---|---|---|---|---|---|---|"]
    for v in ["C", "B", "D", "A", "E", "FUSED"]:
        g = base[base.view == v]
        if g.empty:
            continue
        L.append(f"| {v} | {VIEW_DESC[v]} | {len(g)} | {g.gt_wl_auc.median():.3f} | {g.gt_sub_auc.median():.3f} | {g.mp_refit_auc.median():.3f} | {(g.mp_refit_auc-g.gt_sub_auc).median():+.3f} | {(g.mp_refit_auc>=0.8).mean()*100:.0f}% | {(g.mp_refit_auc>=0.85).mean()*100:.0f}% | {g.mp_refit_balacc.median():.3f} |")
    best = base.sort_values("mp_refit_auc", ascending=False).drop_duplicates(["exercise", "condition"])
    L.append(f"\n규칙별 최적 뷰 선택 시: MP 재적합 AUC 중앙값 {best.mp_refit_auc.median():.3f} | GT 통제군(동일표본) {best.gt_sub_auc.median():.3f} | GT 전체 {best.gt_wl_auc.median():.3f} | ≥0.80 {(best.mp_refit_auc>=0.8).mean()*100:.0f}% (통제군 {(best.gt_sub_auc>=0.8).mean()*100:.0f}%) | ≥0.85 {(best.mp_refit_auc>=0.85).mean()*100:.0f}% (통제군 {(best.gt_sub_auc>=0.85).mean()*100:.0f}%)")
    L.append("최적 뷰 분포: " + ", ".join(f"{v}={n}" for v, n in best.view.value_counts().items()) + "\n")

    sp = res[(res.subtype != "") & (res.qc_flag != "3D불량") & res.mp_refit_auc.notna()]
    if len(sp):
        L += ["## 2. 척추 하위유형 (MP 재적합 AUC 중앙값, 뷰별)\n", "| 하위유형 | " + " | ".join(["C", "B", "D", "A", "E", "FUSED"]) + " | GT 통제군 | GT 전체 |", "|---|" + "---|" * 8]
        for st, g in sp.groupby("subtype"):
            cells = " | ".join(f"{g[g.view==v].mp_refit_auc.median():.3f}" if len(g[g.view==v]) else "-" for v in ["C", "B", "D", "A", "E", "FUSED"])
            gs = g[g.view == "GT_SUBSET"].mp_refit_auc.median() if len(g[g.view == "GT_SUBSET"]) else np.nan
            L.append(f"| {st} | {cells} | {gs:.3f} | {g.gt_wl_auc.median():.3f} |")
        L.append("")

    L += ["## 3. 규칙별 (기본 조건, 최적 뷰) — 전체\n", "| 종목 | 조건 | 최적 뷰 | GT 전체 | GT 통제군 | MP 재적합 | Δ(MP−통제군) | MP 피처 | 규칙 |", "|---|---|---|---|---|---|---|---|---|"]
    for r in best.sort_values(["exercise", "mp_refit_auc"], ascending=[True, False]).itertuples():
        op = "<" if r.mp_sign > 0 else ">"
        L.append(f"| {r.exercise} | {r.condition} | {r.view} | {r.gt_wl_auc:.3f} | {r.gt_sub_auc:.3f} | {r.mp_refit_auc:.3f} | {r.mp_refit_auc-r.gt_sub_auc:+.3f} | {r.mp_feature} | 위반 if {r.mp_feature} {op} {r.mp_threshold:.3g} |")
    L.append("")
    L += ["## 4. 랜드마크 노이즈 손실이 큰 조건 (최적 뷰에서도 MP 재적합 < GT 통제군 − 0.10)\n", "| 종목 | 조건 | GT 통제군 | MP 재적합 | Δ | GT 피처 | MP 피처 |", "|---|---|---|---|---|---|---|"]
    gtf = rules_gt[rules_gt.subtype == ""].drop_duplicates(["exercise", "base_condition"]).set_index(["exercise", "base_condition"])["wl_feature"]
    for r in best[(best.mp_refit_auc < best.gt_sub_auc - 0.10)].sort_values("mp_refit_auc").itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.gt_sub_auc:.3f} | {r.mp_refit_auc:.3f} | {r.mp_refit_auc-r.gt_sub_auc:+.3f} | {gtf.get((r.exercise, r.condition), '')} | {r.mp_feature} |")
    L.append("")
    # 피처(기본명)별 손실 집계 — 무엇이 전이되고 무엇이 안 되나
    best2 = best.copy()
    best2["gt_base"] = best2.apply(lambda r: str(gtf.get((r.exercise, r.condition), "")).rsplit("__", 1)[0], axis=1)
    fam = best2.groupby("gt_base").agg(n=("exercise", "size"), gt_sub=("gt_sub_auc", "median"), mp=("mp_refit_auc", "median")).reset_index()
    fam["d"] = fam["mp"] - fam["gt_sub"]
    fam = fam[fam.n >= 2].sort_values("d")
    L += ["## 5. GT 최적 피처(기본명)별: 통제군 → MP 재적합 중앙값 (n≥2)\n", "| GT 피처 | n | GT 통제군 | MP 재적합 | Δ |", "|---|---|---|---|---|"]
    for r in fam.itertuples():
        L.append(f"| {r.gt_base} | {r.n} | {r.gt_sub:.3f} | {r.mp:.3f} | {r.d:+.3f} |")
    out_md.write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:20]))


if __name__ == "__main__":
    main()
