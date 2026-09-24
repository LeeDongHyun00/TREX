# -*- coding: utf-8 -*-
"""Kotlin 새 코어(replay-jvm 'hysteresis') ↔ 파이썬 프로토타입(prototype_counter.py) 세트별 파리티 — 설계 §6 단계 2.

왜 있나
-------
시작 확정 기본값(첫 쌍 8 s · 이후 진폭 비 0.5~2)은 파이썬 배터리(stress_battery.py, 설계 §12·§13)에서 골랐고, 앱이 쓰는 것은
Kotlin(`RepHysteresis`·`RepStartConfirmation`)이다. 둘이 조용히 갈라지면 배터리의 선택 근거가 앱에 성립하지 않는데, 유닛 테스트는
합성 신호만 보므로 그 갈라짐을 못 잡는다. 이 스크립트가 같은 MM-Fit 캡처에서 둘을 **세트·발화 시각 단위로** 맞춰 보고,
하나라도 다르면 0 이 아닌 코드로 끝난다(코어·확정 규칙·기본값을 어느 쪽에서든 바꾸면 돌린다).

비교 두 가지
    A (같은 입력)   재생기가 카운터에 실제로 넣은 프레임별 신호값(--series)을 prototype_counter.Counter(게이트 = 재생 신호의 minAmp)
                    + apply_policy("first8+amp")(보류는 바로 다음 사이클로만 해소 — 앱과 같다)에 그대로 넣는다 → 코어·확정 논리만 비교.
                    세트 카운트, 발표된 사이클의 발화 시각 목록, 세트 끝 확정 대기 수가 모두 같아야 한다(엄격 — 종료 코드에 반영).
    B (프로토타입 피처)  pose_3d.npy 에서 prototype_counter.features() 로 각을 계산해 캡처와 같은 프레임 시각에 넣는다 → 앱 PostureCore 의
                    각 계산까지 포함한 비교. 카운트만 본다(보고용 — 피처 계산의 부동소수 차이는 코어 파리티가 아니다).
구성: 스쿼트·런지 knee_mean, 컬 elbow_mean 과 elbow_minside(후보, 설계 §4.4). 극성은 셋 다 DOWN(run_replay.HYSTERESIS_POLARITY).

MM-Fit 은 개발 데이터다(설계 §12) — 이 스크립트는 성능이 아니라 두 구현의 동일성만 잰다.

사용법 (재생기 빌드 필요: cd replay-jvm && gradle test installDist)
    python parity_core.py ../../data/mm-fit/captures_pose3d ../../data/mm-fit/mm-fit --out ../../data/mm-fit/exp_parity
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import prototype_counter as pc  # noqa: E402
import run_replay  # noqa: E402

POLICY = "first8+amp"   # 설계 §13 에서 채택한 정책 = 앱 RepStartConfirmation 기본값
TARGETS = {"바벨 스쿼트": ["knee_mean"], "스텝 포워드 다이나믹 런지": ["knee_mean"], "덤벨 컬": ["elbow_mean", "elbow_minside"]}


def manifest(index: dict) -> list[tuple]:
    rows = []
    for s in index["sets"]:
        feats = TARGETS.get(s["exercise"])
        if not feats:
            continue
        pol = run_replay.HYSTERESIS_POLARITY[s["exercise"]]
        key = run_replay.set_key(s)
        for i, feat in enumerate(feats):
            cfg = "hysteresis" if i == 0 else f"hysteresis+{feat}"
            rows.append((f"{key}|{cfg}", s["capture"], s["exercise"], "hysteresis", "" if i == 0 else feat, "" if i == 0 else "35",
                         "0", "", "", pol))
    return rows


def prototype(ts: list[int], vs: list[float], gate: float) -> tuple[list[int], int]:
    c = pc.Counter(gate)
    for t, v in zip(ts, vs):
        c.on_frame(int(t), v)
    published, pending = pc.apply_policy(POLICY, c.fires, c.amps, c.durs)
    return [int(t) for t in published], len(pending)


def capture_times(path: Path) -> list[int]:
    """캡처의 프레임 시각 중 사람이 잡힌 것(poses ≥ 1) — 재생기가 onFrame 을 부른 프레임."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if parts[0] == "F" and int(parts[2]) >= 1:
            out.append(int(parts[1]))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("captures", type=Path, help="mmfit_pose3d_captures.py 가 만든 캡처 폴더(index.json)")
    ap.add_argument("mmfit", type=Path, help="MM-Fit 원본 폴더(wNN/wNN_pose_3d.npy) — 비교 B")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    index = json.loads((a.captures / "index.json").read_text(encoding="utf-8"))
    rows = manifest(index)
    a.out.mkdir(parents=True, exist_ok=True)
    run_replay.write_manifest(rows, a.out / "manifest.tsv", a.captures)
    results = run_replay.run_jvm(a.out / "manifest.tsv", a.out / "replay.jsonl", a.out / "series")
    by_key = {run_replay.set_key(s): s for s in index["sets"]}
    poses: dict[str, tuple] = {}
    table = []
    for row in rows:
        rid = row[0]
        key, cfg = rid.split("|", 1)
        s = by_key[key]
        r = results[rid]
        feat = r["feature"]
        # A: 재생기가 카운터에 넣은 값 그대로
        ser = list(csv.DictReader((a.out / "series" / f"{rid}.tsv").open(encoding="utf-8"), delimiter="\t"))
        ts = [int(x["tMs"]) for x in ser if x["value"] != ""]
        vs = [float(x["value"]) for x in ser if x["value"] != ""]
        a_pub, a_pend = prototype(ts, vs, float(r["minAmp"]))
        # B: 프로토타입 피처(pose_3d.npy)를 캡처와 같은 프레임 시각에
        w = s["workout"]
        if w not in poses:
            pose = np.load(a.mmfit / w / f"{w}_pose_3d.npy")
            frame_no = pose[0, :, 0].astype(int)
            poses[w] = (pc.features(pose[:, :, 1:]), {int(f): i for i, f in enumerate(frame_no)})
        F, row_of = poses[w]
        tb, vb = [], []
        for t in capture_times(a.captures / s["capture"]):
            i = row_of.get(int(round(t * pc.FPS / 1000.0)))
            if i is not None and np.isfinite(F[feat][i]):
                tb.append(t); vb.append(float(F[feat][i]))
        b_pub, _ = prototype(tb, vb, float(r["minAmp"]))
        table.append({"set": key, "exercise": s["exercise"], "config": cfg, "feature": feat, "truth": s.get("truthReps"),
                      "kotlin": r["reps"], "kotlinPublishedMs": r["publishedMs"], "kotlinPending": r["pendingReps"],
                      "A": len(a_pub), "A_publishedMs": a_pub, "A_pending": a_pend, "B": len(b_pub)})
    (a.out / "parity_rows.json").write_text(json.dumps(table, ensure_ascii=False, indent=0), encoding="utf-8")

    groups = defaultdict(list)
    for x in table:
        groups[(x["exercise"], x["config"])].append(x)
    print(f"정책 {POLICY} · 극성 DOWN · MM-Fit 3D 캡처 {len(by_key)}세트 (개발 데이터 — 동일성만 잰다)\n")
    print("| 종목 | 구성 | 세트 | A 카운트 일치 | A 발화 시각 일치 | A 확정 대기 일치 | B 카운트 일치 (보고용) |")
    print("|---|---|---:|---:|---:|---:|---:|")
    strict_bad = 0
    for (ex, cfg), xs in sorted(groups.items()):
        n = len(xs)
        ca = sum(x["kotlin"] == x["A"] for x in xs)
        ta = sum(x["kotlinPublishedMs"] == x["A_publishedMs"] for x in xs)
        pa = sum(x["kotlinPending"] == x["A_pending"] for x in xs)
        cb = sum(x["kotlin"] == x["B"] for x in xs)
        strict_bad += (n - ca) + (n - ta) + (n - pa)
        print(f"| {ex} | {cfg} | {n} | {ca}/{n} | {ta}/{n} | {pa}/{n} | {cb}/{n} |")
    for x in table:
        if x["kotlin"] != x["A"] or x["kotlinPublishedMs"] != x["A_publishedMs"] or x["kotlinPending"] != x["A_pending"]:
            print(f"DIFF(A) {x['set']} {x['config']}: kotlin {x['kotlin']} {x['kotlinPublishedMs']} pending {x['kotlinPending']} / "
                  f"prototype {x['A']} {x['A_publishedMs']} pending {x['A_pending']}")
    for x in table:
        if x["kotlin"] != x["B"]:
            print(f"diff(B) {x['set']} {x['config']}: kotlin {x['kotlin']} / prototype-features {x['B']}")
    print(f"\n{'PASS' if strict_bad == 0 else 'FAIL'} — 엄격 비교(A) 불일치 {strict_bad}건, 결과 {a.out / 'parity_rows.json'}")
    return 0 if strict_bad == 0 else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
