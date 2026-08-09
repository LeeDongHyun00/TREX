package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.feature.FeatureMeasurement
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.PoseFeaturePrimitiveContract
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.feature.measure
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import java.util.Collections

private const val SIGNAL_CONTRACT_ID =
    "trex.research-signal.barbell-squat.lateral-bilateral-knee-flexion.v1"
private const val SIGNAL_FORMULA_ID =
    "supplementary-world-hip-knee-ankle-angle.bilateral-even-median.v1"
private const val RESEARCH_ONLY_USE_ID =
    "raw-research-signal-only.no-decoder-or-user-decision-authority.v1"
private const val DEFAULT_MINIMUM_WORLD_LANDMARK_CONFIDENCE = 0.55

private val LEFT_KNEE_ANGLE_SPEC = PoseScalarFeatureSpec.JointAngle(
    featureContractId = "trex.feature.world.left-hip-knee-ankle-included-angle.v1",
    coordinateSpace = PoseCoordinateSpace.WORLD,
    first = PoseJoint.LEFT_HIP,
    vertex = PoseJoint.LEFT_KNEE,
    third = PoseJoint.LEFT_ANKLE,
)

private val RIGHT_KNEE_ANGLE_SPEC = PoseScalarFeatureSpec.JointAngle(
    featureContractId = "trex.feature.world.right-hip-knee-ankle-included-angle.v1",
    coordinateSpace = PoseCoordinateSpace.WORLD,
    first = PoseJoint.RIGHT_HIP,
    vertex = PoseJoint.RIGHT_KNEE,
    third = PoseJoint.RIGHT_ANKLE,
)

/**
 * Content identity for acquisition of the M4 lateral knee-flexion research candidate.
 *
 * This contract fixes a frame-local scalar and acquisition continuity only. Its hash is a drift
 * receipt, not an authenticity proof or authority to infer exercise state or correctness.
 * [minimumWorldLandmarkConfidence] is only a raw MediaPipe visibility/presence acquisition gate.
 * [maximumObservationGapMs] is only an explicitly supplied transport-continuity gate; it is not a
 * frame-rate claim, biomechanical boundary, decoder parameter, or phase-Gold-derived threshold.
 */
