package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import kotlin.math.acos
import kotlin.math.sqrt

internal enum class FormCheckBodySide { LEFT, RIGHT }

/**
 * A bilateral joint group an exercise needs to observe. Named so guidance can say exactly what
 * is missing rather than asking the user to guess.
 */
internal enum class FormCheckJointGroup(
    val label: String,
    val left: PoseJoint,
    val right: PoseJoint,
) {
    SHOULDER("어깨", PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER),
    ELBOW("팔꿈치", PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW),
    WRIST("손목", PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST),
    HIP("엉덩이", PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP),
    KNEE("무릎", PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE),
    ANKLE("발목", PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE),
    ;

    fun joint(side: FormCheckBodySide): PoseJoint = when (side) {
        FormCheckBodySide.LEFT -> left
        FormCheckBodySide.RIGHT -> right
    }
}

/**
 * Whether this exercise's required joints are observable right now, and which are not.
 *
 * Readiness deliberately ignores the rest of the body: a knee angle needs a hip, a knee and an
 * ankle on one side, so cropped feet on the far side or a head out of frame must not stop the
 * exercise from starting.
 */
internal class FormCheckReadiness internal constructor(
    val ready: Boolean,
    val side: FormCheckBodySide?,
    missingGroups: Set<FormCheckJointGroup>,
) {
    val missingGroups: Set<FormCheckJointGroup> =
        java.util.Collections.unmodifiableSet(LinkedHashSet(missingGroups.sortedBy { it.ordinal }))

    init {
        require(ready == (this.missingGroups.isEmpty() && side != null)) {
            "Readiness must agree with its missing groups"
        }
    }
}

/**
 * The three-joint chain an exercise reads, hinged at [vertex].
 *
 * Naming the chain rather than hard-coding the knee is what lets one geometry engine serve a
 * squat, a hip hinge and a push-up: all three are an included angle at a joint, and none of them
 * needs gravity, camera tilt or absolute distance.
 */
internal class FormCheckDriver(
    val vertex: FormCheckJointGroup,
    val first: FormCheckJointGroup,
    val second: FormCheckJointGroup,
) {
    init {
        require(vertex != first && vertex != second && first != second) {
            "A driver chain needs three distinct joint groups"
        }
    }

    /** Every group the chain needs before the exercise can start. */
    val requiredJoints: Set<FormCheckJointGroup> =
        java.util.Collections.unmodifiableSet(
            LinkedHashSet(listOf(first, vertex, second).sortedBy { it.ordinal }),
        )

    companion object {
        /** hip-knee-ankle. Squats and lunges. */
        val KNEE = FormCheckDriver(
            vertex = FormCheckJointGroup.KNEE,
            first = FormCheckJointGroup.HIP,
            second = FormCheckJointGroup.ANKLE,
        )

        /** shoulder-hip-knee. Hip hinges and knee raises. */
        val HIP = FormCheckDriver(
            vertex = FormCheckJointGroup.HIP,
            first = FormCheckJointGroup.SHOULDER,
            second = FormCheckJointGroup.KNEE,
        )

        /** shoulder-elbow-wrist. Pressing and pulling. */
        val ELBOW = FormCheckDriver(
            vertex = FormCheckJointGroup.ELBOW,
            first = FormCheckJointGroup.SHOULDER,
            second = FormCheckJointGroup.WRIST,
        )

        /**
         * elbow-shoulder-hip. How far the upper arm sits from the torso.
         *
         * This chain answers a question the elbow chain cannot: whether the arm stayed against the
         * body. A curl's fault is the elbow drifting forward, which barely moves the elbow angle
         * but swings this one; a pull-down's work is the upper arm closing toward the ribs, which
         * the elbow angle also misses. The dataset's own conditions for those exercises separate
         * on this chain and score at chance on the elbow.
         */
        val SHOULDER = FormCheckDriver(
            vertex = FormCheckJointGroup.SHOULDER,
            first = FormCheckJointGroup.ELBOW,
            second = FormCheckJointGroup.HIP,
        )
    }
}

/**
 * One evaluable measurement: the included angle at the driver's vertex on the better-observed
 * side. 180 degrees is a straight chain. The angle is a rotation-invariant quantity of three
 * world points, so it makes no assumption about gravity, camera tilt or absolute distance.
 */
