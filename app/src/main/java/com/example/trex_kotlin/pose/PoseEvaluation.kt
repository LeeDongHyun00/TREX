package com.example.trex_kotlin.pose

import com.example.trex_kotlin.catalog.AiHubExercise
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SquatThresholds(
    val readyKneeAngle: Double = 160.0,
    val descentKneeAngle: Double = 152.0,
    val bottomKneeAngle: Double = 110.0,
    val ascentKneeAngle: Double = 132.0,
    val completionKneeAngle: Double = 158.0,
    val maximumTorsoLean: Double = 45.0,
    val maximumKneeAngleDifference: Double = 22.0,
    val minimumKneeTrackingRatio: Double = 0.65,
) {
    init {
        require(bottomKneeAngle < ascentKneeAngle)
        require(ascentKneeAngle < descentKneeAngle)
        require(descentKneeAngle < readyKneeAngle)
        require(completionKneeAngle <= readyKneeAngle)
        require(maximumTorsoLean > 0.0)
        require(maximumKneeAngleDifference > 0.0)
        require(minimumKneeTrackingRatio > 0.0)
    }
}

data class LungeThresholds(
    val readyKneeAngle: Double = 160.0,
    val descentKneeAngle: Double = 152.0,
    val frontBottomKneeAngle: Double = 110.0,
    val rearBottomKneeAngle: Double = 145.0,
    val ascentKneeAngle: Double = 132.0,
    val completionKneeAngle: Double = 158.0,
    val maximumTorsoLean: Double = 35.0,
    val minimumStanceRatio: Double = 0.75,
) {
    init {
        require(frontBottomKneeAngle < ascentKneeAngle)
        require(ascentKneeAngle < descentKneeAngle)
        require(descentKneeAngle < readyKneeAngle)
        require(completionKneeAngle <= readyKneeAngle)
        require(rearBottomKneeAngle < readyKneeAngle)
        require(maximumTorsoLean > 0.0)
        require(minimumStanceRatio > 0.0)
    }
}

/**
 * 판정기의 시간·신뢰도·상태 안정화 설정.
 *
 * 각도 임계값은 시작 기본값일 뿐이다. 실제 배포 전 기기·카메라 각도·사용자군별 검증 데이터로
 * [squat]과 [lunge]를 교정해야 한다.
 */
data class PoseEvaluatorConfig(
    val emaAlpha: Double = 0.45,
    val minimumVisibility: Double = 0.60,
    val minimumPresence: Double = 0.60,
    val stableFrames: Int = 2,
    val maximumTrackingGapMs: Long = 800L,
    val trackingLossGraceMs: Long = 350L,
    val reacquisitionStableFrames: Int = 2,
    val minimumRepDurationMs: Long = 500L,
    val squat: SquatThresholds = SquatThresholds(),
    val lunge: LungeThresholds = LungeThresholds(),
) {
    init {
        require(emaAlpha > 0.0 && emaAlpha <= 1.0)
        require(minimumVisibility in 0.0..1.0)
        require(minimumPresence in 0.0..1.0)
        require(stableFrames > 0)
        require(maximumTrackingGapMs > 0L)
        require(trackingLossGraceMs >= 0L)
        require(reacquisitionStableFrames >= 0)
        require(minimumRepDurationMs >= 0L)
    }
}

interface PoseMotionEvaluator {
    val profile: PoseMotionProfile

    /** 시간 순서대로 들어온 한 프레임을 반영하고 현재 판정 상태를 반환한다. */
    fun accept(frame: PoseFrame): Evaluation

    /** 완료 횟수와 점수는 유지하고 진행 중인 동작·평활 상태만 폐기한다. */
    fun interrupt()

    /** 누적 반복 수와 필터를 포함한 세션 상태 전체를 초기화한다. */
    fun reset()
}

object PoseEvaluatorFactory {
    /** 런타임에서 자세교정 사용을 허용할 AI Hub 운동과 evaluator를 한곳에서 관리한다. */
    private val factories: Map<AiHubExercise, (PoseEvaluatorConfig) -> PoseMotionEvaluator> = mapOf(
        AiHubExercise.BARBELL_SQUAT to ::SymmetricSquatMotionEvaluator,
        AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE to ::AlternatingLungeMotionEvaluator,
        AiHubExercise.STEP_BACKWARD_DYNAMIC_LUNGE to ::AlternatingLungeMotionEvaluator,
    )

    val supportedExercises: Set<AiHubExercise> = factories.keys

