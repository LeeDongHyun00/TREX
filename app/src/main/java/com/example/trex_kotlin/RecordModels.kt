package com.example.trex_kotlin

import androidx.compose.runtime.Immutable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * 기록 확장 모델(§131, 디자인 캔버스 "TREX 격차 페이지 디자인" v2) — 세트별 kg·회·RPE·메모, 체중 기록, XP·배지, 직접 추가한 종목,
 * 주간 루틴 슬롯, 알림 설정, 보관 기간. 전부 org.json 으로 저장하고(TrexStore) iPhone 과 같은 소스다(android-sources.txt).
 * 보상(XP·배지)은 '운동을 마치고 기록한 사실' 에만 걸린다 — 자세 점수·카메라 횟수와 무관하다(디자인 범례, 원칙 #1).
 */

// ============================================================= 세트 기록

/**
 * 한 세트의 기록 — [reps] 는 사용자가 적거나 확정한 횟수, [cameraReps] 는 카메라가 센 값. 둘이 다르면 사용자가 적은 값이 기록 기준이고(P3),
 * 사용자 값이 없으면 통계에서 '추정' 으로 센다(P1). [seconds] 는 버티기·운반 종목의 유지 초.
 */
@Immutable
data class SetEntry(
    val index: Int,
    val kg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val cameraReps: Int? = null,
    val rpe: Int? = null,
    val memo: String? = null,
    val done: Boolean = true,
    /** 기록 당시 체중 — 체중 전부를 드는 종목(풀업·딥스, [ExerciseLoadKind.BODYWEIGHT_FULL])에서 체중 기록이 있을 때만. 볼륨 = (체중 + 추가 kg) × 회. */
    val bodyKg: Double? = null,
) {
    /** 통계가 쓰는 횟수 — 사용자 확정값 우선, 없으면 카메라 값. */
    val countedReps: Int? get() = reps ?: cameraReps
    /** 카메라 횟수만 있는 세트 — 볼륨·추이에서 '추정' 으로 표시한다. */
    val estimated: Boolean get() = reps == null && cameraReps != null
    /** 하중 — 외부 중량이면 kg, 체중 전부를 드는 종목이면 체중 + 추가 kg. 맨몸 일부 종목(스쿼트·푸쉬업)은 kg(추가 중량)만 — 체중 비율을 지어내지 않는다. */
    val loadKg: Double? get() = if (bodyKg != null) bodyKg + (kg ?: 0.0) else kg
    /** 볼륨(하중 × 회) — 하중과 사용자 확정 횟수가 둘 다 있을 때만(추정 횟수로 kg 을 곱하지 않는다). */
    val volumeKg: Double? get() = loadKg?.let { l -> reps?.let { l * it } }
}

internal fun setEntryJson(s: SetEntry): JSONObject = JSONObject().put("i", s.index).put("done", s.done).also { o ->
    s.kg?.let { o.put("kg", it) }
    s.reps?.let { o.put("reps", it) }
    s.seconds?.let { o.put("sec", it) }
    s.cameraReps?.let { o.put("cam", it) }
    s.rpe?.let { o.put("rpe", it) }
    s.memo?.takeIf { it.isNotBlank() }?.let { o.put("memo", it) }
    s.bodyKg?.let { o.put("bw", it) }
}

internal fun setEntryFromJson(o: JSONObject): SetEntry = SetEntry(
    index = o.optInt("i", 1),
    kg = if (o.has("kg")) o.optDouble("kg").takeIf { it.isFinite() && it >= 0 } else null,
    reps = if (o.has("reps")) o.optInt("reps", -1).takeIf { it >= 0 } else null,
    seconds = if (o.has("sec")) o.optInt("sec", -1).takeIf { it >= 0 } else null,
    cameraReps = if (o.has("cam")) o.optInt("cam", -1).takeIf { it >= 0 } else null,
    rpe = if (o.has("rpe")) o.optInt("rpe", -1).takeIf { it in 1..10 } else null,
    memo = o.optString("memo").takeIf { it.isNotBlank() },
    done = o.optBoolean("done", true),
    bodyKg = if (o.has("bw")) o.optDouble("bw").takeIf { it.isFinite() && it > 0 } else null,
)

internal fun encodeSetEntries(sets: List<SetEntry>): JSONArray = JSONArray().also { arr -> sets.forEach { arr.put(setEntryJson(it)) } }
internal fun decodeSetEntries(arr: JSONArray?): List<SetEntry> =
    if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> runCatching { setEntryFromJson(arr.getJSONObject(i)) }.getOrNull() }

