# -*- coding: utf-8 -*-
"""캡처 → 현재 앱 RepCounter 재생 → 세트별 반복 수 채점.

index.json(추출기가 쓴 세트 목록과 정답)을 읽어 매니페스트를 만들고, replay-jvm 의 재생기를 돌린 뒤
세트 단위 오차를 집계한다. 채점 정의는 다른 계열 측정(docs/mmfit-rep-counting.v1.md)과 같게 맞춰
숫자를 나란히 놓을 수 있게 했다:
    MAE  = 세트별 |검출 − 정답| 평균,  OBO = |오차| ≤ 1 인 세트 비율
    count P/R/F1 = Σmin(검출,정답)/Σ검출, Σmin/Σ정답, 조화평균 — 반복 경계 F1 이 아니라 그 상한이다

카운터 구성 (Replay.kt 참고)
    live      앱 세션이 실제로 쓰는 것 — RepCounter(signal, maxGapMs=1500, completeOnReturn=true)
    reversal  RepCounter 기본 구성(반전 확정). 앱 세션은 쓰지 않는다 — 진단용
    live+<피처>  같은 live 구성에 신호만 바꾼 진단. 앱 동작이 아니다

사용법
    python run_replay.py <캡처 폴더(index.json 포함)> --out results/<이름> [--series]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPLAY_BIN = HERE / "replay-jvm" / "build" / "install" / "trex-rep-replay" / "bin" / "trex-rep-replay"

# 진단용 신호 교체 — 앱의 다른 종목이 이미 쓰는 신호만 고른다(새 신호를 만들어 맞추지 않는다).
DIAGNOSTIC_SIGNALS = {
    # 앱의 '크로스 런지'·'바벨 런지' 신호
    "스텝 포워드 다이나믹 런지": [("knee_mean", 35.0), ("knee_minside", 35.0)],
    # 교대 컬에서 한쪽 팔의 굽힘을 그대로 보는 신호(앱의 다른 종목도 minside 계열을 쓴다)
    "덤벨 컬": [("elbow_minside", 35.0)],
}


def build_manifest(index: dict, captures: Path) -> list[tuple]:
    rows = []
    for s in index["sets"]:
        key = s["capture"].replace("/", "__").removesuffix(".cap")
        rows.append((f"{key}|live", s["capture"], s["exercise"], "live", "", ""))
        rows.append((f"{key}|reversal", s["capture"], s["exercise"], "reversal", "", ""))
        for feature, amp in DIAGNOSTIC_SIGNALS.get(s["exercise"], []):
            rows.append((f"{key}|live+{feature}", s["capture"], s["exercise"], "live", feature, str(amp)))
    path = captures / "manifest.tsv"
    path.write_text("".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")
    return rows


def score(sets: list[dict], results: dict[str, dict], config: str) -> dict:
    by_ex: dict[str, list] = defaultdict(list)
    for s in sets:
        key = s["capture"].replace("/", "__").removesuffix(".cap")
        r = results.get(f"{key}|{config}")
        if r is None or "error" in r:
            continue
        by_ex[s["exercise"]].append((s, r))
    out = {}
    for ex, rows in by_ex.items():
        truth = [s["truthReps"] for s, _ in rows]
        det = [r["reps"] for _, r in rows]
        valid = [r["valid"] for _, r in rows]
        err = [d - t for d, t in zip(det, truth)]
        hit = sum(min(d, t) for d, t in zip(det, truth))
        p = hit / sum(det) if sum(det) else float("nan")
        rc = hit / sum(truth) if sum(truth) else float("nan")
        f1 = 2 * p * rc / (p + rc) if p == p and rc == rc and p + rc > 0 else 0.0
        out[ex] = {
            "sets": len(rows), "truth": sum(truth), "detected": sum(det), "valid": sum(valid),
            "MAE": sum(abs(e) for e in err) / len(err), "OBO": sum(abs(e) <= 1 for e in err) / len(err),
            "exact": sum(e == 0 for e in err) / len(err), "under": sum(e < 0 for e in err), "over": sum(e > 0 for e in err),
            "zeroSets": sum(d == 0 for d in det), "precision": p, "recall": rc, "countF1": f1,
            "valueCoverage": sum(r["valueFrames"] for _, r in rows) / max(1, sum(r["frames"] for _, r in rows)),
            "feature": rows[0][1]["feature"], "minAmp": rows[0][1]["minAmp"],
            "perSet": [{"set": s["capture"], "truth": s["truthReps"], "detected": r["reps"], "valid": r["valid"],
                        "samples": r["frames"], "valueFrames": r["valueFrames"]} for s, r in rows],
        }
    return out


BOUNDARY_TOLERANCES_MS = (0, 500, 1000)


def boundary(sets: list[dict], results: dict[str, dict], config: str) -> dict:
    """반복 경계 정답(REHAB24-6)이 있을 때만. 카운터가 반복을 발표한 순간이 정답 반복 구간 안에 들어오면 일치.

    발표 순간 = onFrame 이 true 를 돌려준 프레임(앱이 숫자를 올리는 순간). 복귀형은 준비 위치로 돌아온 뒤
    두 샘플을 더 보고 확정하므로 정답 구간의 끝보다 조금 늦을 수 있다 — 그래서 허용 오차별로 따로 적는다.
    일치는 시간 순서대로 1:1 (발표 하나가 정답 둘을 먹지 않는다).
    """
    groups: dict[tuple, list] = defaultdict(list)
    for s in sets:
        if "reps" not in s:
            continue
        key = s["capture"].replace("/", "__").removesuffix(".cap")
        r = results.get(f"{key}|{config}")
        if r is None or "error" in r:
            continue
        groups[(s["exercise"], s.get("camera", ""))].append((s, r))
    out = {}
    for (ex, cam), rows in groups.items():
        entry = {"recordings": len(rows)}
        for tol in BOUNDARY_TOLERANCES_MS:
            tp = fp = fn = 0
            for s, r in rows:
                truth = [(a - tol, b + tol) for a, b, _ in s["reps"]]
                used = [False] * len(truth)
                for t in sorted(r["repTimesMs"]):
                    match = next((i for i, (a, b) in enumerate(truth) if not used[i] and a <= t <= b), None)
                    if match is None:
                        fp += 1
                    else:
                        used[match] = True
                        tp += 1
                fn += used.count(False)
            p = tp / (tp + fp) if tp + fp else float("nan")
            rc = tp / (tp + fn) if tp + fn else float("nan")
            entry[f"tol{tol}"] = {"tp": tp, "fp": fp, "fn": fn, "precision": p, "recall": rc,
                                  "f1": 2 * p * rc / (p + rc) if p == p and rc == rc and p + rc > 0 else 0.0}
        out[f"{ex}|cam{cam}"] = entry
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("captures", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--series", action="store_true", help="프레임별 신호값을 <out>/series 에 남긴다")
    args = parser.parse_args()

    index = json.loads((args.captures / "index.json").read_text(encoding="utf-8"))
    rows = build_manifest(index, args.captures)
    args.out.mkdir(parents=True, exist_ok=True)
    results_path = args.out / "replay.jsonl"
    cmd = [str(REPLAY_BIN), str(args.captures / "manifest.tsv"), str(results_path)]
    if args.series:
        cmd.append(str(args.out / "series"))
    subprocess.run(cmd, check=True)
    results = {}
    for line in results_path.read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        results[r["id"]] = r

    configs = sorted({row[0].split("|", 1)[1] for row in rows})
    summary = {"source": index.get("source"), "configs": {c: score(index["sets"], results, c) for c in configs}}
    if any("reps" in s for s in index["sets"]):
        summary["boundary"] = {c: boundary(index["sets"], results, c) for c in configs}
    (args.out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"source: {summary['source']}")
    print("| 구성 | 종목 | 신호 | 세트 | 정답 | 검출 | ROM 유효 | MAE | OBO | 정확 | 0회 세트 | P | R | count F1 |")
    print("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for c in configs:
        for ex, m in sorted(summary["configs"][c].items()):
            print(f"| {c} | {ex} | `{m['feature']}` {m['minAmp']:g} | {m['sets']} | {m['truth']} | {m['detected']} | {m['valid']} | "
                  f"{m['MAE']:.2f} | {m['OBO']:.2f} | {m['exact']:.2f} | {m['zeroSets']} | {m['precision']:.3f} | {m['recall']:.3f} | {m['countF1']:.3f} |")
    if "boundary" in summary:
        print()
        print("| 구성 | 종목·카메라 | 녹화 | " + " | ".join(f"경계 F1 ±{t}ms (P/R)" for t in BOUNDARY_TOLERANCES_MS) + " |")
        print("|---|---|---:|" + "---|" * len(BOUNDARY_TOLERANCES_MS))
        for c in configs:
            for group, e in sorted(summary["boundary"][c].items()):
                cells = [f"{e[f'tol{t}']['f1']:.3f} ({e[f'tol{t}']['precision']:.2f}/{e[f'tol{t}']['recall']:.2f})"
                         for t in BOUNDARY_TOLERANCES_MS]
                print(f"| {c} | {group} | {e['recordings']} | " + " | ".join(cells) + " |")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
