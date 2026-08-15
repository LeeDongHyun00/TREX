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
 *
 * A stretch is therefore only ever reported once, by the [HoldEvent.Released] that ends it. The
 * detector deliberately keeps no running best: a "longest hold" accumulated while holding would
 * bank a stretch that abstention later discards, which is the opposite of what the policy
 * promises. A set summary, if one is ever wanted, has to be built from released events.
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

    val holding: Boolean get() = holdStartMs != null

    fun accept(
        timestampMs: Long,
        angleDegrees: Double,
        /**
         * Whether a hold may begin on this frame. The angle alone cannot always say a position is
         * the held one — a standing body and a plank share a straight hip — so the session may
         * veto entry on a frame where a clause it owns says the position is something else. The
         * smoothing history still advances; only the transition into a hold is withheld.
         */
        allowEntry: Boolean = true,
    ): HoldEvent {
        val previousTs = smoothedTimestampMs
        if (previousTs != null && timestampMs <= previousTs) {
            invalidate()
            return HoldEvent.None
        }
        val smoothed = smooth(timestampMs, angleDegrees)

        val startMs = holdStartMs
        if (startMs == null) {
            if (smoothed <= enterDegrees && allowEntry) {
                holdStartMs = timestampMs
                heldMs = 0L
                return HoldEvent.Entered
            }
            return HoldEvent.None
        }

        if (smoothed < exitDegrees) {
            heldMs = timestampMs - startMs
            return HoldEvent.Holding(heldMs)
        }

        val total = timestampMs - startMs
        holdStartMs = null
        heldMs = 0L
        return HoldEvent.Released(heldMs = total, countedAsHold = total >= minimumHoldMs)
    }

    /**
     * Ends the stretch in flight as a normal release, from outside the angle band.
     *
     * For when the position stopped being the held one for a reason the driver angle cannot see
     * — a plank whose body stood up keeps a straight hip, so the band alone would let the hold
     * accrue forever. The stretch up to this moment genuinely happened and is counted by the
     * same rule as a band exit; contrast [invalidate], which is for stretches that were never
     * credibly observed at all.
     */
    fun release(timestampMs: Long): HoldEvent {
        val startMs = holdStartMs ?: return HoldEvent.None
        val total = (timestampMs - startMs).coerceAtLeast(0L)
        holdStartMs = null
        heldMs = 0L
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
