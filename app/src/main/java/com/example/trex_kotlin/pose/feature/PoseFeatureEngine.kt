package com.example.trex_kotlin.pose.feature

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import java.util.Collections
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Finite, immutable vector used after a coordinate domain has been selected explicitly. */
data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Vector coordinates must be finite"
        }
    }

    operator fun minus(other: Vector3): Vector3 = Vector3(
        x = x - other.x,
        y = y - other.y,
        z = z - other.z,
    )

    infix fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

    infix fun cross(other: Vector3): Vector3 = Vector3(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x,
    )

    val length: Double
        get() = hypot(hypot(x, y), z)
}

enum class FeatureUnit {
    DEGREES,
    BODY_SCALE_RATIO,
}

enum class FeatureUnknownReason {
    MISSING_JOINT,
    LOW_CONFIDENCE,
    INVALID_IMAGE_DIMENSIONS,
    DEGENERATE_VECTOR,
    DEGENERATE_SCALE,
    DEGENERATE_REFERENCE,
    INCOMPATIBLE_MEASUREMENTS,
    INSUFFICIENT_SAMPLES,
    NUMERIC_ERROR,
}

/**
 * Result of one coordinate-derived scalar feature.
 *
 * A measurement is either known ([value] is finite and [unknownReason] is null) or explicitly
 * unknown. [rawConfidence] is the minimum raw MediaPipe visibility/presence value of the required
 * joints. It is not a calibrated probability and must not be passed to
 * `CriterionEvidenceSample.qualityWeight` without a versioned quality-calibration adapter.
 * The coordinate space and unit are retained so temporal/composite primitives cannot silently mix
 * normalized-image, world, angle, and distance domains.
 */
class FeatureMeasurement private constructor(
    val value: Double?,
    val rawConfidence: Double,
    val unknownReason: FeatureUnknownReason?,
    val coordinateSpace: PoseCoordinateSpace,
    val unit: FeatureUnit,
    requiredJoints: Set<PoseJoint>,
) {
    val requiredJoints: Set<PoseJoint> =
        Collections.unmodifiableSet(LinkedHashSet(requiredJoints))

    val isKnown: Boolean
        get() = value != null

    init {
        require(rawConfidence.isFinite() && rawConfidence in 0.0..1.0) {
            "Feature raw confidence must be finite and in [0, 1]"
        }
        require((value == null) == (unknownReason != null)) {
            "A feature must have either a finite value or an unknown reason"
        }
        require(value == null || value.isFinite()) { "Feature value must be finite" }
        require(value == null || this.requiredJoints.isNotEmpty()) {
            "A known pose feature must retain its required joints"
        }
    }

    companion object {
        internal fun known(
            value: Double,
            rawConfidence: Double,
            coordinateSpace: PoseCoordinateSpace,
            unit: FeatureUnit,
            requiredJoints: Set<PoseJoint>,
        ): FeatureMeasurement = FeatureMeasurement(
            value = value,
            rawConfidence = rawConfidence,
            unknownReason = null,
            coordinateSpace = coordinateSpace,
            unit = unit,
            requiredJoints = requiredJoints,
        )

        internal fun unknown(
            reason: FeatureUnknownReason,
            rawConfidence: Double,
            coordinateSpace: PoseCoordinateSpace,
            unit: FeatureUnit,
            requiredJoints: Set<PoseJoint>,
        ): FeatureMeasurement = FeatureMeasurement(
            value = null,
            rawConfidence = rawConfidence,
            unknownReason = reason,
            coordinateSpace = coordinateSpace,
            unit = unit,
            requiredJoints = requiredJoints,
        )
    }
}

enum class OrientationReferenceKind {
    GRAVITY,
    BODY_FRAME,
}

/**
 * A segment orientation never assumes that a camera or MediaPipe world axis is gravity.
 * Callers must either provide a gravity vector expressed in the selected coordinate space or name
 * a directed body segment. Direction matters for signed alignment.
 */
sealed interface OrientationReference {
    val kind: OrientationReferenceKind

    data class Gravity(val direction: Vector3) : OrientationReference {
        override val kind: OrientationReferenceKind = OrientationReferenceKind.GRAVITY
    }

    data class BodyAxis(
        val from: PoseJoint,
        val to: PoseJoint,
    ) : OrientationReference {
        override val kind: OrientationReferenceKind = OrientationReferenceKind.BODY_FRAME
    }
}

