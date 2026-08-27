#!/usr/bin/env python
"""연결된 안드로이드 기기에서 세트 로그(JSONL)를 회수하고 요약한다 (spec §14).

앱은 로그를 **외부 앱 전용 저장소**에 쓰므로 루팅·권한 없이 adb pull 로 바로 꺼낼 수 있다:
    /sdcard/Android/data/com.example.trex_kotlin/files/posture_logs/sets-YYYYMMDD.jsonl

사용:
    python pull_logs.py                 # 회수 + 요약
    python pull_logs.py --summary-only  # 이미 받은 파일만 다시 요약
    python pull_logs.py --clear-device  # 회수 후 기기에서 삭제(중복 누적 방지)

출력: outputs/logs/*.jsonl, 표준출력 요약(세트/종목/프레임/판정/측정품질)
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
DEST = HERE / "outputs" / "logs"
PKG = "com.example.trex_kotlin"
REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files/posture_logs"
ADB = r"C:\Users\hp276\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 바닥 종목 — 요약에서 따로 표시 (임계값 미보정 beta 경로)
FLOOR = {"크런치", "푸시업", "니푸쉬업", "플랭크", "라잉 레그 레이즈", "힙쓰러스트", "시저크로스", "바이시클 크런치", "Y - Exercise"}


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"[adb 실패] {' '.join(args)}\n{r.stdout}\n{r.stderr}")
    return (r.stdout or "") + (r.stderr or "")


def pull(clear: bool) -> int:
    devices = [ln.split("\t")[0] for ln in adb("devices").splitlines()[1:] if "\tdevice" in ln]
    if not devices:
        raise SystemExit("[err] 연결된 기기가 없습니다. USB 디버깅을 켜고 `adb devices` 로 확인하세요.")
    print(f"[device] {devices[0]}")
    listing = adb("shell", "ls", REMOTE_DIR, check=False)
    if "No such file" in listing or "Permission denied" in listing:
        raise SystemExit(f"[err] 기기에 로그 폴더가 없습니다: {REMOTE_DIR}\n"
                         "      앱에서 자세교정 세션을 8프레임 이상 진행한 뒤 다시 시도하세요.")
    names = [n.strip() for n in listing.split() if n.strip().endswith(".jsonl")]
    if not names:
        raise SystemExit("[err] 로그 파일(.jsonl)이 없습니다 — 세션을 한 세트 이상 마쳐 주세요.")
    DEST.mkdir(parents=True, exist_ok=True)
    for n in names:
        adb("pull", f"{REMOTE_DIR}/{n}", str(DEST / n))
        print(f"[pull] {n}")
    if clear:
        for n in names:
            adb("shell", "rm", f"{REMOTE_DIR}/{n}", check=False)
        print("[clear] 기기 로그 삭제됨")
    return len(names)


def summarize() -> None:
    files = sorted(DEST.glob("*.jsonl"))
    if not files:
        raise SystemExit(f"[err] 받은 로그가 없습니다: {DEST}")
    logs = []
    for f in files:
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                try:
                    logs.append(json.loads(line))
                except json.JSONDecodeError as e:
                    print(f"[warn] {f.name}: 파싱 실패 — {e}")
    if not logs:
        raise SystemExit("[err] 유효한 세트가 없습니다.")

    print(f"\n=== 세트 로그 요약: {len(logs)}세트 / 파일 {len(files)}개 ===")
    subs = Counter(l.get("subject_id") or "-" for l in logs)
    print(f"수행자: {dict(subs)}  (재보정 GroupKFold 그룹 — 여러 명이면 그만큼 신뢰도가 오른다)")

    by_ex = defaultdict(list)
    for l in logs:
        by_ex[l["exercise"]].append(l)

    print(f"\n{'종목':16s} {'세트':>4s} {'프레임':>6s} {'검출률':>7s} {'추론ms':>7s} {'기울기':>6s} {'판정(위반/전체)':>14s}")
    for ex, ls in sorted(by_ex.items(), key=lambda kv: -len(kv[1])):
        frames = [len(l["frames"]) for l in ls]
        allf = [fr for l in ls for fr in l["frames"]]
        det = sum(1 for fr in allf if fr.get("features")) / max(len(allf), 1)
        infer = [fr["infer_ms"] for fr in allf if fr.get("infer_ms")]
        tilts = [l["tilt_deg"] for l in ls if l.get("tilt_deg") is not None]
        res = [r for l in ls for r in l.get("results", [])]
        viol = sum(1 for r in res if r["verdict"] == "VIOLATION")
        tag = " (바닥)" if ex in FLOOR else ""
        print(f"{ex + tag:16s} {len(ls):>4d} {sum(frames):>6d} {det * 100:>6.1f}% "
              f"{(sum(infer) / max(len(infer), 1)):>6.0f} "
              f"{(sum(tilts) / max(len(tilts), 1)) if tilts else float('nan'):>6.1f} "
              f"{viol:>6d}/{len(res):<7d}")

    # 측정 품질 경고 — 재보정에 쓸 수 있는 로그인지
    print("\n--- 품질 점검 ---")
    short = [l for l in logs if len(l["frames"]) < 16]
    if short:
        print(f"  ! 프레임 16개 미만 세트 {len(short)}개 — 세트를 더 길게(최소 15초) 진행하면 통계가 안정됩니다")
    novis = [l for l in logs if not any(fr.get("vis") for fr in l["frames"])]
    if novis:
        print(f"  ! 가시성 미기록 세트 {len(novis)}개")
    nores = [l for l in logs if not l.get("results")]
    if nores:
        print(f"  ! 판정 결과 없는 세트 {len(nores)}개 — 규칙이 없는 종목이거나 프레임 부족")
    floor_sets = [l for l in logs if l["exercise"] in FLOOR]
    print(f"  바닥 종목 세트: {len(floor_sets)} (임계값 재보정 목표: 종목당 12세트 이상, 30이면 충분)")
    if floor_sets:
        need = defaultdict(int)
        for l in floor_sets:
            need[l["exercise"]] += 1
        for ex, n in sorted(need.items()):
            print(f"    {ex:14s} {n:2d}세트 {'✅' if n >= 12 else '· 부족(' + str(12 - n) + '세트 더)'}")
    print(f"\n다음 단계: python calibrate_from_logs.py --logs {DEST}  (코치 라벨 CSV 준비 후)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary-only", action="store_true")
    ap.add_argument("--clear-device", action="store_true", help="회수 후 기기에서 삭제")
    a = ap.parse_args()
    if not a.summary_only:
        pull(a.clear_device)
    summarize()


if __name__ == "__main__":
    main()
