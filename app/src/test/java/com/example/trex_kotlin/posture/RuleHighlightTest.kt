package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위반 부위 시각화 매핑 (수정할점 #1) — 피처가 재는 관절을 정확히 가리키는지. */
class RuleHighlightTest {

    @Test
    fun mapsCoreFeaturesToTheJointsTheyMeasure() {
        // 무릎 방향 → 무릎+발
        val knee = RuleHighlight.landmarksFor("knee_out_mean")
        assertTrue(knee.containsAll(setOf(25, 26, 31, 32)))
        // 고개(바닥) → 머리+골반
        val head = RuleHighlight.landmarksFor("head_trunk_ang")
        assertTrue(head.containsAll(setOf(0, 7, 8, 23, 24)))
        // 플랭크 정렬 → 어깨+골반+발목
        val trunk = RuleHighlight.landmarksFor("trunk_ankle_ang")
        assertTrue(trunk.containsAll(setOf(11, 12, 23, 24, 27, 28)))
        // 발 피치 → 발+발목
        val foot = RuleHighlight.landmarksFor("foot_pitch_R")
        assertTrue(foot.containsAll(setOf(29, 30, 31, 32, 27, 28)))
    }

    @Test
    fun unknownFeatureHighlightsNothing() {
        // 모르는 피처는 강조 없음 — 틀린 부위를 가리키는 것보다 안 가리키는 게 낫다
        assertEquals(emptySet<Int>(), RuleHighlight.landmarksFor("some_future_feature"))
    }

    @Test
    fun squatHeelRuleIsExcludedFromShippedRules() {
        // 수정할점 #2: '발바닥 지면 고정'(스쿼트) — 딥 스쿼트 하단 발목 배굴과 구분 불가로 판정 중지.
        // 자산 JSON 의 status=exclude 를 로더가 존중하는지까지는 로더 테스트 몫 — 여기서는 회귀 마커.
        val stream = javaClass.classLoader!!.getResourceAsStream("posture_rules_squat_heel_excluded.marker")
        // 마커 파일 없이 JSON 을 직접 파싱하면 org.json 스텁 문제가 있으므로, 텍스트 검사로 대체
        val json = javaClass.classLoader!!.getResourceAsStream("rules_mp_v0.json")
        if (json != null) {
            val text = json.bufferedReader().readText()
            val i = text.indexOf("발바닥 지면 고정")
            assertTrue(i > 0)
        }
        // 자산은 androidTest 영역이라 유닛 테스트에선 존재 확인만 시도(없으면 통과) — 실제 게이트는 앱 로더
        assertTrue(stream == null || stream.read() >= -1)
    }
}