/** 세트 기록 한 줄 — "60 kg × 10 · 10 · 8" / "45초 · 40초" / "12 · 12 · 10회". 카메라 값만 있는 세트는 뒤에 '추정' 을 붙인다. [bodyweight] 종목의 kg 은 추가 중량이라 "+10 kg". */
fun List<SetEntry>.summaryLine(bodyweight: Boolean = false): String {
    val done = filter { it.done }
    if (done.isEmpty()) return ""
    val kgs = done.mapNotNull { it.kg }.distinct()
    val plus = if (bodyweight) "+" else ""
    val counts = done.map { s -> s.seconds?.let { "${it}초" } ?: s.countedReps?.toString() ?: "–" }
    val head = if (kgs.size == 1) "$plus${trimKg(kgs.single())} kg × " else ""
    val tail = if (kgs.size > 1) done.joinToString(" · ") { s -> "$plus${s.kg?.let(::trimKg) ?: "–"}×${s.countedReps ?: s.seconds ?: "–"}" } else counts.joinToString(" · ")
    val unit = if (kgs.size <= 1 && done.all { it.seconds == null }) "회" else ""
    return (head + tail + unit + if (done.any { it.estimated }) " · 추정" else "").trim()
}

fun trimKg(kg: Double): String = if (kg == kg.roundToInt().toDouble()) kg.roundToInt().toString() else ((kg * 10).roundToInt() / 10.0).toString()

/** Epley 추정 1RM — kg × (1 + 회/30). 10회 이하에서만 의미가 있어 그 밖은 null(P4 '추정' 표기). */
fun estimatedOneRepMax(kg: Double, reps: Int): Double? = if (reps in 1..10) kg * (1 + reps / 30.0) else null

// ============================================================= 기록 조회 도우미

/** 기록 항목이 소속된 날짜까지 함께. */
data class DatedSet(val epochDay: Long, val workoutName: String, val set: SetEntry, val manual: Boolean)

fun List<WorkoutHistoryDay>.datedSets(): List<DatedSet> = flatMap { day ->
    day.items.flatMap { item -> item.sets.filter { it.done }.map { DatedSet(day.epochDay, item.workoutName, it, item.source == "manual") } }
}

/** 종목별 최고 kg(확정 횟수 있는 세트) — 없으면 null. */
fun List<WorkoutHistoryDay>.bestKg(workoutName: String): DatedSet? =
    datedSets().filter { it.workoutName == workoutName && it.set.kg != null && it.set.reps != null }.maxByOrNull { it.set.kg!! }

/** 이번 세트가 개인 기록인가 — 같은 종목의 이전 최고 kg 보다 크고 확정 횟수가 있을 때. [before] 는 이 세션 저장 전 기록. */
fun isPersonalRecord(before: List<WorkoutHistoryDay>, workoutName: String, set: SetEntry): Boolean {
    val kg = set.kg ?: return false
    if (set.reps == null || set.reps <= 0) return false
    val previous = before.bestKg(workoutName)?.set?.kg ?: return true
    return kg > previous
}

/** 기간 안 볼륨(kg × 확정 회) 합과 카메라 횟수만 있는 '추정' 세트 수. */
data class VolumeSummary(val kg: Double, val estimatedSets: Int)

fun List<WorkoutHistoryDay>.volume(from: Long, to: Long): VolumeSummary {
    val sets = filter { it.epochDay in from..to }.datedSets()
    return VolumeSummary(sets.sumOf { it.set.volumeKg ?: 0.0 }, sets.count { it.set.estimated })
}

// ============================================================= 연속 운동(목표 요일 기준)

/** 연속 잠시 멈춤 — 부상·여행 때 최대 [MAX_DAYS]일. 멈춘 날은 연속이 줄지 않는다(돈·아이템 없음, P6). */
@Immutable
data class StreakPause(val fromEpochDay: Long, val days: Int) {
    val untilEpochDay: Long get() = fromEpochDay + days - 1
    fun covers(epochDay: Long) = epochDay in fromEpochDay..untilEpochDay
    companion object { const val MAX_DAYS = 14 }
}

/** [today] 전날까지로 구간을 접는다 — 오늘 이후만 덮는 구간은 빠지고, 걸쳐 있는 구간은 어제까지로 줄어 지난 면제가 남는다(PR 65 리뷰). */
fun List<StreakPause>.endedBefore(today: Long): List<StreakPause> = mapNotNull { p ->
    when {
        p.fromEpochDay >= today -> null
        p.untilEpochDay >= today -> p.copy(days = (today - p.fromEpochDay).toInt())
        else -> p
    }
}

fun List<StreakPause>.covers(epochDay: Long): Boolean = any { it.covers(epochDay) }

/**
 * 목표 요일 기준 연속 운동(P6 "목표 요일 기준") — 목표가 아닌 날(계획된 휴식)과 멈춘 날은 건너뛰고, 목표 요일에 안 했으면 끊긴다.
 * [isTarget] 이 모든 요일에 거짓이면(요일 설정 없음) 매일을 목표로 본다(예전 [AppViewModel.attendanceStreak] 과 같다).
 */
