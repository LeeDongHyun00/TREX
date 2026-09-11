"""Turn REHAB24-6 recordings into captures, keeping each repetition's own boundary.

Why this exists alongside the MM-Fit extractor
----------------------------------------------
MM-Fit answered "how many repetitions were in that set" and could not answer "was that the same
repetition". Its labels are set-level, so the count-F1 it supports is an upper bound on the
boundary-matched rep F1 the roadmap's SL-1 gate actually asks for: a counter that fires the right
number of times at the wrong moments scores perfectly on counts and fails on boundaries.

REHAB24-6 closes that gap. Its `Segmentation.csv` names, for every one of 1,072 repetitions, the
first and last frame of that repetition -- plus who performed it, whether it was performed
correctly, and which way the camera was facing. That last column is what makes it worth the trouble
beyond the boundaries themselves: 107 of the repetitions were filmed in **profile**, which is the
placement this engine asks users for and which MM-Fit's fixed frontal camera never provided.

Rights, and what may be built on this
-------------------------------------
REHAB24-6 is **CC BY-NC 4.0**. Non-commercial. It sits in the same class as the AI Hub archive:
statistics and measurement are permitted, and no threshold this repository ships may be fitted from
it. This tool measures a detector and produces no calibration artifact, which is the only use the
licence and this project's own rules both allow.

Citation: REHAB24-6, Zenodo record 13305826.

Usage
-----
    python tools/extract_rehab_captures.py data/rehab24-6 --out data/rehab24-6/captures

Writes, per video:
  <out>/<video>/<video>_full.trexcap            the whole recording, header exercise=SESSION
  <out>/<video>/<video>_index.json              per-repetition truth and detection outcomes
"""

from __future__ import annotations

import argparse
import csv
import json
import multiprocessing as mp_proc
import sys
import time
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from extract_mmfit_captures import (  # noqa: E402
    CAPTURE_MAGIC,
    DEFAULT_MODEL,
    JOINT_COUNT,
    _run_segment,
    verify_model,
)

# REHAB24-6 exercise ids, and the nearest shipped spec. Two of the six have no counterpart at all;
# they are kept because a repetition counter that fires on a movement the user did not select is
# exactly what the unmapped rows measure.
EXERCISE_MAP: dict[int, dict[str, str]] = {
    1: {
        "name": "arm abduction",
        "exercise": "SIDE_LATERAL_RAISE",
        "fidelity": "PROXY_UNILATERAL",
        "note": "Performed with the right arm only. The shipped lateral raise requires both sides "
        "to agree, so this mapping is expected to abstain rather than count -- it is included to "
        "measure that, not to flatter it.",
    },
    2: {
        "name": "arm VW",
        "exercise": "",
        "fidelity": "UNMAPPED_NEGATIVE",
        "note": "No shipped counterpart.",
    },
    3: {
        "name": "table push-up",
        "exercise": "PUSH_UP",
        "fidelity": "PROXY_INCLINE",
        "note": "Hands on a table rather than the floor, so the trunk sits far more upright than "
        "the floor push-up the sixty-degree gravity bound was placed for. Where a gravity reading "
        "is supplied this mapping should fail the posture clause, and that is the correct "
        "behaviour rather than a miss.",
    },
    4: {
        "name": "leg abduction",
        "exercise": "",
        "fidelity": "UNMAPPED_NEGATIVE",
        "note": "No shipped counterpart.",
    },
    5: {
        "name": "lunge",
        "exercise": "STEP_FORWARD_DYNAMIC_LUNGE",
        "fidelity": "PROXY_UNLOADED",
        "note": "Bodyweight lunge against the forward dynamic lunge spec; the engine does not "
        "separate lunge directions in any case (policy rule 5c).",
    },
    6: {
        "name": "squat",
        "exercise": "BARBELL_SQUAT",
        "fidelity": "PROXY_UNLOADED",
        "note": "Bodyweight squat against the barbell squat spec, whose 110/105 degree lines are "
        "uncalibrated heuristics.",
    },
}


