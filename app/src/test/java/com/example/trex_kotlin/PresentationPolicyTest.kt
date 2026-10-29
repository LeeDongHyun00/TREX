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
        // 하단 바 순서 그대로 — 커뮤니티 탭(main §109)이 식단과 내 정보 사이에 있다
        val tabs = listOf(TrexTab.Home, TrexTab.Workout, TrexTab.Diet, TrexTab.Community, TrexTab.Profile)
        assertEquals(tabs, TrexTab.entries.toList())
        val t = TabSwipeThresholds(distance = 64f, flingDistance = 16f, flingVelocity = 400f)
        tabs.forEachIndexed { i, tab ->
            assertEquals(tabs.getOrNull(i + 1), swipedTab(tab, -100f, 0f, t))
            assertEquals(tabs.getOrNull(i - 1), swipedTab(tab, 100f, 0f, t))
            assertNull(swipedTab(tab, 63f, 0f, t))
            assertNull(swipedTab(tab, -63f, 0f, t))
            assertNull(swipedTab(tab, 0f, 0f, t))
        }
    }

    @Test fun quickFlickMovesTabOnlyInTheDraggedDirection() {
        val t = TabSwipeThresholds(distance = 64f, flingDistance = 16f, flingVelocity = 400f)
        assertEquals(TrexTab.Diet, swipedTab(TrexTab.Workout, -20f, -900f, t))  // 짧아도 빠르게 왼쪽이면 다음 탭
        assertEquals(TrexTab.Home, swipedTab(TrexTab.Workout, 20f, 900f, t))
        assertNull(swipedTab(TrexTab.Workout, -10f, -900f, t))                   // 거의 안 움직인 튕김
        assertNull(swipedTab(TrexTab.Workout, -20f, -300f, t))                   // 느리게 조금
        assertNull(swipedTab(TrexTab.Workout, -20f, 900f, t))                    // 밀었다가 되돌아오며 놓음
        assertNull(swipedTab(TrexTab.Profile, -20f, -900f, t))                   // 끝 탭
    }
}
