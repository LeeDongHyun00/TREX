package com.example.trex_kotlin

import com.example.trex_kotlin.posture.MP_LANDMARK_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 뼈대 관절 게이트(§101c) — 10-09 런지 사선 B 의 먼 다리 재현. */
class SkeletonGateTest {
    private fun sample(vararg joints: Triple<Int, Pair<Float, Float>, Float>): Pair<FloatArray, FloatArray> {
        val xy = FloatArray(MP_LANDMARK_COUNT * 2) { 0.5f }; val vis = FloatArray(MP_LANDMARK_COUNT) { 0.98f }
        for ((i, p, v) in joints) { xy[i * 2] = p.first; xy[i * 2 + 1] = p.second; vis[i] = v }
        return xy to vis
    }

    @Test fun farLegBelowTheLegCutIsHiddenAndFrozenWhileTheNearLegIsDrawn() {
        val g = JointGate(0.5f)
        // 15:52 런지 9578~10475: 먼 무릎(25) 가시성 0.69~0.72 에 x 0.42 → 0.74 → 0.70, 가까운 무릎(26) 0.98
        var (xy, vis) = sample(Triple(25, 0.42f to 0.73f, 0.69f), Triple(26, 0.65f to 0.71f, 0.98f))
        g.update(xy, vis, 0.75f)
        assertFalse("0.69 < 다리 문턱 0.80 — 숨긴다(종전 0.5 게이트는 그렸다)", g.shown[25]); assertTrue(g.shown[26])
        val (xy2, vis2) = sample(Triple(25, 0.74f to 0.74f, 0.68f), Triple(26, 0.75f to 0.73f, 0.98f))
        g.update(xy2, vis2, 0.75f)
        assertFalse(g.shown[25]); assertEquals("가까운 무릎은 원시 좌표", 0.75f, g.target[26 * 2], 1e-6f)
        // 먼 무릎은 한 번도 믿은 적이 없어 원시 좌표(점선 끝) — 믿었던 자리가 생기면 그 자리에 머문다
        assertEquals(0.74f, g.target[25 * 2], 1e-6f)
        val (xy3, vis3) = sample(Triple(25, 0.44f to 0.69f, 0.90f))
        g.update(xy3, vis3, 0.75f)
        assertTrue("0.90 ≥ 0.85 — 다시 그린다", g.shown[25])
        val (xy4, vis4) = sample(Triple(25, 0.80f to 0.80f, 0.70f))
        g.update(xy4, vis4, 0.75f)
        assertFalse(g.shown[25]); assertEquals("숨긴 뒤에는 마지막으로 믿은 자리", 0.44f, g.target[25 * 2], 1e-6f)
    }

    @Test fun aSuddenJumpOfAnUntrustedJointIsHeldForTwoSamplesThenAccepted() {
        val g = JointGate(0.5f)
        val (a, va) = sample(Triple(13, 0.30f to 0.50f, 0.85f))   // 팔꿈치, 가시성 0.85(< 0.90)
        g.update(a, va, 0.75f); assertTrue(g.shown[13])
        val (b, vb) = sample(Triple(13, 0.60f to 0.50f, 0.85f))   // 한 샘플에 x 0.30 → 0.60(높이 단위 0.225 > 0.12)
        g.update(b, vb, 0.75f)
        assertFalse("순간 이동은 보류", g.shown[13]); assertEquals(0.30f, g.target[13 * 2], 1e-6f)
        g.update(b, vb, 0.75f)
        assertFalse("둘째 샘플도 보류", g.shown[13])
        g.update(b, vb, 0.75f)
        assertTrue("같은 자리에 머물면 받아들인다", g.shown[13]); assertEquals(0.60f, g.target[13 * 2], 1e-6f)
        // 가시성이 높은 순간 이동은 바로 믿는다
        val (c, vc) = sample(Triple(13, 0.20f to 0.50f, 0.97f))
        g.update(c, vc, 0.75f); assertTrue(g.shown[13]); assertEquals(0.20f, g.target[13 * 2], 1e-6f)
    }

    @Test fun frontViewLegsAtHighVisibilityAreUnaffected() {
        val g = JointGate(0.5f)
        val (xy, vis) = sample(Triple(25, 0.60f to 0.70f, 0.96f), Triple(26, 0.40f to 0.70f, 0.97f), Triple(27, 0.62f to 0.90f, 0.95f), Triple(28, 0.38f to 0.90f, 0.95f))
        g.update(xy, vis, 0.75f)
        for (i in 25..28) assertTrue("$i", g.shown[i])
        // 보통 관절(어깨)은 종전 문턱 0.5 ± 0.10 — 처음 본 관절은 0.60 이상이어야 그린다
        val g2 = JointGate(0.5f)
        val (xy2, vis2) = sample(Triple(11, 0.5f to 0.3f, 0.55f))
        g2.update(xy2, vis2, 0.75f); assertFalse("0.55 < 0.60 — 아직 아님", g2.shown[11])
        val (xy3, vis3) = sample(Triple(11, 0.5f to 0.3f, 0.62f))
        g2.update(xy3, vis3, 0.75f); assertTrue(g2.shown[11])
        // 한 번 그린 관절은 0.40 아래로 내려가야 숨긴다(이력)
        val (xy4, vis4) = sample(Triple(11, 0.5f to 0.3f, 0.45f))
        g2.update(xy4, vis4, 0.75f); assertTrue(g2.shown[11])
    }
}
