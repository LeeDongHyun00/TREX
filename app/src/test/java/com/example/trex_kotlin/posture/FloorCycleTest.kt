package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/**
 * 바닥 반복 계열 v1(§99, 설계 §4.2·§4.5·§4.6) — 합성 `fc_*` 시계열로 기준·사이클·판별·유보·안내의 **계약**을 잠근다. 프레임 간격 300 ms(판정 격자).
 * 크런치: 현 들림 `fc_trunk_lift`, 귀 들림 = 10° + 현 들림(+ 목만 당김 [neck]). 레그 레이즈: 허벅지 `fc_thigh`, 상체 `fc_torso_elev`.
 * 재생기(replay-jvm)도 이 파일을 컴파일한다 — 엔진 파일 밖은 쓰지 않는다.
 */
class FloorCycleTest {
    private fun crunch(lift: Float, neck: Float = 0f, axis: Float = lift, yaw: Float? = 0.05f): Map<String, Float> = buildMap {
        put(FloorChain.TRUNK_LIFT, lift); put(FloorChain.EAR_LIFT, 10f + lift + neck); put(FloorChain.AXIS_H, axis); put(FloorChain.TORSO_ELEV, lift)
        put(FloorChain.CHAIN, 1f); yaw?.let { put(FloorChain.YAW, it) }
    }
    private fun leg(thigh: Float, torso: Float = 0f, knee: Float = 175f, gap: Float? = null, yaw: Float? = 0.05f, legAngle: Float = thigh - 2f): Map<String, Float> = buildMap {
        put(FloorChain.THIGH, thigh); put(FloorChain.LEG, legAngle); put(FloorChain.AXIS_H, kotlin.math.abs(torso)); put(FloorChain.TORSO_ELEV, torso)
        put(FloorChain.KNEE, knee); put(FloorChain.HEAD_LIFT, 22f); put(FloorChain.CHAIN, 1f)
        yaw?.let { put(FloorChain.YAW, it) }; gap?.let { put(FloorChain.KNEE_GAP, it) }
    }

    /** [viaCounter] = 켠 사유를 세션 카운터 구성(`RepCounter.floorEnabled`, 재생기 진단과 같은 길)으로 넘긴다. 아니면 [enabled] 가 있을 때 추적기를 직접 돌린다. */
    private class Run(ex: String, enabled: Set<String>? = null, viaCounter: Boolean = false) {
        val counter = RepCounter.forSession(ex, floor = true)!!.let { c ->
            if (enabled != null && viaCounter) RepCounter(c.signal, maxGapMs = 1500L, completeOnReturn = true, floorEnabled = enabled) else c
        }
        val tracker: FloorCycleTracker = if (enabled == null || viaCounter) counter.floorTracker!! else FloorCycleTracker(counter.floorTracker!!.profile, enabled)
        private val direct = enabled != null && !viaCounter
        var t = 0L
        var retractions = 0
        fun feed(f: Map<String, Float>, n: Int = 1, stepMs: Long = 300L) {
            repeat(n) {
                if (direct) tracker.onFrame(t, f) else { counter.onFrameFeatures(t, f); if (counter.newlyRetracted) retractions++ }
                t += stepMs
            }
        }
        fun seq(vararg fs: Map<String, Float>) = fs.forEach { feed(it) }
        val reps get() = if (direct) tracker.completed.size else counter.reps
        val reasons get() = tracker.rejected.map { it.feature }
    }
    private fun Run.lie(n: Int = 4) = feed(crunch(0f), n)
    private fun Run.crunchRep(peak: Float = 18f, bottom: Float = 0f, neck: Float = 0f, yaw: Float? = 0.05f) =
        seq(crunch(bottom + (peak - bottom) * .33f), crunch(bottom + (peak - bottom) * .66f), crunch(peak, yaw = yaw), crunch(bottom + (peak - bottom) * .66f),
            crunch(bottom + (peak - bottom) * .33f), crunch(bottom, neck = neck), crunch(bottom, neck = neck))
    private fun Run.legRep(peak: Float = 75f, torso: Float = 0f, knee: Float = 175f, gap: Float? = null, yaw: Float? = 0.05f) =
        seq(leg(peak * .2f), leg(peak * .47f, torso * .5f), leg(peak * .8f, torso, knee, gap), leg(peak, torso, knee, gap, yaw), leg(peak * .8f, torso, knee, gap),
            leg(peak * .47f, torso * .5f), leg(peak * .2f), leg(0f), leg(0f))

