# -*- coding: utf-8 -*-
"""AIHub GT 3D 로 렙 신호 설계 질문에 답한다 — 사용자 PC 에서 실행 (데이터가 PC 에만 있다).

MM-Fit 재생(research/external_rep_replay/README.md §4·§7)과 설계(docs/REP_ENGINE_DESIGN.md)가 AIHub 쪽에 남긴 질문:
  Q1 스텝 포워드 다이나믹 런지의 `knee_out_mean` 은 AIHub 에서도 반복당 스윙이 0.10 게이트에 못 미쳤는가?
     (그렇다면 "AIHub 에서 배운 것이 MM-Fit 에서 깨졌다" 가 아니라 AIHub 에서도 통과한 적 없는 설정이다)
  Q2 런지 신호를 `knee_mean` 으로 바꾸면 ROM(가동범위) 기준값은 얼마인가 — 정상 클립 사이클 바닥의 p90 (rep_validity_thresholds.py 와 같은 규약)
  Q3 덤벨 컬을 팔별로 세면 팔별 ROM 기준값과 정상 반복의 스윙 분포는? (설계 §4.4 의 50° 게이트 근거)
     AIHub 컬은 양팔 동시인가(양쪽 팔꿈치가 동시에 90° 아래인 프레임 비율)?
  Q4 스쿼트 `knee_mean` 정상 반복의 스윙 분포 (35° 게이트 여유)

입력: parse_labels.py 산출물 폴더(clips.parquet · conditions.parquet · kp3d.parquet, 있으면 kp3d_frame_ok.parquet).
출력: research/external_rep_replay/results/aihub_rep_signal_check.json (커밋용, 작다) + 화면 표.
AIHub 프레임 간격 0.6s 라 사이클 분할은 성긴 신호 규약(v3 밴드, rep_validity_thresholds.cycles_with_extrema)을 쓴다.
MM-Fit 값과 비교할 때 극값은 얕게 잡힌다는 점(렙당 3~4샘플)을 같이 읽어야 한다.

사용법 (저장소 루트에서):
    python research/aihub_fitness/aihub_rep_signal_check.py --data "<outputs 폴더>"
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from features import apply_qc_mask, compute_frame_features, load_kp3d  # noqa: E402


def cycles_with_extrema(series: np.ndarray, frac: float = 0.15) -> list[tuple[float, float]]:
    """rep_validity_thresholds.cycles_with_extrema 와 같은 규약(v3 밴드, 성긴 신호). 그 모듈은 sklearn 을 끌어와 여기 복사했다."""
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

DEFAULT_DATA = [
    Path(r"C:/Users/hp276/Desktop/trex/.claude/worktrees/correct-exercise-form-6ddf55/research/aihub_fitness/outputs"),
    HERE / "outputs",
]
RESULT = HERE.parent / "external_rep_replay" / "results" / "aihub_rep_signal_check.json"
EXERCISES = {"바벨 스쿼트": ["knee_mean"], "스텝 포워드 다이나믹 런지": ["knee_out_mean", "knee_mean", "knee_minside"],
             "덤벨 컬": ["elbow_mean", "elbow_L", "elbow_R"]}
GATES = {"knee_out_mean": 0.10, "knee_mean": 35.0, "knee_minside": 35.0, "elbow_mean": 35.0, "elbow_L": 35.0, "elbow_R": 35.0}


def q(a, p):
    return float(np.quantile(a, p)) if len(a) else None


def stats(a):
    a = np.asarray([x for x in a if np.isfinite(x)])
    return dict(n=int(len(a)), p10=q(a, .1), p50=q(a, .5), p90=q(a, .9)) if len(a) else dict(n=0)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, default=None)
    args = ap.parse_args()
    data = args.data or next((p for p in DEFAULT_DATA if (p / "kp3d.parquet").is_file()), None)
    if data is None or not (data / "kp3d.parquet").is_file():
        raise SystemExit("kp3d.parquet 을 못 찾았다 — parse_labels.py 산출물 폴더를 --data 로 준다")
    print(f"data: {data}")
    clips = pd.read_parquet(data / "clips.parquet")
    conds = pd.read_parquet(data / "conditions.parquet")
    ids, arr = load_kp3d(data)
    ids, arr, _ = apply_qc_mask(ids, arr, data)
    ids = np.array(ids)
    ex_of = clips.set_index("clip_id").exercise.reindex(ids).to_numpy()
    all_ok = conds.groupby("clip_id").value.all()
    F = compute_frame_features(arr)

    out = {"data": str(data), "frameIntervalS": 0.6, "note": "AIHub 0.6s 표본 — 사이클 극값은 얕게, 스윙은 작게 잡힌다", "exercises": {}}
    print("\n| 종목 | 신호 | 클립 | 정상 클립 | 사이클 | 사이클 스윙 p10/p50/p90 | 게이트 | 스윙 ≥ 게이트 사이클 비율 | 바닥 p90 (ROM 후보, 정상 클립) |")
    print("|---|---|---:|---:|---:|---|---:|---:|---:|")
    for ex, sigs in EXERCISES.items():
        m = np.where(ex_of == ex)[0]
        ex_out = {"clips": int(len(m)), "signals": {}}
        for sig in sigs:
            if sig not in F:
                print(f"| {ex} | {sig} | — | 피처 없음 |"); continue
            swings, bottoms, tops, normal_swings, normal_bottoms, per_clip_swing = [], [], [], [], [], []
            n_normal = 0
            for i in m:
                s = F[sig][i]
                cyc = cycles_with_extrema(s)
                v = s[np.isfinite(s)]
                if len(v):
                    per_clip_swing.append(float(np.quantile(v, .95) - np.quantile(v, .05)))
                normal = bool(all_ok.get(ids[i], False))
                n_normal += normal
                for mn, mx in cyc:
                    swings.append(mx - mn); bottoms.append(mn); tops.append(mx)
                    if normal:
                        normal_swings.append(mx - mn); normal_bottoms.append(mn)
            gate = GATES[sig]
            sw = np.array(swings)
            entry = dict(cycles=int(len(sw)), normalClips=int(n_normal), swing=stats(swings), normalSwing=stats(normal_swings),
                         perClipSwingP95P5=stats(per_clip_swing), bottom=stats(bottoms), top=stats(tops),
                         normalBottom=stats(normal_bottoms), gate=gate,
                         fracCyclesAboveGate=float((sw >= gate).mean()) if len(sw) else None,
                         romCandidateP90NormalBottom=q(np.array(normal_bottoms), .9) if len(normal_bottoms) >= 30 else None)
            ex_out["signals"][sig] = entry
            s_ = entry["swing"]; nb = entry["romCandidateP90NormalBottom"]
            print(f"| {ex} | `{sig}` | {len(m)} | {n_normal} | {entry['cycles']} | "
                  f"{s_.get('p10', float('nan')):.3f} / {s_.get('p50', float('nan')):.3f} / {s_.get('p90', float('nan')):.3f} | {gate:g} | "
                  f"{(entry['fracCyclesAboveGate'] or 0):.2f} | {nb if nb is None else round(nb, 2)} |")
        if ex == "덤벨 컬" and "elbow_L" in F and "elbow_R" in F:
            both = []
            for i in m:
                l, r = F["elbow_L"][i], F["elbow_R"][i]
                ok = np.isfinite(l) & np.isfinite(r)
                if ok.any():
                    both.append(float(((l < 90) & (r < 90))[ok].mean()))
            corr = []
            for i in m:
                l, r = F["elbow_L"][i], F["elbow_R"][i]
                ok = np.isfinite(l) & np.isfinite(r)
                if ok.sum() >= 8 and np.std(l[ok]) > 1 and np.std(r[ok]) > 1:
                    corr.append(float(np.corrcoef(l[ok], r[ok])[0, 1]))
            ex_out["bilateral"] = dict(fracFramesBothBelow90=stats(both), lrCorr=stats(corr))
            print(f"\n덤벨 컬 동시성: 양쪽 팔꿈치 < 90° 프레임 비율 중앙값 {np.median(both):.2f}, 좌우 상관 중앙값 {np.median(corr):.2f} "
                  f"(MM-Fit 교대 컬은 0.00 / −0.28)")
        out["exercises"][ex] = ex_out
    RESULT.parent.mkdir(parents=True, exist_ok=True)
    RESULT.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n[done] → {RESULT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
