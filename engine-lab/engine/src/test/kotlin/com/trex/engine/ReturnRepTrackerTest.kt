package com.trex.engine

import org.junit.Assert.*
import org.junit.Test

class ReturnRepTrackerTest {
    /** 실제 라이브 간격에서 끝점 한 프레임만 보이는 연속 반복. 인위적인 끝점 정지를 넣지 않는다. */
    private class ContinuousMotion(val origin: Float = 1.4f, val far: Float = .4f) {
        val counter = TestChannel(RepSignal("test", .3f), maxGapMs = 1500)
        var time = 0L
        fun sample(value: Float?): Boolean {
            val completed = counter.onFrame(time, value)
            time += 300
            return completed
        }
        fun prepare() { repeat(3) { sample(origin) } }
        fun cycle() {
            for (fraction in listOf(.3f, .7f, 1f, .7f, .3f, 0f))
                sample(origin + (far - origin) * fraction)
        }
    }

    @Test fun continuousRepetitionsNeedNoPauseAtTheReturnPoint() {
        for ((origin, far) in listOf(1.4f to .4f, .4f to 1.4f)) {
            val m = ContinuousMotion(origin, far)
            m.prepare()
            repeat(5) { index ->
                m.cycle()
                assertEquals("복귀 한 프레임에서 마지막 반복까지 확정", index + 1, m.counter.reps)
            }
            repeat(8) { m.sample(origin) }
            assertEquals(5, m.counter.reps)
        }
    }

    @Test fun oneMissingSampleDiscardsAnUnfinishedCycleAndKeepsCompletedCount() {
        val m = ContinuousMotion()
        m.prepare(); m.cycle()
        m.sample(1.1f); m.sample(.7f); m.sample(.4f)
        m.sample(null)
        m.sample(.7f); m.sample(1.1f)
        repeat(4) { m.sample(1.4f) }
        assertEquals(1, m.counter.reps)
        m.cycle()
        assertEquals(2, m.counter.reps)
    }

    @Test fun isolatedLargeSpikesDoNotBecomeRepetitionsAfterLongIdle() {
        val m = ContinuousMotion()
        m.prepare(); repeat(10) { m.sample(1.4f) }
        repeat(4) {
            m.sample(.4f)
            repeat(5) { m.sample(1.4f) }
        }
        assertEquals(0, m.counter.reps)
    }

    @Test fun explicitObservationLossKeepsHistoryButRequiresANewStart() {
        val m = ContinuousMotion()
        m.prepare(); m.cycle(); m.cycle()
        val completedTimes = m.counter.repTimesMs.toList()
        m.sample(1.1f); m.sample(.7f); m.sample(.4f)
        m.counter.onObservationLost()
        assertNull(m.counter.periodMs)
        assertEquals(completedTimes, m.counter.repTimesMs)
        m.sample(.7f); m.sample(1.1f); m.prepare()
        assertEquals(2, m.counter.reps)
        m.cycle()
        assertEquals(3, m.counter.reps)
    }

