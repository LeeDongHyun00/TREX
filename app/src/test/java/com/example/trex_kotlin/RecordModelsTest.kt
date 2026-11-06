package com.example.trex_kotlin

import com.example.trex_kotlin.routines.PresetFilter
import com.example.trex_kotlin.routines.filtered
import com.example.trex_kotlin.routines.routinePresets
import com.example.trex_kotlin.routines.routineTopics
import com.example.trex_kotlin.routines.toWorkouts
import kotlinx.datetime.DayOfWeek
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** §131 기록 확장 — 세트 기록·PR·연속(목표 요일)·배지·내보내기·루틴 프리셋. 전부 '기록한 사실' 만 입력이다. */
class RecordModelsTest {
    private fun day(epoch: Long, vararg items: WorkoutHistoryItem) = WorkoutHistoryDay(epoch, "", "", items.toList(), 0, 0)
    private fun item(name: String, vararg sets: SetEntry, source: String = "session") = WorkoutHistoryItem(name, "10회 × ${sets.size}세트", 5, 30, sets = sets.toList(), source = source)

    @Test fun setEntryRoundTripsThroughJsonAndKeepsCameraValueApart() {
        val s = SetEntry(2, kg = 60.0, reps = null, cameraReps = 9, rpe = 8, memo = "벨트", done = true)
        val back = setEntryFromJson(JSONObject(setEntryJson(s).toString()))
        assertEquals(s, back)
        assertTrue(back.estimated)                     // 카메라 값만 — 추정
        assertNull(back.volumeKg)                       // 추정 횟수로 kg 을 곱하지 않는다
        assertEquals(9, back.countedReps)
        val fixed = back.copy(reps = 10)
        assertFalse(fixed.estimated); assertEquals(600.0, fixed.volumeKg!!, 0.0)   // 고친 값이 기록 기준
    }

    @Test fun summaryLineAndEpley() {
        assertEquals("60 kg × 10 · 10 · 8회", listOf(SetEntry(1, 60.0, 10), SetEntry(2, 60.0, 10), SetEntry(3, 60.0, 8)).summaryLine())
        assertEquals("45초 · 40초", listOf(SetEntry(1, seconds = 45), SetEntry(2, seconds = 40)).summaryLine())
        assertEquals("12 · 12 · 10회 · 추정", listOf(SetEntry(1, reps = 12), SetEntry(2, reps = 12), SetEntry(3, cameraReps = 10)).summaryLine())
        assertEquals(80.0, estimatedOneRepMax(60.0, 10)!!, 0.001)
        assertNull(estimatedOneRepMax(40.0, 15))        // 10회 넘으면 추정하지 않는다
    }

    @Test fun personalRecordNeedsConfirmedRepsAndBeatsPreviousBest() {
        val history = listOf(day(100, item("기본 스쿼트", SetEntry(1, 55.0, 10))))
        assertTrue(isPersonalRecord(history, "기본 스쿼트", SetEntry(1, 60.0, 10)))
        assertFalse(isPersonalRecord(history, "기본 스쿼트", SetEntry(1, 55.0, 12)))
        assertFalse(isPersonalRecord(history, "기본 스쿼트", SetEntry(1, 70.0, reps = null, cameraReps = 10)))   // 추정 횟수는 PR 아님
        assertTrue(isPersonalRecord(emptyList(), "기본 스쿼트", SetEntry(1, 20.0, 5)))                           // 첫 kg 기록도 PR
        assertEquals(VolumeSummary(550.0, 0), history.volume(90, 100))
    }

    @Test fun streakSkipsPlannedRestAndPausedDaysOnly() {
        // 목표 월·수·금. 금(epoch 7) 운동, 토·일 휴식, 월(10) 운동, 화 휴식, 오늘 수(12) 아직 안 함 → 2일
        val mon = 10L   // 1970-01-11 은 일요일 → epoch 10 = 1970-01-11? 계산 대신 요일 함수로 확인한다
        fun dow(e: Long) = kotlinx.datetime.LocalDate.fromEpochDays(e).dayOfWeek
        val monday = (0L..6L).first { dow(it) == DayOfWeek.MONDAY }
        val target = { d: DayOfWeek -> d == DayOfWeek.MONDAY || d == DayOfWeek.WEDNESDAY || d == DayOfWeek.FRIDAY }
        val friday = monday + 4; val nextMonday = monday + 7; val wednesday = monday + 9
        assertEquals(2, targetDayStreak(setOf(friday, nextMonday), wednesday, target))
        // 목표 요일(월)에 안 하면 끊긴다
        assertEquals(0, targetDayStreak(setOf(friday), wednesday, target))
        // 그 주를 멈춤으로 덮으면 이어진다
        assertEquals(1, targetDayStreak(setOf(friday), wednesday, target, StreakPause(nextMonday, 3)))
        // 요일 설정이 없으면 매일 — 예전 규칙과 같다
        assertEquals(2, targetDayStreak(setOf(99L, 100L), 100L, { false }))
        assertEquals(1, targetDayStreak(setOf(98L, 100L), 100L, { false }))
        assertEquals(2, longestTargetDayStreak(setOf(friday, nextMonday), wednesday, target))
    }

