package com.example.trex_kotlin.pose.placement

/**
 * Keeps guidance readable without letting a stale "goal reached" linger.
 *
 * A single frame of diagnostics is too noisy to drive text a person is reading, so ordinary
 * transitions are damped. Two transitions are exempt and take effect on the frame they occur:
 *
 * - Entering a reached goal, because the user has already done what the screen is asking for.
 * - Leaving a reached goal, because a reached badge that outlives the placement it described is
 *   exactly the false reassurance this track exists to avoid.
 *
 * The stabilizer holds no observation data, only the previously shown display and two timestamps.
 */
internal class PlacementCoachGuidanceStabilizer(
    private val minimumHoldMs: Long = DEFAULT_MINIMUM_HOLD_MS,
    private val maximumFrameGapMs: Long = DEFAULT_MAXIMUM_FRAME_GAP_MS,
) {

    init {
        require(minimumHoldMs >= 0) { "Minimum hold must not be negative" }
        require(maximumFrameGapMs > 0) { "Maximum frame gap must be positive" }
    }

    private var shown: PlacementCoachDisplay? = null
    private var shownAtMs: Long = 0L
    private var lastFrameMs: Long? = null

    /** Display currently held, or null before the first frame. */
    val current: PlacementCoachDisplay?
        get() = shown

    fun stabilize(frameTimestampMs: Long, resolved: PlacementCoachDisplay): PlacementCoachDisplay {
        val previousFrameMs = lastFrameMs
        val discontinuous = previousFrameMs != null &&
            (frameTimestampMs < previousFrameMs || frameTimestampMs - previousFrameMs > maximumFrameGapMs)
        lastFrameMs = frameTimestampMs

        if (discontinuous) {
            // The gap means the held guidance describes a placement we can no longer vouch for.
            return adopt(frameTimestampMs, resolved)
        }

        val held = shown ?: return adopt(frameTimestampMs, resolved)
        if (held == resolved) {
            return held
        }
        if (held.goal != resolved.goal || held.goalReached != resolved.goalReached) {
            return adopt(frameTimestampMs, resolved)
        }
        if (frameTimestampMs - shownAtMs >= minimumHoldMs) {
            return adopt(frameTimestampMs, resolved)
        }
        return held
    }

    fun reset() {
        shown = null
        shownAtMs = 0L
        lastFrameMs = null
    }

    private fun adopt(frameTimestampMs: Long, resolved: PlacementCoachDisplay): PlacementCoachDisplay {
        shown = resolved
        shownAtMs = frameTimestampMs
        return resolved
    }

    companion object {
        const val DEFAULT_MINIMUM_HOLD_MS: Long = 700L

        /** Mirrors the observer's own tolerance before it drops its person lock. */
        const val DEFAULT_MAXIMUM_FRAME_GAP_MS: Long = 250L
    }
}
