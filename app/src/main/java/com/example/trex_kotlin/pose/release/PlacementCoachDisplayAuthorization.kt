package com.example.trex_kotlin.pose.release

import java.util.Collections

/**
 * Boundary declaration for the display-only placement coach.
 *
 * The coach shows a camera preview, a skeleton overlay and framing guidance. It never states
 * whether a movement was performed well, so it needs no runtime release authorization and takes
 * none. This object exists to say that in one auditable place and to re-check, at class-load time,
 * that it is still shipping next to a facade with nothing released.
 *
 * It deliberately lives in the release package rather than beside the screen. A display track
 * declared outside the release boundary would be a second boundary; declared here, reading the
 * facade it must not contradict, it is a derivation of the existing one.
 *
 * [PostureCorrectionRuntimeFacade] keeps its public surface unchanged. When a display-only
 * lifecycle can be admitted into the facade itself, this object should be absorbed into it and the
 * facade's pinned method set updated in the same commit.
 */
internal object PlacementCoachDisplayAuthorization {

    const val TRACK_ID: String = "trex.display-only.placement-coach.v1"

    /**
     * Every product authority this track could conceivably claim, all withheld.
     *
     * Kept as a map rather than six constants so a test can enumerate them and so no future edit
     * can flip one without the enumeration noticing.
     */
    val grants: Map<String, Boolean> = Collections.unmodifiableMap(
        linkedMapOf(
            "measurement" to false,
            "verdict" to false,
            "score" to false,
            "cue" to false,
            "shadow" to false,
            "release" to false,
        ),
    )

    /** Exercises the product may currently offer for posture correction. Expected to be none. */
    val observedUserSelectableExerciseCount: Int =
        PostureCorrectionRuntimeFacade.userSelectableExercises.size

    /** Criteria authorized to produce a user-visible outcome. Expected to be none. */
    val observedReleasedCriterionCount: Int =
        PostureCorrectionRuntimeFacade.availabilities.sumOf { it.releasedCriterionCount }

    init {
        check(grants.values.none { it }) {
            "The placement coach must not claim any product authority"
        }
        check(observedUserSelectableExerciseCount == 0) {
            "The placement coach must not ship alongside a user-selectable posture release"
        }
        check(observedReleasedCriterionCount == 0) {
            "The placement coach must not ship alongside an authorized criterion"
        }
    }
}
