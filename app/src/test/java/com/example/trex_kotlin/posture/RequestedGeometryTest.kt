package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class RequestedGeometryTest {
    private fun point(xy:FloatArray,i:Int,x:Float,y:Float){xy[2*i]=x;xy[2*i+1]=y}
    @Test fun sideHingeUsesVisibleChainWhenTheFarLegAndWorldDirectionAreMissing() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){.1f}
        for(i in listOf(11,23,25,27))vis[i]=.95f
        vis[12]=.35f
        point(xy,11,.6f,.25f);point(xy,12,.61f,.25f);point(xy,23,.45f,.5f)
        point(xy,25,.62f,.7f);point(xy,27,.65f,.9f)
        val f=RequestedFormFeatures.augment("굿모닝",mapOf("hip_L" to 95f),xy,vis,1000,1000)
        assertEquals(95f,f.getValue("hip_mean"),0f)
        assertTrue(f.getValue("goodmorning_knee_side_L") in 115f..175f)
        assertFalse(f.containsKey("goodmorning_knee_side_R"))
        vis[27]=0f
        assertFalse(RequestedFormFeatures.augment("굿모닝",mapOf("hip_L" to 95f),xy,vis,1000,1000).containsKey("hip_mean"))
    }
    @Test fun frontStanceAndInwardKneeGeometryAreIndependentOfTheOtherLeg() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){.95f}
        for((a,b,y) in listOf(Triple(11,12,.25f),Triple(23,24,.5f),Triple(25,26,.7f),Triple(27,28,.9f),Triple(31,32,.95f))) {
            point(xy,a,.6f,y);point(xy,b,.4f,y)
        }
        val view=mapOf(ViewEstimator.FEAT_COS to 1f,ViewEstimator.FEAT_SIN to 0f,"shoulder_asym" to 0f)
        val normal=GoodMorningGeometry.features(xy,vis,1000,1000,view)
        assertEquals(1f,normal.getValue("goodmorning_stance"),.001f)
        assertEquals(0f,normal.getValue("goodmorning_toe_L"),.001f)
        point(xy,25,.50f,.7f)
        val inward=GoodMorningGeometry.features(xy,vis,1000,1000,view)
        assertTrue(inward.getValue("goodmorning_knee_out_L")<-.18f)
        assertEquals(0f,inward.getValue("goodmorning_knee_out_R"),.001f)
    }
    @Test fun pushupFlareIsBodyUpperArmAngleAndNotElbowFlexion() {
        val xy=FloatArray(66){.5f};val vis=FloatArray(33){.95f}
        point(xy,11,.3f,.3f);point(xy,12,.7f,.3f);point(xy,23,.4f,.65f);point(xy,24,.6f,.65f)
        point(xy,13,.15f,.45f);point(xy,14,.85f,.45f);point(xy,15,.25f,.5f);point(xy,16,.75f,.5f)
        val original=PushupGeometry.features(xy,vis,1000,1000)
        assertEquals(1.25f,original.getValue("pushup_hand_width"),.001f)
        assertTrue(original.getValue("pushup_elbow_flare_L") in 20f..75f)
        point(xy,15,.25f,.7f) // 손목을 움직여 팔꿈치 굽힘을 바꿔도 위팔 벌어짐은 같다.
        val flexed=PushupGeometry.features(xy,vis,1000,1000)
        assertEquals(original.getValue("pushup_elbow_flare_L"),flexed.getValue("pushup_elbow_flare_L"),.001f)
        point(xy,13,.05f,.3f)
        assertTrue(PushupGeometry.features(xy,vis,1000,1000).getValue("pushup_elbow_flare_L")>75f)
        vis[13]=0f
        assertFalse(PushupGeometry.features(xy,vis,1000,1000).containsKey("pushup_elbow_flare_L"))
    }
}
