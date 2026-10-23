package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 추론 이후 처리(§108c `PoseLandmarks`) — Android 분석기와 iPhone 카메라가 같은 함수로 PoseSample 을 만든다.
 * 여기서는 그 함수의 약속(가시성 거르기·up 자가검증·중력 출처·얼굴 피처 사본·얼굴 결과 시간·크롭 기하)을 고정한다.
 */
class PoseLandmarksTest {

    /** 정면에 선 사람 — MediaPipe 월드 좌표(m, y 아래·골반 원점). 정규화 xy 는 640×480 영상의 가운데 세로선 근처. */
    private fun standing(): Triple<FloatArray, FloatArray, FloatArray> {
        val world = FloatArray(MP_LANDMARK_COUNT * 3)
        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        val vis = FloatArray(MP_LANDMARK_COUNT) { 0.9f }
        fun put(i: Int, x: Float, y: Float, z: Float = 0f) {
            world[i * 3] = x; world[i * 3 + 1] = y; world[i * 3 + 2] = z
            xy[i * 2] = 0.5f + x * 0.4f; xy[i * 2 + 1] = 0.5f + y * 0.4f
        }
        put(0, 0f, -0.62f)                              // 코
        for (i in 1..6) put(i, if (i <= 3) 0.02f else -0.02f, -0.65f)
        put(7, 0.07f, -0.62f); put(8, -0.07f, -0.62f)   // 귀
        put(9, 0.02f, -0.58f); put(10, -0.02f, -0.58f)
        put(11, 0.18f, -0.45f); put(12, -0.18f, -0.45f) // 어깨
        put(13, 0.22f, -0.2f); put(14, -0.22f, -0.2f)
        put(15, 0.23f, 0.02f); put(16, -0.23f, 0.02f)
        for (i in 17..22) put(i, if (i % 2 == 1) 0.24f else -0.24f, 0.06f)
        put(23, 0.1f, 0f); put(24, -0.1f, 0f)           // 골반
        put(25, 0.11f, 0.42f); put(26, -0.11f, 0.42f)
        put(27, 0.12f, 0.84f); put(28, -0.12f, 0.84f)   // 발목
        put(29, 0.12f, 0.88f, 0.05f); put(30, -0.12f, 0.88f, 0.05f)
        put(31, 0.13f, 0.87f, -0.12f); put(32, -0.13f, 0.87f, -0.12f)
        return Triple(xy, vis, world)
    }

    @Test
    fun standingPersonIsDetectedWithFeaturesAndVerifiedUp() {
        val (xy, vis, world) = standing()
        val s = PoseLandmarks.sample(xy, vis, world, 640, 480, 12L, SCREEN_UP)
        assertTrue(s.detected)
        assertTrue("서 있는 사람의 프레임 피처가 계산된다", s.features.isNotEmpty())
        assertTrue(s.upVerified)
        assertFalse(s.upFlipped)
        assertFalse("화면 세로축 폴백(SCREEN_UP 그 객체)은 중력 출처가 아니다", s.upFromGravity)
        assertSame(xy, s.normalizedXy)
        assertSame(world, s.world)
        assertEquals(33, s.visibleJointCount)
        assertEquals(640, s.imageWidth)
        assertEquals(12L, s.inferMs)
    }

    @Test
    fun invertedUpIsFlippedAndReported() {
        val (xy, vis, world) = standing()
        var flips = 0
        val s = PoseLandmarks.sample(xy, vis, world, 640, 480, 0L, Vec3(0f, -1f, 0f), onFlip = { flips++ })
        assertTrue(s.upFlipped)
        assertEquals(1, flips)
        assertEquals(1f, s.up.y, 1e-6f)
        assertTrue("SCREEN_UP 이 아닌 up 은 IMU 출처로 본다", s.upFromGravity)
    }

    @Test
    fun lowVisibilityJointsAreDroppedButCountedOut() {
        val (xy, vis, world) = standing()
        vis[15] = 0.4f; vis[16] = 0.49f
        val s = PoseLandmarks.sample(xy, vis, world, 640, 480, 0L, SCREEN_UP)
        assertEquals(31, s.visibleJointCount)
        val full = PoseLandmarks.sample(standing().first, standing().second, standing().third, 640, 480, 0L, SCREEN_UP)
        assertTrue("손목을 버리면 손목에 기대는 피처가 줄어든다", s.features.size < full.features.size)
    }