    fun supports(exercise: AiHubExercise): Boolean = exercise in supportedExercises

    fun create(
        exercise: AiHubExercise,
        config: PoseEvaluatorConfig = PoseEvaluatorConfig(),
    ): PoseMotionEvaluator? = factories[exercise]?.invoke(config)
}

internal class SymmetricSquatMotionEvaluator(
    config: PoseEvaluatorConfig = PoseEvaluatorConfig(),
) : BasePoseMotionEvaluator(PoseMotionProfile.SYMMETRIC_SQUAT, config) {
    private val thresholds = config.squat
    private val standingHold = ConsecutiveFrameHold(config.stableFrames)
    private val bottomHold = ConsecutiveFrameHold(config.stableFrames)
    private val ascentHold = ConsecutiveFrameHold(config.stableFrames)
    private val completionHold = ConsecutiveFrameHold(config.stableFrames)
    private var repStartTimestampMs: Long? = null
    private var repQuality = SquatRepQuality()

    override fun onTrackedFrame(timestampMs: Long, metrics: PoseMetrics): List<PoseFeedback> {
        val feedback = formFeedback(metrics).toMutableList()
        val kneeAngle = (metrics.leftKneeAngle + metrics.rightKneeAngle) / 2.0

        when (phase) {
            PosePhase.SEEKING -> {
                if (standingHold.update(kneeAngle >= thresholds.readyKneeAngle)) {
                    transitionTo(PosePhase.READY)
                } else {
                    feedback += feedback(
                        PoseFeedbackCode.STAND_TALL,
                        "전신이 보이도록 서서 시작 자세를 잡아 주세요.",
                        PoseFeedbackSeverity.INFO,
                    )
                }
            }

            PosePhase.READY -> {
                if (standingHold.update(kneeAngle < thresholds.descentKneeAngle)) {
                    repStartTimestampMs = timestampMs
                    repQuality = SquatRepQuality().also { it.record(metrics) }
                    transitionTo(PosePhase.DESCENDING)
                }
            }

            PosePhase.DESCENDING -> {
                repQuality.record(metrics)
                when {
                    bottomHold.update(kneeAngle <= thresholds.bottomKneeAngle) -> {
                        transitionTo(PosePhase.BOTTOM)
                    }

                    standingHold.update(kneeAngle >= thresholds.readyKneeAngle) -> {
                        abandonCurrentRep()
                        transitionTo(PosePhase.READY)
                        feedback += feedback(PoseFeedbackCode.GO_DEEPER, "조금 더 깊게 내려가 주세요.")
                    }

                    else -> feedback += feedback(
                        PoseFeedbackCode.DESCEND_WITH_CONTROL,
                        "속도를 조절하며 내려가 주세요.",
                        PoseFeedbackSeverity.INFO,
                    )
                }
            }

            PosePhase.BOTTOM -> {
                repQuality.record(metrics)
                feedback += feedback(PoseFeedbackCode.DRIVE_UP, "발로 바닥을 밀며 올라오세요.")
                if (ascentHold.update(kneeAngle >= thresholds.ascentKneeAngle)) {
                    transitionTo(PosePhase.ASCENDING)
                }
            }

            PosePhase.ASCENDING -> {
                repQuality.record(metrics)
                val duration = timestampMs - (repStartTimestampMs ?: timestampMs)
                when {
                    bottomHold.update(kneeAngle <= thresholds.bottomKneeAngle) -> {
                        transitionTo(PosePhase.BOTTOM)
                    }

                    completionHold.update(
                        kneeAngle >= thresholds.completionKneeAngle &&
                            duration >= config.minimumRepDurationMs,
                    ) -> {
                        completeRep(repQuality.score(thresholds))
                        abandonCurrentRep()
                        transitionTo(PosePhase.READY)
                        feedback += feedback(
                            PoseFeedbackCode.REP_COMPLETE,
                            "좋아요. 한 회 완료했습니다.",
                            PoseFeedbackSeverity.INFO,
                        )
                    }

                    else -> feedback += feedback(PoseFeedbackCode.DRIVE_UP, "끝까지 일어나 주세요.")
                }
            }
        }

        return feedback.distinctBy(PoseFeedback::code)
    }

    override fun onMotionReset() {
        standingHold.reset()
        bottomHold.reset()
        ascentHold.reset()
        completionHold.reset()
        abandonCurrentRep()
    }

    private fun transitionTo(next: PosePhase) {
        phase = next
        standingHold.reset()
        bottomHold.reset()
        ascentHold.reset()
        completionHold.reset()
    }

    private fun abandonCurrentRep() {
        repStartTimestampMs = null
        repQuality = SquatRepQuality()
    }

    private fun formFeedback(metrics: PoseMetrics): List<PoseFeedback> = buildList {
        if (metrics.torsoLean > thresholds.maximumTorsoLean) {
            add(feedback(PoseFeedbackCode.KEEP_CHEST_UP, "상체가 과하게 숙여지지 않도록 해 주세요."))
        }
        if (metrics.kneeAngleDifference > thresholds.maximumKneeAngleDifference) {
            add(feedback(PoseFeedbackCode.KEEP_KNEES_EVEN, "양쪽 무릎을 비슷한 속도로 움직여 주세요."))
        }
        if ((metrics.kneeTrackingRatio ?: 1.0) < thresholds.minimumKneeTrackingRatio) {
            add(feedback(PoseFeedbackCode.KEEP_KNEES_OUT, "무릎이 안쪽으로 모이지 않게 유지해 주세요."))
        }
    }
}

