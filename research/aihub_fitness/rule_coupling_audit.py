# -*- coding: utf-8 -*-
"""전 규칙 동작-결합 감사 — 스쿼트에서 터진 병이 다른 종목·관절에도 있는가 (§28c).

배경: '발바닥 지면 고정'(foot_pitch__min)과 '고개 정면'(face_vs_torso__min)은 **조건과 무관한
주 동작(렙 깊이)과 결합**해 정상 사용자에게 상시 오탐이었다(§28/§28b). 원인 두 축:
  (A) 결합: 피처가 조건이 아니라 렙 위상(깊이)을 따라 움직임 — 정상 클립 내 상관으로 검출
  (B) 극값 통계: min/max 는 매 렙 반복되는 극단 프레임을 뽑아, 결합이 있으면 항상 발화

이 스크립트는 활성 전 규칙(71개)에 같은 두 게이트를 소급 적용한다:
  게이트 A  정상 클립 내 |r(피처통계, 렙깊이대용)| < 0.35   ← 결합 없음
  게이트 B  극값 규칙이면 강건 대안(mean/p10/p90)과 AUC 비교 → 대안이 −0.02 이내면 교체 권고

렙 깊이 대용(주 동작 진폭)은 종목별 렙 신호(REP_SIGNALS 큐레이션)의 클립 내 range 를 쓴다 —
"이 클립에서 얼마나 크게 움직였나". 결합이 있으면 피처 통계가 이 값과 함께 움직인다.

출력: outputs/rule_coupling_audit.csv, RULE_COUPLING_AUDIT.md
한계: GT 3D 기준(MP 전이 전). 바닥 종목은 3D 불량이라 제외 — 별도 2D 경로(rules_floor)에서 감사 필요.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
DATA = Path(r"C:/Users/hp276/Desktop/trex/.claude/worktrees/correct-exercise-form-6ddf55/research/aihub_fitness/outputs")
OUT = HERE / "outputs"

from features import apply_qc_mask, compute_frame_features, load_kp3d
from sklearn.metrics import roc_auc_score

# 종목별 렙 신호 (RepSignals.kt 와 동일 — 주 동작 진폭의 대용)
REP_SIGNAL = {
    "바벨 데드리프트": "hip_mean", "바벨 스티프 데드리프트": "hip_mean", "굿모닝": "hip_mean",
    "바벨 스쿼트": "knee_mean", "버피 테스트": "knee_mean", "크로스 런지": "knee_mean",
    "바벨 런지": "knee_minside", "사이드 런지": "knee_minside",
    "스텝 포워드 다이나믹 런지": "knee_out_mean", "스텝 백워드 다이나믹 런지": "hip_mean",
    "스탠딩 니업": "hip_mean", "풀업": "elbow_mean", "딥스": "elbow_mean", "바벨 로우": "elbow_mean",
    "덤벨 벤트오버 로우": "elbow_mean", "바벨 컬": "elbow_mean", "덤벨 컬": "elbow_mean",
    "페이스 풀": "elbow_mean", "랫풀 다운": "forearm_vert_mean", "사이드 레터럴 레이즈": "forearm_vert_mean",
    "프런트 레이즈": "forearm_vert_mean", "업라이트로우": "forearm_vert_mean",
    "덤벨 체스트 플라이": "forearm_vert_mean", "덤벨 인클라인 체스트 플라이": "forearm_vert_mean",
    "오버 헤드 프레스": "palm_h_sh", "케이블 푸시 다운": "palm_h_sh", "라잉 트라이셉스 익스텐션": "palm_h_sh",
    "덤벨 풀 오버": "palm_h_sh", "로잉머신": "palm_fwd_knee", "행잉 레그 레이즈": "hip_below_knee",
    "케이블 크런치": "knee_elbow_dist", "스탠딩 사이드 크런치": "knee_mean",
}
ALT_STATS = ["mean", "p10", "p90"]
COUPLING_LIMIT = 0.35


def agg(series: np.ndarray, stat: str) -> np.ndarray:
    """(N,T) → (N,). NaN 안전."""
    with np.errstate(all="ignore"):
        if stat == "mean":
            return np.nanmean(series, 1)
        if stat == "min":
            return np.nanmin(series, 1)
        if stat == "max":
            return np.nanmax(series, 1)
        if stat == "std":
            return np.nanstd(series, 1)
        if stat == "range":
            return np.nanmax(series, 1) - np.nanmin(series, 1)
        if stat == "p10":
            return np.nanquantile(series, 0.10, axis=1)
        if stat == "p90":
            return np.nanquantile(series, 0.90, axis=1)
    return np.full(len(series), np.nan)


def oriented_auc(x: np.ndarray, y: np.ndarray) -> float:
    ok = np.isfinite(x)
    if ok.sum() < 40 or len(np.unique(y[ok])) < 2:
        return np.nan
    a = roc_auc_score(y[ok], x[ok])
    return max(a, 1 - a)


def main() -> None:
    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    active = [r for r in doc["rules"] if r["status"] != "exclude"]
    clips = pd.read_parquet(DATA / "clips.parquet")
    conds = pd.read_parquet(DATA / "conditions.parquet")
    ids, arr = load_kp3d(DATA)
    ids, arr, _ = apply_qc_mask(ids, arr, DATA)
    ids = np.array(ids)
    ex_of = clips.set_index("clip_id").exercise.reindex(ids).to_numpy()
    F = compute_frame_features(arr)

    rows = []
    for r in active:
        ex, base, stat = r["exercise"], r["base_feature"], r["stat"]
        if base not in F or ex not in REP_SIGNAL:
            continue
        sig = REP_SIGNAL[ex]
        if sig not in F:
            continue
        m = ex_of == ex
        if m.sum() < 60:
            continue
        lab = (conds[(conds.exercise == ex) & (conds.condition == r["condition"])]
               .drop_duplicates("clip_id").set_index("clip_id").value.reindex(ids[m]))
        ok = lab.notna().to_numpy()
        if ok.sum() < 60:
            continue
        y = (~lab[ok].astype(bool)).astype(int).to_numpy()      # 위반=1
        depth = agg(F[sig][m], "range")[ok]                      # 주 동작 진폭
        cur = agg(F[base][m], stat)[ok]
        fin = np.isfinite(cur) & np.isfinite(depth)
        nrm = fin & (y == 0)
        if nrm.sum() < 30:
            continue
        rho = float(np.corrcoef(cur[nrm], depth[nrm])[0, 1])
        auc_cur = oriented_auc(cur, y)

        best_alt, best_auc, best_rho = "", np.nan, np.nan
        if stat in ("min", "max"):
            for alt in ALT_STATS:
                v = agg(F[base][m], alt)[ok]
                f2 = np.isfinite(v) & np.isfinite(depth)
                n2 = f2 & (y == 0)
                if n2.sum() < 30:
                    continue
                a = oriented_auc(v, y)
                rr = float(np.corrcoef(v[n2], depth[n2])[0, 1])
                # 결합이 더 낮고 AUC 손실이 작은 대안만 후보
                if np.isfinite(a) and abs(rr) < abs(rho) and (np.isnan(best_auc) or a > best_auc):
                    best_alt, best_auc, best_rho = alt, a, rr
        flags = []
        if abs(rho) >= COUPLING_LIMIT:
            flags.append("결합")
        if stat in ("min", "max"):
            flags.append("극값")
        rows.append(dict(exercise=ex, condition=r["condition"], feature=r["feature"], stat=stat,
                         status=r["status"], n=int(ok.sum()), auc=round(auc_cur, 3),
                         coupling=round(rho, 2), rep_signal=sig,
                         alt=best_alt, alt_auc=(round(best_auc, 3) if np.isfinite(best_auc) else np.nan),
                         alt_coupling=(round(best_rho, 2) if np.isfinite(best_rho) else np.nan),
                         flags="+".join(flags)))
        print(f"  {ex:16s} {r['condition'][:20]:20s} {stat:5s} r={rho:+.2f} AUC={auc_cur:.3f}"
              + (f" | 대안 {best_alt} r={best_rho:+.2f} AUC={best_auc:.3f}" if best_alt else ""))

    res = pd.DataFrame(rows)
    res.to_csv(OUT / "rule_coupling_audit.csv", index=False, encoding="utf-8-sig")
    write_report(res)


def write_report(res: pd.DataFrame) -> None:
    coupled = res[res.coupling.abs() >= COUPLING_LIMIT]
    extremum = res[res.stat.isin(["min", "max"])]
    swap = extremum[extremum.alt.astype(bool) & (extremum.alt_auc >= extremum.auc - 0.02)]
    L = ["# 전 규칙 동작-결합 감사 (§28c)\n",
         "스쿼트에서 터진 두 병(주 동작 결합 · 극값 통계)을 활성 전 규칙에 소급 적용.",
         f"검사 {len(res)}건 (GT 3D 기준, 바닥 종목 제외 — 별도 2D 경로).\n",
         f"- **결합 의심**(정상 클립 내 |r| ≥ {COUPLING_LIMIT}): **{len(coupled)}건**",
         f"- 극값(min/max) 규칙: {len(extremum)}건 — 그중 강건 대안이 AUC 손실 ≤0.02 로 대체 가능: **{len(swap)}건**\n",
         "## 1. 결합 의심 (조건이 아니라 렙 위상을 따라감)\n",
         "| 종목 | 조건 | 통계 | 결합 r | AUC | 대안 | 대안 r | 대안 AUC |",
         "|---|---|---|---|---|---|---|---|"]
    for _, r in coupled.sort_values("coupling", key=abs, ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:20]} | {r.stat} | **{r.coupling:+.2f}** | {r.auc} | "
                 f"{r.alt or '—'} | {r.alt_coupling if r.alt else '—'} | {r.alt_auc if r.alt else '—'} |")
    L += ["", "## 2. 극값 규칙 — 강건 통계 교체 후보\n",
          "| 종목 | 조건 | 현재 | AUC | 결합 r | → 대안 | 대안 AUC | 대안 r |",
          "|---|---|---|---|---|---|---|---|"]
    for _, r in swap.sort_values("auc", ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:20]} | {r.stat} | {r.auc} | {r.coupling:+.2f} | "
                 f"**{r.alt}** | {r.alt_auc} | {r.alt_coupling:+.2f} |")
    L += ["", "## 판정 원칙",
          "- **결합 + 극값** 둘 다면 스쿼트와 같은 병 — 교체 또는 exclude.",
          "- **극값만**: 대안 AUC 손실 ≤0.02 면 교체(무손실 강건화). 손실이 크면 유지하되 실기기 관찰 대상.",
          "- **결합만**(mean 계열): 조건-피처 재정의 필요 — AUC 가 높아도 '무엇을 재는가'가 어긋난 것(§25b 감사와 같은 부류).",
          "- 이 감사는 GT 3D 기준이므로 MP 전이 후 결합이 더 커질 수 있다(스쿼트 실측: GT r=0.19 → 기기 r=0.78)."]
    (OUT / "RULE_COUPLING_AUDIT.md").write_text("\n".join(L), encoding="utf-8")
    print(f"\n[done] 검사 {len(res)} · 결합의심 {len(coupled)} · 교체후보 {len(swap)} → {OUT/'RULE_COUPLING_AUDIT.md'}")


if __name__ == "__main__":
    main()
