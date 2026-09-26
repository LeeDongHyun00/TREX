# -*- coding: utf-8 -*-
"""MM-Fit 데이터셋 자체 3D 포즈 → 재생 캡처. MediaPipe 가 아닌 **보조 경로**다.

왜 있나
-------
MM-Fit 영상은 zenodo.org 에 있다. 영상이 없으면 MediaPipe 를 돌릴 수 없지만, 라벨과 데이터셋의
3D 포즈(mm-fit.zip)는 따로 받을 수 있다. 이 포즈는 휴대폰도 MediaPipe 도 아니지만, 연속 영상에서
30fps 로 뽑은 조밀한 골격이고 세트별 반복 수 정답이 붙어 있다. 그래서 "현재 카운터 로직이 실제
연속 동작을 세는가" 를 MediaPipe 잡음과 떼어서 볼 수 있다. MediaPipe 결과를 대신하지 않는다 —
여기서 잘 세도 휴대폰에서 잘 센다는 뜻이 아니고, 여기서 못 세면 MediaPipe 이전의 문제다.

좌표
----
pose_3d.npy = (3축, 프레임, 1+17). 0열은 프레임 번호(라벨과 같은 축), 나머지는 H36M 17관절(mm, z 가 위).
재생기는 MediaPipe 월드 좌표(m, y 아래)를 받아 (x, -y, -z)×100 으로 cm·y 위 좌표를 만든다. 그래서
회전(행렬식 +1 — 좌우를 뒤집지 않는다) (x, y, z) → (x, z, -y) 의 결과가 재생기에서 나오도록 역으로 써 둔다:
    mp = (ox/1000, -oz/1000, oy/1000)   →   재생기 = (ox, oz, -oy)/10 cm
MediaPipe 에 있고 H36M 에 없는 관절(눈·귀·손·발끝·뒤꿈치)은 visibility 0 으로 써서 재생기가 버리게 한다.

사용법
------
    python mmfit_pose3d_captures.py ../../data/mm-fit/mm-fit --out ../../data/mm-fit/captures_pose3d
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np

from capture_format import LANDMARKS, app_cadence, frame_line, write_capture

FPS = 30.0
# 라벨과 영상 시계는 4~5프레임 어긋난다(다른 계열 실측, docs/mmfit-rep-counting.v1.md §1).
# 세트 사이가 통째로 휴식이라 양끝 0.5초 여유는 옆 반복에 닿지 않는다.
MARGIN_FRAMES = 15

# 사용자가 고른 세 종목만 — MM-Fit 활동명 → 앱이 그 운동에서 쓰는 AIHub 규칙 종목(RepSignals 키)
ACTIVITIES = {
    "squats": "바벨 스쿼트",               # MM-Fit 은 맨몸 스쿼트. 앱의 스쿼트 신호는 바벨 스쿼트 것뿐이다
    "lunges": "스텝 포워드 다이나믹 런지",   # 앱 '런지' 의 매핑
    "bicep_curls": "덤벨 컬",
}

# MediaPipe 인덱스 ← H36M 인덱스 (0 hip, 1 rhip, 2 rknee, 3 rankle, 4 lhip, 5 lknee, 6 lankle,
# 7 spine, 8 thorax, 9 nose, 10 head, 11 lsho, 12 lelb, 13 lwri, 14 rsho, 15 relb, 16 rwri)
MP_FROM_H36M = {0: 9, 11: 11, 12: 14, 13: 12, 14: 15, 15: 13, 16: 16,
                23: 4, 24: 1, 25: 5, 26: 2, 27: 6, 28: 3}


def load_labels(path: Path) -> list[dict]:
    rows = []
    with path.open(newline="") as handle:
        for line in csv.reader(handle):
            if line:
                rows.append({"start": int(line[0]), "end": int(line[1]), "reps": int(line[2]), "activity": line[3].strip()})
    return sorted(rows, key=lambda r: r["start"])


def landmarks(joints_mm: np.ndarray) -> list[tuple] | None:
    """joints_mm: (3, 17). 필요한 관절이 하나라도 비면 None(사람 없음 취급)."""
    if not np.all(np.isfinite(joints_mm[:, list(MP_FROM_H36M.values())])):
        return None
    out = []
    for i in range(LANDMARKS):
        j = MP_FROM_H36M.get(i)
        if j is None:
            out.append((0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
            continue
        ox, oy, oz = joints_mm[:, j]
        out.append((0.5, 0.5, 1.0, 1.0, ox / 1000.0, -oz / 1000.0, oy / 1000.0))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dataset_root", type=Path, help="wNN/ 폴더들이 있는 mm-fit 디렉터리")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--margin-frames", type=int, default=MARGIN_FRAMES,
                        help="세트 앞뒤 여유 프레임. 민감도 실험용 — 늘리면 카운터가 준비 자세를 볼 시간이 생긴다")
    args = parser.parse_args()
    margin = args.margin_frames

    index = {"source": "MM-Fit pose_3d (dataset's own 3D pose, not MediaPipe)", "fps": FPS,
             "marginFrames": margin, "cadenceMs": "app (>=300ms)", "sets": []}
    for workout_dir in sorted(p for p in args.dataset_root.iterdir() if p.is_dir()):
        w = workout_dir.name
        pose_path = workout_dir / f"{w}_pose_3d.npy"
        label_path = workout_dir / f"{w}_labels.csv"
        if not pose_path.is_file() or not label_path.is_file():
            continue
        pose = np.load(pose_path)
        frame_no = pose[0, :, 0].astype(int)
        joints = pose[:, :, 1:]
        row_of = {f: i for i, f in enumerate(frame_no)}
        for ordinal, label in enumerate(load_labels(label_path)):
            exercise = ACTIVITIES.get(label["activity"])
            if exercise is None:
                continue
            frames = [f for f in range(label["start"] - margin, label["end"] + margin + 1) if f in row_of]
            times = [int(round(f * 1000.0 / FPS)) for f in frames]
            chosen = app_cadence(times)
            lines = [frame_line(times[k], landmarks(joints[:, row_of[frames[k]], :])) for k in chosen]
            name = f"{w}/{w}_set{ordinal:02d}_{label['activity']}.cap"
            write_capture(args.out / name, {"source": "mmfit_pose3d", "workout": w, "activity": label["activity"],
                                            "startFrame": label["start"], "endFrame": label["end"]}, lines)
            index["sets"].append({"workout": w, "ordinal": ordinal, "activity": label["activity"], "exercise": exercise,
                                  "truthReps": label["reps"], "startFrame": label["start"], "endFrame": label["end"],
                                  "startMs": int(round(label["start"] * 1000.0 / FPS)), "endMs": int(round(label["end"] * 1000.0 / FPS)),
                                  "samples": len(lines), "capture": name})
    (args.out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(index['sets'])} sets -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