internal class AlternatingLungeMotionEvaluator(
    config: PoseEvaluatorConfig = PoseEvaluatorConfig(),
) : BasePoseMotionEvaluator(PoseMotionProfile.ALTERNATING_LUNGE, config) {
    private val thresholds = config.lunge
    private val standingHold = ConsecutiveFrameHold(config.stableFrames)
    private val descentHold = ConsecutiveFrameHold(config.stableFrames)
    private val bottomHold = ConsecutiveFrameHold(config.stableFrames)
    private val ascentHold = ConsecutiveFrameHold(config.stableFrames)
    private val completionHold = ConsecutiveFrameHold(config.stableFrames)
    private var repStartTimestampMs: Long? = null
    private var repQuality = LungeRepQuality()

    override fun onTrackedFrame(timestampMs: Long, metrics: PoseMetrics): List<PoseFeedback> {
        val feedback = formFeedback(metrics).toMutableList()
        val minimumKnee = min(metrics.leftKneeAngle, metrics.rightKneeAngle)

        when (phase) {
            PosePhase.SEEKING -> {
                val standing = metrics.leftKneeAngle >= thresholds.readyKneeAngle &&
                    metrics.rightKneeAngle >= thresholds.readyKneeAngle
                if (standingHold.update(standing)) {
                    transitionTo(PosePhase.READY)
                } else {
                    feedback += feedback(
                        PoseFeedbackCode.STAND_TALL,
                        "두 다리를 펴고 시작 자세를 잡아 주세요.",
                        PoseFeedbackSeverity.INFO,
                    )
                }
            }

            PosePhase.READY -> {
                if (descentHold.update(minimumKnee < thresholds.descentKneeAngle)) {
                    activeSide = if (metrics.leftKneeAngle <= metrics.rightKneeAngle) {
                        PoseSide.LEFT
                    } else {
                        PoseSide.RIGHT
                    }
                    repStartTimestampMs = timestampMs
                    repQuality = LungeRepQuality().also { it.record(metrics, activeSide!!) }
                    transitionTo(PosePhase.DESCENDING)
                }
            }

            PosePhase.DESCENDING -> {
                val side = activeSide ?: return feedback
                repQuality.record(metrics, side)
                val frontKnee = metrics.kneeAngle(side)
                val rearKnee = metrics.kneeAngle(side.opposite())
                val standing = metrics.leftKneeAngle >= thresholds.readyKneeAngle &&
                    metrics.rightKneeAngle >= thresholds.readyKneeAngle

                when {
                    bottomHold.update(
                        frontKnee <= thresholds.frontBottomKneeAngle &&
                            rearKnee <= thresholds.rearBottomKneeAngle,
                    ) -> transitionTo(PosePhase.BOTTOM)

                    standingHold.update(standing) -> {
                        abandonCurrentRep()
                        transitionTo(PosePhase.READY)
                        feedback += feedback(PoseFeedbackCode.GO_DEEPER, "앞뒤 무릎을 조금 더 굽혀 주세요.")
                    }

                    rearKnee > thresholds.rearBottomKneeAngle -> feedback += feedback(
                        PoseFeedbackCode.LOWER_BACK_KNEE,
                        "뒤쪽 무릎도 함께 낮춰 주세요.",
                    )

                    else -> feedback += feedback(
                        PoseFeedbackCode.DESCEND_WITH_CONTROL,
                        "중심을 유지하며 내려가 주세요.",
                        PoseFeedbackSeverity.INFO,
                    )
                }
            }

            PosePhase.BOTTOM -> {
                val side = activeSide ?: return feedback
                repQuality.record(metrics, side)
                feedback += feedback(PoseFeedbackCode.DRIVE_UP, "앞발로 바닥을 밀며 올라오세요.")
                if (ascentHold.update(metrics.kneeAngle(side) >= thresholds.ascentKneeAngle)) {
                    transitionTo(PosePhase.ASCENDING)
                }
            }

            PosePhase.ASCENDING -> {
                val side = activeSide ?: return feedback
                repQuality.record(metrics, side)
                val frontKnee = metrics.kneeAngle(side)
                val rearKnee = metrics.kneeAngle(side.opposite())
                val duration = timestampMs - (repStartTimestampMs ?: timestampMs)
                when {
                    bottomHold.update(
                        frontKnee <= thresholds.frontBottomKneeAngle &&
                            rearKnee <= thresholds.rearBottomKneeAngle,
                    ) -> transitionTo(PosePhase.BOTTOM)

                    completionHold.update(
                        frontKnee >= thresholds.completionKneeAngle &&
                            rearKnee >= thresholds.completionKneeAngle &&
                            duration >= config.minimumRepDurationMs,
                    ) -> {
                        completeRep(repQuality.score(thresholds), side)
                        abandonCurrentRep()
                        transitionTo(PosePhase.READY)
                        feedback += feedback(
                            PoseFeedbackCode.REP_COMPLETE,
                            "좋아요. 런지 한 회를 완료했습니다.",
                            PoseFeedbackSeverity.INFO,
                        )
                    }

                    else -> feedback += feedback(PoseFeedbackCode.DRIVE_UP, "두 다리를 끝까지 펴 주세요.")
                }
            }
        }

        return feedback.distinctBy(PoseFeedback::code)
    }

    override fun onMotionReset() {
        standingHold.reset()
        descentHold.reset()
        bottomHold.reset()
        ascentHold.reset()
        completionHold.reset()
        abandonCurrentRep()
    }

    private fun transitionTo(next: PosePhase) {
        phase = next
        standingHold.reset()
        descentHold.reset()
        bottomHold.reset()
        ascentHold.reset()
        completionHold.reset()
    }

    private fun abandonCurrentRep() {
        repStartTimestampMs = null
        repQuality = LungeRepQuality()
        activeSide = null
    }

    private fun formFeedback(metrics: PoseMetrics): List<PoseFeedback> = buildList {
        if (metrics.torsoLean > thresholds.maximumTorsoLean) {
            add(feedback(PoseFeedbackCode.KEEP_CHEST_UP, "상체를 세운 채 중심을 유지해 주세요."))
        }
        if ((metrics.stanceRatio ?: Double.POSITIVE_INFINITY) < thresholds.minimumStanceRatio) {
            add(feedback(PoseFeedbackCode.WIDEN_STANCE, "앞뒤 보폭을 조금 더 넓혀 주세요."))
        }
    }
}

