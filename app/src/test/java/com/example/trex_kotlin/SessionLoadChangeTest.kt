package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.*
import org.junit.Assert.*
import org.junit.Test

class SessionLoadChangeTest {
    private val now = 1_790_000_000_000L
    private fun set(id: String, session: String, exercise: String = "squat", reps: Double = 12.0) =
        LoadSet(id, session, exercise, now, reps = reps)

    @Test fun sameTimeComparisonExcludesOnlyCurrentSessionAndUsesNonlinearScore() {
        val old = set("old", "previous").copy(endedAt = now - 3_600_000)
        val current = set("new", "current")
        val other = set("other", "other", "dumbbell_curl")
        val changes = sessionLoadChanges(MuscleLoadEngine.snapshot(listOf(old, current, other), now), "current")
        val quads = changes.first { it.after.muscle == Muscle.QUADS }
        assertEquals(MuscleLoadEngine.score(MuscleLoadEngine.decay(1.0)), quads.before.value, .00001)
        assertEquals(MuscleLoadEngine.score(MuscleLoadEngine.decay(1.0) + 1), quads.after.value, .00001)
        assertTrue(quads.delta < MuscleLoadEngine.score(1.0))
        assertFalse(changes.any { it.after.muscle == Muscle.BICEPS })
    }

    @Test fun firstRecordStaysUnknownBeforeAndMissingSessionHasNoChanges() {
        val snapshot = MuscleLoadEngine.snapshot(listOf(set("new", "current")), now)
        val changes = sessionLoadChanges(snapshot, "current")
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { !it.before.known && it.after.known })
        assertTrue(sessionLoadChanges(snapshot, "").isEmpty())
        assertTrue(sessionLoadChanges(snapshot, "different").isEmpty())
    }

    @Test fun duplicateAndCorrectedRecordsDoNotAddAnExtraContribution() {
        val original = set("new", "current")
        val corrected = original.copy(actualReps = 6.0)
        val changes = sessionLoadChanges(LoadSnapshot(sets = listOf(original, corrected), calculatedAt = now), "current")
        assertEquals(MuscleLoadEngine.score(.5), changes.first { it.after.muscle == Muscle.QUADS }.after.value, .00001)
        assertTrue(sessionLoadChanges(MuscleLoadEngine.snapshot(listOf(original.copy(actualReps = 0.0)), now), "current").isEmpty())
    }

    @Test fun partialTimedWorkAndOneSidedLungeContributeOnlyTheirRecordedAmount() {
        val plank = set("p", "current", "plank", 0.0).copy(unit = LoadUnit.SECONDS, seconds = 15.0)
        val lunge = set("l", "current", "lunge", 0.0).copy(unit = LoadUnit.PAIR, left = 3.0, right = 0.0)
        val changes = sessionLoadChanges(MuscleLoadEngine.snapshot(listOf(plank, lunge), now), "current")
        assertEquals(MuscleLoadEngine.score(1.0 / 3), changes.first { it.after.muscle == Muscle.ABS }.after.value, .00001)
        val quads = changes.first { it.after.muscle == Muscle.QUADS }.after
        assertEquals(MuscleLoadEngine.score(.25), quads.left, .00001)
        assertEquals(0.0, quads.right, .00001)
    }

    @Test fun expiredAndFutureSetsAreNotShownAsCurrentChanges() {
        val set = set("new", "current")
        val records = listOf(set.copy(endedAt = now - MuscleLoadEngine.RETENTION_MS - 1), set.copy(id = "future", endedAt = now + 1))
        assertTrue(sessionLoadChanges(LoadSnapshot(sets = records, calculatedAt = now), "current").isEmpty())
    }
}
