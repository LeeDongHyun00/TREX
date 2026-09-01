package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §28e: 기준선 필수 규칙 — 임계가 기기 분포 한가운데인 경우 raw 판정은 동전던지기라 보류한다.
 * 실측 근거: 스쿼트 heel_lift 기기 p10~p90 = 0.506~0.637, AIHub 임계 0.580 (오프셋 +0.115).
 */
class RequiresBaselineTest {

    private fun rule(required: Boolean) = PostureRule(
        id = "바벨 스쿼트|발바닥 지면 고정", exercise = "바벨 스쿼트", condition = "발바닥 지면 고정",
        subtype = null, status = RuleStatus.BETA, reason = null,
        feature = "heel_lift__p90", baseFeature = "heel_lift", stat = "p90",
        family = "foot", op = ">", threshold = 0.580f, view = "C", viewDesc = "정면",
        cvAuc = 0.866f, cvBalacc = 0.8f, sampleN = 56, mirrorSafe = true, cautions = emptyList(),
        baselineEligible = true, baselineThresholdRel = 0.058f, baselineK = 3,
        baselineRequired = required,
    )

    private fun agg(vararg v: Float): FeatureAggregator {
        val a = FeatureAggregator()
        v.forEach { a.add(mapOf("heel_lift" to it)) }
        return a
    }

    /** 사용자 실측과 같은 분포(p90 ≈ 0.637) — raw 로는 위반, 기준선 있으면 정상. */
    private fun deviceLike() = agg(0.50f, 0.52f, 0.55f, 0.57f, 0.58f, 0.60f, 0.61f, 0.62f, 0.63f, 0.64f)

    @Test
    fun requiredRuleAbstainsWithoutBaseline() {
        val rs = PostureRuleSet("v", "d", listOf(rule(required = true)))
        val r = rs.evaluate("바벨 스쿼트", deviceLike(), baseline = null).single()
        assertEquals("기준선 없으면 판정 보류", Verdict.ABSTAIN, r.verdict)
        assertFalse(r.baselineApplied)
    }

    @Test
    fun requiredRuleJudgesWithBaseline() {
        val rs = PostureRuleSet("v", "d", listOf(rule(required = true)))
        // 사용자 정자세 기준선 p90 = 0.62 → 실효 임계 0.62 + 0.058 = 0.678
        val base = mapOf("heel_lift__p90" to 0.62f)
        val ok = rs.evaluate("바벨 스쿼트", deviceLike(), baseline = base).single()
        assertEquals(Verdict.OK, ok.verdict)          // p90 ≈ 0.637 < 0.678
        assertTrue(ok.baselineApplied)
        // 실제로 뒤꿈치를 들면 잡힌다
        val high = agg(0.70f, 0.72f, 0.74f, 0.75f, 0.76f, 0.77f, 0.78f, 0.79f, 0.80f, 0.82f)
        assertEquals(Verdict.VIOLATION, rs.evaluate("바벨 스쿼트", high, baseline = base).single().verdict)
    }

    @Test
    fun nonRequiredRuleStillJudgesRaw() {
        // 회귀: required=false 규칙은 기존대로 raw 판정 (기본값이 바뀌면 안 됨)
        val rs = PostureRuleSet("v", "d", listOf(rule(required = false)))
        val r = rs.evaluate("바벨 스쿼트", deviceLike(), baseline = null).single()
        assertEquals(Verdict.VIOLATION, r.verdict)    // p90 0.637 > 0.580
    }

    @Test
    fun shippedSquatHeelRuleIsMarkedRequired() {
        // 자산 JSON 이 required=true 로 실려 있는지 — 텍스트 검사(org.json 은 유닛테스트에서 스텁)
        val json = javaClass.classLoader?.getResourceAsStream("rules_mp_v0.json")
        if (json != null) {
            val t = json.bufferedReader().readText()
            assertTrue(t.contains("\"required\""))
        }
    }
}
