package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec §62a 발 너비 `stance_sh` = 발목 간격 ÷ 어깨 너비(둘 다 수평). "발 간격 = 어깨 너비" 가 사용자 공식이라 분모가 어깨다.
 * 골반 정규화(`stance_w`)는 서 있을 때도 ×0.78 헛경보를 냈고 바닥에서 두 배로 뛰었다(실기기 2026-09-25).
 */
class StanceWidthTest {

    private fun frame(ankleGap: Float, shoulderGap: Float = 40f, ankleY: Float = 0f): PoseFrame = PoseFrame(
        mapOf(
            Joints.L_SHOULDER to Vec3(-shoulderGap / 2, 150f, 0f), Joints.R_SHOULDER to Vec3(shoulderGap / 2, 150f, 0f),
            Joints.L_HIP to Vec3(-15f, 100f, 0f), Joints.R_HIP to Vec3(15f, 100f, 0f),
            Joints.L_ANKLE to Vec3(-ankleGap / 2, ankleY, 0f), Joints.R_ANKLE to Vec3(ankleGap / 2, ankleY, 0f),
        ),
        Vec3(0f, 1f, 0f),
    )

    @Test
    fun ratioIsAnkleGapOverShoulderWidthInTheHorizontalPlane() {
        assertEquals(1.0f, frame(40f).features().getValue("stance_sh"), 1e-4f)
        assertEquals(1.5f, frame(60f).features().getValue("stance_sh"), 1e-4f)
        assertEquals(0.75f, frame(30f).features().getValue("stance_sh"), 1e-4f)
        // 발목 높이(수직)는 비율에 안 들어간다 — 한쪽 발을 들어도 수평 간격만 본다
        assertEquals(1.0f, frame(40f, ankleY = 10f).features().getValue("stance_sh"), 1e-4f)
        // 골반 정규화 피처는 그대로 남아 있다(기존 기록·비교 지표의 단위)
        assertEquals(40f / 30f, frame(40f).features().getValue("stance_w"), 1e-4f)
    }

    @Test
    fun image2dRatioUsesAnkleAndShoulderXGaps() {
        // §62a 후속 3: 정규화 이미지 x 만 쓴다 — 발목 0.3~0.7(폭 0.4), 어깨 0.4~0.6(폭 0.2) → 2.0. y·깊이는 무관
        val xy = FloatArray(66); val vis = FloatArray(33) { 1f }
        fun put(i: Int, x: Float, y: Float) { xy[i * 2] = x; xy[i * 2 + 1] = y }
        put(11, 0.4f, 0.3f); put(12, 0.6f, 0.3f); put(27, 0.3f, 0.9f); put(28, 0.7f, 0.9f)
        assertEquals(2.0f, Stance2d.of(xy, vis, 0.5f)!!, 1e-5f)
        assertEquals(2.0f, Stance2d.features(xy, vis, 0.5f).getValue("stance_2d"), 1e-5f)
        // 발목 하나라도 가시성 미달이면 없음(유보)
        vis[28] = 0.2f
        assertNull(Stance2d.of(xy, vis, 0.5f))
        vis[28] = 1f
        // 어깨가 겹치면(옆모습) 없음
        put(12, 0.42f, 0.3f)
        assertNull(Stance2d.of(xy, vis, 0.5f))
    }

    @Test
    fun abstainsWhenShouldersCollapse() {
        // 어깨 너비 15 cm 미만(관절 겹침)이면 비율을 만들지 않는다
        assertNull(frame(40f, shoulderGap = 10f).features()["stance_sh"])
    }

