"""Measure the MediaPipe-to-AI Hub bridge on the Day05 lateral view.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT (non-commercial educational scope).

Why this exists
---------------
`docs/heuristic-form-check-threshold-fit.v1.json` fitted depth thresholds on AI Hub *label*
angles. The app never sees those; it sees MediaPipe world landmarks estimated from one lateral
camera. A fitted constant is only usable if it survives that substitution, so this tool runs the
exact model the app ships -- `app/src/main/assets/pose_landmarker_full.task`, SHA-pinned in
`VerifiedMediaPipePoseObserverFactory` -- over the same labelled frames and reports:

  1. detection outcome per frame, including the multi-candidate rate that the runtime's
     primary-person lock treats as AMBIGUOUS;
  2. per-joint 2D position error against the AI Hub label, normalised by body height;
  3. knee included-angle error, MediaPipe world landmarks versus the AI Hub 3D label;
  4. a transfer test: does the AI Hub-fitted threshold still classify the condition when applied
     to MediaPipe angles, and what threshold would be fitted natively on MediaPipe instead.

Item 4 is the decision. Items 1-3 explain whatever it shows.

Inference runs in IMAGE mode because the labelled frames are sparse keyframes, not a continuous
stream; VIDEO-mode tracking across them would carry state between unrelated poses.

Usage:
    python tools/measure_mediapipe_aihub_bridge.py <day05_label_root> [--limit N] [--out PATH]
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL = REPO_ROOT / "app" / "src" / "main" / "assets" / "pose_landmarker_full.task"
DEFAULT_ARTIFACT = REPO_ROOT / "docs" / "mediapipe-aihub-bridge.v1.json"

# Pinned in app/src/main/java/.../camera/VerifiedMediaPipePoseObserverFactory.kt
EXPECTED_MODEL_SHA256 = "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"

# app/src/main/java/.../camera/PoseCameraConfig.kt
NUM_POSES = 2
MIN_POSE_DETECTION_CONFIDENCE = 0.5
MIN_POSE_PRESENCE_CONFIDENCE = 0.5
MIN_TRACKING_CONFIDENCE = 0.5

# app/src/main/java/.../pose/formcheck/FormCheckGeometry.kt
MINIMUM_CHAIN_CONFIDENCE = 0.55

CONTAMINATED_TYPE_CODES = frozenset({"062", "101", "109"})
DEPTH_CONDITION_SUBSTRING = "앞다리"
SUBJECT_PATTERN = re.compile(r"-(Z\d+)_")
THRESHOLD_SEARCH_RANGE = range(40, 181)

# AI Hub joint name -> MediaPipe BlazePose landmark index. Only anatomically unambiguous pairs
# are listed: Neck, Back, Waist, Left/Right Palm have no MediaPipe counterpart, which is the
# documented reason spine-neutral criteria cannot be bridged.
JOINT_MAP: dict[str, int] = {
    "Nose": 0,
    "Left Eye": 2,
    "Right Eye": 5,
    "Left Ear": 7,
    "Right Ear": 8,
    "Left Shoulder": 11,
    "Right Shoulder": 12,
    "Left Elbow": 13,
    "Right Elbow": 14,
    "Left Wrist": 15,
    "Right Wrist": 16,
    "Left Hip": 23,
    "Right Hip": 24,
    "Left Knee": 25,
    "Right Knee": 26,
    "Left Ankle": 27,
    "Right Ankle": 28,
}
MIRRORED_JOINT_MAP: dict[str, int] = {}
for _name, _index in JOINT_MAP.items():
    if _name.startswith("Left "):
        MIRRORED_JOINT_MAP[_name] = JOINT_MAP["Right " + _name[5:]]
    elif _name.startswith("Right "):
        MIRRORED_JOINT_MAP[_name] = JOINT_MAP["Left " + _name[6:]]
    else:
        MIRRORED_JOINT_MAP[_name] = _index

KNEE_CHAIN = {"Left": (23, 25, 27), "Right": (24, 26, 28)}


def _included_angle(a: Any, b: Any, c: Any) -> float | None:
    """Included angle at `b` in degrees, over objects exposing x/y/z."""
    u = (a.x - b.x, a.y - b.y, a.z - b.z)
    v = (c.x - b.x, c.y - b.y, c.z - b.z)
    nu = math.sqrt(sum(t * t for t in u))
    nv = math.sqrt(sum(t * t for t in v))
    if nu < 1e-9 or nv < 1e-9:
        return None
    cosine = sum(p * q for p, q in zip(u, v)) / (nu * nv)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _label_angle(points: dict[str, Any], axes: tuple[str, ...]) -> float | None:
    angles = []
    for side in ("Left", "Right"):
        try:
            p = points[f"{side} Hip"]
            q = points[f"{side} Knee"]
            r = points[f"{side} Ankle"]
        except KeyError:
            continue
        u = [p[k] - q[k] for k in axes]
        v = [r[k] - q[k] for k in axes]
        nu = math.sqrt(sum(t * t for t in u))
        nv = math.sqrt(sum(t * t for t in v))
        if nu < 1e-9 or nv < 1e-9:
            continue
        cosine = sum(a * b for a, b in zip(u, v)) / (nu * nv)
        angles.append(math.degrees(math.acos(max(-1.0, min(1.0, cosine)))))
    return min(angles) if angles else None


def _mediapipe_knee_angle(world: list[Any]) -> float | None:
    """Deepest credible knee bend, replicating the runtime's own chain-confidence gate."""
    angles = []
    for hip_i, knee_i, ankle_i in KNEE_CHAIN.values():
        chain = [world[hip_i], world[knee_i], world[ankle_i]]
        confidence = min(min(lm.visibility, lm.presence) for lm in chain)
        if confidence < MINIMUM_CHAIN_CONFIDENCE:
            continue
        angle = _included_angle(*chain)
        if angle is not None:
            angles.append(angle)
    return min(angles) if angles else None


