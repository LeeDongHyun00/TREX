package com.example.trex_kotlin.community

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 글·댓글 시각(설계 §7) — "방금"·"n분 전"·"n시간 전"·"n일 전", 일주일이 넘으면 "M월 d일"(올해가 아니면 연도까지).
 * 기기 시계가 서버보다 조금 느려 미래 시각이 오면 "방금".
 */
fun timeAgo(at: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val diff = (now - at).coerceAtLeast(0)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        minutes < 24 * 60 -> "${minutes / 60}시간 전"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}일 전"
        else -> {
            val date = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date
            val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
            val md = "${date.month.number}월 ${date.day}일"
            if (date.year == today.year) md else "${date.year}년 $md"
        }
    }
}
