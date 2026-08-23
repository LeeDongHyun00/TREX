package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 개인 기준선: 수집(중앙값) · 저장소 왕복 · 규칙 평가에의 적용. */
class PostureBaselineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rule(
        feature: String,
        op: String,
        threshold: Float,
        eligible: Boolean,
        thresholdRel: Float?,
        exercise: String = "오버 헤드 프레스",
        condition: String = "척추의 중립",
    ): PostureRule {
        val i = feature.lastIndexOf("__")
        return PostureRule(
            id = "$exercise|$condition", exercise = exercise, condition = condition, subtype = null, status = RuleStatus.SHIP, reason = null,
            feature = feature, baseFeature = feature.substring(0, i), stat = feature.substring(i + 2), family = "head", op = op,
            threshold = threshold, view = "D", viewDesc = "", cvAuc = 0.9f, cvBalacc = 0.8f, sampleN = 60, mirrorSafe = true,
            cautions = emptyList(), baselineEligible = eligible, baselineThresholdRel = thresholdRel, baselineK = 3, baselineGain = 0.04f,
        )
    }

    @Test
    fun collectorBuildsMedianAndSkipsSparseFeatures() {
        val c = BaselineCollector("오버 헤드 프레스", listOf("head_pitch__mean", "grip_w__mean", "torso_incl__mean"), requiredSets = 3)
        assertFalse(c.isComplete)
        c.addSet(mapOf("head_pitch__mean" to -10f, "grip_w__mean" to 1.5f, "torso_incl__mean" to 5f))
        c.addSet(mapOf("head_pitch__mean" to -14f, "grip_w__mean" to 1.7f))                      // torso 누락
        c.addSet(mapOf("head_pitch__mean" to -12f, "grip_w__mean" to 1.6f, "torso_incl__mean" to Float.NaN))
        assertTrue(c.isComplete)
        assertEquals(3, c.completedSets)
        val b = c.build(nowIso = "2026-08-22T00:00:00Z")
        assertEquals(-12f, b.values["head_pitch__mean"]!!, 1e-6f)     // 홀수 개 중앙값
        assertEquals(1.6f, b.values["grip_w__mean"]!!, 1e-6f)
        assertNull(b.values["torso_incl__mean"])                         // 유효값 1개 → 제외
        assertEquals(listOf(-10f, -14f, -12f), b.setValues["head_pitch__mean"])
        assertEquals(3, b.nSets)
        // 짝수 개 중앙값 = 가운데 둘의 평균
        assertEquals(2.5f, BaselineCollector.median(listOf(4f, 1f, 3f, 2f)), 1e-6f)
        c.removeLast()
        assertEquals(2, c.completedSets)
        c.reset()
        assertEquals(0, c.completedSets)
    }

    @Test
    fun setValuesComeFromAggregatorWithMinFrames() {
        val agg = FeatureAggregator()
        repeat(8) { i -> agg.add(mapOf("head_pitch" to -10f - i, "grip_w" to 1.5f)) }
        agg.add(mapOf("torso_incl" to 3f))                                   // 1프레임뿐
        val v = BaselineCollector.setValues(agg, listOf("head_pitch__mean", "grip_w__max", "torso_incl__mean", "bogus"), minFrames = 8)
        assertEquals(-13.5f, v["head_pitch__mean"]!!, 1e-5f)
        assertEquals(1.5f, v["grip_w__max"]!!, 1e-6f)
        assertNull(v["torso_incl__mean"])                                    // 프레임 부족
        assertNull(v["bogus"])
    }

    @Test
    fun storeRoundTrip() {
        val store = BaselineStore(tmp.newFile("posture_baseline.tsv"))
        val b1 = ExerciseBaseline("오버 헤드 프레스", linkedMapOf("head_pitch__mean" to -12.25f, "grip_w__mean" to 1.6f),
            mapOf("head_pitch__mean" to listOf(-10f, -14f, -12.25f)), 3, "2026-08-22T00:00:00Z")
        val b2 = ExerciseBaseline("딥스", linkedMapOf("torso_incl__mean" to 21.5f), emptyMap(), 3, "2026-08-22T00:01:00Z")
        store.put(b1)
        val p = store.put(b2)
        assertEquals(listOf("오버 헤드 프레스", "딥스"), p.exercises)
        val loaded = BaselineStore(store.path).load()
        assertEquals(-12.25f, loaded.valuesFor("오버 헤드 프레스")!!["head_pitch__mean"]!!, 1e-5f)
        assertEquals(1.6f, loaded.valuesFor("오버 헤드 프레스")!!["grip_w__mean"]!!, 1e-5f)
        assertEquals(listOf(-10f, -14f, -12.25f), loaded.get("오버 헤드 프레스")!!.setValues["head_pitch__mean"])
        assertEquals(21.5f, loaded.valuesFor("딥스")!!["torso_incl__mean"]!!, 1e-5f)
        assertEquals("2026-08-22T00:01:00Z", loaded.get("딥스")!!.createdAtIso)
        val after = store.remove("딥스")
        assertFalse(after.has("딥스"))
        assertTrue(BaselineStore(store.path).load().has("오버 헤드 프레스"))
        store.clear()
        assertEquals(0, BaselineStore(store.path).load().exercises.size)
    }

    @Test
    fun evaluateUsesRelativeThresholdOnlyForEligibleRulesWithBaseline() {
        // 규칙: head_pitch__mean < -9.958 이면 위반(절대). 상대 임계값: (값 − 기준선) < -5.5 이면 위반.
        val eligible = rule("head_pitch__mean", "<", -9.958f, eligible = true, thresholdRel = -5.5f)
        val plain = rule("grip_w__mean", "<", 1.416f, eligible = false, thresholdRel = null, condition = "전완 지면과 수직")
        val rs = PostureRuleSet("test", "2026-08-22", listOf(eligible, plain))
        assertEquals(listOf("오버 헤드 프레스"), rs.baselineExercises)
        assertEquals(listOf("head_pitch__mean"), rs.baselineFeaturesFor("오버 헤드 프레스"))
        assertEquals(3, rs.baselineSetsFor("오버 헤드 프레스"))

        val agg = FeatureAggregator()
        repeat(10) { agg.add(mapOf("head_pitch" to -16f, "grip_w" to 1.5f)) }   // 절대 기준으로는 head_pitch 위반(-16 < -9.958)

        // 기준선 없음 → 절대 판정: 위반
        val noBase = rs.evaluate("오버 헤드 프레스", agg, baseline = null)
        val r0 = noBase.first { it.rule.feature == "head_pitch__mean" }
        assertEquals(Verdict.VIOLATION, r0.verdict)
        assertFalse(r0.baselineApplied)
        assertEquals(-16f, r0.value!!, 1e-5f)

        // 이 사용자의 정자세 기준선이 -14 → 상대값 -2 > -5.5 → 정상
        val withBase = rs.evaluate("오버 헤드 프레스", agg, baseline = mapOf("head_pitch__mean" to -14f))
        val r1 = withBase.first { it.rule.feature == "head_pitch__mean" }
        assertEquals(Verdict.OK, r1.verdict)
        assertTrue(r1.baselineApplied)
        assertEquals(-2f, r1.value!!, 1e-5f)
        assertEquals(-16f, r1.rawValue!!, 1e-5f)
        // 기준선이 -8 → 상대값 -8 < -5.5 → 위반
        val r2 = rs.evaluate("오버 헤드 프레스", agg, baseline = mapOf("head_pitch__mean" to -8f)).first { it.rule.feature == "head_pitch__mean" }
        assertEquals(Verdict.VIOLATION, r2.verdict)
        // eligible 아닌 규칙은 기준선이 있어도 절대 판정
        val p1 = withBase.first { it.rule.feature == "grip_w__mean" }
        assertFalse(p1.baselineApplied)
        assertEquals(Verdict.OK, p1.verdict)                                  // 1.5 ≥ 1.416
        // 기준선 맵에 해당 피처가 없으면 절대 판정으로 폴백
        val r3 = rs.evaluate("오버 헤드 프레스", agg, baseline = mapOf("other__mean" to 0f)).first { it.rule.feature == "head_pitch__mean" }
        assertFalse(r3.baselineApplied)
        assertEquals(Verdict.VIOLATION, r3.verdict)
    }
}
