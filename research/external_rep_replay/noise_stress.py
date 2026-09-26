# -*- coding: utf-8 -*-
"""잡음 주입 스트레스 테스트 — 휴대폰·MediaPipe 조건을 흉내 낸 섭동 아래에서 카운터가 버티는가.

설계 문서(docs/REP_ENGINE_DESIGN.md)의 코어는 깨끗한 3D 포즈에서 잰 것이다. 휴대폰에서는 관절이 떨리고, 한 프레임씩 튀고,
검출이 끊기고, 추론 간격이 흔들리며, MediaPipe 가 깊은 굴곡을 얕게 읽는다. 폰 잡음의 실제 분포는 아직 재지 않았으므로
여기서는 **크기를 단계별로 키우며** 어느 크기에서 무엇이 먼저 무너지는지를 본다 — 학습 데이터가 아니라 안전 여유의 측정이다.

섭동 (관절 좌표 mm 단위, 각도 계산 전에 넣는다 — MediaPipe 잡음이 랜드마크에 생기는 것과 같은 자리):
  jitter   프레임마다 독립인 가우시안 관절 지터 σ(mm). 20mm 는 400mm 팔다리에서 각도 3~5° 잡음이다
  spike    한 프레임짜리 관절 튐: 확률 p 로 관절 하나를 150mm 옮긴다 (추정 실패 프레임)
  dropout  검출 끊김: 확률 p_in 으로 시작, 평균 10프레임(0.33s) 지속 → 그 프레임은 사람 없음
  timing   추론 간격 지터: 300~333ms 균일 (실기기 실측 범위). thermal = 433~466ms (발열 감속 ×1.5)
  bias     각도 수준 편향: 굴곡이 깊을수록 얕게 읽음 — v' = v + k·max(0, 120 − v)/60 (v=60° 에서 +k)
사용법: python noise_stress.py ../../data/mm-fit/mm-fit --out ../../data/mm-fit/exp_noise [--jvm]
  --jvm 이면 같은 섭동으로 캡처를 만들어 현재 앱 엔진(JVM 재생기)도 돌린다(느림).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prototype_counter as pc  # noqa: E402
from capture_format import frame_line, write_capture  # noqa: E402
from mmfit_pose3d_captures import landmarks  # noqa: E402

FPS = pc.FPS
USED = [1, 2, 3, 4, 5, 6, 11, 12, 13, 14, 15, 16]
MIN_CYCLE_MS = 0   # --min-cycle 로 바꾼다 (컬 팔별 카운터에만 적용)
SMOOTH = False     # --smooth: 3점 중앙값을 켠다 (잡음 적응 평활 검토용)
CONFIGS = {
    "clean": {},
    "jitter10": {"jitter": 10.0},
    "jitter20": {"jitter": 20.0},
    "jitter40": {"jitter": 40.0},
    "spike3": {"spike": 0.03},
    "spike10": {"spike": 0.10},
    "dropout10": {"dropout": 0.01},          # p_in 0.01/프레임 × 평균 10프레임 ≈ 10% 프레임 결손
    "timing": {"timing": "jitter"},
    "thermal": {"timing": "thermal"},
    "bias20": {"bias": 20.0},
    "phone": {"jitter": 20.0, "spike": 0.03, "dropout": 0.005, "timing": "jitter", "bias": 15.0},
    "phone-hard": {"jitter": 40.0, "spike": 0.10, "dropout": 0.01, "timing": "thermal", "bias": 25.0},
}


def perturb(joints, cfg, rng):
    """joints: (3, N, 17) mm. 반환: (perturbed joints, keep mask(N,))."""
    P = joints.copy()
    n = P.shape[1]
    if cfg.get("jitter"):
        P[:, :, USED] += rng.normal(0.0, cfg["jitter"], size=(3, n, len(USED)))
    if cfg.get("spike"):
        hit = np.nonzero(rng.random(n) < cfg["spike"])[0]
        for i in hit:
            j = USED[rng.integers(len(USED))]
            d = rng.normal(size=3); d = d / np.linalg.norm(d) * 150.0
            P[:, i, j] += d
    keep = np.ones(n, dtype=bool)
    if cfg.get("dropout"):
        i = 0
        while i < n:
            if rng.random() < cfg["dropout"]:
                length = max(1, int(rng.exponential(10.0)))
                keep[i:i + length] = False
                i += length
            i += 1
    return P, keep


def cadence(frames, mode, rng):
    """앱 추론 프레임 선택(프레임 인덱스 리스트 → 부분 리스트)."""
    if mode == "jitter":
        steps = lambda: int(rng.integers(9, 11))      # 300~333ms
    elif mode == "thermal":
        steps = lambda: int(rng.integers(13, 15))     # 433~466ms
    else:
        steps = lambda: 9
    out, i = [], 0
    while i < len(frames):
        out.append(frames[i]); i += steps()
    return out


def biased(F, k):
    if not k:
        return F
    G = dict(F)
    for key in ("knee_L", "knee_R", "elbow_L", "elbow_R"):
        v = F[key]; G[key] = v + k * np.clip(120.0 - v, 0.0, 60.0) / 60.0
    G["knee_mean"] = (G["knee_L"] + G["knee_R"]) / 2
    G["elbow_mean"] = (G["elbow_L"] + G["elbow_R"]) / 2
    return G


def run(root: Path, out: Path, jvm: bool, curl_h: float):
    results = {}
    for name, cfg in CONFIGS.items():
        rng = np.random.default_rng(20260924)
        sets = {k: [] for k in ("squat", "lunge", "curl")}
        lead = {k: [0, 0] for k in sets}
        jvm_sets, jvm_root = [], out / "captures" / name
        for wdir in sorted(p for p in root.iterdir() if p.is_dir()):
            w = wdir.name
            pp, lp = wdir / f"{w}_pose_3d.npy", wdir / f"{w}_labels.csv"
            if not pp.is_file():
                continue
            pose = np.load(pp); frame_no = pose[0, :, 0].astype(int)
            J, keep = perturb(pose[:, :, 1:], cfg, rng)
            F = biased(pc.features(J), cfg.get("bias", 0.0))
            row_of = {f: i for i, f in enumerate(frame_no)}
            labels = pc.load_labels(lp); claimed = set()
            for s, e, _, _ in labels:
                claimed.update(range(s - 15, e + 16))
            for o, (s, e, reps, act) in enumerate(labels):
                kind = pc.ACTS.get(act)
                if kind is None:
                    continue
                fr = [f for f in range(s - 15, e + 16) if f in row_of]
                sel = [f for f in cadence(fr, cfg.get("timing"), rng) if keep[row_of[f]]]
                idx = np.array([row_of[f] for f in sel])
                fires = count(kind, F, idx, curl_h)
                sets[kind].append((reps, len(fires), w))
                lf = [f for f in range(s - 15 - 300, s - 15) if f in row_of and f not in claimed]
                if len(lf) >= 90:
                    lsel = [f for f in cadence(lf, cfg.get("timing"), rng) if keep[row_of[f]]]
                    lead[kind][0] += len(count(kind, F, np.array([row_of[f] for f in lsel]), curl_h)); lead[kind][1] += 1
                if jvm:
                    ex = {"squat": "바벨 스쿼트", "lunge": "스텝 포워드 다이나믹 런지", "curl": "덤벨 컬"}[kind]
                    lines = []
                    for f in cadence(fr, cfg.get("timing"), rng):
                        t = int(round(f * 1000.0 / FPS))
                        lines.append(frame_line(t, landmarks(J[:, row_of[f], :]) if keep[row_of[f]] else None))
                    cap = f"{w}/{w}_set{o:02d}_{act}.cap"
                    write_capture(jvm_root / cap, {"source": f"noise:{name}"}, lines)
                    jvm_sets.append({"workout": w, "ordinal": o, "activity": act, "exercise": ex, "truthReps": reps,
                                     "startMs": int(round(s * 1000.0 / FPS)), "endMs": int(round(e * 1000.0 / FPS)),
                                     "samples": len(lines), "capture": cap})
        row = {}
        for kind, rows in sets.items():
            T = sum(r[0] for r in rows); hit = sum(min(r[0], r[1]) for r in rows)
            row[kind] = dict(recall=hit / T, exact=sum(r[0] == r[1] for r in rows) / len(rows),
                             overSets=sum(r[1] > r[0] for r in rows), zeroSets=sum(r[1] == 0 for r in rows),
                             leadPerWindow=lead[kind][0] / max(1, lead[kind][1]))
        if jvm:
            (jvm_root / "index.json").write_text(json.dumps({"source": f"noise:{name}", "sets": jvm_sets}, ensure_ascii=False), encoding="utf-8")
            subprocess.run([sys.executable, str(Path(__file__).parent / "run_replay.py"), str(jvm_root), "--out", str(out / "jvm" / name)],
                           check=True, capture_output=True)
            summ = json.loads((out / "jvm" / name / "summary.json").read_text(encoding="utf-8"))["configs"]["live"]
            for ex, kind in (("바벨 스쿼트", "squat"), ("스텝 포워드 다이나믹 런지", "lunge"), ("덤벨 컬", "curl")):
                m = summ[ex]; row[kind]["live"] = dict(recall=m["recall"], exact=m["exact"], overSets=m["over"], zeroSets=m["zeroSets"])
        results[name] = row
        print(f"[{name}] " + " | ".join(f"{k}: R {v['recall']:.3f} exact {v['exact']:.2f} over {v['overSets']} zero {v['zeroSets']} lead {v['leadPerWindow']:.2f}"
                                      + (f" ‖ live R {v['live']['recall']:.3f} exact {v['live']['exact']:.2f} over {v['live']['overSets']}" if 'live' in v else "")
                                      for k, v in row.items()), flush=True)
    out.mkdir(parents=True, exist_ok=True)
    (out / "noise_summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


def count(kind, F, idx, curl_h):
    if kind in ("squat", "lunge"):
        return pc.run_counter(kind, F, idx, 9, SMOOTH, False, consist=True)
    t = (idx * 1000.0 / FPS).astype(int)
    cs = {s: pc.Counter(curl_h, min_cycle_ms=MIN_CYCLE_MS, smooth=SMOOTH) for s in "LR"}; fires, amps, last = [], [], {"L": -10**9, "R": -10**9}
    for k in range(len(idx)):
        for s in "LR":
            if cs[s].on_frame(t[k], F[f"elbow_{s}"][idx[k]]):
                o = "R" if s == "L" else "L"
                if t[k] - last[o] > 500:
                    fires.append(t[k]); amps.append(cs[s].amps[-1])
                last[s] = t[k]
    return pc.consistency(fires, amps)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", type=Path); ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--jvm", action="store_true"); ap.add_argument("--curl-h", type=float, default=35.0)
    ap.add_argument("--min-cycle", type=int, default=0); ap.add_argument("--smooth", action="store_true")
    a = ap.parse_args()
    MIN_CYCLE_MS = a.min_cycle; SMOOTH = a.smooth
    run(a.root, a.out, a.jvm, a.curl_h)
