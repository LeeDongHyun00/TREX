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
 * facade, no criterion, no stored record. Constants carry their provenance individually: six were
 * measured through the app's own model and cleared a leave-one-global-subject-out balanced
 * accuracy of 0.75 (three depth thresholds -- standing knee-up, lat pull-down, dips -- and three
 * guard limits -- barbell curl, rowing machine, standing side crunch); two cite published
 * standards; the rest are uncalibrated defaults. Exercises whose overshoot has a real consequence
 * are sealed against urging more range whether or not they are calibrated. None of this amounts to
 * release-chain calibration, which is why every surface carries the beta disclosure and [claims]
 * still withholds `calibrated`.
 */
internal object HeuristicFormCheckDeclaration {

    const val TRACK_ID: String = "trex.heuristic-form-check.beta.v1"

    const val POLICY_DOCUMENT_SHA256: String =
        "eb1418b8791eeb37aab900bf2d726d5df39c4219cbbfe4aee9b1c9c78a04d49d"

    const val POLICY_DOCUMENT_PATH: String = "docs/pose-heuristic-form-check.v1.md"

    /** Always-on beta disclosure. The user cannot dismiss it. */
    const val BETA_DISCLOSURE: String = "휴리스틱 참고용이에요 · 정식 검증 전"

    /**
     * Shown wherever an exercise's threshold was fitted on AI Hub data.
     *
     * The dataset's published usage policy permits distributing what is learned from it but
     * requires stating that the data was used, so this is an obligation rather than a courtesy.
     * It appears per exercise rather than app-wide because most exercises carry no AI Hub-derived
     * constant, and a blanket credit would claim a provenance they do not have.
     */
    const val DATA_ATTRIBUTION: String = "임계값 근거: AI Hub 피트니스 자세 이미지"

    // User-facing strings for the workout list live here rather than in the screen file so the
    // language seal scans every word this track ever shows.
    const val TOGGLE_LABEL: String = "자세 체크 베타 · 관찰 안내"
    const val ROW_CAPTION: String = "자세 체크는 휴리스틱 베타 · 정식 판정 준비 중"
    const val PILL_LABEL: String = "자세 체크 베타"

    /**
     * What the track says while it is not counting.
     *
     * Split from the opening guidance because the two mean different things to somebody already
     * in position: "…이 화면에 보이게 서 주세요" asks for a setup they have completed, whereas what
     * actually happened is that the camera lost the joint and the count stopped. Naming that is
     * how policy §3.1's abstention becomes something the user can see rather than a silence they
     * read as a crash.
     */
    const val PAUSED_PERSON: String = "지금은 화면에서 사람을 놓쳐서 세지 않고 있어요"
    const val PAUSED_JOINT_PREFIX: String = "지금은 "
    const val PAUSED_JOINT_SUFFIX: String = " 잘 안 보여서 세지 않고 있어요"
    const val PAUSED_RESUME: String = "다시 보이면 이어서 셀게요"

    /** Spoken when observation resumes after a pause long enough to have been announced. */
    const val RESUMED: String = "다시 보여서 이어서 셀게요"

    /**
     * The one-time framing shown before the first set of a form-check session.
     *
     * Says what the surface is before it says anything about a body, so the drawing on screen is
     * read as an instrument rather than as an opinion.
     */
    const val INTRO_TITLE: String = "카메라가 관절 각도를 재서 보여드려요"
    const val INTRO_MEASURES_PREFIX: String = "이 운동은 "
    const val INTRO_MEASURES_SUFFIX: String = " 각도를 봐요"
    const val INTRO_SILENCE: String = "그 관절이 안 보이면 세지 않고, 그렇다고 알려드려요"
    const val INTRO_NOT_A_VERDICT: String = "잰 값을 그대로 보여줄 뿐 판단은 하지 않아요"
    const val INTRO_DISMISS: String = "알겠어요"

    /** The rest-period review. Read standing still, at arm's length — the one legible moment. */
    const val SUMMARY_TITLE: String = "이번 세트에서 카메라가 본 것"
    const val SUMMARY_EMPTY: String = "이번 세트에서는 관찰된 움직임이 없어요"
    const val SUMMARY_COUNT_SUFFIX: String = "회 세었어요"
    const val SUMMARY_HOLD_SUFFIX: String = "초까지 유지했어요"

    /**
     * Where the exercise's angle constants came from, in a sentence rather than a badge.
     *
     * Policy §4 requires the provenance of each constant to reach the screen, and §4.5 keeps it
     * per exercise. Stated plainly here, including the uncalibrated case: a mark that only ever
     * appears when the evidence is good reads as advertising.
     */
    const val PROVENANCE_FITTED: String = "이 각도 기준은 AI Hub 자세 이미지로 맞춘 값이에요"
    const val PROVENANCE_LITERATURE: String = "이 각도 기준은 공개된 운동 표준을 인용한 값이에요"
    const val PROVENANCE_DEFAULT: String = "이 각도 기준은 아직 맞춰보지 않은 기본값이에요"

