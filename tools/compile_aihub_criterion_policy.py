#!/usr/bin/env python3
"""Compile the curated AI Hub criterion policy into a non-executable Kotlin registry.

The policy layer interprets every exact `(exercise, source condition)` binding but grants no
runtime authority.  It deliberately excludes thresholds, feature specs, calibration artifacts,
scores, and cue text.  A separate approval JSON pins the policy; this compiler never writes or
updates that approval file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import tempfile
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable, Mapping

try:
    from .generate_aihub_exercise_catalog import ENUM_NAMES
except ImportError:  # Direct `python tools/...py` execution.
    from generate_aihub_exercise_catalog import ENUM_NAMES


SCHEMA_VERSION = 1
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = PROJECT_ROOT / "docs" / "aihub-criterion-coverage.json"
DEFAULT_POLICY = PROJECT_ROOT / "docs" / "aihub-criterion-policy.json"
DEFAULT_APPROVAL = PROJECT_ROOT / "docs" / "aihub-criterion-policy-approval.json"
DEFAULT_OUTPUT = (
    PROJECT_ROOT
    / "app/src/main/java/com/example/trex_kotlin/pose/policy/"
    / "AiHubCriterionPolicyCatalog.kt"
)

SOURCE_CONDITION_ID = re.compile(r"^aihub-exact-sha256-[0-9a-f]{64}$")
VERSIONED_ID = re.compile(r"^[a-z0-9][a-z0-9._:/-]*\.v[1-9][0-9]*$")
REASON_CODE = re.compile(r"^[A-Z][A-Z0-9_]*$")
EVIDENCE_REF = re.compile(
    r"^(?P<artifact>[a-z0-9][a-z0-9._/-]*)@sha256:(?P<sha256>[0-9a-f]{64})$"
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")

REVIEW_STATES = {
    "REVIEWED_ENGINEERING_V1",
    "SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION",
    "UNREVIEWED",
}
OBSERVABILITY = {
    "DIRECT",
    "PROXY_UNVALIDATED",
    "PROXY_GOLD_VALIDATED",
    "NOT_OBSERVABLE",
}
PHASE_STATES = {"BOUND", "NOT_APPLICABLE"}
SIDE_KINDS = {
    "MIDLINE",
    "GLOBAL_BODY",
    "BILATERAL_COUPLED",
    "BILATERAL_INDEPENDENT",
    "ACTIVE_LIMB",
    "LEAD_LIMB",
    "TRAIL_LIMB",
    "ALTERNATING_PAIR",
    "CONTRALATERAL_PAIR",
    "NOT_APPLICABLE",
}
ROLE_RELATIVE_SIDES = {
    "ACTIVE_LIMB",
    "LEAD_LIMB",
    "TRAIL_LIMB",
    "ALTERNATING_PAIR",
    "CONTRALATERAL_PAIR",
}
VIEW_STATES = {
    "QUALIFIED_VIEW_REQUIRED",
    "NO_CAMERA_VIEW_SUFFICIENT",
    "NOT_APPLICABLE",
}
CAMERA_OBSERVABILITY = {"DIRECT", "PROXY_UNVALIDATED"}
PERSON_LOCK_CAPABILITY = "trex.capability.primary-person-lock.v1"
VIEW_QUALIFIED_CAPABILITY = "trex.capability.view-qualified.v1"

POLICY_KEYS = {
    "schemaVersion",
    "artifactKind",
    "authority",
    "sourceCoverage",
    "bindings",
}
SOURCE_COVERAGE_KEYS = {
    "catalogSha256",
    "coverageArtifactSha256",
    "metadataSetSha256",
}
BINDING_KEYS = {
    "exerciseId",
    "sourceConditionId",
    "reviewState",
    "releaseState",
    "reasonCodes",
    "decisionEvidenceRefs",
    "interpretation",
}
INTERPRETATION_KEYS = {
    "semanticId",
    "semanticFamilyId",
    "measurementConstructId",
    "claimBoundary",
    "observability",
    "phaseApplicability",
    "sidePolicy",
    "viewApplicability",
    "requiredCapabilityIds",
    "calibrationProvenance",
    "unsupportedReasonCodes",
    "reviewEvidenceRefs",
}
PHASE_KEYS = {"state", "phaseRoleIds"}
SIDE_KEYS = {"kind", "roleResolverContractId"}
VIEW_KEYS = {"state", "viewContractIds"}
CALIBRATION_KEYS = {"state", "artifactSha256", "runtimeDomainId", "evidenceRefs"}
APPROVAL_KEYS = {
    "schemaVersion",
    "artifactKind",
    "authority",
    "approvedSourceCoverageArtifactSha256",
    "approvedPolicySha256",
    "approvedReviewedBindingSetSha256",
    "approvedReviewedBindingCount",
    "approvalScope",
}
SOURCE_ARTIFACT_KEYS = {
    "schemaVersion",
    "artifactKind",
    "authority",
    "sourceProvenance",
    "manifest",
    "conditionRegistry",
    "exercises",
    "labelQuarantine",
    "artifactSha256",
}
SOURCE_PROVENANCE_KEYS = {
    "dataset",
    "catalog",
    "twoDMetadataAudit",
    "quarantineRegistry",
}
SOURCE_CATALOG_KEYS = {
    "path",
    "schemaVersion",
    "catalogSha256",
    "canonicalTextFileSha256",
}
SOURCE_METADATA_AUDIT_KEYS = {
    "sourceRoot",
    "scope",
    "excluded",
    "textIdentity",
    "metadataSetSha256",
}
SOURCE_MANIFEST_KEYS = {
    "exerciseCount",
    "typeCount",
    "twoDRecordCount",
    "exactConditionCount",
    "exerciseConditionAssignmentCount",
    "truthVectorCollisionExerciseCount",
    "truthVectorCollisionGroupCount",
    "truthVectorCollisionTypeCount",
    "truthVectorExcessTypeCount",
    "quarantinedTypeCount",
    "quarantinedRecordCount",
}
SOURCE_CONDITION_KEYS = {
    "id",
    "normalizedExactText",
    "rawTextAliases",
    "exerciseIds",
    "exerciseAssignmentCount",
    "typeOccurrenceCount",
    "trueRecordCount",
    "falseRecordCount",
    "semanticAliasPolicy",
}
SOURCE_EXERCISE_KEYS = {
    "id",
    "normalizedSourceName",
    "rawSourceNameAliases",
    "recordCount",
    "typeCount",
    "conditionAssignmentCount",
    "conditions",
    "types",
    "truthVectorCollisionGroups",
}
SOURCE_ASSIGNMENT_KEYS = {
    "ordinal",
    "conditionId",
    "normalizedExactText",
    "rawTextAliases",
    "trueTypeCount",
    "falseTypeCount",
    "trueRecordCount",
    "falseRecordCount",
}


class PolicyError(RuntimeError):
    """Raised when source, policy, approval, or generated output is unsafe."""


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def canonical_json_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def canonical_fields_sha256(fields: Iterable[tuple[str, str]]) -> str:
    payload = "".join(
        f"{name}:{len(value.encode('utf-8'))}:{value}\n" for name, value in fields
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def canonical_text_file_sha256(path: Path) -> str:
    """Hash UTF-8 text with platform line endings normalized to LF."""

    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise PolicyError(f"Cannot read evidence artifact {path}: {error}") from error
    canonical = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_fingerprinted_artifact(artifact: Mapping[str, Any]) -> bool:
    fingerprint = artifact.get("artifactSha256")
    if not isinstance(fingerprint, str):
        return False
    unsigned = dict(artifact)
    unsigned.pop("artifactSha256", None)
    return canonical_json_sha256(unsigned) == fingerprint


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PolicyError(f"{label} must be an object")
    return value


def _strict_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise PolicyError(
            f"{label} fields differ: missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PolicyError(f"{label} must be a non-empty string")
    if unicodedata.normalize("NFC", value) != value:
        raise PolicyError(f"{label} must use Unicode NFC")
    return value


def _nullable_string(value: Any, label: str) -> str | None:
    if value is None:
        return None
    return _string(value, label)


def _string_list(
    value: Any,
    label: str,
    *,
    pattern: re.Pattern[str] | None = None,
    allow_empty: bool = True,
) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise PolicyError(f"{label} must be an array of strings")
    if not allow_empty and not value:
        raise PolicyError(f"{label} must not be empty")
    if value != sorted(set(value)):
        raise PolicyError(f"{label} must be sorted and unique")
    if pattern is not None and any(pattern.fullmatch(item) is None for item in value):
        raise PolicyError(f"{label} contains an invalid value")
    return list(value)


def _enum(value: Any, allowed: set[str], label: str) -> str:
    text = _string(value, label)
    if text not in allowed:
        raise PolicyError(f"{label} has unsupported value {text!r}")
    return text


def _exact_int(value: Any, label: str) -> int:
    if type(value) is not int:  # bool is an int subclass and must not satisfy JSON schemas.
        raise PolicyError(f"{label} must be an integer")
    return value


def _require_canonical_json_tree(value: Any, label: str) -> None:
    if isinstance(value, str):
        if unicodedata.normalize("NFC", value) != value:
            raise PolicyError(f"{label} contains a non-NFC string")
        if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise PolicyError(f"{label} contains an unpaired Unicode surrogate")
        return
    if isinstance(value, Mapping):
        for key, item in value.items():
            if not isinstance(key, str):
                raise PolicyError(f"{label} contains a non-string object key")
            _require_canonical_json_tree(key, f"{label} key")
            _require_canonical_json_tree(item, f"{label}.{key}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _require_canonical_json_tree(item, f"{label}[{index}]")
        return
    if isinstance(value, float) and not math.isfinite(value):
        raise PolicyError(f"{label} contains a non-finite number")


def _reject_duplicate_object_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PolicyError(f"JSON contains duplicate object key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite_json_constant(value: str) -> None:
    raise PolicyError(f"JSON contains non-finite number {value}")


def _load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_object_keys,
            parse_constant=_reject_nonfinite_json_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PolicyError(f"Cannot read {label} {path}: {error}") from error
    return _object(value, label)


def binding_id(exercise_id: str, source_condition_id: str) -> str:
    return "aihub-binding-sha256-" + canonical_fields_sha256(
        [
            ("bindingIdSchemaVersion", "1"),
            ("exerciseId", exercise_id),
            ("sourceConditionId", source_condition_id),
        ]
    )


def _append_list(
    fields: list[tuple[str, str]],
    field_name: str,
    values: list[str],
) -> None:
    fields.append((f"{field_name}Count", str(len(values))))
    fields.extend((f"{field_name}[{index}]", value) for index, value in enumerate(values))


def binding_policy_sha256(binding: Mapping[str, Any]) -> str:
    fields: list[tuple[str, str]] = [
        ("bindingPolicySchemaVersion", "1"),
        ("bindingId", binding["bindingId"]),
        ("exerciseId", binding["exerciseId"]),
        ("sourceConditionId", binding["sourceConditionId"]),
        ("reviewState", binding["reviewState"]),
        ("releaseState", binding["releaseState"]),
    ]
    _append_list(fields, "reasonCode", binding["reasonCodes"])
    _append_list(fields, "decisionEvidenceRef", binding["decisionEvidenceRefs"])
    interpretation = binding["interpretation"]
    fields.append(("interpretationPresent", str(interpretation is not None).lower()))
    if interpretation is not None:
        fields.extend(
            [
                ("semanticId", interpretation["semanticId"]),
                ("semanticFamilyId", interpretation["semanticFamilyId"]),
                ("measurementConstructId", interpretation["measurementConstructId"]),
                ("claimBoundary", interpretation["claimBoundary"]),
                ("observability", interpretation["observability"]),
                (
                    "phaseApplicabilityState",
                    interpretation["phaseApplicability"]["state"],
                ),
            ]
        )
        _append_list(
            fields,
            "phaseRoleId",
            interpretation["phaseApplicability"]["phaseRoleIds"],
        )
        fields.extend(
            [
                ("sidePolicyKind", interpretation["sidePolicy"]["kind"]),
                (
                    "roleResolverContractId",
                    interpretation["sidePolicy"]["roleResolverContractId"] or "",
                ),
                (
                    "viewApplicabilityState",
                    interpretation["viewApplicability"]["state"],
                ),
            ]
        )
        _append_list(
            fields,
            "viewContractId",
            interpretation["viewApplicability"]["viewContractIds"],
        )
        _append_list(
            fields,
            "requiredCapabilityId",
            interpretation["requiredCapabilityIds"],
        )
        calibration = interpretation["calibrationProvenance"]
        fields.extend(
            [
                ("calibrationState", calibration["state"]),
                ("calibrationArtifactSha256", calibration["artifactSha256"] or ""),
                ("calibrationRuntimeDomainId", calibration["runtimeDomainId"] or ""),
            ]
        )
        _append_list(fields, "calibrationEvidenceRef", calibration["evidenceRefs"])
        _append_list(
            fields,
            "unsupportedReasonCode",
            interpretation["unsupportedReasonCodes"],
        )
        _append_list(
            fields,
            "reviewEvidenceRef",
            interpretation["reviewEvidenceRefs"],
        )
    return canonical_fields_sha256(fields)


def policy_decision_sha256(
    source: Mapping[str, str],
    bindings: list[Mapping[str, Any]],
) -> str:
    fields: list[tuple[str, str]] = [
        ("policySchemaVersion", str(SCHEMA_VERSION)),
        ("sourceCatalogSha256", source["catalogSha256"]),
        ("sourceCoverageArtifactSha256", source["coverageArtifactSha256"]),
        ("sourceMetadataSetSha256", source["metadataSetSha256"]),
        ("bindingCount", str(len(bindings))),
    ]
    for index, binding in enumerate(bindings):
        fields.append((f"binding[{index}].id", binding["bindingId"]))
        fields.append((f"binding[{index}].policySha256", binding["bindingPolicySha256"]))
    return canonical_fields_sha256(fields)


def reviewed_binding_set_sha256(bindings: list[Mapping[str, Any]]) -> str:
    reviewed = [
        binding
        for binding in bindings
        if binding["reviewState"] == "REVIEWED_ENGINEERING_V1"
    ]
    fields: list[tuple[str, str]] = [("reviewedBindingCount", str(len(reviewed)))]
    for index, binding in enumerate(reviewed):
        fields.append((f"binding[{index}].id", binding["bindingId"]))
        fields.append((f"binding[{index}].policySha256", binding["bindingPolicySha256"]))
    return canonical_fields_sha256(fields)


def registry_sha256(
    source: Mapping[str, str],
    policy_sha256: str,
    approval_artifact_sha256: str,
) -> str:
    return canonical_fields_sha256(
        [
            ("registrySchemaVersion", str(SCHEMA_VERSION)),
            ("sourceCatalogSha256", source["catalogSha256"]),
            ("sourceCoverageArtifactSha256", source["coverageArtifactSha256"]),
            ("sourceMetadataSetSha256", source["metadataSetSha256"]),
            ("policySha256", policy_sha256),
            ("approvalArtifactSha256", approval_artifact_sha256),
        ]
    )


def _source_index(
    source_artifact: Mapping[str, Any],
    *,
    enforce_service_pins: bool,
) -> tuple[dict[str, str], set[tuple[str, str]], set[str]]:
    _strict_keys(source_artifact, SOURCE_ARTIFACT_KEYS, "source coverage artifact")
    if not verify_fingerprinted_artifact(source_artifact):
        raise PolicyError("Source coverage artifact fingerprint mismatch")
    if _exact_int(source_artifact.get("schemaVersion"), "source schemaVersion") != 1:
        raise PolicyError("Unsupported source coverage schemaVersion")
    if source_artifact.get("artifactKind") != "AIHUB_CRITERION_COVERAGE":
        raise PolicyError("Unexpected source coverage artifactKind")
    if (
        source_artifact.get("authority")
        != "CATALOG_AND_LABEL_PROVENANCE_ONLY_NOT_RUNTIME_RELEASE"
    ):
        raise PolicyError("Source coverage authority must remain non-runtime")
    provenance = _object(source_artifact.get("sourceProvenance"), "sourceProvenance")
    _strict_keys(provenance, SOURCE_PROVENANCE_KEYS, "sourceProvenance")
    catalog = _object(provenance.get("catalog"), "source catalog provenance")
    _strict_keys(catalog, SOURCE_CATALOG_KEYS, "source catalog provenance")
    if _exact_int(catalog.get("schemaVersion"), "source catalog schemaVersion") != 1:
        raise PolicyError("Unsupported source catalog schemaVersion")
    metadata = _object(provenance.get("twoDMetadataAudit"), "metadata provenance")
    _strict_keys(metadata, SOURCE_METADATA_AUDIT_KEYS, "metadata provenance")
    manifest = _object(source_artifact.get("manifest"), "source manifest")
    _strict_keys(manifest, SOURCE_MANIFEST_KEYS, "source manifest")
    for key, value in manifest.items():
        if _exact_int(value, f"source manifest {key}") < 0:
            raise PolicyError(f"source manifest {key} must be non-negative")
    source = {
        "catalogSha256": _string(catalog.get("catalogSha256"), "catalogSha256"),
        "coverageArtifactSha256": _string(
            source_artifact.get("artifactSha256"), "coverageArtifactSha256"
        ),
        "metadataSetSha256": _string(metadata.get("metadataSetSha256"), "metadataSetSha256"),
    }
    if any(SHA256.fullmatch(value) is None for value in source.values()):
        raise PolicyError("Source provenance contains an invalid SHA-256")

    condition_rows = source_artifact.get("conditionRegistry")
    exercise_rows = source_artifact.get("exercises")
    if not isinstance(condition_rows, list) or not isinstance(exercise_rows, list):
        raise PolicyError("Source coverage arrays are missing")
    condition_ids: set[str] = set()
    condition_text_by_id: dict[str, str] = {}
    for row in condition_rows:
        condition = _object(row, "source condition")
        _strict_keys(condition, SOURCE_CONDITION_KEYS, "source condition")
        condition_id = _string(condition.get("id"), "source condition id")
        if SOURCE_CONDITION_ID.fullmatch(condition_id) is None or condition_id in condition_ids:
            raise PolicyError("Source condition ids are invalid or duplicated")
        normalized_text = _string(
            condition.get("normalizedExactText"), "source normalized exact text"
        )
        expected_condition_id = "aihub-exact-sha256-" + hashlib.sha256(
            normalized_text.encode("utf-8")
        ).hexdigest()
        if condition_id != expected_condition_id:
            raise PolicyError("Source condition id differs from normalized exact text")
        condition_ids.add(condition_id)
        condition_text_by_id[condition_id] = normalized_text

    expected_bindings: set[tuple[str, str]] = set()
    referenced_condition_ids: set[str] = set()
    exercise_ids: set[str] = set()
    for row in exercise_rows:
        exercise = _object(row, "source exercise")
        _strict_keys(exercise, SOURCE_EXERCISE_KEYS, "source exercise")
        exercise_id = _string(exercise.get("id"), "source exercise id")
        if exercise_id not in ENUM_NAMES or exercise_id in exercise_ids:
            raise PolicyError(f"Unknown or duplicate source exercise {exercise_id!r}")
        exercise_ids.add(exercise_id)
        conditions = exercise.get("conditions")
        if not isinstance(conditions, list):
            raise PolicyError(f"Exercise {exercise_id} conditions must be an array")
        for expected_ordinal, condition_row in enumerate(conditions):
            condition = _object(condition_row, f"{exercise_id} condition")
            _strict_keys(
                condition,
                SOURCE_ASSIGNMENT_KEYS,
                f"{exercise_id} condition",
            )
            ordinal = _exact_int(condition.get("ordinal"), "condition ordinal")
            if ordinal != expected_ordinal:
                raise PolicyError(f"Exercise {exercise_id} condition ordinals are not canonical")
            condition_id = _string(condition.get("conditionId"), "conditionId")
            if condition_id not in condition_ids:
                raise PolicyError(f"Exercise {exercise_id} references an unknown condition")
            if condition.get("normalizedExactText") != condition_text_by_id[condition_id]:
                raise PolicyError(
                    f"Exercise {exercise_id} condition text differs from the registry"
                )
            key = (exercise_id, condition_id)
            if key in expected_bindings:
                raise PolicyError(f"Duplicate source assignment {key}")
            expected_bindings.add(key)
            referenced_condition_ids.add(condition_id)

    if referenced_condition_ids != condition_ids:
        raise PolicyError(
            "Source condition exact-set differs from exercise assignments: "
            f"orphan={len(condition_ids - referenced_condition_ids)}"
        )
    actual_manifest_counts = {
        "exerciseCount": len(exercise_ids),
        "exactConditionCount": len(condition_ids),
        "exerciseConditionAssignmentCount": len(expected_bindings),
    }
    for key, actual in actual_manifest_counts.items():
        if manifest[key] != actual:
            raise PolicyError(
                f"Source manifest {key} differs: expected={actual}, actual={manifest[key]}"
            )

    if enforce_service_pins:
        expected = (41, 97, 167)
        actual = (len(exercise_ids), len(condition_ids), len(expected_bindings))
        if actual != expected:
            raise PolicyError(f"Service source pins differ: expected={expected}, actual={actual}")
    return source, expected_bindings, condition_ids


def _validate_interpretation(
    value: Any,
    *,
    source_condition_id: str,
) -> dict[str, Any]:
    interpretation = _object(value, "interpretation")
    _strict_keys(interpretation, INTERPRETATION_KEYS, "interpretation")
    semantic_id = _string(interpretation["semanticId"], "semanticId")
    condition_digest = source_condition_id.removeprefix("aihub-exact-sha256-")
    if semantic_id != f"aihub.condition.exact.{condition_digest}.v1":
        raise PolicyError("semanticId must retain exact source condition identity")
    semantic_family_id = _string(interpretation["semanticFamilyId"], "semanticFamilyId")
    construct_id = _string(interpretation["measurementConstructId"], "measurementConstructId")
    if any(
        VERSIONED_ID.fullmatch(item) is None
        for item in (semantic_id, semantic_family_id, construct_id)
    ):
        raise PolicyError("Interpretation ids must be lowercase and versioned")
    claim_boundary = _string(interpretation["claimBoundary"], "claimBoundary")
    observability = _enum(interpretation["observability"], OBSERVABILITY, "observability")

    phase = _object(interpretation["phaseApplicability"], "phaseApplicability")
    _strict_keys(phase, PHASE_KEYS, "phaseApplicability")
    phase_state = _enum(phase["state"], PHASE_STATES, "phase state")
    phase_ids = _string_list(
        phase["phaseRoleIds"], "phaseRoleIds", pattern=VERSIONED_ID
    )
    if phase_state != "BOUND" or not phase_ids:
        raise PolicyError("Reviewed interpretation must bind a generic phase role")

    side = _object(interpretation["sidePolicy"], "sidePolicy")
    _strict_keys(side, SIDE_KEYS, "sidePolicy")
    side_kind = _enum(side["kind"], SIDE_KINDS, "side kind")
    resolver = _nullable_string(side["roleResolverContractId"], "roleResolverContractId")
    if side_kind in ROLE_RELATIVE_SIDES:
        if resolver is None or VERSIONED_ID.fullmatch(resolver) is None:
            raise PolicyError(f"{side_kind} requires a versioned role resolver")
    elif resolver is not None:
        raise PolicyError(f"{side_kind} cannot declare a role resolver")
    if side_kind == "NOT_APPLICABLE":
        raise PolicyError("Reviewed interpretation requires an explicit side policy")

    view = _object(interpretation["viewApplicability"], "viewApplicability")
    _strict_keys(view, VIEW_KEYS, "viewApplicability")
    view_state = _enum(view["state"], VIEW_STATES, "view state")
    view_ids = _string_list(view["viewContractIds"], "viewContractIds", pattern=VERSIONED_ID)
    if view_state == "QUALIFIED_VIEW_REQUIRED" and not view_ids:
        raise PolicyError("Qualified view policy must include candidate view contracts")
    if view_state != "QUALIFIED_VIEW_REQUIRED" and view_ids:
        raise PolicyError(f"{view_state} cannot include camera view contracts")

    capability_ids = _string_list(
        interpretation["requiredCapabilityIds"],
        "requiredCapabilityIds",
        pattern=VERSIONED_ID,
    )
    if observability in CAMERA_OBSERVABILITY:
        if view_state != "QUALIFIED_VIEW_REQUIRED":
            raise PolicyError("Camera observability requires a qualified view")
        if PERSON_LOCK_CAPABILITY not in capability_ids:
            raise PolicyError("Camera observability requires primary-person lock")
        if VIEW_QUALIFIED_CAPABILITY not in capability_ids:
            raise PolicyError("Camera observability requires view qualification")
    if observability == "NOT_OBSERVABLE" and view_state != "NO_CAMERA_VIEW_SUFFICIENT":
        raise PolicyError("Non-observable construct must reject all camera views")

    calibration = _object(
        interpretation["calibrationProvenance"], "calibrationProvenance"
    )
    _strict_keys(calibration, CALIBRATION_KEYS, "calibrationProvenance")
    if calibration["state"] != "NO_APPROVED_ARTIFACT":
        raise PolicyError("This catalog slice accepts no calibration artifact")
    if calibration["artifactSha256"] is not None or calibration["runtimeDomainId"] is not None:
        raise PolicyError("NO_APPROVED_ARTIFACT must have null artifact and runtime domain")
    calibration_evidence = _string_list(
        calibration["evidenceRefs"],
        "calibration evidenceRefs",
        pattern=EVIDENCE_REF,
        allow_empty=False,
    )
    if observability == "PROXY_GOLD_VALIDATED":
        raise PolicyError("PROXY_GOLD_VALIDATED requires a separate approved release artifact")

    unsupported = _string_list(
        interpretation["unsupportedReasonCodes"],
        "unsupportedReasonCodes",
        pattern=REASON_CODE,
        allow_empty=False,
    )
    review_evidence = _string_list(
        interpretation["reviewEvidenceRefs"],
        "reviewEvidenceRefs",
        pattern=EVIDENCE_REF,
        allow_empty=False,
    )
    return {
        "semanticId": semantic_id,
        "semanticFamilyId": semantic_family_id,
        "measurementConstructId": construct_id,
        "claimBoundary": claim_boundary,
        "observability": observability,
        "phaseApplicability": {"state": phase_state, "phaseRoleIds": phase_ids},
        "sidePolicy": {"kind": side_kind, "roleResolverContractId": resolver},
        "viewApplicability": {"state": view_state, "viewContractIds": view_ids},
        "requiredCapabilityIds": capability_ids,
        "calibrationProvenance": {
            "state": "NO_APPROVED_ARTIFACT",
            "artifactSha256": None,
            "runtimeDomainId": None,
            "evidenceRefs": calibration_evidence,
        },
        "unsupportedReasonCodes": unsupported,
        "reviewEvidenceRefs": review_evidence,
    }


def compile_policy(
    *,
    source_artifact: Mapping[str, Any],
    policy: Mapping[str, Any],
    approval: Mapping[str, Any] | None,
    enforce_service_pins: bool = True,
) -> dict[str, Any]:
    _require_canonical_json_tree(source_artifact, "source coverage")
    _require_canonical_json_tree(policy, "policy")
    if approval is not None:
        _require_canonical_json_tree(approval, "approval")
    source, expected_bindings, condition_ids = _source_index(
        source_artifact, enforce_service_pins=enforce_service_pins
    )
    _strict_keys(policy, POLICY_KEYS, "policy")
    if _exact_int(policy["schemaVersion"], "policy schemaVersion") != SCHEMA_VERSION:
        raise PolicyError("Unsupported policy schemaVersion")
    if policy["artifactKind"] != "AIHUB_CURATED_CRITERION_POLICY":
        raise PolicyError("Unexpected policy artifactKind")
    if policy["authority"] != "CATALOG_ONLY_NOT_RUNTIME_RELEASE":
        raise PolicyError("Policy authority must remain catalog-only")
    policy_source = _object(policy["sourceCoverage"], "policy sourceCoverage")
    _strict_keys(policy_source, SOURCE_COVERAGE_KEYS, "policy sourceCoverage")
    if policy_source != source:
        raise PolicyError("Policy source provenance differs from source coverage")
    rows = policy["bindings"]
    if not isinstance(rows, list):
        raise PolicyError("policy bindings must be an array")

    compiled: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for index, raw in enumerate(rows):
        row = _object(raw, f"binding[{index}]")
        _strict_keys(row, BINDING_KEYS, f"binding[{index}]")
        exercise_id = _string(row["exerciseId"], "exerciseId")
        condition_id = _string(row["sourceConditionId"], "sourceConditionId")
        if exercise_id not in ENUM_NAMES or condition_id not in condition_ids:
            raise PolicyError(f"Binding {index} references an unknown source identity")
        key = (exercise_id, condition_id)
        if key in seen:
            raise PolicyError(f"Duplicate policy binding {key}")
        seen.add(key)
        review_state = _enum(row["reviewState"], REVIEW_STATES, "reviewState")
        if row["releaseState"] != "CATALOG_ONLY":
            raise PolicyError("Policy binding releaseState must remain CATALOG_ONLY")
        reason_codes = _string_list(
            row["reasonCodes"], "reasonCodes", pattern=REASON_CODE
        )
        decision_evidence = _string_list(
            row["decisionEvidenceRefs"],
            "decisionEvidenceRefs",
            pattern=EVIDENCE_REF,
            allow_empty=False,
        )
        if review_state == "REVIEWED_ENGINEERING_V1":
            if reason_codes:
                raise PolicyError("Reviewed binding cannot contain unresolved reasonCodes")
            interpretation = _validate_interpretation(
                row["interpretation"], source_condition_id=condition_id
            )
        else:
            if row["interpretation"] is not None:
                raise PolicyError(f"{review_state} cannot carry an interpretation")
            if not reason_codes:
                raise PolicyError(f"{review_state} requires reasonCodes")
            interpretation = None
        result = {
            "bindingId": binding_id(exercise_id, condition_id),
            "exerciseId": exercise_id,
            "sourceConditionId": condition_id,
            "reviewState": review_state,
            "releaseState": "CATALOG_ONLY",
            "reasonCodes": reason_codes,
            "decisionEvidenceRefs": decision_evidence,
            "interpretation": interpretation,
        }
        result["bindingPolicySha256"] = binding_policy_sha256(result)
        compiled.append(result)

    if seen != expected_bindings:
        raise PolicyError(
            "Policy binding exact-set differs from source coverage: "
            f"missing={len(expected_bindings - seen)}, unexpected={len(seen - expected_bindings)}"
        )
    compiled.sort(key=lambda item: (item["exerciseId"], item["sourceConditionId"]))

    semantic_by_condition: dict[str, set[tuple[str, str, str]]] = defaultdict(set)
    for binding in compiled:
        interpretation = binding["interpretation"]
        if interpretation is not None:
            semantic_by_condition[binding["sourceConditionId"]].add(
                (
                    interpretation["semanticId"],
                    interpretation["semanticFamilyId"],
                    interpretation["measurementConstructId"],
                )
            )
    if any(len(values) != 1 for values in semantic_by_condition.values()):
        raise PolicyError("One exact condition changes semantic identity across exercises")

    policy_sha = policy_decision_sha256(source, compiled)
    reviewed_set_sha = reviewed_binding_set_sha256(compiled)
    reviewed_count = sum(
        binding["reviewState"] == "REVIEWED_ENGINEERING_V1" for binding in compiled
    )
    approval_sha: str | None = None
    combined_registry_sha: str | None = None
    if approval is not None:
        _strict_keys(approval, APPROVAL_KEYS, "approval")
        if _exact_int(approval["schemaVersion"], "approval schemaVersion") != SCHEMA_VERSION:
            raise PolicyError("Unsupported approval schemaVersion")
        if approval["artifactKind"] != "AIHUB_CURATED_CRITERION_POLICY_APPROVAL":
            raise PolicyError("Unexpected approval artifactKind")
        if approval["authority"] != "REPOSITORY_PIN_NOT_RUNTIME_RELEASE":
            raise PolicyError("Approval authority must remain an unauthenticated repository pin")
        if approval["approvalScope"] != "CATALOG_ONLY":
            raise PolicyError("Approval scope must remain CATALOG_ONLY")
        approved_reviewed_count = _exact_int(
            approval["approvedReviewedBindingCount"],
            "approvedReviewedBindingCount",
        )
        expected_approval = {
            "approvedSourceCoverageArtifactSha256": source["coverageArtifactSha256"],
            "approvedPolicySha256": policy_sha,
            "approvedReviewedBindingSetSha256": reviewed_set_sha,
            "approvedReviewedBindingCount": reviewed_count,
        }
        approval = dict(approval)
        approval["approvedReviewedBindingCount"] = approved_reviewed_count
        for field, expected in expected_approval.items():
            if approval[field] != expected:
                raise PolicyError(
                    f"Approval pin mismatch for {field}: expected={expected}, "
                    f"actual={approval[field]}"
                )
        approval_sha = canonical_json_sha256(approval)
        combined_registry_sha = registry_sha256(source, policy_sha, approval_sha)

    counts = Counter(binding["reviewState"] for binding in compiled)
    observability_counts = Counter(
        binding["interpretation"]["observability"]
        for binding in compiled
        if binding["interpretation"] is not None
    )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "sourceCoverage": source,
        "bindings": compiled,
        "policySha256": policy_sha,
        "reviewedBindingSetSha256": reviewed_set_sha,
        "approvalArtifactSha256": approval_sha,
        "registrySha256": combined_registry_sha,
        "manifest": {
            "exerciseCount": len({binding["exerciseId"] for binding in compiled}),
            "conditionCount": len(condition_ids),
            "bindingCount": len(compiled),
            "reviewedBindingCount": reviewed_count,
            "reviewStateCounts": dict(sorted(counts.items())),
            "observabilityCounts": dict(sorted(observability_counts.items())),
            "releaseEligibleBindingCount": 0,
        },
    }


def verify_repository_evidence_refs(
    compiled: Mapping[str, Any],
    *,
    project_root: Path = PROJECT_ROOT,
) -> None:
    """Verify path-backed evidence without making pure policy compilation filesystem-bound.

    This compiler slice only admits repository text artifacts under ``docs/``. External
    evidence needs a separately implemented resolver and trust policy before it can be used.
    """

    evidence_refs: set[str] = set()
    for binding in compiled["bindings"]:
        evidence_refs.update(binding["decisionEvidenceRefs"])
        interpretation = binding["interpretation"]
        if interpretation is None:
            continue
        evidence_refs.update(interpretation["reviewEvidenceRefs"])
        evidence_refs.update(interpretation["calibrationProvenance"]["evidenceRefs"])

    resolved_root = project_root.resolve()
    resolved_docs_root = (resolved_root / "docs").resolve()
    for evidence_ref in sorted(evidence_refs):
        match = EVIDENCE_REF.fullmatch(evidence_ref)
        if match is None:  # Compile-time schema validation should already make this unreachable.
            raise PolicyError(f"Invalid evidence ref {evidence_ref!r}")
        artifact_id = match.group("artifact")
        if not artifact_id.startswith("docs/"):
            raise PolicyError(
                f"No verified evidence resolver exists for artifact {artifact_id!r}"
            )
        artifact_parts = artifact_id.split("/")
        if artifact_parts[0] != "docs" or any(
            part in {"", ".", ".."} for part in artifact_parts
        ):
            raise PolicyError(f"Evidence path is not canonical under docs/: {artifact_id}")
        evidence_path = (resolved_root / artifact_id).resolve()
        try:
            evidence_path.relative_to(resolved_docs_root)
        except ValueError as error:
            raise PolicyError(f"Evidence path escapes docs/: {artifact_id}") from error
        actual_sha256 = canonical_text_file_sha256(evidence_path)
        expected_sha256 = match.group("sha256")
        if actual_sha256 != expected_sha256:
            raise PolicyError(
                f"Evidence artifact drift for {artifact_id}: "
                f"expected={expected_sha256}, actual={actual_sha256}"
            )


def approval_draft(compiled: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": "AIHUB_CURATED_CRITERION_POLICY_APPROVAL",
        "authority": "REPOSITORY_PIN_NOT_RUNTIME_RELEASE",
        "approvedSourceCoverageArtifactSha256": compiled["sourceCoverage"][
            "coverageArtifactSha256"
        ],
        "approvedPolicySha256": compiled["policySha256"],
        "approvedReviewedBindingSetSha256": compiled["reviewedBindingSetSha256"],
        "approvedReviewedBindingCount": compiled["manifest"]["reviewedBindingCount"],
        "approvalScope": "CATALOG_ONLY",
    }


def _kotlin_string(value: str) -> str:
    escaped: list[str] = []
    simple_escapes = {
        "\\": "\\\\",
        '"': '\\"',
        "\t": "\\t",
        "\b": "\\b",
        "\n": "\\n",
        "\r": "\\r",
        "$": "\\$",
    }
    for character in value:
        replacement = simple_escapes.get(character)
        if replacement is not None:
            escaped.append(replacement)
        elif ord(character) < 0x20:
            escaped.append(f"\\u{ord(character):04x}")
        else:
            escaped.append(character)
    return '"' + "".join(escaped) + '"'


def _kotlin_nullable(value: str | None) -> str:
    return "null" if value is None else _kotlin_string(value)


def _kotlin_list(values: Iterable[str]) -> str:
    return "listOf(" + ", ".join(_kotlin_string(value) for value in values) + ")"


def _factory_name(exercise_id: str) -> str:
    parts = exercise_id.split("-")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:]) + "Bindings"


def render_kotlin(compiled: Mapping[str, Any]) -> str:
    if compiled.get("approvalArtifactSha256") is None or compiled.get("registrySha256") is None:
        raise PolicyError("Approved policy is required before Kotlin can be generated")
    source = compiled["sourceCoverage"]
    manifest = compiled["manifest"]
    bindings = compiled["bindings"]
    by_exercise: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for binding in bindings:
        by_exercise[binding["exerciseId"]].append(binding)

    lines = [
        "// Generated by tools/compile_aihub_criterion_policy.py. Do not edit manually.",
        "package com.example.trex_kotlin.pose.policy",
        "",
        "import com.example.trex_kotlin.catalog.AiHubExercise",
        "",
        "/** Catalog-only engineering taxonomy: not expert, clinical, Gold, calibration, or release approval; cannot evaluate, score, or cue a user. */",
        "object AiHubCriterionPolicyCatalog {",
        f"    const val SOURCE_CATALOG_SHA256: String = {_kotlin_string(source['catalogSha256'])}",
        "    const val SOURCE_COVERAGE_ARTIFACT_SHA256: String = "
        f"{_kotlin_string(source['coverageArtifactSha256'])}",
        "    const val SOURCE_METADATA_SET_SHA256: String = "
        f"{_kotlin_string(source['metadataSetSha256'])}",
        f"    const val POLICY_SHA256: String = {_kotlin_string(compiled['policySha256'])}",
        "    const val APPROVAL_ARTIFACT_SHA256: String = "
        f"{_kotlin_string(compiled['approvalArtifactSha256'])}",
        f"    const val REGISTRY_SHA256: String = {_kotlin_string(compiled['registrySha256'])}",
        "",
        "    internal val registry: AiHubCriterionPolicyRegistry = AiHubCriterionPolicyRegistry(",
        f"        schemaVersion = {compiled['schemaVersion']},",
        "        sourceCatalogSha256 = SOURCE_CATALOG_SHA256,",
        "        sourceCoverageArtifactSha256 = SOURCE_COVERAGE_ARTIFACT_SHA256,",
        "        sourceMetadataSetSha256 = SOURCE_METADATA_SET_SHA256,",
        "        approvedPolicySha256 = POLICY_SHA256,",
        "        approvalArtifactSha256 = APPROVAL_ARTIFACT_SHA256,",
        "        approvedRegistrySha256 = REGISTRY_SHA256,",
        "        bindings = allBindings(),",
        f"        expectedExerciseCount = {manifest['exerciseCount']},",
        f"        expectedConditionCount = {manifest['conditionCount']},",
        f"        expectedBindingCount = {manifest['bindingCount']},",
        f"        expectedReviewedBindingCount = {manifest['reviewedBindingCount']},",
        "    )",
        "",
        "    fun binding(exercise: AiHubExercise, sourceConditionId: String): "
        "AiHubCriterionPolicyBinding? = registry.binding(exercise, sourceConditionId)",
        "",
        "    fun bindings(exercise: AiHubExercise): List<AiHubCriterionPolicyBinding> =",
        "        registry.bindings(exercise)",
        "",
        "    private fun allBindings(): List<AiHubCriterionPolicyBinding> = listOf(",
    ]
    for exercise_id in sorted(by_exercise):
        lines.append(f"        *{_factory_name(exercise_id)}().toTypedArray(),")
    lines.extend(["    )", ""])

    for exercise_id in sorted(by_exercise):
        lines.extend(
            [
                f"    private fun {_factory_name(exercise_id)}(): "
                "List<AiHubCriterionPolicyBinding> = listOf(",
            ]
        )
        for binding in by_exercise[exercise_id]:
            lines.extend(
                [
                    "        AiHubCriterionPolicyBinding(",
                    f"            bindingId = {_kotlin_string(binding['bindingId'])},",
                    f"            exercise = AiHubExercise.{ENUM_NAMES[exercise_id]},",
                    "            sourceConditionId = "
                    f"{_kotlin_string(binding['sourceConditionId'])},",
                    "            reviewState = AiHubCriterionReviewState."
                    f"{binding['reviewState']},",
                    "            releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,",
                ]
            )
            interpretation = binding["interpretation"]
            if interpretation is None:
                lines.append("            interpretation = null,")
            else:
                phase = interpretation["phaseApplicability"]
                side = interpretation["sidePolicy"]
                view = interpretation["viewApplicability"]
                calibration = interpretation["calibrationProvenance"]
                lines.extend(
                    [
                        "            interpretation = AiHubCriterionInterpretation(",
                        f"                semanticId = {_kotlin_string(interpretation['semanticId'])},",
                        "                semanticFamilyId = "
                        f"{_kotlin_string(interpretation['semanticFamilyId'])},",
                        "                measurementConstructId = "
                        f"{_kotlin_string(interpretation['measurementConstructId'])},",
                        f"                claimBoundary = {_kotlin_string(interpretation['claimBoundary'])},",
                        "                observability = AiHubCriterionObservability."
                        f"{interpretation['observability']},",
                        "                phaseApplicability = AiHubCriterionPhaseApplicability(",
                        "                    state = AiHubCriterionPhaseApplicabilityState."
                        f"{phase['state']},",
                        f"                    phaseRoleIds = {_kotlin_list(phase['phaseRoleIds'])},",
                        "                ),",
                        "                sidePolicy = AiHubCriterionSidePolicy(",
                        f"                    kind = AiHubCriterionSidePolicyKind.{side['kind']},",
                        "                    roleResolverContractId = "
                        f"{_kotlin_nullable(side['roleResolverContractId'])},",
                        "                ),",
                        "                viewApplicability = AiHubCriterionViewApplicability(",
                        "                    state = AiHubCriterionViewApplicabilityState."
                        f"{view['state']},",
                        "                    viewContractIds = "
                        f"{_kotlin_list(view['viewContractIds'])},",
                        "                ),",
                        "                requiredCapabilityIds = "
                        f"{_kotlin_list(interpretation['requiredCapabilityIds'])},",
                        "                calibrationProvenance = "
                        "AiHubCriterionCalibrationProvenance(",
                        "                    state = "
                        "AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT,",
                        "                    artifactSha256 = null,",
                        "                    runtimeDomainId = null,",
                        "                    evidenceRefs = "
                        f"{_kotlin_list(calibration['evidenceRefs'])},",
                        "                ),",
                        "                unsupportedReasonCodes = "
                        f"{_kotlin_list(interpretation['unsupportedReasonCodes'])},",
                        "                reviewEvidenceRefs = "
                        f"{_kotlin_list(interpretation['reviewEvidenceRefs'])},",
                        "            ),",
                    ]
                )
            lines.extend(
                [
                    f"            reasonCodes = {_kotlin_list(binding['reasonCodes'])},",
                    "            decisionEvidenceRefs = "
                    f"{_kotlin_list(binding['decisionEvidenceRefs'])},",
                    "            approvedBindingPolicySha256 = "
                    f"{_kotlin_string(binding['bindingPolicySha256'])},",
                    "        ),",
                ]
            )
        lines.extend(["    )", ""])
    lines.extend(["}", ""])
    return "\n".join(lines)


def _resolved_output(
    output: Path,
    protected_inputs: Iterable[Path],
    *,
    allowed_root: Path = DEFAULT_OUTPUT.parent,
) -> Path:
    resolved = output.resolve()
    for protected in protected_inputs:
        if resolved == protected.resolve():
            raise PolicyError(f"Generated output collides with protected input: {protected}")
    resolved_root = allowed_root.resolve()
    if resolved.parent != resolved_root or resolved.name != DEFAULT_OUTPUT.name:
        raise PolicyError(
            "Generated output must be the canonical AiHubCriterionPolicyCatalog.kt "
            f"inside {resolved_root}"
        )
    return resolved


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
        ) as target:
            temporary = Path(target.name)
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def write_or_check(path: Path, content: str, *, check: bool) -> None:
    if check:
        current = path.read_bytes() if path.exists() else None
        if current != content.encode("utf-8"):
            raise PolicyError(f"Generated Kotlin is stale: {path}")
        return
    atomic_write(path, content)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--approval", type=Path, default=DEFAULT_APPROVAL)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--print-approval-draft", action="store_true")
    args = parser.parse_args()
    try:
        source = _load_json(args.source, "source coverage")
        policy = _load_json(args.policy, "policy")
        if args.print_approval_draft:
            compiled = compile_policy(
                source_artifact=source,
                policy=policy,
                approval=None,
                enforce_service_pins=True,
            )
            verify_repository_evidence_refs(compiled)
            print(json.dumps(approval_draft(compiled), ensure_ascii=False, indent=2))
            return 0
        approval = _load_json(args.approval, "approval")
        compiled = compile_policy(
            source_artifact=source,
            policy=policy,
            approval=approval,
            enforce_service_pins=True,
        )
        verify_repository_evidence_refs(compiled)
        output = _resolved_output(
            args.output,
            protected_inputs=[args.source, args.policy, args.approval],
        )
        kotlin = render_kotlin(compiled)
        write_or_check(output, kotlin, check=args.check)
    except PolicyError as error:
        parser.error(str(error))
    print(
        "criterion policy ok: "
        f"{compiled['manifest']['bindingCount']} bindings, "
        f"{compiled['manifest']['reviewedBindingCount']} engineering-reviewed, "
        f"sha256={compiled['policySha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