    @Test fun badgesFollowFactsOnly() {
        val none = badges(RewardFacts(0, 0, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0))
        assertTrue(none.none { it.earned })
        val some = badges(RewardFacts(workoutDays = 3, streak = 7, longestStreak = 7, coachSets = 50, guidesSeen = 16, guidesTotal = 16, personalRecords = 1, squatReps = 312, plankSeconds = 1800, dietDays = 7, manualRecords = 4, routineShares = 0))
        assertEquals(setOf("first_workout", "streak_7", "coach_50", "guides_all", "first_pr", "plank_30m", "diet_7"), some.filter { it.earned }.map { it.id }.toSet())
        assertEquals(312 to 500, some.first { it.id == "squat_500" }.let { it.progress to it.goal })
        assertEquals(1, Rewards.level(299)); assertEquals(2, Rewards.level(300)); assertEquals(180, Rewards.toNextLevel(120))
        assertEquals(120, Rewards.sessionXp(9, true)); assertEquals(90, Rewards.sessionXp(9, false))
    }

    @Test fun csvHasDesignColumnsAndNoPostureRawData() {
        val history = listOf(day(20000, item("레그 프레스", SetEntry(1, 90.0, 12, rpe = 8, memo = "메모, 쉼표"), source = "manual")))
        val csv = buildRecordCsv(history, listOf(BodyWeightEntry(20000, "morning", 68.2, 18.4, true, 0L)), null)
        val lines = csv.trim().split("\n")
        assertEquals("날짜,종목,세트,kg,회,카메라 횟수,초,RPE,출처,메모", lines[0])
        assertEquals("2024-10-04,레그 프레스,1,90,12,,,8,수동,\"메모, 쉼표\"", lines[1])
        assertTrue(csv.contains("체중 kg")); assertFalse(csv.contains("xy"))
        val json = JSONObject(buildRecordJson(history, emptyList(), null))
        assertEquals("trex-records-1", json.getString("format"))
        assertEquals(90.0, json.getJSONArray("workouts").getJSONObject(0).getJSONArray("items").getJSONObject(0).getJSONArray("sets").getJSONObject(0).getDouble("kg"), 0.0)
    }

    @Test fun weeklyRoutineAndRetentionAndNotifyRoundTrip() {
        val w = WeeklyRoutine().assigned(DayOfWeek.MONDAY, "preset:R01").assigned(DayOfWeek.THURSDAY, "saved:x")
        assertEquals(w, decodeWeeklyRoutine(JSONObject(encodeWeeklyRoutine(w).toString())))
        assertTrue(w.isTarget(DayOfWeek.MONDAY)); assertFalse(w.isTarget(DayOfWeek.TUESDAY))
        assertEquals(WeeklyRoutine(mapOf(4 to "saved:x")), w.assigned(DayOfWeek.MONDAY, null))
        assertEquals(NotifyPrefs(true, 7, 0, false, true), decodeNotifyPrefs(JSONObject(encodeNotifyPrefs(NotifyPrefs(true, 7, 0, false, true)).toString())))
        assertEquals("제한 없음", RetentionOptions.label(0)); assertEquals("1년", RetentionOptions.label(365))
        assertTrue(plannedRest(kotlinx.datetime.LocalDate.fromEpochDays(1), { it == DayOfWeek.MONDAY }) || !plannedRest(kotlinx.datetime.LocalDate.fromEpochDays(1), { it == DayOfWeek.MONDAY }))
        assertFalse(plannedRest(kotlinx.datetime.LocalDate.fromEpochDays(1), { false }))   // 목표 요일이 없으면 휴식 표시 없음
    }

    @Test fun routinePresetsCoverCatalogAndTopics() {
        assertEquals(60, routinePresets.size); assertEquals(10, routineTopics.size)
        val names = workoutCatalog.values.flatten().map { it.name }.toSet()
        assertTrue(routinePresets.flatMap { it.items }.all { it.name in names })
        assertEquals(60, routinePresets.map { it.id }.toSet().size)
        assertEquals(16, routinePresets.count { it.topic == null })
        assertEquals(4, routinePresets.count { it.topic == "T01" })
        val workouts = routinePresets.first { it.id == "R01" }.toWorkouts(1L)
        assertEquals(listOf("의자 스쿼트", "리버스 런지", "글루트 브릿지", "카프 레이즈"), workouts.map { it.name })
        assertEquals("12회 × 2세트", workouts[0].reps); assertEquals(75, workouts[0].restSeconds)
        assertTrue(routinePresets.filtered(PresetFilter(topic = "T10", beginner = true)).isNotEmpty())
        assertTrue(routinePresets.filtered(PresetFilter(equipment = "밴드")).all { it.equipment == "밴드" })
    }

    @Test fun catalogRegionsAndEquipmentsResolveForEveryExercise() {
        workoutCatalog.values.flatten().forEach { t ->
            assertTrue(t.name, catalogRegionOf(t.name, t.category) in catalogRegions)
            assertTrue(t.name, catalogEquipmentOf(t.name) in catalogEquipments)
        }
        assertEquals("머신", catalogEquipmentOf("레그 프레스")); assertEquals("케이블", catalogEquipmentOf("케이블 페이스 풀")); assertEquals("맨몸", catalogEquipmentOf("벽 푸쉬업"))
        assertEquals("가슴", catalogRegionOf("푸쉬업", "상체")); assertEquals("등", catalogRegionOf("풀업", "상체"))
    }
}