internal class FormCheckAngleSample(
    val side: FormCheckBodySide,
    val includedAngleDegrees: Double,
    val chainConfidence: Double,
) {
    init {
        require(includedAngleDegrees in 0.0..180.0) { "Included angle out of range" }
        require(chainConfidence in 0.0..1.0) { "Confidence out of range" }
    }
}

internal object FormCheckGeometry {

    /** A hip-knee-ankle chain below this world confidence abstains from evaluation. */
    const val MINIMUM_CHAIN_CONFIDENCE: Double = 0.55

    /**
     * Measures [driver] on whichever side is better observed, or null when neither side's full
     * chain is credible — in a lateral pose that is the near limb.
     */
    fun sample(frame: PoseFrame, driver: FormCheckDriver): FormCheckAngleSample? {
        val left = sideSample(frame, FormCheckBodySide.LEFT, driver)
        val right = sideSample(frame, FormCheckBodySide.RIGHT, driver)
        return when {
            left != null && right != null ->
                if (left.chainConfidence >= right.chainConfidence) left else right
            left != null -> left
            else -> right
        }
    }

    /**
     * Which of [required] this frame can observe, on whichever side sees more of them.
     *
     * This is the start gate: the exercise begins as soon as its own joints are visible, with no
     * whole-body framing requirement.
     */
    fun readiness(frame: PoseFrame, required: Set<FormCheckJointGroup>): FormCheckReadiness {
        if (required.isEmpty()) {
            return FormCheckReadiness(ready = false, side = null, missingGroups = emptySet())
        }
        val perSide = FormCheckBodySide.entries.map { side ->
            side to required.filterNot { group -> isCredible(frame, group.joint(side)) }.toSet()
        }
        val best = perSide.minByOrNull { (_, missing) -> missing.size } ?: return FormCheckReadiness(
            ready = false,
            side = null,
            missingGroups = required,
        )
        val (side, missing) = best
        return FormCheckReadiness(
            ready = missing.isEmpty(),
            side = if (missing.isEmpty()) side else null,
            missingGroups = missing,
        )
    }

    private fun isCredible(frame: PoseFrame, joint: PoseJoint): Boolean {
        val landmark = frame.worldLandmarks[joint] ?: return false
        return landmark.confidence >= MINIMUM_CHAIN_CONFIDENCE
    }

    /** Measures one specific side, abstaining when its chain is not credible. */
    fun sideSample(
        frame: PoseFrame,
        side: FormCheckBodySide,
        driver: FormCheckDriver,
    ): FormCheckAngleSample? {
        val first = frame.worldLandmarks[driver.first.joint(side)] ?: return null
        val vertex = frame.worldLandmarks[driver.vertex.joint(side)] ?: return null
        val second = frame.worldLandmarks[driver.second.joint(side)] ?: return null
        val confidence = minOf(first.confidence, vertex.confidence, second.confidence)
        if (confidence < MINIMUM_CHAIN_CONFIDENCE) return null
        val angle = includedAngleDegrees(vertex = vertex, first = first, second = second)
            ?: return null
        return FormCheckAngleSample(
            side = side,
            includedAngleDegrees = angle,
            chainConfidence = confidence,
        )
    }

    /** Included angle at [vertex] between the rays toward [first] and [second], in degrees. */
    fun includedAngleDegrees(
        vertex: PoseLandmark,
        first: PoseLandmark,
        second: PoseLandmark,
    ): Double? {
        val ax = first.x - vertex.x
        val ay = first.y - vertex.y
        val az = first.z - vertex.z
        val bx = second.x - vertex.x
        val by = second.y - vertex.y
        val bz = second.z - vertex.z
        val aLength = sqrt(ax * ax + ay * ay + az * az)
        val bLength = sqrt(bx * bx + by * by + bz * bz)
        if (aLength <= DEGENERATE_EPSILON || bLength <= DEGENERATE_EPSILON) return null
        val cosine = ((ax * bx + ay * by + az * bz) / (aLength * bLength)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    private const val DEGENERATE_EPSILON = 1e-6
}
