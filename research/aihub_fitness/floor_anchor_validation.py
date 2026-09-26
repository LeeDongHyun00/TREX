#!/usr/bin/env python
"""정상-앵커 재배치의 앱 현실 조건 검증 — 기준선 k세트로 임계값을 옮기면 raw 이전보다 나은가.

FLOOR_QUANTILE_TRANSFER 는 앵커를 정상 클립 절반(~10개)으로 썼다. 앱의 기준선 프로토콜은
**정자세 k=3 세트**다. 여기서는 그 현실 조건(k=3, 참고로 5·10)에서 두 재배치 방식을 검증한다:

  shift — t_user = median(앵커 k개) + threshold_rel(= 채택뷰 임계값 − 채택뷰 정상 중앙값).
          rules_floor_v0.2 의 personal_baseline 경로가 이것(앱 평가 코드 재사용).
  quant — t_user = quantile(앵커 k개, q*), q* = 채택뷰 normal_fpr 에 해당하는 분위수.
비교: raw(원시 임계값 그대로), oracle(목표 뷰 라벨로 직접 적합 — 상한).
앵커는 목표 뷰 정상 클립에서 무작위 k개 × R회 반복(시드 고정), 평가는 앵커 제외 클립. 지표는 균형정확도.

추가 안전성 검사: **채택 뷰 안에서의 자기 재배치** — 시점이 안 바뀌었을 때 재배치가 성능을
깎아먹지 않는지(무해성). 깎으면 기준선이 있는 사용자에게 오히려 손해다.

출력: outputs/floor_anchor_validation.csv, outputs/FLOOR_ANCHOR_VALIDATION.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from export_floor_rules import (  # noqa: E402
    JOINTS, MIN_FRAMES, SRC, aggregate_clip, frame_features_stream,
)

RULES = HERE / "rules" / "rules_floor_v0.json"
KS = (3, 5, 10)
REPEATS = 20


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


def flags(x, op, t):
    return (x > t) if op == ">" else (x < t)


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
        ex, feat, op, thr0 = r["exercise"], r["feature"], r["op"], r["threshold"]
        v_star = r["view_best_front"]
        thr_rel = r["personal_baseline"]["threshold_rel"]
        nfpr = r["normal_fpr"]
        d_all = feats[feats.exercise == ex]
        yv = conds[(conds.exercise == ex) & (conds.condition == r["condition"])] \
            .drop_duplicates("clip_id").set_index("clip_id")["value"]
        for view in sorted(d_all.view.unique()):   # 채택 뷰 포함(자기 재배치 무해성)
            tgt = d_all[d_all.view == view].dropna(subset=[feat])
            y_t = yv.reindex(tgt.clip_id)
            m = y_t.notna().to_numpy()
            tgt = tgt[m]
            y = (~y_t[m].astype(bool)).to_numpy().astype(int)
            x = tgt[feat].to_numpy(float)
            g = tgt.performer.to_numpy()
            nrm = np.flatnonzero(y == 0)
            if min(y.sum(), len(y) - y.sum()) < 20 or len(nrm) < 12:
                continue
            rng = np.random.default_rng(abs(hash((ex, feat, view))) % (2**32))
            res = dict(exercise=ex, condition=r["condition"], feature=feat,
                       view=view, self_view=view == v_star, n=len(y),
                       bal_raw=float(balanced_accuracy_score(y, flags(x, op, thr0).astype(int))),
                       bal_oracle=oracle_balacc(x, y, g))
            q = (1 - nfpr) if op == ">" else nfpr
            for k in KS:
                accs_s, accs_q = [], []
                for _ in range(REPEATS):
                    a = rng.choice(nrm, size=k, replace=False)
                    ev = np.ones(len(y), bool)
                    ev[a] = False
                    if min(y[ev].sum(), len(y[ev]) - y[ev].sum()) < 10:
                        continue
                    xa = x[a]
                    t_shift = float(np.median(xa)) + thr_rel
                    t_quant = float(np.quantile(xa, np.clip(q, 0.01, 0.99)))
                    accs_s.append(balanced_accuracy_score(y[ev], flags(x[ev], op, t_shift).astype(int)))
                    accs_q.append(balanced_accuracy_score(y[ev], flags(x[ev], op, t_quant).astype(int)))
                res[f"bal_shift_k{k}"] = float(np.mean(accs_s)) if accs_s else np.nan
                res[f"bal_quant_k{k}"] = float(np.mean(accs_q)) if accs_q else np.nan

            # ---- 동일-수행자 앵커 (앱 상황 그대로): 앵커 = 같은 사람의 정상 k개, 평가 = 그 사람의 나머지.
            #      사람 간 분산이 앵커 노이즈에서 빠진다 — 위의 '타인 앵커'와 비교하면 그 비중이 보인다.
            for k in KS:
                pr_s, pr_r, lab = [], [], []
                for perf in np.unique(g):
                    pi = np.flatnonzero(g == perf)
                    pn = pi[y[pi] == 0]
                    if len(pn) < k + 1 or len(pi) - k < 2:
                        continue
                    for _ in range(5):
                        a = rng.choice(pn, size=k, replace=False)
                        ev = np.setdiff1d(pi, a)
                        t_shift = float(np.median(x[a])) + thr_rel
                        pr_s.extend(flags(x[ev], op, t_shift).astype(int).tolist())
                        pr_r.extend(flags(x[ev], op, thr0).astype(int).tolist())
                        lab.extend(y[ev].tolist())
                lab = np.asarray(lab)
                if len(lab) >= 40 and 0 < lab.sum() < len(lab):
                    res[f"bal_selfshift_k{k}"] = float(balanced_accuracy_score(lab, pr_s))
                    res[f"bal_selfraw_k{k}"] = float(balanced_accuracy_score(lab, pr_r))
                else:
                    res[f"bal_selfshift_k{k}"] = np.nan
                    res[f"bal_selfraw_k{k}"] = np.nan
            rows.append(res)
    out = pd.DataFrame(rows)
    out.to_csv(HERE / "outputs" / "floor_anchor_validation.csv", index=False, encoding="utf-8-sig")

    cross = out[~out.self_view]
    selfv = out[out.self_view]
    L = ["# 정상-앵커 재배치 검증 — 기준선 k세트로 임계값을 옮기면 나아지는가\n",
         f"- 규칙 {out[['exercise','condition']].drop_duplicates().shape[0]}개, 교차 뷰 {len(cross)}건 + 채택 뷰(자기 재배치) {len(selfv)}건. "
         f"앵커 = 목표 뷰 정상 클립 무작위 k개 × {REPEATS}회 평균, 평가는 앵커 제외. 균형정확도(위반=양성)",
         "",
         "## 1. 교차 뷰 (시점이 바뀌었을 때 — 앱의 실제 상황에 대응)\n",
         "| 방법 | k=3 | k=5 | k=10 | 비고 |", "|---|---|---|---|---|",
         f"| raw (재배치 없음) | {cross.bal_raw.median():.3f} | ← | ← | 현재 상태 |",
         f"| **shift (중앙값 이동 = v0.2 경로)** | **{cross.bal_shift_k3.median():.3f}** | {cross.bal_shift_k5.median():.3f} | {cross.bal_shift_k10.median():.3f} | 기존 personal_baseline 배관 재사용 |",
         f"| quant (normal_fpr 분위수) | {cross.bal_quant_k3.median():.3f} | {cross.bal_quant_k5.median():.3f} | {cross.bal_quant_k10.median():.3f} | k 가 작으면 극단 분위수 추정 불가 |",
         f"| oracle (목표 뷰 직접 적합) | {cross.bal_oracle.median():.3f} | ← | ← | 상한 |",
         ""]
    for k in KS:
        ds = cross[f"bal_shift_k{k}"] - cross.bal_raw
        dq = cross[f"bal_quant_k{k}"] - cross.bal_raw
        L.append(f"- k={k} 쌍 비교: Δ(shift−raw) 중앙값 **{ds.median():+.3f}** (개선 {int((ds > .01).sum())}/{len(cross)}, 악화 {int((ds < -.01).sum())}) · "
                 f"Δ(quant−raw) {dq.median():+.3f} (개선 {int((dq > .01).sum())}, 악화 {int((dq < -.01).sum())})")
    L += ["",
          "## 1b. 동일-수행자 앵커 — 앱 상황 그대로 (앵커 = 같은 사람의 정자세 k세트, 평가 = 같은 사람의 나머지)\n",
          "| | k=3 | k=5 | k=10 |", "|---|---|---|---|"]
    for tag, d in [("교차 뷰", cross), ("채택 뷰(무해성)", selfv)]:
        cells = []
        for k in KS:
            dd = d[f"bal_selfshift_k{k}"] - d[f"bal_selfraw_k{k}"]
            cells.append(f"**{dd.median():+.3f}** ({int((dd > .01).sum())}승 {int((dd < -.01).sum())}패)")
        L.append(f"| Δ(shift−raw), {tag} | " + " | ".join(cells) + " |")
    L += ["",
          "타인 앵커(§1)와의 차이가 곧 **사람 간 분산의 비중**이다 — 앱의 앵커는 항상 동일인이므로 §1b 가 배포 판단 기준.",
          "",
          "## 2. 자기 재배치 무해성 (채택 뷰 안, 타인 앵커 — 참고)\n"]
    ds3 = selfv.bal_shift_k3 - selfv.bal_raw
    L += [f"- Δ(shift k=3 − raw) 중앙값 **{ds3.median():+.3f}** (개선 {int((ds3 > .01).sum())}/{len(selfv)}, 악화 {int((ds3 < -.01).sum())})",
          "- 재배치는 시점이 같아도 개인 체형 차이를 일부 흡수하므로 0 근처(무해)가 기대값이다. 크게 음수면 배포하면 안 된다.",
          "",
          "## 3. 조건별 (교차 뷰, k=3)\n",
          "| 종목 | 조건 | raw | shift | quant | oracle |", "|---|---|---|---|---|---|"]
    gg = cross.groupby(["exercise", "condition"]).median(numeric_only=True).reset_index()
    for _, rr in gg.sort_values("bal_oracle", ascending=False).iterrows():
        L.append(f"| {rr.exercise} | {rr.condition[:16]} | {rr.bal_raw:.2f} | {rr.bal_shift_k3:.2f} | {rr.bal_quant_k3:.2f} | {rr.bal_oracle:.2f} |")
    L += ["",
          "## 한계\n",
          "- 교차 뷰는 **각도 변화만** 검증한다 — 높이 변화(서있는 높이 → 바닥)는 AIHub 로 검증 불가. 앵커가 실제 폰 위치에서 수집된다는 점이 이 갭을 우회하지만, '순위 보존이 높이 변화에도 유지된다'는 가정은 세트 로그로만 확인된다.",
          "- 앵커는 AIHub 의 **다른 수행자** 정상 클립이다. 실제 앱 앵커는 **같은 사용자**의 세트라 세트 간 분산이 더 작을 가능성이 높다 — 여기 수치는 보수적(불리한) 추정이다.",
          "- 채택 뷰 임계값·threshold_rel 은 전체 데이터로 적합됐고 자기 재배치 평가와 표본이 겹친다(§2 는 무해성 확인용이지 이득 추정용이 아님)."]
    (HERE / "outputs" / "FLOOR_ANCHOR_VALIDATION.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:30]))
    print(f"\n[done] → outputs/FLOOR_ANCHOR_VALIDATION.md")


if __name__ == "__main__":
    main()