fun targetDayStreak(workoutDays: Set<Long>, today: Long, isTarget: (DayOfWeek) -> Boolean, pauses: List<StreakPause> = emptyList()): Int {
    val anyTarget = DayOfWeek.entries.any(isTarget)
    fun target(day: Long) = !anyTarget || isTarget(LocalDate.fromEpochDays(day).dayOfWeek)
    var streak = 0
    var cursor = today
    if (cursor !in workoutDays) cursor -= 1   // 오늘 아직 안 했으면 어제부터 센다(오늘은 아직 끊긴 것이 아니다)
    var guard = 0
    while (guard++ < 3660) {
        when {
            cursor in workoutDays -> streak += 1
            pauses.covers(cursor) || !target(cursor) -> {}
            else -> break
        }
        cursor -= 1
    }
    return streak
}

/** 가장 길었던 연속(같은 규칙) — 기록 전체를 하루씩 훑는다. */
fun longestTargetDayStreak(workoutDays: Set<Long>, today: Long, isTarget: (DayOfWeek) -> Boolean, pauses: List<StreakPause> = emptyList()): Int {
    val first = workoutDays.minOrNull() ?: return 0
    val anyTarget = DayOfWeek.entries.any(isTarget)
    fun target(day: Long) = !anyTarget || isTarget(LocalDate.fromEpochDays(day).dayOfWeek)
    var best = 0; var run = 0
    for (day in first..today) {
        when {
            day in workoutDays -> { run += 1; best = maxOf(best, run) }
            pauses.covers(day) || !target(day) -> {}
            else -> run = 0
        }
    }
    return best
}

// ============================================================= 체중 기록

@Immutable
data class BodyWeightEntry(
    val epochDay: Long,
    /** "morning" | "evening" */
    val slot: String,
    val kg: Double,
    val bodyFatPercent: Double? = null,
    val fasting: Boolean = false,
    val recordedAt: Long,
) {
    val slotLabel: String get() = if (slot == "evening") "저녁" else "아침"
}

internal fun encodeBodyWeights(list: List<BodyWeightEntry>): JSONArray = JSONArray().also { arr ->
    list.forEach { e ->
        arr.put(JSONObject().put("day", e.epochDay).put("slot", e.slot).put("kg", e.kg).put("fasting", e.fasting).put("at", e.recordedAt)
            .also { o -> e.bodyFatPercent?.let { o.put("fat", it) } })
    }
}
internal fun decodeBodyWeights(arr: JSONArray): List<BodyWeightEntry> = (0 until arr.length()).mapNotNull { i ->
    runCatching {
        val o = arr.getJSONObject(i)
        BodyWeightEntry(o.getLong("day"), o.optString("slot", "morning"), o.getDouble("kg"),
            if (o.has("fat")) o.optDouble("fat").takeIf { it.isFinite() } else null, o.optBoolean("fasting", false), o.optLong("at", 0L))
    }.getOrNull()
}

/** 최근 기록과 [daysAgo]일 전 기록의 차이(kg) — 그날 전후 가장 가까운 기록을 쓴다. 기록이 없으면 null. */
fun List<BodyWeightEntry>.changeSince(latest: BodyWeightEntry, daysAgo: Int): Double? {
    val target = latest.epochDay - daysAgo
    val reference = filter { it.epochDay <= target }.maxByOrNull { it.epochDay } ?: return null
    return latest.kg - reference.kg
}

// ============================================================= XP · 배지

/** XP 한 건 — [key] 로 같은 일을 두 번 주지 않는다("session:<id>", "manual:<epochDay>", "guide:<종목>"). */
@Immutable
data class XpEntry(val at: Long, val key: String, val reason: String, val amount: Int)

internal fun encodeXp(list: List<XpEntry>): JSONArray = JSONArray().also { arr ->
    list.forEach { arr.put(JSONObject().put("at", it.at).put("key", it.key).put("reason", it.reason).put("amount", it.amount)) }
}
internal fun decodeXp(arr: JSONArray): List<XpEntry> = (0 until arr.length()).mapNotNull { i ->
    runCatching { val o = arr.getJSONObject(i); XpEntry(o.getLong("at"), o.getString("key"), o.getString("reason"), o.getInt("amount")) }.getOrNull()
}

/** 보상 규칙 — 숫자는 디자인의 예시값을 그대로 제품 초기값으로 둔다. 자세 점수·카메라 횟수는 입력이 아니다. */
object Rewards {
    const val XP_PER_SET = 10
    const val XP_PR_BONUS = 30
    const val XP_MANUAL_FIRST_OF_DAY = 20
    const val XP_GUIDE = 10
    const val XP_PER_LEVEL = 300