abstract class BasePoseMotionEvaluator(
    final override val profile: PoseMotionProfile,
    protected val config: PoseEvaluatorConfig,
) : PoseMotionEvaluator {
    protected var phase: PosePhase = PosePhase.SEEKING
    protected var activeSide: PoseSide? = null

    private val smoother = EmaPoseSmoother(
        alpha = config.emaAlpha,
        minimumConfidence = min(config.minimumVisibility, config.minimumPresence),
        resetAfterMs = config.maximumTrackingGapMs,
    )
    private val visibilityGate = PoseVisibilityGate(
        minimumVisibility = config.minimumVisibility,
        minimumPresence = config.minimumPresence,
    )
    private val completedScores = mutableListOf<Int>()
    private val sideCounts = mutableMapOf(PoseSide.LEFT to 0, PoseSide.RIGHT to 0)
    private var completedReps = 0
    private var lastAcceptedTimestampMs: Long? = null
    private var lastEvaluation: Evaluation? = null
    private var trackingLossStartedAtMs: Long? = null
    private var motionResetForCurrentLoss = false
    private var recoveryFramesRemaining = 0

    final override fun accept(frame: PoseFrame): Evaluation {
        val previousTimestamp = lastAcceptedTimestampMs
        if (previousTimestamp != null && frame.timestampMs <= previousTimestamp) {
            return lastEvaluation ?: snapshot(PoseTrackingState.NO_POSE, emptyList(), null)
        }

        if (previousTimestamp != null && frame.timestampMs - previousTimestamp > config.maximumTrackingGapMs) {
            resetMotion()
            clearTrackingContinuity()
            recoveryFramesRemaining = config.reacquisitionStableFrames
        }
        lastAcceptedTimestampMs = frame.timestampMs

        val smoothedFrame = smoother.smooth(frame)
        if (smoothedFrame.landmarks.isNotEmpty()) {
            val jointsOutsideImage = REQUIRED_JOINTS.filter { joint ->
                val landmark = smoothedFrame.landmarks[joint]
                landmark == null ||
                    landmark.x !in -NORMALIZED_IMAGE_MARGIN..(1.0 + NORMALIZED_IMAGE_MARGIN) ||
                    landmark.y !in -NORMALIZED_IMAGE_MARGIN..(1.0 + NORMALIZED_IMAGE_MARGIN)
            }
            if (jointsOutsideImage.isNotEmpty()) {
                return trackingFailure(
                    timestampMs = frame.timestampMs,
                    trackingState = PoseTrackingState.LOW_CONFIDENCE,
                    feedback = listOf(
                        feedback(
                            PoseFeedbackCode.MOVE_FULL_BODY_INTO_FRAME,
                            "어깨부터 발목까지 화면 안에 들어오게 이동해 주세요.",
                        ),
                    ),
                )
            }
        }
        val gate = visibilityGate.check(smoothedFrame, REQUIRED_JOINTS)
        if (!gate.accepted) {
            val trackingState = if (frame.landmarks.isEmpty() && frame.worldLandmarks.isEmpty()) {
                PoseTrackingState.NO_POSE
            } else {
                PoseTrackingState.LOW_CONFIDENCE
            }
            val feedback = buildList {
                if (gate.missingJoints.isNotEmpty()) {
                    add(feedback(PoseFeedbackCode.MOVE_FULL_BODY_INTO_FRAME, "전신이 화면에 들어오게 이동해 주세요."))
                }
                if (gate.lowConfidenceJoints.isNotEmpty()) {
                    add(feedback(PoseFeedbackCode.LOW_LANDMARK_CONFIDENCE, "관절이 잘 보이도록 자세나 조명을 조정해 주세요."))
                }
            }
            return trackingFailure(frame.timestampMs, trackingState, feedback)
        }

        val metrics = PoseGeometry.metrics(
            landmarks = gate.landmarks,
            coordinateSpace = gate.coordinateSpace,
            imageAspectRatio = smoothedFrame.imageAspectRatio,
            minimumConfidence = gate.minimumConfidence,
        ) ?: return trackingFailure(
            timestampMs = frame.timestampMs,
            trackingState = PoseTrackingState.LOW_CONFIDENCE,
            feedback = listOf(feedback(PoseFeedbackCode.MOVE_FULL_BODY_INTO_FRAME, "전신 관절을 확인할 수 없습니다.")),
        )

        val lossStartedAt = trackingLossStartedAtMs
        if (lossStartedAt != null) {
            val graceExpired = frame.timestampMs - lossStartedAt >= config.trackingLossGraceMs
            if (!motionResetForCurrentLoss && (graceExpired || !isReacquisitionCompatible(metrics))) {
                resetMotion()
                smoother.reset()
            }
            trackingLossStartedAtMs = null
            motionResetForCurrentLoss = false
            recoveryFramesRemaining = config.reacquisitionStableFrames
        }
        if (recoveryFramesRemaining > 0) {
            if (!isReacquisitionCompatible(metrics)) {
                resetMotion()
                smoother.reset()
            }
            recoveryFramesRemaining -= 1
            return snapshot(
                // 좌표는 복구됐지만 연속성이 확인되기 전까지 세션 시간과 FSM을 정지한다.
                trackingState = PoseTrackingState.LOW_CONFIDENCE,
                feedback = emptyList(),
                metrics = metrics,
            ).also { lastEvaluation = it }
        }

        val evaluation = snapshot(
            trackingState = PoseTrackingState.TRACKING,
            feedback = onTrackedFrame(frame.timestampMs, metrics),
            metrics = metrics,
        )
        lastEvaluation = evaluation
        return evaluation
    }

    final override fun reset() {
        completedReps = 0
        completedScores.clear()
        sideCounts[PoseSide.LEFT] = 0
        sideCounts[PoseSide.RIGHT] = 0
        lastAcceptedTimestampMs = null
        lastEvaluation = null
        smoother.reset()
        clearTrackingContinuity()
        resetMotion()
    }

    final override fun interrupt() {
        lastAcceptedTimestampMs = null
        smoother.reset()
        clearTrackingContinuity()
        resetMotion()
    }

    protected abstract fun onTrackedFrame(
        timestampMs: Long,
        metrics: PoseMetrics,
    ): List<PoseFeedback>

    protected abstract fun onMotionReset()

    protected fun completeRep(score: Int, side: PoseSide? = null) {
        completedReps += 1
        completedScores += score.coerceIn(0, 100)
        if (side != null) sideCounts[side] = sideCounts.getValue(side) + 1
    }

    protected fun feedback(
        code: PoseFeedbackCode,
        message: String,
        severity: PoseFeedbackSeverity = PoseFeedbackSeverity.COACHING,
    ): PoseFeedback = PoseFeedback(code, message, severity)

    private fun resetMotion() {
        phase = PosePhase.SEEKING
        activeSide = null
        onMotionReset()
    }

    private fun trackingFailure(
        timestampMs: Long,
        trackingState: PoseTrackingState,
        feedback: List<PoseFeedback>,
    ): Evaluation {
        val lossStartedAt = trackingLossStartedAtMs ?: timestampMs.also {
            trackingLossStartedAtMs = it
        }
        recoveryFramesRemaining = config.reacquisitionStableFrames

        if (!motionResetForCurrentLoss && timestampMs - lossStartedAt >= config.trackingLossGraceMs) {
            // 짧은 가림은 동작을 유지하지만, 지속된 추적 손실 뒤에는 이전 동작을 이어서
            // 가짜 반복을 완성하지 않도록 진행 상태와 평활 이력을 한 번만 폐기한다.
            resetMotion()
            smoother.reset()
            motionResetForCurrentLoss = true
        }

        return snapshot(trackingState, feedback, null).also { lastEvaluation = it }
    }

    private fun clearTrackingContinuity() {
        trackingLossStartedAtMs = null
        motionResetForCurrentLoss = false
        recoveryFramesRemaining = 0
    }

    /** 추적이 끊긴 사이 핵심 단계를 건너뛰었다면 이전 반복을 이어서 세지 않는다. */
    private fun isReacquisitionCompatible(metrics: PoseMetrics): Boolean = when (phase) {
        PosePhase.DESCENDING -> when (profile) {
            PoseMotionProfile.SYMMETRIC_SQUAT ->
                (metrics.leftKneeAngle + metrics.rightKneeAngle) / 2.0 < config.squat.readyKneeAngle

            PoseMotionProfile.ALTERNATING_LUNGE ->
                min(metrics.leftKneeAngle, metrics.rightKneeAngle) < config.lunge.readyKneeAngle
        }

        PosePhase.BOTTOM -> when (profile) {
            PoseMotionProfile.SYMMETRIC_SQUAT ->
                (metrics.leftKneeAngle + metrics.rightKneeAngle) / 2.0 <= config.squat.ascentKneeAngle

            PoseMotionProfile.ALTERNATING_LUNGE -> {
                val side = activeSide
                side != null && (
                    metrics.kneeAngle(side) <= config.lunge.ascentKneeAngle &&
                        metrics.kneeAngle(side.opposite()) <= config.lunge.rearBottomKneeAngle
                    )
            }
        }

        PosePhase.SEEKING,
        PosePhase.READY,
        PosePhase.ASCENDING,
        -> true
    }

    private fun snapshot(
        trackingState: PoseTrackingState,
        feedback: List<PoseFeedback>,
        metrics: PoseMetrics?,
    ): Evaluation = Evaluation(
        profile = profile,
        phase = phase,
        repCount = completedReps,
        trackingState = trackingState,
        feedback = feedback,
        score = completedScores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        lastRepScore = completedScores.lastOrNull(),
        activeSide = activeSide,
        repsBySide = if (profile == PoseMotionProfile.ALTERNATING_LUNGE) sideCounts.toMap() else emptyMap(),
        metrics = metrics,
    )

    private companion object {
        const val NORMALIZED_IMAGE_MARGIN = 0.08

        val REQUIRED_JOINTS = setOf(
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
            PoseJoint.LEFT_HIP,
            PoseJoint.RIGHT_HIP,
            PoseJoint.LEFT_KNEE,
            PoseJoint.RIGHT_KNEE,
            PoseJoint.LEFT_ANKLE,
            PoseJoint.RIGHT_ANKLE,
        )
    }
}

