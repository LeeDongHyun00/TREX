package com.example.trex_kotlin.pose.criterion

import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

private fun calibrationArtifactSha256(
    contract: CriterionCalibrationContract,
    additiveErrorInterval: MeasurementInterval,
): String {
    val aggregationIdentity = when (val aggregation = contract.aggregation) {
        is CriterionAggregation.WeightedQuantile -> {
            "weighted-quantile:${java.lang.Double.toHexString(aggregation.quantile)}"
        }
        CriterionAggregation.WeightedMean -> "weighted-mean"
    }
    val fields = listOf(
        "artifactSchemaVersion" to "1",
        "criterionId" to contract.criterionId,
        "featureContractId" to contract.featureContractId,
        "featureSpecSha256" to contract.featureSpecSha256,
        "samplingContractSha256" to contract.samplingContractSha256,
        "measurementUnit" to contract.measurementUnit,
        "aggregation" to aggregationIdentity,
        "qualityContractId" to contract.qualityContractId,
        "qualityCalibrationArtifactSha256" to contract.qualityCalibrationArtifactSha256,
        "runtimeDomainId" to contract.runtimeDomainId,
        "validMeasurementLower" to java.lang.Double.toHexString(
            contract.validMeasurementInterval.lower,
        ),
        "validMeasurementUpper" to java.lang.Double.toHexString(
            contract.validMeasurementInterval.upper,
        ),
        "minimumSampleQuality" to java.lang.Double.toHexString(contract.minimumSampleQuality),
        "minimumTimeCoverage" to java.lang.Double.toHexString(contract.minimumTimeCoverage),
        "minimumEvidenceMass" to java.lang.Double.toHexString(contract.minimumEvidenceMass),
        "minimumObservableDurationMs" to contract.minimumObservableDurationMs.toString(),
        "minimumEffectiveSamples" to java.lang.Double.toHexString(
            contract.minimumEffectiveSamples,
        ),
        "maximumGapMs" to contract.maximumGapMs.toString(),
        "correlationHorizonMs" to contract.correlationHorizonMs.toString(),
        "contractVersion" to contract.contractVersion.toString(),
        "additiveErrorLower" to java.lang.Double.toHexString(additiveErrorInterval.lower),
        "additiveErrorUpper" to java.lang.Double.toHexString(additiveErrorInterval.upper),
    )
    return canonicalFieldsSha256(fields)
}

/** Canonical identity expected to be pinned by an independently authorized evaluator manifest. */
internal fun evaluatorSpecSha256(
    approvedCalibrationArtifactSha256: String,
    targetInterval: MeasurementInterval,
    requiredCapabilities: Set<CriterionCapability>,
): String = canonicalFieldsSha256(
    listOf(
        "evaluatorSpecSchemaVersion" to "1",
        "approvedCalibrationArtifactSha256" to approvedCalibrationArtifactSha256,
        "targetLower" to java.lang.Double.toHexString(targetInterval.lower),
        "targetUpper" to java.lang.Double.toHexString(targetInterval.upper),
        "requiredCapabilities" to requiredCapabilities
            .map(CriterionCapability::name)
            .sorted()
            .joinToString(","),
    ),
)

/** A finite, inclusive interval in the criterion's declared measurement unit. */
data class MeasurementInterval(
    val lower: Double,
    val upper: Double,
) {
    init {
        require(lower.isFinite() && upper.isFinite()) {
            "Measurement interval bounds must be finite"
        }
        require(lower <= upper) {
            "Measurement interval lower bound must not exceed its upper bound"
        }
    }
}

/** Explicit phase boundaries prevent leading/trailing observation gaps from disappearing. */
data class CriterionPhaseWindow(
    val startTimestampMs: Long,
    val endTimestampMs: Long,
) {
    init {
        require(startTimestampMs >= 0L) { "Phase start must be non-negative" }
        require(endTimestampMs > startTimestampMs) { "Phase end must be after phase start" }
    }

    val durationMs: Long
        get() = endTimestampMs - startTimestampMs
}

enum class CriterionState {
    PASS,
    FAIL,
    UNKNOWN,
}

/** Direction is retained here; user-facing cues belong to a later policy layer. */
enum class CriterionFailRegion {
    LOW_SIDE,
    HIGH_SIDE,
}

