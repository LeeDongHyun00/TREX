package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.*
import org.junit.Assert.*
import org.junit.Test

class SessionMusclesTest {
    private fun set(id: String, session: String, exercise: String = "dumbbell_curl", reps: Double = 12.0) =
        LoadSet(id, session, exercise, 1_790_000_000_000L, reps = reps)

    @Test fun onlyCurrentSessionIsHighlightedAndStrengthDoesNotDependOnReps() {
        val old = set("old", "previous", "squat")
        val current = set("new", "current")
        val expected = setOf("BICEPS_L", "BICEPS_R", "FOREARMS_L", "FOREARMS_R")
        assertEquals(expected, sessionUsedMuscles(listOf(old, current), "current"))
        assertEquals(expected, sessionUsedMuscles(listOf(current.copy(reps = 1.0)), "current"))
        assertTrue(sessionUsedMuscles(listOf(old), "current").isEmpty())
        assertTrue(sessionUsedMuscles(listOf(current), "").isEmpty())
    }

    @Test fun correctedZeroAndUnperformedSetsAreNotHighlighted() {
        val current = set("new", "current")
        assertTrue(sessionUsedMuscles(listOf(current, current.copy(actualReps = 0.0)), "current").isEmpty())
        assertTrue(sessionUsedMuscles(listOf(current.copy(reps = 0.0)), "current").isEmpty())
        assertTrue(sessionUsedMuscles(listOf(current.copy(exerciseId = "unknown")), "current").isEmpty())
    }

    @Test fun oneSidedLungeAndPartialTimedWorkRespectObservedSideAndDuration() {
        val lunge = set("l", "current", "lunge", 0.0).copy(unit = LoadUnit.PAIR, left = 3.0, right = 0.0)
        val used = sessionUsedMuscles(listOf(lunge), "current")
        assertTrue("QUADS_L" in used)
        assertTrue(used.all { it.endsWith("_L") })
        val plank = set("p", "current", "plank", 0.0).copy(unit = LoadUnit.SECONDS, seconds = 5.0)
        assertTrue("ABS_R" in sessionUsedMuscles(listOf(plank), "current"))
        assertTrue(sessionUsedMuscles(listOf(plank.copy(seconds = 0.0)), "current").isEmpty())
    }
}
