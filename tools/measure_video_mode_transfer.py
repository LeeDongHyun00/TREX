"""Ask whether the shipped constants survive the inference mode the app actually runs.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT (non-commercial educational scope).

Why this exists
---------------
Every calibrated constant in this app was fitted with MediaPipe in IMAGE mode, one still at a
time, and `docs/pose-heuristic-form-check.v1.md` §4.1 records the gap that leaves open: "측정은
IMAGE 모드, 앱 런타임은 VIDEO 모드다." VIDEO mode is not the same estimator. It carries a tracker
between frames and smooths, so it trades per-frame independence for temporal stability — and the
statistic every threshold is fitted on is an *extreme* over a clip, which is exactly the kind of
statistic smoothing moves.

That gap was assumed to need a phone. It does not. The distribution archives turn out to hold
**thirty-two consecutive frames per clip per view**, of which the labels annotate every other one:
a clip is a short real video, not a bag of stills. Feeding those frames in order through VIDEO
mode is the runtime estimator running on real footage, which answers the mode question directly.

What it still does not answer, and no amount of this data will: studio capture is not a gym, and
frame rate and thermal behaviour belong to a device. Those remain phone work.

Method
------
For each clip the bridge plan already selects, this streams that clip's frames **in capture order**
— including the unlabelled even frames, because the temporal context is the point — and runs
`detect_for_video` with a synthetic 30fps clock. The clip statistic is then taken over the *same*
labelled frames the IMAGE run used, so the only thing that changed between the two numbers is the
inference mode.

A fresh landmarker per clip. Reusing one across clips would let the tracker carry a person from
the previous clip into the next, which would be a temporal artefact of the harness rather than of
the runtime.

Usage
-----
    python tools/measure_video_mode_transfer.py <label-root> \
        --archives <dir> [--archives <dir> ...] [--images <unpacked-root>] \
        --only "스탠딩 니업" --out docs/video-mode-transfer.v1.json
"""

from __future__ import annotations

import argparse
import json
import multiprocessing as mp_proc
import queue
import sys
import tarfile
import time
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from measure_mediapipe_aihub_bridge import (  # noqa: E402
    EXERCISE_PROFILES,
    MIN_POSE_DETECTION_CONFIDENCE,
    MIN_POSE_PRESENCE_CONFIDENCE,
    MIN_TRACKING_CONFIDENCE,
    MINIMUM_CHAIN_CONFIDENCE,
    NUM_POSES,
    _fit_threshold,
    _to_detector,
)
from measure_bridge_from_archives import (  # noqa: E402
    DEFAULT_MODEL,
    _member_key,
    _verify_model,
    archive_for_day,
    build_plan,
)

QUEUE_DEPTH = 16
POISON = None

# The dataset records no frame interval. Thirty frames a second is the ordinary capture rate and
# sits inside the range the app itself runs at; the assumption is stated in the artifact because
# the smoothing this measurement is about is a function of it.
ASSUMED_FRAME_INTERVAL_MS = 33


