package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256

private val READINESS_SHA256 = Regex("^[0-9a-f]{64}$")

/** The held-subject research gate did not justify carrying decoder parameters into runtime. */
internal enum class BarbellSquatPhaseResearchContinuation {
    CONTINUATION_REJECTED,
}

/** A failed surrogate study remains evidence about readiness, not an executable calibration. */
internal enum class BarbellSquatPhaseResearchDisposition {
    RETAIN_RESEARCH_SPECIFICATION_ONLY,
}

/** Explicit evidence semantics for the Training-only experiment. */
internal enum class BarbellSquatPhaseResearchEvidenceStatus {
    AIHUB_OFFICIAL_TRAINING_ONLY,
    OFFICIAL_VALIDATION_NOT_READ_NOT_REUSED,
    RETROSPECTIVE_MORPHOLOGY_SURROGATE_NOT_PHASE_GOLD,
    NO_RELIABLE_FRAME_RATE_OR_FRAME_INTERVAL_GROUND_TRUTH,
}

internal enum class BarbellSquatPhaseResearchStudyCoordinateDomain {
    AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
}

internal enum class BarbellSquatPhaseResearchStudyViewRole {
    LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
}

/**
 * Counts of every authority that the rejected research artifact could otherwise be mistaken for.
 *
 * This type deliberately has no positive-entry representation. Introducing any authority requires
 * a different schema and an independently reviewed authorization boundary.
 */
internal class BarbellSquatPhaseResearchAuthority private constructor(
    val releaseAuthority: Int,
    val shadowAuthority: Int,
    val runtimeProviderAuthority: Int,
    val userDecisionAuthority: Int,
    val scoreAuthority: Int,
    val cueAuthority: Int,
    val repetitionCountAuthority: Int,
) {
    val totalAuthority: Int = releaseAuthority +
        shadowAuthority +
        runtimeProviderAuthority +
        userDecisionAuthority +
        scoreAuthority +
        cueAuthority +
        repetitionCountAuthority

    init {
        require(
            listOf(
                releaseAuthority,
                shadowAuthority,
                runtimeProviderAuthority,
                userDecisionAuthority,
                scoreAuthority,
                cueAuthority,
                repetitionCountAuthority,
            ).all { authorityCount -> authorityCount == 0 },
        ) {
            "A rejected research-continuation artifact cannot carry execution authority"
        }
    }

    internal companion object {
        val NONE = BarbellSquatPhaseResearchAuthority(
            releaseAuthority = 0,
            shadowAuthority = 0,
            runtimeProviderAuthority = 0,
            userDecisionAuthority = 0,
            scoreAuthority = 0,
            cueAuthority = 0,
            repetitionCountAuthority = 0,
        )
    }
}

/** Diagnostic outcomes reported by held-subject evaluation; none is phase accuracy. */
internal class BarbellSquatPhaseResearchDiagnostics internal constructor(
    val eligibleSequenceCount: Int,
    val eligibleSubjectCount: Int,
    val eligibleSequenceCoverage: Double,
    val eligibleSubjectCoverage: Double,
    val outerSubjectMacroSurrogateRecall: Double,
    val outerPredictionCoverage: Double,
    val minimumOuterSubjectCoverage: Double,
    val outerCompletedOrderedTopologyCoverage: Double,
    val causalPrefixInvariance: Double,
) {
    init {
        require(eligibleSequenceCount >= 0)
        require(eligibleSubjectCount >= 0)
        listOf(
            eligibleSequenceCoverage,
            eligibleSubjectCoverage,
            outerSubjectMacroSurrogateRecall,
            outerPredictionCoverage,
            minimumOuterSubjectCoverage,
            outerCompletedOrderedTopologyCoverage,
            causalPrefixInvariance,
        ).forEach { value ->
            require(value.isFinite() && value in 0.0..1.0) {
                "Research diagnostic rates must be finite and in [0, 1]"
            }
        }
    }
}

/**
 * Immutable, content-addressed readiness result for the AI Hub Training-only phase experiment.
 *
 * The artifact intentionally contains no decoder thresholds, graph, timing values, pose-consumer,
 * phase-token issuer, provider factory, or product decision API. A report hash records what was
 * evaluated; it is neither a signature nor permission to execute the rejected candidate.
 */
