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

    @Test fun bodyweightLoadKindAndVolume() {
        // 맨몸 스쿼트는 체중 비율 근거가 없어 kg 이 없으면 볼륨도 없다; 풀업·딥스만 체중 + 추가 kg (근거 감사 2026-11-07)
        assertEquals(ExerciseLoadKind.BODYWEIGHT, exerciseLoadKind("기본 스쿼트"))
        assertEquals(ExerciseLoadKind.BODYWEIGHT, exerciseLoadKind("푸쉬업"))
        assertEquals(ExerciseLoadKind.BODYWEIGHT_FULL, exerciseLoadKind("풀업"))
        assertEquals(ExerciseLoadKind.BODYWEIGHT_FULL, exerciseLoadKind("딥스"))
        assertEquals(ExerciseLoadKind.EXTERNAL, exerciseLoadKind("바벨 데드리프트"))
        assertEquals(ExerciseLoadKind.TIMED, exerciseLoadKind("플랭크"))
        assertEquals("+kg", exerciseLoadKind("기본 스쿼트").kgLabel()); assertEquals("kg", exerciseLoadKind("덤벨 컬").kgLabel())
        assertFalse(exerciseLoadKind("풀업").supportsOneRepMax()); assertTrue(exerciseLoadKind("덤벨 컬").supportsOneRepMax())
        assertEquals(null, SetEntry(1, reps = 12).volumeKg)                       // 맨몸, 추가 중량 없음 → 볼륨 없음
        assertEquals(120.0, SetEntry(1, kg = 10.0, reps = 12).volumeKg)            // 추가 중량만
        assertEquals(800.0, SetEntry(1, kg = 10.0, reps = 10, bodyKg = 70.0).volumeKg)   // 풀업: (70 + 10) × 10
        val s = SetEntry(2, kg = 5.0, reps = 8, bodyKg = 72.5)
        assertEquals(s, setEntryFromJson(setEntryJson(s)))
        assertEquals(null, setEntryFromJson(setEntryJson(SetEntry(1, reps = 5))).bodyKg)
        // 근거 약한 가중치(감사 F2 a·b·c) — 옮겨 온 종목은 '추정', 직접 EMG 종목은 아님; 감사 당시 39종
        val weak = com.example.trex_kotlin.trainingload.MuscleLoadCatalog.all.filter { it.evidenceWeak }.map { it.name }
        assertTrue("기본 스쿼트" in weak && "밴드 스쿼트" in weak && "케이블 페이스 풀" in weak && "바이시클 크런치" in weak)
        assertTrue("풀업" !in weak && "플랭크" !in weak && "덤벨 컬" !in weak)
        assertEquals(39, weak.size)
        assertEquals("회(쪽당)", countLabelFor("리버스 런지", false)); assertEquals("초(한쪽)", countLabelFor("사이드 플랭크", true))
        assertEquals("회", countLabelFor("데드버그", false)); assertEquals("회", countLabelFor("덤벨 컬", false))
        assertEquals("+10 kg × 8 · 8회", listOf(SetEntry(1, kg = 10.0, reps = 8), SetEntry(2, kg = 10.0, reps = 8)).summaryLine(bodyweight = true))
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
        assertEquals(1, targetDayStreak(setOf(friday), wednesday, target, listOf(StreakPause(nextMonday, 3))))
        // 멈춤을 해제해도 지난 날의 면제는 남는다 — 수요일에 해제하면 월·화까지로 접힌다(PR 65 리뷰)
        val resumed = listOf(StreakPause(nextMonday, 7)).endedBefore(wednesday)
        assertEquals(listOf(StreakPause(nextMonday, 2)), resumed)
        assertEquals(1, targetDayStreak(setOf(friday), wednesday, target, resumed))
        assertTrue(listOf(StreakPause(wednesday, 3)).endedBefore(wednesday).isEmpty())
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
        // 초보 보기 — 상한이 중급인 루틴(초중급~중급·중급 17개)만 빠지고, 쉬운 수준이 앞에 온다
        val beginner = routinePresets.filtered(PresetFilter(beginner = true))
        assertEquals(43, beginner.size)
        assertTrue(beginner.none { it.level.endsWith("중급") && !it.level.startsWith("초중급") || it.level.contains("~") })
        assertEquals(beginner.map { it.levelRank }, beginner.map { it.levelRank }.sorted())
        assertTrue(routinePresets.filtered(PresetFilter(part = "코어")).all { it.part.contains("코어") || it.part.contains("복근") })
        assertEquals(setOf("full", "lower", "upper", "core"), routinePresets.map { it.image }.toSet())
    }

    @Test fun catalogRegionsAndEquipmentsResolveForEveryExercise() {
        workoutCatalog.values.flatten().forEach { t ->
            assertTrue(t.name, catalogRegionOf(t.name, t.category) in catalogRegions)
            assertTrue(t.name, catalogEquipmentOf(t.name) in catalogEquipments)
        }
        assertEquals("머신", catalogEquipmentOf("레그 프레스")); assertEquals("케이블", catalogEquipmentOf("케이블 페이스 풀")); assertEquals("맨몸", catalogEquipmentOf("벽 푸쉬업"))
        assertEquals("가슴", catalogRegionOf("푸쉬업", "상체")); assertEquals("등", catalogRegionOf("풀업", "상체"))
    }

    /** §134b — 옛 식(분당 상수)으로 적힌 kcal 만 MET 식으로 고쳐 적고, 새 값·분류 없음·가동 0초 세션은 둔다. 두 번 돌려도 같다. */
    @Test fun legacyCaloriesMigrateOnlyWhenTheyMatchTheOldFormula() {
        val kg = { _: Long -> 70.0 }
        val legacySession = WorkoutHistoryItem("기본 스쿼트", "12회 x 3세트", 2, legacyCategoryCalories("하체", 150), category = "하체", durationSeconds = 150)
        val alreadyNew = legacySession.copy(calories = 40)
        val manualSets = listOf(SetEntry(1, reps = 10), SetEntry(2, reps = 10), SetEntry(3, reps = 10))
        val legacyManual = WorkoutHistoryItem("크런치", "10회 × 3세트", 0, legacyCategoryCalories("복근", 90), category = "복근", durationSeconds = 0, sets = manualSets, source = "manual")
        val noCategory = WorkoutHistoryItem("런지", "10회 x 3세트", 2, 14, durationSeconds = 120)
        val zeroSession = WorkoutHistoryItem("런지", "10회 x 3세트", 0, 0, category = "하체", durationSeconds = 0)
        val day = WorkoutHistoryDay(20000, "목", "10/3", listOf(legacySession, alreadyNew, legacyManual, noCategory, zeroSession), 0, 0)
        val migrated = listOf(day).migrateLegacyCalories(kg)
        val items = migrated.single().items
        // 옛 세션: 가동 150초 + 설정 휴식 2 × 120(기본 스쿼트) = 390초 × 5.0 MET(02052)
        assertEquals(estimatedKcal(5.0, 70.0, 390), items[0].calories)
        assertEquals(40, items[1].calories)
        // 옛 수동 기록: 횟수 세트 3 = 90초 + 휴식 2 × 45 = 180초 × 2.8 MET(02024)
        assertEquals(estimatedKcal(2.8, 70.0, 180), items[2].calories)
        assertEquals(14, items[3].calories)
        assertEquals(0, items[4].calories)
        assertEquals(migrated, migrated.migrateLegacyCalories(kg))
        // 바꿀 것이 없으면 같은 객체
        val clean = listOf(day.copy(items = listOf(alreadyNew)))
        assertSame(clean.single(), clean.migrateLegacyCalories(kg).single())
    }
}