    @Test
    fun faceFeaturesGoOnACopyOnlyWhenRequested() {
        val (xy, vis, world) = standing()
        val plain = PoseLandmarks.sample(xy, vis, world, 640, 480, 0L, SCREEN_UP)
        assertFalse(plain.features.containsKey(FloorGaze.FOUND))
        val withFace = PoseLandmarks.sample(xy, vis, world, 640, 480, 0L, SCREEN_UP, addFace = { it[FloorGaze.FOUND] = 1f })
        assertEquals(1f, withFace.features[FloorGaze.FOUND])
        assertEquals(plain.features.size + 1, withFace.features.size)
    }

    @Test
    fun faceTrackRunsEveryGridCellAndExpiresStaleReadings() {
        val track = FaceTrack()
        val crop = FaceCrop(100f, 100f, 50f, -10f)
        assertTrue(track.due(1_000L))
        track.record(1_000L, found = true, yaw = 12f, inferMs = 30L, crop = crop)
        assertFalse(track.due(1_000L + FACE_INTERVAL_MS - 1))
        assertTrue(track.due(1_000L + FACE_INTERVAL_MS))
        val out = HashMap<String, Float>()
        track.apply(1_000L + FACE_STALE_MS, out)
        assertEquals(1f, out[FloorGaze.FOUND])
        assertEquals(12f, out[FloorGaze.YAW])
        assertEquals(30f, out[FloorGaze.INFER_MS])
        assertEquals(-10f, out[FloorGaze.ROTATION])
        assertEquals(50f, out[FloorGaze.SIZE])
        val late = HashMap<String, Float>()
        track.apply(1_000L + FACE_STALE_MS + 1, late)
        assertTrue("오래된 얼굴 결과는 얹지 않는다(모름)", late.isEmpty())
        // 얼굴이 없으면 요 키가 없다
        track.record(2_000L, found = false, yaw = Float.NaN, inferMs = 20L, crop = crop)
        val none = HashMap<String, Float>()
        track.apply(2_000L, none)
        assertEquals(0f, none[FloorGaze.FOUND])
        assertNull(none[FloorGaze.YAW])
        // 머리가 안 보여 건너뛴 칸은 다음 칸까지 다시 돌리지 않는다
        track.skip(3_000L)
        assertFalse(track.due(3_100L))
        track.clear()
        val cleared = HashMap<String, Float>()
        track.apply(3_000L, cleared)
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun faceCropFollowsTheNeckDirection() {
        val (xy, vis, _) = standing()
        val upright = FaceCrop.of(xy, vis, 640, 480)
        assertNotNull(upright)
        upright!!
        // 귀 중점이 어깨 중점 바로 위 — 돌리지 않는다
        assertEquals(0f, upright.rotationDeg, 1e-3f)
        assertEquals(upright.headPx * FACE_CROP_SCALE, upright.side, 1e-4f)
        assertEquals(FACE_CROP_PX / upright.side, upright.scale, 1e-6f)
        assertTrue(upright.headPx >= 40f)
        // 누워 머리가 오른쪽(+x)을 향하면 목 방향이 위에서 시계 방향 90° — 반대로 돌린다
        val lying = xy.copyOf()
        fun set(i: Int, x: Float, y: Float) { lying[i * 2] = x; lying[i * 2 + 1] = y }
        set(11, 0.5f, 0.5f); set(12, 0.5f, 0.52f); set(7, 0.6f, 0.5f); set(8, 0.6f, 0.52f); set(0, 0.62f, 0.51f)
        val turned = FaceCrop.of(lying, vis, 640, 480)!!
        assertEquals(-90f, turned.rotationDeg, 0.5f)
        // 코·귀 중 하나만 보이면 머리를 못 잡는다
        val hidden = vis.copyOf().also { it[0] = 0f; it[7] = 0f }
        assertNull(FaceCrop.of(xy, hidden, 640, 480))
    }
}
