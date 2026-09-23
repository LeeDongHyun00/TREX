package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class AiHubCatalogTest {
    @Test fun catalogDefaultsAndReplacementsStayInside26Exercises() {
        val entries = workoutCatalog.values.flatten()
        assertEquals(26, entries.size)
        assertEquals(postureExerciseMap.keys, entries.map { it.name }.toSet())
        assertEquals(todayPlan, todayPlan.aihubOnly())
        for (entry in entries) {
            val workout = Workout("id", entry.name, entry.reps, entry.duration, true, entry.category)
            assertTrue(recommendedReplacements(workout).all { it.name in postureExerciseMap })
        }
    }

    @Test fun unsupportedPlansCannotRunAndSupportedSettingsSurvive() {
        val valid = Workout("keep", "푸쉬업", "8회 × 2세트", "4분", false, "상체",
            alt = WorkoutAlt("벽 푸쉬업", "12회"), secondsPerRep = 5, restSeconds = 40)
        val removed = valid.copy(id = "remove", name = "기본 스쿼트")
        val before = listOf(removed, valid)
        val after = before.aihubOnly()
        assertEquals(listOf(valid.copy(alt = null)), after)
        assertEquals(after, after.aihubOnly())
        assertEquals(2, before.size)
        assertTrue(buildSessionSteps(before).all { it.workout.name == "푸쉬업" })
        assertTrue(buildSessionSteps(listOf(removed)).isEmpty())
    }
}
