package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import kotlin.math.cos
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

        repeat(2) {
            for (angle in listOf(90.0, 90.0, 170.0, 170.0, 170.0, 170.0, 90.0, 90.0)) {
                state = session.accept(t, true, true, elbowFrame(angle, t))
                t += 200L
            }
        }
        // Reaches only 150: twenty degrees less extension than the set opened with.
        for (angle in listOf(90.0, 90.0, 150.0, 150.0, 150.0, 150.0, 90.0, 90.0)) {
            state = session.accept(t, true, true, elbowFrame(angle, t))
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
                lateralViewQualified = true,
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
            lateralViewQualified = false,
            frame = frameWithKneeAngles(0L, 170.0, 170.0, 1.0, 1.0),
        )

        assertTrue("Required joints are visible, so the exercise must start", state.started)
        assertTrue("A non-lateral view is a quality note, not a blocker", state.sideViewPreferred)
    }

    @Test
    fun aMissingRequiredJointReportsItRatherThanFailingSilently() {
        val session = HeuristicFormCheckSession(FormCheckExercise.BARBELL_SQUAT)

        val state = session.accept(
            timestampMs = 0L,
            hasPrimaryPersonLock = true,
            lateralViewQualified = true,
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
            lateralViewQualified = true,
            frame = frameWithKneeAngles(0L, 170.0, 170.0, 1.0, 1.0),
        )

        assertEquals(FormCheckStartState.WAITING_FOR_PERSON, state.startState)
    }

    // ---- voice ----

    @Test
    fun theAnnouncerNamesTheMissingJointsWithTheRightParticle() {
        val announcer = FormCheckStartAnnouncer()
        val spec = FormCheckExercise.BARBELL_SQUAT

        val ankle = announcer.onState(0L, spec, waiting(setOf(FormCheckJointGroup.ANKLE)))
        assertEquals("발목이 화면에 보이게 서 주세요", ankle)

        val hip = announcer.onState(20_000L, spec, waiting(setOf(FormCheckJointGroup.HIP)))
        assertEquals("엉덩이가 화면에 보이게 서 주세요", hip)
    }

    @Test
    fun theAnnouncerStaysQuietUntilTheSituationChanges() {
        val announcer = FormCheckStartAnnouncer(repeatIntervalMs = 8_000L)
        val spec = FormCheckExercise.BARBELL_SQUAT
        val missing = waiting(setOf(FormCheckJointGroup.ANKLE))

        assertNotNull(announcer.onState(0L, spec, missing))
        assertNull("The same situation must not repeat immediately", announcer.onState(1_000L, spec, missing))
        assertNull(announcer.onState(7_000L, spec, missing))
        assertNotNull("After the interval it may remind once", announcer.onState(9_000L, spec, missing))
    }

    @Test
    fun theAnnouncerSaysTheExerciseStartedAndSuggestsTheSideView() {
        val announcer = FormCheckStartAnnouncer()
        val spec = FormCheckExercise.BARBELL_SQUAT

        val started = announcer.onState(0L, spec, startedState(sideViewPreferred = true))
        assertNotNull(started)
        assertTrue(started!!.contains("시작"))
        assertTrue(started.contains("옆모습"))

        val lateral = announcer.onState(20_000L, spec, startedState(sideViewPreferred = false))
        assertEquals("자세 체크를 시작할게요", lateral)
    }

    @Test
    fun theSideViewSuggestionNamesTheJointTheExerciseMeasures() {
        // Telling a push-up about a knee would describe a joint the track never looked at.
        assertEquals("무릎이", FormCheckStartAnnouncer.sideViewSubject(FormCheckExercise.BARBELL_SQUAT))
        assertEquals("팔꿈치가", FormCheckStartAnnouncer.sideViewSubject(FormCheckExercise.PUSH_UP))
        assertEquals("엉덩이가", FormCheckStartAnnouncer.sideViewSubject(FormCheckExercise.GOOD_MORNING))

        val announcer = FormCheckStartAnnouncer()
        val spoken = announcer.onState(
            0L,
            FormCheckExercise.PUSH_UP,
            startedState(sideViewPreferred = true),
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

    // ---- fixtures ----

    /** Top hold, descent to ~100 degrees, return to top; 200ms cadence beats the EMA. */
    private fun rampSequence(): List<Pair<Long, Double>> {
        val angles = listOf(175.0, 175.0, 100.0, 100.0, 100.0, 100.0, 175.0, 175.0, 175.0)
        return angles.mapIndexed { index, angle -> index * 200L to angle }
    }

    private fun waiting(missing: Set<FormCheckJointGroup>) = FormCheckUiState(
        repCount = 0,
        uncountedAttemptCount = 0,
        startState = FormCheckStartState.WAITING_FOR_JOINTS,
        missingJoints = missing,
        sideViewPreferred = false,
        headline = null,
        suggestion = null,
    )

    private fun startedState(sideViewPreferred: Boolean) = FormCheckUiState(
        repCount = 0,
        uncountedAttemptCount = 0,
        startState = FormCheckStartState.STARTED,
        missingJoints = emptySet(),
        sideViewPreferred = sideViewPreferred,
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
        timestampMs: Long = 0L,
        confidence: Double = 1.0,
        dropped: Set<FormCheckJointGroup> = emptySet(),
    ): PoseFrame {
        val knee = Math.toRadians(kneeAngleDegrees)
        val hip = Math.toRadians(hipAngleDegrees)
        val elbow = Math.toRadians(elbowAngleDegrees)

        fun side(offsetX: Double): Map<FormCheckJointGroup, Triple<Double, Double, Double>> {
            val kneeAt = Triple(offsetX, 0.0, 0.0)
            val hipAt = Triple(offsetX, 0.4, 0.0)
            val ankleAt = Triple(offsetX + 0.4 * sin(knee), 0.4 * cos(knee), 0.0)
            val shoulderAt = Triple(
                hipAt.first + 0.5 * sin(hip),
                hipAt.second - 0.5 * cos(hip),
                0.0,
            )
            val elbowAt = Triple(shoulderAt.first + 0.3, shoulderAt.second, 0.0)
            val wristAt = Triple(
                elbowAt.first - 0.3 * cos(elbow),
                elbowAt.second + 0.3 * sin(elbow),
                0.0,
            )
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
                for ((group, position) in side(offsetX)) {
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
