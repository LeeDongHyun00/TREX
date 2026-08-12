package com.example.trex_kotlin.pose.formcheck

import kotlin.math.exp

/** What one accepted angle sample concluded about a hold in progress. */
internal sealed interface HoldEvent {
    /** The hold has just begun. */
    data object Entered : HoldEvent

    /** The hold is running; [heldMs] is how long it has been maintained without a break. */
    class Holding(val heldMs: Long) : HoldEvent

    /**
     * The position left the hold band. [heldMs] is what the completed stretch amounted to, and
     * [countedAsHold] says whether it lasted long enough to be worth reporting as one.
     */
    class Released(val heldMs: Long, val countedAsHold: Boolean) : HoldEvent

    data object None : HoldEvent
}

/**
 * Hysteresis state machine for isometric exercises, where the movement is holding a position
 * rather than repeating one.
 *
 * The band is one-sided: the position is in the hold while the smoothed angle stays past
 * [enterDegrees], and leaves once it falls back past [exitDegrees]. Both are expressed in the
 * detector's space, where a smaller number always means more work, so a plank's straight body and
 * a wall-sit's bent knee are the same problem with different constants.
 *
 * Like [RepCycleDetector] it knows nothing about exercises, locks or views: the session
 * invalidates it whenever observation quality lapses, and an invalidated stretch is discarded
 * without an event. Time that was not observed is not time the user held anything.
 */
internal class HoldDetector(
    private val enterDegrees: Double,
    private val exitDegrees: Double,
    private val minimumHoldMs: Long = DEFAULT_MINIMUM_HOLD_MS,
    private val smoothingTimeConstantMs: Long = DEFAULT_SMOOTHING_TIME_CONSTANT_MS,
) {

    init {
        require(enterDegrees in 0.0..180.0)
        require(exitDegrees in 0.0..180.0)
        require(enterDegrees < exitDegrees) {
            "Entering a hold must demand more than leaving it, or the band would chatter"
        }
        require(minimumHoldMs > 0L)
        require(smoothingTimeConstantMs > 0L)
    }

    private var smoothedDegrees: Double? = null
    private var smoothedTimestampMs: Long? = null
    private var holdStartMs: Long? = null

    /** Milliseconds held without a break, or zero when not currently holding. */
    var heldMs: Long = 0L
        private set

    /** The longest completed stretch this detector has seen since the last [invalidate]. */
    var longestHeldMs: Long = 0L
        private set

    val holding: Boolean get() = holdStartMs != null

    fun accept(timestampMs: Long, angleDegrees: Double): HoldEvent {
        val previousTs = smoothedTimestampMs
        if (previousTs != null && timestampMs <= previousTs) {
            invalidate()
            return HoldEvent.None
        }
        val smoothed = smooth(timestampMs, angleDegrees)

        val startMs = holdStartMs
        if (startMs == null) {
            if (smoothed <= enterDegrees) {
                holdStartMs = timestampMs
                heldMs = 0L
                return HoldEvent.Entered
            }
            return HoldEvent.None
        }

        if (smoothed < exitDegrees) {
            heldMs = timestampMs - startMs
            longestHeldMs = maxOf(longestHeldMs, heldMs)
            return HoldEvent.Holding(heldMs)
        }

        val total = timestampMs - startMs
        holdStartMs = null
        heldMs = 0L
        longestHeldMs = maxOf(longestHeldMs, total)
        return HoldEvent.Released(heldMs = total, countedAsHold = total >= minimumHoldMs)
    }

    /** Discards the stretch in flight and the smoothing history without emitting anything. */
    fun invalidate() {
        smoothedDegrees = null
        smoothedTimestampMs = null
        holdStartMs = null
        heldMs = 0L
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
        /** Shorter stretches are reported but not treated as a hold worth naming. */
        const val DEFAULT_MINIMUM_HOLD_MS: Long = 3_000L
        const val DEFAULT_SMOOTHING_TIME_CONSTANT_MS: Long = 200L
    }
}