    @Test
    fun arm2dFrontalFeaturesFollowTheGeometry() {
        // 정면(비미러): 사람 왼쪽이 화면 +x. 어깨 (0.35,0.30)·(0.65,0.30), 골반 (0.40,0.60)·(0.60,0.60) → 몸통 0.30(높이 단위, aspect 1)
        val xy = FloatArray(66); val vis = FloatArray(33) { 1f }
        fun put(i: Int, x: Float, y: Float) { xy[i * 2] = x; xy[i * 2 + 1] = y }
        put(11, 0.65f, 0.30f); put(12, 0.35f, 0.30f); put(23, 0.60f, 0.60f); put(24, 0.40f, 0.60f)
        put(13, 0.78f, 0.45f); put(14, 0.30f, 0.45f)      // 왼 팔꿈치 바깥 +0.13/0.30, 오른 팔꿈치 바깥 +0.05/0.30
        put(15, 0.70f, 0.33f); put(16, 0.30f, 0.60f)      // 왼 손목 어깨 아래 0.03(−0.1 몸통), 오른 손목 0.30 아래(−1.0 몸통)
        val f = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = 0f)
        assertEquals(0.13f / 0.30f, f.getValue(Arm2d.ELBOW_LAT_L), 1e-4f)
        assertEquals(0.05f / 0.30f, f.getValue(Arm2d.ELBOW_LAT_R), 1e-4f)
        assertEquals(0.13f / 0.30f, f.getValue(Arm2d.ELBOW_LAT_MAX), 1e-4f)
        assertEquals(-0.15f / 0.30f, f.getValue(Arm2d.ELBOW_RISE_L), 1e-4f)
        assertEquals(-0.03f / 0.30f, f.getValue(Arm2d.WRIST_H_L), 1e-4f)
        assertEquals(-0.30f / 0.30f, f.getValue(Arm2d.WRIST_H_R), 1e-4f)
        assertEquals(-0.03f / 0.30f, f.getValue(Arm2d.WRIST_H_MAX), 1e-4f)
        assertNull("정면에서는 앞/뒤 부호가 없다", f[Arm2d.ELBOW_FWD_MEAN]); assertNull(f[Arm2d.TORSO_TILT])
        // 사선(D, yaw −35°): 앞 = 화면 −x. 왼 팔꿈치는 몸통 선(x 0.5)에서 +0.28 → 뒤(−), 오른 팔꿈치 −0.20 → 앞(+)
        val g = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = -35f)
        assertTrue(g.getValue(Arm2d.ELBOW_FWD_L) < 0f); assertTrue(g.getValue(Arm2d.ELBOW_FWD_R) > 0f)
        assertEquals(0f, g.getValue(Arm2d.TORSO_TILT), 1e-4f)
    }

    @Test
    fun arm2dNearArmFeaturesUseTheCameraSideArmAndItsOwnBodyLine() {
        // §62c 후속 7 — 사선 D(요 −30°): 카메라 쪽 팔 = 왼팔, 앞 = 화면 −x. 먼(오른) 팔꿈치는 가려짐(가시성 0.2)
        val xy = FloatArray(66); val vis = FloatArray(33) { 1f }
        fun put(i: Int, x: Float, y: Float) { xy[i * 2] = x; xy[i * 2 + 1] = y }
        put(11, 0.60f, 0.30f); put(12, 0.45f, 0.30f); put(23, 0.58f, 0.60f); put(24, 0.47f, 0.60f)   // 어깨·골반 중점 x 0.525 → 몸통 0.30
        put(13, 0.66f, 0.45f); put(14, 0.40f, 0.45f)
        vis[14] = 0.2f
        val d = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = -30f)
        assertNull("먼 팔꿈치가 없으면 양팔 평균도 없다", d[Arm2d.ELBOW_FWD_MEAN])
        // 가까운 쪽 몸통 선(왼골반 (0.58,0.60) → 왼어깨 (0.60,0.30))은 팔꿈치 높이 0.45 에서 x 0.59 — 팔꿈치 0.66 은 화면 +0.07 = 뒤(앞이 −x)
        assertEquals(-0.07f / 0.30f, d.getValue(Arm2d.ELBOW_FWD_NEAR), 1e-4f)
        // 바깥 가로: 왼팔 바깥 = 사람 왼쪽 = 화면 +x(골반 순서) — (0.66 − 0.60) ÷ 어깨 가로폭 0.15
        assertEquals(0.06f / 0.15f, d.getValue(Arm2d.ELBOW_LAT_NEAR), 1e-4f)
        assertEquals(d.getValue(Arm2d.ELBOW_LAT_L), d.getValue(Arm2d.ELBOW_LAT_NEAR), 1e-6f)
        // B(요 +30°): 카메라 쪽 팔 = 오른팔 — 오른 몸통 선(0.47,0.60 → 0.45,0.30)은 y 0.45 에서 x 0.46, 앞 = 화면 +x
        vis[14] = 1f
        val b = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = 30f)
        assertEquals((0.40f - 0.46f) / 0.30f, b.getValue(Arm2d.ELBOW_FWD_NEAR), 1e-4f)
        assertEquals(b.getValue(Arm2d.ELBOW_LAT_R), b.getValue(Arm2d.ELBOW_LAT_NEAR), 1e-6f)
        // 정면(요 0°): 가까운 팔 피처 없음. 옆 초입(요 +60°, §62c 후속 10 띠 끝): 앞 성분과 바깥 가로 둘 다. 옆(요 +70°): 앞 성분만(가로는 어깨 가로폭이 작아 흔들린다)
        val c = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = 0f)
        assertNull(c[Arm2d.ELBOW_FWD_NEAR]); assertNull(c[Arm2d.ELBOW_LAT_NEAR])
        val edge = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = 60f)
        assertTrue(edge.containsKey(Arm2d.ELBOW_FWD_NEAR)); assertTrue(edge.containsKey(Arm2d.ELBOW_LAT_NEAR)); assertNull(edge[Arm2d.ELBOW_FWD_MEAN])
        val side = Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = 70f)
        assertTrue(side.containsKey(Arm2d.ELBOW_FWD_NEAR)); assertNull(side[Arm2d.ELBOW_LAT_NEAR]); assertNull(side[Arm2d.ELBOW_FWD_MEAN])
        // 정면 띠 안쪽 12°: 사선으로 잠긴 세트에서 살짝 정면으로 흔들린 프레임도 잰다
        assertTrue(Arm2d.features(xy, vis, 0.5f, aspect = 1f, yawDeg = 12f).containsKey(Arm2d.ELBOW_LAT_NEAR))
    }
}
