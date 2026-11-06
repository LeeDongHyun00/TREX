// Android 정본 테스트 — tools/sync_ios_core.py
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
     * 피치 + = 카메라가 위를 봄 → 같은 점이 화면에서 더 아래로 간다. 월드는 MediaPipe 규약(y 아래, 척도만 쓴다) —
     * [worldK] 는 월드 척도 오차(실제의 k 배). 키 170 cm 의 어깨 높이 0.818 × 1.70 = 1.3906 m, 발목 0.08 → 월드 발목→어깨 1.3106 × k.
     * [rollDeg] 는 카메라 롤 θ — 화면 점을 주점 둘레로 R(θ) 돌리고 up 은 (−sin θ, cos θ).
     */
    private fun frame(camH: Float, dist: Float, pitchDeg: Float, inferMs: Long = 60L, worldK: Float = 1f, rollDeg: Float = 0f): PoseSample {
        val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val th = Math.toRadians(rollDeg.toDouble()).toFloat()
        fun row(heightM: Float): Float {
            val below = atan((camH - heightM) / dist) + p          // 광축 아래 각
            return h / 2f + fPx * tan(below)
        }
        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        val world = FloatArray(MP_LANDMARK_COUNT * 3)
        val shPx = fPx * 0.4f / dist
        fun put(i: Int, px: Float, py: Float) {
            // 수평 화면 좌표(px 오른쪽, py 아래) → 롤 θ 로 돌린 관측(X 오른쪽·Y 위 좌표계에서 R(θ))
            val x = px - w / 2f; val yUp = -(py - h / 2f)
            val xr = x * cos(th) - yUp * sin(th); val yr = x * sin(th) + yUp * cos(th)
            xy[2 * i] = (xr + w / 2f) / w; xy[2 * i + 1] = (-yr + h / 2f) / h
        }
        put(11, w / 2f + shPx / 2, row(1.4f)); put(12, w / 2f - shPx / 2, row(1.4f))
        put(27, w / 2f + 20, row(0.08f)); put(28, w / 2f - 20, row(0.08f))
        world[3 * 11] = 0.2f * worldK; world[3 * 12] = -0.2f * worldK
        world[3 * 27 + 1] = 1.3106f * worldK; world[3 * 28 + 1] = 1.3106f * worldK   // 발목은 어깨 아래(MP y 아래 +)
        val up = Vec3(-sin(th) * cos(p), cos(th) * cos(p), -sin(p))   // 피치 = asin(−up_z), 롤 = atan2(up_x, up_y) = −θ
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
    fun profileStatureFixesTheWorldScale() {
        // 월드가 실제의 1.2 배로 나오면 키 없이는 거리도 1.2 배 — 키 170 cm 를 주면 배율 1/1.2 로 되돌아온다
        val samples = List(6) { frame(0.7f, 2.0f, 0f, worldK = 1.2f) }
        val raw = CameraPlacementEstimator.estimate(samples, null, lens, standing = true)!!
        assertEquals(2.4f, raw.distanceM!!, 0.01f)
        assertNull(raw.scale); assertNull(raw.subjectHeightCm)
        val fixed = CameraPlacementEstimator.estimate(samples, null, lens, standing = true, subjectHeightCm = 170f)!!
        assertEquals(1f / 1.2f, fixed.scale!!, 0.001f)
        assertEquals(170f, fixed.subjectHeightCm!!, 0.01f)
        assertEquals(2.0f, fixed.distanceM!!, 0.01f)
        assertEquals(0.7f, fixed.heightM!!, 0.01f)
        // 바닥 종목·키 0·프레임 5개 미만이면 배율을 쓰지 않는다
        assertNull(CameraPlacementEstimator.estimate(samples, null, lens, standing = false, subjectHeightCm = 170f)!!.scale)
        assertNull(CameraPlacementEstimator.estimate(samples, null, lens, standing = true, subjectHeightCm = 0f)!!.scale)
        assertNull(CameraPlacementEstimator.estimate(samples.take(4), null, lens, standing = true, subjectHeightCm = 170f)!!.scale)
    }

    @Test
    fun rollIsLevelledBeforeTheAnkleRowIsRead() {
        // 폰이 15° 기울면 발목 행이 화면에서 돌아간다 — up 으로 되돌리면 높이가 그대로고, 어깨 폭은 두 점 거리라 거리도 그대로
        val samples = List(3) { frame(0.7f, 2.0f, 0f, rollDeg = 15f) }
        val p = CameraPlacementEstimator.estimate(samples, null, lens, standing = true)!!
        assertEquals(-15f, p.rollDeg!!, 0.05f)
        assertEquals(15f, p.tiltDeg!!, 0.05f)
        assertEquals(0f, p.pitchDeg!!, 0.05f)
        assertEquals(2.0f, p.distanceM!!, 0.01f)
        assertEquals(0.7f, p.heightM!!, 0.02f)
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
