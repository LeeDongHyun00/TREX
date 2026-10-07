# -*- coding: utf-8 -*-
"""FMS ASLR 캡처(fms_captures.py) → 현재 앱 '라잉 레그 레이즈' 바닥 경로 재생 → 표.

묻는 것 (results/legraise_datasets_survey.md §0)
    1. 누운 몸을 MediaPipe 가 얼마나 잡는가 — 판정 프레임 중 사람 검출 · 바닥 코어(어깨·골반) · 카운터 신호(hip_ang) 가 있는 비율
    2. hip_ang 스윙이 문턱 25° 를 넘는가 — 양측 중점(앱 신호) · 드는 다리 쪽(hip_ang_L/R) 각각, 에피소드 안 극값 차
    3. 카운트 — 에피소드(정답 1) · 세 에피소드를 이은 열(정답 3). 준비 자세 유지 중 헛카운트 = 에피소드에서 2 이상
    4. 전문가 점수(세 명의 중앙값, 0~3)별 스윙 — FMS ASLR 점수는 사실상 들어 올린 높이 등급

카운터 구성: 앱 세션 그대로(RepCounter.forSession(floor = true) — 신호 hip_ang, 최소 스윙 25°, ROM 없음).
진단 구성(앱 동작 아님): 같은 복귀형 카운터에 신호만 드는 다리 쪽 hip_ang_L/R 로 바꾼 것(live+hip_ang_<쪽>, 25°).

사용법
    python score_fms_aslr.py <캡처 폴더(index.json)> --out <결과 폴더>
"""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

from capture_format import read_capture
from run_replay import require_fresh_replay

EXERCISE = "라잉 레그 레이즈"
MIN_SWING = 25.0     # RepSignals 등록부 hip_ang minAmp


def run_replay(bin_path: Path, root: Path, index: dict, work: Path) -> dict[str, dict]:
    rows = []
    for kind in ("sets", "episodes"):
        for c in index[kind]:
            cid = c["capture"].removesuffix(".cap")
            rows.append(f"{cid}|live\t{c['capture']}\t{EXERCISE}\tlive\t\t\t1")
            rows.append(f"{cid}|side\t{c['capture']}\t{EXERCISE}\tlive\thip_ang_{c['movingSide']}\t{MIN_SWING}\t1")
    manifest = root / "fms_manifest.tsv"
    manifest.write_text("\n".join(rows) + "\n", encoding="utf-8")
    out = work / "replay.jsonl"
    subprocess.run([str(bin_path), str(manifest), str(out)], check=True)
    return {r["id"]: r for r in map(json.loads, out.read_text(encoding="utf-8").splitlines())}


def dump(bin_path: Path, cap: Path, out: Path) -> list[dict]:
    if not out.is_file() or out.stat().st_mtime < cap.stat().st_mtime:
        subprocess.run([str(bin_path), "--dump-features", str(cap), str(out)], check=True, capture_output=True)
    return [json.loads(x) for x in out.read_text(encoding="utf-8").splitlines()]


def lying_times(cap: Path) -> set[int]:
    """검출 프레임 중 어깨 중점–발목 중점이 화면에서 가로인(|dx| > |dy|, 픽셀) 시각 — 누운 몸을 잡았다는 근사.
    후면 카메라는 배경에 서 있는 사람이 있어 한 명 검출(num_poses=1)이 그쪽을 잡을 수 있고, 끝-방향(sideLow)은 몸이 세로로 보인다."""
    meta, frames = read_capture(cap)
    w, h = float(meta.get("imageWidth", 1)), float(meta.get("imageHeight", 1))
    out = set()
    for f in frames:
        lm = f["landmarks"]
        if f["poses"] < 1 or len(lm) < 33:
            continue
        sx, sy = (lm[11][0] + lm[12][0]) / 2 * w, (lm[11][1] + lm[12][1]) / 2 * h
        ax, ay = (lm[27][0] + lm[28][0]) / 2 * w, (lm[27][1] + lm[28][1]) / 2 * h
        if abs(sx - ax) > abs(sy - ay):
            out.add(f["t"])
    return out


CONFIRM_SPILL_MS = 1500   # 한 회의 복귀 확정은 다음 에피소드 첫 프레임들(준비 자세)에서 난다 — 그 회의 몫으로 센다


