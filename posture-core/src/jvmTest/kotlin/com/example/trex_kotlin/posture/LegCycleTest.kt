// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/**
 * 한 다리 계열 v2(§97) — 합성 시계열로 상태·판별·단위·유보·파리티의 **계약**을 잠근다. 값의 모양은 2026-10-06 폰 세트의 실측(설계 §3)을 따른다.
 * 좌표: 사용자가 카메라를 본다(`leg_face_dx` > 0). 프레임 간격 300 ms(판정 격자). 재생기(replay-jvm)도 이 파일을 컴파일한다 — 엔진 파일 밖은 쓰지 않는다.
 */
class LegCycleTest {
    private fun frame(kneeL: Float = 162f, kneeR: Float = 164f, thighL: Float = 175f, thighR: Float = 175f,
                      cross: Float = 0.6f, hipshift: Float? = 0.5f, liftL: Float = 0f, liftR: Float = 0f, hipY: Float = 0.52f,
                      ekL: Float = 1.3f, ekR: Float = 1.3f, pitch: Float = 8f, roll: Float = -2f, relYaw: Float = 3f,
                      hikeL: Float = 0f, leanL: Float = 0f, ankleYL: Float = 0.87f, ankleYR: Float = 0.87f,
                      latFlexL: Float = 0f, kneeLatL: Float? = null, kneeLatR: Float? = null, hipHeight: Float? = null,
                      extra: Map<String, Float> = emptyMap()): Map<String, Float> = mapOf(
        "knee_L" to kneeL, "knee_R" to kneeR, "thigh_L" to thighL, "thigh_R" to thighR,
        LegGeometry.CROSS to cross, LegGeometry.HIP_Y to hipY, LegGeometry.TORSO2D to 0.24f, LegGeometry.FACE_DX to 0.65f,
        LegGeometry.lift(StepSide.LEFT) to liftL, LegGeometry.lift(StepSide.RIGHT) to liftR,
        LegGeometry.ankleY(StepSide.LEFT) to ankleYL, LegGeometry.ankleY(StepSide.RIGHT) to ankleYR,
        "elbow_knee_L" to ekL, "elbow_knee_R" to ekR, "hand_ear_L" to 0.12f, "hand_ear_R" to 0.12f,
        "knee_out_L" to 0.05f, "knee_out_R" to 0.05f, "knee_out2d_L" to 0.1f, "knee_out2d_R" to 0.1f,
        "torso_pitch" to pitch, "torso_roll" to roll, LegGeometry.REL_YAW to relYaw,
        "hip_hike_L" to hikeL, "hip_hike_R" to -hikeL, "head_lean_L" to leanL, "head_lean_R" to -leanL, "lat_flex_L" to latFlexL, "lat_flex_R" to -latFlexL,
        ViewEstimator.FEAT_COS to 1f, ViewEstimator.FEAT_SIN to 0f) + (if (hipshift != null) mapOf(LegGeometry.HIPSHIFT to hipshift) else emptyMap()) +
        (if (kneeLatL != null) mapOf("knee_lat_L" to kneeLatL) else emptyMap()) + (if (kneeLatR != null) mapOf("knee_lat_R" to kneeLatR) else emptyMap()) +
        (if (hipHeight != null) mapOf("hip_height_rel" to hipHeight) else emptyMap()) + extra

    // 실측 모양의 극점(설계 §3). 서 있는 기준 프레임의 몸통 기울기는 8° — 런지의 힙 힌지 22°(기준 대비 14) 는 정상, 니업·크런치는 세운 채
    private val sideLungeL = frame(kneeL = 85f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 22f)
    private val sideLungeR = frame(kneeR = 80f, kneeL = 148f, cross = 1.9f, hipshift = 0.16f, hipY = 0.62f, pitch = 20f)
    private val crossLungeR = frame(kneeL = 140f, kneeR = 105f, cross = -0.45f, hipY = 0.64f, ankleYR = 0.80f, ankleYL = 0.92f)   // 오른발이 뒤로 교차(더 위)
    private val crossLungeL = frame(kneeL = 100f, kneeR = 138f, cross = -0.5f, hipY = 0.64f, ankleYL = 0.80f, ankleYR = 0.92f)
    private val kneeUpL = frame(thighL = 60f, kneeL = 50f, liftL = 1.3f, liftR = -1.3f, hikeL = 0.09f)
    private val kneeUpR = frame(thighR = 72f, kneeR = 60f, liftR = 1.2f, liftL = -1.2f, hikeL = -0.05f)
    private val crunchL = frame(thighL = 55f, kneeL = 50f, liftL = 1.5f, liftR = -1.5f, ekL = 0.2f, roll = -12f, hikeL = 0.2f, latFlexL = 45f, kneeLatL = 0.6f)
    private val crunchR = frame(thighR = 50f, kneeR = 45f, liftR = 1.5f, liftL = -1.5f, ekR = 0.18f, roll = 14f, hikeL = -0.19f, latFlexL = -45f, kneeLatR = 0.6f)

