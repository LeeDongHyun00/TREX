package com.example.trex_kotlin

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도메인 계산 단일 소스(WorkoutMath) — 화면 3곳에 흩어져 있던 파서/판정의 회귀 방지. */
class WorkoutMathTest {

    @Test
    fun parseRepsHandlesAllPlanFormats() {
        assertEquals(RepsSpec(12, 3, "12회"), parseReps("12회 x 3세트"))
        assertEquals(RepsSpec(60, 3, "60초"), parseReps("60초 x 3세트"))
        assertEquals(RepsSpec(6, 1, "전신 6분"), parseReps("전신 6분"))
        // 세트 표기가 없으면 1세트
        assertEquals(1, parseReps("15회").sets)
        // 숫자가 없으면 최소값으로
        assertEquals(RepsSpec(1, 1, "1회"), parseReps("자유"))
    }

    @Test
    fun formatAndParseRoundTrip() {
        val formatted = formatReps(10, 4)
        assertEquals("10회 x 4세트", formatted)
        val parsed = parseReps(formatted)
        assertEquals(10, parsed.count)
        assertEquals(4, parsed.sets)
    }

    @Test
    fun exerciseSpecMatchesParsedReps() {
        val spec = Workout("id", "스쿼트", "12회 x 3세트", "8분", posture = true, category = "하체").exerciseSpec()
        assertEquals(12, spec.targetReps)
        assertEquals(3, spec.totalSets)
        assertEquals("12회", spec.targetLabel)
    }

    @Test
    fun currentMealIdBoundaries() {
        assertEquals("breakfast", currentMealId(hour = 9))
        assertEquals("lunch", currentMealId(hour = 10))
        assertEquals("snack", currentMealId(hour = 14))
        assertEquals("dinner", currentMealId(hour = 18))
        assertEquals("dinner", currentMealId(hour = 23))
    }

    @Test
    fun recommendedGoalUsesProfileNotHardcodedBody() {
        val small = recommendedNutritionGoal(UserProfile(gender = "female", heightCm = 155.0, weightKg = 48.0, age = 25))
        val large = recommendedNutritionGoal(UserProfile(gender = "male", heightCm = 185.0, weightKg = 90.0, age = 25))
        assertTrue("체격이 크면 목표 칼로리도 커야 한다", large.kcal > small.kcal)
        assertTrue(small.kcal >= 800)
        assertEquals((48.0 * 1.8).toInt().toDouble(), small.protein, 1.0)
    }

    @Test
    fun normalizedGoalClampsToSaneRanges() {
        val g = Nutrition(kcal = 99999, carb = -5.0, protein = 9999.0, fat = 3.2).normalizedGoal()
        assertEquals(5000, g.kcal)
        assertEquals(0.0, g.carb, 0.0)
        assertEquals(400.0, g.protein, 0.0)
    }

    @Test
    fun replaceTodayWithKeyedByEpochDayNotLabel() {
        val today = LocalDate.now().toEpochDay()
        val old = WorkoutHistoryDay(today - 30, "월", "7/25", emptyList(), 10, 100)
        val existing = WorkoutHistoryDay(today, "월", "8/24", emptyList(), 10, 100)
        val record = WorkoutHistoryDay(today, "월", "8/24", listOf(WorkoutHistoryItem("스쿼트", "12회 x 3세트", 8, 56)), 12, 120)

        val updated = listOf(old, existing).replaceTodayWith(record)
        assertEquals("오늘 기록은 교체되어야 한다", 1, updated.count { it.epochDay == today })
        assertEquals(1, updated.first { it.epochDay == today }.items.size)

        val appended = listOf(old).replaceTodayWith(record)
        assertEquals(2, appended.size)
        assertEquals("epochDay 오름차순 정렬", today, appended.last().epochDay)
    }

    @Test
    fun inputFiltersKeepSingleDot() {
        assertEquals("123", "1a2b3".digitsOnly())
        assertEquals("12.57", "12.5.7".decimalOnly())
        assertEquals("125.7", "12ㅁ5.7".decimalOnly())
    }

    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("00:09", 9.asClock())
        assertEquals("02:05", 125.asClock())
    }

    @Test
    fun workoutDurationAndCaloriesDegradeGracefully() {
        val stretch = Workout("s", "스트레칭", "전신 6분", "6분", posture = false, category = "회복")
        assertEquals(6, stretch.durationMinutes())
        assertTrue(stretch.estimatedCalories() >= 24)
        val noNumber = Workout("n", "자유", "자유", "자유", posture = false, category = "기타")
        assertEquals(6, noNumber.durationMinutes())
    }
}
