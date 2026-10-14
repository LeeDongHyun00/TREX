package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** 정상 복귀가 앞서 관측한 오류를 지우는 경우와 일시적인 추적 잡음을 구분한다. */
class RequestedFormEvidenceTest {
    private val wave=listOf(175f,175f,175f,175f,160f,140f,115f,95f,90f,95f,115f,140f,160f,175f,175f,175f)
    private fun view(yaw:Float)=mapOf(ViewEstimator.FEAT_COS to cos(Math.toRadians(yaw.toDouble())).toFloat(),
        ViewEstimator.FEAT_SIN to sin(Math.toRadians(yaw.toDouble())).toFloat())
    private fun rep(ex:String,yaw:Float=-75f,step:Long=300,signal:List<Float> = wave, frames:(Int)->Map<String,Float>):RepFormRep {
        val key=when(ex){"굿모닝"->"hip_mean";"푸시업"->"wrist_shoulder_d";else->"upperarm_vert_mean"}
        val ev=RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex),key,35f)
        signal.forEachIndexed { i,v -> ev.onFrame(i*step,view(yaw)+mapOf(key to v)+frames(i)) }
        return ev.onCycle(signal.lastIndex*step,signal.min(),signal.max(),3*step)
    }
    private fun RepFormRep.out(key:String)=outcomes.first { it.check.feature==key }
    private fun fault(ex:String,key:String,normal:Float,bad:Float,range:IntRange=6..8,yaw:Float=-75f,step:Long=300)=
        rep(ex,yaw,step){i->mapOf(key to if(i in range)bad else normal)}

    @Test fun partialKneeSupportCannotBeErasedByFinishingWithStraightLegs() {
        assertTrue(RequestedFormCoaching.excluded(fault("푸시업","pushup_knee_support_L",0f,1f)))
    }
    @Test fun partialHipSagAndCameraTurnAreNotDilutedByNormalReturn() {
        for((key,normal,bad) in listOf(Triple("pushup_hip_offset_L",0f,-.3f),Triple("pushup_face_yaw",0f,80f)))
            assertTrue(key,RequestedFormCoaching.excluded(fault("푸시업",key,normal,bad)))
    }
    @Test fun handWidthThatChangesDuringThePushupIsStillChecked() {
        assertTrue(RequestedFormCoaching.excluded(fault("푸시업","pushup_hand_width",1.2f,.2f,yaw=0f)))
    }
    @Test fun raisingShouldersBeforeThePeakDoesNotEscapeTheShoulderCheck() {
        for(ex in listOf("프런트 레이즈","사이드 레터럴 레이즈")) {
            assertTrue(ex,RequestedFormCoaching.excluded(fault(ex,"raise_shoulder_gap_L",.4f,.05f,4..6)))
            assertTrue(ex,RequestedFormCoaching.excluded(fault(ex,"raise_shoulder_lift_L",-.5f,-.1f,4..6)))
        }
    }
    @Test fun sustainedHeadTurnDuringEitherRaiseBlocksButCoachingBandDoesNot() {
        for(ex in listOf("프런트 레이즈","사이드 레터럴 레이즈")) {
            assertTrue(RequestedFormCoaching.excluded(fault(ex,"raise_head_turn",0f,80f)))
            assertFalse(RequestedFormCoaching.excluded(fault(ex,"raise_head_turn",0f,60f)))
        }
    }
    @Test fun handsLoweredOnlyDuringGoodMorningBendStillExclude() {
        assertTrue(RequestedFormCoaching.excluded(fault("굿모닝","goodmorning_hand_head_L",.3f,1.4f)))
    }
    @Test fun feetTurnedAfterStandingAreCheckedInTheSameRepetition() {
        assertTrue(RequestedFormCoaching.excluded(fault("굿모닝","goodmorning_toe_L",0f,65f,yaw=0f)))
    }
    @Test fun oneFrameAndBriefAdjustmentsRemainAllowed() {
        val cases=listOf(Triple("푸시업","pushup_knee_support_L",1f),Triple("프런트 레이즈","raise_head_turn",80f),
            Triple("굿모닝","goodmorning_hand_head_L",1.4f))
        for((ex,key,bad) in cases) {
            assertFalse(ex,RequestedFormCoaching.excluded(fault(ex,key,0f,bad,8..8)))
            assertFalse(ex,RequestedFormCoaching.excluded(fault(ex,key,0f,bad,6..8,step=60)))
        }
    }
    @Test fun separateBadFramesAndOppositeDeviationsAreNotJoined() {
        val isolated=rep("푸시업"){ i-> mapOf("pushup_hip_offset_L" to if(i in listOf(5,8,11))-.3f else 0f) }
        val opposite=rep("푸시업"){ i-> mapOf("pushup_hip_offset_L" to if(i in 4..12) {if(i%2==0)-.3f else .35f} else 0f) }
        assertFalse(RequestedFormCoaching.excluded(isolated));assertFalse(RequestedFormCoaching.excluded(opposite))
    }
    @Test fun missingFrameBreaksEvidenceButDoesNotEraseAlreadyConfirmedFault() {
        val broken=rep("푸시업"){i-> if(i in listOf(6,8,10))mapOf("pushup_knee_support_L" to 1f) else emptyMap()}
        assertFalse(RequestedFormCoaching.excluded(broken))
        val confirmed=rep("푸시업"){i-> if(i in 6..8)mapOf("pushup_knee_support_L" to 1f) else emptyMap()}
        assertTrue(RequestedFormCoaching.excluded(confirmed))
        assertFalse(RequestedFormCoaching.observed(rep("푸시업"){emptyMap()}))
    }
    @Test fun timestampsDoNotBridgeLongTrackingGaps() {
        val r=fault("푸시업","pushup_knee_support_L",0f,1f,6..8,step=1000)
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun relativeShoulderEvidenceCannotUseReferenceAcrossOcclusion() {
        val r=rep("프런트 레이즈"){i->when(i){
            in 0..3 -> mapOf("raise_shoulder_lift_L" to -.5f)
            in 7..9 -> mapOf("raise_shoulder_lift_L" to -.1f)
            else -> emptyMap()
        }}
        assertEquals(Verdict.ABSTAIN,r.out("raise_shoulder_lift_L").verdict)
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun confirmedRelativeFaultIsKeptWhenTrackingIsLostAfterIt() {
        val r=rep("프런트 레이즈"){i->when(i){
            in 0..3 -> mapOf("raise_shoulder_lift_L" to -.5f)
            in 4..6 -> mapOf("raise_shoulder_lift_L" to -.1f)
            else -> emptyMap()
        }}
        assertTrue(RequestedFormCoaching.excluded(r))
    }
    @Test fun kneeAndHipViolationsAtDifferentTimesCannotCombineIntoSquat() {
        val extended=listOf(175f,175f,175f,175f,160f,140f,110f,100f,90f,90f,100f,110f,140f,160f,175f,175f)
        val r=rep("굿모닝",signal=extended){i->mapOf(
            "goodmorning_knee_side_L" to if(i in 6..8)90f else 160f,
            "goodmorning_hip_height_L" to if(i in 9..11)1.0f else 2.0f)}
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun shortButConfirmedSimultaneousSquatAtBottomIsNotAveragedAway() {
        val r=rep("굿모닝"){i->mapOf(
            "goodmorning_knee_side_L" to if(i in 7..8)100f else 160f,
            "goodmorning_hip_height_L" to if(i in 7..8)1f else 2f)}
        assertTrue(RequestedFormCoaching.excluded(r))
    }
    @Test fun oppositeLegAndUnknownReferenceDoNotProveSquat() {
        val r=rep("굿모닝"){i->mapOf("goodmorning_knee_side_L" to 100f,"goodmorning_hip_height_R" to if(i in 6..10)1f else 2f)}
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun nominalSideViewStillChecksRaiseHeightAndHeadTurn() {
        for(ex in listOf("프런트 레이즈","사이드 레터럴 레이즈")) for(yaw in listOf(-90f,90f)) {
            val r=rep(ex,yaw){mapOf("upperarm_vert_L" to 145f,"raise_head_turn" to 80f)}
            assertEquals(Verdict.VIOLATION,r.out("upperarm_vert_L").verdict)
            assertTrue(RequestedFormCoaching.excluded(r))
        }
    }
    @Test fun staleViewLockCannotHideCurrentlyObservedFootError() {
        val ex="굿모닝";val ev=RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex),"hip_mean",35f)
        fun next(offset:Long,yaw:Float):RepFormRep {
            wave.forEachIndexed { i,v->ev.onFrame(offset+i*300L,view(yaw)+mapOf("hip_mean" to v,"goodmorning_toe_L" to 65f)) }
            return ev.onCycle(offset+wave.lastIndex*300L,90f,175f,offset+900L)
        }
        repeat(3){next(it*6000L,30f)}
        assertTrue(RequestedFormCoaching.excluded(next(18000L,0f)))
    }
    @Test fun severeTrunkLeanBetweenEndpointsStillExcludesRaise() {
        for(ex in listOf("프런트 레이즈","사이드 레터럴 레이즈"))
            assertTrue(RequestedFormCoaching.excluded(fault(ex,"torso_pitch",0f,-60f,4..6,yaw=-30f)))
    }
    @Test fun staleSupportFlagsAreRemovedWhenTheirInputJointsDisappear() {
        for(ready in listOf(0f,1f)) {
            val f=PushupFormSpecs.withSupport(mapOf("pushup_side_ready" to ready,"pushup_knee_support_L" to 1f))
            assertFalse(f.containsKey("pushup_knee_support_L"))
        }
    }
    @Test fun completedCounterCycleUsesTheSameExclusionForProgressAndLog() {
        val ex="푸시업";val rc=RepCounter.forSession(ex,"min",.7101f,floor=true)!!
        val ev=RepFormSpecs.evaluatorFor(ex,rc)!!
        val signal=listOf(1.4f,1.4f,1.4f,1.4f,1.2f,1f,.8f,.6f,.6f,.8f,1f,1.2f,1.4f,1.4f,1.4f)
        val records=arrayListOf<RepRecord>();var state=RequestedFormState(ex);var total=0
        repeat(3){cycle->signal.forEachIndexed{i,v->
            val now=(cycle*signal.size+i)*300L
            val f=mapOf("wrist_shoulder_d" to v,"pushup_knee_support_L" to if(cycle==1 && i in 6..8)1f else 0f)
            ev.onFrame(now,f);state=state.onFrame(now,f)
            if(rc.onFrameFeatures(now,f)) {
                val tally=RepUnitAccumulator.onCounterFrame(rc,now,records,null)
                val reps=tally.records.map{ev.onCycle(it.tMs,it.cycleMin,it.cycleMax,it.cycleStartMs)}
                state=state.onReps(reps,tally.records,false,now)
                total+=tally.repsNotShort+tally.repsShort
            }
        }}
        assertEquals(3,total);assertEquals(1,state.excluded)
        assertEquals(2,RequestedFormCoaching.counted(ex,true,total,0,false,0,state.excluded))
        assertEquals(1,ev.summary().reps.count(RequestedFormCoaching::excluded))
        assertFalse(RequestedFormCoaching.excluded(ev.summary().reps.last()))
        assertTrue(ev.summary().toLog(0).toJson().contains("\"requested_coach_excluded\":true"))
    }
    @Test fun knownWrongViewDoesNotContributeToShoulderEvidence() {
        val r=fault("프런트 레이즈","raise_shoulder_gap_L",.4f,.05f,yaw=180f)
        assertEquals(Verdict.ABSTAIN,r.out("raise_shoulder_gap_L").verdict)
    }
    @Test fun noFaultLeaksToNextRepAndCoachAndTrackKeepTheirPolicies() {
        val bad=fault("푸시업","pushup_knee_support_L",0f,1f)
        val good=rep("푸시업"){mapOf("pushup_knee_support_L" to 0f)}
        assertTrue(RequestedFormCoaching.excluded(bad));assertFalse(RequestedFormCoaching.excluded(good))
        assertEquals(1,RequestedFormCoaching.counted("푸시업",true,2,0,false,0,1))
        assertEquals(2,RequestedFormCoaching.counted("푸시업",false,2,0,false,0,1))
    }
}