    private class Run(ex: LegProfile) {
        val counter = RepCounter.forSession(ex.title, floor = false)!!
        val tracker = counter.legTracker!!
        var t = 0L
        val events = ArrayList<RepCycle>()
        fun feed(f: Map<String, Float>, n: Int = 1, stepMs: Long = 300L) { repeat(n) { counter.onFrameFeatures(t, f); events += counter.newlyPublished; t += stepMs } }
    }
    private fun Run.ready() = feed(frame(), 4)                 // 0·300·600·900 ms 정지 → 기준
    private fun Run.cycle(p: Map<String, Float>, back: Map<String, Float> = frame()) { feed(p, 3); feed(back, 3) }
    private fun reasons(r: Run) = r.counter.rejectedReps.map { it.feature }

    @Test fun standingBaseFromStillnessThenSideLungesCountPerLegWithSides() {
        val a = Run(LegProfile.SIDE)
        a.feed(frame(), 2); assertFalse(a.tracker.hasBase); assertNull(a.tracker.standing)
        a.ready(); assertTrue(a.tracker.hasBase); assertEquals(162f, a.tracker.standing!!.getValue("knee_L"), 1e-6f)
        a.cycle(sideLungeL); a.cycle(sideLungeR); a.cycle(sideLungeL)
        assertEquals(3, a.counter.reps); assertEquals(listOf(StepSide.LEFT, StepSide.RIGHT, StepSide.LEFT), a.events.map { it.side })
        assertEquals(2, a.tracker.left); assertEquals(1, a.tracker.right); assertTrue(a.counter.rejectedReps.isEmpty())
        a.feed(frame(), 10); assertEquals("쉬는 동안 다시 세지 않는다", 3, a.counter.reps); assertFalse(a.counter.midCycle)
    }

    @Test fun sideLungeIdentityRejectsShallowSquatAndNoShift() {
        val a = Run(LegProfile.SIDE); a.ready()
        a.cycle(frame(kneeL = 125f, kneeR = 160f, cross = 1.6f, hipshift = 0.78f, hipY = 0.58f))          // 얕게
        a.cycle(frame(kneeL = 90f, kneeR = 92f, cross = 0.7f, hipshift = 0.5f, hipY = 0.66f))             // 스쿼트
        a.cycle(frame(kneeL = 90f, kneeR = 150f, cross = 1.6f, hipshift = 0.45f, hipY = 0.6f))            // 골반이 안 옮겨감
        a.cycle(frame(kneeL = 90f, kneeR = 150f, cross = 1.6f, hipshift = null, hipY = 0.6f))             // 옆모습(발목 간격 없음)
        assertEquals(0, a.counter.reps)
        assertEquals(listOf("shallow", "squat_like", "squat_like", "no_shift", "no_direction"), reasons(a))   // 스쿼트는 두 무릎 각각 기각
    }

    @Test fun crossLungeCountsOnAnkleCrossingAndNamesTheBackFoot() {
        val a = Run(LegProfile.CROSS); a.ready()
        a.cycle(crossLungeR, back = frame(cross = 0.2f, hipY = 0.53f))     // 돌아온 자리는 좁아도 된다(실측 0.2)
        a.cycle(crossLungeL, back = frame(cross = 0.15f, hipY = 0.52f))
        assertEquals(2, a.counter.reps); assertEquals(listOf(StepSide.RIGHT, StepSide.LEFT), a.events.map { it.side })
        a.cycle(frame(kneeL = 120f, kneeR = 115f, cross = 0.4f, hipY = 0.64f))                           // 교차 없이 앉음
        a.cycle(frame(cross = -0.4f, hipY = 0.53f, ankleYR = 0.8f, ankleYL = 0.92f))                      // 교차만 하고 안 내려감
        assertEquals(2, a.counter.reps); assertEquals(listOf("no_descent"), reasons(a))
        assertEquals("교차하지 않은 앉기는 사이클조차 아니다", 1, a.counter.rejectedReps.size)
        a.cycle(frame(kneeL = 100f, kneeR = 98f, cross = -0.45f, hipY = 0.64f, ankleYL = 0.86f, ankleYR = 0.87f))   // 뒷발을 못 가름
        assertEquals(3, a.counter.reps); assertNull(a.events.last().side); assertEquals(1, a.tracker.unknown)
    }

