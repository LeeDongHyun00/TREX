package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * §33 촬영 뷰 추정기 — (1) 연구 파리티: view_fixture.txt 의 프레임 관절 → yaw/cos/sin/등급이 Python 과 일치
 * (2) 합성 회전: 정면 골격을 세로축으로 돌리면 등급이 바뀐다 (3) 창 추정: 원형 평균과 R (4) 규칙 게이팅: views_ok 밖이면 유보.
 * 픽스처 생성: research/aihub_fitness/view_estimator.py
 */
class ViewEstimatorTest {

    private data class Case(val name: String, val joints: Map<String, Vec3?>, val expected: Map<String, Float>)

    private fun loadCases(): List<Case> {
        val stream = javaClass.classLoader!!.getResourceAsStream("view_fixture.txt")
            ?: error("view_fixture.txt 없음 — view_estimator.py 를 먼저 실행하세요")
        val cases = ArrayList<Case>()
        var name = ""
        var joints = HashMap<String, Vec3?>()
        var expected = HashMap<String, Float>()
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split(" ")
            when (parts[0]) {
                "CASE" -> { name = parts.drop(1).joinToString(" "); joints = HashMap(); expected = HashMap() }
                "J" -> joints[parts[1]] = Vec3(parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat())
                "F" -> expected[parts[1]] = parts[2].toFloat()
                "END" -> cases += Case(name, joints, expected)
            }
        }
        return cases
    }

    @Test
    fun frameYawMatchesResearch() {
        val cases = loadCases()
        assertTrue("픽스처 케이스가 있어야 한다", cases.size >= 50)
        val classes = HashSet<Int>()
        for (case in cases) {
            val yaw = ViewEstimator.frameYawDeg(case.joints)
            assertNotNull("yaw 계산 실패: ${case.name}", yaw)
            assertEquals("yaw ${case.name}", case.expected.getValue("view_yaw_deg"), yaw!!, 0.05f)
            val f = ViewEstimator.frameFeatures(case.joints)
            assertEquals("cos ${case.name}", case.expected.getValue("view_cos"), f.getValue(ViewEstimator.FEAT_COS), 1e-4f)
            assertEquals("sin ${case.name}", case.expected.getValue("view_sin"), f.getValue(ViewEstimator.FEAT_SIN), 1e-4f)
            val code = case.expected.getValue("view_class").toInt()
            assertEquals("등급 ${case.name}", code, ViewEstimator.classify(yaw).code)
            classes += code
        }
        assertTrue("픽스처가 5뷰를 다 담아야 한다", classes.size >= 4)
    }

    /** 정면을 보는 골격을 세로축(y)으로 θ 만큼 돌린다. 어깨선·골반선 정의상 측정 요 = −θ. */
    private fun skeleton(yawDeg: Float, hips: Boolean = true): Map<String, Vec3?> {
        val th = Math.toRadians(-yawDeg.toDouble())
        fun rot(x: Float, y: Float, z: Float): Vec3 =
            Vec3((x * cos(th) - z * sin(th)).toFloat(), y, (x * sin(th) + z * cos(th)).toFloat())
        val m = HashMap<String, Vec3?>()
        m[Joints.L_SHOULDER] = rot(20f, 140f, 0f)
        m[Joints.R_SHOULDER] = rot(-20f, 140f, 0f)
        if (hips) {
            m[Joints.L_HIP] = rot(10f, 90f, 0f)
            m[Joints.R_HIP] = rot(-10f, 90f, 0f)
        }
        return m
    }

    @Test
    fun syntheticRotationsClassify() {
        val expect = listOf(
            0f to ViewEstimator.ViewClass.C, 10f to ViewEstimator.ViewClass.C,
            33f to ViewEstimator.ViewClass.B, -33f to ViewEstimator.ViewClass.D,
            60f to ViewEstimator.ViewClass.SIDE_B, -60f to ViewEstimator.ViewClass.SIDE_D,
            125f to ViewEstimator.ViewClass.A, -125f to ViewEstimator.ViewClass.E,
            175f to ViewEstimator.ViewClass.R, -175f to ViewEstimator.ViewClass.R,
        )
        for ((deg, cls) in expect) {
            val yaw = ViewEstimator.frameYawDeg(skeleton(deg))!!
            assertEquals("yaw $deg", deg, yaw, 0.5f)
            assertEquals("등급 $deg", cls, ViewEstimator.classify(yaw))
        }
    }

    @Test
    fun shouldersAloneWhenHipsMissing() {
        val yaw = ViewEstimator.frameYawDeg(skeleton(30f, hips = false))!!
        assertEquals(30f, yaw, 0.5f)
        assertNull("어깨·골반 다 없으면 null", ViewEstimator.frameYawDeg(emptyMap()))
        assertTrue(ViewEstimator.frameFeatures(emptyMap()).isEmpty())
    }

    @Test
    fun windowEstimateIsCircularMean() {
        val agg = FeatureAggregator()
        repeat(10) { agg.add(ViewEstimator.frameFeatures(skeleton(33f))) }
        val e = ViewEstimator.estimate(agg)!!
        assertEquals(33f, e.yawDeg, 0.5f)
        assertTrue("일관된 프레임이면 R≈1", e.r > 0.99f)
        assertEquals(ViewEstimator.ViewClass.B, e.cls)
        assertEquals(10, e.frames)

        // 좌우가 반반 뒤집히는 창 — 결과 벡터가 짧아 UNKNOWN (방향 제한 규칙은 유보)
        val mixed = FeatureAggregator()
        repeat(5) { mixed.add(ViewEstimator.frameFeatures(skeleton(33f))) }
        repeat(5) { mixed.add(ViewEstimator.frameFeatures(skeleton(-147f))) }
        val m = ViewEstimator.estimate(mixed)!!
        assertTrue("R 이 작아야", m.r < ViewEstimator.MIN_R)
        assertEquals(ViewEstimator.ViewClass.UNKNOWN, m.cls)

        // 프레임 부족이면 추정 자체를 않는다
        val few = FeatureAggregator()
        repeat(ViewEstimator.MIN_FRAMES - 1) { few.add(ViewEstimator.frameFeatures(skeleton(0f))) }
        assertNull(ViewEstimator.estimate(few))
    }

    private fun rule(viewsOk: Set<String>) = PostureRule(
        id = "바벨 스쿼트|발과 무릎의 방향 일치", exercise = "바벨 스쿼트", condition = "발과 무릎의 방향 일치",
        subtype = null, status = RuleStatus.SHIP, reason = null,
        feature = "knee_out_mean__mean", baseFeature = "knee_out_mean", stat = "mean",
        family = "valgus", op = "<", threshold = 0.0239f, view = "C", viewDesc = "정면",
        cvAuc = 0.90f, cvBalacc = 0.87f, sampleN = 56, mirrorSafe = true, cautions = emptyList(),
        viewsOk = viewsOk,
    )

    /** knee_out_mean 값 + 촬영 방향 프레임 10개. */
    private fun agg(value: Float, yawDeg: Float?): FeatureAggregator {
        val a = FeatureAggregator()
        repeat(10) {
            val f = HashMap<String, Float>()
            f["knee_out_mean"] = value
            if (yawDeg != null) f += ViewEstimator.frameFeatures(skeleton(yawDeg))
            a.add(f)
        }
        return a
    }

    @Test
    fun evaluateAbstainsOutsideAllowedViews() {
        val rs = PostureRuleSet("v", "d", listOf(rule(setOf("C", "B", "D"))))
        // 뒤에서 찍은 창: 값은 '위반'이지만 이 방향에서는 판정하지 않는다
        val rear = rs.evaluate("바벨 스쿼트", agg(-0.10f, 125f)).single()
        assertEquals(Verdict.ABSTAIN, rear.verdict)
        assertTrue("이유가 촬영 방향이어야", rear.abstainReason!!.startsWith("촬영 방향"))
        // 옆에서 찍은 창(미검증 등급) — 마찬가지로 유보
        assertEquals(Verdict.ABSTAIN, rs.evaluate("바벨 스쿼트", agg(-0.10f, 60f)).single().verdict)
        // 정면·앞 비스듬히 — 판정한다
        assertEquals(Verdict.VIOLATION, rs.evaluate("바벨 스쿼트", agg(-0.10f, 0f)).single().verdict)
        assertEquals(Verdict.OK, rs.evaluate("바벨 스쿼트", agg(0.05f, -33f)).single().verdict)
    }

    @Test
    fun missingViewOnlyAbstainsWhenRuleRequiresView() {
        // views_ok 가 비어 있으면 종전 동작 — 어느 방향이든 판정
        val open = PostureRuleSet("v", "d", listOf(rule(emptySet())))
        assertEquals(Verdict.VIOLATION, open.evaluate("바벨 스쿼트", agg(-0.10f, 125f)).single().verdict)
        // 방향 제한 규칙은 방향 피처가 없으면 유보한다. 별도 뷰 독립/바닥 규칙은 views_ok를 비워 둔다.
        val gated = PostureRuleSet("v", "d", listOf(rule(setOf("C"))))
        val r = gated.evaluate("바벨 스쿼트", agg(-0.10f, null)).single()
        assertEquals(Verdict.ABSTAIN, r.verdict)
        assertEquals("촬영 방향 · 확인할 수 없음", r.abstainReason)
        assertEquals(Verdict.VIOLATION, open.evaluate("바벨 스쿼트", agg(-0.10f, null)).single().verdict)
    }

    @Test
    fun unknownOrInsufficientViewNeverCertifiesAnAllowedDirection() {
        val gated = PostureRuleSet("v", "d", listOf(rule(setOf("C"))))
        for (value in listOf(-0.10f, 0.05f)) {
            val mixed = FeatureAggregator()
            repeat(10) { i ->
                mixed.add(mapOf("knee_out_mean" to value) + ViewEstimator.frameFeatures(skeleton(if (i % 2 == 0) 0f else 180f)))
            }
            val unknown = gated.evaluate("바벨 스쿼트", mixed).single()
            assertEquals(Verdict.ABSTAIN, unknown.verdict)
            assertEquals("촬영 방향 · 확인할 수 없음", unknown.abstainReason)

            val few = FeatureAggregator()
            repeat(10) { i -> few.add(mapOf("knee_out_mean" to value) +
                if (i < 7) ViewEstimator.frameFeatures(skeleton(0f)) else emptyMap()) }
            assertEquals(Verdict.ABSTAIN, gated.evaluate("바벨 스쿼트", few).single().verdict)
        }
    }

    @Test
    fun setLogCarriesViewEstimate() {
        val frames = List(10) { ViewEstimator.frameFeatures(skeleton(-33f)) }
        val e = ViewEstimator.estimate(frames)!!
        assertEquals(ViewEstimator.ViewClass.D, e.cls)
        assertEquals(-33f, e.yawDeg, 0.5f)
        assertTrue(abs(e.r - 1f) < 0.01f)
        assertNull("방향 피처 없는 프레임만 있으면 null", ViewEstimator.estimate(List(10) { mapOf("knee_mean" to 90f) }))
    }
}