private object PoseGeometry {
    fun metrics(
        landmarks: Map<PoseJoint, PoseLandmark>,
        coordinateSpace: PoseCoordinateSpace,
        imageAspectRatio: Double,
        minimumConfidence: Double,
    ): PoseMetrics? {
        val scaleX = if (coordinateSpace == PoseCoordinateSpace.NORMALIZED_IMAGE) {
            imageAspectRatio
        } else {
            1.0
        }
        val useDepth = coordinateSpace == PoseCoordinateSpace.WORLD

        fun point(joint: PoseJoint): Point3? = landmarks[joint]?.let {
            Point3(it.x * scaleX, it.y, if (useDepth) it.z else 0.0)
        }

        val leftShoulder = point(PoseJoint.LEFT_SHOULDER) ?: return null
        val rightShoulder = point(PoseJoint.RIGHT_SHOULDER) ?: return null
        val leftHip = point(PoseJoint.LEFT_HIP) ?: return null
        val rightHip = point(PoseJoint.RIGHT_HIP) ?: return null
        val leftKnee = point(PoseJoint.LEFT_KNEE) ?: return null
        val rightKnee = point(PoseJoint.RIGHT_KNEE) ?: return null
        val leftAnkle = point(PoseJoint.LEFT_ANKLE) ?: return null
        val rightAnkle = point(PoseJoint.RIGHT_ANKLE) ?: return null

        val leftKneeAngle = angle(leftHip, leftKnee, leftAnkle) ?: return null
        val rightKneeAngle = angle(rightHip, rightKnee, rightAnkle) ?: return null
        val leftHipAngle = angle(leftShoulder, leftHip, leftKnee) ?: return null
        val rightHipAngle = angle(rightShoulder, rightHip, rightKnee) ?: return null
        val shoulderMidpoint = midpoint(leftShoulder, rightShoulder)
        val hipMidpoint = midpoint(leftHip, rightHip)
        val torso = shoulderMidpoint - hipMidpoint
        val torsoLean = Math.toDegrees(atan2(hypot(torso.x, torso.z), abs(torso.y)))

        val shoulderSpan = distance(leftShoulder, rightShoulder)
        val ankleSpan = distance(leftAnkle, rightAnkle)
        val hipHorizontalSpan = abs(leftHip.x - rightHip.x)
        val ankleHorizontalSpan = abs(leftAnkle.x - rightAnkle.x)
        val kneeHorizontalSpan = abs(leftKnee.x - rightKnee.x)
        val kneeReference = max(hipHorizontalSpan, ankleHorizontalSpan)

        return PoseMetrics(
            leftKneeAngle = leftKneeAngle,
            rightKneeAngle = rightKneeAngle,
            leftHipAngle = leftHipAngle,
            rightHipAngle = rightHipAngle,
            torsoLean = torsoLean,
            kneeAngleDifference = abs(leftKneeAngle - rightKneeAngle),
            kneeTrackingRatio = kneeReference.takeIf { it > EPSILON }?.let { kneeHorizontalSpan / it },
            stanceRatio = shoulderSpan.takeIf { it > EPSILON }?.let { ankleSpan / it },
            minimumConfidence = minimumConfidence,
        )
    }

