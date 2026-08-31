# -*- coding: utf-8 -*-
"""렙 유효성(ROM) 임계값 도출 — "얕으면 무효 렙" 의 기준을 데이터에서 뽑는다 (spec §27 수정판).

원리 (사용자 제안의 수정판 — 4단계 전부가 아니라 하단 ROM 하나만):
- 렙 유효성 = 사이클의 **노력 극값**(수축 끝)이 임계값에 도달했는가.
- 노력 방향은 듀티사이클 비대칭으로 자동 판정: 휴식 자세에 머무는 시간이 길므로
  중앙값이 치우친 반대쪽 극값이 노력 끝이다 (스쿼트: 서 있는 시간 김 → 중앙값 높음 → 노력=min).
- 임계값은 **전 조건 정상(全조건 true) 클립의 렙 극값 분포**에서 관대한 분위수(기준 렙 90% 통과):
  노력=min 이면 p90(이보다 얕으면 무효), 노력=max 이면 p10.
  "고관절이 무릎보다 낮게" 같은 문장 기준은 관절 좌표계에서 그대로 성립하지 않으므로(§21,
  AIHub 정상 스쿼트 98% 가 문장 기준 미달) 임계값은 반드시 데이터에서.

검증: ROM 성격의 기존 조건이 있는 종목(런지 '무릎 90도', 푸시업 '가슴의 충분한 이동')에서
위반 라벨 클립의 렙이 실제로 더 많이 무효 판정되는지 대조(판별력 리포트).

한계: AIHub 0.6s 는 렙당 3~4샘플이라 극값이 얕게 잡힌다 → 임계값도 그만큼 관대(beta 방향으로
안전). 앱(렙당 10~17샘플)의 실측 극값은 더 깊게 잡히므로 통과가 오히려 쉬워진다. 실기기
라벨("N회 중 깊은 것 M회")로 재보정 대상.
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
from rep_signal_survey import FLOOR_VIEW, floor_series

# 앱 RepSignals.kt 와 반드시 일치 (검증 게이트가 main 에서 대조). 종목 → 렙 신호.
APP_SIGNALS = {
    # 바닥
    "푸시업": "wrist_shoulder_d", "니푸쉬업": "wrist_shoulder_d", "크런치": "head_ground",
    "라잉 레그 레이즈": "hip_ang", "힙쓰러스트": "hip_dev_ankle", "Y - Exercise": "hand_shoulder_off",
    "시저크로스": "knee_gap2d", "바이시클 크런치": "knee_gap2d",
    # 서서 — 힙 힌지·스쿼트·런지
    "바벨 데드리프트": "hip_mean", "바벨 스티프 데드리프트": "hip_mean", "굿모닝": "hip_mean",
    "바벨 스쿼트": "knee_mean", "버피 테스트": "knee_mean", "크로스 런지": "knee_mean",
    "바벨 런지": "knee_minside", "사이드 런지": "knee_minside",
    "스텝 포워드 다이나믹 런지": "knee_out_mean", "스텝 백워드 다이나믹 런지": "hip_mean", "스탠딩 니업": "hip_mean",
    # 팔꿈치·전완·손
    "풀업": "elbow_mean", "딥스": "elbow_mean", "바벨 로우": "elbow_mean", "덤벨 벤트오버 로우": "elbow_mean",
    "바벨 컬": "elbow_mean", "덤벨 컬": "elbow_mean", "페이스 풀": "elbow_mean",
    "랫풀 다운": "forearm_vert_mean", "사이드 레터럴 레이즈": "forearm_vert_mean", "프런트 레이즈": "forearm_vert_mean",
    "업라이트로우": "forearm_vert_mean", "덤벨 체스트 플라이": "forearm_vert_mean",
    "덤벨 인클라인 체스트 플라이": "forearm_vert_mean",
    "오버 헤드 프레스": "palm_h_sh", "케이블 푸시 다운": "palm_h_sh", "라잉 트라이셉스 익스텐션": "palm_h_sh",
    "덤벨 풀 오버": "palm_h_sh", "로잉머신": "palm_fwd_knee",
    "행잉 레그 레이즈": "hip_below_knee", "케이블 크런치": "knee_elbow_dist",
}

# ROM 검증용 기존 조건 (있는 종목만) — 위반 클립의 무효율이 정상보다 높아야 판별력 인정
ROM_CONDS = {
    "푸시업": "가슴의 충분한 이동", "니푸쉬업": "가슴의 충분한 이동",
    "바벨 런지": "앞다리 무릎 각도 90도", "딥스": "이완 시 팔꿈치 각도 90도",
    "크런치": "견갑골이 지면으로부터 충분히 올라옴",
}


def cycles_with_extrema(series: np.ndarray, frac: float = 0.15) -> list[tuple[float, float]]:
    """v3 카운터로 사이클 분할 → 사이클별 (min, max). 성긴 신호 규약 그대로 (rep_signal_survey.count_reps)."""
    v = series[np.isfinite(series)]
    if len(v) < 8:
        return []
    if len(v) > 24:
        v = np.array([np.median(v[max(0, i - 1):i + 2]) for i in range(len(v))])
    p10, p90 = np.quantile(v, [0.10, 0.90])
    center, half = (p10 + p90) / 2, frac * (p90 - p10)
    lo, hi = center - half, center + half
    if hi <= lo:
        return []
    state = "high" if v[0] >= hi else ("low" if v[0] <= lo else "mid")
    out, start = [], 0
    for i, x in enumerate(v):
        if state != "low" and x <= lo:
            state = "low"
        elif state == "low" and x >= hi:
            state = "high"
            seg = v[start:i + 1]
            out.append((float(np.min(seg)), float(np.max(seg))))
            start = i + 1
    return out


def main() -> None:
    clips = pd.read_parquet(DATA / "clips.parquet")
    conds = pd.read_parquet(DATA / "conditions.parquet")
    ids, arr = load_kp3d(DATA)
    ids, arr, _ = apply_qc_mask(ids, arr, DATA)
    ids = np.array(ids)
    ex_of = clips.set_index("clip_id").exercise.reindex(ids).to_numpy()
    F3 = compute_frame_features(arr)

    # 클립별 '전 조건 정상' 여부
    all_ok = conds.groupby("clip_id").value.all()

    rows, ktable = [], []
    for ex, sig in APP_SIGNALS.items():
        floor = ex in FLOOR_VIEW
        if floor:
            F, _ = floor_series(ex, FLOOR_VIEW[ex])
            k2ids = None  # floor_series 는 클립 순서를 np.unique 로 반환 — 재구성
            cids = np.unique(pd.read_parquet(DATA / "kp2d.parquet", columns=["clip_id", "view_letter"])
                             .query("view_letter == @FLOOR_VIEW[@ex]").clip_id
                             [lambda s: s.isin(clips[clips.exercise == ex].clip_id)])
            series_of = {cid: F[sig][i] for i, cid in enumerate(cids)} if sig in F else {}
        else:
            m = ex_of == ex
            series_of = {cid: F3[sig][i] for i, cid in zip(np.where(m)[0], ids[m])} if sig in F3 else {}
        if not series_of:
            print(f"  [skip] {ex}: 신호 {sig} 없음")
            continue

        # 듀티사이클 비대칭으로 노력 방향 자동 판정 (전 클립 풀링)
        allv = np.concatenate([s[np.isfinite(s)] for s in series_of.values() if np.isfinite(s).any()])
        p10a, p90a = np.quantile(allv, [0.10, 0.90])
        rel = (np.median(allv) - p10a) / max(p90a - p10a, 1e-9)
        direction = "min" if rel >= 0.5 else "max"   # 휴식이 위쪽 → 노력은 아래쪽 극값

        ref_ids = [c for c in series_of if bool(all_ok.get(c, False))]
        used_all = False
        if len(ref_ids) < 20:            # 정상 조합 클립이 적으면 전체로 폴백 (관대한 분위수라 안전)
            ref_ids = list(series_of)
            used_all = True
        ext = []
        for c in ref_ids:
            for mn, mx in cycles_with_extrema(series_of[c]):
                ext.append(mn if direction == "min" else mx)
        if len(ext) < 30:
            print(f"  [skip] {ex}: 사이클 {len(ext)}개 부족")
            continue
        ext = np.array(ext)
        thr = float(np.quantile(ext, 0.90 if direction == "min" else 0.10))
        pass_ref = float((ext <= thr).mean() if direction == "min" else (ext >= thr).mean())

        # 판별력: ROM 조건이 있으면 위반 클립 무효율 vs 정상 클립 무효율
        disc = ""
        if ex in ROM_CONDS:
            cv = conds[(conds.exercise == ex) & (conds.condition == ROM_CONDS[ex])]
            lab = cv.drop_duplicates("clip_id").set_index("clip_id").value
            inval = {True: [], False: []}
            for c, s in series_of.items():
                y = lab.get(c)
                if y is None or (isinstance(y, float) and np.isnan(y)):
                    continue
                for mn, mx in cycles_with_extrema(s):
                    e = mn if direction == "min" else mx
                    bad = (e > thr) if direction == "min" else (e < thr)
                    inval[bool(y)].append(bad)
            if inval[True] and inval[False]:
                nrm, vio = np.mean(inval[True]), np.mean(inval[False])
                disc = f"정상 무효율 {nrm:.2f} vs 위반 {vio:.2f} ({'판별력 ✓' if vio > nrm + 0.1 else '약함'})"

        rows.append(dict(exercise=ex, signal=sig, direction=direction, threshold=round(thr, 4),
                         n_cycles=len(ext), ref=("전체폴백" if used_all else "전조건정상"),
                         pass_ref=round(pass_ref, 2), duty_rel=round(rel, 2), discrimination=disc))
        ktable.append(f'        romOf("{ex}", "{direction}", {thr:.4f}f)')
        print(f"  {ex:16s} {sig:18s} 노력={direction} thr={thr:.3f} (사이클 {len(ext)}, {rows[-1]['ref']}) {disc}")

    df = pd.DataFrame(rows)
    df.to_csv(OUT / "rep_validity_thresholds.csv", index=False, encoding="utf-8-sig")
    (OUT / "rep_validity_kotlin.txt").write_text("\n".join(ktable), encoding="utf-8")

    L = ["# 렙 유효성(ROM) 임계값 — '얕으면 무효'의 데이터 기준\n",
         "노력 방향은 듀티사이클 비대칭으로 자동 판정(휴식 체류가 긴 쪽의 반대 극값), 임계값은 "
         "**전 조건 정상 클립**의 렙 극값 분포에서 기준 렙 90% 가 통과하는 분위수. "
         "문장 기준(예: '고관절이 무릎 아래')을 좌표에 직역하면 정상 스쿼트 98% 가 실격 — 임계값은 데이터에서.\n",
         "| 종목 | 신호 | 노력 | 임계값 | 사이클 | 기준모집단 | 판별력(ROM 조건 보유 종목) |",
         "|---|---|---|---|---|---|---|"]
    for _, r in df.iterrows():
        L.append(f"| {r.exercise} | `{r.signal}` | {r.direction} | {r.threshold} | {r.n_cycles} | {r.ref} | {r.discrimination} |")
    L += ["", f"- 종목 {len(df)}개 도출. AIHub 0.6s 극값은 얕게 잡히므로 임계값도 관대(beta 안전 방향) — "
          "앱(렙당 10~17샘플)에서는 통과가 더 쉽다. 실기기 라벨('N회 중 깊은 것 M회')로 재보정.",
          "- 무효 판정은 '안 세기'가 아니라 '세되 무효 + 사유 발화' — 심판 방식 (UX 원칙)."]
    (OUT / "REP_VALIDITY.md").write_text("\n".join(L), encoding="utf-8")
    print(f"\n[done] {len(df)}종목 → {OUT / 'REP_VALIDITY.md'} · rep_validity_kotlin.txt")


if __name__ == "__main__":
    main()