def attribute(rep_times: list[int], episodes: list[list[int]]) -> list[int]:
    """이은 열의 카운트 시각을 에피소드에 나눈다: 에피소드 k 의 창 = [k 시작, k+1 시작 + 1.5 s). 마지막은 열 끝까지.
    카운터는 복귀 뒤 준비 자세 표본 2개(≥ 150 ms, ReturnRepTracker)와 평활 지연이 지나야 확정하므로, k 의 회는 k+1 의 첫 프레임에서 확정된다."""
    counts = [0] * len(episodes)
    for t in rep_times:
        for i, (_, t0, t1) in enumerate(episodes):
            hi = episodes[i + 1][1] + CONFIRM_SPILL_MS if i + 1 < len(episodes) else t1 + 1
            if t0 <= t < hi:
                counts[i] += 1
                break
    return counts


def med3(v: list[float]) -> list[float]:
    return [v[i] if i == 0 or i == len(v) - 1 else sorted(v[i - 1:i + 2])[1] for i in range(len(v))]


def swing(frames: list[dict], key: str) -> float | None:
    vals = [f["features"][key] for f in frames if f["features"].get(key) is not None]
    if len(vals) < 3:
        return None
    s = med3(vals)
    return max(s) - min(s)


def pct(a: int, b: int) -> str:
    return f"{100 * a / b:.0f}%" if b else "—"


