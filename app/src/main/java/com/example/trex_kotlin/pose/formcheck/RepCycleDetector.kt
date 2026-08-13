package com.example.trex_kotlin.pose.formcheck

import kotlin.math.exp

/** What one accepted angle sample concluded about the repetition in flight. */
internal sealed interface RepCycleEvent {
    /** A full descent below the rep threshold followed by a return to the top. */
    class Completed(
        val minimumAngleDegrees: Double,
        val durationMs: Long,
    ) : RepCycleEvent

    /** A descent that turned back before reaching the rep threshold. Never counted. */
    class ShallowAttempt(
        val minimumAngleDegrees: Double,
    ) : RepCycleEvent

    /**
     * Reached the rep threshold but returned too quickly to count. Reported separately so the
     * user is told the truthful reason — speed, not depth.
     */
    class TooFastAttempt(
        val minimumAngleDegrees: Double,
        val durationMs: Long,
    ) : RepCycleEvent

    data object None : RepCycleEvent
}

/**
 * Hysteresis state machine over a smoothed joint angle. A repetition is one excursion from the
 * top position below [bottomEnterDegrees] and back above [topEnterDegrees]; a turn-around that
 * never reaches the rep threshold is reported as a shallow attempt instead of a count.
 *
 * The detector is deliberately ignorant of exercises, joints, views and locks — it sees only a
 * number, which is what lets one implementation serve a knee, a hip hinge and an elbow. The
 * session invalidates it whenever observation quality lapses, and an invalidated excursion is
 * discarded without an event: an unobserved repetition is not evidence of one.
 *
 * It does assume the resting position is the extended one, so the angle falls as the movement
 * works. Exercises whose rest position is flexed (a hip thrust, an overhead press) would need
 * their own top and attempt thresholds and are not supported yet.
 */
