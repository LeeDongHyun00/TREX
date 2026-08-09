package com.example.trex_kotlin.catalog

import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.canUsePostureSession
import com.example.trex_kotlin.createWorkoutHistoryDay
import com.example.trex_kotlin.estimatedCalories
import com.example.trex_kotlin.pose.release.PostureCorrectionLifecycle
import com.example.trex_kotlin.pose.release.PostureCorrectionRuntimeFacade
import com.example.trex_kotlin.seedWorkoutHistory
import com.example.trex_kotlin.todayPlan
import com.example.trex_kotlin.withPostureCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHubExerciseCatalogTest {
    @Test
    fun generatedCatalogMatchesThe2dManifestSnapshot() {
        val expectedNames = setOf(
            "Y - Exercise",
            "굿모닝",
            "니푸쉬업",
            "덤벨 벤트오버 로우",
            "덤벨 인클라인 체스트 플라이",
            "덤벨 체스트 플라이",
            "덤벨 컬",
            "덤벨 풀 오버",
            "딥스",
            "라잉 레그 레이즈",
            "라잉 트라이셉스 익스텐션",
            "랫풀 다운",
            "로잉머신",
            "바벨 데드리프트",
            "바벨 런지",
            "바벨 로우",
            "바벨 스쿼트",
            "바벨 스티프 데드리프트",
            "바벨 컬",
            "바이시클 크런치",
            "버피 테스트",
            "사이드 런지",
            "사이드 레터럴 레이즈",
            "스탠딩 니업",
            "스탠딩 사이드 크런치",
            "스텝 백워드 다이나믹 런지",
            "스텝 포워드 다이나믹 런지",
            "시저크로스",
            "업라이트로우",
            "오버 헤드 프레스",
            "케이블 크런치",
            "케이블 푸시 다운",
            "크런치",
            "크로스 런지",
            "페이스 풀",
            "푸시업",
            "풀업",
            "프런트 레이즈",
            "플랭크",
            "행잉 레그 레이즈",
            "힙쓰러스트",
        )
        val entries = AiHubExercise.entries
        val typeCodes = entries.flatMap(AiHubExercise::typeCodes)

        assertEquals(41, entries.size)
        assertEquals(expectedNames, entries.map(AiHubExercise::displayName).toSet())
        assertEquals(entries.size, entries.map(AiHubExercise::id).toSet().size)
        assertEquals(34_468, entries.sumOf(AiHubExercise::recordCount))
        assertEquals(816, typeCodes.size)
        assertEquals((1..816).map { it.toString().padStart(3, '0') }.toSet(), typeCodes.toSet())
        assertEquals("fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c", AiHubExercise.CATALOG_SHA256)
    }

    @Test
    fun everyUserVisiblePlanExerciseAndAlternativeBelongsToTheCatalog() {
        val catalog = AiHubExercise.entries.toSet()

        assertTrue(todayPlan.all { it.exercise in catalog })
        assertTrue(todayPlan.mapNotNull { it.alt?.exercise }.all { it in catalog })
        assertTrue(todayPlan.map { it.name }.all { it in AiHubExercise.byDisplayName })
        assertTrue(seedWorkoutHistory().flatMap { it.items }.all { it.exercise in catalog })
        assertFalse(todayPlan.any(Workout::posture))
        assertFalse(todayPlan.any(Workout::canUsePostureSession))
    }

    @Test
    fun catalogCoverageDoesNotAuthorizeAUserPostureSession() {
        assertEquals(
            AiHubExercise.entries.toSet(),
            PostureCorrectionRuntimeFacade.catalogedExercises,
        )
        assertTrue(PostureCorrectionRuntimeFacade.userSelectableExercises.isEmpty())
        assertTrue(
            AiHubExercise.entries.all { exercise ->
                val availability = PostureCorrectionRuntimeFacade.availability(exercise)
                availability.lifecycle == PostureCorrectionLifecycle.CATALOG_ONLY &&
                    !availability.userSelectable &&
                    !availability.sessionOpenAllowed
            },
        )

        AiHubExercise.entries.forEach { exercise ->
            val attemptedEnable = Workout(
                exercise = exercise,
                reps = "1회 x 1세트",
                duration = "1분",
                posture = false,
            ).withPostureCorrection(true)
            assertFalse(attemptedEnable.posture)
            assertFalse(attemptedEnable.canUsePostureSession())

            assertThrows(IllegalArgumentException::class.java) {
                Workout(
                    exercise = exercise,
                    reps = "1회 x 1세트",
                    duration = "1분",
                    posture = true,
                )
            }
        }
    }

    @Test
    fun workoutPreferenceAloneNeverCreatesAStoredPostureClaim() {
        val history = createWorkoutHistoryDay(todayPlan, elapsedSeconds = 300)

        assertTrue(history.items.all { it.postureCorrection == null })
        assertTrue(seedWorkoutHistory(todayPlan).flatMap { it.items }.all {
            it.postureCorrection == null
        })
    }

    @Test
    fun caloriesUseAiHubTypeInfoCategories() {
        val cases = listOf(
            AiHubExercise.BARBELL_SQUAT to 80,
            AiHubExercise.ROWING_MACHINE to 70,
            AiHubExercise.PLANK to 60,
        )

        cases.forEach { (exercise, calories) ->
            val workout = Workout(
                exercise = exercise,
                reps = "10회 x 1세트",
                duration = "10분",
                posture = false,
            )
            assertEquals(calories, workout.estimatedCalories())
        }
        assertEquals(
            AiHubExercise.entries.map(AiHubExercise::typeInfoType).distinct().toSet(),
            setOf("바벨/덤벨", "기구", "맨몸 운동"),
        )
    }
}
