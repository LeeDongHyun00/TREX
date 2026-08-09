package com.example.trex_kotlin.pose.criterion

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationArtifact
import com.example.trex_kotlin.pose.contract.PoseQualitySignalKind
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.feature.contractId
import com.example.trex_kotlin.pose.feature.measure

/**
 * Runtime semantics that calibration artifacts must reproduce exactly.
 *
 * The hash covers the attested adapter and temporal aggregation rules; changing a boundary,
 * duplicate, view-abstention, quality-abstention, or interpolation policy invalidates calibration.
 */
internal val ATTESTED_CRITERION_SAMPLING_CONTRACT_SHA256: String = canonicalFieldsSha256(
    listOf(
        "criterionSamplingContractSchemaVersion" to "1",
        "windowMembership" to "HALF_OPEN_START_INCLUSIVE_END_EXCLUSIVE",
        "timestampOrder" to "STRICT_AFTER_FIRST_DUPLICATE_RETAINED",
        "foreignPersonOrSource" to "REJECT",
        "unqualifiedView" to "NULL_MEASUREMENT_ZERO_WEIGHT",
        "unknownFeature" to "NULL_MEASUREMENT_ZERO_WEIGHT",
        "qualityAbstention" to "NULL_MEASUREMENT_ZERO_WEIGHT",
        "observableSegment" to "BOTH_ENDPOINTS_OBSERVABLE",
        "segmentIntegration" to "TRAPEZOIDAL_ENDPOINT_QUALITY_V1",
        "eligibleSampleWeight" to "HALF_SEGMENT_DURATION_TIMES_ENDPOINT_QUALITY_V1",
        "timeCoverage" to "OBSERVABLE_SEGMENT_DURATION_OVER_HALF_OPEN_WINDOW_V1",
        "effectiveSamples" to "MIN_KISH_ESS_AND_CORRELATION_HORIZON_SUPPORT_V1",
        "weightedMean" to "INCREMENTAL_CONVEX_FINITE_V1",
        "weightedQuantile" to "VALUE_THEN_TIMESTAMP_LOWER_CUMULATIVE_BOUND_V1",
        "boundaryExtrapolation" to "NONE",
        "evidenceGap" to "ELIGIBLE_TIMESTAMPS_PLUS_WINDOW_BOUNDARIES_V1",
    ),
)

/**
 * Safe binding between a data-defined feature, its hash-pinned criterion specification, and the exact
 * quality calibration used to accumulate evidence.
 */
class PoseCriterionFeatureBinding(
    val criterionSpec: PoseCriterionSpec,
    val featureSpec: PoseScalarFeatureSpec,
    val qualityCalibration: PoseQualityCalibrationArtifact,
) {
    init {
        val contract = criterionSpec.calibrationContract
        require(contract.featureContractId == featureSpec.featureContractId) {
            "Feature contract does not match criterion calibration contract"
        }
        require(contract.featureSpecSha256 == featureSpec.featureSpecSha256) {
            "Feature AST does not match criterion calibration contract"
        }
        require(qualityCalibration.signalKind == PoseQualitySignalKind.CRITERION_EVIDENCE_WEIGHT) {
            "Criterion binding requires a criterion evidence-weight calibration"
        }
        require(contract.featureSpecSha256 == qualityCalibration.featureSpecSha256) {
            "Quality calibration feature AST does not match criterion calibration contract"
        }
        require(contract.measurementUnit == featureSpec.unit.contractId) {
            "Feature unit does not match criterion calibration contract"
        }
        require(contract.qualityContractId == qualityCalibration.qualityContractId) {
            "Quality calibration does not match criterion calibration contract"
        }
        require(
            contract.qualityCalibrationArtifactSha256 ==
                qualityCalibration.artifactSha256,
        ) {
            "Quality calibration artifact does not match criterion calibration contract"
        }
        require(contract.runtimeDomainId == qualityCalibration.runtimeDomainId) {
            "Quality calibration runtime domain does not match criterion calibration contract"
        }

        val coordinateCapability = when (featureSpec.coordinateSpace) {
            PoseCoordinateSpace.NORMALIZED_IMAGE -> CriterionCapability.POSE_2D
            PoseCoordinateSpace.WORLD -> CriterionCapability.POSE_WORLD_RELATIVE
        }
        require(coordinateCapability in criterionSpec.requiredCapabilities) {
            "Criterion must require the capability used by its feature coordinate domain"
        }
        require(CriterionCapability.PRIMARY_PERSON_LOCK in criterionSpec.requiredCapabilities) {
            "A runtime criterion binding requires primary-person lock"
        }
        require(CriterionCapability.VIEW_QUALIFIED in criterionSpec.requiredCapabilities) {
            "A runtime criterion binding requires a validated view contract"
        }
    }
}

enum class CriterionQualityUnknownReason {
    CALIBRATOR_ABSTAINED,
}

data class PoseCriterionSample(
    val evidence: CriterionEvidenceSample,
    val featureUnknownReason: FeatureUnknownReason?,
    val rawConfidence: Double,
    /** False means the immutable quality calibration abstained or the feature was unknown. */
    val qualityCalibrated: Boolean,
    val qualityUnknownReason: CriterionQualityUnknownReason?,
)

/** Frame-level adapter. Phase aggregation and interval decisions remain in [PoseCriterionEngine]. */
class PoseCriterionSampler(
    private val featureEngine: PoseFeatureEngine,
) {
    fun sample(
        frame: PoseFrame,
        binding: PoseCriterionFeatureBinding,
    ): PoseCriterionSample {
        val feature = featureEngine.measure(frame, binding.featureSpec)
        if (!feature.isKnown) {
            return PoseCriterionSample(
                evidence = CriterionEvidenceSample(
                    timestampMs = frame.timestampMs,
                    measurement = null,
                    qualityWeight = 0.0,
                ),
                featureUnknownReason = feature.unknownReason,
                rawConfidence = feature.rawConfidence,
                qualityCalibrated = false,
                qualityUnknownReason = null,
            )
        }

        val calibratedWeight = binding.qualityCalibration.calibratedSignal(feature.rawConfidence)
        if (calibratedWeight == null) {
            return noQualityEvidence(
                frame = frame,
                rawConfidence = feature.rawConfidence,
                reason = CriterionQualityUnknownReason.CALIBRATOR_ABSTAINED,
            )
        }

        return PoseCriterionSample(
            evidence = CriterionEvidenceSample(
                timestampMs = frame.timestampMs,
                measurement = feature.value,
                qualityWeight = calibratedWeight,
            ),
            featureUnknownReason = null,
            rawConfidence = feature.rawConfidence,
            qualityCalibrated = true,
            qualityUnknownReason = null,
        )
    }

    private fun noQualityEvidence(
        frame: PoseFrame,
        rawConfidence: Double,
        reason: CriterionQualityUnknownReason,
    ): PoseCriterionSample = PoseCriterionSample(
        evidence = CriterionEvidenceSample(
            timestampMs = frame.timestampMs,
            measurement = null,
            qualityWeight = 0.0,
        ),
        featureUnknownReason = null,
        rawConfidence = rawConfidence,
        qualityCalibrated = false,
        qualityUnknownReason = reason,
    )
}
