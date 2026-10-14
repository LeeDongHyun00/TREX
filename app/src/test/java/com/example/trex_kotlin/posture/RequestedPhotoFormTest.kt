package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RequestedPhotoFormTest {
    private fun view(yaw:Float)=mapOf(ViewEstimator.FEAT_COS to cos(Math.toRadians(yaw.toDouble())).toFloat(),
        ViewEstimator.FEAT_SIN to sin(Math.toRadians(yaw.toDouble())).toFloat())
    private fun rep(ex:String,yaw:Float,extra:(Int)->Map<String,Float>):RepFormRep {
        val sig=if(ex=="굿모닝")"hip_mean" else "forearm_vert_mean"
        val ev=RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex),sig,35f)
        val wave=listOf(175f,175f,175f,175f,160f,140f,115f,95f,90f,95f,115f,140f,160f,175f,175f)
        wave.forEachIndexed { i,v -> ev.onFrame(i*300L,view(yaw)+mapOf(sig to v,"upperarm_vert_L" to v,"upperarm_vert_R" to v,
            "elbow_L" to 160f,"elbow_R" to 160f,"torso_pitch" to 0f,"knee_L" to 175f,"knee_R" to 175f)+extra(i)) }
        return ev.onCycle(4200,90f,175f,900)
    }
    private fun RepFormRep.feature(key:String)=outcomes.first{it.check.feature==key}

    @Test fun goodMorningSquatLikeDropIsDifferentFromStableHeightHinge() {
        val hinge=rep("굿모닝",-75f) { mapOf("goodmorning_knee_side_L" to 150f,"goodmorning_hip_height_L" to 1.8f) }
        val squat=rep("굿모닝",-75f) { i -> mapOf("goodmorning_knee_side_L" to 90f,"goodmorning_hip_height_L" to if(i in 6..10).8f else 1.8f) }
        assertFalse(RequestedFormCoaching.excluded(hinge))
        assertEquals(Verdict.VIOLATION,squat.feature("goodmorning_hip_height_L").verdict)
        assertTrue(RequestedFormCoaching.excluded(squat))
    }
    @Test fun goodMorningFrontChecksFeetWidthToeDirectionAndKneesIndependently() {
        val r=rep("굿모닝",0f) { mapOf("goodmorning_stance" to 2.6f,"goodmorning_toe_L" to 55f,"goodmorning_knee_out_R" to -.4f) }
        for(key in listOf("goodmorning_stance","goodmorning_toe_L","goodmorning_knee_out_R"))
            assertEquals(key,Verdict.VIOLATION,r.feature(key).verdict)
        assertEquals(Verdict.ABSTAIN,r.feature("goodmorning_knee_side_L").verdict)
    }
    @Test fun goodMorningOppositeKneeCannotHideVisibleStraightLeg() {
        val r=rep("굿모닝",-30f) { mapOf("knee_minside" to 120f,"goodmorning_knee_oblique_L" to 179f) }
        assertEquals(Verdict.VIOLATION,r.feature("goodmorning_knee_oblique_L").verdict)
        assertFalse(RequestedFormCoaching.excluded(r))
    }
    @Test fun goodMorningCoachingAndCountHaveDifferentConservativeThresholds() {
        val r=rep("굿모닝",-30f) { mapOf("goodmorning_knee_oblique_L" to 179f) }
        assertEquals(Verdict.VIOLATION,r.feature("goodmorning_knee_oblique_L").verdict)
        assertFalse(RequestedFormCoaching.excluded(r))
        assertFalse(RequestedFormCoaching.message(r)!!.contains("세지 않았"))
    }
    @Test fun raiseRequiresBothUpperArmsAndDoesNotCountElbowOnlyMotionInCoach() {
        for(ex in listOf("프런트 레이즈","사이드 레터럴 레이즈")) {
            val normal=rep(ex,-30f) { emptyMap() }
            val one=rep(ex,-30f) { mapOf("upperarm_vert_R" to 175f) }
            val curl=rep(ex,-30f) { mapOf("upperarm_vert_L" to 175f,"upperarm_vert_R" to 175f) }
            assertFalse(ex,RequestedFormCoaching.excluded(normal))
            assertTrue(ex,RequestedFormCoaching.excluded(one))
            assertTrue(ex,RequestedFormCoaching.excluded(curl))
        }
    }
    @Test fun raiseInitiallyBentElbowsAndInitiallyLeaningTorsoAreNotLearnedAsNormal() {
        for(ex in listOf("프런트 레이즈","사이드 레터럴 레이즈")) {
            assertFalse(RequestedFormCoaching.excluded(rep(ex,-30f){mapOf("elbow_L" to 80f)}))
            assertTrue(RequestedFormCoaching.excluded(rep(ex,-30f){mapOf("torso_pitch" to -40f)}))
        }
    }
    @Test fun raiseOffScreenOrHiddenJointCannotPassOnWorldEstimateAlone() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){1f}
        xy[13*2]=1.3f;vis[25]=.1f
        val f=RequestedFormFeatures.augment("사이드 레터럴 레이즈",mapOf("elbow_L" to 160f,"elbow_R" to 160f,
            "upperarm_vert_L" to 90f,"knee_L" to 175f),xy,vis,1000,1000)
        assertFalse(f.containsKey("elbow_L"));assertFalse(f.containsKey("upperarm_vert_L"));assertFalse(f.containsKey("knee_L"))
        assertTrue(f.containsKey("elbow_R"))
    }
    @Test fun unrelatedExerciseFeaturesAreReturnedUnchanged() {
        val source=mapOf("elbow_L" to 160f,"knee_L" to 175f)
        for(ex in listOf("덤벨 컬","바벨 스쿼트","플랭크","니푸쉬업","스텝 포워드 다이나믹 런지"))
            assertSame(source,RequestedFormFeatures.augment(ex,source,floatArrayOf(),floatArrayOf(),0,0))
    }
    @Test fun goodMorningHiddenFootAndUnknownViewDoNotCreateStanceOrToeValues() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){1f}
        val source=view(0f)+mapOf("knee_L" to 172f,"shoulder_asym" to .1f)
        vis[27]=0f
        assertFalse(GoodMorningGeometry.features(xy,vis,1000,1000,source).containsKey("goodmorning_stance"))
        assertTrue(GoodMorningGeometry.features(xy,vis,1000,1000,emptyMap()).isEmpty())
    }
    @Test fun requestedCountDecisionIsSavedWithoutRelabelingBetaAsValidated() {
        val r=rep("프런트 레이즈",-30f){mapOf("torso_pitch" to -50f)}
        val summary=RepFormSummary(RepFormSpecs.byExercise.getValue("프런트 레이즈"),null,null,emptyList(),listOf(r),0,0)
        assertTrue(summary.toLog(0).toJson().contains("\"requested_coach_excluded\":true"))
        assertFalse(summary.hasShip); assertTrue(r.correct)
    }
}
