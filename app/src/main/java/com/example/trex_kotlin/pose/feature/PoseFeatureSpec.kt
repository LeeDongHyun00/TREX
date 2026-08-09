package com.example.trex_kotlin.pose.feature

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections

private val FEATURE_CONTRACT_ID = Regex("^[a-z0-9][a-z0-9._:/-]*$")

/**
 * Versioned, data-driven definition of one frame-level scalar pose feature.
 *
 * Exercise specifications contain these values instead of branching on an exercise name. The
 * contract id is also stored in a criterion calibration artifact, so changing a joint mapping,
 * reference frame, sign, or scale invalidates the old calibration by construction.
 */
sealed interface PoseScalarFeatureSpec {
    val featureContractId: String
    val coordinateSpace: PoseCoordinateSpace
    val unit: FeatureUnit
    val requiredJoints: Set<PoseJoint>

    /**
     * Canonical identity of the complete feature AST, not just its human-assigned contract id.
     *
     * Calibration artifacts must pin this value. Any change to a joint, coordinate domain,
     * reference frame/vector, sign policy, or nested feature therefore invalidates calibration.
     */
    val featureSpecSha256: String
        get() = canonicalFeatureSpecSha256(this)

    data class JointAngle(
        override val featureContractId: String,
        override val coordinateSpace: PoseCoordinateSpace,
        val first: PoseJoint,
        val vertex: PoseJoint,
        val third: PoseJoint,
    ) : PoseScalarFeatureSpec {
        override val unit: FeatureUnit = FeatureUnit.DEGREES
        override val requiredJoints: Set<PoseJoint> = immutableJointSet(first, vertex, third)

        init {
            validateFeatureContractId(featureContractId)
            require(requiredJoints.size == 3) { "A joint angle requires three distinct joints" }
        }
    }

    data class NormalizedDistance(
        override val featureContractId: String,
        override val coordinateSpace: PoseCoordinateSpace,
        val first: PoseJoint,
        val second: PoseJoint,
        val scaleStart: PoseJoint,
        val scaleEnd: PoseJoint,
    ) : PoseScalarFeatureSpec {
        override val unit: FeatureUnit = FeatureUnit.BODY_SCALE_RATIO
        override val requiredJoints: Set<PoseJoint> =
            immutableJointSet(first, second, scaleStart, scaleEnd)

        init {
            validateFeatureContractId(featureContractId)
            require(first != second) { "A distance requires two distinct joints" }
            require(scaleStart != scaleEnd) { "A scale segment requires two distinct joints" }
        }
    }

    data class SegmentOrientation(
        override val featureContractId: String,
        override val coordinateSpace: PoseCoordinateSpace,
        val segmentStart: PoseJoint,
        val segmentEnd: PoseJoint,
        val reference: OrientationReference,
    ) : PoseScalarFeatureSpec {
        override val unit: FeatureUnit = FeatureUnit.DEGREES
        override val requiredJoints: Set<PoseJoint> = immutableJointSet(buildSet {
            add(segmentStart)
            add(segmentEnd)
            if (reference is OrientationReference.BodyAxis) {
                add(reference.from)
                add(reference.to)
            }
        })

        init {
            validateFeatureContractId(featureContractId)
            require(segmentStart != segmentEnd) {
                "An orientation segment requires two distinct joints"
            }
            if (reference is OrientationReference.BodyAxis) {
                require(reference.from != reference.to) {
                    "A body-axis reference requires two distinct joints"
                }
            }
        }
    }

    data class SignedAlignment(
        override val featureContractId: String,
        override val coordinateSpace: PoseCoordinateSpace,
        val point: PoseJoint,
        val anchor: PoseJoint,
        val reference: OrientationReference,
        val scaleStart: PoseJoint,
        val scaleEnd: PoseJoint,
    ) : PoseScalarFeatureSpec {
        override val unit: FeatureUnit = FeatureUnit.BODY_SCALE_RATIO
        override val requiredJoints: Set<PoseJoint> = immutableJointSet(buildSet {
            add(point)
            add(anchor)
            add(scaleStart)
            add(scaleEnd)
            if (reference is OrientationReference.BodyAxis) {
                add(reference.from)
                add(reference.to)
            }
        })

        init {
            validateFeatureContractId(featureContractId)
            require(point != anchor) { "Signed alignment requires a distinct point and anchor" }
            require(scaleStart != scaleEnd) { "A scale segment requires two distinct joints" }
            if (reference is OrientationReference.BodyAxis) {
                require(reference.from != reference.to) {
                    "A body-axis reference requires two distinct joints"
                }
            }
        }
    }

    /** Difference is an explicit graph node and cannot silently mix units or coordinate domains. */
    data class Difference(
        override val featureContractId: String,
        val first: PoseScalarFeatureSpec,
        val second: PoseScalarFeatureSpec,
        val absolute: Boolean,
    ) : PoseScalarFeatureSpec {
        override val coordinateSpace: PoseCoordinateSpace = first.coordinateSpace
        override val unit: FeatureUnit = first.unit
        override val requiredJoints: Set<PoseJoint> =
            immutableJointSet(first.requiredJoints + second.requiredJoints)

        init {
            validateFeatureContractId(featureContractId)
            require(first.coordinateSpace == second.coordinateSpace) {
                "A feature difference cannot mix coordinate domains"
            }
            require(first.unit == second.unit) { "A feature difference cannot mix units" }
            require(first.featureContractId != second.featureContractId) {
                "A feature difference requires two distinct input contracts"
            }
        }
    }
}

/** Canonical measurement-unit identity used by signed calibration contracts. */
val FeatureUnit.contractId: String
    get() = when (this) {
        FeatureUnit.DEGREES -> "degrees"
        FeatureUnit.BODY_SCALE_RATIO -> "body-scale-ratio"
    }