internal class BarbellSquatLateralKneeFlexionSignalContract internal constructor(
    expectedSource: PoseObservationSource,
    val minimumWorldLandmarkConfidence: Double,
    val maximumObservationGapMs: Long,
) {
    val contractId: String = SIGNAL_CONTRACT_ID
    val researchUseId: String = RESEARCH_ONLY_USE_ID
    val researchContractSha256: String = BarbellSquatResearchPhaseContract.CURRENT.artifactSha256
    val researchCandidateId: String = BarbellSquatResearchPhaseContract.LATERAL_CANDIDATE_ID
    val researchCandidateSha256: String = BarbellSquatResearchPhaseContract.CURRENT
        .scalarCandidates
        .single { candidate -> candidate.candidateId == researchCandidateId }
        .contentSha256
    val observationContractSha256: String = expectedSource.contract.artifactSha256
    val observationRuntimeDomainId: String = expectedSource.contract.runtimeDomainId
    val preprocessingArtifactSha256: String =
        expectedSource.contract.preprocessingArtifactSha256
    val personLockArtifactId: String = expectedSource.contract.personLockArtifactId
    val personLockArtifactSha256: String = expectedSource.contract.personLockArtifactSha256
    val viewQualifierArtifactId: String = expectedSource.contract.viewQualifierArtifactId
    val viewQualifierArtifactSha256: String =
        expectedSource.contract.viewQualifierArtifactSha256
    val lateralViewContractId: String = BarbellSquatResearchView.LATERAL.contractId
    val coordinateSpace: PoseCoordinateSpace = PoseCoordinateSpace.WORLD
    val formulaId: String = SIGNAL_FORMULA_ID
    val leftIncludedAngleSpecSha256: String = LEFT_KNEE_ANGLE_SPEC.featureSpecSha256
    val rightIncludedAngleSpecSha256: String = RIGHT_KNEE_ANGLE_SPEC.featureSpecSha256
    val featurePrimitiveContractSha256: String = PoseFeaturePrimitiveContract.sha256
    val featureRuntimeContractSha256: String =
        PoseFeatureEngine(minimumWorldLandmarkConfidence).runtimeContractSha256

    init {
        require(minimumWorldLandmarkConfidence.isFinite())
        require(minimumWorldLandmarkConfidence > 0.0 && minimumWorldLandmarkConfidence <= 1.0) {
            "minimumWorldLandmarkConfidence must be finite and in (0, 1]"
        }
        require(maximumObservationGapMs > 0L) {
            "maximumObservationGapMs must be positive"
        }
        require(PoseCoordinateSpace.WORLD in expectedSource.contract.supportedCoordinateSpaces) {
            "The expected source must attest MediaPipe world landmarks"
        }
        require(lateralViewContractId in expectedSource.contract.allowedViewContractIds) {
            "The expected source must allow the exact full-body lateral view contract"
        }
        val candidate = BarbellSquatResearchPhaseContract.CURRENT.scalarCandidates.single {
            it.candidateId == researchCandidateId
        }
        require(candidate.applicableViews == setOf(BarbellSquatResearchView.LATERAL))
        require(candidate.coordinateDomainId == "trex.coordinate.mediapipe-world-relative.v1")
    }

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "barbellSquatLateralKneeFlexionSignalContractSchemaVersion" to "1",
            "contractId" to contractId,
            "researchUseId" to researchUseId,
            "researchContractSha256" to researchContractSha256,
            "researchCandidateId" to researchCandidateId,
            "researchCandidateSha256" to researchCandidateSha256,
            "observationContractSha256" to observationContractSha256,
            "observationRuntimeDomainId" to observationRuntimeDomainId,
            "preprocessingArtifactSha256" to preprocessingArtifactSha256,
            "personLockArtifactId" to personLockArtifactId,
            "personLockArtifactSha256" to personLockArtifactSha256,
            "viewQualifierArtifactId" to viewQualifierArtifactId,
            "viewQualifierArtifactSha256" to viewQualifierArtifactSha256,
            "lateralViewContractId" to lateralViewContractId,
            "coordinateSpace" to coordinateSpace.name,
            "formulaId" to formulaId,
            "leftIncludedAngleSpecSha256" to leftIncludedAngleSpecSha256,
            "rightIncludedAngleSpecSha256" to rightIncludedAngleSpecSha256,
            "featurePrimitiveContractSha256" to featurePrimitiveContractSha256,
            "featureRuntimeContractSha256" to featureRuntimeContractSha256,
            "minimumWorldLandmarkConfidence" to
                java.lang.Double.toHexString(minimumWorldLandmarkConfidence),
            "maximumObservationGapMs" to maximumObservationGapMs.toString(),
        ),
    )
}

/** Why a side could not produce the raw frame-local research scalar. */
internal enum class LateralKneeFlexionUnavailabilityReason {
    EXPECTED_SOURCE_CLOSED,
    OBSERVATION_SOURCE_MISMATCH,
    PRIMARY_PERSON_LOCK_MISSING,
    LATERAL_VIEW_QUALIFICATION_MISSING,
    CAMERA_GEOMETRY_RECEIPT_MISSING,
    CAMERA_GEOMETRY_PREPROCESSING_MISMATCH,
    REQUIRED_WORLD_JOINT_MISSING,
    RAW_CONFIDENCE_BELOW_MINIMUM,
    DEGENERATE_WORLD_VECTOR,
    NUMERIC_EXTRACTION_ERROR,
}

/** Anatomical-side result; the two sides remain visible even when the bilateral scalar is absent. */
internal sealed interface LateralKneeFlexionSideSample {
    val side: PoseSide

    class Measured internal constructor(
        override val side: PoseSide,
        val flexionDegrees: Double,
        val includedAngleDegrees: Double,
        val minimumObservedRawConfidence: Double,
    ) : LateralKneeFlexionSideSample {
        init {
            require(flexionDegrees.isFinite() && flexionDegrees in 0.0..180.0)
            require(includedAngleDegrees.isFinite() && includedAngleDegrees in 0.0..180.0)
            require(minimumObservedRawConfidence in 0.0..1.0)
        }
    }

    class Unavailable internal constructor(
        override val side: PoseSide,
        reasons: Set<LateralKneeFlexionUnavailabilityReason>,
        missingJoints: Set<PoseJoint> = emptySet(),
        lowConfidenceJoints: Set<PoseJoint> = emptySet(),
    ) : LateralKneeFlexionSideSample {
        val reasons: Set<LateralKneeFlexionUnavailabilityReason> = immutableSet(
            reasons.sortedBy(LateralKneeFlexionUnavailabilityReason::name),
        )
        val missingJoints: Set<PoseJoint> = immutableSet(
            missingJoints.sortedBy(PoseJoint::mediaPipeIndex),
        )
        val lowConfidenceJoints: Set<PoseJoint> = immutableSet(
            lowConfidenceJoints.sortedBy(PoseJoint::mediaPipeIndex),
        )

        init {
            require(this.reasons.isNotEmpty())
            require(
                (LateralKneeFlexionUnavailabilityReason.REQUIRED_WORLD_JOINT_MISSING in reasons) ==
                    this.missingJoints.isNotEmpty(),
            )
            require(
                (LateralKneeFlexionUnavailabilityReason.RAW_CONFIDENCE_BELOW_MINIMUM in reasons) ==
                    this.lowConfidenceJoints.isNotEmpty(),
            )
        }
    }
}

