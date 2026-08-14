package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.camera.PoseObserverTrackingStatus
import com.example.trex_kotlin.camera.PoseObserverUnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementCoachDisplayPolicyTest {

    @Test
    fun everyObserverReasonIsClassifiedExactlyOnce() {
        val prioritised = PlacementCoachDisplayPolicy.DISPLAY_PRIORITY
        val suppressed = PlacementCoachDisplayPolicy.NEVER_DISPLAYED

        assertEquals(prioritised.size, prioritised.toSet().size)
        assertTrue(prioritised.none { it in suppressed })
        assertEquals(
            PoseObserverUnknownReason.entries.toSet(),
            prioritised.toSet() + suppressed,
        )
    }

    @Test
    fun priorityWinsRegardlessOfSetIterationOrder() {
        val order = PlacementCoachDisplayPolicy.DISPLAY_PRIORITY
        for (higher in order.indices) {
            for (lower in (higher + 1) until order.size) {
                val expected = order[higher]
                val forward = resolveWithReasons(linkedSetOf(order[higher], order[lower]))
                val reversed = resolveWithReasons(linkedSetOf(order[lower], order[higher]))

                assertEquals(
                    "Expected ${expected.name} to win over ${order[lower].name}",
                    forward.guidance,
                    reversed.guidance,
                )
                assertEquals(guidanceOf(expected), forward.guidance)
            }
        }
    }

    @Test
    fun suppressedReasonsNeverBlockAFramedPlacement() {
        for (suppressed in PlacementCoachDisplayPolicy.NEVER_DISPLAYED) {
            val display = PlacementCoachDisplayPolicy.resolve(
                goal = PlacementCoachGoal.FULL_BODY,
                cameraState = PlacementCameraState.RUNNING,
                observed = framedSignal(reasons = setOf(suppressed)),
            )

            assertEquals(PlacementCoachStage.REACHED, display.stage)
            assertEquals(PlacementCoachGuidance.FULL_BODY_REACHED, display.guidance)
            assertEquals(setOf(suppressed), display.suppressedReasons)
        }
    }

    @Test
    fun suppressedReasonsNeverSelectGuidanceWhilePlacementIsUnsettled() {
        val display = resolveWithReasons(
            linkedSetOf(
                PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED,
                PoseObserverUnknownReason.VIEW_AMBIGUOUS,
                PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING,
            ),
        )

        assertEquals(PlacementCoachGuidance.HOLD_STILL, display.guidance)
        assertEquals(PlacementCoachStage.HOLDING, display.stage)
    }

    @Test
    fun reachingAGoalRequiresItsViewToken() {
        val lockedButUnqualified = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = PlacementObservedSignal(
                trackingStatus = PoseObserverTrackingStatus.TRACKED,
                unknownReasons = emptySet(),
                hasPrimaryPersonLock = true,
                fullBodyViewQualified = false,
                lateralViewQualified = false,
            frontalViewQualified = false,
                candidateCount = 1,
            ),
        )
        assertNotEquals(PlacementCoachStage.REACHED, lockedButUnqualified.stage)

        val qualifiedWithoutLock = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = PlacementObservedSignal(
                trackingStatus = PoseObserverTrackingStatus.TRACKED,
                unknownReasons = emptySet(),
                hasPrimaryPersonLock = false,
                fullBodyViewQualified = true,
                lateralViewQualified = true,
            frontalViewQualified = false,
                candidateCount = 1,
            ),
        )
        assertNotEquals(PlacementCoachStage.REACHED, qualifiedWithoutLock.stage)

        val untracked = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = PlacementObservedSignal(
                trackingStatus = PoseObserverTrackingStatus.ACQUIRING,
                unknownReasons = emptySet(),
                hasPrimaryPersonLock = true,
                fullBodyViewQualified = true,
                lateralViewQualified = true,
            frontalViewQualified = false,
                candidateCount = 1,
            ),
        )
        assertNotEquals(PlacementCoachStage.REACHED, untracked.stage)
    }

    @Test
    fun lateralGoalNeedsTheLateralTokenOnTopOfFraming() {
        val framedOnly = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.LATERAL,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(reasons = setOf(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED)),
        )
        assertEquals(PlacementCoachStage.ADJUSTING, framedOnly.stage)
        assertEquals(PlacementCoachGuidance.TURN_SIDEWAYS, framedOnly.guidance)

        val lateral = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.LATERAL,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(lateral = true),
        )
        assertEquals(PlacementCoachStage.REACHED, lateral.stage)
        assertEquals(PlacementCoachGuidance.LATERAL_REACHED, lateral.guidance)
    }

    @Test
    fun lowConfidenceFromRejectedCandidatesDoesNotBlockReaching() {
        // The observer raises this reason for candidates it discarded, not for the tracked person.
        // A bystander at the edge of frame must not keep the user from ever reaching the goal.
        val display = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(
                reasons = setOf(PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE),
                candidateCount = 2,
            ),
        )

        assertEquals(PlacementCoachStage.REACHED, display.stage)
        assertEquals(
            setOf(PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE),
            display.suppressedReasons,
        )
    }

    @Test
    fun cameraLifecycleOutranksAnyObservation() {
        val unavailable = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.UNAVAILABLE,
            observed = framedSignal(lateral = true),
        )
        assertEquals(PlacementCoachStage.CAMERA_UNAVAILABLE, unavailable.stage)
        assertEquals(PlacementCoachGuidance.CAMERA_BLOCKED, unavailable.guidance)

        val starting = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.STARTING,
            observed = framedSignal(lateral = true),
        )
        assertEquals(PlacementCoachStage.CAMERA_STARTING, starting.stage)

        val running = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = null,
        )
        assertEquals(PlacementCoachStage.CAMERA_STARTING, running.stage)
    }

    @Test
    fun skeletonIsHiddenWhileNoCandidateIsBeingTracked() {
        assertFalse(resolveWithStatus(PoseObserverTrackingStatus.PERSON_NOT_FOUND).skeletonVisible)
        assertFalse(resolveWithStatus(PoseObserverTrackingStatus.INSUFFICIENT_LANDMARKS).skeletonVisible)
        assertFalse(resolveWithStatus(PoseObserverTrackingStatus.AMBIGUOUS).skeletonVisible)
        assertTrue(resolveWithStatus(PoseObserverTrackingStatus.ACQUIRING).skeletonVisible)
        assertTrue(resolveWithStatus(PoseObserverTrackingStatus.TRACKED).skeletonVisible)
        assertTrue(resolveWithStatus(PoseObserverTrackingStatus.TRACK_DISCONTINUITY).skeletonVisible)
    }

    @Test
    fun guidanceTextStatesObservationsRatherThanAssessments() {
        val banned = listOf(
            "점수", "등급", "합격", "불합격", "정확", "부정확", "올바", "잘못", "틀렸",
            "교정", "평가", "좋아요", "나빠요", "위험", "각도", "횟수", "세트",
        )
        val bannedLatin = listOf("pass", "fail", "score", "cue", "verdict", "correct", "grade", "rating")

        for (guidance in PlacementCoachGuidance.entries) {
            val text = "${guidance.headline} ${guidance.detail}"
            assertTrue("Empty guidance text for ${guidance.name}", text.isNotBlank())
            for (word in banned) {
                assertFalse("${guidance.name} contains '$word'", text.contains(word))
            }
            for (word in bannedLatin) {
                assertFalse(
                    "${guidance.name} contains '$word'",
                    text.contains(word, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun disclosureIsPinnedAndDeniesAssessment() {
        assertEquals(
            "이 화면은 자세를 평가하지 않습니다. 카메라 배치만 안내합니다.",
            PlacementCoachDisplayPolicy.NON_VERDICT_DISCLOSURE,
        )
    }

    @Test
    fun vocabularyIsPinned() {
        assertEquals(
            listOf("FULL_BODY", "LATERAL", "FRONTAL"),
            PlacementCoachGoal.entries.map { it.name },
        )
        assertEquals(
            listOf("STARTING", "RUNNING", "UNAVAILABLE"),
            PlacementCameraState.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "CAMERA_UNAVAILABLE",
                "CAMERA_STARTING",
                "NO_PERSON",
                "ADJUSTING",
                "HOLDING",
                "REACHED",
            ),
            PlacementCoachStage.entries.map { it.name },
        )
    }

    @Test
    fun exposedCollectionsAreImmutable() {
        val display = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(reasons = setOf(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED)),
        )

        @Suppress("UNCHECKED_CAST")
        val mutable = display.suppressedReasons as MutableSet<PoseObserverUnknownReason>
        assertThrows(UnsupportedOperationException::class.java) { mutable.clear() }

        @Suppress("UNCHECKED_CAST")
        val priority = PlacementCoachDisplayPolicy.DISPLAY_PRIORITY as MutableList<PoseObserverUnknownReason>
        assertThrows(UnsupportedOperationException::class.java) { priority.clear() }
    }

    @Test
    fun displayEqualityCoversEveryRenderedField() {
        val base = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(),
        )
        val same = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(),
        )
        val otherGoal = PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.LATERAL,
            cameraState = PlacementCameraState.RUNNING,
            observed = framedSignal(lateral = true),
        )

        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        assertNotEquals(base, otherGoal)
    }

    @Test
    fun initialDisplayWaitsForTheCamera() {
        val initial = PlacementCoachDisplayPolicy.initial(PlacementCoachGoal.FULL_BODY)

        assertEquals(PlacementCoachStage.CAMERA_STARTING, initial.stage)
        assertEquals(PlacementCoachGuidance.WAIT_FOR_CAMERA, initial.guidance)
        assertFalse(initial.skeletonVisible)
        assertFalse(initial.goalReached)
    }

    @Test
    fun negativeCandidateCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PlacementObservedSignal(
                trackingStatus = PoseObserverTrackingStatus.TRACKED,
                unknownReasons = emptySet(),
                hasPrimaryPersonLock = true,
                fullBodyViewQualified = true,
                lateralViewQualified = false,
            frontalViewQualified = false,
                candidateCount = -1,
            )
        }
    }

    private fun framedSignal(
        reasons: Set<PoseObserverUnknownReason> = emptySet(),
        lateral: Boolean = false,
        candidateCount: Int = 1,
    ) = PlacementObservedSignal(
        trackingStatus = PoseObserverTrackingStatus.TRACKED,
        unknownReasons = reasons,
        hasPrimaryPersonLock = true,
        fullBodyViewQualified = true,
        lateralViewQualified = lateral,
            frontalViewQualified = false,
        candidateCount = candidateCount,
    )

    private fun resolveWithReasons(reasons: Set<PoseObserverUnknownReason>) =
        PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = PlacementObservedSignal(
                trackingStatus = PoseObserverTrackingStatus.ACQUIRING,
                unknownReasons = reasons,
                hasPrimaryPersonLock = false,
                fullBodyViewQualified = false,
                lateralViewQualified = false,
            frontalViewQualified = false,
                candidateCount = 1,
            ),
        )

    private fun resolveWithStatus(status: PoseObserverTrackingStatus) =
        PlacementCoachDisplayPolicy.resolve(
            goal = PlacementCoachGoal.FULL_BODY,
            cameraState = PlacementCameraState.RUNNING,
            observed = PlacementObservedSignal(
                trackingStatus = status,
                unknownReasons = setOf(PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING),
                hasPrimaryPersonLock = false,
                fullBodyViewQualified = false,
                lateralViewQualified = false,
            frontalViewQualified = false,
                candidateCount = 1,
            ),
        )

    private fun guidanceOf(reason: PoseObserverUnknownReason): PlacementCoachGuidance = when (reason) {
        PoseObserverUnknownReason.PERSON_NOT_FOUND -> PlacementCoachGuidance.STEP_INTO_FRAME
        PoseObserverUnknownReason.PERSON_AMBIGUOUS -> PlacementCoachGuidance.ONLY_ONE_PERSON
        PoseObserverUnknownReason.BODY_OUT_OF_FRAME -> PlacementCoachGuidance.FIT_WHOLE_BODY
        PoseObserverUnknownReason.BODY_TOO_LARGE -> PlacementCoachGuidance.MOVE_FARTHER
        PoseObserverUnknownReason.BODY_TOO_SMALL -> PlacementCoachGuidance.MOVE_CLOSER
        PoseObserverUnknownReason.REQUIRED_LANDMARK_MISSING,
        PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE,
        -> PlacementCoachGuidance.IMPROVE_VISIBILITY
        PoseObserverUnknownReason.CAMERA_GEOMETRY_DISCONTINUITY -> PlacementCoachGuidance.HOLD_DEVICE_STILL
        PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE,
        PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY,
        PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING,
        -> PlacementCoachGuidance.HOLD_STILL
        PoseObserverUnknownReason.VIEW_QUALIFICATION_STABILIZING -> PlacementCoachGuidance.KEEP_BODY_FACING_STEADY
        else -> error("Suppressed reason has no guidance")
    }
}