    private fun angle(first: Point3, vertex: Point3, third: Point3): Double? {
        val a = first - vertex
        val b = third - vertex
        val denominator = a.length * b.length
        if (denominator <= EPSILON) return null
        val cosine = ((a dot b) / denominator).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    private fun midpoint(first: Point3, second: Point3) = Point3(
        x = (first.x + second.x) / 2.0,
        y = (first.y + second.y) / 2.0,
        z = (first.z + second.z) / 2.0,
    )

    private fun distance(first: Point3, second: Point3): Double = (first - second).length

    private const val EPSILON = 1e-8
}

private data class Point3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
    infix fun dot(other: Point3): Double = x * other.x + y * other.y + z * other.z
    val length: Double get() = sqrt(x * x + y * y + z * z)
}

private class ConsecutiveFrameHold(private val requiredFrames: Int) {
    private var frames = 0

    fun update(condition: Boolean): Boolean {
        frames = if (condition) frames + 1 else 0
        return frames >= requiredFrames
    }

    fun reset() {
        frames = 0
    }
}

private data class SquatRepQuality(
    var minimumKneeAngle: Double = 180.0,
    var maximumTorsoLean: Double = 0.0,
    var maximumKneeDifference: Double = 0.0,
    var minimumKneeTrackingRatio: Double = Double.POSITIVE_INFINITY,
) {
    fun record(metrics: PoseMetrics) {
        minimumKneeAngle = min(
            minimumKneeAngle,
            (metrics.leftKneeAngle + metrics.rightKneeAngle) / 2.0,
        )
        maximumTorsoLean = max(maximumTorsoLean, metrics.torsoLean)
        maximumKneeDifference = max(maximumKneeDifference, metrics.kneeAngleDifference)
        metrics.kneeTrackingRatio?.let { minimumKneeTrackingRatio = min(minimumKneeTrackingRatio, it) }
    }

    fun score(thresholds: SquatThresholds): Int = weightedScore(
        lowerIsBetterScore(minimumKneeAngle, goodAtOrBelow = 95.0, badAtOrAbove = 135.0) to 0.40,
        lowerIsBetterScore(maximumTorsoLean, goodAtOrBelow = 15.0, badAtOrAbove = thresholds.maximumTorsoLean) to 0.25,
        lowerIsBetterScore(
            maximumKneeDifference,
            goodAtOrBelow = 8.0,
            badAtOrAbove = thresholds.maximumKneeAngleDifference * 1.5,
        ) to 0.20,
        higherIsBetterScore(
            minimumKneeTrackingRatio.takeIf(Double::isFinite) ?: 1.0,
            goodAtOrAbove = 0.90,
            badAtOrBelow = 0.50,
        ) to 0.15,
    )
}