/** Evaluate a data-defined scalar feature using the same fail-closed primitive implementation. */
fun PoseFeatureEngine.measure(
    frame: PoseFrame,
    spec: PoseScalarFeatureSpec,
): FeatureMeasurement = when (spec) {
    is PoseScalarFeatureSpec.JointAngle -> angle(
        frame = frame,
        first = spec.first,
        vertex = spec.vertex,
        third = spec.third,
        coordinateSpace = spec.coordinateSpace,
    )

    is PoseScalarFeatureSpec.NormalizedDistance -> normalizedDistance(
        frame = frame,
        first = spec.first,
        second = spec.second,
        scaleStart = spec.scaleStart,
        scaleEnd = spec.scaleEnd,
        coordinateSpace = spec.coordinateSpace,
    )

    is PoseScalarFeatureSpec.SegmentOrientation -> segmentOrientation(
        frame = frame,
        segmentStart = spec.segmentStart,
        segmentEnd = spec.segmentEnd,
        reference = spec.reference,
        coordinateSpace = spec.coordinateSpace,
    )

    is PoseScalarFeatureSpec.SignedAlignment -> signedAlignment(
        frame = frame,
        point = spec.point,
        anchor = spec.anchor,
        reference = spec.reference,
        scaleStart = spec.scaleStart,
        scaleEnd = spec.scaleEnd,
        coordinateSpace = spec.coordinateSpace,
    )

    is PoseScalarFeatureSpec.Difference -> {
        val first = measure(frame, spec.first)
        val second = measure(frame, spec.second)
        if (spec.absolute) absoluteDifference(first, second) else signedDifference(first, second)
    }
}

private fun validateFeatureContractId(value: String) {
    require(FEATURE_CONTRACT_ID.matches(value)) {
        "featureContractId must be a lowercase, versioned identifier"
    }
}

private fun immutableJointSet(vararg joints: PoseJoint): Set<PoseJoint> =
    immutableJointSet(joints.asList())

private fun immutableJointSet(joints: Collection<PoseJoint>): Set<PoseJoint> =
    Collections.unmodifiableSet(LinkedHashSet(joints))

internal fun canonicalFeatureSpecSha256(
    spec: PoseScalarFeatureSpec,
    primitiveContractSha256: String = PoseFeaturePrimitiveContract.sha256,
): String {
    val fields = when (spec) {
        is PoseScalarFeatureSpec.JointAngle -> listOf(
            "featureSpecSchemaVersion" to "1",
            "nodeType" to "joint-angle",
            "featureContractId" to spec.featureContractId,
            "coordinateSpace" to spec.coordinateSpace.name,
            "unit" to spec.unit.contractId,
            "first" to spec.first.name,
            "vertex" to spec.vertex.name,
            "third" to spec.third.name,
        )

        is PoseScalarFeatureSpec.NormalizedDistance -> listOf(
            "featureSpecSchemaVersion" to "1",
            "nodeType" to "normalized-distance",
            "featureContractId" to spec.featureContractId,
            "coordinateSpace" to spec.coordinateSpace.name,
            "unit" to spec.unit.contractId,
            "first" to spec.first.name,
            "second" to spec.second.name,
            "scaleStart" to spec.scaleStart.name,
            "scaleEnd" to spec.scaleEnd.name,
        )

        is PoseScalarFeatureSpec.SegmentOrientation -> listOf(
            "featureSpecSchemaVersion" to "1",
            "nodeType" to "segment-orientation",
            "featureContractId" to spec.featureContractId,
            "coordinateSpace" to spec.coordinateSpace.name,
            "unit" to spec.unit.contractId,
            "segmentStart" to spec.segmentStart.name,
            "segmentEnd" to spec.segmentEnd.name,
        ) + spec.reference.canonicalFields()

        is PoseScalarFeatureSpec.SignedAlignment -> listOf(
            "featureSpecSchemaVersion" to "1",
            "nodeType" to "signed-alignment",
            "featureContractId" to spec.featureContractId,
            "coordinateSpace" to spec.coordinateSpace.name,
            "unit" to spec.unit.contractId,
            "point" to spec.point.name,
            "anchor" to spec.anchor.name,
            "scaleStart" to spec.scaleStart.name,
            "scaleEnd" to spec.scaleEnd.name,
        ) + spec.reference.canonicalFields()

        is PoseScalarFeatureSpec.Difference -> listOf(
            "featureSpecSchemaVersion" to "1",
            "nodeType" to "difference",
            "featureContractId" to spec.featureContractId,
            "coordinateSpace" to spec.coordinateSpace.name,
            "unit" to spec.unit.contractId,
            "firstFeatureSpecSha256" to spec.first.featureSpecSha256,
            "secondFeatureSpecSha256" to spec.second.featureSpecSha256,
            "absolute" to spec.absolute.toString(),
        )
    }
    return canonicalFieldsSha256(
        listOf("featurePrimitiveContractSha256" to primitiveContractSha256) + fields,
    )
}

private fun OrientationReference.canonicalFields(): List<Pair<String, String>> = when (this) {
    is OrientationReference.Gravity -> listOf(
        "referenceKind" to "gravity",
        "referenceX" to java.lang.Double.toHexString(direction.x),
        "referenceY" to java.lang.Double.toHexString(direction.y),
        "referenceZ" to java.lang.Double.toHexString(direction.z),
    )

    is OrientationReference.BodyAxis -> listOf(
        "referenceKind" to "body-axis",
        "referenceFrom" to from.name,
        "referenceTo" to to.name,
    )
}