def _worker(
    model_path: str,
    chain_by_clip: dict[str, str],
    extreme_by_clip: dict[str, str],
    inbox: "mp_proc.Queue[Any]",
    outbox: "mp_proc.Queue[Any]",
) -> None:
    """Runs whole clips: one landmarker per clip, frames in order, VIDEO mode."""
    import cv2
    import numpy
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision

    from measure_mediapipe_aihub_bridge import CHAINS, _included_angle

    def chain_extreme(world: list[Any], chain: str, extreme: str) -> float | None:
        """Byte-for-byte the IMAGE tool's statistic, so only the inference mode differs."""
        angles = []
        for first_i, vertex_i, second_i in CHAINS[chain].values():
            points = [world[first_i], world[vertex_i], world[second_i]]
            if min(min(lm.visibility, lm.presence) for lm in points) < MINIMUM_CHAIN_CONFIDENCE:
                continue
            angle = _included_angle(*points)
            if angle is not None:
                angles.append(angle)
        if not angles:
            return None
        return min(angles) if extreme == "min" else max(angles)

    while True:
        item = inbox.get()
        if item is POISON:
            break
        clip_id, ordered = item
        chain = chain_by_clip[clip_id]
        extreme = extreme_by_clip[clip_id]
        landmarker = vision.PoseLandmarker.create_from_options(
            vision.PoseLandmarkerOptions(
                base_options=mp_python.BaseOptions(model_asset_path=model_path),
                running_mode=vision.RunningMode.VIDEO,
                num_poses=NUM_POSES,
                min_pose_detection_confidence=MIN_POSE_DETECTION_CONFIDENCE,
                min_pose_presence_confidence=MIN_POSE_PRESENCE_CONFIDENCE,
                min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
                output_segmentation_masks=False,
            )
        )
        measured: dict[str, float] = {}
        outcomes: Counter[str] = Counter()
        try:
            for index, (key, payload, wanted) in enumerate(ordered):
                buffer = numpy.frombuffer(payload, dtype=numpy.uint8)
                decoded = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
                if decoded is None:
                    outcomes["undecodable"] += 1
                    continue
                rgb = cv2.cvtColor(decoded, cv2.COLOR_BGR2RGB)
                image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                result = landmarker.detect_for_video(
                    image, index * ASSUMED_FRAME_INTERVAL_MS
                )
                if not wanted:
                    continue
                if not result.pose_world_landmarks:
                    outcomes["no_pose"] += 1
                    continue
                if len(result.pose_world_landmarks) > 1:
                    outcomes["ambiguous_multi_person"] += 1
                    continue
                angle = chain_extreme(result.pose_world_landmarks[0], chain, extreme)
                if angle is None:
                    outcomes["chain_below_confidence"] += 1
                    continue
                measured[key] = angle
                outcomes["single_pose"] += 1
        finally:
            landmarker.close()
        outbox.put((clip_id, measured, dict(outcomes)))


