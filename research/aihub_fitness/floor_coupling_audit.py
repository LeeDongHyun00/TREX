# -*- coding: utf-8 -*-
"""바닥 규칙 동작-결합 감사 (§28d) — §28c 를 2D 경로로.

바닥 9종목은 3D 불량이라 §28c(GT 3D) 감사에서 빠졌다. 여기서는 채택 뷰의 **사람 주석 2D**
(= floor_2d_rules 와 같은 피처 정의, 앱과 같은 시점)로 같은 두 게이트를 적용한다:
  게이트 A  정상 클립 내 |r(피처 통계, 렙 진폭)| < 0.35   ← 조건이 아니라 렙 위상을 따라가는가
  게이트 B  극값(min/max) 규칙이면 강건 대안(mean/p10/p90)과 AUC 비교 → 손실 ≤0.02 면 교체 권고

렙 진폭 = 종목별 렙 신호(RepSignals.kt 큐레이션)의 클립 내 range.
등척성(플랭크)은 렙 신호가 없으므로 몸통 정렬 자체의 range 를 대용으로 쓴다(움직임 총량).

한계: AIHub 0.6s·서있는 높이 카메라 기준. 스쿼트 실측에서 GT→기기 결합이 4배 증폭됐으므로
여기 통과분도 기기에서는 결합이 클 수 있다(하한 추정). 최종 판정은 실기기 라벨.
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

from rep_signal_survey import FLOOR_VIEW, floor_series
from sklearn.metrics import roc_auc_score

# RepSignals.kt 와 동일 (§27 큐레이션). 플랭크는 등척성 — 대용 신호 사용.
REP_SIGNAL = {
    "푸시업": "wrist_shoulder_d", "니푸쉬업": "wrist_shoulder_d", "크런치": "head_ground",
    "라잉 레그 레이즈": "hip_ang", "힙쓰러스트": "hip_dev_ankle", "Y - Exercise": "hand_shoulder_off",
    "시저크로스": "knee_gap2d", "바이시클 크런치": "knee_gap2d",
    "플랭크": "trunk_ankle_ang",   # 등척성 — 움직임 총량 대용
}
ALT_STATS = ["mean", "p10", "p90"]
COUPLING_LIMIT = 0.35


def agg(series: np.ndarray, stat: str) -> np.ndarray:
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
    doc = json.load(open(HERE / "rules" / "rules_floor_v0.json", encoding="utf-8"))
    active = [r for r in doc["rules"] if r["status"] != "exclude"]
    clips = pd.read_parquet(DATA / "clips.parquet")
    conds = pd.read_parquet(DATA / "conditions.parquet")

    cache: dict[str, tuple[dict, np.ndarray]] = {}
    rows = []
    for r in active:
        ex, base, stat = r["exercise"], r["base_feature"], r["stat"]
        if ex not in FLOOR_VIEW:
            continue
        if ex not in cache:
            F, _ = floor_series(ex, FLOOR_VIEW[ex])
            cids = np.array(sorted(clips[clips.exercise == ex].clip_id.unique()))
            # floor_series 는 np.unique(clip_id) 순서로 행을 반환 — 그 순서를 재현
            k2ids = np.unique(pd.read_parquet(DATA / "kp2d.parquet", columns=["clip_id", "view_letter"])
                              .query("view_letter == @FLOOR_VIEW[@ex]")
                              .query("clip_id in @cids").clip_id.to_numpy())
            cache[ex] = (F, k2ids)
        F, cids = cache[ex]
        if base not in F:
            continue
        sig = REP_SIGNAL.get(ex)
        if sig not in F:
            continue
        lab = (conds[(conds.exercise == ex) & (conds.condition == r["condition"])]
               .drop_duplicates("clip_id").set_index("clip_id").value.reindex(cids))
        ok = lab.notna().to_numpy()
        if ok.sum() < 40:
            continue
        y = (~lab[ok].astype(bool)).astype(int).to_numpy()
        depth = agg(F[sig], "range")[ok]
        cur = agg(F[base], stat)[ok]
        nrm = np.isfinite(cur) & np.isfinite(depth) & (y == 0)
        if nrm.sum() < 25:
            continue
        rho = float(np.corrcoef(cur[nrm], depth[nrm])[0, 1])
        auc_cur = oriented_auc(cur, y)

        best_alt, best_auc, best_rho = "", np.nan, np.nan
        if stat in ("min", "max"):
            for alt in ALT_STATS:
                v = agg(F[base], alt)[ok]
                n2 = np.isfinite(v) & np.isfinite(depth) & (y == 0)
                if n2.sum() < 25:
                    continue
                a = oriented_auc(v, y)
                rr = float(np.corrcoef(v[n2], depth[n2])[0, 1])
                if np.isfinite(a) and abs(rr) < abs(rho) and (np.isnan(best_auc) or a > best_auc):
                    best_alt, best_auc, best_rho = alt, a, rr
        flags = []
        if abs(rho) >= COUPLING_LIMIT:
            flags.append("결합")
        if stat in ("min", "max"):
            flags.append("극값")
        rows.append(dict(exercise=ex, condition=r["condition"], feature=r["feature"], stat=stat,
                         status=r["status"], n=int(ok.sum()), auc=round(auc_cur, 3),
                         coupling=round(rho, 2), rep_signal=sig, alt=best_alt,
                         alt_auc=(round(best_auc, 3) if np.isfinite(best_auc) else np.nan),
                         alt_coupling=(round(best_rho, 2) if np.isfinite(best_rho) else np.nan),
                         flags="+".join(flags)))
        print(f"  {ex:14s} {r['condition'][:22]:22s} {stat:5s} r={rho:+.2f} AUC={auc_cur:.3f}"
              + (f" | 대안 {best_alt} r={best_rho:+.2f} AUC={best_auc:.3f}" if best_alt else ""))

    res = pd.DataFrame(rows)
    res.to_csv(OUT / "floor_coupling_audit.csv", index=False, encoding="utf-8-sig")
    write_report(res)


def write_report(res: pd.DataFrame) -> None:
    coupled = res[res.coupling.abs() >= COUPLING_LIMIT]
    extremum = res[res.stat.isin(["min", "max"])]
    swap = extremum[extremum.alt.astype(bool) & (extremum.alt_auc >= extremum.auc - 0.02)]
    L = ["# 바닥 규칙 동작-결합 감사 (§28d)\n",
         "§28c(GT 3D)에서 빠졌던 바닥 9종목을 채택 뷰 **주석 2D**(앱과 같은 시점·피처 정의)로 감사.\n",
         f"- 검사 {len(res)}건 · **결합 의심 {len(coupled)}건**(|r| ≥ {COUPLING_LIMIT}) · "
         f"극값 {len(extremum)}건 중 무손실 교체 후보 **{len(swap)}건**\n",
         "## 1. 전체\n",
         "| 종목 | 조건 | 통계 | AUC | 결합 r | 렙신호 | 대안 | 대안 AUC | 대안 r |",
         "|---|---|---|---|---|---|---|---|---|"]
    for _, r in res.sort_values("coupling", key=abs, ascending=False).iterrows():
        mark = "**" if abs(r.coupling) >= COUPLING_LIMIT else ""
        L.append(f"| {r.exercise} | {r.condition[:20]} | {r.stat} | {r.auc} | {mark}{r.coupling:+.2f}{mark} | "
                 f"`{r.rep_signal}` | {r.alt or '—'} | {r.alt_auc if r.alt else '—'} | "
                 f"{r.alt_coupling if r.alt else '—'} |")
    L += ["", "## 2. 판정",
          "- **결합 + 극값**: 스쿼트와 같은 병 — 교체 또는 강등",
          "- **극값만**: 대안 손실 ≤0.02 면 무손실 교체",
          "- **결합만**: 조건-피처 재정의 대상(§28b heel_lift 같은 개별 작업)",
          "",
          "## 한계",
          "- AIHub 0.6s·서있는 높이 카메라 기준 — 스쿼트 실측에서 GT→기기 결합 4배 증폭(§28)이었으므로 **하한 추정**.",
          "- 바닥 규칙은 전부 beta(임계값 미보정) 상태 — 이 감사는 신뢰도 순위를 매기는 것이지 승격 근거가 아니다.",
          "- 플랭크는 등척성이라 '렙 진폭' 대신 몸통 정렬 range 를 대용 — 결합 해석 시 주의."]
    (OUT / "FLOOR_COUPLING_AUDIT.md").write_text("\n".join(L), encoding="utf-8")
    print(f"\n[done] 검사 {len(res)} · 결합 {len(coupled)} · 교체후보 {len(swap)} → {OUT/'FLOOR_COUPLING_AUDIT.md'}")


if __name__ == "__main__":
    main()
