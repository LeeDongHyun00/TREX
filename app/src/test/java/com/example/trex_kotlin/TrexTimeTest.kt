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

    @Test
    fun isoUtcSecondsMatchesSetLogNowIso() {
        val samples = listOf(0L, 999L, 1_000L, 59_999L, 1_761_100_000_123L, 1_761_100_000_999L, 1_798_761_599_999L, 4_102_444_799_999L)
        for (millis in samples) assertEquals("$millis", SetLog.nowIso(Date(millis)), isoUtcSeconds(millis))
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