    @Test fun lungesRejectBentTorsoAndShallowDepthByKneeOrHipHeight() {
        val a = Run(LegProfile.SIDE); a.ready()
        a.cycle(frame(kneeL = 85f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 45f))          // 바닥에서 숙임(기준 8 → 45)
        a.cycle(frame(kneeL = 85f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 31f))          // 허리를 굽힌 다음 진행(출발에 이미 +23)
        a.cycle(frame(kneeL = 85f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 22f, hipHeight = 0.86f))   // 무릎각은 깊은데 골반이 안 내려감
        a.cycle(frame(kneeL = 100f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 22f, hipHeight = 0.80f))  // 무릎 100°(2차 세트 '얕다')
        a.cycle(frame(kneeL = 85f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 22f, hipHeight = 0.76f, extra = mapOf("knee_fwd_foot_L" to 0.75f)))   // 무릎이 발끝을 넘음(§98b)
        a.cycle(frame(kneeL = 85f, kneeR = 150f, cross = 1.8f, hipshift = 0.78f, hipY = 0.62f, pitch = 22f, hipHeight = 0.76f, extra = mapOf("knee_fwd_foot_L" to 0.5f)))    // 제대로(발끝 뒤)
        assertEquals(1, a.counter.reps)
        assertEquals(listOf("torso_bent", "torso_bent", "shallow", "shallow", "knee_over_toe"), reasons(a))
        assertEquals("허리를 숙이면 세지 않아요. 가슴을 들고 상체를 세워 주세요.", LegCycleTracker.cueFor(LegProfile.SIDE, "torso_bent"))
        assertEquals("무릎이 발끝을 넘으면 세지 않아요. 엉덩이를 뒤로 보내 무릎을 발끝 뒤에 두세요.", LegCycleTracker.cueFor(LegProfile.SIDE, "knee_over_toe"))
        val c = Run(LegProfile.CROSS); c.ready()
        c.cycle(frame(kneeL = 140f, kneeR = 105f, cross = -0.45f, hipY = 0.64f, ankleYR = 0.80f, ankleYL = 0.92f, hipHeight = 0.85f), back = frame(cross = 0.2f))   // 골반이 안 내려감
        c.cycle(frame(kneeL = 140f, kneeR = 115f, cross = -0.45f, hipY = 0.64f, ankleYR = 0.80f, ankleYL = 0.92f), back = frame(cross = 0.2f))                      // 더 굽은 무릎 115°
        c.cycle(frame(kneeL = 140f, kneeR = 105f, cross = -0.45f, hipY = 0.64f, ankleYR = 0.80f, ankleYL = 0.92f, pitch = 42f), back = frame(cross = 0.2f))         // 숙임(+34)
        c.cycle(crossLungeR, back = frame(cross = 0.2f))
        assertEquals(1, c.counter.reps); assertEquals(listOf("shallow", "shallow", "torso_bent"), reasons(c))
        assertEquals("더 깊게 내려가야 세요.", LegCycleTracker.cueFor(LegProfile.CROSS, "shallow"))
    }

    @Test fun kneeUpCountsFullHeightOnlyAndEachLegSeparately() {
        val a = Run(LegProfile.KNEE_UP); a.ready()
        a.cycle(kneeUpL); a.cycle(kneeUpR); a.cycle(kneeUpL)
        a.cycle(frame(thighL = 128f, kneeL = 105f, liftL = 0.7f, liftR = -0.7f))                 // 얕게(실측 125~138)
        a.cycle(frame(thighL = 100f, kneeL = 70f, liftL = 0.3f, liftR = -0.3f))                  // 뒤꿈치만 접음
        a.cycle(frame(thighL = 95f, thighR = 95f, kneeL = 90f, kneeR = 90f, liftL = 0f))         // 양다리(점프) — 서로에 대한 발 높이가 0 이라 들림이 아니다
        a.cycle(frame(thighL = 70f, kneeL = 60f, liftL = 1.2f, liftR = -1.2f, thighR = 120f))     // 반대 다리도 올라옴
        assertEquals(3, a.counter.reps); assertEquals(listOf("L", "R", "L"), a.events.map { it.side!!.key })
        assertEquals(listOf("shallow", "no_lift", "no_lift", "no_lift", "both_legs", "knee_straight"), reasons(a))   // 마지막: 반대(오른) 다리는 편 채 올라왔다
        assertEquals("발이 바닥에서 더 떠야 세요. 무릎을 골반 높이까지 올려 주세요.", LegCycleTracker.cueFor(LegProfile.KNEE_UP, "no_lift"))
    }

