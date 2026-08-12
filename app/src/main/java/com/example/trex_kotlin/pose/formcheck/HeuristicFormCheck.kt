package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseFrame
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The heuristic form-check (beta) track.
 *
 * Its contract is `docs/pose-heuristic-form-check.v1.md`, pinned by [POLICY_DOCUMENT_SHA256].
 * The track computes rotation-invariant joint geometry, reports observations in observational
 * language, abstains loudly, and touches nothing in the posture-correction release chain: no
 * facade, no criterion, no stored record. The two dynamic lunges carry MediaPipe-native fits from
 * the Day05 bridge measurement (research scope, clip-level); every other exercise is an
 * uncalibrated heuristic default, and those whose overshoot has a real consequence are sealed
 * against urging more range. None of this amounts to release-chain calibration, which is why
 * every surface carries the beta disclosure and [claims] still withholds `calibrated`.
 */
internal object HeuristicFormCheckDeclaration {

    const val TRACK_ID: String = "trex.heuristic-form-check.beta.v1"

    const val POLICY_DOCUMENT_SHA256: String =
        "8095f19e28e60f090fc297a295605db3e02bbe70a2460ffcaed64f72a655c079"

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
 * Which way the measured angle travels as the movement does its work.
 *
 * A squat, a push-up and a curl all flex: the angle falls. A hip thrust, an overhead press and a
 * triceps push-down extend: the angle rises, and their resting position is the flexed one. The
 * repetition detector only understands "falls to work", so extension exercises are mirrored into
 * its space; every threshold in the policy table stays written as a real joint angle.
 */
internal enum class FormCheckWorkingDirection(
    /** Verb for a completed repetition's extreme. */
    val reachedVerb: String,
    /** Noun phrase for an excursion that never reached the rep line. */
    val shortfallPhrase: String,
    /** How a repetition compares with the set's own opening reps, short of and beyond it. */
    val belowBaselinePhrase: String,
    val beyondBaselinePhrase: String,
) {
    FLEXION(
        reachedVerb = "굽혀졌어요",
        shortfallPhrase = "굽힘이 얕아",
        belowBaselinePhrase = "얕아요",
        beyondBaselinePhrase = "깊어요",
    ),
    EXTENSION(
        reachedVerb = "펴졌어요",
        shortfallPhrase = "폄이 부족해",
        belowBaselinePhrase = "덜 펴졌어요",
        beyondBaselinePhrase = "더 펴졌어요",
    ),
}

/** Whether the exercise repeats a movement or holds a position. */
internal enum class FormCheckCadence {
    /** Counted in repetitions: an excursion away from rest and back. */
    REPETITION,

