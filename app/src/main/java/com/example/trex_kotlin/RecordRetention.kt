package com.example.trex_kotlin

import kotlinx.datetime.toLocalDateTime

/**
 * 근육 피로도가 보는 창(오늘 포함 7일) — 보관 기간 설정과 무관하게 고정이다(`MuscleLoadEngine.RETENTION_DAYS`, P14 안내문).
 * 운동 기록의 보관은 §131 부터 사용자가 정한다([RetentionOptions], 기본 제한 없음) — 예전에는 기록도 이 7일로 잘라 달력·추이·연속 일수가 비었다.
 */
const val RECORD_WINDOW_DAYS = com.example.trex_kotlin.trainingload.MuscleLoadEngine.RETENTION_DAYS

fun workoutRetentionStart(now: Long, zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault()): Long {
    val today = kotlin.time.Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date.toEpochDays()
    return startOfDayMillis(today - (RECORD_WINDOW_DAYS - 1), zone)
}

/** 보관 기간 밖의 날짜를 뺀다. [retentionDays] 0 이면 전부 남긴다. 미래 날짜는 시계 변경 가능성이 있어 지우지 않는다. */
fun List<WorkoutHistoryDay>.retainVisibleWorkoutHistory(today: Long, retentionDays: Int = RetentionOptions.UNLIMITED): List<WorkoutHistoryDay> =
    if (retentionDays <= 0) this else filter { it.epochDay >= today - (retentionDays - 1) }
