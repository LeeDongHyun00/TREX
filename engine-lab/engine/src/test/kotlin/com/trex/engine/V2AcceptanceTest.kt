package com.trex.engine

import org.junit.Assert.*
import org.junit.Test

/** 합성 궤적의 사건 계약만 검증한다. 사람의 자세 정확도나 안전성을 측정한 시험이 아니다. */
class V2AcceptanceTest {
    private class Feed(name: String="바벨 스쿼트", goal: RangeGoal?=null,
        pattern: RepMovementPattern?=null, floorStart: Boolean=false) {
        val p=MovementContracts.deadliftStart(ExerciseRepProfiles.forExercise(name)!!,floorStart)
        val engine=LabEngine(p,pattern ?: p.defaultPattern,goal)
        var t=0L
        var output: EngineOutput?=null
        fun send(f:Map<String,Float>,quality:Boolean=true, confirmed:Boolean=true): EngineOutput {
            output=engine.process(t,mapOf("view_cos" to 1f,"view_sin" to 0f,"body_horizontal" to .9f)+f,quality,confirmed)
            t+=200;return output!!
        }
        fun knee(v:Float)=send(mapOf("knee_mean" to v))
        fun ready(v:Float=175f) {repeat(20){knee(v)}}
        fun squat(bottom:Float=90f) { listOf(160f,140f,bottom,bottom,120f,150f,165f,175f).forEach(::knee) }
    }
    @Test fun c01Exactly26AndAliasesDoNotCreateExtraExercises() {
        assertEquals(26,ExerciseCatalog.profiles.map{it.exercise}.distinct().size)
        assertEquals("바벨 스쿼트",ExerciseCatalog.canonical("기본 스쿼트"))
        assertNull(ExerciseCatalog.canonical("버피"))
        assertNull(ExerciseCatalog.canonical("벽 푸쉬업"))
    }
    @Test fun r01BottomToMiddleDoesNotBecomeAStandingSquat() {
        val f=Feed();f.ready(90f)
        repeat(3){listOf(105f,120f,130f,130f,120f,105f,90f).forEach(f::knee)}
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun r02ObservedAndUserChosenRangeAreSeparate() {
        val f=Feed(goal=RangeGoal(70f));f.ready();f.squat(130f)
        assertEquals(1,f.output!!.counts.total);assertEquals(0,f.output!!.rangeMet)
        f.squat(90f)
        assertEquals(2,f.output!!.counts.total);assertEquals(1,f.output!!.rangeMet)
        assertTrue(f.output!!.events.flatMap{it.cycles.values}.all{it.valid==null})
    }
    @Test fun noGoalDoesNotInventPartialFailures() {
        val f=Feed();f.ready();f.squat(130f)
        assertEquals(1,f.output!!.counts.total);assertNull(f.output!!.rangeMet)
    }
    @Test fun r03LastReturnCountsWithoutTheNextDescent() {
        val f=Feed();f.ready();f.squat();assertEquals(1,f.output!!.counts.total)
        repeat(20){f.knee(175f)};assertEquals(1,f.output!!.counts.total)
    }
    @Test fun r04SingleSpikeAndSmallBouncesAreNotReps() {
        val f=Feed();f.ready();listOf(170f,100f,175f,170f,165f,175f).forEach(f::knee)
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun r05MissingSignalDiscardsHalfCycle() {
        val f=Feed();f.ready();listOf(150f,100f,90f).forEach(f::knee);f.send(emptyMap())
        listOf(120f,150f,175f).forEach(f::knee);assertEquals(0,f.output!!.counts.total)
    }
    @Test fun c09EpochInterruptPreservesCompletedCountAndDiscardsPending() {
        val f=Feed();f.ready();f.squat();f.knee(140f);f.knee(90f);f.engine.interrupt()
        repeat(20){f.knee(175f)};assertEquals(1,f.output!!.counts.total)
        f.squat();assertEquals(2,f.output!!.counts.total)
    }
    @Test fun r06WaitingTimeCannotSubsidizeAFastMotion() {
        val tracker=ReturnRepTracker(35f)
        repeat(30){tracker.onFrame(it*200L,175f)}
        val result=listOf(120f,90f,90f,120f,175f).mapIndexed { i,v->tracker.onFrame(6000+i*50L,v) }
        assertTrue(result.all{it==null})
    }
    @Test fun r07HandOnlyAndR08KneeOnlyNeverMakeAHipHinge() {
        val f=Feed("바벨 데드리프트")
        repeat(50){f.send(mapOf("hip_mean" to 170f,"torso_incl" to 0f,"palm_h_sh" to (it%10).toFloat(),"knee_mean" to if(it%2==0)90f else 170f))}
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun deadliftHipAngleWithoutTorsoMotionIsInsufficient() {
        val f=Feed("바벨 데드리프트")
        repeat(20){f.send(mapOf("hip_mean" to 170f,"torso_incl" to 0f))}
        for(v in listOf(150f,120f,90f,90f,120f,150f,170f)) f.send(mapOf("hip_mean" to v,"torso_incl" to 0f))
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun declaredDeadliftFloorStartUsesOppositePhase() {
        val f=Feed("바벨 데드리프트",floorStart=true)
        repeat(20){f.send(mapOf("hip_mean" to 100f,"torso_incl" to 60f))}
        for(v in listOf(120f,145f,170f,170f,150f,120f,100f)) f.send(mapOf("hip_mean" to v,"torso_incl" to (170-v)))
        assertEquals(1,f.output!!.counts.total)
    }
    @Test fun r11BothLungeKneesAreOneCycle() {
        val f=Feed("런지");f.ready();f.squat();assertEquals(1,f.output!!.counts.total)
        assertEquals(1,f.output!!.counts.unknown);assertEquals(0,f.output!!.counts.left)
    }
    @Test fun r12PushupCollapseIsNotARepAndR13ArmMovementNeedsElbowMotion() {
        for(collapsed in listOf(true,false)) {
            val f=Feed("푸시업")
            repeat(10){f.send(mapOf("wrist_shoulder_d" to .8f,"visible_elbow_angle" to 170f))}
            for(v in listOf(.6f,.4f,if(collapsed).02f else .2f,.2f,.4f,.6f,.8f))
                f.send(mapOf("wrist_shoulder_d" to v,"visible_elbow_angle" to if(collapsed)80f+v*100 else 170f))
            assertEquals(0,f.output!!.counts.total)
        }
    }
    @Test fun r14NeckNoddingDoesNotMakeACrunch() {
        val f=Feed("크런치")
        repeat(40){f.send(mapOf("torso_ground_angle" to 5f,"head_ground" to if(it%2==0).8f else 0f))}
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun r15WrongRaisePlaneDoesNotCount() {
        val f=Feed("프런트 레이즈")
        fun arm(v:Float)=f.send(mapOf("upperarm_vert_L" to v,"upperarm_vert_R" to v,"raise_lateral_L" to .95f,"raise_lateral_R" to .95f))
        repeat(20){arm(175f)};listOf(150f,120f,90f,90f,120f,150f,175f).forEach(::arm)
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun r16KneeOnlySideCrunchNeedsTorsoAndElbowApproach() {
        val f=Feed("스탠딩 사이드 크런치",pattern=RepMovementPattern.LEFT_ONLY)
        for(i in 0..45) f.send(mapOf("knee_h_L" to if(i%12<6)-.5f else 0f,"torso_roll" to 0f,"knee_elbow_dist_L" to 1f))
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun r17ScissorIdentityJumpResetsCycle() {
        val f=Feed("시저크로스")
        repeat(10){f.send(mapOf("knee_gap2d" to .6f,"scissor_signed" to .3f))}
        listOf(.4f,.2f,.05f,.2f,.4f,.6f).forEachIndexed { i,v->
            f.send(mapOf("knee_gap2d" to v,"scissor_signed" to if(i<2).7f else -.7f))
        }
        assertEquals(0,f.output!!.counts.total)
    }
    @Test fun r18PlankNeedsConfirmationAndCannotBridgeLoss() {
        val f=Feed("플랭크");val pose=mapOf("hip_dev_ankle" to .1f,"knee_ang" to 170f)
        repeat(10){f.send(pose,confirmed=false)};assertEquals(0L,f.output!!.observedHoldMs)
        repeat(15){f.send(pose)};val held=f.output!!.observedHoldMs
        assertTrue(held>0);f.send(emptyMap());f.t+=4000;f.send(pose)
        assertEquals(held,f.output!!.observedHoldMs);assertEquals(0,f.output!!.counts.total)
    }
    @Test fun c05AndR19StaleOrUnknownCannotPublishCurrentMetrics() {
        val f=Feed();f.ready();f.send(mapOf("knee_mean" to 100f,"torso_incl" to 40f),quality=false)
        assertEquals("UNOBSERVABLE",f.output!!.phase);assertTrue(f.output!!.measurements.values.all{it==null})
        assertEquals("RESEARCH_MEASUREMENTS_ONLY",f.output!!.formStatus)
    }
    @Test fun r20RightMovementDoesNotCountAsLeft() {
        val f=Feed("덤벨 컬",pattern=RepMovementPattern.LEFT_ONLY)
        repeat(40){f.send(mapOf("elbow_L" to 170f,"elbow_R" to if(it%12<6)60f else 170f))}
        assertEquals(0,f.output!!.counts.total)
    }
}
