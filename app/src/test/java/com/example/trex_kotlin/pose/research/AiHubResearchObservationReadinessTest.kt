package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHubResearchObservationReadinessTest {
    @Test
    fun staticProjectionExactlyMatchesTheGeneratedBaseAndLateralPolicies() {
        val profiles = AiHubResearchObservationReadiness.policyProfilesForTest()

        assertEquals(24, AiHubResearchObservationReadiness.baseAndLateralPolicyCount)
        assertEquals(18, AiHubResearchObservationReadiness.fixedSidePolicyCount)
        assertEquals(6, AiHubResearchObservationReadiness.roleResolverPolicyCount)
        assertEquals(24, profiles.size)
        assertEquals(18, profiles.count { it.roleResolverContractId == null })
        assertEquals(6, profiles.count { it.roleResolverContractId != null })
        profiles.forEach { profile ->
            val generated = requireNotNull(
                AiHubCriterionPolicyCatalog.binding(profile.exercise, profile.sourceConditionId),
            )
            val interpretation = requireNotNull(generated.interpretation)
            assertEquals(AiHubCriterionObservability.DIRECT, interpretation.observability)
            assertEquals(generated.bindingId, profile.bindingId)
            assertEquals(generated.bindingPolicySha256, profile.bindingPolicySha256)
            assertEquals(
                PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS,
                interpretation.requiredCapabilityIds,
            )
            assertTrue(
                PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID in
                    interpretation.viewApplicability.viewContractIds,
            )
            assertEquals(interpretation.phaseApplicability.phaseRoleIds, profile.phaseRoleIds)
            assertEquals(interpretation.sidePolicy.kind, profile.sidePolicyKind)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (profiles as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (profiles.first().phaseRoleIds as MutableList).clear()
        }
        assertSame(profiles, AiHubResearchObservationReadiness.policyProfilesForTest())
    }

    @Test
    fun assessmentTouchesOnlyTheSelectedExerciseAndAlwaysBlocksPhase() {
        val fixture = capabilityFixture()
        val noReceipt = AiHubResearchObservationReadiness.assess(
            AiHubExercise.BARBELL_LUNGE,
            fixture.secondObservation,
            null,
        )

        assertEquals(AiHubExercise.BARBELL_LUNGE, noReceipt.exercise)
        assertEquals(2, noReceipt.baseAndLateralPolicyCount)
        assertEquals(0, noReceipt.capabilityReadyPolicyCount)
        assertEquals(2, noReceipt.roleResolverBlockedPolicyCount)
        assertEquals(2, noReceipt.phaseBlockedPolicyCount)
        assertEquals(2, noReceipt.calibrationBlockedPolicyCount)
        assertEquals(2, noReceipt.shadowAuthorizationBlockedPolicyCount)
        assertEquals(2, noReceipt.referenceEvidenceBlockedPolicyCount)
        assertEquals(2, noReceipt.trustedEvidenceIntakeBlockedPolicyCount)
        assertEquals(2, noReceipt.releaseAuthorizationBlockedPolicyCount)
        assertTrue(noReceipt.policyAssessments.all { it.profile.exercise == noReceipt.exercise })
        assertTrue(noReceipt.policyAssessments.all {
            AiHubResearchObservationBlocker.CAPABILITY_RECEIPT_UNAVAILABLE in it.blockers &&
                AiHubResearchObservationBlocker.PHASE_SCOPE_UNAVAILABLE in it.blockers &&
                AiHubResearchObservationBlocker.ROLE_RESOLVER_UNAVAILABLE in it.blockers
        })

        val unrelatedExercise = AiHubResearchObservationReadiness.assess(
            AiHubExercise.BARBELL_CURL,
            fixture.secondObservation,
            null,
        )
        assertEquals(0, unrelatedExercise.baseAndLateralPolicyCount)
        assertTrue(unrelatedExercise.policyAssessments.isEmpty())
    }

    @Test
    fun canonicalReceiptSatisfiesOnlyObservationCapabilitiesAndNeverProductAuthority() {
        val fixture = capabilityFixture()
        val receipt = fixture.receipt
        val readiness = AiHubResearchObservationReadiness.assess(
            AiHubExercise.ROWING_MACHINE,
            fixture.secondObservation,
            receipt,
        )

        assertEquals(AiHubCriterionPolicyCatalog.POLICY_SHA256, readiness.generatedPolicySha256)
        assertEquals(AiHubCriterionPolicyCatalog.REGISTRY_SHA256, readiness.generatedRegistrySha256)
        assertEquals(receipt.evidenceContractSha256, readiness.capabilityEvidenceContractSha256)
        assertEquals(receipt.receiptSha256, readiness.capabilityReceiptSha256)
        assertEquals(3, readiness.baseAndLateralPolicyCount)
        assertEquals(3, readiness.fixedSidePolicyCount)
        assertEquals(0, readiness.roleResolverBlockedPolicyCount)
        assertEquals(3, readiness.capabilityReadyPolicyCount)
        assertEquals(3, readiness.phaseBlockedPolicyCount)
        assertEquals(3, readiness.calibrationBlockedPolicyCount)
        assertEquals(3, readiness.shadowAuthorizationBlockedPolicyCount)
        assertEquals(3, readiness.referenceEvidenceBlockedPolicyCount)
        assertEquals(3, readiness.trustedEvidenceIntakeBlockedPolicyCount)
        assertEquals(3, readiness.releaseAuthorizationBlockedPolicyCount)
        assertTrue(readiness.policyAssessments.all { assessment ->
            assessment.capabilityEvidenceSatisfied &&
                assessment.phaseScopeSatisfied.not() &&
                assessment.roleResolverSatisfied &&
                assessment.blockers == setOf(
                    AiHubResearchObservationBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE,
                    AiHubResearchObservationBlocker.PHASE_SCOPE_UNAVAILABLE,
                    AiHubResearchObservationBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
                    AiHubResearchObservationBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
                    AiHubResearchObservationBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
                    AiHubResearchObservationBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
                ) &&
                assessment.calibrationArtifactSatisfied.not() &&
                assessment.shadowAuthorizationSatisfied.not() &&
                assessment.referenceEvidenceSatisfied.not() &&
                assessment.trustedEvidenceIntakeSatisfied.not() &&
                assessment.releaseAuthorizationSatisfied.not() &&
                allAuthorityDisabled(assessment)
        })
        assertFalse(readiness.measurementEnabled)
        assertFalse(readiness.shadowEnabled)
        assertFalse(readiness.verdictEnabled)
        assertFalse(readiness.scoreEnabled)
        assertFalse(readiness.cueEnabled)
        assertFalse(readiness.releaseEnabled)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (readiness.policyAssessments as MutableList).clear()
        }

        val foreignFixture = capabilityFixture()
        val foreignReplay = AiHubResearchObservationReadiness.assess(
            AiHubExercise.ROWING_MACHINE,
            foreignFixture.secondObservation,
            receipt,
        )
        assertEquals(0, foreignReplay.capabilityReadyPolicyCount)
        assertEquals(null, foreignReplay.capabilityEvidenceContractSha256)
        assertEquals(null, foreignReplay.capabilityReceiptSha256)
        assertTrue(foreignReplay.policyAssessments.all {
            AiHubResearchObservationBlocker.CAPABILITY_RECEIPT_UNAVAILABLE in it.blockers
        })

        assertEquals(
            3,
            AiHubResearchObservationReadiness.assess(
                AiHubExercise.ROWING_MACHINE,
                fixture.secondObservation,
                receipt,
            ).capabilityReadyPolicyCount,
        )
        val staleReplay = AiHubResearchObservationReadiness.assess(
            AiHubExercise.ROWING_MACHINE,
            fixture.firstObservation,
            receipt,
        )
        assertEquals(0, staleReplay.capabilityReadyPolicyCount)
        assertEquals(null, staleReplay.capabilityEvidenceContractSha256)
        assertEquals(null, staleReplay.capabilityReceiptSha256)

        val modifiedReplay = AiHubResearchObservationReadiness.assess(
            AiHubExercise.ROWING_MACHINE,
            fixture.modifiedSecondObservation,
            receipt,
        )
        assertEquals(0, modifiedReplay.capabilityReadyPolicyCount)
        assertEquals(null, modifiedReplay.capabilityEvidenceContractSha256)
        assertEquals(null, modifiedReplay.capabilityReceiptSha256)

        fixture.source.close()
        val closedReplay = AiHubResearchObservationReadiness.assess(
            AiHubExercise.ROWING_MACHINE,
            fixture.secondObservation,
            receipt,
        )
        assertEquals(0, closedReplay.capabilityReadyPolicyCount)
        assertEquals(null, closedReplay.capabilityEvidenceContractSha256)
        assertEquals(null, closedReplay.capabilityReceiptSha256)
        assertTrue(closedReplay.policyAssessments.all {
            AiHubResearchObservationBlocker.CAPABILITY_RECEIPT_UNAVAILABLE in it.blockers
        })
    }

    private fun allAuthorityDisabled(
        assessment: AiHubResearchObservationPolicyAssessment,
    ): Boolean = !assessment.measurementEnabled &&
        !assessment.shadowEnabled &&
        !assessment.verdictEnabled &&
        !assessment.scoreEnabled &&
        !assessment.cueEnabled &&
        !assessment.releaseEnabled

    private fun capabilityFixture(): ReceiptFixture {
        val source = PoseObservationSource(
            PoseObservationContract(
                runtimeDomainId = "mediapipe-full.research.v1",
                modelArtifactId = "mediapipe.pose-landmarker.full.v1",
                modelArtifactSha256 = SHA_A,
                inferenceOptionsContractId = "mediapipe.video-options.v1",
                inferenceOptionsArtifactSha256 = SHA_D,
                preprocessingContractId = "camerax.geometry-described.v1",
                preprocessingArtifactSha256 = SHA_B,
                landmarkSchemaId = "mediapipe.pose-33.v1",
                landmarkSchemaArtifactSha256 = SHA_C,
                supportedCoordinateSpaces = PoseCoordinateSpace.entries.toSet(),
                phaseViewContractId = "trex.view.full-body-any.v1",
                allowedViewContractIds = setOf(
                    "trex.view.full-body-any.v1",
                    PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                ),
                personLockArtifactId = "primary-person.temporal-lock.v1",
                personLockArtifactSha256 = SHA_B,
                viewQualifierArtifactId = "body-yaw.qualifier.v1",
                viewQualifierArtifactSha256 = SHA_C,
            ),
        )
        val context = PoseCameraGeometryContext(
            sourceImageWidth = 1_920,
            sourceImageHeight = 1_080,
            cropLeft = 100,
            cropTop = 20,
            cropRightExclusive = 1_700,
            cropBottomExclusive = 1_020,
            inputRotationDegrees = 90,
            outputImageWidth = 1_000,
            outputImageHeight = 1_600,
            inferencePixelsMirrored = false,
            displayMirrored = true,
            preprocessingArtifactSha256 = SHA_B,
        )
        val geometry = source.newCameraGeometryEpoch(context)
        val person = source.newPersonTrackEpoch()
        fun observation(timestampMs: Long, modified: Boolean = false) = source.attest(
            frame = PoseFrame(
                timestampMs = timestampMs,
                landmarks = PoseJoint.entries.associateWith { joint ->
                    if (modified && joint == PoseJoint.NOSE) {
                        landmark(joint).copy(x = 0.99)
                    } else {
                        landmark(joint)
                    }
                },
                worldLandmarks = PoseJoint.entries.associateWith(::landmark),
                imageWidth = 1_000,
                imageHeight = 1_600,
                isMirrored = true,
            ),
            personTrackEpoch = person,
            viewQualifications = listOf(
                source.qualifyView(
                    PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                    person,
                    timestampMs,
                ),
            ),
            cameraGeometryEpoch = geometry,
        )
        val evidence = PoseObservationResearchCapabilityEvidence(source, 100L)
        val firstObservation = observation(1_000L)
        val secondObservation = observation(1_040L)
        val modifiedSecondObservation = observation(1_040L, modified = true)
        evidence.accept(firstObservation)
        return ReceiptFixture(
            source = source,
            firstObservation = firstObservation,
            secondObservation = secondObservation,
            modifiedSecondObservation = modifiedSecondObservation,
            receipt = requireNotNull(evidence.accept(secondObservation).receipt),
        )
    }

    private class ReceiptFixture(
        val source: PoseObservationSource,
        val firstObservation: com.example.trex_kotlin.pose.runtime.AttestedPoseObservation,
        val secondObservation: com.example.trex_kotlin.pose.runtime.AttestedPoseObservation,
        val modifiedSecondObservation:
            com.example.trex_kotlin.pose.runtime.AttestedPoseObservation,
        val receipt: PoseObservationResearchCapabilityReceipt,
    )

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

        fun landmark(joint: PoseJoint) = PoseLandmark(
            x = joint.mediaPipeIndex / 100.0,
            y = joint.mediaPipeIndex / 100.0,
            z = joint.mediaPipeIndex / 1_000.0,
        )
    }
}
