# -*- coding: utf-8 -*-
"""라잉 레그 레이즈 양다리 판별(spec §99) — 판별 신호 hip_ang_maxside 의 반복 스윙 분포를 두 모집단에서 재고 문턱을 고른다.

판별 신호 = 몸통축(어깨 중점–골반 중점)과 각 무릎의 고관절각 중 더 편 쪽(PostureFloor). 두 다리를 함께 들면 크게 움직이고,
한 다리만 들면 바닥에 남은 다리를 따라 거의 안 움직인다. RepCounter 의 판별 게이트는 사이클 창 안 이 신호의 원값 최대−최소(스윙)가
문턱 미만이면 그 회를 세지 않는다(창에 표본이 2개 미만이면 판정하지 않고 센다 — 원칙 #1).

  양다리(통과해야 할 회) — AIHub '라잉 레그 레이즈' 전 클립 × 카메라(aihub_floor_mp.py 캡처). 클립 하나 = 반복 하나(16장, 250 ms):
      클립 안 원값 최대−최소. 카운터는 클립 사이 5 s 틈마다 끊겨 클립 안에서 기준을 못 잡으므로 스윙을 직접 잰다.
  한 다리(걸러야 할 회) — FMS ASLR 후면 카메라 이은 열(fms_captures.py): 에피소드 창 안 원값 최대−최소.

    python legraise_identity.py --aihub <aihub_floor_mp 폴더> --fms <fms 캡처 폴더> --out <폴더>
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

from run_replay import require_fresh_replay

KEY = "hip_ang_maxside"
COUNT_KEY = "hip_ang"
THRESHOLDS = (10.0, 15.0, 20.0, 25.0, 30.0)


def dump(bin_path: Path, cap: Path, out: Path) -> list[dict]:
    subprocess.run([str(bin_path), "--dump-features", str(cap), str(out)], check=True, capture_output=True)
    return [json.loads(x) for x in out.read_text(encoding="utf-8").splitlines()]


def swing(frames: list[dict], key: str) -> tuple[float | None, int]:
    vals = [f["features"][key] for f in frames if f["features"].get(key) is not None]
    return (max(vals) - min(vals) if len(vals) >= 2 else None), len(vals)


def upper95(k: int, n: int) -> float:
    """Clopper-Pearson 단측 95 % 상한(정확, 이분 탐색) — k/n 의 참값이 이보다 클 확률 5 %."""
    if n == 0:
        return float("nan")
    if k >= n:
        return 1.0
    lo, hi = k / n, 1.0
    for _ in range(60):
        p = (lo + hi) / 2
        cdf = sum(math.comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(k + 1))
        lo, hi = (p, hi) if cdf > 0.05 else (lo, p)
    return hi


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--aihub", type=Path, required=True)
    ap.add_argument("--fms", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    bin_path = require_fresh_replay()
    a.out.mkdir(parents=True, exist_ok=True)

    both = {}   # 카메라 → [(스윙, 표본 수, 카운트 신호 스윙, 조건)]
    for cap in sorted(a.aihub.glob("*.cap")):
        view = cap.stem
        info = json.loads((a.aihub / f"{view}.clips.json").read_text(encoding="utf-8"))
        frames = dump(bin_path, cap, a.out / f"aihub_{view}.jsonl")
        rows = []
        for c in info["clips"]:
            fs = [f for f in frames if c["t0"] <= f["t"] < c["t0"] + 16 * 250]
            s, n = swing(fs, KEY)
            sc, _ = swing(fs, COUNT_KEY)
            rows.append({"clip": c["clip_id"], "performer": c["performer"], "swing": s, "n": n, "countSwing": sc,
                         "normal": all(c["conditions"].values())})
        both[view] = rows

    index = json.loads((a.fms / "index.json").read_text(encoding="utf-8"))
    one = []
    for s in index["sets"]:
        frames = dump(bin_path, a.fms / s["capture"], a.out / ("fms_" + s["capture"].replace("/", "__") + ".jsonl"))
        for ep, t0, t1 in s["episodes"]:
            fs = [f for f in frames if t0 <= f["t"] <= t1]
            sw, n = swing(fs, KEY)
            sc, _ = swing(fs, COUNT_KEY)
            one.append({"set": s["capture"], "episode": ep, "swing": sw, "n": n, "countSwing": sc})

    lines = ["# 라잉 레그 레이즈 양다리 판별 — hip_ang_maxside 반복 스윙", "",
             "양다리 = AIHub 클립(반복 하나), 한 다리 = FMS ASLR 후면 에피소드(반복 하나). 스윙 = 창 안 원값 최대−최소(°). "
             "'판정 불가' = 표본 2개 미만(게이트는 이때 센다).", "",
             "## 문턱별 기각 비율", "",
             "| 문턱 | " + " | ".join(f"AIHub {v} 양다리 기각" for v in sorted(both)) + " | FMS 한 다리 기각 |",
             "|---:|" + "---:|" * (len(both) + 1)]
    for th in THRESHOLDS:
        cells = []
        for v in sorted(both):
            judged = [r for r in both[v] if r["swing"] is not None]
            k = sum(r["swing"] < th for r in judged)
            cells.append(f"{k}/{len(judged)} ({100 * k / max(1, len(judged)):.1f} %, 상한 {100 * upper95(k, len(judged)):.1f} %)")
        judged1 = [r for r in one if r["swing"] is not None]
        k1 = sum(r["swing"] < th for r in judged1)
        cells.append(f"{k1}/{len(judged1)} ({100 * k1 / max(1, len(judged1)):.0f} %)")
        lines.append(f"| {th:g}° | " + " | ".join(cells) + " |")
    lines += ["", "## 분포 (p5 / p25 / 중앙 / p75 / p95, °) · 판정 불가", "", "| 모집단 | 반복 | 판별 신호 스윙 | 카운트 신호(hip_ang) 스윙 | 판정 불가 |",
              "|---|---:|---|---|---:|"]

    def q(vs):
        vs = sorted(x for x in vs if x is not None)
        if not vs:
            return "—"
        pick = lambda p: vs[min(len(vs) - 1, int(round(p * (len(vs) - 1))))]
        return " / ".join(f"{pick(p):.0f}" for p in (0.05, 0.25, 0.5, 0.75, 0.95))
    for v in sorted(both):
        rs = both[v]
        lines.append(f"| AIHub {v} (양다리) | {len(rs)} | {q(r['swing'] for r in rs)} | {q(r['countSwing'] for r in rs)} | {sum(r['swing'] is None for r in rs)} |")
    lines.append(f"| FMS 후면 (한 다리) | {len(one)} | {q(r['swing'] for r in one)} | {q(r['countSwing'] for r in one)} | {sum(r['swing'] is None for r in one)} |")
    md = "\n".join(lines) + "\n"
    (a.out / "summary.md").write_text(md, encoding="utf-8")
    (a.out / "rows.json").write_text(json.dumps({"aihub": both, "fms": one}, ensure_ascii=False, indent=1), encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