def q(vals: list[float]) -> str:
    if not vals:
        return "—"
    v = sorted(vals)
    k = lambda p: v[min(len(v) - 1, int(round(p * (len(v) - 1))))]
    return f"{k(0.1):.0f} / {statistics.median(v):.0f} / {k(0.9):.0f}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("root", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    bin_path = require_fresh_replay()
    index = json.loads((args.root / "index.json").read_text(encoding="utf-8"))
    args.out.mkdir(parents=True, exist_ok=True)
    work = args.root / "score_work"   # 재생 결과·프레임 덤프는 캡처 옆(data/, git 제외)에 둔다
    work.mkdir(exist_ok=True)
    results = run_replay(bin_path, args.root, index, work)

    # 에피소드별 프레임 피처 — 세트 캡처(이어 붙인 열) 하나를 덤프해 에피소드 시각으로 자른다
    per_ep = []
    for s in index["sets"]:
        frames = dump(bin_path, args.root / s["capture"], work / (s["capture"].replace("/", "__") + ".jsonl"))
        lying = lying_times(args.root / s["capture"])
        caps = {e["episode"]: e for e in index["episodes"] if e["subject"] == s["subject"] and e["movement"] == s["movement"]}
        for ep, t0, t1 in s["episodes"]:
            fs = [f for f in frames if t0 <= f["t"] <= t1]
            e = caps[ep]
            cid = e["capture"].removesuffix(".cap")
            live, side = results[f"{cid}|live"], results[f"{cid}|side"]
            sc = e.get("scores") or []
            per_ep.append({
                "subject": s["subject"], "movement": s["movement"], "side": s["movingSide"], "episode": ep,
                "durationS": round((t1 - t0) / 1000, 1), "judged": len(fs),
                "detected": sum(f["detected"] for f in fs),
                "lying": sum(f["t"] in lying for f in fs),
                "core": sum(bool(f["features"]) for f in fs),
                "hipMid": sum(f["features"].get("hip_ang") is not None for f in fs),
                "hipSide": sum(f["features"].get(f"hip_ang_{s['movingSide']}") is not None for f in fs),
                "swingMid": swing(fs, "hip_ang"), "swingSide": swing(fs, f"hip_ang_{s['movingSide']}"),
                "repsLive": live.get("reps"), "retractedLive": len(json.loads(json.dumps(live.get("retracted", [])))),
                "repsSide": side.get("reps"),
                "score": statistics.median(sc) if sc else None,
            })
    sets = []
    for s in index["sets"]:
        cid = s["capture"].removesuffix(".cap")
        row = {"capture": cid, "truth": s["truthReps"], "live": results[f"{cid}|live"].get("reps"),
               "side": results[f"{cid}|side"].get("reps"), "retracted": results[f"{cid}|live"].get("retracted", [])}
        for k in ("live", "side"):
            row[f"{k}ByEpisode"] = attribute(results[f"{cid}|{k}"].get("repTimesMs", []), s["episodes"])
        sets.append(row)
    (args.out / "episodes.json").write_text(json.dumps(per_ep, ensure_ascii=False, indent=1), encoding="utf-8")
    (args.out / "sets.json").write_text(json.dumps(sets, ensure_ascii=False, indent=1), encoding="utf-8")

    n = len(per_ep)
    J = sum(e["judged"] for e in per_ep)
    lines = [f"# FMS ASLR — 라잉 레그 레이즈 바닥 경로 재생 ({index['camera']})", "",
             f"{index['source']}. 모델 SHA `{index['modelSha256'][:8]}…`. 피험자 {len({e['subject'] for e in per_ep})}명, "
             f"에피소드 {n}개(정답 1회씩), 이어 붙인 열 {len(sets)}개(정답 3회씩).", "",
             "## 1. 검출 (판정 프레임 기준)", "",
             "| | 프레임 | 비율 |", "|---|---:|---:|",
             f"| 판정 프레임 | {J} | |",
             f"| 사람 검출 | {sum(e['detected'] for e in per_ep)} | {pct(sum(e['detected'] for e in per_ep), J)} |",
             f"| 그중 몸이 화면에 가로(어깨–발목 \\|dx\\| > \\|dy\\|) | {sum(e['lying'] for e in per_ep)} | {pct(sum(e['lying'] for e in per_ep), J)} |",
             f"| 바닥 코어(어깨·골반 가시성 ≥ 0.2) | {sum(e['core'] for e in per_ep)} | {pct(sum(e['core'] for e in per_ep), J)} |",
             f"| 카운터 신호 hip_ang(양측 중점, 무릎 가시성 ≥ 0.35) | {sum(e['hipMid'] for e in per_ep)} | {pct(sum(e['hipMid'] for e in per_ep), J)} |",
             f"| 드는 다리 hip_ang_L/R(그 쪽 관절 ≥ 0.5) | {sum(e['hipSide'] for e in per_ep)} | {pct(sum(e['hipSide'] for e in per_ep), J)} |",
             "", "에피소드 단위: 신호 프레임 비율이 50% 미만인 에피소드 " +
             f"{sum(1 for e in per_ep if e['judged'] and e['hipMid'] / e['judged'] < 0.5)}/{n} (중점), "
             f"{sum(1 for e in per_ep if e['judged'] and e['hipSide'] / e['judged'] < 0.5)}/{n} (드는 쪽).", "",
             "## 2. 스윙 (중앙값-3 평활 후 에피소드 안 최대−최소, °)", "",
             "| 신호 | 측정 에피소드 | p10 / 중앙 / p90 | 25° 이상 |", "|---|---:|---|---:|"]
    for key, label in (("swingMid", "hip_ang 양측 중점 (앱)"), ("swingSide", "hip_ang 드는 쪽 (진단)")):
        v = [e[key] for e in per_ep if e[key] is not None]
        lines.append(f"| {label} | {len(v)}/{n} | {q(v)} | {pct(sum(x >= MIN_SWING for x in v), len(v))} |")
    lines += ["", "## 3. 카운트", "",
              "| 구성 | 단위 | 정답 | 정확 | 0회 | 2회 이상(헛카운트) |", "|---|---|---:|---:|---:|---:|"]
    for key, label in (("repsLive", "앱(hip_ang 중점)"), ("repsSide", "진단(드는 쪽)")):
        v = [e[key] for e in per_ep]
        lines.append(f"| {label} | 에피소드 {n} | 1 | {sum(x == 1 for x in v)} | {sum(x == 0 for x in v)} | {sum((x or 0) >= 2 for x in v)} |")
    for key, label in (("live", "앱(hip_ang 중점)"), ("side", "진단(드는 쪽)")):
        v = [s[key] for s in sets]
        dist = defaultdict(int)
        for x in v:
            dist[x] += 1
        lines.append(f"| {label} | 열 {len(sets)} | 3 | {sum(x == 3 for x in v)} | {sum(x == 0 for x in v)} | 분포 "
                     + ", ".join(f"{k}회 {dist[k]}" for k in sorted(dist)) + " |")
    lines += ["", f"앱 구성에서 거둔 첫 회(8 s 안에 둘째 회 없음): 에피소드 {sum(e['retractedLive'] for e in per_ep)}건, "
              f"열 {sum(len(s['retracted']) for s in sets)}건.", "",
              "**에피소드 단독 카운트는 앱 동작의 측정이 아니다.** 카운터는 복귀 뒤 준비 자세 표본 2개(≥ 150 ms)와 3점 평활 지연이 지나야 1회를 확정하는데,"
              " FMS 에피소드는 다리를 내린 직후 녹화가 끝난다. 그래서 아래 표는 이은 열에서 회마다 다음 에피소드 첫 1.5 s 까지를 그 회의 확정 창으로 본다 —"
              " e1·e2 는 뒤에 준비 자세가 이어지는 회(실사용과 같은 조건), e3 은 녹화가 끊긴 회다.", "",
              "| 구성 | e1·e2 (뒤에 준비 자세) | 1회 | 0회 | 2회 이상 | e3 (녹화 끊김) 1회 |", "|---|---:|---:|---:|---:|---:|"]
    for key, label in (("live", "앱(hip_ang 중점)"), ("side", "진단(드는 쪽)")):
        head = [c for s in sets for c in s[f"{key}ByEpisode"][:-1]]
        tail = [s[f"{key}ByEpisode"][-1] for s in sets]
        lines.append(f"| {label} | {len(head)} | {sum(c == 1 for c in head)} ({pct(sum(c == 1 for c in head), len(head))}) | "
                     f"{sum(c == 0 for c in head)} | {sum(c >= 2 for c in head)} | {sum(c == 1 for c in tail)}/{len(tail)} |")
    lines += ["",
              "## 4. 전문가 점수별 (세 명 중앙값)", "",
              "| 점수 | 에피소드 | 중점 스윙 p10/중앙/p90 | 드는 쪽 스윙 p10/중앙/p90 | e1·e2 1회 — 앱 | e1·e2 1회 — 드는 쪽 |", "|---|---:|---|---|---:|---:|"]
    # 이은 열의 에피소드 몫(마지막 에피소드는 녹화가 끊겨 뺀다) — (피험자, 동작, 에피소드) → (앱, 드는 쪽)
    attr = {}
    for s_idx, row in zip(index["sets"], sets):
        for k, (ep, _, _) in enumerate(s_idx["episodes"][:-1]):
            attr[(s_idx["subject"], s_idx["movement"], ep)] = (row["liveByEpisode"][k], row["sideByEpisode"][k])
    for sc in sorted({e["score"] for e in per_ep if e["score"] is not None}):
        es = [e for e in per_ep if e["score"] == sc]
        hs = [attr[(e["subject"], e["movement"], e["episode"])] for e in es if (e["subject"], e["movement"], e["episode"]) in attr]
        lines.append(f"| {sc:g} | {len(es)} | {q([e['swingMid'] for e in es if e['swingMid'] is not None])} | "
                     f"{q([e['swingSide'] for e in es if e['swingSide'] is not None])} | "
                     f"{sum(h[0] == 1 for h in hs)}/{len(hs)} | {sum(h[1] == 1 for h in hs)}/{len(hs)} |")
    lines += ["", "## 5. 피험자·쪽별 (검출 비율 낮은 순 10)", "",
              "| 피험자 | 쪽 | 에피소드 | 신호 프레임(중점) | 중점 스윙 | 앱 카운트(에피소드 단독 — 녹화 끊김 영향) |", "|---|---|---:|---:|---|---|"]
    by = defaultdict(list)
    for e in per_ep:
        by[(e["subject"], e["side"])].append(e)
    worst = sorted(by.items(), key=lambda kv: sum(e["hipMid"] for e in kv[1]) / max(1, sum(e["judged"] for e in kv[1])))[:10]
    for (s, side), es in worst:
        lines.append(f"| {s} | {side} | {len(es)} | {pct(sum(e['hipMid'] for e in es), sum(e['judged'] for e in es))} | "
                     + " · ".join("—" if e["swingMid"] is None else f"{e['swingMid']:.0f}" for e in es) + " | "
                     + " · ".join(str(e["repsLive"]) for e in es) + " |")
    (args.out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")   # 윈도 콘솔 기본(cp949)은 '—' 를 못 쓴다
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
