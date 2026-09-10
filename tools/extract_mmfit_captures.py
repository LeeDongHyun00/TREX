"""Turn MM-Fit RGB workouts into developer captures the form-check session can replay.

Why this exists
---------------
The repetition counter has never been measured against a repetition-level truth, because AI Hub
carries none: its clips are sixteen sparse keyframes with a clip-level scripted condition, so
"how many of the repetitions did the engine count" is unanswerable there and always will be
(`docs/pose-form-check-implementation-guide.v1.md` §2.3, §8).

MM-Fit answers exactly that question. Each workout is one continuous session -- three sets of ten
exercises, rest and walking between them, nothing trimmed away -- and its label file names, for
every set, `(start frame, end frame, repetition count, activity)`. Frames outside any labelled set
are the dataset's own `non_activity`, which is the negative material the roadmap requires a rep
counter to be measured against and which no scripted dataset provides.

What this tool does and does not claim
--------------------------------------
It runs the **model the app ships**, SHA-pinned, in the **mode the app runs** (VIDEO), with the
app's candidate count and confidences, and writes the world landmarks in the app's own capture
format. The counting itself happens later, in Kotlin, by replaying these captures through the real
`HeuristicFormCheckSession` -- so what gets measured is the shipped detector rather than a Python
restatement of it that could drift from it.

Three properties of MM-Fit bound what the resulting numbers may be used for, and they are written
into the artifact rather than left to the reader:

  * **The camera is not where the app asks the user to put it.** MM-Fit records from a fixed
    frontal RGB-D camera; the engine's preferred placement is lateral for all but two exercises.
    So this measures the counter in a placement the app would advise against -- a lower bound.
  * **The movements are not this project's movements.** MM-Fit's squats and lunges are bodyweight,
    its rows and presses are dumbbell; the nearest shipped specs were fitted for the barbell or
    machine variants. The mapping below is a proxy, exercise by exercise, and says so.
  * **Sets are labelled, repetitions are not.** The truth is a count per set, not a boundary per
    repetition, so a temporal rep F1 (±1s matching) cannot be computed from it. What can be
    computed is count error per set and false firing over `non_activity`, which is what the
    companion summary tool reports.

Nothing here fits a threshold or produces a calibration artifact. It measures a detector.

Rights: MM-Fit is CC BY 4.0 (Strömbäck, Huang and Radu, IMWUT 2020), so unlike the AI Hub archive
this material carries no non-commercial restriction. Attribution belongs on any surface that ships
a number derived from it.

Usage
-----
    python tools/extract_mmfit_captures.py data/mm-fit/mm-fit --videos data/mm-fit \
        --out data/mm-fit/captures --workouts w19

Writes, per workout:
  <out>/<workout>/<workout>_full.trexcap                    whole session, header exercise=SESSION
  <out>/<workout>/<workout>_set<NN>_<activity>.trexcap      one labelled set
  <out>/<workout>/<workout>_index.json                      set truth and per-frame outcomes
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import multiprocessing as mp_proc
import sys
import time
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent

# The app pins this file by SHA and every shipped constant was measured through it; a different
# model would make these counts a different engine's counts.
DEFAULT_MODEL = REPO_ROOT / "app" / "src" / "main" / "assets" / "pose_landmarker_full.task"
EXPECTED_MODEL_SHA256 = "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"

# PoseCameraConfig defaults, verbatim. num_poses is 2 rather than 1 on purpose: the app asks for a
# second candidate so it can refuse an ambiguous frame instead of locking onto whoever the model
# ranked first. MediaPipe only applies its own landmark smoothing at num_poses == 1, so both the
# app and this tool receive unsmoothed per-frame landmarks -- which is the state the shipped
# hysteresis and EMA constants were fitted over.
NUM_POSES = 2
MIN_POSE_DETECTION_CONFIDENCE = 0.5
MIN_POSE_PRESENCE_CONFIDENCE = 0.5
MIN_TRACKING_CONFIDENCE = 0.5

CAPTURE_MAGIC = "TREXCAP1"
JOINT_COUNT = 33
COORDINATE_SCALE = 100_000.0

# MM-Fit activity -> the nearest shipped FormCheckExercise, with the distance stated. "proxy" is
# the honest label for most of these: the movement is the same joint arc under a different load,
# and the shipped threshold was fitted (or defaulted) for the other one.
EXERCISE_MAP: dict[str, dict[str, str]] = {
    "squats": {
        "exercise": "BARBELL_SQUAT",
        "fidelity": "PROXY_UNLOADED",
        "note": "MM-Fit squats are bodyweight; the shipped spec is the barbell squat, whose "
        "110/105 degree lines are uncalibrated heuristics and whose hip definition gate at 130 "
        "was placed on loaded-squat label distributions.",
    },
    "lunges": {
        "exercise": "STEP_FORWARD_DYNAMIC_LUNGE",
        "fidelity": "PROXY_UNLOADED",
        "note": "MM-Fit does not say which lunge direction; the engine cannot separate forward "
        "from backward either (policy rule 5c), so the forward spec stands for both.",
    },
    "pushups": {
        "exercise": "PUSH_UP",
        "fidelity": "DIRECT",
        "note": "Same movement. The gravity clause is silent here because a downloaded video "
        "carries no device sensor, so the push-up's posture requirement abstains.",
    },
    "bicep_curls": {
        "exercise": "DUMBBELL_CURL",
        "fidelity": "DIRECT",
        "note": "MM-Fit curls are dumbbell and may alternate arms, which is why the dumbbell spec "
        "(no bilateral coherence) is the right one rather than the barbell curl.",
    },
    "dumbbell_shoulder_press": {
        "exercise": "OVERHEAD_PRESS",
        "fidelity": "PROXY_IMPLEMENT",
        "note": "Dumbbell press against a spec written for the barbell overhead press; the elbow "
        "extension arc is the same, the hand path is not.",
    },
    "dumbbell_rows": {
        "exercise": "DUMBBELL_BENT_OVER_ROW",
        "fidelity": "DIRECT",
        "note": "MM-Fit calls these standing dumbbell rows.",
    },
    "lateral_shoulder_raises": {
        "exercise": "SIDE_LATERAL_RAISE",
        "fidelity": "DIRECT",
        "note": "One of the two shipped exercises whose preferred view is frontal, so MM-Fit's "
        "camera placement is the recommended one here rather than a penalty.",
    },
    "tricep_extensions": {
        "exercise": "LYING_TRICEPS_EXTENSION",
        "fidelity": "PROXY_POSTURE",
        "note": "MM-Fit performs these standing or seated with a dumbbell; the shipped spec is the "
        "lying variant. Same elbow extension, different body orientation -- the weakest mapping "
        "here, and reported separately for that reason.",
    },
    # Deliberately unmapped. They are not silently dropped: they are the strongest negatives in
    # the corpus, because a rep counter that fires on them is firing on a movement the user did
    # not select.
    "situps": {"exercise": "", "fidelity": "UNMAPPED_NEGATIVE", "note": "No shipped counterpart."},
    "jumping_jacks": {
        "exercise": "",
        "fidelity": "UNMAPPED_NEGATIVE",
        "note": "No shipped counterpart.",
    },
}


def verify_model(model_path: Path) -> str:
    """Refuses to run against anything but the pinned model."""
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if digest != EXPECTED_MODEL_SHA256:
        raise SystemExit(
            f"model SHA-256 does not match the app pin\n  expected={EXPECTED_MODEL_SHA256}\n"
            f"  actual  ={digest}"
        )
    return digest


def load_labels(path: Path) -> list[dict[str, Any]]:
    """MM-Fit label rows: start frame, end frame, repetition count, activity."""
    rows: list[dict[str, Any]] = []
    with path.open("r", newline="") as handle:
        for line in csv.reader(handle):
            if not line:
                continue
            rows.append(
                {
                    "startFrame": int(line[0]),
                    "endFrame": int(line[1]),
                    "repetitions": int(line[2]),
                    "activity": line[3].strip(),
                }
            )
    rows.sort(key=lambda row: row["startFrame"])
    return rows


def _claimed_frames(labels: list[dict[str, Any]], margin_frames: int) -> set[int]:
    """Every frame a labelled set claims, margin included."""
    claimed: set[int] = set()
    for label in labels:
        claimed.update(
            range(label["startFrame"] - margin_frames, label["endFrame"] + margin_frames + 1)
        )
    return claimed


def _rest_gaps(frame_of_line: list[int], claimed: set[int]) -> list[list[int]]:
    """The unclaimed frames, split into the contiguous gaps they actually form.

    Emitted one gap per file rather than as a single rest capture, because concatenating gaps
    would put a jump of several minutes between two adjacent frames of one replayed session. The
    detector's smoothing weights a sample by the time since the last one, so across such a jump it
    snaps -- and an excursion left in flight before the jump could be completed by the frame after
    it, manufacturing exactly the false positive this file exists to count. One session per gap
    keeps every measured false positive attributable to movement rather than to the cut.
    """
    gaps: list[list[int]] = []
    current: list[int] = []
    previous: int | None = None
    for index, frame in enumerate(frame_of_line):
        if frame in claimed:
            continue
        if previous is not None and frame != previous + 1:
            if current:
                gaps.append(current)
            current = []
        current.append(index)
        previous = frame
    if current:
        gaps.append(current)
    return gaps


def _round(value: float) -> float:
    return round(value * COORDINATE_SCALE) / COORDINATE_SCALE


def encode_frame(
    timestamp_ms: int,
    has_lock: bool,
    preferred_view: bool,
    world_landmarks: Any,
) -> str:
    """One capture line, in `PoseCaptureCodec`'s format."""
    fields = [
        "F",
        str(timestamp_ms),
        "1" if has_lock else "0",
        "1" if preferred_view else "0",
    ]
    if world_landmarks is not None:
        for index, landmark in enumerate(world_landmarks):
            if index >= JOINT_COUNT:
                break
            visibility = getattr(landmark, "visibility", None)
            presence = getattr(landmark, "presence", None)
            # A landmark whose confidence the model declined to state is written as absent rather
            # than as a confident coordinate: the session's abstention rules must see the gap.
            if visibility is None or presence is None:
                continue
            fields.append(
                f"{index}:{_round(landmark.x)},{_round(landmark.y)},{_round(landmark.z)},"
                f"{_round(visibility)},{_round(presence)}"
            )
    return "\t".join(fields)


