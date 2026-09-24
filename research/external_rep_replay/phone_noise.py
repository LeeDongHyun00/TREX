# -*- coding: utf-8 -*-
"""카운터 입력의 잡음 통계 — 기준 포즈(3D 정답) 없이 캡처·세트 로그만으로 잰다 (설계 §4.7·§9·§15 #12).

입력 (섞어도 된다)
    캡처 폴더(index.json): 랜드마크 캡처 *.cap(extract_mediapipe.py·mmfit_pose3d_captures.py·합성) 또는 피처 캡처 *.fcap(setlog_captures.py)
    세트 로그: sets-*.jsonl 파일이나 그 폴더 — infer_ms 와 실제 프레임 시각이 있어 휴대폰 조건을 가장 직접 본다
무엇을 재나 (세트별 → 종목별)
    추론 간격      연속 기록 프레임의 시각 차 분포(중앙·p10·p90·p99), 400 ms 초과 비율(발열 감속 433~466 ms 의 대리 지표),
                   1.5 s 초과 틈(카운터 사이클 리셋). 세트 로그는 **검출 안 된 프레임을 적지 않으므로** 끊김이 시각 틈으로 나타난다 —
                   그래서 "빠진 프레임 추정" = Σ max(0, round(dt / 세트 중앙 간격) − 1) 을 따로 적는다
    추론 지연      세트 로그 infer_ms (중앙·p90) — 발열의 더 직접적인 대리 지표. 세트 순서(시각)에 따른 추이
    열 상태        spec §58 이후 세트 로그의 thermal{start, changes} — 프레임마다 상태를 붙여 MODERATE(2, 간격 ×1.5) 이상에서의
                   간격을 따로 적는다(InferencePolicy.thermalMultiplier). 필드가 없는 로그는 대리 지표만
    휴식 σ         신호가 휴식 쪽(세트 p95 에서 0.3×게이트 안)에 있는 연속 샘플 쌍의 차 Δ 로 σ = std(Δ)/√2 와 MAD 판(튐에 강함).
                   3D 기준이 없어 실제 느린 움직임이 섞일 수 있다 — 상한으로 읽는다(짝 비교는 mmfit_pair_compare.py)
    한 샘플 튐     |v_i − (v_{i−1}+v_{i+1})/2| > T, 두 이웃보다 같은 쪽으로 튀고 두 이웃끼리는 T/2 안(이웃이 서로 동의)
                   T = 0.3·0.6·1.0 × 게이트(각도 35° 면 10.5·21·35°)
    끊김           사람 없음(랜드마크 poses=0) · 피처 없음(빈 사전) · 신호 없음 비율과 빈 구간 길이
신호: 세트 로그는 reps.signal, 캡처는 index 의 loggedSignal 이나 종목 대표 신호(스쿼트·런지 knee_mean, 컬 elbow_mean), --signal 로 덮는다.
학습 데이터가 아니다 — 안전 여유와 필터 문턱(§15 #12)을 정할 때 볼 실측 분포다.

사용법
    python phone_noise.py <캡처 폴더|sets-*.jsonl|로그 폴더 ...> --out <결과 폴더> [--signal elbow_mean]
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from setlog_captures import iter_logs, log_files, read_feature_capture

PRIMARY = {"바벨 스쿼트": "knee_mean", "스텝 포워드 다이나믹 런지": "knee_mean", "덤벨 컬": "elbow_mean"}
THERMAL_MS = 400          # 앱 300 ms 간격이 발열 배수로 늘면 433~466 ms(설계 §4.6·§9)
MAX_GAP_MS = 1500
SPIKE_FRACTIONS = (0.3, 0.6, 1.0)
REST_FRACTION = 0.3       # 휴식 대역 = 세트 p95 에서 0.3 × 게이트
PAIR_GAP_MS = 400


def gate_of(signal: str) -> float:
    """신호의 최소 스윙(RepSignals 의 모집단 게이트). 표에 없는 신호는 이름으로 짐작한다(각도형 35°, 거리형 0.25)."""
    known = {"knee_out_mean": 0.10, "wrist_shoulder_d": 0.30, "head_ground": 0.15, "hip_ang": 25.0, "hip_dev_ankle": 0.10,
             "hand_shoulder_off": 0.20, "knee_gap2d": 0.25, "palm_h_sh": 0.25, "palm_fwd_knee": 0.25, "hip_below_knee": 0.10,
             "knee_elbow_dist": 0.25}
    if signal in known:
        return known[signal]
    if signal.startswith(("knee_", "elbow_", "hip_", "shoulder_", "forearm_", "trunk_", "ankle_", "wrist_")) or "ang" in signal:
        return 35.0
    return 0.25


# ---------------------------------------------------------------- 입력 → 세트 시계열

def series_from_setlog(log: dict, signal_override: str | None) -> dict:
    frames = log.get("frames") or []
    reps = log.get("reps") if isinstance(log.get("reps"), dict) else {}
    ex = log.get("exercise") or ""
    signal = signal_override or reps.get("signal") or PRIMARY.get(ex)
    t = np.array([int(f.get("t_ms", 0)) for f in frames], dtype=np.int64)
    feats = [f.get("features") or {} for f in frames]
    v = np.array([float(fe[signal]) if signal and fe.get(signal) is not None else np.nan for fe in feats])
    infer = np.array([float(f["infer_ms"]) if f.get("infer_ms") is not None else np.nan for f in frames])
    thermal = np.full(len(t), -1)
    th = log.get("thermal") if isinstance(log.get("thermal"), dict) else None
    if th is not None and th.get("start") is not None:
        thermal[:] = int(th["start"])
        for c in sorted(th.get("changes") or [], key=lambda c: c.get("t_ms", 0)):
            thermal[t >= int(c.get("t_ms", 0))] = int(c.get("status", 0))
    return {"id": str(log.get("set_id")), "exercise": ex, "signal": signal, "t": t, "v": v, "infer": infer, "thermal": thermal,
            "status": np.array([0 if fe else 2 for fe in feats]), "order": str(log.get("created_at") or ""), "kind": "setlog",
            "logsUnDetected": False}


def series_from_capture(path: Path, entry: dict, signal_override: str | None) -> dict:
    ex = entry.get("exercise") or ""
    signal = signal_override or entry.get("loggedSignal") or PRIMARY.get(ex)
    if path.suffix == ".fcap":
        _, frames = read_feature_capture(path)
        t = np.array([f[0] for f in frames], dtype=np.int64)
        v = np.array([f[1].get(signal, np.nan) for f in frames], dtype=float)
        status = np.array([0 if f[1] else 2 for f in frames])
        return {"id": entry.get("capture"), "exercise": ex, "signal": signal, "t": t, "v": v, "infer": np.full(len(t), np.nan),
                "status": status, "order": str(entry.get("createdAt") or entry.get("capture")), "kind": "fcap", "logsUnDetected": False}
    from mmfit_pair_compare import app_features, read_landmark_capture  # noqa: PLC0415 — 랜드마크 캡처일 때만(numpy 각도)
    cap = read_landmark_capture(path)
    status, feat = app_features(cap)
    v = feat.get(signal, np.full(len(cap["t"]), np.nan)) if signal else np.full(len(cap["t"]), np.nan)
    return {"id": entry.get("capture"), "exercise": ex, "signal": signal, "t": cap["t"], "v": np.asarray(v, dtype=float),
            "infer": np.full(len(cap["t"]), np.nan), "status": status, "order": str(entry.get("capture")), "kind": "cap",
            "logsUnDetected": True}


def load_inputs(paths: list[Path], signal_override: str | None) -> list[dict]:
    out = []
    for p in paths:
        if p.is_dir() and (p / "index.json").is_file():
            index = json.loads((p / "index.json").read_text(encoding="utf-8"))
            for e in index.get("sets", []):
                cp = p / e["capture"]
                if cp.is_file():
                    out.append(series_from_capture(cp, e, signal_override))
        elif log_files([p]):
            for _, _, log in iter_logs([p]):
                out.append(series_from_setlog(log, signal_override))
    return out


# ---------------------------------------------------------------- 세트 통계

def set_stats(s: dict) -> dict:
    t, v, st = s["t"], s["v"], s["status"]
    n = len(t)
    dt = np.diff(t) if n > 1 else np.zeros(0)
    med = float(np.median(dt)) if len(dt) else float("nan")
    missing_est = int(sum(max(0, round(d / med) - 1) for d in dt)) if len(dt) and med > 0 else 0
    gate = gate_of(s["signal"]) if s["signal"] else float("nan")
    out = {"id": s["id"], "exercise": s["exercise"], "signal": s["signal"], "order": s["order"], "kind": s["kind"], "frames": int(n),
           "durationS": float((t[-1] - t[0]) / 1000.0) if n > 1 else 0.0,
           "dtMedian": med, "dtP90": float(np.percentile(dt, 90)) if len(dt) else float("nan"),
           "over400": float((dt > THERMAL_MS).mean()) if len(dt) else float("nan"), "gaps1500": int((dt > MAX_GAP_MS).sum()),
           "missingFramesEst": missing_est,
           "inferMedian": float(np.nanmedian(s["infer"])) if np.isfinite(s["infer"]).any() else None,
           "noPose": int((st == 1).sum()), "noFeatures": int((st == 2).sum()),
           "noSignal": int(((st == 0) & ~np.isfinite(v)).sum()), "dt": dt,
           "thermalStart": int(s["thermal"][0]) if n and s.get("thermal") is not None and s["thermal"][0] >= 0 else None,
           "thermalMax": int(s["thermal"].max()) if n and s.get("thermal") is not None and s["thermal"].max() >= 0 else None,
           # 간격 dt[i] 는 프레임 i+1 이 추론될 때의 열 상태로 본다
           "dtThermal": s["thermal"][1:] if s.get("thermal") is not None and n > 1 else np.zeros(0, dtype=int)}
    if not s["signal"] or not np.isfinite(v).any():
        out.update({"restDiffs": np.zeros(0), "triplets": 0, "spikes": {f: 0 for f in SPIKE_FRACTIONS}})
        return out
    ref = np.nanpercentile(v, 95)
    band = REST_FRACTION * gate
    diffs = []
    for i in range(1, n):
        if dt[i - 1] > PAIR_GAP_MS or not (np.isfinite(v[i]) and np.isfinite(v[i - 1])):
            continue
        if abs(v[i] - ref) < band and abs(v[i - 1] - ref) < band:
            diffs.append(v[i] - v[i - 1])
    spikes = {f: 0 for f in SPIKE_FRACTIONS}
    triplets = 0
    for i in range(1, n - 1):
        if dt[i - 1] > PAIR_GAP_MS or dt[i] > PAIR_GAP_MS or not np.all(np.isfinite(v[i - 1:i + 2])):
            continue
        triplets += 1
        a, b, c = v[i - 1], v[i], v[i + 1]
        dev = b - (a + c) / 2
        peak = (b - a) * (b - c) > 0
        for f in SPIKE_FRACTIONS:
            T = f * gate
            if peak and abs(dev) > T and abs(c - a) < T / 2:
                spikes[f] += 1
    out.update({"restDiffs": np.array(diffs), "triplets": triplets, "spikes": spikes, "gate": gate})
    return out


def _thermal(ss: list[dict]) -> dict:
    info = [s for s in ss if s["thermalMax"] is not None]
    if not info:
        return {"setsWithInfo": 0}
    dt = np.concatenate([s["dt"] for s in info])
    st = np.concatenate([s["dtThermal"] for s in info])
    hot = dt[st >= 2]
    cool = dt[(st >= 0) & (st < 2)]
    return {"setsWithInfo": len(info), "setsModeratePlus": sum(s["thermalMax"] >= 2 for s in info),
            "maxStatus": max(s["thermalMax"] for s in info),
            "dtMedianBelowModerate": float(np.median(cool)) if len(cool) else None,
            "dtMedianModeratePlus": float(np.median(hot)) if len(hot) else None, "intervalsModeratePlus": int(len(hot))}


def _sig(d: np.ndarray) -> dict | None:
    if len(d) < 5:
        return None
    mad = float(np.median(np.abs(d - np.median(d)))) * 1.4826
    return {"sigma": float(d.std() / math.sqrt(2)), "robustSigma": mad / math.sqrt(2), "n": int(len(d))}


def summarize(sets: list[dict]) -> dict:
    by = defaultdict(list)
    for s in sets:
        by[(s["exercise"], s["signal"])].append(s)
    out = {}
    for (ex, sig), ss in sorted(by.items(), key=lambda kv: (kv[0][0], str(kv[0][1]))):
        dt = np.concatenate([s["dt"] for s in ss]) if ss else np.zeros(0)
        frames = sum(s["frames"] for s in ss)
        trip = sum(s["triplets"] for s in ss)
        rest = np.concatenate([s["restDiffs"] for s in ss]) if ss else np.zeros(0)
        ordered = sorted(ss, key=lambda s: s["order"])
        third = max(1, len(ordered) // 3)
        infer = [s["inferMedian"] for s in ordered if s["inferMedian"] is not None]
        e = {"exercise": ex, "signal": sig, "gate": gate_of(sig) if sig else None, "sets": len(ss), "frames": frames,
             "interval": {"median": float(np.median(dt)) if len(dt) else None, "p10": float(np.percentile(dt, 10)) if len(dt) else None,
                          "p90": float(np.percentile(dt, 90)) if len(dt) else None, "p99": float(np.percentile(dt, 99)) if len(dt) else None,
                          "over400": float((dt > THERMAL_MS).mean()) if len(dt) else None,
                          "gaps1500": int((dt > MAX_GAP_MS).sum()), "missingFramesEst": sum(s["missingFramesEst"] for s in ss)},
             "thermalTrend": {"dtMedianFirstThird": float(np.median([s["dtMedian"] for s in ordered[:third]])),
                              "dtMedianLastThird": float(np.median([s["dtMedian"] for s in ordered[-third:]])),
                              "inferMedianFirstThird": float(np.median(infer[:third])) if infer else None,
                              "inferMedianLastThird": float(np.median(infer[-third:])) if infer else None},
             "thermal": _thermal(ss),
             "restSigma": _sig(rest),
             "spikes": {f"x{f:g}": {"count": sum(s["spikes"][f] for s in ss), "rate": sum(s["spikes"][f] for s in ss) / max(1, trip)}
                        for f in SPIKE_FRACTIONS},
             "triplets": trip,
             "dropout": {"noPose": sum(s["noPose"] for s in ss) / max(1, frames), "noFeatures": sum(s["noFeatures"] for s in ss) / max(1, frames),
                         "noSignal": sum(s["noSignal"] for s in ss) / max(1, frames)}}
        out[f"{ex}|{sig}"] = e
    return out


def _f(v, fmt=".2f"):
    return "—" if v is None or (isinstance(v, float) and v != v) else format(v, fmt)


def markdown(summary: dict, sources: list[str]) -> str:
    L = ["# 카운터 입력 잡음 — " + ", ".join(sources), "",
         "| 종목 | 신호 (게이트) | 세트 | 프레임 | 간격 중앙 / p10 / p90 / p99 ms | >400 ms | >1.5 s 틈 | 빠진 프레임 추정 | 추론 ms 처음⅓→끝⅓ | 휴식 σ std / MAD (n) | 튐 0.3·0.6·1.0×게이트 (비율) | 사람 없음 / 피처 없음 / 신호 없음 |",
         "|---|---|---:|---:|---|---:|---:|---:|---|---|---|---|"]
    for e in summary.values():
        iv, tr, rs = e["interval"], e["thermalTrend"], e["restSigma"] or {}
        sp = " / ".join(f"{e['spikes'][f'x{f:g}']['rate']:.4f}" for f in SPIKE_FRACTIONS)
        d = e["dropout"]
        L.append(f"| {e['exercise']} | `{e['signal']}` ({_f(e['gate'], 'g')}) | {e['sets']} | {e['frames']} | "
                 f"{_f(iv['median'], '.0f')} / {_f(iv['p10'], '.0f')} / {_f(iv['p90'], '.0f')} / {_f(iv['p99'], '.0f')} | "
                 f"{_f(iv['over400'], '.3f')} | {iv['gaps1500']} | {iv['missingFramesEst']} | "
                 f"{_f(tr['inferMedianFirstThird'], '.0f')} → {_f(tr['inferMedianLastThird'], '.0f')} | "
                 f"{_f(rs.get('sigma'))} / {_f(rs.get('robustSigma'))} ({rs.get('n', 0)}) | {sp} | "
                 f"{d['noPose']:.3f} / {d['noFeatures']:.3f} / {d['noSignal']:.3f} |")
    th_lines = [f"| {e['exercise']} | {e['thermal']['setsWithInfo']} | {e['thermal'].get('setsModeratePlus', '—')} | "
                f"{e['thermal'].get('maxStatus', '—')} | {_f(e['thermal'].get('dtMedianBelowModerate'), '.0f')} | "
                f"{_f(e['thermal'].get('dtMedianModeratePlus'), '.0f')} ({e['thermal'].get('intervalsModeratePlus', 0)}) |"
                for e in summary.values() if e["thermal"].get("setsWithInfo")]
    if th_lines:
        L += ["", "열 상태 (세트 로그 thermal, spec §58)", "",
              "| 종목 | 열 정보 있는 세트 | MODERATE 이상 도달 세트 | 최고 상태 | 간격 중앙 ms (< MODERATE) | 간격 중앙 ms (≥ MODERATE, 간격 수) |",
              "|---|---:|---:|---:|---:|---|"] + th_lines
    L += ["", "휴식 σ 는 3D 기준 없이 잰 상한이다(느린 실제 움직임이 섞일 수 있다). 세트 로그는 검출 안 된 프레임을 적지 않아 끊김이 '틈' 과 '빠진 프레임 추정' 으로 나타난다."]
    return "\n".join(L) + "\n"


def run(inputs: list[Path], out: Path, signal: str | None = None) -> str | None:
    """입력 → <out>/noise_summary.json·.md. 세트가 없으면 None (gate_a.py 도 이 함수를 부른다)."""
    series = load_inputs(inputs, signal)
    if not series:
        return None
    sets = [set_stats(s) for s in series]
    summary = summarize(sets)
    out.mkdir(parents=True, exist_ok=True)
    per_set = [{k: v for k, v in s.items() if k not in ("dt", "restDiffs", "dtThermal")} for s in sets]
    (out / "noise_summary.json").write_text(json.dumps({"inputs": [str(p) for p in inputs], "byExerciseSignal": summary,
                                                        "perSet": per_set}, ensure_ascii=False, indent=1, default=float), encoding="utf-8")
    md = markdown(summary, [p.name for p in inputs])
    (out / "noise_summary.md").write_text(md, encoding="utf-8")
    return md


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--signal", default=None, help="모든 세트에 이 신호를 쓴다")
    args = ap.parse_args()
    md = run(args.inputs, args.out, args.signal)
    if md is None:
        print("입력에서 세트를 찾지 못했다", file=sys.stderr)
        return 1
    print(md)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
