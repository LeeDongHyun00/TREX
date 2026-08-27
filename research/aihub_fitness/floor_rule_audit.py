#!/usr/bin/env python
"""바닥 규칙 판정 기준 감사 — 조건명이 말하는 것과 실제로 재는 것이 일치하는가.

rules_floor_v0.1 은 '충실도 게이트 통과 피처 중 AUC 최대'로 자동 선택됐다. 그 결과
조건명과 무관한 피처가 뽑힐 수 있다(§21 요건 1: 한 조건 = 한 메커니즘).
여기서는 각 규칙에 대해 **조건명에 부합하는 대안 피처**를 직접 지정해 AUC 를 비교한다.

  - 채택 피처가 대안보다 유의하게 낫다  → 프록시지만 데이터가 지지
  - 대안과 비슷하거나 못하다            → 조건명에 맞는 피처로 바꾸는 게 옳다(해석 가능성 이득)
  - 둘 다 낮다                          → 그 조건은 관측 불가에 가깝다

출력: outputs/floor_rule_audit.csv, FLOOR_RULE_AUDIT.md
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from export_floor_rules import build_features, SRC  # noqa: E402

RULES_JSON = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"

# 조건명이 가리키는 메커니즘에 **직접** 대응하는 후보들.
# (조건명 → 그 조건을 문자 그대로 재는 피처들. 채택 피처와 비교하기 위한 것)
NATURAL = {
    "고개 젖힘/숙임 여부": ["head_trunk_ang__mean", "head_trunk_ang__min", "head_trunk_ang__max"],
    "고개 숙임 여부": ["head_trunk_ang__mean", "head_trunk_ang__min"],
    "고개 들지 않기": ["head_trunk_ang__mean", "head_trunk_ang__max", "head_ground__mean", "head_ground__max"],
    "시선 배꼽 고정": ["head_trunk_ang__mean", "head_trunk_ang__std"],
    # 견갑골 = 어깨. 머리로 대신하면 '목만 당기고 어깨는 안 뜨는' 대표 오류를 놓친다
    "견갑골이 지면으로부터 충분히 올라옴": ["shoulder_ground__max", "shoulder_ground__mean", "shoulder_dev__max"],
    # 경추 = 목/머리. 골반으로 대신하는 것이 타당한지
    "경추 중립 또는 후인(retraction) 유지": ["head_trunk_ang__mean", "head_trunk_ang__std", "head_ground__mean"],
    # 몸통-엉덩이 정렬 = 골반이 어깨-발목 선에서 벗어남 (각도보다 직접적)
    "몸통과 엉덩이의 정렬 유지": ["hip_dev_ankle__mean", "hip_dev_ankle__min", "hip_dev_ankle__max", "trunk_ankle_ang__mean"],
    "수축시 무릎부터 어깨까지 일자": ["hip_dev_ankle__max", "hip_dev_ankle__mean", "trunk_ankle_ang__max"],
    # 손 위치 = 손이 몸통축 대비 어디인가
    "손의 위치 가슴 중앙 여부": ["hand_shoulder_off__mean", "shoulder_dev__mean", "shoulder_arm_ang__mean"],
    # 가슴 이동 = 팔꿈치 굽힘 깊이 / 어깨-손목 거리
    "가슴의 충분한 이동": ["wrist_shoulder_d__min", "elbow_ang__min", "shoulder_ground__min"],
    # 다리-지면 거리 = 발목/무릎의 지면 대비 높이 (각도보다 직접적)
    "다리와 지면 사이 적당한 거리": ["ankle_ground__mean", "ankle_ground__min", "knee_ground__mean", "hip_ang__mean"],
}


def auc_cv(x: np.ndarray, y: np.ndarray, g: np.ndarray) -> float:
    ok = np.isfinite(x)
    x, y, g = x[ok], y[ok], g[ok]
    if len(np.unique(y)) < 2 or len(np.unique(g)) < 3:
        return np.nan
    out = []
    for tr, te in GroupKFold(min(5, len(np.unique(g)))).split(x, y, g):
        if len(np.unique(y[tr])) < 2 or len(np.unique(y[te])) < 2:
            continue
        s = 1.0 if roc_auc_score(y[tr], x[tr]) >= 0.5 else -1.0
        out.append(roc_auc_score(y[te], s * x[te]))
    return float(np.mean(out)) if out else np.nan


def main():
    doc = json.load(open(RULES_JSON, encoding="utf-8"))
    rules = doc["rules"]
    feats = build_features()
    conds = pd.read_parquet(SRC / "conditions.parquet")
    print(f"[load] 클립×뷰 {len(feats)} · 규칙 {len(rules)}", flush=True)

    rows = []
    for r in rules:
        ex, cond, view = r["exercise"], r["condition"], r["view_best_front"]
        d = feats[(feats.exercise == ex) & (feats.view == view)]
        y = conds[(conds.exercise == ex) & (conds.condition == cond)].drop_duplicates("clip_id") \
            .set_index("clip_id")["value"].reindex(d.clip_id)
        m = y.notna().to_numpy()
        if m.sum() < 60:
            continue
        yv = (~y[m].astype(bool)).to_numpy().astype(int)   # 위반 = 1
        g = d.performer.to_numpy()[m]
        adopted = r["feature"]
        a_adopted = auc_cv(d[adopted].to_numpy(float)[m], yv, g) if adopted in d else np.nan
        best_alt, best_auc = "", np.nan
        for alt in NATURAL.get(cond, []):
            if alt == adopted or alt not in d.columns:
                continue
            a = auc_cv(d[alt].to_numpy(float)[m], yv, g)
            if np.isfinite(a) and (not np.isfinite(best_auc) or a > best_auc):
                best_alt, best_auc = alt, a
        rows.append(dict(exercise=ex, condition=cond, view=view, n=int(m.sum()),
                         adopted=adopted, auc_adopted=a_adopted,
                         natural=best_alt, auc_natural=best_auc,
                         gain=(a_adopted - best_auc) if np.isfinite(best_auc) else np.nan,
                         rho=r.get("mp_fidelity")))
        print(f"  {ex:12s} {cond[:20]:20s} 채택 {adopted:24s} {a_adopted:.3f} | "
              f"자연 {best_alt:24s} {best_auc if np.isfinite(best_auc) else float('nan'):.3f}", flush=True)

    df = pd.DataFrame(rows)
    df.to_csv(HERE / "outputs" / "floor_rule_audit.csv", index=False, encoding="utf-8-sig")

    L = ["# 바닥 규칙 판정 기준 감사 (rules_floor_v0.1)\n",
         "규칙은 '충실도 게이트 통과 피처 중 AUC 최대'로 **자동 선택**됐다. 그래서 조건명과 무관한 피처가 뽑힐 수 있다.",
         "여기서는 조건명이 문자 그대로 가리키는 **자연 피처**를 직접 지정해 채택 피처와 비교한다.\n",
         "| 종목 | 조건 | 채택 피처 | AUC | 조건명에 맞는 피처 | AUC | 차이 | 판정 |",
         "|---|---|---|---|---|---|---|---|"]
    for _, r in df.sort_values("gain").iterrows():
        if not np.isfinite(r.gain):
            verdict = "대안 없음"
        elif r.gain < -0.02:
            verdict = "❌ **조건명 피처가 더 낫다 — 교체 검토**"
        elif r.gain < 0.02:
            verdict = "⚠ 차이 없음 — 해석 가능한 쪽으로"
        else:
            verdict = "✅ 프록시가 우세"
        L.append(f"| {r.exercise} | {r.condition} | `{r.adopted}` | {r.auc_adopted:.3f} | "
                 f"`{r.natural or '—'}` | {r.auc_natural:.3f} | {r.gain:+.3f} | {verdict} |"
                 if np.isfinite(r.auc_natural) else
                 f"| {r.exercise} | {r.condition} | `{r.adopted}` | {r.auc_adopted:.3f} | — | — | — | 대안 없음 |")
    (HERE / "FLOOR_RULE_AUDIT.md").write_text("\n".join(L), encoding="utf-8")
    print(f"\n[done] → FLOOR_RULE_AUDIT.md")
    print(f"교체 검토(차이 < -0.02): {int((df.gain < -0.02).sum())}건")
    print(f"차이 없음(|차이| < 0.02): {int((df.gain.abs() < 0.02).sum())}건")


if __name__ == "__main__":
    main()
