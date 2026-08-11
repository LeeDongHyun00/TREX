package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.camera.PoseObserverTrackingStatus
import com.example.trex_kotlin.camera.PoseObserverUnknownReason
import java.util.Collections

/**
 * Placement target the coach is currently guiding toward.
 *
 * The two goals mirror the only affirmative view tokens the observer ever mints. Stage one is
 * about framing, stage two is about body orientation.
 */
internal enum class PlacementCoachGoal {
    FULL_BODY,
    LATERAL,
}

/** Camera lifecycle as the coach sees it. It says nothing about the person in frame. */
internal enum class PlacementCameraState {
    STARTING,
    RUNNING,
    UNAVAILABLE,
}

/**
 * Coarse coaching state. None of these describe how well an exercise is performed; they describe
 * what the camera can currently observe.
 */
internal enum class PlacementCoachStage {
    CAMERA_UNAVAILABLE,
    CAMERA_STARTING,
    NO_PERSON,
    ADJUSTING,
    HOLDING,
    REACHED,
}

/**
 * User-visible guidance. Every message states an observation about the camera or the screen and
 * never an assessment of the person's movement.
 *
 * The strings live here rather than in a composable because this module has no Compose test
 * runner; putting them in an enum is the only place a unit test can read the exact text a user
 * will see.
 */
internal enum class PlacementCoachGuidance(
    val headline: String,
    val detail: String,
) {
    WAIT_FOR_CAMERA(
        headline = "카메라를 준비하고 있어요",
        detail = "잠시만 기다려 주세요",
    ),
    CAMERA_BLOCKED(
        headline = "카메라를 사용할 수 없어요",
        detail = "권한과 기기 상태를 확인해 주세요",
    ),
    STEP_INTO_FRAME(
        headline = "화면 안에 서 주세요",
        detail = "머리부터 발끝까지 보이는 자리에 서 주세요",
    ),
    ONLY_ONE_PERSON(
        headline = "화면에 한 사람만 보이게 해 주세요",
        detail = "여러 사람이 보이면 누구를 볼지 정할 수 없어요",
    ),
    FIT_WHOLE_BODY(
        headline = "전신이 화면 안에 들어와야 해요",
        detail = "머리와 발끝이 잘리지 않게 서 주세요",
    ),
    MOVE_FARTHER(
        headline = "조금 더 멀어져 주세요",
        detail = "화면에 비해 몸이 너무 크게 보여요",
    ),
    MOVE_CLOSER(
        headline = "조금 더 가까이 와 주세요",
        detail = "화면에 비해 몸이 너무 작게 보여요",
    ),
    IMPROVE_VISIBILITY(
        headline = "몸이 잘 보이게 해 주세요",
        detail = "주변을 밝게 하고 가리는 물건을 치워 주세요",
    ),
    HOLD_DEVICE_STILL(
        headline = "기기를 고정해 주세요",
        detail = "카메라가 움직이면 처음부터 다시 찾아요",
    ),
    HOLD_STILL(
        headline = "잠시 그대로 있어 주세요",
        detail = "화면에서 사람을 찾는 중이에요",
    ),
    KEEP_BODY_FACING_STEADY(
        headline = "몸의 방향을 유지해 주세요",
        detail = "방향이 바뀌면 처음부터 다시 확인해요",
    ),
    TURN_SIDEWAYS(
        headline = "카메라가 옆모습을 보도록 서 주세요",
        detail = "몸을 한쪽으로 돌려 주세요",
    ),
    FULL_BODY_REACHED(
        headline = "카메라가 전신을 안정적으로 보고 있어요",
        detail = "이어서 옆모습 배치를 확인해 볼까요",
    ),
    LATERAL_REACHED(
        headline = "카메라가 옆모습을 안정적으로 보고 있어요",
        detail = "배치 확인이 끝났어요",
    ),
}

