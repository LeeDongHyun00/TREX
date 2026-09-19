package com.example.trex_kotlin.posture

/** 카메라가 관측한 횟수다. 사용자가 보정한 목표 횟수와 합치거나 좌우에 나누어 배분하지 않는다. */
data class RepObservationSummary(
    val pattern: String,
    val total: Int,
    /** BOTH 반복은 양쪽 각각에도 한 번씩 포함된다. left+right를 총 목표 횟수로 다시 합산하면 안 된다. */
    val left: Int,
    val right: Int,
    val both: Int,
    val unknown: Int,
) {
    init { require(listOf(total, left, right, both, unknown).all { it >= 0 }) }

    val detail: String get() = buildList {
        if (left > 0 || right > 0) add("왼쪽 ${left}회 · 오른쪽 ${right}회")
        if (unknown > 0) add("좌우 미구분 ${unknown}회")
    }.joinToString(" · ")
}

/** 동시 반복은 좌우에 각각 남지만 총횟수는 tracker가 확정한 값을 사용한다. */
fun ExerciseRepCounts.summary(pattern: RepMovementPattern) = RepObservationSummary(
    pattern.name, total, left, right, both, unknown,
)
