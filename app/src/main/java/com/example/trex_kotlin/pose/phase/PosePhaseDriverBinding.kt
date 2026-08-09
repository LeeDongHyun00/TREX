package com.example.trex_kotlin.pose.phase

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationArtifact
import com.example.trex_kotlin.pose.contract.PoseQualitySignalKind
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.SIGNED_FEATURE_RUNTIME_CONTRACT_SHA256
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.feature.contractId
import com.example.trex_kotlin.pose.feature.measure

private val PHASE_SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * Signed composition boundary for a phase feature, phase graph, timing policy, and quality gate.
 *
 * The approved artifact hash covers every phase-decision input. As a result, changing a feature
 * joint/reference, unit/domain, threshold, direction, dwell, edge, dropout policy, or quality
 * artifact cannot silently retain an earlier approval.
 */
internal class PosePhaseDriverBinding(
    val featureSpec: PoseScalarFeatureSpec,
    val engineConfig: PosePhaseEngineConfig,
    val qualityCalibration: PoseQualityCalibrationArtifact,
    val approvedPhaseArtifactSha256: String,
) {
    val phaseArtifactSha256: String = phaseDriverArtifactSha256(
        featureSpec = featureSpec,
        engineConfig = engineConfig,
        qualityCalibration = qualityCalibration,
    )

    init {
        require(qualityCalibration.signalKind == PoseQualitySignalKind.PHASE_GATE_SIGNAL) {
            "Phase binding requires a phase-gate quality calibration"
        }
        require(featureSpec.featureSpecSha256 == qualityCalibration.featureSpecSha256) {
            "Phase quality calibration does not match the phase feature AST"
        }
        require(PHASE_SHA256.matches(approvedPhaseArtifactSha256)) {
            "approvedPhaseArtifactSha256 must be a lowercase SHA-256"
        }
        require(approvedPhaseArtifactSha256 == phaseArtifactSha256) {
            "Phase feature, graph, timing, or quality policy does not match the approved artifact"
        }
    }

    /**
     * Produces an engine observation without treating raw detector confidence as phase quality.
     * A missing scalar or calibration abstention is represented as unusable evidence.
     */
    fun observation(
        frame: PoseFrame,
        featureEngine: PoseFeatureEngine,
    ): PosePhaseObservation {
        require(
            featureEngine.runtimeContractSha256 == SIGNED_FEATURE_RUNTIME_CONTRACT_SHA256,
        ) {
            "Phase feature engine does not match the signed runtime contract"
        }
        val feature = featureEngine.measure(frame, featureSpec)
        return observation(
            timestampMs = frame.timestampMs,
            scalar = feature.value,
            rawConfidence = feature.rawConfidence,
        )
    }

    /** Internal scalar seam for deterministic engine tests; production callers supply a frame. */
    internal fun observation(
        timestampMs: Long,
        scalar: Double?,
        rawConfidence: Double,
    ): PosePhaseObservation {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(scalar == null || scalar.isFinite()) { "A phase scalar must be finite" }
        require(rawConfidence.isFinite() && rawConfidence in 0.0..1.0) {
            "rawConfidence must be finite and in [0, 1]"
        }
        if (scalar == null) {
            return PosePhaseObservation(timestampMs, scalar = null, qualitySignal = 0.0)
        }

        val calibratedSignal = qualityCalibration.calibratedSignal(rawConfidence)
        return PosePhaseObservation(
            timestampMs = timestampMs,
            scalar = calibratedSignal?.let { scalar },
            qualitySignal = calibratedSignal ?: 0.0,
        )
    }
}

/** Canonical artifact identity expected to be pinned by a signed exercise manifest. */
internal fun phaseDriverArtifactSha256(
    featureSpec: PoseScalarFeatureSpec,
    engineConfig: PosePhaseEngineConfig,
    qualityCalibration: PoseQualityCalibrationArtifact,
): String {
    val graph = engineConfig.graph
    val fields = mutableListOf(
        "phaseDriverArtifactSchemaVersion" to "1",
        "featureContractId" to featureSpec.featureContractId,
        "featureSpecSha256" to featureSpec.featureSpecSha256,
        "featureCoordinateSpace" to featureSpec.coordinateSpace.name,
        "featureUnit" to featureSpec.unit.contractId,
        "featureRuntimeContractSha256" to SIGNED_FEATURE_RUNTIME_CONTRACT_SHA256,
        "phaseQualityFeatureSpecSha256" to qualityCalibration.featureSpecSha256,
        "phaseQualityContractId" to qualityCalibration.qualityContractId,
        "phaseQualityRuntimeDomainId" to qualityCalibration.runtimeDomainId,
        "phaseQualityCalibrationArtifactSha256" to
            qualityCalibration.artifactSha256,
        "minimumQualitySignal" to java.lang.Double.toHexString(
            engineConfig.minimumQualitySignal,
        ),
        "maximumObservationGapMs" to engineConfig.maximumObservationGapMs.toString(),
        "unusableObservationGraceMs" to engineConfig.unusableObservationGraceMs.toString(),
        "maximumPhaseDurationMs" to engineConfig.maximumPhaseDurationMs.toString(),
        "initialStateId" to graph.initialStateId.value,
    )

    graph.states.values
        .sortedBy { state -> state.id.value }
        .forEachIndexed { index, state ->
            val predicate = state.enterPredicate
            val prefix = "state[$index]"
            fields += "$prefix.id" to state.id.value
            fields += "$prefix.enterLower" to java.lang.Double.toHexString(
                predicate.enterInterval.lower,
            )
            fields += "$prefix.enterUpper" to java.lang.Double.toHexString(
                predicate.enterInterval.upper,
            )
            fields += "$prefix.holdLower" to java.lang.Double.toHexString(
                predicate.holdInterval.lower,
            )
            fields += "$prefix.holdUpper" to java.lang.Double.toHexString(
                predicate.holdInterval.upper,
            )
            fields += "$prefix.direction" to predicate.direction.name
            fields += "$prefix.directionTolerance" to java.lang.Double.toHexString(
                predicate.directionTolerance,
            )
            fields += "$prefix.minimumDwellMs" to predicate.minimumDwellMs.toString()
        }

    graph.transitions
        .sortedWith(
            compareBy<PosePhaseTransition> { transition -> transition.from.value }
                .thenBy { transition -> transition.to.value }
                .thenBy(PosePhaseTransition::completesCycle),
        )
        .forEachIndexed { index, transition ->
            val prefix = "transition[$index]"
            fields += "$prefix.from" to transition.from.value
            fields += "$prefix.to" to transition.to.value
            fields += "$prefix.completesCycle" to transition.completesCycle.toString()
        }

    return canonicalFieldsSha256(fields)
}
