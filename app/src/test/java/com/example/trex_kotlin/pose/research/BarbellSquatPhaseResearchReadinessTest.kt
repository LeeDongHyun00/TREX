package com.example.trex_kotlin.pose.research

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarbellSquatPhaseResearchReadinessTest {
    @Test
    fun currentPinsTheFinalTrainingOnlyRejectedExperiment() {
        val current = BarbellSquatPhaseResearchReadiness.CURRENT

        assertEquals(
            "6f9c2e5215339e4248055a6a001fa947a75c1781c4119beed74c22d5ca65263f",
            current.reportArtifactSha256,
        )
        assertEquals(
            "286d16329bc3d68e8d2fc48b54d0b9a229f500fed67c23c2f4de3a40b47a39ce",
            current.protocolArtifactSha256,
        )
        assertEquals(
            "820055892273a8888fb15e71cce5c0e9b5169b8f40883ae0ed8e9011aa84a360",
            current.evaluatorCanonicalTextSha256,
        )
        assertEquals(
            "5e8a28bfd2f4c55d8d2eb9b15968a813d1ae6259ec0a1d406aa98f098e511814",
            current.trainingInputManifestSha256,
        )
        assertEquals(
            "47c5240ef6a470f30c942c373b22891fb6710966c6a25d8dcddba44c3fe0ff4d",
            current.experimentIdentitySha256,
        )
        assertEquals(
            BarbellSquatPhaseResearchReadiness.STUDIED_SIGNAL_FAMILY_ID,
            current.studiedSignalFamilyId,
        )
        assertEquals(
            BarbellSquatPhaseResearchReadiness.STUDIED_DECODER_FAMILY_ID,
            current.studiedDecoderFamilyId,
        )
        assertEquals(
            BarbellSquatPhaseResearchStudyCoordinateDomain
                .AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
            current.studyCoordinateDomain,
        )
        assertEquals(
            BarbellSquatPhaseResearchStudyViewRole
                .LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
            current.studyViewRole,
        )
        assertEquals(720, current.trainingSequenceCount)
        assertEquals(42, current.trainingSubjectCount)
        assertEquals(42, current.outerFoldCount)
        assertEquals(159, current.diagnostics.eligibleSequenceCount)
        assertEquals(40, current.diagnostics.eligibleSubjectCount)
        assertEquals(0.2208333333, current.diagnostics.eligibleSequenceCoverage, 0.0)
        assertEquals(0.9523809524, current.diagnostics.eligibleSubjectCoverage, 0.0)
        assertEquals(
            0.259545701,
            current.diagnostics.outerSubjectMacroSurrogateRecall,
            0.0,
        )
        assertEquals(0.5794542536, current.diagnostics.outerPredictionCoverage, 0.0)
        assertEquals(0.0, current.diagnostics.minimumOuterSubjectCoverage, 0.0)
        assertEquals(
            0.4402515723,
            current.diagnostics.outerCompletedOrderedTopologyCoverage,
            0.0,
        )
        assertEquals(1.0, current.diagnostics.causalPrefixInvariance, 0.0)
        assertEquals(
            "7bbb9a1a3e199c5802730a29d167987c0c6786103e98af43c7924de21c859fa4",
            current.artifactSha256,
        )
        assertEquals(0, current.authority.totalAuthority)
    }

    @Test
    fun rejectedReadinessIsDeterministicImmutableAndCarriesNoAuthority() {
        val first = readiness()
        val second = readiness(
            evidenceStatuses = BarbellSquatPhaseResearchEvidenceStatus.entries.reversed().toSet(),
        )

        assertEquals(first.artifactSha256, second.artifactSha256)
        assertTrue(first.artifactSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(
            BarbellSquatPhaseResearchContinuation.CONTINUATION_REJECTED,
            first.continuation,
        )
        assertEquals(
            BarbellSquatPhaseResearchDisposition.RETAIN_RESEARCH_SPECIFICATION_ONLY,
            first.disposition,
        )
        assertEquals(0, first.authority.totalAuthority)
        assertEquals(0, first.authority.releaseAuthority)
        assertEquals(0, first.authority.shadowAuthority)
        assertEquals(0, first.authority.runtimeProviderAuthority)
        assertEquals(0, first.authority.userDecisionAuthority)
        assertEquals(0, first.authority.scoreAuthority)
        assertEquals(0, first.authority.cueAuthority)
        assertEquals(0, first.authority.repetitionCountAuthority)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first.evidenceStatuses as MutableSet).clear()
        }
    }

    @Test
    fun everyProvenanceAndDiagnosticMutationChangesTheContentIdentity() {
        val baseline = readiness()
        val variants = listOf(
            readiness(reportArtifactSha256 = sha('6')),
            readiness(protocolArtifactSha256 = sha('7')),
            readiness(evaluatorCanonicalTextSha256 = sha('8')),
            readiness(trainingInputManifestSha256 = sha('9')),
            readiness(experimentIdentitySha256 = sha('a')),
            readiness(
                diagnostics = diagnostics(outerSubjectMacroSurrogateRecall = 0.26),
            ),
        )

        variants.forEach { variant ->
            assertNotEquals(baseline.artifactSha256, variant.artifactSha256)
        }
    }

    @Test
    fun authorityCannotRepresentPositiveEntriesEvenThroughReflection() {
        assertTrue(
            BarbellSquatPhaseResearchAuthority::class.java.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
        val constructor = BarbellSquatPhaseResearchAuthority::class.java.declaredConstructors
            .single { it.parameterCount == 7 }
        constructor.isAccessible = true

        assertThrows(InvocationTargetException::class.java) {
            constructor.newInstance(0, 0, 1, 0, 0, 0, 0)
        }
        assertThrows(InvocationTargetException::class.java) {
            constructor.newInstance(1, -1, 0, 0, 0, 0, 0)
        }
    }

    @Test
    fun readinessSurfaceContainsNoDecoderParameterOrExecutionSeam() {
        val surfaces = listOf(
            BarbellSquatPhaseResearchReadiness::class.java,
            BarbellSquatPhaseResearchDiagnostics::class.java,
            BarbellSquatPhaseResearchAuthority::class.java,
        )
        val forbiddenTokens = setOf(
            "accept",
            "consume",
            "decode",
            "evaluate",
            "issue",
            "open",
            "predict",
            "threshold",
            "baseline",
            "hysteresis",
            "dwell",
        )

        surfaces.flatMap { type ->
            type.declaredMethods.map { method -> method.name } +
                type.declaredFields.map { field -> field.name }
        }.forEach { name ->
            val tokens = name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter(String::isNotEmpty)
                .map(String::lowercase)
            assertFalse("Forbidden readiness surface: $name", forbiddenTokens.any { it in tokens })
        }
    }

    @Test
    fun inventoryAndEvidenceSemanticsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            readiness(trainingSequenceCount = 719)
        }
        assertThrows(IllegalArgumentException::class.java) {
            readiness(
                evidenceStatuses = BarbellSquatPhaseResearchEvidenceStatus.entries
                    .dropLast(1)
                    .toSet(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            readiness(
                diagnostics = diagnostics(eligibleSequenceCount = 721),
            )
        }
    }

    private fun readiness(
        reportArtifactSha256: String = sha('1'),
        protocolArtifactSha256: String = sha('2'),
        evaluatorCanonicalTextSha256: String = sha('3'),
        trainingInputManifestSha256: String = sha('4'),
        experimentIdentitySha256: String = sha('5'),
        trainingSequenceCount: Int = 720,
        evidenceStatuses: Set<BarbellSquatPhaseResearchEvidenceStatus> =
            BarbellSquatPhaseResearchEvidenceStatus.entries.toSet(),
        diagnostics: BarbellSquatPhaseResearchDiagnostics = diagnostics(),
    ) = BarbellSquatPhaseResearchReadiness(
        artifactId = "trex.research-readiness.barbell-squat.phase-training-surrogate.v1",
        sourceArtifactPath = "docs/barbell-squat-phase-training-experiment.json",
        reportArtifactSha256 = reportArtifactSha256,
        protocolArtifactSha256 = protocolArtifactSha256,
        evaluatorCanonicalTextSha256 = evaluatorCanonicalTextSha256,
        trainingInputManifestSha256 = trainingInputManifestSha256,
        experimentIdentitySha256 = experimentIdentitySha256,
        studiedSignalFamilyId =
            BarbellSquatPhaseResearchReadiness.STUDIED_SIGNAL_FAMILY_ID,
        studiedDecoderFamilyId =
            BarbellSquatPhaseResearchReadiness.STUDIED_DECODER_FAMILY_ID,
        studyCoordinateDomain =
            BarbellSquatPhaseResearchStudyCoordinateDomain
                .AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
        studyViewRole =
            BarbellSquatPhaseResearchStudyViewRole
                .LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
        trainingSequenceCount = trainingSequenceCount,
        trainingSubjectCount = 42,
        outerFoldCount = 42,
        evidenceStatuses = evidenceStatuses,
        diagnostics = diagnostics,
        continuation = BarbellSquatPhaseResearchContinuation.CONTINUATION_REJECTED,
        disposition = BarbellSquatPhaseResearchDisposition.RETAIN_RESEARCH_SPECIFICATION_ONLY,
        authority = BarbellSquatPhaseResearchAuthority.NONE,
    )

    private fun diagnostics(
        eligibleSequenceCount: Int = 159,
        outerSubjectMacroSurrogateRecall: Double = 0.259545701,
    ) = BarbellSquatPhaseResearchDiagnostics(
        eligibleSequenceCount = eligibleSequenceCount,
        eligibleSubjectCount = 40,
        eligibleSequenceCoverage = 0.2208333333,
        eligibleSubjectCoverage = 0.9523809524,
        outerSubjectMacroSurrogateRecall = outerSubjectMacroSurrogateRecall,
        outerPredictionCoverage = 0.5794542536,
        minimumOuterSubjectCoverage = 0.0,
        outerCompletedOrderedTopologyCoverage = 0.4402515723,
        causalPrefixInvariance = 1.0,
    )

    private fun sha(character: Char): String = character.toString().repeat(64)
}
