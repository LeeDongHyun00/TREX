#!/usr/bin/env python3
"""Compare predeclared AI Hub barbell-squat coordinate rules without label leakage.

This tool is deliberately narrower than a generic classifier.  It audits the complete 313--328
factorial, evaluates only semantically predeclared coordinate proxies, selects on Training with
global-Z subject leave-one-out predictions, and applies the locked selection to Validation once.

The resulting artifact is research-only.  AI Hub barbell-squat RGB is unavailable, so it cannot
calibrate the app's MediaPipe observer or authorize PASS/FAIL, scoring, or corrective cues.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import re
import statistics
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

if __package__:
    from .analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _angle_degrees,
        _canonical_sha256,
        _load_json_object,
        _selection_identity,
        decode_metadata_tail,
        extract_subject_id,
        normalize_frame_points,
        paired_three_d_path,
    )
else:
    from analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _angle_degrees,
        _canonical_sha256,
        _load_json_object,
        _selection_identity,
        decode_metadata_tail,
        extract_subject_id,
        normalize_frame_points,
        paired_three_d_path,
    )


SCHEMA_VERSION = 3
ARTIFACT_KIND = "AIHUB_BARBELL_SQUAT_COORDINATE_VALIDATION_EXPERIMENT"
DECISION_USE = "RESEARCH_ONLY_NOT_MEDIAPIPE_CALIBRATION_OR_USER_PASS_FAIL"
EXPERIMENT_FAMILY_ID = "aihub.barbell-squat.coordinate-proxy.v1"
PROTOCOL_ID = "aihub.barbell-squat.coordinate-proxy.protocol.v4"
TEXT_HASH_NORMALIZATION = "UTF8_STRIP_OPTIONAL_BOM_NORMALIZE_CRLF_AND_CR_TO_LF"
APPROVED_CATALOG_SHA256 = (
    "fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c"
)
APPROVED_COVERAGE_SHA256 = (
    "1f6ab0ea0981c6d1ef693ace7e72608a2e9af363b4b52f789a1749f92dae9cb5"
)
EXERCISE = "바벨 스쿼트"
TYPE_CODES = tuple(f"{value:03d}" for value in range(313, 329))
CONDITIONS = (
    "척추의 중립",
    "고개 정면",
    "발과 무릎의 방향 일치",
    "발바닥 지면 고정",
)
EXPECTED_BITS = {
    "313": "1111",
    "314": "0111",
    "315": "1011",
    "316": "1101",
    "317": "1110",
    "318": "0011",
    "319": "0101",
    "320": "0110",
    "321": "1001",
    "322": "1010",
    "323": "1100",
    "324": "1000",
    "325": "0100",
    "326": "0010",
    "327": "0001",
    "328": "0000",
}
EXPECTED_SPLITS = {
    "TRAINING": {"recordsPerType": 45, "subjects": 42},
    "VALIDATION": {"recordsPerType": 7, "subjects": 7},
}
ROUND_DIGITS = 10
EPSILON = 1e-9
MINIMUM_PHASE_PROXY_ROM_DEGREES = 20.0


class SquatExperimentError(RuntimeError):
    """Raised when a source or evaluation contract cannot be satisfied."""


@dataclass(frozen=True)
class Candidate:
    two_d_path: Path
    three_d_path: Path
    sequence_id: str
    type_code: str
    conditions: tuple[bool, ...]
    selection_day_id: str


@dataclass(frozen=True)
class Observation:
    sequence_id: str
    type_code: str
    subject_id: str
    day_id: str
    conditions: tuple[bool, ...]
    features: Mapping[str, float | None]
    frame_count: int
    active_frame_count: int
    two_d_sha256: str
    three_d_sha256: str
    two_d_coordinate_sha256: str
    three_d_coordinate_sha256: str
    active_contract_sha256: str


@dataclass(frozen=True)
class Prediction:
    subject_id: str
    actual: bool
    predicted: bool | None


CANDIDATE_FEATURES: Mapping[str, tuple[str, ...]] = {
    "척추의 중립": (
        "3d.whole.spine-chain-deviation-q75-deg",
        "3d.bottom.spine-chain-deviation-median-deg",
        "2d.views.spine-chain-deviation-q75-median-deg",
    ),
    "고개 정면": (
        "3d.whole.head-body-axis-mismatch-q75-deg",
        "3d.bottom.head-body-axis-mismatch-median-deg",
        "2d.views.head-body-axis-mismatch-q75-median-deg",
    ),
    "발과 무릎의 방향 일치": (
        "3d.whole.worst-side-knee-over-foot-body-lateral-offset-q75-shoulder-width",
        "3d.bottom.worst-side-knee-over-foot-body-lateral-offset-median-shoulder-width",
    ),
    "발바닥 지면 고정": (
        "3d.active.worst-side-camera-coordinate-foot-step-q75-per-torso-scale",
        "3d.active.worst-side-camera-coordinate-foot-excursion-q90-per-torso-scale",
        "2d.active.views.worst-side-camera-coordinate-foot-step-q75-per-torso-scale-median",
    ),
}

CRITERION_CONTRACTS: Mapping[str, Mapping[str, Any]] = {
    "척추의 중립": {
        "semanticId": "aihub.condition.spine-neutral.v1",
        "targetConstructId": "proxy.gross-spine-chain-shape.v1",
        "exactConditionObservability": "PROXY_ONLY_NOT_ANATOMICAL_SPINE_NEUTRAL",
        "claimBoundary": "Must not claim lumbar neutrality, load safety, or diagnosis.",
    },
    "고개 정면": {
        "semanticId": "aihub.condition.head-facing-forward.v1",
        "targetConstructId": "proxy.head-axis-relative-to-shoulder-axis.v1",
        "exactConditionObservability": "PROXY_ONLY_NOT_GAZE",
        "claimBoundary": (
            "Must not claim eye gaze or cervical safety; line-axis geometry cannot distinguish "
            "front from a 180-degree reversed orientation and mixes yaw, roll, and pitch."
        ),
    },
    "발과 무릎의 방향 일치": {
        "semanticId": "aihub.condition.knee-foot-direction-aligned.v1",
        "targetConstructId": "proxy.knee-over-foot-body-lateral-offset.v1",
        "exactConditionObservability": "PROXY_ONLY_NOT_FULL_3D_DIRECTION_OR_LOAD",
        "claimBoundary": (
            "Measures body-lateral knee/toe offset only; body yaw, landmark definition, full 3D "
            "foot heading, joint load, torque, and injury risk remain unidentified."
        ),
    },
    "발바닥 지면 고정": {
        "semanticId": "aihub.condition.plantar-ground-contact-fixed.v1",
        "targetConstructId": "proxy.camera-coordinate-foot-motion.v1",
        "exactConditionObservability": "NOT_OBSERVABLE_FROM_POSE_COORDINATES",
        "claimBoundary": (
            "Only gross camera-coordinate foot motion is measured; plantar contact, pressure, "
            "and ground reaction force are not identified."
        ),
    },
}

PROXY_RESEARCH_SIGNAL_POLICY: Mapping[str, Any] = {
    "purpose": "FIXED_RESEARCH_SIGNAL_TRIAGE_NOT_A_RELEASE_OR_CALIBRATION_GATE",
    "minimumTrainingLosoSubjectMacroBalancedAccuracy": 0.65,
    "minimumLockedValidationSubjectMacroBalancedAccuracy": 0.65,
    "minimumTrainingAndValidationMatchedDirectionConsistency": 0.75,
    "minimumSubjectBootstrap95Low": 0.5,
    "minimumMatchedDirectionWilson95Low": 0.5,
    "minimumLockedSelectiveCoverage": 0.5,
    "minimumSelectiveClassAndSubjectCoverage": 0.5,
    "selectiveMetricMustCoverEverySubject": True,
    "allTrainingLosoFoldsMustMatchSemanticDirection": True,
}

PROTOCOL_CONTRACT: Mapping[str, Any] = {
    "protocolId": PROTOCOL_ID,
    "implementationTextHashNormalization": TEXT_HASH_NORMALIZATION,
    "approvedCoverageSha256": APPROVED_COVERAGE_SHA256,
    "criterionContracts": CRITERION_CONTRACTS,
    "candidateFeaturesByCondition": CANDIDATE_FEATURES,
    "activePolicy": (
        "COMMON_FIVE_VIEW_ACTIVE_YES_MASK_AS_WINDOW_PRIOR; "
        "DO_NOT_BRIDGE_INACTIVE_GAPS"
    ),
    "phaseProxy": {
        "smoothing": "THREE_SAMPLE_MEDIAN_WITHIN_ACTIVE_RUN",
        "bottomWindow": (
            "MAX_ROM_ELIGIBLE_ACTIVE_RUN_CONTIGUOUS_NADIR_PLUS_OR_MINUS_ONE_SAMPLE"
        ),
        "minimumKneeRomDegrees": MINIMUM_PHASE_PROXY_ROM_DEGREES,
    },
    "selection": "TRAINING_GLOBAL_Z_LOSO_ONLY",
    "validationRole": "CONSUMED_DESCRIPTIVE_DEVELOPMENT_REPLICATION",
    "abstention": "MAX_CLASS_SUBJECT_MEDIAN_MAD_X_1_4826",
    "independentUnit": "GLOBAL_Z_SUBJECT",
    "internalSequenceUniqueness": (
        "REQUIRE_UNIQUE_2D_AND_3D_RAW_CONTENT_AND_CANONICAL_COORDINATES_WITHIN_SPLIT"
    ),
    "viewsAreNested": True,
    "framesAreNested": True,
    "proxyResearchSignalPolicy": PROXY_RESEARCH_SIGNAL_POLICY,
}


def _round(value: float) -> float:
    rounded = round(float(value), ROUND_DIGITS)
    return 0.0 if rounded == 0.0 else rounded


def _vector_subtract(a: Sequence[float], b: Sequence[float]) -> tuple[float, float, float]:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _vector_add(a: Sequence[float], b: Sequence[float]) -> tuple[float, float, float]:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def _vector_scale(a: Sequence[float], scale: float) -> tuple[float, float, float]:
    return (a[0] * scale, a[1] * scale, a[2] * scale)


def _dot(a: Sequence[float], b: Sequence[float]) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _norm(a: Sequence[float]) -> float:
    return math.sqrt(_dot(a, a))


def _unit(a: Sequence[float]) -> tuple[float, float, float]:
    length = _norm(a)
    if not math.isfinite(length) or length <= EPSILON:
        raise SequenceDataError("Cannot normalize a degenerate vector")
    return _vector_scale(a, 1.0 / length)


def _distance(a: Sequence[float], b: Sequence[float]) -> float:
    return _norm(_vector_subtract(a, b))


def _axis_angle_degrees(a: Sequence[float], b: Sequence[float]) -> float:
    """Unsigned line-axis mismatch in [0, 90], robust to endpoint naming direction."""

    cosine = abs(_dot(_unit(a), _unit(b)))
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _median(values: Iterable[float]) -> float:
    materialized = [float(value) for value in values]
    if not materialized:
        raise SequenceDataError("Cannot aggregate an empty feature series")
    return float(statistics.median(materialized))


def _quantile(values: Iterable[float], probability: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        raise SequenceDataError("Cannot aggregate an empty feature series")
    if not 0.0 <= probability <= 1.0:
        raise ValueError("probability must be in [0, 1]")
    if len(ordered) == 1:
        return ordered[0]
    position = probability * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def _median_absolute_deviation(values: Iterable[float]) -> float:
    materialized = [float(value) for value in values]
    center = _median(materialized)
    return _median(abs(value - center) for value in materialized)


_IMAGE_FRAME_ORDINAL = re.compile(r"([0-9]+)$")


def _active_frame_contract(
    frames_2d: Sequence[Any],
) -> tuple[tuple[int, ...], tuple[int, ...], tuple[str, ...]]:
    """Return the common active mask and run lengths after validating five-view identity.

    AI Hub's ``active`` value is used only as a sampled movement-window prior.  It is not a
    correctness label or a phase Gold label.  All views must agree, and temporal features never
    bridge an inactive gap.
    """

    view_names: tuple[str, ...] | None = None
    active_indices: list[int] = []
    active_run_lengths: list[int] = []
    current_run = 0
    previous_ordinal: int | None = None
    for index, frame in enumerate(frames_2d):
        if not isinstance(frame, dict):
            raise SequenceDataError("2D frame must be an object")
        names = tuple(sorted(name for name in frame if name.startswith("view")))
        if len(names) != 5:
            raise SequenceDataError("Every 2D frame must expose exactly five views")
        if view_names is None:
            view_names = names
        elif names != view_names:
            raise SequenceDataError("Every 2D frame must expose the same view set")

        active_values: set[str] = set()
        ordinals: set[int] = set()
        for name in names:
            view = frame.get(name)
            if not isinstance(view, dict):
                raise SequenceDataError("2D view must be an object")
            active = view.get("active")
            if active not in {"Yes", "No"}:
                raise SequenceDataError("2D active must be exactly Yes or No")
            active_values.add(active)
            image_key = view.get("img_key")
            if not isinstance(image_key, str) or not image_key:
                raise SequenceDataError("2D img_key must be a non-empty string")
            match = _IMAGE_FRAME_ORDINAL.search(Path(image_key).stem)
            if match is None:
                raise SequenceDataError("2D img_key must end in a numeric frame ordinal")
            ordinals.add(int(match.group(1)))
        if len(active_values) != 1:
            raise SequenceDataError("All views must agree on active for a frame")
        if len(ordinals) != 1:
            raise SequenceDataError("All views must refer to the same sampled frame ordinal")
        ordinal = next(iter(ordinals))
        if previous_ordinal is not None and ordinal <= previous_ordinal:
            raise SequenceDataError("2D img_key frame ordinals must be strictly increasing")
        previous_ordinal = ordinal

        if next(iter(active_values)) == "Yes":
            active_indices.append(index)
            current_run += 1
        elif current_run:
            active_run_lengths.append(current_run)
            current_run = 0
    if current_run:
        active_run_lengths.append(current_run)
    if len(active_indices) < 2 or not any(length >= 2 for length in active_run_lengths):
        raise SequenceDataError("At least two adjacent active frames are required")
    assert view_names is not None
    return tuple(active_indices), tuple(active_run_lengths), view_names


def _partition_runs(
    values: Sequence[Mapping[str, Sequence[float]]],
    run_lengths: Sequence[int],
) -> tuple[tuple[Mapping[str, Sequence[float]], ...], ...]:
    runs: list[tuple[Mapping[str, Sequence[float]], ...]] = []
    offset = 0
    for length in run_lengths:
        runs.append(tuple(values[offset : offset + length]))
        offset += length
    if offset != len(values):
        raise SequenceDataError("Active run lengths do not cover the active frame sequence")
    return tuple(runs)


def _numeric_point(value: Any, dimensions: int) -> tuple[float, float, float]:
    if not isinstance(value, dict):
        raise SequenceDataError("Joint point must be an object")
    axes = ("x", "y", "z")[:dimensions]
    coordinates: list[float] = []
    for axis in axes:
        coordinate = value.get(axis)
        if isinstance(coordinate, bool) or not isinstance(coordinate, (int, float)):
            raise SequenceDataError(f"Joint coordinate {axis} must be numeric")
        coordinate = float(coordinate)
        if not math.isfinite(coordinate):
            raise SequenceDataError(f"Joint coordinate {axis} must be finite")
        coordinates.append(coordinate)
    while len(coordinates) < 3:
        coordinates.append(0.0)
    return coordinates[0], coordinates[1], coordinates[2]


REQUIRED_JOINTS = (
    "Left Ear",
    "Right Ear",
    "Left Shoulder",
    "Right Shoulder",
    "Left Hip",
    "Right Hip",
    "Left Knee",
    "Right Knee",
    "Left Ankle",
    "Right Ankle",
    "Neck",
    "Back",
    "Waist",
    "Left Foot",
    "Right Foot",
)


def _raw_points(points_value: Any, dimensions: int) -> dict[str, tuple[float, float, float]]:
    if not isinstance(points_value, dict):
        raise SequenceDataError("pts must be an object")
    missing = [joint for joint in REQUIRED_JOINTS if joint not in points_value]
    if missing:
        raise SequenceDataError(f"pts missing required joints: {missing}")
    return {
        joint: _numeric_point(points_value[joint], dimensions)
        for joint in REQUIRED_JOINTS
    }


def _torso_scale(points: Mapping[str, Sequence[float]]) -> float:
    pelvis = _vector_scale(_vector_add(points["Left Hip"], points["Right Hip"]), 0.5)
    shoulders = _vector_scale(
        _vector_add(points["Left Shoulder"], points["Right Shoulder"]),
        0.5,
    )
    scale = _distance(pelvis, shoulders)
    if not math.isfinite(scale) or scale <= EPSILON:
        raise SequenceDataError("Torso scale must be positive")
    return scale


def _normalize_2d(points: Mapping[str, Sequence[float]]) -> dict[str, tuple[float, float, float]]:
    pelvis = _vector_scale(_vector_add(points["Left Hip"], points["Right Hip"]), 0.5)
    scale = _torso_scale(points)
    normalized = {
        joint: _vector_scale(_vector_subtract(point, pelvis), 1.0 / scale)
        for joint, point in points.items()
    }
    normalized["Pelvis"] = (0.0, 0.0, 0.0)
    return normalized


def _frame_proxy_features(
    points: Mapping[str, Sequence[float]],
    *,
    dimensions: int,
) -> dict[str, float]:
    if dimensions not in {2, 3}:
        raise ValueError("dimensions must be 2 or 3")
    pelvis = points.get("Pelvis")
    if pelvis is None:
        pelvis = _vector_scale(_vector_add(points["Left Hip"], points["Right Hip"]), 0.5)
    shoulders = _vector_scale(
        _vector_add(points["Left Shoulder"], points["Right Shoulder"]),
        0.5,
    )
    vertical = _vector_subtract(shoulders, pelvis)
    body_lateral = _vector_subtract(points["Right Shoulder"], points["Left Shoulder"])
    head_lateral = _vector_subtract(points["Right Ear"], points["Left Ear"])

    upper_spine = 180.0 - _angle_degrees(points["Neck"], points["Back"], points["Waist"])
    lower_spine = 180.0 - _angle_degrees(points["Back"], points["Waist"], pelvis)
    spine_deviation = max(abs(upper_spine), abs(lower_spine))
    head_body_mismatch = _axis_angle_degrees(head_lateral, body_lateral)

    knee_angles: list[float] = []
    for side in ("Left", "Right"):
        hip = points[f"{side} Hip"]
        knee = points[f"{side} Knee"]
        ankle = points[f"{side} Ankle"]
        knee_angles.append(_angle_degrees(hip, knee, ankle))

    features = {
        "spine": spine_deviation,
        "head": head_body_mismatch,
        "knee_angle": _median(knee_angles),
    }
    if dimensions == 3:
        knee_foot_values: list[float] = []
        lateral_axis = _unit(body_lateral)
        for side in ("Left", "Right"):
            knee = points[f"{side} Knee"]
            ankle = points[f"{side} Ankle"]
            foot = points[f"{side} Foot"]
            # Compare only the body-lateral components of ankle->knee and ankle->toe.  This avoids
            # inventing a ground normal from torso lean, but remains a gross coronal offset proxy.
            knee_lateral = _dot(_vector_subtract(knee, ankle), lateral_axis)
            foot_lateral = _dot(_vector_subtract(foot, ankle), lateral_axis)
            knee_foot_values.append(
                abs(knee_lateral - foot_lateral) / _norm(body_lateral)
            )
        features["knee_foot"] = max(knee_foot_values)
    return features


def _camera_coordinate_foot_motion_features(
    frame_runs: Sequence[Sequence[Mapping[str, Sequence[float]]]],
) -> tuple[float, float]:
    valid_runs = [tuple(run) for run in frame_runs if len(run) >= 2]
    if not valid_runs:
        raise SequenceDataError("Temporal features require an active run with two frames")
    step_quantiles: list[float] = []
    excursion_quantiles: list[float] = []
    for side in ("Left", "Right"):
        normalized_steps: list[float] = []
        normalized_excursions: list[float] = []
        for run in valid_runs:
            scales = [_torso_scale(frame) for frame in run]
            positions = [frame[f"{side} Foot"] for frame in run]
            for index in range(1, len(positions)):
                step_scale = (scales[index - 1] + scales[index]) * 0.5
                normalized_steps.append(
                    _distance(positions[index - 1], positions[index]) / step_scale
                )
            center = tuple(_median(point[axis] for point in positions) for axis in range(3))
            median_scale = _median(scales)
            normalized_excursions.extend(
                _distance(center, point) / median_scale for point in positions
            )
        step_quantiles.append(_quantile(normalized_steps, 0.75))
        excursion_quantiles.append(_quantile(normalized_excursions, 0.90))
    return max(step_quantiles), max(excursion_quantiles)


def _contiguous_bottom_indices(
    feature_runs: Sequence[Sequence[Mapping[str, float]]],
) -> tuple[tuple[int, ...], float]:
    """Select a smoothed, contiguous nadir window and reject negligible squat motion."""

    eligible_runs: list[tuple[float, int, Sequence[Mapping[str, float]]]] = []
    for run_index, run in enumerate(feature_runs):
        if len(run) < 2:
            continue
        angles = [float(frame["knee_angle"]) for frame in run]
        run_rom = _quantile(angles, 0.90) - _quantile(angles, 0.10)
        if run_rom >= MINIMUM_PHASE_PROXY_ROM_DEGREES:
            eligible_runs.append((run_rom, run_index, run))
    if not eligible_runs:
        raise SequenceDataError(
            "No individual active run reaches the minimum phase-proxy knee ROM of "
            f"{MINIMUM_PHASE_PROXY_ROM_DEGREES:g} degrees"
        )
    rom, nadir_run_index, run = max(eligible_runs, key=lambda item: (item[0], -item[1]))
    angles = [float(frame["knee_angle"]) for frame in run]
    smoothed = [
        _median(angles[max(0, index - 1) : min(len(angles), index + 2)])
        for index in range(len(angles))
    ]
    nadir_local_index = min(range(len(smoothed)), key=lambda index: (smoothed[index], index))
    run_offset = sum(len(value) for value in feature_runs[:nadir_run_index])
    window_start = max(0, nadir_local_index - 1)
    window_end = min(len(run), nadir_local_index + 2)
    indices = tuple(run_offset + index for index in range(window_start, window_end))
    if len(indices) < 2:
        raise SequenceDataError("Bottom proxy must contain at least two contiguous active frames")
    return indices, rom


def extract_predeclared_features(
    frames_2d: Any,
    frames_3d: Any,
    diagnostics: dict[str, int] | None = None,
) -> dict[str, float | None]:
    """Extract one sequence-level value for every predeclared candidate contract."""

    if not isinstance(frames_2d, list) or not isinstance(frames_3d, list):
        raise SequenceDataError("2D and 3D frames must be arrays")
    if len(frames_2d) < 2 or len(frames_2d) != len(frames_3d):
        raise SequenceDataError("2D and 3D frame counts must match and contain at least two frames")

    active_indices, active_run_lengths, view_names = _active_frame_contract(frames_2d)
    active_frames_2d = [frames_2d[index] for index in active_indices]
    active_frames_3d = [frames_3d[index] for index in active_indices]
    if diagnostics is not None:
        diagnostics["activeFramesIncluded"] = len(active_indices)
        diagnostics["inactiveFramesExcluded"] = len(frames_2d) - len(active_indices)
        diagnostics["activeRuns"] = len(active_run_lengths)
        diagnostics["nonContiguousActiveSequences"] = int(len(active_run_lengths) > 1)

    raw_3d: list[dict[str, tuple[float, float, float]]] = []
    three_d_frame_features: list[dict[str, float]] = []
    for frame in active_frames_3d:
        if not isinstance(frame, dict):
            raise SequenceDataError("3D frame must be an object")
        raw = _raw_points(frame.get("pts"), dimensions=3)
        normalized = normalize_frame_points(frame.get("pts"))
        raw_3d.append(raw)
        three_d_frame_features.append(_frame_proxy_features(normalized, dimensions=3))
    three_d_feature_runs = _partition_runs(three_d_frame_features, active_run_lengths)
    raw_3d_runs = _partition_runs(raw_3d, active_run_lengths)
    bottom_indices: tuple[int, ...] | None
    try:
        bottom_indices, _ = _contiguous_bottom_indices(three_d_feature_runs)
    except SequenceDataError:
        bottom_indices = None
        if diagnostics is not None:
            diagnostics["phaseProxyUnknownSequences"] = 1
    foot_step_q75, foot_excursion_q90 = _camera_coordinate_foot_motion_features(raw_3d_runs)

    values: dict[str, float | None] = {
        "3d.whole.spine-chain-deviation-q75-deg": _quantile(
            (frame["spine"] for frame in three_d_frame_features),
            0.75,
        ),
        "3d.bottom.spine-chain-deviation-median-deg": (
            _median(three_d_frame_features[index]["spine"] for index in bottom_indices)
            if bottom_indices is not None
            else None
        ),
        "3d.whole.head-body-axis-mismatch-q75-deg": _quantile(
            (frame["head"] for frame in three_d_frame_features),
            0.75,
        ),
        "3d.bottom.head-body-axis-mismatch-median-deg": (
            _median(three_d_frame_features[index]["head"] for index in bottom_indices)
            if bottom_indices is not None
            else None
        ),
        "3d.whole.worst-side-knee-over-foot-body-lateral-offset-q75-shoulder-width": _quantile(
            (frame["knee_foot"] for frame in three_d_frame_features),
            0.75,
        ),
        "3d.bottom.worst-side-knee-over-foot-body-lateral-offset-median-shoulder-width": (
            _median(three_d_frame_features[index]["knee_foot"] for index in bottom_indices)
            if bottom_indices is not None
            else None
        ),
        "3d.active.worst-side-camera-coordinate-foot-step-q75-per-torso-scale":
            foot_step_q75,
        "3d.active.worst-side-camera-coordinate-foot-excursion-q90-per-torso-scale":
            foot_excursion_q90,
    }

    raw_by_view: dict[str, list[dict[str, tuple[float, float, float]]]] = defaultdict(list)
    feature_by_view: dict[str, list[dict[str, float]]] = defaultdict(list)
    for frame in active_frames_2d:
        for name in view_names:
            view = frame[name]
            raw = _raw_points(view.get("pts"), dimensions=2)
            normalized = _normalize_2d(raw)
            raw_by_view[name].append(raw)
            try:
                feature_by_view[name].append(_frame_proxy_features(normalized, dimensions=2))
            except SequenceDataError:
                if diagnostics is not None:
                    diagnostics["invalidTwoDProxyViewFrames"] = (
                        diagnostics.get("invalidTwoDProxyViewFrames", 0) + 1
                    )

    valid_geometry_views = tuple(
        name
        for name in view_names
        if len(feature_by_view[name]) >= 2
        and len(feature_by_view[name]) / len(active_frames_2d) >= 0.75
    )
    if len(valid_geometry_views) < 3:
        raise SequenceDataError("Fewer than three 2D views have sufficient proxy-frame coverage")
    if diagnostics is not None and len(valid_geometry_views) != len(view_names):
        diagnostics["excludedTwoDGeometryViews"] = (
            diagnostics.get("excludedTwoDGeometryViews", 0)
            + len(view_names)
            - len(valid_geometry_views)
        )
    values["2d.views.spine-chain-deviation-q75-median-deg"] = _median(
        _quantile((frame["spine"] for frame in feature_by_view[name]), 0.75)
        for name in valid_geometry_views
    )
    values["2d.views.head-body-axis-mismatch-q75-median-deg"] = _median(
        _quantile((frame["head"] for frame in feature_by_view[name]), 0.75)
        for name in valid_geometry_views
    )
    values[
        "2d.active.views.worst-side-camera-coordinate-foot-step-q75-per-torso-scale-median"
    ] = _median(
        _camera_coordinate_foot_motion_features(
            _partition_runs(raw_by_view[name], active_run_lengths)
        )[0]
        for name in view_names
    )

    expected_features = {feature for features in CANDIDATE_FEATURES.values() for feature in features}
    if values.keys() != expected_features:
        raise SequenceDataError("Extracted feature contract does not match predeclared candidates")
    if not all(value is None or math.isfinite(value) for value in values.values()):
        raise SequenceDataError("Every extracted feature must be finite")
    return {
        name: (_round(values[name]) if values[name] is not None else None)
        for name in sorted(values)
    }


def _condition_tuple(conditions: tuple[tuple[str, bool], ...]) -> tuple[bool, ...]:
    values = dict(conditions)
    if set(values) != set(CONDITIONS):
        raise SequenceDataError("Barbell squat must expose the four approved condition strings")
    return tuple(values[name] for name in CONDITIONS)


def _expected_condition_tuple(type_code: str) -> tuple[bool, ...]:
    bits = EXPECTED_BITS[type_code]
    return tuple(bit == "1" for bit in bits)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_lf_text_sha256(path: Path) -> str:
    """Hash repository text independently of checkout newline conventions."""

    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as error:
        raise SquatExperimentError(f"Cannot hash UTF-8 repository text: {path}") from error
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _canonical_fields_sha256(fields: Sequence[tuple[str, str]]) -> str:
    payload = "".join(
        f"{name}:{len(value.encode('utf-8'))}:{value}\n" for name, value in fields
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _catalog_coverage_sha256(exercise: Mapping[str, Any], catalog_sha256: str) -> str:
    fields: list[tuple[str, str]] = [
        ("schemaVersion", "1"),
        ("exerciseId", "barbell-squat"),
        ("catalogSha256", catalog_sha256),
        ("criterionCount", str(len(CONDITIONS))),
    ]
    for index, condition in enumerate(CONDITIONS):
        fields.extend(
            [
                (f"criterion[{index}].semanticId", str(CRITERION_CONTRACTS[condition]["semanticId"])),
                (f"criterion[{index}].sourceCondition", condition),
            ]
        )
    types = exercise.get("types")
    if not isinstance(types, list):
        raise SquatExperimentError("AI Hub catalog barbell-squat types must be an array")
    fields.append(("typeCount", str(len(types))))
    for index, type_entry in enumerate(types):
        if not isinstance(type_entry, dict):
            raise SquatExperimentError("AI Hub catalog type entry must be an object")
        conditions = type_entry.get("conditions")
        if not isinstance(conditions, list):
            raise SquatExperimentError("AI Hub catalog type conditions must be an array")
        source_names = [item.get("condition") for item in conditions if isinstance(item, dict)]
        if source_names != list(CONDITIONS):
            raise SquatExperimentError("AI Hub catalog source condition order changed")
        truth_bits = "".join(
            "1" if item.get("value") is True else "0" if item.get("value") is False else "?"
            for item in conditions
            if isinstance(item, dict)
        )
        if "?" in truth_bits or len(truth_bits) != len(CONDITIONS):
            raise SquatExperimentError("AI Hub catalog type truth values must be Boolean")
        fields.extend(
            [
                (f"type[{index}].code", str(type_entry.get("code"))),
                (f"type[{index}].recordCount", str(type_entry.get("recordCount"))),
                (f"type[{index}].truthBits", truth_bits),
            ]
        )
    return _canonical_fields_sha256(fields)


def _two_d_coordinate_payload(frames: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            name: frame[name]["pts"]
            for name in sorted(key for key in frame if key.startswith("view"))
        }
        for frame in frames
    ]


def _three_d_coordinate_payload(frames: Sequence[Mapping[str, Any]]) -> list[Any]:
    return [frame.get("pts") for frame in frames]


def _active_semantic_payload(frames: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    payload: list[dict[str, Any]] = []
    for frame in frames:
        first_view = frame[sorted(key for key in frame if key.startswith("view"))[0]]
        match = _IMAGE_FRAME_ORDINAL.search(Path(first_view["img_key"]).stem)
        if match is None:
            raise SequenceDataError("Validated img_key unexpectedly lost its ordinal")
        payload.append({"frameOrdinal": int(match.group(1)), "active": first_view["active"]})
    return payload


def _discover_candidates(root: Path) -> tuple[list[Candidate], dict[str, int]]:
    all_json = sorted(root.rglob("*.json"), key=lambda path: path.relative_to(root).as_posix())
    two_d_paths = [path for path in all_json if not path.name.endswith("-3d.json")]
    if not two_d_paths:
        raise SquatExperimentError(f"No 2D JSON files found under {root}")

    inventory = {
        "twoDMetadataAudited": len(two_d_paths),
        "validMetadata": 0,
        "malformedMetadata": 0,
        "selectedMetadata": 0,
        "unpaired": 0,
        "metadataConflict": 0,
        "activeFramesIncluded": 0,
        "inactiveFramesExcluded": 0,
        "activeRuns": 0,
        "nonContiguousActiveSequences": 0,
        "invalidTwoDProxyViewFrames": 0,
        "excludedTwoDGeometryViews": 0,
        "phaseProxyUnknownSequences": 0,
    }
    identities_by_type: dict[str, set[tuple[str, tuple[tuple[str, bool], ...]]]] = defaultdict(set)
    provisional: list[Candidate] = []
    for path in two_d_paths:
        try:
            type_code, exercise, normalized_conditions = decode_metadata_tail(path)
        except (OSError, SequenceDataError):
            inventory["malformedMetadata"] += 1
            continue
        inventory["validMetadata"] += 1
        identities_by_type[type_code].add((exercise, normalized_conditions))
        if exercise != EXERCISE:
            continue
        inventory["selectedMetadata"] += 1
        if type_code not in EXPECTED_BITS:
            inventory["metadataConflict"] += 1
            continue
        try:
            condition_values = _condition_tuple(normalized_conditions)
        except SequenceDataError:
            inventory["metadataConflict"] += 1
            continue
        if condition_values != _expected_condition_tuple(type_code):
            inventory["metadataConflict"] += 1
            continue
        three_d = paired_three_d_path(path)
        if not three_d.is_file():
            inventory["unpaired"] += 1
            continue
        sequence_id = path.relative_to(root).with_suffix("").as_posix()
        _, day_id = _selection_identity(path, sequence_id)
        provisional.append(
            Candidate(
                two_d_path=path,
                three_d_path=three_d,
                sequence_id=sequence_id,
                type_code=type_code,
                conditions=condition_values,
                selection_day_id=day_id,
            )
        )

    conflicting_types = {
        type_code for type_code, identities in identities_by_type.items() if len(identities) > 1
    }
    if conflicting_types & set(TYPE_CODES):
        raise SquatExperimentError(
            "Barbell squat type identity drift: " + ", ".join(sorted(conflicting_types))
        )
    return provisional, inventory


def _verified_catalog_provenance() -> dict[str, Any]:
    catalog_path = Path(__file__).resolve().parent.parent / "docs" / "aihub-exercise-catalog.json"
    catalog = _load_json_object(catalog_path)
    if catalog.get("catalogSha256") != APPROVED_CATALOG_SHA256:
        raise SquatExperimentError("Approved AI Hub catalog SHA-256 changed")
    exercises = catalog.get("exercises")
    if not isinstance(exercises, list):
        raise SquatExperimentError("AI Hub catalog exercises must be an array")
    matches = [item for item in exercises if isinstance(item, dict) and item.get("id") == "barbell-squat"]
    if len(matches) != 1:
        raise SquatExperimentError("AI Hub catalog must contain one barbell-squat entry")
    exercise = matches[0]
    if (
        exercise.get("name") != EXERCISE
        or exercise.get("recordCount") != EXPECTED_SPLITS["TRAINING"]["recordsPerType"]
        * len(TYPE_CODES)
        or not isinstance(exercise.get("types"), list)
        or [item.get("code") for item in exercise["types"] if isinstance(item, dict)]
        != list(TYPE_CODES)
    ):
        raise SquatExperimentError("AI Hub barbell-squat catalog contract changed")
    coverage_sha256 = _catalog_coverage_sha256(exercise, APPROVED_CATALOG_SHA256)
    if coverage_sha256 != APPROVED_COVERAGE_SHA256:
        raise SquatExperimentError("Approved barbell-squat criterion coverage SHA-256 changed")
    return {
        "relativePath": "docs/aihub-exercise-catalog.json",
        "catalogSha256": APPROVED_CATALOG_SHA256,
        "artifactCanonicalLfTextSha256": _canonical_lf_text_sha256(catalog_path),
        "textHashNormalization": TEXT_HASH_NORMALIZATION,
        "barbellSquatCoverageSha256": coverage_sha256,
    }


def _validate_internal_sequence_uniqueness(
    observations: Sequence[Observation],
    split_name: str,
) -> None:
    hash_contracts = {
        "2D raw content": [item.two_d_sha256 for item in observations],
        "3D raw content": [item.three_d_sha256 for item in observations],
        "2D canonical coordinates": [
            item.two_d_coordinate_sha256 for item in observations
        ],
        "3D canonical coordinates": [
            item.three_d_coordinate_sha256 for item in observations
        ],
    }
    for name, values in hash_contracts.items():
        duplicate_count = len(values) - len(set(values))
        if duplicate_count:
            raise SquatExperimentError(
                f"{split_name} contains {duplicate_count} duplicate {name} sequences"
            )


def load_split(root: Path, split_name: str) -> tuple[list[Observation], dict[str, Any]]:
    root = root.resolve()
    if not root.is_dir():
        raise SquatExperimentError(f"{split_name} root is not a directory: {root}")
    expected = EXPECTED_SPLITS[split_name]
    candidates, inventory = _discover_candidates(root)
    counts_by_type: dict[str, int] = defaultdict(int)
    observations: list[Observation] = []
    quality_failures: dict[str, int] = defaultdict(int)

    for candidate in candidates:
        try:
            two_d = _load_json_object(candidate.two_d_path)
            three_d = _load_json_object(candidate.three_d_path)
            frames_2d = two_d.get("frames")
            frames_3d = three_d.get("frames")
            if not isinstance(frames_2d, list) or not frames_2d:
                raise SequenceDataError("2D frames must be a non-empty array")
            if not isinstance(frames_3d, list) or not frames_3d:
                raise SequenceDataError("3D frames must be a non-empty array")
            if len(frames_2d) != len(frames_3d):
                raise SequenceDataError("2D/3D frame count mismatch")
            subject_id = extract_subject_id(two_d, candidate.two_d_path)
            if candidate.selection_day_id.startswith("__UNKNOWN"):
                raise SequenceDataError("Day identity is unavailable")
            feature_diagnostics: dict[str, int] = {}
            features = extract_predeclared_features(
                frames_2d,
                frames_3d,
                diagnostics=feature_diagnostics,
            )
            two_d_content_sha = _file_sha256(candidate.two_d_path)
            three_d_content_sha = _file_sha256(candidate.three_d_path)
            two_d_coordinate_sha = _canonical_sha256(_two_d_coordinate_payload(frames_2d))
            three_d_coordinate_sha = _canonical_sha256(
                _three_d_coordinate_payload(frames_3d)
            )
            active_contract_sha = _canonical_sha256(_active_semantic_payload(frames_2d))
        except OSError:
            quality_failures["ioError"] += 1
            continue
        except SequenceDataError as error:
            quality_failures[str(error)] += 1
            continue
        observations.append(
            Observation(
                sequence_id=candidate.sequence_id,
                type_code=candidate.type_code,
                subject_id=subject_id,
                day_id=candidate.selection_day_id,
                conditions=candidate.conditions,
                features=features,
                frame_count=len(frames_3d),
                active_frame_count=feature_diagnostics["activeFramesIncluded"],
                two_d_sha256=two_d_content_sha,
                three_d_sha256=three_d_content_sha,
                two_d_coordinate_sha256=two_d_coordinate_sha,
                three_d_coordinate_sha256=three_d_coordinate_sha,
                active_contract_sha256=active_contract_sha,
            )
        )
        for name, count in feature_diagnostics.items():
            inventory[name] = inventory.get(name, 0) + count
        counts_by_type[candidate.type_code] += 1

    expected_codes = set(TYPE_CODES)
    if set(counts_by_type) != expected_codes:
        raise SquatExperimentError(
            f"{split_name} type coverage mismatch: "
            f"missing={sorted(expected_codes - set(counts_by_type))}, "
            f"unexpected={sorted(set(counts_by_type) - expected_codes)}"
        )
    wrong_counts = {
        code: count
        for code, count in counts_by_type.items()
        if count != expected["recordsPerType"]
    }
    if wrong_counts:
        raise SquatExperimentError(
            f"{split_name} per-type record counts changed: {wrong_counts}; "
            f"quality={dict(sorted(quality_failures.items()))}"
        )
    if quality_failures or inventory["metadataConflict"] or inventory["unpaired"]:
        raise SquatExperimentError(
            f"{split_name} contains unusable selected data: "
            f"quality={dict(quality_failures)}, metadata={inventory}"
        )

    observations.sort(key=lambda item: item.sequence_id)
    _validate_internal_sequence_uniqueness(observations, split_name)
    subjects = sorted({item.subject_id for item in observations})
    if len(subjects) != expected["subjects"]:
        raise SquatExperimentError(
            f"{split_name} expected {expected['subjects']} subjects, found {len(subjects)}"
        )
    frame_histogram: dict[str, int] = defaultdict(int)
    active_frame_histogram: dict[str, int] = defaultdict(int)
    for item in observations:
        frame_histogram[str(item.frame_count)] += 1
        active_frame_histogram[str(item.active_frame_count)] += 1
    fingerprint_payload = [
        {
            "sequenceId": item.sequence_id,
            "typeCode": item.type_code,
            "subjectId": item.subject_id,
            "conditions": item.conditions,
            "features": item.features,
            "twoDSha256": item.two_d_sha256,
            "threeDSha256": item.three_d_sha256,
            "twoDCoordinateSha256": item.two_d_coordinate_sha256,
            "threeDCoordinateSha256": item.three_d_coordinate_sha256,
            "activeContractSha256": item.active_contract_sha256,
        }
        for item in observations
    ]
    return observations, {
        "split": split_name,
        "sequenceCount": len(observations),
        "subjectCount": len(subjects),
        "dayIds": sorted({item.day_id for item in observations}),
        "typeCounts": {code: counts_by_type[code] for code in TYPE_CODES},
        "frameCountHistogram": dict(sorted(frame_histogram.items(), key=lambda item: int(item[0]))),
        "activeFrameCountHistogram": dict(
            sorted(active_frame_histogram.items(), key=lambda item: int(item[0]))
        ),
        "metadataInventory": inventory,
        "coordinateFingerprintSha256": _canonical_sha256(fingerprint_payload),
        "twoDContentSetSha256": _canonical_sha256(
            sorted(item.two_d_sha256 for item in observations)
        ),
        "threeDContentSetSha256": _canonical_sha256(
            sorted(item.three_d_sha256 for item in observations)
        ),
        "twoDCoordinateSetSha256": _canonical_sha256(
            sorted(item.two_d_coordinate_sha256 for item in observations)
        ),
        "threeDCoordinateSetSha256": _canonical_sha256(
            sorted(item.three_d_coordinate_sha256 for item in observations)
        ),
        "activeContractManifestSha256": _canonical_sha256(
            [
                {"sequenceId": item.sequence_id, "sha256": item.active_contract_sha256}
                for item in observations
            ]
        ),
        "internalSequenceHashUniqueness": {
            "twoDRawContent": True,
            "threeDRawContent": True,
            "twoDCanonicalCoordinates": True,
            "threeDCanonicalCoordinates": True,
        },
    }


def _subject_label_centers(
    observations: Sequence[Observation],
    condition_index: int,
    feature: str,
) -> tuple[list[float], list[float]]:
    by_subject_label: dict[tuple[str, bool], list[float]] = defaultdict(list)
    for item in observations:
        value = item.features[feature]
        if value is not None:
            by_subject_label[(item.subject_id, item.conditions[condition_index])].append(value)
    true_centers = [
        _median(values) for (subject, label), values in by_subject_label.items() if label
    ]
    false_centers = [
        _median(values) for (subject, label), values in by_subject_label.items() if not label
    ]
    if not true_centers or not false_centers:
        raise SquatExperimentError("Both condition labels need subject support")
    return true_centers, false_centers


def fit_threshold(
    observations: Sequence[Observation],
    condition_index: int,
    feature: str,
) -> dict[str, float | bool | str]:
    true_values, false_values = _subject_label_centers(observations, condition_index, feature)
    true_center = _median(true_values)
    false_center = _median(false_values)
    separation = false_center - true_center
    robust_noise = 1.4826 * max(
        _median_absolute_deviation(true_values),
        _median_absolute_deviation(false_values),
    )
    return {
        "trueCenter": _round(true_center),
        "falseCenter": _round(false_center),
        "threshold": _round((true_center + false_center) * 0.5),
        "abstentionHalfWidth": _round(robust_noise),
        "abstentionPolicy": "MAX_CLASS_SUBJECT_MEDIAN_MAD_X_1_4826",
        "semanticDirectionValid": separation > EPSILON,
    }


def _predict(
    value: float,
    threshold: float,
    abstention_half_width: float,
) -> bool | None:
    if abstention_half_width > 0.0 and abs(value - threshold) <= abstention_half_width:
        return None
    return value < threshold


def _bootstrap_mean_interval(values: Sequence[float]) -> tuple[float, float] | None:
    if not values:
        return None
    if len(values) == 1:
        return float(values[0]), float(values[0])
    seed_payload = json.dumps([_round(value) for value in values], separators=(",", ":"))
    seed = int(hashlib.sha256(seed_payload.encode("utf-8")).hexdigest()[:16], 16)
    generator = random.Random(seed)
    bootstrap_means = sorted(
        statistics.fmean(generator.choice(values) for _ in values)
        for _ in range(4096)
    )
    return _quantile(bootstrap_means, 0.025), _quantile(bootstrap_means, 0.975)


def _wilson_interval(successes: int, total: int) -> tuple[float, float] | None:
    if total <= 0:
        return None
    z = 1.959963984540054
    proportion = successes / total
    denominator = 1.0 + z * z / total
    center = (proportion + z * z / (2.0 * total)) / denominator
    half = (
        z
        * math.sqrt(proportion * (1.0 - proportion) / total + z * z / (4.0 * total * total))
        / denominator
    )
    return max(0.0, center - half), min(1.0, center + half)


def _metrics(predictions: Sequence[Prediction]) -> dict[str, Any]:
    if not predictions:
        raise SquatExperimentError("Cannot score empty predictions")
    tp = fp = tn = fn = unknown = 0
    by_subject: dict[str, list[Prediction]] = defaultdict(list)
    for item in predictions:
        by_subject[item.subject_id].append(item)
        if item.predicted is None:
            unknown += 1
        elif item.actual and item.predicted:
            tp += 1
        elif item.actual and not item.predicted:
            fn += 1
        elif not item.actual and item.predicted:
            fp += 1
        else:
            tn += 1

    sensitivity = tp / (tp + fn) if tp + fn else None
    specificity = tn / (tn + fp) if tn + fp else None
    balanced = (
        (sensitivity + specificity) * 0.5
        if sensitivity is not None and specificity is not None
        else None
    )
    subject_balanced: list[float] = []
    subject_coverages: list[float] = []
    subjects_with_both_class_evidence = 0
    for values in by_subject.values():
        determinate = [value for value in values if value.predicted is not None]
        subject_coverages.append(len(determinate) / len(values))
        subject_tp = sum(value.actual and value.predicted is True for value in determinate)
        subject_fn = sum(value.actual and value.predicted is False for value in determinate)
        subject_tn = sum(not value.actual and value.predicted is False for value in determinate)
        subject_fp = sum(not value.actual and value.predicted is True for value in determinate)
        if subject_tp + subject_fn and subject_tn + subject_fp:
            subjects_with_both_class_evidence += 1
            subject_balanced.append(
                (
                    subject_tp / (subject_tp + subject_fn)
                    + subject_tn / (subject_tn + subject_fp)
                )
                * 0.5
            )
    true_total = sum(item.actual for item in predictions)
    false_total = len(predictions) - true_total
    true_determinate = sum(item.actual and item.predicted is not None for item in predictions)
    false_determinate = sum(not item.actual and item.predicted is not None for item in predictions)
    bootstrap_interval = _bootstrap_mean_interval(subject_balanced)
    return {
        "sequenceCount": len(predictions),
        "subjectCount": len(by_subject),
        "subjectMetricCount": len(subject_balanced),
        "determinateCount": len(predictions) - unknown,
        "unknownCount": unknown,
        "coverage": _round((len(predictions) - unknown) / len(predictions)),
        "trueLabelCoverage": _round(true_determinate / true_total) if true_total else None,
        "falseLabelCoverage": _round(false_determinate / false_total) if false_total else None,
        "minimumSubjectCoverage": _round(min(subject_coverages)),
        "p10SubjectCoverage": _round(_quantile(subject_coverages, 0.10)),
        "subjectsWithBothClassEvidence": subjects_with_both_class_evidence,
        "sensitivity": _round(sensitivity) if sensitivity is not None else None,
        "specificity": _round(specificity) if specificity is not None else None,
        "balancedAccuracy": _round(balanced) if balanced is not None else None,
        "subjectMacroBalancedAccuracy": (
            _round(statistics.fmean(subject_balanced)) if subject_balanced else None
        ),
        "subjectMedianBalancedAccuracy": (
            _round(_median(subject_balanced)) if subject_balanced else None
        ),
        "subjectMacroBalancedAccuracyBootstrap95": (
            {
                "low": _round(bootstrap_interval[0]),
                "high": _round(bootstrap_interval[1]),
                "method": "DETERMINISTIC_SUBJECT_CLUSTER_PERCENTILE_BOOTSTRAP_4096",
            }
            if bootstrap_interval is not None
            else None
        ),
        "confusion": {"tp": tp, "fn": fn, "tn": tn, "fp": fp},
    }


def leave_one_subject_out_metrics(
    observations: Sequence[Observation],
    condition_index: int,
    feature: str,
    selective: bool,
) -> dict[str, Any]:
    subjects = sorted({item.subject_id for item in observations})
    predictions: list[Prediction] = []
    invalid_direction_folds = 0
    for subject in subjects:
        training = [item for item in observations if item.subject_id != subject]
        held_out = [item for item in observations if item.subject_id == subject]
        fit = fit_threshold(training, condition_index, feature)
        if not fit["semanticDirectionValid"]:
            invalid_direction_folds += 1
        margin = float(fit["abstentionHalfWidth"]) if selective else 0.0
        for item in held_out:
            value = item.features[feature]
            predictions.append(
                Prediction(
                    subject_id=subject,
                    actual=item.conditions[condition_index],
                    predicted=(
                        _predict(value, float(fit["threshold"]), margin)
                        if value is not None
                        else None
                    ),
                )
            )
    result = _metrics(predictions)
    result["foldCount"] = len(subjects)
    result["invalidSemanticDirectionFolds"] = invalid_direction_folds
    return result


def locked_metrics(
    observations: Sequence[Observation],
    condition_index: int,
    feature: str,
    fit: Mapping[str, float | bool | str],
    selective: bool,
) -> dict[str, Any]:
    margin = float(fit["abstentionHalfWidth"]) if selective else 0.0
    return _metrics(
        [
            Prediction(
                subject_id=item.subject_id,
                actual=item.conditions[condition_index],
                predicted=(
                    _predict(item.features[feature], float(fit["threshold"]), margin)
                    if item.features[feature] is not None
                    else None
                ),
            )
            for item in observations
        ]
    )


def paired_sign_report(
    observations: Sequence[Observation],
    condition_index: int,
    feature: str,
) -> dict[str, Any]:
    by_cell_label: dict[tuple[str, str, tuple[bool, ...], bool], list[float]] = defaultdict(list)
    for item in observations:
        other_conditions = tuple(
            value for index, value in enumerate(item.conditions) if index != condition_index
        )
        label = item.conditions[condition_index]
        value = item.features[feature]
        if value is not None:
            by_cell_label[(item.subject_id, item.day_id, other_conditions, label)].append(value)
    effects_by_subject: dict[str, list[float]] = defaultdict(list)
    paired_cells = 0
    base_cells = {
        (subject, day, others)
        for subject, day, others, label in by_cell_label
    }
    for subject, day, others in sorted(base_cells):
        true_values = by_cell_label.get((subject, day, others, True))
        false_values = by_cell_label.get((subject, day, others, False))
        if not true_values or not false_values:
            continue
        paired_cells += 1
        effects_by_subject[subject].append(_median(true_values) - _median(false_values))
    subject_effects = {
        subject: _median(values) for subject, values in sorted(effects_by_subject.items())
    }
    consistent = sum(effect < -EPSILON for effect in subject_effects.values())
    interval = _wilson_interval(consistent, len(subject_effects))
    return {
        "estimand": "MATCHED_SUBJECT_DAY_OTHER_CONDITION_CELL_NOT_SAME_REPETITION",
        "matchedSubjectDayConditionCells": paired_cells,
        "matchedSubjects": len(subject_effects),
        "minimumMatchedCellsPerSubject": (
            min(len(values) for values in effects_by_subject.values())
            if effects_by_subject
            else 0
        ),
        "medianTrueMinusFalse": (
            _round(_median(subject_effects.values())) if subject_effects else None
        ),
        "semanticTrueLowerConsistency": (
            _round(consistent / len(subject_effects)) if subject_effects else None
        ),
        "semanticTrueLowerConsistencyWilson95": (
            {"low": _round(interval[0]), "high": _round(interval[1])}
            if interval is not None
            else None
        ),
        "zeroEffectSubjects": sum(
            abs(effect) <= EPSILON for effect in subject_effects.values()
        ),
    }


def evaluate_criteria(
    training: Sequence[Observation],
    validation: Sequence[Observation],
) -> list[dict[str, Any]]:
    reports: list[dict[str, Any]] = []
    for condition_index, condition in enumerate(CONDITIONS):
        candidates: list[dict[str, Any]] = []
        for feature in CANDIDATE_FEATURES[condition]:
            fit = fit_threshold(training, condition_index, feature)
            ordinary = leave_one_subject_out_metrics(
                training,
                condition_index,
                feature,
                selective=False,
            )
            selective = leave_one_subject_out_metrics(
                training,
                condition_index,
                feature,
                selective=True,
            )
            paired = paired_sign_report(training, condition_index, feature)
            candidates.append(
                {
                    "feature": feature,
                    "expectedDirection": "TRUE_LOWER",
                    "fullTrainingFit": fit,
                    "trainingLoso": ordinary,
                    "trainingLosoSelective": selective,
                    "trainingPairedContrast": paired,
                }
            )

        def selection_key(candidate: Mapping[str, Any]) -> tuple[float, float, float, str]:
            fit_valid = 1.0 if candidate["fullTrainingFit"]["semanticDirectionValid"] else 0.0
            balanced = candidate["trainingLoso"]["subjectMacroBalancedAccuracy"] or -1.0
            consistency = (
                candidate["trainingPairedContrast"]["semanticTrueLowerConsistency"] or -1.0
            )
            return (-fit_valid, -balanced, -consistency, str(candidate["feature"]))

        candidates.sort(key=selection_key)
        selected = candidates[0]
        selected_feature = str(selected["feature"])
        fit = selected["fullTrainingFit"]
        validation_ordinary = locked_metrics(
            validation,
            condition_index,
            selected_feature,
            fit,
            selective=False,
        )
        validation_selective = locked_metrics(
            validation,
            condition_index,
            selected_feature,
            fit,
            selective=True,
        )
        validation_paired = paired_sign_report(validation, condition_index, selected_feature)

        train_ba = selected["trainingLoso"]["subjectMacroBalancedAccuracy"] or 0.0
        validation_ba = validation_ordinary["subjectMacroBalancedAccuracy"] or 0.0
        train_consistency = (
            selected["trainingPairedContrast"]["semanticTrueLowerConsistency"] or 0.0
        )
        validation_consistency = validation_paired["semanticTrueLowerConsistency"] or 0.0
        train_selective = selected["trainingLosoSelective"]
        train_bootstrap = selected["trainingLoso"][
            "subjectMacroBalancedAccuracyBootstrap95"
        ]
        validation_bootstrap = validation_ordinary[
            "subjectMacroBalancedAccuracyBootstrap95"
        ]
        train_wilson = selected["trainingPairedContrast"][
            "semanticTrueLowerConsistencyWilson95"
        ]
        validation_wilson = validation_paired[
            "semanticTrueLowerConsistencyWilson95"
        ]
        proxy_signal_replicated = bool(
            fit["semanticDirectionValid"]
            and selected["trainingLoso"]["invalidSemanticDirectionFolds"] == 0
            and train_ba
            >= PROXY_RESEARCH_SIGNAL_POLICY[
                "minimumTrainingLosoSubjectMacroBalancedAccuracy"
            ]
            and validation_ba
            >= PROXY_RESEARCH_SIGNAL_POLICY[
                "minimumLockedValidationSubjectMacroBalancedAccuracy"
            ]
            and train_consistency
            >= PROXY_RESEARCH_SIGNAL_POLICY[
                "minimumTrainingAndValidationMatchedDirectionConsistency"
            ]
            and validation_consistency
            >= PROXY_RESEARCH_SIGNAL_POLICY[
                "minimumTrainingAndValidationMatchedDirectionConsistency"
            ]
            and train_bootstrap is not None
            and train_bootstrap["low"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSubjectBootstrap95Low"]
            and validation_bootstrap is not None
            and validation_bootstrap["low"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSubjectBootstrap95Low"]
            and train_wilson is not None
            and train_wilson["low"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumMatchedDirectionWilson95Low"]
            and validation_wilson is not None
            and validation_wilson["low"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumMatchedDirectionWilson95Low"]
            and train_selective["subjectMacroBalancedAccuracy"] is not None
            and train_selective["subjectMacroBalancedAccuracy"]
            >= PROXY_RESEARCH_SIGNAL_POLICY[
                "minimumTrainingLosoSubjectMacroBalancedAccuracy"
            ]
            and validation_selective["subjectMacroBalancedAccuracy"] is not None
            and validation_selective["subjectMacroBalancedAccuracy"]
            >= PROXY_RESEARCH_SIGNAL_POLICY[
                "minimumLockedValidationSubjectMacroBalancedAccuracy"
            ]
            and train_selective["subjectMetricCount"] == train_selective["subjectCount"]
            and validation_selective["subjectMetricCount"]
            == validation_selective["subjectCount"]
            and train_selective["trueLabelCoverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSelectiveClassAndSubjectCoverage"]
            and train_selective["falseLabelCoverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSelectiveClassAndSubjectCoverage"]
            and validation_selective["trueLabelCoverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSelectiveClassAndSubjectCoverage"]
            and validation_selective["falseLabelCoverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSelectiveClassAndSubjectCoverage"]
            and train_selective["minimumSubjectCoverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSelectiveClassAndSubjectCoverage"]
            and validation_selective["minimumSubjectCoverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumSelectiveClassAndSubjectCoverage"]
            and validation_selective["coverage"]
            >= PROXY_RESEARCH_SIGNAL_POLICY["minimumLockedSelectiveCoverage"]
        )
        criterion_contract = dict(CRITERION_CONTRACTS[condition])
        exact_condition_release_state = (
            "BLOCKED_NOT_OBSERVABLE_FROM_POSE_COORDINATES"
            if criterion_contract["exactConditionObservability"]
            == "NOT_OBSERVABLE_FROM_POSE_COORDINATES"
            else "BLOCKED_NO_MEDIAPIPE_GOLD_OR_UNTOUCHED_EXTERNAL_TEST"
        )
        reports.append(
            {
                "condition": condition,
                "criterionContract": criterion_contract,
                "expectedDirection": "TRUE_LOWER_PROXY_ERROR",
                "selectionProtocol": (
                    "PREDECLARED_FEATURES_TRAINING_GLOBAL_Z_LOSO_ONLY_"
                    "VALIDATION_NOT_USED_FOR_SELECTION"
                ),
                "candidateCount": len(candidates),
                "trainingCandidates": candidates,
                "selectedFeature": selected_feature,
                "lockedValidation": validation_ordinary,
                "lockedValidationSelective": validation_selective,
                "lockedValidationPairedContrast": validation_paired,
                "validationRole": (
                    "CONSUMED_DESCRIPTIVE_DEVELOPMENT_REPLICATION_"
                    "NOT_PRISTINE_CONFIRMATORY_HOLDOUT"
                ),
                "proxyResearchState": (
                    "REPLICATED_COORDINATE_PROXY_SIGNAL_NOT_RELEASE_ELIGIBLE"
                    if proxy_signal_replicated
                    else "INSUFFICIENT_ROBUST_REPLICATION"
                ),
                "exactConditionReleaseState": exact_condition_release_state,
            }
        )
    return reports


def run_experiment(training_root: Path, validation_root: Path) -> dict[str, Any]:
    catalog_provenance = _verified_catalog_provenance()
    training, training_inventory = load_split(training_root, "TRAINING")
    validation, validation_inventory = load_split(validation_root, "VALIDATION")
    training_subjects = {item.subject_id for item in training}
    validation_subjects = {item.subject_id for item in validation}
    subject_overlap = sorted(training_subjects & validation_subjects)
    if subject_overlap:
        raise SquatExperimentError(f"Training/Validation subject overlap: {subject_overlap}")
    overlap_contracts = {
        "2D raw content": (
            {item.two_d_sha256 for item in training},
            {item.two_d_sha256 for item in validation},
        ),
        "3D raw content": (
            {item.three_d_sha256 for item in training},
            {item.three_d_sha256 for item in validation},
        ),
        "2D canonical coordinates": (
            {item.two_d_coordinate_sha256 for item in training},
            {item.two_d_coordinate_sha256 for item in validation},
        ),
        "3D canonical coordinates": (
            {item.three_d_coordinate_sha256 for item in training},
            {item.three_d_coordinate_sha256 for item in validation},
        ),
    }
    for name, (training_hashes, validation_hashes) in overlap_contracts.items():
        overlap = training_hashes & validation_hashes
        if overlap:
            raise SquatExperimentError(
                f"Training/Validation {name} overlap: {len(overlap)} sequences"
            )

    report: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": ARTIFACT_KIND,
        "decisionUse": DECISION_USE,
        "serviceReleaseEligible": False,
        "authenticity": "UNSIGNED_RESEARCH_ARTIFACT_NEVER_LOAD_IN_USER_RUNTIME",
        "experimentFamilyId": EXPERIMENT_FAMILY_ID,
        "protocolContract": PROTOCOL_CONTRACT,
        "protocolArtifactSha256": _canonical_sha256(PROTOCOL_CONTRACT),
        "approvedAiHubCatalog": catalog_provenance,
        "implementationProvenance": {
            "scriptCanonicalLfTextSha256":
                _canonical_lf_text_sha256(Path(__file__).resolve()),
            "sharedParserCanonicalLfTextSha256": _canonical_lf_text_sha256(
                Path(__file__).resolve().with_name("analyze_pose_coordinate_criteria.py")
            ),
            "textHashNormalization": TEXT_HASH_NORMALIZATION,
            "pythonVersion": sys.version.split()[0],
            "dependencies": "PYTHON_STANDARD_LIBRARY_ONLY_PLUS_PINNED_SHARED_PARSER_SOURCE",
        },
        "exercise": EXERCISE,
        "typeCodes": list(TYPE_CODES),
        "conditions": list(CONDITIONS),
        "conditionTruthVectors": EXPECTED_BITS,
        "splitProtocol": {
            "independentUnit": "GLOBAL_Z_SUBJECT",
            "trainingSelection": "GLOBAL_Z_LEAVE_ONE_SUBJECT_OUT",
            "validationProtocol": (
                "LOCKED_WITHIN_RUN_BUT_CONSUMED_DESCRIPTIVE_DEVELOPMENT_REPLICATION"
            ),
            "framesIndependentN": False,
            "viewsIndependentN": False,
            "trainingValidationSubjectOverlap": 0,
            "trainingValidationTwoDRawContentOverlap": 0,
            "trainingValidationThreeDContentOverlap": 0,
            "trainingValidationTwoDCanonicalCoordinateOverlap": 0,
            "trainingValidationThreeDCanonicalCoordinateOverlap": 0,
        },
        "proxyResearchSignalPolicy": PROXY_RESEARCH_SIGNAL_POLICY,
        "featureContract": {
            "candidateFeaturesByCondition": CANDIDATE_FEATURES,
            "normalization": "PELVIS_CENTER_TORSO_SCALE_FOR_GEOMETRY",
            "phaseProxy": (
                "MAX_ROM_ELIGIBLE_ACTIVE_RUN_THREE_SAMPLE_MEDIAN_CONTIGUOUS_NADIR_WINDOW_"
                f"MIN_ROM_{MINIMUM_PHASE_PROXY_ROM_DEGREES:g}_DEG"
            ),
            "activeSemantics": (
                "WINDOW_PRIOR_ONLY_NOT_CORRECTNESS_OR_PHASE_GOLD; "
                "FIVE_VIEWS_MUST_AGREE"
            ),
            "temporalUnit": (
                "ACTIVE_RUN_CAMERA_COORDINATE_STEP_AND_EXCURSION_QUANTILES_"
                "WITHOUT_FPS_SPEED_GROUND_OR_PRESSURE_CLAIM"
            ),
            "bilateralSafetyAggregation": "WORST_SIDE_MAX_NOT_BILATERAL_MEAN",
            "twoDViewPolicy": (
                "AIHUB_FIVE_VIEW_ENSEMBLE_NESTED_WITHIN_SEQUENCE_"
                "NOT_DEPLOYABLE_AS_SINGLE_PHONE_VIEW"
            ),
            "expectedProxyErrorDirection": "TRUE_LOWER",
        },
        "validationConsumptionLedger": {
            "state": "CONSUMED_DEVELOPMENT_BENCHMARK",
            "restriction": (
                "MUST_NOT_BE_RELABELED_AS_AN_UNTOUCHED_CONFIRMATORY_OR_RELEASE_HOLDOUT_"
                "FOR_THIS_EXPERIMENT_FAMILY"
            ),
            "requiredNextTest": (
                "UNTOUCHED_EXTERNAL_MEDIAPIPE_GOLD_SUBJECT_DEVICE_VIEW_HOLDOUT"
            ),
        },
        "inventory": {
            "training": training_inventory,
            "validation": validation_inventory,
        },
        "criteria": evaluate_criteria(training, validation),
        "warnings": [
            "No AI Hub barbell-squat RGB is available for a MediaPipe domain bridge.",
            "Official Validation was observed during development and is descriptive only.",
            "Validation is coordinate-domain only: one day and seven subjects.",
            "Spine, head, knee-foot, and plantar constructs are gross coordinate proxies.",
            "Plantar pressure/contact and actual gaze are not identifiable from these coordinates.",
            "AI Hub five-view ensemble features cannot be deployed as one phone-camera feature.",
            "A replicated proxy signal is not a threshold artifact, calibration, cue, or GA approval.",
        ],
    }
    report["reportFingerprintSha256"] = _canonical_sha256(report)
    return report


def verify_report_fingerprint(report: Mapping[str, Any]) -> bool:
    fingerprint = report.get("reportFingerprintSha256")
    if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        return False
    payload = dict(report)
    del payload["reportFingerprintSha256"]
    return _canonical_sha256(payload) == fingerprint


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def validate_output_path(output: Path, source_roots: Sequence[Path]) -> Path:
    resolved = output.resolve()
    for source_root in source_roots:
        root = source_root.resolve()
        if resolved == root or _is_within(resolved, root):
            raise SquatExperimentError("Output must be outside every source dataset root")
    return resolved


def atomic_write_json(
    path: Path,
    value: Any,
    *,
    overwrite: bool = False,
    expected_old_fingerprint: str | None = None,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if overwrite != (expected_old_fingerprint is not None):
        raise SquatExperimentError(
            "Overwrite requires --overwrite and --expected-old-fingerprint together"
        )
    lock_path = path.with_name(f".{path.name}.lock")
    try:
        lock_descriptor = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise SquatExperimentError(f"Output is locked by another writer: {path}") from error
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    temporary_name: str | None = None
    try:
        os.close(lock_descriptor)
        if path.exists():
            if not overwrite:
                raise SquatExperimentError(f"Output already exists: {path}")
            try:
                old_value = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as error:
                raise SquatExperimentError("Existing output is not a valid report") from error
            if not isinstance(old_value, dict) or not verify_report_fingerprint(old_value):
                raise SquatExperimentError("Existing output fingerprint is invalid")
            if old_value["reportFingerprintSha256"] != expected_old_fingerprint:
                raise SquatExperimentError("Existing output fingerprint changed")
        elif overwrite:
            raise SquatExperimentError("Cannot overwrite a missing output")
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, path)
        temporary_name = None
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)
        lock_path.unlink(missing_ok=True)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("training_root", type=Path)
    parser.add_argument("--validation-root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--expected-old-fingerprint")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        training_root = args.training_root.resolve()
        validation_root = args.validation_root.resolve()
        report = run_experiment(training_root, validation_root)
        if args.output is None:
            if args.overwrite or args.expected_old_fingerprint is not None:
                raise SquatExperimentError("Overwrite options require --output")
            json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
            sys.stdout.write("\n")
        else:
            output = validate_output_path(args.output, (training_root, validation_root))
            atomic_write_json(
                output,
                report,
                overwrite=args.overwrite,
                expected_old_fingerprint=args.expected_old_fingerprint,
            )
    except (OSError, SquatExperimentError) as error:
        print(f"barbell squat experiment failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
