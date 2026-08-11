package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseFrame
import java.util.Collections
import kotlin.math.roundToInt

/**
 * The heuristic form-check (beta) track.
 *
 * Its contract is `docs/pose-heuristic-form-check.v1.md`, pinned by [POLICY_DOCUMENT_SHA256].
 * The track computes rotation-invariant joint geometry, reports observations in observational
 * language, abstains loudly, and touches nothing in the posture-correction release chain: no
 * facade, no criterion, no stored record. The lunge thresholds are MediaPipe-native fits from
 * the Day05 bridge measurement (research scope, clip-level); the barbell squat stays an
 * uncalibrated heuristic default and, being load-bearing, never urges more depth. None of this
 * amounts to release-chain calibration, which is why every surface carries the beta disclosure
 * and [claims] still withholds `calibrated`.
 */
internal object HeuristicFormCheckDeclaration {

    const val TRACK_ID: String = "trex.heuristic-form-check.beta.v1"

    const val POLICY_DOCUMENT_SHA256: String =
        "e2ed81dd14544ce60a0c75e54e5453dbaf23808a684f6bdd597c2c30bef131c8"

    const val POLICY_DOCUMENT_PATH: String = "docs/pose-heuristic-form-check.v1.md"

    /** Always-on beta disclosure. The user cannot dismiss it. */
    const val BETA_DISCLOSURE: String = "휴리스틱 참고용이에요 · 정식 검증 전"

    // User-facing strings for the workout list live here rather than in the screen file so the
    // language seal scans every word this track ever shows.
    const val TOGGLE_LABEL: String = "자세 체크 베타 · 관찰 안내"
    const val ROW_CAPTION: String = "자세 체크는 휴리스틱 베타 · 정식 판정 준비 중"
    const val PILL_LABEL: String = "자세 체크 베타"

    /** Every claim this track could conceivably make, all withheld. */
    val claims: Map<String, Boolean> = Collections.unmodifiableMap(
        linkedMapOf(
            "calibrated" to false,
            "clinical" to false,
            "usesReleaseChain" to false,
            "storesRecords" to false,
            "influencesTimer" to false,
        ),
    )

    init {
        check(claims.values.none { it }) {
            "The heuristic beta track must not claim any validated authority"
        }
    }
}

/**
 * The knee included angle needs exactly this chain on one side. Declared at file scope because
 * enum entry initialisers run before the companion object exists.
 */
private val LEG_CHAIN: Set<FormCheckJointGroup> = setOf(
    FormCheckJointGroup.HIP,
    FormCheckJointGroup.KNEE,
    FormCheckJointGroup.ANKLE,
)

/** Where a threshold constant came from; mirrored in the policy document's §4 table. */
internal enum class FormCheckThresholdProvenance {
    /** Literature-informed default that has never met calibration data. */
    HEURISTIC_DEFAULT,

    /**
     * Fitted on MediaPipe output over AI Hub Day05 lateral label frames under the research-use
     * rights manifest (`docs/mediapipe-aihub-bridge.v1.json`). Clip-level, IMAGE-mode, studio
     * domain: a beta constant, never release calibration.
     */
    MEDIAPIPE_NATIVE_DAY05_FIT_V1,
}

/**
 * Per-exercise thresholds, mirrored from the policy document's §4 table. 180 degrees is a
 * straight leg; depth gates compare the repetition's lowest raw angle. The label-space fits do
 * not transfer to MediaPipe (the bridge card measures +13 degrees of systematic straightening),
 * so calibrated entries carry the MediaPipe-native value, not the AI Hub one.
 */
