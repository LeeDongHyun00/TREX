package com.example.trex_kotlin

import com.example.trex_kotlin.posture.toDinoCopy
import org.junit.Assert.*
import org.junit.Test

class AdaptiveAndDataTest {
    @Test fun narrowAndWideWindowsKeepBothRegionsInsideBounds() {
        for (w in listOf(320, 359, 599, 600, 839, 840, 1200)) {
            for (h in listOf(320, 479, 480, 900)) {
                val r = sessionRegions(w, h, null, 1f)
                listOf(r.camera, r.controls).forEach {
                    assertTrue(it.width > 0 && it.height > 0)
                    assertTrue(it.left >= 0 && it.top >= 0 && it.right <= w && it.bottom <= h)
                }
                assertTrue(r.camera.right <= r.controls.left || r.camera.bottom <= r.controls.top)
            }
        }
    }

    @Test fun offCenterHorizontalHingeUsesItsActualBounds() {
        val r = sessionRegions(840, 900, ContentRect(0, 350, 840, 380), 1f)
        assertEquals(350, r.camera.bottom)
        assertEquals(380, r.controls.top)
        assertEquals(900, r.controls.bottom)
    }

    @Test fun zeroWidthVerticalFoldStillSeparates() {
        val fold = ContentRect(360, 0, 360, 900)
        val r = sessionRegions(840, 900, fold, 1f)
        assertEquals(360, r.camera.right)
        assertEquals(360, r.controls.left)
        val content = singleContentRegion(840, 900, fold, 400)
        assertEquals(ContentRect(400, 0, 800, 900), content)
    }

    @Test fun foldOutsideContentDoesNotShrinkIt() {
        assertNull(splitAroundFold(400, 800, ContentRect(700, 0, 710, 900)))
        assertEquals(ContentRect(0, 0, 400, 800), singleContentRegion(400, 800, null, 600))
    }

    @Test fun dialogueKeepsCautionNumbersAndNouns() {
        assertEquals("주요 관절 확인이 필요 · 3.5초 동안 유지해룡", "주요 관절 확인이 필요 · 3.5초 동안 유지해요".toDinoCopy())
        assertEquals("검증 중이에룡. 정상으로 판정하지 않아룡", "검증 중입니다. 정상으로 판정하지 않아요".toDinoCopy())
        assertEquals("참고 · 아직 검증 중인 항목이에룡", "참고 · 아직 검증 중인 항목이에요".toDinoCopy())
        assertEquals("이미 공룡 말투예룡", "이미 공룡 말투예룡".toDinoCopy())
    }

    @Test fun completedRecordDoesNotInventUnfinishedWorkOrCalories() {
        val done = todayPlan[0].copy(done = true)
        val waiting = todayPlan[1]
        val record = createWorkoutHistoryDay(listOf(done, waiting), 30, elapsedByWorkout = mapOf(done.id to 30))
        assertEquals(1, record.items.size)
        assertEquals(30, record.items.single().durationSeconds)
        assertEquals(0, record.totalMinutes())
        assertEquals(4, record.totalCalories())
        assertEquals(0, waiting.estimatedCalories(0))
        assertFalse(record.summaryText().contains("평소"))
    }

    @Test fun fractionalMinutesAreAddedBeforeRounding() {
        val plan = todayPlan.take(3).map { it.copy(done = true) }
        val record = createWorkoutHistoryDay(plan, 60, elapsedByWorkout = plan.associate { it.id to 20 })
        assertEquals(1, record.totalMinutes())
    }

    @Test fun onlyFullLegacyFingerprintIsRemoved() {
        val sample = WorkoutHistoryDay(20000, "목", "10/3", listOf(
            WorkoutHistoryItem("기본 스쿼트", "12회 x 3세트", 8, 56),
            WorkoutHistoryItem("플랭크", "60초 x 3세트", 5, 25),
        ), 9, 80)
        assertTrue(LegacyDemoData.isSampleDay(sample))
        assertFalse(LegacyDemoData.isSampleDay(sample.copy(averageMinutes = 8)))
        assertFalse(LegacyDemoData.isSampleDay(sample.copy(items = sample.items.map { it.copy(workoutId = "real") })))
        assertFalse(LegacyDemoData.isSampleDiet(mapOf("breakfast" to listOf(FoodEntry("오트밀", Nutrition(150, 27.0, 5.0, 3.0))))))
    }
}
