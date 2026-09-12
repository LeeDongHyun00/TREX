package com.example.trex_kotlin.posture

import java.util.Locale
import kotlin.math.abs

/** §35: 초기 자세와의 변화만 측정한다. 초기 자세가 올바르다는 보증은 아니다. */
data class HoldConfig(val up: Float, val down: Float, val baselineMs: Long, val breakMs: Long)
data class RepRuleConfig(val direction: String, val threshold: Float, val fraction: Float, val minimum: Int)

data class HoldSnapshot(
    val baseline: Float?, val measuredMs: Long, val inBandMs: Long,
    val firstBreakMs: Long?, val direction: String?, val samples: Int,
    /** 첫 이탈 이력과 별개인 현재 상태. 가림이면 null, 범위 안이면 0. */
    val currentSide: Int? = null,
    val currentSideMs: Long = 0,
    val breakMs: Long = 2000,
) {
    val text: String get() = if (baseline == null) "초기 안정 자세를 5초간 측정하고 있어요"
        else "초기 자세 대비 범위 내 ${inBandMs / 1000}초 / 측정 ${measuredMs / 1000}초" +
            (firstBreakMs?.let { " · 첫 지속 이탈 ${it / 1000}초 ($direction)" } ?: " · 지속 이탈 미관측")
}

/** 결측·시간 역전·긴 간격은 연속성을 끊는다. 미관측 시간은 정상 시간에 포함하지 않는다. */
class HoldTracker(private val config: HoldConfig) {
    private val initial = ArrayList<Pair<Long, Float>>()
    private var baseline: Float? = null
    private var previous: Long? = null
    private var previousSide = 0
    private var sideStart: Long? = null
    private var measured = 0L
    private var inBand = 0L
    private var firstBreak: Long? = null
    private var firstDirection: String? = null
    private var count = 0
    private var currentSide: Int? = null

    fun add(t: Long, value: Float?) {
        val gap = previous?.let { t - it }
        if (value == null || !value.isFinite() || (gap != null && (gap <= 0 || gap > 750))) {
            currentSide = null
            previous = null; sideStart = null; previousSide = 0
            if (baseline == null) initial.clear()
            if (value == null || !value.isFinite() || (gap != null && gap <= 0)) return
        }
        if (baseline == null) {
            initial.add(t to value)
            // 흔들리는 준비 동작은 초기 기준으로 고정하지 않는다.
            while (initial.size > 1 && t - initial.first().first > config.baselineMs + 750) initial.removeAt(0)
            if (t - initial.first().first >= config.baselineMs && initial.size >= 8) {
                val values = initial.map { it.second }.sorted()
                if (values.last() - values.first() <= minOf(config.up, config.down)) {
                    baseline = values[values.size / 2]
                }
            }
            previous = t
            return
        }
        val delta = value - baseline!!
        val side = when { delta > config.up -> 1; delta < -config.down -> -1; else -> 0 }
        currentSide = side
        val dt = previous?.let { t - it } ?: 0L
        measured += dt
        if (side == 0 && previousSide == 0) inBand += dt
        if (side == 0) sideStart = null
        else if (side != previousSide || sideStart == null) sideStart = t
        if (side != 0 && t - (sideStart ?: t) >= config.breakMs && firstBreak == null) {
            firstBreak = sideStart
            firstDirection = if (side > 0) "화면 위쪽" else "화면 아래쪽"
        }
        previous = t; previousSide = side; count++
    }

    fun snapshot() = HoldSnapshot(baseline, measured, inBand, firstBreak, firstDirection, count,
        currentSide, if (currentSide != null && currentSide != 0) (previous ?: 0) - (sideStart ?: previous ?: 0) else 0,
        config.breakMs)
}

object FloorTemporal {
    val exercises = setOf("푸시업", "니푸쉬업", "플랭크", "크런치", "라잉 레그 레이즈", "힙쓰러스트", "시저크로스", "Y - Exercise")