# Frames a worker infers over before its segment begins, to be discarded. VIDEO mode carries a
# tracker between frames, so a worker that started cold at its own first frame would report a
# detection state no continuous run would ever produce. Half a second of warm-up is what the
# tracker needs to be indistinguishable from a run that reached that frame the long way.
WARMUP_FRAMES = 15


def _run_segment(job: dict[str, Any]) -> tuple[int, list[tuple[int, str]], dict[str, int]]:
    """Infers one contiguous frame range. Returns (segment index, encoded lines, outcomes)."""
    import cv2  # noqa: PLC0415 -- imported in the worker so the parent needs no GPU/TFLite state
    import mediapipe as mp  # noqa: PLC0415
    from mediapipe.tasks import python as mp_python  # noqa: PLC0415
    from mediapipe.tasks.python import vision  # noqa: PLC0415

    capture = cv2.VideoCapture(job["video"])
    if not capture.isOpened():
        raise RuntimeError(f"cannot open {job['video']}")
    warm_start = max(0, job["start"] - WARMUP_FRAMES)
    capture.set(cv2.CAP_PROP_POS_FRAMES, warm_start)

    landmarker = vision.PoseLandmarker.create_from_options(
        vision.PoseLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=job["model"]),
            running_mode=vision.RunningMode.VIDEO,
            num_poses=NUM_POSES,
            min_pose_detection_confidence=MIN_POSE_DETECTION_CONFIDENCE,
            min_pose_presence_confidence=MIN_POSE_PRESENCE_CONFIDENCE,
            min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
        )
    )

    fps = job["fps"]
    outcomes = {"single_pose": 0, "no_pose": 0, "ambiguous_multi_person": 0}
    lines: list[tuple[int, str]] = []
    frame_index = warm_start
    while frame_index < job["end"]:
        ok, frame_bgr = capture.read()
        if not ok:
            break
        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        # The video frame index is the label's frame index: MM-Fit's label, pose and video streams
        # are the same camera stream, and the alignment is asserted against the pose array's own
        # frame column before any of this is believed.
        timestamp_ms = int(round(frame_index * 1000.0 / fps))
        result = landmarker.detect_for_video(
            mp.Image(image_format=mp.ImageFormat.SRGB, data=frame_rgb), timestamp_ms
        )

        if frame_index >= job["start"]:
            candidates = len(result.pose_landmarks)
            if candidates == 0:
                outcomes["no_pose"] += 1
                has_lock = False
                world = None
            elif candidates > 1:
                # The runtime clears the person lock rather than guessing which body is the user's,
                # and a cleared lock discards the excursion in flight. Reproduced here so the
                # replay sees the same discard.
                outcomes["ambiguous_multi_person"] += 1
                has_lock = False
                world = None
            else:
                outcomes["single_pose"] += 1
                has_lock = True
                world = result.pose_world_landmarks[0]

            # Never claimed. The observer decides view qualification from image landmarks and a
            # geometry contract this tool does not reproduce; the flag drives a placement hint and
            # changes no count, so leaving it false states the ignorance rather than inventing a
            # token.
            lines.append((frame_index, encode_frame(timestamp_ms, has_lock, False, world)))
        frame_index += 1

    capture.release()
    landmarker.close()
    return job["index"], lines, outcomes