    @Test fun kneeUpRejectsStraightLegKickAndBentTorso() {
        val a = Run(LegProfile.KNEE_UP); a.ready()
        a.cycle(frame(thighL = 70f, kneeL = 140f, liftL = 1.7f, liftR = -1.7f))                  // 편 다리를 앞으로 참(2차 세트 무릎 125~170)
        a.cycle(frame(thighL = 136f, kneeL = 128f, liftL = 0.7f, liftR = -0.7f))                 // 낮게 참 — 높이보다 편 다리를 먼저 말한다
        a.cycle(frame(thighL = 60f, kneeL = 50f, liftL = 1.3f, liftR = -1.3f, pitch = 24f))      // 허리 숙임(기준 8 → 24, 2차 세트 21~36)
        a.cycle(kneeUpL)
        assertEquals(1, a.counter.reps); assertEquals(listOf("knee_straight", "knee_straight", "torso_bent"), reasons(a))
        assertEquals("다리를 뻗으면 세지 않아요. 무릎을 접은 채 올려 주세요.", LegCycleTracker.cueFor(LegProfile.KNEE_UP, "knee_straight"))
        assertEquals("허리를 숙이면 세지 않아요. 가슴을 펴고 몸통을 세운 채 올려 주세요.", LegCycleTracker.cueFor(LegProfile.KNEE_UP, "torso_bent"))
    }

    @Test fun sideCrunchNeedsTheTrunkFoldNotJustTheLeg() {
        val a = Run(LegProfile.SIDE_CRUNCH); a.ready()
        a.cycle(crunchL); a.cycle(crunchR)
        a.cycle(frame(thighL = 110f, kneeL = 95f, liftL = 0.7f, liftR = -0.7f, ekL = 1.45f, roll = -6f, kneeLatL = 0.6f))   // 다리만(실측 0.77~1.59)
        a.cycle(frame(thighL = 70f, kneeL = 60f, liftL = 1.2f, liftR = -1.2f, ekL = 1.5f, kneeLatL = 0.6f))                  // 높이 들었지만 옆구리 안 접음
        a.cycle(frame(thighL = 55f, kneeL = 50f, liftL = 1.5f, liftR = -1.5f, ekL = 0.2f, kneeLatL = 0.6f, latFlexL = 10f))   // 닿았으면 측굴이 작아도 센다(닿음이 정의, 측굴은 beta 참고)
        a.cycle(frame(thighL = 60f, kneeL = 55f, liftL = 1.6f, liftR = -1.6f, ekL = 0.8f, kneeLatL = 0.1f, latFlexL = 20f))   // 앞으로 올림(2차 세트 knee_lat −0.08~0.22)
        a.cycle(frame(thighL = 80f, kneeL = 150f, liftL = 1.7f, liftR = -1.7f, ekL = 0.9f, kneeLatL = 1.3f, latFlexL = 40f))  // 편 다리를 옆으로 뻗음(옆으로 기울여도)
        a.cycle(frame(thighL = 60f, kneeL = 55f, liftL = 1.6f, liftR = -1.6f, ekL = 0.9f))                                    // 3D 무릎 위치가 없으면 2D 바깥 위치(0.1 < 0.45)로 앞으로 올림
        a.cycle(frame(thighL = 55f, kneeL = 50f, liftL = 1.5f, liftR = -1.5f, ekL = 0.2f, kneeLatL = 0.6f, latFlexL = 45f, pitch = 22f))   // 앞으로 숙임(+14)
        a.cycle(frame(thighL = 55f, kneeL = 50f, liftL = 1.5f, liftR = -1.5f, ekL = 0.4f, kneeLatL = 0.6f, latFlexL = 45f))   // 가까이 갔지만 안 닿음(0.4 > 0.30)
        a.cycle(frame(thighL = 55f, kneeL = 50f, liftL = 1.5f, liftR = -1.5f, ekL = 0.5f, kneeLatL = 0.6f, latFlexL = 45f, extra = mapOf("elbow_knee_min_L" to 0.25f)))   // 판정 프레임은 0.5 지만 85 ms 최솟값이 닿음(§98a)
        assertEquals(4, a.counter.reps); assertEquals(listOf(StepSide.LEFT, StepSide.RIGHT, StepSide.LEFT, StepSide.LEFT), a.events.map { it.side })
        assertEquals(listOf("no_crunch", "no_crunch", "no_abduct", "knee_straight", "no_abduct", "torso_bent", "no_crunch"), reasons(a))
        assertEquals("앞으로 올리면 세지 않아요. 무릎을 옆으로 올려 주세요.", LegCycleTracker.cueFor(LegProfile.SIDE_CRUNCH, "no_abduct"))
        assertEquals("다리를 뻗으면 세지 않아요. 무릎을 굽힌 채 옆으로 올려 주세요.", LegCycleTracker.cueFor(LegProfile.SIDE_CRUNCH, "knee_straight"))
        assertEquals("앞으로 숙이면 세지 않아요. 몸통을 세운 채 옆구리만 접어 주세요.", LegCycleTracker.cueFor(LegProfile.SIDE_CRUNCH, "torso_bent"))
        assertEquals("다리만 올리면 세지 않아요. 옆구리를 접어 팔꿈치가 무릎에 닿아야 세요.", LegCycleTracker.cueFor(LegProfile.SIDE_CRUNCH, "no_crunch"))
        assertNull(LegCycleTracker.cueFor(LegProfile.SIDE_CRUNCH, "timeout"))
    }

