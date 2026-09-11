#!/usr/bin/env python
"""규칙별 오탐률·신뢰구간 — 출시 임계값을 그대로 두고 "정상을 얼마나 위반이라 하는가"와 "AUC 가 표본 오차 안에서 어디까지 흔들리는가" (spec §32).

지금까지 규칙 JSON 은 교차검증 AUC·균형정확도의 점추정만 실었다. 규칙당 표본이 39~60클립·수행자 24~44명이라 AUC 표준오차가
±0.05 안팎인데 그 폭이 어디에도 없었고, 서서 종목 JSON 에는 바닥 JSON 의 `normal_fpr` 에 해당하는 "정상 클립 오탐률" 필드도 없었다.

방법
  - 실험 A 와 같은 경로: outputs/mp/landmarks_*.parquet → build_mp_arrays (L/R 매핑은 part_2d 가 고른 것) → compute_frame_features
    → 클립 집계 mean/min/max/std/range 에 p10/p90 추가 (§28c 가 극값을 p10/p90 으로 바꾼 규칙 때문. Kotlin FeatureAggregator 와 같은 정의)
  - 규칙의 view_best_front 뷰, 출시 feature/op/threshold 그대로 판정 (재적합 없음). 반대측 가드가 있으면 가드까지 합친 값도 낸다.
  - 라벨: 조건 True = 정상, False = 위반. 척추 하위유형 규칙은 실험 A-2 와 같이 (해당 하위유형 위반 ∪ 정상) 부분집합.
  - 수행자 단위 부트스트랩 B=1000 (수행자를 복원추출, 그 수행자의 클립 전부 포함) → 95% 백분위 구간.
  - AUC 는 위반을 양성으로 두고 점수 = 값(op '>') 또는 -값(op '<'). 재적합이 아니므로 in-sample 이지만 피처·임계값이 고정이라
    낙관 편향은 임계값 선택(Youden) 하나뿐이다.

§28c 임계값 재적합 (--refit-s28c)
  §28c 는 극값 통계를 p10/p90/mean 으로 바꿨는데 그 감사는 GT 3D 기준이었다(스펙 §28c '한계'). 바뀐 통계의 임계값이 MP 분포 위에서
  재적합되지 않아 실측: 덤벨 체스트 플라이 팔꿈치 검출률 0.00, 바벨 런지 뒤다리 0.35, 사이드 크런치 양손 정상 오탐률 0.77, 딥스 팔꿈치 0.39.
  이 모드는 cautions 에 '§28c' 가 있는 규칙의 **피처·통계·방향은 그대로 두고** 임계값만 MP 피처(view_best_front)에서
  rule_engine_v0.fit_rule_cv(단일 피처, 수행자 GroupKFold, Youden) 로 다시 맞춘다. 데이터가 고른 방향이 JSON 의 op 와 다르면 건드리지 않고 보고만 한다.

출력
  outputs/rule_confidence.csv, RULE_CONFIDENCE.md
  --apply      : rules/rules_mp_v0.json 에 confidence{...} 주입 (+ 재적합 결과) + 헤더 counts 를 실제 분포로 갱신
  --gate s32   : (--apply 와 함께) ship 유지 조건 = AUC ≥ 0.85 · AUC 구간 하한 ≥ 0.75 · 균형정확도 ≥ 0.75 · 정상 오탐률 ≤ 0.35 · 검출률 ≥ 0.50.
                 하나라도 미달이면 beta. AUC 만 보던 현행 게이트는 임계값이 죽은 규칙(검출률 0)을 걸러내지 못했다.
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from experiment_a import build_mp_arrays, load_landmarks, part_2d
from features import compute_frame_features
from rule_engine_v0 import fit_rule_cv

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
MP = OUT / "mp"
RULES = HERE / "rules" / "rules_mp_v0.json"
B_BOOT = 1000
SEED = 0
STATS = ("mean", "min", "max", "std", "range", "p10", "p90")
GATE_S32 = dict(auc=0.85, auc_lo=0.75, balacc=0.75, fpr=0.35, tpr=0.50)


def agg_stat(v: np.ndarray, stat: str) -> np.ndarray:
    if stat == "mean":
        return np.nanmean(v, axis=1)
    if stat == "min":
        return np.nanmin(v, axis=1)
    if stat == "max":
        return np.nanmax(v, axis=1)
    if stat == "std":
        return np.nanstd(v, axis=1)
    if stat == "range":
        return np.nanmax(v, axis=1) - np.nanmin(v, axis=1)
    if stat == "p10":
        return np.nanquantile(v, 0.10, axis=1)
    if stat == "p90":
        return np.nanquantile(v, 0.90, axis=1)
    raise ValueError(stat)


def flags(values: np.ndarray, op: str, thr: float) -> np.ndarray:
    return values < thr if op == "<" else values > thr


def metrics(values: np.ndarray, y_norm: np.ndarray, op: str, thr: float, guard) -> dict:
    """y_norm: 1=정상, 0=위반. 반환: fpr/tpr/balacc/auc (+ 가드 포함 오탐률)."""
    prim = flags(values, op, thr)
    viol = prim.copy()
    if guard is not None:
        viol |= (~prim) & flags(values, guard["op"], float(guard["threshold"]))
    norm, bad = y_norm == 1, y_norm == 0
    out = dict(n_normal=int(norm.sum()), n_violation=int(bad.sum()))
    out["normal_fpr"] = float(prim[norm].mean()) if norm.any() else np.nan
    out["tpr"] = float(prim[bad].mean()) if bad.any() else np.nan
    out["balacc"] = float(((1 - out["normal_fpr"]) + out["tpr"]) / 2) if norm.any() and bad.any() else np.nan
    out["normal_fpr_guard"] = float(viol[norm].mean()) if norm.any() else np.nan
    score = values if op == ">" else -values
    out["auc"] = float(roc_auc_score(1 - y_norm, score)) if norm.any() and bad.any() else np.nan
    return out


def bootstrap(values, y_norm, groups, op, thr, guard, rng) -> dict:
    uniq = np.unique(groups)
    idx_by_g = {g: np.flatnonzero(groups == g) for g in uniq}
    acc = {k: [] for k in ("normal_fpr", "tpr", "balacc", "auc", "normal_fpr_guard")}
    for _ in range(B_BOOT):
        pick = rng.choice(uniq, size=len(uniq), replace=True)
        idx = np.concatenate([idx_by_g[g] for g in pick])
        yb = y_norm[idx]
        if yb.min() == yb.max():
            continue
        m = metrics(values[idx], yb, op, thr, guard)
        for k in acc:
            acc[k].append(m[k])
    out = {}
    for k, v in acc.items():
        v = np.array(v, dtype=float)
        v = v[np.isfinite(v)]
        if len(v):
            out[f"{k}_lo"], out[f"{k}_hi"] = float(np.percentile(v, 2.5)), float(np.percentile(v, 97.5))
        else:
            out[f"{k}_lo"], out[f"{k}_hi"] = np.nan, np.nan
    out["n_boot"] = int(len(acc["auc"]))
    return out


def build_features(rules: list[dict]):
    lm = load_landmarks()
    sample = pd.read_parquet(MP / "sample.parquet")
    clips = pd.read_parquet(OUT / "clips.parquet")
    qc = pd.read_csv(OUT / "qc_per_clip.csv")
    bad_ex = set(qc.groupby("exercise")["drop_clip"].mean().pipe(lambda s: s[s > 0.5]).index)
    p2d = part_2d(lm, sample, clips, bad_ex)
    print(f"[2D] 매핑={p2d['tag']} (직접 {p2d['med_direct']:.3f} / 반전 {p2d['med_swap']:.3f})", flush=True)
    keys, arr, flipped = build_mp_arrays(lm, sample, p2d["tag"])
    print(f"[3D] (클립,뷰) {len(keys):,}개, y반전={flipped}", flush=True)
    F = compute_frame_features(arr)
    need = sorted({r["base_feature"] for r in rules if r["status"] in ("ship", "beta")})
    cols = {"clip_id": keys["clip_id"].to_numpy(), "view_letter": keys["view_letter"].to_numpy()}
    missing = []
    for b in need:
        if b not in F:
            missing.append(b)
            continue
        for s in STATS:
            cols[f"{b}__{s}"] = agg_stat(F[b], s)
    if missing:
        print(f"[feat] 연구 피처에 없는 base_feature (건너뜀): {missing}", flush=True)
    return pd.DataFrame(cols), p2d["tag"]


class Sampler:
    """규칙 → (ids, y, values, groups). 실험 A-2 와 같은 표본 정의."""

    def __init__(self, fm: pd.DataFrame):
        self.fm = fm
        self.clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
        self.clips["group"] = np.where(self.clips["performer"].astype(str) != "", self.clips["performer"].astype(str), self.clips["day"].astype(str))
        self.conds = pd.read_parquet(OUT / "conditions.parquet")
        sp = OUT / "spine_subtype.parquet"
        self.spine = pd.read_parquet(sp).set_index("clip_id") if sp.exists() else None
        self.sampled = set(fm["clip_id"])
        self.by_view = {v: g.set_index("clip_id") for v, g in fm.groupby("view_letter")}
        self.by_pair = fm.set_index(["clip_id", "view_letter"])
        est_path = OUT / "view_estimator.csv"
        self.est = pd.read_csv(est_path)[["clip_id", "view", "pred"]] if est_path.exists() else None

    def labels(self, r: dict):
        """규칙의 (clip_id → y) 라벨. 척추 하위유형 부분집합 적용. 실험 A-2 와 같은 정의."""
        clips, conds = self.clips, self.conds
        ids = clips.index[(clips["exercise"] == r["exercise"]) & clips.index.isin(self.sampled)]
        lab = (conds[(conds["clip_id"].isin(ids)) & (conds["condition"] == r["condition"])]
               .drop_duplicates("clip_id").set_index("clip_id")["value"])
        lab = lab.reindex(ids).dropna().astype(int)
        sub = r.get("subtype")
        if sub and sub != "all" and self.spine is not None:
            st = self.spine.reindex(lab.index)["subtype"].fillna("unspecified").to_numpy()
            lab = lab[(st == sub) | (lab.to_numpy() == 1)]
        return lab

    def sample_by_class(self, r: dict) -> dict:
        """추정기 등급별 (values, y, groups): 5뷰 전부를 모아 (clip,view) 를 view_estimator 의 예측 등급으로 나눈다.
        런타임이 보는 것은 카메라 코드가 아니라 추정기 출력이므로 views_ok 는 이 단위로 정의해야 자기 일관적이다."""
        if self.est is None or r["feature"] not in self.fm.columns:
            return {}
        lab = self.labels(r)
        e = self.est[self.est.clip_id.isin(lab.index)]
        out = {}
        for cls, g in e.groupby("pred"):
            pairs = list(zip(g.clip_id, g.view))
            vals = self.by_pair.reindex(pd.MultiIndex.from_tuples(pairs, names=["clip_id", "view_letter"]))[r["feature"]].to_numpy(dtype=float)
            y = lab.reindex(g.clip_id).to_numpy()
            ok = np.isfinite(vals)
            vals, y = vals[ok], y[ok]
            grp = self.clips.loc[g.clip_id.to_numpy()[ok], "group"].to_numpy()
            out[cls] = (vals, y, grp)
        return out

    def sample(self, r: dict):
        feat, view = r["feature"], r.get("view_best_front") or ""
        if feat not in self.fm.columns:
            return None, "피처 없음"
        if view not in self.by_view:
            return None, f"뷰 {view} 없음"
        clips, conds = self.clips, self.conds
        ids = clips.index[(clips["exercise"] == r["exercise"]) & clips.index.isin(self.sampled)]
        lab = (conds[(conds["clip_id"].isin(ids)) & (conds["condition"] == r["condition"])]
               .drop_duplicates("clip_id").set_index("clip_id")["value"])
        lab = lab.reindex(ids).dropna().astype(int)
        ids = lab.index
        y = lab.to_numpy()
        sub = r.get("subtype")
        if sub and sub != "all" and self.spine is not None:
            st = self.spine.reindex(ids)["subtype"].fillna("unspecified").to_numpy()
            keep = (st == sub) | (y == 1)
            ids, y = ids[keep], y[keep]
        vals = self.by_view[view].reindex(ids)[feat].to_numpy(dtype=float)
        ok = np.isfinite(vals)
        ids, y, vals = ids[ok], y[ok], vals[ok]
        grp = clips.loc[ids, "group"].to_numpy()
        if len(y) < 10 or y.min() == y.max():
            return None, f"표본 부족 n={len(y)}"
        return (ids, y, vals, grp), ""


def refit_s28c(rules: list[dict], sampler: Sampler) -> list[dict]:
    """§28c 로 통계가 바뀐 규칙의 임계값을 MP 피처에서 재적합. 피처·통계·op 는 유지. 반환: 보고용 행."""
    log = []
    for r in rules:
        if r["status"] not in ("ship", "beta") or not any("§28c" in c for c in (r.get("cautions") or [])):
            continue
        s, note = sampler.sample(r)
        if s is None:
            log.append(dict(id=r["id"], applied=False, note=note))
            continue
        ids, y, vals, grp = s
        fit = fit_rule_cv(vals.reshape(-1, 1), y, grp, [r["feature"]], n_splits=5)
        if not fit["feature"] or not np.isfinite(fit["threshold"]):
            log.append(dict(id=r["id"], applied=False, note="적합 실패"))
            continue
        op_new = "<" if fit["sign"] > 0 else ">"
        row = dict(id=r["id"], exercise=r["exercise"], condition=r["condition"], feature=r["feature"], op=r["op"], op_data=op_new,
                   thr_old=float(r["threshold"]), thr_new=float(fit["threshold"]), cv_auc_old=r.get("cv_auc"), cv_auc_new=fit["cv_auc"],
                   cv_balacc_old=r.get("cv_balacc"), cv_balacc_new=fit["cv_balacc"], n=int(len(y)))
        if op_new != r["op"]:
            row.update(applied=False, note=f"데이터 방향({op_new})이 JSON op({r['op']})와 반대 — 보류")
            log.append(row)
            continue
        r["threshold_before_s32"] = float(r["threshold"])
        r["threshold"] = round(float(fit["threshold"]), 6)
        r["cv_auc_before_s32"], r["cv_balacc_before_s32"] = r.get("cv_auc"), r.get("cv_balacc")
        r["cv_auc"] = round(float(fit["cv_auc"]), 4)
        r["cv_balacc"] = round(float(fit["cv_balacc"]), 4)
        r["violation_if"] = f"{r['feature']} {r['op']} {r['threshold']:.6g}"
        r["cautions"] = list(r.get("cautions") or []) + [
            f"§32: §28c 통계 교체 임계값은 GT 3D 기준이었음 — MP 피처(뷰 {r.get('view_best_front')})에서 임계값만 재적합 {row['thr_old']:.4g}→{row['thr_new']:.4g} (cv AUC {fit['cv_auc']:.2f}, 균형정확도 {fit['cv_balacc']:.2f})"]
        row.update(applied=True, note="")
        log.append(row)
    return log


def gate_status(row, gate: str | None) -> tuple[str | None, str]:
    """(새 등급 또는 None, 사유)."""
    if gate is None or row.status != "ship":
        return None, ""
    if gate == "s32":
        g = GATE_S32
        fails = []
        if not row.auc >= g["auc"]:
            fails.append(f"AUC {row.auc:.2f}<{g['auc']}")
        if not row.auc_lo >= g["auc_lo"]:
            fails.append(f"AUC 하한 {row.auc_lo:.2f}<{g['auc_lo']}")
        if not row.balacc >= g["balacc"]:
            fails.append(f"균형정확도 {row.balacc:.2f}<{g['balacc']}")
        if not row.normal_fpr <= g["fpr"]:
            fails.append(f"정상 오탐률 {row.normal_fpr:.2f}>{g['fpr']}")
        if not row.tpr >= g["tpr"]:
            fails.append(f"검출률 {row.tpr:.2f}<{g['tpr']}")
        return ("beta", " · ".join(fails)) if fails else (None, "")
    raise ValueError(gate)


def inject(doc: dict, res: pd.DataFrame, gate: str | None, refit_log: list[dict], by_view: pd.DataFrame | None = None):
    by_id = res.set_index("id")
    n_changed, n_inj, n_views = 0, 0, 0
    if by_view is not None:
        for r in doc["rules"]:
            g = by_view[by_view.id == r["id"]]
            if g.empty:
                continue
            r["views_ok"] = sorted(g[g.ok].cls.tolist(), key=lambda c: ["C", "B", "D", "SIDE_B", "SIDE_D", "A", "E", "R", "UNKNOWN"].index(c))
            r["views_eval"] = {
                str(x.cls): dict(n=int(x.n), normal_fpr=(round(float(x.normal_fpr), 4) if np.isfinite(x.normal_fpr) else None),
                                 tpr=(round(float(x.tpr), 4) if np.isfinite(x.tpr) else None), balacc=(round(float(x.balacc), 4) if np.isfinite(x.balacc) else None))
                for x in g.itertuples()}
            n_views += 1
    for r in doc["rules"]:
        if r["id"] not in by_id.index:
            continue
        row = by_id.loc[r["id"]]
        note = row.get("note", "")
        if isinstance(note, str) and note:
            continue
        if not np.isfinite(row.auc):
            continue
        r["confidence"] = dict(
            method="MP 재추론 표본, view_best_front 뷰, 출시 임계값 고정, 수행자 부트스트랩 B=1000 95% 구간 (rule_confidence.py, spec §32)",
            n=int(row.n), n_normal=int(row.n_normal), n_violation=int(row.n_violation), n_performers=int(row.n_performers),
            normal_fpr=round(float(row.normal_fpr), 4), normal_fpr_ci95=[round(float(row.normal_fpr_lo), 4), round(float(row.normal_fpr_hi), 4)],
            normal_fpr_with_guard=(round(float(row.normal_fpr_guard), 4) if bool(row.has_guard) else None),
            tpr=round(float(row.tpr), 4), tpr_ci95=[round(float(row.tpr_lo), 4), round(float(row.tpr_hi), 4)],
            balacc=round(float(row.balacc), 4), balacc_ci95=[round(float(row.balacc_lo), 4), round(float(row.balacc_hi), 4)],
            auc=round(float(row.auc), 4), auc_ci95=[round(float(row.auc_lo), 4), round(float(row.auc_hi), 4)],
            normal_median=round(float(row.normal_median), 6),
        )
        n_inj += 1
        new, why = gate_status(row, gate)
        if new and new != r["status"]:
            r["cautions"] = list(r.get("cautions") or []) + [f"§32 게이트({gate}) 미달 — {why} — {r['status']}에서 {new}로"]
            r["status_before_s32"] = r["status"]
            r["status"] = new
            n_changed += 1
    n_refit = sum(1 for x in refit_log if x.get("applied"))
    doc["counts"] = {s: sum(1 for x in doc["rules"] if x["status"] == s) for s in ("ship", "beta", "exclude")}
    doc["mirror_safe_counts"] = {s: sum(1 for x in doc["rules"] if x["status"] == s and x.get("mirror_safe")) for s in ("ship", "beta")}
    note = doc.get("revision_note") or ""
    if "§32" not in note:
        note += (" | §32 (rule_confidence.py): ship/beta 규칙에 confidence{정상 오탐률·검출률·AUC·균형정확도, 수행자 부트스트랩 95% 구간} 주입"
                 + (f", §28c 통계 교체 규칙 {n_refit}건 임계값 MP 재적합" if n_refit else "")
                 + (f", 게이트 {gate} 로 {n_changed}건 beta 강등" if gate else "") + ", 헤더 counts 를 실제 분포로 갱신")
    if n_views and "§33" not in note:
        note += f" | §33 views_ok 주입 {n_views}건(추정 뷰 등급별 판정 허용 목록, view_estimator.py)"
    doc["revision_note"] = note
    RULES.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[apply] confidence 주입 {n_inj}건, 임계값 재적합 {n_refit}건, 등급 변경 {n_changed}건, views_ok {n_views}건, counts={doc['counts']} -> {RULES}")


def write_report(res: pd.DataFrame, tag: str, refit_log: list[dict], gate: str | None):
    has_note = "note" in res.columns
    ok = res[res["note"].fillna("") == ""].copy() if has_note else res.copy()
    ship, beta = ok[ok.status == "ship"], ok[ok.status == "beta"]
    L = ["# 규칙별 오탐률·신뢰구간 — 출시 임계값 고정, 수행자 부트스트랩 (spec §32)\n",
         f"- 대상: ship {len(ship)} · beta {len(beta)} 규칙 (rules_mp_v0.json), MP 재추론 표본, L/R 매핑 = {tag}, 부트스트랩 B={B_BOOT}",
         "- `normal_fpr` = 정상 라벨 클립 중 출시 임계값이 위반이라 한 비율. `tpr` = 위반 클립 검출률. 구간은 수행자 복원추출 95% 백분위.",
         "- AUC 는 피처·임계값 고정 in-sample 값이라 JSON 의 교차검증 `cv_auc` 와 다를 수 있다. 차이가 크면 교차검증에서 폴드마다 다른 피처가 뽑혔다는 뜻.\n"]
    if refit_log:
        L += ["## 0. §28c 통계 교체 규칙 — MP 피처 임계값 재적합\n",
              "§28c 감사는 GT 3D 기준이라 바뀐 통계(p10/p90/mean)의 임계값이 MP 분포 위에서 맞춰지지 않았다. 피처·통계·방향은 그대로 두고 임계값만 재적합.\n",
              "| 종목 | 조건 | 피처 | op | 임계 (GT→MP) | cv AUC (전→후) | 균형정확도 (전→후) | 적용 |", "|---|---|---|---|---|---|---|---|"]
        for x in refit_log:
            if "thr_old" in x:
                L.append(f"| {x['exercise']} | {x['condition']} | {x['feature']} | {x['op']} | {x['thr_old']:.4g} → {x['thr_new']:.4g} | "
                         f"{(x['cv_auc_old'] or float('nan')):.2f} → {x['cv_auc_new']:.2f} | {(x['cv_balacc_old'] or float('nan')):.2f} → {x['cv_balacc_new']:.2f} | "
                         f"{'예' if x['applied'] else '아니오: ' + x['note']} |")
            else:
                L.append(f"| {x['id']} | | | | | | | 아니오: {x['note']} |")
        L.append("")
    if len(ship):
        L += ["## 1. ship 규칙 분포\n", "| 지표 | 중앙값 | 최소 | 최대 |", "|---|---|---|---|"]
        for k, name in (("normal_fpr", "정상 오탐률"), ("tpr", "위반 검출률"), ("balacc", "균형정확도"), ("auc", "AUC(in-sample)"),
                        ("auc_lo", "AUC 구간 하한"), ("normal_fpr_hi", "오탐률 구간 상한")):
            L.append(f"| {name} | {ship[k].median():.3f} | {ship[k].min():.3f} | {ship[k].max():.3f} |")
        g = GATE_S32
        s32 = (ship.auc >= g["auc"]) & (ship.auc_lo >= g["auc_lo"]) & (ship.balacc >= g["balacc"]) & (ship.normal_fpr <= g["fpr"]) & (ship.tpr >= g["tpr"])
        L += ["", "## 2. 후보 게이트별 ship 잔존 수\n", "| 게이트 | 통과 | 탈락 |", "|---|---|---|"]
        gates = {
            "현행: 점 AUC ≥ 0.85": ship.auc >= 0.85,
            f"s32: AUC ≥ {g['auc']} · 하한 ≥ {g['auc_lo']} · 균형정확도 ≥ {g['balacc']} · 오탐률 ≤ {g['fpr']} · 검출률 ≥ {g['tpr']}": s32,
            "점 AUC ≥ 0.85 & 정상 오탐률 ≤ 0.10": (ship.auc >= 0.85) & (ship.normal_fpr <= 0.10),
            "점 AUC ≥ 0.85 & 정상 오탐률 ≤ 0.20": (ship.auc >= 0.85) & (ship.normal_fpr <= 0.20),
            "구간 하한 ≥ 0.85": ship.auc_lo >= 0.85,
            "균형정확도 ≥ 0.80 & 오탐률 ≤ 0.10 (spec §9 출시 기준)": (ship.balacc >= 0.80) & (ship.normal_fpr <= 0.10),
        }
        for name, m in gates.items():
            L.append(f"| {name} | {int(m.sum())} | {int((~m).sum())} |")
        fail = ship[~s32]
        if len(fail):
            L += ["", "s32 탈락 규칙:"] + [f"- {r.exercise} | {r.condition} | {r.subtype} — {gate_status(r, 's32')[1]}" for r in fail.itertuples()]
    for title, df in (("## 3. ship 규칙 전체 (오탐률 내림차순)", ship.sort_values("normal_fpr", ascending=False)),
                      ("## 4. beta 규칙", beta.sort_values("normal_fpr", ascending=False))):
        if df.empty:
            continue
        L += ["", title + "\n",
              "| 종목 | 조건 | 하위유형 | 규칙 | 뷰 | n(정상/위반) | 수행자 | 정상 오탐률 [95%] | 가드 포함 | 검출률 | 균형정확도 [95%] | AUC [95%] | cv_auc |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in df.itertuples():
            gd = f"{r.normal_fpr_guard:.2f}" if r.has_guard else "-"
            L.append(f"| {r.exercise} | {r.condition} | {r.subtype} | {r.feature} {r.op} {r.threshold:.3g} | {r.view} | {r.n} ({r.n_normal}/{r.n_violation}) | {r.n_performers} | "
                     f"**{r.normal_fpr:.2f}** [{r.normal_fpr_lo:.2f}, {r.normal_fpr_hi:.2f}] | {gd} | {r.tpr:.2f} | {r.balacc:.2f} [{r.balacc_lo:.2f}, {r.balacc_hi:.2f}] | "
                     f"{r.auc:.2f} [{r.auc_lo:.2f}, {r.auc_hi:.2f}] | {r.cv_auc:.2f} |")
    if has_note:
        skipped = res[res["note"].fillna("") != ""]
        if len(skipped):
            L += ["", "## 5. 계산 못 한 규칙\n"] + [f"- {r.id} ({r.status}): {r.note}" for r in skipped.itertuples()]
    (HERE / "RULE_CONFIDENCE.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:40]))


def main():
    apply = "--apply" in sys.argv
    gate = sys.argv[sys.argv.index("--gate") + 1] if "--gate" in sys.argv else None
    do_refit = "--refit-s28c" in sys.argv
    doc = json.load(open(RULES, encoding="utf-8"))
    rules = doc["rules"]
    fm, tag = build_features(rules)
    sampler = Sampler(fm)
    refit_log = refit_s28c(rules, sampler) if do_refit else []
    rng = np.random.default_rng(SEED)

    rows = []
    for r in rules:
        if r["status"] not in ("ship", "beta"):
            continue
        s, note = sampler.sample(r)
        if s is None:
            rows.append(dict(id=r["id"], status=r["status"], note=note))
            continue
        ids, y, vals, grp = s
        op, thr = r["op"], float(r["threshold"])
        m = metrics(vals, y, op, thr, r.get("opposite_guard"))
        m.update(bootstrap(vals, y, grp, op, thr, r.get("opposite_guard"), rng))
        m.update(id=r["id"], exercise=r["exercise"], condition=r["condition"], subtype=r.get("subtype") or "", status=r["status"], feature=r["feature"],
                 op=op, threshold=thr, view=r.get("view_best_front") or "", n=int(len(y)), n_performers=int(len(np.unique(grp))),
                 cv_auc=r.get("cv_auc"), cv_balacc=r.get("cv_balacc"), normal_median=float(np.median(vals[y == 1])),
                 has_guard=bool(r.get("opposite_guard")), note="")
        rows.append(m)
    res = pd.DataFrame(rows)
    OUT.mkdir(exist_ok=True)
    res.to_csv(OUT / "rule_confidence.csv", index=False, encoding="utf-8-sig")
    write_report(res, tag, refit_log, gate)
    by_view = views_table(rules, sampler) if "--views" in sys.argv else None
    if by_view is not None:
        write_views_report(by_view)
    if apply:
        inject(doc, res, gate, refit_log, by_view)


VIEW_OK = dict(balacc=0.75, fpr=0.35, tpr=0.50, n_min=20, n_each_min=5)


def views_table(rules: list[dict], sampler: Sampler) -> pd.DataFrame:
    """규칙 × 추정 뷰 등급별 성능 (출시 임계값 고정). views_ok = 이 규칙을 판정해도 되는 추정 등급 목록 (spec §33)."""
    rows = []
    for r in rules:
        if r["status"] not in ("ship", "beta"):
            continue
        for cls, (vals, y, grp) in sampler.sample_by_class(r).items():
            n = int(len(y))
            row = dict(id=r["id"], exercise=r["exercise"], condition=r["condition"], subtype=r.get("subtype") or "", status=r["status"],
                       home_view=r.get("view_best_front"), cls=cls, n=n, n_normal=int((y == 1).sum()), n_violation=int((y == 0).sum()),
                       n_performers=int(len(np.unique(grp))) if n else 0)
            if n >= VIEW_OK["n_min"] and min(row["n_normal"], row["n_violation"]) >= VIEW_OK["n_each_min"]:
                m = metrics(vals, y, r["op"], float(r["threshold"]), r.get("opposite_guard"))
                row.update(normal_fpr=m["normal_fpr"], tpr=m["tpr"], balacc=m["balacc"], auc=m["auc"],
                           ok=bool(m["balacc"] >= VIEW_OK["balacc"] and m["normal_fpr"] <= VIEW_OK["fpr"] and m["tpr"] >= VIEW_OK["tpr"]))
            else:
                row.update(normal_fpr=np.nan, tpr=np.nan, balacc=np.nan, auc=np.nan, ok=False)
            rows.append(row)
    df = pd.DataFrame(rows)
    df.to_csv(OUT / "rule_views.csv", index=False, encoding="utf-8-sig")
    return df


def write_views_report(bv: pd.DataFrame):
    order = ["C", "B", "D", "SIDE_B", "SIDE_D", "A", "E", "R", "UNKNOWN"]
    L = ["# 규칙 × 추정 뷰 등급 — 출시 임계값을 다른 방향에서 썼을 때 (spec §33)\n",
         "- 5뷰 카메라의 (클립,뷰) 전부를 **추정기(view_estimator.py)가 낸 등급**으로 나눠, 규칙의 출시 임계값 그대로 정상 오탐률·검출률·균형정확도를 냈다.",
         "- 런타임이 보는 것은 카메라 코드가 아니라 추정기 출력이므로 `views_ok` 는 이 단위로 정의해야 자기 일관적이다.",
         f"- 판정 허용(ok) = 표본 ≥ {VIEW_OK['n_min']}(각 라벨 ≥ {VIEW_OK['n_each_min']}) 이고 균형정확도 ≥ {VIEW_OK['balacc']} · 정상 오탐률 ≤ {VIEW_OK['fpr']} · 검출률 ≥ {VIEW_OK['tpr']}. 표본이 없는 등급(측면 등)은 허용하지 않는다 = 유보.\n",
         "## 1. 규칙별 허용 등급\n", "| 종목 | 조건 | 하위유형 | 등급 | 학습 뷰 | 허용 등급(views_ok) | " + " | ".join(f"{c}: n/오탐/검출/균형" for c in order) + " |",
         "|---|---|---|---|---|---|" + "---|" * len(order)]
    for rid, g in bv.groupby("id", sort=False):
        g = g.set_index("cls")
        ok = [c for c in order if c in g.index and bool(g.loc[c, "ok"])]
        cells = []
        for c in order:
            if c not in g.index:
                cells.append("-")
                continue
            x = g.loc[c]
            cells.append(f"{int(x.n)}" if not np.isfinite(x.balacc) else f"{int(x.n)}/{x.normal_fpr:.2f}/{x.tpr:.2f}/**{x.balacc:.2f}**" + ("✓" if x.ok else ""))
        f = g.iloc[0]
        L.append(f"| {f.exercise} | {f.condition} | {f.subtype} | {f.status} | {f.home_view} | {','.join(ok) or '없음'} | " + " | ".join(cells) + " |")
    n_rules = bv.id.nunique()
    n_home_ok = sum(1 for rid, g in bv.groupby("id") if bool(g[g.cls == g.home_view.iloc[0]]["ok"].any()))
    n_mirror_ok = sum(1 for rid, g in bv.groupby("id") if {"B", "D"} <= set(g[g.ok].cls))
    L += ["", "## 2. 요약\n", f"- 규칙 {n_rules}개 중 학습 뷰(view_best_front)와 같은 추정 등급에서 허용되는 규칙 {n_home_ok}개, B·D 양쪽 모두 허용(미러 무관) {n_mirror_ok}개",
          "- 케이블 종목(케이블 푸시 다운·페이스 풀·케이블 크런치)은 데이터에서 수행자가 카메라 C 를 등졌으므로 학습 뷰 C 의 실제 등급은 R(순수 후방)이다 — views_ok 가 그 사실을 그대로 담는다."]
    # 종목별: 어느 한 배치(추정 등급)가 ship 규칙을 몇 개 살리나 — 같은 종목의 규칙이 서로 다른 뷰를 요구하면 한 배치로 다 판정할 수 없다
    ship = bv[bv.status == "ship"]
    L += ["", "## 3. 종목별 최적 배치 — 한 방향에서 판정되는 ship 규칙 수\n",
          "규칙마다 학습 뷰가 따로 골라졌기 때문에(export 가 규칙별 최적 전방 뷰 채택) 같은 종목 안에서 뷰가 갈릴 수 있다. 게이팅 전에는 그 사실이 숨어 있었고, 이제는 배치 안내가 이 표를 따라야 한다.\n",
          "| 종목 | ship 규칙 | C | B | D | 최적 배치 | 한 배치로 못 보는 규칙 |", "|---|---|---|---|---|---|---|"]
    for ex, g in ship.groupby("exercise"):
        ids = g.id.unique()
        cnt = {c: int(sum(1 for rid in ids if bool(g[(g.id == rid) & (g.cls == c)]["ok"].any()))) for c in ("C", "B", "D")}
        best = max(cnt, key=lambda c: (cnt[c], c == "C"))
        miss = [rid.split("|", 1)[1] for rid in ids if not bool(g[(g.id == rid) & (g.cls == best)]["ok"].any())]
        L.append(f"| {ex} | {len(ids)} | {cnt['C']} | {cnt['B']} | {cnt['D']} | {best} ({cnt[best]}/{len(ids)}) | {', '.join(miss) or '-'} |")
    L += ["", "다음 단계 후보: (a) AUC 가 되는 뷰마다 임계값을 따로 실어(`thresholds_by_view`) 런타임이 추정 등급에 맞는 임계값을 고르게 하면 한 배치의 판정 범위가 넓어진다. (b) `ViewGuide` 의 배치 안내가 `view_best_front` 최빈값 대신 이 표의 최적 배치를 말하게 한다."]
    (HERE / "RULE_VIEWS.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[-4:]))


if __name__ == "__main__":
    main()
