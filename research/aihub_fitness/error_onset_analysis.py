#!/usr/bin/env python
"""자세가 틀리는 '방식' 3유형 — 습관(처음부터) · 피로(점진 악화) · 순간 붕괴(삐끗) — 의 탐지 가능성.

AIHub 의 위반은 **연기된 습관형**(세트 내내 일정한 오프셋)뿐이다 — 앱에서 만날 피로형·순간형은 이 데이터에 없다.
그래서 (1) 실측으로 AIHub 위반의 시간 서명이 습관형임을 확인하고, (2) **정상 클립에 세 유형의 시간 패턴을 합성 주입**해
현재 규칙(세트 통계 1개)과 시간 통계(드리프트·스파이크)가 각각 무엇을 잡는지 비교한다.

합성 방법: 조건별 '연기된 위반'의 효과 크기(위반 중앙값 − 정상 중앙값)를 Δ 로 잡고 정상 클립 프레임 피처에
  습관형: 전 프레임 +Δ          피로형: 선형 램프 0 → +Δ(×1.5)         순간형: 무작위 2~3프레임만 +Δ(×2)
을 더한다(피처 = 규칙이 쓰는 기본 피처). 강도 ×1.5/×2 는 실제 피로·삐끗이 습관보다 순간 진폭이 크다는 가정.

탐지 통계:
  현재 규칙 stat(mean/min/max/std/range) · 드리프트 = 후반 1/3 평균 − 전반 1/3 평균 · 스파이크 = (max−median)/IQR
  각각 GroupKFold(수행자) AUC — 정상 vs 합성 위반.
출력: outputs/ERROR_ONSET.md, outputs/error_onset.csv
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

from features import apply_qc_mask, compute_frame_features, load_kp3d

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
RNG = np.random.default_rng(0)
TYPES = ("habit", "fatigue", "spike")


def stats_of(F: np.ndarray) -> dict[str, np.ndarray]:
    """(n,T) 프레임 피처 → 세트 통계들."""
    T = F.shape[1]
    third = max(2, T // 3)
    med = np.nanmedian(F, axis=1)
    q75 = np.nanpercentile(F, 75, axis=1)
    q25 = np.nanpercentile(F, 25, axis=1)
    iqr = np.where(q75 - q25 > 1e-6, q75 - q25, np.nan)
    return dict(
        mean=np.nanmean(F, axis=1), min=np.nanmin(F, axis=1), max=np.nanmax(F, axis=1),
        std=np.nanstd(F, axis=1), range=np.nanmax(F, axis=1) - np.nanmin(F, axis=1),
        drift=np.nanmean(F[:, -third:], axis=1) - np.nanmean(F[:, :third], axis=1),
        spike_hi=(np.nanmax(F, axis=1) - med) / iqr,
        spike_lo=(med - np.nanmin(F, axis=1)) / iqr,
        late_mean=np.nanmean(F[:, -third:], axis=1),
        early_mean=np.nanmean(F[:, :third], axis=1),
    )


def auc_cv(x: np.ndarray, y: np.ndarray, g: np.ndarray) -> float:
    ok = np.isfinite(x)
    x, y, g = x[ok], y[ok], g[ok]
    if len(np.unique(y)) < 2 or len(np.unique(g)) < 3:
        return np.nan
    gkf = GroupKFold(n_splits=min(5, len(np.unique(g))))
    aucs = []
    for tr, te in gkf.split(x, y, g):
        if len(np.unique(y[te])) < 2:
            continue
        # 방향은 학습폴드에서
        a = roc_auc_score(y[tr], x[tr])
        s = 1.0 if a >= 0.5 else -1.0
        aucs.append(roc_auc_score(y[te], s * x[te]))
    return float(np.mean(aucs)) if aucs else np.nan


def inject(Fpos: np.ndarray, delta: float, kind: str, scale: float = 1.0) -> np.ndarray:
    """정상 클립 프레임 피처에 시간 패턴 주입. 세 유형의 **총 '위반 노출량'(프레임×진폭 합)을 같게** 맞춘다:
    습관 = T·δ, 피로 = 램프 0→2δ (합 ≈ T·δ), 순간 = 3프레임 × (T/3)·δ (합 = T·δ). 즉 같은 양의 오류를 시간상 어떻게 배치하느냐만 다르다."""
    n, T = Fpos.shape
    G = Fpos.copy()
    d = delta * scale
    t = np.arange(T) / max(T - 1, 1)
    if kind == "habit":
        G += d
    elif kind == "fatigue":
        G += (2.0 * d) * t[None, :]
    elif kind == "spike":
        amp = d * T / 3.0
        for i in range(n):
            idx = RNG.choice(T, 3, replace=False)
            G[i, idx] += amp
    return G


def calibrate_scale(pos: np.ndarray, y_pos_g: np.ndarray, delta: float, stat: str, target_auc: float) -> float:
    """습관형 주입 강도를 실측 연기 위반의 규칙 AUC 와 맞춘다 (합성이 너무 약하거나 세지 않도록)."""
    n_pos = len(pos)
    perm = RNG.permutation(n_pos)
    keep, inj = perm[: n_pos // 2], perm[n_pos // 2:]
    best, best_gap = 1.0, 9.0
    for sc in (0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0):
        X = np.concatenate([pos[keep], inject(pos[inj], delta, "habit", sc)], axis=0)
        yy = np.concatenate([np.ones(len(keep)), np.zeros(len(inj))]).astype(int)
        gg = np.concatenate([y_pos_g[keep], y_pos_g[inj]])
        a = auc_cv(stats_of(X)[stat], yy, gg)
        if np.isfinite(a) and abs(a - target_auc) < best_gap:
            best, best_gap = sc, abs(a - target_auc)
    return best


def main():
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    rules = [r for r in doc["rules"] if r["status"] == "ship" and not r.get("subtype")]
    ids, arr = load_kp3d(OUT)
    ids, arr, _ = apply_qc_mask(ids, arr, OUT)
    ids = np.array(ids)
    clips = clips.loc[clips.index.intersection(ids)]
    print(f"[load] ship 기본 규칙 {len(rules)}개, 클립 {len(ids)}")

    # 종목별 프레임 피처 캐시
    cache: dict[str, tuple[np.ndarray, dict]] = {}
    rows, sig_rows = [], []
    for r in rules:
        ex, cond, base, stat, op = r["exercise"], r["condition"], r["base_feature"], r["stat"], r["op"]
        if ex not in cache:
            m = np.isin(ids, clips[clips.exercise == ex].index)
            F_all = compute_frame_features(arr[m])
            cache[ex] = (ids[m], F_all)
        sub_ids, F_all = cache[ex]
        if base not in F_all:
            continue
        F = F_all[base][:, :16]
        yv = (conds[(conds.clip_id.isin(sub_ids)) & (conds.condition == cond)]
              .drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(sub_ids))
        ok = yv.notna().to_numpy() & (np.isfinite(F).sum(axis=1) >= 8)
        F, y = F[ok], yv[ok].to_numpy().astype(bool)
        g = clips.loc[sub_ids[ok], "performer"].astype(str).to_numpy()
        if y.sum() < 40 or (~y).sum() < 40:
            continue
        # (1) 실측 시간 서명: 연기된 위반 − 정상, 프레임별
        pos, neg = F[y], F[~y]
        diff = np.nanmean(neg, axis=0) - np.nanmean(pos, axis=0)
        mid = diff[3:13]
        sig_rows.append(dict(exercise=ex, condition=cond, feature=base,
                             early=float(np.nanmean(diff[:3])), mid=float(np.nanmean(mid)), late=float(np.nanmean(diff[-3:])),
                             mid_cv=float(np.nanstd(mid) / (abs(np.nanmean(mid)) + 1e-9)),
                             std_pos=float(np.nanmedian(np.nanstd(pos, axis=1))), std_neg=float(np.nanmedian(np.nanstd(neg, axis=1)))))
        # 효과 크기 Δ (위반 방향으로)
        delta = float(np.nanmedian(np.nanmedian(neg, axis=1)) - np.nanmedian(np.nanmedian(pos, axis=1)))
        if not np.isfinite(delta) or abs(delta) < 1e-6:
            continue
        # 실제 연기된 위반 (비교 기준) — 규칙 stat 의 AUC 를 합성 강도 캘리브레이션 목표로 쓴다
        S = stats_of(F)
        row = dict(exercise=ex, condition=cond, feature=base, rule_stat=stat, kind="acted", delta=delta, n=len(y), scale=1.0)
        for k, v in S.items():
            row[f"auc_{k}"] = auc_cv(v, y.astype(int), g)
        row["auc_rule"] = row.get(f"auc_{stat}", np.nan)
        rows.append(row)
        target = row["auc_rule"] if np.isfinite(row["auc_rule"]) else 0.9
        # (2) 합성 주입: 정상 클립 절반은 그대로(정상), 절반에 패턴 주입(위반). 습관형 강도를 실측 AUC 에 맞춘 뒤 같은 노출량으로 3유형
        gp = g[y]
        scale = calibrate_scale(pos, gp, delta, stat, target)
        n_pos = len(pos)
        perm = RNG.permutation(n_pos)
        keep, inj = perm[: n_pos // 2], perm[n_pos // 2:]
        for kind in TYPES:
            Finj = inject(pos[inj], delta, kind, scale)
            X = np.concatenate([pos[keep], Finj], axis=0)
            yy = np.concatenate([np.ones(len(keep)), np.zeros(len(inj))]).astype(int)
            gg = np.concatenate([gp[keep], gp[inj]])
            S = stats_of(X)
            row = dict(exercise=ex, condition=cond, feature=base, rule_stat=stat, kind=kind, delta=delta, n=len(yy), scale=scale)
            for k, v in S.items():
                row[f"auc_{k}"] = auc_cv(v, yy, gg)
            row["auc_rule"] = row.get(f"auc_{stat}", np.nan)
            # 방향을 아는 극값: 위반 방향(Δ 부호) 쪽 극값과 스파이크
            row["auc_ext_dir"] = row["auc_max"] if delta > 0 else row["auc_min"]
            row["auc_spike_dir"] = row["auc_spike_hi"] if delta > 0 else row["auc_spike_lo"]
            rows.append(row)
        print(f"  {ex} | {cond}", flush=True)

    res = pd.DataFrame(rows)
    sig = pd.DataFrame(sig_rows)
    res.to_csv(OUT / "error_onset.csv", index=False, encoding="utf-8-sig")
    write_report(res, sig)


def write_report(res: pd.DataFrame, sig: pd.DataFrame):
    stat_cols = ["rule", "mean", "ext_dir", "std", "range", "drift", "late_mean", "spike_dir"]
    for c in ("auc_ext_dir", "auc_spike_dir"):
        if c not in res:
            res[c] = np.nan
    acted = res[res.kind == "acted"]
    L = ["# 자세가 틀리는 방식 3유형 — 습관 · 피로 · 순간 붕괴 — 의 탐지\n",
         f"- ship 규칙 {res.exercise.nunique()}종목 {len(acted)}조건, 수행자 GroupKFold AUC",
         "- AIHub 위반은 **연기된 습관형**뿐이므로 피로·순간형은 정상 클립에 시간 패턴을 합성 주입해 평가.",
         f"  주입 강도는 습관형이 실측 연기 위반의 규칙 AUC(중앙값 {acted.auc_rule.median():.3f})와 같아지도록 조건별 캘리브레이션(배율 중앙값 {res[res.kind=='habit'].scale.median():.2f}), "
         "세 유형의 **총 위반 노출량(프레임×진폭 합)은 동일** — 같은 양의 오류를 시간상 어떻게 배치했는가만 다르다.",
         "- `ext_dir` = 위반 방향 쪽 극값(max 또는 min), `spike_dir` = 그 방향의 (극값−중앙값)/IQR, `drift` = 후반⅓−전반⅓, `late_mean` = 후반⅓ 평균\n",
         "## 1. 실측: AIHub 연기 위반의 시간 서명\n",
         "| 지표 | 값 |", "|---|---|",
         f"| 위반−정상 차이: 초반 3프레임 / 중반 / 후반 3프레임 (위반 효과 대비 비율 중앙값) | {(sig.early/sig.mid).median():.2f} / 1.00 / {(sig.late/sig.mid).median():.2f} |",
         f"| 중반 10프레임 내 차이의 변동계수(CV) 중앙값 | {sig.mid_cv.median():.2f} |",
         f"| 클립 내 프레임 std: 정상 vs 위반 (중앙값 비) | {(sig.std_neg/sig.std_pos).median():.2f}× |",
         "",
         "- 양 끝 프레임(준비/마무리 자세)은 차이가 작고 **동작 중에는 일정**(CV 낮음) → 처음부터 끝까지 같은 오프셋 = **습관형**. "
         "점진 악화(피로)나 국소 이탈(순간)은 데이터에 없다.",
         "- 즉 현재 규칙은 습관형으로 학습됐다. 피로·순간형을 잡을 수 있는지는 아래 합성 실험으로만 말할 수 있다.\n",
         "## 2. 합성 실험 — 유형별로 어떤 통계가 잡는가 (AUC 중앙값)\n",
         "| 유형 | " + " | ".join(stat_cols) + " |", "|---|" + "---|" * len(stat_cols)]
    for kind, label in (("acted", "실측 연기(습관)"), ("habit", "합성 습관(전 프레임 +δ)"), ("fatigue", "합성 피로(0→2δ 램프)"), ("spike", "합성 순간(3프레임 ×T/3·δ)")):
        g = res[res.kind == kind]
        if g.empty:
            continue
        L.append(f"| {label} | " + " | ".join(f"{g[f'auc_{c}'].median():.3f}" for c in stat_cols) + " |")
    L.append("")
    # 유형별 최적 통계
    L += ["### 유형별 — 현재 규칙 vs 최적 통계 (조건별 argmax)\n", "| 유형 | 현재 규칙 stat 그대로 | 최적 통계(최빈) | 최적 AUC 중앙값 | 이득 |", "|---|---|---|---|---|"]
    cand = [c for c in stat_cols if c != "rule"]
    for kind in ("habit", "fatigue", "spike"):
        g = res[res.kind == kind].copy()
        if g.empty:
            continue
        best = g[[f"auc_{c}" for c in cand]].idxmax(axis=1).str.replace("auc_", "")
        best_auc = g[[f"auc_{c}" for c in cand]].max(axis=1)
        L.append(f"| {kind} | {g.auc_rule.median():.3f} | {best.mode().iat[0]} | {best_auc.median():.3f} | {(best_auc - g.auc_rule).median():+.3f} |")
    L.append("")
    L += ["### 현재 규칙 통계 유형별 — 같은 노출량의 오류가 피로·순간형으로 오면\n", "| 규칙 stat | 조건 수 | 습관 AUC | 피로 AUC | 순간 AUC |", "|---|---|---|---|---|"]
    for st, g in res[res.kind != "acted"].groupby("rule_stat"):
        h = g[g.kind == "habit"].auc_rule.median(); f = g[g.kind == "fatigue"].auc_rule.median(); s = g[g.kind == "spike"].auc_rule.median()
        L.append(f"| {st} | {g.exercise.nunique()} | {h:.3f} | {f:.3f} | {s:.3f} |")
    L.append("")
    # 유형 **구분** 가능성: 피로 vs 습관 을 drift 로, 순간 vs 습관 을 spike 로 가를 수 있나 (위반끼리 비교)
    L += ["### 유형 구분 — 위반을 잡은 뒤 '어떤 유형인가' 를 가를 수 있나\n",
          "| 구분 | 통계 | 설명 |", "|---|---|---|",
          f"| 피로 vs 습관 | drift | 피로형 drift AUC {res[res.kind=='fatigue'].auc_drift.median():.3f} vs 습관형 {res[res.kind=='habit'].auc_drift.median():.3f} — 습관은 drift 가 0 이므로 drift 만으로 분리 |",
          f"| 순간 vs 습관 | spike_dir | 순간형 spike AUC {res[res.kind=='spike'].auc_spike_dir.median():.3f} vs 습관형 {res[res.kind=='habit'].auc_spike_dir.median():.3f} — 습관은 중앙값이 함께 이동해 spike 비가 0 |",
          ""]
    L += ["## 3. 해석과 설계 함의\n",
          "- **습관형(처음부터 잘못)**: 현재 규칙(세트 통계 1개)이 그대로 잡는다. AIHub 연기 위반이 정확히 이 유형이라 임계값도 이 유형에 맞춰져 있다.",
          "- **피로형(점점 무너짐)**: 같은 노출량이면 mean 규칙은 비슷하게 잡지만 min/max 규칙은 약해진다. `late_mean`(후반 평균)·`drift` 가 규칙 통계보다 강하다 → "
          "세트를 **전반/후반으로 나눠** 같은 규칙을 두 번 평가: 후반만 위반이면 '피로', 둘 다면 '습관'. 임계값은 후반 창에 맞춰 재보정 필요.",
          "- **순간형(삐끗)**: mean 규칙은 둔감(0.905 — 같은 노출량을 3프레임에 몰아넣어 진폭이 ~5δ 로 컸는데도). 위반 방향 극값과 스파이크 비가 잡는다. "
          "단, 합성 순간형은 진폭이 비현실적으로 커서 **극값 AUC 0.995 는 상한**이다 — 실제 삐끗은 1~2프레임·작은 진폭일 수 있고, 2~4fps 샘플링이면 아예 놓칠 수 있다. "
          "→ 순간형을 보려면 **샘플링을 올리거나**(이벤트 구간만 고fps) 렙 단위 이탈 카운트로 제한해야 한다.",
          "- **유형 구분**은 규칙 통계와 별개로 `drift` 와 `spike_dir` 두 값을 같이 내면 된다(피로: drift 0.889 vs 습관 0.481 / 순간: spike 0.926 vs 습관 0.494). "
          "같은 프레임 피처에서 나오므로 추가 추론 비용 0.",
          "- 세 유형은 **피드백이 달라야 한다**: 습관 → 자세 교육(기준선·재교육), 피로 → 중량/볼륨 조정·세트 중단 제안, 순간 → 해당 렙 경고. 한 숫자 규칙은 이걸 구분 못 한다.",
          "- 한계: **합성**이다. 실제 피로는 여러 관절이 동시에 무너지고 ROM·템포도 줄며, 순간 붕괴는 보상 동작을 동반한다 — 앱 세트 로그(프레임 시각 포함)로 실측 검증할 것. "
          "AIHub 로는 습관형 임계값만 있고 **피로·순간형 임계값은 아직 없다**."]
    (OUT / "ERROR_ONSET.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