    /** Non-text descriptions for a screen reader, which the drawing alone cannot carry. */
    const val OVERLAY_DESCRIPTION: String = "카메라가 지금 재고 있는 관절"

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
 * What the user is told the measured joint did.
 *
 * Split from [FormCheckWorkingDirection] because the two answer different questions. The direction
 * is a fact about the detector -- which way the number travels as the movement works -- while this
 * is a fact about anatomy. They coincide for a knee and an elbow and come apart at the shoulder: a
 * lat pull-down closes the elbow-shoulder-hip angle, so the detector reads flexion, but the arm is
 * being *drawn in to the body*, and Korean "어깨가 굽혀졌어요" reads as rounded shoulders -- a posture
 * judgement this track is not entitled to make. Binding the verb to the direction produced exactly
 * that sentence.
 */
internal enum class FormCheckVocabulary(
    /** Verb for a completed repetition's extreme. */
    val reachedVerb: String,
    /** Noun phrase for an excursion that never reached the rep line. */
    val shortfallPhrase: String,
    /** How a repetition compares with the set's own opening reps, short of and beyond it. */
    val belowBaselinePhrase: String,
    val beyondBaselinePhrase: String,
) {
    /** A joint closing: knees, elbows, hips. */
    BENDING(
        reachedVerb = "굽혀졌어요",
        shortfallPhrase = "굽힘이 얕아",
        belowBaselinePhrase = "얕아요",
        beyondBaselinePhrase = "깊어요",
    ),

    /** A joint opening. */
    STRAIGHTENING(
        reachedVerb = "펴졌어요",
        shortfallPhrase = "폄이 부족해",
        belowBaselinePhrase = "덜 펴졌어요",
        beyondBaselinePhrase = "더 펴졌어요",
    ),

    /** A limb drawn in toward the torso: the shoulder chain closing on a pull. */
    DRAWING_IN(
        reachedVerb = "모아졌어요",
        shortfallPhrase = "모아짐이 얕아",
        belowBaselinePhrase = "덜 모아졌어요",
        beyondBaselinePhrase = "더 모아졌어요",
    ),
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
    /** The words that fit this direction unless the exercise names others. */
    val defaultVocabulary: FormCheckVocabulary,
) {
    FLEXION(FormCheckVocabulary.BENDING),
    EXTENSION(FormCheckVocabulary.STRAIGHTENING),
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
    /** Default that has never met calibration data and cites no external standard. */
    HEURISTIC_DEFAULT,

    /**
     * Taken from a published exercise standard, with the bridge card's measured MediaPipe bias
     * applied where the standard is stated in true joint angles. Better-founded than a bare
     * default — the number has a citation — but it has never been fitted against labelled data,
     * so it confers no calibration claim and owes no dataset attribution.
     */
    LITERATURE_STANDARD,

    /**
     * Fitted on MediaPipe output over AI Hub label frames under the research-use rights manifest
     * (`docs/mediapipe-aihub-bridge.v1.json`, artifact version 2). Clip-level, IMAGE-mode, studio
     * domain: a beta constant, never release calibration.
     *
     * Supersedes the v1 fit, which assumed camera view A was the lateral one, reported raw
     * accuracy, and drew on a single capture day. Every constant carrying this provenance was
     * measured from the view that `docs/aihub-measurement-view.v1.json` selects for its exercise
     * and capture day, and cleared a leave-one-subject-out **balanced** accuracy of 0.75 -- the
     * same gate the separability survey uses, so a degenerate always-true classifier cannot pass.
     */
    MEDIAPIPE_NATIVE_FIT_V2,
    ;

    /**
     * Whether this constant was learned from AI Hub data, and therefore carries the dataset's
     * attribution obligation onto whatever surface shows it. A literature standard cites a
     * publication, not the dataset, so it owes nothing here.
     */
    val requiresDataAttribution: Boolean
        get() = this == MEDIAPIPE_NATIVE_FIT_V2

    /** The same distinction stated to the user, rather than encoded in a badge they must decode. */
    val note: String
        get() = when (this) {
            HEURISTIC_DEFAULT -> HeuristicFormCheckDeclaration.PROVENANCE_DEFAULT
            LITERATURE_STANDARD -> HeuristicFormCheckDeclaration.PROVENANCE_LITERATURE
            MEDIAPIPE_NATIVE_FIT_V2 -> HeuristicFormCheckDeclaration.PROVENANCE_FITTED
        }
}

/**
 * A joint this exercise must keep still while the driver chain does the counting.
 *
 * The driver answers "how far did the working joint travel"; the guard answers the other question
 * users mean by form — "did the joint that was supposed to stay put actually stay put". A curl's
 * fault is the upper arm swinging, which the elbow angle barely sees; a rowing stroke's fault is
 * the torso whipping back, which the knee never sees. The dataset separates these conditions on
 * exactly these second chains, at accuracies as good as the depth checks.
 *
 * A guard only ever *observes*. Crossing the limit appends an observation to the completed
 * repetition's headline; it never produces a suggestion, because "keep it still" phrased as
 * advice is a corrective cue, and cues belong to the sealed release chain. It is also not a
 * start requirement: an invisible guard joint silences the guard, never the exercise.
 */
internal class FormCheckGuard(
    /** The chain being watched — a different one from the exercise's driver. */
    val driver: FormCheckDriver,
    /** Which end of the excursion window summarises the guard chain. */
    val extreme: FormCheckGuardExtreme,
    /**
     * A real joint angle on a flexion-read chain. The window statistic staying at or under this
     * limit is what the calibration found on clips where the condition held; a statistic beyond
     * it is the observation worth reporting.
     */
    val limitDegrees: Double,
    /** Guards carry their own provenance: a fitted guard on an unfitted exercise is common. */
    val provenance: FormCheckThresholdProvenance,
    /**
     * The observation for a crossed limit, with `%d` for the measured statistic. Named after the
     * anatomy, like every observation: "어깨가 61도까지 벌어졌어요", never a verdict.
     */
    val crossedObservation: String,
) {
    init {
        require(limitDegrees in 0.0..180.0) { "A guard limit must be a real joint angle" }
        require(crossedObservation.contains("%d")) {
            "The crossed observation must state the measured angle"
        }
    }

    /**
     * Whether the excursion's guard statistic is the observation worth reporting.
     *
     * One rule serves both extremes because both fits read "statistic ≤ limit means the condition
     * held": a MAX guard crosses when the joint swung past the limit, a MIN guard crosses when
     * the joint never came down to it.
     */
    fun crossed(statisticDegrees: Double): Boolean = statisticDegrees > limitDegrees
}

/** Which end of the excursion window a guard reads. */
internal enum class FormCheckGuardExtreme {
    /** The lowest angle seen — for a position that must be reached and kept, like folded arms. */
    MIN,

    /** The highest angle seen — for a joint that must not swing away, like a curl's upper arm. */
    MAX,
}

/** Whose side of the body a definition gate reads, relative to the measured side. */
internal enum class FormCheckGateSide {
    /** The same side the count is attributed to. */
    DRIVER,

