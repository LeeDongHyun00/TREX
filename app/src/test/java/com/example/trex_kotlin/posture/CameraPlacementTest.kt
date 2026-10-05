package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** 배치 지문(§91) — 핀홀로 합성한 프레임에서 거리·높이·피치가 되돌아와야 한다. 좌표 규약은 A4_3_geometry.py 와 같다. */
class CameraPlacementTest {

    private val w = 480; private val h = 640
    private val fPx = 480f
    // f_px = focal/sensor_long × image_long × zoom = 4.0/8.0 × 640 × 1 = 320 … 아래 lens 는 f_px 가 480 이 되도록 잡는다
    private val lens = LensInfo(focalMm = 6f, sensorWMm = 8f, sensorHMm = 6f, activeW = 4000, activeH = 3000, zoom = 1f)

    /**
     * 카메라 높이 [camH], 수평 거리 [dist] 에 선 사람(어깨 폭 0.4 m, 어깨 높이 1.4 m, 발목 높이 0.08 m)을 피치 [pitchDeg] 인 카메라로 찍는다.
     * 피치 + = 카메라가 위를 봄 → 같은 점이 화면에서 더 아래로 간다. 월드는 MediaPipe 규약(골반 원점, 척도만 쓴다).
     */
    private fun frame(camH: Float, dist: Float, pitchDeg: Float, inferMs: Long = 60L): PoseSample {
        val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
        fun row(heightM: Float): Float {
            val below = atan((camH - heightM) / dist) + p          // 광축 아래 각
            return h / 2f + fPx * tan(below)
        }
        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        val world = FloatArray(MP_LANDMARK_COUNT * 3)
        val shPx = fPx * 0.4f / dist
        xy[2 * 11] = (w / 2f + shPx / 2) / w; xy[2 * 11 + 1] = row(1.4f) / h
        xy[2 * 12] = (w / 2f - shPx / 2) / w; xy[2 * 12 + 1] = row(1.4f) / h
        xy[2 * 27] = (w / 2f + 20) / w; xy[2 * 27 + 1] = row(0.08f) / h
        xy[2 * 28] = (w / 2f - 20) / w; xy[2 * 28 + 1] = row(0.08f) / h
        world[3 * 11] = 0.2f; world[3 * 12] = -0.2f
        val up = Vec3(0f, cos(p), -sin(p))   // 피치 = asin(−up_z)
        return PoseSample(true, xy, FloatArray(MP_LANDMARK_COUNT) { 0.9f }, mapOf("knee_mean" to 170f), 30, inferMs, w, h,
            up = up, upFromGravity = true, world = world)
    }

    @Test
    fun recoversDistanceHeightAndPitchFromALevelCamera() {
        val samples = List(5) { frame(camH = 0.7f, dist = 2.0f, pitchDeg = 0f) }
        val p = CameraPlacementEstimator.estimate(samples, listOf(0L, 300L, 600L, 900L, 1200L), lens, standing = true)!!
        assertEquals(5, p.frames)
        assertEquals(0f, p.pitchDeg!!, 0.01f); assertEquals(0f, p.rollDeg!!, 0.01f); assertEquals(0f, p.tiltDeg!!, 0.01f)
        assertEquals(fPx, p.fPx!!, 0.01f)
        assertEquals(2.0f, p.distanceM!!, 0.01f)
        assertEquals(0.7f, p.heightM!!, 0.01f)
        assertEquals(1000f / 300f, p.fps!!, 0.01f)
        assertEquals(60f, p.inferMsMed!!, 0.01f)
    }

    @Test
    fun pitchIsRemovedBeforeTheHeightIsRead() {
        // 카메라가 5° 위를 보면 발목 행이 내려간다 — 피치를 빼지 않으면 높이가 0.7 보다 커진다
        val samples = List(3) { frame(camH = 0.7f, dist = 2.0f, pitchDeg = 5f) }
        val p = CameraPlacementEstimator.estimate(samples, null, lens, standing = true)!!
        assertEquals(5f, p.pitchDeg!!, 0.05f)
        assertEquals(5f, p.tiltDeg!!, 0.05f)
        assertEquals(0.7f, p.heightM!!, 0.02f)
        assertNull(p.fps)
    }

    @Test
    fun floorExercisesKeepDistanceButSkipTheAnkleRowHeight() {
        val p = CameraPlacementEstimator.estimate(List(3) { frame(0.3f, 1.5f, 0f) }, null, lens, standing = false)!!
        assertEquals(1.5f, p.distanceM!!, 0.01f)
        assertNull(p.heightM)
    }

    @Test
    fun withoutLensOrImuOnlyWhatIsKnownIsWritten() {
        val noImu = frame(0.7f, 2f, 0f).let { PoseSample(true, it.normalizedXy, it.visibility, it.features, 30, 60L, w, h, world = it.world) }
        val p = CameraPlacementEstimator.estimate(listOf(noImu, noImu), listOf(0L, 300L), null, standing = true)!!
        assertEquals(0, p.frames)
        assertNull(p.pitchDeg); assertNull(p.fPx); assertNull(p.distanceM); assertNull(p.heightM)
        assertNotNull(p.fps)
        // 아무 입력도 없으면 블록 자체가 없다
        assertNull(CameraPlacementEstimator.estimate(emptyList(), null, null, standing = true))
    }

    @Test
    fun tiltMaxExposesAPhonePickedUpAtTheEnd() {
        val still = List(6) { frame(0.7f, 2f, 0f) }
        val lifted = frame(0.7f, 2f, 60f)
        val p = CameraPlacementEstimator.estimate(still + lifted, null, lens, standing = true)!!
        assertEquals(0f, p.tiltDeg!!, 0.01f)
        assertTrue(p.tiltMaxDeg!! > 59f)
    }

    @Test
    fun focalPxUsesTheLongSidesAndZoom() {
        assertEquals(480f, lens.focalPx(480, 640)!!, 0.01f)
        assertEquals(480f, lens.focalPx(640, 480)!!, 0.01f)
        assertEquals(960f, lens.copy(zoom = 2f).focalPx(480, 640)!!, 0.01f)
        assertNull(lens.copy(focalMm = null).focalPx(480, 640))
    }
}
