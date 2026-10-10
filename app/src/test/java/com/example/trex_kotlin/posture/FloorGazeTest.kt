package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 바닥 세 종목 시선 분류(§101d) — 얼굴 메시 요·검출·화면 밖·교정 규칙. */
class FloorGazeTest {
    private fun f(found: Float?, yaw: Float?, chain: Float = 1f): Map<String, Float> = buildMap {
        put(FloorChain.CHAIN, chain); found?.let { put(FloorGaze.FOUND, it) }; yaw?.let { put(FloorGaze.YAW, it) }
    }

    @Test fun yawBandsClassifyFrontCameraAndAway() {
        assertEquals(FloorGaze.FRONT, FloorGaze.classify(f(1f, 0f)))
        assertEquals(FloorGaze.FRONT, FloorGaze.classify(f(1f, 24f)))
        assertEquals(FloorGaze.CAMERA, FloorGaze.classify(f(1f, 26f)))
        assertEquals(FloorGaze.CAMERA, FloorGaze.classify(f(1f, 85f)))
        assertEquals(FloorGaze.AWAY, FloorGaze.classify(f(1f, -40f)))
        assertEquals("얼굴 모델이 안 돈 프레임(서서 하는 종목·모델 실패·머리 안 보임)은 모름", FloorGaze.UNKNOWN, FloorGaze.classify(f(null, null)))
        assertEquals("검출됐는데 요가 없으면 모름", FloorGaze.UNKNOWN, FloorGaze.classify(f(1f, null)))
    }

    @Test fun faceNotFoundWithTheBodyVisibleIsOffScreenNeverAway() {
        // 폰 1차 확인(10-10): 측면 폰은 카메라 쪽 얼굴만 찾고 옆얼굴(정면)·뒤통수(반대쪽)는 똑같이 못 찾는다 — 미검출은 '화면 밖'(카메라를 향하지 않음)이지 반대쪽이 아니다.
        // §101d 초판의 '옆얼굴을 본 뒤의 미검출 = 반대쪽' 은 천장을 보던 레그 레이즈 4회를 반대쪽으로 읽어 틀린 문장을 냈다(원칙 #1·#6)
        assertEquals(FloorGaze.OFF_SCREEN, FloorGaze.classify(f(0f, null)))
        assertEquals("사람이 없으면 모름", FloorGaze.UNKNOWN, FloorGaze.classify(f(0f, null, chain = 0f)))
        assertFalse(FloorGaze.wrong(FloorGaze.OFF_SCREEN))
        assertEquals("범위 안", FloorGaze.label(FloorGaze.OFF_SCREEN)); assertEquals("off_screen", FloorGaze.key(FloorGaze.OFF_SCREEN))
        // 교정 = 벗어남이 아닌 아는 상태(사용자 결정 10-10 오후: 화면 쪽·반대쪽을 가르지 않는다 — 벗어남 = 카메라를 향함)
        assertTrue(FloorGaze.recovers(FloorGaze.FRONT)); assertTrue(FloorGaze.recovers(FloorGaze.OFF_SCREEN))
        assertFalse(FloorGaze.recovers(FloorGaze.UNKNOWN)); assertFalse(FloorGaze.recovers(FloorGaze.CAMERA)); assertFalse(FloorGaze.recovers(FloorGaze.AWAY))
        assertEquals("벗어남", FloorGaze.label(FloorGaze.AWAY))
    }

    @Test fun majorityIgnoresUnknownUnlessItDominates() {
        assertEquals(FloorGaze.CAMERA, FloorGaze.majority(listOf(FloorGaze.CAMERA, FloorGaze.CAMERA, FloorGaze.FRONT, FloorGaze.UNKNOWN)))
        assertEquals(FloorGaze.UNKNOWN, FloorGaze.majority(listOf(FloorGaze.CAMERA, FloorGaze.UNKNOWN, FloorGaze.UNKNOWN)))
        assertEquals(FloorGaze.UNKNOWN, FloorGaze.majority(emptyList()))
        assertEquals(FloorGaze.FRONT, FloorGaze.majority(listOf(FloorGaze.FRONT, FloorGaze.AWAY, FloorGaze.FRONT)))
        assertEquals("화면 밖은 아는 상태 — 옆얼굴 구간의 어쩌다 한 프레임 검출(10-10 크런치 요 82°)을 흡수한다", FloorGaze.OFF_SCREEN,
            FloorGaze.majority(listOf(FloorGaze.OFF_SCREEN, FloorGaze.OFF_SCREEN, FloorGaze.CAMERA, FloorGaze.OFF_SCREEN, FloorGaze.UNKNOWN)))
        assertTrue(FloorGaze.wrong(FloorGaze.AWAY)); assertFalse(FloorGaze.wrong(FloorGaze.FRONT)); assertFalse(FloorGaze.wrong(FloorGaze.UNKNOWN))
        assertEquals("벗어남", FloorGaze.label(FloorGaze.CAMERA)); assertEquals("측정 중", FloorGaze.label(FloorGaze.UNKNOWN))
    }

    @Test fun crunchRepCarriesTheMajorityGazeOfItsCycle() {
        // 추적기: 사이클 프레임의 상태 다수결이 회에 실린다. 미검출 채 한 회는 화면 밖
        val tr = FloorCycleTracker(FloorProfile.CRUNCH)
        var t = 0L
        // FloorCycleTest 의 crunch() 헬퍼와 같은 프레임 — 몸통 들림(lift)에 얼굴 피처를 얹는다
        fun frame(lift: Float, found: Float?, yaw: Float?) {
            val m = HashMap<String, Float>()
            m[FloorChain.TRUNK_LIFT] = lift; m[FloorChain.EAR_LIFT] = 10f + lift; m[FloorChain.AXIS_H] = lift; m[FloorChain.TORSO_ELEV] = lift; m[FloorChain.CHAIN] = 1f; m[FloorChain.YAW] = 0.05f
            found?.let { m[FloorGaze.FOUND] = it }; yaw?.let { m[FloorGaze.YAW] = it }
            tr.onFrame(t, m); t += 300
        }
        fun rep(found: Float?, yaw: Float?) { for (l in listOf(6f, 12f, 18f, 12f, 6f, 0f, 0f)) frame(l, found, yaw) }
        repeat(4) { frame(0f, 0f, null) }                    // 누움(옆얼굴 — 얼굴 미검출)
        rep(1f, 50f)                                         // 화면 쪽으로 돌린 채 한 회
        rep(0f, null)                                        // 얼굴 미검출 채 한 회 → 화면 밖
        rep(1f, 2f)                                          // 옆얼굴이 검출된 회 → 정면
        val reps = tr.repDetail
        assertEquals("세 회가 세졌는가: ${reps.size}", 3, reps.size)
        assertEquals(FloorGaze.CAMERA, reps[0].gaze)
        assertEquals(FloorGaze.OFF_SCREEN, reps[1].gaze)
        assertEquals(FloorGaze.FRONT, reps[2].gaze)
    }
}
