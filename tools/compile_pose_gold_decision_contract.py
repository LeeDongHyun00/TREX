#!/usr/bin/env python3
"""Compile a catalog-wide, zero-authority pose Gold decision contract.

The artifact produced here is a schema and planning contract only.  It consumes the validated
M8 planning registry and matrix, preserves all 167 exact binding tuples, and states why no
binding may yet produce determinate Gold.  It deliberately contains no restricted-bundle
parser, timestamp model, evaluator, score, cue, session, runtime, or release interface.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    from . import compile_pose_exercise_planning_matrix as planning
except ImportError:  # Direct ``python tools/...py`` execution.
    import compile_pose_exercise_planning_matrix as planning


SCHEMA_VERSION = 2
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = planning.DEFAULT_SOURCE
DEFAULT_POLICY = planning.DEFAULT_POLICY
DEFAULT_APPROVAL = planning.DEFAULT_APPROVAL
DEFAULT_REGISTRY = planning.DEFAULT_REGISTRY
DEFAULT_MATRIX = planning.DEFAULT_OUTPUT
DEFAULT_OUTPUT = PROJECT_ROOT / "docs" / "pose-gold-decision-contract.v2.json"

EXPECTED_SCOPE = {
    "exerciseCount": 41,
    "exactConditionCount": 97,
    "bindingCount": 167,
    "reviewedBindingCount": 148,
    "unresolvedBindingCount": 19,
    "releaseEligibleBindingCount": 0,
    "determinateEligibleBindingCount": 0,
}

PHASE_ROLE_IDS = (
    "trex.phase-role.compound-transition.v1",
    "trex.phase-role.concentric.v1",
    "trex.phase-role.contracted-endpoint.v1",
    "trex.phase-role.full-cycle.v1",
    "trex.phase-role.lengthened-endpoint.v1",
    "trex.phase-role.static-hold.v1",
)

SIDE_POLICY_SYMBOLIC_SLOTS = {
    "ACTIVE_LIMB": ["ACTIVE_LIMB"],
    "ALTERNATING_PAIR": ["ALTERNATING_PAIR"],
    "BILATERAL_COUPLED": ["BILATERAL_PAIR"],
    "BILATERAL_INDEPENDENT": ["LEFT", "RIGHT"],
    "CONTRALATERAL_PAIR": ["CONTRALATERAL_PAIR"],
    "GLOBAL_BODY": ["GLOBAL_BODY"],
    "LEAD_LIMB": ["LEAD_LIMB"],
    "MIDLINE": ["MIDLINE"],
    "TRAIL_LIMB": ["TRAIL_LIMB"],
}
ROLE_RELATIVE_SIDE_POLICIES = frozenset(
    {
        "ACTIVE_LIMB",
        "ALTERNATING_PAIR",
        "CONTRALATERAL_PAIR",
        "LEAD_LIMB",
        "TRAIL_LIMB",
    }
)

REVIEWED_BLOCKERS = (
    "NO_APPROVED_CALIBRATION_ARTIFACT",
    "NO_APPROVED_PHASE_SCOPE_CONTRACT",
    "NO_ATTESTED_REQUIRED_CAPABILITIES",
    "NO_BOUND_REFERENCE_EVIDENCE",
    "NO_TRUSTED_REAL_EVIDENCE_INTAKE",
)
ROLE_RESOLVER_BLOCKER = "NO_APPROVED_SIDE_ROLE_RESOLVER_ARTIFACT"
VIEW_STATE_BLOCKERS = {
    "QUALIFIED_VIEW_REQUIRED": "NO_BOUND_QUALIFIED_VIEW_EVIDENCE",
    "NO_CAMERA_VIEW_SUFFICIENT": "CAMERA_VIEW_NOT_SUFFICIENT",
    "NOT_APPLICABLE": None,
}

TOP_LEVEL_KEYS = {
    "schemaVersion",
    "artifactKind",
    "artifactSha256",
    "authority",
    "decisionUse",
    "contractBoundary",
    "compilerImplementation",
    "compilerImplementationUse",
    "inputProvenance",
    "catalogScope",
    "decisionStateContract",
    "phaseRoleCatalog",
    "sidePolicyCatalog",
    "registeredExerciseProfiles",
    "exercises",
}


class DecisionContractError(RuntimeError):
    """Raised when a decision contract could imply unsupported authority or drift."""


def canonical_json(value: Any) -> str:
    try:
        return planning.canonical_json(value)
    except (planning.PlanningMatrixError, ValueError) as error:
        raise DecisionContractError(f"cannot canonicalize decision contract: {error}") from error


def canonical_json_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def artifact_sha256(value: Mapping[str, Any]) -> str:
    unsigned = dict(value)
    unsigned.pop("artifactSha256", None)
    return canonical_json_sha256(unsigned)


def with_artifact_sha256(value: Mapping[str, Any]) -> dict[str, Any]:
    result = dict(value)
    result.pop("artifactSha256", None)
    result["artifactSha256"] = canonical_json_sha256(result)
    return result


def render_json(value: Mapping[str, Any]) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        indent=2,
        allow_nan=False,
    ) + "\n"


def canonical_lf_text_sha256(path: Path) -> str:
    try:
        text = path.read_bytes().decode("utf-8", errors="strict")
    except (OSError, UnicodeError) as error:
        raise DecisionContractError(f"cannot hash compiler implementation: {error}") from error
    if unicodedata.normalize("NFC", text) != text:
        raise DecisionContractError("compiler implementation must use Unicode NFC")
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _phase_role_catalog(
    binding_counts: Mapping[str, int],
) -> list[dict[str, Any]]:
    return [
        {
            "phaseRoleId": role_id,
            "bindingCount": binding_counts[role_id],
            "scopeContractApprovalState": "NO_APPROVED_SCOPE_CONTRACT",
            "scopeContractArtifactSha256": None,
            "permittedDecisionState": "UNKNOWN_GOLD_ONLY",
            "determinateDecisionEligible": False,
        }
        for role_id in PHASE_ROLE_IDS
    ]


def _side_policy_catalog(
    binding_counts: Mapping[str, int],
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for kind in sorted(SIDE_POLICY_SYMBOLIC_SLOTS):
        relative = kind in ROLE_RELATIVE_SIDE_POLICIES
        result.append(
            {
                "sidePolicyKind": kind,
                "bindingCount": binding_counts[kind],
                "symbolicSlots": list(SIDE_POLICY_SYMBOLIC_SLOTS[kind]),
                "resolverRequirement": (
                    "EXACT_BINDING_POLICY_RESOLVER_REQUIRED"
                    if relative
                    else "NOT_APPLICABLE"
                ),
                "resolverContractIdSource": (
                    "BINDING_POLICY" if relative else "NOT_APPLICABLE"
                ),
                "resolverApprovalState": (
                    "NO_APPROVED_RESOLVER_ARTIFACT" if relative else "NOT_APPLICABLE"
                ),
                "resolverArtifactSha256": None,
                "permittedDecisionState": "UNKNOWN_GOLD_ONLY",
                "determinateDecisionEligible": False,
            }
        )
    return result


def _binding_key(
    exercise_id: str,
    binding: Mapping[str, Any],
    *,
    policy_registry_sha256: str,
) -> dict[str, Any]:
    return {
        "exerciseId": exercise_id,
        "sourceConditionId": binding["sourceConditionId"],
        "bindingId": binding["bindingId"],
        "bindingPolicySha256": binding["bindingPolicySha256"],
        "policyRegistrySha256": policy_registry_sha256,
    }


def _binding_contract(
    exercise_id: str,
    binding: Mapping[str, Any],
    *,
    policy_registry_sha256: str,
) -> dict[str, Any]:
    projection = binding["interpretationProjection"]
    key = _binding_key(
        exercise_id,
        binding,
        policy_registry_sha256=policy_registry_sha256,
    )
    if projection is None:
        return {
            "bindingKey": key,
            "reviewState": binding["reviewState"],
            "interpretationState": "SOURCE_INTERPRETATION_UNRESOLVED",
            "phaseRoleIds": [],
            "sidePolicy": None,
            "evidenceRequirements": None,
            "decisionEligibility": {
                "state": "SOURCE_INTERPRETATION_UNRESOLVED",
                "permittedGoldStates": [],
                "determinateGoldStates": [],
                "blockers": ["SOURCE_INTERPRETATION_UNRESOLVED"],
            },
        }

    phase = projection["phaseApplicability"]
    if phase["state"] != "BOUND" or not phase["phaseRoleIds"]:
        raise DecisionContractError("reviewed binding must retain bound policy phase roles")
    phase_role_ids = list(phase["phaseRoleIds"])
    if phase_role_ids != sorted(set(phase_role_ids)):
        raise DecisionContractError("binding phase roles must be sorted and unique")
    side = projection["sidePolicy"]
    kind = side["kind"]
    if kind not in SIDE_POLICY_SYMBOLIC_SLOTS:
        raise DecisionContractError(f"unsupported policy side kind {kind!r}")
    resolver = side["roleResolverContractId"]
    role_relative = kind in ROLE_RELATIVE_SIDE_POLICIES
    if role_relative != (resolver is not None):
        raise DecisionContractError("side resolver presence differs from side-policy contract")
    blockers = list(REVIEWED_BLOCKERS)
    view = projection["viewApplicability"]
    try:
        view_blocker = VIEW_STATE_BLOCKERS[view["state"]]
    except KeyError as error:
        raise DecisionContractError("unsupported policy view-applicability state") from error
    if view_blocker is not None:
        blockers.append(view_blocker)
    if role_relative:
        blockers.append(ROLE_RESOLVER_BLOCKER)
    blockers.sort()
    calibration = projection["calibrationProvenance"]
    return {
        "bindingKey": key,
        "reviewState": binding["reviewState"],
        "interpretationState": "REVIEWED_POLICY_PROJECTION",
        "phaseRoleIds": phase_role_ids,
        "sidePolicy": {
            "kind": kind,
            "roleResolverContractId": resolver,
            "symbolicSlots": list(SIDE_POLICY_SYMBOLIC_SLOTS[kind]),
            "resolverApprovalState": (
                "NO_APPROVED_RESOLVER_ARTIFACT"
                if role_relative
                else "NOT_APPLICABLE"
            ),
            "resolverArtifactSha256": None,
        },
        "evidenceRequirements": {
            "measurementConstructId": projection["measurementConstructId"],
            "observability": projection["observability"],
            "requiredCapabilityIds": list(projection["requiredCapabilityIds"]),
            "viewApplicability": {
                "state": view["state"],
                "viewContractIds": list(view["viewContractIds"]),
            },
            "calibrationProvenance": {
                "state": calibration["state"],
                "artifactSha256": calibration["artifactSha256"],
                "runtimeDomainId": calibration["runtimeDomainId"],
            },
        },
        "decisionEligibility": {
            "state": "UNKNOWN_GOLD_ONLY",
            "permittedGoldStates": ["UNKNOWN_GOLD"],
            "determinateGoldStates": [],
            "blockers": blockers,
        },
    }


def _registered_profile(
    exercise: Mapping[str, Any],
    binding_contracts: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    plan = exercise["registeredPlan"]
    if plan is None:
        raise DecisionContractError("registered profile requires a registered plan")
    phase_counts: Counter[str] = Counter()
    side_counts: Counter[str] = Counter()
    resolvers: set[str] = set()
    tuples: list[dict[str, Any]] = []
    for binding in binding_contracts:
        side = binding["sidePolicy"]
        if side is None:
            tuples.append(
                {
                    "bindingKey": dict(binding["bindingKey"]),
                    "phaseRoleIds": [],
                    "sidePolicyKind": None,
                    "roleResolverContractId": None,
                    "symbolicSlots": [],
                    "decisionEligibilityState": (
                        "SOURCE_INTERPRETATION_UNRESOLVED"
                    ),
                }
            )
            continue
        for role_id in binding["phaseRoleIds"]:
            phase_counts[role_id] += 1
        side_counts[side["kind"]] += 1
        if side["roleResolverContractId"] is not None:
            resolvers.add(side["roleResolverContractId"])
        tuples.append(
            {
                "bindingKey": dict(binding["bindingKey"]),
                "phaseRoleIds": list(binding["phaseRoleIds"]),
                "sidePolicyKind": side["kind"],
                "roleResolverContractId": side["roleResolverContractId"],
                "symbolicSlots": list(side["symbolicSlots"]),
                "decisionEligibilityState": binding["decisionEligibility"]["state"],
            }
        )
    return {
        "exerciseId": exercise["exerciseId"],
        "planReference": dict(plan),
        "bindingCount": len(binding_contracts),
        "phaseRoleCounts": dict(sorted(phase_counts.items())),
        "sidePolicyCounts": dict(sorted(side_counts.items())),
        "roleResolverContractIds": sorted(resolvers),
        "bindingDecisionTuples": tuples,
        "determinateEligibleBindingCount": 0,
    }


def compile_contract(
    *,
    registry: Mapping[str, Any],
    matrix: Mapping[str, Any],
    compiler_implementation_sha256: str | None = None,
) -> dict[str, Any]:
    """Compile already validated M8 artifacts into the v2 decision contract."""

    try:
        planning._require_canonical_tree(registry, "planning registry")
        planning._require_canonical_tree(matrix, "planning matrix")
        planning._validate_fingerprinted_artifact(registry, "planning registry")
        planning._validate_fingerprinted_artifact(matrix, "planning matrix")
        planning._validate_zero_authority(matrix["authority"], "planning matrix authority")
    except (planning.PlanningMatrixError, KeyError) as error:
        raise DecisionContractError(f"invalid M8 input: {error}") from error
    if matrix.get("planningRegistryArtifactSha256") != registry.get("artifactSha256"):
        raise DecisionContractError("planning matrix registry pin mismatch")

    scope = matrix.get("catalogScope")
    expected_input_scope = {
        "exerciseCount": 41,
        "exactConditionCount": 97,
        "bindingCount": 167,
        "reviewedBindingCount": 148,
        "releaseEligibleBindingCount": 0,
    }
    if not isinstance(scope, dict) or any(
        scope.get(key) != value for key, value in expected_input_scope.items()
    ):
        raise DecisionContractError("planning matrix catalog scope drift")

    policy_provenance = matrix.get("policyProvenance")
    if not isinstance(policy_provenance, dict):
        raise DecisionContractError("planning matrix policy provenance is missing")
    policy_registry_sha256 = policy_provenance.get("policyRegistrySha256")
    if not isinstance(policy_registry_sha256, str):
        raise DecisionContractError("planning matrix policy registry pin is missing")

    exercises_raw = matrix.get("exercises")
    if not isinstance(exercises_raw, list):
        raise DecisionContractError("planning matrix exercises must be an array")
    output_exercises: list[dict[str, Any]] = []
    profiles: list[dict[str, Any]] = []
    seen_bindings: set[tuple[str, str]] = set()
    observed_phase_roles: set[str] = set()
    observed_side_kinds: set[str] = set()
    phase_role_counts: Counter[str] = Counter()
    side_policy_counts: Counter[str] = Counter()
    reviewed_count = 0
    unresolved_count = 0
    for raw_exercise in exercises_raw:
        if not isinstance(raw_exercise, dict):
            raise DecisionContractError("planning matrix exercise must be an object")
        exercise_id = raw_exercise.get("exerciseId")
        if not isinstance(exercise_id, str) or not exercise_id:
            raise DecisionContractError("planning matrix exerciseId is invalid")
        raw_bindings = raw_exercise.get("bindings")
        if not isinstance(raw_bindings, list):
            raise DecisionContractError("planning matrix bindings must be an array")
        binding_contracts: list[dict[str, Any]] = []
        for raw_binding in raw_bindings:
            if not isinstance(raw_binding, dict):
                raise DecisionContractError("planning matrix binding must be an object")
            key = (exercise_id, raw_binding.get("sourceConditionId"))
            if not isinstance(key[1], str) or key in seen_bindings:
                raise DecisionContractError("planning matrix binding exact tuple is invalid or duplicate")
            seen_bindings.add(key)
            contract = _binding_contract(
                exercise_id,
                raw_binding,
                policy_registry_sha256=policy_registry_sha256,
            )
            if contract["interpretationState"] == "SOURCE_INTERPRETATION_UNRESOLVED":
                unresolved_count += 1
            else:
                reviewed_count += 1
                observed_phase_roles.update(contract["phaseRoleIds"])
                observed_side_kinds.add(contract["sidePolicy"]["kind"])
                phase_role_counts.update(contract["phaseRoleIds"])
                side_policy_counts[contract["sidePolicy"]["kind"]] += 1
            binding_contracts.append(contract)
        binding_contracts.sort(
            key=lambda row: row["bindingKey"]["sourceConditionId"]
        )
        output_exercise = {
            "exerciseId": exercise_id,
            "bindingCount": len(binding_contracts),
            "registeredPlanState": (
                None
                if raw_exercise.get("registeredPlan") is None
                else raw_exercise["registeredPlan"]["planState"]
            ),
            "bindings": binding_contracts,
            "determinateEligibleBindingCount": 0,
        }
        output_exercises.append(output_exercise)
        if raw_exercise.get("registeredPlan") is not None:
            profiles.append(_registered_profile(raw_exercise, binding_contracts))

    output_exercises.sort(key=lambda row: row["exerciseId"])
    profiles.sort(key=lambda row: row["exerciseId"])
    if len(output_exercises) != 41 or len(seen_bindings) != 167:
        raise DecisionContractError("decision contract exercise or binding exact-set drift")
    if reviewed_count != 148 or unresolved_count != 19:
        raise DecisionContractError("decision contract review-state counts drift")
    if observed_phase_roles != set(PHASE_ROLE_IDS):
        raise DecisionContractError("policy phase-role exact-set differs from the v2 catalog")
    if observed_side_kinds != set(SIDE_POLICY_SYMBOLIC_SLOTS):
        raise DecisionContractError("policy side-kind exact-set differs from the v2 catalog")
    if sum(phase_role_counts.values()) != reviewed_count:
        raise DecisionContractError("phase-role binding counts do not partition reviewed bindings")
    if sum(side_policy_counts.values()) != reviewed_count:
        raise DecisionContractError("side-policy binding counts do not partition reviewed bindings")

    implementation_sha = (
        canonical_lf_text_sha256(Path(__file__).resolve())
        if compiler_implementation_sha256 is None
        else compiler_implementation_sha256
    )
    if (
        not isinstance(implementation_sha, str)
        or len(implementation_sha) != 64
        or implementation_sha.lower() != implementation_sha
    ):
        raise DecisionContractError("compiler implementation SHA-256 is invalid")
    try:
        int(implementation_sha, 16)
    except ValueError as error:
        raise DecisionContractError("compiler implementation SHA-256 is invalid") from error

    contract = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": "TREX_POSE_GOLD_DECISION_CONTRACT",
        "authority": dict(planning.AUTHORITY_ZERO),
        "decisionUse": (
            "SCHEMA_AND_PLAN_DECISION_ELIGIBILITY_ONLY_NOT_EVIDENCE_GOLD_CALIBRATION_RUNTIME_OR_RELEASE_AUTHORITY"
        ),
        "contractBoundary": {
            "restrictedBundleParserIncluded": False,
            "syntheticFixtureParserIncluded": False,
            "realEvidenceIntakeIncluded": False,
            "reviewOrAdjudicationRecordModelIncluded": False,
            "timestampOrIntervalModelIncluded": False,
            "evaluatorIncluded": False,
            "scoreInterfaceIncluded": False,
            "cueInterfaceIncluded": False,
            "sessionInterfaceIncluded": False,
            "runtimeProviderIncluded": False,
            "releaseTransitionIncluded": False,
        },
        "compilerImplementation": {
            "relativePath": "tools/compile_pose_gold_decision_contract.py",
            "canonicalTextSha256": implementation_sha,
            "normalization": "UTF8_NFC_LF",
        },
        "compilerImplementationUse": (
            "IMPLEMENTATION_DRIFT_DETECTION_ONLY_NOT_APPROVAL_OR_AUTHORITY"
        ),
        "inputProvenance": {
            "planningRegistryArtifactSha256": registry["artifactSha256"],
            "planningMatrixArtifactSha256": matrix["artifactSha256"],
            **dict(policy_provenance),
        },
        "catalogScope": {
            **EXPECTED_SCOPE,
            "phaseRoleCount": len(PHASE_ROLE_IDS),
            "sidePolicyKindCount": len(SIDE_POLICY_SYMBOLIC_SLOTS),
            "registeredExerciseProfileCount": len(profiles),
        },
        "decisionStateContract": {
            "reviewedBindingState": "UNKNOWN_GOLD_ONLY",
            "unreviewedBindingState": "SOURCE_INTERPRETATION_UNRESOLVED",
            "permittedReviewedGoldStates": ["UNKNOWN_GOLD"],
            "determinateGoldStates": [],
            "positiveOrNegativeDecisionAllowed": False,
            "separateEvidenceAndApprovalContractsRequired": [
                "CALIBRATION",
                "CAPABILITY_ATTESTATION",
                "PHASE_SCOPE",
                "REFERENCE_EVIDENCE",
                "ROLE_RESOLVER_WHERE_REQUIRED",
                "TRUSTED_REAL_INTAKE",
                "VIEW_APPLICABILITY",
            ],
        },
        "phaseRoleCatalog": _phase_role_catalog(phase_role_counts),
        "sidePolicyCatalog": _side_policy_catalog(side_policy_counts),
        "registeredExerciseProfiles": profiles,
        "exercises": output_exercises,
    }
    return with_artifact_sha256(contract)


def validate_contract(
    value: Mapping[str, Any],
    *,
    expected: Mapping[str, Any],
) -> None:
    """Validate a generated artifact against the independently rebuilt contract."""

    try:
        planning._require_canonical_tree(value, "decision contract")
        planning._strict_keys(value, TOP_LEVEL_KEYS, "decision contract")
        planning._validate_fingerprinted_artifact(value, "decision contract")
        planning._validate_zero_authority(value["authority"], "decision contract authority")
    except (planning.PlanningMatrixError, KeyError) as error:
        raise DecisionContractError(f"invalid decision contract: {error}") from error
    if value != expected:
        raise DecisionContractError("decision contract differs from independently rebuilt contract")


def compile_from_paths(
    *,
    source_path: Path = DEFAULT_SOURCE,
    policy_path: Path = DEFAULT_POLICY,
    approval_path: Path = DEFAULT_APPROVAL,
    registry_path: Path = DEFAULT_REGISTRY,
    matrix_path: Path = DEFAULT_MATRIX,
    project_root: Path = PROJECT_ROOT,
) -> dict[str, Any]:
    try:
        registry = planning.load_json(
            registry_path,
            "planning registry",
            project_root=project_root,
            require_pretty_lf=True,
        )
        matrix = planning.load_json(
            matrix_path,
            "planning matrix",
            project_root=project_root,
            require_pretty_lf=True,
        )
        expected_matrix = planning.compile_from_paths(
            source_path=source_path,
            policy_path=policy_path,
            approval_path=approval_path,
            registry_path=registry_path,
            project_root=project_root,
        )
    except planning.PlanningMatrixError as error:
        raise DecisionContractError(f"M8 input validation failed: {error}") from error
    if matrix != expected_matrix:
        raise DecisionContractError(
            "planning matrix differs from the independently rebuilt M8 artifact"
        )
    contract = compile_contract(registry=registry, matrix=matrix)
    validate_contract(contract, expected=contract)
    return contract


def _safe_output_path(
    path: Path,
    *,
    project_root: Path,
    input_paths: Sequence[Path],
) -> Path:
    try:
        absolute, relative = planning._absolute_confined(
            path, project_root, "decision contract output path"
        )
    except planning.PlanningMatrixError as error:
        raise DecisionContractError(str(error)) from error
    expected_relative = Path("docs") / "pose-gold-decision-contract.v2.json"
    if relative != expected_relative:
        raise DecisionContractError(
            f"output path must be {expected_relative.as_posix()}"
        )
    for input_path in input_paths:
        try:
            input_absolute, _ = planning._absolute_confined(
                input_path, project_root, "decision contract input path"
            )
        except planning.PlanningMatrixError as error:
            raise DecisionContractError(str(error)) from error
        if absolute == input_absolute:
            raise DecisionContractError("output path must differ from every input path")
    if not absolute.parent.exists():
        raise DecisionContractError("output parent must already exist")
    try:
        planning._assert_no_reparse_chain(
            absolute.parent,
            project_root,
            "decision contract output parent",
            require_regular=False,
        )
        if absolute.exists():
            planning._assert_no_reparse_chain(
                absolute,
                project_root,
                "existing decision contract output",
                require_regular=True,
            )
    except planning.PlanningMatrixError as error:
        raise DecisionContractError(str(error)) from error
    return absolute


def write_or_check(path: Path, value: Mapping[str, Any], *, check: bool) -> None:
    rendered = render_json(value).encode("utf-8")
    if check:
        try:
            current = path.read_bytes()
        except OSError as error:
            raise DecisionContractError(f"cannot read existing decision contract: {error}") from error
        if current != rendered:
            raise DecisionContractError(f"decision contract is stale: {path}")
        return
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as output:
            temporary = Path(output.name)
            output.write(rendered)
            output.flush()
            os.fsync(output.fileno())
        try:
            os.replace(temporary, path)
        except OSError as error:
            raise DecisionContractError(f"atomic decision contract publish failed: {error}") from error
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-coverage", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--policy-approval", type=Path, default=DEFAULT_APPROVAL)
    parser.add_argument("--planning-registry", type=Path, default=DEFAULT_REGISTRY)
    parser.add_argument("--planning-matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    inputs = [
        args.source_coverage,
        args.policy,
        args.policy_approval,
        args.planning_registry,
        args.planning_matrix,
    ]
    try:
        contract = compile_from_paths(
            source_path=args.source_coverage,
            policy_path=args.policy,
            approval_path=args.policy_approval,
            registry_path=args.planning_registry,
            matrix_path=args.planning_matrix,
            project_root=PROJECT_ROOT,
        )
        output = _safe_output_path(
            args.output,
            project_root=PROJECT_ROOT,
            input_paths=inputs,
        )
        write_or_check(output, contract, check=args.check)
    except (DecisionContractError, OSError) as error:
        print(f"pose Gold decision contract failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
