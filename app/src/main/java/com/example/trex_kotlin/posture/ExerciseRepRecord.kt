package com.example.trex_kotlin.posture

/** 측별 원시 반복 근거. tMs는 바깥 RepRecord와 같은 시계/상대 원점을 사용한다. */
data class RepCycleDetail(
    val signalFeature: String,
    val tMs: Long,
    val cycleMin: Float,
    val cycleMax: Float,
    val valid: Boolean?,
)

/**
 * 새 측별 이벤트를 기존 렙 배열과 함께 기록한다.
 * BOTH는 서로 다른 두 피처의 극값을 한 ROM으로 해석할 수 없어 구형 극값/판정을 비운다.
 * 단일 측과 전체 신호는 원래 완료 시각·극값·판정을 유지한다. 원본 이벤트는 바꾸지 않는다.
 */
fun ExerciseRepEvent.toRepRecord(): RepRecord {
    val sources = when (side) {
        RepSide.LEFT -> mapOf(RepSide.LEFT to requireNotNull(left))
        RepSide.RIGHT -> mapOf(RepSide.RIGHT to requireNotNull(right))
        RepSide.UNKNOWN -> mapOf(RepSide.UNKNOWN to requireNotNull(common))
        RepSide.BOTH -> mapOf(RepSide.LEFT to requireNotNull(left), RepSide.RIGHT to requireNotNull(right))
    }
    val rawDetails = sources.mapValues { (sourceSide, raw) ->
        RepCycleDetail(cycles.getValue(sourceSide).signalFeature, raw.tMs, raw.cycleMin, raw.cycleMax, raw.valid)
    }
    val record = if (side == RepSide.BOTH) RepRecord(timeMs, Float.NaN, Float.NaN, null) else sources.values.single()
    return record.copy(side = side, details = rawDetails)
}

/**
 * 세트 시작 기준으로 바깥 완료 시각과 모든 측별 시각을 함께 옮긴다.
 * 음수를 0으로 자르거나 순서를 바꾸지 않는다. 호출부가 같은 단조 시계의 원점을 전달해야 한다.
 */
fun RepRecord.relativeTo(t0: Long): RepRecord = copy(
    tMs = tMs - t0,
    details = details?.mapValues { (_, raw) -> raw.copy(tMs = raw.tMs - t0) },
)
