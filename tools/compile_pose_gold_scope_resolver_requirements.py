#!/usr/bin/env python3
"""Compile zero-authority phase-scope and side-resolver requirements from M10.

The artifact produced here is a catalog-scale inventory of missing trusted artifacts. It cannot
approve a phase scope or side resolver, ingest evidence, assign anatomical sides, attach
timestamps, evaluate posture, or authorize runtime/cue/release behavior.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import tempfile
from collections import defaultdict
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    from . import compile_pose_exercise_planning_matrix as planning
    from . import compile_pose_gold_annotation_contract as annotation
    from . import compile_pose_gold_decision_contract as decision
except ImportError:  # Direct ``python tools/...py`` execution.
    import compile_pose_exercise_planning_matrix as planning
    import compile_pose_gold_annotation_contract as annotation
    import compile_pose_gold_decision_contract as decision


SCHEMA_VERSION = 1
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = annotation.DEFAULT_SOURCE
DEFAULT_POLICY = annotation.DEFAULT_POLICY
DEFAULT_APPROVAL = annotation.DEFAULT_APPROVAL
DEFAULT_REGISTRY = annotation.DEFAULT_REGISTRY
DEFAULT_MATRIX = annotation.DEFAULT_MATRIX
DEFAULT_DECISION_CONTRACT = annotation.DEFAULT_DECISION_CONTRACT
DEFAULT_ANNOTATION_CONTRACT = annotation.DEFAULT_OUTPUT
DEFAULT_OUTPUT = PROJECT_ROOT / "docs" / "pose-gold-scope-resolver-requirements.v1.json"

EXPECTED_SCOPE = {
    "exerciseCount": 41,
    "exactConditionCount": 97,
    "bindingCount": 167,
    "reviewedBindingCount": 148,
    "unresolvedBindingCount": 19,
    "annotationDecisionTemplateCount": 203,
    "roleRelativeAnnotationDecisionTemplateCount": 18,
    "phaseScopeRequirementCount": 78,
    "sideResolverRequirementCount": 13,
    "approvedPhaseScopeRequirementCount": 0,
    "approvedSideResolverRequirementCount": 0,
    "releaseEligibleBindingCount": 0,
    "determinateEligibleTemplateCount": 0,
}

EXPECTED_COVERAGE = {
    "annotationDecisionTemplateCount": 203,
    "phaseCoveredTemplateCount": 203,
    "phaseUncoveredTemplateCount": 0,
    "roleRelativeAnnotationDecisionTemplateCount": 18,
    "resolverCoveredTemplateCount": 18,
    "resolverUncoveredTemplateCount": 0,
    "unresolvedBindingCount": 19,
    "unresolvedBindingGeneratedRequirementCount": 0,
    "orphanRequirementCount": 0,
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
    "requirementStateContract",
    "requirementCoverage",
    "phaseScopeRequirements",
    "sideResolverRequirements",
}

CONTRACT_BOUNDARY = {
    "positiveApprovalTransitionIncluded": False,
    "phaseTopologyIncluded": False,
    "timestampValueModelIncluded": False,
    "anatomicalAssignmentIncluded": False,
    "candidateArtifactParsingIncluded": False,
    "detachedSignatureVerificationIncluded": False,
    "trustRegistryIncluded": False,
    "approverIdentityModelIncluded": False,
    "syntheticFixtureParserIncluded": False,
    "realEvidenceIntakeIncluded": False,
    "reviewOrAdjudicationRecordModelIncluded": False,
    "evaluatorIncluded": False,
    "runtimeProviderIncluded": False,
    "cueInterfaceIncluded": False,
    "releaseTransitionIncluded": False,
}

COMMON_NULL_CANDIDATE_FIELDS = {
    "candidateArtifactSha256": None,
    "detachedSignatureEnvelope": None,
    "trustRegistryArtifactSha256": None,
    "approverId": None,
}

RESOLVER_ONLY_NULL_CANDIDATE_FIELDS = {
    "anatomicalAssignment": None,
}

REQUIREMENT_STATE_CONTRACT = {
    "currentPermittedRequirementStates": ["PENDING_TRUSTED_ARTIFACT"],
    "positiveApprovalStates": [],
    "phaseCandidateFieldsRequiredNull": sorted(COMMON_NULL_CANDIDATE_FIELDS),
    "resolverCandidateFieldsRequiredNull": sorted(
        {**COMMON_NULL_CANDIDATE_FIELDS, **RESOLVER_ONLY_NULL_CANDIDATE_FIELDS}
    ),
    "selfIssuedApprovalAllowed": False,
    "repositoryHashIsApproval": False,
}

PHASE_REQUIREMENT_KEYS = {
    "exerciseId",
    "phaseRoleId",
    "coveredTemplateIds",
    "coveredTemplateCount",
    "requirementState",
    *COMMON_NULL_CANDIDATE_FIELDS,
}

RESOLVER_REQUIREMENT_KEYS = {
    "exerciseId",
    "sidePolicyKind",
    "roleResolverContractId",
    "symbolicSlots",
    "coveredTemplateIds",
    "coveredTemplateCount",
    "requirementState",
    *COMMON_NULL_CANDIDATE_FIELDS,
    *RESOLVER_ONLY_NULL_CANDIDATE_FIELDS,
}

INPUT_PROVENANCE_KEYS = {
    "annotationContractArtifactSha256",
    "annotationContractCompilerCanonicalTextSha256",
    "annotationContractInputProvenance",
}

COMPILER_IMPLEMENTATION_KEYS = {
    "relativePath",
    "canonicalTextSha256",
    "normalization",
}

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
TEMPLATE_ID_RE = re.compile(r"^trex\.annotation-template-sha256-[0-9a-f]{64}$")


class ScopeResolverRequirementsError(RuntimeError):
    """Raised when an input drifts or a requirement could imply unsupported authority."""


def canonical_json(value: Any) -> str:
    try:
        return annotation.canonical_json(value)
    except (annotation.AnnotationContractError, ValueError) as error:
        raise ScopeResolverRequirementsError(
            f"cannot canonicalize scope/resolver requirement data: {error}"
        ) from error


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
        return annotation.canonical_lf_text_sha256(path)
    except annotation.AnnotationContractError as error:
        raise ScopeResolverRequirementsError(str(error)) from error


def _deep_copy(value: Any) -> Any:
    return copy.deepcopy(value)


def _strict_keys(value: Any, keys: set[str], label: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise ScopeResolverRequirementsError(f"{label} must be an object")
    try:
        planning._require_canonical_tree(value, label)
        planning._strict_keys(value, keys, label)
    except planning.PlanningMatrixError as error:
        raise ScopeResolverRequirementsError(str(error)) from error
    return value


def _non_empty_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ScopeResolverRequirementsError(f"{label} must be a non-empty string")
    return value


def _strict_positive_count(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ScopeResolverRequirementsError(f"{label} must be a positive integer")
    return value


def _template_ids(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise ScopeResolverRequirementsError(f"{label} must be a non-empty array")
    if any(not isinstance(item, str) or not TEMPLATE_ID_RE.fullmatch(item) for item in value):
        raise ScopeResolverRequirementsError(f"{label} contains an invalid templateId")
    if value != sorted(set(value)):
        raise ScopeResolverRequirementsError(f"{label} must be sorted and unique")
    return value


def _validate_pending_fields(
    value: Mapping[str, Any],
    label: str,
    *,
    null_fields: Mapping[str, None],
) -> None:
    if value.get("requirementState") != "PENDING_TRUSTED_ARTIFACT":
        raise ScopeResolverRequirementsError(
            f"{label} must remain PENDING_TRUSTED_ARTIFACT"
        )
    for field, required in null_fields.items():
        if value.get(field) is not required:
            raise ScopeResolverRequirementsError(
                f"{label}.{field} must remain null until a separate trusted verifier exists"
            )


def _validate_phase_scope_requirement_row(candidate: Mapping[str, Any]) -> None:
    row = _strict_keys(candidate, PHASE_REQUIREMENT_KEYS, "phase-scope candidate")
    _non_empty_text(row["exerciseId"], "phase-scope candidate.exerciseId")
    phase_role_id = _non_empty_text(
        row["phaseRoleId"], "phase-scope candidate.phaseRoleId"
    )
    if phase_role_id not in decision.PHASE_ROLE_IDS:
        raise ScopeResolverRequirementsError(
            "phase-scope candidate carries an unknown policy phase role"
        )
    template_ids = _template_ids(
        row["coveredTemplateIds"], "phase-scope candidate.coveredTemplateIds"
    )
    count = _strict_positive_count(
        row["coveredTemplateCount"], "phase-scope candidate.coveredTemplateCount"
    )
    if count != len(template_ids):
        raise ScopeResolverRequirementsError(
            "phase-scope candidate coveredTemplateCount differs from its exact set"
        )
    _validate_pending_fields(
        row,
        "phase-scope candidate",
        null_fields=COMMON_NULL_CANDIDATE_FIELDS,
    )


def _validate_side_resolver_requirement_row(candidate: Mapping[str, Any]) -> None:
    row = _strict_keys(candidate, RESOLVER_REQUIREMENT_KEYS, "side-resolver candidate")
    _non_empty_text(row["exerciseId"], "side-resolver candidate.exerciseId")
    side_kind = _non_empty_text(
        row["sidePolicyKind"], "side-resolver candidate.sidePolicyKind"
    )
    if side_kind not in decision.ROLE_RELATIVE_SIDE_POLICIES:
        raise ScopeResolverRequirementsError(
            "side-resolver candidate must use a role-relative side policy"
        )
    _non_empty_text(
        row["roleResolverContractId"],
        "side-resolver candidate.roleResolverContractId",
    )
    symbolic_slots = row["symbolicSlots"]
    if symbolic_slots != decision.SIDE_POLICY_SYMBOLIC_SLOTS[side_kind]:
        raise ScopeResolverRequirementsError(
            "side-resolver candidate symbolic slots differ from the exact policy"
        )
    template_ids = _template_ids(
        row["coveredTemplateIds"], "side-resolver candidate.coveredTemplateIds"
    )
    count = _strict_positive_count(
        row["coveredTemplateCount"], "side-resolver candidate.coveredTemplateCount"
    )
    if count != len(template_ids):
        raise ScopeResolverRequirementsError(
            "side-resolver candidate coveredTemplateCount differs from its exact set"
        )
    _validate_pending_fields(
        row,
        "side-resolver candidate",
        null_fields={
            **COMMON_NULL_CANDIDATE_FIELDS,
            **RESOLVER_ONLY_NULL_CANDIDATE_FIELDS,
        },
    )


def validate_phase_scope_candidate(candidate: Mapping[str, Any]) -> None:
    """Validate a pending candidate against the repository-canonical A0 requirement."""

    _validate_phase_scope_requirement_row(candidate)
    contract = compile_from_paths()
    matches = [
        row
        for row in contract["phaseScopeRequirements"]
        if row["exerciseId"] == candidate["exerciseId"]
        and row["phaseRoleId"] == candidate["phaseRoleId"]
    ]
    if len(matches) != 1 or candidate != matches[0]:
        raise ScopeResolverRequirementsError(
            "phase-scope candidate differs from its repository-canonical pending requirement"
        )


def validate_side_resolver_candidate(candidate: Mapping[str, Any]) -> None:
    """Validate a pending candidate against the repository-canonical A0 requirement."""

    _validate_side_resolver_requirement_row(candidate)
    contract = compile_from_paths()
    matches = [
        row
        for row in contract["sideResolverRequirements"]
        if row["exerciseId"] == candidate["exerciseId"]
        and row["sidePolicyKind"] == candidate["sidePolicyKind"]
        and row["roleResolverContractId"] == candidate["roleResolverContractId"]
    ]
    if len(matches) != 1 or candidate != matches[0]:
        raise ScopeResolverRequirementsError(
            "side-resolver candidate differs from its repository-canonical pending requirement"
        )


def _pending_fields(*, include_anatomical_assignment: bool) -> dict[str, Any]:
    null_fields = dict(COMMON_NULL_CANDIDATE_FIELDS)
    if include_anatomical_assignment:
        null_fields.update(RESOLVER_ONLY_NULL_CANDIDATE_FIELDS)
    return {
        "requirementState": "PENDING_TRUSTED_ARTIFACT",
        **null_fields,
    }


def _compiler_sha(value: str | None) -> str:
    result = (
        canonical_lf_text_sha256(Path(__file__).resolve())
        if value is None
        else value
    )
    if not isinstance(result, str) or not SHA256_RE.fullmatch(result):
        raise ScopeResolverRequirementsError("compiler implementation SHA-256 is invalid")
    return result


def _compile_validated_requirements(
    annotation_contract: Mapping[str, Any],
    *,
    compiler_implementation_sha256: str | None = None,
) -> dict[str, Any]:
    """Compile after the caller has exact-compared M10 with an independent rebuild."""

    if annotation_contract.get("catalogScope") != annotation.EXPECTED_SCOPE:
        raise ScopeResolverRequirementsError("M10 catalog scope drift")

    exercises = annotation_contract.get("exercises")
    if not isinstance(exercises, list) or len(exercises) != EXPECTED_SCOPE["exerciseCount"]:
        raise ScopeResolverRequirementsError("M10 exercise exact-set/count drift")

    templates: list[Mapping[str, Any]] = []
    unresolved_count = 0
    exercise_ids: list[str] = []
    seen_template_ids: set[str] = set()
    for exercise in exercises:
        if not isinstance(exercise, dict):
            raise ScopeResolverRequirementsError("M10 exercise row must be an object")
        exercise_id = _non_empty_text(exercise.get("exerciseId"), "M10 exerciseId")
        exercise_ids.append(exercise_id)
        decision_templates = exercise.get("decisionTemplates")
        unresolved = exercise.get("unresolvedBindings")
        if not isinstance(decision_templates, list) or not isinstance(unresolved, list):
            raise ScopeResolverRequirementsError(
                "M10 exercise must contain template and unresolved arrays"
            )
        unresolved_count += len(unresolved)
        for template in decision_templates:
            if not isinstance(template, dict):
                raise ScopeResolverRequirementsError("M10 template must be an object")
            template_id = template.get("templateId")
            if not isinstance(template_id, str) or not TEMPLATE_ID_RE.fullmatch(template_id):
                raise ScopeResolverRequirementsError("M10 templateId is invalid")
            if template_id in seen_template_ids:
                raise ScopeResolverRequirementsError("duplicate M10 templateId")
            seen_template_ids.add(template_id)
            binding_key = template.get("bindingKey")
            if not isinstance(binding_key, dict) or binding_key.get("exerciseId") != exercise_id:
                raise ScopeResolverRequirementsError("M10 template exercise join drift")
            templates.append(template)

    if exercise_ids != sorted(set(exercise_ids)):
        raise ScopeResolverRequirementsError("M10 exercises must be sorted and unique")
    if len(templates) != EXPECTED_SCOPE["annotationDecisionTemplateCount"]:
        raise ScopeResolverRequirementsError("M10 template count drift")
    if unresolved_count != EXPECTED_SCOPE["unresolvedBindingCount"]:
        raise ScopeResolverRequirementsError("M10 unresolved binding count drift")

    phase_groups: dict[tuple[str, str], set[str]] = defaultdict(set)
    resolver_groups: dict[tuple[str, str, str], set[str]] = defaultdict(set)
    resolver_slots: dict[tuple[str, str, str], set[str]] = defaultdict(set)
    role_relative_template_ids: set[str] = set()

    for template in templates:
        template_id = template["templateId"]
        exercise_id = template["bindingKey"]["exerciseId"]
        phase_role_id = template.get("phaseRoleId")
        side_kind = template.get("sidePolicyKind")
        resolver_id = template.get("roleResolverContractId")
        symbolic_slot = template.get("symbolicSlot")
        if phase_role_id not in decision.PHASE_ROLE_IDS:
            raise ScopeResolverRequirementsError("M10 template phase role drift")
        if side_kind not in decision.SIDE_POLICY_SYMBOLIC_SLOTS:
            raise ScopeResolverRequirementsError("M10 template side policy drift")
        if symbolic_slot not in decision.SIDE_POLICY_SYMBOLIC_SLOTS[side_kind]:
            raise ScopeResolverRequirementsError("M10 template symbolic slot drift")
        phase_groups[(exercise_id, phase_role_id)].add(template_id)

        if side_kind in decision.ROLE_RELATIVE_SIDE_POLICIES:
            if not isinstance(resolver_id, str) or not resolver_id:
                raise ScopeResolverRequirementsError(
                    "role-relative M10 template lacks its exact resolver"
                )
            key = (exercise_id, side_kind, resolver_id)
            resolver_groups[key].add(template_id)
            resolver_slots[key].add(symbolic_slot)
            role_relative_template_ids.add(template_id)
        elif resolver_id is not None:
            raise ScopeResolverRequirementsError(
                "fixed-side M10 template unexpectedly carries a resolver"
            )

    phase_requirements = []
    for (exercise_id, phase_role_id), template_ids in sorted(phase_groups.items()):
        row = {
            "exerciseId": exercise_id,
            "phaseRoleId": phase_role_id,
            "coveredTemplateIds": sorted(template_ids),
            "coveredTemplateCount": len(template_ids),
            **_pending_fields(include_anatomical_assignment=False),
        }
        _validate_phase_scope_requirement_row(row)
        phase_requirements.append(row)

    resolver_requirements = []
    for (exercise_id, side_kind, resolver_id), template_ids in sorted(
        resolver_groups.items()
    ):
        slots = sorted(resolver_slots[(exercise_id, side_kind, resolver_id)])
        if slots != decision.SIDE_POLICY_SYMBOLIC_SLOTS[side_kind]:
            raise ScopeResolverRequirementsError(
                "resolver requirement does not preserve the exact symbolic slot set"
            )
        row = {
            "exerciseId": exercise_id,
            "sidePolicyKind": side_kind,
            "roleResolverContractId": resolver_id,
            "symbolicSlots": slots,
            "coveredTemplateIds": sorted(template_ids),
            "coveredTemplateCount": len(template_ids),
            **_pending_fields(include_anatomical_assignment=True),
        }
        _validate_side_resolver_requirement_row(row)
        resolver_requirements.append(row)

    phase_coverage = [
        template_id
        for row in phase_requirements
        for template_id in row["coveredTemplateIds"]
    ]
    resolver_coverage = [
        template_id
        for row in resolver_requirements
        for template_id in row["coveredTemplateIds"]
    ]
    if (
        len(phase_requirements) != EXPECTED_SCOPE["phaseScopeRequirementCount"]
        or len(resolver_requirements) != EXPECTED_SCOPE["sideResolverRequirementCount"]
        or len(role_relative_template_ids)
        != EXPECTED_SCOPE["roleRelativeAnnotationDecisionTemplateCount"]
        or len(phase_coverage) != len(set(phase_coverage))
        or set(phase_coverage) != seen_template_ids
        or len(resolver_coverage) != len(set(resolver_coverage))
        or set(resolver_coverage) != role_relative_template_ids
    ):
        raise ScopeResolverRequirementsError("requirement exact-set coverage drift")

    contract = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": "TREX_POSE_GOLD_SCOPE_RESOLVER_REQUIREMENTS",
        "authority": dict(planning.AUTHORITY_ZERO),
        "decisionUse": (
            "PENDING_TRUSTED_ARTIFACT_REQUIREMENTS_ONLY_NOT_APPROVAL_TOPOLOGY_EVIDENCE_RUNTIME_CUE_OR_RELEASE_AUTHORITY"
        ),
        "contractBoundary": dict(CONTRACT_BOUNDARY),
        "compilerImplementation": {
            "relativePath": "tools/compile_pose_gold_scope_resolver_requirements.py",
            "canonicalTextSha256": _compiler_sha(compiler_implementation_sha256),
            "normalization": "UTF8_NFC_LF",
        },
        "compilerImplementationUse": (
            "IMPLEMENTATION_DRIFT_DETECTION_ONLY_NOT_APPROVAL_SIGNATURE_OR_AUTHORITY"
        ),
        "inputProvenance": {
            "annotationContractArtifactSha256": annotation_contract["artifactSha256"],
            "annotationContractCompilerCanonicalTextSha256": annotation_contract[
                "compilerImplementation"
            ]["canonicalTextSha256"],
            "annotationContractInputProvenance": _deep_copy(
                annotation_contract["inputProvenance"]
            ),
        },
        "catalogScope": dict(EXPECTED_SCOPE),
        "requirementStateContract": _deep_copy(REQUIREMENT_STATE_CONTRACT),
        "requirementCoverage": dict(EXPECTED_COVERAGE),
        "phaseScopeRequirements": phase_requirements,
        "sideResolverRequirements": resolver_requirements,
    }
    return with_artifact_sha256(contract)


def _validate_contract(
    value: Mapping[str, Any],
    *,
    expected: Mapping[str, Any],
) -> None:
    try:
        planning._require_canonical_tree(value, "scope/resolver requirements")
        planning._strict_keys(value, TOP_LEVEL_KEYS, "scope/resolver requirements")
        planning._validate_fingerprinted_artifact(value, "scope/resolver requirements")
        planning._validate_zero_authority(
            value["authority"], "scope/resolver requirements authority"
        )
    except (planning.PlanningMatrixError, KeyError) as error:
        raise ScopeResolverRequirementsError(
            f"invalid scope/resolver requirements: {error}"
        ) from error

    if value.get("schemaVersion") != SCHEMA_VERSION:
        raise ScopeResolverRequirementsError("scope/resolver schema version drift")
    if value.get("artifactKind") != "TREX_POSE_GOLD_SCOPE_RESOLVER_REQUIREMENTS":
        raise ScopeResolverRequirementsError("scope/resolver artifact kind drift")
    if value.get("catalogScope") != EXPECTED_SCOPE:
        raise ScopeResolverRequirementsError("scope/resolver catalog scope drift")
    if value.get("requirementCoverage") != EXPECTED_COVERAGE:
        raise ScopeResolverRequirementsError("scope/resolver coverage summary drift")
    if value.get("contractBoundary") != CONTRACT_BOUNDARY:
        raise ScopeResolverRequirementsError("scope/resolver contract boundary drift")
    if value.get("requirementStateContract") != REQUIREMENT_STATE_CONTRACT:
        raise ScopeResolverRequirementsError("scope/resolver state contract drift")

    provenance = _strict_keys(
        value.get("inputProvenance"), INPUT_PROVENANCE_KEYS, "input provenance"
    )
    for key in (
        "annotationContractArtifactSha256",
        "annotationContractCompilerCanonicalTextSha256",
    ):
        if not isinstance(provenance[key], str) or not SHA256_RE.fullmatch(provenance[key]):
            raise ScopeResolverRequirementsError(f"input provenance {key} is invalid")
    implementation = _strict_keys(
        value.get("compilerImplementation"),
        COMPILER_IMPLEMENTATION_KEYS,
        "compiler implementation",
    )
    if implementation.get("relativePath") != (
        "tools/compile_pose_gold_scope_resolver_requirements.py"
    ):
        raise ScopeResolverRequirementsError("compiler implementation path drift")
    if implementation.get("normalization") != "UTF8_NFC_LF":
        raise ScopeResolverRequirementsError("compiler normalization drift")
    if not isinstance(implementation.get("canonicalTextSha256"), str) or not SHA256_RE.fullmatch(
        implementation["canonicalTextSha256"]
    ):
        raise ScopeResolverRequirementsError("compiler implementation SHA-256 is invalid")

    phase_rows = value.get("phaseScopeRequirements")
    resolver_rows = value.get("sideResolverRequirements")
    if not isinstance(phase_rows, list) or not isinstance(resolver_rows, list):
        raise ScopeResolverRequirementsError("requirement rows must be arrays")
    if len(phase_rows) != EXPECTED_SCOPE["phaseScopeRequirementCount"]:
        raise ScopeResolverRequirementsError("phase-scope requirement count drift")
    if len(resolver_rows) != EXPECTED_SCOPE["sideResolverRequirementCount"]:
        raise ScopeResolverRequirementsError("side-resolver requirement count drift")

    phase_keys = []
    phase_template_ids = []
    for row in phase_rows:
        _validate_phase_scope_requirement_row(row)
        phase_keys.append((row["exerciseId"], row["phaseRoleId"]))
        phase_template_ids.extend(row["coveredTemplateIds"])
    if phase_keys != sorted(set(phase_keys)):
        raise ScopeResolverRequirementsError(
            "phase-scope requirements must be sorted and unique"
        )
    if len(phase_template_ids) != len(set(phase_template_ids)) or len(
        phase_template_ids
    ) != EXPECTED_COVERAGE["phaseCoveredTemplateCount"]:
        raise ScopeResolverRequirementsError("phase-scope template coverage is not an exact set")

    resolver_keys = []
    resolver_template_ids = []
    for row in resolver_rows:
        _validate_side_resolver_requirement_row(row)
        resolver_keys.append(
            (
                row["exerciseId"],
                row["sidePolicyKind"],
                row["roleResolverContractId"],
            )
        )
        resolver_template_ids.extend(row["coveredTemplateIds"])
    if resolver_keys != sorted(set(resolver_keys)):
        raise ScopeResolverRequirementsError(
            "side-resolver requirements must be sorted and unique"
        )
    if len(resolver_template_ids) != len(set(resolver_template_ids)) or len(
        resolver_template_ids
    ) != EXPECTED_COVERAGE["resolverCoveredTemplateCount"]:
        raise ScopeResolverRequirementsError("resolver template coverage is not an exact set")

    if value != expected:
        raise ScopeResolverRequirementsError(
            "scope/resolver requirements differ from independently rebuilt requirements"
        )


def validate_contract(value: Mapping[str, Any]) -> None:
    """Validate against requirements rebuilt from repository-canonical M10 inputs."""

    # Reject unsafe shapes before doing the more expensive upstream rebuild. The first pass does
    # not establish authenticity; only the second exact comparison does that.
    _validate_contract(value, expected=value)
    expected = compile_from_paths()
    _validate_contract(value, expected=expected)


def compile_from_paths(
    *,
    source_path: Path = DEFAULT_SOURCE,
    policy_path: Path = DEFAULT_POLICY,
    approval_path: Path = DEFAULT_APPROVAL,
    registry_path: Path = DEFAULT_REGISTRY,
    matrix_path: Path = DEFAULT_MATRIX,
    decision_contract_path: Path = DEFAULT_DECISION_CONTRACT,
    annotation_contract_path: Path = DEFAULT_ANNOTATION_CONTRACT,
    project_root: Path = PROJECT_ROOT,
) -> dict[str, Any]:
    try:
        source_contract = planning.load_json(
            annotation_contract_path,
            "M10 annotation contract",
            project_root=project_root,
            require_pretty_lf=True,
        )
        expected_contract = annotation.compile_from_paths(
            source_path=source_path,
            policy_path=policy_path,
            approval_path=approval_path,
            registry_path=registry_path,
            matrix_path=matrix_path,
            decision_contract_path=decision_contract_path,
            project_root=project_root,
        )
        annotation.validate_contract(source_contract, expected=expected_contract)
    except (
        planning.PlanningMatrixError,
        annotation.AnnotationContractError,
    ) as error:
        raise ScopeResolverRequirementsError(
            f"M10 rebuild/input validation failed: {error}"
        ) from error
    result = _compile_validated_requirements(source_contract)
    _validate_contract(result, expected=result)
    return result


def _safe_output_path(
    path: Path,
    *,
    project_root: Path,
    input_paths: Sequence[Path],
) -> Path:
    try:
        absolute, relative = planning._absolute_confined(
            path,
            project_root,
            "scope/resolver requirements output path",
        )
    except planning.PlanningMatrixError as error:
        raise ScopeResolverRequirementsError(str(error)) from error
    expected_relative = Path("docs") / "pose-gold-scope-resolver-requirements.v1.json"
    if relative != expected_relative:
        raise ScopeResolverRequirementsError(
            f"output path must be {expected_relative.as_posix()}"
        )
    for input_path in input_paths:
        try:
            input_absolute, _ = planning._absolute_confined(
                input_path,
                project_root,
                "scope/resolver requirements input path",
            )
        except planning.PlanningMatrixError as error:
            raise ScopeResolverRequirementsError(str(error)) from error
        if absolute == input_absolute:
            raise ScopeResolverRequirementsError(
                "output path must differ from every input path"
            )
    if not absolute.parent.exists():
        raise ScopeResolverRequirementsError("output parent must already exist")
    try:
        planning._assert_no_reparse_chain(
            absolute.parent,
            project_root,
            "scope/resolver requirements output parent",
            require_regular=False,
        )
        if absolute.exists():
            planning._assert_no_reparse_chain(
                absolute,
                project_root,
                "existing scope/resolver requirements output",
                require_regular=True,
            )
    except planning.PlanningMatrixError as error:
        raise ScopeResolverRequirementsError(str(error)) from error
    return absolute


def write_or_check(path: Path, value: Mapping[str, Any], *, check: bool) -> None:
    rendered = render_json(value).encode("utf-8")
    if check:
        try:
            current = path.read_bytes()
        except OSError as error:
            raise ScopeResolverRequirementsError(
                f"cannot read existing scope/resolver requirements: {error}"
            ) from error
        if current != rendered:
            raise ScopeResolverRequirementsError(
                f"scope/resolver requirements are stale: {path}"
            )
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
            raise ScopeResolverRequirementsError(
                f"atomic scope/resolver requirements publish failed: {error}"
            ) from error
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
    parser.add_argument("--decision-contract", type=Path, default=DEFAULT_DECISION_CONTRACT)
    parser.add_argument(
        "--annotation-contract", type=Path, default=DEFAULT_ANNOTATION_CONTRACT
    )
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
        args.decision_contract,
        args.annotation_contract,
    ]
    try:
        contract = compile_from_paths(
            source_path=args.source_coverage,
            policy_path=args.policy,
            approval_path=args.policy_approval,
            registry_path=args.planning_registry,
            matrix_path=args.planning_matrix,
            decision_contract_path=args.decision_contract,
            annotation_contract_path=args.annotation_contract,
            project_root=PROJECT_ROOT,
        )
        output = _safe_output_path(
            args.output,
            project_root=PROJECT_ROOT,
            input_paths=inputs,
        )
        write_or_check(output, contract, check=args.check)
    except (ScopeResolverRequirementsError, OSError) as error:
        print(f"pose Gold scope/resolver requirements failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
