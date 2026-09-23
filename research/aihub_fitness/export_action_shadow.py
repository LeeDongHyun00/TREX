"""명시적으로 저장한 pose packet만 재생 자료로 내보낸다. 운동명/예측을 정답 라벨로 복사하지 않음."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np
from action_input import encode, SCHEMA


def extract(item):
    action = item.get("action", {})
    if action.get("schema") != "trex.action.shadow/1" or not action.get("capture_enabled"):
        raise ValueError("관절 기록을 켠 새 로그가 필요합니다")
    if action.get("raw_dropped", 0) or action.get("busy_dropped", 0):
        raise ValueError("생략/처리 누락이 있는 로그입니다. 완전한 시간 라벨 자료로 내보내지 않습니다")
    if action.get("raw_schema") != "trex.pose.packet/2":
        raise ValueError("지원하지 않는 원관절 스키마")
    frames = action.get("raw_frames", [])
    if not frames:
        raise ValueError("원관절 프레임이 없습니다")
    encoded, times, epochs = [], [], []
    last = None
    for frame in frames:
        t = frame["t_ms"]
        if not isinstance(t, int) or (last is not None and t <= last):
            raise ValueError("역순 또는 중복 프레임 시각")
        last = t
        encoded.append(encode(frame["xy"], frame["visibility"], frame["presence"], frame["width"], frame["height"]))
        times.append(t); epochs.append(frame["epoch"])
    return np.asarray(encoded), np.asarray(times, np.int64), np.asarray(epochs, np.int32)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--set-id")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    items = [json.loads(line) for line in args.jsonl.read_text("utf-8").splitlines() if line.strip()]
    selected = [x for x in items if x.get("set_id") == args.set_id] if args.set_id else items[-1:]
    if len(selected) != 1:
        raise SystemExit("하나의 세트를 선택해야 합니다")
    item = selected[0]
    x, t, epochs = extract(item)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(args.out, x=x, t_ms=t, epoch=epochs)
    metadata = {"schema": SCHEMA, "set_id": item["set_id"], "frames": len(t),
                "human_labels_required": True, "selected_exercise_is_ground_truth": False,
                "source_bundle": item["action"].get("bundle_id")}
    args.out.with_suffix(".metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), "utf-8")
    print(f"{len(t)} frames; human labels required")


if __name__ == "__main__":
    main()
