package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 운동명 → AIHub 규칙 종목 매핑 무결성 (spec §25a — 바닥 종목 연결). */
class PostureExerciseMapTest {

    @Test
    fun floorExercisesAreMapped() {
        // rules_floor_v0.1 에 규칙이 남은 8종목은 세션에서 자세 평가가 켜져야 한다
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
        assertEquals("바벨 스쿼트", postureExerciseMap["바벨 스쿼트"])
        assertEquals("행잉 레그 레이즈", postureExerciseMap["행잉 레그 레이즈"])
        // Bodyweight squat borrowed the barbell standard; it is no longer mapped.
        assertFalse(postureExerciseMap.containsKey("기본 스쿼트"))
        assertEquals(26, postureExerciseMap.size)
        assertEquals(26, postureExerciseMap.values.toSet().size)
    }

    @Test
    fun catalogContainsOnlyAihubMappedExercises() {
        // The catalog is trimmed to AIHub-backed exercises so every pick has a real rule basis.
        val catalog = workoutCatalog.values.flatten()
        assertEquals(postureExerciseMap.keys, catalog.map { it.name }.toSet())
        assertTrue(catalog.all { it.posture && Workout("t", it.name, it.reps, it.duration, true, it.category).postureSupported() })
        assertTrue(todayPlan.all { it.name in postureExerciseMap })
    }
}
