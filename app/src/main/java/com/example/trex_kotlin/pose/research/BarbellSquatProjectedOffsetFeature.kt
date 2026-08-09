package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import java.util.Collections
import kotlin.math.abs
import kotlin.math.hypot

private val RESEARCH_VIEW_IDENTIFIER = Regex("^[a-z0-9][a-z0-9._:/-]*$")
private const val CONSTRUCT_ID = "projected-knee-to-foot-index-lateral-offset"
private const val CONTRACT_SCHEMA_VERSION = 1
private const val DEGENERACY_EPSILON = 1e-9
private const val ASPECT_POLICY_ID =
    "normalized-image-x-times-width-over-height.y-unchanged.z-ignored.v1"
private const val ALGORITHM_ID =
    "abs(dot(knee-ankle,shoulder-unit)-dot(foot-index-ankle,shoulder-unit))" +
        "-over-shoulder-width.v1"
private const val SIDE_SEMANTICS_ID = "left-right-independent-no-average.v1"
private const val VIEW_POLICY_ID = "barbell-squat-knee-foot-front-or-front-oblique.v1"
private val POLICY_ALLOWED_VIEW_CONTRACT_IDS = setOf(
    "trex.view.front-full-body.v1",
    "trex.view.front-oblique-full-body.v1",
)

/**
 * Contract for one raw, bilateral research measurement.
 *
 * The hash is a drift detector, not an authenticity signature. It binds the exact observation
 * contract, accepted view contracts, confidence gate, joint mapping, aspect correction, geometry,
 * and the rule that LEFT and RIGHT remain independent.
 */
internal class BarbellSquatProjectedOffsetContract(
    val observationContractSha256: String,
    allowedViewContractIds: Set<String>,
    val minimumRawConfidence: Double,
) {
    val constructId: String = CONSTRUCT_ID
    val allowedViewContractIds: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(allowedViewContractIds.sorted()),
    )

    init {
        require(observationContractSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "observationContractSha256 must be a lowercase SHA-256"
        }
        require(this.allowedViewContractIds.isNotEmpty()) {
            "allowedViewContractIds must not be empty"
        }
        this.allowedViewContractIds.forEach { viewContractId ->
            require(RESEARCH_VIEW_IDENTIFIER.matches(viewContractId)) {
                "allowed view contract IDs must be lowercase, versioned identifiers"
            }
        }
        require(this.allowedViewContractIds.all { it in POLICY_ALLOWED_VIEW_CONTRACT_IDS }) {
            "allowedViewContractIds must be a subset of the barbell-squat knee/foot view policy"
        }
        require(minimumRawConfidence.isFinite() && minimumRawConfidence in 0.0..1.0) {
            "minimumRawConfidence must be finite and in [0, 1]"
        }
    }

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("projectedOffsetContractSchemaVersion" to CONTRACT_SCHEMA_VERSION.toString())
            add("constructId" to constructId)
            add("observationContractSha256" to observationContractSha256)
            add("coordinateSpace" to PoseCoordinateSpace.NORMALIZED_IMAGE.name)
            add("aspectPolicyId" to ASPECT_POLICY_ID)
            add("algorithmId" to ALGORITHM_ID)
            add("degeneracyEpsilon" to java.lang.Double.toHexString(DEGENERACY_EPSILON))
            add("minimumRawConfidence" to java.lang.Double.toHexString(minimumRawConfidence))
            add("sideSemanticsId" to SIDE_SEMANTICS_ID)
            add("viewPolicyId" to VIEW_POLICY_ID)
            add("policyAllowedViewContractIdCount" to
                POLICY_ALLOWED_VIEW_CONTRACT_IDS.size.toString())
            POLICY_ALLOWED_VIEW_CONTRACT_IDS.sorted().forEachIndexed { index, viewContractId ->
                add("policyAllowedViewContractId[$index]" to viewContractId)
            }
            add("shoulderAxisFrom" to PoseJoint.LEFT_SHOULDER.name)
            add("shoulderAxisTo" to PoseJoint.RIGHT_SHOULDER.name)
            add("leftKneeJoint" to PoseJoint.LEFT_KNEE.name)
            add("leftAnkleJoint" to PoseJoint.LEFT_ANKLE.name)
            add("leftFootIndexJoint" to PoseJoint.LEFT_FOOT_INDEX.name)
            add("rightKneeJoint" to PoseJoint.RIGHT_KNEE.name)
            add("rightAnkleJoint" to PoseJoint.RIGHT_ANKLE.name)
            add("rightFootIndexJoint" to PoseJoint.RIGHT_FOOT_INDEX.name)
            add("allowedViewContractIdCount" to this@BarbellSquatProjectedOffsetContract
                .allowedViewContractIds.size.toString())
            this@BarbellSquatProjectedOffsetContract.allowedViewContractIds
                .forEachIndexed { index, viewContractId ->
                    add("allowedViewContractId[$index]" to viewContractId)
                }
        },
    )
}

