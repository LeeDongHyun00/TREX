package com.example.trex_kotlin

import com.example.trex_kotlin.posture.SetLog
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.UUID

/** §108: 공통 시각·ID 함수가 바꾸기 전 java 판(java.time·Calendar·SetLog·UUID)과 같은 값을 내는가. */
class TrexTimeTest {

    @Test
    fun todayMatchesLocalDateNow() {
        // 자정 경계에 걸리면 앞뒤 중 하나와 같다
        val before = LocalDate.now().toEpochDay()
        val ours = todayEpochDay()
        val after = LocalDate.now().toEpochDay()
        assertTrue("$before $ours $after", ours == before || ours == after)
    }

    @Test
    fun currentHourMatchesCalendar() {
        val before = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val ours = currentHour()
        val after = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        assertTrue("$before $ours $after", ours == before || ours == after)
    }

    @Test
    fun startOfDayMatchesJavaAcrossZonesAndMidnightTransitions() {
        // 자정에 서머타임이 바뀌어 자정이 없는 날이 있는 지역(상파울루·아바나·가자·베이루트)을 섞는다
        val zones = listOf(
            "Asia/Seoul", "UTC", "America/New_York", "Europe/London", "Australia/Lord_Howe",
            "America/Sao_Paulo", "America/Havana", "Asia/Gaza", "Asia/Beirut",
        )
        val first = LocalDate.of(2015, 1, 1).toEpochDay()
        val last = LocalDate.of(2026, 12, 31).toEpochDay()
        for (id in zones) {
            val javaZone = ZoneId.of(id)
            val zone = TimeZone.of(id)
            for (day in first..last) {
                val expected = LocalDate.ofEpochDay(day).atStartOfDay(javaZone).toInstant().toEpochMilli()
                assertEquals("$id $day", expected, startOfDayMillis(day, zone))
            }
        }
    }

    /** 세트 로그 시각 문자열(§108c 전 `SetLog` 의 SimpleDateFormat 판)과 같은가 — 이제 SetLog 가 이 함수들을 부른다. */
    private fun utc(pattern: String, millis: Long): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private val stampSamples = listOf(-1L, 0L, 999L, 1_000L, 59_999L, 1_761_100_000_123L, 1_761_100_000_999L, 1_798_761_599_999L, 4_102_444_799_999L)

    @Test
    fun isoUtcSecondsMatchesSetLogNowIso() {
        for (millis in stampSamples) {
            assertEquals("$millis", utc("yyyy-MM-dd'T'HH:mm:ss'Z'", millis), isoUtcSeconds(millis))
            assertEquals("$millis", isoUtcSeconds(millis), SetLog.nowIso(millis))
        }
    }

    @Test
    fun compactStampsMatchSimpleDateFormat() {
        for (millis in stampSamples) {
            assertEquals("$millis", utc("yyyyMMdd'T'HHmmss", millis), utcCompactStamp(millis))
            assertEquals("$millis", utc("yyyyMMdd", millis), utcDayStamp(millis))
        }
        // 세트 ID = 스탬프 + "-" + UUID 앞 8자리(소문자 16진)
        val id = SetLog.newSetId(1_761_100_000_123L)
        assertTrue(id, Regex("""20251022T022640-[0-9a-f]{8}""").matches(id))
        // 기기 시간대 날짜 — 피드백 기록 파일 이름(SimpleDateFormat 기본 시간대와 같다)
        val seoul = TimeZone.of("Asia/Seoul")
        val java = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
        for (millis in stampSamples) assertEquals("$millis", java.format(Date(millis)), dayStamp(millis, seoul))
    }

    @Test
    fun dayLabelsMatchCalendar() {
        val calendar = Calendar.getInstance()
        val first = LocalDate.of(2026, 1, 1).toEpochDay()
        for (day in first until first + 400) {
            val date = LocalDate.ofEpochDay(day)
            calendar.clear()
            calendar.set(date.year, date.monthValue - 1, date.dayOfMonth)
            val expectedDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "월"
                Calendar.TUESDAY -> "화"
                Calendar.WEDNESDAY -> "수"
                Calendar.THURSDAY -> "목"
                Calendar.FRIDAY -> "금"
                Calendar.SATURDAY -> "토"
                else -> "일"
            }
            val ours = kotlinx.datetime.LocalDate.fromEpochDays(day)
            assertEquals("$date", expectedDay, koreanDayOfWeek(ours.dayOfWeek))
            assertEquals("$date", "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}", monthDayLabel(ours))
        }
    }

    @Test
    fun koreanDayNamesMatchJavaDisplayNames() {
        for (day in java.time.DayOfWeek.entries) {
            val ours = kotlinx.datetime.DayOfWeek.entries[day.ordinal]
            assertEquals("$day FULL", day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN), koreanDayOfWeekFull(ours))
            assertEquals("$day SHORT", day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN), koreanDayOfWeek(ours))
            assertEquals("$day NARROW", day.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.KOREAN), koreanDayOfWeek(ours))
        }
    }

    @Test
    fun labelsMatchDateTimeFormatter() {
        val zone = ZoneId.of("Asia/Seoul")
        val full = java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm")
        val slash = java.time.format.DateTimeFormatter.ofPattern("M/d")
        for (millis in listOf(0L, 1_761_100_000_123L, 1_767_225_599_000L, 1_767_225_600_000L, 1_798_761_599_999L)) {
            val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
            assertEquals("$millis", at.format(full), monthDayTimeLabel(millis, TimeZone.of("Asia/Seoul")))
            assertEquals("$millis", at.format(slash), monthDaySlashLabel(millis, TimeZone.of("Asia/Seoul")))
        }
    }

    @Test
    fun javaNamedHelpersMatchJavaTime() {
        val first = LocalDate.of(2025, 12, 25).toEpochDay()
        for (day in first..first + 70) {
            val java = LocalDate.ofEpochDay(day)
            val ours = kotlinx.datetime.LocalDate.fromEpochDays(day)
            assertEquals(java.monthValue, ours.monthValue)
            assertEquals(java.dayOfMonth, ours.day)
            assertEquals(java.toEpochDay(), ours.toEpochDay())
            assertEquals(java.minusDays(6).toEpochDay(), ours.minusDays(6).toEpochDay())
        }
    }

    @Test
    fun newUuidIsRandomVersion4LikeJava() {
        val seen = HashSet<String>()
        repeat(200) {
            val text = newUuid()
            val parsed = UUID.fromString(text)
            assertEquals(text, parsed.toString())   // 소문자·하이픈 위치까지 java 형식 그대로
            assertEquals(4, parsed.version())
            assertEquals(2, parsed.variant())
            assertTrue(seen.add(text))
        }
    }
}
