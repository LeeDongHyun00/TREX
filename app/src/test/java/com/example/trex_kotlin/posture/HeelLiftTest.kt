package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §28b 재설계 검증: heel_lift(발 내부 기하)와 p90 통계.
 * heel_lift = up·(heel−toe)/|heel−toe| — 발이 바닥에 붙어 있으면 무릎·발목 각도와 무관(하단 결합 구조 차단).
 */
class HeelLiftTest {

    private fun frame(
        lHeel: Vec3?, lFoot: Vec3?, rHeel: Vec3? = null, rFoot: Vec3? = null,
        up: Vec3 = Vec3(0f, 1f, 0f),
    ): PoseFrame = PoseFrame(
        mapOf(
            // valid 게이트(코어 관절)를 위한 몸통 — up 축과 무관한 배치
            Joints.L_SHOULDER to Vec3(-20f, 150f, 0f), Joints.R_SHOULDER to Vec3(20f, 150f, 0f),
            Joints.L_HIP to Vec3(-15f, 100f, 0f), Joints.R_HIP to Vec3(15f, 100f, 0f),
            Joints.L_HEEL to lHeel, Joints.L_FOOT to lFoot,
            Joints.R_HEEL to rHeel, Joints.R_FOOT to rFoot,
        ),
        up,
    )

    @Test
    fun flatFootNearZeroRaisedHeelPositive() {
        // 평평한 발: heel 과 toe 가 같은 높이 → lift ≈ 0
        val flat = frame(lHeel = Vec3(0f, 0f, 0f), lFoot = Vec3(25f, 0f, 0f)).features()
        assertEquals(0f, flat.getValue("heel_lift"), 1e-4f)
        // 뒤꿈치 10cm 들림 (발 길이 25cm 유지 근사) → lift ≈ 10/사선길이
        val raised = frame(lHeel = Vec3(0f, 10f, 0f), lFoot = Vec3(23f, 0f, 0f)).features()
        assertTrue("lift=${raised.getValue("heel_lift")}", raised.getValue("heel_lift") > 0.35f)
    }

    @Test
    fun liftFollowsGravityUpNotWorldAxes() {
        // 폰이 기울어 up 이 (0,0,1) 인 경우 — 같은 기하가 up 축 기준으로 재해석돼야 한다
        val f = frame(lHeel = Vec3(0f, 0f, 10f), lFoot = Vec3(23f, 0f, 0f), up = Vec3(0f, 0f, 1f)).features()
        assertTrue(f.getValue("heel_lift") > 0.35f)
        val flat = frame(lHeel = Vec3(0f, 0f, 0f), lFoot = Vec3(25f, 0f, 0f), up = Vec3(0f, 0f, 1f)).features()
        assertEquals(0f, flat.getValue("heel_lift"), 1e-4f)
    }

    @Test
    fun averagesSidesAndAbstainsWhenMissing() {
        // 한쪽만 보이면 그쪽만, 양쪽 다 없으면 피처 없음(유보)
        val one = frame(lHeel = Vec3(0f, 5f, 0f), lFoot = Vec3(24f, 0f, 0f)).features()
        assertTrue(one.containsKey("heel_lift"))
        val none = frame(lHeel = null, lFoot = null).features()
        assertNull(none["heel_lift"])
        // 좌우 평균: L=0, R=양수 → 중간값
        val both = frame(
            lHeel = Vec3(0f, 0f, 0f), lFoot = Vec3(25f, 0f, 0f),
            rHeel = Vec3(0f, 10f, 5f), rFoot = Vec3(23f, 0f, 5f),
        ).features()
        val l = both.getValue("heel_lift_L"); val r = both.getValue("heel_lift_R")
        assertEquals((l + r) / 2f, both.getValue("heel_lift"), 1e-4f)
    }

    @Test
    fun aggregatorP90MatchesNumpyLinearInterpolation() {
        val agg = FeatureAggregator()
        for (v in listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)) agg.add(mapOf("x" to v))
        // numpy.quantile([1..10], 0.9) = 9.1 / 0.1 = 1.9
        assertEquals(9.1f, agg.stat("x", "p90")!!, 1e-4f)
        assertEquals(1.9f, agg.stat("x", "p10")!!, 1e-4f)
    }
}