    /** Counted in seconds: a position entered and maintained, like a plank. */
    HOLD,
}

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
 * Per-exercise thresholds, mirrored from the policy document's §4 table.
 *
 * Every angle here is a real joint angle: 180 degrees is a straight chain. Flexion exercises work
 * downward from [restAngleDegrees] and extension exercises work upward from it; the session
 * mirrors the extension ones into the detector's space rather than letting the table misstate
 * which angle a user's hip actually reaches.
 *
 * The label-space fits do not transfer to MediaPipe (the bridge card measures +13 degrees of
 * systematic straightening), so calibrated entries carry the MediaPipe-native value.
 */
internal enum class FormCheckExercise(
    val exercise: AiHubExercise,
    /** Which three-joint chain this exercise reads. Determines its required joints. */
    val driver: FormCheckDriver,
    val direction: FormCheckWorkingDirection,
    val cadence: FormCheckCadence,
    /**
     * For a repetition, the resting angle it must return to before another can be armed. For a
     * hold, the angle at which the held position counts as lost.
     */
    val restAngleDegrees: Double,
    /** Passing this arms an excursion; turning back before the rep line reports an attempt. */
    val attemptAngleDegrees: Double,
    /**
     * For a repetition, the extreme it has to reach to be counted. For a hold, the angle that
     * enters the held position.
     */
    val repAngleDegrees: Double,
    /** The extreme beyond which the range needs no suggestion. */
    val reachedAngleDegrees: Double,
    val provenance: FormCheckThresholdProvenance,
    /**
     * A sealed exercise never urges more range. An uncalibrated range-increase suggestion is this
     * track's heaviest possible output wherever overshooting has a real consequence — an external
     * load on the spine, or a joint already at end range — so the init contract forbids the hints
     * outright rather than trusting each entry to omit them (policy §4.2).
     */
    val rangeUrgingSealed: Boolean,
    val setupHint: String,
    /**
     * Shown after an uncounted attempt, or null to report the observation alone. For the lunges a
     * straight near-side reading genuinely can mean the camera-side leg is the rear one, so their
     * hint addresses the setup before the range.
     */
    val attemptHint: String?,
    /** Suggestion after a counted rep whose extreme stopped short of [reachedAngleDegrees]. */
    val rangeHint: String?,
) {
    BARBELL_SQUAT(
        exercise = AiHubExercise.BARBELL_SQUAT,
        driver = FormCheckDriver.KNEE,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 110.0,
        reachedAngleDegrees = 105.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = true,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    STEP_FORWARD_DYNAMIC_LUNGE(
        exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
        driver = FormCheckDriver.KNEE,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 134.0,
        reachedAngleDegrees = 129.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_DAY05_FIT_V1,
        rangeUrgingSealed = false,
        setupHint = "카메라 쪽 다리가 앞으로 오게 서 주세요",
        attemptHint = "카메라 쪽 다리가 앞인지 확인하고 조금 더 굽혀볼까요",
        rangeHint = "다음엔 조금 더 앉아볼까요",
    ),
    STEP_BACKWARD_DYNAMIC_LUNGE(
        exercise = AiHubExercise.STEP_BACKWARD_DYNAMIC_LUNGE,
        driver = FormCheckDriver.KNEE,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 130.0,
        reachedAngleDegrees = 123.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_DAY05_FIT_V1,
        rangeUrgingSealed = false,
        setupHint = "카메라 쪽 다리가 앞으로 오게 서 주세요",
        attemptHint = "카메라 쪽 다리가 앞인지 확인하고 조금 더 굽혀볼까요",
        rangeHint = "다음엔 조금 더 앉아볼까요",
    ),

    // Wave 1. Uncalibrated: the constants borrow the dynamic-lunge fit's shape as the only
    // available prior for MediaPipe's straightening bias, which is why none of them claims
    // provenance beyond HEURISTIC_DEFAULT.
    BARBELL_LUNGE(
        exercise = AiHubExercise.BARBELL_LUNGE,
        driver = FormCheckDriver.KNEE,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 134.0,
        reachedAngleDegrees = 129.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = true,
        setupHint = "카메라 쪽 다리가 앞으로 오게 서 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    STANDING_KNEE_UP(
        exercise = AiHubExercise.STANDING_KNEE_UP,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        reachedAngleDegrees = 125.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 무릎을 조금 더 올려볼까요",
        rangeHint = "다음엔 무릎을 조금 더 올려볼까요",
    ),
    GOOD_MORNING(
        exercise = AiHubExercise.GOOD_MORNING,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        reachedAngleDegrees = 128.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = true,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    PUSH_UP(
        exercise = AiHubExercise.PUSH_UP,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        reachedAngleDegrees = 125.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 엎드려 주세요",
        attemptHint = "다음엔 조금 더 내려가 볼까요",
        rangeHint = "다음엔 조금 더 내려가 볼까요",
    ),

    // Wave 2, flexion. Same elbow chain as the push-up, so these are threshold rows rather than
    // new machinery. Dips is sealed despite being bodyweight: the shoulder is at end range at
    // the bottom, which is the other half of §4.2's criterion.
    KNEE_PUSH_UP(
        exercise = AiHubExercise.KNEE_PUSH_UP,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        reachedAngleDegrees = 125.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 엎드려 주세요",
        attemptHint = "다음엔 조금 더 내려가 볼까요",
        rangeHint = "다음엔 조금 더 내려가 볼까요",
    ),
    DIPS(
        exercise = AiHubExercise.DIPS,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        reachedAngleDegrees = 125.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = true,
        setupHint = "옆모습이 보이게 자세를 잡아 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    BARBELL_CURL(
        exercise = AiHubExercise.BARBELL_CURL,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 120.0,
        reachedAngleDegrees = 100.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 조금 더 감아올려 볼까요",
        rangeHint = "다음엔 조금 더 감아올려 볼까요",
    ),
    DUMBBELL_CURL(
        exercise = AiHubExercise.DUMBBELL_CURL,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 120.0,
        reachedAngleDegrees = 100.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 조금 더 감아올려 볼까요",
        rangeHint = "다음엔 조금 더 감아올려 볼까요",
    ),
    LAT_PULLDOWN(
        exercise = AiHubExercise.LAT_PULLDOWN,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 130.0,
        reachedAngleDegrees = 115.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 앉아 주세요",
        attemptHint = "다음엔 조금 더 당겨볼까요",
        rangeHint = "다음엔 조금 더 당겨볼까요",
    ),

    // Wave 2, extension. Their rest position is the flexed one, so every threshold reads the
    // other way round and the session mirrors them before the detector sees them.
    HIP_THRUST(
        exercise = AiHubExercise.HIP_THRUST,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.EXTENSION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 110.0,
        attemptAngleDegrees = 130.0,
        repAngleDegrees = 145.0,
        reachedAngleDegrees = 160.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = true,
        setupHint = "옆모습이 보이게 자세를 잡아 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    OVERHEAD_PRESS(
        exercise = AiHubExercise.OVERHEAD_PRESS,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.EXTENSION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 100.0,
        attemptAngleDegrees = 120.0,
        repAngleDegrees = 150.0,
        reachedAngleDegrees = 165.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = true,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    CABLE_PUSH_DOWN(
        exercise = AiHubExercise.CABLE_PUSH_DOWN,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.EXTENSION,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 100.0,
        attemptAngleDegrees = 120.0,
        repAngleDegrees = 150.0,
        reachedAngleDegrees = 165.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 팔을 조금 더 펴볼까요",
        rangeHint = "다음엔 팔을 조금 더 펴볼까요",
    ),

    // Isometric. The thresholds read as a band rather than an excursion: the hold begins once
    // the body straightens past the rep angle and ends once it sags back past the rest angle.
    PLANK(
        exercise = AiHubExercise.PLANK,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.EXTENSION,
        cadence = FormCheckCadence.HOLD,
        restAngleDegrees = 145.0,
        attemptAngleDegrees = 152.0,
        repAngleDegrees = 160.0,
        reachedAngleDegrees = 160.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 엎드려 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    ;

    /** The only joints this exercise needs before it can start. */
    val requiredJoints: Set<FormCheckJointGroup> get() = driver.requiredJoints

    /**
     * Mirrors an angle into the detector's space, where a smaller number always means more work.
     * Flexion passes through; extension reflects about a straight chain.
     */
    fun toDetector(angleDegrees: Double): Double = when (direction) {
        FormCheckWorkingDirection.FLEXION -> angleDegrees
        FormCheckWorkingDirection.EXTENSION -> 180.0 - angleDegrees
    }

    /** Converts a detector-space value back to the joint angle a user could be told. */
    fun fromDetector(value: Double): Double = toDetector(value)

    init {
        for (angle in listOf(
            restAngleDegrees,
            attemptAngleDegrees,
            repAngleDegrees,
            reachedAngleDegrees,
        )) {
            require(angle in 0.0..180.0) { "Every threshold must be a real joint angle" }
        }
        require(toDetector(repAngleDegrees) < toDetector(attemptAngleDegrees)) {
            "The rep threshold must sit past the attempt boundary in the working direction"
        }
        require(toDetector(attemptAngleDegrees) < toDetector(restAngleDegrees)) {
            "The attempt boundary must sit past the resting angle in the working direction"
        }
        require(toDetector(reachedAngleDegrees) <= toDetector(repAngleDegrees)) {
            "The reached line must be at least as far as the rep threshold"
        }
        require(!rangeUrgingSealed || (attemptHint == null && rangeHint == null)) {
            "A sealed exercise must not carry range-increase suggestions"
        }
        // A hold has no excursion to fall short of, so an attempt or range hint would describe
        // something the isometric path never reports.
        require(cadence != FormCheckCadence.HOLD || (attemptHint == null && rangeHint == null)) {
            "A hold must not carry repetition suggestions"
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
    /**
     * The side view reads this exercise's own driver joint most directly; anything else still
     * starts, with the view offered as a quality note rather than a gate.
     */
    val sideViewPreferred: Boolean,
    val headline: String?,
    val suggestion: String?,
    /** Seconds of the hold currently in progress; zero for repetition exercises. */
    val holdSeconds: Int = 0,
) {
    val missingJoints: Set<FormCheckJointGroup> =
        Collections.unmodifiableSet(LinkedHashSet(missingJoints.sortedBy { it.ordinal }))

    val started: Boolean
        get() = startState == FormCheckStartState.STARTED

    init {
        require(repCount >= 0)
        require(uncountedAttemptCount >= 0)
        require(holdSeconds >= 0)
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
            suggestion == other.suggestion &&
            holdSeconds == other.holdSeconds
    }

    override fun hashCode(): Int {
        var result = repCount
        result = 31 * result + uncountedAttemptCount
        result = 31 * result + startState.hashCode()
        result = 31 * result + missingJoints.hashCode()
        result = 31 * result + sideViewPreferred.hashCode()
        result = 31 * result + (headline?.hashCode() ?: 0)
        result = 31 * result + (suggestion?.hashCode() ?: 0)
        result = 31 * result + holdSeconds
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
    private val detector = if (spec.cadence == FormCheckCadence.REPETITION) {
        RepCycleDetector(
            bottomEnterDegrees = spec.toDetector(spec.repAngleDegrees),
            attemptEnterDegrees = spec.toDetector(spec.attemptAngleDegrees),
            topEnterDegrees = spec.toDetector(spec.restAngleDegrees),
        )
    } else {
        null
    }

    private val holdDetector = if (spec.cadence == FormCheckCadence.HOLD) {
        HoldDetector(
            enterDegrees = spec.toDetector(spec.repAngleDegrees),
            exitDegrees = spec.toDetector(spec.restAngleDegrees),
        )
    } else {
        null
    }
    private var repCount = 0
    private var uncountedAttemptCount = 0
    private var headline: String? = null
    private var suggestion: String? = null
    private var activeSide: FormCheckBodySide? = null
    private var sideViewPreferred: Boolean = false

    /**
     * Detector-space extremes of this set's opening repetitions, used as the set's own baseline.
     *
     * Comparing a repetition with the user's earlier ones rather than with a population constant
     * is the one comparison this track can make honestly: the thresholds are uncalibrated, but a
     * self-comparison inside one set shares a camera, a body and a systematic bias, so the bias
     * cancels. Only the random part does not, which is why [BASELINE_NOTICEABLE_DEGREES] is well
     * clear of a difference the measurement could invent.
     */
    private val baselineSamples = ArrayList<Double>(BASELINE_REPETITIONS)

    /** "무릎이", "엉덩이가", "팔꿈치가" — the observation names whichever joint it measured. */
    private val vertexSubject: String = spec.driver.vertex.label.let { label ->
        label + FormCheckStartAnnouncer.subjectParticle(label)
    }

    fun accept(
        timestampMs: Long,
        hasPrimaryPersonLock: Boolean,
        lateralViewQualified: Boolean,
        frame: PoseFrame,
    ): FormCheckUiState {
        sideViewPreferred = !lateralViewQualified
        if (!hasPrimaryPersonLock) {
            invalidateDetectors()
            return snapshot(FormCheckStartState.WAITING_FOR_PERSON, spec.requiredJoints)
        }
        val readiness = FormCheckGeometry.readiness(frame, spec.requiredJoints)
        if (!readiness.ready) {
            invalidateDetectors()
            return snapshot(FormCheckStartState.WAITING_FOR_JOINTS, readiness.missingGroups)
        }
        val sample = selectSample(frame)
        if (sample == null) {
            invalidateDetectors()
            return snapshot(FormCheckStartState.WAITING_FOR_JOINTS, spec.requiredJoints)
        }

        val detectorValue = spec.toDetector(sample.includedAngleDegrees)
        holdDetector?.let { hold ->
            when (val event = hold.accept(timestampMs, detectorValue)) {
                HoldEvent.Entered -> {
                    headline = "자세를 잡았어요"
                    suggestion = null
                }

                is HoldEvent.Holding -> {
                    headline = "${(event.heldMs / 1_000L)}초째 유지하고 있어요"
                    suggestion = null
                }

                is HoldEvent.Released -> {
                    headline = if (event.countedAsHold) {
                        "${(event.heldMs / 1_000L)}초 유지했어요"
                    } else {
                        "자세가 잠깐 풀렸어요"
                    }
                    suggestion = null
                }

                HoldEvent.None -> Unit
            }
            return snapshot(FormCheckStartState.STARTED, emptySet())
        }

        when (val event = requireNotNull(detector).accept(timestampMs, detectorValue)) {
            is RepCycleEvent.Completed -> {
                repCount += 1
                val extreme = spec.fromDetector(event.minimumAngleDegrees)
                val observed =
                    "$vertexSubject ${extreme.roundToInt()}도까지 ${spec.direction.reachedVerb}"
                headline = baselineNote(event.minimumAngleDegrees)
                    ?.let { note -> "$observed · $note" }
                    ?: observed
                recordBaseline(event.minimumAngleDegrees)
                // Null for sealed exercises: the observation stands without urging more range.
                suggestion = if (
                    event.minimumAngleDegrees <= spec.toDetector(spec.reachedAngleDegrees)
                ) {
                    null
                } else {
                    spec.rangeHint
                }
            }

            is RepCycleEvent.ShallowAttempt -> {
                uncountedAttemptCount += 1
                headline = "${spec.driver.vertex.label} ${spec.direction.shortfallPhrase} " +
                    "횟수로 세지 않았어요"
                suggestion = spec.attemptHint
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
    private fun selectSample(frame: PoseFrame): FormCheckAngleSample? {
        val held = activeSide
        if (held != null) {
            val current = FormCheckGeometry.sideSample(frame, held, spec.driver)
            if (current != null) return current
        }
        val fresh = FormCheckGeometry.sample(frame, spec.driver) ?: return null
        if (held != null && fresh.side != held) {
            invalidateDetectors()
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
        holdSeconds = ((holdDetector?.heldMs ?: 0L) / 1_000L).toInt(),
    )

    /** Snapshot before any observation has arrived. */
    fun initialSnapshot(): FormCheckUiState =
        snapshot(FormCheckStartState.WAITING_FOR_CAMERA, spec.requiredJoints)

    /** Whichever detector this cadence uses; abstention is identical for both. */
    private fun invalidateDetectors() {
        detector?.invalidate()
        holdDetector?.invalidate()
    }

    /** Keeps only the set's opening repetitions; later ones are compared, never averaged in. */
    private fun recordBaseline(detectorExtreme: Double) {
        if (baselineSamples.size < BASELINE_REPETITIONS) baselineSamples.add(detectorExtreme)
    }

    /**
     * How this repetition sat against the set's opening ones, or null when there is no baseline
     * yet or the difference is inside what the measurement could have invented.
     */
    private fun baselineNote(detectorExtreme: Double): String? {
        if (baselineSamples.size < BASELINE_REPETITIONS) return null
        val baseline = baselineSamples.sorted().let { sorted ->
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
        }
        // Detector space: a larger value is always less work than the baseline.
        val shortfall = detectorExtreme - baseline
        if (abs(shortfall) < BASELINE_NOTICEABLE_DEGREES) return null
        val magnitude = abs(shortfall).roundToInt()
        val phrase = if (shortfall > 0) {
            spec.direction.belowBaselinePhrase
        } else {
            spec.direction.beyondBaselinePhrase
        }
        return "오늘 첫 반복보다 ${magnitude}도 $phrase"
    }

    private companion object {
        /** The opening repetitions that define the set's own baseline. */
        const val BASELINE_REPETITIONS = 2

        /**
         * Below this the difference is not reported. A same-set self-comparison cancels the
         * systematic straightening the bridge card measured, but its random part is unmeasured,
         * so the floor sits clear of the median absolute error rather than at a flattering value.
         */
        const val BASELINE_NOTICEABLE_DEGREES = 15.0
    }
}
