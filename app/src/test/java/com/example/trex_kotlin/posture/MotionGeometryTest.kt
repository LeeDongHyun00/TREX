package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class MotionGeometryTest {
    private fun joints() = buildMap<String,Vec3?> {
        put(Joints.NOSE,Vec3(0f,163f,8f)); put(Joints.L_EAR,Vec3(8f,160f,0f)); put(Joints.R_EAR,Vec3(-8f,160f,0f))
        for ((prefix,sign) in listOf("L" to 1f,"R" to -1f)) {
            put("${prefix}Shoulder",Vec3(sign*20f,145f,0f)); put("${prefix}Hip",Vec3(sign*15f,95f,0f))
            put("${prefix}Elbow",Vec3(sign*33f,120f,8f)); put("${prefix}Wrist",Vec3(sign*30f,90f,15f))
            put("${prefix}Palm",Vec3(sign*31f,85f,17f)); put("${prefix}Knee",Vec3(if(sign>0)5f else -25f,50f,10f))
            put("${prefix}Ankle",Vec3(sign*15f,5f,0f)); put("${prefix}Heel",Vec3(sign*15f,0f,-5f))
            put("${prefix}Foot",Vec3(sign*15f,0f,20f))
        }
    }
    @Test fun footReferencedKneeSignIsAnatomicalAndNotCameraAxis() {
        val pose=joints(); val original=PoseFrame(pose,Vec3(0f,1f,0f)).features()
        assertTrue(original.getValue("knee_track_L")<0)
        assertTrue(original.getValue("knee_track_R")>0)
        val rotate: (Vec3) -> Vec3 = { Vec3(it.y,it.z,it.x) }
        val moved=PoseFrame(pose.mapValues { it.value?.let(rotate)?.plus(Vec3(22f,5f,8f)) },rotate(Vec3(0f,1f,0f))).features()
        val mirror=PoseFrame(pose.mapValues { it.value?.let { v -> Vec3(-v.x,v.y,v.z) } },Vec3(0f,1f,0f)).features()
        for(side in listOf("L","R")) {
            assertEquals(original.getValue("knee_track_$side"),moved.getValue("knee_track_$side"),1e-5f)
            assertEquals(original.getValue("knee_track_$side"),mirror.getValue("knee_track_$side"),1e-5f)
        }
    }
    @Test fun kneeFollowingTurnedOutToeIsNotAutomaticallyInward() {
        val pose=joints().toMutableMap()
        pose[Joints.L_HEEL]=Vec3(15f,0f,-5f); pose[Joints.L_FOOT]=Vec3(30f,0f,10f)
        pose[Joints.L_KNEE]=Vec3(25f,50f,10f)
        assertEquals(0f,PoseFrame(pose).features().getValue("knee_track_L"),1e-4f)
        pose[Joints.L_HEEL]=null
        assertFalse(PoseFrame(pose).features().containsKey("knee_track_L"))
    }
    @Test fun everyContractChannelExistsInItsActualExtractor() {
        val pose=joints(); val world=PoseFrame(pose).features()
        val xy=FloatArray(66) { .5f }; val vis=FloatArray(33) { 1f }
        for ((name,index) in Joints.SINGLE) pose[name]?.let { p ->
            xy[index*2]=(p.z+p.x*.2f+80f)/300f; xy[index*2+1]=(170f-p.y)/200f
        }
        for(contract in MotionContracts.all) {
            val features=if(contract.exercise in FloorTemporal.exercises)
                FloorFeatureExtractor().computeForExercise(contract.exercise,xy,vis,600,400) else world
            for(ch in contract.channels) for(key in listOf(ch.primary,ch.support))
                assertTrue("${contract.exercise}: $key 계산 경로 없음",features[key]?.isFinite()==true)
        }
    }
    @Test fun occludedFloorArmIsNotInventedFromVisibleOtherSide() {
        val xy=FloatArray(66){i -> if(i%2==0) .2f+(i/2)*.01f else .1f+(i/2)*.02f }
        val vis=FloatArray(33){1f}; vis[13]=0f
        val f=FloorFeatureExtractor().sideFeatures(xy,vis,640,480)
        assertFalse(f.containsKey("elbow_ang_L")); assertTrue(f.containsKey("elbow_ang_R"))
    }
}
