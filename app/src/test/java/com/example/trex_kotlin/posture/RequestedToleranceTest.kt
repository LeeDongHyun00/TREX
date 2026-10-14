package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** 정상 변형은 유지하고 큰 이탈만 제외한다. 허용 범위와 실제 카운트 완료를 함께 확인한다. */
class RequestedToleranceTest {
    private val raises=listOf("프런트 레이즈","사이드 레터럴 레이즈")
    private val wave=listOf(175f,175f,175f,175f,160f,140f,115f,95f,90f,95f,115f,140f,160f,175f,175f,175f)
    private fun view(yaw:Float)=mapOf(ViewEstimator.FEAT_COS to cos(Math.toRadians(yaw.toDouble())).toFloat(),
        ViewEstimator.FEAT_SIN to sin(Math.toRadians(yaw.toDouble())).toFloat())
    private fun base(signal:Float)=view(-30f)+mapOf("upperarm_vert_mean" to signal,"upperarm_vert_L" to signal,
        "upperarm_vert_R" to signal,"forearm_vert_mean" to 155f,"elbow_L" to 125f,"elbow_R" to 125f,
        "torso_pitch" to 0f,"knee_L" to 175f,"knee_R" to 175f,"raise_head_turn" to 0f,"raise_head_pitch" to 0f)
    private fun rep(ex:String,extra:(Int)->Map<String,Float>):RepFormRep {
        val feature=RepSignals.byExercise.getValue(ex).feature
        val ev=RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex),feature,if(ex=="푸시업").3f else 35f)
        val signals=if(ex=="푸시업")wave.map{.6f+(it-90f)/85f*.8f} else wave
        signals.forEachIndexed { i,s -> ev.onFrame(i*300L,base(wave[i])+mapOf(feature to s)+extra(i)) }
        return ev.onCycle(signals.lastIndex*300L,signals.min(),signals.max(),900L)
    }
    private fun RepFormRep.feature(key:String)=outcomes.first{it.check.feature==key}

    @Test fun bothRaisesUseUpperArmSoBentElbowsDoNotLoseRepetitions() {
        for(ex in raises) {
            val rc=RepCounter.forSession(ex,floor=false)!!
            assertEquals("upperarm_vert_mean",rc.signal.feature)
            var time=0L
            repeat(3) {
                wave.forEach { arm -> rc.onFrame(time,base(arm)[rc.signal.feature]);time+=300L }
            }
            assertEquals(ex,3,rc.reps)
        }
    }
    @Test fun lowerReturnPositionWithinNaturalMarginCompletesEveryRepetition() {
        for(ex in raises) {
            val rc=RepCounter.forSession(ex,floor=false)!!;var time=0L
            repeat(3) { n ->
                wave.forEachIndexed { i,v ->
                    val arm=if(i>=12 || (n>0 && i<=3))158f else v
                    rc.onFrame(time,arm);time+=300L
                }
            }
            assertEquals(ex,3,rc.reps)
        }
    }
    @Test fun stillRaisedArmsAndSmallFidgetAreNotCompleteRepetitions() {
        for(ex in raises) {
            val incomplete=RepCounter.forSession(ex,floor=false)!!
            wave.forEachIndexed { i,v -> incomplete.onFrame(i*300L,if(i>=11)130f else v) }
            assertEquals(0,incomplete.reps)
            val idle=RepCounter.forSession(ex,floor=false)!!
            repeat(40){idle.onFrame(it*300L,if(it%4==0)163f else 175f)}
            assertEquals(0,idle.reps)
        }
    }
    @Test fun elbowOnlyCurlNoLongerDrivesRaiseCounter() {
        val ex=raises.last();val rc=RepCounter.forSession(ex,floor=false)!!
        wave.forEachIndexed { i,v -> val f=base(175f)+("forearm_vert_mean" to v);rc.onFrame(i*300L,f[rc.signal.feature]) }
        assertEquals(0,rc.reps)
    }
    @Test fun hiddenArmIsNotAStaleAnchorAndNoArmsIsMissingSignal() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){1f};vis[14]=0f
        val rc=RepCounter.forSession(raises.last(),floor=false)!!
        repeat(3) { n -> wave.forEachIndexed { i,v ->
            val f=RequestedFormFeatures.augment(raises.last(),base(v)+("upperarm_vert_R" to 175f),xy,vis,1000,1000)
            assertEquals(v,f.getValue("upperarm_vert_mean"),.001f)
            rc.onFrame((n*wave.size+i)*300L,f[rc.signal.feature])
        } }
        assertEquals(3,rc.reps)
        vis[13]=0f
        assertFalse(RequestedFormFeatures.augment(raises.last(),base(90f),xy,vis,1000,1000).containsKey("upperarm_vert_mean"))
    }
    @Test fun onlyRequestedRaisesHaveWiderReturnTolerance() {
        for((ex,sig) in RepSignals.byExercise)
            if(ex in raises) assertEquals(18f,sig.returnTolerance!!,.001f) else assertNull(ex,sig.returnTolerance)
        assertEquals(18f,RepEngineLog.of(RepCounter.forSession(raises.first(),floor=false)!!).returnTolerance!!,.001f)
        assertNull(RepEngineLog.of(RepCounter.forSession("바벨 스쿼트",floor=false)!!).returnTolerance)
    }
    @Test fun comfortableElbowBendSmallKneeMotionAndDiagonalVariationDoNotExclude() {
        for(ex in raises) {
            val r=rep(ex){i->mapOf("elbow_L" to if(i in 6..10)105f else 150f,
                "knee_L" to if(i in 6..10)150f else 175f,"torso_pitch" to if(i in 6..10)-18f else 0f,
                "raise_front_plane_L" to 42f,"raise_lateral_plane_L" to -22f,
                "raise_shoulder_fwd_L" to if(i in 6..10).22f else 0f)}
            assertFalse(ex,RequestedFormCoaching.excluded(r))
        }
    }
    @Test fun shoulderHeightIsARangeRatherThanExactNinetyDegrees() {
        for(ex in raises) for(angle in listOf(70f,90f,110f,120f)) {
            val r=rep(ex){i->if(i in 6..10)mapOf("upperarm_vert_L" to angle,"upperarm_vert_R" to angle) else emptyMap()}
            assertFalse("$ex $angle",RequestedFormCoaching.excluded(r))
        }
        for(ex in raises) assertTrue(RequestedFormCoaching.excluded(rep(ex){mapOf("upperarm_vert_L" to 145f)}))
    }
    @Test fun ordinaryShoulderMovementPassesAndClearlyLargeShrugStillExcludes() {
        for(ex in raises) {
            val mild=rep(ex){i->mapOf("raise_shoulder_lift_L" to if(i in 6..10)-.23f else -.4f)}
            val severe=rep(ex){i->mapOf("raise_shoulder_lift_L" to if(i in 6..10)-.07f else -.4f)}
            assertFalse(RequestedFormCoaching.excluded(mild));assertTrue(RequestedFormCoaching.excluded(severe))
        }
    }
    @Test fun headForwardIsBodyRelativeAndDoesNotRequireFacingTheCamera() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){1f}
        for(yaw in listOf(-35f,35f)) {
            val f=RaisePhotoGeometry.features(xy,vis,1000,1000,view(yaw)+mapOf("head_yaw" to 0f,"head_pitch" to 0f))
            assertEquals(0f,f.getValue("raise_head_turn"),.001f)
        }
        for(ex in raises) assertFalse(RequestedFormCoaching.excluded(rep(ex){mapOf("raise_head_turn" to 30f)}))
    }
    @Test fun sustainedBigHeadTurnIsSharedByBothRaisesButBriefGlanceIsAllowed() {
        for(ex in raises) {
            assertTrue(RequestedFormCoaching.excluded(rep(ex){mapOf("raise_head_turn" to 85f)}))
            assertFalse(RequestedFormCoaching.excluded(rep(ex){i->mapOf("raise_head_turn" to if(i==8)85f else 0f)}))
        }
    }
    @Test fun missingFaceDoesNotProduceAForwardFacingVerdict() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){1f};vis[8]=0f
        val f=RaisePhotoGeometry.features(xy,vis,1000,1000,view(0f)+mapOf("head_yaw" to 0f,"head_pitch" to 0f))
        assertFalse(f.containsKey("raise_head_turn"))
        assertTrue(RequestedFormState(raises.first()).onFrame(1,f).scopeNote!!.contains("고개 방향 미확인"))
    }
    @Test fun pushupSmallNaturalOffsetsPassButKneeSupportAndLargeBodyCollapseDoNot() {
        val mild=rep("푸시업"){view(-75f)+mapOf("pushup_hip_offset_L" to -.09f,"pushup_head_pitch_L" to 32f,
            "pushup_neck_pitch_L" to 40f,"pushup_hand_width" to 1.9f,"pushup_knee_support_L" to 0f)}
        assertFalse(RequestedFormCoaching.excluded(mild))
        assertTrue(RequestedFormCoaching.excluded(rep("푸시업"){view(-75f)+mapOf("pushup_knee_support_L" to 1f)}))
        assertTrue(RequestedFormCoaching.excluded(rep("푸시업"){view(-75f)+mapOf("pushup_hip_offset_L" to -.25f)}))
    }
    @Test fun goodMorningDoesNotDemandClockDirectionOrExactKneeAndFootAngles() {
        val relaxed=rep("굿모닝"){i->view(-75f)+mapOf("goodmorning_knee_side_L" to 170f,
            "goodmorning_hip_height_L" to if(i in 6..10)1.2f else 1.8f)}
        assertFalse(RequestedFormCoaching.excluded(relaxed))
        val lowHips=rep("굿모닝"){i->view(-75f)+mapOf("goodmorning_knee_side_L" to 140f,
            "goodmorning_hip_height_L" to if(i in 6..10).8f else 1.8f)}
        assertFalse(RequestedFormCoaching.excluded(lowHips))
        assertFalse(RequestedFormCoaching.excluded(rep("굿모닝"){view(-75f)+mapOf("goodmorning_knee_side_L" to 85f)}))
        val foot=rep("굿모닝"){view(0f)+mapOf("goodmorning_stance" to 1.9f,"goodmorning_toe_L" to 25f)}
        assertFalse(RequestedFormCoaching.excluded(foot))
    }
}
