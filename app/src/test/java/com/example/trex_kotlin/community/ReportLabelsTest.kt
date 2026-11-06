package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 내 신고 처리 상태 문구(설계 §3 처리 결과 알림 — 신청인이 앱 안에서 결과를 본다). */
class ReportLabelsTest {
    private fun report(resolved: Boolean, resolution: String?) =
        MyReport(1, ReportTarget.POST, "7", ReportReason.SPAM, 0, resolved, resolution)

    @Test
    fun 처리_상태() {
        assertEquals("확인 중", reportStatusLabel(report(false, null)))
        assertEquals("처리 완료 · 삭제·정지했어요", reportStatusLabel(report(true, "REMOVED")))
        assertEquals("처리 완료 · 규칙 위반이 아니었어요", reportStatusLabel(report(true, "KEPT")))
        assertEquals("댓글", reportTargetLabel(ReportTarget.COMMENT))
    }
}
