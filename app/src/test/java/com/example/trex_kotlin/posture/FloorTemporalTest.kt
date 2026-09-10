package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class FloorTemporalTest {
    private fun tracker() = HoldTracker(HoldConfig(.06f, .06f, 5000, 2000))
    private fun baseline(t: HoldTracker) { for (ms in 0L..5000L step 250) t.add(ms, 0f) }

    @Test fun initialPostureIsNotScoredAndShortSetAbstains() {
        val t = tracker()
        t.add(0, .3f)
        assertNull(t.snapshot().baseline)
        assertEquals(0L, t.snapshot().inBandMs)
    }

    @Test fun sustainedBreakSurvivesRecoveryAndCannotBeAveragedAway() {
        val t = tracker(); baseline(t)
        for (ms in 5250L..7500L step 250) t.add(ms, .13f)
        for (ms in 7750L..15000L step 250) t.add(ms, 0f)
        assertEquals(5250L, t.snapshot().firstBreakMs)
        assertEquals("화면 위쪽", t.snapshot().direction)
        assertTrue(t.snapshot().measuredMs > t.snapshot().inBandMs)
    }

    @Test fun briefNoiseAndAlternatingDirectionsDoNotMakeSustainedBreak() {
        val t = tracker(); baseline(t)
        for (ms in 5250L..10000L step 250) t.add(ms, if (ms % 500L == 0L) .12f else -.12f)
        assertNull(t.snapshot().firstBreakMs)
    }

    @Test fun missingFramesDoNotCountAsGoodTimeOrBridgeAnEvent() {
        val t = tracker(); baseline(t)
        t.add(5250, -.12f); t.add(5500, -.12f)
        val before = t.snapshot().measuredMs
        t.add(6000, null); t.add(12000, -.12f)
        assertEquals(before, t.snapshot().measuredMs)
        assertNull(t.snapshot().firstBreakMs)
    }

    @Test fun unstableSetupCannotBecomeReference() {
        val t = tracker()
        for (ms in 0L..10000L step 250) t.add(ms, if (ms % 500L == 0L) .2f else 0f)
        assertNull(t.snapshot().baseline)
    }

    private fun rule() = PostureRule("test", "푸시업", "가슴의 충분한 이동", null, RuleStatus.BETA, null,
        "wrist_shoulder_d", "wrist_shoulder_d", "rep", "", ">", .71f, "C", "", Float.NaN, Float.NaN, 0, true, emptyList(),
        kind = "rep", repConfig = RepRuleConfig("min", .71f, .34f, 2))

    @Test fun oneDeepRepDoesNotHideRepeatedShallowReps() {
        val result = FloorTemporal.repResult(rule(), listOf(.5f, .9f, .9f, .9f).mapIndexed { i, v -> RepRecord(i * 3000L, v, 1.4f, null) })
        assertEquals(Verdict.VIOLATION, result.verdict)
        assertEquals(.75f, result.value!!, .001f)
        assertEquals(4, result.sampleCount)
    }

    @Test fun noRepsAndSingleRepCannotPass() {
        assertEquals(Verdict.ABSTAIN, FloorTemporal.repResult(rule(), emptyList()).verdict)
        assertEquals(Verdict.ABSTAIN, FloorTemporal.repResult(rule(), listOf(RepRecord(0, .5f, 1.4f, true))).verdict)
    }

    @Test fun repRulesCannotFallBackToSetMinimum() {
        val r = rule(); val agg = FeatureAggregator()
        repeat(12) { agg.add(mapOf("wrist_shoulder_d" to .5f)) }
        assertEquals(Verdict.ABSTAIN, PostureRuleSet("test", "", listOf(r)).evaluate("푸시업", agg).single().verdict)
    }

    @Test fun cleanupCutRequiresReliablePeriodAndAllowsOneLastCycle() {
        assertEquals(12000L, AssessmentWindow.end(25000, listOf(3000, 6000, 9000)))
        assertEquals(11000L, AssessmentWindow.end(11000, listOf(3000, 6000, 9000)))
        assertEquals(25000L, AssessmentWindow.end(25000, listOf(3000, 6000)))
        assertEquals(25000L, AssessmentWindow.end(25000, listOf(3000, 6000, 19000)))
    }

    @Test fun betaOnlyFloorNeverBecomesCleanOrSpeaksCorrection() {
        val r = rule()
        val report = PostureSetReport.build("id", "푸시업", "푸쉬업", CoachMode.COACH, 20, false,
            listOf(RuleResult(r, Verdict.OK, .5f, 4)), emptyList(), 4, 0, 3000,
            measurements = listOf("참고 · 검출 4회"))
        assertEquals(SetVerdict.REFERENCE, report.verdict)
        assertNull(report.accuracy)
        assertFalse(report.voiceLine.contains("깨끗"))
        assertTrue(report.voiceLine.contains("기록"))
    }

    @Test fun observationsWithoutJudgementsRemainUnjudged() {
        val report = PostureSetReport.build("id", "크런치", "크런치", CoachMode.COACH, 20, false,
            emptyList(), emptyList(), 3, 0, 3000, measurements = listOf("머리 들림 근사"))
        assertEquals(SetVerdict.UNJUDGED, report.verdict)
        assertNull(report.accuracy)
        assertTrue(report.summaryLine.contains("확정 판정 없음"))
    }

    @Test fun headLevelCueDoesNotClaimToMeasureHeadMotion() {
        val r = rule().copy(exercise = "힙쓰러스트", condition = "고개 들지 않기", stat = "p10", kind = "window")
        assertFalse(CoachCues.cueFor(r).habit.contains("흔들"))
        assertTrue(CoachCues.measurementNote(r)!!.contains("하위 10%"))
    }

    @Test fun occludedRepDoesNotJoinMovementBeforeAndAfterPause() {
        val counter = RepCounter(RepSignal("x", .3f), maxGapMs = 1500L)
        counter.onFrame(0, 0f)
        counter.onFrame(500, 1f)
        assertFalse(counter.onFrame(5000, 0f))
        assertEquals(0, counter.reps)
    }
}
