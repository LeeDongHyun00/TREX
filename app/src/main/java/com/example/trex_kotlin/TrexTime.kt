package com.example.trex_kotlin

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * 시각·날짜·ID 의 공통 경계(§108). java.time·Calendar·java.util.UUID 대신 kotlinx-datetime·Kotlin 표준을 써서 Android 와 iPhone 이
 * 같은 식으로 센다. 날짜는 기기 시간대 기준이다(LocalDate.now()·Calendar.getInstance() 와 같다).
 * 각 함수가 바꾸기 전 java 판과 같은 값을 내는지는 TrexTimeTest 가 본다.
 */

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 오늘 날짜 — `LocalDate.now()` 와 같다. */
fun localToday(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = Clock.System.todayIn(zone)

/** 오늘의 epochDay — `LocalDate.now().toEpochDay()` 와 같다. */
fun todayEpochDay(zone: TimeZone = TimeZone.currentSystemDefault()): Long = localToday(zone).toEpochDays()

/** 기록 날짜 칸 "월/일" — Calendar 의 `MONTH + 1`/`DAY_OF_MONTH` 와 같다. */
fun monthDayLabel(date: LocalDate): String = "${date.month.number}/${date.day}"

/** 지금 몇 시(0~23) — `Calendar.getInstance().get(Calendar.HOUR_OF_DAY)` 와 같다. */
fun currentHour(zone: TimeZone = TimeZone.currentSystemDefault()): Int = Clock.System.now().toLocalDateTime(zone).hour

/** 그 날의 첫 순간(ms) — `LocalDate.ofEpochDay(d).atStartOfDay(zone)` 과 같다. 자정이 없는 날(서머타임)은 그날의 첫 유효 시각이다. */
fun startOfDayMillis(epochDay: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    LocalDate.fromEpochDays(epochDay).atStartOfDayIn(zone).toEpochMilliseconds()

/** 세트 로그·라벨의 시각 문자열 — UTC, 초 단위. `SetLog.nowIso()` 의 "yyyy-MM-dd'T'HH:mm:ss'Z'" 와 같다. */
fun isoUtcSeconds(millis: Long = nowMillis()): String = Instant.fromEpochSeconds(millis.floorDiv(1000L)).toString()

/** "M월 d일 HH:mm"(기기 시간대) — DateTimeFormatter.ofPattern("M월 d일 HH:mm") 과 같다. */
fun monthDayTimeLabel(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "${t.month.number}월 ${t.day}일 ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** "M/d"(기기 시간대) — DateTimeFormatter.ofPattern("M/d") 과 같다. */
fun monthDaySlashLabel(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    monthDayLabel(Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date)

// java.time 과 같은 이름의 도우미 — 화면 코드가 이름을 거의 그대로 두고 kotlinx-datetime 위에서 돈다.
val LocalDate.monthValue: Int get() = month.number
fun LocalDate.toEpochDay(): Long = toEpochDays()
fun LocalDate.minusDays(days: Long): LocalDate = LocalDate.fromEpochDays(toEpochDays() - days)

/** 요일 이름 — java `getDisplayName(TextStyle.FULL, Locale.KOREAN)` = "월요일"(TrexTimeTest 가 대조한다). */
fun koreanDayOfWeekFull(day: DayOfWeek): String = koreanDayOfWeek(day) + "요일"

/** 요일 한 글자("월"…"일") — java `TextStyle.SHORT`·`NARROW` 의 한국어와 같다. */
fun koreanDayOfWeek(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
}

/** 무작위 UUID 문자열 — `java.util.UUID.randomUUID().toString()` 과 같은 형식(버전 4, 소문자 16진). */
@OptIn(ExperimentalUuidApi::class)
fun newUuid(): String = Uuid.random().toString()
