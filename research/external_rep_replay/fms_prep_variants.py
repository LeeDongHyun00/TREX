# -*- coding: utf-8 -*-
"""FMS ASLR 이은 열로 '카운트다운 직후 바로 움직인 사용자' 를 흉내 내 바닥 준비 기준 심기(spec §99)의 효과를 잰다.

열마다 첫 들어 올림의 시작(드는 쪽 hip_ang_L/R 가 처음 2프레임의 중앙값에서 10° 넘게 벗어난 첫 판정 프레임)을 찾고, 그 300 ms 앞을 준비 끝으로 둔다.
  cut  — 준비 끝 앞 프레임을 지운다(앱: 준비 프레임이 카운터에 닿지 않고 심지도 않는다 = §99 이전 바닥 경로)
  prep — 프레임은 그대로 두고 메타 prepUntilMs 를 적는다(재생기가 앱처럼 그 앞 프레임으로 기준을 심는다 = §99)
같은 지금 재생기로 두 변형을 돌려 e1(첫 회)을 센 열의 수를 견준다. 준비 끝 앞 쉬는 시간이 0.9 s 미만이면(심을 표본 3개가 안 된다) 뺀다.

    python fms_prep_variants.py <캡처 폴더(index.json)> --out <폴더>
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from capture_format import read_capture
from run_replay import require_fresh_replay
from score_fms_aslr import attribute

EXERCISE = "라잉 레그 레이즈"
ONSET_DEG = 10.0
LEAD_MS = 300
MIN_PREP_MS = 900


def onset(dump: list[dict], key: str) -> int | None:
    vals = [(f["t"], f["features"].get(key)) for f in dump if f["features"].get(key) is not None]
    if len(vals) < 4:
        return None
    base = sorted(v for _, v in vals[:2])[0] if len(vals) < 3 else sorted(v for _, v in vals[:3])[1]
    return next((t for t, v in vals if abs(v - base) > ONSET_DEG), None)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    bin_path = require_fresh_replay()
    index = json.loads((a.root / "index.json").read_text(encoding="utf-8"))
    a.out.mkdir(parents=True, exist_ok=True)
    rows, used = [], []
    for s in index["sets"]:
        cap = a.root / s["capture"]
        name = s["capture"].replace("/", "__").removesuffix(".cap")
        dump_path = a.out / f"{name}.dump.jsonl"
        subprocess.run([str(bin_path), "--dump-features", str(cap), str(dump_path)], check=True, capture_output=True)
        dump = [json.loads(x) for x in dump_path.read_text(encoding="utf-8").splitlines()]
        t_on = onset(dump, f"hip_ang_{s['movingSide']}")
        if t_on is None or t_on - LEAD_MS < MIN_PREP_MS:
            continue
        prep_until = t_on - LEAD_MS
        lines = cap.read_text(encoding="utf-8").splitlines()
        head, body = lines[0], lines[1:]
        cut = [ln for ln in body if not ln.startswith("F\t") or int(ln.split("\t")[1]) >= prep_until]
        (a.out / f"{name}.cut.cap").write_text("\n".join([head] + cut) + "\n", encoding="utf-8")
        (a.out / f"{name}.prep.cap").write_text("\n".join([head + f"\tprepUntilMs={prep_until}"] + body) + "\n", encoding="utf-8")
        for v in ("cut", "prep"):
            rows.append(f"{name}|{v}|live\t{a.out / f'{name}.{v}.cap'}\t{EXERCISE}\tlive\t\t\t1\t\t\t")
            rows.append(f"{name}|{v}|side\t{a.out / f'{name}.{v}.cap'}\t{EXERCISE}\tlive\thip_ang_{s['movingSide']}\t25.0\t1\t\t\t")
        used.append((name, s, prep_until))
    manifest = a.out / "manifest.tsv"
    manifest.write_text("\n".join(rows) + "\n", encoding="utf-8")
    out = a.out / "replay.jsonl"
    subprocess.run([str(bin_path), str(manifest), str(out)], check=True, capture_output=True)
    res = {r["id"]: r for r in map(json.loads, out.read_text(encoding="utf-8").splitlines())}
    lines = [f"# 바닥 준비 기준 심기 — FMS ASLR 이은 열 {len(used)}/{len(index['sets'])}개 (준비 끝 = 첫 들어 올림 시작 − {LEAD_MS} ms)", "",
             "| 구성 | 변형 | e1 1회 | e1 0회 | 열 합계(정답 3) 정확 | 심은 열 |", "|---|---|---:|---:|---:|---:|"]
    for cfg in ("live", "side"):
        for v in ("cut", "prep"):
            e1, zero, exact, seeded = 0, 0, 0, 0
            for name, s, _ in used:
                r = res[f"{name}|{v}|{cfg}"]
                c = attribute(r.get("repTimesMs", []), s["episodes"])
                e1 += c[0] == 1
                zero += c[0] == 0
                exact += r["reps"] == 3
                seeded += r.get("standingSeed") is not None
            lines.append(f"| {cfg} | {v} | {e1}/{len(used)} | {zero} | {exact}/{len(used)} | {seeded} |")
    md = "\n".join(lines) + "\n"
    (a.out / "summary.md").write_text(md, encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
