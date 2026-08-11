"""Compile the zero-authority Good Morning synthetic Gold conformance receipt.

This tool has no real-evidence or dataset input. It independently rebuilds the catalog decision
contract, validates one compiler-owned synthetic shape, and emits aggregate readiness only.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unicodedata
from typing import Any, Mapping, Sequence

try:
    from . import compile_pose_exercise_planning_matrix as planning
    from . import compile_pose_gold_decision_contract as decision
except ImportError:
    import compile_pose_exercise_planning_matrix as planning
    import compile_pose_gold_decision_contract as decision


class GoodMorningGoldConformanceError(ValueError):
    pass


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DECISION_CONTRACT = PROJECT_ROOT / "docs" / "pose-gold-decision-contract.v2.json"
DEFAULT_PROTOCOL = PROJECT_ROOT / "docs" / "good-morning-production-gold-protocol.md"
DEFAULT_OUTPUT = PROJECT_ROOT / "docs" / "good-morning-gold-conformance-readiness.v1.json"

SCHEMA_VERSION = 1
ARTIFACT_KIND = "TREX_GOOD_MORNING_GOLD_SYNTHETIC_CONFORMANCE_READINESS"
FIXTURE_ID = "trex.synthetic.good-morning.gold-conformance.v1"
EVIDENCE_CLASS = "COMPILER_OWNED_SYNTHETIC_CONFORMANCE"

BINDING_KEY = {
    "bindingId":
        "aihub-binding-sha256-f900f3dc681053ed9b705e020bac0ed27336aa5776885406a3c07a6db67d453d",
    "bindingPolicySha256":
        "05125e36ac4ebc448120f9d3cc29cbc8837585cde36bc600231a4f30935080e0",
    "exerciseId": "good-morning",
    "policyRegistrySha256":
        "4cda3be23fe34f9b1f0db1a23e301542c4fecda911a402156877cb4263cc04fc",
    "sourceConditionId":
        "aihub-exact-sha256-621f2eb88568c0d247abce9bbdbc763e8e40ae396bd0ba254a77dcd8bbc0394d",
}
PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"
MEASUREMENT_CONSTRUCT_ID = "trex.measurement.knee-flexion-angle-stability.v1"
LATERAL_VIEW_ID = "trex.view.lateral-full-body.v1"
CAPABILITY_IDS = [
    "trex.capability.pose-2d.v1",
    "trex.capability.pose-world-relative.v1",
    "trex.capability.primary-person-lock.v1",
    "trex.capability.temporal-pose.v1",
    "trex.capability.view-qualified.v1",
]
POLICY_BLOCKERS = [
    "NO_APPROVED_CALIBRATION_ARTIFACT",
    "NO_APPROVED_PHASE_SCOPE_CONTRACT",
    "NO_ATTESTED_REQUIRED_CAPABILITIES",
    "NO_BOUND_QUALIFIED_VIEW_EVIDENCE",
    "NO_BOUND_REFERENCE_EVIDENCE",
    "NO_TRUSTED_REAL_EVIDENCE_INTAKE",
]
AUTHORITY_KEYS = [
    "calibrationAuthority",
    "cueAuthority",
    "phaseDecoderAuthority",
    "releaseAuthority",
    "repCountAuthority",
    "runtimeProviderAuthority",
    "scoreAuthority",
    "shadowAuthority",
    "userPassFailUnknownAuthority",
]
READINESS_BLOCKERS = [
    "NO_APPROVED_COMPONENT_RUBRIC",
    "NO_APPROVED_PHASE_SCOPE",
    "NO_APPROVED_RIGHTS_OR_CONSENT",
    "NO_BLINDED_EXPERT_ADJUDICATION",
    "NO_CALIBRATION_ARTIFACT",
    "NO_MOCAP_REFERENCE_EVIDENCE",
    "NO_REAL_PARTICIPANT_CAPTURE",
    "NO_RELEASE_AUTHORIZATION",
    "NO_SHADOW_AUTHORIZATION",
    "NO_TRUSTED_CLOCK_ARTIFACT",
]
COMPONENT_STATES = {
    "CONDITION_SATISFIED",
    "CONDITION_VIOLATED",
    "UNKNOWN_GOLD",
    "NOT_OBSERVABLE",
}
EXPECTED_UNIT_KEYS = [
    (side, component)
    for side in ("LEFT", "RIGHT")
    for component in ("FLEXION", "STABILITY")
]
EXPECTED_TRANSITIONS = [
    "READY_TO_DESCENDING",
    "DESCENDING_TO_BOTTOM",
    "BOTTOM_TO_ASCENDING",
    "ASCENDING_TO_READY",
]


_SYNTHETIC_FIXTURE: dict[str, Any] = {
    "fixtureId": FIXTURE_ID,
    "evidenceClass": EVIDENCE_CLASS,
    "phase": {
        "scopeConvention": "START_INCLUSIVE_END_EXCLUSIVE",
        "readyBaselineStartOffsetMs": 0,
        "cycleStartOffsetMs": 500,
        "cycleEndOffsetMs": 1_600,
        "transitions": [
            {"transitionId": "READY_TO_DESCENDING", "offsetMs": 500},
            {"transitionId": "DESCENDING_TO_BOTTOM", "offsetMs": 850},
            {"transitionId": "BOTTOM_TO_ASCENDING", "offsetMs": 1_150},
            {"transitionId": "ASCENDING_TO_READY", "offsetMs": 1_600},
        ],
    },
    "clock": {
        "transformNumerator": 1,
        "transformDenominator": 1,
        "offsetMicros": 0,
        "nominalCameraFrameMicros": 33_333,
        "pairs": [
            {"deviceOffsetMicros": value, "referenceOffsetMicros": value}
            for value in (0, 250_000, 500_000, 850_000, 1_150_000, 1_600_000)
        ],
    },
    "componentUnits": [
        {"side": side, "componentId": component, "goldState": "UNKNOWN_GOLD"}
        for side, component in EXPECTED_UNIT_KEYS
    ],
    "reviews": [
        {
            "syntheticReviewerToken": f"SYNTHETIC_REVIEWER_{index}",
            "unitKeys": [f"{side}:{component}" for side, component in EXPECTED_UNIT_KEYS],
        }
        for index in range(3)
    ],
    "splitAssignments": [
        {"syntheticParticipantToken": "SYNTHETIC_P0", "split": "DEVELOPMENT"},
        {"syntheticParticipantToken": "SYNTHETIC_P1", "split": "CALIBRATION"},
        {"syntheticParticipantToken": "SYNTHETIC_P2", "split": "LOCKED_INTERNAL_TEST"},
        {"syntheticParticipantToken": "SYNTHETIC_P3", "split": "EXTERNAL_TEST"},
    ],
}


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _artifact_sha256(value: Mapping[str, Any]) -> str:
    unsigned = dict(value)
    unsigned.pop("artifactSha256", None)
    return hashlib.sha256(_canonical_json(unsigned).encode("utf-8")).hexdigest()


def _with_artifact_sha256(value: Mapping[str, Any]) -> dict[str, Any]:
    result = dict(value)
    result.pop("artifactSha256", None)
    result["artifactSha256"] = _artifact_sha256(result)
    return result


def _canonical_lf_bytes(path: Path) -> bytes:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (OSError, UnicodeError) as error:
        raise GoodMorningGoldConformanceError(f"cannot read canonical text: {path}") from error
    if text.startswith("\ufeff"):
        raise GoodMorningGoldConformanceError(f"canonical text must not contain a BOM: {path}")
    normalized = unicodedata.normalize("NFC", text.replace("\r\n", "\n").replace("\r", "\n"))
    return normalized.encode("utf-8")


def _canonical_lf_sha256(path: Path) -> str:
    return hashlib.sha256(_canonical_lf_bytes(path)).hexdigest()


def _load_and_rebuild_decision_contract() -> dict[str, Any]:
    rebuilt = decision.compile_from_paths(project_root=PROJECT_ROOT)
    try:
        committed = planning.load_json(
            DEFAULT_DECISION_CONTRACT,
            "committed decision contract",
            project_root=PROJECT_ROOT,
            require_pretty_lf=True,
        )
        decision.validate_contract(committed, expected=rebuilt)
    except (planning.PlanningMatrixError, decision.DecisionContractError) as error:
        raise GoodMorningGoldConformanceError(
            f"decision contract provenance validation failed: {error}"
        ) from error
    return rebuilt


def _find_and_validate_binding(contract: Mapping[str, Any]) -> dict[str, Any]:
    exercises = contract.get("exercises")
    if not isinstance(exercises, list):
        raise GoodMorningGoldConformanceError("decision contract exercises must be a list")
    matches = [
        binding
        for exercise in exercises
        if isinstance(exercise, dict) and exercise.get("exerciseId") == "good-morning"
        for binding in exercise.get("bindings", [])
        if isinstance(binding, dict) and binding.get("bindingKey") == BINDING_KEY
    ]
    if len(matches) != 1:
        raise GoodMorningGoldConformanceError("exact Good Morning binding must occur once")
    binding = matches[0]
    expected = {
        "bindingKey": BINDING_KEY,
        "decisionEligibility": {
            "blockers": POLICY_BLOCKERS,
            "determinateGoldStates": [],
            "permittedGoldStates": ["UNKNOWN_GOLD"],
            "state": "UNKNOWN_GOLD_ONLY",
        },
        "evidenceRequirements": {
            "calibrationProvenance": {
                "artifactSha256": None,
                "runtimeDomainId": None,
                "state": "NO_APPROVED_ARTIFACT",
            },
            "measurementConstructId": MEASUREMENT_CONSTRUCT_ID,
            "observability": "DIRECT",
            "requiredCapabilityIds": CAPABILITY_IDS,
            "viewApplicability": {
                "state": "QUALIFIED_VIEW_REQUIRED",
                "viewContractIds": [LATERAL_VIEW_ID],
            },
        },
        "interpretationState": "REVIEWED_POLICY_PROJECTION",
        "phaseRoleIds": [PHASE_ROLE_ID],
        "reviewState": "REVIEWED_ENGINEERING_V1",
        "sidePolicy": {
            "kind": "BILATERAL_INDEPENDENT",
            "resolverApprovalState": "NOT_APPLICABLE",
            "resolverArtifactSha256": None,
            "roleResolverContractId": None,
            "symbolicSlots": ["LEFT", "RIGHT"],
        },
    }
    if binding != expected:
        raise GoodMorningGoldConformanceError("Good Morning decision binding drifted")
    return copy.deepcopy(binding)


def compound_gold_state(flexion_state: str, stability_state: str) -> str:
    if flexion_state not in COMPONENT_STATES or stability_state not in COMPONENT_STATES:
        raise GoodMorningGoldConformanceError("unsupported component Gold state")
    if {flexion_state, stability_state} & {"UNKNOWN_GOLD", "NOT_OBSERVABLE"}:
        return "UNKNOWN_GOLD"
    if flexion_state == stability_state == "CONDITION_SATISFIED":
        return "CONDITION_SATISFIED"
    return "CONDITION_VIOLATED"


def _validate_phase(phase: Mapping[str, Any]) -> None:
    if set(phase) != {
        "scopeConvention",
        "readyBaselineStartOffsetMs",
        "cycleStartOffsetMs",
        "cycleEndOffsetMs",
        "transitions",
    }:
        raise GoodMorningGoldConformanceError("synthetic phase schema drifted")
    if phase["scopeConvention"] != "START_INCLUSIVE_END_EXCLUSIVE":
        raise GoodMorningGoldConformanceError("cycle must be half-open")
    start = phase["cycleStartOffsetMs"]
    end = phase["cycleEndOffsetMs"]
    baseline = phase["readyBaselineStartOffsetMs"]
    if any(isinstance(value, bool) or not isinstance(value, int) for value in (baseline, start, end)):
        raise GoodMorningGoldConformanceError("phase offsets must be integers")
    if start - baseline != 500 or not 0 <= baseline < start < end:
        raise GoodMorningGoldConformanceError("synthetic READY baseline or cycle scope drifted")
    transitions = phase["transitions"]
    if not isinstance(transitions, list) or len(transitions) != len(EXPECTED_TRANSITIONS):
        raise GoodMorningGoldConformanceError("synthetic phase transition count drifted")
    ids = [item.get("transitionId") for item in transitions if isinstance(item, dict)]
    offsets = [item.get("offsetMs") for item in transitions if isinstance(item, dict)]
    if ids != EXPECTED_TRANSITIONS or len(offsets) != len(EXPECTED_TRANSITIONS):
        raise GoodMorningGoldConformanceError("synthetic phase transition order drifted")
    if any(isinstance(value, bool) or not isinstance(value, int) for value in offsets):
        raise GoodMorningGoldConformanceError("transition offsets must be integers")
    if offsets != sorted(set(offsets)) or offsets[0] != start or offsets[-1] != end:
        raise GoodMorningGoldConformanceError("transition boundaries do not close the cycle")


def _validate_clock(clock: Mapping[str, Any]) -> None:
    if set(clock) != {
        "transformNumerator",
        "transformDenominator",
        "offsetMicros",
        "nominalCameraFrameMicros",
        "pairs",
    }:
        raise GoodMorningGoldConformanceError("synthetic clock schema drifted")
    numerator = clock["transformNumerator"]
    denominator = clock["transformDenominator"]
    offset = clock["offsetMicros"]
    frame = clock["nominalCameraFrameMicros"]
    if any(isinstance(value, bool) or not isinstance(value, int) for value in (numerator, denominator, offset, frame)):
        raise GoodMorningGoldConformanceError("clock parameters must be integers")
    if numerator <= 0 or denominator <= 0 or frame <= 0:
        raise GoodMorningGoldConformanceError("clock scale and frame interval must be positive")
    pairs = clock["pairs"]
    if not isinstance(pairs, list) or len(pairs) < 2:
        raise GoodMorningGoldConformanceError("clock requires paired samples")
    previous_device = previous_reference = None
    residuals: list[int] = []
    for pair in pairs:
        if not isinstance(pair, dict) or set(pair) != {"deviceOffsetMicros", "referenceOffsetMicros"}:
            raise GoodMorningGoldConformanceError("clock pair schema drifted")
        device_value = pair["deviceOffsetMicros"]
        reference_value = pair["referenceOffsetMicros"]
        if any(isinstance(value, bool) or not isinstance(value, int) for value in (device_value, reference_value)):
            raise GoodMorningGoldConformanceError("clock samples must be integers")
        if previous_device is not None and (device_value <= previous_device or reference_value <= previous_reference):
            raise GoodMorningGoldConformanceError("clock samples must be strictly increasing")
        mapped_numerator = numerator * device_value + denominator * offset
        residual_numerator = abs(mapped_numerator - denominator * reference_value)
        residuals.append((residual_numerator + denominator - 1) // denominator)
        previous_device, previous_reference = device_value, reference_value
    limit = min(10_000, frame // 2)
    ordered = sorted(residuals)
    p95 = ordered[(95 * len(ordered) - 1) // 100]
    if p95 > limit or max(ordered) > frame:
        raise GoodMorningGoldConformanceError("clock residual exceeds the draft protocol")


def _validate_component_units(units: Any) -> None:
    if not isinstance(units, list) or len(units) != len(EXPECTED_UNIT_KEYS):
        raise GoodMorningGoldConformanceError("synthetic component unit count drifted")
    observed: list[tuple[str, str]] = []
    for unit in units:
        if not isinstance(unit, dict) or set(unit) != {"side", "componentId", "goldState"}:
            raise GoodMorningGoldConformanceError("synthetic component unit schema drifted")
        key = (unit["side"], unit["componentId"])
        observed.append(key)
        if unit["goldState"] != "UNKNOWN_GOLD":
            raise GoodMorningGoldConformanceError("synthetic fixture cannot carry determinate Gold")
    if observed != EXPECTED_UNIT_KEYS or len(set(observed)) != len(observed):
        raise GoodMorningGoldConformanceError("synthetic component exact set drifted")


def _validate_reviews(reviews: Any) -> None:
    if not isinstance(reviews, list) or len(reviews) != 3:
        raise GoodMorningGoldConformanceError("synthetic review shape requires three reviewers")
    expected = [f"{side}:{component}" for side, component in EXPECTED_UNIT_KEYS]
    reviewers: set[str] = set()
    for review in reviews:
        if not isinstance(review, dict) or set(review) != {"syntheticReviewerToken", "unitKeys"}:
            raise GoodMorningGoldConformanceError("synthetic review schema drifted")
        token = review["syntheticReviewerToken"]
        if not isinstance(token, str) or not token.startswith("SYNTHETIC_REVIEWER_"):
            raise GoodMorningGoldConformanceError("reviewer token must be compiler-owned")
        reviewers.add(token)
        if review["unitKeys"] != expected:
            raise GoodMorningGoldConformanceError("review unit exact set drifted")
    if len(reviewers) != 3:
        raise GoodMorningGoldConformanceError("synthetic reviewer tokens must be unique")


def _validate_split(assignments: Any) -> None:
    expected_splits = {
        "DEVELOPMENT",
        "CALIBRATION",
        "LOCKED_INTERNAL_TEST",
        "EXTERNAL_TEST",
    }
    if not isinstance(assignments, list) or len(assignments) != 4:
        raise GoodMorningGoldConformanceError("synthetic split shape drifted")
    by_participant: dict[str, str] = {}
    for assignment in assignments:
        if not isinstance(assignment, dict) or set(assignment) != {
            "syntheticParticipantToken", "split"
        }:
            raise GoodMorningGoldConformanceError("synthetic split schema drifted")
        token = assignment["syntheticParticipantToken"]
        split = assignment["split"]
        if not isinstance(token, str) or not token.startswith("SYNTHETIC_P"):
            raise GoodMorningGoldConformanceError("participant token must be compiler-owned")
        if split not in expected_splits:
            raise GoodMorningGoldConformanceError("unsupported synthetic split")
        previous = by_participant.setdefault(token, split)
        if previous != split:
            raise GoodMorningGoldConformanceError("participant crosses synthetic splits")
    if len(by_participant) != 4 or set(by_participant.values()) != expected_splits:
        raise GoodMorningGoldConformanceError("synthetic split exact set drifted")


def _validate_fixture(fixture: Mapping[str, Any]) -> None:
    if set(fixture) != {
        "fixtureId", "evidenceClass", "phase", "clock", "componentUnits", "reviews",
        "splitAssignments",
    }:
        raise GoodMorningGoldConformanceError("synthetic fixture schema drifted")
    if fixture["fixtureId"] != FIXTURE_ID or fixture["evidenceClass"] != EVIDENCE_CLASS:
        raise GoodMorningGoldConformanceError("only the compiler-owned fixture is accepted")
    _validate_phase(fixture["phase"])
    _validate_clock(fixture["clock"])
    _validate_component_units(fixture["componentUnits"])
    _validate_reviews(fixture["reviews"])
    _validate_split(fixture["splitAssignments"])


def compile_readiness() -> dict[str, Any]:
    decision_contract = _load_and_rebuild_decision_contract()
    binding = _find_and_validate_binding(decision_contract)
    fixture = copy.deepcopy(_SYNTHETIC_FIXTURE)
    _validate_fixture(fixture)
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": ARTIFACT_KIND,
        "decisionUse": "SYNTHETIC_SCHEMA_CONFORMANCE_ONLY_NO_REAL_INPUT_OR_GOLD_AUTHORITY",
        "compilerImplementation": {
            "relativePath": "tools/good_morning_gold_conformance.py",
            "canonicalLfSha256": _canonical_lf_sha256(Path(__file__)),
        },
        "inputProvenance": {
            "decisionContractArtifactSha256": decision_contract["artifactSha256"],
            "policyRegistrySha256": BINDING_KEY["policyRegistrySha256"],
            "bindingKey": binding["bindingKey"],
            "protocolDraftRelativePath": "docs/good-morning-production-gold-protocol.md",
            "protocolDraftCanonicalLfSha256": _canonical_lf_sha256(DEFAULT_PROTOCOL),
        },
        "syntheticConformance": {
            "fixtureId": FIXTURE_ID,
            "evidenceClass": EVIDENCE_CLASS,
            "fixtureCount": 1,
            "syntheticCycleShapeCount": 1,
            "syntheticComponentUnitShapeCount": 4,
            "syntheticReviewerTokenCount": 3,
            "syntheticParticipantTokenCount": 4,
            "phaseShapeValidated": True,
            "clockShapeValidated": True,
            "componentExactSetValidated": True,
            "reviewShapeValidated": True,
            "participantSplitShapeValidated": True,
            "allSyntheticComponentStates": "UNKNOWN_GOLD",
        },
        "readiness": {
            "state": "NOT_READY",
            "actualEvidenceCounts": {
                "realParticipantCount": 0,
                "realCaptureCount": 0,
                "realCycleCount": 0,
                "realReviewCount": 0,
                "realAdjudicatedComponentUnitCount": 0,
                "calibrationArtifactCount": 0,
                "shadowAuthorizationCount": 0,
            },
            "blockers": READINESS_BLOCKERS,
        },
        "authority": {key: 0 for key in AUTHORITY_KEYS},
        "contractBoundary": {
            "realEvidenceInputIncluded": False,
            "datasetPathInputIncluded": False,
            "restrictedBundleParserIncluded": False,
            "participantRecordOutputIncluded": False,
            "rawFrameOrLandmarkOutputIncluded": False,
            "absoluteTimestampOutputIncluded": False,
            "runtimeThresholdIncluded": False,
            "phaseDecoderIncluded": False,
            "verdictScoreCueIncluded": False,
            "runtimeProviderIncluded": False,
            "shadowOrReleaseTransitionIncluded": False,
        },
    }
    return _with_artifact_sha256(result)


def render_json(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _confined_output(path: Path) -> Path:
    absolute = Path(os.path.abspath(os.fspath(path)))
    try:
        absolute.relative_to(PROJECT_ROOT)
    except ValueError as error:
        raise GoodMorningGoldConformanceError("output must remain inside the project root") from error
    forbidden = {Path(__file__).resolve(), DEFAULT_PROTOCOL.resolve(), DEFAULT_DECISION_CONTRACT.resolve()}
    if absolute.resolve(strict=False) in forbidden:
        raise GoodMorningGoldConformanceError("output must not collide with an input")
    if absolute.parent.resolve(strict=True) != absolute.parent:
        raise GoodMorningGoldConformanceError("output parent must not redirect")
    if absolute.exists() and (absolute.is_symlink() or not absolute.is_file()):
        raise GoodMorningGoldConformanceError("existing output must be a regular file")
    return absolute


def write_or_check(path: Path, value: Mapping[str, Any], *, check: bool) -> None:
    payload = render_json(value)
    if check:
        try:
            current = path.read_bytes()
        except OSError as error:
            raise GoodMorningGoldConformanceError("cannot read readiness artifact") from error
        if current != payload:
            raise GoodMorningGoldConformanceError("readiness artifact is stale")
        return
    if path.exists():
        raise GoodMorningGoldConformanceError("readiness artifact already exists")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=path.parent, prefix=f".{path.name}.", suffix=".tmp", delete=False
        ) as stream:
            temporary = Path(stream.name)
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, path)
        except OSError as error:
            raise GoodMorningGoldConformanceError("atomic no-clobber publish failed") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--output", type=Path)
    destination.add_argument("--check", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        value = compile_readiness()
        requested = args.check if args.check is not None else args.output
        output = _confined_output(requested)
        write_or_check(output, value, check=args.check is not None)
    except (GoodMorningGoldConformanceError, OSError) as error:
        raise SystemExit(f"Good Morning Gold conformance failed: {error}") from None
    print(value["artifactSha256"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
