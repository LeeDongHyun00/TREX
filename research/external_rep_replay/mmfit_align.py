# -*- coding: utf-8 -*-
"""MM-Fit 영상 시간축 ↔ 라벨 시간축 정렬 — 세트별 지연을 재고, 정답 창을 영상 시각으로 옮긴 index 를 만든다.

왜 있나
    extract_mediapipe.py 는 라벨 프레임 번호를 영상 프레임 번호로 그대로 썼다(지연 0 가정). w00~w15 는 맞지만(세트별 지연 0~±3프레임),
    **w16~w20 은 영상이 라벨보다 점점 앞선다** — 워크아웃 시작에서 0, 약 초당 −0.05프레임씩 벌어져 후반 컬 세트에서는 −2.5~−5.8 s.
    (w19 는 중간에 +30프레임 도약도 있다.) 라벨·pose_3d 는 서로 맞으므로(3D 재생 결과가 정상) 어긋난 쪽은 배포된 영상 파일이다.
    그대로 채점하면 실제 반복이 정답 창 앞으로 빠져 '앞 헛카운트' + '세트 안 놓침' 으로 이중 집계된다 — 카운터가 아니라 채점의 오류다.

어떻게 재나
    세트 창(라벨 ± 0.5 s) 안의 MediaPipe 대표 신호(스쿼트·런지 knee_mean, 컬 elbow_minside)와 같은 세트의 pose_3d 신호를
    lag ∈ [−max_lag, +max_lag] 프레임에서 상관시켜(mmfit_pair_compare.estimate_lag) 최대인 lag 를 고른다.
      r ≥ min_r   → 그 세트의 lag 를 쓴다                                       (how = "xcorr")
      r <  min_r  → 같은 워크아웃의 신뢰 세트(≥ 3개)로 lag = a + b·t 직선을 맞춰 그 시각의 값을 쓴다 (how = "fit")
                    신뢰 세트가 3개 미만이면 0                                     (how = "none")
    반복 주기가 ~2 s 라 상관이 한 주기(±60프레임) 옆에서 가짜 최대를 낼 수 있다 — 그래서 r 문턱을 두고, 낮으면 직선으로 메운다.
    카운터 설정이 아니라 **정답의 시각**을 고치는 것이다(원칙 #4 와 무관 — 어떤 카운터 파라미터도 이 데이터로 맞추지 않는다).

출력 (--out 폴더)
    index.json      세트마다 startMs·endMs 를 영상 시각으로 옮긴 index. 원래 값은 labelStartMs·labelEndMs, 옮긴 양은 labelShiftMs,
                    근거는 lagFrames·lagR·lagHow. capture 는 원본 캡처의 절대 경로(캡처를 복사하지 않는다). run_replay.py 에 그대로 준다.
    alignment.json  세트별 지연 표 + 워크아웃별 직선(a, b, 잔차) — 결과 폴더에 커밋할 수 있는 크기다.

사용법
    python mmfit_align.py ../../data/mm-fit/mp_captures ../../data/mm-fit/mm-fit --out ../../data/mm-fit/mp_captures_aligned
    python run_replay.py ../../data/mm-fit/mp_captures_aligned --out <결과 폴더>
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mmfit_pair_compare as mpc  # noqa: E402

SIGNAL = {"바벨 스쿼트": "knee_mean", "스텝 포워드 다이나믹 런지": "knee_mean", "덤벨 컬": "elbow_minside"}
MARGIN_MS = 500


def set_lag(cap_path: Path, s: dict, gt: dict, f0: int, fps: float, max_lag: int) -> dict:
    cap = mpc.read_landmark_capture(cap_path)
    status, feat = mpc.app_features(cap)
    frames = np.rint(cap["t"] * fps / 1000.0).astype(int)
    in_set = (cap["t"] >= s["startMs"] - MARGIN_MS) & (cap["t"] <= s["endMs"] + MARGIN_MS)
    sample = {"set": s, "exercise": s["exercise"], "primary": SIGNAL[s["exercise"]], "t": cap["t"], "frames": frames,
              "status": status, "feat": feat, "inSet": in_set}
    est = mpc.estimate_lag([sample], gt, f0, max_lag)
    r = est.get("r")
    return {"lagFrames": int(est["lag"]), "r": (None if r is None or r != r else float(r)),
            "r0": (None if est.get("r0") is None or est["r0"] != est["r0"] else float(est["r0"]))}


def align(mp_dir: Path, root: Path, out: Path, max_lag: int, min_r: float) -> dict:
    index = json.loads((mp_dir / "index.json").read_text(encoding="utf-8"))
    by_w: dict[str, list[dict]] = {}
    for s in index["sets"]:
        if s.get("exercise") in SIGNAL:
            by_w.setdefault(s["workout"], []).append(s)
    report, fixed_sets = {"maxLagFrames": max_lag, "minR": min_r, "workouts": {}, "sets": {}}, []
    for w, sets in sorted(by_w.items()):
        pp, lp = root / w / f"{w}_pose_3d.npy", root / w / f"{w}_labels.csv"
        if not pp.is_file() or not lp.is_file():
            print(f"skip {w}: pose_3d/labels 없음", file=sys.stderr)
            continue
        fps = mpc.workout_fps(sets, mpc.load_labels(lp))
        f0, gt = mpc.gt_features(np.load(pp))
        rows = {s["capture"]: set_lag(mp_dir / s["capture"], s, gt, f0, fps, max_lag) for s in sets}
        good = [(s["startMs"] / 1000.0, rows[s["capture"]]["lagFrames"]) for s in sets
                if rows[s["capture"]]["r"] is not None and rows[s["capture"]]["r"] >= min_r]
        fit = None
        if len(good) >= 3:
            x, y = np.array(good).T
            b, a = np.polyfit(x, y, 1)
            fit = {"a": float(a), "bFramesPerS": float(b), "maxResidualFrames": float(np.max(np.abs(y - (a + b * x)))),
                   "points": len(good)}
        report["workouts"][w] = {"fps": fps, "sets": len(sets), "trusted": len(good), "fit": fit}
        for s in sets:
            row = rows[s["capture"]]
            if row["r"] is not None and row["r"] >= min_r:
                lag, how = row["lagFrames"], "xcorr"
            elif fit is not None:
                lag, how = int(round(fit["a"] + fit["bFramesPerS"] * s["startMs"] / 1000.0)), "fit"
            else:
                lag, how = 0, "none"
            shift = int(round(lag * 1000.0 / fps))
            t = dict(s)
            t.update(labelStartMs=s["startMs"], labelEndMs=s["endMs"], startMs=s["startMs"] + shift, endMs=s["endMs"] + shift,
                     labelShiftMs=shift, lagFrames=lag, lagR=row["r"], lagHow=how, capture=str((mp_dir / s["capture"]).resolve()))
            fixed_sets.append(t)
            report["sets"][s["capture"]] = {"workout": w, "exercise": s["exercise"], "labelStartMs": s["startMs"],
                                            "xcorrLagFrames": row["lagFrames"], "r": row["r"], "r0": row["r0"],
                                            "usedLagFrames": lag, "how": how, "shiftMs": shift}
    out.mkdir(parents=True, exist_ok=True)
    fixed = {k: v for k, v in index.items() if k != "sets"}
    fixed["source"] = f"{index.get('source', '')} | mmfit_align.py: 정답 창을 영상 시각으로 옮김(세트별 상관 지연, r ≥ {min_r})"
    fixed["sets"] = fixed_sets
    (out / "index.json").write_text(json.dumps(fixed, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "alignment.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mediapipe", type=Path, help="extract_mediapipe.py mmfit 산출 폴더(index.json)")
    ap.add_argument("dataset_root", type=Path, help="MM-Fit 루트(wNN/wNN_pose_3d.npy, wNN_labels.csv)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-lag", type=int, default=300, help="프레임(30fps 에서 ±10 s)")
    ap.add_argument("--min-r", type=float, default=0.8)
    a = ap.parse_args()
    rep = align(a.mediapipe, a.dataset_root, a.out, a.max_lag, a.min_r)
    print("| 워크아웃 | 세트 | 신뢰 세트 | 직선 기울기 (프레임/초) | 잔차 최대 | 옮긴 양 최대 (s) |")
    print("|---|---:|---:|---:|---:|---:|")
    for w, v in rep["workouts"].items():
        shifts = [abs(x["shiftMs"]) for x in rep["sets"].values() if x["workout"] == w]
        f = v["fit"] or {}
        print(f"| {w} | {v['sets']} | {v['trusted']} | {f.get('bFramesPerS', float('nan')):+.4f} | "
              f"{f.get('maxResidualFrames', float('nan')):.1f} | {max(shifts) / 1000.0:.2f} |")
    fits = sum(1 for x in rep["sets"].values() if x["how"] == "fit")
    print(f"\n세트 {len(rep['sets'])} · 직선으로 메운 세트 {fits} · → {a.out / 'index.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
