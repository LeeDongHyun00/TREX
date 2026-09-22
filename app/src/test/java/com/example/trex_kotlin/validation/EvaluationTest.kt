package com.example.trex_kotlin.validation

import org.junit.Assert.*
import org.junit.Test

class EvaluationTest {
    private fun truth(reps: List<TruthRep> = emptyList(), forms: List<TruthForm> = emptyList(), reviewed: Boolean = true) = Truth("R1",0,10_000,reviewed,reps,forms)
    @Test fun unreviewedIsNotZero() { assertNull(Evaluation.reps(truth(reviewed=false),listOf(PredRep(2000)))) }
    @Test fun explicitZeroCountsFalseReps() { val s=Evaluation.reps(truth(),listOf(PredRep(2000)))!!; assertEquals(1,s.extra); assertNull(s.meanTimingErrorMs) }
    @Test fun onePredictionCannotMatchTwoReps() {
        val t=truth(listOf(TruthRep(0,1000),TruthRep(1000,1500)))
        val s=Evaluation.reps(t,listOf(PredRep(1200)))!!
        assertEquals(1,s.matched); assertEquals(1,s.missed); assertEquals(200.0,s.meanTimingErrorMs!!,0.0)
    }
    @Test fun optimalMatchingDoesNotGreedilyStealLaterRep() {
        val s=Evaluation.reps(truth(listOf(TruthRep(0,1000),TruthRep(1000,1600))),listOf(PredRep(600),PredRep(1100)))!!
        assertEquals(2,s.matched)
    }
    @Test fun toleranceIsInclusive() { assertEquals(1,Evaluation.reps(truth(listOf(TruthRep(0,1000))),listOf(PredRep(1500)))!!.matched) }
    @Test fun distantPredictionIsExtraAndMissed() { val s=Evaluation.reps(truth(listOf(TruthRep(0,1000))),listOf(PredRep(1501)))!!; assertEquals(1,s.missed); assertEquals(1,s.extra) }
    @Test fun onlyLabeledRegionIsScored() { val t=Truth("R",2000,3000,true,emptyList(),emptyList()); assertEquals(0,Evaluation.reps(t,listOf(PredRep(1000),PredRep(4000)))!!.extra) }
    @Test fun leftDoesNotMatchRightInSideScore() { val t=truth(listOf(TruthRep(0,1000,"LEFT"))); assertEquals(0,Evaluation.reps(t,listOf(PredRep(1000,"RIGHT")),matchSide=true)!!.matched) }
    @Test fun totalScoreDoesNotPretendToScoreSide() { val t=truth(listOf(TruthRep(0,1000,"LEFT"))); assertEquals(1,Evaluation.reps(t,listOf(PredRep(1000,"RIGHT")))!!.matched) }
    @Test fun abstentionNeverBecomesNormal() {
        val s=Evaluation.forms(truth(forms=listOf(TruthForm(0,1000,"knee","VIOLATION"))),listOf(PredForm(0,"knee","ABSTAIN"))).single()
        assertEquals(0L,s.judgedMs); assertEquals(0.0,s.sensitivity!!,0.0); assertNull(s.falseAlarmRate)
    }
    @Test fun unsupportedStillShowsMissedError() { val s=Evaluation.forms(truth(forms=listOf(TruthForm(0,1000,"bar","VIOLATION"))),emptyList()).single(); assertEquals(1000L,s.errorMs); assertEquals(0L,s.detectedErrorMs) }
    @Test fun unknownTruthIsNotNormal() { val s=Evaluation.forms(truth(forms=listOf(TruthForm(0,1000,"x","UNKNOWN"))),listOf(PredForm(0,"x","VIOLATION"))).single(); assertEquals(1000L,s.unknownTruthMs); assertNull(s.coverage); assertNull(s.sensitivity) }
    @Test fun predictionsExpireDuringOcclusion() {
        val s=Evaluation.forms(truth(forms=listOf(TruthForm(0,1000,"x","VIOLATION"))),listOf(PredForm(0,"x","VIOLATION"))).single()
        assertEquals(250L,s.detectedErrorMs); assertEquals(.25,s.coverage!!,0.0)
    }
    @Test fun explicitAbstentionEndsPreviousVerdict() {
        val s=Evaluation.forms(truth(forms=listOf(TruthForm(0,1000,"x","OK"))),listOf(PredForm(0,"x","VIOLATION"),PredForm(100,"x","ABSTAIN"))).single()
        assertEquals(100L,s.falseAlarmMs); assertEquals(100L,s.judgedMs)
    }
    @Test fun timeWeightDoesNotCountFramesAsIndependentPeople() {
        val s=Evaluation.forms(truth(forms=listOf(TruthForm(100,400,"x","OK"))),listOf(PredForm(0,"x","VIOLATION"),PredForm(200,"x","OK"))).single()
        assertEquals(100L,s.falseAlarmMs); assertEquals(300L,s.judgedMs)
    }
    @Test fun unlabeledTimeIsIgnored() { assertTrue(Evaluation.forms(truth(),listOf(PredForm(0,"x","VIOLATION"))).isEmpty()) }
    @Test(expected=IllegalArgumentException::class) fun rejectOverlappingTruth() { truth(forms=listOf(TruthForm(0,2000,"x","OK"),TruthForm(1000,3000,"x","VIOLATION"))).validate(10_000) }
    @Test(expected=IllegalArgumentException::class) fun rejectDuplicateReps() { truth(listOf(TruthRep(0,1000),TruthRep(0,1000))).validate(10_000) }
    @Test(expected=IllegalArgumentException::class) fun rejectOutsideVideo() { truth().validate(9000) }
    @Test(expected=IllegalArgumentException::class) fun reviewerRequired() { truth().copy(reviewer="").validate(10_000) }
    @Test fun holdRequiresReviewedTruth() { assertNull(Evaluation.hold(truth(),emptyList())) }
    @Test fun holdReportsMissedAndExtraSeparately() {
        val s=Evaluation.hold(truth().copy(holdsReviewed=true,holds=listOf(TimeSpan(1000,3000))),listOf(TimeSpan(500,2000)))!!
        assertEquals(1000L,s.overlapMs);assertEquals(1000L,s.missedMs);assertEquals(500L,s.extraMs)
    }
    @Test(expected=IllegalArgumentException::class) fun overlappingHoldRejected() {truth().copy(holds=listOf(TimeSpan(0,1000),TimeSpan(500,1500))).validate(10_000)}
}
