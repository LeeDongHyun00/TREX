#!/usr/bin/env python3
"""Synthetic conformance and fail-closed tests for pose_gold_workflow."""

from __future__ import annotations

import copy
import hashlib
import importlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import pose_gold_workflow as gold  # noqa: E402


def sha(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


def geometry(preprocessing_sha: str, epoch: str, rotation: int = 0) -> dict[str, object]:
    width, height = (640, 480) if rotation in {0, 180} else (480, 640)
    fields = [
        ("poseCameraGeometryContextSchemaVersion", "1"),
        ("coordinateDomain", "UPRIGHT_CROPPED_NORMALIZED_IMAGE"),
        ("sourceImageWidth", "640"),
        ("sourceImageHeight", "480"),
        ("cropLeft", "0"),
        ("cropTop", "0"),
        ("cropRightExclusive", "640"),
        ("cropBottomExclusive", "480"),
        ("inputRotationDegrees", str(rotation)),
        ("outputImageWidth", str(width)),
        ("outputImageHeight", str(height)),
        ("outputRotationDegrees", "0"),
        ("inferencePixelsMirrored", "false"),
        ("displayMirrored", "false"),
        ("preprocessingArtifactSha256", preprocessing_sha),
    ]
    return {
        "sourceImageSize": {"width": 640, "height": 480},
        "cropRectangleHalfOpen": {
            "left": 0,
            "top": 0,
            "rightExclusive": 640,
            "bottomExclusive": 480,
        },
        "inputRotationDegrees": rotation,
        "uprightOutputImageSize": {"width": width, "height": height},
        "inferencePixelsMirrored": False,
        "displayMirrored": False,
        "geometryContextArtifactSha256": gold.canonical_fields_sha256(fields),
        "preprocessingArtifactSha256": preprocessing_sha,
        "cameraGeometryEpochId": epoch,
    }


def view(epoch: str) -> dict[str, object]:
    return {
        "state": "QUALIFIED",
        "viewContractId": "trex.view.front-full-body.v1",
        "evidenceSource": "CAPTURE_SETUP_ATTESTATION",
        "evidenceArtifactSha256": sha(f"view:{epoch}"),
        "frontRearResolved": True,
        "cameraGeometryEpochId": epoch,
    }


def phase(timestamps: list[int]) -> dict[str, object]:
    selected = [timestamps[0], timestamps[1], timestamps[2], timestamps[4]]
    boundaries = []
    for (from_phase, to_phase), timestamp in zip(gold.PHASE_EDGES, selected):
        boundaries.append(
            {
                "fromPhase": from_phase,
                "toPhase": to_phase,
                "earliestTimestampNs": timestamp,
                "selectedTimestampNs": timestamp,
                "latestTimestampNs": timestamp,
            }
        )
    return {
        "state": "COMPLETE",
        "cycleIntervalConvention": "START_INCLUSIVE_END_EXCLUSIVE",
        "orderedTopology": list(gold.PHASE_TOPOLOGY),
        "boundaries": boundaries,
        "timestampGapCrossed": False,
        "unknownReasonCodes": [],
    }


def criterion_decisions(plan: dict[str, object], start_ns: int, end_ns: int) -> list[dict[str, object]]:
    decisions: list[dict[str, object]] = []
    for criterion in plan["criterionPlans"]:  # type: ignore[index]
        source_id = criterion["bindingKey"]["sourceConditionId"]
        roles = ["LEFT", "RIGHT"] if criterion["sidePolicy"] == "BILATERAL_INDEPENDENT" else ["MIDLINE"]
        for role in roles:
            is_contact = criterion["observability"] == "NOT_OBSERVABLE"
            view_id = "trex.view.front-full-body.v1"
            view_allowed = view_id in criterion["viewContractIds"]
            decisions.append(
                {
                    "bindingKey": copy.deepcopy(criterion["bindingKey"]),
                    "sideRole": role,
                    "goldState": (
                        "NOT_OBSERVABLE"
                        if is_contact
                        else "CONDITION_SATISFIED"
                        if view_allowed
                        else "UNKNOWN_GOLD"
                    ),
                    "phaseScope": {
                        "startTimestampNs": start_ns,
                        "endTimestampNs": end_ns,
                        "intervalConvention": "START_INCLUSIVE_END_EXCLUSIVE",
                    },
                    "viewContractId": view_id if view_allowed and not is_contact else None,
                    "referenceModalityId": (
                        None if is_contact else "SYNCHRONIZED_MOTION_CAPTURE"
                    ),
                    "attestedContactSensorEvidence": False,
                }
            )
    return sorted(decisions, key=lambda row: (row["bindingKey"]["sourceConditionId"], row["sideRole"]))


def artifact(kind: str, **fields: object) -> dict[str, object]:
    return gold.with_artifact_sha256(
        {"schemaVersion": 1, "artifactKind": kind, **fields}
    )


def synthetic_bundle(protocol: dict[str, object], rights: dict[str, object], plan: dict[str, object]) -> dict[str, object]:
    preprocessing_sha = sha("preprocessing")
    observation_contract_sha = sha("observation-contract")
    clock_sha = sha("clock-alignment")
    contact_sha = sha("contact-attestation")
    split_seal_sha = sha("split-seal")
    observer = {
        "runtimeDomainId": "trex.runtime.production-camera.v1",
        "observationContractArtifactSha256": observation_contract_sha,
        "modelArtifactSha256": sha("model"),
        "preprocessingArtifactSha256": preprocessing_sha,
        "landmarkSchemaArtifactSha256": sha("landmarks"),
        "personLockArtifactSha256": sha("person-lock"),
        "viewQualifierArtifactSha256": sha("view-qualifier"),
        "geometryProviderArtifactSha256": sha("geometry-provider"),
        "mediaPipeTasksVersion": "0.10.14",
        "runningMode": "VIDEO",
        "resolvedDelegate": "CPU",
        "landmarkCount": 33,
        "productionPipeline": True,
        "maximumCaptureGapNs": 1_500_000_000,
    }
    reference = {
        "modalityIds": [gold.CONTACT_MODALITY, "SYNCHRONIZED_MOTION_CAPTURE"],
        "clockAlignmentContractArtifactSha256": clock_sha,
        "maximumClockAlignmentErrorUs": 1_000,
        "contactSensorAttestationContractArtifactSha256": contact_sha,
    }
    manifest = artifact(
        "TREX_POSE_GOLD_BUNDLE_MANIFEST",
        evidenceClass="SYNTHETIC_CONFORMANCE",
        protocolArtifactSha256=protocol["artifactSha256"],
        rightsManifestArtifactSha256=rights["artifactSha256"],
        studyPlanArtifactSha256=plan["artifactSha256"],
        exerciseId="barbell-squat",
        declaredFiles=list(gold.RESTRICTED_FILE_NAMES),
        observerContract=observer,
        referenceEvidenceContract=reference,
        consentRootArtifactSha256=sha("consent-root"),
        reviewerRosterRootArtifactSha256=sha("reviewer-roster"),
        splitSealArtifactSha256=split_seal_sha,
    )
    split_names = ["DEVELOPMENT", "CALIBRATION", "LOCKED_INTERNAL_TEST", "EXTERNAL_TEST"]
    captures: list[dict[str, object]] = []
    observations: list[dict[str, object]] = []
    reviews: list[dict[str, object]] = []
    adjudications: list[dict[str, object]] = []
    assignments: list[dict[str, object]] = []
    for capture_index, split_name in enumerate(split_names):
        suffix = f"{capture_index:02d}"
        capture_id = f"capture-{suffix}"
        participant_id = f"participant-{suffix}"
        session_id = f"session-{suffix}"
        raw_id = f"raw-{suffix}"
        derived_id = f"derived-{suffix}"
        duplicate_id = f"duplicate-{suffix}"
        perceptual_id = f"perceptual-{suffix}"
        epoch = f"geometry-epoch-{suffix}"
        captures.append(
            artifact(
                "TREX_POSE_GOLD_CAPTURE_GROUP",
                captureGroupId=capture_id,
                participantId=participant_id,
                sessionId=session_id,
                rawMediaId=raw_id,
                rawMediaPayloadSha256=sha(f"raw-payload-{suffix}"),
                derivedArtifactIds=[derived_id],
                derivedArtifactPayloads=[
                    {"derivedArtifactId": derived_id, "payloadSha256": sha(f"derived-payload-{suffix}")}
                ],
                contentDuplicateGroupIds=[duplicate_id],
                perceptualDuplicateGroupIds=[perceptual_id],
                perceptualFingerprintContractId="trex.perceptual.dhash.v1",
                perceptualFingerprintArtifactSha256=sha(f"perceptual-fingerprint-{suffix}"),
                exerciseId="barbell-squat",
                deviceProfileId=f"device-{suffix}",
                deviceTier="LOW" if capture_index % 2 == 0 else "MAINSTREAM",
                cameraGeometryEpochId=epoch,
                authoritativeView=view(epoch),
                split=split_name,
                rightsReceiptId=f"rights-receipt-{suffix}",
            )
        )
        base = 10_000_000_000 + capture_index * 10_000_000_000
        timestamps = [base + frame * 1_000_000_000 for frame in range(5)]
        for frame, timestamp in enumerate(timestamps):
            observations.append(
                artifact(
                    "TREX_POSE_GOLD_OBSERVATION",
                    observationId=f"observation-{suffix}-{frame:02d}",
                    captureGroupId=capture_id,
                    frameOrdinal=frame,
                    captureTimestampNs=timestamp,
                    poseTimestampMs=timestamp // 1_000_000,
                    geometry=geometry(preprocessing_sha, epoch),
                    observerContractArtifactSha256=observation_contract_sha,
                    rawCandidateCount=1,
                    personTrackEpochId=f"person-track-{suffix}",
                    qualifiedViewContractIds=["trex.view.front-full-body.v1"],
                    normalizedLandmarkCount=33,
                    worldLandmarkCount=33,
                    normalizedLandmarksPayloadSha256=sha(f"normalized-{suffix}-{frame}"),
                    worldLandmarksPayloadSha256=sha(f"world-{suffix}-{frame}"),
                    confidencePayloadSha256=sha(f"confidence-{suffix}-{frame}"),
                    referenceEvidence={
                        "modalityId": "SYNCHRONIZED_MOTION_CAPTURE",
                        "referenceSampleTimestampNs": timestamp,
                        "alignedCaptureTimestampNs": timestamp,
                        "clockAlignmentArtifactSha256": clock_sha,
                        "absoluteAlignmentErrorUs": 0,
                        "maximumAllowedAlignmentErrorUs": 1_000,
                        "alignmentAccepted": True,
                        "payloadSha256": sha(f"reference-{suffix}-{frame}"),
                        "sensorAttestationArtifactSha256": None,
                    },
                    sourceTruthUse="NOT_GOLD_NOT_READ_BY_REVIEWER",
                    activeMaskUse="MOVEMENT_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD",
                )
            )
        phase_value = phase(timestamps)
        criteria_value = criterion_decisions(plan, timestamps[0], timestamps[4])
        unit_id = f"unit-{suffix}"
        unit_reviews: list[dict[str, object]] = []
        for reviewer_index in range(3):
            unit_reviews.append(
                artifact(
                    "TREX_POSE_GOLD_BLIND_REVIEW",
                    reviewId=f"review-{suffix}-{reviewer_index:02d}",
                    reviewerId=f"reviewer-{reviewer_index:02d}",
                    adjudicableUnitId=unit_id,
                    captureGroupId=capture_id,
                    blinding={
                        "runtimeOutputVisible": False,
                        "candidateThresholdVisible": False,
                        "aiHubTruthVectorVisible": False,
                        "otherReviewVisibleBeforeSubmission": False,
                        "splitVisible": False,
                    },
                    phaseReview=copy.deepcopy(phase_value),
                    viewReview=view(epoch),
                    criterionReviews=copy.deepcopy(criteria_value),
                )
            )
        reviews.extend(unit_reviews)
        unknown_rate = sum(
            row["goldState"] in {"UNKNOWN_GOLD", "NOT_OBSERVABLE"} for row in criteria_value
        ) / len(criteria_value)
        adjudications.append(
            artifact(
                "TREX_POSE_GOLD_ADJUDICATION",
                adjudicationId=f"adjudication-{suffix}",
                adjudicableUnitId=unit_id,
                captureGroupId=capture_id,
                reviewIds=sorted(row["reviewId"] for row in unit_reviews),
                decisionMethod="UNANIMOUS",
                phaseAdjudication=copy.deepcopy(phase_value),
                viewAdjudication=view(epoch),
                criterionAdjudications=copy.deepcopy(criteria_value),
                agreement={
                    "krippendorffAlpha": 1.0,
                    "rawAgreement": 1.0,
                    "positiveAgreement": 1.0,
                    "negativeAgreement": 1.0,
                    "unknownGoldRate": unknown_rate,
                    "adjudicationChangeRate": 0.0,
                },
            )
        )
        assignments.append(
            {
                "participantId": participant_id,
                "sessionIds": [session_id],
                "captureGroupIds": [capture_id],
                "rawMediaIds": [raw_id],
                "derivedArtifactIds": [derived_id],
                "contentDuplicateGroupIds": [duplicate_id],
                "perceptualDuplicateGroupIds": [perceptual_id],
                "split": split_name,
            }
        )
    split_manifest = artifact(
        "TREX_POSE_GOLD_SPLIT_MANIFEST",
        splitManifestId="split-manifest-synthetic-v1",
        splitSealArtifactSha256=split_seal_sha,
        assignmentPrecedesOutcomeReview=True,
        lockedTestAccessState="UNCONSUMED",
        assignments=assignments,
    )
    return {
        "bundle-manifest.json": manifest,
        "capture-groups.jsonl": captures,
        "observations.jsonl": observations,
        "blind-reviews.jsonl": reviews,
        "adjudications.jsonl": adjudications,
        "split-manifest.json": split_manifest,
    }


def rewrite(record: dict[str, object]) -> None:
    updated = gold.with_artifact_sha256(record)
    record.clear()
    record.update(updated)


def write_bundle(root: Path, bundle: dict[str, object]) -> None:
    root.mkdir()
    for filename in gold.RESTRICTED_FILE_NAMES:
        value = bundle[filename]
        path = root / filename
        if filename.endswith(".jsonl"):
            path.write_text("".join(gold.canonical_json(row) + "\n" for row in value), encoding="utf-8", newline="\n")
        else:
            path.write_text(gold._render_json(value), encoding="utf-8", newline="\n")


def file_hashes(root: Path) -> dict[str, str]:
    return {
        filename: hashlib.sha256((root / filename).read_bytes()).hexdigest()
        for filename in gold.RESTRICTED_FILE_NAMES
    }


def file_pins(root: Path) -> dict[str, tuple[int, str]]:
    return {
        filename: ((root / filename).stat().st_size, hashlib.sha256((root / filename).read_bytes()).hexdigest())
        for filename in gold.RESTRICTED_FILE_NAMES
    }


class PoseGoldWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.protocol = gold.load_json(ROOT / "docs/pose-gold-protocol.v1.json", "protocol")
        cls.rights = gold.load_json(ROOT / "docs/pose-data-rights-manifest.v1.json", "rights")
        cls.plan = gold.load_json(ROOT / "docs/barbell-squat-gold-study-plan.v1.json", "plan")
        cls.source, cls.compiled = gold._load_compiled_policy(
            gold.DEFAULT_SOURCE_COVERAGE, gold.DEFAULT_POLICY, gold.DEFAULT_POLICY_APPROVAL
        )

    def compile_bundle(self, bundle: dict[str, object]) -> dict[str, object]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "bundle"
            write_bundle(root, bundle)
            # Deep mutation tests act as compiler-owned synthetic fixtures.  Production CLI
            # callers cannot alter the module's pinned hash map.
            with mock.patch.object(gold, "APPROVED_SYNTHETIC_FILE_PINS", file_pins(root)):
                return gold.compile_receipt(
                    self.protocol,
                    self.rights,
                    self.plan,
                    source_coverage=self.source,
                    compiled_policy=self.compiled,
                    bundle_root=root,
                    evidence_class="SYNTHETIC_CONFORMANCE",
                )

    def test_committed_plan_only_receipt_is_exact_and_deterministic(self) -> None:
        actual = gold.compile_receipt(
            self.protocol,
            self.rights,
            self.plan,
            source_coverage=self.source,
            compiled_policy=self.compiled,
        )
        expected = gold.load_json(ROOT / "docs/barbell-squat-gold-readiness.v1.json", "readiness")
        self.assertEqual(expected, actual)

    def test_public_inputs_accept_crlf_and_key_order_without_changing_receipt(self) -> None:
        expected = gold.compile_receipt(
            self.protocol,
            self.rights,
            self.plan,
            source_coverage=self.source,
            compiled_policy=self.compiled,
        )
        with tempfile.TemporaryDirectory() as temporary:
            values = [self.protocol, self.rights, self.plan]
            loaded = []
            for index, value in enumerate(values):
                path = Path(temporary) / f"public-{index}.json"
                reordered = {key: value[key] for key in reversed(value)}
                rendered = json.dumps(reordered, ensure_ascii=False, indent=4).replace("\n", "\r\n") + "\r\n"
                path.write_bytes(rendered.encode("utf-8"))
                loaded.append(gold.load_json(path, f"public-{index}"))
        actual = gold.compile_receipt(
            loaded[0],
            loaded[1],
            loaded[2],
            source_coverage=self.source,
            compiled_policy=self.compiled,
        )
        self.assertEqual(expected, actual)

    def test_fake_public_authority_and_policy_binding_mutations_fail_closed(self) -> None:
        protocol = copy.deepcopy(self.protocol)
        protocol["privacyContract"]["androidAppPersistenceAllowed"] = True
        rewrite(protocol)
        with self.assertRaises(gold.GoldWorkflowError):
            gold.compile_receipt(
                protocol,
                self.rights,
                self.plan,
                source_coverage=self.source,
                compiled_policy=self.compiled,
            )
        rights = copy.deepcopy(self.rights)
        rights["readiness"] = "VERIFIED_READY"
        rewrite(rights)
        with self.assertRaises(gold.GoldWorkflowError):
            gold.compile_receipt(
                self.protocol,
                rights,
                self.plan,
                source_coverage=self.source,
                compiled_policy=self.compiled,
            )
        mutations = (
            lambda row: row["bindingKey"].update(bindingId="aihub-binding-sha256-" + "0" * 64),
            lambda row: row["bindingKey"].update(bindingPolicySha256="0" * 64),
            lambda row: row.update(measurementConstructId="trex.measurement.fake.v1"),
            lambda row: row.update(viewContractIds=["trex.view.lateral-full-body.v1"]),
            lambda row: row.update(requiredCapabilityIds=["trex.capability.fake.v1"]),
            lambda row: row.update(sidePolicy="MIDLINE"),
            lambda row: row.update(observability="DIRECT"),
        )
        for mutation in mutations:
            plan = copy.deepcopy(self.plan)
            mutation(plan["criterionPlans"][0])
            rewrite(plan)
            with mock.patch.object(gold, "APPROVED_STUDY_PLAN_V1_SHA256", plan["artifactSha256"]):
                with self.assertRaises(gold.GoldWorkflowError):
                    gold.compile_receipt(
                        self.protocol,
                        self.rights,
                        plan,
                        source_coverage=self.source,
                        compiled_policy=self.compiled,
                    )

    def test_synthetic_vertical_slice_happy_path_and_public_privacy(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "bundle"
            write_bundle(root, bundle)
            self.assertEqual(gold.APPROVED_SYNTHETIC_FILE_PINS, file_pins(root))
        receipt = self.compile_bundle(bundle)
        self.assertEqual(1, receipt["actualAggregateCounts"]["syntheticConformanceBundleCount"])
        self.assertTrue(all(
            value == 0
            for key, value in receipt["actualAggregateCounts"].items()
            if key != "syntheticConformanceBundleCount"
        ))
        restricted = receipt["restrictedBundleReceipt"]
        self.assertIn("syntheticFixtureShapeCounts", restricted)
        self.assertNotIn("aggregateCounts", restricted)
        serialized = gold.canonical_json(receipt)
        for forbidden in ("participant-", "reviewer-", "capture-", "observation-", "person-track-", "raw-"):
            self.assertNotIn(forbidden, serialized)

    def test_timestamp_gap_pose_timestamp_and_reference_replay_reject(self) -> None:
        for mutation, pattern in (
            (lambda b: b["observations.jsonl"][1].update(poseTimestampMs=b["observations.jsonl"][0]["poseTimestampMs"]), "timestamp"),
            (lambda b: b["observations.jsonl"][1]["referenceEvidence"].update(referenceSampleTimestampNs=b["observations.jsonl"][0]["referenceEvidence"]["referenceSampleTimestampNs"]), "reference sample timestamps"),
            (lambda b: b["observations.jsonl"][1].update(captureTimestampNs=b["observations.jsonl"][0]["captureTimestampNs"] + 2_000_000_000, poseTimestampMs=(b["observations.jsonl"][0]["captureTimestampNs"] + 2_000_000_000)//1_000_000), "timestamp-gap"),
        ):
            bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
            mutation(bundle)
            rewrite(bundle["observations.jsonl"][1])
            with self.assertRaisesRegex(gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"):
                self.compile_bundle(bundle)

    def test_geometry_landmark_person_and_view_drift_reject(self) -> None:
        mutations = (
            lambda row: row["geometry"].update(inputRotationDegrees=45),
            lambda row: row.update(normalizedLandmarkCount=32),
            lambda row: row.update(personTrackEpochId="another-person-track"),
            lambda row: row.update(qualifiedViewContractIds=["trex.view.front-full-body.v1", "trex.view.lateral-full-body.v1"]),
        )
        for mutation in mutations:
            bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
            mutation(bundle["observations.jsonl"][1])
            rewrite(bundle["observations.jsonl"][1])
            with self.assertRaises(gold.GoldWorkflowError):
                self.compile_bundle(bundle)

    def test_exact_three_blind_reviews_and_conflict_reject(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["blind-reviews.jsonl"][0]["blinding"]["runtimeOutputVisible"] = True
        rewrite(bundle["blind-reviews.jsonl"][0])
        with self.assertRaisesRegex(gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"):
            self.compile_bundle(bundle)
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        del bundle["blind-reviews.jsonl"][0]
        with self.assertRaisesRegex(gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"):
            self.compile_bundle(bundle)
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["blind-reviews.jsonl"][0]["criterionReviews"][0]["goldState"] = "CONDITION_VIOLATED"
        rewrite(bundle["blind-reviews.jsonl"][0])
        with self.assertRaises(gold.GoldWorkflowError):
            self.compile_bundle(bundle)
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["adjudications.jsonl"][0]["decisionMethod"] = "PANEL_CONSENSUS"
        rewrite(bundle["adjudications.jsonl"][0])
        with self.assertRaises(gold.GoldWorkflowError):
            self.compile_bundle(bundle)
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["adjudications.jsonl"][0]["agreement"]["krippendorffAlpha"] = 0.99
        rewrite(bundle["adjudications.jsonl"][0])
        with self.assertRaises(gold.GoldWorkflowError):
            self.compile_bundle(bundle)

    def test_determinate_visual_modality_must_exist_in_capture(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["bundle-manifest.json"]["referenceEvidenceContract"]["modalityIds"].insert(
            0, "BLINDED_EXPERT_MEDIA_REVIEW"
        )
        rewrite(bundle["bundle-manifest.json"])
        row = next(
            item
            for item in bundle["blind-reviews.jsonl"][0]["criterionReviews"]
            if item["goldState"] == "CONDITION_SATISFIED"
        )
        row["referenceModalityId"] = "BLINDED_EXPERT_MEDIA_REVIEW"
        rewrite(bundle["blind-reviews.jsonl"][0])
        with self.assertRaises(gold.GoldWorkflowError):
            self.compile_bundle(bundle)

    def test_unknown_phase_cannot_retain_determinate_criterion_gold(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        unknown_phase = {
            "state": "UNKNOWN_GOLD",
            "cycleIntervalConvention": "START_INCLUSIVE_END_EXCLUSIVE",
            "orderedTopology": list(gold.PHASE_TOPOLOGY),
            "boundaries": [],
            "timestampGapCrossed": False,
            "unknownReasonCodes": ["PHASE_BOUNDARY_UNRESOLVED"],
        }
        for review in bundle["blind-reviews.jsonl"]:
            if review["captureGroupId"] != "capture-00":
                continue
            review["phaseReview"] = copy.deepcopy(unknown_phase)
            for decision in review["criterionReviews"]:
                decision["phaseScope"]["startTimestampNs"] = None
                decision["phaseScope"]["endTimestampNs"] = None
            rewrite(review)
        adjudication = bundle["adjudications.jsonl"][0]
        adjudication["phaseAdjudication"] = copy.deepcopy(unknown_phase)
        for decision in adjudication["criterionAdjudications"]:
            decision["phaseScope"]["startTimestampNs"] = None
            decision["phaseScope"]["endTimestampNs"] = None
        rewrite(adjudication)
        with self.assertRaises(gold.GoldWorkflowError):
            self.compile_bundle(bundle)

    def test_plantar_contact_bool_cannot_create_gold(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        plantar = next(
            row for row in bundle["blind-reviews.jsonl"][0]["criterionReviews"]
            if row["bindingKey"]["sourceConditionId"].endswith("503e2d")
        )
        plantar["goldState"] = "CONDITION_SATISFIED"
        plantar["referenceModalityId"] = gold.CONTACT_MODALITY
        plantar["attestedContactSensorEvidence"] = True
        rewrite(bundle["blind-reviews.jsonl"][0])
        with self.assertRaisesRegex(gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"):
            self.compile_bundle(bundle)

    def test_split_locked_state_exact_cross_kind_and_path_ids_reject(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["split-manifest.json"]["lockedTestAccessState"] = "CONSUMED"
        rewrite(bundle["split-manifest.json"])
        with self.assertRaisesRegex(gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"):
            self.compile_bundle(bundle)
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["capture-groups.jsonl"][3]["rawMediaPayloadSha256"] = bundle["capture-groups.jsonl"][0]["derivedArtifactPayloads"][0]["payloadSha256"]
        rewrite(bundle["capture-groups.jsonl"][3])
        with self.assertRaisesRegex(gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"):
            self.compile_bundle(bundle)
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        bundle["capture-groups.jsonl"][0]["derivedArtifactIds"] = ["../outside"]
        bundle["capture-groups.jsonl"][0]["derivedArtifactPayloads"][0]["derivedArtifactId"] = "../outside"
        rewrite(bundle["capture-groups.jsonl"][0])
        with self.assertRaises(gold.GoldWorkflowError):
            self.compile_bundle(bundle)

    def test_real_intake_rejects_before_bundle_access(self) -> None:
        with mock.patch.object(gold, "_validate_restricted_bundle") as reader:
            with self.assertRaisesRegex(gold.GoldWorkflowError, "NO_TRUSTED_RIGHTS_AUTHORITY_V1"):
                gold.compile_receipt(
                    self.protocol,
                    self.rights,
                    self.plan,
                    source_coverage=self.source,
                    compiled_policy=self.compiled,
                    bundle_root=Path("does-not-exist-private"),
                    evidence_class="REAL_RESTRICTED_GOLD",
                )
            reader.assert_not_called()

    def test_unpinned_synthetic_is_rejected_before_json_parse(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "bundle"
            write_bundle(root, bundle)
            with (root / "capture-groups.jsonl").open("ab") as output:
                output.write(b" ")
            with mock.patch.object(gold, "_loads_json") as json_parser, mock.patch.object(
                gold, "_loads_jsonl_text"
            ) as jsonl_parser:
                with self.assertRaisesRegex(
                    gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"
                ) as raised:
                    gold.compile_receipt(
                        self.protocol,
                        self.rights,
                        self.plan,
                        source_coverage=self.source,
                        compiled_policy=self.compiled,
                        bundle_root=root,
                        evidence_class="SYNTHETIC_CONFORMANCE",
                    )
                json_parser.assert_not_called()
                jsonl_parser.assert_not_called()
                self.assertIsNone(raised.exception.__cause__)

    def test_oversized_synthetic_rejects_without_read_or_parse(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "bundle"
            write_bundle(root, bundle)
            with (root / "observations.jsonl").open("ab") as output:
                output.write(b"X" * 1024)
            with mock.patch.object(Path, "open", side_effect=AssertionError("must not read")), mock.patch.object(
                gold, "_loads_json"
            ) as json_parser, mock.patch.object(gold, "_loads_jsonl_text") as jsonl_parser:
                with self.assertRaisesRegex(
                    gold.GoldWorkflowError, "RESTRICTED_BUNDLE_VALIDATION_FAILED"
                ):
                    gold.compile_receipt(
                        self.protocol,
                        self.rights,
                        self.plan,
                        source_coverage=self.source,
                        compiled_policy=self.compiled,
                        bundle_root=root,
                        evidence_class="SYNTHETIC_CONFORMANCE",
                    )
                json_parser.assert_not_called()
                jsonl_parser.assert_not_called()

    def test_pinned_snapshot_is_parsed_without_path_reopen(self) -> None:
        bundle = synthetic_bundle(self.protocol, self.rights, self.plan)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "bundle"
            write_bundle(root, bundle)
            original = gold._loads_jsonl_text
            swapped = False

            def swap_after_snapshot(text: str, label: str) -> list[dict[str, object]]:
                nonlocal swapped
                if not swapped:
                    swapped = True
                    (root / "observations.jsonl").write_text("{}\n", encoding="utf-8")
                return original(text, label)

            with mock.patch.object(gold, "_loads_jsonl_text", side_effect=swap_after_snapshot):
                receipt = gold.compile_receipt(
                    self.protocol,
                    self.rights,
                    self.plan,
                    source_coverage=self.source,
                    compiled_policy=self.compiled,
                    bundle_root=root,
                    evidence_class="SYNTHETIC_CONFORMANCE",
                )
            self.assertEqual(1, receipt["actualAggregateCounts"]["syntheticConformanceBundleCount"])

    def test_strict_json_and_bool_integer_boundary(self) -> None:
        for payload in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":"e\\u0301"}'):
            with self.assertRaises(gold.GoldWorkflowError):
                gold._loads_json(payload, "adversarial")
        with self.assertRaises(gold.GoldWorkflowError):
            gold._integer(True, "not-an-int")

    def test_compiler_provenance_is_current_and_newline_invariant(self) -> None:
        payload = Path(gold.__file__).read_bytes()
        provenance = gold.compiler_implementation_provenance()
        self.assertEqual(gold.canonical_lf_text_sha256(payload), provenance["canonicalTextSha256"])
        self.assertEqual(
            gold.canonical_lf_text_sha256(b"alpha\nbeta\n"),
            gold.canonical_lf_text_sha256(b"alpha\r\nbeta\r\n"),
        )
        self.assertEqual(
            gold.canonical_lf_text_sha256(b"alpha\nbeta\n"),
            gold.canonical_lf_text_sha256(b"alpha\rbeta\r"),
        )

    def test_policy_compiler_loads_from_script_and_package_imports(self) -> None:
        package_gold = importlib.import_module("tools.pose_gold_workflow")
        source, compiled = package_gold._load_compiled_policy(
            package_gold.DEFAULT_SOURCE_COVERAGE,
            package_gold.DEFAULT_POLICY,
            package_gold.DEFAULT_POLICY_APPROVAL,
        )
        self.assertEqual(41, len(source["exercises"]))
        self.assertEqual(167, len(compiled["bindings"]))

    def test_output_check_is_byte_exact_and_atomic_no_clobber(self) -> None:
        value = {"schemaVersion": 1}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            path.write_bytes(gold._render_json(value).replace("\n", "\r\n").encode("utf-8"))
            with self.assertRaisesRegex(gold.GoldWorkflowError, "stale"):
                gold.write_or_check(path, value, check=True)
            path.unlink()
            original_link = os.link

            def racing_link(source: object, target: object) -> None:
                Path(target).write_text("SENTINEL", encoding="utf-8")
                raise FileExistsError(str(target))

            with mock.patch.object(gold.os, "link", side_effect=racing_link):
                with self.assertRaisesRegex(gold.GoldWorkflowError, "overwrite refused"):
                    gold.write_or_check(path, value, check=False)
            self.assertEqual("SENTINEL", path.read_text(encoding="utf-8"))
            self.assertIsNotNone(original_link)


if __name__ == "__main__":
    unittest.main()
