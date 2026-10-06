package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class CapturePreparationTest {
    @Test fun guidanceNamesDirectionBeforeFiveSecondStart() {
        ExerciseProfiles.all.filter { it.cameraEnabled }.forEach { profile ->
            val text=profile.preparationInstruction
            assertTrue(text.startsWith("권장 촬영 방향은 ${profile.preparationDirection}"))
            assertTrue(text.indexOf(profile.capture.voice) < text.indexOf("3초"))
        }
    }
    @Test fun lungesStateTheLeftPlusRightRuleBeforeTheStart() {
        // 사용자 결정(2026-09-24): 교대 동작은 "왼쪽과 오른쪽을 한 번씩 = 1회" — 런지류는 카운터 사이클(한 걸음) 둘을 1회로 묶는다.
        // 목표 도달 자동 진행(spec §42)은 유지되므로 두 쪽을 다 해야 세트가 넘어간다 — 그 정의를 시작 전에 밝힌다(설계 §4.8).
        // (예전 문장 "번갈아 하는 동작은 한쪽 1회를 1회로 셉니다." 는 이 결정으로 뒤집혔다 — 이 테스트의 기대값도 그래서 바뀌었다.)
        assertEquals("왼쪽과 오른쪽을 한 번씩 해야 1회로 셉니다.", ALTERNATING_COUNT_RULE)
        // 런지만 쪽별로 따로 센다(§63, 사용자 결정 2026-09-26) — 그 규칙을 시작 전에 밝힌다. 바벨·사이드·크로스 런지는 두 걸음 = 1회 그대로
        val lunge = ExerciseProfiles.forName("런지")!!
        assertTrue(lunge.alternating)
        assertEquals(RepUnit.SIDE_EACH, lunge.repUnit)
        assertTrue(lunge.preparationInstruction.contains(SIDE_EACH_COUNT_RULE))
        assertTrue(lunge.preparationInstruction.indexOf(SIDE_EACH_COUNT_RULE) < lunge.preparationInstruction.indexOf("3초"))
        assertFalse(lunge.preparationInstruction.contains(ALTERNATING_COUNT_RULE))
        // 바벨 런지도 쪽별(§66) — 좌우 짝 안내는 사이드 런지로 본다
        assertTrue(ExerciseProfiles.forName("바벨 런지")!!.preparationInstruction.contains(SIDE_EACH_COUNT_RULE))
        val side = ExerciseProfiles.forName("사이드 런지")!!
        assertTrue(side.preparationInstruction.contains(LEG_PAIR_COUNT_RULE))
        assertTrue(side.preparationInstruction.indexOf(LEG_PAIR_COUNT_RULE) < side.preparationInstruction.indexOf("3초"))
        assertEquals(setOf("런지", "바벨 런지", "사이드 런지", "크로스 런지", "덤벨 컬", "스탠딩 니업", "스탠딩 사이드 크런치"),
            ExerciseProfiles.all.filter { it.alternating }.map { it.name }.toSet())
        assertEquals(emptySet<String>(),
            ExerciseProfiles.all.filter { it.repUnit == RepUnit.SIDE_PAIR }.map { it.name }.toSet())
        assertEquals("쪽별 카운트 = 걸음 검사기가 있는 런지(§63·§66)", SIDE_EACH_EXERCISES,
            ExerciseProfiles.all.filter { it.repUnit == RepUnit.SIDE_EACH }.map { it.name }.toSet())
        ExerciseProfiles.all.filter { it.repUnit == RepUnit.SIDE_PAIR }.forEach {
            assertTrue(it.name, it.preparationInstruction.contains(ALTERNATING_COUNT_RULE))
            assertFalse(it.name, it.floor)   // 짝 단위는 서서 하는 런지류뿐 — 바닥 종목은 늘 사이클 단위
        }
        // 짝이 아닌 종목은 짝 규칙도, 폐기된 "한쪽 1회" 정의도 말하지 않는다
        ExerciseProfiles.all.filter { it.repUnit == RepUnit.CYCLE }.forEach {
            assertFalse(it.name, it.preparationInstruction.contains(ALTERNATING_COUNT_RULE))
            assertFalse(it.name, it.preparationInstruction.contains("한 번씩"))
        }
        ExerciseProfiles.all.forEach { assertFalse(it.name, it.preparationInstruction.contains("한쪽 1회")) }
    }
    @Test fun averagedTwoLimbSignalsDoNotPromiseTheSideRule() {
        // 덤벨 컬·스탠딩 니업은 교대 동작이지만 카운트 신호가 두 팔·두 엉덩이 평균이다 — 양쪽을 함께 하는 반복은 한 사이클 = 양쪽 = 1회로
        // 이미 같은 정의이고, 한쪽씩 번갈아 하는 반복은 쪽별 귀속이 없어 짝으로 셀 수 없다. 지키지 못하는 정의를 안내에 넣지 않는다(원칙 #1, 설계 §4.4).
        for (name in listOf("덤벨 컬")) {   // 스탠딩 니업은 §97(한 다리 계열)부터 쪽별(SIDE_EACH) — 사용자 결정 2026-10-06 저녁
            val p = ExerciseProfiles.forName(name)!!
            assertTrue(name, p.alternating)
            assertEquals(name, RepUnit.CYCLE, p.repUnit)
            assertFalse(name, p.preparationInstruction.contains(ALTERNATING_COUNT_RULE))
        }
    }
    @Test fun guidanceKeepsAnatomicalRightAndLeft() {
        val right=ExerciseProfiles.all.first { it.capture==CapturePosition.RIGHT_FRONT }
        val left=ExerciseProfiles.all.first { it.capture==CapturePosition.LEFT_FRONT }
        assertTrue(right.preparationInstruction.contains("오른어깨"))
        assertTrue(left.preparationInstruction.contains("왼어깨"))
        // 사선은 각도까지 말한다(사용자 요청 2026-09-26) — 덤벨 컬은 왼어깨 쪽 45도
        assertTrue(right.preparationInstruction.contains("오른어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요"))
        assertTrue(left.preparationInstruction.contains("왼어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요"))
        val curl = ExerciseProfiles.forName("덤벨 컬")!!
        assertEquals(CapturePosition.LEFT_FRONT, curl.capture)
        assertTrue(curl.preparationInstruction.contains("왼어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요"))
        // 첫 반복이 기준 — 처음을 바르게 하라고 시작 전에 밝힌다(§62c 후속 9). 5초 안내보다 앞
        val hint = "처음 두세 번은 팔꿈치를 옆구리에 붙이고 정확하게 해 주세요."
        assertTrue(curl.preparationInstruction.contains(hint))
        assertTrue(curl.preparationInstruction.indexOf(hint) < curl.preparationInstruction.indexOf("3초"))
        assertFalse(ExerciseProfiles.forName("기본 스쿼트")!!.preparationInstruction.contains(hint))
    }

    private val ready = CaptureFrame(true, "촬영 범위", listOf(.2f,.1f,.8f,.9f))
    private fun controller(floor: Boolean = false) = CapturePreparationController(floor).apply { arm(0) }
    private fun stabilize(c: CapturePreparationController) { for (t in 0L..400L step 200L) { c.observe(t, ready);c.tick(t) } }
    private fun sample(hidden: Set<Int> = emptySet(), emptyFeatures: Boolean = false): PoseSample {
        val xy = FloatArray(66) { .5f }
        for (i in 0..32) { xy[i*2]=if(i%2==0).6f else .4f;xy[i*2+1]=.1f+i/40f }
        return PoseSample(true,xy,FloatArray(33){if(it in hidden) 0f else .9f},
            if(emptyFeatures) emptyMap() else mapOf("torso_pitch" to 0f),33-hidden.size,1,480,640)
    }
    @Test fun seeingSomeoneDoesNotArmStart() {
        val c=CapturePreparationController(false)
        repeat(30){c.observe(it*400L,ready);c.tick(it*400L)}
        assertEquals(PreparationPhase.IDLE,c.state.phase)
    }
    @Test fun bothKindsStartAfterThreeSecondsOfFreshCoverage() {
        for(floor in listOf(false,true)) {
            val c=controller(floor);stabilize(c)
            assertEquals(3,c.state.seconds)
            for(t in 600L..3200L step 200L){c.observe(t,ready);c.tick(t)}
            assertEquals(PreparationPhase.COUNTDOWN,c.state.phase)
            c.observe(3400,ready);assertEquals(PreparationPhase.STARTED,c.tick(3400).phase)
        }
    }
    @Test fun framingIsMeasuredDuringTheIntroAndCountsRightAfterIt() {
        // §89: 안내 음성 중에도 범위·안정을 재되 카운트다운은 보류 — 풀리면 다음 프레임에서 바로 센다
        val c=controller();c.holdStart(true)
        for(t in 0L..6000L step 300L){c.observe(t,ready);c.tick(t)}
        assertEquals(PreparationPhase.WAITING,c.state.phase)
        c.holdStart(false);c.observe(6300,ready)
        assertEquals(PreparationPhase.COUNTDOWN,c.state.phase);assertEquals(3,c.state.seconds)
    }
    @Test fun briefLossFreezesThenContinuesWithoutFiveSecondReset() {
        val c=controller();stabilize(c)
        for(t in 600L..2000L step 200L){c.observe(t,ready);c.tick(t)}
        val remaining=c.state.seconds
        c.observe(2200,CaptureFrame(false,"화면 밖"));c.tick(2200)
        c.tick(2500);assertEquals(remaining,c.state.seconds);assertTrue(c.state.trackingHold)
        c.observe(2600,ready);c.tick(2600);c.observe(3000,ready);c.tick(3000)
        assertFalse(c.state.trackingHold);assertEquals(0,c.state.restarts)
        assertTrue(c.state.seconds<=remaining)
    }
    @Test fun holdAndRestartSayWhatWasNotSeen() {
        // 멈추면 그 이유를, 처음으로 돌아가면 그 이유 + 다시 센다는 말을(사용자 보고 2026-09-29)
        val c=controller();stabilize(c)
        c.observe(600,CaptureFrame(false,"왼손이 안 보여요. 팔이 몸에 가려지지 않게 해 주세요."));c.tick(600)
        assertTrue(c.state.trackingHold);assertTrue(c.state.message.startsWith("왼손이 안 보여요"))
        assertEquals(PreparationPhase.WAITING,c.tick(3200).phase)
        assertTrue(c.state.message,c.state.message.startsWith("왼손이 안 보여요") && c.state.message.endsWith("다시 자리 잡으면 3초를 셉니다."))
        val m=controller();m.observe(0,ready);m.observe(400,ready)
        m.observe(600,ready.copy(anchors=ready.anchors.map{it+.1f}));m.tick(600)
        assertTrue(m.state.message.startsWith("몸이 많이 움직였어요"))
    }
    @Test fun sustainedLossReturnsToGuideAndRestarts() {
        val c=controller();stabilize(c)
        c.observe(600,CaptureFrame(false,"화면 밖"));c.tick(600)
        // 끊김 허용 2.5 s(§89, 종전 1.2 s)
        assertEquals(PreparationPhase.COUNTDOWN,c.tick(3000).phase)
        assertEquals(PreparationPhase.WAITING,c.tick(3200).phase)
        c.observe(3400,ready);c.observe(3800,ready)
        assertEquals(3,c.state.seconds);assertEquals(1,c.state.restarts)
    }
    @Test fun lostFinalFramesCannotStartAnEmptyCamera() {
        val c=controller();stabilize(c)
        for(t in 600L..2800L step 200L){c.observe(t,ready);c.tick(t)}
        assertEquals(1,c.state.seconds)
        assertEquals(PreparationPhase.COUNTDOWN,c.tick(3700).phase)
        assertTrue(c.state.trackingHold)
        assertEquals(PreparationPhase.WAITING,c.tick(6100).phase)
    }
    @Test fun movementOrTurningFreezesCountdown() {
        for(frame in listOf(ready.copy(anchors=ready.anchors.map{it+.1f}), ready.copy(facing=listOf(0f,1f)))) {
            val c=controller();c.observe(0,ready.copy(facing=listOf(1f,0f)));c.observe(400,ready.copy(facing=listOf(1f,0f)))
            c.observe(600,frame);assertTrue(c.tick(600).trackingHold)
            assertEquals(PreparationPhase.WAITING,c.tick(3200).phase)
        }
    }
    @Test fun directionNeedsConsistentEvidenceAndDoesNotGateFloor() {
        val wrong=mapOf("view_cos" to 0f,"view_sin" to 1f)
        val front=mapOf("view_cos" to 1f,"view_sin" to 0f)
        val window=CaptureDirectionWindow(CapturePosition.FRONT,false)
        // 8프레임으로 방향을 정하고, 틀린 방향이 2초 넘게 이어질 때만 막는다(§89)
        for(k in 0..13){assertTrue(window.check(ready,wrong,k*300L).ready)}
        assertFalse(window.check(ready,wrong,4200L).ready)
        for(k in 0..8){window.check(ready,front,4500L+k*300L)}
        assertTrue(window.check(ready,front,7500L).ready)
        val floor=CaptureDirectionWindow(CapturePosition.FLOOR_SIDE,true)
        repeat(12){assertTrue(floor.check(ready,wrong).ready)}
        window.clear();assertTrue(window.check(ready,emptyMap()).ready)
    }
    @Test fun continuousMovementDoesNotBecomeStable() {
        val c=controller()
        for(t in 0L..8000L step 400L)c.observe(t,ready.copy(anchors=ready.anchors.map{it+(t/400%2)*.07f}))
        assertEquals(PreparationPhase.WAITING,c.state.phase)
    }
    @Test fun duplicateFramesCannotCompleteStability() {
        val c=controller();repeat(30){c.observe(400,ready);c.tick(400)}
        assertEquals(PreparationPhase.WAITING,c.state.phase)
    }
    @Test fun manualCountdownNeedsNoCameraAndCancelDisarmsIt() {
        val c=controller();c.manual(100,15)
        assertEquals(1,c.tick(14100).seconds)
        c.cancel();assertEquals(PreparationPhase.IDLE,c.tick(30000).phase)
        c.manual(40000,10);assertEquals(PreparationPhase.STARTED,c.tick(50000).phase)
    }
    @Test fun obliqueAtFiftyDegreesStillCountsAsTheAskedDirection() {
        // 45° 로 서면 MediaPipe 요가 46.2° 를 넘기 일쑤 — 반복 검사와 같은 55° 까지 사선으로 본다(§89)
        val r=Math.toRadians(50.0)
        val at50=mapOf("view_cos" to Math.cos(r).toFloat(),"view_sin" to (Math.sin(r)*ViewEstimator.B_SIGN).toFloat())
        val w=CaptureDirectionWindow(CapturePosition.RIGHT_FRONT,false)
        for(k in 0..20){assertTrue(w.check(ready,at50,k*300L).ready)}
    }
    @Test fun directionBlocksOnlyAClearlyWrongSide() {
        fun at(deg: Double)=Math.toRadians(deg).let { mapOf("view_cos" to Math.cos(it).toFloat(),"view_sin" to Math.sin(it).toFloat()) }
        // 09-29 덤벨 컬: 왼쪽 앞(D) 요청에 요 −18~−23°, 경계(16.4°)를 넘나듦 — 덜 돌아선 것은 막지 않는다
        val d=CaptureDirectionWindow(CapturePosition.LEFT_FRONT,false)
        for(k in 0..20){assertTrue(d.check(ready,at(-12.0*ViewEstimator.B_SIGN),k*300L).ready)}
        // 반대쪽 사선(B)은 2초 뒤 막는다
        val wrong=CaptureDirectionWindow(CapturePosition.LEFT_FRONT,false)
        for(k in 0..13){wrong.check(ready,at(30.0*ViewEstimator.B_SIGN),k*300L)}
        assertFalse(wrong.check(ready,at(30.0*ViewEstimator.B_SIGN),4500L).ready)
        // 정면 요청은 30° 까지 허용
        val f=CaptureDirectionWindow(CapturePosition.FRONT,false)
        for(k in 0..20){assertTrue(f.check(ready,at(25.0),k*300L).ready)}
    }
    @Test fun upperBodyExercisesDoNotNeedLegsAndObliquesDoNotNeedTheFarLimbs() {
        // 컬처럼 팔 운동은 상체만 — 다리가 화면 아래 밖이어도 통과(09-25 컬 세트 무릎 y 1.2~2.5)
        val curl=ExerciseProfiles.forName("덤벨 컬")!!
        assertEquals(FramingRegion.UPPER,curl.framingRegion)
        assertEquals(FramingRegion.FULL,ExerciseProfiles.forName("기본 스쿼트")!!.framingRegion)
        val legsOut=sample();for(i in listOf(25,26,27,28,29,30,31,32)){legsOut.normalizedXy[i*2+1]=1.4f;legsOut.visibility[i]=0f}
        assertTrue(CaptureFraming.inspect(legsOut,CapturePosition.LEFT_FRONT,false,FramingRegion.UPPER).ready)
        val full=CaptureFraming.inspect(legsOut,CapturePosition.FRONT,false,FramingRegion.FULL)
        assertFalse(full.ready);assertTrue(full.message,full.message.contains("발이 화면 아래로"))
        // 사선: 먼 쪽 팔다리(여기선 오른쪽)는 가려져도 된다 — 먼 쪽 어깨·골반·발목만
        assertTrue(CaptureFraming.inspect(sample(setOf(14,16,26)),CapturePosition.LEFT_FRONT,false).ready)
        assertFalse(CaptureFraming.inspect(sample(setOf(14,16,26)),CapturePosition.FRONT,false).ready)
    }
    @Test fun toesAreNotRequiredAndAnklesMayTouchTheBottomEdge() {
        assertTrue(CaptureFraming.inspect(sample(setOf(29,30,31,32)),CapturePosition.FRONT,false).ready)
        val s=sample();s.normalizedXy[27*2+1]=.995f;s.normalizedXy[28*2+1]=1f
        assertTrue(CaptureFraming.inspect(s,CapturePosition.FRONT,false).ready)
    }
    @Test fun missingHandIsNamed() {
        val m=CaptureFraming.inspect(sample(setOf(15)),CapturePosition.FRONT,false).message
        assertTrue(m,m.startsWith("왼손"))
    }
    @Test fun detectedFlagDoesNotSubstituteForJointsOrFeatures() {
        assertFalse(CaptureFraming.inspect(sample(emptyFeatures=true),CapturePosition.FRONT,false).ready)
        assertFalse(CaptureFraming.inspect(sample(setOf(15)),CapturePosition.FRONT,false).ready)
    }
    @Test fun sideNeedsOneCompleteChainAndHead() {
        assertTrue(CaptureFraming.inspect(sample(setOf(12,14,16,24,26,28,32)),CapturePosition.FLOOR_SIDE,true).ready)
        assertFalse(CaptureFraming.inspect(sample(setOf(15,28)),CapturePosition.FLOOR_SIDE,true).ready)
        assertFalse(CaptureFraming.inspect(sample(setOf(0,7,8)),CapturePosition.FLOOR_SIDE,true).ready)
    }
    @Test fun edgeAndInvalidCoordinatesCannotPass() {
        val s=sample();s.normalizedXy[15*2]=.99f
        assertFalse(CaptureFraming.inspect(s,CapturePosition.FRONT,false).ready)
        s.normalizedXy[15*2]=Float.NaN
        assertFalse(CaptureFraming.inspect(s,CapturePosition.FRONT,false).ready)
    }
}
