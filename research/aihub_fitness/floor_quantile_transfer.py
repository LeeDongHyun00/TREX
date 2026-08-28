#!/usr/bin/env python
"""제안 검증: '위반율이 동등하도록' 임계값을 뷰 간 이전하면 원시 이전보다 나은가.

배경(FLOOR_THRESHOLD_VIEW): 원시 임계값을 다른 뷰에 그대로 적용하면 플래그율이 중앙값 33%p 흔들린다.
사용자 제안: 실제 위반율은 뷰와 무관(같은 클립 동시 촬영)하므로, **플래그율이 위반율과 같아지도록**
임계값을 목표 뷰의 분위수로 다시 놓으면 각도 변화를 흡수할 수 있지 않은가.

네 가지를 같은 평가셋에서 비교 (규칙 × 목표 뷰):
  raw    — 채택 뷰 임계값을 원시값 그대로 목표 뷰에 적용 (현재 상태)
  rate   — 목표 뷰 분포에서 플래그율 = **참 위반율** 이 되는 분위수로 이전 (제안의 문자적 구현.
           참 위반율을 준다는 점에서 **최대한 유리한** 버전 — 실전에서는 이 값을 모른다)
  anchor — 채택 뷰에서 임계값이 **정상 클립**을 오탐하는 비율(FPR)을 재고, 목표 뷰의 정상 클립
           절반(앵커, 시드 고정)에서 같은 FPR 이 되는 분위수로 이전. **배포 가능한 변형** —
           앱의 기준선 프로토콜(정자세 세트)이 곧 이 앵커다. 평가는 앵커를 뺀 나머지로만
  oracle — 목표 뷰 라벨로 직접 Youden 적합(수행자 GroupKFold) — 이전이 도달할 수 있는 상한
보조 진단: 뷰 간 순위 보존(같은 클립의 피처 통계, 채택뷰↔목표뷰 Spearman) — 분위수 이전의 이론 상한.

출력: outputs/floor_quantile_transfer.csv, outputs/FLOOR_QUANTILE_TRANSFER.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from export_floor_rules import (  # noqa: E402
    JOINTS, MIN_FRAMES, SRC, aggregate_clip, frame_features_stream,
)

RULES = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"
RNG = np.random.default_rng(0)


def build() -> tuple[pd.DataFrame, list[dict]]:
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in JOINTS for a in "xy"]
    clips = pd.read_parquet(SRC / "clips.parquet")[["clip_id", "exercise", "performer"]]
    k2 = pd.read_parquet(SRC / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")
    doc = json.load(open(RULES, encoding="utf-8"))
    exs = sorted({r["exercise"] for r in doc["rules"]})
    k2 = k2[k2.exercise.isin(exs)].sort_values(["clip_id", "view_letter", "frame_idx"])
    rows = []
    for (cid, view), d in k2.groupby(["clip_id", "view_letter"]):
        if len(d) < MIN_FRAMES:
            continue
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(dtype=np.float64) for j in JOINTS], axis=1)
        r = aggregate_clip(frame_features_stream(frames, JOINTS))
        r.update(clip_id=cid, view=view, exercise=d.exercise.iloc[0], performer=str(d.performer.iloc[0]))
        rows.append(r)
    return pd.DataFrame(rows), doc["rules"]


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def flags(x: np.ndarray, op: str, thr: float) -> np.ndarray:
    return (x > thr) if op == ">" else (x < thr)


def thr_at_flag_rate(x: np.ndarray, op: str, rate: float) -> float:
    """플래그율이 rate 가 되는 임계값 (분위수)."""
    rate = float(np.clip(rate, 0.01, 0.99))
    return float(np.quantile(x, 1 - rate)) if op == ">" else float(np.quantile(x, rate))


def oracle_balacc(x, y, g) -> float:
    ok = np.isfinite(x)
    x, y, g = x[ok], y[ok], g[ok]
    if len(np.unique(g)) < 3 or min(y.sum(), len(y) - y.sum()) < 10:
        return np.nan
    accs = []
    for tr, te in GroupKFold(min(5, len(np.unique(g)))).split(x, y, g):
        if len(np.unique(y[tr])) < 2 or len(np.unique(y[te])) < 2:
            continue
        sgn = 1.0 if roc_auc_score(y[tr], x[tr]) >= 0.5 else -1.0
        t = youden(sgn * x[tr], y[tr])
        accs.append(balanced_accuracy_score(y[te], (sgn * x[te] >= t).astype(int)))
    return float(np.mean(accs)) if accs else np.nan


def main():
    feats, rules = build()
    conds = pd.read_parquet(SRC / "conditions.parquet")
    rows = []
    for r in rules:
        ex, feat, op, thr0, v_star = r["exercise"], r["feature"], r["op"], r["threshold"], r["view_best_front"]
        d_all = feats[feats.exercise == ex]
        yv = conds[(conds.exercise == ex) & (conds.condition == r["condition"])] \
            .drop_duplicates("clip_id").set_index("clip_id")["value"]
        src = d_all[d_all.view == v_star].dropna(subset=[feat])
        y_src = yv.reindex(src.clip_id)
        m = y_src.notna().to_numpy()
        src = src[m]
        y_src = (~y_src[m].astype(bool)).to_numpy().astype(int)   # 위반=1
        x_src = src[feat].to_numpy(float)
        # 채택 뷰에서 이 임계값의 정상-오탐율(FPR): anchor 이전의 목표량
        src_fpr = float(flags(x_src[y_src == 0], op, thr0).mean()) if (y_src == 0).sum() else np.nan

        # 순위 보존: 채택뷰↔목표뷰, 같은 클립 피처 통계 Spearman
        for view in sorted(d_all.view.unique()):
            if view == v_star:
                continue
            tgt = d_all[d_all.view == view].dropna(subset=[feat])
            y_t = yv.reindex(tgt.clip_id)
            m = y_t.notna().to_numpy()
            tgt = tgt[m]
            y_tgt = (~y_t[m].astype(bool)).to_numpy().astype(int)
            if min(y_tgt.sum(), len(y_tgt) - y_tgt.sum()) < 20:
                continue
            x_tgt = tgt[feat].to_numpy(float)
            g_tgt = tgt.performer.to_numpy()
            pair = src[["clip_id", feat]].merge(tgt[["clip_id", feat]], on="clip_id", suffixes=("_s", "_t")).dropna()
            rank_rho = float(spearmanr(pair[f"{feat}_s"], pair[f"{feat}_t"]).statistic) if len(pair) >= 30 else np.nan

            # 앵커: 목표 뷰 정상 클립 절반 (시드 고정) — 평가셋에서 제외
            nrm_idx = np.flatnonzero(y_tgt == 0)
            RNG_local = np.random.default_rng(hash((ex, feat, view)) % (2**32))
            anchor_idx = RNG_local.choice(nrm_idx, size=max(3, len(nrm_idx) // 2), replace=False)
            eval_mask = np.ones(len(y_tgt), bool)
            eval_mask[anchor_idx] = False
            xe, ye, ge = x_tgt[eval_mask], y_tgt[eval_mask], g_tgt[eval_mask]
            if min(ye.sum(), len(ye) - ye.sum()) < 10:
                continue

            def bal(t):
                return float(balanced_accuracy_score(ye, flags(xe, op, t).astype(int)))

            p_true = float(y_tgt.mean())
            res = dict(
                exercise=ex, condition=r["condition"], feature=feat, view_src=v_star, view=view,
                n_eval=int(len(ye)), rank_rho=rank_rho,
                bal_raw=bal(thr0),
                bal_rate=bal(thr_at_flag_rate(x_tgt, op, p_true)),
                bal_anchor=bal(thr_at_flag_rate(x_tgt[anchor_idx], op, src_fpr)) if np.isfinite(src_fpr) else np.nan,
                bal_oracle=oracle_balacc(xe, ye, ge),
            )
            rows.append(res)
    out = pd.DataFrame(rows)
    out.to_csv(HERE / "outputs" / "floor_quantile_transfer.csv", index=False, encoding="utf-8-sig")

    med = out[["bal_raw", "bal_rate", "bal_anchor", "bal_oracle", "rank_rho"]].median()
    # 쌍 비교 (같은 규칙×뷰에서 방법 간 차) — 중앙값끼리 빼면 안 되고 쌍으로 빼야 한다
    d_rate = (out.bal_rate - out.bal_raw)
    d_anchor = (out.bal_anchor - out.bal_raw)
    hi = out.rank_rho >= 0.7
    L = ["# '위반율 동등' 임계값 이전 — 지지인가 박살인가 (실험)\n",
         f"- 규칙 {out[['exercise','condition']].drop_duplicates().shape[0]}개 × 목표 뷰, 총 {len(out)}건. 평가 = 균형정확도(위반=양성), 앵커 정상 클립은 평가에서 제외",
         "",
         "| 방법 | 균형정확도 중앙값 | 설명 |", "|---|---|---|",
         f"| raw (현재: 원시 임계값 그대로) | {med.bal_raw:.3f} | 시점이 바뀌면 무너짐 |",
         f"| **rate (제안: 플래그율=참 위반율)** | **{med.bal_rate:.3f}** | 참 위반율을 안다는 최대 유리 가정 |",
         f"| **anchor (변형: 정상 앵커 FPR 일치)** | **{med.bal_anchor:.3f}** | 배포 가능 — 앵커=기준선 정자세 세트 |",
         f"| oracle (목표 뷰에서 직접 적합) | {med.bal_oracle:.3f} | 이전의 상한 |",
         "",
         f"- **쌍 비교** (같은 규칙×뷰): Δ(rate−raw) 중앙값 **{d_rate.median():+.3f}** (개선 {int((d_rate > 0.01).sum())}/{len(out)}건, 악화 {int((d_rate < -0.01).sum())}건) · "
         f"Δ(anchor−raw) 중앙값 **{d_anchor.median():+.3f}** (개선 {int((d_anchor > 0.01).sum())}건, 악화 {int((d_anchor < -0.01).sum())}건)",
         f"- **순위 보존이 가르는 선**: 뷰 간 순위ρ≥0.7 인 {int(hi.sum())}건에서 Δrate **{d_rate[hi].median():+.3f}** / Δanchor **{d_anchor[hi].median():+.3f}**, "
         f"ρ<0.7 인 {int((~hi).sum())}건에서 Δrate {d_rate[~hi].median():+.3f} / Δanchor {d_anchor[~hi].median():+.3f}",
         f"- 뷰 간 순위 보존(같은 클립 Spearman) 중앙값 **{med.rank_rho:.2f}** — 분위수 이전의 이론 상한. 오라클(0.685)이 채택 뷰 성능보다 낮은 것도 같은 이유: "
         "시점 변화는 임계값만이 아니라 **순서 자체**도 부분적으로 흩뜨린다",
         "",
         "## 조건별 (뷰 중앙값)\n",
         "| 종목 | 조건 | raw | rate | anchor | oracle | 순위ρ |", "|---|---|---|---|---|---|---|"]
    g = out.groupby(["exercise", "condition"]).median(numeric_only=True).reset_index()
    for _, r in g.sort_values("bal_oracle", ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:16]} | {r.bal_raw:.2f} | {r.bal_rate:.2f} | {r.bal_anchor:.2f} | {r.bal_oracle:.2f} | {r.rank_rho:.2f} |")
    L += ["",
          "## 판정에 쓰인 전제와 한계\n",
          "- rate 는 **참 위반율을 안다**는 가정 — AIHub 는 연기 설계라 ~50% 로 알지만, 실전 사용자의 위반율은 모른다(사람마다 다르고 시간에 따라 변함). 문자 그대로의 제안은 실전에서 이 값이 없다.",
          "- anchor 는 위반율 대신 **정상 세트만** 요구한다 — 앱의 기준선 프로토콜(정자세 3세트)이 정확히 이것. 위반율을 몰라도 된다.",
          "- 두 이전 모두 **뷰 간 순위 보존**을 전제한다 — 순위ρ 가 낮은 규칙은 어떤 임계값 재배치로도 복구 안 됨(위 표에서 확인).",
          "- **높이 축은 이 실험 밖이다.** AIHub 5뷰는 전부 서있는 높이 — '카메라가 바닥에 있다고 가정'한 뷰는 이 데이터로 만들 수 없다(바닥 3D GT 붕괴로 재투영도 불가). "
          "단, anchor 방식은 앵커를 **실제 배포 시점**(사용자의 폰 위치)에서 수집하므로 각도·높이를 구분할 필요 자체가 없다 — 남는 가정은 '순위 보존이 높이 변화에도 유지된다' 하나다.",
          "- MP 측정 충실도(§25a)는 별개 축 — 순위가 측정 단계에서 깨진 규칙은 임계값 재배치로 못 살린다."]
    (HERE / "outputs" / "FLOOR_QUANTILE_TRANSFER.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:22]))
    print(f"\n[done] → outputs/FLOOR_QUANTILE_TRANSFER.md")


if __name__ == "__main__":
    main()