    @Test fun rebaseOnReturnGapKeepsBaseAndMissingFrameIsSkipped() {
        val a = Run(LegProfile.KNEE_UP); a.ready()
        a.cycle(kneeUpL, back = frame(thighL = 160f, kneeL = 150f))      // 돌아와 선 자세가 조금 달라도(−15°) 닫히고 재기준
        assertEquals(1, a.counter.reps)
        a.feed(frame(thighL = 160f, kneeL = 150f), 2)
        a.cycle(frame(thighL = 60f, kneeL = 50f, liftL = 1.3f, liftR = -1.3f), back = frame(thighL = 160f, kneeL = 150f))
        assertEquals("새 기준(160°)에서 다시 센다", 2, a.counter.reps)
        a.feed(kneeUpR, 2); assertTrue(a.counter.midCycle); assertNotNull(a.counter.pendingAtSetEnd().inProgress)
        a.t += 2_000; a.feed(frame(), 3)
        assertEquals("공백 뒤 복귀는 세지 않는다", 2, a.counter.reps); assertFalse(a.counter.midCycle)
        a.cycle(kneeUpR); assertEquals("기준이 남아 정지 없이 센다", 3, a.counter.reps)
        a.feed(kneeUpL, 2); a.feed(emptyMap()); a.feed(kneeUpL); a.feed(frame(), 3)
        assertEquals("관절 누락 프레임은 건너뛴다", 4, a.counter.reps)
    }

    @Test fun unknownNameLeavesSideNullAndStrictPairingKeepsItOutOfPairs() {
        val a = Run(LegProfile.KNEE_UP); a.ready()
        a.cycle(kneeUpL + (Lunge2d.NAMES_OK to 0f))
        assertEquals(1, a.counter.reps); assertNull(a.events.single().side); assertEquals(1, a.tracker.unknown)
        val sc = SideStepCounter(1, strictUnknown = true)
        sc.offer(1, StepSide.LEFT, false); val e = sc.offer(2, null, false)
        assertEquals(1, sc.track.unknown); assertEquals(0, sc.track.pairs); assertFalse(e.track.counted); assertFalse(e.track.known)
    }