def load_segmentation(path: Path) -> dict[str, list[dict[str, Any]]]:
    """Repetition rows grouped by video, each with its own first and last frame."""
    by_video: dict[str, list[dict[str, Any]]] = {}
    with path.open("r", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            entry = {
                "repetition": int(row["repetition_number"]),
                "exerciseId": int(row["exercise_id"]),
                "personId": int(row["person_id"]),
                "firstFrame": int(row["first_frame"]),
                "lastFrame": int(row["last_frame"]),
                "correct": row["correctness"] == "1",
                "subtype": row["exercise_subtype"],
                "cam17Orientation": row["cam17_orientation"],
                "mocapErroneous": row["mocap_erroneous"] == "1",
                "extraPersonCam17": int(row["extra_person_in_cam17"] or 0),
                "extraPersonCam18": int(row["extra_person_in_cam18"] or 0),
            }
            by_video.setdefault(row["video_id"], []).append(entry)
    for rows in by_video.values():
        rows.sort(key=lambda row: row["firstFrame"])
    return by_video


def run_video(
    video_id: str,
    camera: str,
    video_path: Path,
    repetitions: list[dict[str, Any]],
    model_path: Path,
    out_root: Path,
    pool: Any,
    workers: int,
) -> dict[str, Any]:
    import cv2  # noqa: PLC0415

    probe = cv2.VideoCapture(str(video_path))
    if not probe.isOpened():
        raise SystemExit(f"cannot open {video_path}")
    fps = probe.get(cv2.CAP_PROP_FPS) or 30.0
    frame_total = int(probe.get(cv2.CAP_PROP_FRAME_COUNT))
    width = int(probe.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(probe.get(cv2.CAP_PROP_FRAME_HEIGHT))
    probe.release()

    span = max(1, -(-frame_total // max(workers, 1)))
    jobs = [
        {
            "index": index,
            "video": str(video_path),
            "model": str(model_path),
            "fps": fps,
            "start": start,
            "end": min(start + span, frame_total),
        }
        for index, start in enumerate(range(0, frame_total, span))
    ]

    outcomes = {"single_pose": 0, "no_pose": 0, "ambiguous_multi_person": 0}
    collected: dict[int, list[tuple[int, str]]] = {}
    # One pool for the whole corpus rather than one per video. Sixty-five recordings would
    # otherwise pay for sixty-five cold starts, and a worker's cold start here is a MediaPipe and
    # OpenCV import plus a model load -- which measured longer than the inference it then did.
    for index, lines, segment_outcomes in pool.imap_unordered(_run_segment, jobs):
        collected[index] = lines
        for key, value in segment_outcomes.items():
            outcomes[key] += value

    ordered = [entry for index in sorted(collected) for entry in collected[index]]
    if [frame for frame, _ in ordered] != sorted(frame for frame, _ in ordered):
        raise SystemExit("segments did not reassemble in frame order")

    out_dir = out_root / f"{video_id}_{camera}"
    out_dir.mkdir(parents=True, exist_ok=True)
    header = f"{CAPTURE_MAGIC}\texercise=SESSION\tjoints={JOINT_COUNT}"
    with (out_dir / f"{video_id}_{camera}_full.trexcap").open("w", newline="\n") as handle:
        handle.write(header + "\n")
        for _, line in ordered:
            handle.write(line + "\n")

    # The whole recording is one exercise performed repeatedly, so the capture is not sliced. The
    # repetitions are carried as truth beside it, in milliseconds, because that is the axis the
    # replay reports its own repetitions on.
    exercise_ids = {row["exerciseId"] for row in repetitions}
    index = {
        "artifactKind": "TREX_REHAB_CAPTURE_INDEX",
        "artifactVersion": 1,
        "video": {
            "id": video_id,
            "camera": camera,
            "path": str(video_path),
            "frames": frame_total,
            "decodedFrames": len(ordered),
            "fps": fps,
            "width": width,
            "height": height,
        },
        "exerciseIds": sorted(exercise_ids),
        "mapping": {
            str(exercise_id): EXERCISE_MAP.get(exercise_id, {})
            for exercise_id in sorted(exercise_ids)
        },
        "detectionOutcomes": outcomes,
        "repetitions": [
            {
                **row,
                "firstMs": int(round(row["firstFrame"] * 1000.0 / fps)),
                "lastMs": int(round(row["lastFrame"] * 1000.0 / fps)),
                "exercise": EXERCISE_MAP.get(row["exerciseId"], {}).get("exercise", ""),
                "fidelity": EXERCISE_MAP.get(row["exerciseId"], {}).get(
                    "fidelity", "UNMAPPED_NEGATIVE"
                ),
            }
            for row in repetitions
        ],
        "source": {
            "dataset": "REHAB24-6",
            "citation": "Zenodo record 13305826",
            "licence": "CC BY-NC 4.0",
            "usage": "MEASUREMENT_ONLY_NO_THRESHOLD_MAY_BE_FITTED",
        },
    }
    (out_dir / f"{video_id}_{camera}_index.json").write_text(
        json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    return index


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_root", type=Path, help="directory holding Segmentation.csv")
    parser.add_argument("--videos", type=Path, default=None, help="default: <root>/videos")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--cameras", nargs="*", default=["17", "18"])
    parser.add_argument("--videos-filter", nargs="*", default=None)
    parser.add_argument("--workers", type=int, default=max(1, mp_proc.cpu_count() - 2))
    args = parser.parse_args(argv)

    verify_model(args.model)
    video_root = args.videos or (args.dataset_root / "videos")
    by_video = load_segmentation(args.dataset_root / "Segmentation.csv")

    started = time.time()
    pool = mp_proc.Pool(processes=max(args.workers, 1))
    for video_id, repetitions in sorted(by_video.items()):
        if args.videos_filter and video_id not in args.videos_filter:
            continue
        for camera in args.cameras:
            candidates = list(video_root.glob(f"**/{video_id}*{camera}*.mp4"))
            if not candidates:
                print(f"skipping {video_id} cam{camera}: no video", file=sys.stderr)
                continue
            print(f"== {video_id} cam{camera}", file=sys.stderr, flush=True)
            out_dir = args.out / f"{video_id}_{camera}"
            if (out_dir / f"{video_id}_{camera}_index.json").is_file():
                print("   already extracted", file=sys.stderr, flush=True)
                continue
            index = run_video(
                video_id,
                camera,
                candidates[0],
                repetitions,
                args.model,
                args.out,
                pool,
                args.workers,
            )
            print(
                f"   {index['video']['decodedFrames']} frames, "
                f"{len(index['repetitions'])} repetitions, "
                f"outcomes={index['detectionOutcomes']} "
                f"({time.time() - started:.0f}s elapsed)",
                file=sys.stderr,
                flush=True,
            )
    pool.close()
    pool.join()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