/** Reasons why one side did not yield a raw research measurement. */
internal enum class ProjectedOffsetAbstentionReason {
    OBSERVATION_SOURCE_MISMATCH,
    PRIMARY_PERSON_LOCK_MISSING,
    ALLOWED_VIEW_QUALIFICATION_MISSING,
    REQUIRED_JOINT_MISSING,
    RAW_CONFIDENCE_BELOW_MINIMUM,
    IMAGE_DIMENSIONS_UNAVAILABLE,
    DEGENERATE_SHOULDER_AXIS,
    NON_FINITE_NUMERIC,
}

/** One side of the raw bilateral sample; it intentionally has no aggregate representation. */
internal sealed interface ProjectedOffsetSideSample {
    val side: PoseSide

    class Measured internal constructor(
        override val side: PoseSide,
        val normalizedOffset: Double,
        val minimumObservedRawConfidence: Double,
    ) : ProjectedOffsetSideSample

    class Abstained internal constructor(
        override val side: PoseSide,
        reasons: Set<ProjectedOffsetAbstentionReason>,
        missingJoints: Set<PoseJoint> = emptySet(),
        lowConfidenceJoints: Set<PoseJoint> = emptySet(),
    ) : ProjectedOffsetSideSample {
        val reasons: Set<ProjectedOffsetAbstentionReason> = Collections.unmodifiableSet(
            LinkedHashSet(reasons.sortedBy(ProjectedOffsetAbstentionReason::name)),
        )
        val missingJoints: Set<PoseJoint> = Collections.unmodifiableSet(
            LinkedHashSet(missingJoints.sortedBy(PoseJoint::mediaPipeIndex)),
        )
        val lowConfidenceJoints: Set<PoseJoint> = Collections.unmodifiableSet(
            LinkedHashSet(lowConfidenceJoints.sortedBy(PoseJoint::mediaPipeIndex)),
        )

        init {
            require(this.reasons.isNotEmpty()) { "An abstention must state at least one reason" }
            require(
                (ProjectedOffsetAbstentionReason.REQUIRED_JOINT_MISSING in this.reasons) ==
                    this.missingJoints.isNotEmpty(),
            ) { "Missing-joint details must match the abstention reasons" }
            require(
                (ProjectedOffsetAbstentionReason.RAW_CONFIDENCE_BELOW_MINIMUM in this.reasons) ==
                    this.lowConfidenceJoints.isNotEmpty(),
            ) { "Low-confidence details must match the abstention reasons" }
        }
    }
}

/**
 * Immutable, no-verdict output from [BarbellSquatProjectedOffsetFeature].
 *
 * The map always has separate LEFT and RIGHT entries and deliberately exposes no mean, maximum,
 * score, cue, or form classification.
 */
internal class BarbellSquatProjectedOffsetMeasurement internal constructor(
    val constructId: String,
    val contractSha256: String,
    val frameTimestampMs: Long,
    sideSamples: Map<PoseSide, ProjectedOffsetSideSample>,
) {
    val sideSamples: Map<PoseSide, ProjectedOffsetSideSample> = Collections.unmodifiableMap(
        LinkedHashMap(sideSamples),
    )

    init {
        require(constructId == CONSTRUCT_ID)
        require(contractSha256.matches(Regex("^[0-9a-f]{64}$")))
        require(frameTimestampMs >= 0L)
        require(this.sideSamples.keys == setOf(PoseSide.LEFT, PoseSide.RIGHT)) {
            "A bilateral measurement must contain exactly LEFT and RIGHT"
        }
        require(this.sideSamples.all { (side, sample) -> sample.side == side })
    }
}

/**
 * Computes the raw `projected-knee-to-foot-index-lateral-offset` research construct.
 *
 * Normalized-image x coordinates are multiplied by image width / image height before geometry is
 * calculated. For each side independently, the knee-from-ankle and foot-index-from-ankle vectors
 * are projected onto the LEFT_SHOULDER-to-RIGHT_SHOULDER unit vector; their absolute difference is
 * divided by shoulder width. The two sides are never averaged.
 *
 * This construct is not a valgus angle or a form verdict. It does not establish knee/foot
 * direction correctness, external load, injury safety, or AI Hub ground truth. It has no runtime or
 * product caller and must remain research-only until separately validated and authorized.
 */