    /**
     * The other side — for a movement that is one-sided *because* the other side stands still.
     * A knee raise is a knee raise only while the standing leg stands; if both hips fold, the
     * movement is a bow or a squat wearing a knee-up's counter.
     */
    OPPOSITE,
}

/** Which end of the excursion window a definition gate evaluates. */
internal enum class FormCheckGateStatistic { WINDOW_MINIMUM, WINDOW_MAXIMUM }

/** Which way the gate's bound cuts. */
internal enum class FormCheckGateComparator { AT_MOST, AT_LEAST }

/**
 * One clause of an exercise's movement definition: a joint that must do — or refrain from —
 * something during the excursion for the driver's arc to count as this exercise's repetition.
 *
 * This is the second half of the lesson the one-knee squat taught. The bilateral rule (§4.8)
 * says the two sides must agree; a definition gate says the *rest of the body* must play its
 * part: a squat's hips travel with its knees, a good morning's knees hold still, a pull-down's
 * elbows bend, a push-down's upper arms stay pinned. The driver angle alone distinguishes none
 * of these from their impostors, because included angles are all the engine can see and many
 * movements share one.
 *
 * A gate is part of the *definition of a repetition*, the same status as the rep threshold —
 * not a form-quality judgement, which stays the sealed release chain's business, and not a
 * guard, which observes without gating. A failed gate reports the truthful reason the excursion
 * was not counted, with the measured angle, and never urges: naming what the joint did is an
 * observation; telling somebody to fix it would be a corrective cue.
 *
 * Gates abstain like everything else here: a chain that was not credibly observed through the
 * window says nothing in either direction, so in the recommended lateral stance an OPPOSITE
 * gate is usually silent and the documented single-side limitation stands.
 */
internal class FormCheckDefinitionGate(
    /** The chain being required — for DRIVER-side gates, a different one from the driver's. */
    val chain: FormCheckDriver,
    val side: FormCheckGateSide = FormCheckGateSide.DRIVER,
    val statistic: FormCheckGateStatistic,
    val comparator: FormCheckGateComparator,
    /** A real joint angle; the window statistic is compared against it. */
    val boundDegrees: Double,
    /** Every gate bound is an uncalibrated default today; candidates for the fit pipeline. */
    val provenance: FormCheckThresholdProvenance,
    /**
     * The observation for a failed gate, with `%d` for the window statistic. States what the
     * joint did and that the excursion was not counted — never an instruction.
     */
    val shortfallObservation: String,
) {
    init {
        require(boundDegrees in 0.0..180.0) { "A gate bound must be a real joint angle" }
        require(shortfallObservation.contains("%d")) {
            "The shortfall observation must state the measured angle"
        }
    }

    fun satisfied(statisticDegrees: Double): Boolean = when (comparator) {
        FormCheckGateComparator.AT_MOST -> statisticDegrees <= boundDegrees
        FormCheckGateComparator.AT_LEAST -> statisticDegrees >= boundDegrees
    }

    /**
     * Whether this clause asks a joint to STAY somewhere rather than to REACH something.
     *
     * The distinction decides how noise is allowed to act on it. A REACH clause reads the extreme
     * the window travelled to, and a spurious frame can only make it easier to satisfy — the
     * failure mode is a movement counted that should not have been, which is the direction this
     * track already lives with. A STAY clause is the opposite: one blown frame is enough to make
     * the extreme cross the line and discard a repetition that really happened, which is the more
     * expensive mistake. So STAY clauses require a sustained reading rather than an extreme
     * (§4.9 rule 6).
     */
    val requiresSustainedReading: Boolean
        get() = when (statistic) {
            FormCheckGateStatistic.WINDOW_MINIMUM ->
                comparator == FormCheckGateComparator.AT_LEAST
            FormCheckGateStatistic.WINDOW_MAXIMUM ->
                comparator == FormCheckGateComparator.AT_MOST
        }
}

/**
 * Per-exercise thresholds, mirrored from the policy document's §4 table.
 *
 * Every angle here is a real joint angle: 180 degrees is a straight chain. Flexion exercises work
 * downward from [restAngleDegrees] and extension exercises work upward from it; the session
 * mirrors the extension ones into the detector's space rather than letting the table misstate
 * which angle a user's hip actually reaches.
 *
 * The label-space fits do not transfer to MediaPipe -- the bridge card measures a median absolute
 * error of 11.2 degrees between the two, and applying a label-fitted threshold directly costs most
 * of the separation it had -- so calibrated entries carry the MediaPipe-native value.
 */
internal enum class FormCheckExercise(
    val exercise: AiHubExercise,
    /** Which three-joint chain this exercise reads. Determines its required joints. */
    val driver: FormCheckDriver,
    val direction: FormCheckWorkingDirection,
    /**
     * How this exercise's movement is described, when the direction's default words would be
     * anatomically wrong. Null takes [FormCheckWorkingDirection.defaultVocabulary].
     */
    private val vocabularyOverride: FormCheckVocabulary? = null,
    /** The joint this exercise watches for staying put, or null when only depth is read. */
    val guard: FormCheckGuard? = null,
    /**
     * Whether this movement is mechanically two-sided — a bar in both hands, both feet pressing
     * together — so that its repetition is *defined* over both sides even though only one chain
     * is measured (§4.8).
     *
     * When true and the opposite side's chain happens to be concurrently observable, an excursion
     * whose two sides persistently disagree is reported rather than counted: a standing knee
     * raise bends one knee through exactly the arc a squat does, and the still leg is the only
     * thing that distinguishes them. False for movements that are one-sided by design (knee-up,
     * side crunch), for the lunges (a stride's two knees genuinely travel differently), and for
     * the dumbbell curl (alternating arms is a legitimate way to do it).
     */
    val bilateralDriver: Boolean = false,
    /**
     * The movement's definition beyond the driver arc: joints that must come along, hold still,
     * or stay put for an excursion to be this exercise's repetition (§4.9). Evaluated in
     * declaration order; the first unmet clause is the one reported. Empty where the driver arc
     * plus the bilateral rule already are the definition, or where the honest discriminator
     * needs an orientation reference this track does not assume.
     */
    val definition: List<FormCheckDefinitionGate> = emptyList(),
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
        bilateralDriver = true,
        // A squat sits back: the hips travel with the knees (the biomechanics literature puts
        // parallel-squat hip flexion far past this line). A knee-only dip — bouncing on the
        // ankles with the torso upright — leaves the hip chain near straight and is not a squat.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.HIP,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "엉덩이가 %d도까지만 굽혀져서 횟수로 세지 않았어요",
            ),
        ),
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 110.0,
        // The biomechanics literature places a parallel squat at roughly 90 degrees of knee
        // included angle (IJSPT squat review); adding the bridge card's measured MediaPipe
        // straightening bias lands at ~103-105. This value targets the parallel squat, cited, not
        // fitted — the dataset carries no depth condition for this exercise at all.
        reachedAngleDegrees = 105.0,
        provenance = FormCheckThresholdProvenance.LITERATURE_STANDARD,
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
        // Uncalibrated, after a fit was tried and withdrawn. Measured on one capture day this
        // exercise looked like the best-calibrated in the app -- 116 degrees at 0.927 balanced over
        // 8 subjects. Re-measured across six capture days and 48 participants it falls to 136
        // degrees at 0.746, under the 0.75 gate, and the 3D ground truth itself only reaches 0.778.
        // The 0.927 was a property of those eight people, not of the exercise; that MediaPipe
        // appeared to beat the label ceiling was the clue. Back to the uncalibrated value the other
        // two lunges use, since the movement pattern is the same and nothing better is earned.
        reachedAngleDegrees = 129.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
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
        // Uncalibrated, and never refittable: on 3D ground truth across 94 subjects this exercise's
        // best chain separates its depth condition at 0.736 balanced, under the 0.75 gate, so no
        // measurement view was ever selected for it and MediaPipe can only do worse than labels
        // that already fail. Shares the lunge family's uncalibrated value; the forward lunge's
        // one-day 116 that this briefly borrowed did not survive a wider population either.
        reachedAngleDegrees = 129.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
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
        // The dataset's own condition is "무릎 충분히 올라오고": a raise hangs the shin, so the
        // raised knee closes roughly as far as the hip does, while a forward bow — which flexes
        // the hip driver identically — keeps the knees straight. And a knee raise is one-sided
        // *because* the standing leg stands: both hips folding together is a bow or a squat
        // wearing this exercise's counter.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.KNEE,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 150.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "무릎이 %d도까지만 굽혀져서 횟수로 세지 않았어요",
            ),
            FormCheckDefinitionGate(
                chain = FormCheckDriver.HIP,
                side = FormCheckGateSide.OPPOSITE,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_LEAST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "반대쪽 엉덩이가 %d도까지 함께 굽혀져서 횟수로 세지 않았어요",
            ),
        ),
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        // Measured 105 degrees, LOSO balanced 0.813 over 48 participants, 942 clips and nine
        // capture days -- the widest evidence any constant in this table has. The clip-level bias
        // of 1.0 degree is the smallest in the whole bridge card: the hip is a torso joint, and
        // those survive a phone camera far better than the limbs do.
        reachedAngleDegrees = 105.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 무릎을 조금 더 올려볼까요",
        rangeHint = "다음엔 무릎을 조금 더 올려볼까요",
    ),
    GOOD_MORNING(
        exercise = AiHubExercise.GOOD_MORNING,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.FLEXION,
        bilateralDriver = true,
        // The dataset's own condition is "무릎 구부린채 고정": a good morning is a hinge on soft
        // knees. A squat flexes the same hip driver through the same arc — the knees folding
        // with it are what make it a squat instead, so knees that dip past soft are the
        // truthful reason the excursion is not a good morning.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.KNEE,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_LEAST,
                boundDegrees = 135.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "무릎이 %d도까지 굽혀져서 횟수로 세지 않았어요",
            ),
        ),
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
        bilateralDriver = true,
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
        bilateralDriver = true,
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
        bilateralDriver = true,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        // Measured 106 degrees, LOSO balanced 0.761 over 32 participants, 566 clips and four
        // capture days.
        reachedAngleDegrees = 106.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
        // Calibrated AND sealed, which nothing else in this table is. Knowing where the line sits
        // does not make it safe to push somebody toward it: at the bottom of a dip the shoulder is
        // at end range, and that is an anatomical fact a better threshold does not change. The
        // calibration improves what this exercise *observes* -- the angle it names is now a
        // measured one -- and the seal keeps it from suggesting more of a movement whose overshoot
        // has a real consequence. Policy §4.2 was revised to say the seal answers a question about
        // consequence, not about evidence.
        rangeUrgingSealed = true,
        setupHint = "옆모습이 보이게 자세를 잡아 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    BARBELL_CURL(
        exercise = AiHubExercise.BARBELL_CURL,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        bilateralDriver = true,
        // A curl keeps the upper arm alongside the ribs. At 180 the upper arm is in line with
        // the torso, so this clause says the arm never came within forty degrees of that — which
        // no curl does, in any variant, in either direction (an included angle is unsigned, so
        // an arm raised high and one carried far behind read the same). It is the same line the
        // overhead press requires from the other side, so no excursion can be both.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.SHOULDER,
                statistic = FormCheckGateStatistic.WINDOW_MAXIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation =
                    "어깨가 %d도까지 벌어져 위팔이 몸통과 거의 일직선이 되어서 횟수로 세지 않았어요",
            ),
        ),
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 120.0,
        reachedAngleDegrees = 100.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        // The dataset's condition for this exercise is not curl depth but "the elbow stays put",
        // and it separates on the shoulder chain the elbow angle barely moves: measured 52
        // degrees, LOSO balanced 0.784 over 18 participants and 574 clips. The window maximum is
        // the evidence — a single swing is the fault, not the average.
        //
        // The guard and the definition gate above read the same chain and the same extreme, and
        // they say different kinds of thing: the guard describes a swing that happened inside a
        // counted repetition, the gate says the arc was not a curl at all. That is safe here only
        // because 140 sits outside every curl population, so the two can never be read as two
        // rungs of one scale (§4.9 rule 7).
        guard = FormCheckGuard(
            driver = FormCheckDriver.SHOULDER,
            extreme = FormCheckGuardExtreme.MAX,
            limitDegrees = 52.0,
            provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            crossedObservation = "어깨가 %d도까지 벌어졌어요",
        ),
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 조금 더 감아올려 볼까요",
        rangeHint = "다음엔 조금 더 감아올려 볼까요",
    ),
    DUMBBELL_CURL(
        exercise = AiHubExercise.DUMBBELL_CURL,
        driver = FormCheckDriver.ELBOW,
        direction = FormCheckWorkingDirection.FLEXION,
        // The same clause as the barbell twin, and it has to be: gating one and not the other
        // would give a user two different behaviours for one movement. The bound sits far enough
        // outside every curl variant that it does not lean on this exercise's weaker fit, and
        // this twin is the more exposed of the two because it carries no bilateral rule.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.SHOULDER,
                statistic = FormCheckGateStatistic.WINDOW_MAXIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation =
                    "어깨가 %d도까지 벌어져 위팔이 몸통과 거의 일직선이 되어서 횟수로 세지 않았어요",
            ),
        ),
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 120.0,
        reachedAngleDegrees = 100.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        // Same movement, weaker evidence: its own measurement reaches 0.739 balanced, under the
        // 0.75 gate, so this guard borrows the barbell twin's limit and must say so — an
        // uncalibrated prior, not a fit. Its clip-level bias also hints at label damage on the
        // dumbbell capture days, which a wider archive could later resolve.
        guard = FormCheckGuard(
            driver = FormCheckDriver.SHOULDER,
            extreme = FormCheckGuardExtreme.MAX,
            limitDegrees = 52.0,
            provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
            crossedObservation = "어깨가 %d도까지 벌어졌어요",
        ),
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 조금 더 감아올려 볼까요",
        rangeHint = "다음엔 조금 더 감아올려 볼까요",
    ),
    LAT_PULLDOWN(
        exercise = AiHubExercise.LAT_PULLDOWN,
        // Reads the shoulder, not the elbow. The dataset's condition for this exercise is how far
        // the upper arm closes toward the ribs, which the elbow angle barely sees: on 3D ground
        // truth the elbow chain scores at chance and the shoulder chain at 0.879 balanced.
        driver = FormCheckDriver.SHOULDER,
        direction = FormCheckWorkingDirection.FLEXION,
        // The detector reads flexion, but the arm is being drawn in to the body. Left to the
        // direction's default this sentence became "어깨가 67도까지 굽혀졌어요", which in Korean
        // describes rounded shoulders -- a posture judgement, from a track that makes none.
        vocabularyOverride = FormCheckVocabulary.DRAWING_IN,
        bilateralDriver = true,
        // A pull-down pulls: the elbows bend as the bar comes toward the chest. A straight-arm
        // swing closes the same shoulder angle without ever being a pull on this machine.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.ELBOW,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 130.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "팔꿈치가 %d도까지만 굽혀져서 횟수로 세지 않았어요",
            ),
        ),
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 130.0,
        // Measured 67 degrees, LOSO balanced 0.855 over 32 participants, 565 clips and four capture
        // days. Widening the population left the threshold unmoved and raised the accuracy, which
        // is what a constant that describes the exercise rather than the sample looks like.
        reachedAngleDegrees = 67.0,
        provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 앉아 주세요",
        attemptHint = "다음엔 조금 더 당겨볼까요",
        rangeHint = "다음엔 조금 더 당겨볼까요",
    ),

    // The stability wave. These two exist because their dataset condition is a guarded joint,
    // not a depth, and the guard fit cleared the gate; their rep-counting thresholds are ordinary
    // uncalibrated defaults, which is what the provenance split on each entry records.
    ROWING_MACHINE(
        exercise = AiHubExercise.ROWING_MACHINE,
        driver = FormCheckDriver.KNEE,
        direction = FormCheckWorkingDirection.FLEXION,
        bilateralDriver = true,
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 110.0,
        reachedAngleDegrees = 100.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        // "No excessive lean-back" separates on the torso line at 132 degrees, LOSO balanced
        // 0.800 over 32 participants and 1,136 clips. The trunk chain reads lean, not spine
        // curvature — a rounded back and a straight hinge look identical to it — so the
        // observation speaks of the torso's angle and nothing else.
        guard = FormCheckGuard(
            driver = FormCheckDriver.TRUNK,
            extreme = FormCheckGuardExtreme.MAX,
            limitDegrees = 132.0,
            provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            crossedObservation = "상체가 %d도까지 젖혀졌어요",
        ),
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 앉아 주세요",
        attemptHint = "다음엔 무릎을 조금 더 굽혀볼까요",
        rangeHint = "다음엔 무릎을 조금 더 굽혀볼까요",
    ),
    STANDING_SIDE_CRUNCH(
        exercise = AiHubExercise.STANDING_SIDE_CRUNCH,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.FLEXION,
        // The dataset asks "무릎이 몸통 측면에서 올라오는지": the crunch raises a knee toward the
        // elbow, so the same two clauses as the knee-up apply — the raised knee closes, and the
        // standing leg stands. What this chain cannot see, the lateral flexion itself, stays
        // unclaimed (§6).
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.KNEE,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 150.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "무릎이 %d도까지만 굽혀져서 횟수로 세지 않았어요",
            ),
            FormCheckDefinitionGate(
                chain = FormCheckDriver.HIP,
                side = FormCheckGateSide.OPPOSITE,
                statistic = FormCheckGateStatistic.WINDOW_MINIMUM,
                comparator = FormCheckGateComparator.AT_LEAST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "반대쪽 엉덩이가 %d도까지 함께 굽혀져서 횟수로 세지 않았어요",
            ),
        ),
        cadence = FormCheckCadence.REPETITION,
        restAngleDegrees = 150.0,
        attemptAngleDegrees = 140.0,
        repAngleDegrees = 135.0,
        reachedAngleDegrees = 125.0,
        provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
        // "Hands stay behind the head" is a MIN guard: folded arms keep the elbow at or under 94
        // degrees at some point in every repetition, and an elbow that never came down to it is
        // an arm that never folded. Measured LOSO balanced 0.856 over 47 participants and 1,574
        // clips — the closest any constant in this table sits to its label ceiling.
        guard = FormCheckGuard(
            driver = FormCheckDriver.ELBOW,
            extreme = FormCheckGuardExtreme.MIN,
            limitDegrees = 94.0,
            provenance = FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            crossedObservation = "팔꿈치가 %d도까지만 굽혀졌어요",
        ),
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 서 주세요",
        attemptHint = "다음엔 무릎을 조금 더 올려볼까요",
        rangeHint = "다음엔 무릎을 조금 더 올려볼까요",
    ),

    // Wave 2, extension. Their rest position is the flexed one, so every threshold reads the
    // other way round and the session mirrors them before the detector sees them.
    HIP_THRUST(
        exercise = AiHubExercise.HIP_THRUST,
        driver = FormCheckDriver.HIP,
        direction = FormCheckWorkingDirection.EXTENSION,
        bilateralDriver = true,
        // The bridge the dataset describes ("수축시 무릎부터 어깨까지 일자") is built on planted
        // feet: the knees stay bent near ninety through the whole repetition. Standing up from a
        // chair extends the same hip driver through the same arc — the knees straightening with
        // it are what make it standing up.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.KNEE,
                statistic = FormCheckGateStatistic.WINDOW_MAXIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "무릎이 %d도까지 펴져서 횟수로 세지 않았어요",
            ),
        ),
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
        bilateralDriver = true,
        // A press finishes overhead: at lockout the upper arm has opened all the way away from
        // the torso. The same elbow extension performed at the waist — a push-down's motion, a
        // punch — never opens the shoulder, and is not a press however straight the arm gets.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.SHOULDER,
                statistic = FormCheckGateStatistic.WINDOW_MAXIMUM,
                comparator = FormCheckGateComparator.AT_LEAST,
                boundDegrees = 140.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "어깨가 %d도까지만 벌어져서 횟수로 세지 않았어요",
            ),
        ),
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
        bilateralDriver = true,
        // The dataset's own condition is "팔꿈치 위치 고정": a push-down keeps the upper arms
        // pinned to the ribs while the forearms travel. If the shoulder opens wide the movement
        // has become a press or a swing — the mirror image of the overhead press's clause.
        definition = listOf(
            FormCheckDefinitionGate(
                chain = FormCheckDriver.SHOULDER,
                statistic = FormCheckGateStatistic.WINDOW_MAXIMUM,
                comparator = FormCheckGateComparator.AT_MOST,
                boundDegrees = 70.0,
                provenance = FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                shortfallObservation = "어깨가 %d도까지 벌어져서 횟수로 세지 않았어요",
            ),
        ),
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
        // The standard test protocol defines a plank as a straight head-to-heel line, ended when
        // the hips sag: 160 degrees of hip angle is that line with a sag tolerance, cited from
        // the protocol rather than fitted — the isometric hold has no clip statistic to fit.
        repAngleDegrees = 160.0,
        reachedAngleDegrees = 160.0,
        provenance = FormCheckThresholdProvenance.LITERATURE_STANDARD,
        rangeUrgingSealed = false,
        setupHint = "옆모습이 보이게 엎드려 주세요",
        attemptHint = null,
        rangeHint = null,
    ),
    ;

    /**
     * The only joints this exercise needs before it can start — the driver's, never the guard's.
     * A hidden guard joint silences the guard observation; making it a start gate would keep the
     * whole exercise from counting because of a joint the count does not read.
     */
    val requiredJoints: Set<FormCheckJointGroup> get() = driver.requiredJoints

    /** The words used to describe what the measured joint did. */
    val vocabulary: FormCheckVocabulary get() = vocabularyOverride ?: direction.defaultVocabulary

    /**
     * Whether any constant this exercise shows was learned from AI Hub data. The guard counts:
     * a fitted guard on an otherwise-default exercise still owes the dataset its credit.
     */
    val requiresDataAttribution: Boolean
        get() = provenance.requiresDataAttribution ||
            guard?.provenance?.requiresDataAttribution == true ||
            // A no-op today — every gate bound is an uncalibrated default. It is here because
            // §4.9 rule 4 names gate bounds as the next thing the fit pipeline will measure, and
            // without this line the first fitted bound would reach a user with no credit shown
            // and no test failing: the §4.5 obligation missed silently rather than loudly.
            definition.any { it.provenance.requiresDataAttribution }

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
        // A guard's window is the repetition excursion; a hold has no such window.
        require(guard == null || cadence == FormCheckCadence.REPETITION) {
            "A guard needs a repetition excursion to watch"
        }
        // The coherence check reads the excursion window too. A hold's two sides matter just as
        // much, but the hold path has no window to compare over, so declaring the flag there
        // would promise a check that never runs.
        require(!bilateralDriver || cadence == FormCheckCadence.REPETITION) {
            "Bilateral coherence needs a repetition excursion to compare over"
        }
        require(definition.isEmpty() || cadence == FormCheckCadence.REPETITION) {
            "A definition gate needs a repetition excursion to evaluate over"
        }
        for (gate in definition) {
            // A driver-side gate on the driver's own chain would restate the rep thresholds; an
            // opposite-side gate on the driver chain is the standing-leg clause and is fine.
            require(gate.side == FormCheckGateSide.OPPOSITE || gate.chain !== driver) {
                "A definition gate must watch a companion chain, not the driver's own"
            }
        }
        require(guard == null || guard.driver !== driver) {
            "A guard must watch a different chain from the driver"
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
    /**
     * Whether this set ever reached [FormCheckStartState.STARTED].
     *
     * Falling back to a waiting state after that is not a setup problem but an abstention, and
     * the two need different words: "서 주세요" tells someone who is already in position to do
     * something they have already done, while what actually happened is that the track stopped
     * counting. Sticky for the life of the set.
     */
    val hasEverStarted: Boolean,
    /** This set's completed excursions, oldest first. Memory only, discarded with the set. */
    repMarks: List<FormCheckRepMark>,
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

    val repMarks: List<FormCheckRepMark> = Collections.unmodifiableList(ArrayList(repMarks))

    val started: Boolean
        get() = startState == FormCheckStartState.STARTED

    /**
     * The track was counting and is not counting now. Distinguished from the opening states so
     * the surface can say the observation paused instead of repeating setup instructions.
     */
    val observationPaused: Boolean
        get() = hasEverStarted && startState != FormCheckStartState.STARTED

    init {
        require(repCount >= 0)
        require(uncountedAttemptCount >= 0)
        require(holdSeconds >= 0)
        require(startState != FormCheckStartState.STARTED || this.missingJoints.isEmpty()) {
            "A started exercise cannot still be missing joints"
        }
        require(startState != FormCheckStartState.STARTED || hasEverStarted) {
            "A started exercise has started"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FormCheckUiState) return false
        return repCount == other.repCount &&
            uncountedAttemptCount == other.uncountedAttemptCount &&
            startState == other.startState &&
            hasEverStarted == other.hasEverStarted &&
            // Size rather than contents: marks are immutable and only ever appended, so the size
            // moves whenever the list does. Leaving it out would let the surface's `!=` guard
            // swallow a new repetition's mark.
            repMarks.size == other.repMarks.size &&
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
        result = 31 * result + hasEverStarted.hashCode()
        result = 31 * result + repMarks.size
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
    private var hasEverStarted: Boolean = false
    private var longestHoldMs: Long = 0L
    private var lastAcceptedTimestampMs: Long? = null
    private val repMarks = ArrayList<FormCheckRepMark>(MARK_CAPACITY)

    /**
     * What the surface may draw on the body this frame, or null while the track abstains.
     *
     * Deliberately not part of [FormCheckUiState]: it moves every frame, and the surface compares
     * snapshots before re-rendering. Null is load-bearing — every return path that stops
     * evaluating clears it, which is how policy §3.1 ("판정 불가를 다른 값으로 위장하지 않는다")
     * becomes a property of this class rather than a rule the drawing code has to remember.
     */
    var liveReading: FormCheckLiveReading? = null
        private set

    /**
     * The guard chain's extreme over the excursion in flight, or null when nothing credible has
     * been seen inside the window. Null at completion is abstention: a guard that was never
     * observed reports nothing, in both directions.
     */
    private var guardWindowDegrees: Double? = null

    /**
     * The coherence window for a bilateral exercise: of the frames inside the excursion where the
     * opposite side's chain was credibly observed *in the same frame*, how many, and in how many
     * the two sides disagreed by more than [BILATERAL_DIVERGENCE_DEGREES].
     *
     * Concurrent, per-frame comparison on purpose. Comparing window extremes would misfire when
     * the far side is only visible near the top — its minimum would read straight and a real
     * squat would be discarded. Two sides seen in the same frame either agree or they do not,
     * however little of the excursion the far side was visible for; and the majority rule keeps
     * a few noisy far-side frames from outvoting an excursion that was coherent throughout.
     */
    private var bilateralConcurrentFrames = 0
    private var bilateralDivergentFrames = 0

    /**
     * Per-gate windows over the excursion in flight: how many frames the gate's chain was
     * credibly observed, and the raw extremes it reached. Raw angles, no smoothing — a gate
     * asks where the joint actually was, and the windows reset with the excursion.
     */
    private val gateObservedFrames = IntArray(spec.definition.size)
    private val gateWindowMinimum = DoubleArray(spec.definition.size) { Double.MAX_VALUE }
    private val gateWindowMaximum = DoubleArray(spec.definition.size) { -Double.MAX_VALUE }

    /**
     * How many frames of the excursion each STAY clause was actually outside its bound. A single
     * misread frame is noise; a position the movement held is the thing worth reporting.
     */
    private val gateViolatingFrames = IntArray(spec.definition.size)

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

    /** "양쪽 무릎이 서로 다르게 움직여서 횟수로 세지 않았어요" — the truthful uncounted reason. */
    private val asymmetricObservation: String =
        "양쪽 $vertexSubject 서로 다르게 움직여서 횟수로 세지 않았어요"

    fun accept(
        timestampMs: Long,
        hasPrimaryPersonLock: Boolean,
        lateralViewQualified: Boolean,
        frame: PoseFrame,
    ): FormCheckUiState {
        sideViewPreferred = !lateralViewQualified
        // Policy §3.1 lists a backwards timestamp among the abstentions, and each detector already
        // discards its own excursion on one. Enforcing it here as well is what makes that clause
        // true of the whole session: the detectors' internal invalidation cannot clear a live
        // reading the session publishes afterwards, so without this the surface would keep drawing
        // an angle from a frame the engine had just refused.
        val previous = lastAcceptedTimestampMs
        if (previous != null && timestampMs <= previous) {
            invalidateDetectors()
            return snapshot(FormCheckStartState.WAITING_FOR_JOINTS, spec.requiredJoints)
        }
        lastAcceptedTimestampMs = timestampMs
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
        hasEverStarted = true

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
                        // Banked only on release, never while holding: a stretch abstention would
                        // later discard must not already be in the set's summary.
                        longestHoldMs = maxOf(longestHoldMs, event.heldMs)
                        "${(event.heldMs / 1_000L)}초 유지했어요"
                    } else {
                        "자세가 잠깐 풀렸어요"
                    }
                    suggestion = null
                }

                HoldEvent.None -> Unit
            }
            // A hold has no excursion, so its reading carries none: the surface draws the angle
            // the body is holding and nothing about a movement that is not happening.
            liveReading = FormCheckLiveReading(
                angleDegrees = sample.includedAngleDegrees,
                chainConfidence = sample.chainConfidence,
                side = sample.side,
                inExcursion = hold.holding,
                excursionExtremeDegrees = null,
            )
            return snapshot(FormCheckStartState.STARTED, emptySet())
        }

        val repDetector = requireNotNull(detector)
        val event = repDetector.accept(timestampMs, detectorValue)
        // The guard watches the same excursion the count comes from — accumulation runs only
        // while one is armed, so a stretch between repetitions is never reported as movement
        // during one. The completing frame sits at the top and is deliberately outside.
        if (repDetector.inExcursion) {
            if (spec.guard != null) accumulateGuard(frame, spec.guard)
            if (spec.bilateralDriver) {
                accumulateBilateral(frame, measuredDegrees = sample.includedAngleDegrees)
            }
            if (spec.definition.isNotEmpty()) accumulateDefinition(frame)
        }
        when (event) {
            is RepCycleEvent.Completed -> {
                val extreme = spec.fromDetector(event.minimumAngleDegrees)
                // Both consumed unconditionally so their windows never leak into the next
                // excursion; evaluated coarsest first — two sides disagreeing is a grosser
                // incoherence than one clause of the definition falling short.
                val incoherent = takeBilateralIncoherence()
                val shortfall = takeDefinitionShortfall()
                if (incoherent) {
                    // The measured side did everything a repetition does; the other side,
                    // watched frame for frame, did not move with it. On a two-sided exercise
                    // that is not the movement being counted, and saying which is the truthful
                    // reason — "얕아" would be false and silence would be indistinguishable
                    // from a missed detection.
                    uncountedAttemptCount += 1
                    headline = asymmetricObservation
                    suggestion = null
                    guardWindowDegrees = null
                    appendMark(
                        kind = FormCheckRepEventKind.ASYMMETRIC,
                        extremeDegrees = extreme,
                        baselineRelation = FormCheckBaselineRelation.SAME,
                        guardDegrees = null,
                    )
                } else if (shortfall != null) {
                    // The driver finished its arc but a joint the movement's definition names
                    // did not do its part. Reporting which joint, and how far it got, is what
                    // the user asked "왜 안 셌지" actually means.
                    val (gate, statistic) = shortfall
                    uncountedAttemptCount += 1
                    headline = gate.shortfallObservation.format(statistic)
                    suggestion = null
                    guardWindowDegrees = null
                    appendMark(
                        kind = FormCheckRepEventKind.INCOMPLETE,
                        extremeDegrees = extreme,
                        baselineRelation = FormCheckBaselineRelation.SAME,
                        guardDegrees = null,
                    )
                } else {
                    repCount += 1
                    val observed =
                        "$vertexSubject ${extreme.roundToInt()}도까지 ${spec.vocabulary.reachedVerb}"
                    // Both read the baseline before this repetition joins it, so the comparison
                    // is against the set's opening repetitions rather than against itself.
                    val relation = baselineRelation(event.minimumAngleDegrees)
                    val guardCrossing = takeGuardCrossing()
                    headline = listOfNotNull(
                        observed,
                        baselineNote(event.minimumAngleDegrees),
                        guardCrossing?.let { spec.guard?.crossedObservation?.format(it) },
                    ).joinToString(" · ")
                    recordBaseline(event.minimumAngleDegrees)
                    appendMark(
                        kind = FormCheckRepEventKind.COUNTED,
                        extremeDegrees = extreme,
                        baselineRelation = relation,
                        guardDegrees = guardCrossing,
                    )
                    // Null for sealed exercises: the observation stands without urging more range.
                    suggestion = if (
                        event.minimumAngleDegrees <= spec.toDetector(spec.reachedAngleDegrees)
                    ) {
                        null
                    } else {
                        spec.rangeHint
                    }
                }
            }

            is RepCycleEvent.ShallowAttempt -> {
                uncountedAttemptCount += 1
                headline = "${spec.driver.vertex.label} ${spec.vocabulary.shortfallPhrase} " +
                    "횟수로 세지 않았어요"
                suggestion = spec.attemptHint
                guardWindowDegrees = null
                resetBilateralWindow()
                resetDefinitionWindows()
                appendMark(
                    kind = FormCheckRepEventKind.SHALLOW,
                    extremeDegrees = spec.fromDetector(event.minimumAngleDegrees),
                    baselineRelation = FormCheckBaselineRelation.SAME,
                    guardDegrees = null,
                )
            }

            is RepCycleEvent.TooFastAttempt -> {
                uncountedAttemptCount += 1
                headline = "동작이 빨라 횟수로 세지 않았어요"
                suggestion = "조금 더 천천히 움직여볼까요"
                guardWindowDegrees = null
                resetBilateralWindow()
                resetDefinitionWindows()
                appendMark(
                    kind = FormCheckRepEventKind.TOO_FAST,
                    extremeDegrees = spec.fromDetector(event.minimumAngleDegrees),
                    baselineRelation = FormCheckBaselineRelation.SAME,
                    guardDegrees = null,
                )
            }

            RepCycleEvent.None -> {
                // A max-duration abort ends the window without an event; whatever the guard and
                // the coherence window saw belongs to a movement that was never counted.
                if (!repDetector.inExcursion) {
                    guardWindowDegrees = null
                    resetBilateralWindow()
                    resetDefinitionWindows()
                }
            }
        }
        liveReading = FormCheckLiveReading(
            angleDegrees = sample.includedAngleDegrees,
            chainConfidence = sample.chainConfidence,
            side = sample.side,
            inExcursion = repDetector.inExcursion,
            excursionExtremeDegrees = repDetector.excursionExtremeDegrees?.let(spec::fromDetector),
        )
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
        hasEverStarted = hasEverStarted,
        repMarks = repMarks,
        missingJoints = if (startState == FormCheckStartState.STARTED) emptySet() else missingJoints,
        sideViewPreferred = sideViewPreferred,
        headline = headline,
        suggestion = suggestion,
        holdSeconds = ((holdDetector?.heldMs ?: 0L) / 1_000L).toInt(),
    )

    /** Snapshot before any observation has arrived. */
    fun initialSnapshot(): FormCheckUiState =
        snapshot(FormCheckStartState.WAITING_FOR_CAMERA, spec.requiredJoints)

    /**
     * What this set amounted to, for the rest period to show.
     *
     * Built from what was already reported: the marks and the last headline, never from a
     * repetition or a hold still in flight. Nothing here is stored — the host keeps the instance
     * for as long as the set lasts and drops it with the set.
     */
    fun summary(): FormCheckSetSummary = FormCheckSetSummary(
        measuredJointLabel = spec.driver.vertex.label,
        cadence = spec.cadence,
        repCount = repCount,
        holdSeconds = (longestHoldMs / 1_000L).toInt(),
        marks = repMarks,
        lastObservation = headline,
        provenanceNote = spec.provenance.note,
        requiresDataAttribution = spec.requiresDataAttribution,
    )

    /**
     * Whichever detector this cadence uses; abstention is identical for both.
     *
     * Clearing [liveReading] here is the single choke point that keeps an unobserved joint from
     * leaving its last angle drawn on the body. Every path in [accept] that gives up on a frame
     * comes through here.
     */
    private fun invalidateDetectors() {
        detector?.invalidate()
        holdDetector?.invalidate()
        guardWindowDegrees = null
        resetBilateralWindow()
        resetDefinitionWindows()
        liveReading = null
    }

    /**
     * Feeds one frame's opposite-side reading into the coherence window.
     *
     * The comparison is between raw included angles of the same chain on the two sides, in the
     * same frame. An extension exercise mirrors both sides identically, so the difference is the
     * same in either space and no mirroring is needed here. A frame where the opposite chain is
     * not credible contributes nothing — an unobserved side is not evidence of anything, in
     * either direction.
     */
    private fun accumulateBilateral(frame: PoseFrame, measuredDegrees: Double) {
        val side = activeSide ?: return
        val sample = FormCheckGeometry.sideSample(frame, side.opposite(), spec.driver) ?: return
        bilateralConcurrentFrames += 1
        if (abs(sample.includedAngleDegrees - measuredDegrees) > BILATERAL_DIVERGENCE_DEGREES) {
            bilateralDivergentFrames += 1
        }
    }

    /**
     * Feeds one frame into every definition gate's window. A gate whose chain is not credible
     * this frame simply gets nothing — an unobserved joint is not evidence, in either direction.
     */
    private fun accumulateDefinition(frame: PoseFrame) {
        val measuredSide = activeSide ?: return
        for ((index, gate) in spec.definition.withIndex()) {
            val side = when (gate.side) {
                FormCheckGateSide.DRIVER -> measuredSide
                FormCheckGateSide.OPPOSITE -> measuredSide.opposite()
            }
            val sample = FormCheckGeometry.sideSample(frame, side, gate.chain) ?: continue
            gateObservedFrames[index] += 1
            gateWindowMinimum[index] = minOf(gateWindowMinimum[index], sample.includedAngleDegrees)
            gateWindowMaximum[index] = maxOf(gateWindowMaximum[index], sample.includedAngleDegrees)
            if (!gate.satisfied(sample.includedAngleDegrees)) {
                gateViolatingFrames[index] += 1
            }
        }
    }

    /**
     * The first definition clause the completed excursion did not satisfy, with its measured
     * window statistic in whole degrees, or null when the definition held; always resets the
     * windows.
     *
     * A gate observed for fewer than [DEFINITION_MINIMUM_OBSERVED_FRAMES] abstains rather than
     * votes — in the recommended lateral stance an opposite-side clause is usually invisible,
     * and the documented single-side limitation stands there.
     */
    private fun takeDefinitionShortfall(): Pair<FormCheckDefinitionGate, Int>? {
        var shortfall: Pair<FormCheckDefinitionGate, Int>? = null
        for ((index, gate) in spec.definition.withIndex()) {
            if (gateObservedFrames[index] < DEFINITION_MINIMUM_OBSERVED_FRAMES) continue
            val statistic = when (gate.statistic) {
                FormCheckGateStatistic.WINDOW_MINIMUM -> gateWindowMinimum[index]
                FormCheckGateStatistic.WINDOW_MAXIMUM -> gateWindowMaximum[index]
            }
            val failed = if (gate.requiresSustainedReading) {
                gateViolatingFrames[index] >= DEFINITION_MINIMUM_VIOLATING_FRAMES
            } else {
                !gate.satisfied(statistic)
            }
            if (failed) {
                shortfall = gate to statistic.roundToInt().coerceIn(0, 180)
                break
            }
        }
        resetDefinitionWindows()
        return shortfall
    }

    private fun resetDefinitionWindows() {
        gateObservedFrames.fill(0)
        gateWindowMinimum.fill(Double.MAX_VALUE)
        gateWindowMaximum.fill(-Double.MAX_VALUE)
        gateViolatingFrames.fill(0)
    }

    /**
     * Whether the completed excursion's two sides persistently disagreed; always resets the
     * window.
     *
     * Requires enough concurrent frames to mean anything and a divergent majority among them.
     * The majority is what separates a one-sided movement — divergent for essentially the whole
     * excursion — from a real repetition whose far side threw a handful of noisy frames; the
     * generous threshold covers what the far side of a monocular estimate can misread by while
     * still sitting far under the near-90° gap a raised knee opens against a standing leg.
     */
    private fun takeBilateralIncoherence(): Boolean {
        val concurrent = bilateralConcurrentFrames
        val divergent = bilateralDivergentFrames
        resetBilateralWindow()
        if (!spec.bilateralDriver) return false
        if (concurrent < BILATERAL_MINIMUM_CONCURRENT_FRAMES) return false
        return divergent * 2 >= concurrent
    }

    private fun resetBilateralWindow() {
        bilateralConcurrentFrames = 0
        bilateralDivergentFrames = 0
    }

    /**
     * Records one completed excursion. Only the three reported events reach this: an excursion
     * discarded by abstention or by the duration ceiling emits nothing, so nothing is banked for
     * a movement the track did not observe to the end.
     */
    private fun appendMark(
        kind: FormCheckRepEventKind,
        extremeDegrees: Double,
        baselineRelation: FormCheckBaselineRelation,
        guardDegrees: Int?,
    ) {
        val observation = headline ?: return
        if (repMarks.size >= MARK_CAPACITY) repMarks.removeAt(0)
        repMarks.add(
            FormCheckRepMark(
                kind = kind,
                extremeDegrees = extremeDegrees.roundToInt().coerceIn(0, 180),
                baselineRelation = baselineRelation,
                guardDegrees = guardDegrees,
                observation = observation,
            ),
        )
    }

    /**
     * Feeds one frame's guard reading into the excursion window, on the driver's own side.
     *
     * The guard reads the side the count is attributed to — mixing a left shoulder into a right
     * arm's repetition would splice two people's worth of anatomy into one sentence. If that
     * side's guard chain is not credible this frame, the frame contributes nothing; if no frame
     * contributes by the end, the guard abstains entirely.
     */
    private fun accumulateGuard(frame: PoseFrame, guard: FormCheckGuard) {
        val side = activeSide ?: return
        val sample = FormCheckGeometry.sideSample(frame, side, guard.driver) ?: return
        val angle = sample.includedAngleDegrees
        val current = guardWindowDegrees
        guardWindowDegrees = when {
            current == null -> angle
            guard.extreme == FormCheckGuardExtreme.MAX -> maxOf(current, angle)
            else -> minOf(current, angle)
        }
    }

    /**
     * The completed excursion's guard reading in whole degrees when it crossed the limit, or null;
     * always resets the window.
     *
     * The number rather than the sentence, because the surface needs both: the sentence goes into
     * the observation text and the number lets the drawing show which joint the reading belongs
     * to. A null is abstention in both directions — a guard that was never observed says nothing,
     * and one that stayed inside its limit is not news.
     */
    private fun takeGuardCrossing(): Int? {
        val guard = spec.guard ?: return null
        val statistic = guardWindowDegrees
        guardWindowDegrees = null
        if (statistic == null || !guard.crossed(statistic)) return null
        return statistic.roundToInt().coerceIn(0, 180)
    }

    /** Keeps only the set's opening repetitions; later ones are compared, never averaged in. */
    private fun recordBaseline(detectorExtreme: Double) {
        if (baselineSamples.size < BASELINE_REPETITIONS) baselineSamples.add(detectorExtreme)
    }

    /**
     * How far this repetition sat from the set's opening ones, in detector space, or null when
     * there is no baseline yet or the difference is inside what the measurement could have
     * invented. A larger value is always less work.
     *
     * The single place the fifteen-degree floor is applied. The sentence and the mark both read
     * it, so a surface cannot draw a difference the wording refuses to speak.
     */
    private fun baselineShortfall(detectorExtreme: Double): Double? {
        if (baselineSamples.size < BASELINE_REPETITIONS) return null
        val baseline = baselineSamples.sorted().let { sorted ->
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
        }
        val shortfall = detectorExtreme - baseline
        return shortfall.takeIf { abs(it) >= BASELINE_NOTICEABLE_DEGREES }
    }

    /** The same comparison as [baselineNote], quantised for a surface that draws it. */
    private fun baselineRelation(detectorExtreme: Double): FormCheckBaselineRelation {
        val shortfall = baselineShortfall(detectorExtreme)
            ?: return FormCheckBaselineRelation.SAME
        return if (shortfall > 0) {
            FormCheckBaselineRelation.BELOW
        } else {
            FormCheckBaselineRelation.BEYOND
        }
    }

    /**
     * How this repetition sat against the set's opening ones, or null when there is no baseline
     * yet or the difference is inside what the measurement could have invented.
     */
    private fun baselineNote(detectorExtreme: Double): String? {
        val shortfall = baselineShortfall(detectorExtreme) ?: return null
        val magnitude = abs(shortfall).roundToInt()
        val phrase = if (shortfall > 0) {
            spec.vocabulary.belowBaselinePhrase
        } else {
            spec.vocabulary.beyondBaselinePhrase
        }
        return "오늘 첫 반복보다 ${magnitude}도 $phrase"
    }

    private companion object {
        /** The opening repetitions that define the set's own baseline. */
        const val BASELINE_REPETITIONS = 2

        /**
         * How many marks a set keeps. Bounded so a long set cannot grow memory without limit;
         * the oldest go first, which is the end the surface stops showing anyway.
         */
        const val MARK_CAPACITY = 20

        /**
         * Above this a frame's two sides count as disagreeing. Generous by design: it has to sit
         * above any asymmetry a coherent repetition shows plus what the far side of a monocular
         * estimate can misread by, and well under the near-90° gap a raised knee opens against a
         * standing leg. Only a *majority* of divergent frames discards, so this line does not
         * need to be sharp.
         */
        const val BILATERAL_DIVERGENCE_DEGREES = 45.0

        /**
         * Fewer concurrent observations than this and the coherence check abstains entirely —
         * the documented single-side limitation stands wherever the far side is out of view,
         * which in the recommended lateral stance is most of the time.
         */
        const val BILATERAL_MINIMUM_CONCURRENT_FRAMES = 5

        /** A definition gate abstains below this many observed frames, for the same reason. */
        const val DEFINITION_MINIMUM_OBSERVED_FRAMES = 5

        /**
         * How many frames a STAY clause must be outside its bound before the excursion is
         * discarded. One frame is noise, and a STAY clause read from a raw extreme can only be
         * pushed the wrong way by noise — the direction that throws away a real repetition. The
         * per-frame error of these chains has never been measured, which is the reason this
         * exists rather than a reason to skip it.
         */
        const val DEFINITION_MINIMUM_VIOLATING_FRAMES = 3

        /**
         * Below this the difference is not reported. A same-set self-comparison cancels the
         * systematic straightening the bridge card measured, but its random part is unmeasured,
         * so the floor sits clear of the median absolute error rather than at a flattering value.
         */
        const val BASELINE_NOTICEABLE_DEGREES = 15.0
    }
}
