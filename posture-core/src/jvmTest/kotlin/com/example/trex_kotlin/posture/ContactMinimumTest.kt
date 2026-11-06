// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 접촉 최소 거리(§98a) — 85 ms 표본의 최솟값·표본 사이 꼭짓점 추정·끊김·가려짐·칸 비우기. */
class ContactMinimumTest {
    private fun f(l: Float?, r: Float? = 1.2f): Map<String, Float> = buildMap { l?.let { put("elbow_knee_L", it) }; r?.let { put("elbow_knee_R", it) } }

    @Test fun keepsTheMinimumBetweenJudgedFramesAndEstimatesTheVertexBetweenSamples() {
        val c = ContactMinimum.elbowKnee()
        var t = 0L
        for (v in listOf(0.9f, 0.6f, 0.35f, 0.5f, 0.8f)) { c.offer(t, f(v)); t += 85 }
        val out = c.drain()
        // 표본 최소 0.35, 이웃(0.6·0.5)이 비대칭이라 꼭짓점은 표본 사이: 0.35 − |0.6 − 0.5| / 2 = 0.30
        assertEquals(0.30f, out.getValue("elbow_knee_min_L"), 1e-6f)
        assertEquals("오른쪽은 변화가 없어도 최솟값은 낸다", 1.2f, out.getValue("elbow_knee_min_R"), 1e-6f)
        assertTrue("비운 뒤 새 표본이 없으면 빈 맵", c.drain().isEmpty())
        c.offer(t, f(0.7f)); assertEquals(0.7f, c.drain().getValue("elbow_knee_min_L"), 1e-6f)
    }

    @Test fun symmetricNeighboursMeanTheSampleIsTheVertexAndTheEstimateNeverGoesBelowZero() {
        val c = ContactMinimum.elbowKnee()
        c.offer(0, f(0.5f)); c.offer(85, f(0.2f)); c.offer(170, f(0.5f))
        assertEquals(0.2f, c.drain().getValue("elbow_knee_min_L"), 1e-6f)
        c.offer(255, f(1.0f)); c.offer(340, f(0.05f)); c.offer(425, f(0.6f))     // 0.05 − 0.2 < 0 → 0
        assertEquals(0f, c.drain().getValue("elbow_knee_min_L"), 1e-6f)
    }

    @Test fun gapsAndMissingValuesBreakTheTripleButNotTheMinimum() {
        val c = ContactMinimum.elbowKnee()
        c.offer(0, f(0.9f)); c.offer(85, f(0.35f)); c.offer(1000, f(0.5f))        // 끊김(> 250 ms) — 이웃을 버린다
        assertEquals(0.35f, c.drain().getValue("elbow_knee_min_L"), 1e-6f)
        c.offer(1085, f(0.9f)); c.offer(1170, f(null)); c.offer(1255, f(0.35f)); c.offer(1340, f(0.5f))   // 가려진 프레임이 가운데 — 꼭짓점 추정 없음
        val out = c.drain()
        assertEquals(0.35f, out.getValue("elbow_knee_min_L"), 1e-6f)
        c.offer(1425, f(null, null)); assertTrue("값이 전혀 없으면 내보낼 것도 없다", c.drain().isEmpty())
        c.offer(1510, f(0.3f)); c.clear(); assertTrue(c.drain().isEmpty())
    }
}