/** Immutable no-verdict output for one attested frame. */
internal class BarbellSquatLateralKneeFlexionSignalMeasurement internal constructor(
    val contractSha256: String,
    val frameTimestampMs: Long,
    sideSamples: Map<PoseSide, LateralKneeFlexionSideSample>,
    val cameraGeometryContextSha256: String?,
    val bilateralMedianDegrees: Double?,
    val sampleProvenanceSha256: String?,
) {
    val sideSamples: Map<PoseSide, LateralKneeFlexionSideSample> =
        Collections.unmodifiableMap(LinkedHashMap(sideSamples))

    init {
        require(frameTimestampMs >= 0L)
        require(this.sideSamples.keys == setOf(PoseSide.LEFT, PoseSide.RIGHT))
        require(this.sideSamples.all { (side, sample) -> side == sample.side })
        val bothMeasured = this.sideSamples.values.all {
            it is LateralKneeFlexionSideSample.Measured
        }
        require((bilateralMedianDegrees != null) == bothMeasured)
        require(bilateralMedianDegrees == null || bilateralMedianDegrees in 0.0..180.0)
        require((sampleProvenanceSha256 != null) == bothMeasured)
        require(sampleProvenanceSha256 == null || cameraGeometryContextSha256 != null)
    }
}

/**
 * Extracts the exact M4 lateral candidate from WORLD landmarks.
 *
 * It performs no temporal decoding. Flexion is `180 - includedAngle(hip, knee, ankle)` and the
 * bilateral even median is the arithmetic mean, only when both anatomical sides are measurable.
 */
