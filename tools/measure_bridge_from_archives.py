"""Measure the MediaPipe-to-AI Hub bridge by reading the distribution archives in place.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT (non-commercial educational scope).

Why this exists alongside `measure_mediapipe_aihub_bridge.py`
------------------------------------------------------------
That tool reads unpacked image trees. Most of this dataset is not unpacked and cannot be: the raw
imagery ships as ~700 GB of tar archives and the working disk has ~20 GB free. Widening the
calibration beyond the three unpacked capture days therefore means reading the archives directly.

Three properties make that fast rather than merely possible:

  * **One archive holds one capture day.** So the day an archive serves is known after a single
    header read, and archives no exercise needs are never opened.
  * **A tar is walked once, in order.** Headers are read sequentially and only the members the
    measurement actually needs are pulled from the stream. Roughly one frame in five is wanted --
    five camera views are stored and a threshold is fitted from one -- so most of each archive is
    skipped without reading its bytes.
  * **Decode and inference are the bottleneck, not I/O.** The archive walk feeds a bounded queue of
    worker processes, so the disk streams while every core infers.

The statistic, the confidence gate, the view selection and the fitting are identical to the
unpacked-tree tool; this file changes where the pixels come from, not what is computed from them.
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
    CHAINS,
    CONTAMINATED_TYPE_CODES,
    EXERCISE_PROFILES,
    EXPECTED_MODEL_SHA256,
    MIN_POSE_DETECTION_CONFIDENCE,
    MIN_POSE_PRESENCE_CONFIDENCE,
    MIN_TRACKING_CONFIDENCE,
    MINIMUM_CHAIN_CONFIDENCE,
    NUM_POSES,
    QUARANTINED_CAPTURE_DAYS,
    SUBJECT_PATTERN,
    _balanced_accuracy,
    _confusion,
    _fit_threshold,
    _included_angle,
    _label_angle,
    _label_sides,
    _to_detector,
)

DEFAULT_MODEL = REPO_ROOT / "app" / "src" / "main" / "assets" / "pose_landmarker_full.task"

# A frame that no view selection wants is never decoded, so the queue only ever carries work.
QUEUE_DEPTH = 256
POISON = None


def _member_key(name: str) -> str:
    """The dataset-relative key a member holds.

    The archives were not packed uniformly: some store `Day41_.../1/A/...` and others
    `./Day41_.../1/A/...`. Without stripping that prefix the capture day of an entire archive reads
    as "." and none of its frames match, which is a silent loss of a whole shoot rather than an
    error.
    """
    return name[2:] if name.startswith("./") else name


def _verify_model(model_path: Path) -> str:
    import hashlib

    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if digest != EXPECTED_MODEL_SHA256:
        raise SystemExit(
            f"model SHA-256 does not match the app pin\n  expected={EXPECTED_MODEL_SHA256}\n"
            f"  actual  ={digest}"
        )
    return digest


def _worker(
    model_path: str,
    chain_by_key: dict[str, str],
    extreme_by_key: dict[str, str],
    inbox: "mp_proc.Queue[Any]",
    outbox: "mp_proc.Queue[Any]",
) -> None:
    """Decodes and measures one frame at a time until the archive walk says stop."""
    import cv2
    import numpy
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision

    landmarker = vision.PoseLandmarker.create_from_options(
        vision.PoseLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=model_path),
            running_mode=vision.RunningMode.IMAGE,
            num_poses=NUM_POSES,
            min_pose_detection_confidence=MIN_POSE_DETECTION_CONFIDENCE,
            min_pose_presence_confidence=MIN_POSE_PRESENCE_CONFIDENCE,
            min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
            output_segmentation_masks=False,
        )
    )

    def chain_sides(world: list[Any], chain: str) -> dict[str, float]:
        """Each credible side's included angle, keyed by side name.

        Kept per side rather than reduced here: the collapsed extreme answers the threshold
        question the same as before, but a same-side pairing against the label needs to know
        WHICH side each reading came from — collapsing Left and Right with a min inside the
        worker bakes one min-over-two of noise into every "per-frame" figure downstream.
        """
        sides: dict[str, float] = {}
        for side, (first_i, vertex_i, second_i) in CHAINS[chain].items():
            points = [world[first_i], world[vertex_i], world[second_i]]
            if min(min(lm.visibility, lm.presence) for lm in points) < MINIMUM_CHAIN_CONFIDENCE:
                continue
            angle = _included_angle(*points)
            if angle is not None:
                sides[side] = angle
        return sides

    def chain_extreme(sides: dict[str, float], extreme: str) -> float | None:
        if not sides:
            return None
        return min(sides.values()) if extreme == "min" else max(sides.values())

    while True:
        item = inbox.get()
        if item is POISON:
            break
        clip_id, img_key, payload = item
        outcome = "single_pose"
        angle = None
        sides: dict[str, float] = {}
        try:
            image = cv2.imdecode(numpy.frombuffer(payload, dtype=numpy.uint8), cv2.IMREAD_COLOR)
            if image is None:
                outcome = "unreadable_image"
            else:
                rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
                result = landmarker.detect(
                    mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                )
                candidates = len(result.pose_landmarks)
                if candidates == 0:
                    outcome = "no_pose"
                elif candidates > 1:
                    # The runtime clears the person lock here and abstains, so this frame is not
                    # evidence about anybody.
                    outcome = "ambiguous_multi_person"
                else:
                    sides = chain_sides(result.pose_world_landmarks[0], chain_by_key[clip_id])
                    angle = chain_extreme(sides, extreme_by_key[clip_id])
                    if angle is None:
                        outcome = "chain_below_confidence"
        except Exception:  # a single bad frame must not end the run
            outcome = "unreadable_image"
        outbox.put((clip_id, img_key, outcome, angle, sides))

    landmarker.close()
    outbox.put(POISON)


def build_plan(label_root: Path, view_artifact: Path, only: set[str] | None) -> dict[str, Any]:
    """Which frames each clip needs, grouped by the capture day that holds them."""
    selection = json.loads(view_artifact.read_text(encoding="utf-8"))
    # Keyed on the chain and extreme the view was chosen FOR, not just the exercise and day. A view
    # is selected by minimising the error of one specific clip statistic, so a selection made for
    # the elbow says nothing about the shoulder. Keying on the exercise alone silently measured the
    # pull-up through a view picked for a chain it does not use.
    usable = {
        (
            unicodedata.normalize("NFC", r["exercise"]).strip(),
            r["captureDay"],
            r["chain"],
            r["extreme"],
        ): r["bestViewIndex"]
        for r in selection.get("selections", [])
        if r.get("usable")
    }

    clips: dict[str, dict[str, Any]] = {}
    frames_by_day: dict[str, dict[str, str]] = defaultdict(dict)
    skipped: Counter[str] = Counter()

    for path in sorted(label_root.rglob("D*.json")):
        if path.name.endswith("-3d.json"):
            continue
        spatial_path = path.with_name(path.stem + "-3d.json")
        if not spatial_path.exists():
            continue
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        type_info = document.get("type_info") or {}
        exercise = unicodedata.normalize("NFC", type_info.get("exercise", "")).strip()
        profile = EXERCISE_PROFILES.get(exercise)
        if profile is None or (only is not None and exercise not in only):
            continue
        frames = document.get("frames") or []
        if not frames or document.get("type") in CONTAMINATED_TYPE_CODES:
            continue
        day = frames[0]["view1"]["img_key"].split("/")[0]
        if day in QUARANTINED_CAPTURE_DAYS:
            skipped["quarantined_capture_day"] += 1
            continue
        view_index = usable.get((exercise, day, profile.chain, profile.extreme))
        if view_index is None:
            skipped["no_view_selection_for_this_chain"] += 1
            continue
        conditions = {
            unicodedata.normalize("NFC", e["condition"]).strip(): e["value"]
            for e in type_info.get("conditions", [])
        }
        keys = [k for k in conditions if profile.condition in k]
        if len(keys) != 1:
            continue
        try:
            spatial = json.loads(spatial_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        spatial_frames = spatial.get("frames") or []
        if len(spatial_frames) != len(frames):
            continue

        # The label-space truth is computed here, where the 3D file is already open, and kept per
        # frame rather than reduced now. The bias this tool reports is the error between two
        # extremes, so both must be taken over the frames that actually survived: reducing the
        # label side over all sixteen while the MediaPipe side keeps only what was detected would
        # add a frame-dropout term whose sign is fixed by the extreme, and report it as estimator
        # error. Frames drop out hardest at maximum flexion, which is exactly where the extreme is.
        view_key = f"view{view_index}"
        label_by_key: dict[str, float] = {}
        label_sides_by_key: dict[str, dict[str, float]] = {}
        for frame, spatial_frame in zip(frames, spatial_frames):
            value = _label_angle(
                spatial_frame["pts"], ("x", "y", "z"), profile.chain, profile.extreme
            )
            if value is not None:
                label_by_key[frame[view_key]["img_key"]] = value
            sides = _label_sides(spatial_frame["pts"], ("x", "y", "z"), profile.chain)
            if sides:
                label_sides_by_key[frame[view_key]["img_key"]] = sides
        first_key = frames[0][view_key]["img_key"]
        match = SUBJECT_PATTERN.search(first_key)
        clip_id = str(path.relative_to(label_root)).replace("\\", "/")
        clips[clip_id] = {
            "exercise": exercise,
            "day": day,
            "subject": match.group(1) if match else "UNKNOWN",
            "label": bool(conditions[keys[0]]),
            "chain": profile.chain,
            "extreme": profile.extreme,
            "direction": profile.direction,
            "labelByKey": label_by_key,
            "labelSidesByKey": label_sides_by_key,
            "expectedFrames": 0,
        }
        for frame in frames:
            key = frame[view_key]["img_key"]
            frames_by_day[day][key] = clip_id
            clips[clip_id]["expectedFrames"] += 1

    return {"clips": clips, "framesByDay": dict(frames_by_day), "skipped": dict(skipped)}


def archive_for_day(archives: list[Path], day: str, cache: dict[str, str]) -> Path | None:
    """One archive holds one capture day, so its first header identifies it."""
    for archive in archives:
        if archive.name in cache:
            if cache[archive.name] == day:
                return archive
            continue
        try:
            with tarfile.open(archive, mode="r:") as tf:
                while True:
                    member = tf.next()
                    if member is None:
                        break
                    if member.isfile():
                        cache[archive.name] = _member_key(member.name).split("/")[0]
                        break
        except (OSError, tarfile.TarError):
            cache[archive.name] = "<unreadable>"
        if cache.get(archive.name) == day:
            return archive
    return None


def run(
    label_root: Path,
    archive_roots: list[Path],
    model_path: Path,
    view_artifact: Path,
    workers: int,
    only: set[str] | None,
    extra_image_roots: list[Path],
    clips_out: Path | None = None,
    frames_out: Path | None = None,
    sides_out: Path | None = None,
) -> dict[str, Any]:
    model_sha = _verify_model(model_path)
    plan = build_plan(label_root, view_artifact, only)
    clips = plan["clips"]
    frames_by_day = plan["framesByDay"]
    total_frames = sum(len(v) for v in frames_by_day.values())
    print(
        f"plan: {len(clips)} clips, {total_frames} frames, {len(frames_by_day)} capture days",
        file=sys.stderr,
        flush=True,
    )

    chain_by_key = {cid: c["chain"] for cid, c in clips.items()}
    extreme_by_key = {cid: c["extreme"] for cid, c in clips.items()}

    inbox: Any = mp_proc.Queue(maxsize=QUEUE_DEPTH)
    outbox: Any = mp_proc.Queue()
    procs = [
        mp_proc.Process(
            target=_worker,
            args=(str(model_path), chain_by_key, extreme_by_key, inbox, outbox),
            daemon=True,
        )
        for _ in range(workers)
    ]
    for p in procs:
        p.start()

    outcomes: Counter[str] = Counter(plan["skipped"])
    measured: dict[str, list[tuple[str, float]]] = defaultdict(list)
    measured_sides: dict[str, list[tuple[str, dict[str, float]]]] = defaultdict(list)
    submitted = 0
    collected = 0
    started = time.time()

    def drain(block: bool = False) -> None:
        nonlocal collected
        while True:
            try:
                item = outbox.get(block=block, timeout=1.0) if block else outbox.get_nowait()
            except queue.Empty:
                return
            if item is POISON:
                continue
            clip_id, img_key, outcome, angle, sides = item
            outcomes[outcome] += 1
            collected += 1
            if angle is not None:
                measured[clip_id].append((img_key, angle))
                measured_sides[clip_id].append((img_key, sides))
            block = False

    # The distribution spans drives: the first twelve barbell archives live beside the labels and
    # the rest arrived on an external volume. One capture day still lives in exactly one archive,
    # so pooling the roots changes where a day is found, never which day it is.
    archives = sorted(
        archive for root in archive_roots if root.is_dir() for archive in root.glob("*.tar")
    )
    day_cache: dict[str, str] = {}

    for day in sorted(frames_by_day):
        wanted = frames_by_day[day]
        # Days already unpacked are read from disk; the archives are only for the rest.
        local_hits = 0
        for key, clip_id in list(wanted.items()):
            for root in extra_image_roots:
                candidate = root / key
                if candidate.exists():
                    inbox.put((clip_id, key, candidate.read_bytes()))
                    submitted += 1
                    local_hits += 1
                    del wanted[key]
                    break
            drain()
        if local_hits:
            print(f"{day}: {local_hits} frames from unpacked tree", file=sys.stderr, flush=True)
        if not wanted:
            continue

        archive = archive_for_day(archives, day, day_cache)
        if archive is None:
            outcomes["missing_archive"] += len(wanted)
            print(f"{day}: NO ARCHIVE for {len(wanted)} frames", file=sys.stderr, flush=True)
            continue

        t0 = time.time()
        found = 0
        try:
            with tarfile.open(archive, mode="r:") as tf:
                while True:
                    member = tf.next()
                    if member is None:
                        break
                    if not member.isfile():
                        continue
                    key = _member_key(member.name)
                    clip_id = wanted.get(key)
                    if clip_id is None:
                        continue
                    handle = tf.extractfile(member)
                    if handle is None:
                        continue
                    inbox.put((clip_id, key, handle.read()))
                    submitted += 1
                    found += 1
                    drain()
        except (OSError, tarfile.TarError) as exc:
            print(f"{day}: archive error {exc}", file=sys.stderr, flush=True)
        outcomes["missing_image"] += len(wanted) - found
        rate = collected / max(time.time() - started, 1e-9)
        print(
            f"{day}: {found}/{len(wanted)} frames from {archive.name} in {time.time()-t0:.0f}s "
            f"| done {collected}/{total_frames} @ {rate:.0f} fps",
            file=sys.stderr,
            flush=True,
        )

    for _ in procs:
        inbox.put(POISON)
    while collected < submitted:
        drain(block=True)
    for p in procs:
        p.join(timeout=30)

    print(f"measured {collected} frames in {time.time()-started:.0f}s", file=sys.stderr, flush=True)

    # ---- fit ----------------------------------------------------------------------------------
    rows_by_exercise: dict[str, list[dict[str, Any]]] = defaultdict(list)
    frame_rows: list[dict[str, Any]] = []
    side_rows: list[dict[str, Any]] = []
    for clip_id, survivors in measured.items():
        if not survivors:
            continue
        clip = clips[clip_id]
        extreme = clip["extreme"]
        angles = [a for _, a in survivors]
        if sides_out is not None:
            # Same-side pairs with full identity. The per-frame rows above collapse Left and
            # Right on both sides with a min/max, so a "per-frame" error there already carries one
            # extreme-of-two of noise and cannot be regrouped into excursions. These rows keep the
            # clip, the frame key and the side, so a paired excursion extreme can be reconstructed
            # exactly — the measurement §4.10's floors were bootstrapped toward, taken directly.
            label_sides_by_key = clip["labelSidesByKey"]
            for key, mp_sides in measured_sides[clip_id]:
                label_sides = label_sides_by_key.get(key)
                if not label_sides:
                    continue
                for side, mp_angle in mp_sides.items():
                    label_angle = label_sides.get(side)
                    if label_angle is None:
                        continue
                    side_rows.append(
                        {
                            "clip": clip_id,
                            "key": key,
                            "side": side,
                            "exercise": clip["exercise"],
                            "chain": clip["chain"],
                            "extreme": extreme,
                            "day": clip["day"],
                            "subject": clip["subject"],
                            "mediapipe": round(mp_angle, 2),
                            "aihub": round(label_angle, 2),
                        }
                    )
        paired_labels = [
            clip["labelByKey"][key] for key, _ in survivors if key in clip["labelByKey"]
        ]
        if frames_out is not None:
            # The per-frame pairs the clip statistic is distilled from. The clip extreme answers
            # the threshold question; these answer the error question — what one frame of this
            # chain misreads by — which no artifact has ever published for a chain other than the
            # knee, though several policy bounds now lean on exactly that number.
            for key, mediapipe_angle in survivors:
                label_angle = clip["labelByKey"].get(key)
                if label_angle is None:
                    continue
                frame_rows.append(
                    {
                        "exercise": clip["exercise"],
                        "chain": clip["chain"],
                        "day": clip["day"],
                        "subject": clip["subject"],
                        "mediapipe": round(mediapipe_angle, 2),
                        "aihub": round(label_angle, 2),
                    }
                )
        clip["aihubExtremeOverMeasuredFrames"] = (
            (min(paired_labels) if extreme == "min" else max(paired_labels))
            if paired_labels
            else None
        )
        rows_by_exercise[clip["exercise"]].append(
            {
                # The bare Z code, because it identifies a participant across the whole dataset
                # rather than within one shoot. Prefixing the capture day would put the held-out
                # person's own clips from every other day into the training set, so the fold would
                # measure within-person consistency and report it as cross-person transfer. Every
                # other experiment in this repo declares LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT for the
                # same reason.
                "subject": clip["subject"],
                "label": clip["label"],
                "mediapipe": min(angles) if extreme == "min" else max(angles),
                "aihub": clip["aihubExtremeOverMeasuredFrames"],
                "day": clip["day"],
                "measuredFrames": len(angles),
            }
        )

    if clips_out is not None:
        clips_out.write_text(
            json.dumps(
                {
                    "note": "Per-clip measurements, so a re-fit needs no second pass.",
                    "rows": {ex: rows for ex, rows in sorted(rows_by_exercise.items())},
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        print(f"wrote per-clip rows to {clips_out}", file=sys.stderr, flush=True)

    if sides_out is not None:
        sides_out.write_text(
            json.dumps(
                {
                    "note": (
                        "Per-(clip, frame, side) same-side (MediaPipe, AI Hub 3D label) angle "
                        "pairs. Unlike the per-frame rows, nothing here is collapsed across "
                        "sides, so paired excursion extremes can be reconstructed exactly."
                    ),
                    "rows": side_rows,
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        print(f"wrote {len(side_rows)} same-side pairs to {sides_out}", file=sys.stderr, flush=True)

    if frames_out is not None:
        frames_out.write_text(
            json.dumps(
                {
                    "note": (
                        "Per-frame (MediaPipe, AI Hub 3D label) angle pairs from the same pass, "
                        "so a per-chain per-frame error card needs no second pass."
                    ),
                    "rows": frame_rows,
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        print(f"wrote {len(frame_rows)} per-frame pairs to {frames_out}", file=sys.stderr, flush=True)

    exercises: list[dict[str, Any]] = []
    for exercise in sorted(rows_by_exercise):
        rows = rows_by_exercise[exercise]
        profile = EXERCISE_PROFILES[exercise]
        direction = profile.direction
        positives = sum(1 for r in rows if r["label"])
        if positives in (0, len(rows)):
            continue
        mp_rows = [(_to_detector(r["mediapipe"], direction), r["label"]) for r in rows]
        pooled = _fit_threshold(mp_rows)
        subjects = sorted({r["subject"] for r in rows})
        folds: list[int] = []
        tp = fn = tn = fp = 0
        for subject in subjects:
            train = [
                (_to_detector(r["mediapipe"], direction), r["label"])
                for r in rows
                if r["subject"] != subject
            ]
            test = [
                (_to_detector(r["mediapipe"], direction), r["label"])
                for r in rows
                if r["subject"] == subject
            ]
            fitted = _fit_threshold(train)
            if fitted is None or not test:
                continue
            folds.append(int(_to_detector(float(fitted), direction)))
            f_tp, f_fn, f_tn, f_fp = _confusion(test, fitted)
            tp += f_tp
            fn += f_fn
            tn += f_tn
            fp += f_fp
        loso = (
            (tp / (tp + fn) + tn / (tn + fp)) / 2.0 if (tp + fn) and (tn + fp) else None
        )
        biases = [r["mediapipe"] - r["aihub"] for r in rows if r["aihub"] is not None]
        biases.sort()
        exercises.append(
            {
                "exercise": exercise,
                "condition": profile.condition,
                "conditionKind": profile.kind,
                "chain": profile.chain,
                "extreme": profile.extreme,
                "direction": direction,
                "clipCount": len(rows),
                "conditionTrueCount": positives,
                "subjectCount": len(subjects),
                "captureDays": sorted({r["day"] for r in rows}),
                "mediapipeNativeThresholdDegrees": (
                    int(_to_detector(float(pooled), direction)) if pooled is not None else None
                ),
                "mediapipeNativeLosoBalancedAccuracy": round(loso, 4) if loso is not None else None,
                "mediapipeNativeBalancedAccuracy": (
                    round(_balanced_accuracy(mp_rows, pooled) or float("nan"), 4)
                    if pooled is not None
                    else None
                ),
                "majorityBaseline": round(max(positives, len(rows) - positives) / len(rows), 4),
                "losoConfusion": {"tp": tp, "fn": fn, "tn": tn, "fp": fp},
                "mediapipeNativeLosoThresholdMinDegrees": min(folds) if folds else None,
                "mediapipeNativeLosoThresholdMaxDegrees": max(folds) if folds else None,
                "clipLevelAngleBiasDegrees": (
                    round(biases[len(biases) // 2], 2) if biases else None
                ),
            }
        )

    return {
        "artifactKind": "TREX_MEDIAPIPE_AIHUB_BRIDGE_ERROR_CARD",
        "artifactVersion": 4,
        "supersedes": (
            "v3 read the archives in place but could only reach the roots on one drive, which "
            "silently halved the curls' capture days and hid the barbell lunge entirely. This "
            "version pools archive roots across drives, adds the barbell lunge profile, and "
            "keeps the per-frame pairs the fit distils, so the per-chain error card comes from "
            "the same pass."
        ),
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT",
        },
        "model": {
            "assetPath": str(model_path.relative_to(REPO_ROOT)).replace("\\", "/"),
            "sha256": model_sha,
            "matchesAppPin": True,
            "runningMode": "IMAGE",
            "numPoses": NUM_POSES,
        },
        "view": {
            "selection": "PER_EXERCISE_CAPTURE_DAY_CHAIN_AND_EXTREME",
            "artifact": "docs/aihub-measurement-view.v1.json",
            "note": (
                "A view is chosen by minimising the error of one specific clip statistic, so a "
                "selection made for another chain is not reused; such clips are skipped."
            ),
        },
        "generalisation": {
            "scheme": "LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT",
            "note": (
                "The fold unit is the dataset-wide participant code, not the participant within a "
                "capture day. A person filmed on several days is one fold, so a held-out person "
                "contributes nothing to the threshold being tested against them."
            ),
        },
        "source": {
            "readInPlaceFromArchives": True,
            "archiveRoots": [str(root) for root in archive_roots],
            "note": "One archive holds one capture day; only the selected view's frames are read.",
        },
        "detectionOutcomes": dict(sorted(outcomes.items())),
        "thresholdTransfer": exercises,
        "limitations": [
            "Studio capture: single lighting condition, standard clothing, uncluttered floor.",
            "Clip-level condition labels, so transfer accuracy is clip quality, not per-rep truth.",
            "IMAGE mode; the shipped runtime uses VIDEO mode, whose tracking may differ.",
            "Passing transfer authorises a heuristic-beta constant only, never a release verdict.",
            "Verdicts use balanced accuracy; raw accuracy is not reported because it rewards "
            "predicting the majority class.",
            "A subject is one participant folder within one capture day, so the same person filmed "
            "on two days counts twice and leave-one-subject-out is optimistic to that extent.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path)
    parser.add_argument(
        "--archives",
        type=Path,
        action="append",
        required=True,
        help="a directory of day tars; repeatable, because the distribution spans drives",
    )
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument(
        "--clips-out",
        type=Path,
        default=None,
        help=(
            "write the per-clip measurements here. Re-fitting under a different fold unit or gate "
            "then costs nothing, instead of another full pass over the archives."
        ),
    )
    parser.add_argument("--only", action="append", default=None)
    parser.add_argument(
        "--frames-out",
        type=Path,
        default=None,
        help=(
            "write the per-frame (MediaPipe, label) angle pairs here, so a per-chain per-frame "
            "error card can be computed without another pass over the archives"
        ),
    )
    parser.add_argument(
        "--sides-out",
        type=Path,
        default=None,
        help=(
            "write per-(clip, frame, side) same-side angle pairs here, so a paired excursion "
            "extreme error can be measured directly rather than bootstrapped"
        ),
    )
    parser.add_argument(
        "--images",
        type=Path,
        action="append",
        default=None,
        help="already-unpacked roots, preferred over the archives when they hold the frame",
    )
    args = parser.parse_args()

    if not args.label_root.is_dir():
        print(f"not a directory: {args.label_root}", file=sys.stderr)
        return 1

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
        args.clips_out,
        args.frames_out,
        args.sides_out,
    )
    args.out.write_text(
        json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"\nwrote {args.out}")
    print("outcomes:", artifact["detectionOutcomes"])
    for entry in artifact["thresholdTransfer"]:
        print(
            f"\n{entry['exercise']}  [{entry['conditionKind']}] {entry['chain']}/{entry['extreme']}"
            f"  clips={entry['clipCount']} subjects={entry['subjectCount']} "
            f"days={len(entry['captureDays'])}"
        )
        print(
            f"   threshold {entry['mediapipeNativeThresholdDegrees']}deg"
            f"  LOSO_bal={entry['mediapipeNativeLosoBalancedAccuracy']}"
            f"  pooled_bal={entry['mediapipeNativeBalancedAccuracy']}"
            f"  base={entry['majorityBaseline']}"
            f"  folds={entry['mediapipeNativeLosoThresholdMinDegrees']}-"
            f"{entry['mediapipeNativeLosoThresholdMaxDegrees']}"
            f"  bias={entry['clipLevelAngleBiasDegrees']}deg"
        )
    return 0


if __name__ == "__main__":
    mp_proc.freeze_support()
    raise SystemExit(main())
