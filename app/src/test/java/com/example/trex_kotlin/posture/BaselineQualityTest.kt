package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 측정된 프레임 카운터 (실기기 로그 진단 DEVICE_SET_VARIANCE 대응).
 *
 * 관절이 가려지면 FloorFeatureExtractor 가 빈 맵을 돌려주는데, FeatureAggregator.frameCount 는
 * 그래도 증가한다. 그 값을 UI 에 보여주면 "120프레임 찍힘"으로 보이지만 실제 측정은 0 — 실기기에서
 * 9세트 중 5세트가 이렇게 낭비됐다. measurableFrames 는 실제로 값이 나온 프레임만 센다.
 */
class BaselineQualityTest {

    private val features = listOf("head_trunk_ang__mean", "hip_dev_ankle__max")

    @Test
    fun emptyFramesCountAsRecordedButNotMeasurable() {
        val agg = FeatureAggregator()
        repeat(20) { agg.add(emptyMap()) }   // 관절 가림: 피처 없음
        assertEquals(20, agg.frameCount)
        assertEquals(0, BaselineCollector.measurableFrames(agg, features))
    }

    @Test
    fun countsMaxAcrossTargetFeatures() {
        val agg = FeatureAggregator()
        repeat(5) { agg.add(mapOf("head_trunk_ang" to 90f, "hip_dev_ankle" to 0.1f)) }
        repeat(7) { agg.add(mapOf("head_trunk_ang" to 92f)) }   // 골반·발목만 가려진 프레임
        assertEquals(12, agg.frameCount)
        // head_trunk 는 12프레임, hip_dev_ankle 은 5프레임 → 최댓값 12
        assertEquals(12, BaselineCollector.measurableFrames(agg, features))
        assertEquals(5, BaselineCollector.measurableFrames(agg, listOf("hip_dev_ankle__max")))
    }

    @Test
    fun unknownFeatureNamesAreZero() {
        val agg = FeatureAggregator()
        repeat(9) { agg.add(mapOf("head_trunk_ang" to 90f)) }
        assertEquals(0, BaselineCollector.measurableFrames(agg, listOf("nope__mean")))
        assertEquals(0, BaselineCollector.measurableFrames(agg, emptyList()))
    }
}
