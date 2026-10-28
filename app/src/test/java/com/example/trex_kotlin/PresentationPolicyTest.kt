package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.LoadSnapshot
import com.example.trex_kotlin.trainingload.Muscle
import com.example.trex_kotlin.trainingload.MuscleStatus
import org.junit.Assert.*
import org.junit.Test

class PresentationPolicyTest {
    @Test fun fatigueListOmitsUnusedAndRecoveredMusclesWithoutChangingSnapshot() {
        val snapshot = LoadSnapshot(listOf(
            MuscleStatus(Muscle.PECS, 0.0, 0.0, null),
            MuscleStatus(Muscle.QUADS, 0.0, 0.0, 1L),
            MuscleStatus(Muscle.BICEPS, 12.0, 80.0, 1L),
            MuscleStatus(Muscle.CALVES, .2, 0.0, 1L),
            MuscleStatus(Muscle.LATS, 45.0, 20.0, 1L),
        ))
        assertEquals(listOf(Muscle.BICEPS, Muscle.LATS, Muscle.CALVES), rankedMuscles(snapshot).map { it.muscle })
        assertEquals(5, snapshot.muscles.size)
        assertTrue(rankedMuscles(LoadSnapshot()).isEmpty())
        assertEquals("1점 미만", muscleScoreLabel(.2))
        assertEquals("80점", muscleScoreLabel(80.0))
    }

    @Test fun equalScoresKeepStableMuscleOrder() {
        val a = MuscleStatus(Muscle.BICEPS, 20.0, 0.0, 1L)
        val b = MuscleStatus(Muscle.PECS, 20.0, 0.0, 1L)
        assertEquals(rankedMuscles(LoadSnapshot(listOf(a, b))), rankedMuscles(LoadSnapshot(listOf(b, a))))
    }

    @Test fun swipeFollowsNavigationOrderWithoutWrapping() {
        val tabs = listOf(TrexTab.Home, TrexTab.Workout, TrexTab.Diet, TrexTab.Profile)
        tabs.forEachIndexed { i, tab ->
            assertEquals(tabs.getOrNull(i + 1), swipedTab(tab, -100f, 64f))
            assertEquals(tabs.getOrNull(i - 1), swipedTab(tab, 100f, 64f))
            assertNull(swipedTab(tab, 63f, 64f))
            assertNull(swipedTab(tab, -63f, 64f))
            assertNull(swipedTab(tab, 0f, 64f))
        }
    }
}