    @Test fun everyProfilePairsLeftAndRightForOneRep() {
        val peaks = mapOf(LegProfile.SIDE to (sideLungeL to sideLungeR), LegProfile.CROSS to (crossLungeL to crossLungeR),
            LegProfile.KNEE_UP to (kneeUpL to kneeUpR), LegProfile.SIDE_CRUNCH to (crunchL to crunchR))
        for ((ex, pk) in peaks) {
            val a = Run(ex); val sc = SideStepCounter(2, strictUnknown = true); a.ready()
            repeat(3) { a.cycle(pk.first, back = if (ex == LegProfile.CROSS) frame(cross = 0.2f) else frame()) }
            a.events.forEach { sc.offer(it.tMs, it.side, false) }
            assertEquals(ex.title, 0, sc.track.pairs); assertFalse(sc.done(false)); assertEquals(1, sc.track.extra)
            a.events.clear(); repeat(2) { a.cycle(pk.second, back = if (ex == LegProfile.CROSS) frame(cross = 0.2f) else frame()) }
            a.events.forEach { sc.offer(it.tMs, it.side, false) }
            assertEquals(ex.title, 2, sc.track.pairs); assertTrue(sc.done(false))
            assertNull(a.counter.signal.romThreshold)
        }
    }

    @Test fun preparationBaseCountsTheImmediateFirstRepAndReplaysViaRestore() {
        val a = Run(LegProfile.SIDE_CRUNCH)
        assertNull(a.counter.standingSeedFrom(listOf(-900L to frame(), -600L to frame(), -300L to frame()), 0L))
        val standing = a.tracker.standing!!
        assertTrue(standing.keys.containsAll(LegCycleTracker.REQUIRED)); assertEquals(8f, standing.getValue("torso_pitch"), 1e-6f); assertEquals(0f, standing.getValue("lat_flex_L"), 1e-6f)
        a.cycle(crunchL); assertEquals(1, a.counter.reps)
        val b = Run(LegProfile.SIDE_CRUNCH); b.tracker.restoreStanding(standing); b.cycle(crunchL)
        assertEquals(a.events, b.events)
        assertEquals(LegCycleTracker.MAX_GAP_MS, a.counter.effectiveMaxGapMs); assertEquals(0L, a.counter.effectiveRefractoryMs)
        val m = a.tracker.annotate(frame(hipY = 0.64f)); assertEquals(0.5f, m.getValue("hip_drop"), 1e-3f)
    }

    /** 앱 PostureLive 의 루프 — annotate → 평가기 → 카운터 → 발표된 사이클마다 onCycle. */
    private class Live(ex: LegProfile) {
        val counter = RepCounter.forSession(ex.title, floor = false)!!
        val rf = RepFormSpecs.evaluatorFor(ex.title, counter)!!
        var t = 0L
        val reps = ArrayList<RepFormRep>()
        fun feed(f: Map<String, Float>, n: Int = 1) = repeat(n) {
            val judged = counter.legTracker!!.annotate(f)
            rf.onFrame(t, judged); counter.onFrameFeatures(t, judged)
            for (c in counter.newlyPublished) reps += rf.onCycle(c.tMs, c.min, c.max, c.startMs, c.side)
            t += 300
        }
    }

    @Test fun evaluatorResolvesTheMovingLegAndBetaNeverCounts() {
        val live = Live(LegProfile.KNEE_UP)
        assertEquals(LegProfile.KNEE_UP, live.rf.legProfile)
        live.feed(frame(), 4)
        live.feed(frame(thighL = 60f, kneeL = 50f, liftL = 1.3f, liftR = -1.3f, hikeL = 0.2f, kneeR = 178f, pitch = 12f), 3); live.feed(frame(), 3)
        val rep = live.reps.single()
        assertEquals(StepSide.LEFT, rep.side)
        val hike = rep.outcomes.first { it.check.id == "repform|스탠딩 니업|골반 균형" }
        assertEquals("hip_hike_L", hike.check.feature); assertEquals(Verdict.VIOLATION, hike.verdict); assertEquals(0.2f, hike.raw!!, 1e-3f)
        val lock = rep.outcomes.first { it.check.id == "repform|스탠딩 니업|지지 무릎 잠김" }
        assertEquals("knee_R", lock.check.feature); assertEquals(Verdict.VIOLATION, lock.verdict)
        assertEquals("숙임 +4° 는 정상(±15) — 더 숙인 회는 v3 판별이 세지 않는다", Verdict.OK, rep.outcomes.first { it.check.id == "repform|스탠딩 니업|가슴 펴기" }.verdict)
        assertFalse("ship 검사가 없으니 판정한 회가 아니다(원칙 #1)", rep.judged); assertTrue("beta 위반은 정확을 깎지 못한다(원칙 #2)", rep.correct)
        val summary = live.rf.summary()
        assertEquals(0, summary.correct)
        val logged = summary.toLog(0).reps.single()
        assertFalse(logged.correct); assertEquals("UNJUDGED", logged.formState); assertEquals("L", logged.movingSide)
        assertTrue(live.rf.onCycle(live.t, 60f, 175f, live.t - 1800, null).outcomes.all { it.verdict == Verdict.ABSTAIN && it.abstainReason == "좌우 미확인" })
    }