    @Test fun sessionSignalsAreTheFloorFamily() {
        val cr = RepCounter.forSession("크런치", floor = true)!!
        assertEquals(FloorChain.TRUNK_LIFT, cr.signal.feature); assertNotNull(cr.floorTracker); assertNull(cr.legTracker)
        assertEquals("head_ground", cr.signal.comparisonSignal().feature)   // TRACK 비교는 기록의 단위를 유지
        assertEquals(FloorCycleTracker.MAX_GAP_MS, cr.effectiveMaxGapMs); assertEquals(0L, cr.effectiveRefractoryMs); assertTrue(cr.effectiveCompleteOnReturn)
        assertNull("세션에서 ROM 을 붙이지 않는다", cr.signal.romThreshold)
        // 규칙 rep 설정이 와도 옛 신호 단위의 ROM 을 새 신호에 붙이지 않는다
        assertNull(RepCounter.forSession("크런치", "max", 0.2953f, floor = true)!!.signal.romThreshold)
        val lr = RepCounter.forSession("라잉 레그 레이즈", floor = true)!!
        assertEquals(FloorChain.THIGH, lr.signal.feature); assertEquals(FloorProfile.LEG_RAISE, lr.floorTracker!!.profile)
        assertEquals("hip_ang", lr.signal.comparisonSignal().feature)
        assertSame(lr.floorTracker, lr.cueSource)
        assertNull(RepCounter.forSession("플랭크", floor = true))
    }

    @Test fun lyingStartCountsCrunchesWithoutStillness() {
        val r = Run("크런치")
        r.feed(crunch(0f)); assertFalse(r.tracker.hasBase)
        r.feed(crunch(0f)); assertTrue("누운 영역 2프레임이면 기준(정지 요구 없음)", r.tracker.hasBase); assertEquals(0f, r.tracker.lying!!.getValue(FloorChain.TRUNK_LIFT), 0f)
        r.crunchRep(); assertFalse(r.counter.midCycle); r.crunchRep(); r.crunchRep()
        assertEquals(3, r.reps); assertTrue(r.reasons.isEmpty()); assertEquals(3, r.tracker.repDetail.size)
        r.lie(10); assertEquals("쉬는 동안 다시 세지 않는다", 3, r.reps)
    }

    @Test fun sittingStartCountsFromTheFirstRepAfterLyingDown() {
        val r = Run("크런치")
        r.feed(crunch(80f), 10)                                    // 앉아서 시작(3 s) — 누운 영역이 아니라 기준이 없다
        assertFalse(r.tracker.hasBase)
        r.seq(crunch(60f), crunch(40f), crunch(20f), crunch(0f), crunch(0f))   // 눕는 동작은 회가 아니다
        assertTrue(r.tracker.hasBase); assertEquals(0, r.reps)
        r.crunchRep(); assertEquals("누운 뒤 첫 회부터", 1, r.reps)
        r.crunchRep(); r.crunchRep(); assertEquals(3, r.reps)
        assertTrue(r.reasons.isEmpty())
    }

    @Test fun tensionKeptBetweenRepsStillCloses() {
        val r = Run("크런치"); r.lie()
        r.crunchRep(peak = 18f)                                    // 첫 회는 바닥에서
        repeat(4) { r.seq(crunch(10f), crunch(16f), crunch(18f), crunch(12f), crunch(7f), crunch(4f), crunch(4f)) }   // 회 사이 바닥이 기준 + 4°(완전히 안 내려옴)
        assertEquals(5, r.reps)
        // 기준은 복귀 체류 중앙값을 따라 한 번에 +3° 안으로만 올라간다(긴장 유지 높이 근처에 머문다) — 출발 여유(기준 + 8°)가 회 사이 바닥(4°)을 늘 덮는다
        assertTrue("base=${r.tracker.baseValue}", r.tracker.baseValue!! in 0f..(4f + FloorProfile.CRUNCH.rebaseUp))
    }

    @Test fun restlessContinuousMotionLikeMmFitW06W14IsCounted() {
        // 쉬지 않는 연속 동작 — 세트 안 현각 최소 18°(w06·w14 실측 17~21°), 정지 창이 없다. 정지·몸 내재 누움 기준이었다면 0회
        val r = Run("크런치")
        val cycle = listOf(18f, 28f, 40f, 55f, 60f, 50f, 38f, 26f)
        repeat(8) { for (v in cycle) r.feed(crunch(v)) }
        r.feed(crunch(18f), 2)
        assertTrue("reps=${r.reps}", r.reps >= 6)
        assertTrue(r.reasons.isEmpty()); assertTrue(r.tracker.discarded.isEmpty())
    }