    fun sessionXp(doneSets: Int, personalRecord: Boolean): Int = XP_PER_SET * doneSets.coerceAtLeast(0) + if (personalRecord) XP_PR_BONUS else 0
    fun level(totalXp: Int): Int = 1 + totalXp.coerceAtLeast(0) / XP_PER_LEVEL
    fun toNextLevel(totalXp: Int): Int = XP_PER_LEVEL - totalXp.coerceAtLeast(0) % XP_PER_LEVEL
}

/** 배지 하나의 진행 — [progress] / [goal], 달성은 progress >= goal. */
@Immutable
data class BadgeStatus(val id: String, val title: String, val short: String, val progress: Int, val goal: Int) {
    val earned: Boolean get() = progress >= goal
}

/** 배지 계산에 드는 사실들 — 전부 '했다·기록했다' 에서 나온다. 자세 점수·카메라 횟수·PR 은 입력이 아니다(§137). */
data class RewardFacts(
    val workoutDays: Int,
    val streak: Int,
    val longestStreak: Int,
    /** 목표 요일을 전부 채운 주(월~일)의 수 — Apple Fitness 'Perfect Week' 꼴. */
    val perfectWeeks: Int,
    /** 목표 요일을 전부 채운 달의 수 — 'Perfect Month' 꼴. */
    val perfectMonths: Int,
    val coachSets: Int,
    val guidesSeen: Int,
    val guidesTotal: Int,
    val squatReps: Int,
    val pushupReps: Int,
    val plankSeconds: Int,
    /** 누적 볼륨(kg × 확정 회) — 세트 기록이 있는 세션·수동 기록만. */
    val volumeKg: Double,
    /** 세션을 09:00 전에 마친 날 수 / 21:00 이후에 마친 날 수 — 직접 적은 기록은 시각이 없어 빠진다. */
    val morningDays: Int,
    val nightDays: Int,
    val dietDays: Int,
    val waterGoalDays: Int,
    val bodyWeightDays: Int,
    val routineShares: Int,
)

/**
 * 배지 표(§137, 2026-11-07 사용자 요청 "첫 PR 제거, 다른 운동 앱 배지 참조"). 사다리는 다른 앱의 틀을 빌렸다 —
 * 운동 일수 10/50/100(Garmin 'Super Strength' 10/25/100/200 · Hevy 100번째 운동 배지), 완벽한 주·달(Apple Fitness Perfect Week/Month — 목표 요일 기준),
 * 아침·저녁(Nike Run Club 'Early Bird'·'Night Owl'), 누적 볼륨(Hevy 볼륨 통계), 물·체중·식단(Fitbit·Garmin 의 습관 배지).
 * PR 배지는 뺐다 — 처음 적은 kg 도 PR 로 세는 정의라 '했다' 의 증거가 아니다. 순서 = 화면 순서(쉬운 것부터).
 */
fun badges(f: RewardFacts): List<BadgeStatus> = listOf(
    BadgeStatus("first_workout", "첫 운동", "첫", f.workoutDays.coerceAtMost(1), 1),
    BadgeStatus("days_10", "운동 10일", "10", f.workoutDays, 10),
    BadgeStatus("days_50", "운동 50일", "50", f.workoutDays, 50),
    BadgeStatus("days_100", "운동 100일", "100", f.workoutDays, 100),
    BadgeStatus("streak_7", "7일 연속", "7일", f.longestStreak, 7),
    BadgeStatus("streak_30", "30일 연속", "30일", f.longestStreak, 30),
    BadgeStatus("streak_100", "100일 연속", "100일", f.longestStreak, 100),
    BadgeStatus("perfect_week", "완벽한 주", "주", f.perfectWeeks.coerceAtMost(1), 1),
    BadgeStatus("perfect_month", "완벽한 달", "달", f.perfectMonths.coerceAtMost(1), 1),
    BadgeStatus("morning_5", "아침 운동 5일", "아침", f.morningDays, 5),
    BadgeStatus("night_5", "저녁 운동 5일", "저녁", f.nightDays, 5),
    BadgeStatus("squat_500", "스쿼트 500회", "스쿼트", f.squatReps, 500),
    BadgeStatus("pushup_500", "푸쉬업 500회", "푸쉬업", f.pushupReps, 500),
    BadgeStatus("plank_30m", "플랭크 30분", "플랭크", f.plankSeconds / 60, 30),
    BadgeStatus("volume_10t", "볼륨 10톤", "10 t", (f.volumeKg / 1000).toInt(), 10),
    BadgeStatus("coach_50", "교정 모드 50세트", "교정", f.coachSets, 50),
    BadgeStatus("guides_all", "가이드 ${f.guidesTotal}종목", "가이드", f.guidesSeen, f.guidesTotal.coerceAtLeast(1)),
    BadgeStatus("diet_7", "식단 7일", "식단", f.dietDays, 7),
    BadgeStatus("diet_30", "식단 30일", "30일", f.dietDays, 30),
    BadgeStatus("water_7", "물 목표 7일", "물", f.waterGoalDays, 7),
    BadgeStatus("weigh_7", "체중 기록 7일", "체중", f.bodyWeightDays, 7),
    BadgeStatus("routine_share", "루틴 공유", "루틴", f.routineShares.coerceAtMost(1), 1),
)