internal class RepCycleDetector(
    private val bottomEnterDegrees: Double,
    private val attemptEnterDegrees: Double = DEFAULT_ATTEMPT_ENTER_DEGREES,
    private val topEnterDegrees: Double = DEFAULT_TOP_ENTER_DEGREES,
    private val minimumRepDurationMs: Long = DEFAULT_MINIMUM_REP_DURATION_MS,
    private val maximumRepDurationMs: Long = DEFAULT_MAXIMUM_REP_DURATION_MS,
    private val smoothingTimeConstantMs: Long = DEFAULT_SMOOTHING_TIME_CONSTANT_MS,
) {

    init {
        require(bottomEnterDegrees in 0.0..180.0)
        require(bottomEnterDegrees < attemptEnterDegrees) {
            "The rep threshold must be deeper than the attempt threshold"
        }
        require(attemptEnterDegrees < topEnterDegrees) {
            "The attempt threshold must be deeper than the top threshold"
        }
        require(topEnterDegrees <= 180.0)
        require(minimumRepDurationMs > 0L)
        require(maximumRepDurationMs > minimumRepDurationMs)
        require(smoothingTimeConstantMs > 0L)
    }

    private var smoothedDegrees: Double? = null
    private var smoothedTimestampMs: Long? = null
    private var excursionStartMs: Long? = null

    /**
     * Tracked on raw samples, not the EMA: smoothing lag raises the smoothed minimum by the
     * descent speed times the time constant, which at fast cadence would reject genuinely deep
     * repetitions as shallow and overstate the reported angle. The EMA drives only the state
     * transitions, where its noise rejection is the point.
     */
    private var excursionMinRawDegrees: Double = Double.MAX_VALUE
    private var hasSeenTop: Boolean = false

    val smoothedAngleDegrees: Double?
        get() = smoothedDegrees

    /**
     * Whether an armed excursion is in flight right now. The guard observation accumulates only
     * inside this window, so a stretch or a head-scratch between repetitions cannot be reported
     * as movement during one.
     */
    val inExcursion: Boolean
        get() = excursionStartMs != null

    /**
     * How far the excursion in flight has travelled so far, in the detector's space, or null when
     * nothing is armed. Raw rather than smoothed for the same reason the completed event reports a
     * raw minimum: the EMA lags the descent and would understate how far the movement has gone.
     */
    val excursionExtremeDegrees: Double?
        get() = excursionMinRawDegrees.takeIf { inExcursion && it != Double.MAX_VALUE }

    fun accept(timestampMs: Long, angleDegrees: Double): RepCycleEvent {
        val previousTs = smoothedTimestampMs
        if (previousTs != null && timestampMs <= previousTs) {
            invalidate()
            return RepCycleEvent.None
        }
        val smoothed = smooth(timestampMs, angleDegrees)

        val startMs = excursionStartMs
        if (startMs == null) {
            if (smoothed >= topEnterDegrees) {
                hasSeenTop = true
            }
            // Arming requires having observed the top since the last reset. Reacquiring the
            // person at the bottom of a squat must not turn the ascent alone into a counted
            // repetition — an unobserved descent is not evidence of one.
            if (hasSeenTop && smoothed <= attemptEnterDegrees) {
                excursionStartMs = timestampMs
                excursionMinRawDegrees = angleDegrees
            }
            return RepCycleEvent.None
        }

        excursionMinRawDegrees = minOf(excursionMinRawDegrees, angleDegrees)
        if (timestampMs - startMs > maximumRepDurationMs) {
            // Too long to be one repetition; whatever this is, it is not counted — and the top
            // must be observed again before anything new can arm, or the tail ascent of this
            // same movement would immediately become a counted repetition of its own.
            excursionStartMs = null
            excursionMinRawDegrees = Double.MAX_VALUE
            hasSeenTop = false
            return RepCycleEvent.None
        }
        if (smoothed < topEnterDegrees) {
            return RepCycleEvent.None
        }

        val minimum = excursionMinRawDegrees
        val duration = timestampMs - startMs
        excursionStartMs = null
        excursionMinRawDegrees = Double.MAX_VALUE
        return when {
            minimum <= bottomEnterDegrees && duration >= minimumRepDurationMs ->
                RepCycleEvent.Completed(minimumAngleDegrees = minimum, durationMs = duration)

            // Depth was reached, so calling this "shallow" would be a false observation; the
            // truthful disqualifier is speed.
            minimum <= bottomEnterDegrees ->
                RepCycleEvent.TooFastAttempt(minimumAngleDegrees = minimum, durationMs = duration)

            minimum <= attemptEnterDegrees ->
                RepCycleEvent.ShallowAttempt(minimumAngleDegrees = minimum)

            else -> RepCycleEvent.None
        }
    }

    /** Discards the excursion in flight and the smoothing history without emitting anything. */
    fun invalidate() {
        smoothedDegrees = null
        smoothedTimestampMs = null
        excursionStartMs = null
        excursionMinRawDegrees = Double.MAX_VALUE
        hasSeenTop = false
    }

    private fun smooth(timestampMs: Long, sampleDegrees: Double): Double {
        val previous = smoothedDegrees
        val previousTs = smoothedTimestampMs
        val next = if (previous == null || previousTs == null) {
            sampleDegrees
        } else {
            val dtMs = (timestampMs - previousTs).toDouble()
            val alpha = 1.0 - exp(-dtMs / smoothingTimeConstantMs.toDouble())
            previous + alpha * (sampleDegrees - previous)
        }
        smoothedDegrees = next
        smoothedTimestampMs = timestampMs
        return next
    }

    companion object {
        const val DEFAULT_ATTEMPT_ENTER_DEGREES: Double = 140.0
        const val DEFAULT_TOP_ENTER_DEGREES: Double = 150.0
        const val DEFAULT_MINIMUM_REP_DURATION_MS: Long = 500L
        const val DEFAULT_MAXIMUM_REP_DURATION_MS: Long = 10_000L
        const val DEFAULT_SMOOTHING_TIME_CONSTANT_MS: Long = 100L
    }
}
