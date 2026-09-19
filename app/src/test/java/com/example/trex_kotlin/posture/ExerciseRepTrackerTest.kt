package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class ExerciseRepTrackerTest {
    private val left = RepSignal("left", .3f, plausibleMin = .1f)
    private val right = RepSignal("right", .3f, plausibleMin = .1f)
    private val motion = listOf(1.1f, .7f, .4f, .7f, 1.1f, 1.4f)

    private inner class Feed(
        pattern: RepMovementPattern,
        val tracker: ExerciseRepTracker = ExerciseRepTracker(pattern, left, right),
    ) {
        var time = 0L
        fun frame(l: Float? = 1.4f, r: Float? = 1.4f): List<ExerciseRepEvent> {
            val features = buildMap {
                if (l != null) put("left", l)
                if (r != null) put("right", r)
            }
            return tracker.onFrame(time, features).also { time += 300 }
        }
        fun prepare(l: Float = 1.4f, r: Float = 1.4f) { repeat(3) { frame(l, r) } }
        fun cycle(l: Boolean = true, r: Boolean = true) {
            for (v in motion) frame(if (l) v else 1.4f, if (r) v else 1.4f)
        }
    }

    @Test fun oppositePhaseSignalsAreNotCancelledByTheirConstantMean() {
        val f = Feed(RepMovementPattern.ALTERNATING_EACH)
        f.prepare(1.4f, .4f)
        repeat(3) {
            for (v in motion) f.frame(v, 1.8f - v)
        }
        assertEquals(ExerciseRepCounts(left = 3, right = 3, total = 6), f.tracker.counts)
        assertEquals(listOf(RepSide.LEFT, RepSide.RIGHT).let { it + it + it }, f.tracker.events.map { it.side })
        assertNull(f.tracker.periodMs) // 한 프레임의 두 완료를 0ms 반복으로 표시하지 않는다.
    }

    @Test fun eachSideModeDoesNotRequireStrictAlternation() {
        val f = Feed(RepMovementPattern.ALTERNATING_EACH)
        f.prepare(); repeat(2) { f.cycle(r = false) }; f.cycle(l = false)
        assertEquals(listOf(RepSide.LEFT, RepSide.LEFT, RepSide.RIGHT), f.tracker.events.map { it.side })
        assertEquals(ExerciseRepCounts(left = 2, right = 1, total = 3), f.tracker.counts)
        assertEquals(1800L, f.tracker.periodMs)
    }

    @Test fun simultaneousBothArmsProduceOneTargetRepAndPreserveBothCycles() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare(); repeat(4) { f.cycle() }
        assertEquals(ExerciseRepCounts(left = 4, right = 4, both = 4, total = 4), f.tracker.counts)
        val last = f.tracker.events.last()
        assertEquals(RepSide.BOTH, last.side)
        assertEquals(setOf(RepSide.LEFT, RepSide.RIGHT), last.cycles.keys)
        assertEquals("left", last.cycles.getValue(RepSide.LEFT).signalFeature)
        assertEquals(.4f, last.cycles.getValue(RepSide.RIGHT).min, .001f)
        assertEquals(4, last.total)
        assertEquals(last.timeMs, last.tMs)
        assertEquals(4, f.tracker.repTimesMs.size)
    }

    @Test fun simultaneousPairMayReturnOneCameraFrameApart() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare()
        for (i in 0..motion.size) {
            val l = motion.getOrNull(i) ?: 1.4f
            val r = motion.getOrNull(i - 1) ?: 1.4f
            f.frame(l, r)
        }
        assertEquals(1, f.tracker.counts.total)
        val e = f.tracker.events.single()
        assertEquals(300L, e.right!!.tMs - e.left!!.tMs)
    }

    @Test fun separatedUnilateralCyclesAreNotAPairedSimultaneousRep() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare(); f.cycle(r = false); f.cycle(l = false)
        assertEquals(ExerciseRepCounts(), f.tracker.counts)
        assertTrue(f.tracker.events.isEmpty())
    }

    @Test fun oneVisibleArmCannotCertifyABothArmsRep() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare()
        for (v in motion) f.frame(v, null)
        assertEquals(0, f.tracker.counts.total)
        f.prepare(); f.cycle()
        assertEquals(1, f.tracker.counts.total)
    }

    @Test fun lossOnOneSideDoesNotDiscardTheOtherSidesEachRep() {
        val f = Feed(RepMovementPattern.ALTERNATING_EACH)
        f.prepare()
        for ((i, v) in motion.withIndex()) f.frame(v, if (i == 2) null else v)
        assertEquals(ExerciseRepCounts(left = 1, total = 1), f.tracker.counts)
        f.prepare(); f.cycle()
        assertEquals(ExerciseRepCounts(left = 2, right = 1, total = 3), f.tracker.counts)
    }

    @Test fun explicitlyLosingOnlyRightSideKeepsLeftCycleInEachMode() {
        val f = Feed(RepMovementPattern.ALTERNATING_EACH)
        f.prepare()
        for (v in motion.take(3)) f.frame(v, v)
        f.tracker.onObservationLost(RepSide.RIGHT)
        for (v in motion.drop(3)) f.frame(v, v)
        assertEquals(ExerciseRepCounts(left = 1, total = 1), f.tracker.counts)
    }

    @Test fun eitherSidesLossInvalidatesPendingSimultaneousPair() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare()
        for (i in motion.indices) f.frame(motion[i], motion.getOrNull(i - 1) ?: 1.4f)
        f.tracker.onObservationLost(RepSide.LEFT)
        f.frame(1.4f, 1.4f)
        assertEquals(0, f.tracker.counts.total)
        f.prepare(); f.cycle()
        assertEquals(1, f.tracker.counts.total)
    }

    @Test fun singleSideModeIgnoresOtherSideMovementAndOcclusion() {
        val l = Feed(RepMovementPattern.LEFT_ONLY)
        l.prepare(); l.cycle(l = false)
        assertEquals(0, l.tracker.counts.total)
        for (v in motion) l.frame(v, null)
        assertEquals(ExerciseRepCounts(left = 1, total = 1), l.tracker.counts)
        val r = Feed(RepMovementPattern.RIGHT_ONLY)
        r.prepare()
        for (v in motion) r.frame(null, v)
        assertEquals(ExerciseRepCounts(right = 1, total = 1), r.tracker.counts)
    }

    @Test fun singleSideCanBeConstructedWithoutTheUnusedSignal() {
        val tracker = ExerciseRepTracker(RepMovementPattern.LEFT_ONLY, leftSignal = left)
        val f = Feed(RepMovementPattern.LEFT_ONLY, tracker)
        f.prepare(); f.cycle()
        assertEquals(1, tracker.counts.left)
        assertEquals("left", tracker.signalDescription)
    }

    @Test fun coupledLungeSignalIsOneUnknownSideCycleRatherThanTwoKnees() {
        for (pattern in listOf(RepMovementPattern.SIMULTANEOUS, RepMovementPattern.ALTERNATING_EACH)) {
            val tracker = ExerciseRepTracker(pattern, commonSignal = RepSignal("whole", .3f))
            var time = 0L
            fun frame(v: Float) {
                tracker.onFrame(time, mapOf("whole" to v, "left" to v, "right" to v))
                time += 300
            }
            repeat(3) { frame(1.4f) }
            repeat(3) { for (v in motion) frame(v) }
            assertEquals(ExerciseRepCounts(unknown = 3, total = 3), tracker.counts)
            assertTrue(tracker.events.all { it.side == RepSide.UNKNOWN && it.common != null })
            assertEquals(setOf(RepSide.UNKNOWN), tracker.events.last().cycles.keys)
            assertEquals(1800L, tracker.observedPeriodMs)
            tracker.onObservationLost()
            assertNull(tracker.periodMs)
            assertEquals(1800L, tracker.observedPeriodMs)
            assertEquals(ExerciseRepCounts(unknown = 3, total = 3), tracker.counts)
        }
    }

    @Test fun commonSignalRomMetadataCanBeOverriddenWithoutChangingPerformedCount() {
        val signal = RepSignal("whole", .3f, romDirection = "min", romThreshold = .2f)
        val tracker = ExerciseRepTracker(RepMovementPattern.SIMULTANEOUS, commonSignal = signal)
        var time = 0L
        for (v in listOf(1.4f, 1.4f, 1.4f) + motion) {
            tracker.onFrame(time, mapOf("whole" to v)); time += 300
        }
        assertEquals(1, tracker.counts.total)
        assertEquals(false, tracker.events.single().cycles.getValue(RepSide.UNKNOWN).valid)
    }

    @Test fun cameraBoundaryKeepsConfirmedCountsButDropsAnUnfinishedPair() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare(); f.cycle()
        val times = f.tracker.repTimesMs
        for (i in motion.indices) f.frame(motion[i], motion.getOrNull(i - 1) ?: 1.4f)
        f.tracker.resetCycle(); f.frame()
        assertEquals(1, f.tracker.counts.total)
        assertEquals(times, f.tracker.repTimesMs)
        assertNull(f.tracker.periodMs)
        f.prepare(); f.cycle()
        assertEquals(2, f.tracker.counts.total)
        assertEquals(1, times.size) // 이전에 반환한 목록은 스냅샷이다.
    }

    @Test fun missingInvalidOrLongGapCannotCompleteInterruptedCycles() {
        for (missing in listOf<Float?>(null, Float.NaN, Float.POSITIVE_INFINITY, .01f)) {
            val f = Feed(RepMovementPattern.ALTERNATING_EACH)
            f.prepare(); f.cycle()
            for (v in motion.take(3)) f.frame(v, v)
            f.frame(missing, missing)
            for (v in motion.drop(3)) f.frame(v, v)
            assertEquals(2, f.tracker.counts.total)
        }
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare()
        for (v in motion.take(3)) f.frame(v, v)
        f.time += 2000
        for (v in motion.drop(3)) f.frame(v, v)
        assertEquals(0, f.tracker.counts.total)
    }

    @Test fun duplicateOrOlderFramesDiscardPendingPairsWithoutRewindingTime() {
        for (delay in listOf(0L, 1000L)) {
            val f = Feed(RepMovementPattern.SIMULTANEOUS)
            f.prepare()
            for (i in motion.indices) f.frame(motion[i], motion.getOrNull(i - 1) ?: 1.4f)
            assertTrue(f.tracker.onFrame(f.time - 300 - delay, mapOf("left" to 1.4f, "right" to 1.4f)).isEmpty())
            f.frame(); f.prepare()
            assertEquals(0, f.tracker.counts.total)
            f.cycle()
            assertEquals(1, f.tracker.counts.total)
        }
    }

    @Test fun fullResetStartsANewSessionAndClock() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare(); f.cycle(); f.tracker.reset(); f.time = 0
        assertEquals(ExerciseRepCounts(), f.tracker.counts)
        assertTrue(f.tracker.events.isEmpty())
        f.prepare(); f.cycle()
        assertEquals(1, f.tracker.counts.total)
    }

    @Test fun observedPeriodRetainsMeasuredSegmentAfterPauseWithoutChangingCounts() {
        for (pattern in RepMovementPattern.entries) {
            val f = Feed(pattern)
            f.prepare()
            repeat(3) { f.cycle(l = pattern != RepMovementPattern.RIGHT_ONLY,
                r = pattern == RepMovementPattern.SIMULTANEOUS || pattern == RepMovementPattern.RIGHT_ONLY) }
            val counts = f.tracker.counts
            val events = f.tracker.events
            assertEquals(1800L, f.tracker.periodMs)
            assertEquals(1800L, f.tracker.observedPeriodMs)
            f.tracker.onObservationLost()
            f.tracker.onObservationLost() // 마감 전에 반복된 손실도 저장 값을 지우지 않는다.
            assertNull(f.tracker.periodMs)
            assertEquals(1800L, f.tracker.observedPeriodMs)
            assertEquals(counts, f.tracker.counts)
            assertEquals(events, f.tracker.events)
        }
    }

    @Test fun oneCompletionOnEachSideOfAGapDoesNotCreateObservedPeriod() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare(); f.cycle()
        f.time += 5000
        f.prepare(); f.cycle()
        assertEquals(2, f.tracker.counts.total)
        assertNull(f.tracker.periodMs)
        assertNull(f.tracker.observedPeriodMs)
        f.tracker.onObservationLost()
        assertNull(f.tracker.observedPeriodMs)
    }

    @Test fun aNewMeasurableSegmentReplacesThePriorObservedPeriodAndResetClearsIt() {
        val f = Feed(RepMovementPattern.SIMULTANEOUS)
        f.prepare(); repeat(2) { f.cycle() }
        f.tracker.onObservationLost()
        f.prepare(); f.cycle()
        assertNull(f.tracker.periodMs)
        assertEquals(1800L, f.tracker.observedPeriodMs)
        repeat(2) { f.frame() }
        f.cycle()
        assertEquals(2400L, f.tracker.periodMs)
        f.tracker.resetCycle()
        assertNull(f.tracker.periodMs)
        assertEquals(2400L, f.tracker.observedPeriodMs)
        f.tracker.reset()
        assertNull(f.tracker.periodMs)
        assertNull(f.tracker.observedPeriodMs)
        assertEquals(ExerciseRepCounts(), f.tracker.counts)
    }

    @Test fun missingSideClosesTimingWithoutDiscardingTheOtherSidesCounts() {
        val f = Feed(RepMovementPattern.ALTERNATING_EACH)
        f.prepare(); repeat(2) { f.cycle(r = false) }
        val counts = f.tracker.counts
        f.frame(r = null)
        assertNull(f.tracker.periodMs)
        assertEquals(1800L, f.tracker.observedPeriodMs)
        assertEquals(counts, f.tracker.counts)
    }

    @Test fun sameFrameEachSideCompletionsNeverProduceOrCacheAnArtificialPeriod() {
        val f = Feed(RepMovementPattern.ALTERNATING_EACH)
        f.prepare(); repeat(3) { f.cycle() }
        assertEquals(6, f.tracker.counts.total)
        assertNull(f.tracker.periodMs)
        assertNull(f.tracker.observedPeriodMs)
        f.tracker.onObservationLost()
        assertNull(f.tracker.observedPeriodMs)
    }

    @Test fun misleadingOrUnsupportedSignalConfigurationsAreRejected() {
        fun rejects(block: () -> Unit) {
            try { block(); fail("구성 오류를 거부해야 합니다") } catch (_: IllegalArgumentException) { }
        }
        rejects { ExerciseRepTracker(RepMovementPattern.LEFT_ONLY, commonSignal = left) }
        rejects { ExerciseRepTracker(RepMovementPattern.SIMULTANEOUS, leftSignal = left) }
        rejects { ExerciseRepTracker(RepMovementPattern.SIMULTANEOUS, left, left) }
        rejects { ExerciseRepTracker(RepMovementPattern.SIMULTANEOUS, left, right, left) }
        rejects { ExerciseRepTracker(RepMovementPattern.LEFT_ONLY, leftSignal = left.copy(isometric = true)) }
    }
}