/**
 * 목표 요일을 전부 채운 주(월~일)의 수 — [today] 까지 끝난 주만(마지막 목표 요일이 지났거나 오늘이고 그날도 했을 때). 목표 요일이 없는 주는 세지 않고,
 * 연속 멈춤([pauses])이 덮는 목표 요일은 한 것으로 친다(연속 규칙과 같다). 첫 운동 전의 주는 보지 않는다.
 */
fun perfectWeeks(workoutDays: Set<Long>, today: Long, isTarget: (DayOfWeek) -> Boolean, pauses: List<StreakPause> = emptyList()): Int {
    val first = workoutDays.minOrNull() ?: return 0
    val target = everyDayIfNoTarget(isTarget)
    val firstDate = LocalDate.fromEpochDays(first)
    var weekStart = first - (firstDate.dayOfWeek.isoDayNumber - 1)
    var count = 0
    while (weekStart <= today) {
        val targets = (weekStart..weekStart + 6).filter { target(LocalDate.fromEpochDays(it).dayOfWeek) }
        if (targets.isNotEmpty() && targets.last() <= today && targets.all { it in workoutDays || pauses.any { p -> p.covers(it) } }) count += 1
        weekStart += 7
    }
    return count
}

/** 목표 요일을 전부 채운 달의 수 — [perfectWeeks] 와 같은 규칙을 달력 달에 적용한다. */
fun perfectMonths(workoutDays: Set<Long>, today: Long, isTarget: (DayOfWeek) -> Boolean, pauses: List<StreakPause> = emptyList()): Int {
    val first = workoutDays.minOrNull() ?: return 0
    val target = everyDayIfNoTarget(isTarget)
    var month = LocalDate.fromEpochDays(first).let { LocalDate(it.year, it.month, 1) }
    val todayDate = LocalDate.fromEpochDays(today)
    var count = 0
    while (month <= todayDate) {
        val next = if (month.month.number == 12) LocalDate(month.year + 1, 1, 1) else LocalDate(month.year, month.month.number + 1, 1)
        val targets = (month.toEpochDays() until next.toEpochDays()).filter { target(LocalDate.fromEpochDays(it).dayOfWeek) }
        if (targets.isNotEmpty() && targets.last() <= today && targets.all { it in workoutDays || pauses.any { p -> p.covers(it) } }) count += 1
        month = next
    }
    return count
}

/** 목표 요일이 하나도 없으면 매일이 목표 — 연속 규칙(`targetDayStreak`)과 같은 해석. */
private fun everyDayIfNoTarget(isTarget: (DayOfWeek) -> Boolean): (DayOfWeek) -> Boolean =
    if (DayOfWeek.entries.none(isTarget)) { _ -> true } else isTarget

// ============================================================= 직접 추가한 종목(P9)

/** 내 기록에만 쓰이는 종목 — 자세 비교 없음. [muscles] 는 고른 주 근육(`Muscle.name`) — 근육 부하는 고른 근육으로만 대략 추정한다. */
@Immutable
data class CustomExercise(
    val id: String,
    val name: String,
    val category: String,
    /** "바벨" | "덤벨" | "머신" | "케이블" | "맨몸" */
    val equipment: String,
    val muscles: List<String>,
    /** true 면 시간(초) 단위. */
    val timed: Boolean,
    val createdAt: Long,
) {
    internal fun template(): WorkoutTemplate = WorkoutTemplate(name, if (timed) "30초 × 3세트" else "12회 × 3세트", "8분", category, false)
}

internal fun encodeCustomExercises(list: List<CustomExercise>): JSONArray = JSONArray().also { arr ->
    list.forEach { e ->
        arr.put(JSONObject().put("id", e.id).put("name", e.name).put("category", e.category).put("equipment", e.equipment)
            .put("muscles", JSONArray(e.muscles)).put("timed", e.timed).put("createdAt", e.createdAt))
    }
}
internal fun decodeCustomExercises(arr: JSONArray): List<CustomExercise> = (0 until arr.length()).mapNotNull { i ->
    runCatching {
        val o = arr.getJSONObject(i)
        val m = o.optJSONArray("muscles")
        CustomExercise(o.getString("id"), o.getString("name"), o.getString("category"), o.optString("equipment", "맨몸"),
            if (m == null) emptyList() else (0 until m.length()).map { m.getString(it) }, o.optBoolean("timed", false), o.optLong("createdAt", 0L))
    }.getOrNull()
}