def run_workout(
    workout: str,
    label_path: Path,
    video_path: Path,
    model_path: Path,
    out_root: Path,
    progress_every: int,
    workers: int,
    margin_frames: int,
) -> dict[str, Any]:
    import cv2  # noqa: PLC0415 -- imported late so --help works without the measurement venv

    labels = load_labels(label_path)
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
    started = time.time()
    done_segments = 0
    with mp_proc.Pool(processes=max(workers, 1)) as pool:
        for index, segment_lines, segment_outcomes in pool.imap_unordered(_run_segment, jobs):
            collected[index] = segment_lines
            for key, value in segment_outcomes.items():
                outcomes[key] += value
            done_segments += 1
            if progress_every:
                seen = sum(len(value) for value in collected.values())
                elapsed = max(time.time() - started, 1e-6)
                print(
                    f"  {workout} segment {done_segments}/{len(jobs)} "
                    f"{seen}/{frame_total} frames ({seen / elapsed:.1f} fps) {outcomes}",
                    file=sys.stderr,
                    flush=True,
                )

    ordered = [entry for index in sorted(collected) for entry in collected[index]]
    frame_of_line = [frame for frame, _ in ordered]
    lines = [line for _, line in ordered]
    if frame_of_line != sorted(frame_of_line):
        raise SystemExit("segments did not reassemble in frame order")

    out_dir = out_root / workout
    out_dir.mkdir(parents=True, exist_ok=True)

    def write_capture(path: Path, exercise_id: str, selected: list[int]) -> int:
        header = f"{CAPTURE_MAGIC}\texercise={exercise_id}\tjoints={JOINT_COUNT}"
        with path.open("w", newline="\n") as handle:
            handle.write(header + "\n")
            for line_index in selected:
                handle.write(lines[line_index] + "\n")
        return len(selected)

    # The whole session, including every rest gap and every exercise the user did not select. This
    # is the file the negative measurement replays.
    write_capture(out_dir / f"{workout}_full.trexcap", "SESSION", list(range(len(lines))))

    claimed = _claimed_frames(labels, margin_frames)
    rest_gaps = _rest_gaps(frame_of_line, claimed)
    for ordinal, gap in enumerate(rest_gaps):
        write_capture(out_dir / f"{workout}_rest{ordinal:02d}.trexcap", "SESSION", gap)

    sets: list[dict[str, Any]] = []
    for ordinal, label in enumerate(labels):
        mapping = EXERCISE_MAP.get(label["activity"], {})
        exercise_id = mapping.get("exercise", "")
        # Cross-correlating a MediaPipe hip trajectory against MM-Fit's own 2D pose over the same
        # frame indices peaks at a lag of four to five frames, not zero: the label and video
        # streams are the same recording but not the same clock to the frame. A symmetric margin
        # absorbs that rather than pretending the offset is known exactly, and it is safe to spend
        # because sets are separated by whole rest periods -- there is no neighbouring repetition
        # for half a second of slack to reach.
        selected = [
            index
            for index, frame in enumerate(frame_of_line)
            if label["startFrame"] - margin_frames <= frame <= label["endFrame"] + margin_frames
        ]
        name = f"{workout}_set{ordinal:02d}_{label['activity']}.trexcap"
        # An unmapped activity still gets a capture written, under the session header: the
        # negative measurement needs its frames, and naming it after an exercise it is not would
        # be the one thing this file must not do.
        write_capture(out_dir / name, exercise_id or "SESSION", selected)
        sets.append(
            {
                "ordinal": ordinal,
                "activity": label["activity"],
                "labelledRepetitions": label["repetitions"],
                "startFrame": label["startFrame"],
                "endFrame": label["endFrame"],
                "frames": len(selected),
                "marginFrames": margin_frames,
                "capture": name,
                "exercise": exercise_id,
                "fidelity": mapping.get("fidelity", "UNMAPPED_NEGATIVE"),
                "mappingNote": mapping.get("note", ""),
            }
        )

    index = {
        "artifactKind": "TREX_MMFIT_CAPTURE_INDEX",
        "restFrames": sum(len(gap) for gap in rest_gaps),
        "restGaps": len(rest_gaps),
        "artifactVersion": 1,
        "workout": workout,
        "video": {
            "path": str(video_path),
            "frames": frame_total,
            "decodedFrames": len(lines),
            "fps": fps,
            "width": width,
            "height": height,
        },
        "model": {"sha256": EXPECTED_MODEL_SHA256, "runningMode": "VIDEO", "numPoses": NUM_POSES},
        "detectionOutcomes": outcomes,
        "sets": sets,
        "source": {
            "dataset": "MM-Fit",
            "citation": "Strömbäck, Huang and Radu, IMWUT 2020, doi:10.1145/3432701",
            "licence": "CC BY 4.0",
        },
    }
    (out_dir / f"{workout}_index.json").write_text(
        json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    return index


def reslice_workout(
    workout: str,
    label_path: Path,
    out_root: Path,
    margin_frames: int,
) -> dict[str, Any]:
    """Rebuilds the per-set and rest captures from an existing session capture.

    Slicing is free and inference is not, so a change to how the corpus is cut -- a different
    margin, a new negative file -- must not cost another pass over the video. The session capture
    is the record of what the model saw; everything else is a view of it.
    """
    out_dir = out_root / workout
    session_path = out_dir / f"{workout}_full.trexcap"
    index_path = out_dir / f"{workout}_index.json"
    if not session_path.is_file() or not index_path.is_file():
        raise SystemExit(f"{workout}: no session capture to re-slice")

    index = json.loads(index_path.read_text(encoding="utf-8"))
    fps = index["video"]["fps"]
    lines = [
        line
        for line in session_path.read_text(encoding="utf-8").splitlines()[1:]
        if line.startswith("F\t")
    ]
    # The frame index is recoverable from the timestamp the same way it was written, so the
    # session capture stays the single record and no sidecar has to agree with it.
    frame_of_line = [int(round(int(line.split("\t")[1]) * fps / 1000.0)) for line in lines]

    def write_capture(path: Path, exercise_id: str, selected: list[int]) -> None:
        header = f"{CAPTURE_MAGIC}\texercise={exercise_id}\tjoints={JOINT_COUNT}"
        with path.open("w", newline="\n") as handle:
            handle.write(header + "\n")
            for line_index in selected:
                handle.write(lines[line_index] + "\n")

    labels = load_labels(label_path)
    claimed = _claimed_frames(labels, margin_frames)
    sets: list[dict[str, Any]] = []
    for ordinal, label in enumerate(labels):
        mapping = EXERCISE_MAP.get(label["activity"], {})
        exercise_id = mapping.get("exercise", "")
        selected = [
            i
            for i, frame in enumerate(frame_of_line)
            if label["startFrame"] - margin_frames <= frame <= label["endFrame"] + margin_frames
        ]
        name = f"{workout}_set{ordinal:02d}_{label['activity']}.trexcap"
        write_capture(out_dir / name, exercise_id or "SESSION", selected)
        sets.append(
            {
                "ordinal": ordinal,
                "activity": label["activity"],
                "labelledRepetitions": label["repetitions"],
                "startFrame": label["startFrame"],
                "endFrame": label["endFrame"],
                "frames": len(selected),
                "marginFrames": margin_frames,
                "capture": name,
                "exercise": exercise_id,
                "fidelity": mapping.get("fidelity", "UNMAPPED_NEGATIVE"),
                "mappingNote": mapping.get("note", ""),
            }
        )

    rest_gaps = _rest_gaps(frame_of_line, claimed)
    for path in out_dir.glob(f"{workout}_rest*.trexcap"):
        path.unlink()
    for ordinal, gap in enumerate(rest_gaps):
        write_capture(out_dir / f"{workout}_rest{ordinal:02d}.trexcap", "SESSION", gap)

    index["sets"] = sets
    index["restFrames"] = sum(len(gap) for gap in rest_gaps)
    index["restGaps"] = len(rest_gaps)
    index_path.write_text(json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8")
    return index


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_root", type=Path, help="directory holding wNN/ label folders")
    parser.add_argument("--videos", type=Path, required=True, help="directory holding wNN_rgb.mp4")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--workouts", nargs="*", default=None, help="default: every video present")
    parser.add_argument("--progress-every", type=int, default=1)
    parser.add_argument("--workers", type=int, default=max(1, mp_proc.cpu_count() - 2))
    parser.add_argument(
        "--set-margin-frames",
        type=int,
        default=15,
        help="frames added either side of a labelled set, absorbing the measured label/video "
        "clock offset (default 15, half a second at 30fps)",
    )
    parser.add_argument(
        "--reslice-only",
        action="store_true",
        help="rebuild set and rest captures from existing session captures, without inference",
    )
    args = parser.parse_args(argv)

    if not args.reslice_only:
        verify_model(args.model)

    workouts = args.workouts
    if not workouts:
        workouts = sorted(path.name[:3] for path in args.videos.glob("w??_rgb.mp4"))
    if not workouts:
        raise SystemExit(f"no wNN_rgb.mp4 under {args.videos}")

    for workout in workouts:
        label_path = args.dataset_root / workout / f"{workout}_labels.csv"
        video_path = args.videos / f"{workout}_rgb.mp4"
        if not label_path.is_file():
            print(f"skipping {workout}: no labels", file=sys.stderr)
            continue
        if not video_path.is_file():
            print(f"skipping {workout}: no video", file=sys.stderr)
            continue
        print(f"== {workout}", file=sys.stderr, flush=True)
        if args.reslice_only:
            index = reslice_workout(workout, label_path, args.out, args.set_margin_frames)
            print(
                f"   re-sliced {len(index['sets'])} sets, {index['restFrames']} rest frames",
                file=sys.stderr,
                flush=True,
            )
            continue
        index = run_workout(
            workout,
            label_path,
            video_path,
            args.model,
            args.out,
            args.progress_every,
            args.workers,
            args.set_margin_frames,
        )
        print(
            f"   {index['video']['decodedFrames']} frames, {len(index['sets'])} sets, "
            f"outcomes={index['detectionOutcomes']}",
            file=sys.stderr,
            flush=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