    @Test fun nonFiniteAndPhysicallyImplausibleInputsBreakTheCycle() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, .01f)) {
            val m = Motion()
            m.stable(); m.cycle()
            repeat(5) { m.sample(.4f) }
            m.sample(invalid)
            m.stable()
            assertEquals("관측 손실: $invalid", 1, m.counter.reps)
            m.cycle()
            assertEquals(2, m.counter.reps)
        }
    }

    @Test fun tooShortCompleteExcursionsCannotMatureWhileStandingStill() {
        val c = TestChannel(RepSignal("test", .3f))
        var t = 0L
        repeat(3) { c.onFrame(t, 1.4f); t += 300 }
        repeat(20) {
            for (v in listOf(1.1f, .7f, .4f, .7f, 1.1f, 1.4f)) {
                c.onFrame(t, v); t += 20
            }
        }
        repeat(12) { c.onFrame(t, 1.4f); t += 300 }
        assertEquals(0, c.reps)
    }

    @Test fun movingPreparationWithoutStableStartDoesNotCount() {
        val c = TestChannel(RepSignal("test", .3f))
        var t = 0L
        repeat(5) {
            for (v in listOf(1.4f, 1.1f, .7f, .4f, .7f, 1.1f)) {
                c.onFrame(t, v); t += 300
            }
        }
        assertEquals(0, c.reps)
    }

    @Test fun longGapWithoutExplicitMissingSampleInvalidatesTheCycle() {
        val m = ContinuousMotion()
        m.prepare(); m.cycle()
        m.sample(1.1f); m.sample(.7f); m.sample(.4f)
        m.time += 1501
        m.sample(.7f); m.sample(1.1f); m.prepare()
        assertEquals(1, m.counter.reps)
        m.cycle()
        assertEquals(2, m.counter.reps)
    }

    @Test fun duplicateAndRegressingTimestampsCannotSupplyReturnEvidence() {
        for (backward in listOf(0L, 600L)) {
            val m = ContinuousMotion()
            m.prepare(); m.cycle()
            m.sample(1.1f); m.sample(.7f); m.sample(.4f)
            val staleTime = m.time - 300 - backward
            repeat(4) { assertFalse(m.counter.onFrame(staleTime, 1.4f)) }
            m.sample(.7f); m.sample(1.1f); m.prepare()
            assertEquals(1, m.counter.reps)
            m.cycle()
            assertEquals(2, m.counter.reps)
        }
    }

    @Test fun trackerAlsoRejectsStaleFramesWhenUsedWithoutTheCounter() {
        val tracker = ReturnRepTracker(.3f)
        for (t in listOf(0L, 300L, 600L)) assertNull(tracker.onFrame(t, 1.4f))
        assertNull(tracker.onFrame(900, .7f))
        assertNull(tracker.onFrame(1200, .4f))
        assertNull(tracker.onFrame(1200, 1.4f))
        // 뒤늦게 배달된 표본 여러 개로 준비 상태를 만들 수 없다.
        for (t in listOf(0L, 400L, 800L)) assertNull(tracker.onFrame(t, 1.4f))
        for ((t, v) in listOf(1500L to .4f, 1800L to .7f, 2100L to 1.4f))
            assertNull(tracker.onFrame(t, v))
        assertNull(tracker.onFrame(2400, 1.4f))
        assertNull(tracker.onFrame(2700, 1.4f))
        var completed = 0
        for ((index, v) in listOf(1.1f, .7f, .4f, .7f, 1.1f, 1.4f).withIndex())
            if (tracker.onFrame(3000L + index * 300L, v) != null) completed++
        assertEquals(1, completed)
    }

    private class Motion {
        val counter = TestChannel(RepSignal("test", .3f, plausibleMin = .1f), maxGapMs = 1500)
        var time = 0L
        fun sample(value: Float?) { counter.onFrame(time, value); time += 100 }
        fun stable(value: Float = 1.4f) { repeat(7) { sample(value) } }
        fun cycle(from: Float = 1.4f, to: Float = .4f, returnTo: Float = from) {
            for (i in 1..10) sample(from + (to - from) * i / 10)
            for (i in 1..10) sample(to + (returnTo - to) * i / 10)
            stable(returnTo)
        }
    }
    @Test fun lastRepCompletesWithoutStartingAnother() {
        val m=Motion();m.stable();repeat(5){m.cycle()}
        assertEquals(5,m.counter.reps)
        repeat(50){m.sample(1.4f)}
        assertEquals(5,m.counter.reps)
        assertTrue(m.counter.lastCycleMin < .6f)
    }
    @Test fun returnWorksWhenTheExerciseStartsAtTheLowEnd() {
        val m=Motion();m.stable(.4f);repeat(3){m.cycle(.4f,1.4f)}
        assertEquals(3,m.counter.reps)
    }
    @Test fun incompleteReturnAndStaticPreparationAreNotReps() {
        val m=Motion();repeat(40){m.sample(1.4f)}
        assertEquals(0,m.counter.reps)
        m.cycle(returnTo=.8f)
        assertEquals(0,m.counter.reps)
    }
    @Test fun smallMovementsAreNotReps() {
        val m=Motion();m.stable();repeat(4){m.cycle(to=1.2f)}
        assertEquals(0,m.counter.reps)
    }
    @Test fun occlusionCannotCompleteTheInterruptedMovement() {
        val m=Motion();m.stable();repeat(10){m.sample(.4f)}
        repeat(20){m.sample(null)}
        m.stable();assertEquals(0,m.counter.reps)
        m.cycle();assertEquals(1,m.counter.reps)
    }
    @Test fun pausingKeepsCountButDiscardsAnUnfinishedRep() {
        val m=Motion();m.stable();m.cycle();repeat(10){m.sample(.4f)}
        m.counter.resetCycle();m.stable()
        assertEquals(1,m.counter.reps)
        m.cycle();assertEquals(2,m.counter.reps)
    }
    @Test fun fullResetAllowsANewSessionClock() {
        val m=Motion();m.stable();m.cycle();m.counter.reset();m.time=0;m.stable();m.cycle()
        assertEquals(1,m.counter.reps)
    }
}
