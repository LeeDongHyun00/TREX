package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 바닥 규칙의 정상-앵커 재배치 (rules_floor_v0.2 personal_baseline, spec §25a).
 *
 * 임계값은 AIHub 채택 뷰의 투영에 묶여 있다. 사용자의 폰 시점에서 찍은 정자세 기준선으로
 * 임계값 '위치'를 옮긴다: 값 − 기준선중앙값 을 threshold_rel(= 임계값 − AIHub 정상중앙값) 과 비교.
 * 검증: FLOOR_ANCHOR_VALIDATION — 동일-수행자 앵커 k=3 에서 Δ+0.027 (34승 9패).
 */
class FloorAnchorTest {

    private fun floorRule(thr: Float, thrRel: Float) = PostureRule(
        id = "floor|푸시업|고개 젖힘/숙임 여부", exercise = "푸시업", condition = "고개 젖힘/숙임 여부",
        subtype = null, status = RuleStatus.BETA, reason = null,
        feature = "head_trunk_ang__mean", baseFeature = "head_trunk_ang", stat = "mean",
        family = "floor2d", op = ">", threshold = thr, view = "C", viewDesc = "측면", cvAuc = 0.89f,
        cvBalacc = 0.8f, sampleN = 800, mirrorSafe = true, cautions = emptyList(),
        baselineEligible = true, baselineThresholdRel = thrRel, baselineK = 3,
    )

    private fun agg(vararg frames: Float): FeatureAggregator {
        val a = FeatureAggregator()
        frames.forEach { a.add(mapOf("head_trunk_ang" to it)) }
        return a
    }

    @Test
    fun reanchoredThresholdMovesWithUserBaseline() {
        // AIHub 채택 뷰: 임계값 103, 정상 중앙값 93 → threshold_rel = 10
        val rs = PostureRuleSet("floor_v0.2", "t", listOf(floorRule(103f, 10f)))
        // 사용자의 폰 시점에서는 같은 자세가 더 크게 투영됨: 정자세 기준선 중앙값 = 120
        val baseline = mapOf("head_trunk_ang__mean" to 120f)

        // 값 125: raw 로는 위반(>103)이지만, 재배치로는 125−120=5 < 10 → 정상
        val ok = rs.evaluate("푸시업", agg(*FloatArray(10) { 125f }), baseline = baseline).single()
        assertEquals(Verdict.OK, ok.verdict)
        assertTrue(ok.baselineApplied)
        assertEquals(5f, ok.value!!, 1e-4f)
        assertEquals(125f, ok.rawValue!!, 1e-4f)

        // 값 135: 135−120=15 > 10 → 위반 (재배치 후에도 진짜 이탈은 잡힌다)
        val bad = rs.evaluate("푸시업", agg(*FloatArray(10) { 135f }), baseline = baseline).single()
        assertEquals(Verdict.VIOLATION, bad.verdict)
        assertTrue(bad.baselineApplied)
    }

    @Test
    fun withoutBaselineFallsBackToRawThreshold() {
        val rs = PostureRuleSet("floor_v0.2", "t", listOf(floorRule(103f, 10f)))
        val r = rs.evaluate("푸시업", agg(*FloatArray(10) { 125f }), baseline = null).single()
        assertEquals(Verdict.VIOLATION, r.verdict)   // raw 임계값(>103) 그대로
        assertFalse(r.baselineApplied)
    }

    @Test
    fun baselineForOtherFeatureDoesNotApply() {
        val rs = PostureRuleSet("floor_v0.2", "t", listOf(floorRule(103f, 10f)))
        val r = rs.evaluate("푸시업", agg(*FloatArray(10) { 125f }),
            baseline = mapOf("knee_ang__mean" to 120f)).single()
        assertFalse(r.baselineApplied)
        assertEquals(Verdict.VIOLATION, r.verdict)
    }
}
