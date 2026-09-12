package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class ReturnRepTrackerTest {
    private class Motion {
        val counter = RepCounter(RepSignal("test", .3f, plausibleMin = .1f), maxGapMs = 1500, completeOnReturn = true)
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
