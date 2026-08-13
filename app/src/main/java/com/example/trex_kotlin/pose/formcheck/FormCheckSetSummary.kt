package com.example.trex_kotlin.pose.formcheck

import java.util.Collections

/**
 * What one set amounted to, assembled for the rest period.
 *
 * The set is the moment this track can finally be read. During a repetition the phone is metres
 * away and the user is side-on to it, so a sentence on screen reaches nobody; standing still
 * between sets they are close, unloaded and looking. The motor-learning literature the design
 * rests on says the same thing from the other direction — feedback delivered after a bout is
 * retained better than feedback delivered during one — so the detail belongs here and the live
 * surface keeps only what survives at three metres.
 *
 * Everything in here is already-published wording: the observations are the strings the engine
 * built for the headline, so the summary invents no vocabulary of its own.
 *
 * Memory only. The host holds the latest instance for the set in flight and drops it when the set
 * number changes; nothing reaches the workout record, preferences or disk (policy §5-2).
 */
internal class FormCheckSetSummary(
    /** The joint this exercise measured, named so the summary says what it looked at. */
    val measuredJointLabel: String,
    val cadence: FormCheckCadence,
    val repCount: Int,
    /** The longest hold this set reported, in seconds; zero for repetition exercises. */
    val holdSeconds: Int,
    marks: List<FormCheckRepMark>,
    /** The final observation the set produced, for a hold that has no marks to list. */
    val lastObservation: String?,
    /** Where this exercise's angle constants came from, stated plainly. */
    val provenanceNote: String,
    val requiresDataAttribution: Boolean,
) {
    val marks: List<FormCheckRepMark> = Collections.unmodifiableList(ArrayList(marks))

    /** Whether there is anything to say. An unobserved set says so rather than showing zeroes. */
    val hasObservations: Boolean
        get() = repCount > 0 || holdSeconds > 0 || this.marks.isNotEmpty() || lastObservation != null

    /** Excursions the set reported without counting, whatever the truthful reason. */
    val uncountedCount: Int
        get() = this.marks.count { it.kind != FormCheckRepEventKind.COUNTED }

    init {
        require(repCount >= 0)
        require(holdSeconds >= 0)
    }
}
