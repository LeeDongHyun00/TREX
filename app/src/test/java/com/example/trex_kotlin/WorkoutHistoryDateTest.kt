package com.example.trex_kotlin

import com.example.trex_kotlin.catalog.AiHubExercise
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History used to be regenerated from a clock on every launch, so its dates could not be wrong for
 * long. Now that it survives restarts, the date is the part that has to hold: it decides which
 * record a finished session replaces, how the list is keyed, and whether the screen may call what
 * it is showing "이번 주".
 */
class WorkoutHistoryDateTest {

    private fun dayAt(epochDay: Long, minutes: Int = 10, calories: Int = 60) = WorkoutHistoryDay(
        epochDay = epochDay,
        items = listOf(WorkoutHistoryItem(AiHubExercise.PLANK, "60초 x 3세트", minutes, calories)),
        averageMinutes = minutes,
        averageCalories = calories,
    )

    @Test
    fun labelsAreDerivedFromTheDateRatherThanStoredBesideIt() {
        // 2026-08-13 is a Thursday.
        val epochDay = LocalDate.of(2026, 8, 13).toEpochDay()

        assertEquals("목", koreanDayLabel(epochDay))
        assertEquals("8/13", monthDayLabel(epochDay))
        assertEquals("목", dayAt(epochDay).dayLabel)
        assertEquals("8/13", dayAt(epochDay).dateLabel)
    }

    @Test
    fun everyDayOfTheWeekHasALabel() {
        val start = LocalDate.of(2026, 8, 10).toEpochDay() // Monday
        val labels = (0 until 7).map { koreanDayLabel(start + it) }

        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), labels)
    }

    @Test
    fun recordingTwiceOnOneDayReplacesRatherThanAccumulates() {
        val today = LocalDate.of(2026, 8, 13).toEpochDay()
        val history = listOf(dayAt(today, minutes = 10, calories = 60))

        val updated = history.replaceTodayWith(dayAt(today, minutes = 25, calories = 180))

        assertEquals(1, updated.size)
        assertEquals(25, updated.single().averageMinutes)
    }

    @Test
    fun theSameCalendarDateAYearApartIsADifferentRecord() {
        // The old key was the rendered "8/13", which repeats annually: a user returning after
        // twelve months had their previous record silently overwritten instead of a new one added.
        val lastYear = LocalDate.of(2025, 8, 13).toEpochDay()
        val today = LocalDate.of(2026, 8, 13).toEpochDay()
        val history = listOf(dayAt(lastYear))

        val updated = history.replaceTodayWith(dayAt(today))

        assertEquals(2, updated.size)
        assertEquals(listOf(lastYear, today), updated.map { it.epochDay })
        assertEquals("8/13", updated[0].dateLabel)
        assertEquals("8/13", updated[1].dateLabel)
    }

    @Test
    fun recordsStayChronologicalAndBounded() {
        val start = LocalDate.of(2026, 8, 1).toEpochDay()
        var history = emptyList<WorkoutHistoryDay>()

        // Recorded out of order, as a backfill or a clock change would produce.
        for (offset in listOf(3L, 0L, 5L, 1L, 9L, 2L, 8L, 4L, 7L, 6L)) {
            history = history.replaceTodayWith(dayAt(start + offset))
        }

        assertEquals(WorkoutHistoryRetentionDays, history.size)
        assertEquals(history.map { it.epochDay }.sorted(), history.map { it.epochDay })
        assertEquals(start + 9, history.last().epochDay)
        assertTrue("Retention drops the oldest first", history.none { it.epochDay < start + 3 })
    }

    @Test
    fun theRecordBeingWrittenIsNeverTheOneEvicted() {
        // A phone that boots with a reset clock dates a finished session before everything already
        // stored. Truncating the sorted list would drop that record and the session would vanish
        // with no error, having already shown its completion screen.
        val start = LocalDate.of(2026, 8, 10).toEpochDay()
        val stored = (0L until 7L).map { dayAt(start + it) }

        val backdated = dayAt(start - 400, minutes = 33, calories = 222)
        val updated = stored.replaceTodayWith(backdated)

        assertEquals(WorkoutHistoryRetentionDays, updated.size)
        assertEquals(backdated.epochDay, updated.first().epochDay)
        assertEquals(33, updated.first().averageMinutes)
        // The oldest of the already-stored days is what made room for it.
        assertTrue(updated.none { it.epochDay == start })
    }

    @Test
    fun aFullHistoryStaysFullWhenTheNewestDayIsAdded() {
        val start = LocalDate.of(2026, 8, 10).toEpochDay()
        val stored = (0L until 7L).map { dayAt(start + it) }

        val updated = stored.replaceTodayWith(dayAt(start + 7))

        assertEquals(WorkoutHistoryRetentionDays, updated.size)
        assertEquals(start + 7, updated.last().epochDay)
        assertEquals(start + 1, updated.first().epochDay)
    }

    @Test
    fun everyRetainedRecordHasAUniqueListKey() {
        val start = LocalDate.of(2026, 8, 1).toEpochDay()
        var history = emptyList<WorkoutHistoryDay>()
        for (offset in 0L until 400L) {
            history = history.replaceTodayWith(dayAt(start + offset))
        }

        // The list key is the epoch day precisely because the rendered label is not unique.
        assertEquals(history.size, history.map { it.epochDay }.toSet().size)
    }

    @Test
    fun aWeekOfTrainingCountsAsThisWeek() {
        val today = LocalDate.of(2026, 8, 13).toEpochDay()
        val history = (0L until 7L).map { dayAt(today - it) }

        assertTrue(history.coverOnlyTheLastWeek(today))
    }

    @Test
    fun recordsFromBeforeTheWeekDoNotCountAsThisWeek() {
        // The case the label was lying about: seven recorded days that are not seven recent days.
        val today = LocalDate.of(2026, 8, 13).toEpochDay()
        val history = listOf(dayAt(today - 40), dayAt(today - 39), dayAt(today))

        assertFalse(history.coverOnlyTheLastWeek(today))
    }

    @Test
    fun aRecordExactlySevenDaysOldIsOutsideTheWeek() {
        val today = LocalDate.of(2026, 8, 13).toEpochDay()

        assertTrue(listOf(dayAt(today - 6)).coverOnlyTheLastWeek(today))
        assertFalse(listOf(dayAt(today - 7)).coverOnlyTheLastWeek(today))
    }

    @Test
    fun anEmptyHistoryIsNotThisWeek() {
        // It is not a week of training with nothing in it; the screen shows its empty state.
        assertFalse(emptyList<WorkoutHistoryDay>().coverOnlyTheLastWeek(todayEpochDay()))
    }

    @Test
    fun aRecordDatedInTheFutureDoesNotCountAsThisWeek() {
        // A device clock moved backwards leaves records ahead of "today". Treating those as recent
        // would let the summary claim a week that has not happened.
        val today = LocalDate.of(2026, 8, 13).toEpochDay()

        assertFalse(listOf(dayAt(today + 1)).coverOnlyTheLastWeek(today))
    }

    @Test
    fun afinishedSessionIsDatedTheDayItHappened() {
        val epochDay = LocalDate.of(2026, 8, 13).toEpochDay()

        val record = createWorkoutHistoryDay(todayPlan, elapsedSeconds = 1_200, epochDay = epochDay)

        assertEquals(epochDay, record.epochDay)
        assertEquals("목", record.dayLabel)
    }
}
