package com.example.trex_kotlin

import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.RepObservationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 완료·기록 화면이 횟수를 자세 합격으로 인증하거나 수동 입력에서 좌우를 만들어 내지 않는지 검증한다. */
class RepObservationPresentationTest {
    private fun report(summary: RepObservationSummary? = null, partial: Int? = 0) = PostureSetReport.build(
        setId = "rep-ui", exercise = "덤벨 컬", workoutName = "덤벨 컬", mode = CoachMode.COACH,
        frames = 40, baselineActive = false, results = emptyList(), onset = emptyList(),
        repsValid = 2, repsPartial = partial, tempoMs = null, observedReps = summary,
    )

    @Test fun simultaneousSidesRemainOneTotalPerPair() {
        val report = report(RepObservationSummary("SIMULTANEOUS", 2, 2, 2, 2, 0))
        val presentation = report.repObservationPresentation()
        assertEquals("참고 · 관측 2회 · 자세 미확정", presentation.summary)
        assertEquals("함께 2회", presentation.sides)
        assertEquals(presentation, report.toCorrection().repObservationPresentation())
        assertFalse(presentation.lines.any { it.contains("유효") || it.contains("무효") })
    }

    @Test fun newReportUsesItsAuthoritativeTotalAndKeepsAlternatingSides() {
        val presentation = report(RepObservationSummary("ALTERNATING_EACH", 5, 2, 3, 0, 0))
            .repObservationPresentation()
        assertEquals("참고 · 관측 5회 · 자세 미확정", presentation.summary)
        assertEquals("왼쪽 2회 · 오른쪽 3회", presentation.sides)
    }

    @Test fun manualTotalOverrideDoesNotRewriteCameraTotalOrSides() {
        val correction = report(RepObservationSummary("SIMULTANEOUS", 2, 2, 2, 2, 0)).toCorrection()
        assertEquals(correction.repObservationPresentation(), correction.copy(actualReps = 7).repObservationPresentation())
        assertEquals("함께 2회", correction.copy(actualReps = 7).repObservationPresentation().sides)
    }

    @Test fun oldHistoryMissingObservationsDoesNotBecomeZero() {
        val presentation = PostureCorrection(focus = "기록", actualReps = 8).repObservationPresentation()
        assertTrue(presentation.lines.isEmpty())
        val totalOnly = PostureCorrection(focus = "기록", repsValid = 8).repObservationPresentation()
        assertEquals("참고 · 관측 8회 · 자세 미확정", totalOnly.summary)
        assertNull(totalOnly.sides)
        assertNull(totalOnly.rangeNote)
    }

    @Test fun rangeNoteIsSeparateAndAbsentWhenThereAreNoPartialObservations() {
        val presentation = report(partial = 1).repObservationPresentation()
        assertEquals("참고 · 관측 3회 · 자세 미확정", presentation.summary)
        assertEquals("가동 범위 참고 · 기준에 못 미친 관측 1회", presentation.rangeNote)
        assertNull(report(partial = 0).repObservationPresentation().rangeNote)
        assertNull(report(partial = null).repObservationPresentation().rangeNote)
    }

    @Test fun unknownSideDoesNotInventLeftOrRightObservation() {
        val presentation = report(RepObservationSummary("SIMULTANEOUS", 2, 0, 0, 0, 2)).repObservationPresentation()
        assertEquals("좌우 미구분 2회", presentation.sides)
        val selectedSide = report(RepObservationSummary("LEFT_ONLY", 2, 2, 0, 0, 0)).repObservationPresentation()
        assertEquals("왼쪽 2회", selectedSide.sides)
    }
}
