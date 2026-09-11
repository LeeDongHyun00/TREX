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
    @Test fun preparationAndRepetitionsNeverExpire() {
        val steps = buildSessionSteps(listOf(workout()))
        var p = SessionProgress(0, 0).tick(600000, false, steps[0].timed, false)
        assertFalse(p.targetReached(steps[0])); assertEquals(0L, p.elapsedMs)
        p = p.advance(steps, 0, false)
        p = p.tick(600000, false, steps[1].timed)
        assertFalse(p.targetReached(steps[1])); assertEquals(600000L, p.elapsedMs)
        p = p.setRepetitions(steps, 1, 12)
        assertTrue(p.targetReached(steps[1]))
        p = p.advance(steps, 1, false)
        assertEquals(SessionPhase.REST, steps[p.index].phase)
        p = p.tick(60000, false, steps[p.index].timed)
        assertTrue(p.targetReached(steps[p.index]))
    }
    @Test fun countsAreEditableAndStaleEventsCannotAffectNextSet() {
        val steps = buildSessionSteps(listOf(workout()))
        var p = SessionProgress(1,0).setRepetitions(steps,1,12).setRepetitions(steps,1,8)
        assertFalse(p.targetReached(steps[1]))
        p = p.advance(steps,1,true)
        assertTrue(p.completed.isEmpty())
        assertEquals("8회 × 1세트",p.completedWorkouts(steps).single().reps)
        assertEquals(p,p.setRepetitions(steps,1,99))
        assertTrue(p.completedOriginalIds(steps).isEmpty())
    }
    @Test fun exitingKeepsPartialCountWithoutCertifyingCompletion() {
        val steps=buildSessionSteps(listOf(workout()))
        val p=SessionProgress(1,0).setRepetitions(steps,1,7).captureCount(steps[1])
        assertEquals("7회 × 1세트",p.completedWorkouts(steps).single().reps)
        assertTrue(p.completed.isEmpty());assertFalse(p.completedWorkouts(steps).single().done)
        assertTrue(p.setRepetitions(steps,1,0).captureCount(steps[1]).completedWorkouts(steps).isEmpty())
    }
    @Test fun explicitTargetKeepsTimeIndependentFromObservationAndLegacyText() {
        val time = workout().copy(target=WorkoutTarget.Duration(10))
        val steps=buildSessionSteps(listOf(time))
        assertTrue(steps[1].timed)
        assertEquals("10초", steps[1].workout.repsSpec().targetLabel)
        val p=SessionProgress(1,10000).tick(10000,false,steps[1].timed)
        assertTrue(p.targetReached(steps[1]))
        assertEquals(p,p.setRepetitions(steps,1,12))
        assertFalse(steps[0].timed)
    }
    @Test fun skippingASetDoesNotCertifyTheWholeExerciseCompleted() {
        val steps=buildSessionSteps(listOf(workout("8회 × 2세트")))
        var p=SessionProgress(0,1)
        while(p.index>=0){val i=p.index;p=p.setRepetitions(steps,i,if(i==1)0 else 8);p=p.advance(steps,i,i==1)}
        assertEquals(1,p.completedWorkouts(steps).size);assertTrue(p.completedOriginalIds(steps).isEmpty())
    }
}
