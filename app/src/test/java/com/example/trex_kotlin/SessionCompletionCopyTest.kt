package com.example.trex_kotlin

import com.example.trex_kotlin.posture.*
import org.junit.Assert.*
import org.junit.Test

class SessionCompletionCopyTest {
    private fun report(beta: Boolean, verdict: Verdict) = PostureSetReport(
        "set", "바벨 스쿼트", "스쿼트", CoachMode.COACH, 40, false,
        listOf(RuleOutcome("rule", "고개 정면", "시선", beta, verdict, null, null,
            "시선이 정면을 벗어나 있어요", "정면을 바라봐 주세요", null, .8f)), null, null, null,
    )

    @Test fun referenceSummaryDoesNotCoachOrClaimClean() {
        val reference = report(true, Verdict.VIOLATION)
        assertEquals(SetVerdict.REFERENCE, reference.verdict)
        assertEquals("운동별 자세 기록을 저장했어요", sessionHeadline(listOf(reference)))
        assertFalse(sessionHeadline(listOf(reference)).contains("참고만"))
        assertFalse(sessionHeadline(listOf(reference)).contains("깨끗"))
    }

    @Test fun referenceDoesNotHideShipIssueAndAbstainDoesNotBecomeClean() {
        val issue = report(false, Verdict.VIOLATION)
        assertEquals(sessionHeadline(listOf(issue)), sessionHeadline(listOf(report(true, Verdict.VIOLATION), issue)))
        assertEquals("자세를 판정할 만큼 화면에 잡히지 않았어요", sessionHeadline(listOf(report(false, Verdict.ABSTAIN))))
    }
}