enum class CriterionUnknownReason {
    MISSING_CAPABILITY,
    UNCALIBRATED_DOMAIN,
    CALIBRATION_CONTRACT_MISMATCH,
    CALIBRATION_ARTIFACT_MISMATCH,
    INVALID_MEASUREMENT,
    NUMERIC_ERROR,
    NO_EVIDENCE,
    NO_ELIGIBLE_EVIDENCE,
    EXCESSIVE_GAP,
    INSUFFICIENT_TIME_COVERAGE,
    INSUFFICIENT_EVIDENCE_MASS,
    INSUFFICIENT_OBSERVABLE_DURATION,
    INSUFFICIENT_EFFECTIVE_SAMPLES,
    BOUNDARY_OVERLAP,
}

enum class CriterionCapability {
    POSE_2D,
    POSE_WORLD_RELATIVE,
    TEMPORAL_POSE,
    GROUND_PROXY,
    OBJECT_TRACK,
    PRIMARY_PERSON_LOCK,
    VIEW_QUALIFIED,
    ANATOMICAL_SEGMENT_FRAME,
}

/** The aggregation policy is versioned with each criterion instead of selected at runtime. */
sealed interface CriterionAggregation {
    data class WeightedQuantile(val quantile: Double) : CriterionAggregation {
        init {
            require(quantile.isFinite() && quantile > 0.0 && quantile < 1.0) {
                "Weighted quantile must be finite and in (0, 1)"
            }
        }
    }

    data object WeightedMean : CriterionAggregation
}

/**
 * Exact identity of the construct and runtime domain used to produce a calibration artifact.
 *
 * Opaque IDs are versioned by the offline pipeline. In particular, [featureContractId] includes
 * landmark mapping and reference-frame semantics, while [qualityContractId] identifies the
 * calibrated meaning of a sample quality value. A model, camera/view, or evaluator change gets a
 * different [runtimeDomainId]. The quality gate and residual correlation horizon are part of this
 * identity because changing either invalidates the calibrated phase aggregate or evidence count.
 */
data class CriterionCalibrationContract(
    val criterionId: String,
    val featureContractId: String,
    val featureSpecSha256: String,
    val samplingContractSha256: String,
    val measurementUnit: String,
    val aggregation: CriterionAggregation,
    val qualityContractId: String,
    val qualityCalibrationArtifactSha256: String,
    val runtimeDomainId: String,
    val validMeasurementInterval: MeasurementInterval,
    val minimumSampleQuality: Double,
    val minimumTimeCoverage: Double,
    val minimumEvidenceMass: Double,
    val minimumObservableDurationMs: Long,
    val minimumEffectiveSamples: Double,
    val maximumGapMs: Long,
    val correlationHorizonMs: Long,
    val contractVersion: Int,
) {
    init {
        require(criterionId.isNotBlank()) { "criterionId must not be blank" }
        require(featureContractId.isNotBlank()) { "featureContractId must not be blank" }
        require(SHA256_REGEX.matches(featureSpecSha256)) {
            "featureSpecSha256 must be a lowercase SHA-256 value"
        }
        require(SHA256_REGEX.matches(samplingContractSha256)) {
            "samplingContractSha256 must be a lowercase SHA-256 value"
        }
        require(measurementUnit.isNotBlank()) { "measurementUnit must not be blank" }
        require(qualityContractId.isNotBlank()) { "qualityContractId must not be blank" }
        require(SHA256_REGEX.matches(qualityCalibrationArtifactSha256)) {
            "qualityCalibrationArtifactSha256 must be a lowercase SHA-256 value"
        }
        require(runtimeDomainId.isNotBlank()) { "runtimeDomainId must not be blank" }
        require(minimumSampleQuality.isFinite() && minimumSampleQuality > 0.0 && minimumSampleQuality <= 1.0) {
            "minimumSampleQuality must be finite and in (0, 1]"
        }
        require(minimumTimeCoverage.isFinite() && minimumTimeCoverage > 0.0 && minimumTimeCoverage <= 1.0) {
            "minimumTimeCoverage must be finite and in (0, 1]"
        }
        require(minimumEvidenceMass.isFinite() && minimumEvidenceMass > 0.0 && minimumEvidenceMass <= 1.0) {
            "minimumEvidenceMass must be finite and in (0, 1]"
        }
        require(minimumObservableDurationMs > 0L) {
            "minimumObservableDurationMs must be positive"
        }
        require(minimumEffectiveSamples.isFinite() && minimumEffectiveSamples >= 1.0) {
            "minimumEffectiveSamples must be finite and at least one"
        }
        require(maximumGapMs > 0L) { "maximumGapMs must be positive" }
        require(correlationHorizonMs > 0L) { "correlationHorizonMs must be positive" }
        require(contractVersion > 0) { "contractVersion must be positive" }
    }
}

