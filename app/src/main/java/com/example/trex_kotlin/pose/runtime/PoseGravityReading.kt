package com.example.trex_kotlin.pose.runtime

import kotlin.math.hypot

/**
 * Which way is down, in the frame the pose coordinates live in.
 *
 * Everything else this project measures is a rotation-invariant included angle, chosen precisely
 * so that no camera tilt or body facing could change it. This is the one quantity that is not,
 * and it exists because a whole class of movements is invisible without it: a press performed
 * face-down produces the same joint angles as the same arm movement performed standing, and no
 * included angle can separate them. Only the direction of gravity can.
 *
 * **In the camera plane, not in space.** The reading is the projection of gravity onto the upright
 * output image plane, expressed the way the pose landmarks are: x to the right, y downward. The
 * out-of-plane component is dropped rather than guessed, because recovering it would need the
 * camera's pose in the world, which nothing here attests. A projected angle is a smaller claim
 * than a spatial one and it is the claim the evidence supports.
 *
 * [inPlaneMagnitude] is what makes the smaller claim honest. A phone aimed straight down at the
 * floor, or straight up at a ceiling, projects almost nothing onto its own image plane, and the
 * direction that survives is noise. Below [MINIMUM_IN_PLANE_MAGNITUDE] the reading refuses to be
 * constructed at all, so a caller cannot accidentally consume a direction the sensor did not
 * really give.
 */
class PoseGravityReading private constructor(
    /** Unit vector along gravity, in the pose landmark frame: x right, y down. */
    val directionX: Double,
    val directionY: Double,
    /**
     * The fraction of gravity that lay in the image plane, from 0 (camera aimed along gravity) to
     * 1 (image plane contains gravity exactly). Kept so a consumer can be stricter than the floor.
     */
    val inPlaneMagnitude: Double,
    val timestampMs: Long,
) {
    companion object {
        /** The contract this reading is produced under, pinned by the sampling implementation. */
        const val CONTRACT_ID: String = "trex.device-gravity.in-image-plane.v1"

        /**
         * Below this fraction the in-plane direction is noise rather than measurement.
         *
         * 0.30 is about seventeen degrees of tilt away from aiming straight along gravity. A phone
         * stood on a floor or a shelf to film somebody is nowhere near it; a phone lying flat on a
         * table pointing at the ceiling is well inside it, and gets silence instead of a guess.
         */
        const val MINIMUM_IN_PLANE_MAGNITUDE: Double = 0.30

        /**
         * A reading older than this is not used. The sensor runs far faster than the camera, so a
         * gap this size means delivery stopped rather than that the device sat still.
         */
        const val MAXIMUM_AGE_MS: Long = 500L

        /**
         * Builds a reading from gravity already expressed in the pose landmark frame, or null when
         * too little of it lies in the image plane to be worth reporting.
         *
         * [outOfPlane] is only ever used to normalise the in-plane fraction; its sign is never
         * consumed, which is deliberate — the sign is the part of the sensor-to-camera transform
         * this project has no attested way to verify.
         */
        fun of(x: Double, y: Double, outOfPlane: Double, timestampMs: Long): PoseGravityReading? {
            if (!x.isFinite() || !y.isFinite() || !outOfPlane.isFinite()) return null
            if (timestampMs < 0L) return null
            val inPlane = hypot(x, y)
            val total = hypot(inPlane, outOfPlane)
            if (total <= 1e-6) return null
            val fraction = inPlane / total
            if (fraction < MINIMUM_IN_PLANE_MAGNITUDE) return null
            return PoseGravityReading(
                directionX = x / inPlane,
                directionY = y / inPlane,
                inPlaneMagnitude = fraction,
                timestampMs = timestampMs,
            )
        }
    }

    /** Whether this reading is recent enough to describe [atTimestampMs]. */
    fun isFreshAt(atTimestampMs: Long): Boolean =
        atTimestampMs - timestampMs in -MAXIMUM_AGE_MS..MAXIMUM_AGE_MS
}
