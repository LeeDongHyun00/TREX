#!/usr/bin/env python3
"""Evaluate a Good Morning knee-stability surrogate on AI Hub Training only.

This experiment consumes the fixed 348-sequence Good Morning Training factorial.  It preserves
LEFT and RIGHT knee flexion independently and uses only the sixteen sampled frame ordinals.  It
does not read official Validation, infer frame time or camera view, run MediaPipe, establish Gold,
or emit a runtime threshold, posture verdict, score, repetition, release decision, or cue.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import stat
import statistics
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

if __package__:
    from .analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _angle_degrees,
        _canonical_sha256,
        extract_subject_id,
        paired_three_d_path,
        normalize_text,
        normalized_conditions,
    )
else:
    from analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _angle_degrees,
        _canonical_sha256,
        extract_subject_id,
        paired_three_d_path,
        normalize_text,
        normalized_conditions,
    )


SCHEMA_VERSION = 1
ARTIFACT_KIND = "AIHUB_GOOD_MORNING_TRAINING_ONLY_KNEE_STABILITY_SURROGATE_EXPERIMENT"
DECISION_USE = "RESEARCH_ONLY_NO_RUNTIME_THRESHOLD_PHASE_VIEW_MEDIAPIPE_GOLD_OR_RELEASE_AUTHORITY"
PROTOCOL_ID = "aihub.good-morning.knee-stability.ordinal-surrogate.protocol.v1"
EXERCISE_NAME = "굿모닝"
EXERCISE_ID = "good-morning"
SOURCE_CONDITION_ID = (
    "aihub-exact-sha256-621f2eb88568c0d247abce9bbdbc763e8e40ae396bd0ba254a77dcd8bbc0394d"
)
SOURCE_CONDITION_TEXT = "무릎 구부린채 고정"
SIDES = ("LEFT", "RIGHT")
METRIC_IDS = (
    "MEDIAN_FLEXION_DEGREES",
    "ROBUST_P90_P10_RANGE_DEGREES",
    "MEDIAN_ABSOLUTE_ORDINAL_DELTA_DEGREES",
)
DIRECTIONS = ("HIGH_IS_TRUE", "LOW_IS_TRUE")
METRIC_DIRECTION_CONTRACT: Mapping[str, str] = {
    "MEDIAN_FLEXION_DEGREES": "HIGH_IS_TRUE",
    "ROBUST_P90_P10_RANGE_DEGREES": "LOW_IS_TRUE",
    "MEDIAN_ABSOLUTE_ORDINAL_DELTA_DEGREES": "LOW_IS_TRUE",
}
COMPONENT_METRIC_CONTRACT: Mapping[str, tuple[str, ...]] = {
    "FLEXION": ("MEDIAN_FLEXION_DEGREES",),
    "STABILITY": (
        "ROBUST_P90_P10_RANGE_DEGREES",
        "MEDIAN_ABSOLUTE_ORDINAL_DELTA_DEGREES",
    ),
}
EXPECTED_FRAME_COUNT = 16
EXPECTED_SEQUENCE_COUNT = 348
EXPECTED_SUBJECT_COUNT = 41
EXPECTED_TYPE_COUNTS: Mapping[str, int] = {
    "185": 44,
    "186": 43,
    "187": 42,
    "188": 44,
    "189": 44,
    "190": 44,
    "191": 44,
    "192": 43,
}
EXPECTED_TRUTH_VECTORS: Mapping[str, str] = {
    "185": "111",
    "186": "011",
    "187": "101",
    "188": "110",
    "189": "001",
    "190": "100",
    "191": "010",
    "192": "000",
}
CONDITION_ORDER = (SOURCE_CONDITION_TEXT, "시선 방향 유지", "척추의 중립")
EXPECTED_LABEL_COUNTS = {"FALSE": 174, "TRUE": 174}
TYPE_FILE_PATTERN = re.compile(r"-(?P<type>[0-9]+)[.]json$", re.IGNORECASE)
ROUND_DIGITS = 10
MAX_SELECTED_JSON_BYTES = 4 * 1024 * 1024

CONTINUATION_POLICY: Mapping[str, float] = {
    "minimumEachSideEachComponentSubjectMacroBalancedAccuracyUnknownAsMiss": 0.85,
    "minimumEachSideCompoundSubjectMacroBalancedAccuracyUnknownAsMiss": 0.85,
    "minimumEachSideEachComponentMinimumSubjectAccuracyUnknownAsMiss": 0.75,
    "minimumEachSideCompoundMinimumSubjectAccuracyUnknownAsMiss": 0.75,
    "minimumEachSideEachComponentPredictionCoverage": 0.95,
    "minimumEachSideCompoundPredictionCoverage": 0.95,
    "minimumEachSideEachComponentModalRuleFraction": 0.90,
    "maximumEachSideEachComponentModalRuleThresholdRelativeIqr": 0.05,
}

PROTOCOL_CONTRACT: Mapping[str, Any] = {
    "protocolId": PROTOCOL_ID,
    "inputRole": "AIHUB_OFFICIAL_TRAINING_ONLY",
    "officialValidationRole": "FORBIDDEN_NO_STAT_NO_READ_NO_REUSE",
    "exerciseId": EXERCISE_ID,
    "sourceConditionId": SOURCE_CONDITION_ID,
    "sourceConditionText": SOURCE_CONDITION_TEXT,
    "independentUnit": "GLOBAL_Z_SUBJECT",
    "outerEvaluation": "LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT",
    "innerRuleSelection": "OUTER_TRAINING_SUBJECTS_ONLY",
    "labelRole": "SEQUENCE_LEVEL_AI_HUB_TRAINING_SURROGATE_NOT_FRAME_OR_PHASE_GOLD",
    "coordinateDomain": "AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD",
    "ordinalContract": {
        "frameCount": EXPECTED_FRAME_COUNT,
        "frameIdentity": "ORDINAL_0_THROUGH_15_ONLY",
        "fpsOrTimestampEvidence": "ABSENT_MUST_NOT_BE_INFERRED",
    },
    "sideContract": "LEFT_AND_RIGHT_RETAINED_INDEPENDENTLY_NO_BILATERAL_COLLAPSE",
    "featureContract": {
        "jointTriplet": "HIP_KNEE_ANKLE",
        "flexionDefinition": "180_DEGREES_MINUS_INCLUDED_3D_HIP_KNEE_ANKLE_ANGLE",
        "predeclaredMetricIds": list(METRIC_IDS),
        "metricDirectionContract": dict(METRIC_DIRECTION_CONTRACT),
        "compoundComponentMetricContract": {
            key: list(value) for key, value in COMPONENT_METRIC_CONTRACT.items()
        },
        "compoundDecision": "FLEXION_AND_STABILITY_UNKNOWN_IF_EITHER_COMPONENT_UNKNOWN",
        "robustPercentiles": [0.10, 0.90],
        "deltaDomain": "ADJACENT_ORDINAL_ONLY_NO_TIME_NORMALIZATION",
        "featureSearchOutsideContract": "FORBIDDEN",
    },
    "ruleFamily": {
        "directions": list(DIRECTIONS),
        "directionSelection": "FORBIDDEN_FIXED_BY_METRIC_DIRECTION_CONTRACT",
        "thresholdSource": "MIDPOINTS_BETWEEN_DISTINCT_OUTER_TRAINING_VALUES_ONLY",
        "fitObjective": [
            "SUBJECT_MACRO_BALANCED_ACCURACY_UNKNOWN_AS_MISS",
            "SEQUENCE_ACCURACY_UNKNOWN_AS_MISS",
            "PREDICTION_COVERAGE",
            "LOWEST_PREDECLARED_METRIC_AND_THRESHOLD_TIE_BREAK",
        ],
        "runtimeThresholdRole": "NONE_RESEARCH_DIAGNOSTIC_ONLY",
    },
    "unknownPolicy": "MISSING_INVALID_OR_NONFINITE_FEATURE_IS_UNKNOWN_AND_COUNTS_AS_MISS",
    "selectedInputSnapshot": (
        "ENUMERATION_DEV_INO_SIZE_MTIME_NS_PREFLIGHT_THEN_O_NOFOLLOW_WHERE_AVAILABLE_AND_"
        "SAME_DESCRIPTOR_FSTAT_BEFORE_AFTER_SINGLE_BOUNDED_SNAPSHOT_PARSE_AND_SHA256"
    ),
    "externalContentRoot": "ABSENT",
    "detachedInputSignature": "ABSENT",
    "viewRole": "UNAVAILABLE_CAMERA_IDS_A_TO_E_ARE_NOT_VIEW_QUALIFICATION",
    "phaseGold": "ABSENT",
    "mediaPipeBridge": "ABSENT_NO_GOOD_MORNING_PIXELS",
    "goldRole": "ABSENT_TRAINING_LABEL_IS_NOT_INDEPENDENT_GOLD",
    "textHashNormalization": "UTF8_CANONICAL_LF_CRLF_AND_CR_NORMALIZED_TO_LF",
    "continuationPolicy": CONTINUATION_POLICY,
    "authority": {
        "runtimeThresholdAuthority": 0,
        "phaseAuthority": 0,
        "viewAuthority": 0,
        "mediaPipeBridgeAuthority": 0,
        "goldAuthority": 0,
        "releaseAuthority": 0,
        "shadowAuthority": 0,
        "userDecisionAuthority": 0,
        "scoreAuthority": 0,
        "cueAuthority": 0,
    },
}


class GoodMorningExperimentError(RuntimeError):
    """Raised when the Training-only experiment cannot satisfy its fixed contract."""


@dataclass(frozen=True)
class JsonSnapshot:
    payload: dict[str, Any]
    content_sha256: str
    byte_count: int


@dataclass(frozen=True)
class FileIdentity:
    device: int
    inode: int
    size: int
    mtime_ns: int

    @classmethod
    def from_stat(cls, metadata: os.stat_result) -> "FileIdentity":
        return cls(
            device=int(metadata.st_dev),
            inode=int(metadata.st_ino),
            size=int(metadata.st_size),
            mtime_ns=int(metadata.st_mtime_ns),
        )


@dataclass(frozen=True)
class SideFeatures:
    median_flexion_degrees: float | None
    robust_range_degrees: float | None
    median_absolute_ordinal_delta_degrees: float | None
    unknown_reason: str | None = None

    def metric(self, metric_id: str) -> float | None:
        return {
            "MEDIAN_FLEXION_DEGREES": self.median_flexion_degrees,
            "ROBUST_P90_P10_RANGE_DEGREES": self.robust_range_degrees,
            "MEDIAN_ABSOLUTE_ORDINAL_DELTA_DEGREES":
                self.median_absolute_ordinal_delta_degrees,
        }[metric_id]

    def payload(self) -> dict[str, Any]:
        return {
            "medianFlexionDegrees": self.median_flexion_degrees,
            "robustP90P10RangeDegrees": self.robust_range_degrees,
            "medianAbsoluteOrdinalDeltaDegrees":
                self.median_absolute_ordinal_delta_degrees,
            "unknownReason": self.unknown_reason,
        }


@dataclass(frozen=True)
class SequenceObservation:
    sequence_id: str
    type_code: str
    subject_id: str
    day_id: str
    target: bool
    left: SideFeatures
    right: SideFeatures
    two_d_sha256: str
    three_d_sha256: str

    def side(self, side: str) -> SideFeatures:
        return self.left if side == "LEFT" else self.right


@dataclass(frozen=True)
class DiagnosticRule:
    metric_id: str
    direction: str
    threshold_degrees: float

    def payload(self) -> dict[str, Any]:
        result = {
            "metricId": self.metric_id,
            "direction": self.direction,
            "thresholdDegrees": _round(self.threshold_degrees),
        }
        result["configurationId"] = "cfg-" + _canonical_sha256(result)[:16]
        return result


@dataclass(frozen=True)
class Decision:
    value: bool | None
    unknown_reason: str | None = None


def _round(value: float) -> float:
    result = round(float(value), ROUND_DIGITS)
    return 0.0 if result == 0.0 else result


def _percentile(values: Iterable[float], probability: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        raise ValueError("Cannot take percentile of empty values")
    position = probability * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def _subject_sha256(subject_id: str) -> str:
    return _canonical_sha256({"namespace": "AIHUB_GLOBAL_Z_SUBJECT", "subjectId": subject_id})


def _canonical_lf_text_sha256(path: Path) -> str:
    try:
        text = path.read_bytes().decode("utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise GoodMorningExperimentError(f"Expected UTF-8 text: {path}") from error
    canonical = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _snapshot_json_once(path: Path, expected_identity: FileIdentity) -> JsonSnapshot:
    """Read one bounded immutable byte snapshot, then parse and hash those exact bytes."""

    if not 1 <= expected_identity.size <= MAX_SELECTED_JSON_BYTES:
        raise GoodMorningExperimentError(
            f"Selected Training JSON size is outside the preflight bound: {path}"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor: int | None = None
    try:
        descriptor = os.open(path, flags)
        before = os.fstat(descriptor)
        _assert_open_identity(path, before, expected_identity)
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            content = source.read(expected_identity.size + 1)
        after = os.fstat(descriptor)
        _assert_open_identity(path, after, expected_identity)
    except OSError as error:
        raise GoodMorningExperimentError(f"Cannot snapshot Training input: {path}") from error
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass
    if len(content) != expected_identity.size:
        raise GoodMorningExperimentError(
            f"Selected Training JSON changed after size preflight: {path}"
        )
    try:
        payload = json.loads(content.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GoodMorningExperimentError(f"Selected Training JSON is invalid: {path}") from error
    if not isinstance(payload, dict):
        raise GoodMorningExperimentError(f"Selected Training JSON root is not an object: {path}")
    return JsonSnapshot(
        payload=payload,
        content_sha256=hashlib.sha256(content).hexdigest(),
        byte_count=len(content),
    )


def _assert_open_identity(
    path: Path,
    metadata: os.stat_result,
    expected: FileIdentity,
) -> None:
    if not stat.S_ISREG(metadata.st_mode):
        raise GoodMorningExperimentError(f"Opened Training input is not regular: {path}")
    if FileIdentity.from_stat(metadata) != expected:
        raise GoodMorningExperimentError(
            f"Selected Training input identity changed after enumeration: {path}"
        )


def _is_validation_component(value: str) -> bool:
    return "validation" in value.casefold()


def _reject_validation_path_before_probe(path: Path) -> None:
    if any(_is_validation_component(part) for part in Path(path).parts):
        raise GoodMorningExperimentError(
            "Official Validation is forbidden before any path stat or read"
        )


def _absolute_training_root(path: Path) -> Path:
    """Return an absolute non-reparse Training root without resolving links.

    The Validation lexical rejection intentionally precedes lstat, resolve, exists, or is_dir.
    """

    path = Path(path)
    _reject_validation_path_before_probe(path)
    if not any(part.casefold().startswith("1.training") for part in path.parts):
        raise GoodMorningExperimentError("Input must be under an explicit 1.Training component")
    absolute = Path(os.path.abspath(os.fspath(path)))
    prefixes = [Path(absolute.anchor)]
    current = prefixes[0]
    for component in absolute.parts[1:]:
        current = current / component
        prefixes.append(current)
    metadata: os.stat_result | None = None
    for prefix in prefixes:
        try:
            metadata = prefix.lstat()
        except OSError as error:
            raise GoodMorningExperimentError(
                f"Training path prefix cannot be inspected: {prefix}"
            ) from error
        attributes = getattr(metadata, "st_file_attributes", 0)
        if stat.S_ISLNK(metadata.st_mode) or attributes & getattr(
            stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x0400
        ):
            raise GoodMorningExperimentError(
                f"Training path prefix must not be a symlink or reparse point: {prefix}"
            )
        if not stat.S_ISDIR(metadata.st_mode):
            raise GoodMorningExperimentError(
                f"Training path prefix is not a directory: {prefix}"
            )
    assert metadata is not None
    return absolute


def _iter_training_files(root: Path) -> Iterator[tuple[Path, FileIdentity]]:
    pending = [root]
    while pending:
        directory = pending.pop()
        try:
            with os.scandir(directory) as entries:
                ordered = sorted(entries, key=lambda item: item.name.casefold())
        except OSError as error:
            raise GoodMorningExperimentError(f"Cannot scan Training directory: {directory}") from error
        for entry in reversed(ordered):
            # This name-only check deliberately happens before entry.stat/is_dir/is_file.
            if _is_validation_component(entry.name):
                raise GoodMorningExperimentError(
                    "Validation-named descendant rejected before stat or read"
                )
            path = Path(entry.path)
            try:
                metadata = entry.stat(follow_symlinks=False)
            except OSError as error:
                raise GoodMorningExperimentError(f"Cannot inspect Training entry: {path}") from error
            attributes = getattr(metadata, "st_file_attributes", 0)
            if stat.S_ISLNK(metadata.st_mode) or attributes & getattr(
                stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x0400
            ):
                raise GoodMorningExperimentError(
                    f"Training tree must not contain symlink/reparse entries: {path}"
                )
            if stat.S_ISDIR(metadata.st_mode):
                pending.append(path)
            elif stat.S_ISREG(metadata.st_mode):
                # Windows DirEntry.stat can report zero dev/inode while Path.lstat exposes the
                # descriptor-comparable file identity.  Capture that identity during enumeration.
                try:
                    identity_metadata = path.lstat()
                except OSError as error:
                    raise GoodMorningExperimentError(
                        f"Cannot capture Training file identity: {path}"
                    ) from error
                identity_attributes = getattr(identity_metadata, "st_file_attributes", 0)
                if stat.S_ISLNK(identity_metadata.st_mode) or identity_attributes & getattr(
                    stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x0400
                ):
                    raise GoodMorningExperimentError(
                        f"Training file became a symlink/reparse entry: {path}"
                    )
                if not stat.S_ISREG(identity_metadata.st_mode):
                    raise GoodMorningExperimentError(
                        f"Training file identity is no longer regular: {path}"
                    )
                yield path, FileIdentity.from_stat(identity_metadata)


def _discover_selected_pairs(
    root: Path,
) -> list[tuple[Path, FileIdentity, Path, FileIdentity, str]]:
    selected_two_d: list[tuple[Path, FileIdentity, str]] = []
    selected_three_d: dict[Path, FileIdentity] = {}
    expected_types = set(EXPECTED_TYPE_COUNTS)
    for path, identity in _iter_training_files(root):
        if path.suffix.casefold() != ".json":
            continue
        if path.name.casefold().endswith("-3d.json"):
            base = path.name[:-8]
            match = TYPE_FILE_PATTERN.search(base + ".json")
            if match and match.group("type") in expected_types:
                selected_three_d[path] = identity
            continue
        match = TYPE_FILE_PATTERN.search(path.name)
        if match and match.group("type") in expected_types:
            selected_two_d.append((path, identity, match.group("type")))

    pairs = []
    for two_d, two_d_identity, filename_type in selected_two_d:
        three_d = paired_three_d_path(two_d)
        if three_d not in selected_three_d:
            raise GoodMorningExperimentError(f"Selected Training pair is missing: {two_d.name}")
        pairs.append(
            (two_d, two_d_identity, three_d, selected_three_d[three_d], filename_type)
        )
    pairs.sort(key=lambda item: item[0].relative_to(root).as_posix())
    if len(pairs) != EXPECTED_SEQUENCE_COUNT:
        raise GoodMorningExperimentError(
            f"Expected {EXPECTED_SEQUENCE_COUNT} selected Training pairs, found {len(pairs)}"
        )
    invalid_sizes = [
        path
        for two_d, two_d_identity, three_d, three_d_identity, _ in pairs
        for path, identity in ((two_d, two_d_identity), (three_d, three_d_identity))
        if not 1 <= identity.size <= MAX_SELECTED_JSON_BYTES
        or identity.device == 0
        or identity.inode == 0
    ]
    if invalid_sizes:
        raise GoodMorningExperimentError(
            "Selected Training JSON size preflight failed before content snapshot"
        )
    return pairs


def _point3(points: Any, joint: str) -> tuple[float, float, float]:
    if not isinstance(points, dict) or joint not in points or not isinstance(points[joint], dict):
        raise SequenceDataError(f"3D pts missing {joint}")
    result = []
    for axis in ("x", "y", "z"):
        value = points[joint].get(axis)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise SequenceDataError(f"{joint}.{axis} must be numeric")
        number = float(value)
        if not math.isfinite(number):
            raise SequenceDataError(f"{joint}.{axis} must be finite")
        result.append(number)
    return tuple(result)  # type: ignore[return-value]


def _side_features(frames: Sequence[Any], side: str) -> SideFeatures:
    prefix = "Left" if side == "LEFT" else "Right"
    flexions = []
    try:
        for ordinal, frame in enumerate(frames):
            if not isinstance(frame, dict):
                raise SequenceDataError(f"3D frame {ordinal} must be an object")
            points = frame.get("pts")
            included = _angle_degrees(
                _point3(points, f"{prefix} Hip"),
                _point3(points, f"{prefix} Knee"),
                _point3(points, f"{prefix} Ankle"),
            )
            flexion = 180.0 - included
            if not math.isfinite(flexion) or not 0.0 <= flexion <= 180.0:
                raise SequenceDataError("Knee flexion must be finite and in [0, 180]")
            flexions.append(flexion)
    except (KeyError, SequenceDataError, TypeError, ValueError):
        return SideFeatures(None, None, None, "INVALID_OR_NONFINITE_3D_KNEE_TRAJECTORY")
    if len(flexions) != EXPECTED_FRAME_COUNT:
        return SideFeatures(None, None, None, "ORDINAL_COUNT_NOT_16")
    deltas = [abs(right - left) for left, right in zip(flexions, flexions[1:])]
    result = SideFeatures(
        median_flexion_degrees=_round(statistics.median(flexions)),
        robust_range_degrees=_round(
            _percentile(flexions, 0.90) - _percentile(flexions, 0.10)
        ),
        median_absolute_ordinal_delta_degrees=_round(statistics.median(deltas)),
    )
    if not all(
        value is not None and math.isfinite(value)
        for value in (
            result.median_flexion_degrees,
            result.robust_range_degrees,
            result.median_absolute_ordinal_delta_degrees,
        )
    ):
        return SideFeatures(None, None, None, "NONFINITE_DERIVED_METRIC")
    return result


def _truth_vector(conditions: Mapping[str, bool]) -> str:
    try:
        return "".join("1" if conditions[name] else "0" for name in CONDITION_ORDER)
    except KeyError as error:
        raise GoodMorningExperimentError(f"Missing exact condition {error.args[0]!r}") from error


def _verified_catalog_provenance() -> dict[str, Any]:
    path = Path(__file__).resolve().parent.parent / "docs" / "aihub-criterion-coverage.json"
    try:
        artifact = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GoodMorningExperimentError("Criterion coverage artifact is unavailable") from error
    if not isinstance(artifact, dict):
        raise GoodMorningExperimentError("Criterion coverage artifact root is invalid")
    fingerprint = artifact.get("artifactSha256")
    unsigned = dict(artifact)
    unsigned.pop("artifactSha256", None)
    if not isinstance(fingerprint, str) or _canonical_sha256(unsigned) != fingerprint:
        raise GoodMorningExperimentError("Criterion coverage artifact fingerprint is invalid")
    condition = next(
        (row for row in artifact.get("conditionRegistry", []) if row.get("id") == SOURCE_CONDITION_ID),
        None,
    )
    exercise = next(
        (row for row in artifact.get("exercises", []) if row.get("id") == EXERCISE_ID),
        None,
    )
    if not isinstance(condition, dict) or condition.get("normalizedExactText") != SOURCE_CONDITION_TEXT:
        raise GoodMorningExperimentError("Exact source condition catalog binding changed")
    if not isinstance(exercise, dict):
        raise GoodMorningExperimentError("Good Morning catalog entry is missing")
    if exercise.get("recordCount") != EXPECTED_SEQUENCE_COUNT or exercise.get("typeCount") != 8:
        raise GoodMorningExperimentError("Good Morning catalog inventory changed")
    type_rows = {str(row.get("code")): row for row in exercise.get("types", [])}
    for code, vector in EXPECTED_TRUTH_VECTORS.items():
        row = type_rows.get(code)
        if not isinstance(row, dict) or row.get("truthVector") != vector:
            raise GoodMorningExperimentError(f"Catalog truth vector changed for type {code}")
        if row.get("recordCount") != EXPECTED_TYPE_COUNTS[code]:
            raise GoodMorningExperimentError(f"Catalog record count changed for type {code}")
    return {
        "relativePath": "docs/aihub-criterion-coverage.json",
        "artifactSha256": fingerprint,
        "artifactCanonicalLfTextSha256": _canonical_lf_text_sha256(path),
        "conditionProjectionSha256": _canonical_sha256(condition),
        "exerciseProjectionSha256": _canonical_sha256(exercise),
        "textHashNormalization": PROTOCOL_CONTRACT["textHashNormalization"],
    }


def load_training(training_root: Path) -> tuple[list[SequenceObservation], dict[str, Any]]:
    """Load and gate only the fixed official Training Good Morning factorial."""

    root = _absolute_training_root(training_root)
    pairs = _discover_selected_pairs(root)
    observations = []
    type_counts: Counter[str] = Counter()
    labels: Counter[str] = Counter()
    days: Counter[str] = Counter()
    unknown_by_side: Counter[str] = Counter()
    input_manifest = []
    for (
        two_d_path,
        two_d_identity,
        three_d_path,
        three_d_identity,
        filename_type,
    ) in pairs:
        sequence_id = two_d_path.relative_to(root).with_suffix("").as_posix()
        try:
            two_d_snapshot = _snapshot_json_once(two_d_path, two_d_identity)
            three_d_snapshot = _snapshot_json_once(three_d_path, three_d_identity)
            two_d = two_d_snapshot.payload
            three_d = three_d_snapshot.payload
            type_code = normalize_text(two_d.get("type"), "type", two_d_path)
            type_info = two_d.get("type_info")
            if not isinstance(type_info, dict):
                raise SequenceDataError("type_info must be an object")
            key = normalize_text(type_info.get("key"), "type_info.key", two_d_path)
            exercise = normalize_text(
                type_info.get("exercise"), "type_info.exercise", two_d_path
            )
            normalized = normalized_conditions(type_info.get("conditions"), two_d_path)
            if key != type_code:
                raise SequenceDataError("Root type and type_info.key differ")
            if type_code != filename_type or type_code not in EXPECTED_TYPE_COUNTS:
                raise SequenceDataError("Filename/type_info type mismatch")
            if exercise != EXERCISE_NAME:
                raise SequenceDataError("Selected type is not Good Morning")
            condition_map = dict(normalized)
            if set(condition_map) != set(CONDITION_ORDER):
                raise SequenceDataError("Good Morning condition schema changed")
            if _truth_vector(condition_map) != EXPECTED_TRUTH_VECTORS[type_code]:
                raise SequenceDataError("Good Morning truth vector changed")
            frames_2d = two_d.get("frames")
            frames_3d = three_d.get("frames")
            if not isinstance(frames_2d, list) or len(frames_2d) != EXPECTED_FRAME_COUNT:
                raise SequenceDataError("2D frame count must be exactly 16")
            if not isinstance(frames_3d, list) or len(frames_3d) != EXPECTED_FRAME_COUNT:
                raise SequenceDataError("3D frame count must be exactly 16")
            subject_id = extract_subject_id(two_d, two_d_path)
            day_id = next(
                (part for part in two_d_path.relative_to(root).parts if part.startswith("Day")),
                None,
            )
            if not day_id:
                raise SequenceDataError("Day identity is unavailable")
            left = _side_features(frames_3d, "LEFT")
            right = _side_features(frames_3d, "RIGHT")
            two_d_sha = two_d_snapshot.content_sha256
            three_d_sha = three_d_snapshot.content_sha256
        except (OSError, SequenceDataError) as error:
            raise GoodMorningExperimentError(
                f"Unusable selected Training sequence {sequence_id}: {error}"
            ) from error
        target = condition_map[SOURCE_CONDITION_TEXT]
        item = SequenceObservation(
            sequence_id=sequence_id,
            type_code=type_code,
            subject_id=subject_id,
            day_id=day_id,
            target=target,
            left=left,
            right=right,
            two_d_sha256=two_d_sha,
            three_d_sha256=three_d_sha,
        )
        observations.append(item)
        type_counts[type_code] += 1
        labels["TRUE" if target else "FALSE"] += 1
        days[day_id] += 1
        if left.unknown_reason:
            unknown_by_side["LEFT"] += 1
        if right.unknown_reason:
            unknown_by_side["RIGHT"] += 1
        input_manifest.append(
            {
                "sequenceIdentitySha256": _canonical_sha256(
                    {"namespace": "AIHUB_TRAINING_SEQUENCE", "sequenceId": sequence_id}
                ),
                "subjectSha256": _subject_sha256(subject_id),
                "typeCode": type_code,
                "target": target,
                "twoDSha256": two_d_sha,
                "threeDSha256": three_d_sha,
                "sideFeatureSha256": _canonical_sha256(
                    {"LEFT": left.payload(), "RIGHT": right.payload()}
                ),
            }
        )

    observations.sort(key=lambda item: item.sequence_id)
    input_manifest.sort(key=lambda item: item["sequenceIdentitySha256"])
    subjects = sorted({item.subject_id for item in observations})
    if dict(sorted(type_counts.items())) != dict(EXPECTED_TYPE_COUNTS):
        raise GoodMorningExperimentError(f"Training type counts changed: {dict(type_counts)}")
    if dict(sorted(labels.items())) != EXPECTED_LABEL_COUNTS:
        raise GoodMorningExperimentError(f"Training target balance changed: {dict(labels)}")
    if len(observations) != EXPECTED_SEQUENCE_COUNT or len(subjects) != EXPECTED_SUBJECT_COUNT:
        raise GoodMorningExperimentError("Training sequence or global-Z subject count changed")
    for field in ("two_d_sha256", "three_d_sha256"):
        values = [getattr(item, field) for item in observations]
        if len(values) != len(set(values)):
            raise GoodMorningExperimentError(f"Duplicate selected Training content: {field}")
    return observations, {
        "split": "TRAINING",
        "officialValidationStatCount": 0,
        "officialValidationReadCount": 0,
        "sequenceCount": len(observations),
        "globalZSubjectCount": len(subjects),
        "globalZSubjectSetSha256": _canonical_sha256(
            [_subject_sha256(value) for value in subjects]
        ),
        "typeCounts": {code: type_counts[code] for code in EXPECTED_TYPE_COUNTS},
        "targetCounts": {key: labels[key] for key in ("FALSE", "TRUE")},
        "dayCounts": dict(sorted(days.items())),
        "frameCount": EXPECTED_FRAME_COUNT,
        "ordinalDomain": list(range(EXPECTED_FRAME_COUNT)),
        "unknownFeatureSequenceCountsBySide": {
            side: unknown_by_side[side] for side in SIDES
        },
        "inputManifestSha256": _canonical_sha256(input_manifest),
        "absoluteOrRelativePathsPersisted": False,
    }


def _decision(item: SequenceObservation, side: str, rule: DiagnosticRule | None) -> Decision:
    features = item.side(side)
    if rule is None:
        return Decision(None, "NO_OUTER_TRAINING_RULE")
    value = features.metric(rule.metric_id)
    if value is None or not math.isfinite(value):
        return Decision(None, features.unknown_reason or "NONFINITE_FEATURE")
    if rule.direction == "HIGH_IS_TRUE":
        return Decision(value >= rule.threshold_degrees)
    return Decision(value <= rule.threshold_degrees)


def _classification_metrics(
    observations: Sequence[SequenceObservation],
    decisions: Sequence[Decision],
) -> dict[str, Any]:
    if not observations or len(observations) != len(decisions):
        raise ValueError("Metrics require aligned non-empty observations and decisions")
    grouped: dict[str, list[tuple[SequenceObservation, Decision]]] = defaultdict(list)
    for item, decision in zip(observations, decisions):
        grouped[item.subject_id].append((item, decision))
    subject_accuracy = []
    subject_balanced = []
    subject_coverage = []
    correct = determinate = 0
    label_total: Counter[str] = Counter()
    label_correct: Counter[str] = Counter()
    unknown_reasons: Counter[str] = Counter()
    for rows in grouped.values():
        local_correct = local_determinate = 0
        local_total: Counter[bool] = Counter()
        local_correct_by_label: Counter[bool] = Counter()
        for item, decision in rows:
            local_total[item.target] += 1
            label_total["TRUE" if item.target else "FALSE"] += 1
            if decision.value is None:
                unknown_reasons[decision.unknown_reason or "UNSPECIFIED_UNKNOWN"] += 1
                continue
            determinate += 1
            local_determinate += 1
            if decision.value == item.target:
                correct += 1
                local_correct += 1
                local_correct_by_label[item.target] += 1
                label_correct["TRUE" if item.target else "FALSE"] += 1
        subject_accuracy.append(local_correct / len(rows))
        subject_coverage.append(local_determinate / len(rows))
        subject_balanced.append(
            statistics.mean(
                local_correct_by_label[label] / count
                for label, count in sorted(local_total.items())
            )
        )
    return {
        "subjectCount": len(grouped),
        "sequenceCount": len(observations),
        "determinateCount": determinate,
        "unknownCount": len(observations) - determinate,
        "predictionCoverage": _round(determinate / len(observations)),
        "sequenceAccuracyUnknownAsMiss": _round(correct / len(observations)),
        "subjectMacroAccuracyUnknownAsMiss": _round(statistics.mean(subject_accuracy)),
        "subjectMacroBalancedAccuracyUnknownAsMiss": _round(
            statistics.mean(subject_balanced)
        ),
        "minimumSubjectAccuracyUnknownAsMiss": _round(min(subject_accuracy)),
        "minimumSubjectCoverage": _round(min(subject_coverage)),
        "recallByLabelUnknownAsMiss": {
            label: _round(label_correct[label] / label_total[label])
            for label in ("FALSE", "TRUE")
        },
        "selectiveAccuracy": _round(correct / determinate) if determinate else None,
        "unknownReasons": dict(sorted(unknown_reasons.items())),
    }


def _subject_macro_weights(
    observations: Sequence[SequenceObservation],
) -> dict[tuple[str, bool], float]:
    counts: Counter[tuple[str, bool]] = Counter((item.subject_id, item.target) for item in observations)
    subjects = sorted({item.subject_id for item in observations})
    class_count = {
        subject: sum(int(counts[(subject, label)] > 0) for label in (False, True))
        for subject in subjects
    }
    return {
        (subject, label): 1.0 / (len(subjects) * class_count[subject] * count)
        for (subject, label), count in counts.items()
    }


def _candidate_rule(
    observations: Sequence[SequenceObservation],
    side: str,
    metric_id: str,
    direction: str,
) -> tuple[DiagnosticRule, tuple[float, float, float]] | None:
    finite = []
    for item in observations:
        value = item.side(side).metric(metric_id)
        if value is not None and math.isfinite(value):
            finite.append((float(value), item))
    distinct = sorted({value for value, _ in finite})
    if len(distinct) < 2:
        return None
    weights = _subject_macro_weights(observations)
    if direction == "HIGH_IS_TRUE":
        macro_correct = sum(weights[(item.subject_id, True)] for _, item in finite if item.target)
        sequence_correct = sum(int(item.target) for _, item in finite)
    else:
        macro_correct = sum(
            weights[(item.subject_id, False)] for _, item in finite if not item.target
        )
        sequence_correct = sum(int(not item.target) for _, item in finite)
    grouped_values: dict[float, list[SequenceObservation]] = defaultdict(list)
    for value, item in finite:
        grouped_values[value].append(item)
    best: tuple[tuple[float, float, float, float], DiagnosticRule] | None = None
    for value, next_value in zip(distinct, distinct[1:]):
        for item in grouped_values[value]:
            contribution = weights[(item.subject_id, item.target)]
            if direction == "HIGH_IS_TRUE":
                macro_correct += contribution if not item.target else -contribution
                sequence_correct += 1 if not item.target else -1
            else:
                macro_correct += contribution if item.target else -contribution
                sequence_correct += 1 if item.target else -1
        threshold = (value + next_value) / 2.0
        coverage = len(finite) / len(observations)
        objective = (
            _round(macro_correct),
            _round(sequence_correct / len(observations)),
            _round(coverage),
            -threshold,
        )
        rule = DiagnosticRule(metric_id, direction, _round(threshold))
        if best is None or objective > best[0]:
            best = (objective, rule)
    assert best is not None
    return best[1], best[0][:3]


def fit_component_rule(
    observations: Sequence[SequenceObservation],
    side: str,
    component: str,
) -> tuple[DiagnosticRule | None, dict[str, Any] | None]:
    if component not in COMPONENT_METRIC_CONTRACT:
        raise ValueError(f"Unknown compound component: {component}")
    best: tuple[tuple[float, float, float, int, float], DiagnosticRule] | None = None
    for metric_index, metric_id in enumerate(COMPONENT_METRIC_CONTRACT[component]):
        direction = METRIC_DIRECTION_CONTRACT[metric_id]
        candidate = _candidate_rule(observations, side, metric_id, direction)
        if candidate is None:
            continue
        rule, scores = candidate
        key = (
            scores[0],
            scores[1],
            scores[2],
            -metric_index,
            -rule.threshold_degrees,
        )
        if best is None or key > best[0]:
            best = (key, rule)
    if best is None:
        return None, None
    rule = best[1]
    metrics = _classification_metrics(
        observations,
        [_decision(item, side, rule) for item in observations],
    )
    return rule, metrics


def _compound_decision(flexion: Decision, stability: Decision) -> Decision:
    if flexion.value is None or stability.value is None:
        reasons = []
        if flexion.value is None:
            reasons.append("FLEXION_" + (flexion.unknown_reason or "UNKNOWN"))
        if stability.value is None:
            reasons.append("STABILITY_" + (stability.unknown_reason or "UNKNOWN"))
        return Decision(None, "+".join(reasons))
    return Decision(flexion.value and stability.value)


def _rule_stability(
    rules: Sequence[DiagnosticRule | None],
    observations: Sequence[SequenceObservation],
    side: str,
) -> dict[str, Any]:
    available = [rule for rule in rules if rule is not None]
    if not available:
        return {
            "foldCount": len(rules),
            "ruleAvailableFoldCount": 0,
            "modalRuleFraction": 0.0,
            "modalRuleThresholdRelativeIqr": None,
        }
    families = Counter((rule.metric_id, rule.direction) for rule in available)
    modal, modal_count = sorted(families.items(), key=lambda item: (-item[1], item[0]))[0]
    modal_rules = [
        rule for rule in available if (rule.metric_id, rule.direction) == modal
    ]
    thresholds = [rule.threshold_degrees for rule in modal_rules]
    values = [
        value
        for item in observations
        if (value := item.side(side).metric(modal[0])) is not None and math.isfinite(value)
    ]
    value_range = max(values) - min(values) if values else 0.0
    threshold_iqr = _percentile(thresholds, 0.75) - _percentile(thresholds, 0.25)
    relative_iqr = 0.0 if value_range == 0.0 else threshold_iqr / value_range
    return {
        "foldCount": len(rules),
        "ruleAvailableFoldCount": len(available),
        "familySelectionCounts": {
            f"{metric}|{direction}": count
            for (metric, direction), count in sorted(families.items())
        },
        "modalRule": {"metricId": modal[0], "direction": modal[1]},
        "modalRuleFraction": _round(modal_count / len(rules)),
        "modalRuleThresholdDegrees": {
            "minimum": _round(min(thresholds)),
            "q25": _round(_percentile(thresholds, 0.25)),
            "median": _round(statistics.median(thresholds)),
            "q75": _round(_percentile(thresholds, 0.75)),
            "maximum": _round(max(thresholds)),
            "iqr": _round(threshold_iqr),
        },
        "modalRuleThresholdRelativeIqr": _round(relative_iqr),
        "uniqueConfigurationCount": len(
            {_canonical_sha256(rule.payload()) for rule in available}
        ),
    }


def run_outer_cv(observations: Sequence[SequenceObservation]) -> dict[str, Any]:
    ordered_observations = sorted(observations, key=lambda item: item.sequence_id)
    subjects = sorted({item.subject_id for item in ordered_observations})
    if len(subjects) < 3:
        raise GoodMorningExperimentError("LOSO requires at least three global-Z subjects")
    fold_manifest = []
    decision_manifest = []
    outer_observations: dict[str, list[SequenceObservation]] = {side: [] for side in SIDES}
    outer_decisions = {
        side: {key: [] for key in (*COMPONENT_METRIC_CONTRACT, "COMPOUND")}
        for side in SIDES
    }
    selected_rules = {
        side: {component: [] for component in COMPONENT_METRIC_CONTRACT}
        for side in SIDES
    }
    for heldout in subjects:
        training = [item for item in ordered_observations if item.subject_id != heldout]
        testing = [item for item in ordered_observations if item.subject_id == heldout]
        training_subjects = {item.subject_id for item in training}
        testing_subjects = {item.subject_id for item in testing}
        if training_subjects & testing_subjects:
            raise GoodMorningExperimentError("Global-Z subject leakage detected")
        fold = {
            "heldoutSubjectSha256": _subject_sha256(heldout),
            "trainingSubjectCount": len(training_subjects),
            "heldoutSequenceCount": len(testing),
            "subjectOverlapCount": 0,
            "sides": {},
        }
        for side in SIDES:
            rules = {}
            component_decisions = {}
            component_fold = {}
            for component in COMPONENT_METRIC_CONTRACT:
                rule, inner_metrics = fit_component_rule(training, side, component)
                decisions = [_decision(item, side, rule) for item in testing]
                rules[component] = rule
                component_decisions[component] = decisions
                selected_rules[side][component].append(rule)
                outer_decisions[side][component].extend(decisions)
                component_fold[component] = {
                    "selectedRule": rule.payload() if rule else None,
                    "innerTrainingMetrics": inner_metrics,
                    "outerHeldoutMetrics": _classification_metrics(testing, decisions),
                }
            compound_decisions = [
                _compound_decision(flexion, stability)
                for flexion, stability in zip(
                    component_decisions["FLEXION"], component_decisions["STABILITY"]
                )
            ]
            outer_observations[side].extend(testing)
            outer_decisions[side]["COMPOUND"].extend(compound_decisions)
            fold["sides"][side] = {
                "components": component_fold,
                "compoundOuterHeldoutMetrics": _classification_metrics(
                    testing, compound_decisions
                ),
            }
            for index, item in enumerate(testing):
                component_payload = {}
                for component in COMPONENT_METRIC_CONTRACT:
                    rule = rules[component]
                    decision = component_decisions[component][index]
                    component_payload[component] = {
                        "configurationId": (
                            rule.payload()["configurationId"] if rule else None
                        ),
                        "decision": (
                            "UNKNOWN"
                            if decision.value is None
                            else "TRUE" if decision.value else "FALSE"
                        ),
                        "unknownReason": decision.unknown_reason,
                    }
                compound = compound_decisions[index]
                decision_manifest.append(
                    {
                        "sequenceIdentitySha256": _canonical_sha256(
                            {"namespace": "AIHUB_TRAINING_SEQUENCE", "id": item.sequence_id}
                        ),
                        "side": side,
                        "target": item.target,
                        "components": component_payload,
                        "compoundDecision": (
                            "UNKNOWN"
                            if compound.value is None
                            else "TRUE" if compound.value else "FALSE"
                        ),
                        "compoundUnknownReason": compound.unknown_reason,
                    }
                )
        fold_manifest.append(fold)

    per_side = {}
    for side in SIDES:
        per_side[side] = {
            "components": {
                component: {
                    "outerMetrics": _classification_metrics(
                        outer_observations[side], outer_decisions[side][component]
                    ),
                    "ruleStability": _rule_stability(
                        selected_rules[side][component], ordered_observations, side
                    ),
                }
                for component in COMPONENT_METRIC_CONTRACT
            },
            "compoundOuterMetrics": _classification_metrics(
                outer_observations[side], outer_decisions[side]["COMPOUND"]
            ),
        }
    policy = CONTINUATION_POLICY
    checks = []
    for side in SIDES:
        for component in COMPONENT_METRIC_CONTRACT:
            metrics = per_side[side]["components"][component]["outerMetrics"]
            stability = per_side[side]["components"][component]["ruleStability"]
            checks.extend([
                {
                    "id": f"{side}_{component}_SUBJECT_MACRO_BALANCED_ACCURACY",
                    "observed": metrics["subjectMacroBalancedAccuracyUnknownAsMiss"],
                    "operator": ">=",
                    "required": policy[
                        "minimumEachSideEachComponentSubjectMacroBalancedAccuracyUnknownAsMiss"
                    ],
                    "passed": metrics["subjectMacroBalancedAccuracyUnknownAsMiss"]
                    >= policy[
                        "minimumEachSideEachComponentSubjectMacroBalancedAccuracyUnknownAsMiss"
                    ],
                },
                {
                    "id": f"{side}_{component}_MINIMUM_SUBJECT_ACCURACY",
                    "observed": metrics["minimumSubjectAccuracyUnknownAsMiss"],
                    "operator": ">=",
                    "required": policy[
                        "minimumEachSideEachComponentMinimumSubjectAccuracyUnknownAsMiss"
                    ],
                    "passed": metrics["minimumSubjectAccuracyUnknownAsMiss"]
                    >= policy[
                        "minimumEachSideEachComponentMinimumSubjectAccuracyUnknownAsMiss"
                    ],
                },
                {
                    "id": f"{side}_{component}_PREDICTION_COVERAGE",
                    "observed": metrics["predictionCoverage"],
                    "operator": ">=",
                    "required": policy["minimumEachSideEachComponentPredictionCoverage"],
                    "passed": metrics["predictionCoverage"]
                    >= policy["minimumEachSideEachComponentPredictionCoverage"],
                },
                {
                    "id": f"{side}_{component}_MODAL_RULE_FRACTION",
                    "observed": stability["modalRuleFraction"],
                    "operator": ">=",
                    "required": policy[
                        "minimumEachSideEachComponentModalRuleFraction"
                    ],
                    "passed": stability["modalRuleFraction"]
                    >= policy["minimumEachSideEachComponentModalRuleFraction"],
                },
                {
                    "id": f"{side}_{component}_MODAL_RULE_THRESHOLD_RELATIVE_IQR",
                    "observed": stability["modalRuleThresholdRelativeIqr"],
                    "operator": "<=",
                    "required": policy[
                        "maximumEachSideEachComponentModalRuleThresholdRelativeIqr"
                    ],
                    "passed": stability["modalRuleThresholdRelativeIqr"] is not None
                    and stability["modalRuleThresholdRelativeIqr"]
                    <= policy[
                        "maximumEachSideEachComponentModalRuleThresholdRelativeIqr"
                    ],
                },
            ])
        compound = per_side[side]["compoundOuterMetrics"]
        checks.extend([
            {
                "id": f"{side}_COMPOUND_SUBJECT_MACRO_BALANCED_ACCURACY",
                "observed": compound["subjectMacroBalancedAccuracyUnknownAsMiss"],
                "operator": ">=",
                "required": policy[
                    "minimumEachSideCompoundSubjectMacroBalancedAccuracyUnknownAsMiss"
                ],
                "passed": compound["subjectMacroBalancedAccuracyUnknownAsMiss"]
                >= policy[
                    "minimumEachSideCompoundSubjectMacroBalancedAccuracyUnknownAsMiss"
                ],
            },
            {
                "id": f"{side}_COMPOUND_MINIMUM_SUBJECT_ACCURACY",
                "observed": compound["minimumSubjectAccuracyUnknownAsMiss"],
                "operator": ">=",
                "required": policy[
                    "minimumEachSideCompoundMinimumSubjectAccuracyUnknownAsMiss"
                ],
                "passed": compound["minimumSubjectAccuracyUnknownAsMiss"]
                >= policy["minimumEachSideCompoundMinimumSubjectAccuracyUnknownAsMiss"],
            },
            {
                "id": f"{side}_COMPOUND_PREDICTION_COVERAGE",
                "observed": compound["predictionCoverage"],
                "operator": ">=",
                "required": policy["minimumEachSideCompoundPredictionCoverage"],
                "passed": compound["predictionCoverage"]
                >= policy["minimumEachSideCompoundPredictionCoverage"],
            },
        ])
    passed = all(check["passed"] for check in checks)
    return {
        "outerFoldAudit": {
            "foldCount": len(subjects),
            "trainingSubjectsPerFold": len(subjects) - 1,
            "maximumSubjectOverlapCount": max(
                fold["subjectOverlapCount"] for fold in fold_manifest
            ),
            "foldManifestSha256": _canonical_sha256(fold_manifest),
            "outerDecisionManifestSha256": _canonical_sha256(decision_manifest),
        },
        "perSide": per_side,
        "researchContinuationGate": {
            "policy": dict(policy),
            "checks": checks,
            "continuationStatus": (
                "CONTINUE_TO_INDEPENDENT_GOLD_BRIDGE_COLLECTION_ONLY"
                if passed
                else "REJECTED"
            ),
            "runtimeThresholdStatus": "NO_RUNTIME_THRESHOLD",
            "selectedRuntimeRule": None,
        },
    }


def _shared_dependency_provenance() -> dict[str, str]:
    path = Path(__file__).resolve().parent / "analyze_pose_coordinate_criteria.py"
    return {
        "coordinateParserCanonicalLfTextSha256": _canonical_lf_text_sha256(path),
        "textHashNormalization": str(PROTOCOL_CONTRACT["textHashNormalization"]),
    }


def build_report(training_root: Path) -> dict[str, Any]:
    observations, inventory = load_training(training_root)
    protocol_sha = _canonical_sha256(PROTOCOL_CONTRACT)
    script_path = Path(__file__).resolve()
    script_sha = _canonical_lf_text_sha256(script_path)
    dependency = _shared_dependency_provenance()
    experiment = run_outer_cv(observations)
    report: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": ARTIFACT_KIND,
        "decisionUse": DECISION_USE,
        "protocolId": PROTOCOL_ID,
        "protocolSha256": protocol_sha,
        "tool": {
            "relativePath": "tools/good_morning_knee_stability_experiment.py",
            "scriptCanonicalLfTextSha256": script_sha,
            "textHashNormalization": PROTOCOL_CONTRACT["textHashNormalization"],
        },
        "sharedImplementationProvenance": dependency,
        "catalogProvenance": _verified_catalog_provenance(),
        "dataUse": {
            "input": "AIHUB_OFFICIAL_TRAINING_ONLY",
            "officialValidation": "NOT_STATTED_NOT_READ_NOT_REUSED",
            "frameTime": "ORDINAL_ONLY_NO_FPS_OR_TIMESTAMP",
            "cameraView": "NOT_EVALUATED",
            "mediaPipe": "NOT_EVALUATED_NO_PIXEL_BRIDGE",
            "phaseGold": "ABSENT",
            "independentGold": "ABSENT",
        },
        "inputSnapshotThreatModel": {
            "rootPrefixPolicy": "ABSOLUTE_ANCHOR_THROUGH_ROOT_LSTAT_NO_SYMLINK_OR_REPARSE",
            "enumerationIdentity": ["st_dev", "st_ino", "st_size", "st_mtime_ns"],
            "openPolicy": "O_NOFOLLOW_WHERE_PLATFORM_SUPPORTS_IT",
            "descriptorChecks": "REGULAR_AND_EXACT_IDENTITY_BEFORE_AND_AFTER_READ",
            "parseAndHashSource": "SAME_BOUNDED_BYTE_SNAPSHOT",
            "externalContentRoot": None,
            "detachedInputSignature": None,
            "cryptographicFilesystemAuthenticity": False,
            "residualRisk": (
                "NO_EXTERNAL_CONTENT_ROOT_OR_SIGNATURE; A HOST OR FILESYSTEM CAPABLE OF "
                "FORGING IDENTITY/METADATA REMAINS OUTSIDE THIS LOCAL RACE DEFENSE"
            ),
        },
        "evaluatedScope": {
            "exerciseId": EXERCISE_ID,
            "sourceConditionId": SOURCE_CONDITION_ID,
            "coordinateDomain": "AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD",
            "sides": list(SIDES),
            "ordinalFrameCount": EXPECTED_FRAME_COUNT,
            "metricIds": list(METRIC_IDS),
        },
        "trainingInventory": inventory,
        "experimentIdentitySha256": _canonical_sha256(
            {
                "protocolSha256": protocol_sha,
                "scriptCanonicalLfTextSha256": script_sha,
                "sharedImplementationProvenance": dependency,
                "inputManifestSha256": inventory["inputManifestSha256"],
            }
        ),
        "experiment": experiment,
        "authority": dict(PROTOCOL_CONTRACT["authority"]),
        "limitations": [
            "The AI Hub condition is a sequence-level Training surrogate, not frame, phase, expert, motion-capture, or independent Gold.",
            "FLEXION and STABILITY are operational feature branches fitted to the same compound sequence label; without separate component Gold, their per-component metrics do not validate either component.",
            "The sixteen entries are sampled ordinals without reliable FPS, timestamps, dwell, velocity, or latency meaning.",
            "Camera IDs A-E do not establish lateral or other qualified runtime views.",
            "Good Morning pixels are absent, so no production MediaPipe observation bridge was evaluated.",
            "AI Hub triangulated 3D axes, origin, and units are not MediaPipe WORLD provenance.",
            "Outer-fold thresholds are diagnostic research values and are never emitted as runtime parameters.",
            "Local descriptor identity checks are not an external content root or detached signature and do not establish hostile-filesystem authenticity.",
            "UNKNOWN and nonfinite observations count as misses; selective metrics cannot satisfy the continuation gate.",
            "Even a passing continuation gate could authorize only independent bridge/Gold collection, never product behavior.",
        ],
    }
    report["reportFingerprintSha256"] = _canonical_sha256(report)
    return report


def verify_report_fingerprint(report: Mapping[str, Any]) -> bool:
    fingerprint = report.get("reportFingerprintSha256")
    if not isinstance(fingerprint, str):
        return False
    unsigned = dict(report)
    unsigned.pop("reportFingerprintSha256", None)
    return _canonical_sha256(unsigned) == fingerprint


def check_committed_report(path: Path, expected: Mapping[str, Any]) -> None:
    if not path.is_file():
        raise GoodMorningExperimentError(f"Checked artifact does not exist: {path}")
    try:
        actual_text = path.read_text(encoding="utf-8")
        actual = json.loads(actual_text)
    except (OSError, json.JSONDecodeError) as error:
        raise GoodMorningExperimentError("Checked artifact is not valid UTF-8 JSON") from error
    if not isinstance(actual, dict) or not verify_report_fingerprint(actual):
        raise GoodMorningExperimentError("Checked artifact fingerprint is invalid")
    expected_text = json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if actual != expected:
        raise GoodMorningExperimentError("Checked artifact content is stale")
    if actual_text != expected_text:
        raise GoodMorningExperimentError("Checked artifact canonical layout is stale")


def validate_output_path(path: Path, training_root: Path) -> Path:
    _reject_validation_path_before_probe(path)
    output = Path(os.path.abspath(os.fspath(path)))
    source = Path(os.path.abspath(os.fspath(training_root)))
    try:
        output.relative_to(source)
    except ValueError:
        return output
    raise GoodMorningExperimentError("Output/check artifact must be outside Training input")


def atomic_write_json(
    path: Path,
    report: Mapping[str, Any],
) -> None:
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if path.exists():
        raise GoodMorningExperimentError("Output already exists; choose a new path")
    path.parent.mkdir(parents=False, exist_ok=True)
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", delete=False, dir=path.parent
        ) as temporary:
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary_name = temporary.name
        # A hard link is an atomic no-clobber publish: an existing/racing target makes link fail.
        os.link(temporary_name, path)
        Path(temporary_name).unlink()
        temporary_name = None
    except FileExistsError as error:
        if temporary_name:
            try:
                Path(temporary_name).unlink(missing_ok=True)
            except OSError:
                pass
        raise GoodMorningExperimentError("Output appeared during no-clobber publish") from error
    except OSError as error:
        if temporary_name:
            try:
                Path(temporary_name).unlink(missing_ok=True)
            except OSError:
                pass
        raise GoodMorningExperimentError(f"Cannot write report atomically: {path}") from error


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("training_root", type=Path)
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument("--output", type=Path)
    destination.add_argument(
        "--check",
        type=Path,
        metavar="ARTIFACT",
        help="recompute from Training and fail unless ARTIFACT is canonically current",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        # Lexical rejection occurs before build_report can probe the input path.
        _reject_validation_path_before_probe(args.training_root)
        report = build_report(args.training_root)
        if args.check is not None:
            checked = validate_output_path(args.check, args.training_root)
            check_committed_report(checked, report)
            print(report["reportFingerprintSha256"])
        elif args.output is None:
            json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
            sys.stdout.write("\n")
        else:
            output = validate_output_path(args.output, args.training_root)
            atomic_write_json(output, report)
    except (OSError, GoodMorningExperimentError) as error:
        print(f"Good Morning Training-only experiment failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