/**
 * A narrow service contract for a single scalar criterion.
 *
 * Durations and event-order constructs are derived as temporal features before entering this
 * scalar engine. The spec has no unsafe evidence-mass default: every caller must choose a positive
 * threshold from calibration data. Both pinned SHA values are content-addressed dependencies of
 * the evaluator package; the constructor recomputes the evaluator identity so target/capability
 * weakening cannot retain an earlier package identity. This does not authenticate a signer.
 */
class PoseCriterionSpec(
    val calibrationContract: CriterionCalibrationContract,
    val approvedCalibrationArtifactSha256: String,
    val approvedEvaluatorSpecSha256: String,
    val targetInterval: MeasurementInterval,
    requiredCapabilities: Set<CriterionCapability>,
) {
    /** Copying prevents a caller from mutating a validated spec through its input set. */
    val requiredCapabilities: Set<CriterionCapability> =
        Collections.unmodifiableSet(LinkedHashSet(requiredCapabilities))

    val evaluatorSpecSha256: String = evaluatorSpecSha256(
        approvedCalibrationArtifactSha256 = approvedCalibrationArtifactSha256,
        targetInterval = targetInterval,
        requiredCapabilities = this.requiredCapabilities,
    )

    val criterionId: String
        get() = calibrationContract.criterionId

    val aggregation: CriterionAggregation
        get() = calibrationContract.aggregation

    val minimumSampleQuality: Double
        get() = calibrationContract.minimumSampleQuality

    val correlationHorizonMs: Long
        get() = calibrationContract.correlationHorizonMs

    val minimumTimeCoverage: Double
        get() = calibrationContract.minimumTimeCoverage

    val minimumEvidenceMass: Double
        get() = calibrationContract.minimumEvidenceMass

    val minimumObservableDurationMs: Long
        get() = calibrationContract.minimumObservableDurationMs

    val minimumEffectiveSamples: Double
        get() = calibrationContract.minimumEffectiveSamples

    val maximumGapMs: Long
        get() = calibrationContract.maximumGapMs

    init {
        require(SHA256_REGEX.matches(approvedCalibrationArtifactSha256)) {
            "approvedCalibrationArtifactSha256 must be a lowercase SHA-256"
        }
        require(SHA256_REGEX.matches(approvedEvaluatorSpecSha256)) {
            "approvedEvaluatorSpecSha256 must be a lowercase SHA-256"
        }
        require(this.requiredCapabilities.isNotEmpty()) {
            "A pose criterion must declare at least one required capability"
        }
        require(approvedEvaluatorSpecSha256 == evaluatorSpecSha256) {
            "target interval or required capabilities do not match the approved evaluator spec"
        }
    }
}

/**
 * Error interval calibrated for the same phase-level aggregation declared by the criterion.
 *
 * If e = Gold aggregate - runtime aggregate, [additiveErrorInterval] contains e. The engine never
 * treats a frame-level landmark interval as a substitute for this phase-level calibration.
 */
data class CriterionAggregateCalibration(
    val contract: CriterionCalibrationContract,
    val additiveErrorInterval: MeasurementInterval,
) {
    /** Content identity is recomputed from every decision-relevant artifact field. */
    val artifactSha256: String = calibrationArtifactSha256(contract, additiveErrorInterval)
}

/** A null [measurement] records elapsed but unobservable time. */
data class CriterionEvidenceSample(
    val timestampMs: Long,
    val measurement: Double?,
    val qualityWeight: Double,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(measurement == null || measurement.isFinite()) { "measurement must be finite" }
        require(qualityWeight.isFinite() && qualityWeight in 0.0..1.0) {
            "qualityWeight must be finite and in [0, 1]"
        }
    }
}

