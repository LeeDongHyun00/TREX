#!/usr/bin/env python
"""앱 세트 로그 + 코치 라벨 → 규칙 임계값 재보정 (KOTLIN_PORTING_SPEC §9 / §14).

입력
  --logs    앱이 남긴 JSON Lines (schema trex.posture.setlog/1; PostureSetLog.kt). 디렉터리/파일 여러 개 가능
  --labels  CSV: set_id, condition, value[, subtype, subject_id]
              value = 1/true/정상/ok → 조건 충족, 0/false/위반/ng → 위반 (AIHub 라벨 의미와 동일)
              subtype 은 '척추의 중립' 처럼 하위유형이 있는 조건의 위반 세트에만 (flexion/lateral/...)
  --rules   현재 규칙 JSON (rules/rules_mp_v0.json)
절차 (규칙마다)
  1) 로그의 프레임 피처를 앱과 같은 식(mean/min/max/std/range, NaN 무시)으로 집계 → 세트당 값 1개
  2) 라벨과 합쳐 n ≥ --min-sets, 각 클래스 ≥ --min-class 이면 재보정:
       - 피처·방향(op)은 유지, 임계값만 Youden J 로 다시 맞춤 (1차 원칙: spec §9-2)
       - 수행자(subject_id) 가 2명 이상이면 GroupKFold, 아니면 StratifiedKFold 로 CV AUC/균형정확도 보고
       - CV AUC < 0.70 이면 'feature_weak' 경고 + (--suggest) 같은 패밀리 화이트리스트에서 더 나은 피처 제안
  3) 임계값을 바꾼 규칙셋 JSON(version 접미 +calib-YYYYMMDD) + 리포트 출력. 데이터가 부족한 규칙은 그대로 두고 표시.
출력: <out>/rules_calibrated.json, calibration_report.md, calibration_report.csv
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import warnings
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold, StratifiedKFold

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
SCHEMA = "trex.posture.setlog/1"
STATS = ("mean", "min", "max", "std", "range")
TRUE_WORDS = {"1", "true", "t", "yes", "y", "ok", "정상", "충족", "pass"}
FALSE_WORDS = {"0", "false", "f", "no", "n", "ng", "위반", "불충족", "fail"}


# ---------------------------------------------------------------- 로그 읽기
def iter_log_files(paths: list[str]):
    for p in paths:
        if os.path.isdir(p):
            for f in sorted(glob.glob(os.path.join(p, "*.jsonl"))):
                yield f
        else:
            for f in sorted(glob.glob(p)):
                yield f


def load_sets(paths: list[str]) -> pd.DataFrame:
    rows = []
    n_bad = 0
    for f in iter_log_files(paths):
        with open(f, encoding="utf-8") as fh:
            for ln, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    n_bad += 1
                    continue
                if d.get("schema") != SCHEMA:
                    n_bad += 1
                    continue
                rows.append(dict(set_id=d["set_id"], exercise=d.get("exercise", ""), subject_id=d.get("subject_id") or "",
                                 model=d.get("model", ""), delegate=d.get("delegate", ""), front_camera=d.get("front_camera"),
                                 up_from_gravity=d.get("up_from_gravity"), tilt_deg=d.get("tilt_deg"),
                                 sample_interval_ms=d.get("sample_interval_ms"), n_frames=len(d.get("frames", [])),
                                 frames=d.get("frames", []), source=os.path.basename(f), line=ln))
    if n_bad:
        print(f"[warn] 스키마 불일치/파싱 실패 {n_bad}줄 건너뜀")
    return pd.DataFrame(rows)


def aggregate_set(frames: list[dict]) -> dict[str, dict[str, float]]:
    """앱 FeatureAggregator 와 동일: 피처별 유효값(finite)만 모아 mean/min/max/std(모집단)/range. count 도 반환."""
    vals: dict[str, list[float]] = {}
    for fr in frames:
        for k, v in (fr.get("features") or {}).items():
            if v is None:
                continue
            try:
                fv = float(v)
            except (TypeError, ValueError):
                continue
            if np.isfinite(fv):
                vals.setdefault(k, []).append(fv)
    out = {}
    for k, v in vals.items():
        a = np.asarray(v, dtype=np.float64)
        out[k] = dict(mean=float(a.mean()), min=float(a.min()), max=float(a.max()), std=float(a.std()), range=float(a.max() - a.min()),
                      count=int(len(a)))
    return out


def parse_value(v) -> int | None:
    s = str(v).strip().lower()
    if s in TRUE_WORDS:
        return 1
    if s in FALSE_WORDS:
        return 0
    return None


def youden(score: np.ndarray, y: np.ndarray) -> float:
    fpr, tpr, thr = roc_curve(y, score)
    k = int(np.argmax(tpr - fpr))
    t = thr[k]
    return float(t) if np.isfinite(t) else float(np.median(score))


def fit_threshold(values: np.ndarray, y: np.ndarray, sign: int, groups: np.ndarray, n_splits: int = 5) -> dict:
    """피처·방향 고정, 임계값만 재적합. score = sign*value (클수록 정상)."""
    score = sign * values
    n_groups = len(np.unique(groups[groups != ""])) if (groups != "").any() else 0
    use_group = n_groups >= 2 and (groups != "").all()
    if use_group:
        splitter = GroupKFold(n_splits=min(n_splits, n_groups))
        splits = splitter.split(values, y, groups)
        method = f"GroupKFold({min(n_splits, n_groups)}, 수행자 {n_groups}명)"
    else:
        k = int(min(n_splits, min(int(y.sum()), int(len(y) - y.sum()))))
        k = max(2, k)
        splitter = StratifiedKFold(n_splits=k, shuffle=True, random_state=0)
        splits = splitter.split(values, y)
        method = f"StratifiedKFold({k}) — subject_id 부족, 수행자 누수 가능"
    aucs, baccs = [], []
    for tr, te in splits:
        if len(np.unique(y[tr])) < 2 or len(np.unique(y[te])) < 2:
            continue
        t = youden(score[tr], y[tr])
        aucs.append(roc_auc_score(y[te], score[te]))
        baccs.append(balanced_accuracy_score(y[te], (score[te] >= t).astype(int)))
    t_all = youden(score, y)
    return dict(cv_auc=(float(np.mean(aucs)) if aucs else np.nan), cv_auc_std=(float(np.std(aucs)) if aucs else np.nan),
                cv_balacc=(float(np.mean(baccs)) if baccs else np.nan), insample_auc=float(roc_auc_score(y, score)),
                threshold=float(sign * t_all), method=method, n_folds=len(aucs))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--logs", nargs="+", required=True)
    ap.add_argument("--labels", required=True)
    ap.add_argument("--rules", default=str(HERE / "rules" / "rules_mp_v0.json"))
    ap.add_argument("--out", default=str(HERE / "outputs" / "calib"))
    ap.add_argument("--min-sets", type=int, default=30)
    ap.add_argument("--min-class", type=int, default=8)
    ap.add_argument("--min-frames", type=int, default=8)
    ap.add_argument("--include-exclude", action="store_true", help="exclude 등급 규칙도 재보정 시도")
    ap.add_argument("--suggest", action="store_true", help="CV AUC 가 약한 규칙에 같은 패밀리 안 대안 피처 제안")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    sets = load_sets(args.logs)
    if sets.empty:
        raise SystemExit("세트 로그가 없습니다")
    labels = pd.read_csv(args.labels, dtype=str).fillna("")
    for c in ("set_id", "condition", "value"):
        if c not in labels.columns:
            raise SystemExit(f"labels.csv 에 '{c}' 컬럼이 필요합니다")
    labels["y"] = labels["value"].map(parse_value)
    bad = labels["y"].isna().sum()
    if bad:
        print(f"[warn] 해석 불가한 value {bad}건 제외")
    labels = labels[labels["y"].notna()].copy()
    labels["y"] = labels["y"].astype(int)
    if "subtype" not in labels.columns:
        labels["subtype"] = ""
    if "subject_id" in labels.columns:
        subj = labels.drop_duplicates("set_id").set_index("set_id")["subject_id"]
        sets["subject_id"] = np.where(sets["subject_id"].astype(str) != "", sets["subject_id"], sets["set_id"].map(subj).fillna(""))
    print(f"[load] 세트 {len(sets)} (종목 {sets.exercise.nunique()}, 수행자 {sets.subject_id.replace('', np.nan).nunique()}명) | 라벨 {len(labels)}행 (조건 {labels.condition.nunique()}개)")

    # 세트 집계
    aggs = {sid: aggregate_set(fr) for sid, fr in zip(sets["set_id"], sets["frames"])}
    set_meta = sets.set_index("set_id")

    rules_doc = json.load(open(args.rules, encoding="utf-8"))
    rules = rules_doc["rules"]
    if args.suggest:
        from rule_engine_v0 import SPINE_FAMILIES, columns_for, families_for, fit_rule_cv
        from features import mediapipe_computable

    rows = []
    n_cal = 0
    for r in rules:
        if r["status"] == "exclude" and not args.include_exclude:
            rows.append(dict(id=r["id"], status=r["status"], action="skip(exclude)"))
            continue
        ex, cond, st = r["exercise"], r["condition"], r.get("subtype")
        lab = labels[labels["condition"] == cond]
        if st and st != "all":
            # 하위유형 규칙: 정상(1) 전부 + 해당 하위유형으로 표시된 위반만
            lab = lab[(lab["y"] == 1) | (lab["subtype"] == st)]
        cand = []
        for row in lab.itertuples():
            if row.set_id not in aggs:
                continue
            if set_meta.loc[row.set_id, "exercise"] != ex:
                continue
            a = aggs[row.set_id].get(r["base_feature"])
            if not a or a["count"] < args.min_frames:
                continue
            cand.append((row.set_id, a[r["stat"]], int(row.y), str(set_meta.loc[row.set_id, "subject_id"])))
        n = len(cand)
        n_pos = sum(1 for c in cand if c[2] == 1)
        n_neg = n - n_pos
        base = dict(id=r["id"], exercise=ex, condition=cond, subtype=st or "", status=r["status"], feature=r["feature"], op=r["op"],
                    threshold_prev=r["threshold"], n_sets=n, n_pos=n_pos, n_neg=n_neg)
        if n < args.min_sets or min(n_pos, n_neg) < args.min_class:
            rows.append(dict(base, action="insufficient", note=f"세트 {n} (정상 {n_pos}/위반 {n_neg}) < 기준 {args.min_sets}/{args.min_class}"))
            continue
        vals = np.array([c[1] for c in cand], dtype=np.float64)
        y = np.array([c[2] for c in cand], dtype=int)
        grp = np.array([c[3] for c in cand], dtype=object)
        sign = 1 if r["op"] == "<" else -1
        fit = fit_threshold(vals, y, sign, grp)
        warn = []
        if np.isnan(fit["cv_auc"]) or fit["cv_auc"] < 0.70:
            warn.append("feature_weak: 이 데이터에서 피처 판별력 낮음 — 피처 재선택 검토")
        shift = fit["threshold"] - r["threshold"]
        scale = float(np.std(vals)) if np.std(vals) > 0 else 1.0
        if abs(shift) > 1.0 * scale:
            warn.append(f"threshold_shift: 임계값 이동 {shift:+.3g} (표본 표준편차의 {abs(shift)/scale:.1f}배)")
        suggestion = None
        if args.suggest and (not np.isnan(fit["cv_auc"])) and fit["cv_auc"] < 0.80:
            fams = SPINE_FAMILIES.get(st, SPINE_FAMILIES["all"]) if st else families_for(cond)
            # 후보 = 같은 패밀리 × 5통계 중 로그에 존재하는 것 (MP 가능 피처만)
            all_names = sorted({f"{b}__{s}" for sid in [c[0] for c in cand] for b in aggs[sid] for s in STATS if mediapipe_computable(b)})
            cols = columns_for(fams, all_names, mp_only=True) if fams else all_names
            if cols:
                X = np.array([[aggs[sid].get(c.rsplit("__", 1)[0], {}).get(c.rsplit("__", 1)[1], np.nan) for c in cols] for sid in [c[0] for c in cand]], dtype=np.float64)
                g2 = np.where(grp == "", np.array([c[0] for c in cand], dtype=object), grp)
                rr = fit_rule_cv(X, y, g2, cols)
                if rr["feature"] and (np.isnan(fit["cv_auc"]) or rr["cv_auc"] > fit["cv_auc"] + 0.03):
                    suggestion = dict(feature=rr["feature"], op=("<" if rr["sign"] > 0 else ">"), threshold=rr["threshold"], cv_auc=rr["cv_auc"])
        r["threshold_prev"] = r["threshold"]
        r["threshold"] = round(fit["threshold"], 6)
        r["violation_if"] = f"{r['feature']} {r['op']} {fit['threshold']:.4g}"
        r["calibration"] = dict(source="app_setlogs", date=str(date.today()), n_sets=n, n_pos=n_pos, n_neg=n_neg,
                                n_subjects=int(len(set(g for g in grp if g))), method=fit["method"], cv_auc=(None if np.isnan(fit["cv_auc"]) else round(fit["cv_auc"], 4)),
                                cv_balacc=(None if np.isnan(fit["cv_balacc"]) else round(fit["cv_balacc"], 4)), insample_auc=round(fit["insample_auc"], 4),
                                warnings=warn, suggested_feature=suggestion)
        n_cal += 1
        rows.append(dict(base, action="calibrated", threshold_new=fit["threshold"], cv_auc=fit["cv_auc"], cv_balacc=fit["cv_balacc"],
                         insample_auc=fit["insample_auc"], method=fit["method"], note="; ".join(warn),
                         suggestion=(f"{suggestion['feature']} {suggestion['op']} {suggestion['threshold']:.4g} (AUC {suggestion['cv_auc']:.3f})" if suggestion else "")))

    rules_doc["version"] = f"{rules_doc.get('version', 'mp_v0')}+calib-{date.today():%Y%m%d}"
    rules_doc["calibration_note"] = f"{n_cal}개 규칙 임계값을 앱 세트 로그({len(sets)}세트)로 재보정. 나머지는 이전 값 유지."
    (out / "rules_calibrated.json").write_text(json.dumps(rules_doc, ensure_ascii=False, indent=1), encoding="utf-8")
    rep = pd.DataFrame(rows)
    rep.to_csv(out / "calibration_report.csv", index=False, encoding="utf-8-sig")

    cal = rep[rep.action == "calibrated"] if "action" in rep else rep.iloc[0:0]
    L = [f"# 임계값 재보정 리포트 ({date.today()})\n",
         f"- 세트 로그 {len(sets)}개 (종목 {sets.exercise.nunique()}, 수행자 {sets.subject_id.replace('', np.nan).nunique()}명, 프레임 중앙값 {int(sets.n_frames.median())}) | 라벨 {len(labels)}행",
         f"- 규칙 {len(rules)}개 중 재보정 {n_cal} / 데이터 부족 {(rep.action=='insufficient').sum()} / 건너뜀 {(rep.action.str.startswith('skip')).sum()}",
         f"- 기준: 세트 ≥ {args.min_sets}, 클래스당 ≥ {args.min_class}, 프레임 ≥ {args.min_frames} | 방법: 피처·방향 고정, Youden J 임계값, 수행자 GroupKFold(가능 시)\n",
         "## 재보정된 규칙\n", "| 규칙 | n(정상/위반) | 이전 임계값 | 새 임계값 | CV AUC | 균형정확도 | 방법 | 경고 / 제안 |", "|---|---|---|---|---|---|---|---|"]
    for r in cal.itertuples():
        L.append(f"| {r.id} | {r.n_sets} ({r.n_pos}/{r.n_neg}) | {r.threshold_prev:.4g} | {r.threshold_new:.4g} | {r.cv_auc:.3f} | {r.cv_balacc:.3f} | {r.method} | {r.note} {('→ 제안: ' + r.suggestion) if r.suggestion else ''} |")
    ins = rep[rep.action == "insufficient"]
    if len(ins):
        L += ["", "## 데이터 부족 (이전 임계값 유지)\n", "| 규칙 | 현황 |", "|---|---|"]
        for r in ins.itertuples():
            L.append(f"| {r.id} | {r.note} |")
    (out / "calibration_report.md").write_text("\n".join(L), encoding="utf-8")
    print(f"[done] 재보정 {n_cal} / 부족 {(rep.action=='insufficient').sum()} → {out}")


if __name__ == "__main__":
    main()
