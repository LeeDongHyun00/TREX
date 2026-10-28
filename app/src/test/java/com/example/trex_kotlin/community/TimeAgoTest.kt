package com.example.trex_kotlin.community

import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** 글·댓글 시각 — "방금"·"n분 전"·"n시간 전"·"n일 전"·"M월 d일"(설계 §7 문구). */
class TimeAgoTest {
    private val seoul = TimeZone.of("Asia/Seoul")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    // 2026-10-29 12:00 KST
    private val now = 1_793_242_800_000L

    private fun ago(diff: Long) = timeAgo(now - diff, now, seoul)

    @Test
    fun 가까운_시각은_얼마_전으로() {
        assertEquals("방금", ago(0))
        assertEquals("방금", ago(59_000))
        assertEquals("1분 전", ago(minute))
        assertEquals("59분 전", ago(59 * minute))
        assertEquals("1시간 전", ago(hour))
        assertEquals("23시간 전", ago(23 * hour))
        assertEquals("1일 전", ago(day))
        assertEquals("6일 전", ago(6 * day))
    }

    @Test
    fun 일주일이_넘으면_날짜로() {
        assertEquals("10월 22일", ago(7 * day))
        assertEquals("2025년 10월 29일", ago(365 * day))
    }

    @Test
    fun 기기_시계가_조금_느려도_미래_글은_방금() {
        assertEquals("방금", timeAgo(now + 5 * minute, now, seoul))
    }
}
