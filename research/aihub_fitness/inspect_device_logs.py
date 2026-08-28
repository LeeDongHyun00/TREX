#!/usr/bin/env python
"""실기기 세트 로그(JSONL) 요약 — 무엇이 몇 세트 찍혔고, 바닥 피처가 들어있는지.

사용: python inspect_device_logs.py [로그디렉터리]
기본 디렉터리: outputs/device_logs (adb pull 결과)
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
FLOOR_FEATS = {"hip_dev_ankle", "hip_dev_knee", "knee_dev", "shoulder_dev", "trunk_ankle_ang",
               "head_trunk_ang", "shoulder_arm_ang", "hand_shoulder_off", "wrist_shoulder_d",
               "shoulder_ground", "hip_ground", "knee_ground", "ankle_ground", "head_ground"}
STANDING_FEATS = {"torso_incl", "torso_pitch", "knee_out_mean", "shoulder_asym", "sh_over_hip_fwd"}


def main():
    d = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "outputs" / "device_logs"
    files = sorted(d.glob("*.jsonl"))
    if not files:
        sys.exit(f"[err] {d} 에 jsonl 없음")
    logs = []
    for f in files:
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                logs.append((f.name, json.loads(line)))
    print(f"파일 {len(files)}개, 세트 {len(logs)}개\n")

    by_ex = defaultdict(list)
    for fn, g in logs:
        by_ex[g.get("exercise", "?")].append((fn, g))

    print(f"{'종목':22s} {'세트':>4s} {'프레임(중앙)':>10s} {'피처종류':>8s} {'노트':<28s} {'날짜'}")
    for ex, items in sorted(by_ex.items(), key=lambda kv: -len(kv[1])):
        frames = sorted(len(g.get("frames", [])) for _, g in items)
        med = frames[len(frames) // 2] if frames else 0
        feats = set()
        for _, g in items:
            for fr in g.get("frames", [])[:3]:
                feats |= set(fr.get("features", {}))
        kind = "바닥2D" if feats & FLOOR_FEATS else ("서있기3D" if feats & STANDING_FEATS else "?")
        notes = Counter((g.get("note") or "-") for _, g in items)
        note_s = ", ".join(f"{k}×{v}" for k, v in list(notes.items())[:2])
        days = sorted({g.get("created_at", "")[:10] for _, g in items})
        print(f"{ex:22s} {len(items):>4d} {med:>10d} {kind:>8s} {note_s:<28s} {'/'.join(days)}")

    # 바닥 종목 상세 (재배치 검증 대상)
    floor_sets = [(fn, g) for fn, g in logs
                  if any(set(fr.get("features", {})) & FLOOR_FEATS for fr in g.get("frames", []))]
    print(f"\n바닥 2D 피처를 쓴 세트: {len(floor_sets)}개")
    for fn, g in floor_sets[:12]:
        fr = g.get("frames", [])
        nf = len(fr)
        detected = sum(1 for x in fr if x.get("features"))
        print(f"  {g.get('created_at','')[:19]} {g.get('exercise','?'):12s} 프레임 {nf:3d}(피처 {detected:3d}) "
              f"note={g.get('note')} rules={g.get('rules_version')} tilt={g.get('tilt_deg')}")
    if not floor_sets:
        print("  (없음 — 바닥 종목 세트가 아직 기록되지 않았다)")


if __name__ == "__main__":
    main()
