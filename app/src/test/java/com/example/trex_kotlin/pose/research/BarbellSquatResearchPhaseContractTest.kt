package com.example.trex_kotlin.pose.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarbellSquatResearchPhaseContractTest {
    @Test
    fun artifactIsDeterministicAndConsumedEvidenceDriftChangesIdentity() {
        val first = BarbellSquatResearchPhaseContract.createCurrent()
        val second = BarbellSquatResearchPhaseContract.createCurrent()

        assertEquals(first.artifactSha256, second.artifactSha256)
        assertEquals(
            "7ca4751630f7625ffb8c7858ed74f3a921aa6ce91666f9a9ed60e827a9374352",
            first.artifactSha256,
        )
        assertTrue(first.artifactSha256.matches(Regex("^[0-9a-f]{64}$")))

        val changedEvidence = first.evidenceProvenance.copy(
            sourceReportFingerprintSha256 = "f".repeat(64),
        )
        val drifted = BarbellSquatResearchPhaseContract.createCurrent(changedEvidence)
        assertNotEquals(first.artifactSha256, drifted.artifactSha256)
    }

    @Test
    fun topologyAndHalfOpenCycleScopeAreExact() {
        val artifact = BarbellSquatResearchPhaseContract.createCurrent()

        assertEquals(
            listOf(
                BarbellSquatResearchPhaseState.READY,
                BarbellSquatResearchPhaseState.DESCENDING,
                BarbellSquatResearchPhaseState.BOTTOM,
                BarbellSquatResearchPhaseState.ASCENDING,
                BarbellSquatResearchPhaseState.READY,
            ),
            artifact.cyclePath,
        )
        assertEquals(
            listOf(
                BarbellSquatResearchPhaseTransition(
                    BarbellSquatResearchPhaseState.READY,
                    BarbellSquatResearchPhaseState.DESCENDING,
                    false,
                ),
                BarbellSquatResearchPhaseTransition(
                    BarbellSquatResearchPhaseState.DESCENDING,
                    BarbellSquatResearchPhaseState.BOTTOM,
                    false,
                ),
                BarbellSquatResearchPhaseTransition(
                    BarbellSquatResearchPhaseState.BOTTOM,
                    BarbellSquatResearchPhaseState.ASCENDING,
                    false,
                ),
                BarbellSquatResearchPhaseTransition(
                    BarbellSquatResearchPhaseState.ASCENDING,
                    BarbellSquatResearchPhaseState.READY,
                    true,
                ),
            ),
            artifact.transitions,
        )
        assertEquals(1, artifact.transitions.count { it.completesCycle })
        assertTrue(artifact.transitions.last().completesCycle)
        assertEquals(
            BarbellSquatResearchWindowSemantics.START_INCLUSIVE_END_EXCLUSIVE,
            artifact.windowSemantics,
        )

        assertThrows(IllegalArgumentException::class.java) {
            rebuild(artifact, cyclePath = artifact.cyclePath.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            rebuild(
                artifact,
                transitions = artifact.transitions.mapIndexed { index, edge ->
                    edge.copy(completesCycle = index == 0)
                },
            )
        }
    }

    @Test
    fun scalarCandidatesAreCausalViewSpecificAndMustAgreeWhenBothAreAvailable() {
        val artifact = BarbellSquatResearchPhaseContract.createCurrent()
        val front = artifact.scalarCandidates.single {
            it.candidateId == BarbellSquatResearchPhaseContract.FRONT_CANDIDATE_ID
        }
        val lateral = artifact.scalarCandidates.single {
            it.candidateId == BarbellSquatResearchPhaseContract.LATERAL_CANDIDATE_ID
        }

        assertEquals(
            setOf(BarbellSquatResearchView.FRONT, BarbellSquatResearchView.FRONT_OBLIQUE),
            front.applicableViews,
        )
        assertEquals(setOf(BarbellSquatResearchView.LATERAL), lateral.applicableViews)
        assertTrue(front.formulaContractId.contains("pelvis-to-bilateral-ankle"))
        assertTrue(front.normalizationContractId.contains("causal-ready-prefix-running-mean"))
        assertTrue(front.causalityContractId.contains("no-lookahead"))
        assertTrue(lateral.formulaContractId.contains("median-left-right-knee-flexion"))
        assertEquals("trex.research-normalization.none.v1", lateral.normalizationContractId)
        assertEquals(
            BarbellSquatResearchCandidateAgreementPolicy
                .REQUIRE_ALL_SIMULTANEOUSLY_AVAILABLE_CANDIDATES_TO_AGREE,
            artifact.candidateAgreementPolicy,
        )
        assertEquals(BarbellSquatResearchAgreementDimension.entries.toSet(), artifact.agreementDimensions)
        assertEquals(
            BarbellSquatResearchProhibitedTemporalOperation.entries.toSet(),
            artifact.prohibitedTemporalOperations,
        )
    }

    @Test
    fun everyDiscontinuityResetsAndDiscardsAndIncompleteCyclesDiscard() {
        val artifact = BarbellSquatResearchPhaseContract.createCurrent()

        assertEquals(BarbellSquatResearchResetCause.entries.toSet(), artifact.resetCauses)
        assertEquals(BarbellSquatResearchDiscardCause.entries.toSet(), artifact.discardCauses)
        assertTrue(
            BarbellSquatResearchDiscardCause.INCOMPLETE_ORDERED_CYCLE in artifact.discardCauses,
        )
        assertTrue(
            BarbellSquatResearchResetCause.AVAILABLE_CANDIDATE_DISAGREEMENT in
                artifact.resetCauses,
        )
    }

    @Test
    fun consumedDevelopmentEvidenceAndNonApprovalLimitsAreExplicit() {
        val artifact = BarbellSquatResearchPhaseContract.createCurrent()

        assertEquals(BarbellSquatResearchArtifactUse.RESEARCH_CANDIDATE_ONLY, artifact.artifactUse)
        assertEquals(BarbellSquatResearchExecutionMode.SPECIFICATION_ONLY, artifact.executionMode)
        assertEquals(
            BarbellSquatResearchValidationUse
                .PRIOR_VALIDATION_CONSUMED_NOT_READ_OR_REUSED_IN_TRAINING_PHASE_EXPERIMENT,
            artifact.evidenceProvenance.officialValidationUse,
        )
        assertEquals(
            BarbellSquatPhaseResearchReadiness.CURRENT.reportArtifactSha256,
            artifact.evidenceProvenance.sourceReportFingerprintSha256,
        )
        assertEquals(
            BarbellSquatPhaseResearchReadiness.CURRENT.protocolArtifactSha256,
            artifact.evidenceProvenance.protocolArtifactSha256,
        )
        assertEquals(
            BarbellSquatPhaseResearchReadiness.CURRENT.artifactSha256,
            artifact.evidenceProvenance.readinessArtifactSha256,
        )
        assertEquals(
            BarbellSquatResearchPhaseContract.LATERAL_CANDIDATE_ID,
            artifact.evidenceProvenance.studiedSignalFamilyId,
        )
        assertNotEquals(
            BarbellSquatResearchPhaseContract.FRONT_CANDIDATE_ID,
            artifact.evidenceProvenance.studiedSignalFamilyId,
        )
        assertEquals(
            BarbellSquatPhaseResearchReadiness.STUDIED_DECODER_FAMILY_ID,
            artifact.evidenceProvenance.studiedDecoderFamilyId,
        )
        assertEquals(
            BarbellSquatPhaseResearchStudyCoordinateDomain
                .AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
            artifact.evidenceProvenance.studyCoordinateDomain,
        )
        assertEquals(
            BarbellSquatPhaseResearchStudyViewRole
                .LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
            artifact.evidenceProvenance.studyViewRole,
        )
        assertEquals(
            BarbellSquatResearchPhaseSupervision.ACTIVE_MASK_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD,
            artifact.evidenceProvenance.phaseSupervision,
        )
        assertEquals(
            BarbellSquatResearchFrameTimeEvidence
                .NO_RELIABLE_FRAME_RATE_OR_FRAME_INTERVAL_GROUND_TRUTH,
            artifact.evidenceProvenance.frameTimeEvidence,
        )
        assertEquals(BarbellSquatResearchLimitation.entries.toSet(), artifact.limitations)
        assertTrue(BarbellSquatResearchLimitation.NO_PHASE_GOLD in artifact.limitations)
        assertTrue(
            BarbellSquatResearchLimitation.TRAINING_SURROGATE_CONTINUATION_REJECTED in
                artifact.limitations,
        )
        assertTrue(BarbellSquatResearchLimitation.NO_RUNTIME_PHASE_PROVIDER_BINDING in artifact.limitations)
        assertTrue(
            BarbellSquatResearchLimitation.NO_AUTHORIZED_RUNTIME_PHASE_CALIBRATION_ARTIFACT in
                artifact.limitations,
        )
        assertTrue(BarbellSquatResearchLimitation.NO_PRODUCT_RELEASE_AUTHORITY in artifact.limitations)
        assertTrue(BarbellSquatResearchLimitation.NO_USER_DECISION_AUTHORITY in artifact.limitations)
    }

    @Test
    fun ownedCollectionsAreImmutable() {
        val artifact = BarbellSquatResearchPhaseContract.createCurrent()

        assertImmutableList(artifact.cyclePath)
        assertImmutableList(artifact.transitions)
        assertImmutableList(artifact.scalarCandidates)
        assertImmutableSet(artifact.scalarCandidates.first().applicableViews)
        assertImmutableSet(artifact.agreementDimensions)
        assertImmutableSet(artifact.prohibitedTemporalOperations)
        assertImmutableSet(artifact.resetCauses)
        assertImmutableSet(artifact.discardCauses)
        assertImmutableSet(artifact.limitations)
    }

    @Test
    fun contractSurfaceHasNoDecoderOrUserDecisionApi() {
        val contractTypes = listOf(
            BarbellSquatResearchPhaseContract::class.java,
            BarbellSquatResearchScalarCandidate::class.java,
            BarbellSquatResearchEvidenceProvenance::class.java,
        )
        val forbiddenMethodTokens = listOf(
            "threshold",
            "verdict",
            "cue",
            "score",
            "evaluate",
            "session",
            "approve",
            "release",
            "pass",
            "fail",
        )
        val methods = contractTypes.flatMap { it.declaredMethods.toList() }

        methods.forEach { method ->
            val surface = buildString {
                append(method.name)
                append(':')
                append(method.returnType.name)
                method.parameterTypes.forEach { append(':').append(it.name) }
            }.lowercase()
            forbiddenMethodTokens.forEach { token ->
                assertTrue("Forbidden research API token '$token' in $surface", token !in surface)
            }
            assertTrue("Runtime phase binding leaked into $surface", "posephaseengine" !in surface)
            assertTrue("Observation input leaked into $surface", "poseobservation" !in surface)
            assertTrue("Criterion output leaked into $surface", "posecriterionresult" !in surface)
        }
    }

    private fun rebuild(
        source: BarbellSquatResearchPhaseContract,
        cyclePath: List<BarbellSquatResearchPhaseState> = source.cyclePath,
        transitions: List<BarbellSquatResearchPhaseTransition> = source.transitions,
    ) = BarbellSquatResearchPhaseContract(
        contractId = source.contractId,
        artifactUse = source.artifactUse,
        executionMode = source.executionMode,
        cyclePath = cyclePath,
        transitions = transitions,
        cycleScopeStartPolicyId = source.cycleScopeStartPolicyId,
        cycleScopeEndPolicyId = source.cycleScopeEndPolicyId,
        windowSemantics = source.windowSemantics,
        scalarCandidates = source.scalarCandidates,
        candidateAgreementPolicy = source.candidateAgreementPolicy,
        agreementDimensions = source.agreementDimensions,
        prohibitedTemporalOperations = source.prohibitedTemporalOperations,
        resetCauses = source.resetCauses,
        discardCauses = source.discardCauses,
        evidenceProvenance = source.evidenceProvenance,
        limitations = source.limitations,
    )

    private fun assertImmutableList(values: List<*>) {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (values as MutableList<Any?>).clear()
        }
    }

    private fun assertImmutableSet(values: Set<*>) {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (values as MutableSet<Any?>).clear()
        }
    }
}