    @Test fun crunchChecksResolveAndAllChecksAreBetaWithCues() {
        val live = Live(LegProfile.SIDE_CRUNCH)
        live.feed(frame(), 4); live.feed(crunchL + mapOf("head_lean_L" to 22f, LegGeometry.REL_YAW to 30f), 3); live.feed(frame(), 3)
        val rep = live.reps.single()
        assertEquals(Verdict.VIOLATION, rep.outcomes.first { it.check.id == "repform|스탠딩 사이드 크런치|머리 당김" }.verdict)
        assertEquals("head_lean_L", rep.outcomes.first { it.check.id == "repform|스탠딩 사이드 크런치|머리 당김" }.check.feature)
        assertEquals(Verdict.VIOLATION, rep.outcomes.first { it.check.id == "repform|스탠딩 사이드 크런치|가슴 정면" }.verdict)
        assertEquals(Verdict.OK, rep.outcomes.first { it.check.id == "repform|스탠딩 사이드 크런치|골반 고정" }.verdict)
        for (ex in LegProfile.entries) {
            val checks = RepFormSpecs.byExercise.getValue(ex.title)
            assertTrue(checks.isNotEmpty())
            assertTrue(checks.all { it.status == RuleStatus.BETA && !it.ship && !it.cue.isNullOrBlank() && it.fix.isNotBlank() })
            assertFalse(RepSignals.byExercise.getValue(ex.title).validated)
        }
        assertEquals("knee_R", LegProfile.SIDE.resolve("knee_{support}", StepSide.LEFT))
        assertEquals(LegGeometry.CROSS, LegProfile.CROSS.resolve(LegProfile.CROSS.signal, StepSide.LEFT))
    }

    @Test fun geometryGivesLateralFlexionAndKneeTravelAlongTheFoot() {
        val j = joints + mapOf(Joints.L_ANKLE to Vec3(15f, -90f, 0f), Joints.L_FOOT to Vec3(15f, -90f, 20f), Joints.R_ANKLE to Vec3(-15f, -90f, 0f), Joints.R_FOOT to Vec3(-15f, -90f, 20f),
            Joints.L_KNEE to Vec3(15f, -45f, 15f), Joints.R_KNEE to Vec3(-15f, -45f, 0f))
        val frame = PoseFrame(j, Vec3(0f, 1f, 0f))
        val a = FloatArray(66) { Float.NaN }; val vis = FloatArray(33) { 1f }
        fun put(i: Int, x: Float, y: Float) { a[i * 2] = x; a[i * 2 + 1] = y }
        put(11, 0.60f, 0.35f); put(12, 0.40f, 0.25f); put(23, 0.56f, 0.55f); put(24, 0.44f, 0.55f)     // 왼어깨(화면 오른쪽)가 내려감 = 왼 옆구리 접힘
        put(25, 0.58f, 0.75f); put(26, 0.42f, 0.75f); put(27, 0.60f, 0.95f); put(28, 0.40f, 0.95f)
        val f = LegGeometry.features(frame, a, vis, 0.5f, 1f, 0f)
        assertEquals(26.57f, f.getValue("lat_flex_L"), 0.05f); assertEquals(-26.57f, f.getValue("lat_flex_R"), 0.05f)
        assertEquals("무릎이 발 방향으로 15 cm ÷ 정강이 47.4", 0.316f, f.getValue("knee_fwd_foot_L"), 1e-3f)
        assertEquals(0f, f.getValue("knee_fwd_foot_R"), 1e-6f)
    }

