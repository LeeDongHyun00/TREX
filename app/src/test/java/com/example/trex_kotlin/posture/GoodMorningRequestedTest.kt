package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** 사진의 핵심 조건과 관측 불확실성을 함께 검사한다. */
class GoodMorningRequestedTest {
    private val wave=listOf(175f,175f,175f,175f,160f,140f,115f,95f,90f,95f,115f,140f,160f,175f,175f,175f)
    private fun view(yaw:Float)=mapOf(ViewEstimator.FEAT_COS to cos(Math.toRadians(yaw.toDouble())).toFloat(),
        ViewEstimator.FEAT_SIN to sin(Math.toRadians(yaw.toDouble())).toFloat())
    private fun rep(yaw:Float=-75f,extra:(Int)->Map<String,Float>):RepFormRep {
        val ev=RepFormEvaluator(RepFormSpecs.byExercise.getValue("굿모닝"),"hip_mean",35f)
        wave.forEachIndexed { i,v -> ev.onFrame(i*300L,view(yaw)+mapOf("hip_mean" to v)+extra(i)) }
        return ev.onCycle(wave.lastIndex*300L,90f,175f,900L)
    }
    private fun RepFormRep.feature(key:String)=outcomes.first{it.check.feature==key}
    private fun squat(i:Int,side:String="L",knee:Float=120f,drop:Float=.7f)=mapOf(
        "goodmorning_knee_side_$side" to if(i in 6..10)knee else 175f,
        "goodmorning_hip_height_$side" to if(i in 6..10)1.8f-drop else 1.8f)
    private fun point(xy:FloatArray,i:Int,x:Float,y:Float) { xy[2*i]=x;xy[2*i+1]=y }
    private fun pose():Pair<FloatArray,FloatArray> {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){.1f}
        for(i in listOf(7,11,13,15,23,25,27)) vis[i]=.95f
        vis[12]=.35f
        point(xy,11,.65f,.30f);point(xy,12,.66f,.30f);point(xy,23,.4f,.5f)
        point(xy,25,.52f,.7f);point(xy,27,.5f,.9f)
        point(xy,7,.70f,.24f);point(xy,13,.52f,.25f);point(xy,15,.66f,.21f)
        return xy to vis
    }
    @Test fun kneeBendAndHipDropTogetherExcludeOnEitherSideIncludingNominalSideView() {
        for((yaw,side) in listOf(-75f to "L",75f to "R",-90f to "L",90f to "R")) {
            val r=rep(yaw){squat(it,side)}
            assertTrue("$yaw $side",RequestedFormCoaching.excluded(r))
            assertTrue(RequestedFormCoaching.message(r)!!.contains("무릎이 크게 굽혀지면서 엉덩이"))
        }
    }
    @Test fun eitherConditionAloneDoesNotExcludeOrAskForClockDirection() {
        for(r in listOf(rep{squat(it,knee=150f)},rep{squat(it,drop=.2f)})) {
            assertFalse(RequestedFormCoaching.excluded(r));assertNull(RequestedFormCoaching.message(r))
        }
    }
    @Test fun oppositeLegConditionsAndMissingReferenceCannotCombineToExclude() {
        val wrongSide=rep{ i -> squat(i)-"goodmorning_hip_height_L"+mapOf("goodmorning_hip_height_R" to if(i in 6..10)1.1f else 1.8f) }
        assertFalse(RequestedFormCoaching.excluded(wrongSide))
        val noReference=rep { i -> if(i in 6..10)squat(i) else mapOf("goodmorning_knee_side_L" to 175f) }
        assertEquals(Verdict.ABSTAIN,noReference.feature("goodmorning_hip_height_L").verdict)
        assertFalse(RequestedFormCoaching.excluded(noReference))
    }
    @Test fun oneNoisyFrameDoesNotTurnAHingeIntoASquat() {
        val r=rep{i->squat(i,knee=if(i==8)110f else 155f,drop=if(i==8).8f else .2f)}
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun photographLikeHingeHasBroadDepthToleranceButShallowNodDoesNot() {
        for(angle in listOf(40f,55f,70f,85f))
            assertFalse(RequestedFormCoaching.excluded(rep{mapOf("goodmorning_torso_incl_L" to angle)}))
        assertTrue(RequestedFormCoaching.excluded(rep{mapOf("goodmorning_torso_incl_L" to 20f)}))
    }
    @Test fun handPositionAllowsLooseClaspAndBriefAdjustmentButNotHandsKeptAtWaist() {
        for(distance in listOf(.1f,.4f,.75f,.89f))
            assertFalse(RequestedFormCoaching.excluded(rep{mapOf("goodmorning_hand_head_L" to distance)}))
        assertTrue(RequestedFormCoaching.excluded(rep{mapOf("goodmorning_hand_head_L" to 1.5f)}))
        assertFalse(RequestedFormCoaching.excluded(rep{i->mapOf("goodmorning_hand_head_L" to if(i==8)1.5f else .3f)}))
        val r=rep{emptyMap()}
        assertEquals(Verdict.ABSTAIN,r.feature("goodmorning_hand_head_L").verdict)
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun toeDirectionHasMarginAndOnlyClearlyTurnedFeetExclude() {
        for(angle in listOf(-25f,0f,25f))
            assertFalse(RequestedFormCoaching.excluded(rep(0f){mapOf("goodmorning_toe_L" to angle)}))
        for(angle in listOf(-60f,60f))
            assertTrue(RequestedFormCoaching.excluded(rep(0f){mapOf("goodmorning_toe_L" to angle)}))
        assertEquals(Verdict.ABSTAIN,rep{emptyMap()}.feature("goodmorning_toe_L").verdict)
    }
    @Test fun realGeometryMeasuresPhotoLikeDepthAndNearHeadHandAtNinetyDegrees() {
        val (xy,vis)=pose()
        val f=RequestedFormFeatures.augment("굿모닝",view(-90f),xy,vis,1000,1000)
        assertTrue(f.getValue("goodmorning_torso_incl_L") in 45f..60f)
        assertTrue(f.getValue("goodmorning_hand_head_L") < .3f)
        assertFalse(f.containsKey("goodmorning_hand_head_R"))
        assertTrue(f.containsKey("hip_mean"))
        assertFalse(RequestedFormCoaching.excluded(rep(-90f){ f-"hip_mean" }))
        point(xy,15,.4f,.5f)
        val low=RequestedFormFeatures.augment("굿모닝",view(-90f),xy,vis,1000,1000)
        assertTrue(low.getValue("goodmorning_hand_head_L")>.9f)
        assertTrue(RequestedFormCoaching.excluded(rep(-90f){low-"hip_mean"}))
    }
    @Test fun hiddenHandsDoNotReuseStaleFeaturesOrRequireFingerLandmarks() {
        val (xy,vis)=pose();vis[15]=.1f
        val f=RequestedFormFeatures.augment("굿모닝",view(-90f)+mapOf("goodmorning_hand_head_L" to 1.5f),xy,vis,1000,1000)
        assertFalse(f.containsKey("goodmorning_hand_head_L"))
        assertFalse(RequestedFormCoaching.excluded(rep(-90f){f-"hip_mean"}))
        val note=RequestedFormState("굿모닝").onFrame(1,f).scopeNote!!
        assertTrue(note.contains("손 위치 일부 미확인"));assertTrue(note.contains("손가락 깍지는 판정하지"))
    }
    @Test fun threeCyclesKeepTwoCoachRepsWhenOnlyMiddleCycleIsSquatLike() {
        val rc=RepCounter.forSession("굿모닝",floor=false)!!
        val ev=RepFormSpecs.evaluatorFor("굿모닝",rc)!!
        val records=arrayListOf<RepRecord>()
        var state=RequestedFormState("굿모닝");var notShort=0;var short=0
        repeat(3) { cycle -> wave.forEachIndexed { i,v ->
            val now=(cycle*wave.size+i)*300L
            val f=view(-75f)+mapOf("hip_mean" to v)+squat(i,knee=if(cycle==1)120f else 155f,drop=if(cycle==1).7f else .2f)
            ev.onFrame(now,f);state=state.onFrame(now,f)
            if(rc.onFrameFeatures(now,f)) {
                val tally=RepUnitAccumulator.onCounterFrame(rc,now,records,null)
                val reps=tally.records.map { ev.onCycle(it.tMs,it.cycleMin,it.cycleMax,it.cycleStartMs) }
                state=state.onReps(reps,tally.records,false,now)
                notShort+=tally.repsNotShort;short+=tally.repsShort
            }
        } }
        assertEquals(3,rc.reps);assertEquals(1,state.excluded)
        assertEquals(2,RequestedFormCoaching.counted("굿모닝",true,notShort,short,false,0,state.excluded))
        assertEquals(3,RequestedFormCoaching.counted("굿모닝",false,notShort,short,false,0,state.excluded))
    }
    @Test fun handObservationDoesNotDependOnVisibleAnkles() {
        val (xy,vis)=pose();vis[27]=.1f
        val f=RequestedFormFeatures.augment("굿모닝",emptyMap(),xy,vis,1000,1000)
        assertTrue(f.containsKey("goodmorning_hand_head_L"))
        assertFalse(f.containsKey("goodmorning_torso_incl_L"))
    }
}
