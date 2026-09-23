"""motion-1 실기기 로그를 Kotlin MotionReplayTest가 읽는 TSV로 내보낸다.

기존 300ms 로그로 누락된 관절·중간 프레임을 만들어내지 않는다.
예: python research/aihub_fitness/export_motion_replay.py sets.jsonl --out outputs/motion.tsv
PowerShell: $env:TREX_MOTION_REPLAY=(Resolve-Path outputs/motion.tsv).Path
그다음 gradlew.bat :app:testDebugUnitTest --tests "*.MotionReplayTest" --rerun-tasks
"""
import argparse
import json
from pathlib import Path


def export(source: Path, destination: Path, set_id: str | None = None) -> dict:
    selected = None
    with source.open(encoding="utf-8-sig") as stream:
        for line in stream:
            if not line.strip():
                continue
            item = json.loads(line)
            if item.get("motion") and (set_id is None or item.get("set_id") == set_id):
                selected = item
    if selected is None:
        raise ValueError("motion 입력이 있는 세트가 없습니다. 구형 로그의 미관측 프레임은 재생할 수 없습니다.")
    motion = selected["motion"]
    if not motion.get("frames"):
        raise ValueError("반복 추적 프레임이 없는 세트입니다. 유지 운동은 기존 측정 로그를 확인하세요.")
    if motion.get("dropped", 0):
        raise ValueError("일부 motion 프레임이 생략된 세트입니다. 전체 세트 일치 검증에서 제외합니다.")
    rows = ["#trex.motion-replay/1", f"#exercise={selected['exercise']}", f"#version={motion['version']}"]
    for frame in motion["frames"]:
        cells = [str(frame["t_ms"]), frame["phase"], str(frame["completed"]), str(frame["infer_ms"])]
        for key, value in sorted(frame["features"].items()):
            if any(c in key for c in "\t\r\n="):
                raise ValueError(f"잘못된 피처 이름: {key!r}")
            if value is not None:
                cells.append(f"{key}={value}")
        rows.append("\t".join(cells))
    if any("\n" in row or "\r" in row for row in rows):
        raise ValueError("메타데이터에 줄바꿈이 있습니다.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return {"set_id": selected.get("set_id"), "frames": len(motion["frames"]), "out": str(destination)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--set-id")
    args = parser.parse_args()
    print(json.dumps(export(args.source, args.out, args.set_id), ensure_ascii=False))
