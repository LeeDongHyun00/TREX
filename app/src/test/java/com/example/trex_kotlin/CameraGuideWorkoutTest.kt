package com.example.trex_kotlin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera guide is a display-only preference. These tests seal the boundary between it and the
 * posture-correction release chain: enabling the guide must never open a posture session, never
 * flip the posture flag, and never leave a stored posture claim behind.
 */
class CameraGuideWorkoutTest {

    @Test
    fun seedPlanShipsWithTheGuideOff() {
        assertTrue(todayPlan.isNotEmpty())
        assertTrue(todayPlan.none(Workout::cameraGuide))
    }

    @Test
    fun enablingTheGuideGrantsNoPostureAuthority() {
        for (workout in todayPlan) {
            val guided = workout.withCameraGuide(true)

            assertTrue(guided.cameraGuide)
            assertFalse(guided.posture)
            assertFalse(guided.canUsePostureSession())
        }
    }

    @Test
    fun theGuideSurvivesThePostureDowngradeUsedByTheSessionRouter() {
        val guided = todayPlan.first().withCameraGuide(true)

        val routed = guided.withPostureCorrection(false)

        assertTrue(routed.cameraGuide)
        assertFalse(routed.posture)
    }

    @Test
    fun theTwoCameraTogglesAreMutuallyExclusive() {
        // Only one camera layer ever runs; a toggle that stayed on while silently superseded
        // would misstate what the session will do.
        val squat = todayPlan.first { it.exercise.name == "BARBELL_SQUAT" }

        val formChecked = squat.withCameraGuide(true).withFormCheck(true)
        assertTrue(formChecked.formCheck)
        assertFalse(formChecked.cameraGuide)

        val guided = formChecked.withCameraGuide(true)
        assertTrue(guided.cameraGuide)
        assertFalse(guided.formCheck)
    }

    @Test
    fun aGuidedSessionStoresNoPostureClaim() {
        val guidedPlan = todayPlan.map { it.withCameraGuide(true) }

        val day = createWorkoutHistoryDay(guidedPlan, elapsedSeconds = 1_800)

        assertTrue(day.items.isNotEmpty())
        for (item in day.items) {
            assertNull(item.postureCorrection)
        }
    }
}