/**
 * One frame of observer diagnostics, reduced to what the coach may consume.
 *
 * The constructor deliberately validates almost nothing. The observer's own invariants already
 * couple these fields, but a display-only surface must never crash a user session by asserting a
 * combination the runtime turns out to emit. [PlacementCoachDisplayPolicy.resolve] re-checks every
 * condition it depends on instead.
 */
internal class PlacementObservedSignal(
    val trackingStatus: PoseObserverTrackingStatus,
    unknownReasons: Set<PoseObserverUnknownReason>,
    val hasPrimaryPersonLock: Boolean,
    val fullBodyViewQualified: Boolean,
    val lateralViewQualified: Boolean,
    val candidateCount: Int,
) {
    val unknownReasons: Set<PoseObserverUnknownReason> =
        Collections.unmodifiableSet(LinkedHashSet(unknownReasons.sortedBy { it.name }))

    init {
        require(candidateCount >= 0) { "Candidate count must not be negative" }
    }
}

/**
 * Immutable coaching output for one frame.
 *
 * This is not a data class on purpose. A generated `copy()` would let any call site in the module
 * mint a [PlacementCoachStage.REACHED] display without going through [PlacementCoachDisplayPolicy].
 */
internal class PlacementCoachDisplay internal constructor(
    val goal: PlacementCoachGoal,
    val stage: PlacementCoachStage,
    val guidance: PlacementCoachGuidance,
    val skeletonVisible: Boolean,
    suppressedReasons: Set<PoseObserverUnknownReason>,
) {
    /**
     * Reasons that were present while the goal was already met. They exist for the in-memory
     * attempt recorder and must never be rendered.
     */
    val suppressedReasons: Set<PoseObserverUnknownReason> =
        Collections.unmodifiableSet(LinkedHashSet(suppressedReasons.sortedBy { it.name }))

    val goalReached: Boolean
        get() = stage == PlacementCoachStage.REACHED

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlacementCoachDisplay) return false
        return goal == other.goal &&
            stage == other.stage &&
            guidance == other.guidance &&
            skeletonVisible == other.skeletonVisible &&
            suppressedReasons == other.suppressedReasons
    }

    override fun hashCode(): Int {
        var result = goal.hashCode()
        result = 31 * result + stage.hashCode()
        result = 31 * result + guidance.hashCode()
        result = 31 * result + skeletonVisible.hashCode()
        result = 31 * result + suppressedReasons.hashCode()
        return result
    }

    override fun toString(): String =
        "PlacementCoachDisplay(goal=$goal, stage=$stage, guidance=$guidance, " +
            "skeletonVisible=$skeletonVisible, suppressedReasons=$suppressedReasons)"
}

/**
 * The display-only placement track.
 *
 * Its contract is written down in `docs/pose-nonverdict-display-policy.v1.md` and pinned by
 * [POLICY_DOCUMENT_SHA256]. Editing the document without updating the constant in the same commit
 * makes the governance test red.
 */
internal object PlacementCoachDisplayPolicy {

    /** SHA-256 of the LF-normalised policy document that governs this track. */
    const val POLICY_DOCUMENT_SHA256: String =
        "0afdaa25feb035425a1ad079bee46f975408fd1ec9091341c2665aa576078cdd"

    const val POLICY_DOCUMENT_PATH: String = "docs/pose-nonverdict-display-policy.v1.md"

    /** Always-on banner required by the policy document. The user cannot dismiss it. */
    const val NON_VERDICT_DISCLOSURE: String =
        "이 화면은 자세를 평가하지 않습니다. 카메라 배치만 안내합니다."

    /**
     * Reasons that ride along with a perfectly acceptable placement and must never surface.
     *
     * The observer mints the full-body token from the framing gate alone and does not revoke it in
     * the later orientation branch, so a front-facing person always carries FRONT_REAR_UNRESOLVED
     * and an oblique one always carries VIEW_AMBIGUOUS.
     */
    val NEVER_DISPLAYED: Set<PoseObserverUnknownReason> = Collections.unmodifiableSet(
        linkedSetOf(
            PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED,
            PoseObserverUnknownReason.VIEW_AMBIGUOUS,
        ),
    )

