package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferencePolicyTest {
    private val p = InferencePolicy(sampleIntervalMs = 300L, idleIntervalMs = 400L, resultIntervalMs = 1500L)

    @Test
    fun recordingIntervalEqualsSampleInterval() {
        assertEquals(300L, p.intervalMs(InferencePhase.RECORDING, InferencePolicy.THERMAL_NONE))
        assertEquals(400L, p.intervalMs(InferencePhase.IDLE, InferencePolicy.THERMAL_NONE))
        assertEquals(1500L, p.intervalMs(InferencePhase.RESULT, InferencePolicy.THERMAL_NONE))
    }

    @Test
    fun thermalStatusSlowsDownButKeepsEnoughSamplesPerSet() {
        assertEquals(1.0f, p.thermalMultiplier(InferencePolicy.THERMAL_LIGHT))
        assertEquals(1.5f, p.thermalMultiplier(InferencePolicy.THERMAL_MODERATE))
        assertEquals(2.0f, p.thermalMultiplier(InferencePolicy.THERMAL_SEVERE))
        assertEquals(3.0f, p.thermalMultiplier(InferencePolicy.THERMAL_SHUTDOWN))
        // 심한 발열(×2) 에서도 10초 세트면 16프레임 이상 — 연구 기준 창(16프레임) 유지
        val severe = p.intervalMs(InferencePhase.RECORDING, InferencePolicy.THERMAL_SEVERE)
        assertTrue(10_000L / severe >= 16)
    }

    @Test
    fun shouldInferGatesByInterval() {
        assertTrue(p.shouldInfer(nowMs = 1000L, lastInferAtMs = 0L, InferencePhase.IDLE, 0))   // 첫 프레임
        assertFalse(p.shouldInfer(nowMs = 1200L, lastInferAtMs = 1000L, InferencePhase.IDLE, 0)) // 200ms < 400ms
        assertTrue(p.shouldInfer(nowMs = 1400L, lastInferAtMs = 1000L, InferencePhase.IDLE, 0))
        assertTrue(p.shouldInfer(nowMs = 1300L, lastInferAtMs = 1000L, InferencePhase.RECORDING, 0))
        assertFalse(p.shouldInfer(nowMs = 1300L, lastInferAtMs = 1000L, InferencePhase.RECORDING, InferencePolicy.THERMAL_MODERATE)) // 450ms
    }
}
