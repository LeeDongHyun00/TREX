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
    // §99 — 기준은 처음 가만히 있던 자세인데, 세트 중 쉬는 자세가 그보다 더 펴져 있다(MM-Fit 스쿼트 w18: 세트 전 154°, 세트 중 165°).
    // v1 은 띠(±0.066) 밖이라 0회였다. 기준을 진폭 미만으로 지나친 복귀는 복귀다
    @Test fun returnPastTheAnchorCounts() {
        val m=Motion();m.stable(1.2f);repeat(4){m.cycle(from=1.4f,to=.4f)}
        assertEquals(4,m.counter.reps)
    }
    // 매번 끝까지 펴지 않는 세트 — 깊이 1.0 중 0.2 를 남기고 돌아온다(깊이의 1/3 이하). v1 은 0회
    @Test fun returnShortOfTheAnchorWithinAThirdOfTheDepthCounts() {
        val m=Motion();m.stable(1.4f);repeat(4){m.cycle(from=1.2f,to=.4f)}
        assertEquals(4,m.counter.reps)
    }
    // 깊이의 1/3 을 넘게 남기면(0.4/1.0) 여전히 복귀가 아니다 — 반만 올라온 반동은 1회가 아니다
    @Test fun halfwayBounceIsNotAReturn() {
        val m=Motion();m.stable(1.4f);m.cycle(to=.4f,returnTo=1.0f);m.cycle(from=1.0f,to=.4f,returnTo=1.0f)
        assertEquals(0,m.counter.reps)
    }
    // 기준을 진폭 이상 지나치면 반대쪽의 새 움직임이지 복귀가 아니다
    @Test fun overshootByAFullAmplitudeIsNotAReturn() {
        val m=Motion();m.stable(1.0f);repeat(6){m.sample(.6f)};repeat(10){m.sample(1.5f)}
        assertEquals(0,m.counter.reps)
    }
    @Test fun fullResetAllowsANewSessionClock() {
        val m=Motion();m.stable();m.cycle();m.counter.reset();m.time=0;m.stable();m.cycle()
        assertEquals(1,m.counter.reps)
    }
}