def run(
    label_root: Path,
    archive_roots: list[Path],
    model_path: Path,
    view_artifact: Path,
    workers: int,
    only: set[str] | None,
    extra_image_roots: list[Path],
) -> dict[str, Any]:
    model_sha = _verify_model(model_path)
    plan = build_plan(label_root, view_artifact, only)
    clips = plan["clips"]
    frames_by_day = plan["framesByDay"]
    print(f"plan: {len(clips)} clips over {len(frames_by_day)} capture days", file=sys.stderr)

    chain_by_clip = {cid: c["chain"] for cid, c in clips.items()}
    extreme_by_clip = {cid: c["extreme"] for cid, c in clips.items()}

    inbox: Any = mp_proc.Queue(maxsize=QUEUE_DEPTH)
    outbox: Any = mp_proc.Queue()
    procs = [
        mp_proc.Process(
            target=_worker,
            args=(str(model_path), chain_by_clip, extreme_by_clip, inbox, outbox),
            daemon=True,
        )
        for _ in range(workers)
    ]
    for p in procs:
        p.start()

    results: dict[str, dict[str, float]] = {}
    outcomes: Counter[str] = Counter()
    submitted = 0

    def drain(block: bool = False) -> None:
        nonlocal results
        while True:
            try:
                item = outbox.get(block=block, timeout=1.0) if block else outbox.get_nowait()
            except queue.Empty:
                return
            clip_id, measured, clip_outcomes = item
            results[clip_id] = measured
            outcomes.update(clip_outcomes)
            block = False

    archives = sorted(
        archive for root in archive_roots if root.is_dir() for archive in root.glob("*.tar")
    )
    day_cache: dict[str, str] = {}
    started = time.time()

    for day in sorted(frames_by_day):
        wanted_keys = frames_by_day[day]
        # A clip's frames are contiguous in the archive, so the whole sequence — labelled frames
        # and the unlabelled ones between them — is gathered before the clip is handed over.
        # The unlabelled frames are what make this VIDEO rather than a slower IMAGE run.
        prefixes: dict[str, str] = {}
        for key, clip_id in wanted_keys.items():
            prefixes[key.rsplit("/", 1)[0]] = clip_id
        gathered: dict[str, list[tuple[str, bytes, bool]]] = defaultdict(list)

        def offer(key: str, payload: bytes) -> None:
            directory = key.rsplit("/", 1)[0]
            clip_id = prefixes.get(directory)
            if clip_id is None:
                return
            gathered[clip_id].append((key, payload, key in wanted_keys))

        local_hits = 0
        for root in extra_image_roots:
            day_root = root / day
            if not day_root.is_dir():
                continue
            for directory in prefixes:
                folder = root / directory
                if not folder.is_dir():
                    continue
                for path in sorted(folder.iterdir()):
                    if path.suffix.lower() != ".jpg":
                        continue
                    offer(f"{directory}/{path.name}", path.read_bytes())
                    local_hits += 1
        if local_hits:
            print(f"{day}: {local_hits} frames from unpacked tree", file=sys.stderr, flush=True)

        if not gathered:
            archive = archive_for_day(archives, day, day_cache)
            if archive is None:
                outcomes["missing_archive"] += len(wanted_keys)
                print(f"{day}: NO ARCHIVE", file=sys.stderr, flush=True)
                continue
            t0 = time.time()
            with tarfile.open(archive, mode="r:") as tf:
                while True:
                    member = tf.next()
                    if member is None:
                        break
                    if not member.isfile():
                        continue
                    key = _member_key(member.name)
                    if key.rsplit("/", 1)[0] not in prefixes:
                        continue
                    handle = tf.extractfile(member)
                    if handle is None:
                        continue
                    offer(key, handle.read())
            print(
                f"{day}: gathered {sum(len(v) for v in gathered.values())} frames "
                f"from {archive.name} in {time.time() - t0:.0f}s",
                file=sys.stderr,
                flush=True,
            )

        for clip_id, frames in gathered.items():
            frames.sort(key=lambda row: row[0])
            inbox.put((clip_id, frames))
            submitted += 1
            drain()
        drain()
        print(
            f"{day}: {len(results)}/{submitted} clips done "
            f"({time.time() - started:.0f}s)",
            file=sys.stderr,
            flush=True,
        )

    for _ in procs:
        inbox.put(POISON)
    while len(results) < submitted:
        drain(block=True)
    for p in procs:
        p.join(timeout=30)

    # ---- fit, exactly as the IMAGE run does -----------------------------------------------
    rows_by_exercise: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for clip_id, measured in results.items():
        if not measured:
            continue
        clip = clips[clip_id]
        extreme = clip["extreme"]
        angles = list(measured.values())
        rows_by_exercise[clip["exercise"]].append(
            {
                "subject": clip["subject"],
                "label": clip["label"],
                "mediapipe": min(angles) if extreme == "min" else max(angles),
                "day": clip["day"],
            }
        )

    transfer: list[dict[str, Any]] = []
    for exercise in sorted(rows_by_exercise):
        rows = rows_by_exercise[exercise]
        profile = EXERCISE_PROFILES[exercise]
        positives = sum(1 for r in rows if r["label"])
        if positives in (0, len(rows)):
            continue
        direction = profile.direction
        subjects = sorted({r["subject"] for r in rows})
        folds: list[int] = []
        tp = fn = tn = fp = 0
        for subject in subjects:
            train = [
                (_to_detector(r["mediapipe"], direction), r["label"])
                for r in rows
                if r["subject"] != subject
            ]
            threshold = _fit_threshold(train)
            if threshold is None:
                continue
            folds.append(threshold)
            for r in rows:
                if r["subject"] != subject:
                    continue
                predicted = _to_detector(r["mediapipe"], direction) <= threshold
                if r["label"] and predicted:
                    tp += 1
                elif r["label"]:
                    fn += 1
                elif predicted:
                    fp += 1
                else:
                    tn += 1
        sensitivity = tp / (tp + fn) if tp + fn else None
        specificity = tn / (tn + fp) if tn + fp else None
        loso = (
            (sensitivity + specificity) / 2.0
            if sensitivity is not None and specificity is not None
            else None
        )
        pooled = _fit_threshold(
            [(_to_detector(r["mediapipe"], direction), r["label"]) for r in rows]
        )
        transfer.append(
            {
                "exercise": exercise,
                "chain": profile.chain,
                "extreme": profile.extreme,
                "conditionKind": profile.kind,
                "clipCount": len(rows),
                "subjectCount": len(subjects),
                "captureDays": sorted({r["day"] for r in rows}),
                "videoModeThresholdDegrees": (
                    None if pooled is None else (pooled if direction == "FLEXION" else 180 - pooled)
                ),
                "videoModeLosoBalancedAccuracy": None if loso is None else round(loso, 4),
                "losoThresholdMinDegrees": min(folds) if folds else None,
                "losoThresholdMaxDegrees": max(folds) if folds else None,
            }
        )

    return {
        "artifactKind": "TREX_VIDEO_MODE_TRANSFER",
        "artifactVersion": 1,
        "question": (
            "Whether constants fitted in MediaPipe IMAGE mode still separate their condition when "
            "the same footage is played through VIDEO mode, which is what the app runs."
        ),
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT",
        },
        "model": {"artifactSha256": model_sha},
        "method": {
            "runningMode": "VIDEO",
            "framesPerClip": "all frames in capture order; statistic taken over the labelled subset",
            "assumedFrameIntervalMs": ASSUMED_FRAME_INTERVAL_MS,
            "landmarkerLifetime": "one per clip, so no tracker state crosses a clip boundary",
            "generalisation": "LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT",
        },
        "detectionOutcomes": dict(outcomes),
        "transfer": transfer,
        "limitations": [
            "Studio capture: single lighting, standard clothing, uncluttered background. This "
            "measures the inference mode, not the environment.",
            "The frame interval is assumed rather than recorded, and VIDEO-mode smoothing depends "
            "on it.",
            "Says nothing about frame rate, thermal behaviour, or the per-frame copy path on a "
            "real device.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("label_root", type=Path)
    parser.add_argument("--archives", type=Path, action="append", required=True)
    parser.add_argument("--images", type=Path, action="append", default=None)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--only", action="append", default=None)
    args = parser.parse_args(argv)

    only = (
        {unicodedata.normalize("NFC", n).strip() for n in args.only} if args.only else None
    )
    if only:
        unknown = only - set(EXERCISE_PROFILES)
        if unknown:
            print(f"no profile for: {sorted(unknown)}", file=sys.stderr)
            return 1

    artifact = run(
        args.label_root,
        args.archives,
        args.model,
        REPO_ROOT / "docs" / "aihub-measurement-view.v1.json",
        args.workers,
        only,
        list(args.images or []),
    )
    args.out.write_text(
        json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"\nwrote {args.out}")
    print("outcomes:", artifact["detectionOutcomes"])
    for entry in artifact["transfer"]:
        print(
            f"\n{entry['exercise']}  {entry['chain']}/{entry['extreme']}  "
            f"clips={entry['clipCount']} subjects={entry['subjectCount']}"
        )
        print(
            f"   VIDEO threshold {entry['videoModeThresholdDegrees']}deg  "
            f"LOSO_bal={entry['videoModeLosoBalancedAccuracy']}  "
            f"folds={entry['losoThresholdMinDegrees']}-{entry['losoThresholdMaxDegrees']}"
        )
    return 0


if __name__ == "__main__":
    mp_proc.freeze_support()
    raise SystemExit(main())