class PoseCriterionResult internal constructor(
    val criterionId: String,
    val evaluatorSpecSha256: String,
    val state: CriterionState,
    val failRegion: CriterionFailRegion?,
    val unknownReason: CriterionUnknownReason?,
    val aggregatedMeasurement: Double?,
    val calibratedMeasurementInterval: MeasurementInterval?,
    val calibrationArtifactSha256: String?,
    val timeCoverage: Double,
    val evidenceMass: Double,
    val windowDurationMs: Long,
    val observableDurationMs: Double,
    val eligibleDurationMs: Double,
    val rawEffectiveSamples: Double,
    val effectiveSamples: Double,
    val correlationHorizonMs: Long,
    val maximumEvidenceGapMs: Long,
    val missingCapabilities: Set<CriterionCapability>,
) {
    init {
        require(criterionId.isNotBlank())
        require(SHA256_REGEX.matches(evaluatorSpecSha256))
        require(aggregatedMeasurement == null || aggregatedMeasurement.isFinite())
        require(timeCoverage.isFinite() && timeCoverage in 0.0..1.0)
        require(evidenceMass.isFinite() && evidenceMass in 0.0..1.0)
        require(windowDurationMs > 0L)
        require(observableDurationMs.isFinite() && observableDurationMs >= 0.0)
        require(
            eligibleDurationMs.isFinite() &&
                eligibleDurationMs >= 0.0 &&
                eligibleDurationMs <= observableDurationMs,
        )
        require(rawEffectiveSamples.isFinite() && rawEffectiveSamples >= 0.0)
        require(effectiveSamples.isFinite() && effectiveSamples >= 0.0)
        require(correlationHorizonMs > 0L)
        require(maximumEvidenceGapMs >= 0L)
        require(
            calibrationArtifactSha256 == null || SHA256_REGEX.matches(calibrationArtifactSha256),
        )
        when (state) {
            CriterionState.PASS -> require(
                failRegion == null &&
                    unknownReason == null &&
                    calibratedMeasurementInterval != null &&
                    calibrationArtifactSha256 != null &&
                    missingCapabilities.isEmpty(),
            )
            CriterionState.FAIL -> require(
                failRegion != null &&
                    unknownReason == null &&
                    calibratedMeasurementInterval != null &&
                    calibrationArtifactSha256 != null &&
                    missingCapabilities.isEmpty(),
            )
            CriterionState.UNKNOWN -> require(failRegion == null && unknownReason != null)
        }
    }
}

/**
 * Aggregates one explicit phase window, applies its matching calibration, and then uses interval
 * containment for the final PASS / FAIL / UNKNOWN decision.
 */