    // ---- 기하
    private val joints = mapOf(
        Joints.L_SHOULDER to Vec3(20f, 50f, 0f), Joints.R_SHOULDER to Vec3(-20f, 50f, 0f),
        Joints.L_HIP to Vec3(15f, 0f, 0f), Joints.R_HIP to Vec3(-15f, 0f, 0f),
        Joints.L_KNEE to Vec3(15f, -45f, 0f), Joints.R_KNEE to Vec3(-15f, -45f, 0f),
        Joints.L_ANKLE to Vec3(15f, -90f, 0f), Joints.R_ANKLE to Vec3(-15f, -90f, 0f))
    private fun xy(ankleLx: Float = 0.60f, ankleRx: Float = 0.40f, ankleLy: Float = 0.90f, ankleRy: Float = 0.90f, hipMidX: Float = 0.5f, earTilt: Float = 0f): FloatArray {
        val a = FloatArray(66) { Float.NaN }
        fun put(i: Int, x: Float, y: Float) { a[i * 2] = x; a[i * 2 + 1] = y }
        put(11, 0.65f, 0.30f); put(12, 0.35f, 0.30f); put(23, hipMidX + 0.10f, 0.50f); put(24, hipMidX - 0.10f, 0.50f)
        put(25, 0.60f, 0.70f); put(26, 0.40f, 0.70f); put(27, ankleLx, ankleLy); put(28, ankleRx, ankleRy)
        put(13, 0.72f, 0.42f); put(14, 0.28f, 0.42f); put(15, 0.70f, 0.20f); put(16, 0.30f, 0.20f); put(7, 0.55f, 0.12f + earTilt); put(8, 0.45f, 0.12f - earTilt)
        return a
    }
    private val vis = FloatArray(33) { 1f }

    @Test fun geometryGivesCrossingHipShiftLiftAndHeadLeanInTorsoUnits() {
        val f = LegGeometry.features(PoseFrame(joints), xy(), vis, 0.5f, 0.75f, 0f)
        assertEquals(0.2f, f.getValue(LegGeometry.TORSO2D), 1e-4f)
        assertTrue("카메라를 본다: 왼어깨가 화면 오른쪽", f.getValue(LegGeometry.FACE_DX) > 0f)
        assertEquals("왼발이 사용자 왼쪽(화면 오른쪽)이면 +", 0.75f, f.getValue(LegGeometry.CROSS), 1e-3f)
        assertEquals(0.5f, f.getValue(LegGeometry.HIPSHIFT), 1e-3f)
        assertEquals(0f, f.getValue(LegGeometry.lift(StepSide.LEFT)), 1e-6f)
        assertEquals(180f, f.getValue("thigh_L"), 0.01f); assertEquals(0f, f.getValue("head_lean_L"), 0.01f)
        val crossed = LegGeometry.features(PoseFrame(joints), xy(ankleLx = 0.40f, ankleRx = 0.55f, ankleRy = 0.80f, hipMidX = 0.58f), vis, 0.5f, 0.75f, 0f)
        assertTrue("발목이 교차하면 −", crossed.getValue(LegGeometry.CROSS) < -0.4f)
        assertEquals("오른발이 더 위(멀다) = 왼발이 오른발보다 낮다", -0.10f / crossed.getValue(LegGeometry.TORSO2D), crossed.getValue(LegGeometry.lift(StepSide.LEFT)), 1e-3f)
        val shifted = LegGeometry.features(PoseFrame(joints), xy(hipMidX = 0.58f), vis, 0.5f, 0.75f, 0f)
        assertEquals("골반이 왼발 쪽으로 옮겨감", 0.9f, shifted.getValue(LegGeometry.HIPSHIFT), 1e-3f)
        val lean = LegGeometry.features(PoseFrame(joints), xy(earTilt = 0.02f), vis, 0.5f, 0.75f, 0f)
        assertTrue("왼귀가 아래로 = 머리가 왼쪽으로 기움", lean.getValue("head_lean_L") > 5f); assertEquals(-lean.getValue("head_lean_L"), lean.getValue("head_lean_R"), 1e-4f)
        val profile = LegGeometry.features(PoseFrame(joints), xy(ankleLx = 0.50f, ankleRx = 0.49f), vis, 0.5f, 0.75f, 0f)
        assertFalse("옆모습(발목 간격 없음)엔 골반 위치가 없다", profile.containsKey(LegGeometry.HIPSHIFT))
        val rolled = LegGeometry.features(PoseFrame(joints), xy(), vis, 0.5f, 0.75f, 10f)
        for (k in listOf(LegGeometry.TORSO2D, "hand_ear_L", "elbow_knee_R")) assertEquals(k, f.getValue(k), rolled.getValue(k), 1e-4f)
        val hidden = vis.copyOf().also { it[27] = 0f }
        val h = LegGeometry.features(PoseFrame(joints), xy(), hidden, 0.5f, 0.75f, 0f)
        assertFalse(h.containsKey(LegGeometry.CROSS)); assertTrue(h.containsKey("thigh_L")); assertTrue(h.containsKey(LegGeometry.HIP_Y))
    }
}
