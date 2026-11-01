// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** §133: 실제 화면 관측 조건과 가림·투영 불일치·회전의 경계를 확인한다. */
class StandingFormGeometryTest {
    private fun fixture(change: (FloatArray,FloatArray,MutableMap<String,Float>)->Unit = {_,_,_->}, up: Vec3=Vec3(0f,1f,0f)): Map<String,Float> {
        val xy=FloatArray(66);val vis=FloatArray(33)
        fun p(i:Int,x:Float,y:Float) {xy[i*2]=x;xy[i*2+1]=y;vis[i]=1f}
        p(11,.4f,.2f);p(12,.5f,.2f);p(23,.4f,.5f);p(24,.5f,.5f)
        p(25,.42f,.7f);p(26,.52f,.7f);p(27,.4f,.9f);p(28,.5f,.9f)
        p(13,.25f,.2f);p(15,.1f,.25f);p(14,.65f,.2f);p(16,.8f,.25f)
        val b=mutableMapOf("view_cos" to .5f,"view_sin" to -.866f,FourExerciseForm.GOOD_MORNING_SIGNAL to 20f,
            "upperarm_vert_L" to 90f,"upperarm_vert_R" to 90f,"elbow_L" to 160f,"elbow_R" to 160f,
            "raise_plane_L" to 30f,"raise_plane_R" to 30f,"knee_L" to 90f,"knee_R" to 90f)
        change(xy,vis,b)
        return StandingFormGeometry.apply(b,xy,vis,1f,up)
    }
    @Test fun clearImageUsesObservedLegsInsteadOfBadWorldDepth() {
        val f=fixture()
        assertTrue(f.getValue(StandingFormGeometry.knee("L"))>165f)
        assertEquals(180f,f.getValue(FourExerciseForm.GOOD_MORNING_SIGNAL),.01f)
        assertEquals(160f,f.getValue(StandingFormGeometry.elbow("L")),.01f)
        assertEquals(30f,f.getValue(StandingFormGeometry.plane("L")),.01f)
        assertFalse(f.containsKey(StandingFormGeometry.plane("R")))
        assertEquals(90f,f.getValue("knee_L"),.01f) // 다른 종목의 원래 피처는 보존한다.
    }
    @Test fun leanIsVisibleFromShoulderAndHip() {
        val f=fixture({xy,_,_->xy[22]=.55f;xy[24]=.65f})
        assertTrue(f.getValue(StandingFormGeometry.TILT)>25f)
        assertTrue(f.getValue(FourExerciseForm.GOOD_MORNING_SIGNAL)<155f)
    }
    @Test fun hiddenKneeDoesNotReuseUnreliableWorldAngle() {
        val f=fixture({_,vis,_->vis[25]=.4f})
        assertFalse(f.containsKey(StandingFormGeometry.knee("L")))
        assertTrue(f.containsKey(StandingFormGeometry.knee("R")))
    }
    @Test fun hiddenWristAbstainsForThatElbowAndPlane() {
        val f=fixture({_,vis,_->vis[15]=.4f})
        assertFalse(f.containsKey(StandingFormGeometry.elbow("L")))
        assertFalse(f.containsKey(StandingFormGeometry.plane("L")))
    }
    @Test fun InconsistentImageAndWorldElbowAbstains() {
        val f=fixture({_,_,b->b["elbow_L"]=100f})
        assertFalse(f.containsKey(StandingFormGeometry.elbow("L")))
        assertFalse(f.containsKey(StandingFormGeometry.plane("L")))
    }
    @Test fun loweredArmsDoNotEvaluateRaisedForm() {
        val f=fixture({_,_,b->b["upperarm_vert_L"]=140f})
        assertFalse(f.containsKey(StandingFormGeometry.elbow("L")))
        assertFalse(f.containsKey(StandingFormGeometry.plane("L")))
    }
    @Test fun frontViewCannotVerifyForwardTrunkMotion() {
        val f=fixture({_,_,b->b["view_cos"]=1f;b["view_sin"]=0f})
        assertFalse(f.containsKey(FourExerciseForm.GOOD_MORNING_SIGNAL))
        assertFalse(f.containsKey(StandingFormGeometry.TILT))
        assertFalse(f.containsKey(StandingFormGeometry.plane("L")))
    }
    @Test fun phoneRollPreservesBodyAndKneeAngles() {
        val normal=fixture()
        val rotated=fixture({xy,_,_->for(i in 0..32){val x=xy[i*2];xy[i*2]=1f-xy[i*2+1];xy[i*2+1]=x}},Vec3(1f,0f,0f))
        for(k in listOf(StandingFormGeometry.TILT,StandingFormGeometry.knee("L"),StandingFormGeometry.elbow("L")))
            assertEquals(normal.getValue(k),rotated.getValue(k),.01f)
    }
    @Test fun collapsedOrOutOfFramePointsAreNotErrors() {
        val f=fixture({xy,_,_->xy[26]=xy[22];xy[27]=xy[23];xy[28]=1.1f})
        assertFalse(f.containsKey(StandingFormGeometry.elbow("L")))
        assertFalse(f.containsKey(StandingFormGeometry.elbow("R")))
    }
}
