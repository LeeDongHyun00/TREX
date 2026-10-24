package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class WorkoutEditorValuesTest {
    private val lunge = Workout("lunge", "런지", "10회 × 3세트", "10분", true, "하체", restSeconds = 60)

    @Test fun savedLungeRestIsUsedByEveryRestStep() {
        val edited = lunge.withEditorValues(false, 3, 12, 95)
        assertEquals(95, edited.restSeconds)
        assertEquals(listOf(95, 95), buildSessionSteps(listOf(edited)).filter { it.phase == SessionPhase.REST }.map { it.seconds })
        assertEquals(WorkoutTarget.Repetitions(12), edited.resolvedTarget())
    }

    @Test fun durationGoalAndSetChangesPreserveChosenRestIncludingZero() {
        val edited = lunge.withEditorValues(true, 4, 45, 125)
        assertEquals(listOf(125, 125, 125), buildSessionSteps(listOf(edited)).filter { it.phase == SessionPhase.REST }.map { it.seconds })
        val noRest = edited.withEditorValues(false, 4, 8, 0)
        assertEquals(0, noRest.restSeconds)
        assertTrue(buildSessionSteps(listOf(noRest)).none { it.phase == SessionPhase.REST })
        assertEquals(60, lunge.restSeconds) // 취소 전 원본은 바뀌지 않는다.
    }

    @Test fun plankKeepsOnlyTimeGoals() {
        val legacy = Workout("plank", "플랭크", "12회 × 4세트", "6분", true, "코어", restSeconds = 30)
        val fixed = legacy.timeOnlyNormalized()
        assertEquals(WorkoutTarget.Duration(45), fixed.resolvedTarget()) // 12회를 12초로 읽지 않는다
        assertEquals("45초 × 4세트", fixed.reps)
        assertEquals(30, fixed.restSeconds)
        assertEquals(WorkoutTarget.Duration(45),
            legacy.copy(target = WorkoutTarget.Repetitions(20)).timeOnlyNormalized().resolvedTarget())
        val timed = legacy.withEditorValues(true, 3, 60, 30)
        assertSame(timed, timed.timeOnlyNormalized())
        assertSame(lunge, lunge.timeOnlyNormalized()) // 다른 종목의 횟수 목표는 그대로
    }
}
