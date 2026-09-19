package com.example.trex_kotlin

import com.example.trex_kotlin.posture.ExerciseRepProfiles
import com.example.trex_kotlin.posture.ExerciseProfiles
import com.example.trex_kotlin.posture.RepMovementPattern
import com.example.trex_kotlin.posture.RepSideAttribution

/** 저장한 수행 방식은 해당 종목에서 지원하는 경우에만 적용한다. 종목 변경/구형 저장값은 기본값으로 복원한다. */
fun Workout.repProfile() = ExerciseRepProfiles.forExercise(ExerciseProfiles.forName(name)?.referenceExercise ?: name)

fun Workout.resolvedRepPattern(): RepMovementPattern? = repProfile()?.takeUnless { it.isometric }?.let { profile ->
    repMovementPattern?.takeIf { it in profile.allowedPatterns } ?: profile.defaultPattern
}

/** 런지의 전체 관측은 좌우 역할 자동 판별과 구분한다. */
fun Workout.repPatternLabel(pattern: RepMovementPattern): String =
    if (repProfile()?.sideAttribution == RepSideAttribution.USER_DECLARED_LEAD) when (pattern) {
        RepMovementPattern.SIMULTANEOUS, RepMovementPattern.ALTERNATING_EACH -> "전체 횟수"
        RepMovementPattern.LEFT_ONLY -> "왼쪽 기준"
        RepMovementPattern.RIGHT_ONLY -> "오른쪽 기준"
    } else pattern.displayName()

fun RepMovementPattern.displayName(): String = when (this) {
    RepMovementPattern.SIMULTANEOUS -> "양쪽 함께"
    RepMovementPattern.ALTERNATING_EACH -> "좌우 각각"
    RepMovementPattern.LEFT_ONLY -> "왼쪽만"
    RepMovementPattern.RIGHT_ONLY -> "오른쪽만"
}

/** 숫자 목표의 단위를 설정/준비/수동 보정/라이브에서 동일하게 보여 준다. */
fun Workout.repCountExplanation(): String? {
    val profile = repProfile() ?: return null
    if (profile.allowedPatterns.size <= 1) return null
    val pattern = resolvedRepPattern()
    if (profile.sideAttribution == RepSideAttribution.USER_DECLARED_LEAD) {
        return when (pattern) {
            RepMovementPattern.LEFT_ONLY -> "선택한 왼쪽 앞·지지 다리 기준 · 내려갔다 복귀하면 1회"
            RepMovementPattern.RIGHT_ONLY -> "선택한 오른쪽 앞·지지 다리 기준 · 내려갔다 복귀하면 1회"
            RepMovementPattern.SIMULTANEOUS, RepMovementPattern.ALTERNATING_EACH -> "내려갔다 복귀하면 1회 · 좌우는 구분하지 않고 합계로 기록"
            null -> null
        }
    }
    return when (pattern) {
        RepMovementPattern.SIMULTANEOUS -> if (profile.commonSignal != null) "왕복 1회 · 좌우는 구분하지 않고 합계로 기록" else "양쪽이 함께 왕복하면 1회"
        RepMovementPattern.ALTERNATING_EACH -> "한쪽 왕복이 1회 · 목표는 좌우 합계"
        RepMovementPattern.LEFT_ONLY -> "왼쪽 왕복만 1회로 기록"
        RepMovementPattern.RIGHT_ONLY -> "오른쪽 왕복만 1회로 기록"
        null -> null
    }
}
