package com.example.trex_kotlin

import kotlinx.datetime.toLocalDateTime

/** 화면에서 조회하는 오늘 포함 7일. 미래 날짜는 시계 변경 가능성이 있어 삭제하지 않는다. */
const val RECORD_WINDOW_DAYS = com.example.trex_kotlin.trainingload.MuscleLoadEngine.RETENTION_DAYS

fun workoutRetentionStart(now: Long, zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault()): Long {
    val today = kotlin.time.Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date.toEpochDays()
    return startOfDayMillis(today - (RECORD_WINDOW_DAYS - 1), zone)
}
fun List<WorkoutHistoryDay>.retainVisibleWorkoutHistory(today: Long): List<WorkoutHistoryDay> =
    filter { it.epochDay >= today - (RECORD_WINDOW_DAYS - 1) }