private data class LungeRepQuality(
    var minimumFrontKneeAngle: Double = 180.0,
    var minimumRearKneeAngle: Double = 180.0,
    var maximumTorsoLean: Double = 0.0,
    var minimumStanceRatio: Double = Double.POSITIVE_INFINITY,
) {
    fun record(metrics: PoseMetrics, activeSide: PoseSide) {
        minimumFrontKneeAngle = min(minimumFrontKneeAngle, metrics.kneeAngle(activeSide))
        minimumRearKneeAngle = min(minimumRearKneeAngle, metrics.kneeAngle(activeSide.opposite()))
        maximumTorsoLean = max(maximumTorsoLean, metrics.torsoLean)
        metrics.stanceRatio?.let { minimumStanceRatio = min(minimumStanceRatio, it) }
    }

    fun score(thresholds: LungeThresholds): Int = weightedScore(
        lowerIsBetterScore(minimumFrontKneeAngle, goodAtOrBelow = 95.0, badAtOrAbove = 135.0) to 0.35,
        lowerIsBetterScore(minimumRearKneeAngle, goodAtOrBelow = 125.0, badAtOrAbove = 160.0) to 0.25,
        lowerIsBetterScore(maximumTorsoLean, goodAtOrBelow = 12.0, badAtOrAbove = thresholds.maximumTorsoLean) to 0.25,
        higherIsBetterScore(
            minimumStanceRatio.takeIf(Double::isFinite) ?: 1.0,
            goodAtOrAbove = 1.0,
            badAtOrBelow = 0.55,
        ) to 0.15,
    )
}

