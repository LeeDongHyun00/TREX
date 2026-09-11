package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class CapturePreparationTest {
    @Test fun guidanceNamesDirectionBeforeFiveSecondStart() {
        ExerciseProfiles.all.filter { it.cameraEnabled }.forEach { profile ->
            val text=profile.preparationInstruction
            assertTrue(text.startsWith("권장 촬영 방향은 ${profile.preparationDirection}"))
            assertTrue(text.indexOf(profile.capture.voice) < text.indexOf("5초"))
        }
    }
    @Test fun guidanceKeepsAnatomicalRightAndLeft() {
        val right=ExerciseProfiles.all.first { it.capture==CapturePosition.RIGHT_FRONT }
        val left=ExerciseProfiles.all.first { it.capture==CapturePosition.LEFT_FRONT }
        assertTrue(right.preparationInstruction.contains("오른어깨"))
        assertTrue(left.preparationInstruction.contains("왼어깨"))
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
    @Test fun bothKindsStartAfterFiveSecondsOfFreshCoverage() {
        for(floor in listOf(false,true)) {
            val c=controller(floor);stabilize(c)
            assertEquals(5,c.state.seconds)
            for(t in 600L..5200L step 200L){c.observe(t,ready);c.tick(t)}
            assertEquals(PreparationPhase.COUNTDOWN,c.state.phase)
            c.observe(5400,ready);assertEquals(PreparationPhase.STARTED,c.tick(5400).phase)
        }
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
    @Test fun sustainedLossReturnsToGuideAndRestartsAtFive() {
        val c=controller();stabilize(c)
        c.observe(600,CaptureFrame(false,"화면 밖"));c.tick(600)
        assertEquals(PreparationPhase.COUNTDOWN,c.tick(1600).phase)
        assertEquals(PreparationPhase.WAITING,c.tick(1800).phase)
        c.observe(2000,ready);c.observe(2400,ready)
        assertEquals(5,c.state.seconds);assertEquals(1,c.state.restarts)
    }
    @Test fun lostFinalFramesCannotStartAnEmptyCamera() {
        val c=controller();stabilize(c)
        for(t in 600L..5000L step 200L){c.observe(t,ready);c.tick(t)}
        assertEquals(1,c.state.seconds)
        assertEquals(PreparationPhase.COUNTDOWN,c.tick(5900).phase)
        assertTrue(c.state.trackingHold)
        assertEquals(PreparationPhase.WAITING,c.tick(7000).phase)
    }
    @Test fun movementOrTurningFreezesCountdown() {
        for(frame in listOf(ready.copy(anchors=ready.anchors.map{it+.1f}), ready.copy(facing=listOf(0f,1f)))) {
            val c=controller();c.observe(0,ready.copy(facing=listOf(1f,0f)));c.observe(400,ready.copy(facing=listOf(1f,0f)))
            c.observe(600,frame);assertTrue(c.tick(600).trackingHold)
            assertEquals(PreparationPhase.WAITING,c.tick(1800).phase)
        }
    }
    @Test fun directionNeedsConsistentEvidenceAndDoesNotGateFloor() {
        val wrong=mapOf("view_cos" to 0f,"view_sin" to 1f)
        val front=mapOf("view_cos" to 1f,"view_sin" to 0f)
        val window=CaptureDirectionWindow(CapturePosition.FRONT,false)
        repeat(7){assertTrue(window.check(ready,wrong).ready)}
        assertFalse(window.check(ready,wrong).ready)
        repeat(8){window.check(ready,front)}
        assertTrue(window.check(ready,front).ready)
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
