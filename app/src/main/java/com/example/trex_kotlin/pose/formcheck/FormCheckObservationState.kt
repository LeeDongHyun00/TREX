package com.example.trex_kotlin.pose.formcheck

/**
 * The reading the surface may draw on the body right now, or null while the track abstains.
 *
 * Kept out of [FormCheckUiState] on purpose. The UI state is a discrete snapshot that changes a
 * handful of times per set, and the surface compares it with `!=` before re-rendering; a value
 * that moves every camera frame would defeat that comparison and recompose the whole chip at
 * frame rate. This class travels on its own channel, read only inside a Canvas.
 *
 * Nullability is the abstention contract (policy §3.1). Every path that stops evaluating a frame
 * clears this to null, so a surface that draws it can never leave a stale angle on the body while
 * the camera has lost the joint — the failure the policy calls "판정 불가를 다른 값으로 위장".
 */
internal class FormCheckLiveReading(
    /** The driver chain's included angle this frame, as a real joint angle. */
    val angleDegrees: Double,
    /** The weakest landmark confidence in the chain; the surface fades itself as this falls. */
    val chainConfidence: Double,
    /** Which side the measurement is attributed to, so the surface draws that side's joints. */
    val side: FormCheckBodySide,
    /** Whether an armed excursion is in flight; false between repetitions. */
    val inExcursion: Boolean,
    /** The extreme reached so far inside the excursion in flight, as a real joint angle. */
    val excursionExtremeDegrees: Double?,
) {
    init {
        require(angleDegrees in 0.0..180.0) { "A live reading must be a real joint angle" }
        require(chainConfidence in 0.0..1.0) { "Confidence out of range" }
        require(excursionExtremeDegrees == null || excursionExtremeDegrees in 0.0..180.0) {
            "An excursion extreme must be a real joint angle"
        }
    }
}

/** Why an excursion did or did not become a repetition. Mirrors [RepCycleEvent]. */
internal enum class FormCheckRepEventKind {
    COUNTED,

    /** Turned back before the rep line. */
    SHALLOW,

    /** Reached the rep line but returned faster than a repetition can be. */
    TOO_FAST,

    /**
     * The measured side completed a full excursion while the opposite side, concurrently
     * observed, visibly did not travel with it. On a two-sided exercise that is not a
     * repetition — a knee raised in front of a squat counter bends one knee exactly like a
     * squat does, and only the still leg gives it away.
     */
    ASYMMETRIC,
}

/**
 * How a repetition sat against the set's own opening repetitions.
 *
 * Three values, never a continuous quantity. Policy §4.4 silences differences under fifteen
 * degrees because a same-set self-comparison cancels the systematic straightening the bridge card
 * measured but leaves its random part unmeasured. A surface that mapped the extreme onto a
 * continuous height would redraw exactly the differences that clause refuses to speak, so the
 * quantisation happens here, where the policy floor already lives, rather than in the drawing code.
 */
internal enum class FormCheckBaselineRelation {
    /** No baseline yet, or a difference inside what the measurement could have invented. */
    SAME,

    /** Less work than the set's opening repetitions. */
    BELOW,

    /** More work than the set's opening repetitions. */
    BEYOND,
}

/**
 * One completed excursion, kept so the set's own history is visible without a sentence.
 *
 * The marks exist only inside the session that produced them: the surface remembers the session
 * per set, so a new set discards them, and there is no path to disk, preferences or a record
 * (policy §5-2). [observation] is the same string the headline carries, assembled by the same
 * code — the mark introduces no new user-facing wording of its own.
 */
internal class FormCheckRepMark(
    val kind: FormCheckRepEventKind,
    /** The excursion's extreme, as a real joint angle the user could be told. */
    val extremeDegrees: Int,
    val baselineRelation: FormCheckBaselineRelation,
    /** The guard chain's window extreme, present only when it crossed and was observed. */
    val guardDegrees: Int?,
    val observation: String,
) {
    init {
        require(extremeDegrees in 0..180) { "A mark's extreme must be a real joint angle" }
        require(guardDegrees == null || guardDegrees in 0..180) {
            "A guard reading must be a real joint angle"
        }
        require(observation.isNotBlank()) { "A mark must carry the observation it was reported with" }
        // An uncounted excursion has no baseline comparison to make: the comparison is defined
        // over completed repetitions, and drawing one for a shallow attempt would compare a
        // movement with repetitions it never joined.
        require(kind == FormCheckRepEventKind.COUNTED || baselineRelation == FormCheckBaselineRelation.SAME) {
            "Only a counted repetition carries a baseline relation"
        }
    }
}
