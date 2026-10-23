package com.example.trex_kotlin

import com.example.trex_kotlin.posture.ExerciseProfiles
import com.example.trex_kotlin.posture.PostureRuleSet
import com.example.trex_kotlin.posture.PostureScope
import com.example.trex_kotlin.posture.PostureTrial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자세 교정 시험 단계(2026-10-08) — §101c(사용자 결정 2026-10-09 오후)로 집합이 비었다: 꼬리표·시작 문장이 나오지 않고 기계만 남는다. */
class PostureTrialTest {
    private fun workout(name: String) = Workout("t-$name", name, "10회 × 3세트", "7분", true, "복근")

    @Test fun trialSetsAreEmptyAndStayConsistent() {
        assertTrue(PostureTrial.appNames.isEmpty()); assertTrue(PostureTrial.ruleNames.isEmpty())
        assertEquals(PostureTrial.appNames.size, PostureTrial.ruleNames.size)
    }

    @Test fun noListTagForAnyExercise() {
        for (n in listOf("플랭크", "레그 레이즈", "크런치", "기본 스쿼트")) assertFalse(n, workout(n).postureTrial())
        assertTrue(workout("플랭크").postureSupported())
    }

    @Test fun startLineHasNoTrialSentence() {
        val s = PostureScope.of(PostureRuleSet("t", "d", emptyList()), "라잉 레그 레이즈")
        assertFalse(s.startLine!!.contains(PostureTrial.START_LINE))
        assertFalse(s.cardLine.contains(PostureTrial.LABEL))
        assertTrue("프로필은 그대로", ExerciseProfiles.forName("레그 레이즈") != null)
    }
}
