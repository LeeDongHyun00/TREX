package com.trex.engine

/** 좌우 각각은 순서를 강제하지 않는다. 양쪽 한 쌍을 1회로 바꾸는 목표 단위는 별도 기능이다. */
enum class RepMovementPattern { SIMULTANEOUS, ALTERNATING_EACH, LEFT_ONLY, RIGHT_ONLY }

/** UNKNOWN은 전체 왕복은 관측했지만 수행 측을 구분할 근거가 없다는 뜻이다. */
enum class RepSide { LEFT, RIGHT, BOTH, UNKNOWN }

/**
 * 확정해 출력한 이벤트의 집계. BOTH는 left/right를 각각 1 올리고 total은 1만 올린다.
 * UNKNOWN은 left/right를 추측하지 않고 unknown/total만 1 올린다. 정상 자세 횟수가 아니다.
 */
data class ExerciseRepCounts(
    val left: Int = 0,
    val right: Int = 0,
    val both: Int = 0,
    val unknown: Int = 0,
    val total: Int = 0,
)

data class ExerciseRepCycle(val signalFeature: String, val min: Float, val max: Float, val valid: Boolean?)

/** 양쪽의 극값을 섞어 새로운 평균 피처의 ROM 판정으로 만들지 않는다. */
data class ExerciseRepEvent(
    val timeMs: Long,
    val side: RepSide,
    val counts: ExerciseRepCounts,
    val cycles: Map<RepSide, ExerciseRepCycle>,
    val left: RepRecord? = null,
    val right: RepRecord? = null,
    val common: RepRecord? = null,
) {
    val tMs: Long get() = timeMs
    val total: Int get() = counts.total
}

/**
 * 운동별 신호 선택과 횟수 단위를 연결하는 추적기. 신호의 임계값/촬영 방향 검증은 프로필의 책임이다.
 *
 * - 독립 신호는 평균내지 않고 각 측의 엄격한 복귀 FSM으로 처리한다.
 * - 양쪽 함께는 두 복귀가 [simultaneousWindowMs] 안에서 확인됐을 때 BOTH 한 건을 출력한다.
 *   이 창은 짝짓기 정책이며 전체 궤적의 동시성이나 올바른 자세를 인증하지 않는다.
 * - 좌우 각각은 관측된 각 측의 왕복마다 한 건이다. 같은 측을 연속 수행해도 세며,
 *   한 프레임에서 양쪽이 완료되면 LEFT/RIGHT 두 건이다. 목표를 몰래 쌍 단위로 바꾸지 않는다.
 * - commonSignal은 런지처럼 양 관절이 함께 움직여 수행 측을 알 수 없는 전체 신호다.
 *   두 무릎 카운터를 합산하지 않고 UNKNOWN 한 건만 출력한다. 한쪽 목표에는 사용할 수 없다.
 * - 한쪽만은 등록한 그 측의 신호만 사용한다. 런지의 앞/지지 다리는 별도 사용자 선택이
 *   선행되어야 하며, 이 클래스가 무릎 굽힘만 보고 앞다리를 자동으로 알아내지는 않는다.
 *
 * 카메라/측면/동작 변형이 바뀌면 [resetCycle]을 호출한다. 누적 완료 수는 보존한다.
 * [onFrame]에는 같은 단조 시계의 ms와 가시성 검사를 통과한 피처만 넣는다.
 * 가려진 측을 과거 값으로 채워 넣지 않는다. 준비/발화 정책과 폼 평가는 이 클래스 밖이다.
 */