/** 카탈로그 부위 칩(P8) — 카탈로그 분류(하체·상체·코어·복근)와 세부 부위(가슴·등·어깨·팔)를 같이 고른다. */
val catalogRegions: List<String> = listOf("하체", "가슴", "등", "어깨", "팔", "코어", "복근", "유산소")

/** 한 종목의 세부 부위 — 상체는 주요 근육으로 가슴·등·어깨·팔을 가른다(`workoutRegion` 과 같은 이름 집합). */
fun catalogRegionOf(name: String, category: String): String = when (workoutRegion(name, category)) {
    "가슴·팔" -> "가슴"
    "팔" -> "팔"
    "등" -> "등"
    "어깨" -> "어깨"
    else -> when (category) { "코어" -> "코어"; "복근" -> "복근"; "유산소" -> "유산소"; else -> "하체" }
}

/** 카탈로그 기구 칩(P8) — 부하 프로필의 `Equipment` 를 여섯 묶음으로. 밴드는 맨몸과 다르다(장력, 근거 감사 §6). */
val catalogEquipments: List<String> = listOf("바벨", "덤벨", "머신", "케이블", "밴드", "맨몸")

fun catalogEquipmentOf(name: String): String {
    val e = com.example.trex_kotlin.trainingload.MuscleLoadCatalog.find(name)?.equipment ?: return "맨몸"
    return when (e) {
        com.example.trex_kotlin.trainingload.Equipment.BAND -> "밴드"
        com.example.trex_kotlin.trainingload.Equipment.BARBELL -> "바벨"
        com.example.trex_kotlin.trainingload.Equipment.DUMBBELL, com.example.trex_kotlin.trainingload.Equipment.BENCH -> "덤벨"
        com.example.trex_kotlin.trainingload.Equipment.CABLE -> "케이블"
        com.example.trex_kotlin.trainingload.Equipment.NONE, com.example.trex_kotlin.trainingload.Equipment.PULLUP_BAR,
        com.example.trex_kotlin.trainingload.Equipment.DIP_BAR, com.example.trex_kotlin.trainingload.Equipment.RACK,
        com.example.trex_kotlin.trainingload.Equipment.STABILITY_BALL, com.example.trex_kotlin.trainingload.Equipment.BAND -> "맨몸"
        else -> "머신"
    }
}

// ============================================================= 주간 루틴(P11)

/** 요일(1=월 … 7=일) → 루틴 참조. 참조는 "preset:<id>" 또는 "saved:<id>". 없는 요일은 계획된 휴식이다. */
@Immutable
data class WeeklyRoutine(val slots: Map<Int, String> = emptyMap()) {
    fun ref(day: DayOfWeek): String? = slots[day.isoDayNumber]
    fun isTarget(day: DayOfWeek): Boolean = slots.containsKey(day.isoDayNumber)
    fun assigned(day: DayOfWeek, ref: String?): WeeklyRoutine =
        WeeklyRoutine(if (ref == null) slots - day.isoDayNumber else slots + (day.isoDayNumber to ref))
}

internal fun encodeWeeklyRoutine(w: WeeklyRoutine): JSONObject = JSONObject().also { o -> w.slots.forEach { (d, r) -> o.put(d.toString(), r) } }
internal fun decodeWeeklyRoutine(o: JSONObject): WeeklyRoutine =
    WeeklyRoutine(buildMap { o.keys().forEach { k -> k.toIntOrNull()?.let { d -> if (d in 1..7) put(d, o.getString(k)) } } })

val DayOfWeek.isoDayNumber: Int get() = ordinal + 1

// ============================================================= 알림 설정(P16) · 보관 기간(P14)

