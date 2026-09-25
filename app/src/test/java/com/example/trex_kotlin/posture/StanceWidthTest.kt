package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