internal class BarbellSquatLateralKneeFlexionSignalExtractor(
    internal val expectedSource: PoseObservationSource,
    maximumObservationGapMs: Long,
    minimumWorldLandmarkConfidence: Double = DEFAULT_MINIMUM_WORLD_LANDMARK_CONFIDENCE,
) {
    val contract = BarbellSquatLateralKneeFlexionSignalContract(
        expectedSource = expectedSource,
        minimumWorldLandmarkConfidence = minimumWorldLandmarkConfidence,
        maximumObservationGapMs = maximumObservationGapMs,
    )
    private val featureEngine = PoseFeatureEngine(minimumWorldLandmarkConfidence)

    fun measure(
        observation: AttestedPoseObservation,
    ): BarbellSquatLateralKneeFlexionSignalMeasurement {
        val globalReason = when {
            !expectedSource.isOpen ->
                LateralKneeFlexionUnavailabilityReason.EXPECTED_SOURCE_CLOSED
            !observation.isFrom(expectedSource) ->
                LateralKneeFlexionUnavailabilityReason.OBSERVATION_SOURCE_MISMATCH
            !observation.hasPrimaryPersonLock ->
                LateralKneeFlexionUnavailabilityReason.PRIMARY_PERSON_LOCK_MISSING
            observation.viewQualification(contract.lateralViewContractId) == null ->
                LateralKneeFlexionUnavailabilityReason.LATERAL_VIEW_QUALIFICATION_MISSING
            observation.cameraGeometryReceipt == null ->
                LateralKneeFlexionUnavailabilityReason.CAMERA_GEOMETRY_RECEIPT_MISSING
            observation.cameraGeometryReceipt.epoch.context.preprocessingArtifactSha256 !=
                contract.preprocessingArtifactSha256 ->
                LateralKneeFlexionUnavailabilityReason.CAMERA_GEOMETRY_PREPROCESSING_MISMATCH
            else -> null
        }
        val geometrySha256 = observation.cameraGeometryReceipt
            ?.takeIf { observation.isFrom(expectedSource) }
            ?.contextArtifactSha256
        if (globalReason != null) {
            return unavailableMeasurement(observation, globalReason, geometrySha256)
        }

        val samples = linkedMapOf(
            PoseSide.LEFT to measureSide(observation, PoseSide.LEFT, LEFT_KNEE_ANGLE_SPEC),
            PoseSide.RIGHT to measureSide(observation, PoseSide.RIGHT, RIGHT_KNEE_ANGLE_SPEC),
        )
        val measured = samples.values.filterIsInstance<LateralKneeFlexionSideSample.Measured>()
        val median = if (measured.size == 2) {
            measured[0].flexionDegrees / 2.0 + measured[1].flexionDegrees / 2.0
        } else {
            null
        }
        val provenance = median?.let {
            canonicalFieldsSha256(
                buildList {
                    add("barbellSquatLateralKneeFlexionSampleProvenanceSchemaVersion" to "1")
                    add("contractSha256" to contract.artifactSha256)
                    add("cameraGeometryContextSha256" to requireNotNull(geometrySha256))
                    add("frameTimestampMs" to observation.frame.timestampMs.toString())
                    PoseSide.entries.forEach { side ->
                        val sample = samples.getValue(side) as LateralKneeFlexionSideSample.Measured
                        add("${side.name}.includedAngleDegrees" to
                            java.lang.Double.toHexString(sample.includedAngleDegrees))
                        add("${side.name}.flexionDegrees" to
                            java.lang.Double.toHexString(sample.flexionDegrees))
                        add("${side.name}.minimumObservedRawConfidence" to
                            java.lang.Double.toHexString(sample.minimumObservedRawConfidence))
                    }
                    add("bilateralMedianDegrees" to java.lang.Double.toHexString(it))
                },
            )
        }
        return BarbellSquatLateralKneeFlexionSignalMeasurement(
            contractSha256 = contract.artifactSha256,
            frameTimestampMs = observation.frame.timestampMs,
            sideSamples = samples,
            cameraGeometryContextSha256 = geometrySha256,
            bilateralMedianDegrees = median,
            sampleProvenanceSha256 = provenance,
        )
    }

    private fun measureSide(
        observation: AttestedPoseObservation,
        side: PoseSide,
        spec: PoseScalarFeatureSpec.JointAngle,
    ): LateralKneeFlexionSideSample {
        val worldLandmarks = observation.frame.worldLandmarks
        val missing = spec.requiredJoints.filterTo(linkedSetOf()) { it !in worldLandmarks }
        if (missing.isNotEmpty()) {
            return LateralKneeFlexionSideSample.Unavailable(
                side = side,
                reasons = setOf(
                    LateralKneeFlexionUnavailabilityReason.REQUIRED_WORLD_JOINT_MISSING,
                ),
                missingJoints = missing,
            )
        }
        val lowConfidence = spec.requiredJoints.filterTo(linkedSetOf()) { joint ->
            worldLandmarks.getValue(joint).confidence < contract.minimumWorldLandmarkConfidence
        }
        if (lowConfidence.isNotEmpty()) {
            return LateralKneeFlexionSideSample.Unavailable(
                side = side,
                reasons = setOf(
                    LateralKneeFlexionUnavailabilityReason.RAW_CONFIDENCE_BELOW_MINIMUM,
                ),
                lowConfidenceJoints = lowConfidence,
            )
        }
        return featureEngine.measure(observation.frame, spec).toSideSample(side)
    }

    private fun unavailableMeasurement(
        observation: AttestedPoseObservation,
        reason: LateralKneeFlexionUnavailabilityReason,
        geometrySha256: String?,
    ) = BarbellSquatLateralKneeFlexionSignalMeasurement(
        contractSha256 = contract.artifactSha256,
        frameTimestampMs = observation.frame.timestampMs,
        sideSamples = PoseSide.entries.associateWithTo(LinkedHashMap()) { side ->
            LateralKneeFlexionSideSample.Unavailable(side, setOf(reason))
        },
        cameraGeometryContextSha256 = geometrySha256,
        bilateralMedianDegrees = null,
        sampleProvenanceSha256 = null,
    )
}

/** Causes that break a contiguous acquisition segment; none is an exercise-form decision. */
internal enum class BarbellSquatResearchSignalResetCause {
    OBSERVATION_SOURCE_DISCONTINUITY,
    EXPECTED_SOURCE_CLOSED,
    PERSON_TRACK_EPOCH_DISCONTINUITY,
    LATERAL_VIEW_UNQUALIFIED,
    CAMERA_GEOMETRY_UNATTESTED,
    CAMERA_GEOMETRY_EPOCH_DISCONTINUITY,
    CROP_RECT_DISCONTINUITY,
    IMAGE_DIMENSION_DISCONTINUITY,
    ROTATION_DISCONTINUITY,
    MIRROR_METADATA_DISCONTINUITY,
    PREPROCESSING_ARTIFACT_DISCONTINUITY,
    TIMESTAMP_NOT_STRICTLY_INCREASING,
    OBSERVATION_GAP_EXCEEDED,
    SIGNAL_UNAVAILABLE,
}

