# -*- coding: utf-8 -*-
"""카운터 변경 A/B — 같은 캡처를 두 재생기 빌드(변경 전 = --baseline, 변경 후 = 지금 빌드)로 돌려 세트별 카운트를 견준다.

재생기는 앱 소스를 그 자리에서 컴파일하므로, 앱 카운터를 고치기 전에 설치본(build/install/trex-rep-replay)을 복사해 두면
그 복사본이 '변경 전 앱' 이다. 코퍼스마다 앱 세션 구성(live)만 돌린다. FMS(index 에 종목이 세트마다 없다)는 live + 드는 쪽 진단.

출력: <out>/ab.md (코퍼스 × 종목 요약, 바뀐 세트 목록), <out>/<코퍼스>/{before,after}.jsonl

    python tracker_ab.py --baseline <복사한 bin/trex-rep-replay(.bat)> --out <폴더> \
        mmfit=<data/mmfit_mp_captures> rehab=<data/rehab_mp_captures> phone=<phone_fcap> fms_back=<data/fms_mp_captures/back>
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

from run_replay import load_results, manifest_rows, metrics, require_fresh_replay, set_key, set_rows, write_manifest
from score_fms_aslr import attribute

FMS_EXERCISE = "라잉 레그 레이즈"


def fms_rows(index: dict) -> list[tuple]:
    rows = []
    for s in index["sets"]:
        cid = set_key(s)
        rows.append((f"{cid}|live", s["capture"], FMS_EXERCISE, "live", "", "", "1", "", "", ""))
        rows.append((f"{cid}|side", s["capture"], FMS_EXERCISE, "live", f"hip_ang_{s['movingSide']}", "25.0", "1", "", "", ""))
    return rows


def run(bin_path: Path, manifest: Path, out: Path) -> dict[str, dict]:
    subprocess.run([str(bin_path), str(manifest), str(out)], check=True, capture_output=True)
    return load_results(out)


def fms_summary(index: dict, res: dict[str, dict], cfg: str) -> dict:
    head, tail = [], []
    for s in index["sets"]:
        c = attribute(res[f"{set_key(s)}|{cfg}"].get("repTimesMs", []), s["episodes"])
        head += c[:-1]
        tail.append(c[-1])
    return {"e12": len(head), "e12_one": sum(x == 1 for x in head), "e12_zero": sum(x == 0 for x in head),
            "e12_multi": sum(x >= 2 for x in head), "e3_one": sum(x == 1 for x in tail), "sets": len(tail)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpora", nargs="+", help="이름=캡처 폴더(index.json)")
    ap.add_argument("--baseline", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    after_bin = require_fresh_replay()
    lines = ["# 카운터 A/B — 변경 전 → 변경 후", ""]
    changed_lines = ["", "## 바뀐 세트", "", "| 코퍼스 | 세트 | 종목 | 구성 | 정답 | 앱 로그 | 변경 전 | 변경 후 |", "|---|---|---|---|---:|---:|---:|---:|"]
    for spec in args.corpora:
        name, _, path = spec.partition("=")
        root = Path(path)
        index = json.loads((root / "index.json").read_text(encoding="utf-8"))
        fms = "exercise" in index and index["sets"] and "exercise" not in index["sets"][0]
        rows = fms_rows(index) if fms else manifest_rows(index, {"live"})
        work = args.out / name
        work.mkdir(parents=True, exist_ok=True)
        manifest = work / "manifest.tsv"
        write_manifest(rows, manifest, root)
        before = run(args.baseline, manifest, work / "before.jsonl")
        after = run(after_bin, manifest, work / "after.jsonl")
        lines += [f"## {name} ({len(index['sets'])}세트)", ""]
        if fms:
            lines += ["| 구성 | e1·e2 1회 (전 → 후) | 0회 | 2회 이상 | e3 1회 |", "|---|---:|---:|---:|---:|"]
            for cfg in ("live", "side"):
                b, a = fms_summary(index, before, cfg), fms_summary(index, after, cfg)
                lines.append(f"| {cfg} | {b['e12_one']}/{b['e12']} → **{a['e12_one']}/{a['e12']}** | {b['e12_zero']} → {a['e12_zero']} | "
                             f"{b['e12_multi']} → {a['e12_multi']} | {b['e3_one']} → {a['e3_one']} /{a['sets']} |")
            for s in index["sets"]:
                for cfg in ("live", "side"):
                    k = f"{set_key(s)}|{cfg}"
                    if before[k]["reps"] != after[k]["reps"]:
                        changed_lines.append(f"| {name} | {s['capture']} | {FMS_EXERCISE} | {cfg} | {s['truthReps']} | | {before[k]['reps']} | {after[k]['reps']} |")
            lines.append("")
            continue
        rb, ra = set_rows(index["sets"], before, "live", None), set_rows(index["sets"], after, "live", None)
        by_b, by_a = defaultdict(list), defaultdict(list)
        for x in rb:
            by_b[x["exercise"]].append(x)
        for x in ra:
            by_a[x["exercise"]].append(x)
        lines += ["| 종목 | 세트 | 정확 일치 (전 → 후) | MAE | 과다 세트 | 0회 세트 | 앞 헛카운트 | 뒤 헛카운트 | 앞뒤 포함 정확 | 로그와 같음 |",
                  "|---|---:|---|---|---|---|---|---|---|---|"]
        for ex in sorted(by_b):
            mb, ma = metrics(by_b[ex]), metrics(by_a[ex])

            def g(m, k, fmt="{:.2f}"):
                v = m.get(k)
                return "—" if v is None else fmt.format(v)

            def w(m, side):
                return "—" if side not in m else str(m[side]["falseCounts"])
            logged_b = sum(1 for x in by_b[ex] if x["loggedReps"] is not None and x["totalReps"] == x["loggedReps"])
            logged_a = sum(1 for x in by_a[ex] if x["loggedReps"] is not None and x["totalReps"] == x["loggedReps"])
            n_log = sum(1 for x in by_b[ex] if x["loggedReps"] is not None)
            lines.append(f"| {ex} | {len(by_b[ex])} | {g(mb, 'exact')} → **{g(ma, 'exact')}** | {g(mb, 'MAE')} → {g(ma, 'MAE')} | "
                         f"{g(mb, 'over', '{}')} → {g(ma, 'over', '{}')} | {g(mb, 'zeroSets', '{}')} → {g(ma, 'zeroSets', '{}')} | "
                         f"{w(mb, 'lead')} → {w(ma, 'lead')} | {w(mb, 'after')} → {w(ma, 'after')} | "
                         f"{g(mb, 'exactWithWindows')} → {g(ma, 'exactWithWindows')} | "
                         + (f"{logged_b}/{n_log} → {logged_a}/{n_log}" if n_log else "—") + " |")
            for xb, xa in zip(by_b[ex], by_a[ex]):
                if xb["totalReps"] != xa["totalReps"] or xb["detected"] != xa["detected"]:
                    tb = xb["detected"] if xb["truth"] is not None else xb["totalReps"]
                    ta = xa["detected"] if xa["truth"] is not None else xa["totalReps"]
                    extra = "" if xb["lead"] is None else f" (앞 {xb['lead']}→{xa['lead']}, 뒤 {xb['after']}→{xa['after']})"
                    changed_lines.append(f"| {name} | {xb['set']} | {ex} | live | {'' if xb['truth'] is None else xb['truth']} | "
                                         f"{'' if xb['loggedReps'] is None else xb['loggedReps']} | {tb} | {ta}{extra} |")
        lines.append("")
    md = "\n".join(lines + changed_lines) + "\n"
    (args.out / "ab.md").write_text(md, encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
