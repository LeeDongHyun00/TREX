#!/usr/bin/env python3
"""Compile catalog-wide UNKNOWN-only pose Gold annotation reference templates.

This compiler expands the validated M9 decision contract into symbolic annotation references.
It does not define exercise topology, accept evidence, parse synthetic or REAL bundles, attach
timestamps, evaluate posture, or authorize shadow/runtime/cue/release behavior.
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
from collections import Counter
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    from . import compile_pose_exercise_planning_matrix as planning
    from . import compile_pose_gold_decision_contract as decision
except ImportError:  # Direct ``python tools/...py`` execution.
    import compile_pose_exercise_planning_matrix as planning
    import compile_pose_gold_decision_contract as decision


SCHEMA_VERSION = 2
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = decision.DEFAULT_SOURCE
DEFAULT_POLICY = decision.DEFAULT_POLICY
DEFAULT_APPROVAL = decision.DEFAULT_APPROVAL
DEFAULT_REGISTRY = decision.DEFAULT_REGISTRY
DEFAULT_MATRIX = decision.DEFAULT_MATRIX
DEFAULT_DECISION_CONTRACT = decision.DEFAULT_OUTPUT
DEFAULT_OUTPUT = PROJECT_ROOT / "docs" / "pose-gold-annotation-contract.v2.json"

EXPECTED_SCOPE = {
    "exerciseCount": 41,
    "exactConditionCount": 97,
    "bindingCount": 167,
    "reviewedBindingCount": 148,
    "unresolvedBindingCount": 19,
    "annotationDecisionTemplateCount": 203,
    "releaseEligibleBindingCount": 0,
    "determinateEligibleTemplateCount": 0,
    "phaseRoleCount": 6,
    "sidePolicyKindCount": 9,
    "symbolicSideSlotCount": 10,
    "registeredExerciseProfileCount": 2,
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
    "phaseScopeReferenceContract",
    "sideRoleReferenceContract",
    "criterionDecisionReferenceContract",
    "registeredExerciseProfiles",
    "exercises",
}

ENVELOPE_TOP_LEVEL_KEYS = {
    "schemaVersion",
    "artifactKind",
    "authority",
    "decisionUse",
    "annotationContractArtifactSha256",
    "exerciseId",
    "syntheticUnit",
    "phaseScopeReferences",
    "sideRoleReferences",
    "criterionDecisions",
}

FORBIDDEN_ENVELOPE_KEY_FRAGMENTS = (
    "participant",
    "reviewer",
    "subject",
    "consent",
    "session",
    "capture",
    "timestamp",
    "media",
    "video",
    "image",
    "audio",
    "landmark",
    "coordinate",
    "leaf",
    "privatekey",
    "secret",
    "password",
)
ANNOTATION_TEMPLATE_ID = re.compile(r"^trex\.annotation-template-sha256-[0-9a-f]{64}$")


class AnnotationContractError(RuntimeError):
    """Raised when annotation schema data drifts or could imply unsupported authority."""


def canonical_json(value: Any) -> str:
    try:
        return decision.canonical_json(value)
    except (decision.DecisionContractError, ValueError) as error:
        raise AnnotationContractError(f"cannot canonicalize annotation data: {error}") from error


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
        return decision.canonical_lf_text_sha256(path)
    except decision.DecisionContractError as error:
        raise AnnotationContractError(str(error)) from error


def _deep_copy(value: Any) -> Any:
    return copy.deepcopy(value)


def _template_id(
    binding_key: Mapping[str, Any],
    phase_role_id: str,
    side_policy_kind: str,
    symbolic_slot: str,
    role_resolver_contract_id: str | None,
) -> str:
    identity = {
        "templateIdentitySchemaVersion": 1,
        "bindingKey": dict(binding_key),
        "phaseRoleId": phase_role_id,
        "sidePolicyKind": side_policy_kind,
        "symbolicSlot": symbolic_slot,
        "roleResolverContractId": role_resolver_contract_id,
    }
    return f"trex.annotation-template-sha256-{canonical_json_sha256(identity)}"


def _reviewed_template_rows(
    binding: Mapping[str, Any],
) -> list[dict[str, Any]]:
    phase_role_ids = binding.get("phaseRoleIds")
    side_policy = binding.get("sidePolicy")
    evidence = binding.get("evidenceRequirements")
    eligibility = binding.get("decisionEligibility")
    if (
        binding.get("reviewState") != "REVIEWED_ENGINEERING_V1"
        or binding.get("interpretationState") != "REVIEWED_POLICY_PROJECTION"
        or not isinstance(phase_role_ids, list)
        or not phase_role_ids
        or phase_role_ids != sorted(set(phase_role_ids))
        or not isinstance(side_policy, dict)
        or not isinstance(evidence, dict)
        or not isinstance(eligibility, dict)
    ):
        raise AnnotationContractError("reviewed binding shape differs from M9")
    kind = side_policy.get("kind")
    slots = side_policy.get("symbolicSlots")
    resolver = side_policy.get("roleResolverContractId")
    if kind not in decision.SIDE_POLICY_SYMBOLIC_SLOTS:
        raise AnnotationContractError("reviewed binding has an unsupported side policy")
    if slots != decision.SIDE_POLICY_SYMBOLIC_SLOTS[kind]:
        raise AnnotationContractError("reviewed binding symbolic slots differ from M9 policy")
    role_relative = kind in decision.ROLE_RELATIVE_SIDE_POLICIES
    if role_relative:
        if not isinstance(resolver, str) or not resolver:
            raise AnnotationContractError("role-relative binding requires its exact resolver")
    elif resolver is not None:
        raise AnnotationContractError("fixed side-policy binding cannot carry a resolver")
    if eligibility.get("state") != "UNKNOWN_GOLD_ONLY":
        raise AnnotationContractError("reviewed binding must remain UNKNOWN_GOLD_ONLY")
    if eligibility.get("permittedGoldStates") != ["UNKNOWN_GOLD"]:
        raise AnnotationContractError("reviewed binding Gold states differ from M9")
    if eligibility.get("determinateGoldStates") != []:
        raise AnnotationContractError("reviewed binding cannot permit determinate Gold")
    blockers = eligibility.get("blockers")
    if not isinstance(blockers, list) or blockers != sorted(set(blockers)):
        raise AnnotationContractError("reviewed binding blockers must be sorted and unique")

    rows: list[dict[str, Any]] = []
    for phase_role_id in phase_role_ids:
        if phase_role_id not in decision.PHASE_ROLE_IDS:
            raise AnnotationContractError("reviewed binding has an unsupported phase role")
        for symbolic_slot in slots:
            rows.append(
                {
                    "templateId": _template_id(
                        binding["bindingKey"],
                        phase_role_id,
                        kind,
                        symbolic_slot,
                        resolver,
                    ),
                    "bindingKey": _deep_copy(binding["bindingKey"]),
                    "reviewState": binding["reviewState"],
                    "interpretationState": binding["interpretationState"],
                    "phaseRoleId": phase_role_id,
                    "sidePolicyKind": kind,
                    "symbolicSlot": symbolic_slot,
                    "roleResolverContractId": resolver,
                    "evidenceRequirements": _deep_copy(evidence),
                    "blockers": list(blockers),
                    "decisionEligibility": _deep_copy(eligibility),
                    "currentGoldState": "UNKNOWN_GOLD",
                }
            )
    return rows


def _unresolved_binding_row(binding: Mapping[str, Any]) -> dict[str, Any]:
    if (
        binding.get("interpretationState") != "SOURCE_INTERPRETATION_UNRESOLVED"
        or binding.get("phaseRoleIds") != []
        or binding.get("sidePolicy") is not None
        or binding.get("evidenceRequirements") is not None
    ):
        raise AnnotationContractError("unresolved binding must not invent phase, side, or evidence")
    eligibility = binding.get("decisionEligibility")
    if not isinstance(eligibility, dict) or eligibility != {
        "state": "SOURCE_INTERPRETATION_UNRESOLVED",
        "permittedGoldStates": [],
        "determinateGoldStates": [],
        "blockers": ["SOURCE_INTERPRETATION_UNRESOLVED"],
    }:
        raise AnnotationContractError("unresolved binding decision state differs from M9")
    return {
        "bindingKey": _deep_copy(binding["bindingKey"]),
        "reviewState": binding["reviewState"],
        "interpretationState": binding["interpretationState"],
        "phaseRoleIds": [],
        "sidePolicy": None,
        "evidenceRequirements": None,
        "decisionEligibility": _deep_copy(eligibility),
        "annotationDecisionTemplateCount": 0,
    }


def _phase_scope_reference_contract(
    decision_contract: Mapping[str, Any],
    template_counts: Mapping[str, int],
) -> dict[str, Any]:
    source_rows = decision_contract["phaseRoleCatalog"]
    if [row["phaseRoleId"] for row in source_rows] != list(decision.PHASE_ROLE_IDS):
        raise AnnotationContractError("M9 phase-role catalog exact-set drift")
    roles: list[dict[str, Any]] = []
    for row in source_rows:
        if (
            row.get("scopeContractApprovalState") != "NO_APPROVED_SCOPE_CONTRACT"
            or row.get("scopeContractArtifactSha256") is not None
            or row.get("determinateDecisionEligible") is not False
        ):
            raise AnnotationContractError("M9 phase role claims unsupported scope authority")
        role_id = row["phaseRoleId"]
        roles.append(
            {
                "phaseRoleId": role_id,
                "reviewedBindingCount": row["bindingCount"],
                "annotationDecisionTemplateCount": template_counts[role_id],
                "approvedScopeState": "NO_APPROVED_SCOPE_CONTRACT",
                "approvedScopeArtifactSha256": None,
                "currentPermittedScopeState": "UNKNOWN_GOLD_ONLY",
                "determinateScopeEligible": False,
            }
        )
    return {
        "schemaVersion": 1,
        "keyFields": ["phaseRoleId", "occurrenceOrdinal"],
        "phaseRoles": roles,
        "occurrenceOrdinalContract": "NON_NEGATIVE_INTEGER_PER_EXERCISE_OCCURRENCE",
        "futureIntervalConvention": "START_INCLUSIVE_END_EXCLUSIVE",
        "boundaryUncertaintyPolicy": "UNCERTAIN_OR_MISSING_BOUNDARY_REMAINS_UNKNOWN_GOLD",
        "requiredEvidenceContract": (
            "SEPARATELY_APPROVED_EXERCISE_PHASE_SCOPE_AND_BOUNDARY_EVIDENCE_REQUIRED"
        ),
        "approvedScopeArtifactRequiredForDeterminateGold": True,
        "currentApprovedScopeArtifactCount": 0,
        "timestampValueFieldsAllowed": False,
        "runtimeIntervalModelIncluded": False,
        "externalIntervalInputIncluded": False,
        "topologyIncluded": False,
        "currentPermittedScopeStates": ["UNKNOWN_GOLD"],
        "determinateScopeStates": [],
    }


def _side_role_reference_contract(
    decision_contract: Mapping[str, Any],
    template_counts: Mapping[str, int],
) -> dict[str, Any]:
    source_rows = decision_contract["sidePolicyCatalog"]
    by_kind = {row["sidePolicyKind"]: row for row in source_rows}
    if set(by_kind) != set(decision.SIDE_POLICY_SYMBOLIC_SLOTS):
        raise AnnotationContractError("M9 side-policy catalog exact-set drift")
    policies: list[dict[str, Any]] = []
    all_slots: set[str] = set()
    for kind in sorted(decision.SIDE_POLICY_SYMBOLIC_SLOTS):
        source = by_kind[kind]
        slots = decision.SIDE_POLICY_SYMBOLIC_SLOTS[kind]
        if source.get("symbolicSlots") != slots:
            raise AnnotationContractError("M9 side-policy symbolic slots drift")
        role_relative = kind in decision.ROLE_RELATIVE_SIDE_POLICIES
        if source.get("resolverApprovalState") != (
            "NO_APPROVED_RESOLVER_ARTIFACT" if role_relative else "NOT_APPLICABLE"
        ):
            raise AnnotationContractError("M9 side-policy resolver approval drift")
        if source.get("resolverArtifactSha256") is not None:
            raise AnnotationContractError("M9 side-policy cannot claim a resolver artifact")
        all_slots.update(slots)
        policies.append(
            {
                "sidePolicyKind": kind,
                "reviewedBindingCount": source["bindingCount"],
                "annotationDecisionTemplateCount": template_counts[kind],
                "symbolicSlots": list(slots),
                "roleRelative": role_relative,
                "resolverRequirement": source["resolverRequirement"],
                "resolverContractIdSource": source["resolverContractIdSource"],
                "resolverApprovalState": source["resolverApprovalState"],
                "resolverArtifactSha256": None,
                "anatomicalAssignmentIncluded": False,
                "currentPermittedSideState": "UNKNOWN_GOLD_ONLY",
                "determinateSideEligible": False,
            }
        )
    if len(all_slots) != EXPECTED_SCOPE["symbolicSideSlotCount"]:
        raise AnnotationContractError("symbolic side-slot exact-set drift")
    return {
        "schemaVersion": 1,
        "keyFields": [
            "sidePolicyKind",
            "symbolicSlot",
            "roleResolverContractId",
        ],
        "sidePolicies": policies,
        "symbolicSlots": sorted(all_slots),
        "roleRelativeSidePolicyKinds": sorted(decision.ROLE_RELATIVE_SIDE_POLICIES),
        "staticSymbolicSlotAliasingAllowed": False,
        "exactBindingPolicyResolverRequiredForRoleRelativeKinds": True,
        "currentApprovedResolverArtifactCount": 0,
        "anatomicalAssignmentIncluded": False,
        "currentPermittedSideStates": ["UNKNOWN_GOLD"],
        "determinateSideStates": [],
    }


def _registered_profile(
    source_profile: Mapping[str, Any],
    exercise: Mapping[str, Any],
) -> dict[str, Any]:
    templates = exercise["decisionTemplates"]
    phase_counts = Counter(row["phaseRoleId"] for row in templates)
    side_counts = Counter(row["sidePolicyKind"] for row in templates)
    slot_counts = Counter(row["symbolicSlot"] for row in templates)
    if source_profile.get("exerciseId") != exercise["exerciseId"]:
        raise AnnotationContractError("M9 registered profile exercise join drift")
    if source_profile.get("bindingCount") != exercise["bindingCount"]:
        raise AnnotationContractError("M9 registered profile binding count drift")
    source_binding_keys = {
        canonical_json(row["bindingKey"])
        for row in source_profile.get("bindingDecisionTuples", [])
    }
    exercise_binding_keys = {
        canonical_json(row["bindingKey"])
        for row in templates + exercise["unresolvedBindings"]
    }
    if source_binding_keys != exercise_binding_keys:
        raise AnnotationContractError("M9 registered profile binding exact-set drift")
    return {
        "exerciseId": exercise["exerciseId"],
        "planReference": _deep_copy(source_profile["planReference"]),
        "bindingCount": exercise["bindingCount"],
        "reviewedBindingCount": exercise["reviewedBindingCount"],
        "unresolvedBindingCount": exercise["unresolvedBindingCount"],
        "annotationDecisionTemplateCount": exercise["annotationDecisionTemplateCount"],
        "phaseRoleTemplateCounts": dict(sorted(phase_counts.items())),
        "sidePolicyTemplateCounts": dict(sorted(side_counts.items())),
        "symbolicSlotTemplateCounts": dict(sorted(slot_counts.items())),
        "decisionTemplateIds": [row["templateId"] for row in templates],
        "determinateEligibleTemplateCount": 0,
    }


def compile_annotation_contract(
    *,
    decision_contract: Mapping[str, Any],
    compiler_implementation_sha256: str | None = None,
) -> dict[str, Any]:
    """Compile one already rebuilt and validated M9 decision contract."""

    try:
        decision.validate_contract(decision_contract, expected=decision_contract)
    except decision.DecisionContractError as error:
        raise AnnotationContractError(f"invalid M9 decision contract: {error}") from error
    if decision_contract.get("schemaVersion") != decision.SCHEMA_VERSION:
        raise AnnotationContractError("unsupported M9 schemaVersion")
    if decision_contract.get("artifactKind") != "TREX_POSE_GOLD_DECISION_CONTRACT":
        raise AnnotationContractError("unexpected M9 artifactKind")
    source_scope = decision_contract.get("catalogScope")
    expected_m9_scope = {
        **decision.EXPECTED_SCOPE,
        "phaseRoleCount": 6,
        "sidePolicyKindCount": 9,
        "registeredExerciseProfileCount": 2,
    }
    if source_scope != expected_m9_scope:
        raise AnnotationContractError("M9 catalog scope drift")

    source_exercises = decision_contract.get("exercises")
    if not isinstance(source_exercises, list):
        raise AnnotationContractError("M9 exercises must be an array")
    exercises: list[dict[str, Any]] = []
    all_template_ids: set[str] = set()
    all_template_identity_tuples: set[str] = set()
    all_binding_keys: set[str] = set()
    condition_ids: set[str] = set()
    phase_template_counts: Counter[str] = Counter()
    side_template_counts: Counter[str] = Counter()
    reviewed_count = 0
    unresolved_count = 0
    previous_exercise_id: str | None = None
    for source_exercise in source_exercises:
        if not isinstance(source_exercise, dict):
            raise AnnotationContractError("M9 exercise must be an object")
        exercise_id = source_exercise.get("exerciseId")
        if not isinstance(exercise_id, str) or not exercise_id:
            raise AnnotationContractError("M9 exerciseId is invalid")
        if previous_exercise_id is not None and exercise_id <= previous_exercise_id:
            raise AnnotationContractError("M9 exercises must be sorted and unique")
        previous_exercise_id = exercise_id
        bindings = source_exercise.get("bindings")
        if not isinstance(bindings, list):
            raise AnnotationContractError("M9 exercise bindings must be an array")
        templates: list[dict[str, Any]] = []
        unresolved: list[dict[str, Any]] = []
        for binding in bindings:
            if not isinstance(binding, dict):
                raise AnnotationContractError("M9 binding must be an object")
            binding_key = binding.get("bindingKey")
            if not isinstance(binding_key, dict) or binding_key.get("exerciseId") != exercise_id:
                raise AnnotationContractError("M9 binding key exercise join drift")
            key_text = canonical_json(binding_key)
            if key_text in all_binding_keys:
                raise AnnotationContractError("M9 binding key is duplicate")
            all_binding_keys.add(key_text)
            condition_id = binding_key.get("sourceConditionId")
            if not isinstance(condition_id, str):
                raise AnnotationContractError("M9 sourceConditionId is invalid")
            condition_ids.add(condition_id)
            if binding.get("interpretationState") == "SOURCE_INTERPRETATION_UNRESOLVED":
                unresolved.append(_unresolved_binding_row(binding))
                unresolved_count += 1
            else:
                binding_templates = _reviewed_template_rows(binding)
                templates.extend(binding_templates)
                reviewed_count += 1
                for template in binding_templates:
                    template_id = template["templateId"]
                    identity_tuple = canonical_json(
                        {
                            "bindingKey": template["bindingKey"],
                            "phaseRoleId": template["phaseRoleId"],
                            "sidePolicyKind": template["sidePolicyKind"],
                            "symbolicSlot": template["symbolicSlot"],
                            "roleResolverContractId": template[
                                "roleResolverContractId"
                            ],
                        }
                    )
                    if not ANNOTATION_TEMPLATE_ID.fullmatch(template_id):
                        raise AnnotationContractError("annotation template identity is invalid")
                    if (
                        template_id in all_template_ids
                        or identity_tuple in all_template_identity_tuples
                    ):
                        raise AnnotationContractError("annotation template identity collision")
                    all_template_ids.add(template_id)
                    all_template_identity_tuples.add(identity_tuple)
                    phase_template_counts[template["phaseRoleId"]] += 1
                    side_template_counts[template["sidePolicyKind"]] += 1
        templates.sort(
            key=lambda row: (
                row["bindingKey"]["sourceConditionId"],
                row["phaseRoleId"],
                row["sidePolicyKind"],
                row["symbolicSlot"],
            )
        )
        unresolved.sort(key=lambda row: row["bindingKey"]["sourceConditionId"])
        exercises.append(
            {
                "exerciseId": exercise_id,
                "registeredPlanState": source_exercise.get("registeredPlanState"),
                "bindingCount": len(bindings),
                "reviewedBindingCount": len(bindings) - len(unresolved),
                "unresolvedBindingCount": len(unresolved),
                "annotationDecisionTemplateCount": len(templates),
                "decisionTemplates": templates,
                "unresolvedBindings": unresolved,
                "determinateEligibleTemplateCount": 0,
            }
        )

    if len(exercises) != EXPECTED_SCOPE["exerciseCount"]:
        raise AnnotationContractError("exercise count drift")
    if len(all_binding_keys) != EXPECTED_SCOPE["bindingCount"]:
        raise AnnotationContractError("binding exact-set drift")
    if len(condition_ids) != EXPECTED_SCOPE["exactConditionCount"]:
        raise AnnotationContractError("condition exact-set drift")
    if reviewed_count != EXPECTED_SCOPE["reviewedBindingCount"]:
        raise AnnotationContractError("reviewed binding count drift")
    if unresolved_count != EXPECTED_SCOPE["unresolvedBindingCount"]:
        raise AnnotationContractError("unresolved binding count drift")
    if len(all_template_ids) != EXPECTED_SCOPE["annotationDecisionTemplateCount"]:
        raise AnnotationContractError("annotation decision-template count drift")
    if set(phase_template_counts) != set(decision.PHASE_ROLE_IDS):
        raise AnnotationContractError("annotation phase-role exact-set drift")
    if set(side_template_counts) != set(decision.SIDE_POLICY_SYMBOLIC_SLOTS):
        raise AnnotationContractError("annotation side-policy exact-set drift")

    exercise_by_id = {row["exerciseId"]: row for row in exercises}
    source_profiles = decision_contract.get("registeredExerciseProfiles")
    if not isinstance(source_profiles, list):
        raise AnnotationContractError("M9 registered profiles must be an array")
    profiles = [
        _registered_profile(source_profile, exercise_by_id[source_profile["exerciseId"]])
        for source_profile in source_profiles
    ]
    profiles.sort(key=lambda row: row["exerciseId"])
    if len(profiles) != EXPECTED_SCOPE["registeredExerciseProfileCount"]:
        raise AnnotationContractError("registered exercise-profile count drift")

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
        raise AnnotationContractError("compiler implementation SHA-256 is invalid")
    try:
        int(implementation_sha, 16)
    except ValueError as error:
        raise AnnotationContractError("compiler implementation SHA-256 is invalid") from error

    contract = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": "TREX_POSE_GOLD_ANNOTATION_CONTRACT",
        "authority": dict(planning.AUTHORITY_ZERO),
        "decisionUse": (
            "SYMBOLIC_UNKNOWN_GOLD_ANNOTATION_REFERENCE_SCHEMA_ONLY_NOT_TOPOLOGY_EVIDENCE_RUNTIME_CUE_OR_RELEASE_AUTHORITY"
        ),
        "contractBoundary": {
            "phaseTopologyIncluded": False,
            "timestampValueModelIncluded": False,
            "timestampInputIncluded": False,
            "externalSyntheticPathParserIncluded": False,
            "restrictedRealParserIncluded": False,
            "realEvidenceIntakeIncluded": False,
            "rawMediaOrLandmarkInputIncluded": False,
            "reviewOrAdjudicationRecordModelIncluded": False,
            "evaluatorIncluded": False,
            "scoreInterfaceIncluded": False,
            "cueInterfaceIncluded": False,
            "runtimeProviderIncluded": False,
            "releaseTransitionIncluded": False,
        },
        "compilerImplementation": {
            "relativePath": "tools/compile_pose_gold_annotation_contract.py",
            "canonicalTextSha256": implementation_sha,
            "normalization": "UTF8_NFC_LF",
        },
        "compilerImplementationUse": (
            "IMPLEMENTATION_DRIFT_DETECTION_ONLY_NOT_APPROVAL_OR_AUTHORITY"
        ),
        "inputProvenance": {
            "decisionContractArtifactSha256": decision_contract["artifactSha256"],
            "decisionContractCompilerCanonicalTextSha256": decision_contract[
                "compilerImplementation"
            ]["canonicalTextSha256"],
            "decisionContractInputProvenance": _deep_copy(
                decision_contract["inputProvenance"]
            ),
        },
        "catalogScope": dict(EXPECTED_SCOPE),
        "phaseScopeReferenceContract": _phase_scope_reference_contract(
            decision_contract,
            phase_template_counts,
        ),
        "sideRoleReferenceContract": _side_role_reference_contract(
            decision_contract,
            side_template_counts,
        ),
        "criterionDecisionReferenceContract": {
            "schemaVersion": 1,
            "keyFields": [
                "bindingKey.exerciseId",
                "bindingKey.sourceConditionId",
                "bindingKey.bindingId",
                "bindingKey.bindingPolicySha256",
                "bindingKey.policyRegistrySha256",
                "phaseScopeReference.phaseRoleId",
                "phaseScopeReference.occurrenceOrdinal",
                "sideRoleReference.sidePolicyKind",
                "sideRoleReference.symbolicSlot",
                "sideRoleReference.roleResolverContractId",
            ],
            "templateOccurrenceOrdinalState": "BOUND_BY_CONFORMANCE_ENVELOPE",
            "exactM9EvidenceRequirementsRequired": True,
            "exactM9BlockersRequired": True,
            "exactM9DecisionEligibilityRequired": True,
            "currentPermittedGoldStates": ["UNKNOWN_GOLD"],
            "determinateGoldStates": [],
            "positiveOrNegativeDecisionAllowed": False,
        },
        "registeredExerciseProfiles": profiles,
        "exercises": exercises,
    }
    return with_artifact_sha256(contract)


def validate_contract(
    value: Mapping[str, Any],
    *,
    expected: Mapping[str, Any],
) -> None:
    try:
        planning._require_canonical_tree(value, "annotation contract")
        planning._strict_keys(value, TOP_LEVEL_KEYS, "annotation contract")
        planning._validate_fingerprinted_artifact(value, "annotation contract")
        planning._validate_zero_authority(value["authority"], "annotation contract authority")
    except (planning.PlanningMatrixError, KeyError) as error:
        raise AnnotationContractError(f"invalid annotation contract: {error}") from error
    if value != expected:
        raise AnnotationContractError(
            "annotation contract differs from independently rebuilt contract"
        )


def compile_from_paths(
    *,
    source_path: Path = DEFAULT_SOURCE,
    policy_path: Path = DEFAULT_POLICY,
    approval_path: Path = DEFAULT_APPROVAL,
    registry_path: Path = DEFAULT_REGISTRY,
    matrix_path: Path = DEFAULT_MATRIX,
    decision_contract_path: Path = DEFAULT_DECISION_CONTRACT,
    project_root: Path = PROJECT_ROOT,
) -> dict[str, Any]:
    try:
        source_contract = planning.load_json(
            decision_contract_path,
            "M9 decision contract",
            project_root=project_root,
            require_pretty_lf=True,
        )
        expected_contract = decision.compile_from_paths(
            source_path=source_path,
            policy_path=policy_path,
            approval_path=approval_path,
            registry_path=registry_path,
            matrix_path=matrix_path,
            project_root=project_root,
        )
        decision.validate_contract(source_contract, expected=expected_contract)
    except (planning.PlanningMatrixError, decision.DecisionContractError) as error:
        raise AnnotationContractError(f"M9 rebuild/input validation failed: {error}") from error
    result = compile_annotation_contract(decision_contract=source_contract)
    validate_contract(result, expected=result)
    return result


def _canonical_registered_profile(
    profile: Mapping[str, Any],
    schema: Mapping[str, Any],
) -> tuple[Mapping[str, Any], Mapping[str, Any]]:
    try:
        validate_contract(schema, expected=schema)
    except AnnotationContractError as error:
        raise AnnotationContractError(f"invalid envelope schema: {error}") from error
    exercise_id = profile.get("exerciseId")
    canonical_profiles = [
        row
        for row in schema["registeredExerciseProfiles"]
        if row.get("exerciseId") == exercise_id
    ]
    if len(canonical_profiles) != 1 or profile != canonical_profiles[0]:
        raise AnnotationContractError("envelope profile is not an exact registered schema profile")
    exercises = [
        row for row in schema["exercises"] if row.get("exerciseId") == exercise_id
    ]
    if len(exercises) != 1:
        raise AnnotationContractError("envelope profile exercise is absent or duplicate")
    return canonical_profiles[0], exercises[0]


def _build_unknown_conformance_envelope(
    profile: Mapping[str, Any],
    schema: Mapping[str, Any],
) -> dict[str, Any]:
    canonical_profile, exercise = _canonical_registered_profile(profile, schema)
    templates_by_id = {
        row["templateId"]: row for row in exercise["decisionTemplates"]
    }
    template_ids = canonical_profile["decisionTemplateIds"]
    if set(template_ids) != set(templates_by_id) or len(template_ids) != len(templates_by_id):
        raise AnnotationContractError("registered profile template exact-set drift")
    templates = [templates_by_id[template_id] for template_id in template_ids]

    phase_keys = sorted({row["phaseRoleId"] for row in templates})
    phase_references = [
        {
            "phaseRoleId": phase_role_id,
            "occurrenceOrdinal": 0,
            "scopeState": "UNKNOWN_GOLD",
            "scopeApprovalState": "NO_APPROVED_SCOPE_CONTRACT",
            "approvedScopeArtifactSha256": None,
        }
        for phase_role_id in phase_keys
    ]
    side_keys = sorted(
        {
            (
                row["sidePolicyKind"],
                row["symbolicSlot"],
                row["roleResolverContractId"],
            )
            for row in templates
        },
        key=lambda item: (item[0], item[1], item[2] or ""),
    )
    side_references = [
        {
            "sidePolicyKind": kind,
            "symbolicSlot": slot,
            "roleResolverContractId": resolver,
            "resolverApprovalState": (
                "NO_APPROVED_RESOLVER_ARTIFACT"
                if kind in decision.ROLE_RELATIVE_SIDE_POLICIES
                else "NOT_APPLICABLE"
            ),
            "resolverArtifactSha256": None,
            "anatomicalAssignment": None,
            "sideState": "UNKNOWN_GOLD",
        }
        for kind, slot, resolver in side_keys
    ]
    criterion_decisions = [
        {
            "templateId": row["templateId"],
            "bindingKey": _deep_copy(row["bindingKey"]),
            "phaseScopeReference": {
                "phaseRoleId": row["phaseRoleId"],
                "occurrenceOrdinal": 0,
            },
            "sideRoleReference": {
                "sidePolicyKind": row["sidePolicyKind"],
                "symbolicSlot": row["symbolicSlot"],
                "roleResolverContractId": row["roleResolverContractId"],
            },
            "goldState": "UNKNOWN_GOLD",
        }
        for row in templates
    ]
    return {
        "schemaVersion": 1,
        "artifactKind": "TREX_POSE_GOLD_UNKNOWN_CONFORMANCE_ENVELOPE",
        "authority": dict(planning.AUTHORITY_ZERO),
        "decisionUse": (
            "IN_MEMORY_SYNTHETIC_STRUCTURE_CONFORMANCE_ONLY_NOT_EVIDENCE_ANNOTATION_GOLD_RUNTIME_CUE_OR_RELEASE_AUTHORITY"
        ),
        "annotationContractArtifactSha256": schema["artifactSha256"],
        "exerciseId": canonical_profile["exerciseId"],
        "syntheticUnit": {
            "unitKind": "UNKNOWN_GOLD_REFERENCE_CONFORMANCE_ONLY",
            "occurrenceOrdinal": 0,
            "goldState": "UNKNOWN_GOLD",
        },
        "phaseScopeReferences": phase_references,
        "sideRoleReferences": side_references,
        "criterionDecisions": criterion_decisions,
    }


def _reject_private_envelope_names(value: Any, path: str = "envelope") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str):
                raise AnnotationContractError(f"{path} contains a non-string key")
            folded = "".join(character for character in key.lower() if character.isalnum())
            if any(fragment in folded for fragment in FORBIDDEN_ENVELOPE_KEY_FRAGMENTS):
                raise AnnotationContractError(f"{path} contains forbidden private field {key!r}")
            _reject_private_envelope_names(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_private_envelope_names(child, f"{path}[{index}]")


def build_unknown_conformance_envelope(
    exercise_id: str,
) -> dict[str, Any]:
    """Build an UNKNOWN-only envelope from repository-canonical inputs only.

    The public helper intentionally accepts no caller-provided schema, profile, or path. This
    prevents a re-fingerprinted mapping from becoming a synthetic envelope merely because its
    own hash is internally consistent.
    """

    schema = compile_from_paths()
    profile = _registered_profile_for_exercise(exercise_id, schema)
    envelope = _build_unknown_conformance_envelope(profile, schema)
    _validate_unknown_conformance_envelope(envelope, profile, schema)
    return envelope


def validate_unknown_conformance_envelope(
    envelope: Mapping[str, Any],
    exercise_id: str,
) -> None:
    """Validate against a freshly rebuilt repository-canonical registered profile."""

    schema = compile_from_paths()
    profile = _registered_profile_for_exercise(exercise_id, schema)
    _validate_unknown_conformance_envelope(envelope, profile, schema)


def _registered_profile_for_exercise(
    exercise_id: str,
    schema: Mapping[str, Any],
) -> Mapping[str, Any]:
    if not isinstance(exercise_id, str) or not exercise_id:
        raise AnnotationContractError("envelope exerciseId must be a non-empty string")
    profiles = [
        row
        for row in schema["registeredExerciseProfiles"]
        if row.get("exerciseId") == exercise_id
    ]
    if len(profiles) != 1:
        raise AnnotationContractError(
            "envelope exerciseId is not an exact registered repository profile"
        )
    return profiles[0]


def _validate_unknown_conformance_envelope(
    envelope: Mapping[str, Any],
    profile: Mapping[str, Any],
    schema: Mapping[str, Any],
) -> None:
    expected = _build_unknown_conformance_envelope(profile, schema)
    try:
        planning._require_canonical_tree(envelope, "UNKNOWN conformance envelope")
        planning._strict_keys(
            envelope,
            ENVELOPE_TOP_LEVEL_KEYS,
            "UNKNOWN conformance envelope",
        )
        planning._validate_zero_authority(
            envelope["authority"],
            "UNKNOWN conformance envelope authority",
        )
    except (planning.PlanningMatrixError, KeyError) as error:
        raise AnnotationContractError(f"invalid UNKNOWN conformance envelope: {error}") from error
    _reject_private_envelope_names(envelope)
    if envelope != expected:
        raise AnnotationContractError(
            "UNKNOWN conformance envelope differs from its exact registered profile"
        )


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
            "annotation contract output path",
        )
    except planning.PlanningMatrixError as error:
        raise AnnotationContractError(str(error)) from error
    expected_relative = Path("docs") / "pose-gold-annotation-contract.v2.json"
    if relative != expected_relative:
        raise AnnotationContractError(
            f"output path must be {expected_relative.as_posix()}"
        )
    for input_path in input_paths:
        try:
            input_absolute, _ = planning._absolute_confined(
                input_path,
                project_root,
                "annotation contract input path",
            )
        except planning.PlanningMatrixError as error:
            raise AnnotationContractError(str(error)) from error
        if absolute == input_absolute:
            raise AnnotationContractError("output path must differ from every input path")
    if not absolute.parent.exists():
        raise AnnotationContractError("output parent must already exist")
    try:
        planning._assert_no_reparse_chain(
            absolute.parent,
            project_root,
            "annotation contract output parent",
            require_regular=False,
        )
        if absolute.exists():
            planning._assert_no_reparse_chain(
                absolute,
                project_root,
                "existing annotation contract output",
                require_regular=True,
            )
    except planning.PlanningMatrixError as error:
        raise AnnotationContractError(str(error)) from error
    return absolute


def write_or_check(path: Path, value: Mapping[str, Any], *, check: bool) -> None:
    rendered = render_json(value).encode("utf-8")
    if check:
        try:
            current = path.read_bytes()
        except OSError as error:
            raise AnnotationContractError(
                f"cannot read existing annotation contract: {error}"
            ) from error
        if current != rendered:
            raise AnnotationContractError(f"annotation contract is stale: {path}")
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
            raise AnnotationContractError(
                f"atomic annotation contract publish failed: {error}"
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
    parser.add_argument(
        "--decision-contract",
        type=Path,
        default=DEFAULT_DECISION_CONTRACT,
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
    ]
    try:
        contract = compile_from_paths(
            source_path=args.source_coverage,
            policy_path=args.policy,
            approval_path=args.policy_approval,
            registry_path=args.planning_registry,
            matrix_path=args.planning_matrix,
            decision_contract_path=args.decision_contract,
            project_root=PROJECT_ROOT,
        )
        output = _safe_output_path(
            args.output,
            project_root=PROJECT_ROOT,
            input_paths=inputs,
        )
        write_or_check(output, contract, check=args.check)
    except (AnnotationContractError, OSError) as error:
        print(f"pose Gold annotation contract failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
