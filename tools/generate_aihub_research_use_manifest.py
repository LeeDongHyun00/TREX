"""Generate the AI Hub research-use rights manifest.

This manifest authorizes a narrow, non-commercial educational scope for the AI Hub 013 fitness
pose dataset. It deliberately does NOT transition `pose-data-rights-manifest.v1.json`, whose
VERIFIED_READY state would require participant consent, retention SLAs and a restricted-access
audit that do not exist -- asserting them would be a false record. The two manifests govern
disjoint scopes and are validated independently.

Usage:
    python tools/generate_aihub_research_use_manifest.py            # write
    python tools/generate_aihub_research_use_manifest.py --check    # verify in place
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import unicodedata
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = REPO_ROOT / "docs" / "pose-data-rights-manifest.aihub-research.v1.json"
GOLD_MANIFEST_PATH = REPO_ROOT / "docs" / "pose-data-rights-manifest.v1.json"

# The Gold-collection manifest this document must never be confused with or used to transition.
GOLD_MANIFEST_ID = "trex.pose-data-rights-manifest.v1"
GOLD_MANIFEST_SHA256 = "bfe2a80776fb65da20724d475bda61cf2adf6692587fe4f67f22c238a3a1b4df"


def canonical_sha256(document: dict[str, Any]) -> str:
    """SHA-256 over the manifest with the top-level artifactSha256 removed.

    Mirrors the canonicalization block the v1 rights manifest declares, and is verified against
    that manifest's own published digest by the test suite.
    """
    payload = {key: value for key, value in document.items() if key != "artifactSha256"}
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    serialized = unicodedata.normalize("NFC", serialized)
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def build_manifest() -> dict[str, Any]:
    document: dict[str, Any] = {
        "artifactKind": "TREX_AIHUB_RESEARCH_USE_RIGHTS_MANIFEST",
        "manifestId": "trex.aihub-research-use-rights.v1",
        "schemaVersion": 1,
        "issuedDate": "2026-08-12",
        "status": "VERIFIED_FOR_DECLARED_SCOPE",
        "readiness": "READY_FOR_DECLARED_SCOPE_ONLY",
        "decisionUse": (
            "AUTHORIZES_NON_COMMERCIAL_EDUCATIONAL_ANALYSIS_OF_AIHUB_013_LABELS_ONLY_"
            "GRANTS_NO_RELEASE_RUNTIME_CUE_OR_SCORE_AUTHORITY"
        ),
        # Disjoint from the Gold-collection manifest. This document neither supersedes nor
        # transitions it; that manifest stays NOT_READY because TREX has collected no participants.
        "goldCollectionManifestRelation": {
            "goldManifestId": GOLD_MANIFEST_ID,
            "goldManifestSha256": GOLD_MANIFEST_SHA256,
            "goldManifestRemainsNotReady": True,
            "relationship": "DISJOINT_SCOPE_NEITHER_SUPERSEDES_NOR_TRANSITIONS",
            "thisManifestMayAuthorizeParticipantCollection": False,
        },
        "scope": {
            "datasetId": "AIHUB_013_FITNESS_POSE_IMAGE",
            "dataClassInScope": "AIHUB_SOURCE_DATA",
            "useClass": "NON_COMMERCIAL_EDUCATIONAL_CAPSTONE",
            "commercialUseAuthorized": False,
            "derivedThresholdPublicAppDistributionAuthorized": False,
            "redistributionOfRawOrDerivedRawAuthorized": False,
            "subjectExercises": [
                "STEP_FORWARD_DYNAMIC_LUNGE",
                "STEP_BACKWARD_DYNAMIC_LUNGE",
                "STANDING_KNEE_UP",
                "STANDING_SIDE_CRUNCH",
                "BURPEE_TEST",
            ],
        },
        # Single-party, unsigned. Recorded honestly so no downstream reader mistakes this for the
        # detached-signature trust root that protocol v2 still requires.
        "attestation": {
            "basis": "OWNER_ACCEPTED_AIHUB_SITE_USE_AGREEMENT",
            "attestedBy": "REPOSITORY_OWNER",
            "attestationMode": "SINGLE_PARTY_UNSIGNED",
            "detachedSignaturePresent": False,
            "externalCoSignerPresent": False,
            "pinnedTrustRegistryPresent": False,
            "termsEvidenceDocumentSha256": None,
            "limitation": (
                "Owner attestation of an accepted site agreement. Not a signed licence opinion, "
                "not external legal review, and not the protocol v2 trust root."
            ),
        },
        # Unchanged from the fail-closed baseline. Research authorization is not release authority.
        "authority": {
            "calibrationAuthority": 0,
            "cueAuthority": 0,
            "phaseDecoderAuthority": 0,
            "releaseAuthority": 0,
            "repCountAuthority": 0,
            "runtimeProviderAuthority": 0,
            "scoreAuthority": 0,
            "shadowAuthority": 0,
            "userPassFailUnknownAuthority": 0,
        },
        "permittedOperations": [
            "AIHUB_LABEL_STATISTICAL_ANALYSIS",
            "AIHUB_LABEL_THRESHOLD_FITTING_FOR_HEURISTIC_BETA",
            "LEAVE_ONE_SUBJECT_OUT_CROSS_VALIDATION",
            "MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT",
            "AGGREGATE_ONLY_RESULT_REPORTING",
            "PUBLIC_SCHEMA_REVIEW",
            "SYNTHETIC_CONFORMANCE_FIXTURE_VALIDATION",
        ],
        "prohibitedOperations": [
            "COMMERCIAL_USE",
            "RAW_MEDIA_OR_POSE_GIT_PERSISTENCE",
            "ANDROID_ASSET_DATABASE_CACHE_OR_LOG_PERSISTENCE",
            "REAL_PARTICIPANT_COLLECTION",
            "RELEASE_CRITERION_AUTHORIZATION",
            "SHADOW_OR_USER_RUNTIME_USE_OF_AIHUB_DERIVED_VERDICTS",
            "REDISTRIBUTION_OF_RAW_OR_DERIVED_RAW_DATA",
            "PUBLIC_APP_DISTRIBUTION_OF_AIHUB_DERIVED_THRESHOLDS",
        ],
        "storageContract": {
            "androidPersistenceAllowed": False,
            "offlineRestrictedWorkspaceRequired": True,
            "publicRepositoryMayContainRawOrDerivedParticipantData": False,
            "rawDatasetRemainsOutsideGitAndApk": True,
        },
        "gitPolicy": {
            "aggregateStatisticalSummaryAllowed": True,
            "fittedThresholdConstantAllowed": True,
            "rawMediaAllowed": False,
            "rawPoseLandmarkOrTrajectoryAllowed": False,
            "subjectIdentifierAllowed": False,
        },
        # Honest residue. Resolving these is required before the listed consequence, not before
        # the permitted operations above.
        "openBlockers": [
            {
                "blocker": "AIHUB_TERMS_EVIDENCE_DOCUMENT_NOT_ARCHIVED",
                "blocks": "Any later claim that the licence scope was independently reviewable",
                "resolution": (
                    "Archive the accepted AI Hub use-agreement text and record its SHA-256 in "
                    "attestation.termsEvidenceDocumentSha256"
                ),
            },
            {
                "blocker": "PUBLIC_APP_DISTRIBUTION_RIGHTS_NOT_REVIEWED",
                "blocks": "Shipping AI Hub-derived thresholds in a publicly distributed build",
                "resolution": (
                    "Confirm derived-artifact distribution terms before any public release; the "
                    "capstone scope assumes non-public builds"
                ),
            },
            {
                "blocker": "DETACHED_SIGNATURE_TRUST_ROOT_NOT_IMPLEMENTED",
                "blocks": "Release-chain authorization (protocol v2), not research analysis",
                "resolution": "Implement the protocol v2 verifier and pinned trust registry",
            },
            {
                "blocker": "MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_NOT_QUANTIFIED",
                "blocks": (
                    "Treating an AI Hub-fitted threshold as valid for MediaPipe world landmarks"
                ),
                "resolution": (
                    "Run the bridge measurement permitted above and publish the per-joint error "
                    "data card before adopting fitted constants"
                ),
            },
        ],
        "canonicalization": {
            "artifactHashAlgorithm": "SHA-256",
            "artifactHashInput": "RFC8259_JSON_WITH_TOP_LEVEL_ARTIFACT_SHA256_REMOVED",
            "artifactHashSerialization": "UTF8_SORTED_KEYS_COMPACT_NO_TRAILING_NEWLINE",
            "fileSerialization": "UTF8_PRETTY_2_SPACES_LF_FINAL_NEWLINE",
            "nonFiniteNumbersAllowed": False,
            "unicodeNormalization": "NFC",
        },
    }
    document["artifactSha256"] = canonical_sha256(document)
    return document


def serialize(document: dict[str, Any]) -> str:
    return json.dumps(document, sort_keys=True, indent=2, ensure_ascii=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify without writing")
    args = parser.parse_args()

    document = build_manifest()
    rendered = serialize(document)

    if args.check:
        if not MANIFEST_PATH.exists():
            print(f"missing manifest: {MANIFEST_PATH}", file=sys.stderr)
            return 1
        current = MANIFEST_PATH.read_text(encoding="utf-8")
        if current != rendered:
            print("manifest drifted from generator output", file=sys.stderr)
            return 1
        stored = json.loads(current)
        if stored["artifactSha256"] != canonical_sha256(stored):
            print("manifest artifactSha256 does not match its own content", file=sys.stderr)
            return 1
        print(f"ok: {MANIFEST_PATH.name} sha256={stored['artifactSha256']}")
        return 0

    MANIFEST_PATH.write_text(rendered, encoding="utf-8")
    print(f"wrote {MANIFEST_PATH.name} sha256={document['artifactSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