/**
 * Pure coordinate primitive engine. Every frame primitive requires an explicit [PoseCoordinateSpace]
 * and never falls back from missing world landmarks to normalized image landmarks (or vice versa).
 */
class PoseFeatureEngine(
    val minimumConfidence: Double = 0.6,
) {
    val degeneracyEpsilon: Double = PoseFeaturePrimitiveContract.degeneracyEpsilon
    val runtimeContractSha256: String = featureRuntimeContractSha256(minimumConfidence)

    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimumConfidence must be finite and in [0, 1]"
        }
    }

    /** Gross included angle in degrees, in [0, 180]. */
    fun angle(
        frame: PoseFrame,
        first: PoseJoint,
        vertex: PoseJoint,
        third: PoseJoint,
        coordinateSpace: PoseCoordinateSpace,
    ): FeatureMeasurement = measure(
        frame = frame,
        coordinateSpace = coordinateSpace,
        requiredJoints = setOf(first, vertex, third),
        unit = FeatureUnit.DEGREES,
    ) { points ->
        angleBetween(
            points.getValue(first) - points.getValue(vertex),
            points.getValue(third) - points.getValue(vertex),
            firstDegenerateReason = FeatureUnknownReason.DEGENERATE_VECTOR,
            secondDegenerateReason = FeatureUnknownReason.DEGENERATE_VECTOR,
        )
    }

    /** Distance divided by an explicitly declared body segment length. */
    fun normalizedDistance(
        frame: PoseFrame,
        first: PoseJoint,
        second: PoseJoint,
        scaleStart: PoseJoint,
        scaleEnd: PoseJoint,
        coordinateSpace: PoseCoordinateSpace,
    ): FeatureMeasurement = measure(
        frame = frame,
        coordinateSpace = coordinateSpace,
        requiredJoints = setOf(first, second, scaleStart, scaleEnd),
        unit = FeatureUnit.BODY_SCALE_RATIO,
    ) { points ->
        val scale = (points.getValue(scaleEnd) - points.getValue(scaleStart)).length
        if (!scale.isFinite()) {
            Calculation.Unknown(FeatureUnknownReason.NUMERIC_ERROR)
        } else if (scale <= degeneracyEpsilon) {
            Calculation.Unknown(FeatureUnknownReason.DEGENERATE_SCALE)
        } else {
            Calculation.Known((points.getValue(first) - points.getValue(second)).length / scale)
        }
    }

    /** Unsigned segment angle relative to an explicit gravity vector or directed body axis. */
    fun segmentOrientation(
        frame: PoseFrame,
        segmentStart: PoseJoint,
        segmentEnd: PoseJoint,
        reference: OrientationReference,
        coordinateSpace: PoseCoordinateSpace,
    ): FeatureMeasurement {
        val requiredJoints = buildSet {
            add(segmentStart)
            add(segmentEnd)
            if (reference is OrientationReference.BodyAxis) {
                add(reference.from)
                add(reference.to)
            }
        }
        return measure(
            frame = frame,
            coordinateSpace = coordinateSpace,
            requiredJoints = requiredJoints,
            unit = FeatureUnit.DEGREES,
        ) { points ->
            val segment = points.getValue(segmentEnd) - points.getValue(segmentStart)
            val referenceVector = reference.resolve(points)
            angleBetween(
                segment,
                referenceVector,
                firstDegenerateReason = FeatureUnknownReason.DEGENERATE_VECTOR,
                secondDegenerateReason = FeatureUnknownReason.DEGENERATE_REFERENCE,
            )
        }
    }

    /**
     * Signed displacement of [point] from [anchor] along [reference], normalized by a body scale.
     * A BodyAxis is directed from `from` to `to`; reversing it reverses the sign by contract.
     */
    fun signedAlignment(
        frame: PoseFrame,
        point: PoseJoint,
        anchor: PoseJoint,
        reference: OrientationReference,
        scaleStart: PoseJoint,
        scaleEnd: PoseJoint,
        coordinateSpace: PoseCoordinateSpace,
    ): FeatureMeasurement {
        val requiredJoints = buildSet {
            add(point)
            add(anchor)
            add(scaleStart)
            add(scaleEnd)
            if (reference is OrientationReference.BodyAxis) {
                add(reference.from)
                add(reference.to)
            }
        }
        return measure(
            frame = frame,
            coordinateSpace = coordinateSpace,
            requiredJoints = requiredJoints,
            unit = FeatureUnit.BODY_SCALE_RATIO,
        ) { points ->
            val referenceUnit = reference.resolve(points).unitOrNull()
                ?: return@measure Calculation.Unknown(FeatureUnknownReason.DEGENERATE_REFERENCE)
            val scale = (points.getValue(scaleEnd) - points.getValue(scaleStart)).length
            if (!scale.isFinite()) {
                Calculation.Unknown(FeatureUnknownReason.NUMERIC_ERROR)
            } else if (scale <= degeneracyEpsilon) {
                Calculation.Unknown(FeatureUnknownReason.DEGENERATE_SCALE)
            } else {
                val displacement = points.getValue(point) - points.getValue(anchor)
                Calculation.Known((displacement dot referenceUnit) / scale)
            }
        }
    }

    /** Direction-preserving difference, useful for left-minus-right symmetry and displacement. */
    fun signedDifference(
        first: FeatureMeasurement,
        second: FeatureMeasurement,
    ): FeatureMeasurement = combine(first, second) { left, right -> left - right }

    /** Absolute difference, useful when only symmetry magnitude is part of the construct. */
    fun absoluteDifference(
        first: FeatureMeasurement,
        second: FeatureMeasurement,
    ): FeatureMeasurement = combine(first, second) { left, right -> abs(left - right) }

    /**
     * Max-minus-min over a fully observed, single-domain series.
     *
     * Missing samples are not silently discarded: temporal coverage belongs to the criterion
     * evidence layer, so an incomplete primitive series remains unknown here.
     */
    fun rangeOfMotion(
        measurements: List<FeatureMeasurement>,
        coordinateSpace: PoseCoordinateSpace,
    ): FeatureMeasurement {
        if (measurements.size < 2) {
            return FeatureMeasurement.unknown(
                reason = FeatureUnknownReason.INSUFFICIENT_SAMPLES,
                rawConfidence = measurements.minOfOrNull(FeatureMeasurement::rawConfidence) ?: 0.0,
                coordinateSpace = coordinateSpace,
                unit = measurements.firstOrNull()?.unit ?: FeatureUnit.DEGREES,
                requiredJoints = measurements.flatMapTo(mutableSetOf()) { it.requiredJoints },
            )
        }
        val unit = measurements.first().unit
        val requiredJoints = measurements.flatMapTo(mutableSetOf()) { it.requiredJoints }
        val rawConfidence = measurements.minOf(FeatureMeasurement::rawConfidence)
        if (measurements.any { it.coordinateSpace != coordinateSpace || it.unit != unit }) {
            return FeatureMeasurement.unknown(
                FeatureUnknownReason.INCOMPATIBLE_MEASUREMENTS,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }
        val unknown = measurements.firstOrNull { !it.isKnown }
        if (unknown != null) {
            return FeatureMeasurement.unknown(
                requireNotNull(unknown.unknownReason),
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }
        val values = measurements.map { requireNotNull(it.value) }
        return finiteMeasurement(
            value = values.max() - values.min(),
            rawConfidence = rawConfidence,
            coordinateSpace = coordinateSpace,
            unit = unit,
            requiredJoints = requiredJoints,
        )
    }

    private fun combine(
        first: FeatureMeasurement,
        second: FeatureMeasurement,
        operation: (Double, Double) -> Double,
    ): FeatureMeasurement {
        val requiredJoints = first.requiredJoints + second.requiredJoints
        val rawConfidence = min(first.rawConfidence, second.rawConfidence)
        if (first.coordinateSpace != second.coordinateSpace || first.unit != second.unit) {
            return FeatureMeasurement.unknown(
                FeatureUnknownReason.INCOMPATIBLE_MEASUREMENTS,
                rawConfidence,
                first.coordinateSpace,
                first.unit,
                requiredJoints,
            )
        }
        val unknown = listOf(first, second).firstOrNull { !it.isKnown }
        if (unknown != null) {
            return FeatureMeasurement.unknown(
                requireNotNull(unknown.unknownReason),
                rawConfidence,
                first.coordinateSpace,
                first.unit,
                requiredJoints,
            )
        }
        return finiteMeasurement(
            operation(requireNotNull(first.value), requireNotNull(second.value)),
            rawConfidence,
            first.coordinateSpace,
            first.unit,
            requiredJoints,
        )
    }

    private fun measure(
        frame: PoseFrame,
        coordinateSpace: PoseCoordinateSpace,
        requiredJoints: Set<PoseJoint>,
        unit: FeatureUnit,
        calculation: (Map<PoseJoint, Vector3>) -> Calculation,
    ): FeatureMeasurement {
        require(requiredJoints.isNotEmpty()) { "A pose feature must require at least one joint" }
        val source = when (coordinateSpace) {
            PoseCoordinateSpace.NORMALIZED_IMAGE -> frame.landmarks
            PoseCoordinateSpace.WORLD -> frame.worldLandmarks
        }
        val present = requiredJoints.mapNotNull(source::get)
        val rawConfidence = present.minOfOrNull { it.confidence } ?: 0.0
        if (present.size != requiredJoints.size) {
            return FeatureMeasurement.unknown(
                FeatureUnknownReason.MISSING_JOINT,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }
        if (
            coordinateSpace == PoseCoordinateSpace.NORMALIZED_IMAGE &&
            (frame.imageWidth <= 0 || frame.imageHeight <= 0)
        ) {
            return FeatureMeasurement.unknown(
                FeatureUnknownReason.INVALID_IMAGE_DIMENSIONS,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }
        if (rawConfidence < minimumConfidence) {
            return FeatureMeasurement.unknown(
                FeatureUnknownReason.LOW_CONFIDENCE,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }

        val points = try {
            requiredJoints.associateWith { joint ->
                val landmark = source.getValue(joint)
                if (coordinateSpace == PoseCoordinateSpace.NORMALIZED_IMAGE) {
                    Vector3(landmark.x * frame.imageAspectRatio, landmark.y, 0.0)
                } else {
                    Vector3(landmark.x, landmark.y, landmark.z)
                }
            }
        } catch (_: IllegalArgumentException) {
            return FeatureMeasurement.unknown(
                FeatureUnknownReason.NUMERIC_ERROR,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }

        val result = try {
            calculation(points)
        } catch (_: IllegalArgumentException) {
            Calculation.Unknown(FeatureUnknownReason.NUMERIC_ERROR)
        }
        return when (result) {
            is Calculation.Known -> finiteMeasurement(
                result.value,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
            is Calculation.Unknown -> FeatureMeasurement.unknown(
                result.reason,
                rawConfidence,
                coordinateSpace,
                unit,
                requiredJoints,
            )
        }
    }

    private fun finiteMeasurement(
        value: Double,
        rawConfidence: Double,
        coordinateSpace: PoseCoordinateSpace,
        unit: FeatureUnit,
        requiredJoints: Set<PoseJoint>,
    ): FeatureMeasurement = if (value.isFinite()) {
        FeatureMeasurement.known(value, rawConfidence, coordinateSpace, unit, requiredJoints)
    } else {
        FeatureMeasurement.unknown(
            FeatureUnknownReason.NUMERIC_ERROR,
            rawConfidence,
            coordinateSpace,
            unit,
            requiredJoints,
        )
    }

    private fun angleBetween(
        first: Vector3,
        second: Vector3,
        firstDegenerateReason: FeatureUnknownReason,
        secondDegenerateReason: FeatureUnknownReason,
    ): Calculation {
        val firstUnit = first.unitOrNull()
        val secondUnit = second.unitOrNull()
        if (firstUnit == null) return Calculation.Unknown(firstDegenerateReason)
        if (secondUnit == null) return Calculation.Unknown(secondDegenerateReason)
        val sine = (firstUnit cross secondUnit).length
        val cosine = firstUnit dot secondUnit
        return Calculation.Known(Math.toDegrees(atan2(sine, cosine)))
    }

    private fun Vector3.unitOrNull(): Vector3? {
        val norm = length
        if (!norm.isFinite() || norm <= degeneracyEpsilon) return null
        return try {
            Vector3(x / norm, y / norm, z / norm)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun OrientationReference.resolve(points: Map<PoseJoint, Vector3>): Vector3 = when (this) {
        is OrientationReference.Gravity -> direction
        is OrientationReference.BodyAxis -> points.getValue(to) - points.getValue(from)
    }

    private sealed interface Calculation {
        data class Known(val value: Double) : Calculation
        data class Unknown(val reason: FeatureUnknownReason) : Calculation
    }
}
