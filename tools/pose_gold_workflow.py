#!/usr/bin/env python3
"""Compile a privacy-safe, fail-closed TREX pose-Gold readiness receipt.

The compiler has two deliberately different modes:

* without ``--bundle-root`` it verifies the three public, content-addressed
  contracts and emits the plan-only readiness receipt;
* with ``--bundle-root`` it validates one exact six-file restricted bundle and
  emits only aggregate counts plus one bundle-root digest.

It never emits participant, session, capture, reviewer, consent, frame, media,
or leaf-evidence identities.  Version 1 has no positive runtime authority.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import stat
import sys
import tempfile
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


SCHEMA_VERSION = 1
APPROVED_PROTOCOL_V1_SHA256 = (
    "dec8870a206fdeac132face2ca926e44ee87113b8cf6aa434241d69ae94552cb"
)
APPROVED_RIGHTS_V1_SHA256 = (
    "bfe2a80776fb65da20724d475bda61cf2adf6692587fe4f67f22c238a3a1b4df"
)
APPROVED_STUDY_PLAN_V1_SHA256 = (
    "a8299498fb045f870cff5a5151659767a6c96c1fc7521e606c1c09475a2ab172"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
VERSIONED_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._:/-]*\.v[1-9][0-9]*$")
PRIVATE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE_COVERAGE = PROJECT_ROOT / "docs/aihub-criterion-coverage.json"
DEFAULT_POLICY = PROJECT_ROOT / "docs/aihub-criterion-policy.json"
DEFAULT_POLICY_APPROVAL = PROJECT_ROOT / "docs/aihub-criterion-policy-approval.json"

AUTHORITY_KEYS = frozenset(
    {
        "calibrationAuthority",
        "cueAuthority",
        "phaseDecoderAuthority",
        "releaseAuthority",
        "repCountAuthority",
        "runtimeProviderAuthority",
        "scoreAuthority",
        "shadowAuthority",
        "userPassFailUnknownAuthority",
    }
)
ZERO_AUTHORITY = {key: 0 for key in sorted(AUTHORITY_KEYS)}

CANONICALIZATION = {
    "artifactHashAlgorithm": "SHA-256",
    "artifactHashInput": "RFC8259_JSON_WITH_TOP_LEVEL_ARTIFACT_SHA256_REMOVED",
    "artifactHashSerialization": "UTF8_SORTED_KEYS_COMPACT_NO_TRAILING_NEWLINE",
    "fileSerialization": "UTF8_PRETTY_2_SPACES_LF_FINAL_NEWLINE",
    "nonFiniteNumbersAllowed": False,
    "unicodeNormalization": "NFC",
}

RESTRICTED_FILE_NAMES = (
    "bundle-manifest.json",
    "capture-groups.jsonl",
    "observations.jsonl",
    "blind-reviews.jsonl",
    "adjudications.jsonl",
    "split-manifest.json",
)

# Only this repository-owned deterministic synthetic fixture may cross the v1 restricted-input
# boundary.  A caller-provided evidenceClass marker or self hash cannot turn real participant
# data into synthetic data.  These are raw file-byte hashes and are checked before JSON parsing.
APPROVED_SYNTHETIC_FILE_PINS = {
    "bundle-manifest.json": (2462, "60a145b36c6448d53e68fc8bcc44ca4b2c822ffecbabae0d333f3255ad3755f1"),
    "capture-groups.jsonl": (5101, "3a96b86ec63ee0378c15d49554aa4568db3b9df361aa2219023ad8f934a53bfc"),
    "observations.jsonl": (38760, "b6b9a2e3947bad3bb9371752d6d39a65fc50608465908fc195b1d776b15eef31"),
    "blind-reviews.jsonl": (70932, "27adef90777439fd4c3d0c9b416dced8bfbc32468a1e9e5696a39f8f978e733d"),
    "adjudications.jsonl": (23932, "c8eca92a4a50a73466c253fa8fae25ce92c91d6423b40a20448845502993c905"),
    "split-manifest.json": (2195, "459375c236632ffcb45cf2076d6f84c24100bc69edb573609cbce9969e41a92e"),
}

PUBLIC_PROTOCOL_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "authority",
        "canonicalization",
        "criterionGoldContract",
        "decisionUse",
        "geometryContract",
        "phaseGoldContract",
        "privacyContract",
        "protocolId",
        "referenceEvidenceContract",
        "restrictedBundleContract",
        "reviewContract",
        "rightsContract",
        "runtimeContract",
        "splitContract",
        "supportedCatalogScope",
        "viewGoldContract",
    }
)
PUBLIC_RIGHTS_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "approvalEvidenceSlots",
        "approvalTrustContract",
        "approvedServiceLevels",
        "authority",
        "blockers",
        "canonicalization",
        "dataClasses",
        "decisionUse",
        "gitPolicy",
        "manifestId",
        "permittedCurrentOperations",
        "prohibitedUntilVerifiedReady",
        "readiness",
        "retentionAndBackupContract",
        "status",
        "storageContract",
        "verifiedReadyRequirements",
    }
)
PUBLIC_STUDY_PLAN_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "approvalProvenanceSlots",
        "approvalTrustContract",
        "authority",
        "canonicalization",
        "cohortContract",
        "criterionPlans",
        "currentActualEvidenceCounts",
        "decisionUse",
        "exerciseId",
        "officialAiHubValidationUse",
        "phaseGoldPlan",
        "policyProvenance",
        "priorResearchProvenance",
        "protocolArtifactSha256",
        "readiness",
        "referenceEvidencePlan",
        "reviewPlan",
        "rightsManifestArtifactSha256",
        "schemaVersion",
        "studyId",
        "viewGoldPlan",
    }
)

BUNDLE_MANIFEST_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "evidenceClass",
        "protocolArtifactSha256",
        "rightsManifestArtifactSha256",
        "studyPlanArtifactSha256",
        "exerciseId",
        "declaredFiles",
        "observerContract",
        "referenceEvidenceContract",
        "consentRootArtifactSha256",
        "reviewerRosterRootArtifactSha256",
        "splitSealArtifactSha256",
    }
)
CAPTURE_GROUP_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "captureGroupId",
        "participantId",
        "sessionId",
        "rawMediaId",
        "rawMediaPayloadSha256",
        "derivedArtifactIds",
        "derivedArtifactPayloads",
        "contentDuplicateGroupIds",
        "perceptualDuplicateGroupIds",
        "perceptualFingerprintContractId",
        "perceptualFingerprintArtifactSha256",
        "exerciseId",
        "deviceProfileId",
        "deviceTier",
        "cameraGeometryEpochId",
        "authoritativeView",
        "split",
        "rightsReceiptId",
    }
)
OBSERVATION_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "observationId",
        "captureGroupId",
        "frameOrdinal",
        "captureTimestampNs",
        "poseTimestampMs",
        "geometry",
        "observerContractArtifactSha256",
        "rawCandidateCount",
        "personTrackEpochId",
        "qualifiedViewContractIds",
        "normalizedLandmarkCount",
        "worldLandmarkCount",
        "normalizedLandmarksPayloadSha256",
        "worldLandmarksPayloadSha256",
        "confidencePayloadSha256",
        "referenceEvidence",
        "sourceTruthUse",
        "activeMaskUse",
    }
)
BLIND_REVIEW_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "reviewId",
        "reviewerId",
        "adjudicableUnitId",
        "captureGroupId",
        "blinding",
        "phaseReview",
        "viewReview",
        "criterionReviews",
    }
)
ADJUDICATION_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "adjudicationId",
        "adjudicableUnitId",
        "captureGroupId",
        "reviewIds",
        "decisionMethod",
        "phaseAdjudication",
        "viewAdjudication",
        "criterionAdjudications",
        "agreement",
    }
)
SPLIT_MANIFEST_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "artifactKind",
        "artifactSha256",
        "splitManifestId",
        "splitSealArtifactSha256",
        "assignmentPrecedesOutcomeReview",
        "lockedTestAccessState",
        "assignments",
    }
)

PHASE_TOPOLOGY = ("READY", "DESCENDING", "BOTTOM", "ASCENDING", "READY")
PHASE_EDGES = tuple(zip(PHASE_TOPOLOGY, PHASE_TOPOLOGY[1:]))
SPLITS = frozenset(
    {"DEVELOPMENT", "CALIBRATION", "LOCKED_INTERNAL_TEST", "EXTERNAL_TEST"}
)
EVIDENCE_CLASSES = frozenset(
    {"PLAN_ONLY", "SYNTHETIC_CONFORMANCE", "REAL_RESTRICTED_GOLD"}
)
GOLD_STATES = frozenset(
    {"CONDITION_SATISFIED", "CONDITION_VIOLATED", "UNKNOWN_GOLD", "NOT_OBSERVABLE"}
)
CONTACT_MODALITY = "SYNCHRONIZED_ATTESTED_CONTACT_OR_FORCE_SENSOR"

PLAN_COUNT_KEYS = frozenset(
    {
        "adjudicatedCriterionDecisionCount",
        "adjudicatedPhaseCycleCount",
        "adjudicatedViewDecisionCount",
        "captureGroupCount",
        "participantCount",
        "realRestrictedBundleCount",
        "reviewerCount",
    }
)
APPROVAL_SLOT_KEYS = frozenset(
    {
        "annotationRubricArtifactSha256",
        "annotationToolArtifactSha256",
        "captureProtocolApprovalArtifactSha256",
        "clockAlignmentContractArtifactSha256",
        "cohortPowerPlanArtifactSha256",
        "reviewerRosterRootArtifactSha256",
        "splitSealArtifactSha256",
    }
)
RIGHTS_APPROVAL_SLOT_KEYS = frozenset(
    {
        "accessAuditApprovalArtifactSha256",
        "aiHubCommercialUseApprovalArtifactSha256",
        "aiHubDerivedDistributionApprovalArtifactSha256",
        "backupDeletionPolicyApprovalArtifactSha256",
        "participantConsentTemplateApprovalArtifactSha256",
        "privacyNoticeApprovalArtifactSha256",
        "retentionPolicyApprovalArtifactSha256",
        "withdrawalDeletionPolicyApprovalArtifactSha256",
    }
)
RIGHTS_SERVICE_LEVEL_KEYS = frozenset(
    {
        "backupDeletionMaximumDays",
        "primaryRetentionMaximumDays",
        "withdrawalDeletionMaximumDays",
    }
)
READINESS_AGGREGATE_COUNT_KEYS = frozenset(
    {
        "adjudicatedCriterionDecisionCount",
        "adjudicatedPhaseCycleCount",
        "adjudicatedViewDecisionCount",
        "calibrationArtifactCount",
        "captureGroupCount",
        "deviceTierCount",
        "eligibleGoldCycleCount",
        "externalTestCaptureGroupCount",
        "lockedInternalTestCaptureGroupCount",
        "participantCount",
        "realRestrictedBundleCount",
        "reviewerCount",
        "runtimePhaseProviderCount",
        "syntheticConformanceBundleCount",
    }
)


class GoldWorkflowError(RuntimeError):
    """Raised when an input could be mistaken for stronger evidence than it is."""


def canonical_json(value: Any) -> str:
    _validate_json_tree(value, "JSON")
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def canonical_json_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def compiler_implementation_provenance() -> dict[str, str]:
    try:
        payload = Path(__file__).read_bytes()
    except (OSError, UnicodeDecodeError) as error:
        raise GoldWorkflowError("cannot fingerprint compiler implementation") from error
    return {
        "contractId": "trex.pose-gold-workflow-compiler.v1",
        "relativePath": "tools/pose_gold_workflow.py",
        "canonicalTextSha256": canonical_lf_text_sha256(payload),
        "normalization": "UTF8_CRLF_LF_CR_TO_LF",
    }


def canonical_lf_text_sha256(payload: bytes) -> str:
    try:
        text_value = payload.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise GoldWorkflowError("canonical text must be strict UTF-8") from error
    normalized = text_value.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def artifact_sha256(value: Mapping[str, Any]) -> str:
    unsigned = dict(value)
    unsigned.pop("artifactSha256", None)
    return canonical_json_sha256(unsigned)


def with_artifact_sha256(value: Mapping[str, Any]) -> dict[str, Any]:
    result = dict(value)
    result.pop("artifactSha256", None)
    result["artifactSha256"] = canonical_json_sha256(result)
    return result


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise GoldWorkflowError(f"JSON contains duplicate key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise GoldWorkflowError(f"JSON contains non-finite number {value}")


def _validate_json_tree(value: Any, label: str) -> None:
    if value is None or type(value) in {bool, int}:
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise GoldWorkflowError(f"{label} contains a non-finite number")
        return
    if isinstance(value, str):
        if unicodedata.normalize("NFC", value) != value:
            raise GoldWorkflowError(f"{label} contains a non-NFC string")
        if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise GoldWorkflowError(f"{label} contains an unpaired Unicode surrogate")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _validate_json_tree(item, f"{label}[{index}]")
        return
    if isinstance(value, Mapping):
        for key, item in value.items():
            if not isinstance(key, str):
                raise GoldWorkflowError(f"{label} contains a non-string object key")
            _validate_json_tree(key, f"{label} key")
            _validate_json_tree(item, f"{label}.{key}")
        return
    raise GoldWorkflowError(f"{label} contains unsupported JSON value {type(value).__name__}")


def _is_reparse(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x0400)
    )


def _assert_no_reparse_chain(path: Path, *, require_regular: bool) -> Path:
    absolute = Path(os.path.abspath(os.fspath(path)))
    parts = absolute.parts
    if not parts:
        raise GoldWorkflowError(f"Invalid empty path: {path}")
    current = Path(parts[0])
    for component in parts[1:]:
        current = current / component
        try:
            metadata = current.lstat()
        except OSError as error:
            raise GoldWorkflowError(f"Cannot stat input path {current}: {error}") from error
        if _is_reparse(metadata):
            raise GoldWorkflowError(f"Symlink or reparse point is forbidden: {current}")
    try:
        metadata = absolute.lstat()
    except OSError as error:
        raise GoldWorkflowError(f"Cannot stat input path {absolute}: {error}") from error
    if require_regular and not stat.S_ISREG(metadata.st_mode):
        raise GoldWorkflowError(f"Expected a regular file: {absolute}")
    if not require_regular and not stat.S_ISDIR(metadata.st_mode):
        raise GoldWorkflowError(f"Expected a directory: {absolute}")
    return absolute


def _loads_json(text: str, label: str) -> Any:
    try:
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite,
        )
    except (UnicodeError, json.JSONDecodeError) as error:
        raise GoldWorkflowError(f"Cannot parse {label}: {error}") from error
    _validate_json_tree(value, label)
    return value


def load_json(path: Path, label: str) -> dict[str, Any]:
    source = _assert_no_reparse_chain(path, require_regular=True)
    try:
        text = source.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise GoldWorkflowError(f"Cannot read {label}: {error}") from error
    return _object(_loads_json(text, label), label)


def load_jsonl(path: Path, label: str) -> list[dict[str, Any]]:
    source = _assert_no_reparse_chain(path, require_regular=True)
    try:
        text = source.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise GoldWorkflowError(f"Cannot read {label}: {error}") from error
    return _loads_jsonl_text(text, label)


def _loads_jsonl_text(text: str, label: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            raise GoldWorkflowError(f"{label}:{line_number} is blank")
        records.append(_object(_loads_json(line, f"{label}:{line_number}"), label))
    if not records:
        raise GoldWorkflowError(f"{label} must contain at least one record")
    return records


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise GoldWorkflowError(f"{label} must be an object")
    return value


def _array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise GoldWorkflowError(f"{label} must be an array")
    return value


def _closed(value: Mapping[str, Any], expected: Iterable[str], label: str) -> None:
    expected_set = set(expected)
    actual = set(value)
    if actual != expected_set:
        raise GoldWorkflowError(
            f"{label} fields differ: missing={sorted(expected_set - actual)}, "
            f"unexpected={sorted(actual - expected_set)}"
        )


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise GoldWorkflowError(f"{label} must be a non-empty, trimmed string")
    if unicodedata.normalize("NFC", value) != value:
        raise GoldWorkflowError(f"{label} must use Unicode NFC")
    return value


def _private_id(value: Any, label: str) -> str:
    text = _text(value, label)
    if PRIVATE_ID_RE.fullmatch(text) is None or text in {".", ".."}:
        raise GoldWorkflowError(f"{label} is not a valid opaque identifier")
    if "/" in text or "\\" in text:
        raise GoldWorkflowError(f"{label} must not contain a path")
    return text


def _sha(value: Any, label: str) -> str:
    text = _text(value, label)
    if SHA256_RE.fullmatch(text) is None:
        raise GoldWorkflowError(f"{label} must be a lowercase SHA-256")
    return text


def _integer(value: Any, label: str, *, minimum: int | None = None) -> int:
    if type(value) is not int:
        raise GoldWorkflowError(f"{label} must be an integer (boolean is forbidden)")
    if minimum is not None and value < minimum:
        raise GoldWorkflowError(f"{label} must be >= {minimum}")
    return value


def _number(
    value: Any,
    label: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise GoldWorkflowError(f"{label} must be a finite JSON number")
    number = float(value)
    if not math.isfinite(number):
        raise GoldWorkflowError(f"{label} must be finite")
    if minimum is not None and number < minimum:
        raise GoldWorkflowError(f"{label} must be >= {minimum}")
    if maximum is not None and number > maximum:
        raise GoldWorkflowError(f"{label} must be <= {maximum}")
    return number


def _boolean(value: Any, label: str) -> bool:
    if type(value) is not bool:
        raise GoldWorkflowError(f"{label} must be a boolean")
    return value


def _enum(value: Any, allowed: Iterable[str], label: str) -> str:
    text = _text(value, label)
    allowed_set = set(allowed)
    if text not in allowed_set:
        raise GoldWorkflowError(f"{label} has unsupported value {text!r}")
    return text


def _sorted_unique_strings(value: Any, label: str, *, allow_empty: bool = True) -> list[str]:
    items = _array(value, label)
    strings = [_text(item, f"{label}[{index}]") for index, item in enumerate(items)]
    if not allow_empty and not strings:
        raise GoldWorkflowError(f"{label} must not be empty")
    if strings != sorted(set(strings)):
        raise GoldWorkflowError(f"{label} must be sorted and unique")
    return strings


def _require_self_hash(value: Mapping[str, Any], label: str) -> str:
    claimed = _sha(value.get("artifactSha256"), f"{label}.artifactSha256")
    actual = artifact_sha256(value)
    if claimed != actual:
        raise GoldWorkflowError(f"{label} self hash mismatch: expected={claimed}, actual={actual}")
    return claimed


def _require_zero_authority(value: Any, label: str) -> None:
    authority = _object(value, label)
    _closed(authority, AUTHORITY_KEYS, label)
    for key in AUTHORITY_KEYS:
        if _integer(authority[key], f"{label}.{key}", minimum=0) != 0:
            raise GoldWorkflowError(f"{label}.{key} must remain zero")


def _require_public_envelope(
    value: Mapping[str, Any], expected_keys: Iterable[str], expected_kind: str, label: str
) -> str:
    _closed(value, expected_keys, label)
    if _integer(value["schemaVersion"], f"{label}.schemaVersion") != SCHEMA_VERSION:
        raise GoldWorkflowError(f"{label} has unsupported schemaVersion")
    if value["artifactKind"] != expected_kind:
        raise GoldWorkflowError(f"{label} has unexpected artifactKind")
    if value["canonicalization"] != CANONICALIZATION:
        raise GoldWorkflowError(f"{label} canonicalization contract differs")
    _require_zero_authority(value["authority"], f"{label}.authority")
    return _require_self_hash(value, label)


def _validate_protocol(protocol: Mapping[str, Any]) -> str:
    digest = _require_public_envelope(
        protocol,
        PUBLIC_PROTOCOL_TOP_LEVEL_KEYS,
        "TREX_POSE_GOLD_PROTOCOL",
        "protocol",
    )
    if protocol["protocolId"] != "trex.pose-gold-protocol.v1":
        raise GoldWorkflowError("protocolId differs from the v1 contract")
    # Protocol v1 is an immutable safety contract. A semantic weakening must be a new protocol
    # version and compiler review, not a co-edited self hash.
    if digest != APPROVED_PROTOCOL_V1_SHA256:
        raise GoldWorkflowError(
            "protocol v1 is not the approved immutable safety contract: "
            f"expected={APPROVED_PROTOCOL_V1_SHA256}, actual={digest}"
        )
    scope = _object(protocol["supportedCatalogScope"], "protocol.supportedCatalogScope")
    if scope != {
        "catalogContext": {
            "exerciseCount": 41,
            "policyBindingCount": 167,
            "sourceExactConditionCount": 97,
        },
        "compilerScope": "BARBELL_SQUAT_VERTICAL_SLICE_ONLY",
        "executableExerciseIds": ["barbell-squat"],
        "schemaConceptsReusableAcrossCatalog": True,
        "schemaReuseRequiresVersionedExercisePlanCompiler": True,
        "studyPlanSelectsExactBindings": True,
    }:
        raise GoldWorkflowError("protocol compiler scope differs from the approved M7 vertical slice")
    bundle = _object(protocol["restrictedBundleContract"], "protocol.restrictedBundleContract")
    if bundle.get("fileCount") != len(RESTRICTED_FILE_NAMES):
        raise GoldWorkflowError("protocol restricted file count differs")
    if tuple(bundle.get("files", [])) != RESTRICTED_FILE_NAMES:
        raise GoldWorkflowError("protocol restricted filenames differ")
    if bundle.get("undeclaredFilesAllowed") is not False or bundle.get(
        "symlinksAndReparsePointsAllowed"
    ) is not False:
        raise GoldWorkflowError("protocol must reject undeclared and reparse entries")
    phase = _object(protocol["phaseGoldContract"], "protocol.phaseGoldContract")
    if phase.get("cycleIntervalConvention") != "START_INCLUSIVE_END_EXCLUSIVE":
        raise GoldWorkflowError("protocol must use half-open cycle intervals")
    if phase.get("activeMaskMayBeUsedAsPhaseGold") is not False or phase.get(
        "futureDerivedSurrogateMayBeUsedAsGold"
    ) is not False:
        raise GoldWorkflowError("active/source-derived phase supervision cannot be Gold")
    review = _object(protocol["reviewContract"], "protocol.reviewContract")
    if review.get("reviewersPerAdjudicableUnit") != 3:
        raise GoldWorkflowError("protocol must require exactly three reviewers")
    view = _object(protocol["viewGoldContract"], "protocol.viewGoldContract")
    if view.get("frontRearMayBeInferredFromPoseBodyAxisAlone") is not False:
        raise GoldWorkflowError("pose body axis cannot establish front versus rear")
    return digest


def _validate_rights(rights: Mapping[str, Any]) -> str:
    digest = _require_public_envelope(
        rights,
        PUBLIC_RIGHTS_TOP_LEVEL_KEYS,
        "TREX_POSE_DATA_RIGHTS_MANIFEST",
        "rights",
    )
    # The rights manifest is no longer pinned to [APPROVED_RIGHTS_V1_SHA256] here. That pin made
    # the v1 manifest immutable in fact, not only by convention, and removing it is an owner
    # decision recorded in docs/pose-gold-evidence-intake.md.
    #
    # What is lost: a manifest edited to claim VERIFIED_READY no longer fails on identity alone.
    # What still holds: the structural checks below, which independently require a VERIFIED_READY
    # manifest to carry non-null approval evidence, positive service levels, every retention
    # safeguard, a verified access audit and every data class ready. Those are what actually stop
    # an unearned transition, so the fail-closed property is narrowed rather than removed.

    readiness = _enum(rights["readiness"], {"NOT_READY", "VERIFIED_READY"}, "rights.readiness")
    status = _enum(rights["status"], {"UNVERIFIED", "VERIFIED"}, "rights.status")
    blockers = _sorted_unique_strings(rights["blockers"], "rights.blockers")
    slots = _object(rights["approvalEvidenceSlots"], "rights.approvalEvidenceSlots")
    service = _object(rights["approvedServiceLevels"], "rights.approvedServiceLevels")
    _closed(slots, RIGHTS_APPROVAL_SLOT_KEYS, "rights.approvalEvidenceSlots")
    _closed(service, RIGHTS_SERVICE_LEVEL_KEYS, "rights.approvedServiceLevels")

    requirements = _object(rights["verifiedReadyRequirements"], "rights.verifiedReadyRequirements")
    _closed(
        requirements,
        {
            "allApprovalEvidenceSlotsMustBeNonNullSha256",
            "allBlockersMustBeResolved",
            "allServiceLevelsMustBePositiveIntegers",
            "detachedSignaturesRequired",
            "externalTrustVerificationState",
            "individualConsentOrReviewerIdentifiersRemainOutsidePublicManifest",
            "minimumProtocolVersionForVerifiedReady",
            "pinnedTrustRegistryRequired",
            "sha256PresenceAloneMayAuthorizeTransition",
            "verifiedReadyTransitionAllowedInManifestV1",
        },
        "rights.verifiedReadyRequirements",
    )
    expected_requirements = {
        "allApprovalEvidenceSlotsMustBeNonNullSha256": True,
        "allBlockersMustBeResolved": True,
        "allServiceLevelsMustBePositiveIntegers": True,
        "detachedSignaturesRequired": True,
        "externalTrustVerificationState": "NOT_IMPLEMENTED_IN_V1",
        "individualConsentOrReviewerIdentifiersRemainOutsidePublicManifest": True,
        "minimumProtocolVersionForVerifiedReady": 2,
        "pinnedTrustRegistryRequired": True,
        "sha256PresenceAloneMayAuthorizeTransition": False,
        "verifiedReadyTransitionAllowedInManifestV1": False,
    }
    if requirements != expected_requirements:
        raise GoldWorkflowError("rights verified-ready trust requirements differ")

    retention = _object(rights["retentionAndBackupContract"], "rights.retentionAndBackupContract")
    _closed(
        retention,
        {
            "approvedBackupDeletionSlaPresent",
            "approvedPrimaryRetentionSchedulePresent",
            "approvedWithdrawalDeletionSlaPresent",
            "collectionMayStart",
            "legalHoldProcedureVerified",
        },
        "rights.retentionAndBackupContract",
    )
    storage = _object(rights["storageContract"], "rights.storageContract")
    _closed(
        storage,
        {
            "androidPersistenceAllowed",
            "offlineRestrictedWorkspaceRequired",
            "publicRepositoryMayContainRawOrDerivedParticipantData",
            "restrictedAccessAuditVerified",
        },
        "rights.storageContract",
    )
    if storage["androidPersistenceAllowed"] is not False or storage[
        "publicRepositoryMayContainRawOrDerivedParticipantData"
    ] is not False or storage["offlineRestrictedWorkspaceRequired"] is not True:
        raise GoldWorkflowError("rights storage privacy boundary was weakened")

    git_policy = _object(rights["gitPolicy"], "rights.gitPolicy")
    _closed(
        git_policy,
        {
            "aggregateReadinessReceiptAllowed",
            "consentOrReviewerRecordAllowed",
            "leafContentHashAllowed",
            "participantOrCapturePseudonymAllowed",
            "rawMediaAllowed",
            "rawPoseLandmarkOrTrajectoryAllowed",
            "timestampedCaptureMetadataAllowed",
        },
        "rights.gitPolicy",
    )
    expected_git_policy = {
        "aggregateReadinessReceiptAllowed": True,
        "consentOrReviewerRecordAllowed": False,
        "leafContentHashAllowed": False,
        "participantOrCapturePseudonymAllowed": False,
        "rawMediaAllowed": False,
        "rawPoseLandmarkOrTrajectoryAllowed": False,
        "timestampedCaptureMetadataAllowed": False,
    }
    if git_policy != expected_git_policy:
        raise GoldWorkflowError("rights Git privacy policy was weakened")

    data_classes = _array(rights["dataClasses"], "rights.dataClasses")
    if not data_classes:
        raise GoldWorkflowError("rights must declare protected data classes")
    seen_classes: set[str] = set()
    class_rows: list[dict[str, Any]] = []
    for index, raw in enumerate(data_classes):
        row = _object(raw, f"rights.dataClasses[{index}]")
        state_fields = {key for key in row if key.endswith("State")}
        if len(state_fields) != 1:
            raise GoldWorkflowError("each rights data class needs exactly one readiness state")
        _closed(row, {"classId", "realDataUseReady", *state_fields}, f"rights.dataClasses[{index}]")
        class_id = _text(row["classId"], f"rights.dataClasses[{index}].classId")
        if class_id in seen_classes:
            raise GoldWorkflowError("duplicate rights data class")
        seen_classes.add(class_id)
        _boolean(row["realDataUseReady"], f"rights.dataClasses[{index}].realDataUseReady")
        for state_field in state_fields:
            _text(row[state_field], f"rights.dataClasses[{index}].{state_field}")
        class_rows.append(row)

    if readiness == "VERIFIED_READY":
        if status != "VERIFIED" or blockers:
            raise GoldWorkflowError("VERIFIED_READY rights must be VERIFIED with no blockers")
        for key, value in slots.items():
            _sha(value, f"rights.approvalEvidenceSlots.{key}")
        for key, value in service.items():
            _integer(value, f"rights.approvedServiceLevels.{key}", minimum=1)
        if any(value is not True for value in retention.values()):
            raise GoldWorkflowError("VERIFIED_READY requires all retention safeguards")
        if storage["restrictedAccessAuditVerified"] is not True:
            raise GoldWorkflowError("VERIFIED_READY requires a verified restricted-access audit")
        for row in class_rows:
            if row["realDataUseReady"] is not True:
                raise GoldWorkflowError("VERIFIED_READY requires every data class ready")
            state_value = next(value for key, value in row.items() if key.endswith("State"))
            if state_value != "VERIFIED":
                raise GoldWorkflowError("VERIFIED_READY requires verified data-class states")
    else:
        if status != "UNVERIFIED":
            raise GoldWorkflowError("NOT_READY rights must remain UNVERIFIED")
        if not blockers:
            raise GoldWorkflowError("NOT_READY rights must retain explicit blockers")
        if any(value is not None for value in slots.values()):
            raise GoldWorkflowError("unverified rights cannot carry approval artifacts")
        if any(value is not None for value in service.values()):
            raise GoldWorkflowError("unverified rights cannot carry approved service levels")
        if any(value is not False for value in retention.values()):
            raise GoldWorkflowError("unverified rights cannot enable collection safeguards")
        if storage["restrictedAccessAuditVerified"] is not False:
            raise GoldWorkflowError("unverified rights cannot claim access-audit verification")
        if any(row["realDataUseReady"] is not False for row in class_rows):
            raise GoldWorkflowError("unverified rights data classes must remain unavailable")
    return digest


def _load_compiled_policy(
    source_path: Path, policy_path: Path, approval_path: Path
) -> tuple[dict[str, Any], dict[str, Any]]:
    source = load_json(source_path, "AI Hub criterion coverage")
    policy = load_json(policy_path, "AI Hub criterion policy")
    approval = load_json(approval_path, "AI Hub criterion policy approval")
    try:
        if __package__:
            from .compile_aihub_criterion_policy import PolicyError, compile_policy
        else:
            from compile_aihub_criterion_policy import PolicyError, compile_policy
    except ImportError as error:
        raise GoldWorkflowError("Cannot import authoritative AI Hub policy compiler") from None
    try:
        compiled = compile_policy(
            source_artifact=source,
            policy=policy,
            approval=approval,
            enforce_service_pins=True,
        )
    except PolicyError as error:
        raise GoldWorkflowError(f"Cannot compile authoritative AI Hub policy: {error}") from error
    return source, compiled


def _condition_texts(source: Mapping[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for index, raw in enumerate(_array(source.get("conditionRegistry"), "conditionRegistry")):
        row = _object(raw, f"conditionRegistry[{index}]")
        result[_text(row.get("id"), "condition id")] = _text(
            row.get("normalizedExactText"), "condition normalizedExactText"
        )
    return result


def _sorted_unique_private_ids(value: Any, label: str, *, allow_empty: bool = True) -> list[str]:
    result = _sorted_unique_strings(value, label, allow_empty=allow_empty)
    for index, item in enumerate(result):
        _private_id(item, f"{label}[{index}]")
    return result


def _validate_study_plan(
    plan: Mapping[str, Any],
    *,
    protocol: Mapping[str, Any],
    protocol_sha: str,
    rights_sha: str,
    source: Mapping[str, Any],
    compiled_policy: Mapping[str, Any],
) -> str:
    digest = _require_public_envelope(
        plan,
        PUBLIC_STUDY_PLAN_TOP_LEVEL_KEYS,
        "TREX_BARBELL_SQUAT_GOLD_STUDY_PLAN",
        "study plan",
    )
    if digest != APPROVED_STUDY_PLAN_V1_SHA256:
        raise GoldWorkflowError(
            "study plan v1 is immutable preregistration: "
            f"expected={APPROVED_STUDY_PLAN_V1_SHA256}, actual={digest}"
        )
    if plan["protocolArtifactSha256"] != protocol_sha:
        raise GoldWorkflowError("study plan protocol cross-pin mismatch")
    if plan["rightsManifestArtifactSha256"] != rights_sha:
        raise GoldWorkflowError("study plan rights cross-pin mismatch")
    if plan["readiness"] != "NOT_READY":
        raise GoldWorkflowError("v1 study plan must remain NOT_READY")
    expected_kind = f"TREX_{_text(plan['exerciseId'], 'study plan.exerciseId').replace('-', '_').upper()}_GOLD_STUDY_PLAN"
    if plan["artifactKind"] != expected_kind:
        raise GoldWorkflowError("study plan artifactKind does not match exerciseId")

    counts = _object(plan["currentActualEvidenceCounts"], "study plan.currentActualEvidenceCounts")
    _closed(counts, PLAN_COUNT_KEYS, "study plan.currentActualEvidenceCounts")
    for key, value in counts.items():
        if _integer(value, f"study plan.currentActualEvidenceCounts.{key}", minimum=0) != 0:
            raise GoldWorkflowError("the preregistered M7 plan cannot claim actual evidence")

    approval_slots = _object(plan["approvalProvenanceSlots"], "study plan.approvalProvenanceSlots")
    _closed(approval_slots, APPROVAL_SLOT_KEYS, "study plan.approvalProvenanceSlots")
    for key, value in approval_slots.items():
        if value is not None:
            _sha(value, f"study plan.approvalProvenanceSlots.{key}")

    cohort = _object(plan["cohortContract"], "study plan.cohortContract")
    _closed(
        cohort,
        {
            "deviceTiersRequired",
            "externalNaturalUseCohortRequired",
            "participantGroupedSplitRequired",
            "powerAndSampleSizeState",
            "scriptedSingleFaultAndMultiFaultCohortsRequired",
            "subjectByDeviceByQualifiedViewCoverageRequired",
        },
        "study plan.cohortContract",
    )
    if _sorted_unique_strings(cohort["deviceTiersRequired"], "deviceTiersRequired", allow_empty=False) != [
        "LOW",
        "MAINSTREAM",
    ]:
        raise GoldWorkflowError("M7 cohort must retain low and mainstream device tiers")
    for field in (
        "externalNaturalUseCohortRequired",
        "participantGroupedSplitRequired",
        "scriptedSingleFaultAndMultiFaultCohortsRequired",
        "subjectByDeviceByQualifiedViewCoverageRequired",
    ):
        if _boolean(cohort[field], f"cohortContract.{field}") is not True:
            raise GoldWorkflowError(f"cohortContract.{field} must remain required")
    if cohort["powerAndSampleSizeState"] != "NOT_YET_APPROVED":
        raise GoldWorkflowError("M7 cannot claim an approved power plan")
    if plan["officialAiHubValidationUse"] != {
        "m7ReadCount": 0,
        "state": "CONSUMED_DEVELOPMENT_BENCHMARK_EXCLUDED_FROM_GOLD_FITTING_CALIBRATION_AND_LOCKED_TEST",
    }:
        raise GoldWorkflowError("consumed AI Hub Validation cannot be reused as untouched Gold")
    phase = _object(plan["phaseGoldPlan"], "study plan.phaseGoldPlan")
    _closed(
        phase,
        {
            "completedCycleBoundary",
            "cycleIntervalConvention",
            "cycleScopeStartPolicy",
            "orderedTopology",
            "requiredEvidence",
            "setupReadyWindowIncludedInCompletedCycle",
            "unknownBoundaryPolicy",
        },
        "study plan.phaseGoldPlan",
    )
    if tuple(phase.get("orderedTopology", [])) != PHASE_TOPOLOGY:
        raise GoldWorkflowError("barbell-squat phase topology differs")
    if phase.get("cycleIntervalConvention") != "START_INCLUSIVE_END_EXCLUSIVE":
        raise GoldWorkflowError("barbell-squat phase intervals must be half-open")
    if phase.get("cycleScopeStartPolicy") != "FIRST_TRANSITION_BOUNDARY" or phase.get(
        "setupReadyWindowIncludedInCompletedCycle"
    ) is not False:
        raise GoldWorkflowError("phase completed-cycle scope differs")
    review = _object(plan["reviewPlan"], "study plan.reviewPlan")
    _closed(
        review,
        {
            "agreementGate",
            "blindedIndependentReviewRequired",
            "reviewersPerAdjudicableUnit",
            "unresolvedDisagreementState",
        },
        "study plan.reviewPlan",
    )
    if review.get("reviewersPerAdjudicableUnit") != 3 or review.get(
        "blindedIndependentReviewRequired"
    ) is not True:
        raise GoldWorkflowError("study plan must require three blinded reviewers")
    if review["agreementGate"] != "KRIPPENDORFF_ALPHA_GE_0_80_OR_UNKNOWN_GOLD" or review[
        "unresolvedDisagreementState"
    ] != "UNKNOWN_GOLD":
        raise GoldWorkflowError("study plan agreement fail-closed policy differs")

    view_plan = _object(plan["viewGoldPlan"], "study plan.viewGoldPlan")
    _closed(
        view_plan,
        {
            "frontRearPoseOnlyInferenceAllowed",
            "missingAuthoritativeEvidenceState",
            "requiredCandidateViewContractIds",
            "requiredEvidence",
        },
        "study plan.viewGoldPlan",
    )
    if view_plan["frontRearPoseOnlyInferenceAllowed"] is not False or view_plan[
        "missingAuthoritativeEvidenceState"
    ] != "UNKNOWN_GOLD":
        raise GoldWorkflowError("study plan view authority boundary differs")
    candidate_views = _sorted_unique_strings(
        view_plan["requiredCandidateViewContractIds"],
        "study plan.viewGoldPlan.requiredCandidateViewContractIds",
        allow_empty=False,
    )

    reference_plan = _object(plan["referenceEvidencePlan"], "study plan.referenceEvidencePlan")
    _closed(
        reference_plan,
        {
            "clockAlignmentAcceptanceContractState",
            "clockAlignmentEvidenceRequiredForPairedTimeSeries",
            "phaseReferenceModalityIds",
            "runtimeCandidateDerivedReferenceAllowed",
            "viewReferenceModalityIds",
        },
        "study plan.referenceEvidencePlan",
    )
    if reference_plan["runtimeCandidateDerivedReferenceAllowed"] is not False or reference_plan[
        "clockAlignmentEvidenceRequiredForPairedTimeSeries"
    ] is not True:
        raise GoldWorkflowError("runtime candidate cannot become reference evidence")
    allowed_reference = set(protocol["referenceEvidenceContract"]["allowedReferenceModalityIds"])
    phase_modalities = _sorted_unique_strings(
        reference_plan["phaseReferenceModalityIds"], "phaseReferenceModalityIds", allow_empty=False
    )
    view_modalities = _sorted_unique_strings(
        reference_plan["viewReferenceModalityIds"], "viewReferenceModalityIds", allow_empty=False
    )
    if not set(phase_modalities).issubset(allowed_reference) or not set(view_modalities).issubset(
        allowed_reference
    ):
        raise GoldWorkflowError("study plan uses an unapproved reference modality")
    clock_state = reference_plan["clockAlignmentAcceptanceContractState"]
    clock_approval = approval_slots["clockAlignmentContractArtifactSha256"]
    if clock_state == "APPROVED":
        if clock_approval is None:
            raise GoldWorkflowError("approved clock alignment requires an approval artifact")
    elif clock_state == "V1_APPROVAL_TRUST_NOT_IMPLEMENTED_REAL_INTAKE_MUST_FAIL":
        if clock_approval is not None:
            raise GoldWorkflowError("unapproved clock alignment cannot carry an approval artifact")
    else:
        raise GoldWorkflowError("unsupported clock-alignment state")

    prior = _object(plan["priorResearchProvenance"], "study plan.priorResearchProvenance")
    _closed(
        prior,
        {
            "phaseResearchContractArtifactSha256",
            "phaseResearchReadinessArtifactSha256",
            "phaseTrainingReportFingerprintSha256",
            "researchContinuation",
            "researchUse",
        },
        "study plan.priorResearchProvenance",
    )
    for key in (
        "phaseResearchContractArtifactSha256",
        "phaseResearchReadinessArtifactSha256",
        "phaseTrainingReportFingerprintSha256",
    ):
        _sha(prior[key], f"study plan.priorResearchProvenance.{key}")
    if prior["researchContinuation"] != "REJECTED_NO_RUNTIME_DECODER_PARAMETERS" or prior[
        "researchUse"
    ] != "TRAINING_SURROGATE_ONLY_NOT_PHASE_GOLD":
        raise GoldWorkflowError("prior rejected research cannot gain Gold authority")

    provenance = _object(plan["policyProvenance"], "study plan.policyProvenance")
    expected_provenance = {
        "approvalArtifactSha256": compiled_policy["approvalArtifactSha256"],
        "approvedPolicySha256": compiled_policy["policySha256"],
        "policyRegistrySha256": compiled_policy["registrySha256"],
        "sourceCatalogSha256": source["sourceProvenance"]["catalog"]["catalogSha256"],
        "sourceCoverageArtifactSha256": source["artifactSha256"],
        "sourceMetadataSetSha256": source["sourceProvenance"]["twoDMetadataAudit"][
            "metadataSetSha256"
        ],
    }
    if provenance != expected_provenance:
        raise GoldWorkflowError("study plan policy provenance differs from authoritative compiler")

    exercise_id = _text(plan["exerciseId"], "study plan.exerciseId")
    compiled_rows = {
        row["sourceConditionId"]: row
        for row in compiled_policy["bindings"]
        if row["exerciseId"] == exercise_id
    }
    texts = _condition_texts(source)
    criterion_plans = _array(plan["criterionPlans"], "study plan.criterionPlans")
    seen: set[str] = set()
    for index, raw in enumerate(criterion_plans):
        criterion = _object(raw, f"criterionPlans[{index}]")
        allowed_keys = {
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
        if "goldStateWithoutAttestedContactSensor" in criterion:
            allowed_keys.add("goldStateWithoutAttestedContactSensor")
        _closed(criterion, allowed_keys, f"criterionPlans[{index}]")
        key = _object(criterion["bindingKey"], f"criterionPlans[{index}].bindingKey")
        _closed(
            key,
            {"exerciseId", "sourceConditionId", "bindingId", "bindingPolicySha256", "policyRegistrySha256"},
            f"criterionPlans[{index}].bindingKey",
        )
        source_id = _text(key["sourceConditionId"], "sourceConditionId")
        if source_id in seen:
            raise GoldWorkflowError("study plan contains duplicate exact condition binding")
        seen.add(source_id)
        authoritative = compiled_rows.get(source_id)
        if authoritative is None or authoritative["interpretation"] is None:
            raise GoldWorkflowError("study plan binding is not an authoritative reviewed binding")
        interpretation = authoritative["interpretation"]
        expected_key = {
            "exerciseId": authoritative["exerciseId"],
            "sourceConditionId": authoritative["sourceConditionId"],
            "bindingId": authoritative["bindingId"],
            "bindingPolicySha256": authoritative["bindingPolicySha256"],
            "policyRegistrySha256": compiled_policy["registrySha256"],
        }
        if key != expected_key:
            raise GoldWorkflowError("study plan exact binding tuple differs from authoritative policy")
        comparisons = {
            "measurementConstructId": interpretation["measurementConstructId"],
            "observability": interpretation["observability"],
            "phaseRoleId": interpretation["phaseApplicability"]["phaseRoleIds"][0],
            "sidePolicy": interpretation["sidePolicy"]["kind"],
            "sourceConditionExactText": texts[source_id],
        }
        for field, expected in comparisons.items():
            if criterion[field] != expected:
                raise GoldWorkflowError(f"criterionPlans[{index}].{field} differs from policy")
        if criterion["requiredCapabilityIds"] != interpretation["requiredCapabilityIds"]:
            raise GoldWorkflowError("criterion required capabilities differ from policy")
        if criterion["viewContractIds"] != interpretation["viewApplicability"]["viewContractIds"]:
            raise GoldWorkflowError("criterion views differ from policy")
        if not set(criterion["viewContractIds"]).issubset(set(candidate_views)):
            raise GoldWorkflowError("criterion view is absent from the study view plan")
        modalities = _sorted_unique_strings(
            criterion["permittedReferenceModalityIds"],
            f"criterionPlans[{index}].permittedReferenceModalityIds",
            allow_empty=False,
        )
        if not set(modalities).issubset(allowed_reference):
            raise GoldWorkflowError("criterion uses an unapproved reference modality")
        if criterion["calibrationState"] != "NO_APPROVED_ARTIFACT":
            raise GoldWorkflowError("M7 criterion cannot claim an approved calibration")
        if interpretation["observability"] == "NOT_OBSERVABLE":
            if criterion.get("goldStateWithoutAttestedContactSensor") != (
                "UNKNOWN_GOLD_AND_NOT_OBSERVABLE"
            ):
                raise GoldWorkflowError("non-observable contact criterion must remain unknown")
            if modalities != [CONTACT_MODALITY]:
                raise GoldWorkflowError("plantar contact requires the attested contact modality")
    if seen != set(compiled_rows):
        raise GoldWorkflowError("study plan must select the exercise's complete exact binding set")
    return digest


def _validate_artifact_record(
    record: Mapping[str, Any], expected_keys: Iterable[str], expected_kind: str, label: str
) -> None:
    _closed(record, expected_keys, label)
    if _integer(record["schemaVersion"], f"{label}.schemaVersion") != SCHEMA_VERSION:
        raise GoldWorkflowError(f"{label} has unsupported schemaVersion")
    if record["artifactKind"] != expected_kind:
        raise GoldWorkflowError(f"{label} has unexpected artifactKind")
    _require_self_hash(record, label)


def _bundle_root_digest(files: Mapping[str, Sequence[Mapping[str, Any]]]) -> str:
    digest = hashlib.sha256()
    for filename in RESTRICTED_FILE_NAMES:
        records = files[filename]
        digest.update(filename.encode("utf-8"))
        digest.update(b"\0")
        for record in records:
            digest.update(canonical_json(record).encode("utf-8"))
            digest.update(b"\n")
    return digest.hexdigest()


def _validate_restricted_bundle(
    root: Path,
    *,
    evidence_class: str,
    protocol: Mapping[str, Any],
    rights: Mapping[str, Any],
    plan: Mapping[str, Any],
) -> dict[str, Any]:
    # The detailed cross-record validation lives below this boundary so no private value can
    # accidentally be copied into the returned aggregate dictionary.
    bundle_root = _assert_no_reparse_chain(root, require_regular=False)
    actual_entries: set[str] = set()
    try:
        with os.scandir(bundle_root) as entries:
            for entry in entries:
                metadata = entry.stat(follow_symlinks=False)
                if _is_reparse(metadata) or not stat.S_ISREG(metadata.st_mode):
                    raise GoldWorkflowError(f"Restricted bundle entry is not a regular file: {entry.name}")
                actual_entries.add(entry.name)
    except OSError as error:
        raise GoldWorkflowError(f"Cannot enumerate restricted bundle: {error}") from error
    if actual_entries != set(RESTRICTED_FILE_NAMES):
        raise GoldWorkflowError(
            "Restricted bundle exact-set differs: "
            f"missing={sorted(set(RESTRICTED_FILE_NAMES) - actual_entries)}, "
            f"unexpected={sorted(actual_entries - set(RESTRICTED_FILE_NAMES))}"
        )
    snapshots: dict[str, bytes] = {}
    if evidence_class == "SYNTHETIC_CONFORMANCE":
        approved_sources: dict[str, Path] = {}
        # Phase one is metadata-only across the complete exact set.  A single oversized file
        # rejects the bundle before any potentially private file content is read.
        for filename in RESTRICTED_FILE_NAMES:
            source = _assert_no_reparse_chain(bundle_root / filename, require_regular=True)
            try:
                expected_size, _ = APPROVED_SYNTHETIC_FILE_PINS[filename]
                if source.stat(follow_symlinks=False).st_size != expected_size:
                    raise GoldWorkflowError("UNAPPROVED_SYNTHETIC_FIXTURE_V1")
            except OSError as error:
                raise GoldWorkflowError("SYNTHETIC_FIXTURE_PREFLIGHT_STAT_FAILED") from error
            approved_sources[filename] = source
        # Phase two snapshots each bounded file exactly once; parsing consumes only these bytes.
        for filename in RESTRICTED_FILE_NAMES:
            source = approved_sources[filename]
            expected_size, expected_sha = APPROVED_SYNTHETIC_FILE_PINS[filename]
            try:
                with source.open("rb") as input_file:
                    payload = input_file.read(expected_size + 1)
            except OSError as error:
                raise GoldWorkflowError("SYNTHETIC_FIXTURE_PREFLIGHT_READ_FAILED") from error
            if len(payload) != expected_size or hashlib.sha256(payload).hexdigest() != expected_sha:
                raise GoldWorkflowError("UNAPPROVED_SYNTHETIC_FIXTURE_V1")
            snapshots[filename] = payload
    try:
        decoded = {
            filename: payload.decode("utf-8", errors="strict")
            for filename, payload in snapshots.items()
        }
    except UnicodeDecodeError as error:
        raise GoldWorkflowError("SYNTHETIC_FIXTURE_UTF8_INVALID") from error
    manifest = _object(_loads_json(decoded[RESTRICTED_FILE_NAMES[0]], "bundle manifest"), "bundle manifest")
    captures = _loads_jsonl_text(decoded[RESTRICTED_FILE_NAMES[1]], "capture groups")
    observations = _loads_jsonl_text(decoded[RESTRICTED_FILE_NAMES[2]], "observations")
    reviews = _loads_jsonl_text(decoded[RESTRICTED_FILE_NAMES[3]], "blind reviews")
    adjudications = _loads_jsonl_text(decoded[RESTRICTED_FILE_NAMES[4]], "adjudications")
    split_manifest = _object(
        _loads_json(decoded[RESTRICTED_FILE_NAMES[5]], "split manifest"), "split manifest"
    )
    files: dict[str, Sequence[Mapping[str, Any]]] = {
        RESTRICTED_FILE_NAMES[0]: [manifest],
        RESTRICTED_FILE_NAMES[1]: captures,
        RESTRICTED_FILE_NAMES[2]: observations,
        RESTRICTED_FILE_NAMES[3]: reviews,
        RESTRICTED_FILE_NAMES[4]: adjudications,
        RESTRICTED_FILE_NAMES[5]: [split_manifest],
    }
    _validate_bundle_records(
        manifest,
        captures,
        observations,
        reviews,
        adjudications,
        split_manifest,
        evidence_class=evidence_class,
        protocol=protocol,
        rights=rights,
        plan=plan,
    )
    return {
        "evidenceClass": evidence_class,
        "bundleRootSha256": _bundle_root_digest(files),
        "verificationState": "SYNTHETIC_SCHEMA_CONFORMANCE_ONLY_NOT_GOLD_EVIDENCE",
        "syntheticFixtureShapeCounts": {
            "adjudicationRecordCount": len(adjudications),
            "blindReviewRecordCount": len(reviews),
            "captureRecordCount": len(captures),
            "observationRecordCount": len(observations),
            "splitAssignmentRecordCount": len(split_manifest["assignments"]),
        },
    }


def _validate_bundle_records(
    manifest: Mapping[str, Any],
    captures: Sequence[Mapping[str, Any]],
    observations: Sequence[Mapping[str, Any]],
    reviews: Sequence[Mapping[str, Any]],
    adjudications: Sequence[Mapping[str, Any]],
    split_manifest: Mapping[str, Any],
    *,
    evidence_class: str,
    protocol: Mapping[str, Any],
    rights: Mapping[str, Any],
    plan: Mapping[str, Any],
) -> dict[str, int]:
    # Filled in by the schema-specific validators below.  Keeping the return value numeric-only
    # is a deliberate privacy boundary.
    _validate_artifact_record(
        manifest, BUNDLE_MANIFEST_TOP_LEVEL_KEYS, "TREX_POSE_GOLD_BUNDLE_MANIFEST", "bundle manifest"
    )
    if manifest["evidenceClass"] != evidence_class:
        raise GoldWorkflowError("CLI evidence class differs from bundle manifest")
    if manifest["protocolArtifactSha256"] != protocol["artifactSha256"]:
        raise GoldWorkflowError("bundle protocol cross-pin mismatch")
    if manifest["rightsManifestArtifactSha256"] != rights["artifactSha256"]:
        raise GoldWorkflowError("bundle rights cross-pin mismatch")
    if manifest["studyPlanArtifactSha256"] != plan["artifactSha256"]:
        raise GoldWorkflowError("bundle study-plan cross-pin mismatch")
    if tuple(manifest["declaredFiles"]) != RESTRICTED_FILE_NAMES:
        raise GoldWorkflowError("bundle declared file list differs")
    if manifest["exerciseId"] != plan["exerciseId"]:
        raise GoldWorkflowError("bundle exercise differs from study plan")
    for field in (
        "consentRootArtifactSha256",
        "reviewerRosterRootArtifactSha256",
        "splitSealArtifactSha256",
    ):
        _sha(manifest[field], f"bundle manifest.{field}")
    observer = _object(manifest["observerContract"], "bundle manifest.observerContract")
    _closed(
        observer,
        {
            "runtimeDomainId",
            "observationContractArtifactSha256",
            "modelArtifactSha256",
            "preprocessingArtifactSha256",
            "landmarkSchemaArtifactSha256",
            "personLockArtifactSha256",
            "viewQualifierArtifactSha256",
            "geometryProviderArtifactSha256",
            "mediaPipeTasksVersion",
            "runningMode",
            "resolvedDelegate",
            "landmarkCount",
            "productionPipeline",
            "maximumCaptureGapNs",
        },
        "bundle manifest.observerContract",
    )
    _text(observer["runtimeDomainId"], "observer runtimeDomainId")
    for field in (
        "observationContractArtifactSha256",
        "modelArtifactSha256",
        "preprocessingArtifactSha256",
        "landmarkSchemaArtifactSha256",
        "personLockArtifactSha256",
        "viewQualifierArtifactSha256",
        "geometryProviderArtifactSha256",
    ):
        _sha(observer[field], f"observerContract.{field}")
    _text(observer["mediaPipeTasksVersion"], "observer mediaPipeTasksVersion")
    if observer["runningMode"] != "VIDEO":
        raise GoldWorkflowError("Gold observations require production VIDEO running mode")
    _enum(observer["resolvedDelegate"], {"CPU", "GPU"}, "observer resolvedDelegate")
    if _integer(observer["landmarkCount"], "observer landmarkCount") != 33:
        raise GoldWorkflowError("MediaPipe observer landmark schema must contain exactly 33 landmarks")
    if _boolean(observer["productionPipeline"], "observer productionPipeline") is not True:
        raise GoldWorkflowError("bundle must bind the production CameraX/MediaPipe pipeline")
    _integer(observer["maximumCaptureGapNs"], "observer maximumCaptureGapNs", minimum=1)
    reference = _object(
        manifest["referenceEvidenceContract"], "bundle manifest.referenceEvidenceContract"
    )
    _closed(
        reference,
        {
            "modalityIds",
            "clockAlignmentContractArtifactSha256",
            "maximumClockAlignmentErrorUs",
            "contactSensorAttestationContractArtifactSha256",
        },
        "bundle manifest.referenceEvidenceContract",
    )
    modalities = _sorted_unique_strings(
        reference["modalityIds"], "bundle reference modalityIds", allow_empty=False
    )
    allowed_modalities = set(protocol["referenceEvidenceContract"]["allowedReferenceModalityIds"])
    if not set(modalities).issubset(allowed_modalities):
        raise GoldWorkflowError("bundle declares an unapproved reference modality")
    _sha(
        reference["clockAlignmentContractArtifactSha256"],
        "bundle clockAlignmentContractArtifactSha256",
    )
    _integer(reference["maximumClockAlignmentErrorUs"], "maximumClockAlignmentErrorUs", minimum=0)
    _sha(
        reference["contactSensorAttestationContractArtifactSha256"],
        "contactSensorAttestationContractArtifactSha256",
    )
    if evidence_class == "REAL_RESTRICTED_GOLD":
        raise GoldWorkflowError(
            "NO_TRUSTED_RIGHTS_AUTHORITY_V1: real restricted Gold requires a future "
            "detached-signature and pinned-public-key contract"
        )
    elif evidence_class != "SYNTHETIC_CONFORMANCE":
        raise GoldWorkflowError("a restricted bundle must be synthetic or real evidence")
    # Additional strict cross-record validators are intentionally separate helpers.
    return _validate_capture_observation_review_split_records(
        manifest, captures, observations, reviews, adjudications, split_manifest, plan=plan
    )


def _validate_capture_observation_review_split_records(
    manifest: Mapping[str, Any],
    captures: Sequence[Mapping[str, Any]],
    observations: Sequence[Mapping[str, Any]],
    reviews: Sequence[Mapping[str, Any]],
    adjudications: Sequence[Mapping[str, Any]],
    split_manifest: Mapping[str, Any],
    *,
    plan: Mapping[str, Any],
) -> dict[str, int]:
    observer = _object(manifest["observerContract"], "bundle manifest.observerContract")
    reference_contract = _object(
        manifest["referenceEvidenceContract"], "bundle manifest.referenceEvidenceContract"
    )
    manifest_modalities = set(reference_contract["modalityIds"])
    plan_views = set(plan["viewGoldPlan"]["requiredCandidateViewContractIds"])
    phase_modalities = set(plan["referenceEvidencePlan"]["phaseReferenceModalityIds"])
    criteria = {
        row["bindingKey"]["sourceConditionId"]: row for row in plan["criterionPlans"]
    }

    capture_by_id = _artifact_map(
        captures,
        CAPTURE_GROUP_TOP_LEVEL_KEYS,
        "TREX_POSE_GOLD_CAPTURE_GROUP",
        "captureGroupId",
        "capture groups",
    )
    if not capture_by_id:
        raise GoldWorkflowError("restricted bundle must contain at least one capture group")
    if list(capture_by_id) != sorted(capture_by_id):
        raise GoldWorkflowError("capture groups must be ordered by captureGroupId")

    for capture_id, capture in capture_by_id.items():
        if capture["exerciseId"] != plan["exerciseId"]:
            raise GoldWorkflowError("capture group exercise differs from the study plan")
        for field in (
            "participantId",
            "sessionId",
            "rawMediaId",
            "deviceProfileId",
            "cameraGeometryEpochId",
            "rightsReceiptId",
        ):
            _private_id(capture[field], f"capture {capture_id}.{field}")
        _sorted_unique_private_ids(
            capture["derivedArtifactIds"],
            f"capture {capture_id}.derivedArtifactIds",
            allow_empty=False,
        )
        _sorted_unique_private_ids(
            capture["contentDuplicateGroupIds"],
            f"capture {capture_id}.contentDuplicateGroupIds",
        )
        _sorted_unique_private_ids(
            capture["perceptualDuplicateGroupIds"],
            f"capture {capture_id}.perceptualDuplicateGroupIds",
        )
        _sha(capture["rawMediaPayloadSha256"], f"capture {capture_id}.rawMediaPayloadSha256")
        fingerprint_contract = _text(
            capture["perceptualFingerprintContractId"],
            f"capture {capture_id}.perceptualFingerprintContractId",
        )
        if VERSIONED_ID_RE.fullmatch(fingerprint_contract) is None:
            raise GoldWorkflowError("perceptual fingerprint contract must be a versioned identifier")
        _sha(
            capture["perceptualFingerprintArtifactSha256"],
            f"capture {capture_id}.perceptualFingerprintArtifactSha256",
        )
        derived_payloads = _array(
            capture["derivedArtifactPayloads"], f"capture {capture_id}.derivedArtifactPayloads"
        )
        payload_ids: list[str] = []
        for payload_index, raw_payload in enumerate(derived_payloads):
            payload = _object(raw_payload, f"capture {capture_id}.derivedArtifactPayloads[{payload_index}]")
            _closed(payload, {"derivedArtifactId", "payloadSha256"}, "derived artifact payload")
            payload_ids.append(_private_id(payload["derivedArtifactId"], "derivedArtifactId"))
            _sha(payload["payloadSha256"], "derived artifact payloadSha256")
        if payload_ids != capture["derivedArtifactIds"]:
            raise GoldWorkflowError("derived artifact payload provenance must exactly match sorted IDs")
        _enum(capture["deviceTier"], {"LOW", "MAINSTREAM"}, f"capture {capture_id}.deviceTier")
        _enum(capture["split"], SPLITS, f"capture {capture_id}.split")
        _validate_view_decision(
            capture["authoritativeView"],
            label=f"capture {capture_id}.authoritativeView",
            allowed_views=plan_views,
            expected_geometry_epoch=capture["cameraGeometryEpochId"],
        )

    observation_by_id = _artifact_map(
        observations,
        OBSERVATION_TOP_LEVEL_KEYS,
        "TREX_POSE_GOLD_OBSERVATION",
        "observationId",
        "observations",
    )
    if not observation_by_id:
        raise GoldWorkflowError("restricted bundle must contain observations")
    observation_sort = [
        (record["captureGroupId"], record["frameOrdinal"], record["observationId"])
        for record in observations
    ]
    if observation_sort != sorted(observation_sort):
        raise GoldWorkflowError("observations must be ordered by capture, frame, and observation id")
    observations_by_capture: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for observation_id, observation in observation_by_id.items():
        capture_id = _private_id(
            observation["captureGroupId"], f"observation {observation_id}.captureGroupId"
        )
        capture = capture_by_id.get(capture_id)
        if capture is None:
            raise GoldWorkflowError("observation refers to an unknown capture group")
        observations_by_capture[capture_id].append(observation)
        frame = _integer(observation["frameOrdinal"], f"observation {observation_id}.frameOrdinal", minimum=0)
        timestamp = _integer(
            observation["captureTimestampNs"],
            f"observation {observation_id}.captureTimestampNs",
            minimum=0,
        )
        pose_ms = _integer(
            observation["poseTimestampMs"], f"observation {observation_id}.poseTimestampMs", minimum=0
        )
        if pose_ms != timestamp // 1_000_000:
            raise GoldWorkflowError("pose timestamp must be the exact millisecond projection of capture time")
        if observation["observerContractArtifactSha256"] != observer[
            "observationContractArtifactSha256"
        ]:
            raise GoldWorkflowError("observation contract provenance mismatch")
        if _integer(observation["rawCandidateCount"], "observation.rawCandidateCount", minimum=0) != 1:
            raise GoldWorkflowError("Gold observation requires exactly one locked person candidate")
        _private_id(observation["personTrackEpochId"], "observation.personTrackEpochId")
        if _integer(observation["normalizedLandmarkCount"], "normalizedLandmarkCount") != 33:
            raise GoldWorkflowError("normalized MediaPipe landmark count must be exactly 33")
        if _integer(observation["worldLandmarkCount"], "worldLandmarkCount") != 33:
            raise GoldWorkflowError("world MediaPipe landmark count must be exactly 33")
        for field in (
            "normalizedLandmarksPayloadSha256",
            "worldLandmarksPayloadSha256",
            "confidencePayloadSha256",
        ):
            _sha(observation[field], f"observation {observation_id}.{field}")
        qualified_views = _sorted_unique_strings(
            observation["qualifiedViewContractIds"],
            f"observation {observation_id}.qualifiedViewContractIds",
        )
        if not set(qualified_views).issubset(plan_views):
            raise GoldWorkflowError("observation contains a non-preregistered qualified view")
        capture_view = capture["authoritativeView"]
        if capture_view["state"] == "QUALIFIED" and capture_view["viewContractId"] not in qualified_views:
            raise GoldWorkflowError("observation does not qualify the capture's authoritative view")
        _validate_geometry(
            observation["geometry"],
            label=f"observation {observation_id}.geometry",
            expected_epoch=capture["cameraGeometryEpochId"],
            expected_preprocessing_sha=observer["preprocessingArtifactSha256"],
        )
        _validate_reference_evidence(
            observation["referenceEvidence"],
            label=f"observation {observation_id}.referenceEvidence",
            capture_timestamp_ns=timestamp,
            manifest_modalities=manifest_modalities,
            phase_modalities=phase_modalities,
            clock_alignment_sha=reference_contract["clockAlignmentContractArtifactSha256"],
            maximum_alignment_error_us=reference_contract["maximumClockAlignmentErrorUs"],
            contact_attestation_contract_sha=reference_contract[
                "contactSensorAttestationContractArtifactSha256"
            ],
        )
        if observation["sourceTruthUse"] != "NOT_GOLD_NOT_READ_BY_REVIEWER":
            raise GoldWorkflowError("source truth or AI Hub vector cannot be Gold or visible to reviewers")
        if observation["activeMaskUse"] != "MOVEMENT_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD":
            raise GoldWorkflowError("active/source-derived masks cannot be phase Gold")

    capture_gap_detected: dict[str, bool] = {}
    capture_reference_modality: dict[str, str] = {}
    for capture_id, capture_observations in observations_by_capture.items():
        frames = [record["frameOrdinal"] for record in capture_observations]
        timestamps = [record["captureTimestampNs"] for record in capture_observations]
        pose_timestamps = [record["poseTimestampMs"] for record in capture_observations]
        if frames != list(range(len(frames))):
            raise GoldWorkflowError(f"capture {capture_id} frame ordinals must be contiguous from zero")
        if len(timestamps) < len(PHASE_EDGES) + 1 or any(
            current >= following for current, following in zip(timestamps, timestamps[1:])
        ):
            raise GoldWorkflowError("capture timestamps must be strictly increasing and span a cycle")
        if any(current >= following for current, following in zip(pose_timestamps, pose_timestamps[1:])):
            raise GoldWorkflowError("MediaPipe VIDEO timestamps must be strictly increasing")
        if len({record["personTrackEpochId"] for record in capture_observations}) != 1:
            raise GoldWorkflowError("a completed capture cycle cannot cross a person-track epoch")
        if len({canonical_json(record["geometry"]) for record in capture_observations}) != 1:
            raise GoldWorkflowError("geometry drift must start a new capture group")
        if len({canonical_json(record["qualifiedViewContractIds"]) for record in capture_observations}) != 1:
            raise GoldWorkflowError("qualified-view drift must start a new capture group")
        reference_timestamps = [
            record["referenceEvidence"]["referenceSampleTimestampNs"]
            for record in capture_observations
        ]
        if any(
            current >= following
            for current, following in zip(reference_timestamps, reference_timestamps[1:])
        ):
            raise GoldWorkflowError("reference sample timestamps must be strictly increasing one-to-one")
        reference_payloads = [
            record["referenceEvidence"]["payloadSha256"] for record in capture_observations
        ]
        if len(set(reference_payloads)) != len(reference_payloads):
            raise GoldWorkflowError("reference samples must not be replayed within a capture")
        modalities_in_capture = {
            record["referenceEvidence"]["modalityId"] for record in capture_observations
        }
        if len(modalities_in_capture) != 1:
            raise GoldWorkflowError("a v1 capture must bind one continuous reference modality")
        capture_reference_modality[capture_id] = next(iter(modalities_in_capture))
        maximum_gap = observer["maximumCaptureGapNs"]
        capture_gap_detected[capture_id] = any(
            following - current > maximum_gap
            for current, following in zip(timestamps, timestamps[1:])
        )
    if set(observations_by_capture) != set(capture_by_id):
        raise GoldWorkflowError("every capture group must have production observations")

    review_by_id = _artifact_map(
        reviews,
        BLIND_REVIEW_TOP_LEVEL_KEYS,
        "TREX_POSE_GOLD_BLIND_REVIEW",
        "reviewId",
        "blind reviews",
    )
    review_sort = [
        (record["adjudicableUnitId"], record["reviewerId"], record["reviewId"])
        for record in reviews
    ]
    if review_sort != sorted(review_sort):
        raise GoldWorkflowError("blind reviews must be ordered by unit, reviewer, and review id")
    reviews_by_unit: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    unit_to_capture: dict[str, str] = {}
    reviewer_ids: set[str] = set()
    for review_id, review in review_by_id.items():
        reviewer = _private_id(review["reviewerId"], f"review {review_id}.reviewerId")
        unit = _private_id(review["adjudicableUnitId"], f"review {review_id}.adjudicableUnitId")
        capture_id = _private_id(review["captureGroupId"], f"review {review_id}.captureGroupId")
        if capture_id not in capture_by_id:
            raise GoldWorkflowError("blind review refers to an unknown capture group")
        previous_capture = unit_to_capture.setdefault(unit, capture_id)
        if previous_capture != capture_id:
            raise GoldWorkflowError("one adjudicable unit cannot span capture groups")
        reviewer_ids.add(reviewer)
        reviews_by_unit[unit].append(review)
        _validate_blinding(review["blinding"], f"review {review_id}.blinding")
        start_ns, end_ns, phase_signature = _validate_phase_decision(
            review["phaseReview"],
            label=f"review {review_id}.phaseReview",
            observation_timestamps={
                row["captureTimestampNs"] for row in observations_by_capture[capture_id]
            },
            timestamp_gap_detected=capture_gap_detected[capture_id],
        )
        view_signature = _validate_view_decision(
            review["viewReview"],
            label=f"review {review_id}.viewReview",
            allowed_views=plan_views,
            expected_geometry_epoch=capture_by_id[capture_id]["cameraGeometryEpochId"],
        )
        if view_signature != canonical_json(capture_by_id[capture_id]["authoritativeView"]):
            raise GoldWorkflowError("reviewed view must match independently bound capture view evidence")
        _validate_criterion_decisions(
            review["criterionReviews"],
            label=f"review {review_id}.criterionReviews",
            criteria=criteria,
            manifest_modalities=manifest_modalities,
            phase_start_ns=start_ns,
            phase_end_ns=end_ns,
            qualified_view=capture_by_id[capture_id]["authoritativeView"]["viewContractId"],
            bound_reference_modality=capture_reference_modality[capture_id],
        )
        # Explicit use keeps the phase result part of the validated review signature.
        if not phase_signature:
            raise GoldWorkflowError("phase review signature cannot be empty")

    if len(reviewer_ids) != 3:
        raise GoldWorkflowError("a bundle must use exactly three blinded reviewers")
    expected_reviewers = sorted(reviewer_ids)
    for unit, unit_reviews in reviews_by_unit.items():
        if len(unit_reviews) != 3 or sorted(row["reviewerId"] for row in unit_reviews) != expected_reviewers:
            raise GoldWorkflowError(f"adjudicable unit {unit} must have exactly the same three reviewers")
    if set(unit_to_capture.values()) != set(capture_by_id) or len(unit_to_capture) != len(capture_by_id):
        raise GoldWorkflowError("each capture group must map to exactly one adjudicable cycle unit")

    adjudication_by_id = _artifact_map(
        adjudications,
        ADJUDICATION_TOP_LEVEL_KEYS,
        "TREX_POSE_GOLD_ADJUDICATION",
        "adjudicationId",
        "adjudications",
    )
    adjudication_sort = [
        (record["adjudicableUnitId"], record["adjudicationId"]) for record in adjudications
    ]
    if adjudication_sort != sorted(adjudication_sort):
        raise GoldWorkflowError("adjudications must be ordered by unit and adjudication id")
    adjudication_by_unit: dict[str, Mapping[str, Any]] = {}
    criterion_decision_count = 0
    eligible_cycle_count = 0
    for adjudication_id, adjudication in adjudication_by_id.items():
        unit = _private_id(
            adjudication["adjudicableUnitId"], f"adjudication {adjudication_id}.adjudicableUnitId"
        )
        capture_id = _private_id(
            adjudication["captureGroupId"], f"adjudication {adjudication_id}.captureGroupId"
        )
        unit_reviews = reviews_by_unit.get(unit)
        if unit_reviews is None or unit_to_capture[unit] != capture_id:
            raise GoldWorkflowError("adjudication unit/capture does not match reviewed evidence")
        if unit in adjudication_by_unit:
            raise GoldWorkflowError("an adjudicable unit has more than one adjudication")
        adjudication_by_unit[unit] = adjudication
        review_ids = _sorted_unique_private_ids(
            adjudication["reviewIds"], f"adjudication {adjudication_id}.reviewIds", allow_empty=False
        )
        if review_ids != sorted(row["reviewId"] for row in unit_reviews):
            raise GoldWorkflowError("adjudication must cite exactly the three submitted reviews")
        method = _enum(
            adjudication["decisionMethod"],
            {"UNANIMOUS", "PANEL_CONSENSUS", "UNRESOLVED_QUARANTINE"},
            f"adjudication {adjudication_id}.decisionMethod",
        )
        observation_timestamps = {
            row["captureTimestampNs"] for row in observations_by_capture[capture_id]
        }
        start_ns, end_ns, phase_signature = _validate_phase_decision(
            adjudication["phaseAdjudication"],
            label=f"adjudication {adjudication_id}.phaseAdjudication",
            observation_timestamps=observation_timestamps,
            timestamp_gap_detected=capture_gap_detected[capture_id],
        )
        view_signature = _validate_view_decision(
            adjudication["viewAdjudication"],
            label=f"adjudication {adjudication_id}.viewAdjudication",
            allowed_views=plan_views,
            expected_geometry_epoch=capture_by_id[capture_id]["cameraGeometryEpochId"],
        )
        criterion_signature = _validate_criterion_decisions(
            adjudication["criterionAdjudications"],
            label=f"adjudication {adjudication_id}.criterionAdjudications",
            criteria=criteria,
            manifest_modalities=manifest_modalities,
            phase_start_ns=start_ns,
            phase_end_ns=end_ns,
            qualified_view=capture_by_id[capture_id]["authoritativeView"]["viewContractId"],
            bound_reference_modality=capture_reference_modality[capture_id],
        )
        criterion_decision_count += len(adjudication["criterionAdjudications"])
        agreement = _validate_agreement(
            adjudication["agreement"], f"adjudication {adjudication_id}.agreement"
        )
        review_phase_signatures = {canonical_json(row["phaseReview"]) for row in unit_reviews}
        review_view_signatures = {canonical_json(row["viewReview"]) for row in unit_reviews}
        review_criterion_signatures = {
            canonical_json(row["criterionReviews"]) for row in unit_reviews
        }
        unanimous = (
            len(review_phase_signatures) == 1
            and len(review_view_signatures) == 1
            and len(review_criterion_signatures) == 1
        )
        expected_agreement = _recompute_agreement(
            unit_reviews, adjudication["criterionAdjudications"]
        )
        if any(abs(agreement[key] - expected_agreement[key]) > 1e-12 for key in agreement):
            raise GoldWorkflowError("agreement statistics differ from the three submitted reviews")
        if method == "UNANIMOUS":
            if not unanimous:
                raise GoldWorkflowError("UNANIMOUS adjudication cannot hide review disagreement")
            if phase_signature not in review_phase_signatures or view_signature not in review_view_signatures or criterion_signature not in review_criterion_signatures:
                raise GoldWorkflowError("UNANIMOUS adjudication must exactly preserve submitted decisions")
        elif method == "PANEL_CONSENSUS":
            raise GoldWorkflowError("synthetic v1 cannot authorize panel-resolved determinate Gold")
        if method == "UNRESOLVED_QUARANTINE":
            if adjudication["phaseAdjudication"]["state"] != "UNKNOWN_GOLD" or adjudication[
                "viewAdjudication"
            ]["state"] != "UNKNOWN_GOLD":
                raise GoldWorkflowError("unresolved phase/view disagreement must remain UNKNOWN_GOLD")
            if any(
                row["goldState"] not in {"UNKNOWN_GOLD", "NOT_OBSERVABLE"}
                for row in adjudication["criterionAdjudications"]
            ):
                raise GoldWorkflowError("unresolved criterion disagreement must remain unknown")
        if agreement["krippendorffAlpha"] < 0.80 and any(
            row["goldState"] in {"CONDITION_SATISFIED", "CONDITION_VIOLATED"}
            for row in adjudication["criterionAdjudications"]
        ):
            raise GoldWorkflowError("agreement below 0.80 cannot produce determinate criterion Gold")
        if (
            adjudication["phaseAdjudication"]["state"] == "COMPLETE"
            and adjudication["viewAdjudication"]["state"] == "QUALIFIED"
            and method != "UNRESOLVED_QUARANTINE"
        ):
            eligible_cycle_count += 1
    if set(adjudication_by_unit) != set(reviews_by_unit):
        raise GoldWorkflowError("every reviewed unit must have exactly one adjudication")

    split_counts = _validate_split_manifest(
        split_manifest,
        captures=capture_by_id,
        observations_by_capture=observations_by_capture,
        expected_split_seal_sha=manifest["splitSealArtifactSha256"],
    )
    return {
        "adjudicatedCriterionDecisionCount": criterion_decision_count,
        "adjudicatedPhaseCycleCount": len(adjudication_by_unit),
        "adjudicatedViewDecisionCount": len(adjudication_by_unit),
        "captureGroupCount": len(capture_by_id),
        "deviceTierCount": len({row["deviceTier"] for row in captures}),
        "eligibleGoldCycleCount": eligible_cycle_count,
        "externalTestCaptureGroupCount": split_counts["EXTERNAL_TEST"],
        "lockedInternalTestCaptureGroupCount": split_counts["LOCKED_INTERNAL_TEST"],
        "participantCount": len({row["participantId"] for row in captures}),
        "realRestrictedBundleCount": 0,
        "reviewerCount": len(reviewer_ids),
    }


def _artifact_map(
    records: Sequence[Mapping[str, Any]],
    expected_keys: Iterable[str],
    expected_kind: str,
    id_field: str,
    label: str,
) -> dict[str, Mapping[str, Any]]:
    result: dict[str, Mapping[str, Any]] = {}
    for index, record in enumerate(records):
        record_label = f"{label}[{index}]"
        _validate_artifact_record(record, expected_keys, expected_kind, record_label)
        record_id = _private_id(record[id_field], f"{record_label}.{id_field}")
        if record_id in result:
            raise GoldWorkflowError(f"duplicate {id_field} in {label}")
        result[record_id] = record
    return result


def _validate_view_decision(
    raw: Any,
    *,
    label: str,
    allowed_views: set[str],
    expected_geometry_epoch: str,
) -> str:
    value = _object(raw, label)
    _closed(
        value,
        {
            "state",
            "viewContractId",
            "evidenceSource",
            "evidenceArtifactSha256",
            "frontRearResolved",
            "cameraGeometryEpochId",
        },
        label,
    )
    if value["cameraGeometryEpochId"] != expected_geometry_epoch:
        raise GoldWorkflowError(f"{label} is not bound to the capture geometry epoch")
    state = _enum(value["state"], {"QUALIFIED", "UNKNOWN_GOLD"}, f"{label}.state")
    front_rear = _boolean(value["frontRearResolved"], f"{label}.frontRearResolved")
    if state == "QUALIFIED":
        view = _text(value["viewContractId"], f"{label}.viewContractId")
        if view not in allowed_views:
            raise GoldWorkflowError(f"{label} uses a non-preregistered view")
        if value["evidenceSource"] not in {
            "CAPTURE_SETUP_ATTESTATION",
            "INDEPENDENT_IMAGE_REVIEW",
        }:
            raise GoldWorkflowError(f"{label} lacks authoritative non-pose view evidence")
        _sha(value["evidenceArtifactSha256"], f"{label}.evidenceArtifactSha256")
        if ("front" in view or "rear" in view) and not front_rear:
            raise GoldWorkflowError(f"{label} must explicitly resolve front versus rear")
    else:
        if value["viewContractId"] is not None or value["evidenceArtifactSha256"] is not None:
            raise GoldWorkflowError(f"{label} UNKNOWN_GOLD cannot carry authoritative view claims")
        if value["evidenceSource"] != "NONE" or front_rear:
            raise GoldWorkflowError(f"{label} UNKNOWN_GOLD must fail closed")
    return canonical_json(value)


def _validate_geometry(
    raw: Any,
    *,
    label: str,
    expected_epoch: str,
    expected_preprocessing_sha: str,
) -> None:
    value = _object(raw, label)
    _closed(
        value,
        {
            "sourceImageSize",
            "cropRectangleHalfOpen",
            "inputRotationDegrees",
            "uprightOutputImageSize",
            "inferencePixelsMirrored",
            "displayMirrored",
            "geometryContextArtifactSha256",
            "preprocessingArtifactSha256",
            "cameraGeometryEpochId",
        },
        label,
    )
    source = _object(value["sourceImageSize"], f"{label}.sourceImageSize")
    output = _object(value["uprightOutputImageSize"], f"{label}.uprightOutputImageSize")
    crop = _object(value["cropRectangleHalfOpen"], f"{label}.cropRectangleHalfOpen")
    _closed(source, {"width", "height"}, f"{label}.sourceImageSize")
    _closed(output, {"width", "height"}, f"{label}.uprightOutputImageSize")
    _closed(
        crop,
        {"left", "top", "rightExclusive", "bottomExclusive"},
        f"{label}.cropRectangleHalfOpen",
    )
    source_width = _integer(source["width"], f"{label}.sourceImageSize.width", minimum=1)
    source_height = _integer(source["height"], f"{label}.sourceImageSize.height", minimum=1)
    left = _integer(crop["left"], f"{label}.crop.left", minimum=0)
    top = _integer(crop["top"], f"{label}.crop.top", minimum=0)
    right = _integer(crop["rightExclusive"], f"{label}.crop.rightExclusive", minimum=1)
    bottom = _integer(crop["bottomExclusive"], f"{label}.crop.bottomExclusive", minimum=1)
    if not (left < right <= source_width and top < bottom <= source_height):
        raise GoldWorkflowError(f"{label} has an invalid half-open crop rectangle")
    rotation = _integer(value["inputRotationDegrees"], f"{label}.inputRotationDegrees", minimum=0)
    if rotation not in {0, 90, 180, 270}:
        raise GoldWorkflowError(f"{label}.inputRotationDegrees must be 0, 90, 180, or 270")
    crop_width, crop_height = right - left, bottom - top
    expected_width, expected_height = (
        (crop_width, crop_height) if rotation in {0, 180} else (crop_height, crop_width)
    )
    if (
        _integer(output["width"], f"{label}.uprightOutputImageSize.width", minimum=1)
        != expected_width
        or _integer(output["height"], f"{label}.uprightOutputImageSize.height", minimum=1)
        != expected_height
    ):
        raise GoldWorkflowError(f"{label} upright dimensions do not match crop and rotation")
    if _boolean(value["inferencePixelsMirrored"], f"{label}.inferencePixelsMirrored"):
        raise GoldWorkflowError("mirrored inference pixels are forbidden; display mirroring is separate")
    _boolean(value["displayMirrored"], f"{label}.displayMirrored")
    if value["preprocessingArtifactSha256"] != expected_preprocessing_sha:
        raise GoldWorkflowError(f"{label} preprocessing provenance mismatch")
    if value["cameraGeometryEpochId"] != expected_epoch:
        raise GoldWorkflowError(f"{label} geometry epoch mismatch")
    expected_geometry_sha = canonical_fields_sha256(
        [
            ("poseCameraGeometryContextSchemaVersion", "1"),
            ("coordinateDomain", "UPRIGHT_CROPPED_NORMALIZED_IMAGE"),
            ("sourceImageWidth", str(source_width)),
            ("sourceImageHeight", str(source_height)),
            ("cropLeft", str(left)),
            ("cropTop", str(top)),
            ("cropRightExclusive", str(right)),
            ("cropBottomExclusive", str(bottom)),
            ("inputRotationDegrees", str(rotation)),
            ("outputImageWidth", str(expected_width)),
            ("outputImageHeight", str(expected_height)),
            ("outputRotationDegrees", "0"),
            ("inferencePixelsMirrored", "false"),
            (
                "displayMirrored",
                "true" if value["displayMirrored"] else "false",
            ),
            ("preprocessingArtifactSha256", expected_preprocessing_sha),
        ]
    )
    if value["geometryContextArtifactSha256"] != expected_geometry_sha:
        raise GoldWorkflowError(f"{label} geometry hash differs from Kotlin canonical-fields parity")


def canonical_fields_sha256(fields: Sequence[tuple[str, str]]) -> str:
    payload = "".join(
        f"{name}:{len(value.encode('utf-8'))}:{value}\n" for name, value in fields
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _validate_reference_evidence(
    raw: Any,
    *,
    label: str,
    capture_timestamp_ns: int,
    manifest_modalities: set[str],
    phase_modalities: set[str],
    clock_alignment_sha: str,
    maximum_alignment_error_us: int,
    contact_attestation_contract_sha: str,
) -> None:
    value = _object(raw, label)
    _closed(
        value,
        {
            "modalityId",
            "referenceSampleTimestampNs",
            "alignedCaptureTimestampNs",
            "clockAlignmentArtifactSha256",
            "absoluteAlignmentErrorUs",
            "maximumAllowedAlignmentErrorUs",
            "alignmentAccepted",
            "payloadSha256",
            "sensorAttestationArtifactSha256",
        },
        label,
    )
    modality = _text(value["modalityId"], f"{label}.modalityId")
    if modality not in manifest_modalities or modality not in phase_modalities:
        raise GoldWorkflowError(f"{label} modality is not preregistered for phase reference")
    if modality.startswith("RUNTIME_"):
        raise GoldWorkflowError(f"{label} cannot use candidate-derived runtime reference")
    _integer(value["referenceSampleTimestampNs"], f"{label}.referenceSampleTimestampNs", minimum=0)
    aligned = _integer(
        value["alignedCaptureTimestampNs"], f"{label}.alignedCaptureTimestampNs", minimum=0
    )
    if value["clockAlignmentArtifactSha256"] != clock_alignment_sha:
        raise GoldWorkflowError(f"{label} clock-alignment provenance mismatch")
    actual_error_us = abs(aligned - capture_timestamp_ns) // 1_000
    declared_error_us = _integer(
        value["absoluteAlignmentErrorUs"], f"{label}.absoluteAlignmentErrorUs", minimum=0
    )
    maximum_error_us = _integer(
        value["maximumAllowedAlignmentErrorUs"],
        f"{label}.maximumAllowedAlignmentErrorUs",
        minimum=0,
    )
    if maximum_error_us != maximum_alignment_error_us:
        raise GoldWorkflowError(f"{label} cannot choose its own alignment tolerance")
    if declared_error_us != actual_error_us or declared_error_us > maximum_error_us:
        raise GoldWorkflowError(f"{label} violates the declared clock-alignment tolerance")
    if _boolean(value["alignmentAccepted"], f"{label}.alignmentAccepted") is not True:
        raise GoldWorkflowError(f"{label} must be explicitly accepted by the clock contract")
    _sha(value["payloadSha256"], f"{label}.payloadSha256")
    attestation = value["sensorAttestationArtifactSha256"]
    if modality == CONTACT_MODALITY:
        if attestation != contact_attestation_contract_sha:
            raise GoldWorkflowError(f"{label} contact evidence lacks the pinned sensor attestation")
    elif attestation is not None:
        raise GoldWorkflowError(f"{label} non-contact evidence cannot carry a sensor attestation")


def _validate_blinding(raw: Any, label: str) -> None:
    value = _object(raw, label)
    expected = {
        "runtimeOutputVisible": False,
        "candidateThresholdVisible": False,
        "aiHubTruthVectorVisible": False,
        "otherReviewVisibleBeforeSubmission": False,
        "splitVisible": False,
    }
    _closed(value, expected, label)
    if value != expected:
        raise GoldWorkflowError(f"{label} violates independent reviewer blindness")


def _validate_phase_decision(
    raw: Any,
    *,
    label: str,
    observation_timestamps: set[int],
    timestamp_gap_detected: bool,
) -> tuple[int | None, int | None, str]:
    value = _object(raw, label)
    _closed(
        value,
        {
            "state",
            "cycleIntervalConvention",
            "orderedTopology",
            "boundaries",
            "timestampGapCrossed",
            "unknownReasonCodes",
        },
        label,
    )
    if value["cycleIntervalConvention"] != "START_INCLUSIVE_END_EXCLUSIVE":
        raise GoldWorkflowError(f"{label} must use half-open cycle intervals")
    if tuple(value["orderedTopology"]) != PHASE_TOPOLOGY:
        raise GoldWorkflowError(f"{label} topology differs from the preregistered squat cycle")
    declared_gap = _boolean(value["timestampGapCrossed"], f"{label}.timestampGapCrossed")
    if declared_gap != timestamp_gap_detected:
        raise GoldWorkflowError(f"{label} timestamp-gap declaration differs from observations")
    state = _enum(value["state"], {"COMPLETE", "UNKNOWN_GOLD"}, f"{label}.state")
    reasons = _sorted_unique_strings(value["unknownReasonCodes"], f"{label}.unknownReasonCodes")
    boundaries = _array(value["boundaries"], f"{label}.boundaries")
    if state == "UNKNOWN_GOLD":
        if boundaries or not reasons:
            raise GoldWorkflowError(f"{label} unknown phase requires reasons and no inferred boundaries")
        return None, None, canonical_json(value)
    if timestamp_gap_detected:
        raise GoldWorkflowError(f"{label} cannot interpolate a complete cycle across a timestamp gap")
    if reasons or len(boundaries) != len(PHASE_EDGES):
        raise GoldWorkflowError(f"{label} complete cycle requires the exact four transitions")
    selected: list[int] = []
    uncertainty: list[tuple[int, int]] = []
    capture_min, capture_max = min(observation_timestamps), max(observation_timestamps)
    for index, (raw_boundary, edge) in enumerate(zip(boundaries, PHASE_EDGES)):
        boundary = _object(raw_boundary, f"{label}.boundaries[{index}]")
        _closed(
            boundary,
            {
                "fromPhase",
                "toPhase",
                "earliestTimestampNs",
                "selectedTimestampNs",
                "latestTimestampNs",
            },
            f"{label}.boundaries[{index}]",
        )
        if (boundary["fromPhase"], boundary["toPhase"]) != edge:
            raise GoldWorkflowError(f"{label} phase transition order differs")
        earliest = _integer(boundary["earliestTimestampNs"], "boundary earliest", minimum=0)
        chosen = _integer(boundary["selectedTimestampNs"], "boundary selected", minimum=0)
        latest = _integer(boundary["latestTimestampNs"], "boundary latest", minimum=0)
        if not capture_min <= earliest <= chosen <= latest <= capture_max or chosen not in observation_timestamps:
            raise GoldWorkflowError(f"{label} boundary uncertainty or timestamp provenance is invalid")
        selected.append(chosen)
        uncertainty.append((earliest, latest))
    if any(current >= following for current, following in zip(selected, selected[1:])):
        raise GoldWorkflowError(f"{label} transition boundaries must be strictly monotonic")
    if any(current[1] >= following[0] for current, following in zip(uncertainty, uncertainty[1:])):
        raise GoldWorkflowError(f"{label} boundary uncertainty intervals must not overlap")
    return selected[0], selected[-1], canonical_json(value)


def _validate_criterion_decisions(
    raw: Any,
    *,
    label: str,
    criteria: Mapping[str, Mapping[str, Any]],
    manifest_modalities: set[str],
    phase_start_ns: int | None,
    phase_end_ns: int | None,
    qualified_view: str | None,
    bound_reference_modality: str,
) -> str:
    values = _array(raw, label)
    actual: dict[tuple[str, str], Mapping[str, Any]] = {}
    expected: set[tuple[str, str]] = set()
    for source_id, criterion in criteria.items():
        roles = ("LEFT", "RIGHT") if criterion["sidePolicy"] == "BILATERAL_INDEPENDENT" else ("MIDLINE",)
        expected.update((source_id, role) for role in roles)
    for index, raw_value in enumerate(values):
        value = _object(raw_value, f"{label}[{index}]")
        _closed(
            value,
            {
                "bindingKey",
                "sideRole",
                "goldState",
                "phaseScope",
                "viewContractId",
                "referenceModalityId",
                "attestedContactSensorEvidence",
            },
            f"{label}[{index}]",
        )
        key = _object(value["bindingKey"], f"{label}[{index}].bindingKey")
        _closed(
            key,
            {"exerciseId", "sourceConditionId", "bindingId", "bindingPolicySha256", "policyRegistrySha256"},
            f"{label}[{index}].bindingKey",
        )
        source_id = _text(key["sourceConditionId"], f"{label}[{index}].sourceConditionId")
        criterion = criteria.get(source_id)
        if criterion is None or key != criterion["bindingKey"]:
            raise GoldWorkflowError(f"{label} contains a non-authoritative exact binding tuple")
        role = _enum(value["sideRole"], {"LEFT", "RIGHT", "MIDLINE"}, f"{label}[{index}].sideRole")
        pair = (source_id, role)
        if pair in actual:
            raise GoldWorkflowError(f"{label} contains a duplicate binding/side decision")
        actual[pair] = value
        gold_state = _enum(value["goldState"], GOLD_STATES, f"{label}[{index}].goldState")
        scope = _object(value["phaseScope"], f"{label}[{index}].phaseScope")
        _closed(scope, {"startTimestampNs", "endTimestampNs", "intervalConvention"}, f"{label}[{index}].phaseScope")
        if scope["intervalConvention"] != "START_INCLUSIVE_END_EXCLUSIVE":
            raise GoldWorkflowError("criterion phase scope must use a half-open interval")
        if phase_start_ns is None:
            if scope["startTimestampNs"] is not None or scope["endTimestampNs"] is not None:
                raise GoldWorkflowError("unknown phase cannot acquire an inferred criterion interval")
        else:
            if scope["startTimestampNs"] != phase_start_ns or scope["endTimestampNs"] != phase_end_ns:
                raise GoldWorkflowError("criterion decision must bind the adjudicated cycle interval")
        contact_evidence = _boolean(
            value["attestedContactSensorEvidence"], f"{label}[{index}].attestedContactSensorEvidence"
        )
        modality = value["referenceModalityId"]
        view = value["viewContractId"]
        if modality is not None:
            modality = _text(modality, f"{label}[{index}].referenceModalityId")
            if modality not in criterion["permittedReferenceModalityIds"] or modality not in manifest_modalities:
                raise GoldWorkflowError("criterion decision uses an undeclared reference modality")
        if view is not None:
            view = _text(view, f"{label}[{index}].viewContractId")
            if view not in criterion["viewContractIds"] or view != qualified_view:
                raise GoldWorkflowError("criterion decision uses an inapplicable or unqualified view")
        determinate = gold_state in {"CONDITION_SATISFIED", "CONDITION_VIOLATED"}
        if determinate and (phase_start_ns is None or phase_end_ns is None):
            raise GoldWorkflowError(
                "determinate criterion Gold requires a complete adjudicated phase interval"
            )
        is_contact = criterion["observability"] == "NOT_OBSERVABLE"
        if is_contact:
            # The v1 observation schema has one phase-reference payload per frame and cannot
            # simultaneously bind a synchronized contact channel.  A boolean self-attestation
            # is not evidence, so the vertical slice intentionally keeps plantar contact
            # non-determinate until a versioned multi-modality record exists.
            if determinate or contact_evidence:
                raise GoldWorkflowError(
                    "plantar contact is NOT_OBSERVABLE in v1; a future synchronized sensor schema is required"
                )
            if gold_state not in {"UNKNOWN_GOLD", "NOT_OBSERVABLE"}:
                raise GoldWorkflowError("plantar contact without a bound sensor must fail closed")
            if modality is not None:
                raise GoldWorkflowError("non-determinate plantar v1 decision cannot claim sensor provenance")
            if view is not None:
                raise GoldWorkflowError("plantar contact cannot be inferred from a camera view")
        else:
            if contact_evidence:
                raise GoldWorkflowError("contact-sensor attestation cannot authorize a non-contact criterion")
            if determinate and (modality is None or view is None):
                raise GoldWorkflowError("determinate visual criterion Gold needs applicable view and reference")
            if determinate and modality != bound_reference_modality:
                raise GoldWorkflowError(
                    "determinate visual criterion must bind the capture's synchronized reference modality"
                )
    if set(actual) != expected:
        raise GoldWorkflowError(f"{label} must cover every preregistered binding and side exactly once")
    if list(actual) != sorted(actual):
        raise GoldWorkflowError(f"{label} must be deterministically ordered by condition and side")
    return canonical_json(values)


def _validate_agreement(raw: Any, label: str) -> dict[str, float]:
    value = _object(raw, label)
    keys = {
        "krippendorffAlpha",
        "rawAgreement",
        "positiveAgreement",
        "negativeAgreement",
        "unknownGoldRate",
        "adjudicationChangeRate",
    }
    _closed(value, keys, label)
    return {key: _number(value[key], f"{label}.{key}", minimum=0.0, maximum=1.0) for key in keys}


def _recompute_agreement(
    reviews: Sequence[Mapping[str, Any]], adjudicated: Sequence[Mapping[str, Any]]
) -> dict[str, float]:
    ratings_by_item = list(
        zip(*[[row["goldState"] for row in review["criterionReviews"]] for review in reviews])
    )
    pair_total = 0
    pair_equal = 0
    positive_total = positive_equal = 0
    negative_total = negative_equal = 0
    all_ratings: list[str] = []
    for ratings in ratings_by_item:
        all_ratings.extend(ratings)
        for left in range(len(ratings)):
            for right in range(left + 1, len(ratings)):
                pair_total += 1
                pair_equal += int(ratings[left] == ratings[right])
                if "CONDITION_SATISFIED" in {ratings[left], ratings[right]}:
                    positive_total += 1
                    positive_equal += int(
                        ratings[left] == ratings[right] == "CONDITION_SATISFIED"
                    )
                if "CONDITION_VIOLATED" in {ratings[left], ratings[right]}:
                    negative_total += 1
                    negative_equal += int(
                        ratings[left] == ratings[right] == "CONDITION_VIOLATED"
                    )
    observed_disagreement = 1.0 - (pair_equal / pair_total if pair_total else 1.0)
    counts = Counter(all_ratings)
    total = len(all_ratings)
    expected_agreement = (
        sum(count * (count - 1) for count in counts.values()) / (total * (total - 1))
        if total > 1
        else 1.0
    )
    expected_disagreement = 1.0 - expected_agreement
    alpha = (
        1.0
        if expected_disagreement == 0.0 and observed_disagreement == 0.0
        else max(0.0, 1.0 - observed_disagreement / expected_disagreement)
        if expected_disagreement > 0.0
        else 0.0
    )
    adjudicated_states = [row["goldState"] for row in adjudicated]
    changed = 0
    for ratings, state in zip(ratings_by_item, adjudicated_states):
        frequencies = Counter(ratings)
        best = max(frequencies.values())
        modes = {candidate for candidate, count in frequencies.items() if count == best}
        changed += int(len(modes) != 1 or state not in modes)
    return {
        "krippendorffAlpha": alpha,
        "rawAgreement": pair_equal / pair_total if pair_total else 1.0,
        "positiveAgreement": positive_equal / positive_total if positive_total else 1.0,
        "negativeAgreement": negative_equal / negative_total if negative_total else 1.0,
        "unknownGoldRate": (
            sum(state in {"UNKNOWN_GOLD", "NOT_OBSERVABLE"} for state in adjudicated_states)
            / len(adjudicated_states)
            if adjudicated_states
            else 1.0
        ),
        "adjudicationChangeRate": changed / len(adjudicated_states) if adjudicated_states else 1.0,
    }


def _validate_split_manifest(
    split_manifest: Mapping[str, Any],
    *,
    captures: Mapping[str, Mapping[str, Any]],
    observations_by_capture: Mapping[str, Sequence[Mapping[str, Any]]],
    expected_split_seal_sha: str,
) -> dict[str, int]:
    _validate_artifact_record(
        split_manifest,
        SPLIT_MANIFEST_TOP_LEVEL_KEYS,
        "TREX_POSE_GOLD_SPLIT_MANIFEST",
        "split manifest",
    )
    _private_id(split_manifest["splitManifestId"], "splitManifestId")
    if split_manifest["splitSealArtifactSha256"] != expected_split_seal_sha:
        raise GoldWorkflowError("split manifest seal differs from bundle manifest")
    if _boolean(split_manifest["assignmentPrecedesOutcomeReview"], "assignmentPrecedesOutcomeReview") is not True:
        raise GoldWorkflowError("split assignment must precede outcome review")
    if split_manifest["lockedTestAccessState"] != "UNCONSUMED":
        raise GoldWorkflowError("locked test must be UNCONSUMED at intake")
    assignments = _array(split_manifest["assignments"], "split manifest.assignments")
    if not assignments:
        raise GoldWorkflowError("split manifest must contain assignments")
    participant_assignment: dict[str, Mapping[str, Any]] = {}
    component_split: dict[tuple[str, str], str] = {}
    split_counts = Counter({split: 0 for split in SPLITS})
    for index, raw in enumerate(assignments):
        assignment = _object(raw, f"split assignments[{index}]")
        _closed(
            assignment,
            {
                "participantId",
                "sessionIds",
                "captureGroupIds",
                "rawMediaIds",
                "derivedArtifactIds",
                "contentDuplicateGroupIds",
                "perceptualDuplicateGroupIds",
                "split",
            },
            f"split assignments[{index}]",
        )
        participant = _private_id(assignment["participantId"], "split participantId")
        if participant in participant_assignment:
            raise GoldWorkflowError("participant appears in more than one split assignment")
        participant_assignment[participant] = assignment
        split = _enum(assignment["split"], SPLITS, "split assignment.split")
        for field, kind in (
            ("sessionIds", "session"),
            ("captureGroupIds", "capture"),
            ("rawMediaIds", "raw"),
            ("derivedArtifactIds", "derived"),
            ("contentDuplicateGroupIds", "duplicate"),
            ("perceptualDuplicateGroupIds", "perceptual-duplicate"),
        ):
            values = _sorted_unique_private_ids(
                assignment[field],
                f"split assignment.{field}",
                allow_empty=field in {"contentDuplicateGroupIds", "perceptualDuplicateGroupIds"},
            )
            for identifier in values:
                existing = component_split.setdefault((kind, identifier), split)
                if existing != split:
                    raise GoldWorkflowError(f"{kind} identity or duplicate group crosses splits")
        split_counts[split] += len(assignment["captureGroupIds"])
    if list(participant_assignment) != sorted(participant_assignment):
        raise GoldWorkflowError("split assignments must be ordered by participantId")
    if set(split_counts) != SPLITS or any(split_counts[split] < 1 for split in SPLITS):
        raise GoldWorkflowError("development, calibration, locked internal, and external splits are required")

    assigned_captures: set[str] = set()
    for capture_id, capture in captures.items():
        assignment = participant_assignment.get(capture["participantId"])
        if assignment is None:
            raise GoldWorkflowError("capture participant is absent from the split ledger")
        split = assignment["split"]
        if capture["split"] != split:
            raise GoldWorkflowError("capture split differs from participant assignment")
        expected_membership = {
            "sessionIds": capture["sessionId"],
            "captureGroupIds": capture_id,
            "rawMediaIds": capture["rawMediaId"],
        }
        for field, identifier in expected_membership.items():
            if identifier not in assignment[field]:
                raise GoldWorkflowError(f"capture {field} is absent from its atomic split assignment")
        if not set(capture["derivedArtifactIds"]).issubset(assignment["derivedArtifactIds"]):
            raise GoldWorkflowError("capture derived artifacts are absent from the atomic split assignment")
        if not set(capture["contentDuplicateGroupIds"]).issubset(
            assignment["contentDuplicateGroupIds"]
        ):
            raise GoldWorkflowError("capture duplicate groups are absent from the atomic split assignment")
        if not set(capture["perceptualDuplicateGroupIds"]).issubset(
            assignment["perceptualDuplicateGroupIds"]
        ):
            raise GoldWorkflowError("capture perceptual duplicate groups are absent from the atomic split assignment")
        assigned_captures.add(capture_id)
    ledger_capture_ids = {
        identifier for kind, identifier in component_split if kind == "capture"
    }
    if ledger_capture_ids != assigned_captures:
        raise GoldWorkflowError("split ledger contains missing or extraneous capture groups")
    payload_split: dict[str, str] = {}
    for capture_id, capture in captures.items():
        split = capture["split"]
        payloads = [capture["rawMediaPayloadSha256"]] + [
            row["payloadSha256"]
            for row in capture["derivedArtifactPayloads"]
        ] + [
            digest
            for observation in observations_by_capture[capture_id]
            for digest in (
                observation["normalizedLandmarksPayloadSha256"],
                observation["worldLandmarksPayloadSha256"],
                observation["confidencePayloadSha256"],
                observation["referenceEvidence"]["payloadSha256"],
            )
        ]
        for digest in payloads:
            existing = payload_split.setdefault(digest, split)
            if existing != split:
                raise GoldWorkflowError("exact evidence payload bytes cross split boundaries")
    return dict(split_counts)


def compile_receipt(
    protocol: Mapping[str, Any],
    rights: Mapping[str, Any],
    plan: Mapping[str, Any],
    *,
    source_coverage: Mapping[str, Any],
    compiled_policy: Mapping[str, Any],
    bundle_root: Path | None = None,
    evidence_class: str = "PLAN_ONLY",
) -> dict[str, Any]:
    protocol_sha = _validate_protocol(protocol)
    rights_sha = _validate_rights(rights)
    plan_sha = _validate_study_plan(
        plan,
        protocol=protocol,
        protocol_sha=protocol_sha,
        rights_sha=rights_sha,
        source=source_coverage,
        compiled_policy=compiled_policy,
    )
    evidence_class = _enum(evidence_class, EVIDENCE_CLASSES, "evidence class")
    if bundle_root is None and evidence_class != "PLAN_ONLY":
        raise GoldWorkflowError("non-plan evidence class requires --bundle-root")
    if bundle_root is not None and evidence_class == "PLAN_ONLY":
        raise GoldWorkflowError("--bundle-root requires an explicit non-plan evidence class")
    if evidence_class == "REAL_RESTRICTED_GOLD":
        # V1 deliberately has no trust registry or detached-signature verifier.  Reject before
        # even stat'ing the restricted path so forbidden real evidence is never unnecessarily
        # parsed into this process.
        raise GoldWorkflowError(
            "NO_TRUSTED_RIGHTS_AUTHORITY_V1: real restricted Gold intake is forbidden"
        )
    restricted = None
    if bundle_root is not None:
        try:
            restricted = _validate_restricted_bundle(
                bundle_root,
                evidence_class=evidence_class,
                protocol=protocol,
                rights=rights,
                plan=plan,
            )
        except GoldWorkflowError:
            raise GoldWorkflowError("RESTRICTED_BUNDLE_VALIDATION_FAILED") from None
    receipt = _base_readiness_receipt(protocol, rights, plan)
    if restricted is not None:
        receipt["restrictedBundleReceipt"] = restricted
        if evidence_class == "SYNTHETIC_CONFORMANCE":
            receipt["actualAggregateCounts"]["syntheticConformanceBundleCount"] = 1
    finalized = with_artifact_sha256(receipt)
    _validate_public_receipt(finalized, protocol=protocol, rights=rights, plan=plan)
    return finalized


def _base_readiness_receipt(
    protocol: Mapping[str, Any], rights: Mapping[str, Any], plan: Mapping[str, Any]
) -> dict[str, Any]:
    counts = _object(plan["currentActualEvidenceCounts"], "study plan.currentActualEvidenceCounts")
    return {
        "schemaVersion": 1,
        "artifactKind": "TREX_BARBELL_SQUAT_GOLD_READINESS",
        "readinessId": f"trex.gold-readiness.{plan['exerciseId']}.v1",
        "exerciseId": plan["exerciseId"],
        "decisionUse": "AGGREGATE_READINESS_RECEIPT_ONLY_NOT_REAL_GOLD_CALIBRATION_RUNTIME_OR_RELEASE_AUTHORITY",
        "compilerScope": "BARBELL_SQUAT_VERTICAL_SLICE_ONLY",
        "compilerImplementation": compiler_implementation_provenance(),
        "readiness": "NOT_READY",
        "protocolArtifactSha256": protocol["artifactSha256"],
        "rightsManifestArtifactSha256": rights["artifactSha256"],
        "studyPlanArtifactSha256": plan["artifactSha256"],
        "canonicalization": dict(CANONICALIZATION),
        "authority": dict(ZERO_AUTHORITY),
        "approvalTrustState": {
            "detachedSignatureVerification": "NOT_IMPLEMENTED_IN_V1",
            "minimumProtocolVersionForPositiveRealIntake": 2,
            "pinnedTrustRegistry": "NOT_DEFINED_IN_V1",
            "sha256Slots": "SCHEMA_PLACEHOLDERS_ONLY",
            "v1VerifiedReadyOrRealIntakeTransitionAllowed": False,
        },
        "actualAggregateCounts": {
            "adjudicatedCriterionDecisionCount": counts["adjudicatedCriterionDecisionCount"],
            "adjudicatedPhaseCycleCount": counts["adjudicatedPhaseCycleCount"],
            "adjudicatedViewDecisionCount": counts["adjudicatedViewDecisionCount"],
            "calibrationArtifactCount": 0,
            "captureGroupCount": counts["captureGroupCount"],
            "deviceTierCount": 0,
            "eligibleGoldCycleCount": 0,
            "externalTestCaptureGroupCount": 0,
            "lockedInternalTestCaptureGroupCount": 0,
            "participantCount": counts["participantCount"],
            "realRestrictedBundleCount": counts["realRestrictedBundleCount"],
            "reviewerCount": counts["reviewerCount"],
            "runtimePhaseProviderCount": 0,
            "syntheticConformanceBundleCount": 0,
        },
        "evidenceState": {
            "criterionGold": "ABSENT",
            "phaseGold": "ABSENT",
            "productionMediaPipeReferencePairing": "ABSENT",
            "realRestrictedBundle": "ABSENT",
            "rights": rights["status"],
            "studyPlan": "DEFINED_NOT_EXECUTED",
            "viewGold": "ABSENT",
        },
        "blockers": [
            "DATA_RIGHTS_UNVERIFIED",
            "DETACHED_APPROVAL_SIGNATURE_VERIFICATION_NOT_IMPLEMENTED_IN_V1",
            "M6_TRAINING_SURROGATE_CONTINUATION_REJECTED",
            "NO_APPROVED_ANNOTATION_RUBRIC",
            "NO_APPROVED_ANNOTATION_TOOL",
            "NO_APPROVED_CAPTURE_PROTOCOL",
            "NO_APPROVED_COHORT_POWER_PLAN",
            "NO_APPROVED_CRITERION_QUALITY_OR_RESIDUAL_CALIBRATION",
            "NO_APPROVED_PARTICIPANT_CONSENT_RETENTION_OR_BACKUP_CONTRACT",
            "NO_APPROVED_PHASE_QUALITY_OR_BOUNDARY_CALIBRATION",
            "NO_APPROVED_REFERENCE_CLOCK_ALIGNMENT_CONTRACT",
            "NO_APPROVED_REVIEWER_ROSTER_ROOT",
            "NO_AUTHORIZED_PHASE_VIEW_OR_CAPABILITY_PROVIDER",
            "NO_REAL_CAPTURE_GROUPS_OR_PARTICIPANTS",
            "NO_REAL_RESTRICTED_GOLD_BUNDLE",
            "NO_SPLIT_SEAL",
            "NO_SUBJECT_BY_DEVICE_BY_QUALIFIED_VIEW_COHORT",
            "NO_SYNCHRONIZED_PRODUCTION_MEDIAPIPE_TO_REFERENCE_EVIDENCE",
            "NO_THREE_REVIEWER_BLINDED_PHASE_VIEW_OR_CRITERION_GOLD",
            "OFFICIAL_VALIDATION_ALREADY_CONSUMED_AND_EXCLUDED",
            "PINNED_APPROVAL_TRUST_REGISTRY_NOT_DEFINED_IN_V1",
            "PLANTAR_CONTACT_NOT_OBSERVABLE_WITHOUT_ATTESTED_CONTACT_SENSOR",
            "REAL_RESTRICTED_GOLD_INTAKE_FORBIDDEN_IN_V1",
            "VERIFIED_READY_TRANSITION_FORBIDDEN_IN_V1",
        ],
        "m6ResearchProvenance": {
            "phaseResearchContractArtifactSha256": plan["priorResearchProvenance"][
                "phaseResearchContractArtifactSha256"
            ],
            "phaseResearchReadinessArtifactSha256": plan["priorResearchProvenance"][
                "phaseResearchReadinessArtifactSha256"
            ],
            "phaseTrainingReportFingerprintSha256": plan["priorResearchProvenance"][
                "phaseTrainingReportFingerprintSha256"
            ],
            "researchContinuation": plan["priorResearchProvenance"]["researchContinuation"],
            "researchUse": plan["priorResearchProvenance"]["researchUse"],
        },
    }


def _validate_public_receipt(
    receipt: Mapping[str, Any],
    *,
    protocol: Mapping[str, Any],
    rights: Mapping[str, Any],
    plan: Mapping[str, Any],
) -> None:
    expected_keys = set(_base_readiness_receipt(protocol, rights, plan)) | {"artifactSha256"}
    has_restricted = "restrictedBundleReceipt" in receipt
    if has_restricted:
        expected_keys.add("restrictedBundleReceipt")
    _closed(receipt, expected_keys, "public readiness receipt")
    _require_self_hash(receipt, "public readiness receipt")
    expected = _base_readiness_receipt(protocol, rights, plan)
    if has_restricted:
        expected["actualAggregateCounts"]["syntheticConformanceBundleCount"] = 1
    actual_base = dict(receipt)
    actual_base.pop("artifactSha256")
    restricted = actual_base.pop("restrictedBundleReceipt", None)
    if actual_base != expected:
        raise GoldWorkflowError("public readiness receipt differs from its closed aggregate schema")
    counts = _object(receipt["actualAggregateCounts"], "receipt.actualAggregateCounts")
    _closed(counts, READINESS_AGGREGATE_COUNT_KEYS, "receipt.actualAggregateCounts")
    for key, value in counts.items():
        _integer(value, f"receipt.actualAggregateCounts.{key}", minimum=0)
    _require_zero_authority(receipt["authority"], "receipt.authority")
    if receipt["compilerImplementation"] != compiler_implementation_provenance():
        raise GoldWorkflowError("receipt compiler implementation provenance is stale")
    if restricted is not None:
        restricted_object = _object(restricted, "receipt.restrictedBundleReceipt")
        _closed(
            restricted_object,
            {
                "evidenceClass",
                "bundleRootSha256",
                "verificationState",
                "syntheticFixtureShapeCounts",
            },
            "receipt.restrictedBundleReceipt",
        )
        if restricted_object["evidenceClass"] != "SYNTHETIC_CONFORMANCE" or restricted_object[
            "verificationState"
        ] != "SYNTHETIC_SCHEMA_CONFORMANCE_ONLY_NOT_GOLD_EVIDENCE":
            raise GoldWorkflowError("restricted receipt cannot imply real or Gold authority")
        _sha(restricted_object["bundleRootSha256"], "restricted receipt bundle root")
        shape = _object(
            restricted_object["syntheticFixtureShapeCounts"],
            "restricted receipt.syntheticFixtureShapeCounts",
        )
        _closed(
            shape,
            {
                "adjudicationRecordCount",
                "blindReviewRecordCount",
                "captureRecordCount",
                "observationRecordCount",
                "splitAssignmentRecordCount",
            },
            "restricted receipt.syntheticFixtureShapeCounts",
        )
        for key, value in shape.items():
            _integer(value, f"syntheticFixtureShapeCounts.{key}", minimum=0)
    _assert_public_value_shapes(receipt)


def _assert_public_value_shapes(value: Any, path: tuple[str, ...] = ()) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            lowered = key.lower()
            if any(
                forbidden in lowered
                for forbidden in (
                    "participantid",
                    "reviewerid",
                    "sessionid",
                    "rawmediaid",
                    "capturetimestamp",
                    "posetimestamp",
                    "landmarkpayload",
                    "trajectory",
                    "leafcontenthash",
                )
            ):
                raise GoldWorkflowError("public receipt contains a forbidden private field shape")
            _assert_public_value_shapes(item, (*path, key))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _assert_public_value_shapes(item, (*path, str(index)))
    elif isinstance(value, str):
        if path[-2:] == ("compilerImplementation", "relativePath"):
            return
        lowered = value.lower()
        if re.search(r"(^|[^a-z])(participant|reviewer|session|capture|observation|person-track|raw)-", lowered):
            raise GoldWorkflowError("public receipt contains a private identifier shape")
        if re.match(r"^[a-zA-Z]:[\\/]", value) or value.startswith(("/", "\\\\")):
            raise GoldWorkflowError("public receipt contains an absolute path")
        if ".." in Path(value).parts:
            raise GoldWorkflowError("public receipt contains path traversal")


def _render_json(value: Mapping[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"


def _safe_output_path(path: Path, protected: Sequence[Path], bundle_root: Path | None) -> Path:
    absolute = Path(os.path.abspath(os.fspath(path)))
    for candidate in protected:
        if absolute == Path(os.path.abspath(os.fspath(candidate))):
            raise GoldWorkflowError("output must not overwrite an input artifact")
    if bundle_root is not None:
        root = Path(os.path.abspath(os.fspath(bundle_root)))
        try:
            absolute.relative_to(root)
        except ValueError:
            pass
        else:
            raise GoldWorkflowError("public output must be outside the restricted bundle")
    parent = absolute.parent
    if not parent.exists():
        raise GoldWorkflowError("output parent must already exist")
    _assert_no_reparse_chain(parent, require_regular=False)
    if absolute.exists():
        _assert_no_reparse_chain(absolute, require_regular=True)
    return absolute


def write_or_check(path: Path, value: Mapping[str, Any], *, check: bool) -> None:
    rendered = _render_json(value)
    if check:
        source = _assert_no_reparse_chain(path, require_regular=True)
        try:
            current = source.read_bytes()
        except OSError as error:
            raise GoldWorkflowError(f"cannot read existing readiness receipt: {error}") from error
        if current != rendered.encode("utf-8"):
            raise GoldWorkflowError(f"readiness receipt is stale: {path}")
        return
    if path.exists():
        raise GoldWorkflowError(f"output already exists; overwrite is forbidden: {path}")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
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
            # Publishing by hard-link is an atomic no-clobber operation.  In particular, a
            # target created after the earlier existence check is never overwritten.
            os.link(temporary, path)
        except FileExistsError as error:
            raise GoldWorkflowError(f"output appeared during atomic publish; overwrite refused: {path}") from error
        except OSError as error:
            raise GoldWorkflowError(f"atomic no-clobber publish is unavailable: {error}") from error
        temporary.unlink()
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--rights", type=Path, required=True)
    parser.add_argument("--study-plan", type=Path, required=True)
    parser.add_argument("--source-coverage", type=Path, default=DEFAULT_SOURCE_COVERAGE)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--policy-approval", type=Path, default=DEFAULT_POLICY_APPROVAL)
    parser.add_argument("--bundle-root", type=Path)
    parser.add_argument("--evidence-class", choices=sorted(EVIDENCE_CLASSES), default="PLAN_ONLY")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        protocol = load_json(args.protocol, "public protocol")
        rights = load_json(args.rights, "public rights manifest")
        plan = load_json(args.study_plan, "public study plan")
        source, compiled = _load_compiled_policy(
            args.source_coverage, args.policy, args.policy_approval
        )
        receipt = compile_receipt(
            protocol,
            rights,
            plan,
            source_coverage=source,
            compiled_policy=compiled,
            bundle_root=args.bundle_root,
            evidence_class=args.evidence_class,
        )
        output = _safe_output_path(
            args.output,
            [
                args.protocol,
                args.rights,
                args.study_plan,
                args.source_coverage,
                args.policy,
                args.policy_approval,
            ],
            args.bundle_root,
        )
        write_or_check(output, receipt, check=args.check)
    except (GoldWorkflowError, OSError) as error:
        print(f"pose Gold workflow failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
