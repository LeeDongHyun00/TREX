package com.example.trex_kotlin.pose.placement

import java.util.Collections
import java.util.EnumMap

/**
 * In-memory summary of one placement attempt.
 *
 * Every field is a duration difference or a count. No coordinate, frame, landmark or wall-clock
 * instant survives into this type, which is what lets the coach report how placement went without
 * retaining anything about the person who was in front of the camera.
 */
internal class PlacementCoachAttemptAggregate internal constructor(
    val acceptedFrameCount: Int,
    val attemptDurationMs: Long,
    val fullBodyReachedAfterMs: Long?,
    val lateralReachedAfterMs: Long?,
    val discontinuityCount: Int,
    val droppedFrameCount: Int,
    guidanceDwellMs: Map<PlacementCoachGuidance, Long>,
) {
    val guidanceDwellMs: Map<PlacementCoachGuidance, Long> =
        Collections.unmodifiableMap(EnumMap(guidanceDwellMs))

    init {
        require(acceptedFrameCount >= 0) { "Accepted frame count must not be negative" }
        require(attemptDurationMs >= 0) { "Attempt duration must not be negative" }
        require(discontinuityCount >= 0) { "Discontinuity count must not be negative" }
        require(droppedFrameCount >= 0) { "Dropped frame count must not be negative" }
        require(fullBodyReachedAfterMs == null || fullBodyReachedAfterMs >= 0) {
            "Full-body reach time must not be negative"
        }
        require(lateralReachedAfterMs == null || lateralReachedAfterMs >= 0) {
            "Lateral reach time must not be negative"
        }
        require(guidanceDwellMs.values.all { it >= 0 }) { "Dwell time must not be negative" }
    }
}

/**
 * Accumulates the aggregates behind roadmap experiment E1 — how long placement takes and which
 * guidance holds people up — under four deliberate limits.
 *
 * 1. Memory only. There is no file, preference, database, network or logging path here.
 * 2. Aggregate only. The recorder keeps one previous timestamp so it can take a difference; that
 *    value is never exposed and nothing else about a frame is kept.
 * 3. Bounded. Acceptance stops at [maximumAcceptedFrames] so a forgotten screen cannot grow.
 * 4. Capped attribution. A single gap contributes at most [maximumDwellStepMs] to a guidance
 *    bucket, so one long pause cannot dominate the distribution.
 *
 * The research traces elsewhere in this module discard everything on a discontinuity. That is the
 * wrong trade here: the distribution of what holds people up is the whole point of E1, so a gap is
 * counted rather than allowed to erase the attempt.
 */
internal class PlacementCoachAttemptRecorder(
    private val maximumAcceptedFrames: Int = DEFAULT_MAXIMUM_ACCEPTED_FRAMES,
    private val maximumDwellStepMs: Long = DEFAULT_MAXIMUM_DWELL_STEP_MS,
) {

    init {
        require(maximumAcceptedFrames > 0) { "Maximum accepted frames must be positive" }
        require(maximumDwellStepMs > 0) { "Maximum dwell step must be positive" }
    }

    private val guidanceDwellMs = EnumMap<PlacementCoachGuidance, Long>(PlacementCoachGuidance::class.java)
    private var previousFrameMs: Long? = null
    private var previousGuidance: PlacementCoachGuidance? = null
    private var elapsedMs: Long = 0L
    private var acceptedFrameCount: Int = 0
    private var droppedFrameCount: Int = 0
    private var discontinuityCount: Int = 0
    private var fullBodyReachedAfterMs: Long? = null
    private var lateralReachedAfterMs: Long? = null

    fun accept(frameTimestampMs: Long, display: PlacementCoachDisplay) {
        if (acceptedFrameCount >= maximumAcceptedFrames) {
            droppedFrameCount++
            return
        }

        val previous = previousFrameMs
        if (previous != null) {
            val delta = frameTimestampMs - previous
            if (delta < 0L || delta > maximumDwellStepMs) {
                discontinuityCount++
            }
            val advance = delta.coerceAtLeast(0L)
            elapsedMs += advance
            previousGuidance?.let { guidance ->
                val step = advance.coerceAtMost(maximumDwellStepMs)
                guidanceDwellMs[guidance] = (guidanceDwellMs[guidance] ?: 0L) + step
            }
        }

        if (display.goalReached) {
            when (display.goal) {
                PlacementCoachGoal.FULL_BODY ->
                    if (fullBodyReachedAfterMs == null) fullBodyReachedAfterMs = elapsedMs
                PlacementCoachGoal.LATERAL ->
                    if (lateralReachedAfterMs == null) lateralReachedAfterMs = elapsedMs
            }
        }

        previousFrameMs = frameTimestampMs
        previousGuidance = display.guidance
        acceptedFrameCount++
    }

    fun snapshot(): PlacementCoachAttemptAggregate = PlacementCoachAttemptAggregate(
        acceptedFrameCount = acceptedFrameCount,
        attemptDurationMs = elapsedMs,
        fullBodyReachedAfterMs = fullBodyReachedAfterMs,
        lateralReachedAfterMs = lateralReachedAfterMs,
        discontinuityCount = discontinuityCount,
        droppedFrameCount = droppedFrameCount,
        guidanceDwellMs = guidanceDwellMs,
    )

    fun reset() {
        guidanceDwellMs.clear()
        previousFrameMs = null
        previousGuidance = null
        elapsedMs = 0L
        acceptedFrameCount = 0
        droppedFrameCount = 0
        discontinuityCount = 0
        fullBodyReachedAfterMs = null
        lateralReachedAfterMs = null
    }

    companion object {
        const val DEFAULT_MAXIMUM_ACCEPTED_FRAMES: Int = 10_000

        /** Matches the observer tolerance the stabilizer uses, so both agree on what a gap is. */
        const val DEFAULT_MAXIMUM_DWELL_STEP_MS: Long =
            PlacementCoachGuidanceStabilizer.DEFAULT_MAXIMUM_FRAME_GAP_MS
    }
}
