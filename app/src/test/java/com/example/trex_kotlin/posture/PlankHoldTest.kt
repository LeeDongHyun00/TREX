package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/**
 * 플랭크 유지 시계(§99, 설계 §4.3·§4.7) — 진입 소급·단조 증가·사유 순서·사유별 시간·폴백을 잠근다. 프레임 간격 300 ms(판정 격자).
 * 재생기(replay-jvm)도 이 파일을 컴파일한다 — 엔진 파일 밖은 쓰지 않는다.
 */
class PlankHoldTest {
    private fun plank(knee: Float = 175f, hipFloor: Float = 0.4f, hipOff: Float = 0.02f, axis: Float? = 5f, yaw: Float? = 0.05f, chain: Float = 1f): Map<String, Float> = buildMap {
        put(FloorChain.CHAIN, chain); put(FloorChain.KNEE, knee); put(FloorChain.HIP_FLOOR, hipFloor); put(FloorChain.HIP_OFF, hipOff)
        axis?.let { put(FloorChain.AXIS_H, it) }; yaw?.let { put(FloorChain.YAW, it) }
    }

    private class Run {
        val clock = PlankHoldClock()
        var t = 0L
        val events = ArrayList<PlankHoldEvent>()
        val held = ArrayList<Long>()
        fun feed(f: Map<String, Float>?, n: Int = 1, stepMs: Long = 300L) { repeat(n) { events += clock.onFrame(t, f); held += clock.heldMs; t += stepMs } }
        fun kinds() = events.map { it.kind }
    }

    @Test fun entryIsConfirmedAfterOneSecondAndCreditedRetroactively() {
        val r = Run()
        r.feed(plank(), 4)                                          // 0·300·600·900 ms — 아직 확정 전
        assertEquals(0L, r.clock.heldMs); assertEquals(PlankHoldClock.Phase.WAIT, r.clock.phase)
        r.feed(plank())                                             // 1200 ms — 확정, 진입부터 소급
        assertEquals(PlankHoldClock.Phase.HOLD, r.clock.phase); assertEquals(1200L, r.clock.heldMs)
        assertEquals(listOf(PlankHoldEvent.Kind.START), r.kinds()); assertEquals(0L, r.clock.firstHoldAt)
        r.feed(plank(), 10); assertEquals(4200L, r.clock.heldMs)
        assertEquals(PlankHoldClock.START_CUE, PlankHoldClock.cueFor(r.events.first()))
    }

