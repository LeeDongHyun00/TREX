package com.example.trex_kotlin

import com.example.trex_kotlin.posture.PostureSetReport

/** 관측 횟수는 자세 합격 판정이나 사용자가 수정한 실제 횟수와 구별해 표시한다. */
internal data class RepObservationPresentation(
    val summary: String?,
    val sides: String?,
    val rangeNote: String?,
) {
    val lines: List<String> get() = listOfNotNull(summary, sides, rangeNote)
}

internal fun PostureSetReport.repObservationPresentation(): RepObservationPresentation = repObservationPresentation(
    total = observedReps?.total ?: repsValid?.let { it + (repsPartial ?: 0) },
    partial = repsPartial,
    left = observedReps?.left,
    right = observedReps?.right,
    both = observedReps?.both,
    unknown = observedReps?.unknown,
    pattern = observedReps?.pattern,
)

internal fun PostureCorrection.repObservationPresentation(): RepObservationPresentation = repObservationPresentation(
    // actualReps는 사용자의 입력이다. 카메라 관측 값을 덮거나 좌우에 임의 배분하지 않는다.
    total = repsValid?.let { it + (repsPartial ?: 0) },
    partial = repsPartial,
    left = observedLeftReps,
    right = observedRightReps,
    both = observedBothReps,
    unknown = observedUnknownReps,
    pattern = repMovementPattern,
)

private fun repObservationPresentation(
    total: Int?, partial: Int?, left: Int?, right: Int?, both: Int?, unknown: Int?, pattern: String?,
): RepObservationPresentation {
    val sides = buildList {
        if (both != null && both > 0) {
            // BOTH는 좌우 각각에도 포함된다. 함께 움직인 횟수를 두 배로 표시하지 않는다.
            add("함께 ${both}회")
            left?.let { if (it > both) add("왼쪽만 ${it - both}회") }
            right?.let { if (it > both) add("오른쪽만 ${it - both}회") }
        } else if (pattern == "LEFT_ONLY") {
            left?.let { add("왼쪽 ${it}회") }
        } else if (pattern == "RIGHT_ONLY") {
            right?.let { add("오른쪽 ${it}회") }
        } else if ((left ?: 0) > 0 || (right ?: 0) > 0) {
            left?.let { add("왼쪽 ${it}회") }
            right?.let { add("오른쪽 ${it}회") }
        }
        if (unknown != null && unknown > 0) add("좌우 미구분 ${unknown}회")
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    return RepObservationPresentation(
        summary = total?.let { "참고 · 관측 ${it}회 · 자세 미확정" },
        sides = sides,
        rangeNote = partial?.takeIf { it > 0 }?.let { "가동 범위 참고 · 기준에 못 미친 관측 ${it}회" },
    )
}