    fun guide(exercise: String): String = "촬영 · 몸 옆에서 전신을 담아주세요. 기준 · " + when (exercise) {
        "푸시업" -> "머리부터 발목까지 정렬을 유지하며 가슴을 내리고 밀어 올려요."
        "니푸쉬업" -> "무릎을 지지하고 머리부터 무릎까지 정렬을 유지하며 가슴을 내려요."
        "플랭크" -> "팔꿈치를 어깨 아래에 놓고 몸통과 골반의 정렬을 유지해요."
        "힙쓰러스트" -> "상단에서 어깨·골반·무릎을 정렬하고 허리를 과하게 젖히지 않아요."
        "크런치" -> "목만 당기지 않고 상체를 말아 견갑골을 올리고 천천히 내려요."
        "라잉 레그 레이즈" -> "허리가 뜨지 않는 범위에서 무릎 각도를 유지하며 다리를 올리고 내려요."
        "시저크로스" -> "허리와 무릎 각도를 유지하며 다리를 번갈아 교차해요."
        "Y - Exercise" -> "목을 몸통과 정렬하고 엄지를 위로 향해 양팔을 Y자로 들어요."
        else -> "측정 가능한 움직임만 기록해요."
    }

    fun repResult(rule: PostureRule, reps: List<RepRecord>): RuleResult {
        val cfg = rule.repConfig ?: return RuleResult(rule, Verdict.ABSTAIN, null, 0, abstainReason = "반복 기준 없음")
        val observed = reps.filter { it.cycleMin.isFinite() && it.cycleMax.isFinite() }
        val bad = observed.count { if (cfg.direction == "min") it.cycleMin > cfg.threshold else it.cycleMax < cfg.threshold }
        val verdict = when {
            observed.size < cfg.minimum -> Verdict.ABSTAIN
            bad >= cfg.minimum && bad.toFloat() / observed.size >= cfg.fraction -> Verdict.VIOLATION
            else -> Verdict.OK
        }
        return RuleResult(rule, verdict, if (observed.isEmpty()) null else bad.toFloat() / observed.size, observed.size,
            abstainReason = if (verdict == Verdict.ABSTAIN) "완료 반복 ${cfg.minimum}회 필요" else null,
            measurement = "참고 · 검출 ${observed.size}회 중 범위 미달 ${bad}회 · 마지막 반복은 누락될 수 있어요")
    }

    fun holdResult(rule: PostureRule, snapshot: HoldSnapshot): RuleResult = RuleResult(
        rule, if (snapshot.baseline == null || snapshot.measuredMs < 2000) Verdict.ABSTAIN
        else if (snapshot.firstBreakMs != null) Verdict.VIOLATION else Verdict.OK,
        snapshot.baseline, snapshot.samples,
        abstainReason = if (snapshot.baseline == null) "초기 안정 구간 필요" else if (snapshot.measuredMs < 2000) "측정 시간 부족" else null,
        measurement = "참고 · ${snapshot.text} · 올바른 자세 여부는 미확정",
    )

    fun observation(exercise: String, features: Map<String, Float>): String {
        val names = when (exercise) {
            "플랭크" -> listOf("head_trunk_ang" to "투영 고개각")
            "크런치" -> listOf("head_ground" to "머리 들림 근사")
            "Y - Exercise" -> listOf("hand_shoulder_off" to "팔 들림 근사")
            "푸시업" -> listOf("hip_dev_ankle" to "몸통 정렬 변화")
            "니푸쉬업" -> listOf("hip_dev_knee" to "몸통 정렬 변화")
            "라잉 레그 레이즈", "시저크로스" -> listOf("knee_ang" to "투영 무릎각", "ankle_ground" to "발목 높이 근사")
            else -> emptyList()
        }
        return names.mapNotNull { (key, label) -> features[key]?.takeIf { it.isFinite() }?.let {
            "$label ${String.format(Locale.US, "%.2f", it)}"
        } }.joinToString(" · ")
    }
}

/** 마지막 검출 뒤 한 주기까지만 집계. 반복 3개 미만·불규칙 간격이면 추측해서 자르지 않는다. */
object AssessmentWindow {
    fun end(lastFrame: Long, repTimes: List<Long>): Long {
        if (repTimes.size < 3) return lastFrame
        val gaps = repTimes.zipWithNext { a, b -> b - a }
        if (gaps.any { it <= 0 }) return lastFrame
        val period = RepMetrics.medianPeriodMs(repTimes) ?: return lastFrame
        if (gaps.any { abs(it - period) > period / 2 }) return lastFrame
        return minOf(lastFrame, repTimes.last() + period)
    }
}