    @Test fun gettingUpAtSetEndIsSilent() {
        val r = Run("크런치"); r.lie(); r.crunchRep(); r.crunchRep()
        val rejectedBefore = r.counter.rejectedReps.size
        r.seq(crunch(10f), crunch(30f), crunch(60f)); r.feed(crunch(80f), 8)   // 일어나 앉아 머무름
        assertEquals(2, r.reps); assertEquals("틱도 이유도 없다", rejectedBefore, r.counter.rejectedReps.size)
        assertEquals(listOf("exit"), r.tracker.discarded.map { it.reason }); assertFalse(r.tracker.hasBase)
        assertFalse(r.counter.midCycle)
        // 다시 누우면 이어서 센다
        r.seq(crunch(40f), crunch(20f), crunch(0f), crunch(0f), crunch(0f)); r.crunchRep(); assertEquals(3, r.reps)
    }

    @Test fun gapLongerThan750msDropsOnlyTheCandidate() {
        val r = Run("크런치"); r.lie()
        r.seq(crunch(6f), crunch(12f), crunch(18f)); assertTrue(r.counter.midCycle)
        r.t += 900                                                  // 0.9 s 공백(가림)
        r.seq(crunch(12f), crunch(6f), crunch(0f), crunch(0f))
        assertEquals(0, r.reps); assertTrue("기준은 지킨다", r.tracker.hasBase)
        r.crunchRep(); assertEquals(1, r.reps)
        // 750 ms 이하의 틈은 이어서 센다
        r.seq(crunch(6f), crunch(12f)); r.t += 450; r.seq(crunch(18f), crunch(12f), crunch(6f), crunch(0f), crunch(0f))
        assertEquals(2, r.reps)
    }

    @Test fun neckOnlyIsOnByDefaultAndNeverDeletesARep() {
        // 사용자 결정 Q6(4.5° 로 시작) — 엔진 재생 4.5 %(13/286)는 '세지 않은 동작에 이유를 말한' 비율이다. 이 사유는 주 신호가 출발하지 않은 동작에만 나서 어떤 회도
        // 지우지 않으므로 회를 지우는 판별의 2 % 입장선 대상이 아니다(리뷰 2026-10-07 — 끄면 그 동작이 틱도 이유도 없이 사라진다)
        assertEquals(setOf("sit_up", "neck_only"), FloorProfile.CRUNCH.defaultEnabled)
        assertEquals(setOf("trunk_up", "shallow"), FloorProfile.LEG_RAISE.defaultEnabled)
        val r = Run("크런치"); r.lie(); r.crunchRep()
        r.seq(crunch(0f, neck = 8f), crunch(1f, neck = 13f), crunch(1f, neck = 13f), crunch(0f, neck = 6f), crunch(0f), crunch(0f))
        assertEquals("목만 당긴 동작은 주 신호가 출발하지 않아 원래 세지 않는다", 1, r.reps)
        assertEquals("기본으로 이유를 말한다(낮은 틱)", listOf("neck_only"), r.counter.rejectedReps.map { it.feature })
        // 꺼도 횟수는 같다 — 회를 지우지 않는 사유
        val off = Run("크런치", enabled = setOf("sit_up"), viaCounter = true); off.lie(); off.crunchRep()
        off.seq(crunch(0f, neck = 8f), crunch(1f, neck = 13f), crunch(1f, neck = 13f), crunch(0f, neck = 6f), crunch(0f), crunch(0f))
        assertEquals(r.reps, off.reps); assertTrue(off.counter.rejectedReps.isEmpty())
    }

