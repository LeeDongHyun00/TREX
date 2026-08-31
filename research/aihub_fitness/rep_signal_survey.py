# -*- coding: utf-8 -*-
"""전 종목 렙 신호 조사 — 어떤 피처로 렙을 나눌 것인가를 데이터로 정한다.

렙 라벨이 없는 AIHub 에서 렙 신호를 고르는 원리: 올바른 렙 신호라면 **같은 종목의 모든 클립에서
비슷한 횟수**가 세져야 한다(수행 프로토콜이 클립당 렙 수를 대체로 고정하므로). 그래서
(종목 × 피처)마다 자기보정 히스테리시스 카운터를 돌리고, 클립 간 **카운트 일관성**(최빈값 ±1 비율)이
가장 높은 피처를 렙 신호로 채택한다. 절대 정답 없이도 나쁜 신호(노이즈 진동·비주기 피처)는
클립마다 제멋대로 세져 일관성에서 탈락한다.

부가 산출:
- 종목 유형 분류: 동적(주기성 강함) / 교대형(L/R 역위상) / 등척성(주기성 없음)
- 렙 주기 추정 → 앱 3.3fps 에서의 렙당 샘플 수 → 빠른 렙 위험 플래그
- 서브샘플링 스트레스: 0.6s(렙당 ~4샘플) vs 1.2s(~2샘플) 카운트 붕괴율 — 최소 샘플/렙의 실측 근거

주의(한계):
- AIHub 프레임 간격 ~0.6s 는 앱(0.3s)보다 성기다 — 여기서 일관되게 세지는 신호는 앱에서 더 잘 세진다.
- 클립당 참 렙 수를 모르므로 '일관성'은 정확도의 프록시다. 실기기 라벨(labels_device.csv)이 최종 검증.
- 바닥 9종목은 3D 불량이라 채택 뷰의 사람 주석 2D 로 계산(앱과 같은 시점).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
DATA = Path(r"C:/Users/hp276/Desktop/trex/.claude/worktrees/correct-exercise-form-6ddf55/research/aihub_fitness/outputs")
OUT = HERE / "outputs"

import floor_2d_rules as f2d
from features import apply_qc_mask, compute_frame_features, load_kp3d

FRAME_DT = 0.6  # AIHub 프레임 간격(초) — 16프레임 ≈ 9.6s
APP_FPS = 3.33  # 앱 샘플링 (300ms)

FLOOR_VIEW = {"푸시업": "C", "니푸쉬업": "B", "플랭크": "B", "힙쓰러스트": "B", "시저크로스": "C",
              "크런치": "E", "라잉 레그 레이즈": "E", "Y - Exercise": "B", "바이시클 크런치": "E"}

# 교대형 판별용 좌우 쌍 (역위상이면 교대). 이름은 아래 raw 좌우 시계열 계산과 일치.
LR_PAIRS = [("knee_L", "knee_R"), ("elbow_L", "elbow_R"), ("wrist_h_L", "wrist_h_R"), ("knee_h_L", "knee_h_R")]


def count_reps(series: np.ndarray, min_gap: int = 1, qlo: float = 0.40, qhi: float = 0.60) -> int:
    """자기보정 히스테리시스 카운터 (설계 §2 와 동일 원리, 프레임 단위).

    렙 1 = 밴드 상단 위 → 하단 아래 → 다시 상단 위 (전체 사이클). NaN 프레임은 압축.
    밴드는 클립 자신의 p30/p70 — 절대 임계값이 없어 종목·시점·체형과 무관.
    """
    v = series[np.isfinite(series)]
    if len(v) < 8:
        return -1  # 측정 불가
    # 평활은 샘플 밀도에 적응: 렙당 ~4샘플(AIHub 0.6s)에서는 3점 중앙값이 1샘플 폭의 렙 꼭대기를
    # 지워버린다(실측: 스쿼트 49,163,96 → 96). 짧은 시계열은 원시값 그대로, 긴 시계열(앱 0.3s,
    # 렙당 ~17샘플)만 평활한다. 히스테리시스 밴드가 단발 잡음의 1차 방어선.
    sm = v if len(v) <= 24 else np.array([np.median(v[max(0, i - 1):i + 2]) for i in range(len(v))])
    # 밴드 폭도 샘플 밀도에 적응: 렙당 3~4샘플에서는 샘플된 극값이 바깥 분위수에 못 미친다 —
    # 스쿼트 육안 정답 6클립 캘리브레이션에서 p40/p60 이 p30/p70 보다 오차합 8→3 (긴 시계열은 넓게).
    if len(v) > 24:
        qlo, qhi = 0.30, 0.70
    lo, hi = np.quantile(sm, [qlo, qhi])
    rng = sm.max() - sm.min()
    # 진폭 게이트는 카운팅 밴드와 분리 (p30/p70 고정): 카운팅 밴드를 좁힐 때 게이트까지 좁아지면
    # 진짜 진동을 '활동 없음'으로 기각한다 (1차 실행에서 17종목이 이렇게 죽었다)
    g30, g70 = np.quantile(sm, [0.30, 0.70])
    if rng <= 0 or (g70 - g30) < 0.15 * rng:
        return 0
    state = "high" if sm[0] >= hi else ("low" if sm[0] <= lo else "mid")
    reps, last = 0, -10
    for i, x in enumerate(sm):
        if state != "low" and x <= lo:
            state = "low"
        elif state == "low" and x >= hi:
            if i - last >= min_gap:
                reps += 1
                last = i
            state = "high"
    return reps


def survey_features(F: dict[str, np.ndarray], keep: np.ndarray) -> pd.DataFrame:
    """F: {base: (N,T)} → 피처별 카운트 일관성 표. keep: 이 종목의 클립 마스크."""
    rows = []
    for base, arr in F.items():
        counts = np.array([count_reps(arr[i]) for i in np.where(keep)[0]])
        ok = counts >= 0
        if ok.sum() < 30:
            continue
        c = counts[ok]
        vals, freq = np.unique(c, return_counts=True)
        mode = int(vals[freq.argmax()])
        consist = float(np.isin(c, [mode - 1, mode, mode + 1]).mean())
        rows.append(dict(feature=base, n=int(ok.sum()), mode=mode, median=float(np.median(c)),
                         consistency=consist, zero_share=float((c == 0).mean())))
    return pd.DataFrame(rows)


def lr_antiphase(arrL: np.ndarray, arrR: np.ndarray, keep: np.ndarray) -> float:
    """좌우 시계열의 클립 내 상관 중앙값 — 강한 음수면 교대형."""
    cors = []
    for i in np.where(keep)[0]:
        a, b = arrL[i], arrR[i]
        m = np.isfinite(a) & np.isfinite(b)
        if m.sum() < 10:
            continue
        aa, bb = a[m] - a[m].mean(), b[m] - b[m].mean()
        den = np.sqrt((aa ** 2).sum() * (bb ** 2).sum())
        if den > 0:
            cors.append(float((aa * bb).sum() / den))
    return float(np.median(cors)) if cors else np.nan


def raw_lr_series(arr: np.ndarray) -> dict[str, np.ndarray]:
    """교대 판별용 좌우 원시 시계열: 무릎·팔꿈치 각도, 손목·무릎 높이(y)."""
    from features import J

    def ang(a, b, c):
        u = arr[:, :, J[a]] - arr[:, :, J[b]]
        w = arr[:, :, J[c]] - arr[:, :, J[b]]
        cos = (u * w).sum(-1) / np.maximum(np.linalg.norm(u, axis=-1) * np.linalg.norm(w, axis=-1), 1e-6)
        return np.degrees(np.arccos(np.clip(cos, -1, 1)))

    return {
        "knee_L": ang("LHip", "LKnee", "LAnkle"), "knee_R": ang("RHip", "RKnee", "RAnkle"),
        "elbow_L": ang("LShoulder", "LElbow", "LWrist"), "elbow_R": ang("RShoulder", "RElbow", "RWrist"),
        "wrist_h_L": arr[:, :, J["LWrist"], 1], "wrist_h_R": arr[:, :, J["RWrist"], 1],
        "knee_h_L": arr[:, :, J["LKnee"], 1], "knee_h_R": arr[:, :, J["RKnee"], 1],
    }


def floor_series(exercise: str, view: str) -> tuple[dict[str, np.ndarray], np.ndarray]:
    """바닥 종목: 채택 뷰 주석 2D → floor_2d_rules 피처의 (N,16) 시계열."""
    clips = pd.read_parquet(DATA / "clips.parquet")
    cids = clips[clips.exercise == exercise].clip_id
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in f2d.JOINTS for a in "xy"]
    k2 = pd.read_parquet(DATA / "kp2d.parquet", columns=cols)
    k2 = k2[(k2.view_letter == view) & k2.clip_id.isin(cids)].drop(columns="view_letter")
    k2 = k2.sort_values(["clip_id", "frame_idx"])
    F2 = f2d.frame_features(k2)
    ids = k2.clip_id.to_numpy()
    uniq, inv = np.unique(ids, return_inverse=True)
    fidx = k2.frame_idx.to_numpy() - 1
    out = {}
    for base, v in F2.items():
        M = np.full((len(uniq), 16), np.nan)
        M[inv, np.clip(fidx, 0, 15)] = v
        out[base] = M
    return out, np.ones(len(uniq), bool)


def main() -> None:
    clips = pd.read_parquet(DATA / "clips.parquet")
    ids, arr = load_kp3d(DATA)
    ids, arr, _ = apply_qc_mask(ids, arr, DATA)
    ids = np.array(ids)
    ex_of = clips.set_index("clip_id").exercise.reindex(ids).to_numpy()
    F3 = compute_frame_features(arr)
    LR = raw_lr_series(arr)

    results, best_rows = [], []
    for ex in sorted(clips.exercise.unique()):
        floor = ex in FLOOR_VIEW
        if floor:
            F, keep = floor_series(ex, FLOOR_VIEW[ex])
            lr = np.nan  # 2D 채택 뷰에서 좌우 쌍은 원근이 섞여 3D 만큼 깨끗하지 않음 — 3D 양호 프레임으로 별도 계산
            k3keep = ex_of == ex
            if k3keep.sum() >= 30:
                lr = np.nanmin([lr_antiphase(LR[a], LR[b], k3keep) for a, b in LR_PAIRS])
        else:
            F, keep = F3, ex_of == ex
            lr = np.nanmin([lr_antiphase(LR[a], LR[b], keep) for a, b in LR_PAIRS])
        if keep.sum() < 30:
            continue
        # 카운트 행렬 (피처 × 클립) → 클립별 합의 카운트: 몸 전체가 렙 주기로 움직이므로
        # 다수 피처가 합의하는 값이 참 렙수의 프록시다. 드리프트형(항상 2회) 피처는 합의와 어긋나 탈락.
        idxs = np.where(keep)[0]
        cmat = {}
        for base, a in F.items():
            cc = np.array([count_reps(a[i]) for i in idxs])
            if (cc >= 0).sum() >= 30:
                cmat[base] = cc
        if len(cmat) < 8:
            continue
        M = np.stack(list(cmat.values()))          # (피처, 클립)
        cons = np.full(M.shape[1], -1)
        for j in range(M.shape[1]):
            col = M[:, j]
            col = col[col >= 1]
            if len(col) >= 5:
                vals, freq = np.unique(col, return_counts=True)
                cons[j] = int(vals[freq.argmax()])
        okj = cons >= 2
        rows_t = []
        for base, cc in cmat.items():
            m = okj & (cc >= 0)
            if m.sum() < 30:
                continue
            agree = float((np.abs(cc[m] - cons[m]) <= 1).mean())
            med = float(np.median(cc[m]))
            vals, freq = np.unique(cc[m], return_counts=True)
            rows_t.append(dict(feature=base, n=int(m.sum()), mode=int(vals[freq.argmax()]),
                               median=med, consistency=agree, zero_share=float((cc[m] == 0).mean())))
        t = pd.DataFrame(rows_t)
        if t.empty:
            continue
        t["exercise"] = ex
        t["consensus_median"] = float(np.median(cons[okj])) if okj.sum() else np.nan
        results.append(t)
        # 채택: 중앙 렙수 ≥2.5(진짜 진동) 중 합의 일치율 최대
        cand = t[(t["median"] >= 2.0) & (t["mode"] <= 8)].sort_values(["consistency", "n"], ascending=False)
        if len(cand):
            b = cand.iloc[0]
            r2 = cand.iloc[1] if len(cand) > 1 else None
            period = 16 * FRAME_DT / max(b["median"], 1e-6)
            best_rows.append(dict(
                exercise=ex, floor=floor, best=b.feature, consistency=round(b.consistency, 3),
                mode=int(b["mode"]), runner_up=(r2.feature if r2 is not None else ""),
                runner_consistency=(round(r2.consistency, 3) if r2 is not None else np.nan),
                period_s=round(period, 2), samples_per_rep_app=round(period * APP_FPS, 1),
                lr_corr=round(lr, 2) if np.isfinite(lr) else np.nan,
            ))
        else:
            best_rows.append(dict(exercise=ex, floor=floor, best="", consistency=np.nan, mode=0,
                                  runner_up="", runner_consistency=np.nan, period_s=np.nan,
                                  samples_per_rep_app=np.nan, lr_corr=round(lr, 2) if np.isfinite(lr) else np.nan))

    allt = pd.concat(results, ignore_index=True)
    best = pd.DataFrame(best_rows)

    # 유형 분류: 교대형(좌우 역위상) / 동적 / 등척성·비주기
    def classify(r):
        if np.isfinite(r.lr_corr) and r.lr_corr < -0.35 and r.consistency == r.consistency and r.consistency >= 0.5:
            return "교대형"
        if r.consistency == r.consistency and r.consistency >= 0.6 and r["mode"] >= 2:
            return "동적"
        if r.consistency == r.consistency and r.consistency >= 0.45 and r["mode"] >= 2:
            return "동적(약)"
        return "등척성/비주기"

    best["type"] = best.apply(classify, axis=1)

    # 서브샘플링 스트레스: 최적 신호를 stride 2(1.2s, 렙당 ~절반 샘플)로 다시 세면
    stress = []
    for _, r in best[best.best != ""].iterrows():
        ex = r.exercise
        F, keep = (floor_series(ex, FLOOR_VIEW[ex]) if r.floor else (F3, ex_of == ex))
        a = F[r.best]
        idx = np.where(keep)[0]
        c1 = np.array([count_reps(a[i]) for i in idx])
        c2 = np.array([count_reps(a[i, ::2], min_gap=1) for i in idx])
        ok = (c1 > 0) & (c2 >= 0)
        if ok.sum() < 20:
            continue
        stress.append(dict(exercise=ex, samples_per_rep_full=round(16 / max(np.median(c1[ok]), 1e-6), 1),
                           recovery=float(np.median(c2[ok] / np.maximum(c1[ok], 1))),
                           period_s=r.period_s))
    stress = pd.DataFrame(stress)

    allt.to_csv(OUT / "rep_signal_survey_all.csv", index=False, encoding="utf-8-sig")
    best.to_csv(OUT / "rep_signals.csv", index=False, encoding="utf-8-sig")
    stress.to_csv(OUT / "rep_subsample_stress.csv", index=False, encoding="utf-8-sig")
    write_report(best, stress)


def write_report(best: pd.DataFrame, stress: pd.DataFrame) -> None:
    L = ["# 전 종목 렙 신호 조사 — 무엇으로 렙을 나누나\n",
         "선별 원리: 렙 라벨이 없어도, 올바른 렙 신호는 같은 종목의 모든 클립에서 비슷한 횟수를 센다. "
         "(종목×피처) 전수에 자기보정 카운터를 돌려 **클립 간 카운트 일관성**(최빈값±1 비율)으로 채택했다. "
         "AIHub 프레임 간격 0.6s(렙당 ~4샘플)는 앱 0.3s 보다 성긴 조건 — 여기서 세지면 앱에서 더 잘 세진다.\n",
         "| 종목 | 유형 | 렙 신호 | 합의일치 | 최빈렙 | 주기(s) | 앱 샘플/렙 | 좌우상관 |",
         "|---|---|---|---|---|---|---|---|"]
    for _, r in best.sort_values(["type", "consistency"], ascending=[True, False]).iterrows():
        L.append(f"| {r.exercise}{' 🧎' if r.floor else ''} | {r.type} | `{r.best}` | "
                 f"{r.consistency if r.consistency == r.consistency else float('nan'):.2f} | {r['mode']} | "
                 f"{r.period_s} | {r.samples_per_rep_app} | {r.lr_corr} |")
    dyn = best[best.type.str.startswith("동적")]
    L += ["",
          f"- 동적 {len(dyn)} · 교대형 {(best.type == '교대형').sum()} · 등척성/비주기 {(best.type == '등척성/비주기').sum()}",
          f"- 동적 종목 일관성 중앙값 **{dyn.consistency.median():.2f}** · 렙 주기 중앙값 **{dyn.period_s.median():.1f}s** "
          f"→ 앱(3.3fps) 렙당 샘플 중앙값 **{dyn.samples_per_rep_app.median():.0f}개**",
          "",
          "## 서브샘플링 스트레스 — 렙당 샘플이 절반이 되면\n",
          "| 종목 | 렙당 샘플(0.6s) | 1.2s 로 성기게 했을 때 카운트 회복률 |", "|---|---|---|"]
    for _, r in stress.sort_values("recovery").iterrows():
        L.append(f"| {r.exercise} | {r.samples_per_rep_full} | {r.recovery:.2f} |")
    L += ["",
          f"- 회복률 중앙값 **{stress.recovery.median():.2f}** — 렙당 ~2샘플에서는 카운트가 붕괴한다. "
          f"**최소 렙당 4샘플**이 실측 하한선.",
          "",
          "## 앱 정책으로 번역 (빠른 렙 대처)",
          "- 렙당 4샘플 하한 ⇒ 3.3fps 에서 **렙 주기 ≥ 1.2s** 까지 안전. 그보다 빠르면 놓친다.",
          "- 대처 1 — **주기 추정 기반 적응 샘플링**: 카운터가 추정한 렙 주기가 1.5s 아래로 내려오면 "
          "InferencePolicy 를 ACTIVE 한정 5~6fps 로 부스트(주기 0.7s 까지 커버), 세트 끝나면 복귀.",
          "- 대처 2 — **에일리어싱 자가 진단**: 측정 주기가 샘플 간격의 2~3배에 근접하면 언더카운트 위험 신호 — "
          "카운트를 확정하지 말고 '빠른 반복 감지' 로 표시.",
          "- 대처 3 — **반사이클 카운트**: 상단 복귀뿐 아니라 하단 도달도 세어 유효 해상도 2× (오카운트 위험과 교환 — 부스트 불가 기기 폴백).",
          "- 대처 4 — UX 최후선: 부스트로도 못 따라가는 초고속 반복은 '정확한 카운트를 위해 조금 천천히' 안내. "
          "폼 관점에서도 통제된 템포가 권장되므로 코칭과 정합.",
          ]
    L += ['', '## 최종 채택 (큐레이션) — 설문 + 기기 실측 + 운동학\n', '서서 하는 32종목은 설문 승자를 그대로 쓴다(합의일치 0.77~0.95, 운동학 타당: 스쿼트→무릎각, 데드→상체숙임, 컬→팔꿈치…).', '**바닥 9종목은 AIHub 0.6s 주석으로 판별 불가** — 전 피처가 0.65~0.81 동률이고 전부 중앙 2회로 언더카운트', '(푸시업 예: knee_ground 0.731 vs wrist_shoulder_d 0.670 — 유의미한 차이가 아님). 결정적 근거는 기기 실측:', 'wrist_shoulder_d 가 3.3fps(렙당 ~17샘플)에서 정답 3~4회를 4회로 적중했다. 따라서:\n', '| 바닥 종목 | 채택 신호 | 근거 |', '|---|---|---|', '| 푸시업·니푸쉬업 | `wrist_shoulder_d` | **기기 검증**(4/4 적중) |', '| 크런치 | `head_ground` | 운동학(상체 말아올림) — M0 검증 대상 |', '| 라잉 레그 레이즈 | `hip_ang` | 운동학(다리 올림) — M0 |', '| 힙쓰러스트 | `hip_dev_ankle` | 운동학(골반 상승) — M0 |', '| Y-Exercise | `hand_shoulder_off` | 운동학(팔 올림) — M0 |', '| 시저크로스·바이시클 | `knee_gap2d` | 교대형 — M0 |', "| **플랭크** | — (**등척성 확정**) | 설문의 '2회' = 진입+이탈 아티팩트. 상태기계 SETTLING/END 가 흡수 → ACTIVE 중 카운트 0 = HoldTimer |", '', '교대형(시저크로스·바이시클·스탠딩 니업·스탠딩 사이드 크런치)의 좌우 역위상은 0.6s 에일리어싱 때문에', '이 데이터로 확인 불가(lr_corr −0.24~0.33) — 운동 정의로 분류하고 기기 3.3fps 로그로 재검한다.']
    (OUT / "REP_SIGNALS.md").write_text("\n".join(L), encoding="utf-8")
    print(f"[done] 종목 {len(best)} · 동적 {len(dyn)} → {OUT / 'REP_SIGNALS.md'}")


if __name__ == "__main__":
    main()
