package com.example.trex_kotlin.pose.readiness

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.research.PoseObservationResearchCapabilities
import com.example.trex_kotlin.pose.research.PoseObservationResearchCapabilityEvidence
import com.example.trex_kotlin.pose.research.PoseObservationResearchCapabilityReceipt
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCriterionRuntimeReadinessTest {
    @Test
    fun catalogProjectionMatchesM10AndM11ExactSets() {
        val templates = PoseCriterionRuntimeReadinessCatalog.templateProfilesForTest()

        assertEquals(41, PoseCriterionRuntimeReadinessCatalog.exerciseCount)
        assertEquals(148, PoseCriterionRuntimeReadinessCatalog.reviewedBindingCount)
        assertEquals(19, PoseCriterionRuntimeReadinessCatalog.unresolvedBindingCount)
        assertEquals(203, PoseCriterionRuntimeReadinessCatalog.annotationTemplateCount)
        assertEquals(
            "e26dabd9fed10049df3909d19561e82bb4b950ffcabe99367b6673141c050f77",
            PoseCriterionRuntimeReadinessCatalog.annotationTemplateIdentitySetSha256,
        )
        assertEquals(78, PoseCriterionRuntimeReadinessCatalog.phaseScopeRequirementCount)
        assertEquals(13, PoseCriterionRuntimeReadinessCatalog.sideResolverRequirementCount)
        assertEquals(18, PoseCriterionRuntimeReadinessCatalog.resolverRequiredTemplateCount)
        assertEquals(203, templates.map { it.annotationTemplateId }.toSet().size)
        assertEquals(
            mapOf(
                "trex.phase-role.compound-transition.v1" to 3,
                "trex.phase-role.concentric.v1" to 2,
                "trex.phase-role.contracted-endpoint.v1" to 26,
                "trex.phase-role.full-cycle.v1" to 135,
                "trex.phase-role.lengthened-endpoint.v1" to 33,
                "trex.phase-role.static-hold.v1" to 4,
            ),
            templates.groupingBy { it.phaseRoleId }.eachCount(),
        )
        assertEquals(
            mapOf(
                AiHubCriterionSidePolicyKind.ACTIVE_LIMB to 3,
                AiHubCriterionSidePolicyKind.ALTERNATING_PAIR to 1,
                AiHubCriterionSidePolicyKind.BILATERAL_COUPLED to 16,
                AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT to 110,
                AiHubCriterionSidePolicyKind.CONTRALATERAL_PAIR to 1,
                AiHubCriterionSidePolicyKind.GLOBAL_BODY to 8,
                AiHubCriterionSidePolicyKind.LEAD_LIMB to 10,
                AiHubCriterionSidePolicyKind.MIDLINE to 51,
                AiHubCriterionSidePolicyKind.TRAIL_LIMB to 3,
            ),
            templates.groupingBy { it.sidePolicyKind }.eachCount(),
        )
        assertEquals(
            mapOf(
                AiHubCriterionObservability.DIRECT to 119,
                AiHubCriterionObservability.PROXY_UNVALIDATED to 62,
                AiHubCriterionObservability.NOT_OBSERVABLE to 22,
            ),
            templates.groupingBy { it.observability }.eachCount(),
        )
        assertEquals(
            "5d52c5408187a24e50c0017fb086675aadef8be757aa1091e6abac8ed64a57b7",
            PoseCriterionRuntimeReadinessCatalog.ANNOTATION_CONTRACT_SHA256,
        )
        assertEquals(
            "5700926bb5aa13e38aa118599d4353691f202090792432a7a539473ad1e0074a",
            PoseCriterionRuntimeReadinessCatalog.SCOPE_RESOLVER_REQUIREMENTS_SHA256,
        )
    }

    @Test
    fun annotationTemplateIdentityMatchesCanonicalM10KnownAnswer() {
        val goodMorning = PoseCriterionRuntimeReadinessCatalog.templateProfilesForTest()
            .filter { it.bindingId == GOOD_MORNING_KNEE_STABILITY_BINDING_ID }
            .associateBy { it.symbolicSideSlot }

        assertEquals(
            "trex.annotation-template-sha256-" +
                "514afc3300caba14fc227fe6eab777e1b8e219e57c67926efbc8742a5281139a",
            goodMorning.getValue(PoseCriterionSymbolicSideSlot.LEFT).annotationTemplateId,
        )
        assertEquals(
            "trex.annotation-template-sha256-" +
                "a6fc0f37110241e2ede63e5495f2fdb5005399e04be366eed430e7a92052d187",
            goodMorning.getValue(PoseCriterionSymbolicSideSlot.RIGHT).annotationTemplateId,
        )
    }

    @Test
    fun unresolvedBindingsRemainUnknownAndGenerateNoTemplates() {
        val unresolved = PoseCriterionRuntimeReadinessCatalog.unresolvedProfilesForTest()
        val reviewedBindingIds = PoseCriterionRuntimeReadinessCatalog.templateProfilesForTest()
            .map { it.bindingId }
            .toSet()

        assertEquals(19, unresolved.size)
        assertTrue(unresolved.none { it.bindingId in reviewedBindingIds })
        unresolved.forEach { profile ->
            assertTrue(profile.reviewState != AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1)
            assertEquals(PoseCriterionRuntimeDecisionState.UNKNOWN, profile.decisionState)
            assertEquals(
                setOf(PoseCriterionRuntimeReadinessBlocker.SOURCE_INTERPRETATION_UNRESOLVED),
                profile.blockers,
            )
            assertFalse(profile.measurementEnabled)
            assertFalse(profile.verdictEnabled)
            assertFalse(profile.cueEnabled)
            assertFalse(profile.releaseEnabled)
        }
    }

    @Test
    fun emptyEvidenceMakesAll203TemplatesUnknownWithExactBlockerClasses() {
        val assessments = AiHubExercise.entries.flatMap { exercise ->
            PoseCriterionRuntimeReadinessCatalog.assess(exercise, null, null)
                .templateAssessments
        }

        assertEquals(203, assessments.size)
        assertEquals(203, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.PHASE_SCOPE_CONTRACT_UNAVAILABLE in it.blockers
        })
        assertEquals(203, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE in it.blockers
        })
        assertEquals(203, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.MEASUREMENT_CONSTRUCT_PROVIDER_UNAVAILABLE in
                it.blockers
        })
        listOf(
            PoseCriterionRuntimeReadinessBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
        ).forEach { blocker ->
            assertEquals(203, assessments.count { blocker in it.blockers })
        }
        assertEquals(203, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.REQUIRED_CAPABILITY_EVIDENCE_UNAVAILABLE in
                it.blockers
        })
        assertEquals(181, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.QUALIFIED_VIEW_EVIDENCE_UNAVAILABLE in it.blockers
        })
        assertEquals(22, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.CAMERA_VIEW_NOT_SUFFICIENT in it.blockers
        })
        assertEquals(62, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.PROXY_GOLD_VALIDATION_UNAVAILABLE in it.blockers
        })
        assertEquals(18, assessments.count {
            PoseCriterionRuntimeReadinessBlocker.SIDE_ROLE_RESOLVER_UNAVAILABLE in it.blockers
        })
        assertTrue(assessments.all { it.decisionState == PoseCriterionRuntimeDecisionState.UNKNOWN })
        assertTrue(assessments.none {
            it.measurementEnabled || it.verdictEnabled || it.scoreEnabled || it.cueEnabled ||
                it.shadowEnabled || it.releaseEnabled
        })
    }

    @Test
    fun canonicalLateralReceiptOnlySatisfiesWhatItActuallyProves() {
        val fixture = capabilityFixture()
        val assessment = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            fixture.secondObservation,
            fixture.receipt,
        )
        val kneeTemplates = assessment.templateAssessments.filter {
            it.profile.bindingId == GOOD_MORNING_KNEE_STABILITY_BINDING_ID
        }
        val trunkTemplate = assessment.templateAssessments.single {
            it.profile.bindingId != GOOD_MORNING_KNEE_STABILITY_BINDING_ID
        }

        assertEquals(3, assessment.templateAssessments.size)
        assertEquals(1, assessment.unresolvedBindings.size)
        assertEquals(2, assessment.observationEvidenceSatisfiedTemplateCount)
        assertTrue(kneeTemplates.all { it.capabilityEvidenceSatisfied })
        assertTrue(kneeTemplates.all { it.qualifiedViewEvidenceSatisfied })
        assertTrue(kneeTemplates.all { it.observationEvidenceSatisfied })
        assertFalse(trunkTemplate.capabilityEvidenceSatisfied)
        assertTrue(trunkTemplate.qualifiedViewEvidenceSatisfied)
        assertFalse(trunkTemplate.observationEvidenceSatisfied)
        assertTrue(
            PoseCriterionRuntimeReadinessBlocker.REQUIRED_CAPABILITY_EVIDENCE_UNAVAILABLE in
                trunkTemplate.blockers,
        )
        assertTrue(
            PoseCriterionRuntimeReadinessBlocker.PROXY_GOLD_VALIDATION_UNAVAILABLE in
                trunkTemplate.blockers,
        )
        assertTrue(assessment.templateAssessments.all {
            it.decisionState == PoseCriterionRuntimeDecisionState.UNKNOWN &&
                !it.measurementEnabled && !it.verdictEnabled && !it.cueEnabled &&
                !it.releaseEnabled
        })
        assertEquals(fixture.receipt.evidenceContractSha256,
            assessment.capabilityEvidenceContractSha256)
        assertEquals(fixture.receipt.receiptSha256, assessment.capabilityReceiptSha256)
    }

    @Test
    fun staleModifiedForeignAndClosedReceiptsFailClosed() {
        val fixture = capabilityFixture()
        val valid = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            fixture.secondObservation,
            fixture.receipt,
        )
        val stale = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            fixture.firstObservation,
            fixture.receipt,
        )
        val modified = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            fixture.modifiedSecondObservation,
            fixture.receipt,
        )
        val foreign = capabilityFixture()
        val replayed = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            foreign.secondObservation,
            fixture.receipt,
        )

        assertEquals(2, valid.observationEvidenceSatisfiedTemplateCount)
        listOf(stale, modified, replayed).forEach { rejected ->
            assertEquals(0, rejected.observationEvidenceSatisfiedTemplateCount)
            assertNull(rejected.capabilityEvidenceContractSha256)
            assertNull(rejected.capabilityReceiptSha256)
        }
        fixture.source.close()
        val closed = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            fixture.secondObservation,
            fixture.receipt,
        )
        assertEquals(0, closed.observationEvidenceSatisfiedTemplateCount)
        assertNull(closed.capabilityReceiptSha256)
        foreign.source.close()
    }

    @Test
    fun outputsAreImmutableAndExposeNoProductResultTypes() {
        val assessment = PoseCriterionRuntimeReadinessCatalog.assess(
            AiHubExercise.GOOD_MORNING,
            null,
            null,
        )
        @Suppress("UNCHECKED_CAST")
        val mutable = assessment.templateAssessments as MutableList<Any>
        assertThrows(UnsupportedOperationException::class.java) { mutable.clear() }
        @Suppress("UNCHECKED_CAST")
        val mutableBlockers = assessment.templateAssessments.first().blockers as MutableSet<Any>
        assertThrows(UnsupportedOperationException::class.java) { mutableBlockers.clear() }

        val forbiddenTypeFragments = listOf(
            "PoseCriterionResult",
            "PoseCriterionGraph",
            "PoseFeedback",
            "PoseMetrics",
        )
        val exposedTypes = listOf(
            PoseCriterionRuntimeReadinessAssessment::class.java,
            PoseCriterionRuntimeTemplateAssessment::class.java,
            PoseCriterionRuntimeTemplateProfile::class.java,
        ).flatMap { type ->
            type.declaredMethods.map { it.returnType.name } +
                type.declaredFields.map { it.type.name }
        }
        assertTrue(exposedTypes.none { exposed ->
            forbiddenTypeFragments.any(exposed::contains)
        })
    }

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
                    landmark(joint).let { value ->
                        if (modified && joint == PoseJoint.NOSE) value.copy(x = 0.99) else value
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
        val first = observation(1_000L)
        val second = observation(1_040L)
        val modified = observation(1_040L, modified = true)
        evidence.accept(first)
        return ReceiptFixture(
            source = source,
            firstObservation = first,
            secondObservation = second,
            modifiedSecondObservation = modified,
            receipt = requireNotNull(evidence.accept(second).receipt),
        )
    }

    private class ReceiptFixture(
        val source: PoseObservationSource,
        val firstObservation: AttestedPoseObservation,
        val secondObservation: AttestedPoseObservation,
        val modifiedSecondObservation: AttestedPoseObservation,
        val receipt: PoseObservationResearchCapabilityReceipt,
    )

    private companion object {
        const val GOOD_MORNING_KNEE_STABILITY_BINDING_ID =
            "aihub-binding-sha256-f900f3dc681053ed9b705e020bac0ed27336aa5776885406a3c07a6db67d453d"
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