class PoseCriterionEngine {
    fun evaluate(
        spec: PoseCriterionSpec,
        phaseWindow: CriterionPhaseWindow,
        samples: List<CriterionEvidenceSample>,
        availableCapabilities: Set<CriterionCapability>,
        calibration: CriterionAggregateCalibration?,
    ): PoseCriterionResult {
        validateSamples(phaseWindow, samples)
        val summary = summarize(phaseWindow, samples, spec)
        val missingCapabilities = spec.requiredCapabilities - availableCapabilities
        val matchingCalibration = calibration?.takeIf {
            it.contract == spec.calibrationContract &&
                it.artifactSha256 == spec.approvedCalibrationArtifactSha256
        }
        val calibratedBounds = summary.aggregatedMeasurement?.let { aggregate ->
            matchingCalibration?.additiveErrorInterval?.let { error ->
                aggregate + error.lower to aggregate + error.upper
            }
        }
        val calibratedInterval = calibratedBounds?.let { (lower, upper) ->
            if (lower.isFinite() && upper.isFinite()) MeasurementInterval(lower, upper) else null
        }
        val numericalCalibrationFailure = calibratedBounds != null && calibratedInterval == null

        val unknownReason = when {
            missingCapabilities.isNotEmpty() -> CriterionUnknownReason.MISSING_CAPABILITY
            calibration == null -> CriterionUnknownReason.UNCALIBRATED_DOMAIN
            calibration.contract != spec.calibrationContract -> {
                CriterionUnknownReason.CALIBRATION_CONTRACT_MISMATCH
            }
            calibration.artifactSha256 != spec.approvedCalibrationArtifactSha256 -> {
                CriterionUnknownReason.CALIBRATION_ARTIFACT_MISMATCH
            }
            summary.invalidMeasurementCount > 0 -> CriterionUnknownReason.INVALID_MEASUREMENT
            numericalCalibrationFailure -> CriterionUnknownReason.NUMERIC_ERROR
            summary.observedSegmentCount == 0 -> CriterionUnknownReason.NO_EVIDENCE
            summary.timeCoverage < spec.minimumTimeCoverage -> {
                CriterionUnknownReason.INSUFFICIENT_TIME_COVERAGE
            }
            summary.evidenceMass < spec.minimumEvidenceMass -> {
                CriterionUnknownReason.INSUFFICIENT_EVIDENCE_MASS
            }
            summary.eligibleSegmentCount == 0 -> CriterionUnknownReason.NO_ELIGIBLE_EVIDENCE
            summary.maximumEvidenceGapMs > spec.maximumGapMs -> CriterionUnknownReason.EXCESSIVE_GAP
            summary.observableDurationMs < spec.minimumObservableDurationMs.toDouble() -> {
                CriterionUnknownReason.INSUFFICIENT_OBSERVABLE_DURATION
            }
            summary.effectiveSamples < spec.minimumEffectiveSamples -> {
                CriterionUnknownReason.INSUFFICIENT_EFFECTIVE_SAMPLES
            }
            summary.aggregatedMeasurement == null || calibratedInterval == null -> {
                CriterionUnknownReason.INSUFFICIENT_EVIDENCE_MASS
            }
            else -> null
        }

        if (unknownReason != null) {
            return result(
                spec = spec,
                state = CriterionState.UNKNOWN,
                unknownReason = unknownReason,
                summary = summary,
                calibration = calibration,
                calibratedInterval = calibratedInterval,
                missingCapabilities = missingCapabilities,
            )
        }

        val measurement = requireNotNull(calibratedInterval)
        val target = spec.targetInterval
        return when {
            measurement.lower >= target.lower && measurement.upper <= target.upper -> result(
                spec = spec,
                state = CriterionState.PASS,
                summary = summary,
                calibration = calibration,
                calibratedInterval = measurement,
            )
            measurement.upper < target.lower -> result(
                spec = spec,
                state = CriterionState.FAIL,
                failRegion = CriterionFailRegion.LOW_SIDE,
                summary = summary,
                calibration = calibration,
                calibratedInterval = measurement,
            )
            measurement.lower > target.upper -> result(
                spec = spec,
                state = CriterionState.FAIL,
                failRegion = CriterionFailRegion.HIGH_SIDE,
                summary = summary,
                calibration = calibration,
                calibratedInterval = measurement,
            )
            else -> result(
                spec = spec,
                state = CriterionState.UNKNOWN,
                unknownReason = CriterionUnknownReason.BOUNDARY_OVERLAP,
                summary = summary,
                calibration = calibration,
                calibratedInterval = measurement,
            )
        }
    }

    private fun validateSamples(
        phaseWindow: CriterionPhaseWindow,
        samples: List<CriterionEvidenceSample>,
    ) {
        samples.forEach { sample ->
            require(
                sample.timestampMs >= phaseWindow.startTimestampMs &&
                    sample.timestampMs < phaseWindow.endTimestampMs,
            ) {
                "Criterion sample ${sample.timestampMs} is outside phase window " +
                    "[${phaseWindow.startTimestampMs}, ${phaseWindow.endTimestampMs})"
            }
        }
        samples.zipWithNext().forEach { (previous, current) ->
            require(current.timestampMs > previous.timestampMs) {
                "Criterion sample timestamps must be strictly increasing; " +
                    "found ${previous.timestampMs} then ${current.timestampMs}"
            }
        }
    }

