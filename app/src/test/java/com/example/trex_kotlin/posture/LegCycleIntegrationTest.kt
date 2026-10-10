package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 한 다리 계열(§97)의 앱 쪽 연결 — 규칙셋 강등·범위 카드·짝 누적기·세트 로그. 재생기는 이 파일을 컴파일하지 않는다(PostureRule·세트 로그는 앱 전용). */
class LegCycleIntegrationTest {
    private fun ship(id: String, exercise: String, condition: String, feature: String) = PostureRule(id, exercise, condition, null, RuleStatus.SHIP, "AIHub 세트 창 규칙",
        "${feature}__mean", feature, "mean", "world", ">", 1f, "C", "정면", .8f, .8f, 60, true, emptyList())

    @Test fun legacyShipWindowRulesDemoteToBetaNotExcludeAndGazeStaysShip() {
        val hands = ship("스탠딩 사이드 크런치|양 손이 머리 뒤에 위치", "스탠딩 사이드 크런치", "양 손이 머리 뒤에 위치", "palm_head_dist")
        val gaze = ship("스탠딩 사이드 크런치|시선 정면 유지", "스탠딩 사이드 크런치", "시선 정면 유지", "head_pitch")
        val kneeUp = ship("스탠딩 니업|무릎 충분히 올라오고", "스탠딩 니업", "무릎 충분히 올라오고", "knee_minside")
        val rs = PostureRuleSet("test", "", listOf(hands, gaze, kneeUp)).plusRepForm()
        assertEquals("반복 검사·판별이 같은 질문을 맡는다 — 못 보는 것(EXCLUDE)과 섞지 않는다", RuleStatus.BETA, rs.rules.first { it.id == hands.id }.status)
        assertEquals(RuleStatus.BETA, rs.rules.first { it.id == kneeUp.id }.status)
        assertTrue(rs.rules.first { it.id == hands.id }.cautions.any { "왼손·오른손 머리 위치" in it })
        assertEquals("대신할 검사가 없는 규칙은 그대로", RuleStatus.SHIP, rs.rules.first { it.id == gaze.id }.status)
        // §101a: 니업 '무릎 높이' 만 ship(사용자 정의 게이트 예외 U11), 나머지는 beta
        assertEquals(listOf("repform|스탠딩 니업|무릎 높이"), rs.rules.filter { it.exercise == "스탠딩 니업" && it.kind == "rep_form" && it.status == RuleStatus.SHIP }.map { it.id })
        assertEquals(4, rs.rules.count { it.exercise == "스탠딩 니업" && it.kind == "scope" && it.status == RuleStatus.EXCLUDE })
        val scope = PostureScope.of(rs, "스탠딩 니업")
        assertTrue(scope.hasAnyJudgement); assertFalse(scope.provisionalOnly)
        assertTrue(scope.startLine!!.contains("한 번씩"))
    }

    @Test fun pairedUnitAccumulatorNeedsBothSidesAndSkipsUnknown() {
        val acc = RepUnitAccumulator(RepUnit.SIDE_EACH)
        assertNull(acc.offerSide(1, null, StepSide.LEFT)); assertNull(acc.offerSide(2, null, StepSide.LEFT))
        assertNull("쪽 미확인은 짝에 넣지 않는다", acc.offerSide(3, null, null))
        assertEquals(0, acc.completed); assertTrue(acc.pendingHalf); assertEquals(1L, acc.pendingHalfAtMs)
        assertNotNull(acc.offerSide(4, null, StepSide.RIGHT)); assertEquals(1, acc.completed); assertEquals(2L, acc.pendingHalfAtMs)
        assertNotNull(acc.offerSide(5, null, StepSide.RIGHT)); assertEquals(2, acc.completed); assertFalse(acc.pendingHalf)
        assertTrue(acc.reps.all { it.valid == null })
        for (ex in LegProfile.entries) assertEquals(ex.title, RepUnit.SIDE_EACH, ExerciseProfiles.forName(ex.title)!!.repUnit)
    }

    @Test fun engineLogNamesTheTrackerAndCarriesTheStandingBase() {
        val rc = RepCounter.forSession("스탠딩 니업", floor = false)!!
        val standing = mapOf("knee_L" to 162f, "knee_R" to 164f, "thigh_L" to 175f, "thigh_R" to 175f, LegGeometry.CROSS to 0.6f, LegGeometry.HIP_Y to 0.52f, LegGeometry.TORSO2D to 0.24f)
        assertNull(RepEngineLog.of(rc).standing)
        rc.standingSeedFrom(listOf(-900L to standing, -600L to standing, -300L to standing), 0L)
        val log = RepEngineLog.of(rc)
        assertEquals(LegCycleTracker.VERSION, log.engine)
        assertEquals(LegCycleTracker.MAX_GAP_MS, log.maxGapMs)
        assertEquals(0.24f, log.standing!!.getValue(LegGeometry.TORSO2D), 1e-6f)
        assertNull(log.romThreshold); assertNull(log.seed)
    }
}
