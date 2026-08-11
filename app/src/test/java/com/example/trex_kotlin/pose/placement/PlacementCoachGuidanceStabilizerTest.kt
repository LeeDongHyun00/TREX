package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.camera.PoseObserverTrackingStatus
import com.example.trex_kotlin.camera.PoseObserverUnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlacementCoachGuidanceStabilizerTest {

    @Test
    fun firstFrameIsShownImmediately() {
        val stabilizer = PlacementCoachGuidanceStabilizer()

        val shown = stabilizer.stabilize(0L, tooSmall)

        assertEquals(tooSmall, shown)
        assertEquals(tooSmall, stabilizer.current)
    }

    @Test
    fun ordinaryTransitionsWaitForTheHoldWindow() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(0L, tooSmall)

        assertEquals(tooSmall, stabilizer.stabilize(100L, tooLarge))
        assertEquals(tooSmall, stabilizer.stabilize(300L, tooLarge))
        assertEquals(tooSmall, stabilizer.stabilize(500L, tooLarge))
        assertEquals(tooLarge, stabilizer.stabilize(700L, tooLarge))
    }

    @Test
    fun reachingTheGoalIsShownWithoutDelay() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(0L, tooSmall)

        assertEquals(reachedFullBody, stabilizer.stabilize(100L, reachedFullBody))
    }

    @Test
    fun leavingTheGoalIsShownWithoutDelay() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(0L, reachedFullBody)

        // A reached badge that outlives its placement is the false reassurance we must avoid.
        assertEquals(tooSmall, stabilizer.stabilize(50L, tooSmall))
    }

    @Test
    fun switchingGoalIsShownWithoutDelay() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(0L, reachedFullBody)

        assertEquals(turnSideways, stabilizer.stabilize(60L, turnSideways))
    }

    @Test
    fun frameGapBeyondObserverToleranceDropsTheHeldGuidance() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L, maximumFrameGapMs = 250L)
        stabilizer.stabilize(0L, tooSmall)

        assertEquals(tooSmall, stabilizer.stabilize(200L, tooLarge))
        // 500ms is still inside the hold window, so only the 300ms gap can explain the switch.
        assertEquals(tooLarge, stabilizer.stabilize(500L, tooLarge))
    }

    @Test
    fun nonMonotonicTimestampDropsTheHeldGuidance() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(1_000L, tooSmall)

        assertEquals(tooLarge, stabilizer.stabilize(900L, tooLarge))
    }

    @Test
    fun repeatingTheSameDisplayDoesNotRestartTheHoldWindow() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(0L, tooSmall)

        stabilizer.stabilize(200L, tooSmall)
        stabilizer.stabilize(400L, tooSmall)

        // The window is measured from the first adoption, not from the last identical frame.
        assertEquals(tooSmall, stabilizer.stabilize(600L, tooLarge))
        assertEquals(tooLarge, stabilizer.stabilize(700L, tooLarge))
    }

    @Test
    fun resetForgetsEverything() {
        val stabilizer = PlacementCoachGuidanceStabilizer(minimumHoldMs = 700L)
        stabilizer.stabilize(0L, reachedFullBody)

        stabilizer.reset()

        assertNull(stabilizer.current)
        assertEquals(tooSmall, stabilizer.stabilize(10L, tooSmall))
    }

    @Test
    fun configurationIsValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            PlacementCoachGuidanceStabilizer(minimumHoldMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlacementCoachGuidanceStabilizer(maximumFrameGapMs = 0L)
        }
    }

    private val tooSmall = resolve(PoseObserverUnknownReason.BODY_TOO_SMALL)
    private val tooLarge = resolve(PoseObserverUnknownReason.BODY_TOO_LARGE)

    private val reachedFullBody = PlacementCoachDisplayPolicy.resolve(
        goal = PlacementCoachGoal.FULL_BODY,
        cameraState = PlacementCameraState.RUNNING,
        observed = PlacementObservedSignal(
            trackingStatus = PoseObserverTrackingStatus.TRACKED,
            unknownReasons = emptySet(),
            hasPrimaryPersonLock = true,
            fullBodyViewQualified = true,
            lateralViewQualified = false,
            candidateCount = 1,
        ),
    )

    private val turnSideways = PlacementCoachDisplayPolicy.resolve(
        goal = PlacementCoachGoal.LATERAL,
        cameraState = PlacementCameraState.RUNNING,
        observed = PlacementObservedSignal(
            trackingStatus = PoseObserverTrackingStatus.TRACKED,
            unknownReasons = emptySet(),
            hasPrimaryPersonLock = true,
            fullBodyViewQualified = true,
            lateralViewQualified = false,
            candidateCount = 1,
        ),
    )

    private fun resolve(reason: PoseObserverUnknownReason) = PlacementCoachDisplayPolicy.resolve(
        goal = PlacementCoachGoal.FULL_BODY,
        cameraState = PlacementCameraState.RUNNING,
        observed = PlacementObservedSignal(
            trackingStatus = PoseObserverTrackingStatus.ACQUIRING,
            unknownReasons = setOf(reason),
            hasPrimaryPersonLock = false,
            fullBodyViewQualified = false,
            lateralViewQualified = false,
            candidateCount = 1,
        ),
    )
}