internal class BarbellSquatPhaseResearchReadiness internal constructor(
    val artifactId: String,
    val sourceArtifactPath: String,
    val reportArtifactSha256: String,
    val protocolArtifactSha256: String,
    val evaluatorCanonicalTextSha256: String,
    val trainingInputManifestSha256: String,
    val experimentIdentitySha256: String,
    val studiedSignalFamilyId: String,
    val studiedDecoderFamilyId: String,
    val studyCoordinateDomain: BarbellSquatPhaseResearchStudyCoordinateDomain,
    val studyViewRole: BarbellSquatPhaseResearchStudyViewRole,
    val trainingSequenceCount: Int,
    val trainingSubjectCount: Int,
    val outerFoldCount: Int,
    evidenceStatuses: Set<BarbellSquatPhaseResearchEvidenceStatus>,
    val diagnostics: BarbellSquatPhaseResearchDiagnostics,
    val continuation: BarbellSquatPhaseResearchContinuation,
    val disposition: BarbellSquatPhaseResearchDisposition,
    val authority: BarbellSquatPhaseResearchAuthority,
) {
    val evidenceStatuses: Set<BarbellSquatPhaseResearchEvidenceStatus> =
        java.util.Collections.unmodifiableSet(
            LinkedHashSet(evidenceStatuses.sortedBy { status -> status.name }),
        )

    init {
        require(artifactId == "trex.research-readiness.barbell-squat.phase-training-surrogate.v1")
        require(sourceArtifactPath == "docs/barbell-squat-phase-training-experiment.json")
        require(studiedSignalFamilyId == STUDIED_SIGNAL_FAMILY_ID)
        require(studiedDecoderFamilyId == STUDIED_DECODER_FAMILY_ID)
        require(
            studyCoordinateDomain ==
                BarbellSquatPhaseResearchStudyCoordinateDomain
                    .AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
        )
        require(
            studyViewRole ==
                BarbellSquatPhaseResearchStudyViewRole
                    .LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
        )
        listOf(
            reportArtifactSha256,
            protocolArtifactSha256,
            evaluatorCanonicalTextSha256,
            trainingInputManifestSha256,
            experimentIdentitySha256,
        ).forEach { hash ->
            require(READINESS_SHA256.matches(hash)) {
                "Research provenance must use lowercase SHA-256 identities"
            }
        }
        require(trainingSequenceCount == 720)
        require(trainingSubjectCount == 42)
        require(outerFoldCount == trainingSubjectCount)
        require(this.evidenceStatuses == BarbellSquatPhaseResearchEvidenceStatus.entries.toSet())
        require(diagnostics.eligibleSequenceCount <= trainingSequenceCount)
        require(diagnostics.eligibleSubjectCount <= trainingSubjectCount)
        require(
            continuation == BarbellSquatPhaseResearchContinuation.CONTINUATION_REJECTED,
        )
        require(
            disposition ==
                BarbellSquatPhaseResearchDisposition.RETAIN_RESEARCH_SPECIFICATION_ONLY,
        )
        require(authority.totalAuthority == 0)
    }

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("barbellSquatPhaseResearchReadinessSchemaVersion" to "1")
            add("artifactId" to artifactId)
            add("sourceArtifactPath" to sourceArtifactPath)
            add("reportArtifactSha256" to reportArtifactSha256)
            add("protocolArtifactSha256" to protocolArtifactSha256)
            add("evaluatorCanonicalTextSha256" to evaluatorCanonicalTextSha256)
            add("trainingInputManifestSha256" to trainingInputManifestSha256)
            add("experimentIdentitySha256" to experimentIdentitySha256)
            add("studiedSignalFamilyId" to studiedSignalFamilyId)
            add("studiedDecoderFamilyId" to studiedDecoderFamilyId)
            add("studyCoordinateDomain" to studyCoordinateDomain.name)
            add("studyViewRole" to studyViewRole.name)
            add("trainingSequenceCount" to trainingSequenceCount.toString())
            add("trainingSubjectCount" to trainingSubjectCount.toString())
            add("outerFoldCount" to outerFoldCount.toString())
            val orderedEvidenceStatuses =
                this@BarbellSquatPhaseResearchReadiness.evidenceStatuses
            add("evidenceStatusCount" to orderedEvidenceStatuses.size.toString())
            orderedEvidenceStatuses.forEachIndexed { index, status ->
                add("evidenceStatus[$index]" to status.name)
            }
            add("eligibleSequenceCount" to diagnostics.eligibleSequenceCount.toString())
            add("eligibleSubjectCount" to diagnostics.eligibleSubjectCount.toString())
            add(
                "eligibleSequenceCoverage" to
                    java.lang.Double.toHexString(diagnostics.eligibleSequenceCoverage),
            )
            add(
                "eligibleSubjectCoverage" to
                    java.lang.Double.toHexString(diagnostics.eligibleSubjectCoverage),
            )
            add(
                "outerSubjectMacroSurrogateRecall" to
                    java.lang.Double.toHexString(
                        diagnostics.outerSubjectMacroSurrogateRecall,
                    ),
            )
            add(
                "outerPredictionCoverage" to
                    java.lang.Double.toHexString(diagnostics.outerPredictionCoverage),
            )
            add(
                "minimumOuterSubjectCoverage" to
                    java.lang.Double.toHexString(diagnostics.minimumOuterSubjectCoverage),
            )
            add(
                "outerCompletedOrderedTopologyCoverage" to
                    java.lang.Double.toHexString(
                        diagnostics.outerCompletedOrderedTopologyCoverage,
                    ),
            )
            add(
                "causalPrefixInvariance" to
                    java.lang.Double.toHexString(diagnostics.causalPrefixInvariance),
            )
            add("continuation" to continuation.name)
            add("disposition" to disposition.name)
            add("authority.release" to authority.releaseAuthority.toString())
            add("authority.shadow" to authority.shadowAuthority.toString())
            add("authority.runtimeProvider" to authority.runtimeProviderAuthority.toString())
            add("authority.userDecision" to authority.userDecisionAuthority.toString())
            add("authority.score" to authority.scoreAuthority.toString())
            add("authority.cue" to authority.cueAuthority.toString())
            add("authority.repetitionCount" to authority.repetitionCountAuthority.toString())
        },
    )

    internal companion object {
        const val STUDIED_SIGNAL_FAMILY_ID =
            "trex.research-phase-signal.barbell-squat.bilateral-knee-flexion-median.v1"
        const val STUDIED_DECODER_FAMILY_ID =
            "trex.research-phase-decoder.barbell-squat.absolute-knee-flexion-hysteresis.v1"

        private const val CURRENT_REPORT_SHA256 =
            "6f9c2e5215339e4248055a6a001fa947a75c1781c4119beed74c22d5ca65263f"
        private const val CURRENT_PROTOCOL_SHA256 =
            "286d16329bc3d68e8d2fc48b54d0b9a229f500fed67c23c2f4de3a40b47a39ce"
        private const val CURRENT_EVALUATOR_CANONICAL_TEXT_SHA256 =
            "820055892273a8888fb15e71cce5c0e9b5169b8f40883ae0ed8e9011aa84a360"
        private const val CURRENT_TRAINING_INPUT_MANIFEST_SHA256 =
            "5e8a28bfd2f4c55d8d2eb9b15968a813d1ae6259ec0a1d406aa98f098e511814"
        private const val CURRENT_EXPERIMENT_IDENTITY_SHA256 =
            "47c5240ef6a470f30c942c373b22891fb6710966c6a25d8dcddba44c3fe0ff4d"
        private const val REPOSITORY_DRIFT_PIN_SHA256 =
            "7bbb9a1a3e199c5802730a29d167987c0c6786103e98af43c7924de21c859fa4"

        val CURRENT: BarbellSquatPhaseResearchReadiness = createCurrent().also { readiness ->
            check(readiness.artifactSha256 == REPOSITORY_DRIFT_PIN_SHA256) {
                "Barbell-squat phase research readiness drifted: " +
                    "expected=$REPOSITORY_DRIFT_PIN_SHA256, " +
                    "actual=${readiness.artifactSha256}"
            }
        }

        internal fun createCurrent(
            reportArtifactSha256: String = CURRENT_REPORT_SHA256,
            protocolArtifactSha256: String = CURRENT_PROTOCOL_SHA256,
            evaluatorCanonicalTextSha256: String =
                CURRENT_EVALUATOR_CANONICAL_TEXT_SHA256,
            trainingInputManifestSha256: String = CURRENT_TRAINING_INPUT_MANIFEST_SHA256,
            experimentIdentitySha256: String = CURRENT_EXPERIMENT_IDENTITY_SHA256,
            diagnostics: BarbellSquatPhaseResearchDiagnostics = currentDiagnostics(),
        ): BarbellSquatPhaseResearchReadiness = BarbellSquatPhaseResearchReadiness(
            artifactId =
                "trex.research-readiness.barbell-squat.phase-training-surrogate.v1",
            sourceArtifactPath = "docs/barbell-squat-phase-training-experiment.json",
            reportArtifactSha256 = reportArtifactSha256,
            protocolArtifactSha256 = protocolArtifactSha256,
            evaluatorCanonicalTextSha256 = evaluatorCanonicalTextSha256,
            trainingInputManifestSha256 = trainingInputManifestSha256,
            experimentIdentitySha256 = experimentIdentitySha256,
            studiedSignalFamilyId = STUDIED_SIGNAL_FAMILY_ID,
            studiedDecoderFamilyId = STUDIED_DECODER_FAMILY_ID,
            studyCoordinateDomain =
                BarbellSquatPhaseResearchStudyCoordinateDomain
                    .AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
            studyViewRole =
                BarbellSquatPhaseResearchStudyViewRole
                    .LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
            trainingSequenceCount = 720,
            trainingSubjectCount = 42,
            outerFoldCount = 42,
            evidenceStatuses = BarbellSquatPhaseResearchEvidenceStatus.entries.toSet(),
            diagnostics = diagnostics,
            continuation = BarbellSquatPhaseResearchContinuation.CONTINUATION_REJECTED,
            disposition =
                BarbellSquatPhaseResearchDisposition.RETAIN_RESEARCH_SPECIFICATION_ONLY,
            authority = BarbellSquatPhaseResearchAuthority.NONE,
        )

        private fun currentDiagnostics() = BarbellSquatPhaseResearchDiagnostics(
            eligibleSequenceCount = 159,
            eligibleSubjectCount = 40,
            eligibleSequenceCoverage = 0.2208333333,
            eligibleSubjectCoverage = 0.9523809524,
            outerSubjectMacroSurrogateRecall = 0.259545701,
            outerPredictionCoverage = 0.5794542536,
            minimumOuterSubjectCoverage = 0.0,
            outerCompletedOrderedTopologyCoverage = 0.4402515723,
            causalPrefixInvariance = 1.0,
        )
    }
}