    /**
     * A time segment is observable only when both endpoints are observable. This conservative
     * trapezoidal rule never extrapolates the first or last frame to a phase boundary.
     */
    private fun summarize(
        phaseWindow: CriterionPhaseWindow,
        samples: List<CriterionEvidenceSample>,
        spec: PoseCriterionSpec,
    ): EvidenceSummary {
        val sampleWeights = DoubleArray(samples.size)
        var observableDurationMs = 0.0
        var qualityDurationMs = 0.0
        var eligibleDurationMs = 0.0
        var observedSegmentCount = 0
        var eligibleSegmentCount = 0

        samples.zipWithNext().forEachIndexed { index, (left, right) ->
            if (left.measurement != null && right.measurement != null) {
                val durationMs = (right.timestampMs - left.timestampMs).toDouble()
                observedSegmentCount += 1
                observableDurationMs += durationMs
                qualityDurationMs += durationMs * (left.qualityWeight + right.qualityWeight) / 2.0
                if (
                    left.isEligible(spec.calibrationContract) &&
                    right.isEligible(spec.calibrationContract)
                ) {
                    eligibleSegmentCount += 1
                    eligibleDurationMs += durationMs
                    sampleWeights[index] += durationMs * left.qualityWeight / 2.0
                    sampleWeights[index + 1] += durationMs * right.qualityWeight / 2.0
                }
            }
        }

        val weightedMeasurements = samples.mapIndexedNotNull { index, sample ->
            val measurement = sample.measurement
            val weight = sampleWeights[index]
            if (measurement != null && weight > 0.0) {
                WeightedMeasurement(sample.timestampMs, measurement, weight)
            } else {
                null
            }
        }
        val weightSum = weightedMeasurements.sumOf(WeightedMeasurement::weight)
        val squaredWeightSum = weightedMeasurements.sumOf { it.weight * it.weight }
        val rawEffectiveSamples = if (squaredWeightSum > 0.0) {
            weightSum * weightSum / squaredWeightSum
        } else {
            0.0
        }
        val correlationAdjustedTimeSupport = if (eligibleDurationMs > 0.0) {
            max(1.0, eligibleDurationMs / spec.correlationHorizonMs.toDouble())
        } else {
            0.0
        }
        val effectiveSamples = min(rawEffectiveSamples, correlationAdjustedTimeSupport)
        val aggregatedMeasurement = aggregate(weightedMeasurements, spec.aggregation)
        val windowDurationMs = phaseWindow.durationMs

        return EvidenceSummary(
            aggregatedMeasurement = aggregatedMeasurement,
            timeCoverage = (observableDurationMs / windowDurationMs).coerceIn(0.0, 1.0),
            evidenceMass = (qualityDurationMs / windowDurationMs).coerceIn(0.0, 1.0),
            windowDurationMs = windowDurationMs,
            observableDurationMs = observableDurationMs,
            eligibleDurationMs = eligibleDurationMs,
            rawEffectiveSamples = rawEffectiveSamples,
            effectiveSamples = effectiveSamples,
            correlationHorizonMs = spec.correlationHorizonMs,
            maximumEvidenceGapMs = maximumEvidenceGapMs(
                phaseWindow,
                samples,
                spec.calibrationContract,
            ),
            observedSegmentCount = observedSegmentCount,
            eligibleSegmentCount = eligibleSegmentCount,
            invalidMeasurementCount = samples.count {
                it.measurement != null && !it.isWithinMeasurementRange(spec.calibrationContract)
            },
        )
    }

    /** Null and zero-quality frames must not make a long usable-evidence gap appear continuous. */
    private fun maximumEvidenceGapMs(
        phaseWindow: CriterionPhaseWindow,
        samples: List<CriterionEvidenceSample>,
        contract: CriterionCalibrationContract,
    ): Long {
        val evidenceTimestamps = samples
            .filter { it.isEligible(contract) }
            .map(CriterionEvidenceSample::timestampMs)
        if (evidenceTimestamps.isEmpty()) return phaseWindow.durationMs

        return buildList {
            add(evidenceTimestamps.first() - phaseWindow.startTimestampMs)
            evidenceTimestamps.zipWithNext().forEach { (previous, current) -> add(current - previous) }
            add(phaseWindow.endTimestampMs - evidenceTimestamps.last())
        }.max()
    }

