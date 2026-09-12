package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class RoutineOverviewTest {
    private fun exercise(category: String, seconds: Int, sets: Int = 1, rest: Int = 0) =
        Workout(category, category, "$seconds 초 × $sets 세트", "999분", false, category,
            restSeconds = rest, target = WorkoutTarget.Duration(seconds))

    @Test fun emptyPlanHasNoInventedTime() {
        assertEquals(RoutineOverview(RoutineFocus.EMPTY, 0, 0), routineOverview(emptyList()))
    }
    @Test fun cooldownDoesNotChangeMainFocusButCountsInTime() {
        val summary = routineOverview(listOf(exercise("하체", 120), exercise("회복", 600)))
        assertEquals(RoutineFocus.LOWER, summary.focus); assertEquals(12, summary.minutes)
    }
    @Test fun recoveryOnlyAndEveryCategoryHaveArtwork() {
        for ((category, expected) in listOf("상체" to RoutineFocus.UPPER, "하체" to RoutineFocus.LOWER,
            "복근" to RoutineFocus.CORE, "코어" to RoutineFocus.CORE, "유산소" to RoutineFocus.CARDIO,
            "회복" to RoutineFocus.RECOVERY, "직접 추가" to RoutineFocus.FULL)) {
            assertEquals(expected, routineOverview(listOf(exercise(category, 30))).focus)
        }
    }
    @Test fun similarWeightsAreFullBodyRegardlessOfOrder() {
        val plan = listOf(exercise("하체", 100), exercise("상체", 98))
        assertEquals(RoutineFocus.FULL, routineOverview(plan).focus)
        assertEquals(routineOverview(plan), routineOverview(plan.reversed()))
    }
    @Test fun restDoesNotBiasFocusAndRoundingHappensOnce() {
        val plan = listOf(exercise("상체", 61), exercise("코어", 20, 2, 600))
        val result = routineOverview(plan)
        assertEquals(RoutineFocus.UPPER, result.focus)
        assertEquals(701, result.totalSeconds); assertEquals(12, result.minutes)
        assertEquals(3, result.totalSets)
        assertEquals("2개 운동 · 3세트 · 약 12분", result.detail)
        assertEquals(1, routineOverview(listOf(exercise("하체", 20), exercise("상체", 20))).minutes)
    }
    @Test fun editedRepsPaceAndSetsChangeFocusWithoutChangingCompletion() {
        val upper = Workout("u", "푸쉬업", "10회 × 2세트", "999분", false, "상체", secondsPerRep = 3, restSeconds = 0)
        val lower = exercise("하체", 100)
        assertEquals(RoutineFocus.LOWER, routineOverview(listOf(upper, lower)).focus)
        val edited = upper.copy(secondsPerRep = 10, done = true)
        assertEquals(RoutineFocus.UPPER, routineOverview(listOf(edited, lower)).focus)
        assertEquals(routineOverview(listOf(edited, lower)), routineOverview(listOf(edited.copy(done = false), lower)))
    }
}
