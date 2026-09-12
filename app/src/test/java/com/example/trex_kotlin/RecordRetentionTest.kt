package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class RecordRetentionTest {
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
