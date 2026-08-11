#!/usr/bin/env python3
"""Quantify a causal lateral barbell-squat phase-decoder candidate on Training only.

The AI Hub ``active`` mask is a movement-window prior, not phase Gold.  This tool therefore
reports agreement with a deliberately named retrospective morphology *surrogate*, never phase
accuracy.  It never accepts an official Validation input and can never emit release authority,
PASS/FAIL posture decisions, scores, repetitions, or corrective cues.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
import re
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

if __package__:
    from .analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _angle_degrees,
        _canonical_sha256,
        _load_json_object,
        extract_subject_id,
    )
    from .barbell_squat_validation_experiment import (
        EXPECTED_SPLITS,
        TYPE_CODES,
        SquatExperimentError,
        _active_frame_contract,
        _active_semantic_payload,
        _discover_candidates,
        _file_sha256,
        _raw_points,
        _three_d_coordinate_payload,
        _two_d_coordinate_payload,
        _validate_internal_sequence_uniqueness,
        _verified_catalog_provenance,
        atomic_write_json,
        validate_output_path,
        verify_report_fingerprint,
    )
else:
    from analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _angle_degrees,
        _canonical_sha256,
        _load_json_object,
        extract_subject_id,
    )
    from barbell_squat_validation_experiment import (
        EXPECTED_SPLITS,
        TYPE_CODES,
        SquatExperimentError,
        _active_frame_contract,
        _active_semantic_payload,
        _discover_candidates,
        _file_sha256,
        _raw_points,
        _three_d_coordinate_payload,
        _two_d_coordinate_payload,
        _validate_internal_sequence_uniqueness,
        _verified_catalog_provenance,
        atomic_write_json,
        validate_output_path,
        verify_report_fingerprint,
    )


SCHEMA_VERSION = 1
ARTIFACT_KIND = "AIHUB_BARBELL_SQUAT_TRAINING_ONLY_CAUSAL_PHASE_SURROGATE_EXPERIMENT"
DECISION_USE = "RESEARCH_ONLY_NO_PHASE_GOLD_NO_RUNTIME_OR_RELEASE_AUTHORITY"
PROTOCOL_ID = "aihub.barbell-squat.lateral-causal-phase-surrogate.protocol.v1"
EVALUATED_SIGNAL_FAMILY_ID = (
    "trex.research-phase-signal.barbell-squat.bilateral-knee-flexion-median.v1"
)
EVALUATED_DECODER_FAMILY_ID = (
    "trex.research-phase-decoder.barbell-squat.absolute-knee-flexion-hysteresis.v1"
)
EVALUATED_COORDINATE_DOMAIN = "AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD"
EVALUATED_VIEW_ROLE = "LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION"
ROUND_DIGITS = 10
PHASES = ("READY", "DESCENDING", "BOTTOM", "ASCENDING")
UNKNOWN = "UNKNOWN"
MINIMUM_SURROGATE_RUN_FRAMES = 6
MINIMUM_SURROGATE_ROM_DEGREES = 20.0
SURROGATE_BOTTOM_FRACTION_FROM_PEAK = 0.15
SURROGATE_READY_FRACTION_FROM_TOP = 0.20
SURROGATE_REVERSAL_TOLERANCE_FRACTION = 0.08
SURROGATE_MAX_SIGNIFICANT_REVERSALS_PER_SIDE = 1

PARAMETER_GRID: Mapping[str, tuple[float | int, ...]] = {
    "baselineFrameCount": (2, 3),
    "trailingMedianWindow": (1, 3),
    "baselineStabilityDegrees": (8.0, 12.0),
    "readyBandDegrees": (8.0, 12.0),
    "descentEntryDegrees": (10.0, 15.0),
    "motionDegreesPerSample": (1.5, 3.0),
    "bottomMinimumDisplacementDegrees": (40.0, 55.0, 70.0),
    "reversalDegreesPerSample": (1.5, 3.0),
}

RESEARCH_CONTINUATION_POLICY: Mapping[str, float] = {
    "minimumSurrogateEligibleSequenceCoverage": 0.70,
    "minimumSurrogateEligibleSubjectCoverage": 1.0,
    "minimumOuterSubjectMacroSurrogateRecall": 0.70,
    "minimumOuterPredictionCoverage": 0.60,
    "minimumOuterSubjectCoverage": 0.40,
    "minimumOuterPerSurrogatePhaseCoverage": 0.40,
    "minimumOuterCompletedTopologyCoverage": 0.50,
    "minimumModalConfigurationFraction": 0.25,
    "maximumNormalizedParameterIqr": 0.50,
    "requiredCausalPrefixInvariance": 1.0,
}

PROTOCOL_CONTRACT: Mapping[str, Any] = {
    "protocolId": PROTOCOL_ID,
    "inputRole": "AIHUB_OFFICIAL_TRAINING_ONLY",
    "officialValidationRole": "FORBIDDEN_NOT_READ_NOT_REUSED",
    "independentUnit": "GLOBAL_Z_SUBJECT",
    "outerEvaluation": "LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT",
    "candidateSelection": "INNER_TRAINING_SUBJECTS_ONLY_LEXICOGRAPHIC_OBJECTIVE",
    "learnedThresholdRole": "RESEARCH_CANDIDATE_DIAGNOSTICS_ONLY_NOT_RUNTIME_PARAMETERS",
    "phaseGold": "ABSENT",
    "activeMaskRole": "MOVEMENT_WINDOW_PRIOR_ONLY_NOT_PHASE_OR_BOUNDARY_GOLD",
    "frameTimeEvidence": "SAMPLED_FRAME_ORDER_ONLY_NO_RELIABLE_FPS_OR_INTERVAL",
    "coordinateDomain": "AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD",
    "runtimeViewClaim": "LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION",
    "evaluatedSignalFamilyId": EVALUATED_SIGNAL_FAMILY_ID,
    "bilateralSignal": "ARITHMETIC_MEDIAN_OF_LEFT_AND_RIGHT_3D_KNEE_FLEXION",
    "activeRunPolicy": "DO_NOT_BRIDGE_INACTIVE_GAPS",
    "surrogateReference": {
        "name": "RETROSPECTIVE_FULL_RUN_MORPHOLOGY_SURROGATE_NOT_PHASE_GOLD",
        "smoothing": "CENTERED_THREE_SAMPLE_MEDIAN_FUTURE_ALLOWED_REFERENCE_ONLY",
        "dominantRun": "MAXIMUM_ROM_ELIGIBLE_CONTIGUOUS_ACTIVE_RUN_EARLIEST_TIE",
        "minimumFrames": MINIMUM_SURROGATE_RUN_FRAMES,
        "minimumRomDegrees": MINIMUM_SURROGATE_ROM_DEGREES,
        "bottomFractionFromPeak": SURROGATE_BOTTOM_FRACTION_FROM_PEAK,
        "readyFractionFromTop": SURROGATE_READY_FRACTION_FROM_TOP,
        "significantReversalToleranceFraction": SURROGATE_REVERSAL_TOLERANCE_FRACTION,
        "maximumSignificantReversalsPerSide":
            SURROGATE_MAX_SIGNIFICANT_REVERSALS_PER_SIDE,
        "requiredTopology": list(PHASES),
    },
    "decoderFamily": {
        "id": EVALUATED_DECODER_FAMILY_ID,
        "causality": "PAST_AND_CURRENT_SAMPLES_ONLY_FROZEN_INITIAL_BASELINE",
        "resetScope": "EACH_CONTIGUOUS_ACTIVE_RUN_INDEPENDENTLY",
        "unknownPolicy": "CALIBRATION_OR_UNSUPPORTED_TRANSITION_ABSTAINS",
        "parameterGrid": PARAMETER_GRID,
    },
    "fitObjective": [
        "SUBJECT_MACRO_SURROGATE_RECALL_UNKNOWN_COUNTS_AS_MISS",
        "MINIMUM_SUBJECT_DETERMINATE_COVERAGE",
        "FRAME_DETERMINATE_COVERAGE",
        "COMPLETED_ORDERED_TOPOLOGY_COVERAGE",
        "LOWEST_CANONICAL_CONFIGURATION_ID_TIE_BREAK",
    ],
    "causalAudit": "EVERY_PREFIX_OUTPUT_MUST_EQUAL_SAME_FULL_RUN_OUTPUT_PREFIX",
    "implementationDependencyPaths": [
        "tools/analyze_pose_coordinate_criteria.py",
        "tools/barbell_squat_validation_experiment.py",
    ],
    "textHashNormalization": "UTF8_CANONICAL_LF_CRLF_AND_CR_NORMALIZED_TO_LF",
    "committedArtifactCheck": "CANONICAL_JSON_LAYOUT_AFTER_NEWLINE_NORMALIZATION",
    "researchContinuationPolicy": RESEARCH_CONTINUATION_POLICY,
    "releaseAuthority": 0,
    "shadowAuthority": 0,
    "userDecisionAuthority": 0,
}


@dataclass(frozen=True)
class TrainingSequence:
    sequence_id: str
    type_code: str
    subject_id: str
    day_id: str
    active_runs: tuple[tuple[float, ...], ...]
    frame_count: int
    active_frame_count: int
    two_d_sha256: str
    three_d_sha256: str
    two_d_coordinate_sha256: str
    three_d_coordinate_sha256: str
    active_contract_sha256: str


@dataclass(frozen=True)
class SurrogateRecord:
    sequence_id: str
    subject_id: str
    values: tuple[float, ...]
    labels: tuple[str, ...]
    rom_degrees: float
    active_run_index: int


@dataclass(frozen=True)
class DecoderConfig:
    baseline_frame_count: int
    trailing_median_window: int
    baseline_stability_degrees: float
    ready_band_degrees: float
    descent_entry_degrees: float
    motion_degrees_per_sample: float
    bottom_minimum_displacement_degrees: float
    reversal_degrees_per_sample: float

    def payload(self) -> dict[str, float | int | str]:
        values: dict[str, float | int | str] = {
            "baselineFrameCount": self.baseline_frame_count,
            "trailingMedianWindow": self.trailing_median_window,
            "baselineStabilityDegrees": self.baseline_stability_degrees,
            "readyBandDegrees": self.ready_band_degrees,
            "descentEntryDegrees": self.descent_entry_degrees,
            "motionDegreesPerSample": self.motion_degrees_per_sample,
            "bottomMinimumDisplacementDegrees":
                self.bottom_minimum_displacement_degrees,
            "reversalDegreesPerSample": self.reversal_degrees_per_sample,
        }
        values["configurationId"] = "cfg-" + _canonical_sha256(values)[:16]
        return values

    @property
    def configuration_id(self) -> str:
        return str(self.payload()["configurationId"])


@dataclass(frozen=True)
class FrameDecision:
    phase: str | None
    abstention_reason: str | None


@dataclass(frozen=True)
class SubjectCounts:
    subject_id: str
    reference_by_phase: tuple[int, ...]
    correct_by_phase: tuple[int, ...]
    determinate_by_phase: tuple[int, ...]
    selective_correct: int
    reference_frames: int
    determinate_frames: int
    sequence_count: int
    completed_topology_count: int
    abstention_reasons: tuple[tuple[str, int], ...]


def _round(value: float) -> float:
    result = round(float(value), ROUND_DIGITS)
    return 0.0 if result == 0.0 else result


def _percentile(values: Iterable[float], probability: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        raise ValueError("Cannot take a percentile of no values")
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
    except UnicodeDecodeError as error:
        raise SquatExperimentError(f"Expected UTF-8 text for portable hashing: {path}") from error
    canonical_lf = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(canonical_lf.encode("utf-8")).hexdigest()


def _partition(values: Sequence[float], lengths: Sequence[int]) -> tuple[tuple[float, ...], ...]:
    runs: list[tuple[float, ...]] = []
    offset = 0
    for length in lengths:
        runs.append(tuple(values[offset : offset + length]))
        offset += length
    if offset != len(values):
        raise SequenceDataError("Active run lengths do not cover bilateral knee flexion")
    return tuple(runs)


def _bilateral_knee_flexion(points_value: Any) -> float:
    points = _raw_points(points_value, 3)
    flexions = []
    for side in ("Left", "Right"):
        included = _angle_degrees(
            points[f"{side} Hip"],
            points[f"{side} Knee"],
            points[f"{side} Ankle"],
        )
        flexion = 180.0 - included
        if not math.isfinite(flexion) or not 0.0 <= flexion <= 180.0:
            raise SequenceDataError("Knee flexion must be finite and in [0, 180]")
        flexions.append(flexion)
    return _round(statistics.median(flexions))


def load_training(root: Path) -> tuple[list[TrainingSequence], dict[str, Any]]:
    """Fail-closed loader for the fixed 720-sequence official Training factorial."""

    root = root.resolve()
    if not root.is_dir():
        raise SquatExperimentError(f"Training root is not a directory: {root}")
    if any(part.casefold().startswith("2.validation") for part in root.parts):
        raise SquatExperimentError("Official Validation is forbidden in this Training-only tool")

    candidates, metadata_inventory = _discover_candidates(root)
    expected = EXPECTED_SPLITS["TRAINING"]
    sequences: list[TrainingSequence] = []
    counts_by_type: Counter[str] = Counter()
    frame_histogram: Counter[int] = Counter()
    active_frame_histogram: Counter[int] = Counter()
    active_run_histogram: Counter[int] = Counter()
    active_frames_included = 0
    inactive_frames_excluded = 0
    active_runs_total = 0
    non_contiguous_active_sequences = 0
    manifest: list[dict[str, Any]] = []

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
            active_indices, active_run_lengths, _ = _active_frame_contract(frames_2d)
            active_values = tuple(
                _bilateral_knee_flexion(frames_3d[index].get("pts"))
                for index in active_indices
            )
            active_runs = _partition(active_values, active_run_lengths)
            subject_id = extract_subject_id(two_d, candidate.two_d_path)
            if candidate.selection_day_id.startswith("__UNKNOWN"):
                raise SequenceDataError("Day identity is unavailable")
            two_d_sha = _file_sha256(candidate.two_d_path)
            three_d_sha = _file_sha256(candidate.three_d_path)
            two_d_coordinate_sha = _canonical_sha256(_two_d_coordinate_payload(frames_2d))
            three_d_coordinate_sha = _canonical_sha256(_three_d_coordinate_payload(frames_3d))
            active_contract_sha = _canonical_sha256(_active_semantic_payload(frames_2d))
        except (OSError, SequenceDataError) as error:
            raise SquatExperimentError(
                f"Unusable selected Training sequence {candidate.sequence_id}: {error}"
            ) from error

        item = TrainingSequence(
            sequence_id=candidate.sequence_id,
            type_code=candidate.type_code,
            subject_id=subject_id,
            day_id=candidate.selection_day_id,
            active_runs=active_runs,
            frame_count=len(frames_3d),
            active_frame_count=len(active_values),
            two_d_sha256=two_d_sha,
            three_d_sha256=three_d_sha,
            two_d_coordinate_sha256=two_d_coordinate_sha,
            three_d_coordinate_sha256=three_d_coordinate_sha,
            active_contract_sha256=active_contract_sha,
        )
        sequences.append(item)
        counts_by_type[item.type_code] += 1
        frame_histogram[item.frame_count] += 1
        active_frame_histogram[item.active_frame_count] += 1
        active_run_histogram[len(item.active_runs)] += 1
        active_frames_included += item.active_frame_count
        inactive_frames_excluded += item.frame_count - item.active_frame_count
        active_runs_total += len(item.active_runs)
        non_contiguous_active_sequences += int(len(item.active_runs) > 1)
        manifest.append(
            {
                "sequenceId": item.sequence_id,
                "typeCode": item.type_code,
                "subjectSha256": _subject_sha256(item.subject_id),
                "twoDSha256": item.two_d_sha256,
                "threeDSha256": item.three_d_sha256,
                "twoDCoordinateSha256": item.two_d_coordinate_sha256,
                "threeDCoordinateSha256": item.three_d_coordinate_sha256,
                "activeContractSha256": item.active_contract_sha256,
            }
        )

    sequences.sort(key=lambda item: item.sequence_id)
    manifest.sort(key=lambda item: item["sequenceId"])
    expected_codes = set(TYPE_CODES)
    if set(counts_by_type) != expected_codes:
        raise SquatExperimentError("Training type coverage changed")
    wrong_counts = {
        code: counts_by_type[code]
        for code in TYPE_CODES
        if counts_by_type[code] != expected["recordsPerType"]
    }
    if wrong_counts or len(sequences) != expected["recordsPerType"] * len(TYPE_CODES):
        raise SquatExperimentError(f"Training sequence counts changed: {wrong_counts}")
    subjects = sorted({item.subject_id for item in sequences})
    if len(subjects) != expected["subjects"]:
        raise SquatExperimentError(
            f"Training expected {expected['subjects']} global subjects, found {len(subjects)}"
        )

    # Reuse the established four-way duplicate contract without reimplementing it.
    class _HashView:
        def __init__(self, item: TrainingSequence) -> None:
            self.two_d_sha256 = item.two_d_sha256
            self.three_d_sha256 = item.three_d_sha256
            self.two_d_coordinate_sha256 = item.two_d_coordinate_sha256
            self.three_d_coordinate_sha256 = item.three_d_coordinate_sha256

    _validate_internal_sequence_uniqueness(
        [_HashView(item) for item in sequences],
        "TRAINING_PHASE_EXPERIMENT",
    )
    return sequences, {
        "split": "TRAINING",
        "officialValidationReadCount": 0,
        "sequenceCount": len(sequences),
        "subjectCount": len(subjects),
        "typeCounts": {code: counts_by_type[code] for code in TYPE_CODES},
        "dayIds": sorted({item.day_id for item in sequences}),
        "frameCountHistogram": {str(k): v for k, v in sorted(frame_histogram.items())},
        "activeFrameCountHistogram": {
            str(k): v for k, v in sorted(active_frame_histogram.items())
        },
        "activeRunCountHistogram": {
            str(k): v for k, v in sorted(active_run_histogram.items())
        },
        "metadataAudit": {
            key: metadata_inventory[key]
            for key in (
                "twoDMetadataAudited",
                "validMetadata",
                "malformedMetadata",
                "selectedMetadata",
                "unpaired",
                "metadataConflict",
            )
        },
        "phaseExtractionCounts": {
            "activeFramesIncluded": active_frames_included,
            "inactiveFramesExcluded": inactive_frames_excluded,
            "activeRuns": active_runs_total,
            "nonContiguousActiveSequences": non_contiguous_active_sequences,
        },
        "subjectSetSha256": _canonical_sha256([_subject_sha256(value) for value in subjects]),
        "inputManifestSha256": _canonical_sha256(manifest),
        "internalSequenceHashUniqueness": {
            "twoDRawContent": True,
            "threeDRawContent": True,
            "twoDCanonicalCoordinates": True,
            "threeDCanonicalCoordinates": True,
        },
    }


def _centered_median_three(values: Sequence[float]) -> tuple[float, ...]:
    return tuple(
        statistics.median(values[max(0, index - 1) : min(len(values), index + 2)])
        for index in range(len(values))
    )


def build_surrogate(sequence: TrainingSequence) -> tuple[SurrogateRecord | None, str | None]:
    """Build a retrospective morphology reference; this is intentionally not causal or Gold."""

    eligible: list[tuple[float, int, tuple[float, ...]]] = []
    for index, run in enumerate(sequence.active_runs):
        if len(run) < MINIMUM_SURROGATE_RUN_FRAMES:
            continue
        smoothed = _centered_median_three(run)
        rom = max(smoothed) - min(smoothed)
        if rom >= MINIMUM_SURROGATE_ROM_DEGREES:
            eligible.append((rom, index, smoothed))
    if not eligible:
        return None, "NO_CONTIGUOUS_ACTIVE_RUN_WITH_MINIMUM_FRAMES_AND_ROM"

    # Highest ROM first, then earliest run.  Full-run selection is surrogate-only lookahead.
    rom, run_index, smoothed = sorted(eligible, key=lambda item: (-item[0], item[1]))[0]
    peak_index = max(range(len(smoothed)), key=lambda index: (smoothed[index], -index))
    bottom_threshold = max(smoothed) - SURROGATE_BOTTOM_FRACTION_FROM_PEAK * rom
    bottom_start = peak_index
    while bottom_start > 0 and smoothed[bottom_start - 1] >= bottom_threshold:
        bottom_start -= 1
    bottom_end = peak_index + 1
    while bottom_end < len(smoothed) and smoothed[bottom_end] >= bottom_threshold:
        bottom_end += 1
    if bottom_start == 0 or bottom_end == len(smoothed):
        return None, "SURROGATE_BOTTOM_TOUCHES_ACTIVE_RUN_BOUNDARY"

    tolerance = SURROGATE_REVERSAL_TOLERANCE_FRACTION * rom
    before_reversals = sum(
        1
        for left, right in zip(smoothed[:bottom_start], smoothed[1 : bottom_start + 1])
        if right - left < -tolerance
    )
    after_reversals = sum(
        1
        for left, right in zip(smoothed[bottom_end - 1 :], smoothed[bottom_end:])
        if right - left > tolerance
    )
    if (
        before_reversals > SURROGATE_MAX_SIGNIFICANT_REVERSALS_PER_SIDE
        or after_reversals > SURROGATE_MAX_SIGNIFICANT_REVERSALS_PER_SIDE
    ):
        return None, "SURROGATE_MULTIPLE_SIGNIFICANT_DIRECTION_REVERSALS"

    top_reference = statistics.median(tuple(smoothed[:2]) + tuple(smoothed[-2:]))
    ready_threshold = top_reference + SURROGATE_READY_FRACTION_FROM_TOP * rom
    labels: list[str] = []
    for index, value in enumerate(smoothed):
        if bottom_start <= index < bottom_end:
            labels.append("BOTTOM")
        elif index < bottom_start:
            labels.append("READY" if value <= ready_threshold else "DESCENDING")
        else:
            labels.append("READY" if value <= ready_threshold else "ASCENDING")
    if set(labels) != set(PHASES):
        return None, "SURROGATE_DOES_NOT_CONTAIN_COMPLETE_FOUR_STATE_TOPOLOGY"

    return (
        SurrogateRecord(
            sequence_id=sequence.sequence_id,
            subject_id=sequence.subject_id,
            values=sequence.active_runs[run_index],
            labels=tuple(labels),
            rom_degrees=_round(rom),
            active_run_index=run_index,
        ),
        None,
    )


def decoder_configurations() -> tuple[DecoderConfig, ...]:
    keys = tuple(PARAMETER_GRID)
    configs = []
    for values in itertools.product(*(PARAMETER_GRID[key] for key in keys)):
        payload = dict(zip(keys, values))
        configs.append(
            DecoderConfig(
                baseline_frame_count=int(payload["baselineFrameCount"]),
                trailing_median_window=int(payload["trailingMedianWindow"]),
                baseline_stability_degrees=float(payload["baselineStabilityDegrees"]),
                ready_band_degrees=float(payload["readyBandDegrees"]),
                descent_entry_degrees=float(payload["descentEntryDegrees"]),
                motion_degrees_per_sample=float(payload["motionDegreesPerSample"]),
                bottom_minimum_displacement_degrees=float(
                    payload["bottomMinimumDisplacementDegrees"]
                ),
                reversal_degrees_per_sample=float(payload["reversalDegreesPerSample"]),
            )
        )
    return tuple(sorted(configs, key=lambda item: item.configuration_id))


def decode_causal(values: Sequence[float], config: DecoderConfig) -> tuple[FrameDecision, ...]:
    """Pure causal state machine; every decision consumes only values at or before its index."""

    if config.trailing_median_window not in {1, 3}:
        raise ValueError("Only predeclared trailing median windows are allowed")
    decisions: list[FrameDecision] = []
    smoothed: list[float] = []
    baseline: float | None = None
    state = "READY"
    maximum_displacement = 0.0

    for index, value in enumerate(values):
        if not math.isfinite(value):
            decisions.append(FrameDecision(None, "NON_FINITE_SIGNAL"))
            continue
        window_start = max(0, index - config.trailing_median_window + 1)
        smoothed.append(float(statistics.median(values[window_start : index + 1])))
        if index + 1 < config.baseline_frame_count:
            decisions.append(FrameDecision(None, "INITIAL_BASELINE_CALIBRATION"))
            continue
        if baseline is None:
            baseline_window = smoothed[: config.baseline_frame_count]
            if max(baseline_window) - min(baseline_window) > config.baseline_stability_degrees:
                decisions.append(FrameDecision(None, "INITIAL_BASELINE_NOT_STABLE"))
                # Initial READY is an invariant; a later window must not silently replace it.
                decisions.extend(
                    FrameDecision(None, "INITIAL_BASELINE_NOT_STABLE")
                    for _ in range(index + 1, len(values))
                )
                return tuple(decisions)
            baseline = float(statistics.median(baseline_window))

        previous = smoothed[index - 1] if index else smoothed[index]
        delta = smoothed[index] - previous
        displacement = smoothed[index] - baseline
        maximum_displacement = max(maximum_displacement, displacement)
        phase: str | None = None
        reason: str | None = None

        if state == "READY":
            if (
                displacement >= config.descent_entry_degrees
                and delta >= config.motion_degrees_per_sample
            ):
                state = "DESCENDING"
                phase = state
            elif displacement <= config.ready_band_degrees:
                phase = "READY"
            else:
                reason = "READY_TO_DESCENDING_EVIDENCE_INSUFFICIENT"
        elif state == "DESCENDING":
            if (
                maximum_displacement >= config.bottom_minimum_displacement_degrees
                and delta <= -config.reversal_degrees_per_sample
            ):
                state = "BOTTOM"
                phase = state
            elif delta >= -config.reversal_degrees_per_sample:
                phase = "DESCENDING"
            else:
                reason = "REVERSAL_BEFORE_MINIMUM_BOTTOM_DISPLACEMENT"
        elif state == "BOTTOM":
            if delta <= -config.motion_degrees_per_sample:
                state = "ASCENDING"
                phase = state
            elif abs(delta) < config.reversal_degrees_per_sample:
                phase = "BOTTOM"
            else:
                reason = "BOTTOM_TO_ASCENDING_EVIDENCE_INSUFFICIENT"
        elif state == "ASCENDING":
            if abs(displacement) <= config.ready_band_degrees:
                state = "READY"
                phase = state
            elif delta <= config.motion_degrees_per_sample:
                phase = "ASCENDING"
            else:
                reason = "ASCENDING_DIRECTION_CONTRADICTION"
        else:  # pragma: no cover - state is closed above
            raise AssertionError(f"Unexpected decoder state: {state}")
        decisions.append(FrameDecision(phase, reason))
    return tuple(decisions)


def _completed_topology(decisions: Sequence[FrameDecision]) -> bool:
    states = [item.phase for item in decisions if item.phase is not None]
    required = ("READY", "DESCENDING", "BOTTOM", "ASCENDING", "READY")
    cursor = 0
    for state in states:
        if state == required[cursor]:
            cursor += 1
            if cursor == len(required):
                return True
    return False


def _subject_counts(records: Sequence[SurrogateRecord], config: DecoderConfig) -> SubjectCounts:
    if not records:
        raise ValueError("A subject count requires records")
    subject = records[0].subject_id
    if any(item.subject_id != subject for item in records):
        raise ValueError("Subject records must share one global subject")
    reference = [0] * len(PHASES)
    correct = [0] * len(PHASES)
    determinate = [0] * len(PHASES)
    selective_correct = 0
    completed = 0
    reasons: Counter[str] = Counter()
    for record in records:
        decisions = decode_causal(record.values, config)
        if len(decisions) != len(record.labels):
            raise AssertionError("Decoder and surrogate frame counts diverged")
        completed += int(_completed_topology(decisions))
        for expected, decision in zip(record.labels, decisions):
            phase_index = PHASES.index(expected)
            reference[phase_index] += 1
            if decision.phase is None:
                reasons[decision.abstention_reason or "UNSPECIFIED"] += 1
                continue
            determinate[phase_index] += 1
            if decision.phase == expected:
                correct[phase_index] += 1
                selective_correct += 1
    return SubjectCounts(
        subject_id=subject,
        reference_by_phase=tuple(reference),
        correct_by_phase=tuple(correct),
        determinate_by_phase=tuple(determinate),
        selective_correct=selective_correct,
        reference_frames=sum(reference),
        determinate_frames=sum(determinate),
        sequence_count=len(records),
        completed_topology_count=completed,
        abstention_reasons=tuple(sorted(reasons.items())),
    )


def aggregate_metrics(counts: Sequence[SubjectCounts]) -> dict[str, Any]:
    if not counts:
        raise ValueError("Metrics require at least one subject")
    reference = [sum(item.reference_by_phase[i] for item in counts) for i in range(len(PHASES))]
    correct = [sum(item.correct_by_phase[i] for item in counts) for i in range(len(PHASES))]
    determinate = [
        sum(item.determinate_by_phase[i] for item in counts) for i in range(len(PHASES))
    ]
    subject_recalls = [
        statistics.mean(
            item.correct_by_phase[i] / item.reference_by_phase[i]
            for i in range(len(PHASES))
            if item.reference_by_phase[i]
        )
        for item in counts
    ]
    subject_coverages = [item.determinate_frames / item.reference_frames for item in counts]
    total_reference = sum(reference)
    total_determinate = sum(determinate)
    selective_correct = sum(item.selective_correct for item in counts)
    sequences = sum(item.sequence_count for item in counts)
    completed = sum(item.completed_topology_count for item in counts)
    abstentions: Counter[str] = Counter()
    for item in counts:
        abstentions.update(dict(item.abstention_reasons))
    return {
        "subjectCount": len(counts),
        "sequenceCount": sequences,
        "referenceFrameCount": total_reference,
        "determinateFrameCount": total_determinate,
        "abstainedFrameCount": total_reference - total_determinate,
        "predictionCoverage": _round(total_determinate / total_reference),
        "minimumSubjectCoverage": _round(min(subject_coverages)),
        "p10SubjectCoverage": _round(_percentile(subject_coverages, 0.10)),
        "subjectMacroSurrogateRecall": _round(statistics.mean(subject_recalls)),
        "frameSurrogateAgreementUnknownAsMiss": _round(sum(correct) / total_reference),
        "selectiveSurrogateAgreement": (
            _round(selective_correct / total_determinate) if total_determinate else None
        ),
        "completedOrderedTopologyCoverage": _round(completed / sequences),
        "coverageBySurrogatePhase": {
            PHASES[i]: _round(determinate[i] / reference[i]) if reference[i] else None
            for i in range(len(PHASES))
        },
        "recallBySurrogatePhaseUnknownAsMiss": {
            PHASES[i]: _round(correct[i] / reference[i]) if reference[i] else None
            for i in range(len(PHASES))
        },
        "abstentionReasons": dict(sorted(abstentions.items())),
    }


def _fit_key(metrics: Mapping[str, Any]) -> tuple[float, float, float, float]:
    return (
        float(metrics["subjectMacroSurrogateRecall"]),
        float(metrics["minimumSubjectCoverage"]),
        float(metrics["predictionCoverage"]),
        float(metrics["completedOrderedTopologyCoverage"]),
    )


def _configuration_stability(selected: Sequence[DecoderConfig]) -> dict[str, Any]:
    counts = Counter(item.configuration_id for item in selected)
    modal_id, modal_count = sorted(counts.items(), key=lambda item: (-item[1], item[0]))[0]
    parameter_accessors = {
        "baselineFrameCount": lambda item: float(item.baseline_frame_count),
        "trailingMedianWindow": lambda item: float(item.trailing_median_window),
        "baselineStabilityDegrees": lambda item: item.baseline_stability_degrees,
        "readyBandDegrees": lambda item: item.ready_band_degrees,
        "descentEntryDegrees": lambda item: item.descent_entry_degrees,
        "motionDegreesPerSample": lambda item: item.motion_degrees_per_sample,
        "bottomMinimumDisplacementDegrees":
            lambda item: item.bottom_minimum_displacement_degrees,
        "reversalDegreesPerSample": lambda item: item.reversal_degrees_per_sample,
    }
    summaries: dict[str, Any] = {}
    normalized_iqrs = []
    for name, accessor in parameter_accessors.items():
        values = [accessor(item) for item in selected]
        q25 = _percentile(values, 0.25)
        q75 = _percentile(values, 0.75)
        grid_values = [float(value) for value in PARAMETER_GRID[name]]
        grid_range = max(grid_values) - min(grid_values)
        normalized_iqr = 0.0 if grid_range == 0 else (q75 - q25) / grid_range
        normalized_iqrs.append(normalized_iqr)
        summaries[name] = {
            "minimum": _round(min(values)),
            "q25": _round(q25),
            "median": _round(statistics.median(values)),
            "q75": _round(q75),
            "maximum": _round(max(values)),
            "normalizedIqrWithinPredeclaredGrid": _round(normalized_iqr),
        }
    return {
        "foldCount": len(selected),
        "uniqueConfigurationCount": len(counts),
        "modalConfigurationId": modal_id,
        "modalConfigurationFraction": _round(modal_count / len(selected)),
        "configurationSelectionCounts": dict(sorted(counts.items())),
        "maximumNormalizedParameterIqr": _round(max(normalized_iqrs)),
        "parameters": summaries,
    }


def run_training_only_experiment(
    sequences: Sequence[TrainingSequence],
    inventory: Mapping[str, Any],
) -> dict[str, Any]:
    unavailable: Counter[str] = Counter()
    records: list[SurrogateRecord] = []
    for item in sequences:
        record, reason = build_surrogate(item)
        if record is None:
            unavailable[reason or "UNSPECIFIED"] += 1
        else:
            records.append(record)
    subjects = sorted({item.subject_id for item in sequences})
    record_subjects = sorted({item.subject_id for item in records})
    records_by_subject = {
        subject: [item for item in records if item.subject_id == subject]
        for subject in record_subjects
    }
    configs = decoder_configurations()
    counts_by_config_subject: dict[tuple[str, str], SubjectCounts] = {}
    for config in configs:
        for subject in record_subjects:
            counts_by_config_subject[(config.configuration_id, subject)] = _subject_counts(
                records_by_subject[subject],
                config,
            )

    folds: list[dict[str, Any]] = []
    selected_configs: list[DecoderConfig] = []
    outer_counts: list[SubjectCounts] = []
    decision_manifest: list[dict[str, Any]] = []
    prefix_count = 0
    prefix_match_count = 0
    for heldout in subjects:
        training_subjects = [subject for subject in record_subjects if subject != heldout]
        best_config: DecoderConfig | None = None
        best_metrics: dict[str, Any] | None = None
        for config in configs:
            metrics = aggregate_metrics(
                [
                    counts_by_config_subject[(config.configuration_id, subject)]
                    for subject in training_subjects
                ]
            )
            if best_metrics is None or _fit_key(metrics) > _fit_key(best_metrics):
                best_config = config
                best_metrics = metrics
        assert best_config is not None and best_metrics is not None
        selected_configs.append(best_config)
        heldout_eligible = heldout in records_by_subject
        heldout_metrics = None
        if heldout_eligible:
            heldout_counts = counts_by_config_subject[(best_config.configuration_id, heldout)]
            outer_counts.append(heldout_counts)
            heldout_metrics = aggregate_metrics([heldout_counts])

            for record in records_by_subject[heldout]:
                full = decode_causal(record.values, best_config)
                for prefix_length in range(1, len(record.values) + 1):
                    prefix = decode_causal(record.values[:prefix_length], best_config)
                    prefix_count += 1
                    if prefix == full[:prefix_length]:
                        prefix_match_count += 1
                decision_manifest.append(
                    {
                        "sequenceSha256": _canonical_sha256(
                            {"namespace": "AIHUB_SEQUENCE", "sequenceId": record.sequence_id}
                        ),
                        "configurationId": best_config.configuration_id,
                        "surrogateLabels": record.labels,
                        "decisions": [item.phase or UNKNOWN for item in full],
                        "abstentionReasons": [item.abstention_reason for item in full],
                    }
                )

        folds.append(
            {
                "heldoutSubjectSha256": _subject_sha256(heldout),
                "trainingSubjectCount": len(training_subjects),
                "trainingSubjectSetSha256": _canonical_sha256(
                    [_subject_sha256(value) for value in training_subjects]
                ),
                "subjectOverlapCount": 0,
                "heldoutSurrogateEligible": heldout_eligible,
                "selectedConfiguration": best_config.payload(),
                "innerTrainingMetrics": best_metrics,
                "outerHeldoutMetrics": heldout_metrics,
            }
        )

    prefix_rate = prefix_match_count / prefix_count if prefix_count else 0.0
    if prefix_rate != 1.0:
        raise SquatExperimentError("Causal prefix invariance failed")
    outer_metrics = aggregate_metrics(outer_counts)
    stability = _configuration_stability(selected_configs)
    policy = RESEARCH_CONTINUATION_POLICY
    surrogate_coverage = len(records) / len(sequences)
    per_phase_coverages = [
        value
        for value in outer_metrics["coverageBySurrogatePhase"].values()
        if value is not None
    ]
    checks = {
        "completeFixedTrainingFactorial": (
            inventory.get("sequenceCount") == 720
            and inventory.get("subjectCount") == 42
            and inventory.get("officialValidationReadCount") == 0
        ),
        "minimumSurrogateEligibleSequenceCoverage": (
            surrogate_coverage >= policy["minimumSurrogateEligibleSequenceCoverage"]
        ),
        "minimumSurrogateEligibleSubjectCoverage": (
            len(record_subjects) / len(subjects)
            >= policy["minimumSurrogateEligibleSubjectCoverage"]
        ),
        "minimumOuterSubjectMacroSurrogateRecall": (
            outer_metrics["subjectMacroSurrogateRecall"]
            >= policy["minimumOuterSubjectMacroSurrogateRecall"]
        ),
        "minimumOuterPredictionCoverage": (
            outer_metrics["predictionCoverage"] >= policy["minimumOuterPredictionCoverage"]
        ),
        "minimumOuterSubjectCoverage": (
            outer_metrics["minimumSubjectCoverage"]
            >= policy["minimumOuterSubjectCoverage"]
        ),
        "minimumOuterPerSurrogatePhaseCoverage": (
            bool(per_phase_coverages)
            and min(per_phase_coverages)
            >= policy["minimumOuterPerSurrogatePhaseCoverage"]
        ),
        "minimumOuterCompletedTopologyCoverage": (
            outer_metrics["completedOrderedTopologyCoverage"]
            >= policy["minimumOuterCompletedTopologyCoverage"]
        ),
        "minimumModalConfigurationFraction": (
            stability["modalConfigurationFraction"]
            >= policy["minimumModalConfigurationFraction"]
        ),
        "maximumNormalizedParameterIqr": (
            stability["maximumNormalizedParameterIqr"]
            <= policy["maximumNormalizedParameterIqr"]
        ),
        "causalPrefixInvariance": prefix_rate == policy["requiredCausalPrefixInvariance"],
    }
    continuation = all(checks.values())
    return {
        "surrogateReference": {
            "role": "RETROSPECTIVE_MORPHOLOGY_SURROGATE_NOT_PHASE_GOLD",
            "eligibleSequenceCount": len(records),
            "ineligibleSequenceCount": len(sequences) - len(records),
            "eligibleSequenceCoverage": _round(surrogate_coverage),
            "eligibleSubjectCount": len(record_subjects),
            "ineligibleSubjectCount": len(subjects) - len(record_subjects),
            "eligibleSubjectCoverage": _round(len(record_subjects) / len(subjects)),
            "ineligibleSubjectSetSha256": _canonical_sha256(
                [_subject_sha256(value) for value in sorted(set(subjects) - set(record_subjects))]
            ),
            "ineligibleReasons": dict(sorted(unavailable.items())),
            "romDegrees": {
                "minimum": _round(min(item.rom_degrees for item in records)),
                "median": _round(statistics.median(item.rom_degrees for item in records)),
                "maximum": _round(max(item.rom_degrees for item in records)),
            },
        },
        "candidateGrid": {
            "familyId": PROTOCOL_CONTRACT["decoderFamily"]["id"],
            "configurationCount": len(configs),
            "configurationManifestSha256": _canonical_sha256(
                [item.payload() for item in configs]
            ),
        },
        "subjectGroupedOuterEvaluation": {
            "split": "TRAINING_GLOBAL_Z_LOSO",
            "foldCount": len(folds),
            "foldManifestSha256": _canonical_sha256(folds),
            "folds": folds,
            "outerMetrics": outer_metrics,
        },
        "causalPrefixAudit": {
            "definition": "DECODE_EVERY_PREFIX_EQUALS_FULL_RUN_OUTPUT_PREFIX",
            "prefixCount": prefix_count,
            "matchingPrefixCount": prefix_match_count,
            "invarianceRate": _round(prefix_rate),
            "decisionManifestSha256": _canonical_sha256(decision_manifest),
        },
        "thresholdStabilityAcrossOuterFolds": stability,
        "researchContinuationGate": {
            "policy": policy,
            "checks": checks,
            "allChecksPass": continuation,
            "surrogateGateStatus": "PASS" if continuation else "FAIL",
            # No same-signal surrogate can authorize decoder parameters, even when its
            # descriptive continuation checks pass.  Independent phase Gold is mandatory.
            "continuationStatus": "REJECTED",
            "runtimeDecoderParameterStatus": "NO_RUNTIME_DECODER_PARAMETERS",
            "diagnosticThresholdRole":
                "RESEARCH_CANDIDATE_DIAGNOSTICS_ONLY_NOT_RUNTIME_PARAMETERS",
            "disposition": "RETAIN_RESEARCH_SPECIFICATION_ONLY",
            "releaseAuthority": 0,
            "shadowAuthority": 0,
            "runtimeProviderAuthority": 0,
        },
    }


def _script_sha256() -> str:
    return _canonical_lf_text_sha256(Path(__file__).resolve())


def _shared_dependency_provenance() -> dict[str, str]:
    tools_root = Path(__file__).resolve().parent
    return {
        "coordinateParserCanonicalLfTextSha256": _canonical_lf_text_sha256(
            tools_root / "analyze_pose_coordinate_criteria.py"
        ),
        "validationHelperCanonicalLfTextSha256": _canonical_lf_text_sha256(
            tools_root / "barbell_squat_validation_experiment.py"
        ),
        "textHashNormalization": PROTOCOL_CONTRACT["textHashNormalization"],
    }


def _portable_catalog_provenance() -> dict[str, Any]:
    provenance = _verified_catalog_provenance()
    provenance.pop("artifactByteSha256", None)
    catalog_path = Path(__file__).resolve().parent.parent / "docs" / "aihub-exercise-catalog.json"
    provenance["artifactCanonicalLfTextSha256"] = _canonical_lf_text_sha256(catalog_path)
    provenance["textHashNormalization"] = PROTOCOL_CONTRACT["textHashNormalization"]
    return provenance


def build_report(training_root: Path) -> dict[str, Any]:
    sequences, inventory = load_training(training_root)
    protocol_sha = _canonical_sha256(PROTOCOL_CONTRACT)
    script_sha = _script_sha256()
    shared_dependencies = _shared_dependency_provenance()
    experiment = run_training_only_experiment(sequences, inventory)
    report: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": ARTIFACT_KIND,
        "decisionUse": DECISION_USE,
        "protocolId": PROTOCOL_ID,
        "protocolSha256": protocol_sha,
        "tool": {
            "relativePath": "tools/barbell_squat_phase_training_experiment.py",
            "scriptCanonicalLfTextSha256": script_sha,
            "textHashNormalization": PROTOCOL_CONTRACT["textHashNormalization"],
        },
        "sharedImplementationProvenance": shared_dependencies,
        "catalogProvenance": _portable_catalog_provenance(),
        "dataUse": {
            "input": "AIHUB_OFFICIAL_TRAINING_ONLY",
            "officialValidation": "NOT_READ_NOT_REUSED",
            "phaseGold": "ABSENT",
            "activeMask": "MOVEMENT_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD",
            "absoluteOrRelativePathsPersisted": False,
        },
        "evaluatedScope": {
            "signalFamilyId": EVALUATED_SIGNAL_FAMILY_ID,
            "decoderFamilyId": EVALUATED_DECODER_FAMILY_ID,
            "coordinateDomain": EVALUATED_COORDINATE_DOMAIN,
            "viewRole": EVALUATED_VIEW_ROLE,
            "frontCandidateEvaluated": False,
        },
        "trainingInventory": inventory,
        "experimentIdentitySha256": _canonical_sha256(
            {
                "protocolSha256": protocol_sha,
                "scriptCanonicalLfTextSha256": script_sha,
                "sharedImplementationProvenance": shared_dependencies,
                "inputManifestSha256": inventory["inputManifestSha256"],
            }
        ),
        "experiment": experiment,
        "authority": {
            "releaseAuthority": 0,
            "shadowAuthority": 0,
            "runtimeProviderAuthority": 0,
            "userPassFailUnknownAuthority": 0,
            "scoreAuthority": 0,
            "cueAuthority": 0,
            "repCountAuthority": 0,
        },
        "limitations": [
            "No expert, motion-capture, or manually annotated phase Gold exists.",
            "The retrospective morphology surrogate is derived from the same knee signal and is circular evidence, not accuracy evidence.",
            "AI Hub active is only a sampled movement-window prior and has no phase boundary semantics.",
            "AI Hub frame order has no reliable FPS or inter-frame timing ground truth.",
            "AI Hub triangulated 3D is not paired MediaPipe WORLD output from the production camera pipeline.",
            "The offline dominant-active-run selection uses future context and is not a runtime segmentation algorithm.",
            "Official Validation was not read or reused; it remains consumed by earlier development.",
            "Passing the research-continuation gate only prioritizes independent phase-Gold collection.",
        ],
    }
    report["reportFingerprintSha256"] = _canonical_sha256(report)
    return report


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
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--expected-old-fingerprint")
    return parser


def check_committed_report(path: Path, expected: Mapping[str, Any]) -> None:
    """Verify fingerprint, semantic identity, and canonical layout without writing.

    Text-mode reading normalizes CRLF/CR checkouts to LF.  This intentionally makes the check
    portable while still rejecting reordered, compacted, or otherwise stale JSON.
    """

    if not path.is_file():
        raise SquatExperimentError(f"Checked artifact does not exist: {path}")
    try:
        actual_text = path.read_text(encoding="utf-8")
        actual = json.loads(actual_text)
    except (OSError, json.JSONDecodeError) as error:
        raise SquatExperimentError("Checked artifact is not valid UTF-8 JSON") from error
    if not isinstance(actual, dict) or not verify_report_fingerprint(actual):
        raise SquatExperimentError("Checked artifact fingerprint is invalid")
    expected_text = json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if actual != expected:
        raise SquatExperimentError("Checked artifact content is stale")
    if actual_text != expected_text:
        raise SquatExperimentError(
            "Checked artifact canonical layout is not current after newline normalization"
        )


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        training_root = args.training_root.resolve()
        report = build_report(training_root)
        if args.check is not None:
            if args.overwrite or args.expected_old_fingerprint is not None:
                raise SquatExperimentError("Overwrite options cannot be used with --check")
            checked = validate_output_path(args.check, (training_root,))
            check_committed_report(checked, report)
            print(report["reportFingerprintSha256"])
        elif args.output is None:
            if args.overwrite or args.expected_old_fingerprint is not None:
                raise SquatExperimentError("Overwrite options require --output")
            json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
            sys.stdout.write("\n")
        else:
            output = validate_output_path(args.output, (training_root,))
            atomic_write_json(
                output,
                report,
                overwrite=args.overwrite,
                expected_old_fingerprint=args.expected_old_fingerprint,
            )
    except (OSError, SquatExperimentError) as error:
        print(f"barbell squat Training-only phase experiment failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