internal enum class FormCheckExercise(
    val exercise: AiHubExercise,
    val repDepthDegrees: Double,
    val reachedDepthDegrees: Double,
    val provenance: FormCheckThresholdProvenance,
    /**
     * A load-bearing exercise never urges more depth: an uncalibrated deeper-suggestion under
     * external load is this track's heaviest possible output, so the init contract forbids the
     * hints outright (policy §4.2).
     */
    val loadBearing: Boolean,
    val setupHint: String,
    /**
     * Shown after a shallow attempt, or null to report the observation alone. For the lunges a
     * straight near-side reading genuinely can mean the camera-side leg is the rear one, so
     * their hint addresses the setup before the depth.
     */
    val shallowHint: String?,
    /** Suggestion after a counted rep whose lowest point stayed above the reached line. */
    val deeperHint: String?,
    /** The only joints this exercise needs before it can start. */
    val requiredJoints: Set<FormCheckJointGroup>,
) {
    BARBELL_SQUAT(
        exercise = AiHubExercise.BARBELL_SQUAT,
        repDepthDegrees = 110.0,
        reachedDepthDegrees = 105.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        loadBearing = true,
        setupHint = "옆모습이 보이게 서 주세요",
        shallowHint = null,
        deeperHint = null,
        requiredJoints = LEG_CHAIN,
    ),
    STEP_FORWARD_DYNAMIC_LUNGE(
        exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
        repDepthDegrees = 134.0,
        reachedDepthDegrees = 129.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_DAY05_FIT_V1,
        loadBearing = false,
        setupHint = "카메라 쪽 다리가 앞으로 오게 서 주세요",
        shallowHint = "카메라 쪽 다리가 앞인지 확인하고 조금 더 굽혀볼까요",
        deeperHint = "다음엔 조금 더 앉아볼까요",
        requiredJoints = LEG_CHAIN,
    ),
    STEP_BACKWARD_DYNAMIC_LUNGE(
        exercise = AiHubExercise.STEP_BACKWARD_DYNAMIC_LUNGE,
        repDepthDegrees = 130.0,
        reachedDepthDegrees = 123.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_DAY05_FIT_V1,
        loadBearing = false,
        setupHint = "카메라 쪽 다리가 앞으로 오게 서 주세요",
        shallowHint = "카메라 쪽 다리가 앞인지 확인하고 조금 더 굽혀볼까요",
        deeperHint = "다음엔 조금 더 앉아볼까요",
        requiredJoints = LEG_CHAIN,
    ),
    ;

    init {
        require(reachedDepthDegrees <= repDepthDegrees) {
            "Reached-depth must be at least as deep as the rep threshold"
        }
        require(repDepthDegrees < RepCycleDetector.DEFAULT_ATTEMPT_ENTER_DEGREES) {
            "The rep threshold must stay below the shallow-attempt boundary"
        }
        require(!loadBearing || (shallowHint == null && deeperHint == null)) {
            "A load-bearing exercise must not carry depth-increase suggestions"
        }
        require(requiredJoints.isNotEmpty()) { "An exercise must declare its required joints" }
    }

    companion object {
        fun of(exercise: AiHubExercise): FormCheckExercise? =
            entries.firstOrNull { it.exercise == exercise }

        fun supports(exercise: AiHubExercise): Boolean = of(exercise) != null
    }
}

/** Why the exercise has not started yet, or [STARTED] once it has. */
internal enum class FormCheckStartState {
    /** No camera observation has arrived yet. */
    WAITING_FOR_CAMERA,

    /** The person is not locked, so no measurement can be attributed to anyone. */
    WAITING_FOR_PERSON,

    /** The exercise's own joints are not all observable yet. */
    WAITING_FOR_JOINTS,

    STARTED,
}

/** Immutable snapshot the session UI renders. Counts and text only, never pose data. */
internal class FormCheckUiState internal constructor(
    val repCount: Int,
    /** Excursions reported but not counted, whatever the truthful reason (depth or speed). */
    val uncountedAttemptCount: Int,
    val startState: FormCheckStartState,
    missingJoints: Set<FormCheckJointGroup>,
    /** The side view reads the knee bend more directly; anything else still starts. */
    val sideViewPreferred: Boolean,
    val headline: String?,
    val suggestion: String?,
) {
    val missingJoints: Set<FormCheckJointGroup> =
        Collections.unmodifiableSet(LinkedHashSet(missingJoints.sortedBy { it.ordinal }))

    val started: Boolean
        get() = startState == FormCheckStartState.STARTED

    init {
        require(repCount >= 0)
        require(uncountedAttemptCount >= 0)
        require(startState != FormCheckStartState.STARTED || this.missingJoints.isEmpty()) {
            "A started exercise cannot still be missing joints"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FormCheckUiState) return false
        return repCount == other.repCount &&
            uncountedAttemptCount == other.uncountedAttemptCount &&
            startState == other.startState &&
            missingJoints == other.missingJoints &&
            sideViewPreferred == other.sideViewPreferred &&
            headline == other.headline &&
            suggestion == other.suggestion
    }

    override fun hashCode(): Int {
        var result = repCount
        result = 31 * result + uncountedAttemptCount
        result = 31 * result + startState.hashCode()
        result = 31 * result + missingJoints.hashCode()
        result = 31 * result + sideViewPreferred.hashCode()
        result = 31 * result + (headline?.hashCode() ?: 0)
        result = 31 * result + (suggestion?.hashCode() ?: 0)
        return result
    }
}