/** One update from [BarbellSquatResearchSignalContinuityStream]. */
internal class BarbellSquatResearchSignalStreamUpdate internal constructor(
    val frameTimestampMs: Long,
    val measurement: BarbellSquatLateralKneeFlexionSignalMeasurement,
    val continuitySegmentId: Long?,
    resetCauses: Set<BarbellSquatResearchSignalResetCause>,
) {
    val resetCauses: Set<BarbellSquatResearchSignalResetCause> = immutableSet(
        resetCauses.sortedBy(BarbellSquatResearchSignalResetCause::name),
    )

    /** The frame belongs to a continuity segment only when this is true. */
    val acceptedForContinuity: Boolean
        get() = continuitySegmentId != null
}

/**
 * Strict, bounded-memory continuity wrapper for raw research signal acquisition.
 *
 * It retains only opaque continuity identities, geometry metadata and a timestamp high-water mark;
 * it never buffers scalar history or interprets the sequence. Every update carries the diagnostic
 * frame-local measurement, but only [BarbellSquatResearchSignalStreamUpdate.acceptedForContinuity]
 * means that measurement entered a continuity segment. Replayed or regressing timestamps are
 * returned with a null segment id and therefore cannot be interpreted as accepted.
 */
internal class BarbellSquatResearchSignalContinuityStream(
    private val extractor: BarbellSquatLateralKneeFlexionSignalExtractor,
) : AutoCloseable {
    private var activePersonEpoch: PosePersonTrackEpoch? = null
    private var activeGeometryEpoch: PoseCameraGeometryEpoch? = null
    private var activeGeometryContext: PoseCameraGeometryContext? = null
    private var lastAcceptedTimestampMs: Long? = null
    private var timestampHighWaterMs: Long? = null
    private var activeSegmentId: Long? = null
    private var nextSegmentId = 1L
    private var closed = false

    fun accept(observation: AttestedPoseObservation): BarbellSquatResearchSignalStreamUpdate {
        check(!closed) { "Research signal continuity stream is closed" }
        val measurement = extractor.measure(observation)
        val causes = linkedSetOf<BarbellSquatResearchSignalResetCause>()

        if (!extractor.expectedSource.isOpen) {
            causes += BarbellSquatResearchSignalResetCause.EXPECTED_SOURCE_CLOSED
            clearActive()
            return update(measurement, null, causes)
        }
        if (!observation.isFrom(extractor.expectedSource)) {
            causes += BarbellSquatResearchSignalResetCause.OBSERVATION_SOURCE_DISCONTINUITY
            clearActive()
            return update(measurement, null, causes)
        }

        val timestamp = observation.frame.timestampMs
        val highWater = timestampHighWaterMs
        if (highWater != null && timestamp <= highWater) {
            causes += BarbellSquatResearchSignalResetCause.TIMESTAMP_NOT_STRICTLY_INCREASING
            clearActive()
            return update(measurement, null, causes)
        }
        timestampHighWaterMs = timestamp

        val personEpoch = observation.personTrackEpoch
        if (personEpoch == null) {
            causes += BarbellSquatResearchSignalResetCause.PERSON_TRACK_EPOCH_DISCONTINUITY
            clearActive()
            return update(measurement, null, causes)
        }
        val previousPerson = activePersonEpoch
        if (previousPerson != null && personEpoch !== previousPerson) {
            causes += BarbellSquatResearchSignalResetCause.PERSON_TRACK_EPOCH_DISCONTINUITY
        }
        if (observation.viewQualification(extractor.contract.lateralViewContractId) == null) {
            causes += BarbellSquatResearchSignalResetCause.LATERAL_VIEW_UNQUALIFIED
            clearActive()
            return update(measurement, null, causes)
        }

        val receipt = observation.cameraGeometryReceipt
        if (receipt == null) {
            causes += BarbellSquatResearchSignalResetCause.CAMERA_GEOMETRY_UNATTESTED
            clearActive()
            return update(measurement, null, causes)
        }
        val geometryEpoch = receipt.epoch
        val context = geometryEpoch.context
        val previousGeometryEpoch = activeGeometryEpoch
        val previousContext = activeGeometryContext
        if (previousGeometryEpoch != null && geometryEpoch !== previousGeometryEpoch) {
            causes += geometryDriftCauses(requireNotNull(previousContext), context)
        }

        val previousTimestamp = lastAcceptedTimestampMs
        if (
            previousTimestamp != null &&
            timestamp - previousTimestamp > extractor.contract.maximumObservationGapMs
        ) {
            causes += BarbellSquatResearchSignalResetCause.OBSERVATION_GAP_EXCEEDED
        }
        if (measurement.bilateralMedianDegrees == null) {
            causes += BarbellSquatResearchSignalResetCause.SIGNAL_UNAVAILABLE
            clearActive()
            return update(measurement, null, causes)
        }

        if (causes.isNotEmpty()) clearActive()
        if (activeSegmentId == null) activeSegmentId = nextSegmentId++
        activePersonEpoch = personEpoch
        activeGeometryEpoch = geometryEpoch
        activeGeometryContext = context
        lastAcceptedTimestampMs = timestamp
        return update(measurement, activeSegmentId, causes)
    }

    override fun close() {
        clearActive()
        timestampHighWaterMs = null
        closed = true
    }

    private fun clearActive() {
        activePersonEpoch = null
        activeGeometryEpoch = null
        activeGeometryContext = null
        lastAcceptedTimestampMs = null
        activeSegmentId = null
    }

    private fun update(
        measurement: BarbellSquatLateralKneeFlexionSignalMeasurement,
        segmentId: Long?,
        causes: Set<BarbellSquatResearchSignalResetCause>,
    ) = BarbellSquatResearchSignalStreamUpdate(
        frameTimestampMs = measurement.frameTimestampMs,
        measurement = measurement,
        continuitySegmentId = segmentId,
        resetCauses = causes,
    )

    private fun geometryDriftCauses(
        previous: PoseCameraGeometryContext,
        current: PoseCameraGeometryContext,
    ): Set<BarbellSquatResearchSignalResetCause> = buildSet {
        if (
            previous.cropLeft != current.cropLeft ||
            previous.cropTop != current.cropTop ||
            previous.cropRightExclusive != current.cropRightExclusive ||
            previous.cropBottomExclusive != current.cropBottomExclusive
        ) add(BarbellSquatResearchSignalResetCause.CROP_RECT_DISCONTINUITY)
        if (
            previous.sourceImageWidth != current.sourceImageWidth ||
            previous.sourceImageHeight != current.sourceImageHeight ||
            previous.outputImageWidth != current.outputImageWidth ||
            previous.outputImageHeight != current.outputImageHeight
        ) add(BarbellSquatResearchSignalResetCause.IMAGE_DIMENSION_DISCONTINUITY)
        if (previous.inputRotationDegrees != current.inputRotationDegrees) {
            add(BarbellSquatResearchSignalResetCause.ROTATION_DISCONTINUITY)
        }
        if (
            previous.inferencePixelsMirrored != current.inferencePixelsMirrored ||
            previous.displayMirrored != current.displayMirrored
        ) add(BarbellSquatResearchSignalResetCause.MIRROR_METADATA_DISCONTINUITY)
        if (previous.preprocessingArtifactSha256 != current.preprocessingArtifactSha256) {
            add(BarbellSquatResearchSignalResetCause.PREPROCESSING_ARTIFACT_DISCONTINUITY)
        }
        if (isEmpty()) {
            add(BarbellSquatResearchSignalResetCause.CAMERA_GEOMETRY_EPOCH_DISCONTINUITY)
        }
    }
}

private fun FeatureMeasurement.toSideSample(side: PoseSide): LateralKneeFlexionSideSample {
    val includedAngle = value
    if (includedAngle != null) {
        val flexion = 180.0 - includedAngle
        if (flexion.isFinite() && flexion in 0.0..180.0) {
            return LateralKneeFlexionSideSample.Measured(
                side = side,
                flexionDegrees = flexion,
                includedAngleDegrees = includedAngle,
                minimumObservedRawConfidence = rawConfidence,
            )
        }
    }
    val reason = when (unknownReason) {
        FeatureUnknownReason.DEGENERATE_VECTOR ->
            LateralKneeFlexionUnavailabilityReason.DEGENERATE_WORLD_VECTOR
        else -> LateralKneeFlexionUnavailabilityReason.NUMERIC_EXTRACTION_ERROR
    }
    return LateralKneeFlexionSideSample.Unavailable(side, setOf(reason))
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
