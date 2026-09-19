package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min

class SideCrunchFeatureTest {
    private fun body() = mutableMapOf<String, Vec3?>(
        Joints.L_HIP to Vec3(10f, 100f, 0f), Joints.R_HIP to Vec3(-10f, 100f, 0f),
        Joints.L_SHOULDER to Vec3(20f, 150f, 0f), Joints.R_SHOULDER to Vec3(-20f, 150f, 0f),
        Joints.L_ELBOW to Vec3(40f, 130f, 0f), Joints.R_ELBOW to Vec3(-40f, 130f, 0f),
        Joints.L_KNEE to Vec3(10f, 60f, 0f), Joints.R_KNEE to Vec3(-10f, 60f, 0f),
        Joints.L_ANKLE to Vec3(10f, 10f, 0f), Joints.R_ANKLE to Vec3(-10f, 10f, 0f),
    )

    @Test fun newIpsilateralDistancesPreserveBothSidesAndExistingMinimum() {
        val joints = body()
        joints[Joints.L_KNEE] = Vec3(35f, 120f, 0f)
        val f = PoseFrame(joints).features()
        assertTrue(f.getValue("knee_elbow_dist_L") < f.getValue("knee_elbow_dist_R"))
        assertEquals(min(f.getValue("knee_elbow_dist_L"), f.getValue("knee_elbow_dist_R")),
            f.getValue("knee_elbow_dist"), .0001f)
    }

    @Test fun missingElbowDoesNotProduceZeroDistanceOrBorrowContralateralValue() {
        val joints = body()
        joints[Joints.L_ELBOW] = null
        val f = PoseFrame(joints).features()
        assertFalse(f.containsKey("knee_elbow_dist_L"))
        assertTrue(f.containsKey("knee_elbow_dist_R"))
        assertEquals(f.getValue("knee_elbow_dist_R"), f.getValue("knee_elbow_dist"), .0001f)
    }

    @Test fun loweringOnlyTheArmChangesDistanceButNotKneeRiseSignal() {
        val joints = body()
        val before = PoseFrame(joints).features()
        joints[Joints.L_ELBOW] = Vec3(20f, 90f, 0f)
        val after = PoseFrame(joints).features()
        assertTrue(after.getValue("knee_elbow_dist_L") < before.getValue("knee_elbow_dist_L"))
        assertEquals(before.getValue("knee_h_L"), after.getValue("knee_h_L"), .0001f)
    }
}