/**
 * Pure-Kotlin evaluation session for one workout item.
 *
 * The exercise starts as soon as the person is locked and this exercise's own joints are
 * observable. It deliberately does not wait for a whole-body side-view token: cropped feet on
 * the far side or a head out of frame say nothing about whether a knee angle can be measured,
 * and demanding them left the session waiting forever. The side view is kept as a quality note
 * because it reads the knee bend most directly.
 *
 * Losing the lock or any required joint invalidates the repetition in flight. The output is a
 * count plus observational text — there is no verdict, no score and nothing is persisted.
 */
internal class HeuristicFormCheckSession(
    private val spec: FormCheckExercise,
) {
    private val detector = RepCycleDetector(bottomEnterDegrees = spec.repDepthDegrees)
    private var repCount = 0
    private var uncountedAttemptCount = 0
    private var headline: String? = null
    private var suggestion: String? = null
    private var activeSide: FormCheckBodySide? = null
    private var sideViewPreferred: Boolean = false

    fun accept(
        timestampMs: Long,
        hasPrimaryPersonLock: Boolean,
        lateralViewQualified: Boolean,
        frame: PoseFrame,
    ): FormCheckUiState {
        sideViewPreferred = !lateralViewQualified
        if (!hasPrimaryPersonLock) {
            detector.invalidate()
            return snapshot(FormCheckStartState.WAITING_FOR_PERSON, spec.requiredJoints)
        }
        val readiness = FormCheckGeometry.readiness(frame, spec.requiredJoints)
        if (!readiness.ready) {
            detector.invalidate()
            return snapshot(FormCheckStartState.WAITING_FOR_JOINTS, readiness.missingGroups)
        }
        val sample = selectSample(frame)
        if (sample == null) {
            detector.invalidate()
            return snapshot(FormCheckStartState.WAITING_FOR_JOINTS, spec.requiredJoints)
        }

        when (val event = detector.accept(timestampMs, sample.kneeIncludedAngleDegrees)) {
            is RepCycleEvent.Completed -> {
                repCount += 1
                val minimum = event.minimumAngleDegrees.roundToInt()
                headline = "무릎이 ${minimum}도까지 굽혀졌어요"
                // Null for load-bearing exercises: the observation stands without urging depth.
                suggestion = if (event.minimumAngleDegrees <= spec.reachedDepthDegrees) {
                    null
                } else {
                    spec.deeperHint
                }
            }

            is RepCycleEvent.ShallowAttempt -> {
                uncountedAttemptCount += 1
                headline = "무릎 굽힘이 얕아 횟수로 세지 않았어요"
                suggestion = spec.shallowHint
            }

            is RepCycleEvent.TooFastAttempt -> {
                uncountedAttemptCount += 1
                headline = "동작이 빨라 횟수로 세지 않았어요"
                suggestion = "조금 더 천천히 움직여볼까요"
            }

            RepCycleEvent.None -> Unit
        }
        return snapshot(FormCheckStartState.STARTED, emptySet())
    }

    /**
     * Sticky side selection. The front and rear knees of a lunge are different physical
     * quantities, so a per-frame confidence argmax that flips sides mid-repetition would splice
     * two angle series into one stream and let a far-side burst fake a top crossing — one lunge
     * counted as two. The active side is kept while its chain stays credible; switching sides
     * discards the excursion in flight, consistent with the abstention policy.
     */
    private fun selectSample(frame: PoseFrame): FormCheckKneeSample? {
        val held = activeSide
        if (held != null) {
            val current = FormCheckGeometry.sideSample(frame, held)
            if (current != null) return current
        }
        val fresh = FormCheckGeometry.kneeSample(frame) ?: return null
        if (held != null && fresh.side != held) {
            detector.invalidate()
        }
        activeSide = fresh.side
        return fresh
    }

    fun snapshot(
        startState: FormCheckStartState,
        missingJoints: Set<FormCheckJointGroup>,
    ): FormCheckUiState = FormCheckUiState(
        repCount = repCount,
        uncountedAttemptCount = uncountedAttemptCount,
        startState = startState,
        missingJoints = if (startState == FormCheckStartState.STARTED) emptySet() else missingJoints,
        sideViewPreferred = sideViewPreferred,
        headline = headline,
        suggestion = suggestion,
    )

    /** Snapshot before any observation has arrived. */
    fun initialSnapshot(): FormCheckUiState =
        snapshot(FormCheckStartState.WAITING_FOR_CAMERA, spec.requiredJoints)
}
