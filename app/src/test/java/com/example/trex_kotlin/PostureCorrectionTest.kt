package com.example.trex_kotlin

import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.OnsetState
import com.example.trex_kotlin.posture.PostureRule
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.RepObservationSummary
import com.example.trex_kotlin.posture.RuleResult
import com.example.trex_kotlin.posture.RuleStatus
import com.example.trex_kotlin.posture.SetVerdict
import com.example.trex_kotlin.posture.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 기록 변환이 실제로 판정한 범위를 넘어 정상·교정 완료로 표현하지 않는지 확인한다. */
class PostureCorrectionTest {
    private val kneeRule = PostureRule(
        id = "스쿼트|무릎", exercise = "바벨 스쿼트", condition = "발과 무릎의 방향 일치",
        subtype = null, status = RuleStatus.SHIP, reason = null,
        feature = "knee_out_mean__mean", baseFeature = "knee_out_mean", stat = "mean",
        family = "x", op = "<", threshold = 0.01f, view = "C", viewDesc = "",
        cvAuc = 0.97f, cvBalacc = 0.8f, sampleN = 60, mirrorSafe = true, cautions = emptyList(),
    )

    private fun report(rule: PostureRule = kneeRule, recovered: Boolean = false) = PostureSetReport.build(
        setId = "scope-test", exercise = "바벨 스쿼트", workoutName = "스쿼트", mode = CoachMode.COACH,
        frames = 40, baselineActive = false, results = listOf(RuleResult(rule, Verdict.OK, 0f, 16)),
        onset = if (recovered) listOf(OnsetState(rule, Verdict.VIOLATION, Verdict.OK, 0f, 0f, OnsetKind.RECOVERED)) else emptyList(),
        repsValid = null, repsPartial = null, tempoMs = null,
    )

    @Test fun cleanRecordRetainsJudgedScope() {
        val report = report()
        assertEquals(SetVerdict.CLEAN, report.verdict)
        val correction = report.toCorrection()
        assertEquals("판정한 항목 범위 내", correction.focus)
        assertFalse(correction.focus.contains("깨끗"))
        assertNull(correction.observedLeftReps)
        assertNull(correction.observedRightReps)
        assertNull(correction.observedBothReps)
        assertNull(correction.observedUnknownReps)
        assertNull(correction.repMovementPattern)
    }

    @Test fun recoveredRecordDescribesObservationNotCompletedCorrection() {
        val report = report(recovered = true)
        assertEquals(SetVerdict.RECOVERED, report.verdict)
        val correction = report.toCorrection()
        assertEquals("무릎 관측 회복", correction.focus)
        assertFalse(correction.focus.contains("교정"))
        assertNull(correction.fix)
    }

    @Test fun betaOnlyRecordKeepsReferenceScope() {
        val report = report(kneeRule.copy(status = RuleStatus.BETA))
        assertEquals("참고 기준 이상 없음", report.toCorrection().focus)
        assertNull(report.accuracy)
    }

    @Test fun observedSummaryCopiesWithoutBecomingManualActualReps() {
        val correction = report().copy(observedReps = RepObservationSummary(
            pattern = "SIMULTANEOUS", total = 2, left = 2, right = 2, both = 2, unknown = 0,
        )).toCorrection()
        assertEquals(2, correction.observedLeftReps)
        assertEquals(2, correction.observedRightReps)
        assertEquals(2, correction.observedBothReps)
        assertEquals(0, correction.observedUnknownReps)
        assertEquals("SIMULTANEOUS", correction.repMovementPattern)
        assertNull(correction.actualReps)
        assertNull(correction.formLabel)
    }
}
