package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 합성 시계열은 상태·단위·유보 계약 검증이다. 사람의 검출 정확도 측정이 아니다. */
class FourExerciseTest {
    private fun frame(l: Float = 175f, r: Float = 175f, kneeL: Float = 175f, kneeR: Float = 175f,
                      x: Float = .35f, z: Float = 0f, roll: Float = 0f): Map<String, Float> = mapOf(
        "four_visible" to 1f, "four_leg_cm" to 85f, "four_axis_x" to 1f, "four_axis_z" to 0f,
        "four_img_torso" to .3f,
        "four_img_x_L" to (if(z < -.1f) .3f else if(x > .5f && kneeL < kneeR) .9f else .6f),
        "four_img_y_L" to (if(z < -.1f) .7f else .8f),
        "four_img_x_R" to (if(z > .1f) .7f else if(x > .5f && kneeR < kneeL) .1f else .4f),
        "four_img_y_R" to (if(z > .1f) .7f else .8f),
        "four_feet_x" to x, "four_feet_z" to z,
        "knee_L" to kneeL, "knee_R" to kneeR, "four_thigh_L" to l, "four_thigh_R" to r,
        "four_ankle_h_L" to (-1f + (175f-l)/100f), "four_ankle_h_R" to (-1f + (175f-r)/100f),
        "four_knee_out_L" to .2f, "four_knee_out_R" to .2f,
        "torso_roll" to roll, "torso_pitch" to 0f, "four_twist" to 0f,
        "four_elbow_knee_L" to .7f, "four_elbow_knee_R" to .7f,
        "four_hand_head_L" to .4f, "four_hand_head_R" to .4f,
        ViewEstimator.FEAT_COS_SH to 1f, ViewEstimator.FEAT_SIN_SH to 0f)
    private class Run(ex: FourExercise) {
        val counter = RepCounter.forSession(ex.title, floor = false)!!
        var t = 0L
        val events = ArrayList<RepCycle>()
        fun feed(f: Map<String, Float>, n: Int = 1) { repeat(n) { counter.onFrameFeatures(t, f); events += counter.newlyPublished; t += 200 } }
    }
    private fun Run.ready() = feed(frame(), 4)
    private fun Run.lift(side: StepSide, roll: Float = 0f) {
        feed(if (side == StepSide.LEFT) frame(l=100f,roll=roll) else frame(r=100f,roll=roll), 3)
        feed(frame(), 2)
    }
    private fun Run.lunge(ex: FourExercise, side: StepSide, named: Boolean = true) {
        val peak = if (ex == FourExercise.CROSS) frame(kneeL=95f,kneeR=95f,x=-.18f,z=if(side==StepSide.LEFT) .5f else -.5f)
            else frame(kneeL=if(side==StepSide.LEFT) 95f else 172f,kneeR=if(side==StepSide.RIGHT) 95f else 172f,x=.9f)
        feed(peak + (Lunge2d.NAMES_OK to if (named) 1f else 0f), 3); feed(frame(), 2)
    }
    @Test fun lastLiftCompletesWithoutNextDepartureAndRestDoesNotRepeat() {
        val a = Run(FourExercise.KNEE_UP); a.ready(); a.lift(StepSide.LEFT); a.feed(frame(), 12)
        assertEquals(1, a.counter.reps); assertEquals(StepSide.LEFT, a.events.single().side)
    }
    @Test fun sameSideLiftsEachCountAndUseNoAveragedRom() {
        val a = Run(FourExercise.KNEE_UP); a.ready(); repeat(3) { a.lift(StepSide.RIGHT) }
        assertEquals(3, a.counter.reps); assertEquals(3, a.counter.fourTracker!!.right)
        assertNull(a.counter.signal.romThreshold)
    }
    @Test fun overlappingOppositeLiftDoesNotCloseTheWrongLeg() {
        val a = Run(FourExercise.KNEE_UP); a.ready()
        a.feed(frame(l=100f),3); a.feed(frame(l=100f,r=100f),2)
        a.feed(frame(r=100f),2)
        assertEquals(listOf(StepSide.LEFT), a.events.map { it.side })
        a.feed(frame(),2)
        assertEquals(listOf(StepSide.LEFT,StepSide.RIGHT), a.events.map { it.side })
    }
    @Test fun oneSidedLungesNeverMakeAContralateralPair() {
        for (ex in listOf(FourExercise.CROSS,FourExercise.SIDE)) {
            val a = Run(ex); val sc = SideStepCounter(2, strictUnknown=true); a.ready()
            repeat(3) { a.lunge(ex,StepSide.LEFT) }
            a.events.forEach { sc.offer(it.tMs,it.side,false) }
            assertEquals(ex.title,0,sc.track.pairs); assertFalse(sc.done(false)); assertEquals(1,sc.track.extra)
            a.events.clear(); repeat(2) { a.lunge(ex,StepSide.RIGHT) }
            a.events.forEach { sc.offer(it.tMs,it.side,false) }
            assertEquals(2,sc.track.pairs); assertTrue(sc.done(false))
        }
    }
    @Test fun unknownLungeIsRecordedButNeverGuessedIntoOtherSide() {
        val a = Run(FourExercise.CROSS); a.ready(); a.lunge(FourExercise.CROSS,StepSide.LEFT,named=false)
        assertEquals(1,a.counter.reps); assertNull(a.events.single().side)
        val sc = SideStepCounter(1,true); sc.offer(1,StepSide.LEFT,false); val e=sc.offer(2,null,false)
        assertEquals(1,sc.track.unknown); assertEquals(0,sc.track.pairs); assertFalse(e.track.counted); assertNull(e.track.switchTo)
    }
    @Test fun incompleteAndPulsingMotionNeverFlushAtSetEnd() {
        val a=Run(FourExercise.SIDE); a.ready(); a.feed(frame(kneeL=90f,x=.9f),3)
        repeat(4) { a.feed(frame(kneeL=135f,x=.9f)); a.feed(frame(kneeL=90f,x=.9f)) }
        assertEquals(0,a.counter.reps); assertNotNull(a.counter.pendingAtSetEnd().inProgress)
        a.feed(frame(),2); assertEquals(1,a.counter.reps)
    }
    @Test fun minSideSwitchWithFeetStillWideDoesNotManufactureACycle() {
        val a=Run(FourExercise.SIDE); a.ready()
        a.feed(frame(kneeL=90f,x=.9f),3); a.feed(frame(x=.9f),3); a.feed(frame(kneeR=90f,x=.9f),3)
        assertEquals(0,a.counter.reps)
    }
    @Test fun squatIsNotASideOrCrossLunge() {
        for(ex in listOf(FourExercise.CROSS,FourExercise.SIDE)) {
            val a=Run(ex); a.ready(); a.feed(frame(kneeL=90f,kneeR=90f),3); a.feed(frame(),2)
            assertEquals(0,a.counter.reps); assertEquals("four_identity_unknown",a.counter.rejectedReps.single().feature)
        }
    }
    @Test fun forwardCrossAndFixedFeetAreOutsideTheStepProfile() {
        val a=Run(FourExercise.CROSS); a.ready()
        // 상대 발목 배치는 같아도 앞발이 움직인 전방 교차다. 후방 교차로 세지 않는다.
        val forward=frame(kneeL=95f,kneeR=95f,x=-.18f,z=.5f)+mapOf("four_img_x_L" to .3f,"four_img_y_L" to .7f,"four_img_x_R" to .4f,"four_img_y_R" to .8f)
        a.feed(forward,3); a.feed(frame(),2); assertEquals(0,a.counter.reps)
        val b=Run(FourExercise.SIDE); b.ready()
        b.feed(frame(kneeL=90f,x=.9f)+("four_img_x_L" to .6f),3); b.feed(frame(),2)
        assertEquals(0,b.counter.reps)
    }
    @Test fun heelCurlWithoutThighLiftDoesNotCount() {
        val a=Run(FourExercise.KNEE_UP); a.ready(); a.feed(frame(kneeL=70f),4); a.feed(frame(),3)
        assertEquals(0,a.counter.reps)
    }
    @Test fun simultaneousDoubleLiftAndImageContradictionDoNotCount() {
        val a=Run(FourExercise.KNEE_UP); a.ready(); a.feed(frame(l=90f,r=90f),3); a.feed(frame(),2)
        assertEquals(0,a.counter.reps); assertEquals(2,a.counter.rejectedReps.size)
        val b=Run(FourExercise.KNEE_UP); b.ready(); b.feed(frame(l=90f)+(Lunge2d.KNEE_H2D to -.5f),3); b.feed(frame(),2)
        assertEquals(0,b.counter.reps)
    }
    @Test fun switchingWorkingSideBeforeReturnLeavesSideUnknown() {
        val a=Run(FourExercise.SIDE); a.ready()
        a.feed(frame(kneeL=95f,x=.9f),3); a.feed(frame(kneeR=90f,x=.9f),3); a.feed(frame(),2)
        assertEquals(1,a.counter.reps); assertNull(a.events.single().side)
    }
    @Test fun dropoutPauseAndNameFlipCannotBridgeUnobservedMotion() {
        for(mode in 0..3) {
            val a=Run(FourExercise.KNEE_UP); a.ready(); a.lift(StepSide.LEFT)
            a.feed(frame(r=100f),3)
            when(mode) {
                0 -> a.feed(emptyMap())
                1 -> a.counter.resetCycle()
                2 -> { a.t+=1000; a.feed(frame()) }
                else -> a.feed(frame(r=100f)+("four_axis_x" to -1f))
            }
            a.feed(frame(),5); assertEquals("mode=$mode",1,a.counter.reps)
            a.lift(StepSide.RIGHT); assertEquals(2,a.counter.reps)
        }
    }
    @Test fun sideCrunchAllowsSideBendButWaitsForItsReturn() {
        val a=Run(FourExercise.SIDE_CRUNCH); a.ready()
        a.feed(frame(l=100f,roll=30f),3); a.feed(frame(roll=25f),3)
        assertEquals(0,a.counter.reps); a.feed(frame(),2); assertEquals(1,a.counter.reps)
        assertFalse(FourExerciseForm.checks(FourExercise.SIDE_CRUNCH).any { it.feature=="four_abs_roll" })
    }
    @Test fun preparationSeedCountsImmediateFirstDepartureAndReplays() {
        val a=Run(FourExercise.KNEE_UP)
        a.counter.standingSeedFrom(listOf(-600L to frame(),-400L to frame(),-200L to frame()),0L)
        val seed=a.counter.fourTracker!!.seed!!
        a.lift(StepSide.LEFT); assertEquals(1,a.counter.reps)
        val b=Run(FourExercise.KNEE_UP); b.counter.fourTracker!!.restoreSeed(seed); b.lift(StepSide.LEFT)
        assertEquals(a.events,b.events)
    }
    @Test fun formNeedsViewSideAndEnoughObservedPeakFrames() {
        val a=Run(FourExercise.SIDE_CRUNCH)
        fun assess(side:StepSide?, frames:List<Pair<Long,Map<String,Float>>>):RepFormRep {
            val rf=RepFormSpecs.evaluatorFor(FourExercise.SIDE_CRUNCH.title,a.counter)!!
            frames.forEach { rf.onFrame(it.first,it.second) }; return rf.onCycle(1800,90f,175f,0,side)
        }
        val fs=(0..9).map { it*200L to frame(l=90f,roll=30f) }
        assertTrue(assess(null,fs).outcomes.all { it.verdict==Verdict.ABSTAIN })
        assertTrue(assess(StepSide.LEFT,fs.map { it.first to (it.second-ViewEstimator.FEAT_COS_SH-ViewEstimator.FEAT_SIN_SH) }).outcomes.all { it.verdict==Verdict.ABSTAIN })
        val r=assess(StepSide.LEFT,fs)
        assertTrue(r.outcomes.any { it.verdict==Verdict.OK }); assertFalse(r.judged); assertTrue(r.correct)
        val hidden=assess(StepSide.LEFT,fs.map { it.first to (it.second-"four_hand_head_R") })
        assertEquals(Verdict.ABSTAIN,hidden.outcomes.first { it.check.feature=="four_hand_head_R" }.verdict)
    }
    @Test fun allNewChecksAreBetaAndNeverGateRepetitions() {
        for(ex in FourExercise.entries) {
            assertFalse(RepSignals.byExercise.getValue(ex.title).validated)
            assertNull(RepSignals.byExercise.getValue(ex.title).romThreshold)
            assertTrue(FourExerciseForm.checks(ex).all { it.status==RuleStatus.BETA && !it.gates })
        }
    }
}
