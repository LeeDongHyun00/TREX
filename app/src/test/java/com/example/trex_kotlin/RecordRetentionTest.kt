package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class RecordRetentionTest {
    @Test fun muscleCutoffUsesTheSameLocalCalendarWeekIncludingDst() {
        val zone = kotlinx.datetime.TimeZone.of("America/New_York")
        val now = kotlin.time.Instant.parse("2026-03-10T18:00:00Z").toEpochMilliseconds()
        val cutoff = workoutRetentionStart(now, zone)
        assertEquals(kotlin.time.Instant.parse("2026-03-04T05:00:00Z").toEpochMilliseconds(), cutoff)
        val retained = com.example.trex_kotlin.trainingload.MuscleLoadEngine.snapshot(
            listOf(com.example.trex_kotlin.trainingload.LoadSet("expired", "s", "squat", cutoff - 1, reps = 12.0),
                com.example.trex_kotlin.trainingload.LoadSet("boundary", "s", "squat", cutoff, reps = 12.0)),
            now, oldestAt = cutoff)
        assertEquals(listOf("boundary"), retained.sets.map { it.id })
    }
    private fun day(epoch: Long) = WorkoutHistoryDay(epoch,"","",emptyList(),0,0)
    @Test fun retainsCalendarWeekRatherThanSevenOldRecords() {
        val history = listOf(50L,90L,93L,94L,99L,100L).map(::day)
        assertEquals(listOf(94L,99L,100L),history.retainVisibleWorkoutHistory(100).map { it.epochDay })
        assertEquals(listOf(99L,100L),history.retainVisibleWorkoutHistory(101).map { it.epochDay })
    }
    @Test fun futureDateIsPreservedIfClockMovesBack() {
        assertEquals(listOf(day(102)),listOf(day(102)).retainVisibleWorkoutHistory(100))
    }
    @Test fun writingTodayAlsoDropsExpiredRecords() {
        val merged = listOf(day(10),day(94),day(99),day(100)).replaceTodayWith(day(100))
        assertEquals(listOf(94L,99L,100L),merged.map { it.epochDay })
    }
}
