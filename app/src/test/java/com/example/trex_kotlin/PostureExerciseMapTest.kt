package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 운동명 → AIHub 규칙 종목 매핑 무결성 (spec §25a — 바닥 종목 연결). */
class PostureExerciseMapTest {

    @Test
    fun floorExercisesAreMapped() {
        // Reference mappings survive independently of the camera rollout scope.
        val expected = mapOf(
            "푸쉬업" to "푸시업",
            "니 푸쉬업" to "니푸쉬업",
            "플랭크" to "플랭크",
            "크런치" to "크런치",
            "레그 레이즈" to "라잉 레그 레이즈",
            "힙 쓰러스트" to "힙쓰러스트",
            "시저 크로스" to "시저크로스",
            "Y 레이즈" to "Y - Exercise",
        )
        for ((app, aihub) in expected) {
            assertEquals("매핑 누락/불일치: $app", aihub, postureExerciseMap[app])
        }
    }

    @Test
    fun bicycleCrunchIsNotMapped() {
        // MP 충실도 게이트 후 규칙 0개 — 지원한다고 표시하면 안 된다
        assertFalse(postureExerciseMap.containsKey("바이시클 크런치"))
    }

    @Test
    fun standingExercisesStillMapped() {
        assertEquals("바벨 스쿼트", postureExerciseMap["기본 스쿼트"])
        assertEquals("행잉 레그 레이즈", postureExerciseMap["행잉 레그 레이즈"])
        // 옛 표시 이름 "바벨 스쿼트" 는 map 키가 아니다(앱 이름은 "기본 스쿼트", 값이 AIHub 이름) — 저장된 옛 이름은 WorkoutNames.canonical 이 바꾼다
        assertFalse(postureExerciseMap.containsKey("바벨 스쿼트"))
        assertEquals("기본 스쿼트", WorkoutNames.canonical("바벨 스쿼트"))
        assertEquals(26, postureExerciseMap.size)
        assertEquals(26, postureExerciseMap.values.toSet().size)
    }

    @Test
    fun catalogHoldsAihubMappedExercisesPlusCameraOffExpansion() {
        // §130: AIHub 26 종은 전부 map 에 있고, 나머지 46 종은 map 에도 프로필에도 없어 카메라가 꺼진다(posture=false, postureSupported 거짓).
        val catalog = workoutCatalog.values.flatten()
        val names = catalog.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(72, names.size)
        assertTrue(postureExerciseMap.keys.all { it in names })
        val expansion = catalog.filter { it.name !in postureExerciseMap }
        assertEquals(46, expansion.size)
        assertTrue(expansion.all { !it.posture })
        assertTrue(expansion.all { com.example.trex_kotlin.posture.ExerciseProfiles.forName(it.name) == null })
        assertEquals(16, catalog.count { it.posture })
        assertTrue(catalog.all { it.posture == Workout("t", it.name, it.reps, it.duration, true, it.category).postureSupported() })
        assertTrue(todayPlan.all { it.name in postureExerciseMap })
    }
    @Test fun cameraScopeMatchesSixteenDedicatedGuides() {
        val expected = ExerciseGuides.all.map { it.name }.toSet()
        val actual = com.example.trex_kotlin.posture.ExerciseProfiles.all.filter { it.cameraEnabled }.map { it.name }.toSet()
        assertEquals(16, expected.size)
        assertEquals(expected, actual)
        (postureExerciseMap.keys - expected + setOf("", "unknown", "바벨 스쿼트")).forEach { name ->
            assertFalse(name, Workout("stored", name, "10회", "5분", true, "상체").postureSupported())
        }
    }
}