    @Test fun heldTimeNeverDecreasesAndBriefBreaksAreCredited() {
        val r = Run()
        r.feed(plank(), 10)                                         // 확정 뒤 2700 ms
        val before = r.clock.heldMs
        r.feed(plank(knee = 120f), 3)                               // 0.9 s 무릎 — 1 s 안에 회복
        assertEquals("보류 중에는 늘지 않는다", before, r.clock.heldMs)
        r.feed(plank())                                             // 최근 1 s 의 위반 비율 0.75 — 아직 회복이 아니다(2026-10-08, 한 프레임 회복은 확정을 리셋하지 않는다)
        assertEquals(before, r.clock.heldMs); assertEquals(PlankHoldClock.Phase.HOLD, r.clock.phase)
        r.feed(plank())                                             // 비율 0.5 — 회복, 보류분(무릎 3칸 + 정상 2칸) 적립
        assertEquals("회복하면 보류분까지 적립", before + 1500L, r.clock.heldMs)
        assertEquals(PlankHoldClock.Phase.HOLD, r.clock.phase); assertTrue(r.clock.stopMs.isEmpty())
        r.feed(plank(knee = 120f), 6); r.feed(plank(), 8); r.feed(null, 5); r.feed(plank(), 8)
        assertTrue("단조 증가", r.held.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun flickerAroundTheHipThresholdStopsAndIsNotCredited() {
        // 폰 플랭크 2026-10-07: 골반을 바닥에 댄 값(0.086~0.107)이 옛 문턱 0.10 안팎을 오가 7.5 s 가 유지로 적립됐다 — 문턱 0.15 + 최근 1 s 의 70 % 규칙
        assertEquals(PlankHoldClock.HIPS_LOW, PlankHoldClock.stopReason(plank(hipFloor = 0.107f)))
        assertNull(PlankHoldClock.stopReason(plank(hipFloor = 0.17f)))
        assertEquals("이력: 골반으로 멈춘 뒤에는 문턱 + 0.05 를 넘어야 정상", PlankHoldClock.HIPS_LOW, PlankHoldClock.stopReason(plank(hipFloor = 0.17f), PlankHoldClock.HIP_FLOOR_MIN + PlankHoldClock.HIP_FLOOR_HYSTERESIS))
        val r = Run()
        r.feed(plank(), 10)                                         // HOLD, 2700 까지
        val held = r.clock.heldMs
        // 위반 3·정상 1 이 되풀이(비율 0.75) — 옛 규칙은 정상 한 칸마다 보류를 적립하고 리셋했다
        repeat(3) { r.feed(plank(hipFloor = 0.12f), 3); r.feed(plank(hipFloor = 0.16f)) }
        assertEquals(PlankHoldClock.Phase.STOP, r.clock.phase)
        assertEquals("흔들린 구간은 한 칸도 적립하지 않는다", held, r.clock.heldMs)
        assertEquals(HoldStop(2700L, PlankHoldClock.HIPS_LOW), r.clock.firstStop)
        assertEquals("보류 시간은 전부 멈춤(정상 칸도 확정 사유로)", r.clock.wallMs - held, r.clock.stopMs.values.sum())
        // 이력: 0.17(문턱 위, 재개선 0.20 아래)로는 재개하지 않고, 0.21 이면 1 s 뒤 재개
        r.feed(plank(hipFloor = 0.17f), 5); assertEquals(PlankHoldClock.Phase.STOP, r.clock.phase)
        r.feed(plank(hipFloor = 0.21f), 5); assertEquals(PlankHoldClock.Phase.HOLD, r.clock.phase)
    }

    @Test fun kneesAreAskedBeforeTheHipMisreadAndStopTimeIsDiscarded() {
        assertEquals("무릎을 대면 골반 오프셋이 오독된다 — 무릎을 먼저 묻는다", PlankHoldClock.KNEES_DOWN, PlankHoldClock.stopReason(plank(knee = 110f, hipFloor = 0.02f, hipOff = 0.4f)))
        assertEquals(PlankHoldClock.HIPS_LOW, PlankHoldClock.stopReason(plank(hipFloor = 0.05f, hipOff = 0.4f)))
        assertEquals(PlankHoldClock.PIKE, PlankHoldClock.stopReason(plank(hipOff = 0.3f)))
        assertNull("측면이 아니면 솟음을 묻지 않는다", PlankHoldClock.stopReason(plank(hipOff = 0.3f, yaw = 0.3f)))
        assertNull(PlankHoldClock.stopReason(plank(hipOff = 0.3f, yaw = null)))
        assertEquals(PlankHoldClock.OUT_OF_VIEW, PlankHoldClock.stopReason(plank(chain = 0f, knee = 110f)))
        assertEquals(PlankHoldClock.NOT_PRONE, PlankHoldClock.stopReason(plank(axis = 70f, knee = 110f)))
        assertNull("축각도 화면 수평도도 없으면 묻지 않는다", PlankHoldClock.stopReason(plank(axis = null)))
        // up 미확인 — 화면 수평도로 묻는다(서서 팔짱을 끼면 플랭크 시간이 아니다)
        assertEquals(PlankHoldClock.NOT_PRONE, PlankHoldClock.stopReason(plank(axis = null) + (FloorChain.FLAT_SCREEN to 0.3f)))
        assertNull(PlankHoldClock.stopReason(plank(axis = null) + (FloorChain.FLAT_SCREEN to 0.95f)))
        assertNull("축각이 있으면 축각이 정본", PlankHoldClock.stopReason(plank() + (FloorChain.FLAT_SCREEN to 0.3f)))
        assertNull("팔 편 플랭크도 센다 — 지지 방식은 게이트가 아니다", PlankHoldClock.stopReason(plank() + (FloorChain.ELBOW to 170f)))

        val r = Run()
        r.feed(plank(), 10)                                         // 0~2700, 확정 1200
        val held = r.clock.heldMs
        r.feed(plank(knee = 110f), 4)                               // 3000·3300·3600·3900 — 2700 부터 1 s 뒤(3900) 멈춤 확정
        assertEquals(PlankHoldClock.Phase.STOP, r.clock.phase); assertEquals(held, r.clock.heldMs)
        assertEquals(HoldStop(2700L, PlankHoldClock.KNEES_DOWN), r.clock.firstStop)
        assertEquals(PlankHoldEvent(PlankHoldEvent.Kind.STOP, 2700L, PlankHoldClock.KNEES_DOWN), r.events.last())
        assertTrue(PlankHoldClock.cueFor(r.events.last())!!.startsWith("무릎이 바닥에 닿아"))
        assertEquals(PlankHoldClock.KNEES_DOWN, r.clock.reason)
    }

    @Test fun stopTimeIsKeptPerReasonAndResumeNeedsOneSecond() {
        val r = Run()
        r.feed(plank(), 10)                                         // HOLD (2700 까지)
        r.feed(plank(knee = 110f), 10)                              // 3000~5700 무릎
        r.feed(plank(chain = 0f), 7)                                // 6000~7800 화면 밖
        r.feed(plank(), 4)                                          // 8100~9000 — 아직 재개 전
        assertEquals(PlankHoldClock.Phase.STOP, r.clock.phase)
        r.feed(plank())                                             // 9300 — 8100 부터 1.2 s, 재개 확정(소급 1200)
        assertEquals(PlankHoldClock.Phase.HOLD, r.clock.phase)
        assertEquals(PlankHoldEvent.Kind.RESUME, r.events.last().kind); assertNull(PlankHoldClock.cueFor(r.events.last()))
        assertEquals(2700L + 1200L, r.clock.heldMs)
        assertEquals(3000L, r.clock.stopMs.getValue(PlankHoldClock.KNEES_DOWN))   // 2700→5700
        assertEquals(2400L, r.clock.stopMs.getValue(PlankHoldClock.OUT_OF_VIEW))  // 5700→8100(첫 재개 프레임까지)
        val segs = r.clock.segments.map { it.state }
        assertEquals("첫 프레임부터 버텨 대기 구간은 길이 0 — 남기지 않는다", listOf("hold", PlankHoldClock.KNEES_DOWN, PlankHoldClock.OUT_OF_VIEW, "hold"), segs)
        assertEquals(r.clock.wallMs, r.clock.segments.last().t1 - r.clock.segments.first().t0)
    }

    @Test fun observationGapsCountAsOutOfViewAndAreNotCredited() {
        val r = Run()
        r.feed(plank(), 10)
        val held = r.clock.heldMs
        r.t += 1_700                                                // 2 s 동안 프레임 없음(일시정지·검출 끊김)
        r.feed(plank())
        assertEquals(PlankHoldClock.Phase.STOP, r.clock.phase); assertEquals(held, r.clock.heldMs)
        assertEquals(2000L, r.clock.stopMs.getValue(PlankHoldClock.OUT_OF_VIEW))
        // 750 ms 이하의 틈은 HOLD 안에서 그대로 적립(칸 간격은 750 으로 자른다)
        val s = Run(); s.feed(plank(), 10); val h = s.clock.heldMs; s.t += 400; s.feed(plank())
        assertEquals(h + 700L, s.clock.heldMs)
    }

    @Test fun notProneIsSilentThenRemindsOnceAfterFiveSeconds() {
        val r = Run()
        r.feed(plank(), 10)
        r.feed(plank(axis = 80f), 5)                                // 일어섬 — 멈춤 확정
        val stop = r.events.last()
        assertEquals(PlankHoldClock.NOT_PRONE, stop.reason); assertNull("바로 말하지 않는다", PlankHoldClock.cueFor(stop))
        r.feed(plank(axis = 80f), 20)
        assertEquals(1, r.events.count { it.kind == PlankHoldEvent.Kind.REMIND })
        assertEquals(PlankHoldClock.NOT_PRONE_REMIND_CUE, PlankHoldClock.cueFor(r.events.first { it.kind == PlankHoldEvent.Kind.REMIND }))
    }

    @Test fun clockFallbackOnlyWhenTheBodyWasMostlyOutOfView() {
        // 촬영 실패 — 20 s 동안 한 번도 확인 못 했고 그동안 대부분 화면 밖(화면 밖 12 s + 무릎 8.7 s)
        val r = Run(); r.clock.start(0L)
        r.feed(null, 40); r.feed(plank(knee = 100f), 30)
        assertEquals(PlankHoldClock.SOURCE_CLOCK, r.clock.source)
        assertEquals(1, r.events.count { it.kind == PlankHoldEvent.Kind.FALLBACK })
        assertEquals(PlankHoldClock.FALLBACK_CUE, PlankHoldClock.cueFor(r.events.single { it.kind == PlankHoldEvent.Kind.FALLBACK }))
        assertTrue("처음 버티기 전 시간은 멈춤 시간이 아니다", r.clock.stopMs.isEmpty())
        assertTrue("대기 시간은 따로 남는다", r.clock.waitMs.getValue(PlankHoldClock.OUT_OF_VIEW) >= 11_700L)
        r.feed(plank(), 10); assertTrue("폴백 뒤에도 카메라 시간은 로그용으로 잰다", r.clock.heldMs > 0); assertEquals(PlankHoldClock.SOURCE_CLOCK, r.clock.source)
        // 사람이 아예 안 잡힘(폰이 벽) — 20 s 에 폴백
        val gone = Run(); gone.clock.start(0L); gone.feed(null, 70)
        assertEquals(PlankHoldEvent(PlankHoldEvent.Kind.FALLBACK, 20_100L), gone.events.single { it.kind == PlankHoldEvent.Kind.FALLBACK })
        // 20 s 안에 한 번이라도 확인하면 폴백하지 않는다
        val ok = Run(); ok.clock.start(0L); ok.feed(null, 50); ok.feed(plank(), 10); ok.feed(null, 60)
        assertEquals(PlankHoldClock.SOURCE_CAMERA, ok.clock.source)
    }

    @Test fun kneePlankSeenByTheCameraDoesNotFallBackAndSaysWhy() {
        // 리뷰 2026-10-07: 무릎을 댄 채(사슬은 다 보임) 45 s 세트 — 전에는 20 s 에 '카메라가 확인하지 못해' 로 시계로 넘어가 무릎 플랭크가 플랭크 시간이 됐다(Q3 우회)
        val r = Run(); r.clock.start(0L)
        r.feed(plank(knee = 110f), 150)
        assertEquals(PlankHoldClock.SOURCE_CAMERA, r.clock.source); assertEquals(0L, r.clock.heldMs)
        assertTrue(r.events.none { it.kind == PlankHoldEvent.Kind.FALLBACK })
        val hints = r.events.filter { it.kind == PlankHoldEvent.Kind.HINT }
        assertEquals("사유마다 세트에 한 번", listOf(PlankHoldEvent(PlankHoldEvent.Kind.HINT, 5_100L, PlankHoldClock.KNEES_DOWN)), hints)
        assertEquals("무릎을 펴면 시간을 재기 시작해요.", PlankHoldClock.cueFor(hints.single()))
        assertEquals("무릎은 대기 시간으로 남는다(멈춤이 아니다)", 44_700L, r.clock.waitMs.getValue(PlankHoldClock.KNEES_DOWN)); assertTrue(r.clock.stopMs.isEmpty())
        assertEquals(PlankHoldClock.KNEES_DOWN, r.clock.reason)
        // 사유가 바뀌면 그 사유도 한 번 — 다시 무릎이면 말하지 않는다
        r.feed(plank(hipOff = 0.3f), 20); r.feed(plank(knee = 110f), 20)
        assertEquals(listOf(PlankHoldClock.KNEES_DOWN, PlankHoldClock.PIKE), r.events.filter { it.kind == PlankHoldEvent.Kind.HINT }.map { it.reason })
        assertTrue(PlankHoldClock.waitCue(PlankHoldClock.PIKE)!!.startsWith("엉덩이를 내리면"))
        // 첫 HOLD 뒤에는 안내하지 않는다(멈춤 음성이 맡는다)
        r.feed(plank(), 10); r.feed(plank(hipOff = 0.3f, knee = 110f), 30)
        assertEquals(2, r.events.count { it.kind == PlankHoldEvent.Kind.HINT })
    }

    @Test fun fallbackWaitsForAHoldCandidateBeingConfirmed() {
        // 19.5 s 에 플랭크에 들어가면 20 s 칸에 폴백했다가 곧바로 '시간을 재기 시작해요' 가 따라왔다 — 확인 중인 후보는 확정이나 깨짐까지 기다린다
        val r = Run(); r.clock.start(0L)
        r.feed(null, 65); r.feed(plank(), 6)                         // 0~19.2 s 화면 밖, 19.5 s 부터 플랭크
        assertEquals(PlankHoldClock.SOURCE_CAMERA, r.clock.source)
        assertEquals("화면 밖 안내(5 s) 뒤 시작 — 폴백 없음", listOf(PlankHoldEvent.Kind.HINT, PlankHoldEvent.Kind.START), r.events.map { it.kind })
        assertEquals(19_500L, r.clock.firstHoldAt)
    }

    @Test fun snapshotCarriesStartEndAndWaitForTheReplay() {
        val r = Run(); r.t = 1_000; r.clock.start(400L)
        r.feed(null, 5); r.feed(plank(), 10)
        val s = r.clock.snapshot()
        assertEquals(400L, s.startAt); assertEquals(r.t - 300L, s.endAt)
        assertEquals(r.clock.waitMs, s.waitMs); assertTrue(s.waitMs.containsKey(PlankHoldClock.OUT_OF_VIEW))
    }

    @Test fun pauseIsOutsideTheClockAndDoesNotBecomeAStop() {
        // 앱 배선(2026-10-07): 일시정지 동안은 적립·멈춤·경과·폴백 어디에도 넣지 않는다 — 재개 뒤 첫 프레임의 공백이 화면 밖 멈춤(과 그 음성)이 되지 않게
        val r = Run(); r.clock.start(0L)
        r.feed(plank(), 10)                                         // 0~2700 HOLD
        val held = r.clock.heldMs
        r.clock.pause(2_800)
        assertTrue(r.clock.paused); assertTrue("멈춘 동안의 프레임은 무시", r.clock.onFrame(5_000, null).isEmpty())
        r.t = 12_800; r.clock.resume(12_800)                        // 10 s 일시정지
        r.feed(plank(), 5)                                          // 12 800(재개 시각과 같아 무시)~14 000
        assertEquals(PlankHoldClock.Phase.HOLD, r.clock.phase)
        assertFalse(r.events.any { it.kind == PlankHoldEvent.Kind.STOP }); assertTrue(r.clock.stopMs.isEmpty())
        assertEquals(held + 1200L, r.clock.heldMs)
        assertEquals("경과에서 일시정지를 뺀다", 4_000L, r.clock.wallMs)
        assertEquals(listOf("hold", PlankHoldClock.PAUSE_LABEL, "hold"), r.clock.segments.map { it.state })
        assertEquals(HoldSegment(2_800, 12_800, PlankHoldClock.PAUSE_LABEL), r.clock.segments[1])
        // 폴백 20 s 도 일시정지를 빼고 센다
        val f = Run(); f.clock.start(0L); f.feed(null, 33); f.clock.pause(10_000); f.t = 30_000; f.clock.resume(30_000); f.feed(null, 20)
        assertEquals(PlankHoldClock.SOURCE_CAMERA, f.clock.source)
        f.feed(null, 20); assertEquals(PlankHoldClock.SOURCE_CLOCK, f.clock.source)
    }

    @Test fun snapshotAndResetAndCueSource() {
        val r = Run(); r.feed(plank(), 10); r.feed(plank(knee = 100f), 5)
        val s = r.clock.snapshot()
        assertEquals(r.clock.heldMs, s.heldMs); assertEquals(PlankHoldClock.Phase.STOP, s.phase); assertEquals(PlankHoldClock.SOURCE_CAMERA, s.source)
        assertEquals((r.clock as IdentityCueSource).cueFor(PlankHoldClock.HIPS_LOW), PlankHoldClock.cueFor(PlankHoldClock.HIPS_LOW))
        assertNull(PlankHoldClock.cueFor(PlankHoldClock.NOT_PRONE))
        r.clock.reset()
        assertEquals(0L, r.clock.heldMs); assertEquals(PlankHoldClock.Phase.WAIT, r.clock.phase); assertTrue(r.clock.segments.isEmpty()); assertNull(r.clock.firstStop)
    }
}
