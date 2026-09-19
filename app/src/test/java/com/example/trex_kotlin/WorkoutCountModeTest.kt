package com.example.trex_kotlin

import com.example.trex_kotlin.posture.RepMovementPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장한 수행 방식·종목 기본값·화면에 설명하는 횟수 단위가 일치해야 한다. */
class WorkoutCountModeTest {
    private fun workout(name: String, pattern: RepMovementPattern? = null) = Workout(
        id = "count-mode", name = name, reps = "12회 × 1세트", duration = "1분",
        posture = true, category = "테스트", repMovementPattern = pattern,
    )

    @Test fun dumbbellCurlDefaultsToBothReturningAsOne() {
        val workout = workout("덤벨 컬")
        assertEquals(RepMovementPattern.SIMULTANEOUS, workout.resolvedRepPattern())
        assertEquals("양쪽이 함께 왕복하면 1회", workout.repCountExplanation())
        assertNull(workout.repMovementPattern)
    }

    @Test fun alternatingCurlUsesEachSideAsOneWithoutChangingTheTarget() {
        val workout = workout("덤벨 컬", RepMovementPattern.ALTERNATING_EACH)
        assertEquals(RepMovementPattern.ALTERNATING_EACH, workout.resolvedRepPattern())
        assertEquals("한쪽 왕복이 1회 · 목표는 좌우 합계", workout.repCountExplanation())
        assertEquals(12, workout.resolvedTarget().amount)
    }

    @Test fun standingKneeUpDefaultsToEachSide() {
        val workout = workout("스탠딩 니업")
        assertEquals(RepMovementPattern.ALTERNATING_EACH, workout.resolvedRepPattern())
        assertEquals("한쪽 왕복이 1회 · 목표는 좌우 합계", workout.repCountExplanation())
    }

    @Test fun unsupportedStoredPatternFallsBackToTheNewExerciseDefault() {
        val workout = workout("바벨 컬", RepMovementPattern.ALTERNATING_EACH)
        assertEquals(RepMovementPattern.SIMULTANEOUS, workout.resolvedRepPattern())
        assertNull(workout.repCountExplanation())
    }

    @Test fun lungeSideMeansTheSelectedLeadOrSupportingLeg() {
        for (name in listOf("런지", "바벨 런지", "사이드 런지", "크로스 런지")) {
            assertEquals("선택한 왼쪽 앞·지지 다리 기준 · 내려갔다 복귀하면 1회",
                workout(name, RepMovementPattern.LEFT_ONLY).repCountExplanation())
            assertEquals("선택한 오른쪽 앞·지지 다리 기준 · 내려갔다 복귀하면 1회",
                workout(name, RepMovementPattern.RIGHT_ONLY).repCountExplanation())
        }
    }

    @Test fun lungeCommonSignalDoesNotClaimSimultaneousLimbDetection() {
        for (pattern in listOf(RepMovementPattern.SIMULTANEOUS, RepMovementPattern.ALTERNATING_EACH)) {
            val description = workout("바벨 런지", pattern).repCountExplanation()!!
            assertTrue(description.contains("좌우는 구분하지 않고 합계로 기록"))
            assertFalse(description.contains("양쪽이 함께"))
            assertFalse(description.contains("양팔"))
        }
    }

    @Test fun holdsHaveNoRepPatternEvenWithAnOldStoredSelection() {
        for (pattern in listOf(null, RepMovementPattern.ALTERNATING_EACH, RepMovementPattern.LEFT_ONLY)) {
            val workout = workout("플랭크", pattern)
            assertNull(workout.resolvedRepPattern())
            assertNull(workout.repCountExplanation())
        }
    }

    @Test fun unsupportedExerciseHasNoRepModeOrCountHint() {
        val workout = workout("마무리 스트레칭", RepMovementPattern.LEFT_ONLY)
        assertNull(workout.resolvedRepPattern())
        assertNull(workout.repCountExplanation())
    }
}
