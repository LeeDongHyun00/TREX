package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.camera.PoseObserverTrackingStatus
import com.example.trex_kotlin.camera.PoseObserverUnknownReason
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementCoachAttemptRecorderTest {

    @Test
    fun emptyRecorderReportsNothing() {
        val aggregate = PlacementCoachAttemptRecorder().snapshot()

        assertEquals(0, aggregate.acceptedFrameCount)
        assertEquals(0L, aggregate.attemptDurationMs)
        assertNull(aggregate.fullBodyReachedAfterMs)
        assertNull(aggregate.lateralReachedAfterMs)
        assertEquals(0, aggregate.discontinuityCount)
        assertTrue(aggregate.guidanceDwellMs.isEmpty())
    }

    @Test
    fun reachTimeIsMeasuredFromTheFirstAcceptedFrame() {
        val recorder = PlacementCoachAttemptRecorder()

        recorder.accept(10_000L, tooSmall)
        recorder.accept(10_100L, tooSmall)
        recorder.accept(10_200L, reachedFullBody)

        val aggregate = recorder.snapshot()
        assertEquals(200L, aggregate.fullBodyReachedAfterMs)
        assertEquals(200L, aggregate.attemptDurationMs)
        assertEquals(3, aggregate.acceptedFrameCount)
    }

    @Test
    fun onlyTheFirstReachOfEachGoalIsRecorded() {
        val recorder = PlacementCoachAttemptRecorder()

        recorder.accept(0L, tooSmall)
        recorder.accept(100L, reachedFullBody)
        recorder.accept(200L, tooSmall)
        recorder.accept(300L, reachedFullBody)
        recorder.accept(400L, reachedLateral)

        val aggregate = recorder.snapshot()
        assertEquals(100L, aggregate.fullBodyReachedAfterMs)
        assertEquals(400L, aggregate.lateralReachedAfterMs)
    }

    @Test
    fun dwellIsAttributedToTheGuidanceThatWasOnScreen() {
        val recorder = PlacementCoachAttemptRecorder()

        recorder.accept(0L, tooSmall)
        recorder.accept(100L, tooLarge)
        recorder.accept(200L, tooLarge)
        recorder.accept(250L, reachedFullBody)

        val dwell = recorder.snapshot().guidanceDwellMs
        assertEquals(100L, dwell[PlacementCoachGuidance.MOVE_CLOSER])
        assertEquals(150L, dwell[PlacementCoachGuidance.MOVE_FARTHER])
        assertNull(dwell[PlacementCoachGuidance.FULL_BODY_REACHED])
    }

    @Test
    fun aSingleGapContributesNoMoreThanTheCap() {
        val recorder = PlacementCoachAttemptRecorder(maximumDwellStepMs = 250L)

        recorder.accept(0L, tooSmall)
        recorder.accept(5_000L, tooSmall)

        val aggregate = recorder.snapshot()
        assertEquals(250L, aggregate.guidanceDwellMs[PlacementCoachGuidance.MOVE_CLOSER])
        // The gap still counts toward real elapsed time so reach times stay honest.
        assertEquals(5_000L, aggregate.attemptDurationMs)
        assertEquals(1, aggregate.discontinuityCount)
    }

    @Test
    fun discontinuitiesAreCountedRatherThanErasingTheAttempt() {
        val recorder = PlacementCoachAttemptRecorder(maximumDwellStepMs = 250L)

        recorder.accept(0L, tooSmall)
        recorder.accept(100L, tooSmall)
        recorder.accept(2_000L, tooSmall)
        recorder.accept(1_500L, tooSmall)

        val aggregate = recorder.snapshot()
        assertEquals(2, aggregate.discontinuityCount)
        assertEquals(4, aggregate.acceptedFrameCount)
        assertTrue(aggregate.attemptDurationMs > 0L)
    }

    @Test
    fun backwardTimestampsNeverProduceNegativeDurations() {
        val recorder = PlacementCoachAttemptRecorder()

        recorder.accept(1_000L, tooSmall)
        recorder.accept(400L, reachedFullBody)

        val aggregate = recorder.snapshot()
        assertEquals(0L, aggregate.attemptDurationMs)
        assertEquals(0L, aggregate.fullBodyReachedAfterMs)
    }

    @Test
    fun acceptanceStopsAtTheBound() {
        val recorder = PlacementCoachAttemptRecorder(maximumAcceptedFrames = 3)

        repeat(10) { index -> recorder.accept(index * 10L, tooSmall) }

        val aggregate = recorder.snapshot()
        assertEquals(3, aggregate.acceptedFrameCount)
        assertEquals(7, aggregate.droppedFrameCount)
    }

    @Test
    fun resetForgetsEverything() {
        val recorder = PlacementCoachAttemptRecorder()
        recorder.accept(0L, tooSmall)
        recorder.accept(100L, reachedFullBody)

        recorder.reset()

        val aggregate = recorder.snapshot()
        assertEquals(0, aggregate.acceptedFrameCount)
        assertNull(aggregate.fullBodyReachedAfterMs)
        assertTrue(aggregate.guidanceDwellMs.isEmpty())
    }

    @Test
    fun aggregateIsImmutable() {
        val recorder = PlacementCoachAttemptRecorder()
        recorder.accept(0L, tooSmall)
        recorder.accept(100L, tooLarge)
        val aggregate = recorder.snapshot()

        @Suppress("UNCHECKED_CAST")
        val mutable = aggregate.guidanceDwellMs as MutableMap<PlacementCoachGuidance, Long>
        assertThrows(UnsupportedOperationException::class.java) { mutable.clear() }

        // A later frame must not mutate an aggregate already handed out.
        recorder.accept(200L, tooLarge)
        assertEquals(100L, aggregate.guidanceDwellMs[PlacementCoachGuidance.MOVE_CLOSER])
    }

    @Test
    fun aggregateExposesNoObservationDataOrAbsoluteTime() {
        val forbidden = setOf(
            "PoseFrame",
            "PoseLandmark",
            "AttestedPoseObservation",
            "PoseObserverUpdate",
            "PosePersonTrackEpoch",
            "PoseCameraGeometryEpoch",
        )

        val fields = PlacementCoachAttemptAggregate::class.java.declaredFields
            .filterNot { it.isSynthetic }
        for (field in fields) {
            assertFalse(
                "Aggregate field ${field.name} leaks ${field.type.simpleName}",
                field.type.simpleName in forbidden,
            )
            assertFalse(
                "Aggregate field ${field.name} looks like an absolute instant",
                field.name.contains("timestamp", ignoreCase = true),
            )
        }

        val methods = PlacementCoachAttemptAggregate::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        for (method in methods) {
            assertFalse(
                "Aggregate method ${method.name} leaks ${method.returnType.simpleName}",
                method.returnType.simpleName in forbidden,
            )
            assertFalse(
                "Aggregate method ${method.name} looks like an absolute instant",
                method.name.contains("timestamp", ignoreCase = true),
            )
        }
    }

    @Test
    fun configurationIsValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            PlacementCoachAttemptRecorder(maximumAcceptedFrames = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlacementCoachAttemptRecorder(maximumDwellStepMs = 0L)
        }
    }

    private val tooSmall = resolve(PoseObserverUnknownReason.BODY_TOO_SMALL)
    private val tooLarge = resolve(PoseObserverUnknownReason.BODY_TOO_LARGE)

    private val reachedFullBody = PlacementCoachDisplayPolicy.resolve(
        goal = PlacementCoachGoal.FULL_BODY,
        cameraState = PlacementCameraState.RUNNING,
        observed = framed(lateral = false),
    )

    private val reachedLateral = PlacementCoachDisplayPolicy.resolve(
        goal = PlacementCoachGoal.LATERAL,
        cameraState = PlacementCameraState.RUNNING,
        observed = framed(lateral = true),
    )

    private fun framed(lateral: Boolean) = PlacementObservedSignal(
        trackingStatus = PoseObserverTrackingStatus.TRACKED,
        unknownReasons = emptySet(),
        hasPrimaryPersonLock = true,
        fullBodyViewQualified = true,
        lateralViewQualified = lateral,
            frontalViewQualified = false,
        candidateCount = 1,
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
            frontalViewQualified = false,
            candidateCount = 1,
        ),
    )
}