    @Test fun neckOnlyPullIsRejectedWithItsReasonWhenEnabled() {
        val r = Run("크런치", enabled = setOf("sit_up", "neck_only"), viaCounter = true); r.lie()
        r.crunchRep()                                               // 정상 회 — 귀가 함께 들려도 목만 당김이 아니다
        assertTrue(r.reasons.isEmpty())
        r.seq(crunch(0f, neck = 8f), crunch(1f, neck = 13f), crunch(1f, neck = 13f), crunch(0f, neck = 6f), crunch(0f), crunch(0f))
        assertEquals(1, r.reps); assertEquals(listOf("neck_only"), r.reasons)
        assertEquals("neck_only", r.counter.rejectedReps.last().feature)
        assertTrue(r.tracker.cueFor("neck_only")!!.startsWith("목만 당기면 세지 않아요"))
        assertEquals(r.tracker.cueFor("neck_only"), r.counter.cueSource!!.cueFor("neck_only"))
        // 측면이 아니면(정점 fc_yaw > 0.15) 판별을 유보 — 목만 당김도 말하지 않는다(세지도 않는다)
        r.seq(crunch(0f, neck = 8f, yaw = 0.3f), crunch(1f, neck = 13f, yaw = 0.3f), crunch(1f, neck = 13f, yaw = 0.3f), crunch(0f, neck = 6f), crunch(0f), crunch(0f))
        assertEquals(1, r.reasons.size); assertEquals(1, r.reps)
    }

