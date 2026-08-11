#!/usr/bin/env python3
"""Compile the 41-exercise catalog policy into a non-runtime planning matrix.

This compiler joins the authoritative AI Hub source coverage, curated policy, repository
approval pin, and a registry of public exercise planning artifacts.  It validates only the
common policy projection of registered plans; artifact-specific Gold protocol validation stays
with the artifact-specific compiler.  The generated matrix has no runtime, score, cue, phase
decoder, calibration, or release authority.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import stat
import sys
import tempfile
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence

try:
    from .compile_aihub_criterion_policy import PolicyError, compile_policy
except ImportError:  # Direct ``python tools/...py`` execution.
    from compile_aihub_criterion_policy import PolicyError, compile_policy


SCHEMA_VERSION = 1
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = PROJECT_ROOT / "docs" / "aihub-criterion-coverage.json"
DEFAULT_POLICY = PROJECT_ROOT / "docs" / "aihub-criterion-policy.json"
DEFAULT_APPROVAL = PROJECT_ROOT / "docs" / "aihub-criterion-policy-approval.json"
DEFAULT_REGISTRY = PROJECT_ROOT / "docs" / "pose-exercise-planning-registry.v1.json"
DEFAULT_OUTPUT = PROJECT_ROOT / "docs" / "pose-exercise-planning-matrix.v1.json"

EXPECTED_SCOPE = {
    "exerciseCount": 41,
    "exactConditionCount": 97,
    "bindingCount": 167,
    "reviewedBindingCount": 148,
    "releaseEligibleBindingCount": 0,
}

AUTHORITY_ZERO = {
    "calibrationAuthority": 0,
    "cueAuthority": 0,
    "phaseDecoderAuthority": 0,
    "releaseAuthority": 0,
    "repCountAuthority": 0,
    "runtimeProviderAuthority": 0,
    "scoreAuthority": 0,
    "shadowAuthority": 0,
    "userPassFailUnknownAuthority": 0,
}

REGISTRY_KEYS = {
    "schemaVersion",
    "artifactKind",
    "artifactSha256",
    "authority",
    "policyProvenance",
    "catalogScope",
    "registeredPlans",
}
REGISTRY_PLAN_KEYS = {
    "exerciseId",
    "artifactKind",
    "artifactPath",
    "artifactSha256",
    "planState",
}
PROVENANCE_KEYS = {
    "sourceCatalogSha256",
    "sourceCoverageArtifactSha256",
    "sourceMetadataSetSha256",
    "approvedPolicySha256",
    "approvalArtifactSha256",
    "policyRegistrySha256",
}
BINDING_KEY_KEYS = {
    "bindingId",
    "bindingPolicySha256",
    "exerciseId",
    "sourceConditionId",
    "policyRegistrySha256",
}
MODERN_CRITERION_PLAN_KEYS = {
    "bindingKey",
    "reviewState",
    "releaseState",
    "reasonCodes",
    "decisionEvidenceRefs",
    "semanticId",
    "semanticFamilyId",
    "measurementConstructId",
    "claimBoundary",
    "observability",
    "phaseApplicability",
    "sidePolicy",
    "viewApplicability",
    "requiredCapabilityIds",
    "calibrationState",
    "calibrationProvenance",
    "unsupportedReasonCodes",
    "reviewEvidenceRefs",
}
MODERN_PLAN_KEYS = {
    "schemaVersion",
    "artifactKind",
    "artifactSha256",
    "authority",
    "exerciseId",
    "readiness",
    "decisionUse",
    "policyProvenance",
    "criterionPlans",
    "currentActualEvidenceCounts",
    "blockers",
    "phaseRequirements",
    "sideRequirements",
    "viewRequirements",
    "capabilityRequirements",
}
LEGACY_PLAN_KEYS = {
    "schemaVersion",
    "artifactKind",
    "artifactSha256",
    "authority",
    "exerciseId",
    "readiness",
    "decisionUse",
    "policyProvenance",
    "criterionPlans",
    "currentActualEvidenceCounts",
    "approvalProvenanceSlots",
    "approvalTrustContract",
    "canonicalization",
    "cohortContract",
    "officialAiHubValidationUse",
    "phaseGoldPlan",
    "priorResearchProvenance",
    "protocolArtifactSha256",
    "referenceEvidencePlan",
    "reviewPlan",
    "rightsManifestArtifactSha256",
    "studyId",
    "viewGoldPlan",
}
LEGACY_CRITERION_PLAN_KEYS = {
    "bindingKey",
    "calibrationState",
    "goldClaimBoundary",
    "measurementConstructId",
    "observability",
    "permittedReferenceModalityIds",
    "phaseRoleId",
    "requiredCapabilityIds",
    "sidePolicy",
    "sourceConditionExactText",
    "viewContractIds",
}
LEGACY_CONTACT_CRITERION_EXTRA_KEY = "goldStateWithoutAttestedContactSensor"
ACTUAL_EVIDENCE_COUNT_KEYS = {
    "adjudicatedCriterionDecisionCount",
    "adjudicatedPhaseCycleCount",
    "adjudicatedViewDecisionCount",
    "captureGroupCount",
    "participantCount",
    "realRestrictedBundleCount",
    "reviewerCount",
}
MODERN_BLOCKERS = [
    "NO_ACTUAL_GOLD_EVIDENCE",
    "NO_APPROVED_CALIBRATION_ARTIFACTS",
    "NO_APPROVED_PHASE_GRAPH",
    "NO_ATTESTED_RUNTIME_PROVIDER",
    "NO_RUNTIME_RELEASE_AUTHORIZATION",
]
PLAN_STATES = {
    "PREREGISTERED_GOLD_STUDY_PLAN_NOT_READY",
    "POLICY_PROJECTION_ONLY_NO_APPROVED_TOPOLOGY",
}
CATALOG_POLICY_ONLY = "CATALOG_POLICY_ONLY_NO_REGISTERED_PLAN"

DISPOSITIONS = {
    None: "SOURCE_INTERPRETATION_UNRESOLVED",
    "DIRECT": "REVIEWED_DIRECT_REQUIRES_GOLD_CALIBRATION",
    "PROXY_UNVALIDATED": "REVIEWED_PROXY_REQUIRES_GOLD_VALIDATION",
    "PROXY_GOLD_VALIDATED": "REVIEWED_PROXY_REQUIRES_GOLD_VALIDATION",
    "NOT_OBSERVABLE": "REVIEWED_NOT_OBSERVABLE_REQUIRES_EXTRA_CAPABILITY",
}


class PlanningMatrixError(RuntimeError):
    """Raised when an input or output cannot be accepted without adding authority."""


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


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
        raise PlanningMatrixError(f"cannot hash compiler implementation: {error}") from error
    if unicodedata.normalize("NFC", text) != text:
        raise PlanningMatrixError("compiler implementation must use Unicode NFC")
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _reject_duplicate_object_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PlanningMatrixError(f"JSON contains duplicate object key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise PlanningMatrixError(f"JSON contains non-finite number {value}")


def _require_canonical_tree(value: Any, label: str) -> None:
    if isinstance(value, str):
        if unicodedata.normalize("NFC", value) != value:
            raise PlanningMatrixError(f"{label} contains a non-NFC string")
        if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise PlanningMatrixError(f"{label} contains an unpaired Unicode surrogate")
        return
    if isinstance(value, Mapping):
        for key, item in value.items():
            if not isinstance(key, str):
                raise PlanningMatrixError(f"{label} contains a non-string object key")
            _require_canonical_tree(key, f"{label} key")
            _require_canonical_tree(item, f"{label}.{key}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _require_canonical_tree(item, f"{label}[{index}]")
        return
    if isinstance(value, float) and not math.isfinite(value):
        raise PlanningMatrixError(f"{label} contains a non-finite number")


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PlanningMatrixError(f"{label} must be an object")
    return value


def _array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise PlanningMatrixError(f"{label} must be an array")
    return value


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise PlanningMatrixError(f"{label} must be a non-empty string")
    if unicodedata.normalize("NFC", value) != value:
        raise PlanningMatrixError(f"{label} must use Unicode NFC")
    return value


def _exact_int(value: Any, label: str) -> int:
    if type(value) is not int:
        raise PlanningMatrixError(f"{label} must be an integer")
    return value


def _strict_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise PlanningMatrixError(
            f"{label} fields differ: missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )


def _is_reparse(path: Path) -> bool:
    if path.is_symlink():
        return True
    is_junction = getattr(path, "is_junction", None)
    return bool(is_junction is not None and is_junction())


def _absolute_confined(path: Path, project_root: Path, label: str) -> tuple[Path, Path]:
    root = Path(os.path.abspath(os.fspath(project_root)))
    candidate = path if path.is_absolute() else root / path
    absolute = Path(os.path.abspath(os.fspath(candidate)))
    try:
        relative = absolute.relative_to(root)
    except ValueError as error:
        raise PlanningMatrixError(f"{label} escapes the project root") from error
    if relative == Path("."):
        raise PlanningMatrixError(f"{label} must name a file below the project root")
    return absolute, relative


def _assert_no_reparse_chain(
    path: Path,
    project_root: Path,
    label: str,
    *,
    require_regular: bool,
) -> Path:
    absolute, relative = _absolute_confined(path, project_root, label)
    root = Path(os.path.abspath(os.fspath(project_root)))
    current = root
    if _is_reparse(current):
        raise PlanningMatrixError(f"{label} project root must not be a reparse point")
    for part in relative.parts:
        current = current / part
        if current.exists() and _is_reparse(current):
            raise PlanningMatrixError(f"{label} traverses a symlink or junction")
    if require_regular:
        try:
            mode = absolute.stat().st_mode
        except OSError as error:
            raise PlanningMatrixError(f"cannot inspect {label}: {error}") from error
        if not stat.S_ISREG(mode):
            raise PlanningMatrixError(f"{label} must be a regular file")
    return absolute


def load_json(
    path: Path,
    label: str,
    *,
    project_root: Path = PROJECT_ROOT,
    require_pretty_lf: bool = False,
) -> dict[str, Any]:
    source = _assert_no_reparse_chain(
        path, project_root, label, require_regular=True
    )
    try:
        raw = source.read_bytes()
        text = raw.decode("utf-8", errors="strict")
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_object_keys,
            parse_constant=_reject_nonfinite,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PlanningMatrixError(f"cannot read {label}: {error}") from error
    result = _object(value, label)
    _require_canonical_tree(result, label)
    if require_pretty_lf and raw != render_json(result).encode("utf-8"):
        raise PlanningMatrixError(
            f"{label} must use sorted pretty JSON, UTF-8, LF, and one final newline"
        )
    return result


def _expected_provenance(compiled: Mapping[str, Any]) -> dict[str, Any]:
    source = _object(compiled["sourceCoverage"], "compiled sourceCoverage")
    return {
        "sourceCatalogSha256": source["catalogSha256"],
        "sourceCoverageArtifactSha256": source["coverageArtifactSha256"],
        "sourceMetadataSetSha256": source["metadataSetSha256"],
        "approvedPolicySha256": compiled["policySha256"],
        "approvalArtifactSha256": compiled["approvalArtifactSha256"],
        "policyRegistrySha256": compiled["registrySha256"],
    }


def _validate_zero_authority(value: Any, label: str) -> None:
    authority = _object(value, label)
    if authority != AUTHORITY_ZERO:
        raise PlanningMatrixError(f"{label} must keep all nine authority axes at zero")


def _validate_fingerprinted_artifact(value: Mapping[str, Any], label: str) -> None:
    fingerprint = value.get("artifactSha256")
    if not isinstance(fingerprint, str) or len(fingerprint) != 64:
        raise PlanningMatrixError(f"{label} must carry a SHA-256 artifactSha256")
    if artifact_sha256(value) != fingerprint:
        raise PlanningMatrixError(f"{label} artifactSha256 mismatch")


def _validate_registry_relative_path(value: Any) -> str:
    text = _string(value, "registered plan artifactPath")
    if "\\" in text:
        raise PlanningMatrixError("registered plan artifactPath must use POSIX separators")
    path = PurePosixPath(text)
    if path.is_absolute() or str(path) != text:
        raise PlanningMatrixError("registered plan artifactPath must be canonical and relative")
    if any(part in {"", ".", ".."} for part in path.parts):
        raise PlanningMatrixError("registered plan artifactPath contains an unsafe segment")
    if len(path.parts) < 2 or path.parts[0] != "docs" or path.suffix != ".json":
        raise PlanningMatrixError("registered plan artifactPath must name a JSON file under docs")
    return text


def _validate_projection_row(
    row: Mapping[str, Any],
    binding: Mapping[str, Any],
    *,
    policy_registry_sha256: str,
) -> None:
    key = _object(row.get("bindingKey"), "criterion plan bindingKey")
    _strict_keys(key, BINDING_KEY_KEYS, "criterion plan bindingKey")
    expected_key = {
        "bindingId": binding["bindingId"],
        "bindingPolicySha256": binding["bindingPolicySha256"],
        "exerciseId": binding["exerciseId"],
        "sourceConditionId": binding["sourceConditionId"],
        "policyRegistrySha256": policy_registry_sha256,
    }
    if key != expected_key:
        raise PlanningMatrixError(
            f"criterion plan bindingKey drift for {binding['bindingId']}"
        )
    interpretation = _object(binding["interpretation"], "reviewed interpretation")
    expected_scalars = {
        "measurementConstructId": interpretation["measurementConstructId"],
        "observability": interpretation["observability"],
        "calibrationState": interpretation["calibrationProvenance"]["state"],
    }
    for field, expected in expected_scalars.items():
        if row.get(field) != expected:
            raise PlanningMatrixError(
                f"criterion plan {field} drift for {binding['bindingId']}"
            )
    if row.get("requiredCapabilityIds") != interpretation["requiredCapabilityIds"]:
        raise PlanningMatrixError(
            f"criterion plan requiredCapabilityIds drift for {binding['bindingId']}"
        )

    # New declarations retain complete policy objects.  Legacy preregistrations retain the
    # earlier scalar/list representation; the adapter is selected by fields, never exercise ID.
    if "phaseApplicability" in row:
        _strict_keys(row, MODERN_CRITERION_PLAN_KEYS, "modern criterion plan")
        full_exact = {
            "reviewState": binding["reviewState"],
            "releaseState": binding["releaseState"],
            "reasonCodes": binding["reasonCodes"],
            "decisionEvidenceRefs": binding["decisionEvidenceRefs"],
            "semanticId": interpretation["semanticId"],
            "semanticFamilyId": interpretation["semanticFamilyId"],
            "measurementConstructId": interpretation["measurementConstructId"],
            "claimBoundary": interpretation["claimBoundary"],
            "observability": interpretation["observability"],
            "phaseApplicability": interpretation["phaseApplicability"],
            "sidePolicy": interpretation["sidePolicy"],
            "viewApplicability": interpretation["viewApplicability"],
            "requiredCapabilityIds": interpretation["requiredCapabilityIds"],
            "calibrationState": interpretation["calibrationProvenance"]["state"],
            "calibrationProvenance": interpretation["calibrationProvenance"],
            "unsupportedReasonCodes": interpretation["unsupportedReasonCodes"],
            "reviewEvidenceRefs": interpretation["reviewEvidenceRefs"],
        }
        for field, expected in full_exact.items():
            if row.get(field) != expected:
                raise PlanningMatrixError(
                    f"criterion plan {field} drift for {binding['bindingId']}"
                )
    else:
        actual_keys = frozenset(row)
        allowed_keys = LEGACY_CRITERION_PLAN_KEYS
        contact_keys = allowed_keys | {LEGACY_CONTACT_CRITERION_EXTRA_KEY}
        if actual_keys not in {frozenset(allowed_keys), frozenset(contact_keys)}:
            raise PlanningMatrixError(
                "legacy criterion plan fields differ from the v1 adapter contract"
            )
        if LEGACY_CONTACT_CRITERION_EXTRA_KEY in row:
            if (
                interpretation["observability"] != "NOT_OBSERVABLE"
                or row[LEGACY_CONTACT_CRITERION_EXTRA_KEY]
                != "UNKNOWN_GOLD_AND_NOT_OBSERVABLE"
            ):
                raise PlanningMatrixError(
                    "legacy contact-only Gold state is invalid for this binding"
                )
        phase = interpretation["phaseApplicability"]
        phase_ids = phase["phaseRoleIds"]
        if phase["state"] != "BOUND" or len(phase_ids) != 1:
            raise PlanningMatrixError(
                "legacy criterion plan cannot represent this phase applicability exactly"
            )
        if row.get("phaseRoleId") != phase_ids[0]:
            raise PlanningMatrixError(
                f"legacy criterion plan phaseRoleId drift for {binding['bindingId']}"
            )
        side = interpretation["sidePolicy"]
        if side["roleResolverContractId"] is not None:
            raise PlanningMatrixError(
                "legacy criterion plan cannot attest a role resolver contract"
            )
        if row.get("sidePolicy") != side["kind"]:
            raise PlanningMatrixError(
                f"legacy criterion plan sidePolicy drift for {binding['bindingId']}"
            )
        view = interpretation["viewApplicability"]
        if row.get("viewContractIds") != view["viewContractIds"]:
            raise PlanningMatrixError(
                f"legacy criterion plan viewContractIds drift for {binding['bindingId']}"
            )
        inferred_view_state = (
            "QUALIFIED_VIEW_REQUIRED"
            if view["viewContractIds"]
            else "NO_CAMERA_VIEW_SUFFICIENT"
        )
        if view["state"] != inferred_view_state:
            raise PlanningMatrixError(
                "legacy criterion plan cannot represent this view applicability exactly"
            )


def _validate_plan(
    plan: Mapping[str, Any],
    registration: Mapping[str, Any],
    bindings: Sequence[Mapping[str, Any]],
    *,
    expected_provenance: Mapping[str, Any],
) -> None:
    _validate_fingerprinted_artifact(plan, "registered plan")
    if plan.get("schemaVersion") != SCHEMA_VERSION:
        raise PlanningMatrixError("registered plan has unsupported schemaVersion")
    if plan.get("artifactKind") != registration["artifactKind"]:
        raise PlanningMatrixError("registered plan artifactKind differs from registry")
    if plan.get("artifactSha256") != registration["artifactSha256"]:
        raise PlanningMatrixError("registered plan artifactSha256 differs from registry")
    if plan.get("exerciseId") != registration["exerciseId"]:
        raise PlanningMatrixError("registered plan exerciseId differs from registry")
    if plan.get("readiness") != "NOT_READY":
        raise PlanningMatrixError("registered plan readiness must remain NOT_READY")
    _validate_zero_authority(plan.get("authority"), "registered plan authority")
    provenance = _object(plan.get("policyProvenance"), "registered plan policyProvenance")
    _strict_keys(provenance, PROVENANCE_KEYS, "registered plan policyProvenance")
    if provenance != expected_provenance:
        raise PlanningMatrixError("registered plan policy provenance drift")

    reviewed = {
        binding["bindingId"]: binding
        for binding in bindings
        if binding["reviewState"] == "REVIEWED_ENGINEERING_V1"
    }
    rows = _array(plan.get("criterionPlans"), "registered plan criterionPlans")
    modern_shape = bool(rows) and all(
        isinstance(row, dict) and "phaseApplicability" in row for row in rows
    )
    legacy_shape = bool(rows) and all(
        isinstance(row, dict) and "phaseApplicability" not in row for row in rows
    )
    if not modern_shape and not legacy_shape:
        raise PlanningMatrixError(
            "registered plan criterion rows must use one supported projection shape"
        )
    seen: set[str] = set()
    for index, raw in enumerate(rows):
        row = _object(raw, f"criterionPlans[{index}]")
        key = _object(row.get("bindingKey"), f"criterionPlans[{index}].bindingKey")
        binding_id = _string(key.get("bindingId"), "criterion plan bindingId")
        if binding_id in seen:
            raise PlanningMatrixError(f"duplicate criterion plan binding {binding_id}")
        seen.add(binding_id)
        binding = reviewed.get(binding_id)
        if binding is None:
            raise PlanningMatrixError(
                f"criterion plan references an unreviewed or unknown binding {binding_id}"
            )
        _validate_projection_row(
            row,
            binding,
            policy_registry_sha256=expected_provenance["policyRegistrySha256"],
        )
    if seen != set(reviewed):
        raise PlanningMatrixError(
            "registered plan criterion binding exact-set differs from reviewed policy"
        )

    counts = _object(
        plan.get("currentActualEvidenceCounts"),
        "registered plan currentActualEvidenceCounts",
    )
    _strict_keys(
        counts,
        ACTUAL_EVIDENCE_COUNT_KEYS,
        "registered plan currentActualEvidenceCounts",
    )
    for field, value in counts.items():
        if _exact_int(value, f"currentActualEvidenceCounts.{field}") != 0:
            raise PlanningMatrixError(
                "registered plan actual evidence counts must remain zero"
            )

    if modern_shape:
        _strict_keys(plan, MODERN_PLAN_KEYS, "modern planning declaration")
        if registration["planState"] != "POLICY_PROJECTION_ONLY_NO_APPROVED_TOPOLOGY":
            raise PlanningMatrixError(
                "modern planning declaration requires projection-only planState"
            )
        if plan["artifactKind"] != "TREX_POSE_EXERCISE_GOLD_PLANNING_DECLARATION":
            raise PlanningMatrixError("unexpected modern planning declaration artifactKind")
        if plan.get("decisionUse") != (
            "POLICY_PROJECTION_ONLY_NOT_GOLD_CALIBRATION_PHASE_TOPOLOGY_RUNTIME_OR_RELEASE_AUTHORITY"
        ):
            raise PlanningMatrixError("modern planning declaration decisionUse drift")
        if plan.get("blockers") != MODERN_BLOCKERS:
            raise PlanningMatrixError("modern planning declaration blockers drift")

        interpretations = [
            _object(binding["interpretation"], "reviewed interpretation")
            for binding in reviewed.values()
        ]
        phase_role_ids = sorted(
            {
                role_id
                for interpretation in interpretations
                for role_id in interpretation["phaseApplicability"]["phaseRoleIds"]
            }
        )
        side_kinds = sorted(
            {interpretation["sidePolicy"]["kind"] for interpretation in interpretations}
        )
        role_resolvers = sorted(
            {
                resolver
                for interpretation in interpretations
                if (resolver := interpretation["sidePolicy"]["roleResolverContractId"])
                is not None
            }
        )
        view_states = sorted(
            {
                interpretation["viewApplicability"]["state"]
                for interpretation in interpretations
            }
        )
        view_ids = sorted(
            {
                view_id
                for interpretation in interpretations
                for view_id in interpretation["viewApplicability"]["viewContractIds"]
            }
        )
        capability_ids = sorted(
            {
                capability_id
                for interpretation in interpretations
                for capability_id in interpretation["requiredCapabilityIds"]
            }
        )
        expected_unions = {
            "phaseRequirements": {
                "requiredPolicyPhaseRoleIds": phase_role_ids,
                "topologyState": "NOT_DEFINED_NO_APPROVED_PHASE_GRAPH",
            },
            "sideRequirements": {
                "roleResolverContractIds": role_resolvers,
                "sidePolicyKinds": side_kinds,
            },
            "viewRequirements": {
                "viewApplicabilityStates": view_states,
                "viewContractIds": view_ids,
            },
            "capabilityRequirements": {
                "requiredCapabilityIds": capability_ids,
            },
        }
        for field, expected in expected_unions.items():
            if plan.get(field) != expected:
                raise PlanningMatrixError(
                    f"modern planning declaration {field} policy union drift"
                )
    else:
        _strict_keys(plan, LEGACY_PLAN_KEYS, "legacy Gold study plan")
        if plan["artifactKind"] != "TREX_BARBELL_SQUAT_GOLD_STUDY_PLAN":
            raise PlanningMatrixError("unexpected legacy Gold study plan artifactKind")
        if registration["planState"] != "PREREGISTERED_GOLD_STUDY_PLAN_NOT_READY":
            raise PlanningMatrixError(
                "legacy preregistration requires preregistered Gold study planState"
            )


def _counter(value: Counter[str]) -> dict[str, int]:
    return dict(sorted(value.items()))


def _set_and_counts(values: Sequence[str]) -> dict[str, Any]:
    counts = Counter(values)
    return {"ids": sorted(counts), "counts": _counter(counts)}


def _source_assignments(
    source_artifact: Mapping[str, Any],
) -> tuple[dict[str, Mapping[str, Any]], dict[tuple[str, str], Mapping[str, Any]]]:
    exercises: dict[str, Mapping[str, Any]] = {}
    assignments: dict[tuple[str, str], Mapping[str, Any]] = {}
    for raw_exercise in _array(source_artifact.get("exercises"), "source exercises"):
        exercise = _object(raw_exercise, "source exercise")
        exercise_id = _string(exercise.get("id"), "source exercise id")
        if exercise_id in exercises:
            raise PlanningMatrixError(f"duplicate source exercise {exercise_id}")
        exercises[exercise_id] = exercise
        for raw_assignment in _array(exercise.get("conditions"), "source conditions"):
            assignment = _object(raw_assignment, "source assignment")
            key = (exercise_id, _string(assignment.get("conditionId"), "conditionId"))
            if key in assignments:
                raise PlanningMatrixError(f"duplicate source assignment {key}")
            assignments[key] = assignment
    return exercises, assignments


def _binding_projection(
    binding: Mapping[str, Any], assignment: Mapping[str, Any]
) -> dict[str, Any]:
    interpretation = binding["interpretation"]
    observability = None if interpretation is None else interpretation["observability"]
    if observability not in DISPOSITIONS:
        raise PlanningMatrixError(f"unsupported observability {observability!r}")
    projection: dict[str, Any] | None
    if interpretation is None:
        projection = None
    else:
        projection = {
            "semanticId": interpretation["semanticId"],
            "semanticFamilyId": interpretation["semanticFamilyId"],
            "measurementConstructId": interpretation["measurementConstructId"],
            "claimBoundary": interpretation["claimBoundary"],
            "observability": interpretation["observability"],
            "phaseApplicability": interpretation["phaseApplicability"],
            "sidePolicy": interpretation["sidePolicy"],
            "viewApplicability": interpretation["viewApplicability"],
            "requiredCapabilityIds": interpretation["requiredCapabilityIds"],
            "calibrationProvenance": interpretation["calibrationProvenance"],
            "unsupportedReasonCodes": interpretation["unsupportedReasonCodes"],
            "reviewEvidenceRefs": interpretation["reviewEvidenceRefs"],
        }
    return {
        "bindingId": binding["bindingId"],
        "bindingPolicySha256": binding["bindingPolicySha256"],
        "sourceConditionId": binding["sourceConditionId"],
        "sourceConditionExactText": assignment["normalizedExactText"],
        "sourceOrdinal": assignment["ordinal"],
        "sourceAssignmentCounts": {
            "trueTypeCount": assignment["trueTypeCount"],
            "falseTypeCount": assignment["falseTypeCount"],
            "trueRecordCount": assignment["trueRecordCount"],
            "falseRecordCount": assignment["falseRecordCount"],
        },
        "reviewState": binding["reviewState"],
        "releaseState": binding["releaseState"],
        "reasonCodes": binding["reasonCodes"],
        "decisionEvidenceRefs": binding["decisionEvidenceRefs"],
        "planningDisposition": DISPOSITIONS[observability],
        "interpretationProjection": projection,
    }


def _exercise_summary(bindings: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    reviews: Counter[str] = Counter()
    observability: Counter[str] = Counter()
    phase_states: Counter[str] = Counter()
    phase_roles: list[str] = []
    side_kinds: list[str] = []
    role_resolvers: list[str] = []
    view_states: Counter[str] = Counter()
    views: list[str] = []
    capabilities: list[str] = []
    calibration_states: Counter[str] = Counter()
    dispositions: Counter[str] = Counter()
    for binding in bindings:
        reviews[binding["reviewState"]] += 1
        interpretation = binding["interpretation"]
        if interpretation is None:
            dispositions[DISPOSITIONS[None]] += 1
            continue
        obs = interpretation["observability"]
        observability[obs] += 1
        dispositions[DISPOSITIONS[obs]] += 1
        phase = interpretation["phaseApplicability"]
        phase_states[phase["state"]] += 1
        phase_roles.extend(phase["phaseRoleIds"])
        side = interpretation["sidePolicy"]
        side_kinds.append(side["kind"])
        if side["roleResolverContractId"] is not None:
            role_resolvers.append(side["roleResolverContractId"])
        view = interpretation["viewApplicability"]
        view_states[view["state"]] += 1
        views.extend(view["viewContractIds"])
        capabilities.extend(interpretation["requiredCapabilityIds"])
        calibration_states[interpretation["calibrationProvenance"]["state"]] += 1
    reviewed_count = reviews["REVIEWED_ENGINEERING_V1"]
    return {
        "bindingCount": len(bindings),
        "reviewedBindingCount": reviewed_count,
        "unresolvedBindingCount": len(bindings) - reviewed_count,
        "releaseEligibleBindingCount": 0,
        "reviewStateCounts": _counter(reviews),
        "observabilityCounts": _counter(observability),
        "phaseApplicabilityStateCounts": _counter(phase_states),
        "phaseRoles": _set_and_counts(phase_roles),
        "sidePolicyKinds": _set_and_counts(side_kinds),
        "roleResolverContracts": _set_and_counts(role_resolvers),
        "viewApplicabilityStateCounts": _counter(view_states),
        "viewContracts": _set_and_counts(views),
        "requiredCapabilities": _set_and_counts(capabilities),
        "calibrationStateCounts": _counter(calibration_states),
        "planningDispositionCounts": _counter(dispositions),
    }


def compile_matrix(
    *,
    source_artifact: Mapping[str, Any],
    compiled_policy: Mapping[str, Any],
    registry: Mapping[str, Any],
    plan_documents: Mapping[str, Mapping[str, Any]],
    compiler_implementation_sha256: str | None = None,
) -> dict[str, Any]:
    """Compile validated in-memory artifacts into a deterministic planning matrix."""

    _require_canonical_tree(source_artifact, "source coverage")
    _require_canonical_tree(compiled_policy, "compiled policy")
    _require_canonical_tree(registry, "planning registry")
    _strict_keys(registry, REGISTRY_KEYS, "planning registry")
    if _exact_int(registry["schemaVersion"], "registry schemaVersion") != SCHEMA_VERSION:
        raise PlanningMatrixError("unsupported planning registry schemaVersion")
    if registry["artifactKind"] != "TREX_POSE_EXERCISE_PLANNING_REGISTRY":
        raise PlanningMatrixError("unexpected planning registry artifactKind")
    _validate_fingerprinted_artifact(registry, "planning registry")
    _validate_zero_authority(registry["authority"], "planning registry authority")

    manifest = _object(compiled_policy.get("manifest"), "compiled policy manifest")
    actual_scope = {
        "exerciseCount": manifest.get("exerciseCount"),
        "exactConditionCount": manifest.get("conditionCount"),
        "bindingCount": manifest.get("bindingCount"),
        "reviewedBindingCount": manifest.get("reviewedBindingCount"),
        "releaseEligibleBindingCount": manifest.get("releaseEligibleBindingCount"),
    }
    if actual_scope != EXPECTED_SCOPE:
        raise PlanningMatrixError(
            f"compiled policy scope drift: expected={EXPECTED_SCOPE}, actual={actual_scope}"
        )
    catalog_scope = _object(registry["catalogScope"], "registry catalogScope")
    _strict_keys(catalog_scope, set(EXPECTED_SCOPE), "registry catalogScope")
    for key, value in catalog_scope.items():
        _exact_int(value, f"registry catalogScope.{key}")
    if catalog_scope != EXPECTED_SCOPE:
        raise PlanningMatrixError("registry catalogScope differs from compiled policy scope")

    expected_provenance = _expected_provenance(compiled_policy)
    provenance = _object(registry["policyProvenance"], "registry policyProvenance")
    _strict_keys(provenance, PROVENANCE_KEYS, "registry policyProvenance")
    if provenance != expected_provenance:
        raise PlanningMatrixError("registry policy provenance drift")

    bindings = list(_array(compiled_policy.get("bindings"), "compiled bindings"))
    bindings.sort(key=lambda item: (item["exerciseId"], item["sourceConditionId"]))
    by_exercise: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for raw_binding in bindings:
        binding = _object(raw_binding, "compiled binding")
        if binding.get("releaseState") != "CATALOG_ONLY":
            raise PlanningMatrixError("compiled binding releaseState must remain CATALOG_ONLY")
        by_exercise[binding["exerciseId"]].append(binding)

    source_exercises, assignments = _source_assignments(source_artifact)
    if set(source_exercises) != set(by_exercise):
        raise PlanningMatrixError("source exercise exact-set differs from compiled policy")
    expected_assignment_keys = {
        (binding["exerciseId"], binding["sourceConditionId"]) for binding in bindings
    }
    if set(assignments) != expected_assignment_keys:
        raise PlanningMatrixError("source assignment exact-set differs from compiled policy")

    registrations = _array(registry["registeredPlans"], "registeredPlans")
    registered: dict[str, dict[str, Any]] = {}
    previous_exercise_id: str | None = None
    for index, raw_registration in enumerate(registrations):
        registration = _object(raw_registration, f"registeredPlans[{index}]")
        _strict_keys(registration, REGISTRY_PLAN_KEYS, f"registeredPlans[{index}]")
        exercise_id = _string(registration["exerciseId"], "registered exerciseId")
        if previous_exercise_id is not None and exercise_id <= previous_exercise_id:
            raise PlanningMatrixError("registeredPlans must be sorted and unique by exerciseId")
        previous_exercise_id = exercise_id
        if exercise_id not in by_exercise:
            raise PlanningMatrixError(f"registered plan references unknown exercise {exercise_id}")
        path = _validate_registry_relative_path(registration["artifactPath"])
        artifact_kind = _string(registration["artifactKind"], "registered artifactKind")
        artifact_fingerprint = _string(
            registration["artifactSha256"], "registered artifactSha256"
        )
        if len(artifact_fingerprint) != 64:
            raise PlanningMatrixError("registered artifactSha256 must contain 64 hex digits")
        try:
            int(artifact_fingerprint, 16)
        except ValueError as error:
            raise PlanningMatrixError(
                "registered artifactSha256 must contain lowercase hex digits"
            ) from error
        if artifact_fingerprint.lower() != artifact_fingerprint:
            raise PlanningMatrixError(
                "registered artifactSha256 must contain lowercase hex digits"
            )
        plan_state = _string(registration["planState"], "registered planState")
        if plan_state not in PLAN_STATES:
            raise PlanningMatrixError(f"unsupported registered planState {plan_state}")
        plan = plan_documents.get(path)
        if plan is None:
            raise PlanningMatrixError(f"registered plan document not loaded: {path}")
        _validate_plan(
            plan,
            registration,
            by_exercise[exercise_id],
            expected_provenance=expected_provenance,
        )
        registered[exercise_id] = {
            "artifactKind": artifact_kind,
            "artifactPath": path,
            "artifactSha256": artifact_fingerprint,
            "planState": plan_state,
            "commonPolicyProjectionValidationState": "VERIFIED",
            "deepArtifactValidationState": (
                "OUTSIDE_MATRIX_COMPILER_REQUIRES_ARTIFACT_SPECIFIC_CHECK"
            ),
        }
    if set(plan_documents) != {
        registration["artifactPath"] for registration in registrations
    }:
        raise PlanningMatrixError("loaded plan document exact-set differs from registry")

    output_exercises: list[dict[str, Any]] = []
    planning_states: Counter[str] = Counter()
    disposition_counts: Counter[str] = Counter()
    for exercise_id in sorted(by_exercise):
        exercise_bindings = by_exercise[exercise_id]
        source_exercise = source_exercises[exercise_id]
        plan_reference = registered.get(exercise_id)
        planning_state = (
            CATALOG_POLICY_ONLY if plan_reference is None else plan_reference["planState"]
        )
        planning_states[planning_state] += 1
        projections: list[dict[str, Any]] = []
        for binding in exercise_bindings:
            key = (exercise_id, binding["sourceConditionId"])
            projection = _binding_projection(binding, assignments[key])
            projections.append(projection)
            disposition_counts[projection["planningDisposition"]] += 1
        summary = _exercise_summary(exercise_bindings)
        output_exercises.append(
            {
                "exerciseId": exercise_id,
                "sourceCatalogCounts": {
                    "recordCount": source_exercise["recordCount"],
                    "typeCount": source_exercise["typeCount"],
                    "conditionAssignmentCount": source_exercise[
                        "conditionAssignmentCount"
                    ],
                },
                "planningState": planning_state,
                "registeredPlan": plan_reference,
                "policySummary": summary,
                "bindings": projections,
                "releaseEligibleBindingCount": 0,
            }
        )

    source_manifest = _object(source_artifact.get("manifest"), "source manifest")
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
        raise PlanningMatrixError("compiler implementation SHA-256 is invalid")
    try:
        int(implementation_sha, 16)
    except ValueError as error:
        raise PlanningMatrixError("compiler implementation SHA-256 is invalid") from error

    matrix = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": "TREX_POSE_EXERCISE_PLANNING_MATRIX",
        "authority": dict(AUTHORITY_ZERO),
        "decisionUse": (
            "CATALOG_PLANNING_ONLY_NOT_GOLD_CALIBRATION_RUNTIME_SCORE_CUE_OR_RELEASE_AUTHORITY"
        ),
        "validationBoundary": {
            "commonRegisteredPlanProjection": "VERIFIED_AGAINST_COMPILED_POLICY",
            "deepRegisteredPlanSemantics": (
                "REQUIRES_SEPARATE_ARTIFACT_SPECIFIC_COMPILER_CHECK"
            ),
        },
        "policyProvenance": dict(expected_provenance),
        "planningRegistryArtifactSha256": registry["artifactSha256"],
        "compilerImplementation": {
            "relativePath": "tools/compile_pose_exercise_planning_matrix.py",
            "canonicalTextSha256": implementation_sha,
            "normalization": "UTF8_NFC_LF",
        },
        "compilerImplementationUse": (
            "IMPLEMENTATION_DRIFT_DETECTION_ONLY_NOT_APPROVAL_OR_AUTHORITY"
        ),
        "catalogScope": {
            **EXPECTED_SCOPE,
            "sourceTypeCount": source_manifest["typeCount"],
            "sourceTwoDRecordCount": source_manifest["twoDRecordCount"],
        },
        "planningManifest": {
            "registeredPlanCount": len(registered),
            "unregisteredExerciseCount": len(by_exercise) - len(registered),
            "planningStateCounts": _counter(planning_states),
            "planningDispositionCounts": _counter(disposition_counts),
        },
        "exercises": output_exercises,
    }
    return with_artifact_sha256(matrix)


def compile_from_paths(
    *,
    source_path: Path = DEFAULT_SOURCE,
    policy_path: Path = DEFAULT_POLICY,
    approval_path: Path = DEFAULT_APPROVAL,
    registry_path: Path = DEFAULT_REGISTRY,
    project_root: Path = PROJECT_ROOT,
) -> dict[str, Any]:
    source = load_json(source_path, "source coverage", project_root=project_root)
    policy = load_json(policy_path, "criterion policy", project_root=project_root)
    approval = load_json(approval_path, "criterion policy approval", project_root=project_root)
    registry = load_json(
        registry_path,
        "planning registry",
        project_root=project_root,
        require_pretty_lf=True,
    )
    try:
        compiled = compile_policy(
            source_artifact=source,
            policy=policy,
            approval=approval,
            enforce_service_pins=True,
        )
    except PolicyError as error:
        raise PlanningMatrixError(f"criterion policy compilation failed: {error}") from error

    plans: dict[str, Mapping[str, Any]] = {}
    for index, raw in enumerate(
        _array(registry.get("registeredPlans"), "registeredPlans")
    ):
        registration = _object(raw, f"registeredPlans[{index}]")
        path_text = _validate_registry_relative_path(registration.get("artifactPath"))
        if path_text in plans:
            raise PlanningMatrixError(f"duplicate registered artifactPath {path_text}")
        plans[path_text] = load_json(
            project_root / Path(PurePosixPath(path_text)),
            f"registered plan {path_text}",
            project_root=project_root,
            require_pretty_lf=True,
        )
    return compile_matrix(
        source_artifact=source,
        compiled_policy=compiled,
        registry=registry,
        plan_documents=plans,
    )


def _safe_output_path(
    path: Path,
    *,
    project_root: Path,
    input_paths: Sequence[Path],
) -> Path:
    absolute, relative = _absolute_confined(path, project_root, "output path")
    expected_relative = Path("docs") / "pose-exercise-planning-matrix.v1.json"
    if relative != expected_relative:
        raise PlanningMatrixError(
            f"output path must be the canonical generated artifact {expected_relative.as_posix()}"
        )
    for input_path in input_paths:
        input_absolute, _ = _absolute_confined(input_path, project_root, "input path")
        if absolute == input_absolute:
            raise PlanningMatrixError("output path must differ from every input path")
    parent = absolute.parent
    if not parent.exists():
        raise PlanningMatrixError("output parent must already exist")
    _assert_no_reparse_chain(parent, project_root, "output parent", require_regular=False)
    if absolute.exists():
        _assert_no_reparse_chain(
            absolute, project_root, "existing output", require_regular=True
        )
    return absolute


def write_or_check(path: Path, value: Mapping[str, Any], *, check: bool) -> None:
    rendered = render_json(value).encode("utf-8")
    if check:
        try:
            current = path.read_bytes()
        except OSError as error:
            raise PlanningMatrixError(f"cannot read existing matrix: {error}") from error
        if current != rendered:
            raise PlanningMatrixError(f"planning matrix is stale: {path}")
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
            raise PlanningMatrixError(f"atomic matrix publish failed: {error}") from error
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
    ]
    try:
        matrix = compile_from_paths(
            source_path=args.source_coverage,
            policy_path=args.policy,
            approval_path=args.policy_approval,
            registry_path=args.planning_registry,
            project_root=PROJECT_ROOT,
        )
        output = _safe_output_path(
            args.output,
            project_root=PROJECT_ROOT,
            input_paths=inputs,
        )
        write_or_check(output, matrix, check=args.check)
    except (PlanningMatrixError, OSError) as error:
        print(f"pose exercise planning matrix failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
