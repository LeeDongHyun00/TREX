package com.example.trex_kotlin

import com.example.trex_kotlin.posture.ExerciseProfiles
import com.example.trex_kotlin.posture.PostureRuleSet
import com.example.trex_kotlin.posture.PostureScope
import com.example.trex_kotlin.posture.PostureTrial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자세 교정 시험 단계 종목(크런치·레그 레이즈·플랭크, 사용자 결정 2026-10-08) — 목록 꼬리표·시작 안내가 같은 집합을 본다. */
class PostureTrialTest {
    private fun workout(name: String) = Workout("t-$name", name, "10회 × 3세트", "7분", true, "복근")

    @Test fun trialNamesAreCatalogExercisesAndMapToTheirRuleNames() {
        assertEquals(setOf("크런치", "레그 레이즈", "플랭크"), PostureTrial.appNames)
        for (app in PostureTrial.appNames) {
            assertTrue("프로필 없음: $app", ExerciseProfiles.forName(app) != null)
            assertTrue("규칙 이름 집합과 어긋남: $app", postureExerciseMap.getValue(app) in PostureTrial.ruleNames)
        }
        assertEquals(PostureTrial.appNames.size, PostureTrial.ruleNames.size)
    }

    @Test fun listTagOnlyForTrialExercises() {
        assertTrue(workout("플랭크").postureTrial())
        assertTrue(workout("레그 레이즈").postureTrial())
        assertTrue(workout("크런치").postureTrial())
        assertFalse(workout("기본 스쿼트").postureTrial())
        assertFalse(workout("푸쉬업").postureTrial())
        assertTrue(workout("플랭크").postureSupported())   // 시험 단계여도 카메라는 켤 수 있다
    }

    @Test fun startLineLeadsWithTheTrialSentence() {
        val s = PostureScope.of(PostureRuleSet("t", "d", emptyList()), "라잉 레그 레이즈")
        assertTrue(s.startLine!!.startsWith(PostureTrial.START_LINE))
        assertTrue(s.cardLine.startsWith(PostureTrial.LABEL))
        val squat = PostureScope.of(PostureRuleSet("t", "d", emptyList()), "바벨 스쿼트")
        assertFalse(squat.cardLine.contains(PostureTrial.LABEL))
    }
}
