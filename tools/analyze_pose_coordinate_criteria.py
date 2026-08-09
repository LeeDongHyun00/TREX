#!/usr/bin/env python3
"""Build a candidate-only criterion research report from AI Hub pose coordinates.

The report deliberately does not emit PASS/FAIL thresholds or calibration artifacts.  It uses
the 2D JSON only for authoritative exercise/condition metadata, sequence identity, and the global
``Z`` subject identifier.  Gross, axis-independent features are computed from the basename-paired
3D JSON after pelvis centering and torso-length normalization.

Statistical support is counted in sequences and subjects.  Frames and the five camera views are
never treated as independent observations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import sys
import tempfile
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence


SCHEMA_VERSION = 2
ARTIFACT_KIND = "AIHUB_COORDINATE_CRITERION_CANDIDATE_RESEARCH"
DECISION_USE = "CANDIDATE_RESEARCH_ONLY_NOT_THRESHOLD_OR_PASS_FAIL_CALIBRATION"
DEFAULT_QUARANTINE_CODES = frozenset({"062", "101", "109"})
TAIL_BYTES = 64 * 1024
SUBJECT_PATTERN = re.compile(r"(?:^|[-_/])(Z[0-9]+)(?=[_/.-]|$)")
TYPE_CODE_PATTERN = re.compile(r"^[0-9]+$")
FIRST_IMAGE_KEY_PATTERN = re.compile(rb'"img_key"\s*:\s*"((?:[^"\\]|\\.)*)"')
ROUND_DIGITS = 12
ZERO_TOLERANCE = 1e-12

AIHUB_JOINTS = (
    "Nose",
    "Left Eye",
    "Right Eye",
    "Left Ear",
    "Right Ear",
    "Left Shoulder",
    "Right Shoulder",
    "Left Elbow",
    "Right Elbow",
    "Left Wrist",
    "Right Wrist",
    "Left Hip",
    "Right Hip",
    "Left Knee",
    "Right Knee",
    "Left Ankle",
    "Right Ankle",
    "Neck",
    "Left Palm",
    "Right Palm",
    "Back",
    "Waist",
    "Left Foot",
    "Right Foot",
)

ANGLE_FEATURES = {
    "left_elbow_angle_deg": ("Left Shoulder", "Left Elbow", "Left Wrist"),
    "left_hip_angle_deg": ("Left Shoulder", "Left Hip", "Left Knee"),
    "left_knee_angle_deg": ("Left Hip", "Left Knee", "Left Ankle"),
    "right_elbow_angle_deg": ("Right Shoulder", "Right Elbow", "Right Wrist"),
    "right_hip_angle_deg": ("Right Shoulder", "Right Hip", "Right Knee"),
    "right_knee_angle_deg": ("Right Hip", "Right Knee", "Right Ankle"),
}

# All distances are computed after pelvis centering and torso-length scaling.  The list is fixed
# in source so feature search cannot silently expand after seeing condition labels.
DISTANCE_FEATURES = {
    "ankle_separation_torso": ("Left Ankle", "Right Ankle"),
    "elbow_separation_torso": ("Left Elbow", "Right Elbow"),
    "foot_separation_torso": ("Left Foot", "Right Foot"),
    "hip_separation_torso": ("Left Hip", "Right Hip"),
    "knee_separation_torso": ("Left Knee", "Right Knee"),
    "left_ankle_to_pelvis_torso": ("Left Ankle", "Pelvis"),
    "left_foot_to_pelvis_torso": ("Left Foot", "Pelvis"),
    "left_knee_to_pelvis_torso": ("Left Knee", "Pelvis"),
    "left_wrist_to_hip_torso": ("Left Wrist", "Left Hip"),
    "left_wrist_to_pelvis_torso": ("Left Wrist", "Pelvis"),
    "nose_to_pelvis_torso": ("Nose", "Pelvis"),
    "palm_separation_torso": ("Left Palm", "Right Palm"),
    "right_ankle_to_pelvis_torso": ("Right Ankle", "Pelvis"),
    "right_foot_to_pelvis_torso": ("Right Foot", "Pelvis"),
    "right_knee_to_pelvis_torso": ("Right Knee", "Pelvis"),
    "right_wrist_to_hip_torso": ("Right Wrist", "Right Hip"),
    "right_wrist_to_pelvis_torso": ("Right Wrist", "Pelvis"),
    "shoulder_separation_torso": ("Left Shoulder", "Right Shoulder"),
    "wrist_separation_torso": ("Left Wrist", "Right Wrist"),
}

AGGREGATIONS = ("min", "max", "median", "rom")


class CoordinateAnalysisError(RuntimeError):
    """Raised for an invalid analysis request or unusable dataset root."""


class SequenceDataError(ValueError):
    """Raised when one selected sequence cannot satisfy the coordinate contract."""


@dataclass(frozen=True)
class SequenceCandidate:
    two_d_path: Path
    three_d_path: Path
    sequence_id: str
    type_code: str
    exercise: str
    conditions: tuple[tuple[str, bool], ...]
    selection_subject_id: str
    selection_day_id: str


@dataclass(frozen=True)
class SequenceObservation:
    sequence_id: str
    type_code: str
    exercise: str
    subject_id: str
    conditions: tuple[tuple[str, bool], ...]
    features: dict[str, float]


def normalize_text(value: Any, field: str, path: Path) -> str:
    if not isinstance(value, str):
        raise SequenceDataError(f"{path}: {field} must be a string")
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFC", value)).strip()
    if not normalized:
        raise SequenceDataError(f"{path}: {field} must not be blank")
    return normalized


def normalized_conditions(value: Any, path: Path) -> tuple[tuple[str, bool], ...]:
    if not isinstance(value, list) or not value:
        raise SequenceDataError(f"{path}: type_info.conditions must be a non-empty array")
    result: dict[str, bool] = {}
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise SequenceDataError(f"{path}: conditions[{index}] must be an object")
        name = normalize_text(item.get("condition"), f"conditions[{index}].condition", path)
        condition_value = item.get("value")
        if not isinstance(condition_value, bool):
            raise SequenceDataError(f"{path}: conditions[{index}].value must be boolean")
        if name in result:
            raise SequenceDataError(f"{path}: duplicate normalized condition {name!r}")
        result[name] = condition_value
    return tuple(sorted(result.items()))


def decode_metadata_tail(path: Path) -> tuple[str, str, tuple[tuple[str, bool], ...]]:
    """Decode root type and trailing type_info without loading the large frame payload."""

    size = path.stat().st_size
    with path.open("rb") as source:
        source.seek(max(0, size - TAIL_BYTES))
        try:
            tail = source.read().decode("utf-8")
        except UnicodeDecodeError as error:
            raise SequenceDataError(f"{path}: invalid UTF-8") from error

    marker = tail.rfind('"type_info"')
    if marker < 0:
        raise SequenceDataError(f"{path}: type_info not found in final {TAIL_BYTES} bytes")
    root_type_matches = list(
        re.finditer(r'"type"\s*:\s*"([^"\\]+)"\s*,\s*$', tail[:marker]),
    )
    if not root_type_matches:
        raise SequenceDataError(f"{path}: root type before type_info is missing")
    type_code = normalize_text(root_type_matches[-1].group(1), "type", path)
    if not TYPE_CODE_PATTERN.fullmatch(type_code):
        raise SequenceDataError(f"{path}: type code must contain only digits")

    colon = tail.find(":", marker)
    if colon < 0:
        raise SequenceDataError(f"{path}: type_info has no value")
    start = colon + 1
    while start < len(tail) and tail[start].isspace():
        start += 1
    try:
        type_info, end = json.JSONDecoder().raw_decode(tail, start)
    except (ValueError, json.JSONDecodeError) as error:
        raise SequenceDataError(f"{path}: invalid type_info JSON: {error}") from error
    if not isinstance(type_info, dict) or tail[end:].strip() != "}":
        raise SequenceDataError(f"{path}: invalid trailing type_info structure")

    key = normalize_text(type_info.get("key"), "type_info.key", path)
    if key != type_code:
        raise SequenceDataError(f"{path}: root type {type_code!r} != type_info.key {key!r}")
    exercise = normalize_text(type_info.get("exercise"), "type_info.exercise", path)
    conditions = normalized_conditions(type_info.get("conditions"), path)
    return type_code, exercise, conditions


def paired_three_d_path(two_d_path: Path) -> Path:
    return two_d_path.with_name(f"{two_d_path.stem}-3d.json")


def _load_json_object(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as source:
            value = json.load(source)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SequenceDataError(f"{path}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise SequenceDataError(f"{path}: JSON root must be an object")
    return value


def extract_subject_id(two_d_payload: dict[str, Any], path: Path = Path("<memory>")) -> str:
    frames = two_d_payload.get("frames")
    if not isinstance(frames, list) or not frames:
        raise SequenceDataError(f"{path}: frames must be a non-empty array")

    subjects: set[str] = set()
    image_key_count = 0
    for frame_index, frame in enumerate(frames):
        if not isinstance(frame, dict):
            raise SequenceDataError(f"{path}: frames[{frame_index}] must be an object")
        views = [value for key, value in sorted(frame.items()) if key.startswith("view")]
        if not views:
            raise SequenceDataError(f"{path}: frames[{frame_index}] has no view object")
        for view in views:
            if not isinstance(view, dict):
                raise SequenceDataError(f"{path}: view must be an object")
            image_key = view.get("img_key")
            if not isinstance(image_key, str) or not image_key:
                raise SequenceDataError(f"{path}: view img_key must be a non-empty string")
            image_key_count += 1
            subjects.update(SUBJECT_PATTERN.findall(image_key))

    if image_key_count == 0 or not subjects:
        raise SequenceDataError(f"{path}: no global Z subject id found in img_key")
    if len(subjects) != 1:
        raise SequenceDataError(f"{path}: multiple global Z subject ids found: {sorted(subjects)}")
    return next(iter(subjects))


def _selection_identity(path: Path, sequence_id: str) -> tuple[str, str]:
    """Read the first image identity for deterministic pre-payload stratification.

    This is only a selection hint.  Every selected payload is still parsed in full and must pass
    ``extract_subject_id``, which verifies that all frames and views carry one global subject.
    """

    buffer = bytearray()
    try:
        with path.open("rb") as source:
            while chunk := source.read(64 * 1024):
                buffer.extend(chunk)
                match = FIRST_IMAGE_KEY_PATTERN.search(buffer)
                if match is None:
                    continue
                encoded = b'"' + match.group(1) + b'"'
                image_key = json.loads(encoded.decode("utf-8"))
                if not isinstance(image_key, str) or not image_key:
                    break
                subjects = sorted(set(SUBJECT_PATTERN.findall(image_key)))
                subject_id = subjects[0] if len(subjects) == 1 else "__UNKNOWN_SUBJECT__"
                segments = [item for item in image_key.replace("\\", "/").split("/") if item]
                day_id = next(
                    (item for item in segments if item.casefold().startswith("day")),
                    segments[0] if segments else "__UNKNOWN_DAY__",
                )
                return subject_id, day_id
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
        pass

    # Keep malformed hints deterministic and clustered rather than giving each record a synthetic
    # unique subject, which would make corrupt records appear artificially diverse to the cap.
    relative_day = sequence_id.split("/", 1)[0] if "/" in sequence_id else "__UNKNOWN_DAY__"
    return "__UNKNOWN_SUBJECT__", relative_day


def _point(value: Any, joint: str) -> tuple[float, float, float]:
    if not isinstance(value, dict):
        raise SequenceDataError(f"Joint {joint!r} must be an object")
    coordinates: list[float] = []
    for axis in ("x", "y", "z"):
        coordinate = value.get(axis)
        if isinstance(coordinate, bool) or not isinstance(coordinate, (int, float)):
            raise SequenceDataError(f"Joint {joint!r}.{axis} must be numeric")
        coordinate = float(coordinate)
        if not math.isfinite(coordinate):
            raise SequenceDataError(f"Joint {joint!r}.{axis} must be finite")
        coordinates.append(coordinate)
    return coordinates[0], coordinates[1], coordinates[2]


def _add(a: Sequence[float], b: Sequence[float]) -> tuple[float, float, float]:
    return tuple(x + y for x, y in zip(a, b))  # type: ignore[return-value]


def _subtract(a: Sequence[float], b: Sequence[float]) -> tuple[float, float, float]:
    return tuple(x - y for x, y in zip(a, b))  # type: ignore[return-value]


def _multiply(a: Sequence[float], scalar: float) -> tuple[float, float, float]:
    return tuple(x * scalar for x in a)  # type: ignore[return-value]


def _norm(vector: Sequence[float]) -> float:
    return math.sqrt(sum(value * value for value in vector))


def _distance(a: Sequence[float], b: Sequence[float]) -> float:
    return _norm(_subtract(a, b))


def normalize_frame_points(points_value: Any) -> dict[str, tuple[float, float, float]]:
    """Pelvis-center and torso-scale one 24-joint frame without assuming dataset axes."""

    if not isinstance(points_value, dict):
        raise SequenceDataError("3D pts must be an object")
    missing = [joint for joint in AIHUB_JOINTS if joint not in points_value]
    if missing:
        raise SequenceDataError(f"3D pts missing joints: {missing}")
    points = {joint: _point(points_value[joint], joint) for joint in AIHUB_JOINTS}
    pelvis = _multiply(_add(points["Left Hip"], points["Right Hip"]), 0.5)
    shoulder_center = _multiply(
        _add(points["Left Shoulder"], points["Right Shoulder"]),
        0.5,
    )
    torso_length = _distance(pelvis, shoulder_center)
    if not math.isfinite(torso_length) or torso_length <= 1e-9:
        raise SequenceDataError("Pelvis-to-shoulder torso length must be positive")

    normalized = {
        joint: _multiply(_subtract(point, pelvis), 1.0 / torso_length)
        for joint, point in points.items()
    }
    normalized["Pelvis"] = (0.0, 0.0, 0.0)
    normalized["Shoulder Center"] = _multiply(
        _subtract(shoulder_center, pelvis),
        1.0 / torso_length,
    )
    return normalized


def _angle_degrees(
    first: Sequence[float],
    vertex: Sequence[float],
    third: Sequence[float],
) -> float:
    first_vector = _subtract(first, vertex)
    third_vector = _subtract(third, vertex)
    denominator = _norm(first_vector) * _norm(third_vector)
    if denominator <= 1e-12:
        raise SequenceDataError("Cannot compute an angle from a zero-length segment")
    dot = sum(a * b for a, b in zip(first_vector, third_vector))
    cross = (
        first_vector[1] * third_vector[2] - first_vector[2] * third_vector[1],
        first_vector[2] * third_vector[0] - first_vector[0] * third_vector[2],
        first_vector[0] * third_vector[1] - first_vector[1] * third_vector[0],
    )
    # atan2 is stable near 0 and 180 degrees, where acos loses precision after normalization.
    return math.degrees(math.atan2(_norm(cross), dot))


def extract_frame_features(points_value: Any) -> dict[str, float]:
    points = normalize_frame_points(points_value)
    features: dict[str, float] = {}
    for name, (first, vertex, third) in ANGLE_FEATURES.items():
        features[name] = _angle_degrees(points[first], points[vertex], points[third])
    for name, (first, second) in DISTANCE_FEATURES.items():
        features[name] = _distance(points[first], points[second])
    return features


def _rounded(value: float) -> float:
    rounded = round(float(value), ROUND_DIGITS)
    return 0.0 if rounded == 0.0 else rounded


def aggregate_frame_features(frame_features: Sequence[dict[str, float]]) -> dict[str, float]:
    if len(frame_features) < 2:
        raise SequenceDataError("At least two valid 3D frames are required")
    feature_names = sorted(frame_features[0])
    if any(sorted(frame) != feature_names for frame in frame_features):
        raise SequenceDataError("All valid frames must expose the same feature contract")

    result: dict[str, float] = {}
    for name in feature_names:
        values = [frame[name] for frame in frame_features]
        result[f"{name}__min"] = _rounded(min(values))
        result[f"{name}__max"] = _rounded(max(values))
        result[f"{name}__median"] = _rounded(statistics.median(values))
        result[f"{name}__rom"] = _rounded(max(values) - min(values))
    return result


def extract_sequence_features(frames: Any) -> tuple[dict[str, float], int]:
    """Return aggregate features and the number of invalid 3D frames that were excluded."""

    if not isinstance(frames, list) or not frames:
        raise SequenceDataError("3D frames must be a non-empty array")
    valid: list[dict[str, float]] = []
    invalid_count = 0
    for frame in frames:
        try:
            if not isinstance(frame, dict):
                raise SequenceDataError("3D frame must be an object")
            valid.append(extract_frame_features(frame.get("pts")))
        except SequenceDataError:
            invalid_count += 1
    return aggregate_frame_features(valid), invalid_count


def _stable_order_key(*parts: str) -> str:
    payload = json.dumps(parts, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _subject_day_type_stratified_cap(
    candidates: Sequence[SequenceCandidate],
    maximum: int | None,
) -> tuple[list[SequenceCandidate], int]:
    if maximum is None or len(candidates) <= maximum:
        return list(candidates), 0

    groups: dict[str, dict[tuple[str, str], list[SequenceCandidate]]] = defaultdict(
        lambda: defaultdict(list),
    )
    for candidate in candidates:
        cell = (candidate.selection_subject_id, candidate.selection_day_id)
        groups[candidate.type_code][cell].append(candidate)
    for type_cells in groups.values():
        for values in type_cells.values():
            values.sort(
                key=lambda item: (_stable_order_key("sequence", item.sequence_id), item.sequence_id),
            )

    selected: list[SequenceCandidate] = []
    type_order = sorted(
        groups,
        key=lambda type_code: (_stable_order_key("type", type_code), type_code),
    )
    cell_order = {
        type_code: sorted(
            groups[type_code],
            key=lambda cell: (
                _stable_order_key("cell", type_code, cell[0], cell[1]),
                cell,
            ),
        )
        for type_code in type_order
    }
    cell_cursors = {type_code: 0 for type_code in groups}
    offsets = {
        (type_code, cell): 0
        for type_code, cells in cell_order.items()
        for cell in cells
    }
    while len(selected) < maximum:
        made_progress = False
        for type_code in type_order:
            cells = cell_order[type_code]
            for _ in range(len(cells)):
                cursor = cell_cursors[type_code] % len(cells)
                cell = cells[cursor]
                cell_cursors[type_code] += 1
                offset_key = (type_code, cell)
                offset = offsets[offset_key]
                values = groups[type_code][cell]
                if offset >= len(values):
                    continue
                selected.append(values[offset])
                offsets[offset_key] += 1
                made_progress = True
                break
            if len(selected) == maximum:
                break
        if not made_progress:
            break
    return selected, len(candidates) - len(selected)


def _condition_signature(conditions: tuple[tuple[str, bool], ...]) -> str:
    payload = json.dumps(conditions, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


def _median(values: Iterable[float]) -> float:
    materialized = list(values)
    if not materialized:
        raise ValueError("Median requires at least one value")
    return float(statistics.median(materialized))


def _stratum_effect(
    true_records: Sequence[SequenceObservation],
    false_records: Sequence[SequenceObservation],
    feature_name: str,
) -> tuple[float | None, float | None, str, int]:
    true_by_subject: dict[str, list[float]] = defaultdict(list)
    false_by_subject: dict[str, list[float]] = defaultdict(list)
    for record in true_records:
        true_by_subject[record.subject_id].append(record.features[feature_name])
    for record in false_records:
        false_by_subject[record.subject_id].append(record.features[feature_name])
    paired_subjects = sorted(true_by_subject.keys() & false_by_subject.keys())
    if paired_subjects:
        deltas = [
            _median(true_by_subject[subject]) - _median(false_by_subject[subject])
            for subject in paired_subjects
        ]
        return (
            _median(deltas),
            None,
            "PAIRED_SUBJECT_MEDIAN_DIFFERENCE",
            len(paired_subjects),
        )

    true_subject_medians = [_median(values) for values in true_by_subject.values()]
    false_subject_medians = [_median(values) for values in false_by_subject.values()]
    return (
        None,
        _median(true_subject_medians) - _median(false_subject_medians),
        "UNPAIRED_SUBJECT_MEDIAN_DIFFERENCE_DESCRIPTIVE_ONLY",
        0,
    )


def _support(records: Iterable[SequenceObservation]) -> dict[str, int]:
    values = list(records)
    return {
        "sequences": len({record.sequence_id for record in values}),
        "subjects": len({record.subject_id for record in values}),
    }


def _criterion_report(
    records: Sequence[SequenceObservation],
    condition_names: Sequence[str],
    criterion_index: int,
) -> dict[str, Any]:
    criterion_name = condition_names[criterion_index]
    strata: dict[tuple[bool, ...], dict[bool, list[SequenceObservation]]] = defaultdict(
        lambda: {False: [], True: []},
    )
    for record in records:
        values_by_name = dict(record.conditions)
        other_values = tuple(
            values_by_name[name]
            for index, name in enumerate(condition_names)
            if index != criterion_index
        )
        strata[other_values][values_by_name[criterion_name]].append(record)

    contrast_strata = [
        (other_values, groups)
        for other_values, groups in sorted(strata.items())
        if groups[False] and groups[True]
    ]
    all_true = [record for _, groups in contrast_strata for record in groups[True]]
    all_false = [record for _, groups in contrast_strata for record in groups[False]]
    paired_subjects = {
        subject
        for _, groups in contrast_strata
        for subject in (
            {record.subject_id for record in groups[True]}
            & {record.subject_id for record in groups[False]}
        )
    }

    stratum_reports: list[dict[str, Any]] = []
    other_names = [
        name for index, name in enumerate(condition_names) if index != criterion_index
    ]
    for stratum_index, (other_values, groups) in enumerate(contrast_strata):
        true_types = sorted({record.type_code for record in groups[True]})
        false_types = sorted({record.type_code for record in groups[False]})
        stratum_reports.append(
            {
                "stratumId": stratum_index,
                "otherConditions": [
                    {"condition": name, "value": value}
                    for name, value in zip(other_names, other_values)
                ],
                "trueTypeCodes": true_types,
                "falseTypeCodes": false_types,
                "hammingOneTypeVectorPairs": len(true_types) * len(false_types),
                "trueSupport": _support(groups[True]),
                "falseSupport": _support(groups[False]),
                "pairedSubjects": len(
                    {record.subject_id for record in groups[True]}
                    & {record.subject_id for record in groups[False]},
                ),
            },
        )

    feature_effects: list[dict[str, Any]] = []
    feature_names = sorted(records[0].features) if records else []
    for feature_name in feature_names:
        effects_by_stratum: list[dict[str, Any]] = []
        candidate_effects: list[float] = []
        for stratum_index, (_, groups) in enumerate(contrast_strata):
            effect, descriptive_effect, method, paired_count = _stratum_effect(
                groups[True],
                groups[False],
                feature_name,
            )
            effect = _rounded(effect) if effect is not None else None
            descriptive_effect = (
                _rounded(descriptive_effect) if descriptive_effect is not None else None
            )
            if effect is not None:
                candidate_effects.append(effect)
            effects_by_stratum.append(
                {
                    "stratumId": stratum_index,
                    "trueMedian": _rounded(
                        _median(record.features[feature_name] for record in groups[True]),
                    ),
                    "falseMedian": _rounded(
                        _median(record.features[feature_name] for record in groups[False]),
                    ),
                    "robustMedianEffectTrueMinusFalse": effect,
                    "descriptiveUnpairedSubjectMedianDifference": descriptive_effect,
                    "effectMethod": method,
                    "pairedSubjects": paired_count,
                    "candidateEligible": effect is not None,
                    "ineligibilityReason": (
                        None if effect is not None else "NO_PAIRED_SUBJECT_SUPPORT"
                    ),
                },
            )
        aggregate_effect = (
            _rounded(_median(candidate_effects)) if candidate_effects else None
        )
        nonzero_effects = [
            effect for effect in candidate_effects if abs(effect) > ZERO_TOLERANCE
        ]
        if aggregate_effect is None:
            direction = "INELIGIBLE_NO_PAIRED_SUBJECT_SUPPORT"
            consistency = None
        elif abs(aggregate_effect) <= ZERO_TOLERANCE:
            direction = "MIXED_OR_ZERO"
            consistency = 0.0
        else:
            direction = "TRUE_HIGHER" if aggregate_effect > 0.0 else "TRUE_LOWER"
            expected_positive = aggregate_effect > 0.0
            matches = sum((effect > 0.0) == expected_positive for effect in nonzero_effects)
            consistency = matches / len(nonzero_effects) if nonzero_effects else 0.0
        feature_effects.append(
            {
                "feature": feature_name,
                "robustMedianEffectAcrossMatchedStrata": aggregate_effect,
                "direction": direction,
                "candidateDirectionEligible": aggregate_effect is not None,
                "directionConsistencyAcrossNonzeroStrata": (
                    _rounded(consistency) if consistency is not None else None
                ),
                "pairedEligibleStrata": len(candidate_effects),
                "descriptiveOnlyStrata": len(effects_by_stratum) - len(candidate_effects),
                "nonzeroStrata": len(nonzero_effects),
                "effectsByStratum": effects_by_stratum,
            },
        )

    return {
        "criterion": criterion_name,
        "hammingOneContrastAvailable": bool(contrast_strata),
        "hammingOneStrata": len(contrast_strata),
        "hammingOneTypeVectorPairs": sum(
            item["hammingOneTypeVectorPairs"] for item in stratum_reports
        ),
        "pairedCandidateContrastAvailable": bool(paired_subjects),
        "trueSupport": _support(all_true),
        "falseSupport": _support(all_false),
        "pairedSubjects": len(paired_subjects),
        "strata": stratum_reports,
        "featureEffects": feature_effects,
    }


def _exercise_reports(observations: Sequence[SequenceObservation]) -> list[dict[str, Any]]:
    by_exercise: dict[str, list[SequenceObservation]] = defaultdict(list)
    for observation in observations:
        by_exercise[observation.exercise].append(observation)

    reports: list[dict[str, Any]] = []
    for exercise in sorted(by_exercise):
        exercise_records = sorted(
            by_exercise[exercise],
            key=lambda item: item.sequence_id,
        )
        by_schema: dict[tuple[str, ...], list[SequenceObservation]] = defaultdict(list)
        for record in exercise_records:
            by_schema[tuple(name for name, _ in record.conditions)].append(record)

        schema_reports: list[dict[str, Any]] = []
        for condition_names, records in sorted(by_schema.items()):
            schema_reports.append(
                {
                    "conditionSchemaId": _condition_signature(
                        tuple((name, False) for name in condition_names),
                    ),
                    "conditionNames": list(condition_names),
                    "sequenceSupport": len({record.sequence_id for record in records}),
                    "subjectSupport": len({record.subject_id for record in records}),
                    "typeCodes": sorted({record.type_code for record in records}),
                    "criteria": [
                        _criterion_report(records, condition_names, index)
                        for index in range(len(condition_names))
                    ],
                },
            )
        reports.append(
            {
                "exercise": exercise,
                "sequenceSupport": len({record.sequence_id for record in exercise_records}),
                "subjectSupport": len({record.subject_id for record in exercise_records}),
                "typeCodes": sorted({record.type_code for record in exercise_records}),
                "conditionSchemas": schema_reports,
            },
        )
    return reports


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def analyze(
    source_root: Path,
    exercise_filters: Sequence[str] | None = None,
    max_sequences: int | None = None,
    quarantine_codes: frozenset[str] = DEFAULT_QUARANTINE_CODES,
) -> dict[str, Any]:
    source_root = source_root.resolve()
    if not source_root.is_dir():
        raise CoordinateAnalysisError(f"Source root is not a directory: {source_root}")
    if max_sequences is not None and max_sequences <= 0:
        raise CoordinateAnalysisError("max_sequences must be positive")
    normalized_filters = {
        normalize_text(value, "exercise filter", source_root) for value in exercise_filters or []
    }

    all_json = sorted(source_root.rglob("*.json"), key=lambda path: path.relative_to(source_root).as_posix())
    two_d_paths = [path for path in all_json if not path.name.endswith("-3d.json")]
    three_d_paths = [path for path in all_json if path.name.endswith("-3d.json")]
    if not two_d_paths:
        raise CoordinateAnalysisError(f"No 2D JSON files found under {source_root}")

    two_d_relative = {path.relative_to(source_root).as_posix() for path in two_d_paths}
    paired_two_d_relative = {
        path.with_name(path.name.removesuffix("-3d.json") + ".json")
        .relative_to(source_root)
        .as_posix()
        for path in three_d_paths
    }
    orphan_three_d = len(paired_two_d_relative - two_d_relative)
    inventory: dict[str, int] = {
        "twoDDiscovered": len(two_d_paths),
        "threeDDiscovered": len(three_d_paths),
        "orphanThreeDDiscovered": orphan_three_d,
        "validTwoDMetadata": 0,
        "malformedTwoDMetadata": 0,
        "twoDMetadataNotDecoded": 0,
        "filteredOutByExercise": 0,
        "selectedByExercise": 0,
        "quarantinedByTypeCode": 0,
        "metadataConflictSequences": 0,
        "unpairedTwoD": 0,
        "eligiblePairedBeforeLimit": 0,
        "limitedOut": 0,
        "attempted": 0,
        "analyzed": 0,
        "malformedTwoDPayload": 0,
        "emptyTwoDFrames": 0,
        "malformedThreeDPayload": 0,
        "emptyThreeDFrames": 0,
        "frameCountMismatch": 0,
        "missingOrAmbiguousSubject": 0,
        "insufficientValidThreeDFrames": 0,
        "invalidThreeDFrames": 0,
        "partiallyValidThreeDSequences": 0,
    }

    metadata_cache: dict[
        Path,
        tuple[str, str, tuple[tuple[str, bool], ...]] | None,
    ] = {}

    def decode_counted(
        path: Path,
    ) -> tuple[str, str, tuple[tuple[str, bool], ...]] | None:
        if path in metadata_cache:
            return metadata_cache[path]
        try:
            metadata = decode_metadata_tail(path)
        except (OSError, SequenceDataError):
            inventory["malformedTwoDMetadata"] += 1
            metadata = None
        else:
            inventory["validTwoDMetadata"] += 1
        metadata_cache[path] = metadata
        return metadata

    metadata_candidates: list[SequenceCandidate] = []
    metadata_by_type: dict[
        str,
        set[tuple[str, tuple[tuple[str, bool], ...]]],
    ] = defaultdict(set)
    # Accuracy takes precedence over the old one-representative shortcut: every discovered 2D
    # tail is decoded, including non-selected exercises, so type identity drift cannot hide behind
    # a filter.
    for path in two_d_paths:
        metadata = decode_counted(path)
        if metadata is None:
            continue
        type_code, exercise, conditions = metadata
        metadata_by_type[type_code].add((exercise, conditions))
        if normalized_filters and exercise not in normalized_filters:
            inventory["filteredOutByExercise"] += 1
            continue
        inventory["selectedByExercise"] += 1
        if type_code in quarantine_codes:
            inventory["quarantinedByTypeCode"] += 1
            continue
        pair_path = paired_three_d_path(path)
        if not pair_path.is_file():
            inventory["unpairedTwoD"] += 1
            continue
        sequence_id = path.relative_to(source_root).with_suffix("").as_posix()
        selection_subject_id, selection_day_id = _selection_identity(path, sequence_id)
        candidate = SequenceCandidate(
            two_d_path=path,
            three_d_path=pair_path,
            sequence_id=sequence_id,
            type_code=type_code,
            exercise=exercise,
            conditions=conditions,
            selection_subject_id=selection_subject_id,
            selection_day_id=selection_day_id,
        )
        metadata_candidates.append(candidate)

    inventory["twoDMetadataNotDecoded"] = (
        len(two_d_paths)
        - inventory["validTwoDMetadata"]
        - inventory["malformedTwoDMetadata"]
    )

    conflicting_types = {
        type_code
        for type_code, identity_variants in metadata_by_type.items()
        if len(identity_variants) > 1
    }
    eligible_candidates: list[SequenceCandidate] = []
    for candidate in metadata_candidates:
        if candidate.type_code in conflicting_types:
            inventory["metadataConflictSequences"] += 1
        else:
            eligible_candidates.append(candidate)
    inventory["eligiblePairedBeforeLimit"] = len(eligible_candidates)
    selected_candidates, limited_out = _subject_day_type_stratified_cap(
        eligible_candidates,
        max_sequences,
    )
    inventory["limitedOut"] = limited_out

    observations: list[SequenceObservation] = []
    for candidate in selected_candidates:
        inventory["attempted"] += 1
        try:
            two_d = _load_json_object(candidate.two_d_path)
        except SequenceDataError:
            inventory["malformedTwoDPayload"] += 1
            continue
        frames_2d = two_d.get("frames")
        if not isinstance(frames_2d, list):
            inventory["malformedTwoDPayload"] += 1
            continue
        if not frames_2d:
            inventory["emptyTwoDFrames"] += 1
            continue
        try:
            subject_id = extract_subject_id(two_d, candidate.two_d_path)
        except SequenceDataError:
            inventory["missingOrAmbiguousSubject"] += 1
            continue

        try:
            three_d = _load_json_object(candidate.three_d_path)
        except SequenceDataError:
            inventory["malformedThreeDPayload"] += 1
            continue
        frames_3d = three_d.get("frames")
        if not isinstance(frames_3d, list):
            inventory["malformedThreeDPayload"] += 1
            continue
        if not frames_3d:
            inventory["emptyThreeDFrames"] += 1
            continue
        if len(frames_2d) != len(frames_3d):
            inventory["frameCountMismatch"] += 1
            continue
        try:
            features, invalid_frames = extract_sequence_features(frames_3d)
        except SequenceDataError:
            inventory["insufficientValidThreeDFrames"] += 1
            continue
        inventory["invalidThreeDFrames"] += invalid_frames
        if invalid_frames:
            inventory["partiallyValidThreeDSequences"] += 1
            continue
        observations.append(
            SequenceObservation(
                sequence_id=candidate.sequence_id,
                type_code=candidate.type_code,
                exercise=candidate.exercise,
                subject_id=subject_id,
                conditions=candidate.conditions,
                features=features,
            ),
        )
        inventory["analyzed"] += 1

    observations.sort(key=lambda item: item.sequence_id)
    fingerprint_records = [
        {
            "sequenceId": item.sequence_id,
            "typeCode": item.type_code,
            "exercise": item.exercise,
            "subjectId": item.subject_id,
            "conditions": list(item.conditions),
            "features": item.features,
        }
        for item in observations
    ]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": ARTIFACT_KIND,
        "decisionUse": DECISION_USE,
        "warnings": [
            "This report identifies coordinate-feature candidates only.",
            "It does not define thresholds, PASS/FAIL rules, calibration, or clinical validity.",
            "AI Hub 3D axes and physical units are not assumed; trunk orientation is excluded.",
            "MediaPipe runtime-domain agreement requires a separate locked bridge study.",
            "Every discovered 2D metadata tail is audited even when exercise filters are used.",
            "Unpaired-subject contrasts are descriptive only and cannot produce candidate direction.",
        ],
        "configuration": {
            "exerciseFilters": sorted(normalized_filters),
            "maxSequences": max_sequences,
            "limitSelection": (
                "DETERMINISTIC_TYPE_THEN_SUBJECT_DAY_ROUND_ROBIN_STABLE_HASH"
            ),
            "limitSelectionIdentity": (
                "FIRST_IMG_KEY_HINT_THEN_FULL_SELECTED_PAYLOAD_SUBJECT_VALIDATION"
            ),
            "defaultQuarantinedTypeCodes": sorted(quarantine_codes),
        },
        "featureContract": {
            "landmarks": list(AIHUB_JOINTS),
            "normalization": "PELVIS_CENTER_AND_PELVIS_TO_SHOULDER_CENTER_TORSO_LENGTH",
            "axisDependentTrunkOrientation": "EXCLUDED_DATASET_AXES_UNCONFIRMED",
            "frameFeatures": sorted(ANGLE_FEATURES) + sorted(DISTANCE_FEATURES),
            "sequenceAggregations": list(AGGREGATIONS),
            "invalidFramePolicy": "REJECT_SEQUENCE_IF_ANY_FRAME_INVALID_REQUIRE_TWO_FRAMES",
        },
        "supportUnit": {
            "independentUnits": ["sequence", "global_Z_subject"],
            "framesAreIndependentN": False,
            "viewsAreIndependentN": False,
        },
        "inventoryScope": {
            "discoveryAndPairCounts": "FULL_SOURCE_ROOT",
            "selectedExerciseMetadataAndPairCounts": "ALL_SELECTED_FILES_BEFORE_LIMIT",
            "payloadAndFrameQualityCounts": "ATTEMPTED_FILES_AFTER_DETERMINISTIC_LIMIT",
            "nonSelectedMetadataValidation": "ALL_DISCOVERED_2D_JSON_TAILS",
        },
        "inventory": inventory,
        "analyzedSequenceFingerprintSha256": _canonical_sha256(fingerprint_records),
        "exercises": _exercise_reports(observations),
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_root", type=Path, help="Extracted AI Hub labeling-data root")
    parser.add_argument(
        "--exercise",
        action="append",
        default=[],
        help="Exact normalized exercise name; repeat to select multiple exercises",
    )
    parser.add_argument("--output", type=Path, help="Write JSON report here instead of stdout")
    parser.add_argument(
        "--max-sequences",
        type=int,
        help="Deterministic type-balanced cap for expensive full-payload analysis",
    )
    return parser


def _validated_output_path(source_root: Path, output: Path) -> Path:
    resolved_source = source_root.resolve()
    resolved_output = output.resolve()
    if resolved_output == resolved_source or resolved_source in resolved_output.parents:
        raise CoordinateAnalysisError(
            "Output must be outside source_root so a report cannot overwrite or become input: "
            f"{resolved_output}",
        )
    return resolved_output


def _write_text_atomic(path: Path, rendered: str) -> None:
    temporary_path: Path | None = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as destination:
            temporary_path = Path(destination.name)
            destination.write(rendered)
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temporary_path, path)
    except OSError as error:
        if temporary_path is not None:
            try:
                temporary_path.unlink(missing_ok=True)
            except OSError:
                pass
        raise CoordinateAnalysisError(f"Cannot write output atomically to {path}: {error}") from error


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        source_root = args.source_root.resolve()
        output_path = (
            _validated_output_path(source_root, args.output) if args.output else None
        )
        report = analyze(
            source_root=source_root,
            exercise_filters=args.exercise,
            max_sequences=args.max_sequences,
        )
        rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        if output_path is not None:
            _write_text_atomic(output_path, rendered)
        else:
            sys.stdout.write(rendered)
    except (CoordinateAnalysisError, SequenceDataError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
