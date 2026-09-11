package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class WorkoutSessionTest {
    @Test fun observedOnlyExerciseKeepsPersonalMeasurementsInHistoryWithoutAccuracy() {
        val report=com.example.trex_kotlin.posture.PostureSetReport.build("id","벽 푸쉬업","벽 푸쉬업",
            com.example.trex_kotlin.posture.CoachMode.COACH,20,false,emptyList(),emptyList(),null,null,null,
            measurements=listOf("초반보다 왼쪽 팔꿈치의 움직임 범위가 줄었어요"))
        val correction=report.toCorrection()
        assertTrue(correction.focus.contains("왼쪽 팔꿈치"))
        assertEquals("unjudged",correction.kind);assertNull(report.accuracy)
    }
    private fun workout(reps: String = "12회 × 3세트", rest: Int = 60) =
        Workout("squat","바벨 스쿼트",reps,"999분",false,"하체",secondsPerRep=4,restSeconds=rest)

    @Test fun timeComesFromRepsAndOnlyTheGapsBetweenSets() {
        val timing=workout().timing()
        assertEquals(48,timing.workSeconds);assertEquals(264,timing.totalSeconds)
        assertEquals(5,workout().durationMinutes())
    }
    @Test fun holdDurationIsNotMultipliedByRepPace() {
        assertEquals(30,workout("30초 × 1세트").timing().totalSeconds)
        assertEquals(360,workout("전신 6분").timing().workSeconds)
        assertEquals(1,workout("전신 6분").setDraft().sets)
        assertEquals(420,workout("3분 × 2세트").timing().totalSeconds)
        assertEquals("3분",buildSessionSteps(listOf(workout("3분 × 2세트")))[1].workout.repsSpec().targetLabel)
    }
    @Test fun scheduleHasEverySetAndNoFinalRest() {
        val steps=buildSessionSteps(listOf(workout()))
        assertEquals(listOf(SessionPhase.PREPARE,SessionPhase.WORK,SessionPhase.REST,SessionPhase.WORK,SessionPhase.REST,SessionPhase.WORK),steps.map{it.phase})
        assertEquals(3,steps.filter{it.phase==SessionPhase.WORK}.map{it.workout.id}.distinct().size)
        assertEquals(listOf(2,3),steps.filter{it.phase==SessionPhase.REST}.map{it.setNumber})
    }
    @Test fun zeroRestAndSingleSetDoNotCreateEmptyTimers() {
        assertEquals(4,buildSessionSteps(listOf(workout(rest=0))).size)
        assertEquals(2,buildSessionSteps(listOf(workout("8회 × 1세트"))).size)
    }
    @Test fun elapsedClockUsesMillisecondsAndPauseDoesNotConsumeTime() {
        val p=SessionProgress(0,5000).tick(1200,false)
        assertEquals(4,p.secondsLeft);assertEquals(1200L,p.elapsedMs)
        assertEquals(p,p.tick(9999,true));assertEquals(0,p.tick(9999,false).secondsLeft)
    }
    @Test fun staleTimeoutCannotAdvanceTwiceAfterSkip() {
        val steps=buildSessionSteps(listOf(workout()))
        val p=SessionProgress(1,10).advance(steps,1,true)
        assertEquals(2,p.index);assertEquals(p,p.advance(steps,1,false))
        assertTrue(p.completed.isEmpty());assertEquals(setOf(1),p.skipped)
    }
    @Test fun allTimersCanReachCompletionWithoutButtonPresses() {
        val steps=buildSessionSteps(listOf(workout()))
        var p=SessionProgress(0,steps[0].seconds*1000L)
        while(p.index>=0){p=p.tick(p.remainingMs,false);p=p.advance(steps,p.index,false)}
        assertEquals(3,p.completedWorkouts(steps).size)
        assertEquals(setOf("squat"),p.completedOriginalIds(steps))
    }
    @Test fun skippingASetDoesNotCertifyTheWholeExerciseCompleted() {
        val steps=buildSessionSteps(listOf(workout("8회 × 2세트")))
        var p=SessionProgress(0,1)
        while(p.index>=0){val i=p.index;p=p.advance(steps,i,i==1)}
        assertEquals(1,p.completedWorkouts(steps).size);assertTrue(p.completedOriginalIds(steps).isEmpty())
    }
}
