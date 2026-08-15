package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.runtime.PoseGravityReading
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicFormCheckEngineTest {

    // ---- geometry ----

    @Test
    fun includedAngleMatchesConstructedAngles() {
        for (target in listOf(30.0, 90.0, 120.0, 175.0)) {
            val angle = FormCheckGeometry.includedAngleDegrees(
                vertex = point(0.0, 0.0, 0.0),
                first = point(0.0, 1.0, 0.0),
                second = point(sin(Math.toRadians(target)), cos(Math.toRadians(target)), 0.0),
            )
            assertNotNull(angle)
            assertEquals(target, angle!!, 1e-6)
        }
    }

    @Test
    fun degenerateChainAbstainsInsteadOfGuessing() {
        val collapsed = FormCheckGeometry.includedAngleDegrees(
            vertex = point(0.0, 0.0, 0.0),
            first = point(0.0, 0.0, 0.0),
            second = point(0.0, 1.0, 0.0),
        )
        assertNull(collapsed)
    }

    @Test
    fun kneeSamplePrefersTheBetterObservedSide() {
        val frame = frameWithKneeAngles(
            timestampMs = 0L,
            leftAngleDegrees = 90.0,
            rightAngleDegrees = 170.0,
            leftConfidence = 0.9,
            rightConfidence = 0.6,
        )
        val sample = FormCheckGeometry.sample(frame, FormCheckDriver.KNEE)

        assertNotNull(sample)
        assertEquals(FormCheckBodySide.LEFT, sample!!.side)
        assertEquals(90.0, sample.includedAngleDegrees, 1.0)
    }

    @Test
    fun kneeSampleAbstainsWhenNoChainIsCredible() {
        val frame = frameWithKneeAngles(
            timestampMs = 0L,
            leftAngleDegrees = 90.0,
            rightAngleDegrees = 90.0,
            leftConfidence = 0.3,
            rightConfidence = 0.4,
        )
        assertNull(FormCheckGeometry.sample(frame, FormCheckDriver.KNEE))
    }

    @Test
    fun eachDriverReadsItsOwnChainOnTheSameFrame() {
        // One geometry engine, three exercises: the driver is what selects the joints, so a hip
        // hinge and a push-up need no bespoke measurement code.
        val frame = frameWithChains(
            kneeAngleDegrees = 95.0,
            hipAngleDegrees = 120.0,
            elbowAngleDegrees = 60.0,
        )

        assertEquals(
            95.0,
            FormCheckGeometry.sample(frame, FormCheckDriver.KNEE)!!.includedAngleDegrees,
            1.0,
        )
        assertEquals(
            120.0,
            FormCheckGeometry.sample(frame, FormCheckDriver.HIP)!!.includedAngleDegrees,
            1.0,
        )
        assertEquals(
            60.0,
            FormCheckGeometry.sample(frame, FormCheckDriver.ELBOW)!!.includedAngleDegrees,
            1.0,
        )
    }

    @Test
    fun anExerciseOnlyWaitsForItsOwnChain() {
        // A push-up never needed an ankle, so a cropped foot must not stall it.
        val frame = frameWithChains(
            kneeAngleDegrees = 170.0,
            hipAngleDegrees = 170.0,
            elbowAngleDegrees = 100.0,
            dropped = setOf(FormCheckJointGroup.ANKLE),
        )

        val pushUp = FormCheckGeometry.readiness(
            frame,
            FormCheckExercise.PUSH_UP.requiredJoints,
        )
        val squat = FormCheckGeometry.readiness(
            frame,
            FormCheckExercise.BARBELL_SQUAT.requiredJoints,
        )

        assertTrue(pushUp.ready)
        assertFalse(squat.ready)
        assertEquals(setOf(FormCheckJointGroup.ANKLE), squat.missingGroups)
    }

    @Test
    fun theObservationNamesTheJointItActuallyMeasured() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PUSH_UP)
        var state = session.initialSnapshot()

        var t = 0L
        for (angle in listOf(175.0, 175.0, 120.0, 120.0, 120.0, 120.0, 175.0, 175.0)) {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = 175.0,
                    hipAngleDegrees = 175.0,
                    elbowAngleDegrees = angle,
                    timestampMs = t,
                ),
            )
            t += 200L
        }

        assertEquals(1, state.repCount)
        assertTrue("Expected an elbow observation, got ${state.headline}", state.headline!!.startsWith("팔꿈치가"))
    }

    @Test
    fun anExtensionExerciseCountsWhenTheJointStraightens() {
        // A hip thrust rests flexed and works upward. Feeding the raw angle to a detector that
        // only understands "falls to work" would never arm; the session mirrors it instead.
        val session = HeuristicFormCheckSession(FormCheckExercise.HIP_THRUST)
        var state = session.initialSnapshot()

        var t = 0L
        for (angle in listOf(100.0, 100.0, 135.0, 165.0, 165.0, 165.0, 120.0, 100.0, 100.0)) {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = 90.0,
                    hipAngleDegrees = angle,
                    elbowAngleDegrees = 170.0,
                    timestampMs = t,
                ),
            )
            t += 200L
        }

        assertEquals(1, state.repCount)
        assertTrue("Expected an extension observation, got ${state.headline}", state.headline!!.contains("펴졌어요"))
        assertNull("165 degrees passed the 160 reached line", state.suggestion)
    }

    @Test
    fun anExtensionExerciseThatStopsShortReportsShortfallNotShallowness() {
        val session = HeuristicFormCheckSession(FormCheckExercise.CABLE_PUSH_DOWN)
        var state = session.initialSnapshot()

        // Turns back at 135: past the 120 attempt line but short of the 150 rep line.
        var t = 0L
        for (angle in listOf(95.0, 95.0, 130.0, 135.0, 135.0, 110.0, 95.0, 95.0)) {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = 170.0,
                    hipAngleDegrees = 170.0,
                    elbowAngleDegrees = angle,
                    timestampMs = t,
                ),
            )
            t += 200L
        }

        assertEquals(0, state.repCount)
        assertEquals(1, state.uncountedAttemptCount)
        assertTrue("Expected a shortfall phrase, got ${state.headline}", state.headline!!.contains("폄이 부족해"))
        assertEquals(FormCheckExercise.CABLE_PUSH_DOWN.attemptHint, state.suggestion)
    }

    @Test
    fun mirroringRoundTripsForBothDirections() {
        for (spec in FormCheckExercise.entries) {
            for (angle in listOf(0.0, 37.5, 90.0, 142.0, 180.0)) {
                assertEquals(angle, spec.fromDetector(spec.toDetector(angle)), 1e-9)
            }
            // The detector's own invariant, expressed in its space.
            assertTrue(
                "${spec.name} thresholds must order correctly once mirrored",
                spec.toDetector(spec.repAngleDegrees) < spec.toDetector(spec.attemptAngleDegrees) &&
                    spec.toDetector(spec.attemptAngleDegrees) <
                    spec.toDetector(spec.restAngleDegrees),
            )
        }
    }

    // ---- hold cadence ----

    @Test
    fun aHoldAccumulatesSecondsWhileThePositionIsMaintained() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PLANK)
        var state = session.initialSnapshot()

        var t = 0L
        // Straightens past 160 and stays there for five seconds of samples.
        repeat(26) {
            state = session.accept(t, true, true, hipFrame(170.0, t))
            t += 200L
        }

        assertTrue("Expected accumulated seconds, got ${state.holdSeconds}", state.holdSeconds >= 4)
        assertTrue(state.headline!!.contains("유지하고 있어요"))
        assertEquals("A hold counts no repetitions", 0, state.repCount)
    }

    @Test
    fun losingTheHoldReportsWhatItAmountedTo() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PLANK)
        var state = session.initialSnapshot()

        var t = 0L
        repeat(26) {
            state = session.accept(t, true, true, hipFrame(170.0, t))
            t += 200L
        }
        // The hips sag well past the release line.
        repeat(6) {
            state = session.accept(t, true, true, hipFrame(120.0, t))
            t += 200L
        }

        assertTrue("Expected a completed-hold report, got ${state.headline}", state.headline!!.contains("초 유지했어요"))
        assertEquals(0, state.holdSeconds)
    }

    @Test
    fun aDiscardedHoldIsNeverBankedAsABest() {
        // The detector reports a stretch only when it ends, so a stretch that abstention throws
        // away leaves nothing behind for a later summary to pick up.
        val detector = HoldDetector(enterDegrees = 20.0, exitDegrees = 35.0)
        var t = 0L
        repeat(30) {
            detector.accept(t, 10.0)
            t += 200L
        }
        assertTrue("The hold was running", detector.heldMs > 3_000L)

        detector.invalidate()

        assertEquals(0L, detector.heldMs)
        assertFalse(detector.holding)
        // Re-entering starts from zero rather than resuming the discarded stretch.
        detector.accept(t, 10.0)
        assertEquals(0L, detector.heldMs)
    }

    @Test
    fun aHoldBrokenByLostObservationIsDiscardedNotBanked() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PLANK)
        var state = session.initialSnapshot()

        var t = 0L
        repeat(16) {
            state = session.accept(t, true, true, hipFrame(170.0, t))
            t += 200L
        }
        val before = state.holdSeconds
        assertTrue(before > 0)

        // The lock drops: unobserved time is not time the user held anything.
        state = session.accept(t, false, true, hipFrame(170.0, t))
        t += 200L

        assertEquals(0, state.holdSeconds)
        assertFalse(state.started)
    }

    @Test
    fun aBriefTouchOfThePositionIsNotReportedAsAHold() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PLANK)
        var state = session.initialSnapshot()

        var t = 0L
        repeat(4) {
            state = session.accept(t, true, true, hipFrame(170.0, t))
            t += 200L
        }
        repeat(6) {
            state = session.accept(t, true, true, hipFrame(120.0, t))
            t += 200L
        }

        assertTrue("Expected a break report, got ${state.headline}", state.headline!!.contains("잠깐 풀렸어요"))
    }

    // ---- personal baseline ----

    @Test
    fun theFirstRepetitionsCarryNoBaselineComparison() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PUSH_UP)
        var state = session.initialSnapshot()
        var t = 0L

        repeat(2) {
            for (angle in listOf(175.0, 175.0, 120.0, 120.0, 120.0, 120.0, 175.0, 175.0)) {
                state = session.accept(t, true, true, elbowFrame(angle, t))
                t += 200L
            }
        }

        assertEquals(2, state.repCount)
        assertFalse(
            "The opening repetitions define the baseline, so they cannot compare with it",
            state.headline!!.contains("오늘 첫 반복"),
        )
    }

    @Test
    fun aLaterRepetitionThatFallsWellShortIsComparedWithTheSetsOpening() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PUSH_UP)
        var state = session.initialSnapshot()
        var t = 0L

        repeat(2) {
            for (angle in listOf(175.0, 175.0, 100.0, 100.0, 100.0, 100.0, 175.0, 175.0)) {
                state = session.accept(t, true, true, elbowFrame(angle, t))
                t += 200L
            }
        }
        // Third rep bottoms out 30 degrees shallower than the baseline of 100.
        for (angle in listOf(175.0, 175.0, 130.0, 130.0, 130.0, 130.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
            t += 200L
        }

        assertEquals(3, state.repCount)
        assertTrue(
            "Expected a self-comparison, got ${state.headline}",
            state.headline!!.contains("오늘 첫 반복보다 30도 얕아요"),
        )
    }

    @Test
    fun aDifferenceInsideTheMeasurementsOwnNoiseStaysSilent() {
        val session = HeuristicFormCheckSession(FormCheckExercise.PUSH_UP)
        var state = session.initialSnapshot()
        var t = 0L

        repeat(2) {
            for (angle in listOf(175.0, 175.0, 100.0, 100.0, 100.0, 100.0, 175.0, 175.0)) {
                state = session.accept(t, true, true, elbowFrame(angle, t))
                t += 200L
            }
        }
        // Ten degrees is inside the bridge card's median absolute error; saying it would be
        // reporting the measurement's own noise as the user's change.
        for (angle in listOf(175.0, 175.0, 110.0, 110.0, 110.0, 110.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
            t += 200L
        }

        assertEquals(3, state.repCount)
        assertFalse(state.headline!!.contains("오늘 첫 반복"))
    }

    @Test
    fun anExtensionExerciseComparesInItsOwnDirection() {
        val session = HeuristicFormCheckSession(FormCheckExercise.CABLE_PUSH_DOWN)
        var state = session.initialSnapshot()
        var t = 0L

        // Upper arms pinned: this exercise's definition gate reads the shoulder chain, and the
        // fixture's incidental shoulder placement would fail it.
        fun pushDownFrame(angle: Double, timestamp: Long) = frameWithChains(
            kneeAngleDegrees = 175.0,
            hipAngleDegrees = 175.0,
            elbowAngleDegrees = angle,
            shoulderAngleDegrees = 25.0,
            timestampMs = timestamp,
        )

        repeat(2) {
            for (angle in listOf(90.0, 90.0, 170.0, 170.0, 170.0, 170.0, 90.0, 90.0)) {
                state = session.accept(t, true, true, pushDownFrame(angle, t))
                t += 200L
            }
        }
        // Reaches only 150: twenty degrees less extension than the set opened with.
        for (angle in listOf(90.0, 90.0, 150.0, 150.0, 150.0, 150.0, 90.0, 90.0)) {
            state = session.accept(t, true, true, pushDownFrame(angle, t))
            t += 200L
        }

        assertEquals(3, state.repCount)
        assertTrue(
            "Expected an extension-shaped comparison, got ${state.headline}",
            state.headline!!.contains("오늘 첫 반복보다 20도 덜 펴졌어요"),
        )
    }

    // ---- rep cycle detector ----

    @Test
    fun aFullExcursionBelowTheRepThresholdCompletesOneRep() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        var completed: RepCycleEvent.Completed? = null

        for ((timestamp, angle) in rampSequence()) {
            val event = detector.accept(timestamp, angle)
            if (event is RepCycleEvent.Completed) completed = event
        }

        assertNotNull("Expected one completed rep", completed)
        assertTrue(completed!!.minimumAngleDegrees <= 110.0)
        assertTrue(completed.durationMs >= 500L)
    }

    @Test
    fun aTurnaroundAboveTheRepThresholdIsAShallowAttemptNotARep() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        val events = mutableListOf<RepCycleEvent>()

        var t = 0L
        for (angle in listOf(175.0, 175.0, 130.0, 130.0, 130.0, 175.0, 175.0)) {
            events += detector.accept(t, angle)
            t += 200L
        }

        assertTrue(events.none { it is RepCycleEvent.Completed })
        assertTrue(events.any { it is RepCycleEvent.ShallowAttempt })
    }

    @Test
    fun aDeepButTooFastExcursionIsReportedAsSpeedNotDepth() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        val events = mutableListOf<RepCycleEvent>()

        // Reaches well below the rep threshold but bounces back inside the minimum duration.
        for ((timestamp, angle) in listOf(
            0L to 175.0, 100L to 175.0, 200L to 95.0, 300L to 95.0, 400L to 175.0, 500L to 175.0,
        )) {
            events += detector.accept(timestamp, angle)
        }

        assertTrue(events.none { it is RepCycleEvent.Completed })
        assertTrue(events.none { it is RepCycleEvent.ShallowAttempt })
        val tooFast = events.filterIsInstance<RepCycleEvent.TooFastAttempt>().single()
        assertTrue(
            "Depth was genuinely reached: ${tooFast.minimumAngleDegrees}",
            tooFast.minimumAngleDegrees <= 110.0,
        )
    }

    @Test
    fun anAscentAloneAfterReacquisitionIsNeverCounted() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        detector.invalidate()

        // Reacquired at the bottom of a squat: the descent was never observed, so standing up
        // must not become a repetition.
        val events = mutableListOf<RepCycleEvent>()
        for ((timestamp, angle) in listOf(
            0L to 95.0, 200L to 95.0, 400L to 95.0, 600L to 175.0, 800L to 175.0, 1_000L to 175.0,
        )) {
            events += detector.accept(timestamp, angle)
        }
        assertTrue(events.all { it is RepCycleEvent.None })

        // The next fully observed excursion counts normally.
        var completed = false
        for ((timestamp, angle) in listOf(
            1_200L to 100.0, 1_400L to 100.0, 1_600L to 100.0, 1_800L to 175.0, 2_000L to 175.0,
        )) {
            if (detector.accept(timestamp, angle) is RepCycleEvent.Completed) completed = true
        }
        assertTrue("The fully observed rep after reacquisition must count", completed)
    }

    @Test
    fun invalidationDiscardsTheExcursionWithoutAnEvent() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        detector.accept(0L, 175.0)
        detector.accept(200L, 100.0)
        detector.accept(400L, 100.0)

        detector.invalidate()

        // The return to the top after invalidation must not complete the discarded rep.
        val event = detector.accept(600L, 175.0)
        assertTrue(event is RepCycleEvent.None)
        assertNull(detector.smoothedAngleDegrees.takeIf { false }) // smoothing restarted below
        assertEquals(175.0, detector.smoothedAngleDegrees!!, 1e-6)
    }

    @Test
    fun nonMonotonicTimestampsInvalidateInsteadOfCounting() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        detector.accept(1_000L, 175.0)
        detector.accept(1_200L, 100.0)

        val event = detector.accept(900L, 175.0)

        assertTrue(event is RepCycleEvent.None)
    }

    @Test
    fun anExcursionLongerThanTheMaximumIsNeverCounted() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        detector.accept(0L, 175.0)
        detector.accept(200L, 100.0)
        detector.accept(11_000L, 100.0)

        val event = detector.accept(11_200L, 175.0)

        assertTrue(event is RepCycleEvent.None)
    }

    // ---- session ----

    @Test
    fun aDeepRepIsCountedWithAnObservationalHeadline() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()

        for ((timestamp, angle) in rampSequence()) {
            state = session.accept(
                timestampMs = timestamp,
                hasPrimaryPersonLock = true,
                preferredViewQualified = true,
                frame = frameWithKneeAngles(timestamp, angle, angle, 1.0, 1.0),
            )
        }

        assertEquals(1, state.repCount)
        assertTrue(state.started)
        assertNotNull(state.headline)
        assertTrue(state.headline!!.contains("도까지 굽혀졌어요"))
        assertNull("A rep at reached depth needs no suggestion", state.suggestion)
    }

    @Test
    fun losingTheLockMidRepDiscardsTheRepWithoutCounting() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)

        session.accept(0L, true, true, frameWithKneeAngles(0L, 175.0, 175.0, 1.0, 1.0))
        session.accept(200L, true, true, frameWithKneeAngles(200L, 100.0, 100.0, 1.0, 1.0))
        session.accept(400L, true, true, frameWithKneeAngles(400L, 100.0, 100.0, 1.0, 1.0))

        val abstained = session.accept(600L, false, true, frameWithKneeAngles(600L, 100.0, 100.0, 1.0, 1.0))
        assertFalse(abstained.started)

        val returned = session.accept(800L, true, true, frameWithKneeAngles(800L, 175.0, 175.0, 1.0, 1.0))
        assertEquals(0, returned.repCount)
    }

    @Test
    fun losingARequiredJointMidRepDiscardsTheRepWithoutCounting() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)

        session.accept(0L, true, true, frameWithKneeAngles(0L, 175.0, 175.0, 1.0, 1.0))
        session.accept(200L, true, true, frameWithKneeAngles(200L, 100.0, 100.0, 1.0, 1.0))

        // The ankle drops out at the bottom: the excursion in flight is discarded.
        val abstained = session.accept(
            400L,
            true,
            true,
            frameWithMissingGroups(setOf(FormCheckJointGroup.ANKLE)),
        )
        assertFalse(abstained.started)
        assertEquals(setOf(FormCheckJointGroup.ANKLE), abstained.missingJoints)

        val returned = session.accept(600L, true, true, frameWithKneeAngles(600L, 175.0, 175.0, 1.0, 1.0))
        assertEquals(0, returned.repCount)
    }

    @Test
    fun shallowAttemptsAreReportedButNeverCounted() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()

        var t = 0L
        for (angle in listOf(175.0, 175.0, 130.0, 130.0, 130.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, angle, angle, 1.0, 1.0))
            t += 200L
        }

        assertEquals(0, state.repCount)
        assertEquals(1, state.uncountedAttemptCount)
        assertTrue(state.headline!!.contains("얕아 횟수로 세지 않았어요"))
        // The squat is load-bearing: the observation stands alone, no depth urging (policy §4.2).
        assertNull(state.suggestion)
    }

    @Test
    fun aShallowLungeAttemptDeliversTheSetupHint() {
        val session = HeuristicFormCheckSession(FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE)
        var state = session.initialSnapshot()

        // Turns around at 137: below the 140 attempt line, above the 134 rep threshold.
        var t = 0L
        for (angle in listOf(175.0, 175.0, 137.0, 137.0, 137.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, angle, angle, 1.0, 1.0))
            t += 200L
        }

        assertEquals(0, state.repCount)
        assertEquals(1, state.uncountedAttemptCount)
        assertEquals(FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.attemptHint, state.suggestion)
    }

    @Test
    fun aCountedLungeRepAboveTheReachedLineSuggestsMoreDepth() {
        val session = HeuristicFormCheckSession(FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE)
        var state = session.initialSnapshot()

        // Bottoms out at 131: counted (<= 134) but above the 129 reached-depth line.
        var t = 0L
        for (angle in listOf(175.0, 175.0, 131.0, 131.0, 131.0, 131.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, angle, angle, 1.0, 1.0))
            t += 200L
        }

        assertEquals(1, state.repCount)
        assertEquals(FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.rangeHint, state.suggestion)
        assertNotNull(state.suggestion)
    }

    @Test
    fun aCountedSquatRepAboveTheReachedLineStaysObservationOnly() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()

        // Bottoms out at 108: counted (<= 110) but above the 105 reached-depth line, which for
        // the load-bearing squat must yield the observation without a deeper suggestion.
        var t = 0L
        for (angle in listOf(175.0, 175.0, 108.0, 108.0, 108.0, 108.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, angle, angle, 1.0, 1.0))
            t += 200L
        }

        assertEquals(1, state.repCount)
        assertTrue(state.headline!!.contains("108도까지 굽혀졌어요"))
        assertNull(state.suggestion)
    }

    @Test
    fun aDeepButFastRepGetsSpeedTruthfulCopyNotShallowCopy() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()

        var t = 0L
        for (angle in listOf(175.0, 175.0, 95.0, 95.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, angle, angle, 1.0, 1.0))
            t += 100L
        }

        assertEquals(0, state.repCount)
        assertEquals(1, state.uncountedAttemptCount)
        assertTrue(state.headline!!.contains("빨라 횟수로 세지 않았어요"))
        assertFalse(state.headline!!.contains("얕아"))
    }

    @Test
    fun aFarSideConfidenceBurstAtTheBottomDoesNotDoubleCount() {
        val session = HeuristicFormCheckSession(FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE)
        var state = session.initialSnapshot()

        fun accept(t: Long, left: Double, right: Double, leftConf: Double, rightConf: Double) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, left, right, leftConf, rightConf))
        }

        // Near side is RIGHT (front leg, bending); far side LEFT (rear leg, straight).
        var t = 0L
        for (angle in listOf(172.0, 172.0, 140.0, 120.0, 100.0, 100.0)) {
            accept(t, 165.0, angle, 0.4, 0.9)
            t += 200L
        }
        // Far-side burst at the bottom: LEFT becomes briefly more confident while straight.
        repeat(3) {
            accept(t, 165.0, 100.0, 0.95, 0.9)
            t += 100L
        }
        // Real stand-up on the near side.
        for (angle in listOf(100.0, 130.0, 160.0, 172.0, 172.0)) {
            accept(t, 165.0, angle, 0.4, 0.9)
            t += 200L
        }

        assertEquals("A far-side burst must not fabricate a repetition", 1, state.repCount)
    }

    @Test
    fun theReportedMinimumIsTheRawObservationNotTheSmoothedOne() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        var completed: RepCycleEvent.Completed? = null

        // Fast cadence: smoothing lag would overstate the minimum by several degrees.
        var t = 0L
        for (angle in listOf(
            175.0, 175.0, 130.0, 95.0, 95.0, 95.0, 95.0, 95.0, 130.0, 175.0, 175.0,
        )) {
            val event = detector.accept(t, angle)
            if (event is RepCycleEvent.Completed) completed = event
            t += 100L
        }

        assertNotNull(completed)
        assertEquals(95.0, completed!!.minimumAngleDegrees, 1e-6)
    }

    @Test
    fun theTailOfAnOvertimeExcursionIsNotCountedAsARep() {
        val detector = RepCycleDetector(bottomEnterDegrees = 110.0)
        detector.accept(0L, 175.0)
        detector.accept(200L, 100.0)
        // Held at the bottom past the maximum duration; the excursion is discarded there.
        detector.accept(11_000L, 100.0)

        // The eventual ascent alone must not become a fresh counted repetition.
        var counted = false
        for ((timestamp, angle) in listOf(
            11_200L to 100.0, 11_700L to 100.0, 12_200L to 175.0, 12_400L to 175.0,
        )) {
            if (detector.accept(timestamp, angle) is RepCycleEvent.Completed) counted = true
        }
        assertFalse(counted)
    }

    // ---- start criteria ----

    @Test
    fun readinessReportsExactlyWhichRequiredJointsAreMissing() {
        val required = FormCheckExercise.BARBELL_SQUAT.requiredJoints
        val frame = frameWithMissingGroups(setOf(FormCheckJointGroup.ANKLE))

        val readiness = FormCheckGeometry.readiness(frame, required)

        assertFalse(readiness.ready)
        assertEquals(setOf(FormCheckJointGroup.ANKLE), readiness.missingGroups)
        assertNull(readiness.side)
    }

    @Test
    fun readinessIgnoresEverythingOutsideTheRequiredJoints() {
        // Head and shoulders absent entirely: a knee angle never needed them.
        val required = FormCheckExercise.BARBELL_SQUAT.requiredJoints
        val frame = frameWithMissingGroups(emptySet())

        val readiness = FormCheckGeometry.readiness(frame, required)

        assertTrue(readiness.ready)
        assertTrue(readiness.missingGroups.isEmpty())
        assertNotNull(readiness.side)
    }

    @Test
    fun theExerciseStartsWithoutAnyWholeBodySideViewToken() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)

        val state = session.accept(
            timestampMs = 0L,
            hasPrimaryPersonLock = true,
            preferredViewQualified = false,
            frame = frameWithKneeAngles(0L, 170.0, 170.0, 1.0, 1.0),
        )

        assertTrue("Required joints are visible, so the exercise must start", state.started)
        assertTrue("A non-lateral view is a quality note, not a blocker", state.preferredViewSuggested)
    }

    @Test
    fun aMissingRequiredJointReportsItRatherThanFailingSilently() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)

        val state = session.accept(
            timestampMs = 0L,
            hasPrimaryPersonLock = true,
            preferredViewQualified = true,
            frame = frameWithMissingGroups(setOf(FormCheckJointGroup.ANKLE)),
        )

        assertFalse(state.started)
        assertEquals(FormCheckStartState.WAITING_FOR_JOINTS, state.startState)
        assertEquals(setOf(FormCheckJointGroup.ANKLE), state.missingJoints)
    }

    @Test
    fun anUnlockedPersonIsDistinguishedFromMissingJoints() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)

        val state = session.accept(
            timestampMs = 0L,
            hasPrimaryPersonLock = false,
            preferredViewQualified = true,
            frame = frameWithKneeAngles(0L, 170.0, 170.0, 1.0, 1.0),
        )

        assertEquals(FormCheckStartState.WAITING_FOR_PERSON, state.startState)
    }

    // ---- voice ----

    /** Timing knobs zeroed: these tests are about the wording, and the timing has its own. */
    private fun immediateAnnouncer(repeatIntervalMs: Long = 8_000L) = FormCheckStartAnnouncer(
        repeatIntervalMs = repeatIntervalMs,
        stabilityMs = 0L,
        minimumGapMs = 0L,
    )

    @Test
    fun theAnnouncerNamesTheMissingJointsWithTheRightParticle() {
        val announcer = immediateAnnouncer()
        val spec = FormCheckExercise.BARBELL_SQUAT

        val ankle = announcer.onState(0L, spec, waiting(setOf(FormCheckJointGroup.ANKLE)))
        assertEquals("발목이 화면에 보이게 서 주세요", ankle)

        val hip = announcer.onState(20_000L, spec, waiting(setOf(FormCheckJointGroup.HIP)))
        assertEquals("엉덩이가 화면에 보이게 서 주세요", hip)
    }

    @Test
    fun theAnnouncerStaysQuietUntilTheSituationChanges() {
        val announcer = immediateAnnouncer(repeatIntervalMs = 8_000L)
        val spec = FormCheckExercise.BARBELL_SQUAT
        val missing = waiting(setOf(FormCheckJointGroup.ANKLE))

        assertNotNull(announcer.onState(0L, spec, missing))
        assertNull("The same situation must not repeat immediately", announcer.onState(1_000L, spec, missing))
        assertNull(announcer.onState(7_000L, spec, missing))
        assertNotNull("After the interval it may remind once", announcer.onState(9_000L, spec, missing))
    }

    @Test
    fun theAnnouncerSaysTheExerciseStartedAndSuggestsTheSideView() {
        val announcer = immediateAnnouncer()
        val spec = FormCheckExercise.BARBELL_SQUAT

        val started = announcer.onState(0L, spec, startedState(preferredViewSuggested = true))
        assertNotNull(started)
        assertTrue(started!!.contains("시작"))
        assertTrue(started.contains("옆모습"))

        // The start is announced once per set; a fresh set gets a fresh announcer.
        val lateral = immediateAnnouncer()
            .onState(0L, spec, startedState(preferredViewSuggested = false))
        assertEquals("자세 체크를 시작할게요", lateral)
    }

    @Test
    fun aFlappingSituationIsNeverSpoken() {
        // The real-device failure this exists for: a lateral stance makes the person lock flap
        // about once a second, and deduplicating only against the immediately-previous phrase
        // let every flip through — the guidance became a metronome. A situation must now hold
        // for the stability window before it is spoken at all, so alternation is pure silence.
        val announcer = FormCheckStartAnnouncer(
            repeatIntervalMs = 8_000L,
            stabilityMs = 1_200L,
            minimumGapMs = 2_500L,
        )
        val spec = FormCheckExercise.BARBELL_SQUAT
        val lost = pausedWaiting(setOf(FormCheckJointGroup.ANKLE))
        val running = startedState(preferredViewSuggested = false)

        assertNotNull("The first start is the exception and speaks at once",
            announcer.onState(0L, spec, running))

        var t = 1_000L
        repeat(10) {
            assertNull("A flap must not be spoken", announcer.onState(t, spec, lost))
            t += 600L
            assertNull("Nor its return", announcer.onState(t, spec, running))
            t += 600L
        }
    }

    @Test
    fun aPersistentPauseIsSpokenOnceAndItsResumeOnce() {
        val announcer = FormCheckStartAnnouncer(
            repeatIntervalMs = 8_000L,
            stabilityMs = 1_200L,
            minimumGapMs = 2_500L,
        )
        val spec = FormCheckExercise.BARBELL_SQUAT
        val running = startedState(preferredViewSuggested = false)
        val lostPerson = FormCheckUiState(
            repCount = 0,
            uncountedAttemptCount = 0,
            startState = FormCheckStartState.WAITING_FOR_PERSON,
            hasEverStarted = true,
            repMarks = emptyList(),
            missingJoints = setOf(FormCheckJointGroup.HIP, FormCheckJointGroup.KNEE, FormCheckJointGroup.ANKLE),
            preferredViewSuggested = false,
            headline = null,
            suggestion = null,
        )

        assertNotNull(announcer.onState(0L, spec, running))
        // The pause holds long enough to be real; the wording is the pause, not setup guidance.
        assertNull(announcer.onState(3_000L, spec, lostPerson))
        val paused = announcer.onState(4_400L, spec, lostPerson)
        assertEquals(HeuristicFormCheckDeclaration.PAUSED_PERSON, paused)

        // The resume is worth a word only because the pause got one.
        assertNull(announcer.onState(7_000L, spec, running))
        val resumed = announcer.onState(8_300L, spec, running)
        assertEquals(HeuristicFormCheckDeclaration.RESUMED, resumed)

        // A silent blip earns a silent resume.
        assertNull(announcer.onState(9_000L, spec, lostPerson))
        assertNull(announcer.onState(9_300L, spec, running))
        assertNull(announcer.onState(11_000L, spec, running))
    }

    @Test
    fun theRetryDelayLetsAPendingSituationBecomeSpeakable() {
        val announcer = FormCheckStartAnnouncer(
            repeatIntervalMs = 8_000L,
            stabilityMs = 1_200L,
            minimumGapMs = 2_500L,
        )
        val spec = FormCheckExercise.BARBELL_SQUAT
        val missing = waiting(setOf(FormCheckJointGroup.ANKLE))

        assertNull("Not yet stable", announcer.onState(0L, spec, missing))
        val delay = announcer.retryDelayMs(0L)
        assertNotNull("Something is pending, so a retry is scheduled", delay)
        assertEquals(1_200L, delay)
        assertNotNull("After the wait the same call speaks", announcer.onState(1_200L, spec, missing))
    }

    @Test
    fun theSideViewSuggestionNamesTheJointTheExerciseMeasures() {
        // Telling a push-up about a knee would describe a joint the track never looked at.
        assertEquals("무릎이", FormCheckStartAnnouncer.viewNoteSubject(FormCheckExercise.BARBELL_SQUAT))
        assertEquals("팔꿈치가", FormCheckStartAnnouncer.viewNoteSubject(FormCheckExercise.PUSH_UP))
        assertEquals("엉덩이가", FormCheckStartAnnouncer.viewNoteSubject(FormCheckExercise.GOOD_MORNING))

        val announcer = FormCheckStartAnnouncer()
        val spoken = announcer.onState(
            0L,
            FormCheckExercise.PUSH_UP,
            startedState(preferredViewSuggested = true),
        )
        assertNotNull(spoken)
        assertTrue("Expected the elbow named, got $spoken", spoken!!.contains("팔꿈치가"))
        assertFalse(spoken.contains("무릎"))
    }

    @Test
    fun theAnnouncerSaysNothingBeforeTheCameraIsReady() {
        val announcer = FormCheckStartAnnouncer()
        val state = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT).initialSnapshot()

        assertNull(announcer.onState(0L, FormCheckExercise.BARBELL_SQUAT, state))
    }

    @Test
    fun everySupportedExerciseHasCoherentThresholds() {
        // Ordering is only meaningful in the detector's space, where a smaller number always
        // means more work; in raw angles an extension exercise reads the other way round.
        for (spec in FormCheckExercise.entries) {
            assertTrue(
                "${spec.name}: the reached line must be at least as far as the rep line",
                spec.toDetector(spec.reachedAngleDegrees) <= spec.toDetector(spec.repAngleDegrees),
            )
            assertTrue(
                "${spec.name}: the rep line must sit past the attempt boundary",
                spec.toDetector(spec.repAngleDegrees) < spec.toDetector(spec.attemptAngleDegrees),
            )
            assertTrue(
                "${spec.name}: the attempt boundary must sit past the resting angle",
                spec.toDetector(spec.attemptAngleDegrees) < spec.toDetector(spec.restAngleDegrees),
            )
            assertTrue(spec.setupHint.isNotBlank())
            assertTrue(FormCheckExercise.supports(spec.exercise))
        }
    }

    @Test
    fun everySupportedExerciseCountsARepetitionFromItsOwnThresholds() {
        // A table row is only useful if a movement described by that row actually counts. Driving
        // each exercise through rest -> its rep line -> rest catches an entry whose thresholds
        // cannot be satisfied by any real excursion.
        for (spec in FormCheckExercise.entries) {
            if (spec.cadence == FormCheckCadence.HOLD) continue
            val session = HeuristicFormCheckSession(spec)
            var state = session.initialSnapshot()
            // Both ends clear their thresholds rather than sitting on them. Resting exactly on
            // the line never completes, because the smoothed angle only approaches it
            // asymptotically; working exactly on it is decided by the last bit of the angle the
            // fixture reconstructs. Real movements clear both, so the fixture does too.
            val work = spec.fromDetector(
                (spec.toDetector(spec.reachedAngleDegrees) - 3.0).coerceAtLeast(0.0),
            )
            val rest = spec.fromDetector(
                (spec.toDetector(spec.restAngleDegrees) + 25.0).coerceAtMost(180.0),
            )
            var t = 0L
            for (angle in listOf(rest, rest, work, work, work, work, rest, rest)) {
                state = session.accept(t, true, true, frameForDriver(spec, angle, t))
                t += 200L
            }
            assertEquals("${spec.name} must count one repetition", 1, state.repCount)
            assertNull("${spec.name} reached its line, so it needs no hint", state.suggestion)
        }
    }

    // ---- the guard chain ----

    @Test
    fun guardReportsTheJointThatMovedDuringACountedRepetition() {
        // A curl counted on the elbow whose shoulder swung to 70 degrees mid-repetition: the
        // count stands, and the completed observation names the swing with its measured angle.
        val spec = FormCheckExercise.BARBELL_CURL
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        for ((elbow, shoulder) in listOf(
            175.0 to 30.0, 175.0 to 30.0,
            95.0 to 30.0, 95.0 to 70.0, 95.0 to 30.0,
            175.0 to 30.0, 175.0 to 30.0,
        )) {
            state = session.accept(t, true, true, curlFrame(elbow, shoulder, t))
            t += 200L
        }

        assertEquals(1, state.repCount)
        val headline = requireNotNull(state.headline)
        assertTrue(headline, headline.contains("어깨가 70도까지 벌어졌어요"))
        assertNull("A guard observes; it never suggests", state.suggestion)
    }

    @Test
    fun guardStaysSilentWhenTheJointStaysPut() {
        val spec = FormCheckExercise.BARBELL_CURL
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        for (elbow in listOf(175.0, 175.0, 95.0, 95.0, 95.0, 175.0, 175.0)) {
            state = session.accept(t, true, true, curlFrame(elbow, shoulder = 30.0, t = t))
            t += 200L
        }

        assertEquals(1, state.repCount)
        val headline = requireNotNull(state.headline)
        assertFalse("A held joint is not an observation", headline.contains("벌어졌"))
    }

    @Test
    fun movementBetweenRepetitionsNeverReachesTheGuard() {
        // A stretch or a head-scratch at rest swings the shoulder far past the limit, but the
        // excursion has not armed, so the next counted repetition must not report it.
        val spec = FormCheckExercise.BARBELL_CURL
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        for ((elbow, shoulder) in listOf(
            175.0 to 30.0, 175.0 to 160.0, 175.0 to 30.0,
            95.0 to 30.0, 95.0 to 30.0, 95.0 to 30.0,
            175.0 to 30.0, 175.0 to 30.0,
        )) {
            state = session.accept(t, true, true, curlFrame(elbow, shoulder, t))
            t += 200L
        }

        assertEquals(1, state.repCount)
        val headline = requireNotNull(state.headline)
        assertFalse(
            "Rest-window movement leaked into the repetition's guard",
            headline.contains("벌어졌"),
        )
    }

    @Test
    fun guardAbstainsWhenItsOwnJointsAreHidden() {
        // The guard chain needs the hip; the elbow driver does not. Hiding the hip silences the
        // guard and nothing else — an unobserved joint is not evidence in either direction.
        val spec = FormCheckExercise.BARBELL_CURL
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        for (elbow in listOf(175.0, 175.0, 95.0, 95.0, 95.0, 175.0, 175.0)) {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = 175.0,
                    hipAngleDegrees = 175.0,
                    elbowAngleDegrees = elbow,
                    timestampMs = t,
                    dropped = setOf(FormCheckJointGroup.HIP),
                ),
            )
            t += 200L
        }

        assertEquals("The count never depended on the guard", 1, state.repCount)
        val headline = requireNotNull(state.headline)
        assertFalse(headline.contains("벌어졌"))
    }

    @Test
    fun aMinGuardReportsAnArmThatNeverFolded() {
        // The side crunch's guard reads the other way: hands behind the head keep the elbow at or
        // under 94 degrees at some point in every repetition, so an elbow that never came down to
        // it is the observation.
        val spec = FormCheckExercise.STANDING_SIDE_CRUNCH
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        for (hip in listOf(175.0, 175.0, 120.0, 120.0, 120.0, 175.0, 175.0)) {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = 175.0,
                    hipAngleDegrees = hip,
                    elbowAngleDegrees = 150.0,
                    timestampMs = t,
                ),
            )
            t += 200L
        }

        assertEquals(1, state.repCount)
        val headline = requireNotNull(state.headline)
        assertTrue(headline, headline.contains("팔꿈치가 150도까지만 굽혀졌어요"))
    }

    @Test
    fun aMinGuardStaysSilentWhenTheArmStayedFolded() {
        val spec = FormCheckExercise.STANDING_SIDE_CRUNCH
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        for (hip in listOf(175.0, 175.0, 120.0, 120.0, 120.0, 175.0, 175.0)) {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = 175.0,
                    hipAngleDegrees = hip,
                    elbowAngleDegrees = 70.0,
                    timestampMs = t,
                ),
            )
            t += 200L
        }

        assertEquals(1, state.repCount)
        val headline = requireNotNull(state.headline)
        assertFalse(headline.contains("팔꿈치가"))
    }

    @Test
    fun theCountAnnouncerSpeaksEachCountOnceAndConsumesWhilePaused() {
        val announcer = FormCheckCountAnnouncer()

        assertNull("Zero is the absence of a count", announcer.onCount(0))
        assertEquals("1회", announcer.onCount(1))
        assertNull("The same count is never repeated", announcer.onCount(1))
        assertEquals("2회", announcer.onCount(2))
        assertNull("A paused count is consumed, not deferred", announcer.onCount(3, muted = true))
        assertNull("…so resuming does not announce it late", announcer.onCount(3))
        assertEquals("4회", announcer.onCount(4))
        assertNull("A fresh set resets the baseline silently", announcer.onCount(0))
        assertEquals("1회", announcer.onCount(1))
    }

    private fun curlFrame(elbow: Double, shoulder: Double, t: Long) = frameWithChains(
        kneeAngleDegrees = 175.0,
        hipAngleDegrees = 175.0,
        elbowAngleDegrees = elbow,
        shoulderAngleDegrees = shoulder,
        timestampMs = t,
    )

    // ---- what the surface is allowed to draw ----

    @Test
    fun abstentionClearsTheLiveReadingOnEveryPath() {
        // Policy §3.1. The surface draws the live reading on the body, so a value that survived an
        // abstention would leave an angle glowing on a joint the camera can no longer see — the
        // "판정 불가를 다른 값으로 위장" the clause forbids. Every giving-up path is checked, because
        // one that forgets is invisible until somebody walks out of frame mid-set.
        val lostLock = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        lostLock.accept(0L, true, true, frameForDriver(FormCheckExercise.BARBELL_SQUAT, 170.0, 0L))
        assertNotNull(lostLock.liveReading)
        lostLock.accept(200L, false, true, frameForDriver(FormCheckExercise.BARBELL_SQUAT, 170.0, 200L))
        assertNull("Losing the person lock must clear the reading", lostLock.liveReading)

        val lostJoint = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        lostJoint.accept(0L, true, true, frameForDriver(FormCheckExercise.BARBELL_SQUAT, 170.0, 0L))
        assertNotNull(lostJoint.liveReading)
        lostJoint.accept(200L, true, true, frameWithMissingGroups(setOf(FormCheckJointGroup.ANKLE)))
        assertNull("A missing required joint must clear the reading", lostJoint.liveReading)

        val belowConfidence = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        belowConfidence.accept(0L, true, true, frameForDriver(FormCheckExercise.BARBELL_SQUAT, 170.0, 0L))
        assertNotNull(belowConfidence.liveReading)
        belowConfidence.accept(
            200L,
            true,
            true,
            frameWithChains(
                kneeAngleDegrees = 170.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = 175.0,
                timestampMs = 200L,
                confidence = 0.54,
            ),
        )
        assertNull("A chain under the confidence floor must clear the reading", belowConfidence.liveReading)

        val reversed = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        reversed.accept(400L, true, true, frameForDriver(FormCheckExercise.BARBELL_SQUAT, 170.0, 400L))
        assertNotNull(reversed.liveReading)
        reversed.accept(200L, true, true, frameForDriver(FormCheckExercise.BARBELL_SQUAT, 170.0, 200L))
        assertNull("A backwards timestamp must clear the reading", reversed.liveReading)
    }

    @Test
    fun repMarksQuantiseToThePolicyFloor() {
        // Policy §4.4 silences a self-comparison under fifteen degrees because the random part of
        // the measurement error was never measured. The mark carries the same gate as the
        // sentence, so a surface cannot draw a difference the wording refuses to speak.
        // The curl counts anything at or under 120 degrees, so all three comparisons below stay
        // inside the counted band and the only thing under test is the floor itself.
        val spec = FormCheckExercise.BARBELL_CURL
        val inside = markRelations(spec, listOf(90.0, 90.0, 104.0))
        assertEquals(
            "A fourteen-degree difference is inside what the measurement could have invented",
            FormCheckBaselineRelation.SAME,
            inside.last(),
        )

        val outside = markRelations(spec, listOf(90.0, 90.0, 107.0))
        assertEquals(
            "A seventeen-degree shortfall is the set's own comparison and may be reported",
            FormCheckBaselineRelation.BELOW,
            outside.last(),
        )

        val deeper = markRelations(spec, listOf(90.0, 90.0, 74.0))
        assertEquals(FormCheckBaselineRelation.BEYOND, deeper.last())

        // The opening repetitions have nothing to compare against and must not pretend otherwise.
        assertTrue(inside.take(2).all { it == FormCheckBaselineRelation.SAME })
    }

    @Test
    fun noMarkIsBankedForAnExcursionAbstentionDiscarded() {
        // The same discipline the hold detector documents: time that was not observed is not time
        // the user held anything, and a repetition that was not observed to its end is not one.
        val spec = FormCheckExercise.PUSH_UP
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        for ((t, angle) in listOf(0L to 175.0, 200L to 175.0, 400L to 100.0, 600L to 100.0)) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
        }
        assertTrue("The excursion is in flight", state.repMarks.isEmpty())

        // The person is lost at the bottom and comes back standing.
        state = session.accept(800L, false, true, elbowFrame(100.0, 800L))
        for ((t, angle) in listOf(1_000L to 175.0, 1_200L to 175.0, 1_400L to 175.0)) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
        }

        assertEquals("A discarded excursion leaves no mark", 0, state.repMarks.size)
        assertEquals(0, state.repCount)
        assertEquals(0, state.uncountedAttemptCount)
    }

    @Test
    fun anUncountedExcursionIsMarkedWithItsTruthfulReason() {
        val spec = FormCheckExercise.PUSH_UP
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        // Deep enough to arm, never deep enough to count.
        for ((t, angle) in listOf(
            0L to 175.0,
            200L to 175.0,
            400L to 138.0,
            600L to 138.0,
            800L to 175.0,
            1_000L to 175.0,
        )) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
        }

        assertEquals(1, state.repMarks.size)
        val mark = state.repMarks.single()
        assertEquals(FormCheckRepEventKind.SHALLOW, mark.kind)
        // An uncounted excursion never joined the repetitions the baseline is made of.
        assertEquals(FormCheckBaselineRelation.SAME, mark.baselineRelation)
        assertEquals(
            "The mark carries the same observation the surface would have shown",
            state.headline,
            mark.observation,
        )
    }

    @Test
    fun aOneSidedExcursionOnABilateralExerciseIsNotCounted() {
        // The real-device failure this exists for: standing still and raising one knee bends
        // that knee through exactly the arc a squat does, and the single-side model counted it.
        // The still leg, concurrently observed, is what tells the two movements apart.
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()
        fun frame(t: Long, left: Double, right: Double) =
            session.accept(t, true, true, frameWithKneeAngles(t, left, right, 1.0, 0.9))
                .also { state = it }

        frame(0L, 175.0, 175.0)
        frame(200L, 175.0, 175.0)
        // The measured (left) knee dives; the right knee stands at 175 throughout.
        for ((index, angle) in listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0).withIndex()) {
            frame(400L + index * 200L, angle, 175.0)
        }
        frame(1_800L, 175.0, 175.0)
        frame(2_000L, 175.0, 175.0)

        assertEquals("A one-sided excursion is not a squat", 0, state.repCount)
        assertEquals(1, state.uncountedAttemptCount)
        val mark = state.repMarks.single()
        assertEquals(FormCheckRepEventKind.ASYMMETRIC, mark.kind)
        assertEquals("양쪽 무릎이 서로 다르게 움직여서 횟수로 세지 않았어요", state.headline)
        assertEquals(state.headline, mark.observation)
        assertNull("An asymmetric discard urges nothing", state.suggestion)
    }

    @Test
    fun aBilateralRepetitionWithBothKneesTravellingCounts() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()
        fun frame(t: Long, both: Double) =
            session.accept(t, true, true, frameWithKneeAngles(t, both, both, 1.0, 0.9))
                .also { state = it }

        frame(0L, 175.0)
        frame(200L, 175.0)
        for ((index, angle) in listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0).withIndex()) {
            frame(400L + index * 200L, angle)
        }
        frame(1_800L, 175.0)
        frame(2_000L, 175.0)

        assertEquals("Both knees travelled together: an ordinary squat", 1, state.repCount)
        assertEquals(0, state.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.COUNTED, state.repMarks.single().kind)
    }

    @Test
    fun theCoherenceCheckAbstainsWhenTheOppositeSideIsUnobserved() {
        // The recommended stance is lateral, where the far side is occluded. The documented
        // single-side limitation stands there: no concurrent observation, no check, the count
        // proceeds on the side the camera can see.
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()
        fun frame(t: Long, left: Double) =
            session.accept(t, true, true, frameWithKneeAngles(t, left, 175.0, 1.0, 0.2))
                .also { state = it }

        frame(0L, 175.0)
        frame(200L, 175.0)
        for ((index, angle) in listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0).withIndex()) {
            frame(400L + index * 200L, angle)
        }
        frame(1_800L, 175.0)
        frame(2_000L, 175.0)

        assertEquals("An invisible far side is not evidence against the repetition", 1, state.repCount)
        assertEquals(0, state.uncountedAttemptCount)
    }

    @Test
    fun aUnilateralExerciseNeverRunsTheCoherenceCheck() {
        // A standing knee-up is one-sided by definition: the other leg standing still is the
        // exercise being done correctly, not a discrepancy — and the definition gates agree,
        // because the raised knee folds and the standing hip stands.
        val spec = FormCheckExercise.STANDING_KNEE_UP
        assertFalse(spec.bilateralDriver)

        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        // The left leg raises — hip folds, shin hangs — while the right leg stands straight.
        fun frame(t: Long, hip: Double, knee: Double): FormCheckUiState {
            state = session.accept(
                t,
                true,
                true,
                frameWithChains(
                    kneeAngleDegrees = knee,
                    hipAngleDegrees = hip,
                    elbowAngleDegrees = 175.0,
                    timestampMs = t,
                    oppositeKneeAngleDegrees = 175.0,
                    oppositeHipAngleDegrees = 175.0,
                ),
            )
            return state
        }

        frame(0L, 175.0, 175.0)
        frame(200L, 175.0, 175.0)
        for ((index, angle) in listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0).withIndex()) {
            frame(400L + index * 200L, angle, angle)
        }
        frame(1_800L, 175.0, 175.0)
        frame(2_000L, 175.0, 175.0)

        assertEquals(1, state.repCount)
        assertEquals(0, state.uncountedAttemptCount)
    }

    // ---- definition gates: the movement, not just the driver arc ----

    /** Runs one excursion of [spec] through frames built per step, returning the final state. */
    private fun runExcursion(
        spec: FormCheckExercise,
        restAngle: Double,
        workAngles: List<Double>,
        frame: (t: Long, driverAngle: Double) -> PoseFrame,
    ): FormCheckUiState {
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        fun step(angle: Double) {
            state = session.accept(t, true, true, frame(t, angle))
            t += 200L
        }
        step(restAngle)
        step(restAngle)
        for (angle in workAngles) step(angle)
        step(restAngle)
        step(restAngle)
        step(restAngle)
        return state
    }

    @Test
    fun aKneeOnlyDipIsNotASquat() {
        // Bouncing on the ankles bends the knees through a squat's whole arc while the hips
        // stay near straight. The definition says a squat sits back, so the excursion is
        // reported with the joint that fell short rather than counted.
        val depths = listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0)
        val bounce = runExcursion(FormCheckExercise.BARBELL_SQUAT, 175.0, depths) { t, knee ->
            frameWithChains(
                kneeAngleDegrees = knee,
                hipAngleDegrees = 170.0,
                elbowAngleDegrees = 175.0,
                timestampMs = t,
            )
        }
        assertEquals(0, bounce.repCount)
        assertEquals(1, bounce.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.INCOMPLETE, bounce.repMarks.single().kind)
        assertEquals("엉덩이가 170도까지만 굽혀져서 횟수로 세지 않았어요", bounce.headline)
        assertNull("A definition shortfall urges nothing", bounce.suggestion)

        // The same knee arc with the hips travelling is simply a squat.
        val squat = runExcursion(FormCheckExercise.BARBELL_SQUAT, 175.0, depths) { t, knee ->
            frameWithChains(
                kneeAngleDegrees = knee,
                hipAngleDegrees = knee,
                elbowAngleDegrees = 175.0,
                timestampMs = t,
            )
        }
        assertEquals(1, squat.repCount)
        assertEquals(0, squat.uncountedAttemptCount)
    }

    @Test
    fun oneGrazingFrameDoesNotSatisfyTheSquatsHipClause() {
        // The bridge sweep measured a true 140-150 degree hip reading at or under 140 on 27.7%
        // of frames, so a single frame at the extreme is not evidence the hip went there. The
        // squat's clause demands three satisfying frames and is judged on the third-best
        // reading — which is also the number it reports, so the sentence stays literally true.
        val knees = listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0)
        fun run(hips: List<Double>): FormCheckUiState {
            val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
            var state = session.initialSnapshot()
            var t = 0L
            var work = 0
            fun step(knee: Double, hip: Double) {
                state = session.accept(
                    t,
                    true,
                    true,
                    frameWithChains(
                        kneeAngleDegrees = knee,
                        hipAngleDegrees = hip,
                        elbowAngleDegrees = 175.0,
                        timestampMs = t,
                    ),
                )
                t += 200L
            }
            step(175.0, 170.0)
            step(175.0, 170.0)
            for (knee in knees) {
                step(knee, hips[work])
                work += 1
            }
            step(175.0, 170.0)
            step(175.0, 170.0)
            step(175.0, 170.0)
            return state
        }

        // Two frames graze the bound; the third-best reading is 170 and the excursion is
        // reported with that sustained angle, not with the instant it grazed.
        val grazed = run(listOf(170.0, 120.0, 120.0, 170.0, 170.0, 170.0))
        assertEquals(0, grazed.repCount)
        assertEquals(1, grazed.uncountedAttemptCount)
        assertEquals("엉덩이가 170도까지만 굽혀져서 횟수로 세지 않았어요", grazed.headline)

        // Three frames at the same depth are a position the hip actually held.
        val held = run(listOf(170.0, 120.0, 120.0, 120.0, 170.0, 170.0))
        assertEquals(1, held.repCount)
        assertEquals(0, held.uncountedAttemptCount)
    }

    @Test
    fun aForwardBowIsNotAKneeUp() {
        // A bow flexes the hip driver exactly like a raise, but the knees stay straight — the
        // dataset's own condition for this exercise is that the knee comes up.
        val arc = listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0)
        val bow = runExcursion(FormCheckExercise.STANDING_KNEE_UP, 175.0, arc) { t, hip ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = hip,
                elbowAngleDegrees = 175.0,
                timestampMs = t,
            )
        }
        assertEquals(0, bow.repCount)
        assertEquals(1, bow.uncountedAttemptCount)
        assertEquals("무릎이 175도까지만 굽혀져서 횟수로 세지 않았어요", bow.headline)
    }

    @Test
    fun aSquatPatternIsNotAKneeUp() {
        // Knees and hips folding on both sides is a squat; the knee-up's standing leg is part
        // of its definition, so the opposite hip folding along is the truthful reason.
        val arc = listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0)
        val squat = runExcursion(FormCheckExercise.STANDING_KNEE_UP, 175.0, arc) { t, hip ->
            frameWithChains(
                kneeAngleDegrees = hip,
                hipAngleDegrees = hip,
                elbowAngleDegrees = 175.0,
                timestampMs = t,
            )
        }
        assertEquals(0, squat.repCount)
        assertEquals(1, squat.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.INCOMPLETE, squat.repMarks.single().kind)
        assertEquals("반대쪽 엉덩이가 100도까지 함께 굽혀져서 횟수로 세지 않았어요", squat.headline)
    }

    @Test
    fun theSetsReferenceDistanceIsSaidOnlyWhenTheCameraCouldHaveSeenIt() {
        // The lat pull-down is the one exercise that may state a distance: its counted band is
        // 63 degrees against a shoulder floor of 25, and the floor was measured on this very
        // exercise's clips. Three outcomes, and the middle one is the reason the feature is
        // trustworthy — a difference the measurement could have invented is named as unsayable
        // rather than printed as a digit.
        fun setSummaryFor(shoulderExtreme: Double): FormCheckSetSummary {
            val session = HeuristicFormCheckSession(FormCheckExercise.LAT_PULLDOWN)
            var t = 0L
            fun step(shoulder: Double) {
                session.accept(
                    t,
                    true,
                    true,
                    frameWithChains(
                        kneeAngleDegrees = 175.0,
                        hipAngleDegrees = 175.0,
                        elbowAngleDegrees = 100.0,
                        shoulderAngleDegrees = shoulder,
                        timestampMs = t,
                    ),
                )
                t += 200L
            }
            step(170.0)
            step(170.0)
            for (angle in listOf(125.0, shoulderExtreme, shoulderExtreme, shoulderExtreme, 125.0)) {
                step(angle)
            }
            step(170.0)
            step(170.0)
            step(170.0)
            return session.summary()
        }

        // The line is 67 and a smaller angle is more work, so a shortfall means stopping
        // ABOVE it. 94 is 27 degrees short — past the shoulder chain's 25-degree floor.
        val wide = setSummaryFor(94.0)
        assertEquals(1, wide.repCount)
        assertEquals(
            "이번 세트에서 가장 많이 모은 반복은 기준 각도와 27도 차이였어요",
            wide.referenceGapLine,
        )

        // 74 is 7 degrees short: inside what the extreme could have invented, so the difference
        // is named as unsayable rather than printed.
        val narrow = setSummaryFor(74.0)
        assertEquals(1, narrow.repCount)
        assertEquals(
            HeuristicFormCheckDeclaration.REFERENCE_GAP_BELOW_FLOOR,
            narrow.referenceGapLine,
        )

        // 40 is past the line entirely: nothing to report, and the silence is the message.
        val reached = setSummaryFor(40.0)
        assertEquals(1, reached.repCount)
        assertNull("Past the line there is nothing to report", reached.referenceGapLine)

        // The reference itself accompanies the distance, always.
        assertEquals("이 운동이 보는 기준 각도는 어깨 67도예요", wide.referenceLine)
    }

    @Test
    fun aSealedExerciseSaysThatItDoesNotStateADistance() {
        // Silence about the silence is what turns a deliberate absence into a suspected bug, so
        // the sealed exercises name the decision instead of leaving a blank (§4.10).
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        val summary = session.summary()
        assertEquals(HeuristicFormCheckDeclaration.REFERENCE_SEALED, summary.referenceLine)
        assertNull(summary.referenceGapLine)
    }

    @Test
    fun anUncalibratedExerciseStatesNoReferenceAtAll() {
        // Telling somebody they are twenty degrees off an invented number is worse than the
        // vague hint they get today, so an unfitted threshold names no line and no distance.
        val session = HeuristicFormCheckSession(FormCheckExercise.FACE_PULL)
        val summary = session.summary()
        assertNull(summary.referenceLine)
        assertNull(summary.referenceGapLine)
    }

    @Test
    fun aFoldedBodyPullIsNotAPullUp() {
        // The elbow arc of a pull-up performed while the body folds — a seated row, a squatting
        // cable pull — flexes the hip past the measured floor of every hanging excursion. The
        // hip names the truthful reason, sustained for three frames per the STAY rule.
        val arc = listOf(135.0, 110.0, 105.0, 105.0, 110.0, 135.0)
        val folded = runExcursion(FormCheckExercise.PULL_UP, 175.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 170.0,
                hipAngleDegrees = 100.0,
                elbowAngleDegrees = elbow,
                timestampMs = t,
            )
        }
        assertEquals(0, folded.repCount)
        assertEquals(1, folded.uncountedAttemptCount)
        assertEquals("엉덩이가 100도까지 굽혀져서 횟수로 세지 않았어요", folded.headline)

        // The same arc with the body hanging straight is simply a pull-up.
        val hanging = runExcursion(FormCheckExercise.PULL_UP, 175.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 170.0,
                hipAngleDegrees = 172.0,
                elbowAngleDegrees = elbow,
                timestampMs = t,
            )
        }
        assertEquals(1, hanging.repCount)
        assertEquals(0, hanging.uncountedAttemptCount)
    }

    @Test
    fun aSquatPatternIsNotAGoodMorning() {
        // The hinge's dataset condition is "무릎 구부린채 고정": knees folding through the arc
        // make the movement a squat, however correct the hip hinge looked.
        val arc = listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0)
        val squat = runExcursion(FormCheckExercise.GOOD_MORNING, 175.0, arc) { t, hip ->
            frameWithChains(
                kneeAngleDegrees = 100.0,
                hipAngleDegrees = hip,
                elbowAngleDegrees = 175.0,
                timestampMs = t,
            )
        }
        assertEquals(0, squat.repCount)
        assertEquals("무릎이 100도까지 굽혀져서 횟수로 세지 않았어요", squat.headline)

        // Soft knees are the exercise: the same hinge on near-straight legs counts.
        val hinge = runExcursion(FormCheckExercise.GOOD_MORNING, 175.0, arc) { t, hip ->
            hipFrame(hip, t)
        }
        assertEquals(1, hinge.repCount)
    }

    @Test
    fun sittingUpFromAChairIsNotAHipThrust() {
        // Standing up extends the hips through the thrust's whole arc — with the knees
        // straightening under it, which the planted-feet bridge never does.
        val arc = listOf(130.0, 150.0, 165.0, 165.0, 165.0, 150.0)
        var kneeByStep = listOf(100.0, 120.0, 150.0, 170.0, 170.0, 150.0).iterator()
        val standUp = runExcursion(FormCheckExercise.HIP_THRUST, 100.0, arc) { t, hip ->
            frameWithChains(
                kneeAngleDegrees = if (kneeByStep.hasNext()) kneeByStep.next() else 100.0,
                hipAngleDegrees = hip,
                elbowAngleDegrees = 170.0,
                timestampMs = t,
            )
        }
        assertEquals(0, standUp.repCount)
        assertEquals(1, standUp.uncountedAttemptCount)
        assertEquals("무릎이 170도까지 펴져서 횟수로 세지 않았어요", standUp.headline)
    }

    @Test
    fun anArmSwingIsNotALatPulldown() {
        // The shoulder chain closes identically whether the bar was pulled or the straight arm
        // swung down; the elbows bending are what make it a pull.
        val arc = listOf(120.0, 60.0, 60.0, 60.0, 60.0, 120.0)
        val swing = runExcursion(FormCheckExercise.LAT_PULLDOWN, 155.0, arc) { t, shoulder ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = 170.0,
                shoulderAngleDegrees = shoulder,
                timestampMs = t,
            )
        }
        assertEquals(0, swing.repCount)
        assertEquals("팔꿈치가 170도까지만 굽혀져서 횟수로 세지 않았어요", swing.headline)

        val pull = runExcursion(FormCheckExercise.LAT_PULLDOWN, 155.0, arc) { t, shoulder ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = 80.0,
                shoulderAngleDegrees = shoulder,
                timestampMs = t,
            )
        }
        assertEquals(1, pull.repCount)
    }

    @Test
    fun aWaistPressIsNotAnOverheadPress() {
        // A press finishes overhead. The same elbow extension with the upper arm hanging at
        // the waist is a push-down's motion wearing this exercise's counter. Rest is fed past
        // the exercise's own rest line so the smoothed return actually crosses it.
        val arc = listOf(120.0, 150.0, 165.0, 165.0, 165.0, 165.0, 150.0)
        val waist = runExcursion(FormCheckExercise.OVERHEAD_PRESS, 90.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = elbow,
                shoulderAngleDegrees = 25.0,
                timestampMs = t,
            )
        }
        assertEquals(0, waist.repCount)
        assertEquals("어깨가 25도까지만 벌어져서 횟수로 세지 않았어요", waist.headline)

        val press = runExcursion(FormCheckExercise.OVERHEAD_PRESS, 90.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = elbow,
                shoulderAngleDegrees = 160.0,
                timestampMs = t,
            )
        }
        assertEquals(1, press.repCount)
    }

    @Test
    fun anOverheadSwingIsNotAPushDown() {
        // The dataset's condition for this exercise is "팔꿈치 위치 고정": the upper arms stay
        // pinned. Opened wide, the same elbow extension has become a press.
        val arc = listOf(120.0, 150.0, 165.0, 165.0, 165.0, 165.0, 150.0)
        val overhead = runExcursion(FormCheckExercise.CABLE_PUSH_DOWN, 90.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = elbow,
                shoulderAngleDegrees = 160.0,
                timestampMs = t,
            )
        }
        assertEquals(0, overhead.repCount)
        assertEquals("어깨가 160도까지 벌어져서 횟수로 세지 않았어요", overhead.headline)
    }

    @Test
    fun anOverheadPressIsNotACurl() {
        // Both bend the elbow through the curl's arc. What separates them is where the upper arm
        // goes: a press puts it in line with the torso, which no curl variant does.
        val arc = listOf(130.0, 110.0, 95.0, 95.0, 95.0, 110.0)
        val press = runExcursion(FormCheckExercise.BARBELL_CURL, 175.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = elbow,
                shoulderAngleDegrees = 165.0,
                timestampMs = t,
            )
        }
        assertEquals(0, press.repCount)
        assertEquals(1, press.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.INCOMPLETE, press.repMarks.single().kind)
        assertEquals(
            "어깨가 165도까지 벌어져 위팔이 몸통과 거의 일직선이 되어서 횟수로 세지 않았어요",
            press.headline,
        )
    }

    @Test
    fun aSwingingCurlStillCountsAndIsObservedRatherThanDiscarded() {
        // The guard and the gate read the same joint and the same extreme, so this is the case
        // that proves they are different kinds of statement. A shoulder swinging to 100 is far
        // past the guard's 52 and nowhere near the gate's 140: the repetition counts, and the
        // swing is reported as an observation attached to it.
        val arc = listOf(130.0, 110.0, 95.0, 95.0, 95.0, 110.0)
        val swung = runExcursion(FormCheckExercise.BARBELL_CURL, 175.0, arc) { t, elbow ->
            frameWithChains(
                kneeAngleDegrees = 175.0,
                hipAngleDegrees = 175.0,
                elbowAngleDegrees = elbow,
                shoulderAngleDegrees = 100.0,
                timestampMs = t,
            )
        }
        assertEquals(1, swung.repCount)
        assertEquals(0, swung.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.COUNTED, swung.repMarks.single().kind)
        assertTrue(
            "Expected the guard's observation alongside the reach, got ${swung.headline}",
            swung.headline!!.contains("어깨가 100도까지 벌어졌어요"),
        )
    }

    @Test
    fun aSingleBlownFrameDoesNotDiscardARepetition() {
        // A STAY clause reads a raw extreme, so noise can only push it the wrong way — the
        // direction that throws away a repetition that really happened. One frame is noise; the
        // clause asks for a position the movement held.
        // Paired elbow/shoulder frames, so each shoulder reading lands on a known point of the
        // excursion rather than on whichever frame an iterator happens to reach.
        fun run(shoulders: List<Double>): FormCheckUiState {
            val elbows = listOf(175.0, 175.0, 130.0, 110.0, 95.0, 95.0, 95.0, 110.0, 175.0, 175.0, 175.0)
            check(elbows.size == shoulders.size)
            val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_CURL)
            var state = session.initialSnapshot()
            for (i in elbows.indices) {
                state = session.accept(
                    i * 200L,
                    true,
                    true,
                    frameWithChains(
                        kneeAngleDegrees = 175.0,
                        hipAngleDegrees = 175.0,
                        elbowAngleDegrees = elbows[i],
                        shoulderAngleDegrees = shoulders[i],
                        timestampMs = i * 200L,
                    ),
                )
            }
            return state
        }

        // The excursion arms on the third frame, where the elbow first passes the attempt line.
        val strict = listOf(30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0)
        val oneSpike = listOf(30.0, 30.0, 30.0, 175.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0)
        val sustained = listOf(30.0, 30.0, 175.0, 175.0, 175.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0)

        assertEquals("A strict curl counts", 1, run(strict).repCount)
        assertEquals("One misread frame must not discard the repetition", 1, run(oneSpike).repCount)

        val held = run(sustained)
        assertEquals("A position the movement held is reported", 0, held.repCount)
        assertEquals(1, held.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.INCOMPLETE, held.repMarks.single().kind)
    }

    // ---- posture: which way the body is pointing ----

    /** A gravity reading pointing down the image, as an upright phone produces. */
    private fun downwardGravity(t: Long) =
        PoseGravityReading.of(x = 0.0, y = 9.8, outOfPlane = 0.0, timestampMs = t)!!

    /**
     * Runs a push-up-shaped elbow excursion with the torso posed at [torsoDegrees] away from
     * gravity, so the only thing under test is which way the body points.
     */
    private fun pushUpExcursion(
        torsoDegrees: Double,
        gravityAt: (Long) -> PoseGravityReading?,
    ): FormCheckUiState {
        val session = HeuristicFormCheckSession(FormCheckExercise.PUSH_UP)
        var state = session.initialSnapshot()
        val elbows = listOf(175.0, 175.0, 130.0, 110.0, 110.0, 110.0, 130.0, 175.0, 175.0, 175.0)
        val radians = Math.toRadians(torsoDegrees)
        for ((index, elbow) in elbows.withIndex()) {
            val t = index * 200L
            // The torso is placed at the requested angle from the image-down direction, which is
            // where the gravity fixture points.
            state = session.accept(
                t,
                true,
                true,
                frameWithTorsoDirection(
                    elbowAngleDegrees = elbow,
                    torsoX = sin(radians),
                    torsoY = cos(radians),
                    timestampMs = t,
                ),
                gravity = gravityAt(t),
            )
        }
        return state
    }

    @Test
    fun aStandingArmMovementIsNotAPushUp() {
        // Every included angle a push-up makes lies inside a standing curl's range, so the only
        // thing that separates them is which way the body points. A torso along gravity is
        // somebody standing up, whatever their elbow did.
        val standing = pushUpExcursion(torsoDegrees = 5.0, gravityAt = ::downwardGravity)

        assertEquals(0, standing.repCount)
        assertEquals(1, standing.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.INCOMPLETE, standing.repMarks.single().kind)
        assertTrue(
            "Expected the posture observation, got ${standing.headline}",
            standing.headline!!.contains("몸통이 중력 기준"),
        )
        assertNull("A posture shortfall urges nothing", standing.suggestion)
    }

    @Test
    fun aFaceDownPressCounts() {
        val prone = pushUpExcursion(torsoDegrees = 85.0, gravityAt = ::downwardGravity)

        assertEquals(1, prone.repCount)
        assertEquals(0, prone.uncountedAttemptCount)
        assertEquals(FormCheckRepEventKind.COUNTED, prone.repMarks.single().kind)
    }

    @Test
    fun thePostureClauseAbstainsWithoutAUsableGravityReading() {
        // A device with no sensor, and a reading too stale to describe the frame, are different
        // facts with the same correct output: say nothing and count on the joints alone.
        val noSensor = pushUpExcursion(torsoDegrees = 5.0, gravityAt = { null })
        assertEquals("An unmeasured direction is not evidence", 1, noSensor.repCount)
        assertEquals(0, noSensor.uncountedAttemptCount)

        val stale = pushUpExcursion(
            torsoDegrees = 5.0,
            gravityAt = { PoseGravityReading.of(0.0, 9.8, 0.0, timestampMs = 0L) },
        )
        assertEquals("A stale reading is not evidence either", 1, stale.repCount)
    }

    @Test
    fun aCameraAimedAlongGravityYieldsNoReadingAtAll() {
        // Pointing a phone at the floor puts almost nothing of gravity in its own image plane,
        // and the direction that survives is noise. The reading refuses to be built.
        assertNull(PoseGravityReading.of(x = 0.1, y = 0.1, outOfPlane = 9.8, timestampMs = 100L))
        assertNotNull(PoseGravityReading.of(x = 0.0, y = 9.8, outOfPlane = 0.0, timestampMs = 100L))

        val reading = PoseGravityReading.of(x = 0.0, y = 9.8, outOfPlane = 0.0, timestampMs = 100L)!!
        assertEquals(1.0, reading.directionY, 1e-9)
        assertEquals(0.0, reading.directionX, 1e-9)
        assertTrue(reading.isFreshAt(200L))
        assertFalse(reading.isFreshAt(5_000L))
    }

    /**
     * Runs the plank with a straight body posed at [torsoDegrees] from gravity. The straight
     * hip is the same in every case; which way the body points is the only variable.
     */
    private fun plankRun(
        torsoDegrees: Double,
        gravityAt: (Long) -> PoseGravityReading?,
        frames: Int = 30,
        torsoDegreesAt: ((Int) -> Double)? = null,
    ): FormCheckUiState {
        val session = HeuristicFormCheckSession(FormCheckExercise.PLANK)
        var state = session.initialSnapshot()
        for (index in 0 until frames) {
            val t = index * 200L
            val radians = Math.toRadians(torsoDegreesAt?.invoke(index) ?: torsoDegrees)
            state = session.accept(
                t,
                true,
                true,
                frameWithTorsoDirection(
                    elbowAngleDegrees = 90.0,
                    torsoX = sin(radians),
                    torsoY = cos(radians),
                    timestampMs = t,
                ),
                gravity = gravityAt(t),
            )
        }
        return state
    }

    @Test
    fun aStandingBodyIsNotAPlank() {
        // A standing body keeps the exact straight hip a plank does, so before v2.11 the hold
        // accrued on somebody standing still — the driver angle alone cannot tell the two
        // apart. Orientation is part of the held position's identity: along gravity, no hold
        // begins, and the truthful reason is reported once the reading is sustained.
        val standing = plankRun(torsoDegrees = 5.0, gravityAt = ::downwardGravity)

        assertEquals(0, standing.holdSeconds)
        assertTrue(
            "Expected the posture observation, got ${standing.headline}",
            standing.headline!!.contains("몸통이 중력 기준") &&
                standing.headline!!.contains("유지 시간을 세지 않았어요"),
        )
        assertNull("A hold posture veto urges nothing", standing.suggestion)
    }

    @Test
    fun aProneBodyHoldsAPlank() {
        val prone = plankRun(torsoDegrees = 85.0, gravityAt = ::downwardGravity)
        assertTrue("Expected an accruing hold, got ${prone.holdSeconds}s", prone.holdSeconds >= 4)
    }

    @Test
    fun thePlankPostureClauseAbstainsWithoutAUsableGravityReading() {
        // No sensor means no orientation evidence, and the driver counts alone — the same
        // fail-open the push-ups document. The cost is stated in §4.3c, not hidden here.
        val noSensor = plankRun(torsoDegrees = 5.0, gravityAt = { null })
        assertTrue(
            "An unmeasured direction is not evidence: ${noSensor.holdSeconds}s",
            noSensor.holdSeconds >= 4,
        )
    }

    @Test
    fun standingUpEndsThePlankAndCountsTheHeldStretch() {
        // The stretch until the body turned upright genuinely happened. Ending it as a normal
        // release — counted seconds, not a discard — is what keeps the summary honest.
        val state = plankRun(
            torsoDegrees = 85.0,
            gravityAt = ::downwardGravity,
            frames = 35,
            torsoDegreesAt = { index -> if (index < 25) 85.0 else 5.0 },
        )

        assertEquals("The hold must stop accruing once the body stands", 0, state.holdSeconds)
        assertTrue(
            "Expected the counted stretch, got ${state.headline}",
            state.headline!!.contains("유지했어요"),
        )
    }

    @Test
    fun onlyTheProneThreeDependOnWhichWayTheBodyPoints() {
        // Dips was grouped with them as a gravity case and it is not one: measured on the 3D
        // labels its torso sits 19 degrees from vertical against a curl's 5, inside the same
        // range rather than outside it. Nothing was gained by giving it a posture clause, so it
        // does not have one. The plank joined in v2.11 for the identity reason the squat's hip
        // clause exists: a standing body keeps the same straight hip a plank does, so without
        // orientation the hold would accrue on somebody standing still.
        val withPosture = FormCheckExercise.entries.filter { it.posture != null }.toSet()
        assertEquals(
            setOf(
                FormCheckExercise.PUSH_UP,
                FormCheckExercise.KNEE_PUSH_UP,
                FormCheckExercise.PLANK,
                // v2.11: sitting down flexes both hips through this exercise's whole arc, and
                // the knee gate anatomy suggested was measured and refused — the population
                // bends its knees. Lying is orientation, and only gravity can say it.
                FormCheckExercise.LYING_LEG_RAISE,
            ),
            withPosture,
        )
        assertNull(FormCheckExercise.DIPS.posture)
        for (spec in withPosture) {
            assertEquals(60.0, spec.posture!!.minimumTorsoGravityAngleDegrees, 0.0)
        }
    }

    @Test
    fun definitionGatesAbstainWhenTheirChainIsUnobserved() {
        // A squat measured through frames that carry no shoulders cannot see the hip chain, so
        // the hip clause abstains and the count proceeds — the same discipline as everything
        // else here: an unobserved joint is not evidence in either direction.
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)
        var state = session.initialSnapshot()
        var t = 0L
        fun step(both: Double) {
            state = session.accept(t, true, true, frameWithKneeAngles(t, both, both, 1.0, 0.9))
            t += 200L
        }
        step(175.0)
        step(175.0)
        for (angle in listOf(120.0, 100.0, 100.0, 100.0, 100.0, 120.0)) step(angle)
        step(175.0)
        step(175.0)

        assertEquals("An invisible companion chain is not a failed one", 1, state.repCount)
        assertEquals(0, state.uncountedAttemptCount)
    }

    @Test
    fun theBilateralFlagNamesExactlyTheTwoSidedExercises() {
        val bilateral = FormCheckExercise.entries.filter { it.bilateralDriver }.toSet()
        assertEquals(
            setOf(
                FormCheckExercise.BARBELL_SQUAT,
                FormCheckExercise.GOOD_MORNING,
                FormCheckExercise.PUSH_UP,
                FormCheckExercise.KNEE_PUSH_UP,
                FormCheckExercise.DIPS,
                FormCheckExercise.BARBELL_CURL,
                FormCheckExercise.LAT_PULLDOWN,
                FormCheckExercise.ROWING_MACHINE,
                FormCheckExercise.HIP_THRUST,
                FormCheckExercise.OVERHEAD_PRESS,
                FormCheckExercise.CABLE_PUSH_DOWN,
                FormCheckExercise.PULL_UP,
                FormCheckExercise.FACE_PULL,
                FormCheckExercise.UPRIGHT_ROW,
                FormCheckExercise.BARBELL_ROW,
                FormCheckExercise.BARBELL_DEADLIFT,
                FormCheckExercise.BARBELL_STIFF_DEADLIFT,
                FormCheckExercise.HANGING_LEG_RAISE,
                FormCheckExercise.LYING_LEG_RAISE,
                FormCheckExercise.LYING_TRICEPS_EXTENSION,
                FormCheckExercise.DUMBBELL_PULLOVER,
                FormCheckExercise.SIDE_LATERAL_RAISE,
            ),
            bilateral,
        )
        // The exclusions are decisions, not omissions: the lunges stride, the dumbbell curl and
        // the front raise may alternate, the one-arm bench row is the standard way to perform
        // it, the knee-up and side crunch are one-sided by design, the plank has no excursion
        // to compare over.
        assertFalse(FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.bilateralDriver)
        assertFalse(FormCheckExercise.CROSS_LUNGE.bilateralDriver)
        assertFalse(FormCheckExercise.DUMBBELL_CURL.bilateralDriver)
        assertFalse(FormCheckExercise.FRONT_RAISE.bilateralDriver)
        assertFalse(FormCheckExercise.DUMBBELL_BENT_OVER_ROW.bilateralDriver)
        assertFalse(FormCheckExercise.STANDING_KNEE_UP.bilateralDriver)
        assertFalse(FormCheckExercise.PLANK.bilateralDriver)
        // A side lunge bends one leg while the other stays straight — the asymmetry is the
        // exercise, exactly as it is for the knee-up.
        assertFalse(FormCheckExercise.SIDE_LUNGE.bilateralDriver)
    }

    @Test
    fun theSetSummaryReportsOnlyWhatWasObserved() {
        val spec = FormCheckExercise.PUSH_UP
        val session = HeuristicFormCheckSession(spec)
        assertFalse(
            "An untouched set has nothing to review",
            session.summary().hasObservations,
        )

        var state = session.initialSnapshot()
        // Three frames at the bottom, so the excursion outlasts the 500ms floor that separates a
        // repetition from a movement too fast to be one.
        for ((t, angle) in listOf(
            0L to 175.0,
            200L to 175.0,
            400L to 100.0,
            600L to 100.0,
            800L to 100.0,
            1_000L to 175.0,
            1_200L to 175.0,
        )) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
        }

        val summary = session.summary()
        assertTrue(summary.hasObservations)
        assertEquals(1, summary.repCount)
        assertEquals(0, summary.uncountedCount)
        assertEquals(state.repMarks.size, summary.marks.size)
        assertEquals(spec.driver.vertex.label, summary.measuredJointLabel)
        assertEquals(spec.provenance.note, summary.provenanceNote)
        assertEquals(spec.requiresDataAttribution, summary.requiresDataAttribution)
    }

    @Test
    fun theSpokenCountCarriesTheSetsOwnComparisonAndNothingElse() {
        // Speech is the only channel that reaches somebody standing side-on to the phone, so the
        // count carries the comparison §4.4 already permits. SAME covers both "no baseline yet"
        // and "inside the floor", and both are silences the wording owes.
        val vocabulary = FormCheckExercise.PUSH_UP.vocabulary
        assertEquals(
            "3회",
            FormCheckStartAnnouncer.countPhrase(3, FormCheckBaselineRelation.SAME, vocabulary),
        )
        assertEquals(
            "3회 · 첫 반복보다 얕아요",
            FormCheckStartAnnouncer.countPhrase(3, FormCheckBaselineRelation.BELOW, vocabulary),
        )
        assertEquals(
            "3회 · 첫 반복보다 깊어요",
            FormCheckStartAnnouncer.countPhrase(3, FormCheckBaselineRelation.BEYOND, vocabulary),
        )
        // A shoulder chain is drawn in, never bent: the spoken clause follows anatomy for the
        // same reason the written one does.
        assertEquals(
            "2회 · 첫 반복보다 덜 모아졌어요",
            FormCheckStartAnnouncer.countPhrase(
                2,
                FormCheckBaselineRelation.BELOW,
                FormCheckExercise.LAT_PULLDOWN.vocabulary,
            ),
        )
    }

    @Test
    fun eachDistinctUncountedReasonIsSpokenOncePerSet() {
        val announcer = FormCheckUncountedAnnouncer()
        val shallow = "무릎 굽힘이 얕아 횟수로 세지 않았어요"

        assertNull("Nothing to say before anything happens", announcer.onUncounted(0, null))
        assertNotNull("The first reason speaks", announcer.onUncounted(1, shallow))
        assertNull("The same reason again stays silent", announcer.onUncounted(2, shallow))
        assertNotNull(
            "A genuinely different reason gets its own sentence",
            announcer.onUncounted(3, "엉덩이가 158도까지만 굽혀져서 횟수로 세지 않았어요"),
        )
        assertNull(
            "The same situation measured a few degrees apart is one reason, not two",
            announcer.onUncounted(4, "엉덩이가 162도까지만 굽혀져서 횟수로 세지 않았어요"),
        )
        assertNotNull(announcer.onUncounted(5, "동작이 빨라 횟수로 세지 않았어요"))
        assertNull(
            "Three distinct reasons is where the voice channel stops lecturing",
            announcer.onUncounted(6, "양쪽 무릎이 서로 다르게 움직여서 횟수로 세지 않았어요"),
        )
    }

    // ---- fixtures ----

    /** The baseline relation of each mark a run of completed repetitions produced. */
    private fun markRelations(
        spec: FormCheckExercise,
        extremes: List<Double>,
    ): List<FormCheckBaselineRelation> {
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        var t = 0L
        fun step(angle: Double) {
            state = session.accept(t, true, true, frameForDriver(spec, angle, t))
            t += 200L
        }
        step(175.0)
        step(175.0)
        for (extreme in extremes) {
            step(extreme)
            step(extreme)
            step(extreme)
            step(175.0)
            step(175.0)
        }
        return state.repMarks.map { it.baselineRelation }
    }

    /** Top hold, descent to ~100 degrees, return to top; 200ms cadence beats the EMA. */
    private fun rampSequence(): List<Pair<Long, Double>> {
        val angles = listOf(175.0, 175.0, 100.0, 100.0, 100.0, 100.0, 175.0, 175.0, 175.0)
        return angles.mapIndexed { index, angle -> index * 200L to angle }
    }

    private fun waiting(missing: Set<FormCheckJointGroup>) = FormCheckUiState(
        repCount = 0,
        uncountedAttemptCount = 0,
        startState = FormCheckStartState.WAITING_FOR_JOINTS,
        hasEverStarted = false,
        repMarks = emptyList(),
        missingJoints = missing,
        preferredViewSuggested = false,
        headline = null,
        suggestion = null,
    )

    /** The same waiting state after the exercise had already started: an abstention, not setup. */
    private fun pausedWaiting(missing: Set<FormCheckJointGroup>) = FormCheckUiState(
        repCount = 0,
        uncountedAttemptCount = 0,
        startState = FormCheckStartState.WAITING_FOR_JOINTS,
        hasEverStarted = true,
        repMarks = emptyList(),
        missingJoints = missing,
        preferredViewSuggested = false,
        headline = null,
        suggestion = null,
    )

    private fun startedState(preferredViewSuggested: Boolean) = FormCheckUiState(
        repCount = 0,
        uncountedAttemptCount = 0,
        startState = FormCheckStartState.STARTED,
        hasEverStarted = true,
        repMarks = emptyList(),
        missingJoints = emptySet(),
        preferredViewSuggested = preferredViewSuggested,
        headline = null,
        suggestion = null,
    )

    /** A frame whose leg chain is credible except for the named groups, which are absent. */
    private fun frameWithMissingGroups(missing: Set<FormCheckJointGroup>): PoseFrame {
        val full = frameWithKneeAngles(0L, 170.0, 170.0, 1.0, 1.0)
        val dropped = missing.flatMap { listOf(it.left, it.right) }.toSet()
        return PoseFrame(
            timestampMs = 0L,
            landmarks = emptyMap(),
            worldLandmarks = full.worldLandmarks.filterKeys { it !in dropped },
        )
    }

    private fun point(x: Double, y: Double, z: Double) = PoseLandmark(x, y, z, 1.0, 1.0)

    /**
     * A frame whose torso points in a requested image-plane direction, with the elbow chain at a
     * requested angle. Everything is built around the hip so the torso direction is exact.
     */
    private fun frameWithTorsoDirection(
        elbowAngleDegrees: Double,
        torsoX: Double,
        torsoY: Double,
        timestampMs: Long,
    ): PoseFrame {
        val elbow = Math.toRadians(elbowAngleDegrees)
        fun rot(x: Double, y: Double, r: Double) =
            (x * cos(r) - y * sin(r)) to (x * sin(r) + y * cos(r))

        val world = buildMap {
            for ((side, offsetX) in listOf(
                FormCheckBodySide.LEFT to -0.1,
                FormCheckBodySide.RIGHT to 0.1,
            )) {
                // Hip at the origin; the shoulder sits one torso length back along the requested
                // direction, so hip - shoulder points exactly the way the test asked for.
                val hip = Triple(offsetX, 0.0, 0.0)
                val shoulder = Triple(offsetX - 0.5 * torsoX, -0.5 * torsoY, 0.0)
                val elbowAt = Triple(shoulder.first + 0.3, shoulder.second, 0.0)
                val toShoulderX = shoulder.first - elbowAt.first
                val toShoulderY = shoulder.second - elbowAt.second
                val length = hypot(toShoulderX, toShoulderY)
                val (wx, wy) = rot(toShoulderX / length, toShoulderY / length, elbow)
                val knee = Triple(offsetX + 0.4 * torsoX, 0.4 * torsoY, 0.0)
                val ankle = Triple(offsetX + 0.8 * torsoX, 0.8 * torsoY, 0.0)
                for ((group, point) in listOf(
                    FormCheckJointGroup.HIP to hip,
                    FormCheckJointGroup.SHOULDER to shoulder,
                    FormCheckJointGroup.ELBOW to elbowAt,
                    FormCheckJointGroup.WRIST to Triple(
                        elbowAt.first + 0.3 * wx,
                        elbowAt.second + 0.3 * wy,
                        0.0,
                    ),
                    FormCheckJointGroup.KNEE to knee,
                    FormCheckJointGroup.ANKLE to ankle,
                )) {
                    put(
                        group.joint(side),
                        PoseLandmark(point.first, point.second, point.third, 1.0, 1.0),
                    )
                }
            }
        }
        return PoseFrame(timestampMs = timestampMs, landmarks = emptyMap(), worldLandmarks = world)
    }

    /** A hip-chain frame with the other chains held straight. */
    private fun hipFrame(angleDegrees: Double, timestampMs: Long): PoseFrame = frameWithChains(
        kneeAngleDegrees = 175.0,
        hipAngleDegrees = angleDegrees,
        elbowAngleDegrees = 175.0,
        timestampMs = timestampMs,
    )

    /** An elbow-chain frame with the leg chains held straight. */
    private fun elbowFrame(angleDegrees: Double, timestampMs: Long): PoseFrame = frameWithChains(
        kneeAngleDegrees = 175.0,
        hipAngleDegrees = 175.0,
        elbowAngleDegrees = angleDegrees,
        timestampMs = timestampMs,
    )

    /** A frame that puts [angleDegrees] on [spec]'s own driver chain and rests the other two. */
    private fun frameForDriver(
        spec: FormCheckExercise,
        angleDegrees: Double,
        timestampMs: Long,
    ): PoseFrame = frameWithChains(
        kneeAngleDegrees = if (spec.driver == FormCheckDriver.KNEE) angleDegrees else 175.0,
        hipAngleDegrees = if (spec.driver == FormCheckDriver.HIP) angleDegrees else 175.0,
        elbowAngleDegrees = if (spec.driver == FormCheckDriver.ELBOW) angleDegrees else 175.0,
        shoulderAngleDegrees = if (spec.driver == FormCheckDriver.SHOULDER) angleDegrees else null,
        timestampMs = timestampMs,
    )

    /**
     * A frame carrying all three driver chains at once, each hinged to its requested angle.
     *
     * Joints are placed so every triple reads independently: the knee sits at the origin with the
     * hip above it, the shoulder swings about the hip, and the wrist swings about the elbow.
     */
    private fun frameWithChains(
        kneeAngleDegrees: Double,
        hipAngleDegrees: Double,
        elbowAngleDegrees: Double,
        shoulderAngleDegrees: Double? = null,
        timestampMs: Long = 0L,
        confidence: Double = 1.0,
        dropped: Set<FormCheckJointGroup> = emptySet(),
        // The RIGHT side's leg, when it must differ from the measured LEFT — a standing leg
        // under a knee raise, a still knee beside a travelling one. Null mirrors the left.
        oppositeKneeAngleDegrees: Double? = null,
        oppositeHipAngleDegrees: Double? = null,
    ): PoseFrame {
        val elbow = Math.toRadians(elbowAngleDegrees)

        /** Rotates a 2D vector, so a joint can be placed at a requested included angle. */
        fun rotate(x: Double, y: Double, radians: Double): Pair<Double, Double> =
            (x * cos(radians) - y * sin(radians)) to (x * sin(radians) + y * cos(radians))

        fun side(
            offsetX: Double,
            kneeDegrees: Double,
            hipDegrees: Double,
        ): Map<FormCheckJointGroup, Triple<Double, Double, Double>> {
            val knee = Math.toRadians(kneeDegrees)
            val hip = Math.toRadians(hipDegrees)
            val kneeAt = Triple(offsetX, 0.0, 0.0)
            val hipAt = Triple(offsetX, 0.4, 0.0)
            val ankleAt = Triple(offsetX + 0.4 * sin(knee), 0.4 * cos(knee), 0.0)
            val shoulderAt = Triple(
                hipAt.first + 0.5 * sin(hip),
                hipAt.second - 0.5 * cos(hip),
                0.0,
            )
            // The upper arm swings about the shoulder, measured off the shoulder-to-hip direction,
            // so the elbow-shoulder-hip angle is whatever the caller asked for. Left null it keeps
            // the original fixed placement, which the knee, hip and elbow cases were written
            // against and which must not move under them.
            val elbowAt = if (shoulderAngleDegrees == null) {
                Triple(shoulderAt.first + 0.3, shoulderAt.second, 0.0)
            } else {
                val toHipX = hipAt.first - shoulderAt.first
                val toHipY = hipAt.second - shoulderAt.second
                val length = hypot(toHipX, toHipY)
                val (dx, dy) = rotate(
                    toHipX / length,
                    toHipY / length,
                    Math.toRadians(shoulderAngleDegrees),
                )
                Triple(shoulderAt.first + 0.3 * dx, shoulderAt.second + 0.3 * dy, 0.0)
            }
            // The forearm swings about the elbow, measured off the elbow-to-shoulder direction, so
            // the elbow angle stays independent of wherever the upper arm was just placed.
            val toShoulderX = shoulderAt.first - elbowAt.first
            val toShoulderY = shoulderAt.second - elbowAt.second
            val forearm = hypot(toShoulderX, toShoulderY)
            val (wx, wy) = rotate(toShoulderX / forearm, toShoulderY / forearm, elbow)
            val wristAt = Triple(elbowAt.first + 0.3 * wx, elbowAt.second + 0.3 * wy, 0.0)
            return mapOf(
                FormCheckJointGroup.KNEE to kneeAt,
                FormCheckJointGroup.HIP to hipAt,
                FormCheckJointGroup.ANKLE to ankleAt,
                FormCheckJointGroup.SHOULDER to shoulderAt,
                FormCheckJointGroup.ELBOW to elbowAt,
                FormCheckJointGroup.WRIST to wristAt,
            )
        }

        val world = buildMap {
            for ((bodySide, offsetX) in listOf(
                FormCheckBodySide.LEFT to -0.1,
                FormCheckBodySide.RIGHT to 0.1,
            )) {
                val kneeDegrees = if (bodySide == FormCheckBodySide.RIGHT) {
                    oppositeKneeAngleDegrees ?: kneeAngleDegrees
                } else {
                    kneeAngleDegrees
                }
                val hipDegrees = if (bodySide == FormCheckBodySide.RIGHT) {
                    oppositeHipAngleDegrees ?: hipAngleDegrees
                } else {
                    hipAngleDegrees
                }
                for ((group, position) in side(offsetX, kneeDegrees, hipDegrees)) {
                    if (group in dropped) continue
                    val (x, y, z) = position
                    put(group.joint(bodySide), PoseLandmark(x, y, z, confidence, confidence))
                }
            }
        }
        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = emptyMap(),
            worldLandmarks = world,
        )
    }

    private fun frameWithKneeAngles(
        timestampMs: Long,
        leftAngleDegrees: Double,
        rightAngleDegrees: Double,
        leftConfidence: Double,
        rightConfidence: Double,
    ): PoseFrame {
        fun chain(
            hip: PoseJoint,
            knee: PoseJoint,
            ankle: PoseJoint,
            angleDegrees: Double,
            confidence: Double,
            offsetX: Double,
        ): Map<PoseJoint, PoseLandmark> {
            val radians = Math.toRadians(angleDegrees)
            return mapOf(
                knee to PoseLandmark(offsetX, 0.0, 0.0, confidence, confidence),
                hip to PoseLandmark(offsetX, 0.4, 0.0, confidence, confidence),
                ankle to PoseLandmark(
                    offsetX + 0.4 * sin(radians),
                    0.4 * cos(radians),
                    0.0,
                    confidence,
                    confidence,
                ),
            )
        }

        val world = chain(
            PoseJoint.LEFT_HIP, PoseJoint.LEFT_KNEE, PoseJoint.LEFT_ANKLE,
            leftAngleDegrees, leftConfidence, -0.1,
        ) + chain(
            PoseJoint.RIGHT_HIP, PoseJoint.RIGHT_KNEE, PoseJoint.RIGHT_ANKLE,
            rightAngleDegrees, rightConfidence, 0.1,
        )
        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = emptyMap(),
            worldLandmarks = world,
        )
    }
}