    private fun aggregate(
        samples: List<WeightedMeasurement>,
        aggregation: CriterionAggregation,
    ): Double? {
        if (samples.isEmpty()) return null
        return when (aggregation) {
            is CriterionAggregation.WeightedQuantile -> weightedQuantile(samples, aggregation.quantile)
            CriterionAggregation.WeightedMean -> weightedMean(samples)
        }
    }

    /** Incremental convex weighting keeps the mean finite for every finite in-range value. */
    private fun weightedMean(samples: List<WeightedMeasurement>): Double {
        var mean = samples.first().measurement
        var cumulativeWeight = samples.first().weight
        samples.drop(1).forEach { sample ->
            val nextWeight = cumulativeWeight + sample.weight
            val ratio = sample.weight / nextWeight
            mean = if ((mean >= 0.0) == (sample.measurement >= 0.0)) {
                mean + (sample.measurement - mean) * ratio
            } else {
                mean * (1.0 - ratio) + sample.measurement * ratio
            }
            cumulativeWeight = nextWeight
        }
        return mean
    }

    private fun weightedQuantile(samples: List<WeightedMeasurement>, quantile: Double): Double {
        val sorted = samples.sortedWith(
            compareBy<WeightedMeasurement>(WeightedMeasurement::measurement)
                .thenBy(WeightedMeasurement::timestampMs),
        )
        val threshold = sorted.sumOf(WeightedMeasurement::weight) * quantile
        var cumulative = 0.0
        sorted.forEach { sample ->
            cumulative += sample.weight
            if (cumulative >= threshold) return sample.measurement
        }
        return sorted.last().measurement
    }

    private fun result(
        spec: PoseCriterionSpec,
        state: CriterionState,
        summary: EvidenceSummary,
        calibration: CriterionAggregateCalibration?,
        calibratedInterval: MeasurementInterval?,
        failRegion: CriterionFailRegion? = null,
        unknownReason: CriterionUnknownReason? = null,
        missingCapabilities: Set<CriterionCapability> = emptySet(),
    ): PoseCriterionResult = PoseCriterionResult(
        criterionId = spec.criterionId,
        evaluatorSpecSha256 = spec.evaluatorSpecSha256,
        state = state,
        failRegion = failRegion,
        unknownReason = unknownReason,
        aggregatedMeasurement = summary.aggregatedMeasurement,
        calibratedMeasurementInterval = calibratedInterval,
        calibrationArtifactSha256 = calibration?.artifactSha256,
        timeCoverage = summary.timeCoverage,
        evidenceMass = summary.evidenceMass,
        windowDurationMs = summary.windowDurationMs,
        observableDurationMs = summary.observableDurationMs,
        eligibleDurationMs = summary.eligibleDurationMs,
        rawEffectiveSamples = summary.rawEffectiveSamples,
        effectiveSamples = summary.effectiveSamples,
        correlationHorizonMs = summary.correlationHorizonMs,
        maximumEvidenceGapMs = summary.maximumEvidenceGapMs,
        missingCapabilities = missingCapabilities.toSet(),
    )

    private data class WeightedMeasurement(
        val timestampMs: Long,
        val measurement: Double,
        val weight: Double,
    )

    private data class EvidenceSummary(
        val aggregatedMeasurement: Double?,
        val timeCoverage: Double,
        val evidenceMass: Double,
        val windowDurationMs: Long,
        val observableDurationMs: Double,
        val eligibleDurationMs: Double,
        val rawEffectiveSamples: Double,
        val effectiveSamples: Double,
        val correlationHorizonMs: Long,
        val maximumEvidenceGapMs: Long,
        val observedSegmentCount: Int,
        val eligibleSegmentCount: Int,
        val invalidMeasurementCount: Int,
    )

    private fun CriterionEvidenceSample.isWithinMeasurementRange(
        contract: CriterionCalibrationContract,
    ): Boolean {
        val value = measurement ?: return false
        return value >= contract.validMeasurementInterval.lower &&
            value <= contract.validMeasurementInterval.upper
    }

    private fun CriterionEvidenceSample.isEligible(
        contract: CriterionCalibrationContract,
    ): Boolean = isWithinMeasurementRange(contract) &&
        qualityWeight >= contract.minimumSampleQuality
}