    /**
     * Display order for the remaining reasons, following the observer pipeline's causal order:
     * candidate exists, candidate is unique, framing geometry, landmark quality, temporal settling.
     *
     * Guidance drawn from a later stage is unactionable while an earlier one is unresolved. The
     * observer sorts its reason set by name, which is not a severity order, so this list — never
     * the set's iteration order — decides what the user reads.
     */
    val DISPLAY_PRIORITY: List<PoseObserverUnknownReason> = Collections.unmodifiableList(
        listOf(
            PoseObserverUnknownReason.PERSON_NOT_FOUND,
            PoseObserverUnknownReason.PERSON_AMBIGUOUS,
            PoseObserverUnknownReason.BODY_OUT_OF_FRAME,
            PoseObserverUnknownReason.BODY_TOO_LARGE,
            PoseObserverUnknownReason.BODY_TOO_SMALL,
            PoseObserverUnknownReason.REQUIRED_LANDMARK_MISSING,
            PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE,
            PoseObserverUnknownReason.CAMERA_GEOMETRY_DISCONTINUITY,
            PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE,
            PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY,
            PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING,
            PoseObserverUnknownReason.VIEW_QUALIFICATION_STABILIZING,
        ),
    )

    init {
        val classified = DISPLAY_PRIORITY.toSet() + NEVER_DISPLAYED
        check(DISPLAY_PRIORITY.size == DISPLAY_PRIORITY.toSet().size) {
            "Display priority must not repeat a reason"
        }
        check(DISPLAY_PRIORITY.none { it in NEVER_DISPLAYED }) {
            "A reason cannot be both prioritised and suppressed"
        }
        check(classified.size == PoseObserverUnknownReason.entries.size) {
            "Every observer reason must be classified exactly once"
        }
    }

    /** Guidance shown before the first observation of a session arrives. */
    fun initial(goal: PlacementCoachGoal): PlacementCoachDisplay = PlacementCoachDisplay(
        goal = goal,
        stage = PlacementCoachStage.CAMERA_STARTING,
        guidance = PlacementCoachGuidance.WAIT_FOR_CAMERA,
        skeletonVisible = false,
        suppressedReasons = emptySet(),
    )

