package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

/** §134b — 소모 kcal 의 MET 표: 카탈로그·퇴역 종목·기본 계획이 전부 Compendium 행을 갖고, 모르는 이름은 분류 기본값으로 간다. */
class ExerciseMetTest {
    @Test fun everyCatalogAndRetiredExerciseHasACompendiumRow() {
        val catalog = workoutCatalog.values.flatten().map { it.name }
        assertEquals(72, catalog.size)
        assertEquals(emptyList<String>(), catalog.filterNot(ExerciseMet::hasRow))
        assertEquals(emptyList<String>(), retiredWorkoutCategories.keys.filterNot(ExerciseMet::hasRow))
        assertEquals(emptyList<String>(), todayPlan.map { it.name }.filterNot(ExerciseMet::hasRow))
    }

    @Test fun rowsFollowTheStatedRules() {
        assertEquals("02052", ExerciseMet.of("기본 스쿼트", "하체").code) // 바벨 스쿼트
        assertEquals("02052", ExerciseMet.of("바벨 데드리프트", "하체").code)
        assertEquals("02054", ExerciseMet.of("덤벨 컬", "상체").code)
        assertEquals("02054", ExerciseMet.of("파머스 캐리", "코어").code) // 운반 전용 행 없음
        assertEquals("02056", ExerciseMet.of("런지", "하체").code)
        assertEquals("02056", ExerciseMet.of("푸쉬업", "상체").code)
        assertEquals("02022", ExerciseMet.of("풀업", "상체").code)
        assertEquals("02024", ExerciseMet.of("플랭크", "코어").code)
        assertEquals("02030", ExerciseMet.of("버드독", "코어").code)
        assertEquals("02020", ExerciseMet.of("버피", "유산소").code)
        assertEquals(2.3, ExerciseMet.of("햄스트링 스트레칭", "회복").met, 0.0)
        // 내가 추가한 종목 — 분류 기본값
        assertEquals("02054", ExerciseMet.of("내 종목", "상체").code)
        assertEquals("02024", ExerciseMet.of("내 종목", "복근").code)
        assertEquals("12010", ExerciseMet.of("내 종목", "유산소").code)
        assertEquals("02101", ExerciseMet.of("내 종목", "회복").code)
        assertEquals("02064", ExerciseMet.of("내 종목", null).code)
        assertFalse(ExerciseMet.hasRow("내 종목"))
    }

    @Test fun defaultBodyWeightMatchesProfileDefault() {
        assertEquals(UserProfile().weightKg, DEFAULT_BODY_KG, 0.0)
    }
}