def _fit_threshold(rows: list[tuple[float, bool]]) -> int | None:
    positives = [a for a, label in rows if label]
    negatives = [a for a, label in rows if not label]
    if not positives or not negatives:
        return None
    best, best_accuracy = None, -1.0
    for threshold in THRESHOLD_SEARCH_RANGE:
        correct = sum(1 for a in positives if a <= threshold)
        correct += sum(1 for a in negatives if a > threshold)
        accuracy = correct / len(rows)
        if accuracy > best_accuracy:
            best, best_accuracy = threshold, accuracy
    return best


def _accuracy(rows: list[tuple[float, bool]], threshold: float) -> float:
    return sum(1 for a, label in rows if (a <= threshold) == label) / len(rows)


def _percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def _verify_model(model_path: Path) -> str:
    import hashlib

    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if digest != EXPECTED_MODEL_SHA256:
        raise SystemExit(
            f"model SHA-256 does not match the app pin\n  expected={EXPECTED_MODEL_SHA256}\n"
            f"  actual  ={digest}"
        )
    return digest


def run(label_root: Path, model_path: Path, limit: int | None) -> dict[str, Any]:
    import cv2
    import numpy
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision

    model_sha = _verify_model(model_path)

    landmarker = vision.PoseLandmarker.create_from_options(
        vision.PoseLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=str(model_path)),
            running_mode=vision.RunningMode.IMAGE,
            num_poses=NUM_POSES,
            min_pose_detection_confidence=MIN_POSE_DETECTION_CONFIDENCE,
            min_pose_presence_confidence=MIN_POSE_PRESENCE_CONFIDENCE,
            min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
            output_segmentation_masks=False,
        )
    )

    # img_key is dataset-relative and already carries the capture-day directory name, so it
    # resolves against the label root's parent rather than the label root itself.
    dataset_root = label_root.parent
    outcomes: Counter[str] = Counter()
    joint_errors: dict[str, list[float]] = defaultdict(list)
    joint_errors_mirrored: dict[str, list[float]] = defaultdict(list)
    angle_pairs: list[tuple[float, float]] = []
    clip_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)

    label_paths = [p for p in sorted(label_root.glob("D05-*.json")) if not p.name.endswith("-3d.json")]
    processed_clips = 0

    for label_path in label_paths:
        if limit is not None and processed_clips >= limit:
            break
        document = json.loads(label_path.read_text(encoding="utf-8"))
        frames = document.get("frames") or []
        type_info = document.get("type_info") or {}
        if not frames or document.get("type") in CONTAMINATED_TYPE_CODES:
            continue
        conditions = {
            unicodedata.normalize("NFC", e["condition"]).strip(): e["value"]
            for e in type_info.get("conditions", [])
        }
        depth_keys = [k for k in conditions if DEPTH_CONDITION_SUBSTRING in k]
        if len(depth_keys) != 1:
            continue
        spatial_path = label_path.with_name(label_path.stem + "-3d.json")
        if not spatial_path.exists():
            continue
        spatial_frames = json.loads(spatial_path.read_text(encoding="utf-8")).get("frames") or []
        if len(spatial_frames) != len(frames):
            continue

        exercise = unicodedata.normalize("NFC", type_info["exercise"]).strip()
        subject_match = SUBJECT_PATTERN.search(frames[0]["view1"]["img_key"])
        subject = subject_match.group(1) if subject_match else "UNKNOWN"

        clip_mp_angles: list[float] = []
        clip_label_angles: list[float] = []

        for frame, spatial_frame in zip(frames, spatial_frames):
            view = frame["view1"]
            image_path = dataset_root / view["img_key"]
            if not image_path.exists():
                outcomes["missing_image"] += 1
                continue
            # cv2.imread cannot open the dataset's non-ASCII Windows paths; decode from bytes.
            image_bgr = cv2.imdecode(
                numpy.frombuffer(image_path.read_bytes(), dtype=numpy.uint8), cv2.IMREAD_COLOR
            )
            if image_bgr is None:
                outcomes["unreadable_image"] += 1
                continue
            image_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
            result = landmarker.detect(
                mp.Image(image_format=mp.ImageFormat.SRGB, data=image_rgb)
            )

            candidate_count = len(result.pose_landmarks)
            if candidate_count == 0:
                outcomes["no_pose"] += 1
                continue
            if candidate_count > 1:
                # The runtime clears the person lock here and abstains.
                outcomes["ambiguous_multi_person"] += 1
                continue
            outcomes["single_pose"] += 1

            image_landmarks = result.pose_landmarks[0]
            world_landmarks = result.pose_world_landmarks[0]
            height, width = image_rgb.shape[:2]

            label_points = view["pts"]
            try:
                shoulder_y = (
                    label_points["Left Shoulder"]["y"] + label_points["Right Shoulder"]["y"]
                ) / 2
                ankle_y = (label_points["Left Ankle"]["y"] + label_points["Right Ankle"]["y"]) / 2
                body_scale = abs(shoulder_y - ankle_y)
            except KeyError:
                body_scale = 0.0
            if body_scale > 10:
                for mapping, sink in (
                    (JOINT_MAP, joint_errors),
                    (MIRRORED_JOINT_MAP, joint_errors_mirrored),
                ):
                    for name, index in mapping.items():
                        point = label_points.get(name)
                        if point is None:
                            continue
                        predicted = image_landmarks[index]
                        dx = predicted.x * width - point["x"]
                        dy = predicted.y * height - point["y"]
                        sink[name].append(math.hypot(dx, dy) / body_scale)

            mp_angle = _mediapipe_knee_angle(world_landmarks)
            label_angle = _label_angle(spatial_frame["pts"], ("x", "y", "z"))
            if mp_angle is None:
                outcomes["chain_below_confidence"] += 1
                continue
            if label_angle is None:
                continue
            angle_pairs.append((mp_angle, label_angle))
            clip_mp_angles.append(mp_angle)
            clip_label_angles.append(label_angle)

        if clip_mp_angles and clip_label_angles:
            clip_rows[exercise].append(
                {
                    "subject": subject,
                    "label": bool(conditions[depth_keys[0]]),
                    "mediapipeMinAngle": min(clip_mp_angles),
                    "aihubMinAngle": min(clip_label_angles),
                    "observedFrames": len(clip_mp_angles),
                }
            )
        processed_clips += 1

    landmarker.close()

    # Mapping-convention check: if the mirrored assignment fits better, Left/Right disagree.
    def _median_of(sink: dict[str, list[float]]) -> float:
        pooled = [v for values in sink.values() for v in values]
        return statistics.median(pooled) if pooled else float("nan")

    direct_median = _median_of(joint_errors)
    mirrored_median = _median_of(joint_errors_mirrored)
    convention = "DIRECT" if direct_median <= mirrored_median else "MIRRORED"
    chosen = joint_errors if convention == "DIRECT" else joint_errors_mirrored

    angle_errors = [mp - lab for mp, lab in angle_pairs]
    absolute_errors = [abs(e) for e in angle_errors]

    exercises: list[dict[str, Any]] = []
    for exercise in sorted(clip_rows):
        rows = clip_rows[exercise]
        aihub_rows = [(r["aihubMinAngle"], r["label"]) for r in rows]
        mp_rows = [(r["mediapipeMinAngle"], r["label"]) for r in rows]
        positives = sum(1 for r in rows if r["label"])
        if positives in (0, len(rows)):
            continue
        aihub_threshold = _fit_threshold(aihub_rows)
        mp_threshold = _fit_threshold(mp_rows)

        subjects = sorted({r["subject"] for r in rows})
        folds: list[int] = []
        correct = held = 0
        for subject in subjects:
            train = [(r["mediapipeMinAngle"], r["label"]) for r in rows if r["subject"] != subject]
            test = [(r["mediapipeMinAngle"], r["label"]) for r in rows if r["subject"] == subject]
            fitted = _fit_threshold(train)
            if fitted is None or not test:
                continue
            folds.append(fitted)
            correct += sum(1 for a, label in test if (a <= fitted) == label)
            held += len(test)

        exercises.append(
            {
                "exercise": exercise,
                "clipCount": len(rows),
                "conditionTrueCount": positives,
                "subjectCount": len(subjects),
                "aihubFittedThresholdDegrees": aihub_threshold,
                "aihubThresholdAppliedToMediapipeAccuracy": (
                    round(_accuracy(mp_rows, aihub_threshold), 4)
                    if aihub_threshold is not None
                    else None
                ),
                "mediapipeNativeThresholdDegrees": mp_threshold,
                "mediapipeNativeAccuracy": (
                    round(_accuracy(mp_rows, mp_threshold), 4) if mp_threshold is not None else None
                ),
                "mediapipeNativeLosoAccuracy": round(correct / held, 4) if held else None,
                "mediapipeNativeLosoThresholdMinDegrees": min(folds) if folds else None,
                "mediapipeNativeLosoThresholdMaxDegrees": max(folds) if folds else None,
                "clipLevelAngleBiasDegrees": round(
                    statistics.median(
                        [r["mediapipeMinAngle"] - r["aihubMinAngle"] for r in rows]
                    ),
                    2,
                ),
            }
        )

    return {
        "artifactKind": "TREX_MEDIAPIPE_AIHUB_BRIDGE_ERROR_CARD",
        "artifactVersion": 1,
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
        "view": {"cameraView": "A", "labelView": "view1", "orientation": "LATERAL"},
        "jointMappingConvention": {
            "selected": convention,
            "directMedianNormalisedError": round(direct_median, 4),
            "mirroredMedianNormalisedError": round(mirrored_median, 4),
            "unmappableAiHubJoints": ["Neck", "Back", "Waist", "Left Palm", "Right Palm"],
        },
        "detectionOutcomes": dict(sorted(outcomes.items())),
        "jointPositionError": {
            "unit": "FRACTION_OF_SHOULDER_TO_ANKLE_HEIGHT",
            "perJoint": {
                name: {
                    "n": len(values),
                    "median": round(statistics.median(values), 4),
                    "p95": round(_percentile(values, 0.95), 4),
                }
                for name, values in sorted(chosen.items())
            },
        },
        "kneeAngleError": {
            "unit": "DEGREES",
            "definition": "MEDIAPIPE_WORLD_MINUS_AIHUB_3D_LABEL_PER_FRAME",
            "n": len(angle_errors),
            "medianSignedBias": round(statistics.median(angle_errors), 2) if angle_errors else None,
            "meanSignedBias": round(statistics.fmean(angle_errors), 2) if angle_errors else None,
            "medianAbsolute": (
                round(statistics.median(absolute_errors), 2) if absolute_errors else None
            ),
            "p95Absolute": round(_percentile(absolute_errors, 0.95), 2) if absolute_errors else None,
        },
        "thresholdTransfer": exercises,
        "limitations": [
            "Studio capture: single lighting condition, standard clothing, uncluttered floor.",
            "Eight subjects. Any per-joint tail beyond that population is unmeasured here.",
            "Clip-level condition labels, so transfer accuracy is clip quality, not per-rep truth.",
            "IMAGE mode; the shipped runtime uses VIDEO mode, whose tracking may differ.",
            "Passing transfer authorises a heuristic-beta constant only, never a release verdict.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--out", type=Path, default=DEFAULT_ARTIFACT)
    parser.add_argument("--limit", type=int, default=None, help="max clips (pilot runs)")
    args = parser.parse_args()

    if not args.label_root.is_dir():
        print(f"not a directory: {args.label_root}", file=sys.stderr)
        return 1

    artifact = run(args.label_root, args.model, args.limit)
    rendered = json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
    args.out.write_text(rendered, encoding="utf-8")

    print(f"wrote {args.out.name}\n")
    print("detection outcomes:", artifact["detectionOutcomes"])
    print("joint mapping     :", artifact["jointMappingConvention"]["selected"])
    angle = artifact["kneeAngleError"]
    print(
        f"knee angle error  : n={angle['n']} bias={angle['medianSignedBias']}deg "
        f"|err| median={angle['medianAbsolute']}deg p95={angle['p95Absolute']}deg"
    )
    for entry in artifact["thresholdTransfer"]:
        print(f"\n{entry['exercise']}  clips={entry['clipCount']} subjects={entry['subjectCount']}")
        print(
            f"   AI Hub threshold {entry['aihubFittedThresholdDegrees']}deg applied to MediaPipe"
            f" -> acc={entry['aihubThresholdAppliedToMediapipeAccuracy']}"
        )
        print(
            f"   MediaPipe-native {entry['mediapipeNativeThresholdDegrees']}deg"
            f" -> acc={entry['mediapipeNativeAccuracy']}"
            f" LOSO={entry['mediapipeNativeLosoAccuracy']}"
            f" (folds {entry['mediapipeNativeLosoThresholdMinDegrees']}-"
            f"{entry['mediapipeNativeLosoThresholdMaxDegrees']}deg)"
        )
        print(f"   clip-level angle bias {entry['clipLevelAngleBiasDegrees']}deg")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