class ExerciseRepTracker(
    val pattern: RepMovementPattern,
    private val leftSignal: RepSignal? = null,
    private val rightSignal: RepSignal? = null,
    private val commonSignal: RepSignal? = null,
    private val simultaneousWindowMs: Long = 600L,
    private val refractoryMs: Long = 1200L,
    private val maxGapMs: Long = 1500L,
) {
    init {
        require(refractoryMs > 0 && maxGapMs > 0)
        require(simultaneousWindowMs >= 0 && simultaneousWindowMs < refractoryMs && simultaneousWindowMs <= maxGapMs)
        require(listOfNotNull(leftSignal, rightSignal, commonSignal).none { it.isometric }) {
            "유지 운동은 반복 추적기에 등록하지 않습니다."
        }
        if (commonSignal != null) {
            require(leftSignal == null && rightSignal == null) { "전체 신호와 독립 좌우 신호를 함께 지정할 수 없습니다." }
            require(pattern == RepMovementPattern.SIMULTANEOUS || pattern == RepMovementPattern.ALTERNATING_EACH) {
                "수행 측을 알 수 없는 전체 신호로 한쪽 목표를 세지 않습니다."
            }
        } else {
            require(when (pattern) {
                RepMovementPattern.LEFT_ONLY -> leftSignal != null
                RepMovementPattern.RIGHT_ONLY -> rightSignal != null
                else -> leftSignal != null && rightSignal != null
            }) { "선택한 수행 방식에 필요한 측의 신호가 없습니다." }
            require(leftSignal == null || rightSignal == null || leftSignal.feature != rightSignal.feature) {
                "같은 평균/전체 신호를 좌우 두 신호로 복제하지 않습니다."
            }
        }
    }

    private fun counter(signal: RepSignal?) = signal?.let {
        ReturnChannel(it, refractoryMs = refractoryMs, maxGapMs = maxGapMs)
    }
    private val leftCounter = counter(leftSignal)
    private val rightCounter = counter(rightSignal)
    private val commonCounter = counter(commonSignal)
    private var pendingLeft: RepRecord? = null
    private var pendingRight: RepRecord? = null
    private var previousTime: Long? = null
    private var currentCounts = ExerciseRepCounts()
    private val completedEvents = arrayListOf<ExerciseRepEvent>()
    private var timingStartIndex = 0
    private var lastMeasurableSegmentPeriodMs: Long? = null

    val counts: ExerciseRepCounts @Synchronized get() = currentCounts
    val events: List<ExerciseRepEvent> @Synchronized get() = completedEvents.toList()
    val repTimesMs: List<Long> @Synchronized get() = completedEvents.map { it.timeMs }
    /** 손실을 가로지르거나 같은 프레임의 좌우 두 완료를 0ms 주기로 표현하지 않는다. */
    val periodMs: Long? @Synchronized get() {
        val times = completedEvents.drop(timingStartIndex).map { it.timeMs }
        if (times.zipWithNext().any { (a, b) -> b <= a }) return null
        return RepMetrics.medianPeriodMs(times)
    }
    /**
     * 마지막으로 주기를 측정할 수 있었던 연속 관측 구간의 스냅샷. 세트 전체 평균이 아니다.
     * 현재 구간에서 완료 2건 이상을 측정했다면 현재 값, 아니면 마지막 종료 구간의 값이다.
     * 일시정지/가림 뒤에도 보존하되 손실을 가로지르는 간격은 만들지 않는다. 좌우 각각 모드에서
     * 같은 프레임에 두 완료가 있는 구간은 단일 주기를 제공하지 않으며 측별 주기를 추측하지 않는다.
     */
    val observedPeriodMs: Long? @Synchronized get() = periodMs ?: lastMeasurableSegmentPeriodMs

    private fun closeTimingSegment() {
        // 두 측을 모두 처리한 완료 이벤트 목록에서만 보존한다. 한 프레임의 첫 emit만 저장하지 않는다.
        periodMs?.let { lastMeasurableSegmentPeriodMs = it }
        timingStartIndex = completedEvents.size
    }
    val signalDescription: String get() = when {
        commonSignal != null -> commonSignal.feature
        pattern == RepMovementPattern.LEFT_ONLY -> leftSignal!!.feature
        pattern == RepMovementPattern.RIGHT_ONLY -> rightSignal!!.feature
        else -> "${leftSignal!!.feature} / ${rightSignal!!.feature}"
    }

    /** 한쪽 손실은 좌우 각각 모드의 반대쪽 관측을 버리지 않는다. 동시 모드는 양쪽 쌍을 무효화한다. */
    @Synchronized
    fun onObservationLost(side: RepSide? = null) {
        closeTimingSegment()
        val all = side == null || side == RepSide.BOTH || side == RepSide.UNKNOWN ||
            commonCounter != null || pattern == RepMovementPattern.SIMULTANEOUS
        if (all || side == RepSide.LEFT) { leftCounter?.onObservationLost(); pendingLeft = null }
        if (all || side == RepSide.RIGHT) { rightCounter?.onObservationLost(); pendingRight = null }
        if (all) commonCounter?.onObservationLost()
    }

    /** 촬영 경계에서는 미확정 쌍과 진행 반복을 버리고 이미 출력한 횟수는 유지한다. */
    @Synchronized fun resetCycle() = onObservationLost()

    /** 새로운 세션에서만 누적 수와 단조 시계의 원점을 초기화한다. */
    @Synchronized
    fun reset() {
        leftCounter?.reset(); rightCounter?.reset(); commonCounter?.reset()
        pendingLeft = null; pendingRight = null; previousTime = null
        currentCounts = ExerciseRepCounts(); completedEvents.clear(); timingStartIndex = 0
        lastMeasurableSegmentPeriodMs = null
    }

    @Synchronized
    fun onFrame(timeMs: Long, features: Map<String, Float>): List<ExerciseRepEvent> {
        val previous = previousTime
        if (previous != null && timeMs <= previous) { onObservationLost(); return emptyList() }
        if (previous != null && timeMs - previous > maxGapMs) onObservationLost()
        previousTime = timeMs

        commonCounter?.let { counter ->
            val completed = advance(counter, timeMs, features) ?: return emptyList()
            return listOf(emit(timeMs, RepSide.UNKNOWN, common = completed))
        }

        if (pattern == RepMovementPattern.LEFT_ONLY) {
            val completed = advance(leftCounter!!, timeMs, features) ?: return emptyList()
            return listOf(emit(timeMs, RepSide.LEFT, left = completed))
        }
        if (pattern == RepMovementPattern.RIGHT_ONLY) {
            val completed = advance(rightCounter!!, timeMs, features) ?: return emptyList()
            return listOf(emit(timeMs, RepSide.RIGHT, right = completed))
        }

        if (pattern == RepMovementPattern.SIMULTANEOUS &&
            (!observable(leftSignal!!, features) || !observable(rightSignal!!, features))) {
            onObservationLost()
            return emptyList()
        }

        val left = advance(leftCounter!!, timeMs, features)
        val right = advance(rightCounter!!, timeMs, features)
        if (pattern == RepMovementPattern.ALTERNATING_EACH) {
            return buildList {
                if (left != null) add(emit(timeMs, RepSide.LEFT, left = left))
                if (right != null) add(emit(timeMs, RepSide.RIGHT, right = right))
            }
        }

        if (pendingLeft?.let { timeMs - it.tMs > simultaneousWindowMs } == true) pendingLeft = null
        if (pendingRight?.let { timeMs - it.tMs > simultaneousWindowMs } == true) pendingRight = null
        if (left != null) pendingLeft = left
        if (right != null) pendingRight = right
        val pairLeft = pendingLeft ?: return emptyList()
        val pairRight = pendingRight ?: return emptyList()
        pendingLeft = null; pendingRight = null
        return listOf(emit(timeMs, RepSide.BOTH, left = pairLeft, right = pairRight))
    }

    private fun observable(signal: RepSignal, features: Map<String, Float>): Boolean {
        val value = features[signal.feature] ?: return false
        return value.isFinite() && (signal.plausibleMin == null || value >= signal.plausibleMin) &&
            (signal.plausibleMax == null || value <= signal.plausibleMax)
    }

    private fun advance(counter: ReturnChannel, timeMs: Long, features: Map<String, Float>): RepRecord? {
        if (!observable(counter.signal, features)) closeTimingSegment()
        if (!counter.onFrame(timeMs, features[counter.signal.feature])) return null
        return RepRecord(timeMs, counter.lastCycleMin, counter.lastCycleMax,
            counter.signal.isValidRep(counter.lastCycleMin, counter.lastCycleMax))
    }

    private fun emit(
        timeMs: Long, side: RepSide, left: RepRecord? = null, right: RepRecord? = null, common: RepRecord? = null,
    ): ExerciseRepEvent {
        currentCounts = currentCounts.copy(
            left = currentCounts.left + if (side == RepSide.LEFT || side == RepSide.BOTH) 1 else 0,
            right = currentCounts.right + if (side == RepSide.RIGHT || side == RepSide.BOTH) 1 else 0,
            both = currentCounts.both + if (side == RepSide.BOTH) 1 else 0,
            unknown = currentCounts.unknown + if (side == RepSide.UNKNOWN) 1 else 0,
            total = currentCounts.total + 1,
        )
        val cycles = buildMap {
            if (left != null) put(RepSide.LEFT, ExerciseRepCycle(leftSignal!!.feature, left.cycleMin, left.cycleMax, left.valid))
            if (right != null) put(RepSide.RIGHT, ExerciseRepCycle(rightSignal!!.feature, right.cycleMin, right.cycleMax, right.valid))
            if (common != null) put(RepSide.UNKNOWN, ExerciseRepCycle(commonSignal!!.feature, common.cycleMin, common.cycleMax, common.valid))
        }
        return ExerciseRepEvent(timeMs, side, currentCounts, cycles, left, right, common).also { completedEvents += it }
    }
}