    @Test fun fullSitUpIsNotACrunch() {
        val r = Run("크런치"); r.lie(); r.crunchRep()
        r.seq(crunch(10f), crunch(30f), crunch(55f), crunch(85f), crunch(55f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals(1, r.reps); assertEquals(listOf("sit_up"), r.reasons)
        assertEquals(85f, r.tracker.rejectedDetail.last().trunkPeak, 0f)
        assertTrue(FloorCycleTracker.cueFor(FloorProfile.CRUNCH, "sit_up")!!.startsWith("끝까지 일어나면"))
        // 정점이 80° 이하면 센다(sit_up 띠 80°, Q8)
        r.seq(crunch(10f), crunch(30f), crunch(48f), crunch(75f), crunch(48f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals(2, r.reps)
    }

    @Test fun shallowCrunchIsCountedByDefaultAndRejectedOnlyWhenEnabled() {
        val off = Run("크런치"); off.lie(); off.crunchRep(peak = 6f)
        assertEquals("shallow 은 기본 끔(Q7)", 1, off.reps)
        val on = Run("크런치", enabled = setOf("sit_up", "neck_only", "shallow")); on.lie(); on.crunchRep(peak = 6f)
        assertEquals(0, on.reps); assertEquals(listOf("shallow"), on.reasons)
    }

    @Test fun disabledReasonsPass() {
        val r = Run("크런치", enabled = emptySet()); r.lie()
        r.seq(crunch(10f), crunch(30f), crunch(55f), crunch(85f), crunch(55f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals(1, r.reps); assertTrue(r.reasons.isEmpty())
        r.seq(crunch(0f, neck = 8f), crunch(1f, neck = 13f), crunch(1f, neck = 13f), crunch(0f, neck = 6f), crunch(0f), crunch(0f))
        assertTrue(r.reasons.isEmpty())
    }

    @Test fun notSideViewAbstainsIdentityAndCounts() {
        val r = Run("크런치"); r.lie()
        r.seq(crunch(10f), crunch(30f), crunch(55f), crunch(85f, yaw = 0.3f), crunch(55f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals("판별 유보 = 센다(원칙 #7 '유보는 통과')", 1, r.reps); assertTrue(r.reasons.isEmpty())
        assertEquals(1, r.tracker.identityAbstain.size); assertTrue(r.tracker.repDetail.single().identityAbstain)
        // 측면도가 아예 없어도(먼 쪽 가림) 유보
        r.crunchRep(yaw = null); assertEquals(2, r.reps); assertEquals(2, r.tracker.identityAbstain.size)
    }

    @Test fun endOnViewWithCollapsedProjectionAbstainsEvenWhenYawLooksSide() {
        // 발 쪽에서 찍은 끝 방향 촬영(AIHub E): fc_yaw 는 측면처럼 작지만 어깨–발목 ÷ 어깨폭이 8 미만 — 투영이 무너져 현 들림이 85° 로 읽혀도 sit_up 으로 지우지 않는다
        val r = Run("크런치"); r.lie()
        val endOn = crunch(85f) + (FloorChain.RATIO to 5f)
        r.seq(crunch(10f), crunch(30f), crunch(55f), endOn, crunch(55f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals(1, r.reps); assertTrue(r.reasons.isEmpty()); assertEquals(1, r.tracker.identityAbstain.size)
        // 측면(ratio ≥ 8)이면 종전대로 sit_up
        val side = crunch(85f) + (FloorChain.RATIO to 20f)
        r.seq(crunch(10f), crunch(30f), crunch(55f), side, crunch(55f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals(1, r.reps); assertEquals(listOf("sit_up"), r.reasons)
    }

    @Test fun legRaiseCountsThighCyclesAndOrdersReasons() {
        val r = Run("라잉 레그 레이즈")
        r.feed(leg(0f), 3); assertTrue(r.tracker.hasBase)
        r.legRep(); r.legRep(); assertEquals(2, r.reps)
        r.legRep(peak = 28f); assertEquals(listOf("shallow"), r.reasons)                       // 진폭 < 30°
        r.legRep(torso = 35f); assertEquals("trunk_up", r.reasons.last())                       // 상체를 듦
        r.legRep(peak = 28f, torso = 35f); assertEquals("상체 들기가 얕음보다 먼저", "trunk_up", r.reasons.last())
        r.legRep(knee = 90f); assertEquals("knee_bent 는 기본 끔(Q10)", 3, r.reps)
        r.legRep(gap = 0.6f); assertEquals("one_leg 는 기본 끔", 4, r.reps)
        assertTrue(FloorCycleTracker.cueFor(FloorProfile.LEG_RAISE, "trunk_up")!!.startsWith("상체를 들면"))
        val k = Run("라잉 레그 레이즈", enabled = setOf("trunk_up", "knee_bent", "shallow")); k.feed(leg(0f), 3)
        k.legRep(peak = 28f, knee = 90f); assertEquals("무릎 굽힘이 얕음보다 먼저", listOf("knee_bent"), k.reasons)
    }

    @Test fun legRaiseFeetTouchJudgesOnlyTheTroughBetweenReps() {
        val r = Run("라잉 레그 레이즈", enabled = setOf("trunk_up", "shallow", "feet_touch")); r.feed(leg(0f), 3)
        r.legRep(); assertEquals("휴식에서 출발한 첫 회는 묻지 않는다", 1, r.reps)
        r.legRep(); assertEquals("회 사이에 발이 바닥(첫 누운 다리각 + 3° 안)", listOf("feet_touch"), r.reasons)
        // 이번 회도 바닥에서 출발(기각)하지만 바닥 위에서 멈추고, 다음 회는 그 자리에서 올리면 센다
        val hover = arrayOf(leg(35f), leg(60f), leg(75f), leg(60f), leg(35f), leg(15f, legAngle = 12f), leg(12f, legAngle = 10f), leg(12f, legAngle = 10f))
        r.seq(*hover); assertEquals(listOf("feet_touch", "feet_touch"), r.reasons)
        r.seq(*hover); assertEquals(2, r.reps); assertEquals(2, r.reasons.size)
    }

    @Test fun firstRepIsTentativeForEightSeconds() {
        val r = Run("크런치"); r.lie(); r.crunchRep()
        assertEquals(1, r.reps)
        r.lie(30)                                                   // 9 s 동안 둘째 회가 없다 — 준비 동작이었다
        assertEquals(0, r.reps); assertEquals(1, r.retractions); assertEquals(1, r.counter.retractedReps.size)
        assertTrue(r.tracker.completed.isEmpty())
        r.crunchRep(); r.crunchRep(); assertEquals(2, r.reps)
        r.lie(30); assertEquals("둘째 회가 오면 확정", 2, r.reps)
    }

    @Test fun tentativeFirstRepIsArmedOncePerSet() {
        // 리뷰 2026-10-07: 거둔 뒤 다음 회가 다시 '첫 회' 가 되어 느린 세트가 회마다 앞 회를 거뒀다
        val r = Run("크런치"); r.lie(); r.crunchRep(); r.lie(30)
        assertEquals(0, r.reps); assertEquals(1, r.retractions)
        r.crunchRep(); r.lie(30)
        assertEquals("거둔 뒤의 회는 잠정이 아니다", 1, r.reps); assertEquals(1, r.retractions)
        r.crunchRep(); r.lie(40); r.crunchRep(); assertEquals(3, r.reps); assertEquals(1, r.counter.retractedReps.size)
    }

    @Test fun slowLegRaiseSetWithNineSecondRepsCountsEveryRep() {
        // 상승 3 s · 천천히 하강 5 s · 쉼 1 s = 9 s 간격(AIHub 클립당 약 1회 템포). 확인 창이 8 s 이던 때는 회마다 앞 회를 거둬 끝 수가 0~1 이었다
        assertEquals(12_000L, FloorProfile.LEG_RAISE.firstRepConfirmMs); assertEquals(8_000L, FloorProfile.CRUNCH.firstRepConfirmMs)
        val r = Run("라잉 레그 레이즈"); r.feed(leg(0f), 3)
        val up = (1..10).map { 75f * it / 10 }; val down = (16 downTo 0).map { 75f * it / 17 }
        repeat(10) { for (v in up + down) r.feed(leg(v)); r.feed(leg(0f), 3) }
        r.feed(leg(0f), 50)                                         // 세트 끝 15 s 쉼 — 확정된 회는 거두지 않는다
        assertEquals(10, r.reps); assertEquals(0, r.retractions); assertTrue(r.tracker.retracted.isEmpty())
        assertTrue(r.reasons.isEmpty())
    }

    @Test fun rejectedMotionAfterTheFirstRepConfirmsIt() {
        // 정상 1회 + 끝까지 일어남 3회 — 세지 않은 동작도 세트가 시작됐다는 증거다. 전에는 정상 회가 지워졌다
        val r = Run("크런치"); r.lie(); r.crunchRep()
        repeat(3) { r.seq(crunch(10f), crunch(30f), crunch(55f), crunch(85f), crunch(55f), crunch(30f), crunch(10f), crunch(0f), crunch(0f)) }
        r.lie(40)
        assertEquals(1, r.reps); assertEquals(0, r.retractions); assertEquals(listOf("sit_up", "sit_up", "sit_up"), r.reasons)
    }

    @Test fun pauseConfirmsTheTentativeFirstRep() {
        // 크런치 1회(화면 '1') → 폰 위치를 고치려 일시정지 → 10 s 뒤 재개. 전에는 재개 첫 프레임에서 거뒀다(쉬는 동안 거두지 않는다 — RepCounter.resetCycle 과 같은 결정)
        val r = Run("크런치"); r.lie(); r.crunchRep(); assertEquals(1, r.reps)
        r.counter.resetCycle()
        r.t += 10_000
        r.lie(30)
        assertEquals(1, r.reps); assertEquals(0, r.retractions)
    }

    @Test fun slowTopSitUpIsRejectedWithItsReasonEvenWhenTheExitFiresFirst() {
        // 위에서 1.8 s 머무는 윗몸일으키기 — 현 들림 > 50° 1.5 s 출구가 판별보다 먼저 걸린다. 곧 누우면 sit_up 으로 말한다(전에는 소리 없이 버리고 기준을 잃었다)
        val r = Run("크런치"); r.lie(); r.crunchRep(); r.crunchRep()
        r.seq(crunch(10f), crunch(30f), crunch(60f)); r.feed(crunch(85f), 6)
        assertEquals(listOf("exit"), r.tracker.discarded.map { it.reason }); assertTrue(r.reasons.isEmpty())
        r.seq(crunch(60f), crunch(30f), crunch(10f), crunch(0f), crunch(0f))
        assertEquals(listOf("sit_up"), r.reasons); assertEquals(2, r.reps)
        assertEquals(85f, r.tracker.rejectedDetail.last().trunkPeak, 0f)
        r.crunchRep(); assertEquals("다시 누우면 이어서 센다", 3, r.reps)
        // 세트 끝에 일어나 앉아 머물면(5 s 안에 안 돌아옴) 소리 없이 — 그리고 기준을 못 잡은 채 5 s 지나면 누운 기준 안내를 한 번 더 한다
        r.seq(crunch(10f), crunch(30f), crunch(60f)); r.feed(crunch(85f), 30)
        assertEquals(1, r.reasons.size)
        assertEquals(FloorCycleTracker.noBaseCue(FloorProfile.CRUNCH), r.tracker.guideCue(r.t))
        assertNull("한 번만", r.tracker.guideCue(r.t + 300))
    }

    @Test fun legRaiseWithoutGravityStillExitsWhenSittingUp() {
        // up 이 없으면(fc_axis_h 없음) 관측 층이 상체 기울기를 화면 위 기준으로 낸다 — 누운 기준 대비로 보면 출구·상체 들기가 산다(리뷰 2026-10-07: 전에는 꺼져 1회로 셌다)
        fun noUp(thigh: Float, torso: Float = 0f) = leg(thigh, torso) - FloorChain.AXIS_H
        val r = Run("라잉 레그 레이즈"); r.feed(noUp(0f), 3)
        repeat(2) { r.seq(*listOf(15f, 35f, 60f, 75f, 60f, 35f, 15f, 0f, 0f).map { noUp(it) }.toTypedArray()) }
        assertEquals(2, r.reps)
        // 일어나 앉아(상체 90°, 허벅지는 몸통 연장선 대비 90°) 3 s 머문 뒤 다시 눕는다
        r.seq(noUp(20f, 20f), noUp(50f, 50f), noUp(80f, 80f)); r.feed(noUp(90f, 90f), 10)
        r.seq(noUp(60f, 60f), noUp(30f, 30f), noUp(0f, 0f), noUp(0f, 0f))
        assertEquals("앉았다 눕기는 회가 아니다", 2, r.reps)
        assertTrue(r.tracker.discarded.any { it.reason == "exit" } || r.reasons.contains("trunk_up"))
    }

    @Test fun prepareAndLoggedLyingGiveTheSameReps() {
        // 리뷰 2026-10-07: 재생기의 restoreLying 은 출발 최소점을 기준(중앙값)으로 잡아 준비 프레임 최솟값을 쓰는 앱과 첫 회 출발이 한 칸 어긋났다
        val prep = listOf(0.2f, 1.4f, 0.6f, 1.0f, 0.8f).mapIndexed { k, v -> 1000L + k * 300L to crunch(v) }
        val slow = listOf(1f, 2f, 3f, 4f, 4.8f, 5.5f, 5.5f, 4f, 3f, 2f, 1f, 0.8f, 0.8f, 0.8f).map { crunch(it) }
        val app = Run("크런치"); app.counter.standingSeedFrom(prep, 2500L); assertTrue(app.tracker.hasBase)
        val lying = app.tracker.lying!!
        assertEquals(1f, lying.getValue(FloorCycleTracker.LYING_PREP), 0f); assertEquals(0.2f, lying.getValue(FloorCycleTracker.LYING_MIN), 1e-6f)
        val replay = Run("크런치"); replay.tracker.restoreLying(lying)
        app.t = 2500; replay.t = 2500
        slow.forEach { app.feed(it); replay.feed(it) }
        assertEquals(1, app.reps)
        assertEquals(app.tracker.repDetail, replay.tracker.repDetail); assertEquals(app.counter.repTimesMs.toList(), replay.counter.repTimesMs.toList())
        // 세트 중에 잡힌 기준(앉아서 시작)은 심지 않는다 — 재생기도 같은 프레임에서 스스로 잡아 출구 기록이 같다
        val sitStart = listOf(80f, 80f, 80f, 80f, 80f, 80f, 60f, 40f, 20f, 0f, 0f, 0f).map { crunch(it) } +
            listOf(6f, 12f, 18f, 12f, 6f, 0f, 0f).map { crunch(it) }
        val a2 = Run("크런치"); sitStart.forEach { a2.feed(it) }
        assertEquals(0f, a2.tracker.lying!!.getValue(FloorCycleTracker.LYING_PREP), 0f)
        val r2 = Run("크런치"); r2.tracker.restoreLying(a2.tracker.lying!!); assertFalse("세트 중 기준은 심지 않는다", r2.tracker.hasBase)
        sitStart.forEach { r2.feed(it) }
        assertEquals(a2.tracker.discarded, r2.tracker.discarded); assertEquals(a2.reps, r2.reps)
        assertEquals(a2.tracker.repDetail, r2.tracker.repDetail)
    }

    @Test fun lostSignalCueNamesTheMissingJoint() {
        // 리뷰 2026-10-07: 크런치 현 들림은 발목, 레그 레이즈 허벅지는 무릎이 있어야 계산되는데 늘 '어깨와 골반이 보이게' 라고 했다
        val c = Run("크런치"); c.lie()
        c.feed(mapOf(FloorChain.TORSO to 120f, FloorChain.CHAIN to 0f, FloorChain.TORSO_ELEV to 0f), 11)   // 어깨·골반은 보이고 발목만 잘림
        assertEquals("발목까지 보이게 해 주세요. 지금은 세지 못해요.", c.tracker.guideCue(c.t))
        val t = Run("크런치"); t.lie(); t.feed(mapOf("hip_ang" to 170f), 11)                                  // 몸통이 안 보임(관측 층 피처 없음)
        assertEquals(FloorCycleTracker.SIGNAL_LOST_CUE, t.tracker.guideCue(t.t))
        val l = Run("라잉 레그 레이즈"); l.feed(leg(0f), 3); l.feed(mapOf(FloorChain.TORSO to 120f, FloorChain.CHAIN to 0f), 11)
        assertEquals("무릎이 보이게 해 주세요. 지금은 세지 못해요.", l.tracker.guideCue(l.t))
        // 사람이 아예 안 잡혀 모르면 그 종목에 필요한 관절 전부
        val gone = Run("라잉 레그 레이즈"); gone.feed(leg(0f), 3)
        assertEquals(FloorCycleTracker.signalLostCue(FloorProfile.LEG_RAISE, null), gone.tracker.guideCue(gone.t + 3_100))
        assertTrue(FloorCycleTracker.signalLostCue(FloorProfile.CRUNCH, null).contains("발목"))
    }

    @Test fun prepareSeedsOnlyFromLyingCountdown() {
        val lying = (0 until 6).map { 1000L + it * 300L to crunch(0f) }
        val r = Run("크런치")
        assertNull(r.counter.standingSeedFrom(lying, 2500L)); assertTrue(r.tracker.hasBase)
        r.t = 2500; r.crunchRep(); assertEquals("준비 기준으로 첫 회부터", 1, r.reps)
        val sitting = (0 until 6).map { 1000L + it * 300L to crunch(80f) }
        val s = Run("크런치"); s.counter.standingSeedFrom(sitting, 2500L); assertFalse("앉아 있으면 심지 않는다", s.tracker.hasBase)
        val noUp = (0 until 6).map { 1000L + it * 300L to crunch(0f) - FloorChain.AXIS_H }
        val u = Run("크런치"); u.counter.standingSeedFrom(noUp, 2500L); assertFalse("축각이 없으면(up 미확인) 심지 않는다", u.tracker.hasBase)
        val q = Run("크런치"); q.tracker.restoreLying(mapOf(FloorChain.TRUNK_LIFT to 0f)); assertTrue(q.tracker.hasBase)
    }

    @Test fun unknownUpCountsWithoutTheLyingGate() {
        // 축각이 없으면 누운 영역 게이트 없이 — 앉은 프레임은 출구가 막고, 누우면 기준이 내려온다
        val r = Run("크런치")
        r.feed(crunch(80f) - FloorChain.AXIS_H, 8)
        r.seq(*listOf(40f, 20f, 0f, 0f, 0f).map { crunch(it) - FloorChain.AXIS_H }.toTypedArray())
        repeat(3) { r.seq(*listOf(6f, 12f, 18f, 12f, 6f, 0f, 0f).map { crunch(it) - FloorChain.AXIS_H }.toTypedArray()) }
        assertEquals(3, r.reps)
    }

    @Test fun annotateAddsTorsoTiltAgainstTheLyingBase() {
        val r = Run("라잉 레그 레이즈"); r.feed(leg(0f, torso = 2f), 3)
        assertEquals(10f, r.tracker.annotate(leg(0f, torso = 12f)).getValue(FloorChain.TORSO_TILT), 1e-4f)
        assertFalse(Run("라잉 레그 레이즈").tracker.annotate(leg(0f)).containsKey(FloorChain.TORSO_TILT))
    }

    @Test fun guideCuesForMissingBaseAndLostSignal() {
        val r = Run("크런치")
        r.feed(crunch(80f), 18)                                    // 앉은 채 5 s 넘게 — 한 번
        val first = r.tracker.guideCue(r.t)
        assertEquals(FloorCycleTracker.noBaseCue(FloorProfile.CRUNCH), first)
        assertNull("한 번만", r.tracker.guideCue(r.t + 300))
        // 사이클 신호가 3 s 끊기면 15 s 간격으로(사람이 안 잡혀 빠진 관절을 모르면 크런치에 필요한 관절 전부)
        r.lie(); val lost = r.t + 3_100
        val cue = FloorCycleTracker.signalLostCue(FloorProfile.CRUNCH, null)
        assertEquals(cue, r.tracker.guideCue(lost))
        assertNull(r.tracker.guideCue(lost + 5_000))
        assertEquals(cue, r.tracker.guideCue(lost + 15_000))
    }

    @Test fun legCycleTrackerIsAnIdentityCueSourceWithUnchangedLines() {
        for (p in LegProfile.entries) {
            val t = LegCycleTracker(p)
            for (reason in listOf("shallow", "torso_bent", "knee_straight", "no_crunch", "no_cross", "timeout"))
                assertEquals(LegCycleTracker.cueFor(p, reason), (t as IdentityCueSource).cueFor(reason))
        }
    }
}