private fun PoseMetrics.kneeAngle(side: PoseSide): Double = when (side) {
    PoseSide.LEFT -> leftKneeAngle
    PoseSide.RIGHT -> rightKneeAngle
}

private fun PoseSide.opposite(): PoseSide = when (this) {
    PoseSide.LEFT -> PoseSide.RIGHT
    PoseSide.RIGHT -> PoseSide.LEFT
}

private fun weightedScore(vararg values: Pair<Double, Double>): Int {
    val totalWeight = values.sumOf(Pair<Double, Double>::second)
    if (totalWeight <= 0.0) return 0
    return (values.sumOf { (value, weight) -> value * weight } / totalWeight)
        .roundToInt()
        .coerceIn(0, 100)
}

private fun lowerIsBetterScore(
    value: Double,
    goodAtOrBelow: Double,
    badAtOrAbove: Double,
): Double {
    if (value <= goodAtOrBelow) return 100.0
    if (value >= badAtOrAbove) return 0.0
    return 100.0 * (badAtOrAbove - value) / (badAtOrAbove - goodAtOrBelow)
}

private fun higherIsBetterScore(
    value: Double,
    goodAtOrAbove: Double,
    badAtOrBelow: Double,
): Double {
    if (value >= goodAtOrAbove) return 100.0
    if (value <= badAtOrBelow) return 0.0
    return 100.0 * (value - badAtOrBelow) / (goodAtOrAbove - badAtOrBelow)
}
