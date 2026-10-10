package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서서 하는 종목의 세트 중 가로 잘림 감시(spec §101, 10-08 사이드 런지) — 연속 3·사용자 기준 방향·15 s 간격·세트당 3회·양쪽 이탈. */
class StandingFramingTest {
    private fun xy(leftAnkleX: Float = 0.4f, rightAnkleX: Float = 0.6f): FloatArray {
        val a = FloatArray(66) { 0.5f }
        for (i in intArrayOf(27, 29, 31)) a[i * 2] = leftAnkleX
        for (i in intArrayOf(28, 30, 32)) a[i * 2] = rightAnkleX
        return a
    }
    private val facingCamera = mapOf(LegGeometry.FACE_DX to 0.3f)

    @Test fun threeConsecutiveEdgeFramesBlockAndThreeCleanFramesRelease() {
        val f = StandingFraming()
        assertTrue(f.update(0, xy(), facingCamera).ok)
        assertTrue(f.update(300, xy(leftAnkleX = 0.99f), facingCamera).ok)
        assertTrue(f.update(600, xy(leftAnkleX = 0.99f), facingCamera).ok)
        val r = f.update(900, xy(leftAnkleX = 0.99f), facingCamera)
        assertFalse(r.ok)
        // MediaPipe 왼발목(27)이 이미지 오른쪽으로 나감 + 카메라를 봄 = 사용자 왼발 → 오른쪽으로 옮기라고
        assertEquals("왼발이 화면 밖으로 나가요", r.message); assertEquals("오른쪽으로 반걸음 옮겨 서 주세요", r.fix)
        assertEquals(true, r.outRight)
        assertFalse(f.update(1200, xy(), facingCamera).ok); assertFalse(f.update(1500, xy(), facingCamera).ok)
        assertTrue(f.update(1800, xy(), facingCamera).ok)
    }

    @Test fun directionFlipsWhenTheUserFacesAway() {
        val f = StandingFraming()
        repeat(3) { f.update(it * 300L, xy(leftAnkleX = 0.99f), mapOf(LegGeometry.FACE_DX to -0.3f)) }
        assertEquals("오른발이 화면 밖으로 나가요", f.report.message); assertEquals("왼쪽으로 반걸음 옮겨 서 주세요", f.report.fix)
    }

    @Test fun speechIsGappedAndCappedPerSet() {
        val f = StandingFraming()
        repeat(3) { f.update(it * 300L, xy(rightAnkleX = 0.01f), facingCamera) }
        assertEquals("오른발이 화면 밖으로 나가요. 왼쪽으로 반걸음 옮겨 서 주세요.", f.takeSpeech(900))
        assertNull(f.takeSpeech(5_000))
        assertTrue(f.takeSpeech(16_000) != null); assertTrue(f.takeSpeech(31_000) != null)
        assertNull("세트당 3회", f.takeSpeech(46_000))
        f.reset(); assertTrue(f.report.ok)
    }

    @Test fun bothSidesWithinTenSecondsAskToStepBack() {
        val f = StandingFraming()
        repeat(3) { f.update(it * 300L, xy(leftAnkleX = 0.99f), facingCamera) }
        repeat(3) { f.update(2000L + it * 300L, xy(rightAnkleX = 0.01f), facingCamera) }
        assertEquals(StandingFraming.BOTH_MESSAGE, f.report.message); assertEquals(StandingFraming.BOTH_FIX, f.report.fix)
    }

    @Test fun insideTheTwoPercentBandIsNotAnEdge() {
        val f = StandingFraming()
        repeat(5) { assertTrue(f.update(it * 300L, xy(leftAnkleX = 0.975f, rightAnkleX = 0.03f), facingCamera).ok) }
    }
}