    /**
     * Turn one frame of diagnostics into one piece of guidance.
     *
     * Reaching a goal is decided by view tokens alone. A reached placement may still carry
     * REQUIRED_LANDMARK_LOW_CONFIDENCE, which the observer raises for *rejected other candidates*
     * rather than the tracked person; treating any leftover reason as blocking would mean a
     * bystander at the edge of frame keeps the user from ever reaching the goal.
     */
    fun resolve(
        goal: PlacementCoachGoal,
        cameraState: PlacementCameraState,
        observed: PlacementObservedSignal?,
    ): PlacementCoachDisplay {
        if (cameraState == PlacementCameraState.UNAVAILABLE) {
            return display(goal, PlacementCoachStage.CAMERA_UNAVAILABLE, PlacementCoachGuidance.CAMERA_BLOCKED)
        }
        if (cameraState == PlacementCameraState.STARTING || observed == null) {
            return display(goal, PlacementCoachStage.CAMERA_STARTING, PlacementCoachGuidance.WAIT_FOR_CAMERA)
        }

        val skeletonVisible = observed.trackingStatus in SKELETON_VISIBLE_STATUSES
        val framed = observed.trackingStatus == PoseObserverTrackingStatus.TRACKED &&
            observed.hasPrimaryPersonLock &&
            observed.fullBodyViewQualified
        val reached = framed && (goal == PlacementCoachGoal.FULL_BODY || observed.lateralViewQualified)

        if (reached) {
            return PlacementCoachDisplay(
                goal = goal,
                stage = PlacementCoachStage.REACHED,
                guidance = when (goal) {
                    PlacementCoachGoal.FULL_BODY -> PlacementCoachGuidance.FULL_BODY_REACHED
                    PlacementCoachGoal.LATERAL -> PlacementCoachGuidance.LATERAL_REACHED
                },
                skeletonVisible = skeletonVisible,
                suppressedReasons = observed.unknownReasons,
            )
        }

        // Framing is already settled and only the orientation is missing. The reasons that remain
        // here are suppressed ones, so the priority scan below would fall through to a generic
        // "hold still" that gives the user nothing to act on.
        if (framed && goal == PlacementCoachGoal.LATERAL) {
            return display(goal, PlacementCoachStage.ADJUSTING, PlacementCoachGuidance.TURN_SIDEWAYS, skeletonVisible)
        }

        val leading = DISPLAY_PRIORITY.firstOrNull { it in observed.unknownReasons }
            ?: return display(goal, PlacementCoachStage.HOLDING, PlacementCoachGuidance.HOLD_STILL, skeletonVisible)

        val stage = when (leading) {
            PoseObserverUnknownReason.PERSON_NOT_FOUND -> PlacementCoachStage.NO_PERSON
            PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE,
            PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY,
            PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING,
            PoseObserverUnknownReason.VIEW_QUALIFICATION_STABILIZING,
            -> PlacementCoachStage.HOLDING
            else -> PlacementCoachStage.ADJUSTING
        }
        return display(goal, stage, guidanceFor(leading), skeletonVisible)
    }

    private fun guidanceFor(reason: PoseObserverUnknownReason): PlacementCoachGuidance = when (reason) {
        PoseObserverUnknownReason.PERSON_NOT_FOUND -> PlacementCoachGuidance.STEP_INTO_FRAME
        PoseObserverUnknownReason.PERSON_AMBIGUOUS -> PlacementCoachGuidance.ONLY_ONE_PERSON
        PoseObserverUnknownReason.BODY_OUT_OF_FRAME -> PlacementCoachGuidance.FIT_WHOLE_BODY
        PoseObserverUnknownReason.BODY_TOO_LARGE -> PlacementCoachGuidance.MOVE_FARTHER
        PoseObserverUnknownReason.BODY_TOO_SMALL -> PlacementCoachGuidance.MOVE_CLOSER
        PoseObserverUnknownReason.REQUIRED_LANDMARK_MISSING,
        PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE,
        -> PlacementCoachGuidance.IMPROVE_VISIBILITY
        PoseObserverUnknownReason.CAMERA_GEOMETRY_DISCONTINUITY -> PlacementCoachGuidance.HOLD_DEVICE_STILL
        PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE,
        PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY,
        PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING,
        -> PlacementCoachGuidance.HOLD_STILL
        PoseObserverUnknownReason.VIEW_QUALIFICATION_STABILIZING -> PlacementCoachGuidance.KEEP_BODY_FACING_STEADY
        PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED,
        PoseObserverUnknownReason.VIEW_AMBIGUOUS,
        -> error("Suppressed reason must never select guidance")
    }

    private fun display(
        goal: PlacementCoachGoal,
        stage: PlacementCoachStage,
        guidance: PlacementCoachGuidance,
        skeletonVisible: Boolean = false,
    ): PlacementCoachDisplay = PlacementCoachDisplay(
        goal = goal,
        stage = stage,
        guidance = guidance,
        skeletonVisible = skeletonVisible,
        suppressedReasons = emptySet(),
    )

    private val SKELETON_VISIBLE_STATUSES = setOf(
        PoseObserverTrackingStatus.ACQUIRING,
        PoseObserverTrackingStatus.TRACKED,
        PoseObserverTrackingStatus.TRACK_DISCONTINUITY,
    )
}