internal class BarbellSquatProjectedOffsetFeature(
    private val expectedSource: PoseObservationSource,
    allowedViewContractIds: Set<String>,
    minimumRawConfidence: Double,
) {
    val contract: BarbellSquatProjectedOffsetContract =
        BarbellSquatProjectedOffsetContract(
            observationContractSha256 = expectedSource.contract.artifactSha256,
            allowedViewContractIds = allowedViewContractIds,
            minimumRawConfidence = minimumRawConfidence,
        )

    init {
        require(PoseCoordinateSpace.NORMALIZED_IMAGE in expectedSource.contract.supportedCoordinateSpaces) {
            "The expected observation source must support normalized-image coordinates"
        }
        require(contract.allowedViewContractIds.all {
            it in expectedSource.contract.allowedViewContractIds
        }) {
            "Every feature view contract must be allowed by the observation source"
        }
    }

    fun measure(observation: AttestedPoseObservation): BarbellSquatProjectedOffsetMeasurement {
        val globalReason = when {
            !observation.isFrom(expectedSource) ->
                ProjectedOffsetAbstentionReason.OBSERVATION_SOURCE_MISMATCH
            !observation.hasPrimaryPersonLock ->
                ProjectedOffsetAbstentionReason.PRIMARY_PERSON_LOCK_MISSING
            contract.allowedViewContractIds.none(observation::isViewQualified) ->
                ProjectedOffsetAbstentionReason.ALLOWED_VIEW_QUALIFICATION_MISSING
            observation.frame.imageWidth <= 0 || observation.frame.imageHeight <= 0 ->
                ProjectedOffsetAbstentionReason.IMAGE_DIMENSIONS_UNAVAILABLE
            else -> null
        }
        if (globalReason != null) {
            return abstainedMeasurement(observation, setOf(globalReason))
        }

        val aspect = observation.frame.imageWidth.toDouble() / observation.frame.imageHeight
        if (!aspect.isFinite() || aspect <= 0.0) {
            return abstainedMeasurement(
                observation,
                setOf(ProjectedOffsetAbstentionReason.NON_FINITE_NUMERIC),
            )
        }
        val landmarks = observation.frame.landmarks
        val shoulderJoints = setOf(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER)
        val missingShoulders = shoulderJoints.filterTo(linkedSetOf()) { it !in landmarks }
        if (missingShoulders.isNotEmpty()) {
            return abstainedMeasurement(
                observation = observation,
                reasons = setOf(ProjectedOffsetAbstentionReason.REQUIRED_JOINT_MISSING),
                missingJoints = missingShoulders,
            )
        }
        val lowConfidenceShoulders = shoulderJoints.filterTo(linkedSetOf()) { joint ->
            landmarks.getValue(joint).confidence < contract.minimumRawConfidence
        }
        if (lowConfidenceShoulders.isNotEmpty()) {
            return abstainedMeasurement(
                observation = observation,
                reasons = setOf(ProjectedOffsetAbstentionReason.RAW_CONFIDENCE_BELOW_MINIMUM),
                lowConfidenceJoints = lowConfidenceShoulders,
            )
        }

        val leftShoulder = landmarks.getValue(PoseJoint.LEFT_SHOULDER).aspectCorrected(aspect)
        val rightShoulder = landmarks.getValue(PoseJoint.RIGHT_SHOULDER).aspectCorrected(aspect)
        val shoulderDx = rightShoulder.x - leftShoulder.x
        val shoulderDy = rightShoulder.y - leftShoulder.y
        val shoulderWidth = hypot(shoulderDx, shoulderDy)
        if (!shoulderWidth.isFinite()) {
            return abstainedMeasurement(
                observation,
                setOf(ProjectedOffsetAbstentionReason.NON_FINITE_NUMERIC),
            )
        }
        if (shoulderWidth <= DEGENERACY_EPSILON) {
            return abstainedMeasurement(
                observation,
                setOf(ProjectedOffsetAbstentionReason.DEGENERATE_SHOULDER_AXIS),
            )
        }
        val lateralUnit = Point2(shoulderDx / shoulderWidth, shoulderDy / shoulderWidth)

        val samples = linkedMapOf(
            PoseSide.LEFT to measureSide(
                side = PoseSide.LEFT,
                kneeJoint = PoseJoint.LEFT_KNEE,
                ankleJoint = PoseJoint.LEFT_ANKLE,
                footIndexJoint = PoseJoint.LEFT_FOOT_INDEX,
                landmarks = landmarks,
                aspect = aspect,
                lateralUnit = lateralUnit,
                shoulderWidth = shoulderWidth,
            ),
            PoseSide.RIGHT to measureSide(
                side = PoseSide.RIGHT,
                kneeJoint = PoseJoint.RIGHT_KNEE,
                ankleJoint = PoseJoint.RIGHT_ANKLE,
                footIndexJoint = PoseJoint.RIGHT_FOOT_INDEX,
                landmarks = landmarks,
                aspect = aspect,
                lateralUnit = lateralUnit,
                shoulderWidth = shoulderWidth,
            ),
        )
        return BarbellSquatProjectedOffsetMeasurement(
            constructId = contract.constructId,
            contractSha256 = contract.artifactSha256,
            frameTimestampMs = observation.frame.timestampMs,
            sideSamples = samples,
        )
    }

    private fun measureSide(
        side: PoseSide,
        kneeJoint: PoseJoint,
        ankleJoint: PoseJoint,
        footIndexJoint: PoseJoint,
        landmarks: Map<PoseJoint, PoseLandmark>,
        aspect: Double,
        lateralUnit: Point2,
        shoulderWidth: Double,
    ): ProjectedOffsetSideSample {
        val sideJoints = setOf(kneeJoint, ankleJoint, footIndexJoint)
        val missingJoints = sideJoints.filterTo(linkedSetOf()) { it !in landmarks }
        if (missingJoints.isNotEmpty()) {
            return ProjectedOffsetSideSample.Abstained(
                side = side,
                reasons = setOf(ProjectedOffsetAbstentionReason.REQUIRED_JOINT_MISSING),
                missingJoints = missingJoints,
            )
        }
        val rawConfidenceJoints = shoulderJoints() + sideJoints
        val lowConfidenceJoints = rawConfidenceJoints.filterTo(linkedSetOf()) { joint ->
            landmarks.getValue(joint).confidence < contract.minimumRawConfidence
        }
        if (lowConfidenceJoints.isNotEmpty()) {
            return ProjectedOffsetSideSample.Abstained(
                side = side,
                reasons = setOf(ProjectedOffsetAbstentionReason.RAW_CONFIDENCE_BELOW_MINIMUM),
                lowConfidenceJoints = lowConfidenceJoints,
            )
        }

        val knee = landmarks.getValue(kneeJoint).aspectCorrected(aspect)
        val ankle = landmarks.getValue(ankleJoint).aspectCorrected(aspect)
        val footIndex = landmarks.getValue(footIndexJoint).aspectCorrected(aspect)
        val kneeProjection = (knee - ankle).dot(lateralUnit)
        val footProjection = (footIndex - ankle).dot(lateralUnit)
        val normalizedOffset = abs(kneeProjection - footProjection) / shoulderWidth
        val minimumObservedRawConfidence = rawConfidenceJoints
            .minOf { joint -> landmarks.getValue(joint).confidence }
        if (!normalizedOffset.isFinite() || !minimumObservedRawConfidence.isFinite()) {
            return ProjectedOffsetSideSample.Abstained(
                side = side,
                reasons = setOf(ProjectedOffsetAbstentionReason.NON_FINITE_NUMERIC),
            )
        }
        return ProjectedOffsetSideSample.Measured(
            side = side,
            normalizedOffset = normalizedOffset,
            minimumObservedRawConfidence = minimumObservedRawConfidence,
        )
    }

    private fun abstainedMeasurement(
        observation: AttestedPoseObservation,
        reasons: Set<ProjectedOffsetAbstentionReason>,
        missingJoints: Set<PoseJoint> = emptySet(),
        lowConfidenceJoints: Set<PoseJoint> = emptySet(),
    ): BarbellSquatProjectedOffsetMeasurement = BarbellSquatProjectedOffsetMeasurement(
        constructId = contract.constructId,
        contractSha256 = contract.artifactSha256,
        frameTimestampMs = observation.frame.timestampMs,
        sideSamples = PoseSide.entries.associateWithTo(LinkedHashMap()) { side ->
            ProjectedOffsetSideSample.Abstained(
                side = side,
                reasons = reasons,
                missingJoints = missingJoints,
                lowConfidenceJoints = lowConfidenceJoints,
            )
        },
    )
}

private fun shoulderJoints(): Set<PoseJoint> = setOf(
    PoseJoint.LEFT_SHOULDER,
    PoseJoint.RIGHT_SHOULDER,
)

private data class Point2(val x: Double, val y: Double) {
    operator fun minus(other: Point2): Point2 = Point2(x - other.x, y - other.y)
    fun dot(other: Point2): Double = x * other.x + y * other.y
}

private fun PoseLandmark.aspectCorrected(aspect: Double): Point2 = Point2(x * aspect, y)
