package com.trex.engine

data class RepSignal(
    val feature: String,
    val minAmp: Float,
    val isometric: Boolean = false,
    val validated: Boolean = false,
    /**
     * ROM(가동범위) 유효성 — "얕으면 무효 렙" (spec §27 수정판, REP_VALIDITY.md).
     * 임계값은 AIHub **전 조건 정상 클립**의 렙 극값 분포에서 기준 렙 90% 통과 분위수 —
     * 문장 기준을 좌표에 직역하면 정상 스쿼트 98% 가 실격이라, 기준은 반드시 데이터에서.
     * romDirection: "min"=사이클 하단 극값이 임계값 이하여야 유효, "max"=상단 극값이 이상.
     * romValidated: ROM 성격의 AIHub 조건으로 판별력 검증됨(위반 클립 무효율 2.8~3.8배) —
     * 검증 종목만 구체 사유(romCue)를 말하고, 나머지는 방향 중립 사유로.
     */
    val romDirection: String? = null,
    val romThreshold: Float? = null,
    val romValidated: Boolean = false,
    val romCue: String? = null,
    /** 물리 타당 범위 (모집단: AIHub 프레임 분포) — 밖의 값은 측정 붕괴로 보고 일시정지. */
    val plausibleMin: Float? = null,
    val plausibleMax: Float? = null,
    val startMin: Float? = null,
    val startMax: Float? = null,
    val outboundSign: Int = 0,
    /** 함께 움직여야 하는 피처의 최소 변화량. 정자세 판정과 별개다. */
    val supportingMotion: Map<String, Float> = emptyMap(),
    val signChangeFeature: String? = null,
) {
    /** 완료된 렙의 ROM 유효성. null = ROM 기준 없음(항상 유효 취급). */
    fun isValidRep(cycleMin: Float, cycleMax: Float): Boolean? {
        val thr = romThreshold ?: return null
        return when (romDirection) {
            "min" -> cycleMin <= thr
            "max" -> cycleMax >= thr
            else -> null
        }
    }

    val invalidCue: String
        get() = romCue ?: "동작 범위가 부족했어요. 끝까지 움직여 주세요"
}


data class RepRecord(val tMs: Long, val cycleMin: Float, val cycleMax: Float, val valid: Boolean?, val startMs: Long = 0)
object RepMetrics {
    fun medianPeriodMs(times: List<Long>): Long? {
        if (times.size < 2) return null
        val gaps = times.zipWithNext { a, b -> b-a }.sorted()
        return if (gaps.size % 2 == 1) gaps[gaps.size/2] else (gaps[gaps.size/2-1]+gaps[gaps.size/2])/2
    }
}
/** 기존 반전 카운터·규칙 레지스트리에 의존하지 않는 엄격한 복귀 채널. */
internal class ReturnChannel(val signal: RepSignal, refractoryMs: Long, maxGapMs: Long) {
    private val fsm = ReturnRepTracker(signal.minAmp, refractoryMs, maxGapMs, signal.startMin, signal.startMax, signal.outboundSign)
    private val ranges = mutableMapOf<String, Pair<Float, Float>>()
    var lastStartMs = 0L; private set
    var lastCycleMin = 0f; private set
    var lastCycleMax = 0f; private set
    fun reset() = fsm.reset()
    fun onObservationLost() { fsm.onObservationLost(); ranges.clear() }
    fun onFrame(time: Long, value: Float?, features: Map<String, Float> = emptyMap()): Boolean {
        if (value == null || !value.isFinite() || signal.plausibleMin?.let { value < it } == true || signal.plausibleMax?.let { value > it } == true) {
            fsm.onObservationLost(); return false
        }
        val keys = signal.supportingMotion.keys + listOfNotNull(signal.signChangeFeature)
        if (keys.any { features[it]?.isFinite() != true }) { onObservationLost(); return false }
        if (!fsm.moving) ranges.clear()
        for (key in keys) {
            val v = features.getValue(key); val old = ranges[key]
            ranges[key] = minOf(v, old?.first ?: v) to maxOf(v, old?.second ?: v)
        }
        val cycle = fsm.onFrame(time, value) ?: return false
        if (signal.supportingMotion.any { (key, min) -> ranges.getValue(key).let { it.second - it.first < min } }) return false
        if (signal.signChangeFeature?.let { key -> ranges.getValue(key).let { it.first >= -.05f || it.second <= .05f } } == true) return false
        lastCycleMin = cycle.min; lastCycleMax = cycle.max
        lastStartMs = cycle.startMs
        return true
    }
}
