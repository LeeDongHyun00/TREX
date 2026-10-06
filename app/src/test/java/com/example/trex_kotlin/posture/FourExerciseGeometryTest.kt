package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class FourExerciseGeometryTest {
    private val joints = mapOf(
        Joints.L_SHOULDER to Vec3(20f,50f,0f), Joints.R_SHOULDER to Vec3(-20f,50f,0f),
        Joints.L_HIP to Vec3(15f,0f,0f), Joints.R_HIP to Vec3(-15f,0f,0f),
        Joints.L_KNEE to Vec3(15f,-45f,0f), Joints.R_KNEE to Vec3(-15f,-45f,0f),
        Joints.L_ANKLE to Vec3(15f,-90f,0f), Joints.R_ANKLE to Vec3(-15f,-90f,0f),
        Joints.L_ELBOW to Vec3(30f,30f,0f), Joints.R_ELBOW to Vec3(-30f,30f,0f))
    @Test fun missingEssentialJointCannotUseSingleSideFallback() {
        for(key in joints.keys.take(8)) assertTrue(key, FourExerciseGeometry.features(PoseFrame(joints-key)).isEmpty())
    }
    @Test fun thighAngleIsIndependentOfTorsoLeanAndWorldOrigin() {
        val f=FourExerciseGeometry.features(PoseFrame(joints))
        assertEquals(180f,f.getValue("four_thigh_L"),.01f)
        val bent=joints.mapValues { (key,v) -> if(key.endsWith("Shoulder")) v+Vec3(0f,-15f,25f) else v }
        assertEquals(f.getValue("four_thigh_L"),FourExerciseGeometry.features(PoseFrame(bent)).getValue("four_thigh_L"),.01f)
        val moved=joints.mapValues { (_,v)->v+Vec3(100f,30f,-15f) }
        val m=FourExerciseGeometry.features(PoseFrame(moved))
        f.forEach { (k,v)->assertEquals(k,v,m.getValue(k),.0001f) }
    }
    @Test fun anatomicalSidesSurviveCameraRotationAndScale() {
        val raised=joints+mapOf(Joints.L_KNEE to Vec3(15f,0f,45f),Joints.L_ANKLE to Vec3(15f,-45f,45f))
        val a=FourExerciseGeometry.features(PoseFrame(raised))
        val rotated=raised.mapValues { (_,v)->Vec3(v.z*1.3f,v.y*1.3f,-v.x*1.3f) }
        val b=FourExerciseGeometry.features(PoseFrame(rotated))
        assertEquals(90f,a.getValue("four_thigh_L"),.01f)
        assertEquals(a.getValue("four_thigh_L"),b.getValue("four_thigh_L"),.01f)
        assertEquals(a.getValue("four_elbow_knee_L"),b.getValue("four_elbow_knee_L"),.001f)
        assertEquals(180f,b.getValue("four_thigh_R"),.01f)
    }
}