/** 기기 안에서만 예약하는 알림 — 서버 푸시가 아니다. [hour]:[minute] 는 운동 요일 알림 시각. */
@Immutable
data class NotifyPrefs(
    val workoutDays: Boolean = false,
    val hour: Int = 19,
    val minute: Int = 30,
    val restEnd: Boolean = true,
    val prAndStreak: Boolean = false,
) {
    val timeLabel: String get() = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

internal fun encodeNotifyPrefs(p: NotifyPrefs): JSONObject =
    JSONObject().put("workoutDays", p.workoutDays).put("hour", p.hour).put("minute", p.minute).put("restEnd", p.restEnd).put("prAndStreak", p.prAndStreak)
internal fun decodeNotifyPrefs(o: JSONObject): NotifyPrefs = NotifyPrefs(
    o.optBoolean("workoutDays", false), o.optInt("hour", 19).coerceIn(0, 23), o.optInt("minute", 30).coerceIn(0, 59),
    o.optBoolean("restEnd", true), o.optBoolean("prAndStreak", false),
)

/** 기록 보관 기간(일). 0 = 제한 없음. 근육 피로도는 어느 쪽이든 최근 7일만 계산한다(`MuscleLoadEngine.RETENTION_DAYS`). */
object RetentionOptions {
    const val UNLIMITED = 0
    val choices: List<Int> = listOf(7, 90, 365, UNLIMITED)
    fun label(days: Int): String = when (days) { UNLIMITED -> "제한 없음"; 365 -> "1년"; else -> "${days}일" }
}

// ============================================================= 내보내기(P15)

/** CSV 열 — 디자인 P15 그대로: 날짜 · 종목 · 세트 · kg · 회 · 카메라 횟수 · 초 · RPE · 출처 · 메모. 자세 원본(관절 좌표)·음식 사진은 넣지 않는다. */
fun buildRecordCsv(history: List<WorkoutHistoryDay>, bodyWeights: List<BodyWeightEntry>, diet: Map<Long, Map<String, List<FoodEntry>>>?): String {
    fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    fun date(epochDay: Long): String { val d = LocalDate.fromEpochDays(epochDay); return "${d.year}-${d.month.number.toString().padStart(2, '0')}-${d.day.toString().padStart(2, '0')}" }
    val sb = StringBuilder("날짜,종목,세트,kg,회,카메라 횟수,초,RPE,출처,메모\n")
    history.sortedBy { it.epochDay }.forEach { day ->
        day.items.forEach { item ->
            val source = if (item.source == "manual") "수동" else "세션"
            if (item.sets.isEmpty()) {
                sb.append(listOf(date(day.epochDay), item.workoutName, "", "", "", item.postureCorrection?.repsValid, item.durationSeconds, "", source, item.reps).joinToString(",", transform = ::cell)).append('\n')
            } else item.sets.forEach { s ->
                sb.append(listOf(date(day.epochDay), item.workoutName, s.index, s.kg?.let(::trimKg), s.reps, s.cameraReps, s.seconds, s.rpe, source, s.memo).joinToString(",", transform = ::cell)).append('\n')
            }
        }
    }
    if (bodyWeights.isNotEmpty()) {
        sb.append("\n날짜,시간대,체중 kg,체지방 %,공복\n")
        bodyWeights.sortedBy { it.epochDay }.forEach { e -> sb.append(listOf(date(e.epochDay), e.slotLabel, trimKg(e.kg), e.bodyFatPercent, if (e.fasting) "예" else "").joinToString(",", transform = ::cell)).append('\n') }
    }
    if (diet != null) {
        sb.append("\n날짜,끼니,음식,인분,kcal\n")
        diet.entries.sortedBy { it.key }.forEach { (day, slots) ->
            slots.forEach { (slot, foods) -> foods.forEach { f -> sb.append(listOf(date(day), slot, f.name, f.qty, (f.nutrition.kcal * f.qty).roundToInt()).joinToString(",", transform = ::cell)).append('\n') } }
        }
    }
    return sb.toString()
}

fun buildRecordJson(history: List<WorkoutHistoryDay>, bodyWeights: List<BodyWeightEntry>, diet: Map<Long, Map<String, List<FoodEntry>>>?): String {
    val root = JSONObject().put("format", "trex-records-1")
    root.put("workouts", JSONArray().also { arr ->
        history.sortedBy { it.epochDay }.forEach { day ->
            arr.put(JSONObject().put("epochDay", day.epochDay).put("items", JSONArray().also { items ->
                day.items.forEach { item ->
                    items.put(JSONObject().put("name", item.workoutName).put("reps", item.reps).put("seconds", item.durationSeconds ?: item.durationMinutes * 60)
                        .put("source", item.source ?: "session").put("sets", encodeSetEntries(item.sets)))
                }
            }))
        }
    })
    root.put("bodyWeights", encodeBodyWeights(bodyWeights))
    if (diet != null) root.put("diet", JSONObject().also { o ->
        diet.entries.sortedBy { it.key }.forEach { (day, slots) ->
            o.put(day.toString(), JSONObject().also { s -> slots.forEach { (slot, foods) -> s.put(slot, JSONArray().also { a -> foods.forEach { f -> a.put(JSONObject().put("name", f.name).put("qty", f.qty).put("kcal", f.nutrition.kcal)) } }) } })
        }
    })
    // 들여쓰기 없는 toString — iPhone 의 공통 org.json(OrgJson.kt)에는 toString(indent) 가 없다(PR 64 shared CI 가 잡음, §138)
    return root.toString()
}

// ============================================================= 통계(P1)

/** 기간의 집계 — 운동일·세트·볼륨·시간·소모(추정)·부위 비중(세트당 운동 시간 합산의 제품용 분류). */
data class RecordStats(
    val days: Int, val sets: Int, val volume: VolumeSummary, val seconds: Int, val kcal: Int,
    val partShare: Map<String, Int>, val topExercises: List<Triple<String, Int, String>>,
)

fun List<WorkoutHistoryDay>.stats(from: Long, to: Long): RecordStats {
    val inRange = filter { it.epochDay in from..to && it.items.isNotEmpty() }
    val items = inRange.flatMap { it.items }
    val seconds = items.sumOf { it.durationSeconds ?: it.durationMinutes * 60 }
    val byPart = items.groupBy { when (it.category) { "하체" -> "하체"; "코어", "복근" -> "코어"; "유산소" -> "유산소"; else -> "상체" } }
        .mapValues { (_, list) -> list.sumOf { it.durationSeconds ?: it.durationMinutes * 60 } }
    val total = byPart.values.sum().coerceAtLeast(1)
    val share = byPart.mapValues { (_, s) -> (s * 100.0 / total).roundToInt() }.filterValues { it > 0 }
    val setsOf = items.groupBy { it.workoutName }.mapValues { (_, list) -> list.sumOf { if (it.sets.isEmpty()) it.repsSpecSets() else it.sets.count { s -> s.done } } }
    val top = setsOf.entries.sortedByDescending { it.value }.take(3).map { (name, n) ->
        val best = inRange.bestKg(name)?.set?.kg?.let { "최고 ${trimKg(it)} kg" }
            ?: items.filter { it.workoutName == name }.flatMap { it.sets }.mapNotNull { it.seconds }.maxOrNull()?.let { "최장 ${it}초" } ?: ""
        Triple(name, n, best)
    }
    return RecordStats(inRange.size, setsOf.values.sum(), volume(from, to), seconds, items.sumOf { it.calories }, share, top)
}

private fun WorkoutHistoryItem.repsSpecSets(): Int = parseReps(reps).sets

/**
 * §134b — 옛 kcal 식(분류별 분당 상수 × 가동 분, `legacyCategoryCalories`)으로 적힌 항목만 MET 식으로 고쳐 적는다. 값이 옛 식과 **정확히 같은** 항목만 바꾸므로
 * 새 식으로 적힌 값은 건드리지 않고 다시 불러도 같은 결과다. 옛 세션은 휴식 시간이 남아 있지 않아 세트 사이 설정 휴식을 더한다(`offlineActivitySeconds`).
 * 두는 것: 분류를 모르는 항목(옛 식을 되짚을 수 없다), 가동 시간이 없거나 0초인 세션 항목(시간을 지어내지 않는다). [bodyKgOn] 은 그 날짜의 체중.
 */
fun List<WorkoutHistoryDay>.migrateLegacyCalories(bodyKgOn: (Long) -> Double): List<WorkoutHistoryDay> = map { day ->
    val items = day.items.map { item ->
        val category = item.category ?: return@map item
        val work = item.durationSeconds ?: return@map item
        val manual = item.source == "manual"
        if (!manual && work <= 0) return@map item
        val legacy = legacyCategoryCalories(category, if (manual) maxOf(work, item.sets.size * 30) else work)
        if (item.calories != legacy) return@map item
        val sets = if (manual) item.sets.size.coerceAtLeast(1) else item.repsSpecSets()
        item.copy(calories = estimatedKcal(ExerciseMet.of(item.workoutName, category).met, bodyKgOn(day.epochDay), offlineActivitySeconds(item.workoutName, category, sets, work)))
    }
    if (items == day.items) day else day.copy(items = items)
}

/** 세션을 마치고 기록한 직후의 보상 요약(P5 보상 띠) — [xp] 0 이면 같은 세션을 다시 저장한 것(중복 지급 없음). */
@Immutable
data class SessionReward(
    val xp: Int,
    val personalRecord: Pair<String, SetEntry>?,
    val previousBestKg: Double?,
    val newBadges: List<BadgeStatus>,
    val streak: Int,
    val level: Int,
    val toNextLevel: Int,
    /** 이번 세션의 볼륨(kg × 확정 회) — kg 을 적은 세트만. */
    val volumeKg: Double = 0.0,
) {
    /** 지급 뒤 이 레벨 안에서 찬 XP(0 ≤ x < 300) — 바 애니메이션의 끝. */
    val xpInLevelAfter: Int get() = Rewards.XP_PER_LEVEL - toNextLevel
    /** 지급 전 이 레벨 안의 XP — 음수면 이번 지급으로 레벨이 올랐다(바가 한 번 꽉 차고 0 부터 다시). */
    val xpInLevelBefore: Int get() = xpInLevelAfter - xp
    val leveledUp: Boolean get() = xp > 0 && xpInLevelBefore < 0
}

/** 주간 루틴·요일 설정에서 그 날이 계획된 휴식인가 — 목표 요일이 하나도 없으면 휴식 표시를 하지 않는다. */
fun plannedRest(day: LocalDate, isTarget: (DayOfWeek) -> Boolean): Boolean = DayOfWeek.entries.any(isTarget) && !isTarget(day.dayOfWeek)
