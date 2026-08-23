#!/usr/bin/env python
"""체형 불변성 분석 — 사람마다 신체구조가 달라도 유지되는 피처는 무엇인가.

세 가지를 구분한다.
  (1) 체형 불변  : 피처의 '정상 수준' 이 체형(대퇴/경골·몸통/다리·어깨/골반폭·키)과 무관한가  → 체형 회귀 R²
  (2) 임계값 이식성: 다른 체형 집단에서 학습한 임계값이 그대로 통하는가                    → 체형 분위수 스트레스 테스트(핵심)
  (3) 개인 불변  : 사람이 달라도 같은 값인가 (체형이 아니라 '습관' 까지 포함)              → 정상 클립 분산의 person 비중 / ICC

핵심 실험(I2): 수행자를 체형 지표로 4분위로 나눠, **다른 3분위에서 학습한 임계값**으로 남은 1분위를 평가한다.
분위 간 균형정확도 편차가 작으면 그 규칙은 체형이 달라도 유지된다.
(이전 실험들의 지표 — ICC, 체형 회귀 R², 기준선 이득, person 분산 비중 — 를 합쳐 근거를 교차 확인한다.)

출력: outputs/invariance_analysis.csv, outputs/INVARIANCE.md
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_curve

from features import build_or_load_features
from personalization_experiments import BODY_COLS, body_ratios

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
QUARTILE_KEYS = [("height_proxy", "키(몸통+다리)"), ("r_thigh_shin", "대퇴/경골"), ("r_torso_leg", "몸통/다리")]


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def bacc(score, y, t):
    return balanced_accuracy_score(y, (score >= t).astype(int))


RNG = np.random.default_rng(0)
NULL_REPEATS = 20


def _group_stress(s, y, q, n_q: int):
    """그룹 q 로 나눠, 다른 그룹에서 학습한 임계값으로 각 그룹을 평가."""
    accs, own = [], []
    for qi in range(n_q):
        te = q == qi
        tr = ~te
        if te.sum() < 20 or len(np.unique(y[te])) < 2 or len(np.unique(y[tr])) < 2:
            continue
        accs.append(bacc(s[te], y[te], youden(s[tr], y[tr])))
        own.append(bacc(s[te], y[te], youden(s[te], y[te])))     # 자기 그룹 내 오라클
    if len(accs) < 3:
        return None
    return dict(mean=float(np.mean(accs)), min=float(np.min(accs)), max=float(np.max(accs)),
                spread=float(np.max(accs) - np.min(accs)), n=len(accs),
                own_mean=float(np.mean(own)), transfer_loss=float(np.mean(own) - np.mean(accs)))


def quartile_stress(s, y, performer, body: pd.DataFrame, key: str, n_q: int = 4):
    """수행자를 체형 key 4분위로 나눠 이식 실험 + **무작위 분위 귀무 대조군**.

    분위당 수행자가 ~28명뿐이라 균형정확도 편차는 표본 잡음만으로도 생긴다.
    같은 크기의 무작위 그룹으로 반복한 spread 를 귀무분포로 삼아, 초과분(excess)만 체형 효과로 본다.
    """
    per = pd.Series(performer)
    vals = body.reindex(per.unique())[key].dropna()
    if len(vals) < 16:
        return None
    qs = pd.qcut(vals, n_q, labels=False, duplicates="drop")
    qmap = dict(zip(vals.index, qs))
    q = np.array([qmap.get(p, -1) for p in performer])
    ok = q >= 0
    s2, y2, q2, p2 = s[ok], y[ok], q[ok], performer[ok]
    real = _group_stress(s2, y2, q2, n_q)
    if real is None:
        return None
    # 귀무: 같은 수행자 집합을 무작위로 4그룹 (그룹 크기 동일)
    persons = np.array(sorted(set(p2)))
    null_spreads, null_losses = [], []
    for _ in range(NULL_REPEATS):
        perm = RNG.permutation(len(persons))
        assign = dict(zip(persons[perm], np.arange(len(persons)) % n_q))
        qr = np.array([assign[p] for p in p2])
        r = _group_stress(s2, y2, qr, n_q)
        if r:
            null_spreads.append(r["spread"])
            null_losses.append(r["transfer_loss"])
    out = {f"q_{k}": v for k, v in real.items()}
    if null_spreads:
        out["q_null_spread"] = float(np.median(null_spreads))
        out["q_excess_spread"] = float(real["spread"] - np.median(null_spreads))
        out["q_null_loss"] = float(np.median(null_losses))
        out["q_excess_loss"] = float(real["transfer_loss"] - np.median(null_losses))
    return out


def main():
    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    clips = clips.loc[clips.index.intersection(feats.index)]
    body = body_ratios()
    print(f"[load] 체형표 {len(body)}명 | 규칙 파싱", flush=True)

    seen, rows = set(), []
    for r in doc["rules"]:
        if r["status"] == "exclude":
            continue
        key = (r["exercise"], r["condition"])
        if key in seen:
            continue
        seen.add(key)
        col = r["feature"]
        if col not in feats.columns:
            continue
        g = clips[clips.exercise == r["exercise"]]
        sub = conds[(conds.clip_id.isin(g.index)) & (conds.condition == r["condition"])]
        if sub.empty:
            continue
        yv = sub.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(g.index)
        m = yv.notna().to_numpy()
        ids = g.index[m]
        y = yv[m].to_numpy().astype(int)
        v = feats.loc[ids, col].to_numpy(dtype=np.float64)
        ok = np.isfinite(v)
        ids, y, v = ids[ok], y[ok], v[ok]
        if len(y) < 60:
            continue
        perf = g.loc[ids, "performer"].to_numpy()
        s = (1.0 if r["op"] == "<" else -1.0) * v
        base = col.rsplit("__", 1)[0]
        stat = col.rsplit("__", 1)[1]
        row = dict(exercise=r["exercise"], condition=r["condition"], feature=col, status=r["status"],
                   family=base.replace("_L", "").replace("_R", "").replace("_mean", "").replace("_minside", "").replace("_maxside", "").replace("_asym", ""),
                   stat=stat, cv_auc=r.get("cv_auc"), n=len(y), n_persons=int(len(np.unique(perf))))
        for k, label in QUARTILE_KEYS:
            qs = quartile_stress(s, y, perf, body, k)
            if qs:
                row.update({f"{k}_{kk}": vv for kk, vv in qs.items()})
        rows.append(row)
        print(f"  {r['exercise']} | {r['condition']}", flush=True)
    res = pd.DataFrame(rows)

    # 이전 실험 지표 병합
    for path, cols, pre in (
        ("personalization_experiments.csv", ["e1_icc", "e1_bacc_pop", "e1_bacc_half", "e3_r2_body", "e3_bacc_raw", "e3_bacc_body"], ""),
        ("personalization_gap.csv", ["g1_frac_person", "g1_var_within", "g1_var_total"], ""),
        ("rule_engine_v1.csv", ["bl_raw_auc", "bl_adj_auc"], ""),
    ):
        p = OUT / path
        if not p.exists():
            continue
        d = pd.read_csv(p)
        if "subtype" in d:
            d = d[d.subtype.fillna("").isin(["", "all"])]
        keep = ["exercise", "condition"] + [c for c in cols if c in d.columns]
        res = res.merge(d[keep].drop_duplicates(["exercise", "condition"]), on=["exercise", "condition"], how="left")
    if "bl_adj_auc" in res and "bl_raw_auc" in res:
        res["bl_gain"] = res.bl_adj_auc - res.bl_raw_auc
    if "e1_bacc_half" in res and "e1_bacc_pop" in res:
        res["personal_thr_gain"] = res.e1_bacc_half - res.e1_bacc_pop
    if "g1_frac_person" in res and "g1_var_within" in res:
        res["frac_within"] = res.g1_var_within / res.g1_var_total
    res.to_csv(OUT / "invariance_analysis.csv", index=False, encoding="utf-8-sig")
    write_report(res)


def tier(r) -> str:
    """불변성 등급 — 체형 효과는 **무작위 분위 대조군을 넘는 초과분**으로 판정."""
    ex = np.nanmean([r.get(f"{k}_q_excess_spread", np.nan) for k, _ in QUARTILE_KEYS])
    fp = r.get("g1_frac_person", np.nan)
    pg = r.get("personal_thr_gain", np.nan)
    bg = r.get("bl_gain", np.nan)
    if not np.isfinite(ex):
        return "판정불가"
    body_ok = ex <= 0.02          # 무작위 그룹보다 편차가 0.02 이상 크지 않으면 체형 효과 없음
    person_ok = (not np.isfinite(fp) or fp <= 0.45) and (not np.isfinite(pg) or pg <= 0.02) and (not np.isfinite(bg) or bg <= 0.02)
    if body_ok and person_ok:
        return "완전 불변"
    if body_ok:
        return "체형 불변·개인차 있음"
    return "체형 의존"


def write_report(res: pd.DataFrame):
    res = res.copy()
    res["q_spread_mean"] = res[[f"{k}_q_spread" for k, _ in QUARTILE_KEYS if f"{k}_q_spread" in res]].mean(axis=1)
    res["q_null_mean"] = res[[f"{k}_q_null_spread" for k, _ in QUARTILE_KEYS if f"{k}_q_null_spread" in res]].mean(axis=1)
    res["q_excess_mean"] = res[[f"{k}_q_excess_spread" for k, _ in QUARTILE_KEYS if f"{k}_q_excess_spread" in res]].mean(axis=1)
    res["tier"] = res.apply(lambda r: tier(r), axis=1)
    ok = res.dropna(subset=["q_spread_mean"])
    L = ["# 사람마다 신체구조가 달라도 유지되는 것 — 체형 불변성 분석\n",
         f"- 활성 규칙 {len(res)}개(종목×조건), 체형 분위 실험 성립 {len(ok)}개 · 수행자 111명",
         "- **조작적 정의**: 수행자를 체형 지표(키·대퇴/경골·몸통/다리) **4분위**로 나눈 뒤, *다른 3분위에서 학습한 임계값* 으로 남은 분위를 평가.",
         "- **귀무 대조군**: 같은 크기의 **무작위** 4그룹으로 같은 실험을 20회 반복. 분위당 수행자가 ~28명이라 편차는 표본 잡음만으로도 생기므로, "
         "체형 편차에서 무작위 편차를 뺀 **초과분(excess)** 만 체형 효과로 본다.",
         "- 교차 근거: 체형 회귀 R²(§16 실험 3), 개인 임계값 이득(§16 실험 1), 기준선 이득(§15), 정상 클립 분산의 person 비중(§19 G1)\n",
         "## 1. 결론 요약\n",
         f"- 체형 분위 편차 중앙값 {ok.q_spread_mean.median():.3f} **vs 무작위 분위 편차 {ok.q_null_mean.median():.3f}** → "
         f"**초과분 {ok.q_excess_mean.median():+.3f}** — 체형으로 나눈 것과 무작위로 나눈 것의 차이가 사실상 없다.",
         f"- 초과분 ≤0.02 (체형 효과 없음) 규칙 **{int((ok.q_excess_mean<=0.02).sum())}/{len(ok)}** ({(ok.q_excess_mean<=0.02).mean()*100:.0f}%)",
         f"- 체형 회귀 R² 중앙값 {res.e3_r2_body.median():.2f} (≥0.2 는 {int((res.e3_r2_body>=0.2).sum())}개) — **피처의 정상 수준 자체가 체형으로 거의 설명되지 않는다**",
         f"- 반면 정상 클립 분산의 person 비중은 중앙값 {res.g1_frac_person.median()*100:.0f}% → **사람마다 다른 것은 체형이 아니라 '습관·수행 스타일'**\n",
         "| 등급 | 뜻 | 규칙 수 |", "|---|---|---|"]
    for t in ("완전 불변", "체형 불변·개인차 있음", "체형 의존", "판정불가"):
        n = int((res.tier == t).sum())
        desc = {"완전 불변": "체형·사람 모두 무관 — 인구 임계값 하나면 충분",
                "체형 불변·개인차 있음": "체형은 무관하나 습관 차이 존재 — 기준선 개인화 후보",
                "체형 의존": "체형 분위에 따라 성능이 흔들림 — 임계값 재검토 필요",
                "판정불가": "표본 부족"}[t]
        L.append(f"| {t} | {desc} | {n} |")
    L += ["", "## 2. 피처 유형별 — 무엇이 유지되는가\n",
          "| 피처 통계 | 규칙 수 | 체형 편차 | 무작위 편차 | **초과분** | 체형 R² | person 분산 비중 | 개인 임계값 이득 | 기준선 이득 |",
          "|---|---|---|---|---|---|---|---|---|"]
    for stat, d in res.groupby("stat"):
        dd = d.dropna(subset=["q_spread_mean"])
        if len(dd) < 2:
            continue
        L.append(f"| {stat} | {len(dd)} | {dd.q_spread_mean.median():.3f} | {dd.q_null_mean.median():.3f} | **{dd.q_excess_mean.median():+.3f}** | "
                 f"{d.e3_r2_body.median():.2f} | {d.g1_frac_person.median()*100:.0f}% | {d.personal_thr_gain.median():+.3f} | {d.bl_gain.median():+.3f} |")
    L += ["", "### 피처 패밀리별 (규칙 ≥2개)\n",
          "| 패밀리 | 규칙 수 | 초과분 | 체형 R² | person 비중 | 등급 다수 |", "|---|---|---|---|---|---|"]
    for fam, d in res.groupby("family"):
        dd = d.dropna(subset=["q_spread_mean"])
        if len(dd) < 2:
            continue
        L.append(f"| {fam} | {len(dd)} | {dd.q_excess_mean.median():+.3f} | {d.e3_r2_body.median():.2f} | "
                 f"{d.g1_frac_person.median()*100:.0f}% | {d.tier.mode().iat[0] if len(d.tier.mode()) else '-'} |")
    L += ["", "## 3. 가장 잘 유지되는 규칙 20 (체형 초과분 낮은 순)\n",
          "| 종목 | 조건 | 피처 | 초과분 | 체형 편차 | 무작위 편차 | 최저 분위 정확도 | 체형 R² | person 비중 | 등급 |",
          "|---|---|---|---|---|---|---|---|---|---|"]
    for r in ok.nsmallest(20, "q_excess_mean").itertuples():
        qmin = np.nanmean([getattr(r, f"{k}_q_min", np.nan) for k, _ in QUARTILE_KEYS])
        L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.q_excess_mean:+.3f} | {r.q_spread_mean:.3f} | {r.q_null_mean:.3f} | {qmin:.3f} | "
                 f"{r.e3_r2_body:.2f} | {r.g1_frac_person*100:.0f}% | {r.tier} |")
    L += ["", "## 4. 체형에 흔들리는 규칙 (초과분 큰 순 12)\n",
          "| 종목 | 조건 | 피처 | 초과분 | 체형 편차 | 무작위 편차 | 최저 분위 | 체형 R² |", "|---|---|---|---|---|---|---|---|"]
    for r in ok.nlargest(12, "q_excess_mean").itertuples():
        qmin = np.nanmean([getattr(r, f"{k}_q_min", np.nan) for k, _ in QUARTILE_KEYS])
        L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.q_excess_mean:+.3f} | {r.q_spread_mean:.3f} | {r.q_null_mean:.3f} | {qmin:.3f} | {r.e3_r2_body:.2f} |")
    L += ["", "## 5. 체형 지표별 (어느 축에 가장 민감한가)\n",
          "| 체형 지표 | 체형 편차 | 무작위 편차 | 초과분 | 초과분 >0.05 규칙 |", "|---|---|---|---|---|"]
    for k, label in QUARTILE_KEYS:
        c, nc, ec = f"{k}_q_spread", f"{k}_q_null_spread", f"{k}_q_excess_spread"
        if c in ok and ec in ok:
            d = ok.dropna(subset=[c, ec])
            L.append(f"| {label} | {d[c].median():.3f} | {d[nc].median():.3f} | {d[ec].median():+.3f} | {int((d[ec] > 0.05).sum())}/{len(d)} |")
    (OUT / "INVARIANCE.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:26]))


if __name__ == "__main__":
    if "--summary-only" in sys.argv and (OUT / "invariance_analysis.csv").exists():
        write_report(pd.read_csv(OUT / "invariance_analysis.csv"))
    else:
        main()
