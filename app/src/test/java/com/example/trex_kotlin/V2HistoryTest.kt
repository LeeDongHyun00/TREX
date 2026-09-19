package com.example.trex_kotlin

import com.example.trex_kotlin.posture.*
import org.junit.Assert.*
import org.junit.Test

class V2HistoryTest {
    private val workout=Workout("one","바벨 스쿼트","7회 × 1세트","1분",true,"하체",target=WorkoutTarget.Repetitions(7))
    private fun report(actual:Int?=null)=PostureSetReport("s","바벨 스쿼트","세트",CoachMode.COACH,50,false,
        emptyList(),null,null,null,observedReps=RepObservationSummary("SIMULTANEOUS",10,0,0,0,10),
        observationEngine=true,userEnteredReps=actual,rangeGoalReps=7)
    @Test fun selectedRangeSevenIsNotMistakenForTenObservedRepsOrUserTruth() {
        val item=createWorkoutHistoryDay(listOf(workout),30,mapOf("one" to report())).items.single()
        assertEquals("10회 × 1세트",item.reps)
        assertNull(item.postureCorrection!!.actualReps)
        assertEquals(10,item.postureCorrection!!.observedUnknownReps)
        assertNull(item.accuracy)
    }
    @Test fun manualCorrectionPreservesModelCount() {
        val progress=com.trex.engine.RepProgress(observed=10,rangeMet=7,userEntered=12)
        assertEquals(7,progress.goalProgress)
        assertEquals(12,progress.recordedCount)
        val item=createWorkoutHistoryDay(listOf(workout),30,mapOf("one" to report(12))).items.single()
        assertEquals("12회 × 1세트",item.reps)
        assertEquals(12,item.postureCorrection!!.actualReps)
        assertEquals(10,item.postureCorrection!!.observedUnknownReps)
    }
    @Test fun unsupportedNewPlansAreRemovedWithoutChangingLegacyHistory() {
        val old=workout.copy(name="버피")
        assertTrue(normalizeV2Plan(listOf(old)).isEmpty())
        assertEquals("버피",createWorkoutHistoryDay(listOf(old),30).items.single().workoutName)
        assertEquals("바벨 스쿼트",normalizeV2Plan(listOf(workout.copy(name="기본 스쿼트"))).single().name)
    }
    @Test fun missingCameraResultDoesNotDiscardExplicitUserRecord() {
        val manual=report(8).copy(observedReps=null,rangeGoalReps=0)
        val item=createWorkoutHistoryDay(listOf(workout),30,mapOf("one" to manual)).items.single()
        assertEquals("8회 × 1세트",item.reps)
        assertEquals(8,item.postureCorrection!!.actualReps)
        assertNull(item.postureCorrection!!.observedUnknownReps)
    }
}
