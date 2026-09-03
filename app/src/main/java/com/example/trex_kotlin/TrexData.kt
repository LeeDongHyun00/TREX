package com.example.trex_kotlin

import androidx.compose.runtime.Immutable
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.SetVerdict
import java.time.LocalDate
import java.util.Calendar

enum class TrexTab(val label: String) {
    Home("홈"),
    Workout("운동"),
    Diet("식단"),
    Profile("내 정보"),
}

enum class LoginMode {
    Login,
    Signup,
    Find,
}

enum class WorkoutNavigationTab(val label: String) {
    Schedule("운동 스케쥴"),
    History("운동 기록"),
}

/** 온보딩에서 수집한 사용자 프로필 — 권장 영양/강도 계산의 입력. */
@Immutable
data class UserProfile(
    val goal: String = "general",
    val dayMask: Int = 0,
    val place: String? = null,
    val bodyweightOnly: Boolean = false,
    val equipmentMask: Int = 0,
    val gender: String = "none",
    val heightCm: Double = 170.0,
    val weightKg: Double = 65.0,
    val age: Int = 30,
    val activityFactor: Double = 1.35,
)

@Immutable
data class Workout(
    val id: String,
    val name: String,
    val reps: String,
    val duration: String,
    val posture: Boolean,
    val category: String,
    val alt: WorkoutAlt? = null,
    /** 오늘 세션에서 완료했는지 (리디자인: 홈/운동 탭 진행률과 카드 번호 칩 상태). */
    val done: Boolean = false,
)

@Immutable
data class WorkoutAlt(
    val name: String,
    val reps: String,
)

/**
 * 기록 항목에 접혀 들어가는 세트 리포트 요약 (spec §30). [focus] 는 관찰 문장(끝 마침표 없음) —
 * CLEAN 이면 "자세 깨끗", UNJUDGED 면 "자세 판정 없음", TRACK 이면 summaryLine. 나머지는 리포트에서 그대로 옮긴 값이고
 * [actualReps]/[formLabel] 만 완료 화면의 자가 라벨로 나중에 채워진다. 새 필드는 전부 기본값 null 이라 구버전 저장 기록도 그대로 읽힌다.
 */
@Immutable
data class PostureCorrection(
    val focus: String,
    /** "habit"|"drift"|"recovered"|"violation"|"clean"|"reference"|"unjudged". null = 구버전·목업 기록. */
    val kind: String? = null,
    val bodyPart: String? = null,
    val fix: String? = null,
    val note: String? = null,
    val beta: Boolean = false,
    val setId: String? = null,
    /** "coach"|"track" */
    val mode: String? = null,
    val judged: Int? = null,
    val abstained: Int? = null,
    val repsValid: Int? = null,
    val repsPartial: Int? = null,
    val tempoMs: Long? = null,
    val actualReps: Int? = null,
    val formLabel: String? = null,
)

/**
 * 세트 리포트 → 기록 항목. posture 패키지는 app 에 의존할 수 없어서 확장함수는 이쪽에 둔다.
 * kind 는 verdict 소문자를 쓰되 ISSUE 만 헤드라인의 onset 종류(habit/drift/violation)로 세분한다 — 기록 화면이 라벨 색을 고르는 키.
 */
fun PostureSetReport.toCorrection(): PostureCorrection {
    // REFERENCE 는 non-beta 헤드라인이 없으므로 첫 후보(베타)를 대표로 — beta 플래그가 같이 실려 UI 가 "참고" 로 낮춘다
    val lead = headline ?: candidates.firstOrNull()
    val focus = when {
        mode == CoachMode.TRACK -> summaryLine
        verdict == SetVerdict.CLEAN -> if (betaOnly) "검증 중인 항목 기준으로는 이상 없었어요" else "자세 깨끗했어요"
        verdict == SetVerdict.UNJUDGED -> "자세 판정 없음"
        // "좋아요, 무릎 자세가 교정됐어요" 는 코칭 발화 문장이라 "{운동}에서 {관찰}" 틀에 안 맞는다 — 기록용 관찰문으로
        verdict == SetVerdict.RECOVERED -> "${lead?.bodyPart ?: "자세"} 자세가 세트 후반에 교정됐어요"
        else -> lead?.observation ?: summaryLine
    }
    val kind = when (verdict) {
        SetVerdict.ISSUE -> when (headline?.kind) {
            OnsetKind.HABIT -> "habit"
            OnsetKind.DRIFT -> "drift"
            else -> "violation"
        }
        else -> verdict.name.lowercase()
    }
    return PostureCorrection(
        focus = focus,
        kind = kind,
        bodyPart = lead?.bodyPart,
        fix = lead?.fix?.takeIf { it.isNotBlank() && verdict != SetVerdict.RECOVERED },   // 교정된 세트에 "다음엔 …" 은 어긋난다
        note = lead?.note,
        beta = lead?.beta ?: false,
        setId = setId,
        mode = if (mode == CoachMode.TRACK) "track" else "coach",
        judged = judged,
        abstained = abstained,
        repsValid = repsValid,
        repsPartial = repsPartial,
        tempoMs = tempoMs,
    )
}

@Immutable
data class WorkoutHistoryItem(
    val workoutName: String,
    val reps: String,
    val durationMinutes: Int,
    val calories: Int,
    val postureCorrection: PostureCorrection? = null,
    /** 자세 정확도(%) — 자세 엔진이 산출. 없으면 null 로 두고 UI 에서 숨긴다. */
    val accuracy: Int? = null,
)

@Immutable
data class WorkoutHistoryDay(
    /** 그 날의 epoch day — 날짜가 바뀌어도 기록이 밀리지 않게 하는 정본 키. */
    val epochDay: Long,
    val dayLabel: String,
    val dateLabel: String,
    val items: List<WorkoutHistoryItem>,
    val averageMinutes: Int,
    val averageCalories: Int,
)

@Immutable
data class Nutrition(
    val kcal: Int,
    val carb: Double,
    val protein: Double,
    val fat: Double,
)

@Immutable
data class FoodEntry(
    val name: String,
    val nutrition: Nutrition,
    /** 수량 — 리디자인의 직접 기록 시트 스테퍼. 합산 시 곱해진다. */
    val qty: Int = 1,
)

@Immutable
data class MealMeta(
    val id: String,
    val label: String,
)

@Immutable
data class GoalItem(
    val id: String,
    val label: String,
    val description: String,
)

val todayPlan = listOf(
    Workout(
        id = "squat",
        name = "기본 스쿼트",
        reps = "12회 x 3세트",
        duration = "8분",
        posture = true,
        category = "하체",
        alt = WorkoutAlt("의자 스쿼트", "10회 x 3세트"),
    ),
    Workout(
        id = "plank",
        name = "플랭크",
        reps = "60초 x 3세트",
        duration = "5분",
        posture = false,
        category = "코어",
        alt = WorkoutAlt("데드버그", "12회 x 3세트"),
    ),
    Workout(
        id = "lunge",
        name = "런지",
        reps = "10회 x 3세트",
        duration = "10분",
        posture = true,
        category = "하체",
        alt = WorkoutAlt("제자리 스텝업", "12회 x 3세트"),
    ),
    Workout(
        id = "pushup",
        name = "푸쉬업 입문",
        reps = "8회 x 3세트",
        duration = "6분",
        posture = false,
        category = "상체",
        alt = WorkoutAlt("벽 푸쉬업", "12회 x 3세트"),
    ),
    Workout(
        id = "stretch",
        name = "마무리 스트레칭",
        reps = "전신 6분",
        duration = "6분",
        posture = false,
        category = "회복",
        alt = WorkoutAlt("폼롤러 마무리", "전신 5분"),
    ),
)

val onboardingGoals = listOf(
    GoalItem("lower", "건강한 하체 만들어룡!", "스쿼트 · 런지 중심"),
    GoalItem("diet", "다이어트를 목표로 해룡!", "유산소 + 식단 관리"),
    GoalItem("simple", "간단하게 운동만 하고 싶어룡!", "하루 10분 루틴"),
    GoalItem("core", "탄탄한 코어 잡고싶어룡!", "플랭크 · 복근 루틴"),
    GoalItem("posture", "자세부터 바로잡고 싶어룡!", "거북목 · 골반 교정"),
)

val mealMetas = listOf(
    MealMeta("breakfast", "아침"),
    MealMeta("lunch", "점심"),
    MealMeta("snack", "간식"),
    MealMeta("dinner", "저녁"),
)

val foodDatabase = linkedMapOf(
    "닭가슴살" to Nutrition(165, 0.0, 31.0, 3.6),
    "현미밥" to Nutrition(220, 46.0, 5.0, 1.7),
    "바나나" to Nutrition(89, 23.0, 1.1, 0.3),
    "오트밀" to Nutrition(150, 27.0, 5.0, 3.0),
    "그릭요거트" to Nutrition(100, 4.0, 17.0, 0.0),
    "고구마" to Nutrition(130, 30.0, 2.0, 0.1),
    "샐러드" to Nutrition(120, 8.0, 4.0, 7.0),
    "사과" to Nutrition(95, 25.0, 0.5, 0.3),
    "아몬드" to Nutrition(160, 6.0, 6.0, 14.0),
)

fun seedFoods(): Map<String, List<FoodEntry>> = mapOf(
    "breakfast" to listOf(
        FoodEntry("오트밀", Nutrition(150, 27.0, 5.0, 3.0)),
        FoodEntry("바나나", Nutrition(89, 23.0, 1.1, 0.3)),
        FoodEntry("아몬드", Nutrition(160, 6.0, 6.0, 14.0)),
    ),
    "lunch" to listOf(
        FoodEntry("닭가슴살", Nutrition(165, 0.0, 31.0, 3.6)),
        FoodEntry("현미밥", Nutrition(220, 46.0, 5.0, 1.7)),
        FoodEntry("샐러드", Nutrition(120, 8.0, 4.0, 7.0)),
    ),
    "snack" to listOf(
        FoodEntry("사과", Nutrition(95, 25.0, 0.5, 0.3)),
        FoodEntry("그릭요거트", Nutrition(100, 4.0, 17.0, 0.0)),
    ),
    "dinner" to emptyList(),
)

fun emptyDietSlots(): Map<String, List<FoodEntry>> =
    mealMetas.associate { it.id to emptyList() }

fun Nutrition.plus(other: Nutrition): Nutrition = Nutrition(
    kcal = kcal + other.kcal,
    carb = carb + other.carb,
    protein = protein + other.protein,
    fat = fat + other.fat,
)

fun Iterable<FoodEntry>.totalNutrition(): Nutrition =
    fold(Nutrition(0, 0.0, 0.0, 0.0)) { acc, entry ->
        Nutrition(
            kcal = acc.kcal + entry.nutrition.kcal * entry.qty,
            carb = acc.carb + entry.nutrition.carb * entry.qty,
            protein = acc.protein + entry.nutrition.protein * entry.qty,
            fat = acc.fat + entry.nutrition.fat * entry.qty,
        )
    }

/**
 * 첫 실행 데모용 시드 기록 (백엔드 연동 전). 저장소가 비어 있을 때만 쓰인다.
 * 시드에는 자세 데이터를 넣지 않는다: 지어낸 지적은 실데이터의 신뢰를 깎는다.
 */
fun seedWorkoutHistory(plan: List<Workout> = todayPlan): List<WorkoutHistoryDay> {
    val calendar = Calendar.getInstance()
    val todayEpoch = LocalDate.now().toEpochDay()

    return (0..6).map { index ->
        val dayCalendar = calendar.clone() as Calendar
        dayCalendar.add(Calendar.DAY_OF_MONTH, index - 6)
        val selected = plan.rotate(index).take(2 + index % 3)
        val items = selected.map { workout ->
            WorkoutHistoryItem(
                workoutName = workout.name,
                reps = workout.reps,
                durationMinutes = workout.durationMinutes(),
                calories = workout.estimatedCalories(),
                postureCorrection = null,
                accuracy = null,
            )
        }

        WorkoutHistoryDay(
            epochDay = todayEpoch - (6 - index),
            dayLabel = dayCalendar.koreanDayOfWeek(),
            dateLabel = "${dayCalendar.get(Calendar.MONTH) + 1}/${dayCalendar.get(Calendar.DAY_OF_MONTH)}",
            items = items,
            averageMinutes = (items.sumOf { it.durationMinutes } - 4 - index % 2).coerceAtLeast(8),
            averageCalories = (items.sumOf { it.calories } - 28 - index * 2).coerceAtLeast(80),
        )
    }
}

/** @param reports workoutId → 이번 세션의 세트 리포트. 자세를 켠 운동이라도 리포트가 없으면 항목의 자세 칸은 null 이다(판정 안 한 것을 지어내지 않는다). */
fun createWorkoutHistoryDay(
    plan: List<Workout>,
    elapsedSeconds: Int,
    reports: Map<String, PostureSetReport> = emptyMap(),
): WorkoutHistoryDay {
    val calendar = Calendar.getInstance()
    val items = plan.map { workout ->
        val report = reports[workout.id]
        WorkoutHistoryItem(
            workoutName = workout.name,
            reps = workout.reps,
            durationMinutes = workout.durationMinutes(),
            calories = workout.estimatedCalories(),
            postureCorrection = report?.toCorrection(),
            accuracy = report?.accuracy,
        )
    }
    val totalMinutes = (elapsedSeconds / 60).takeIf { it > 0 } ?: items.sumOf { it.durationMinutes }
    val totalCalories = items.sumOf { it.calories }

    return WorkoutHistoryDay(
        epochDay = LocalDate.now().toEpochDay(),
        dayLabel = calendar.koreanDayOfWeek(),
        dateLabel = "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}",
        items = items,
        averageMinutes = (totalMinutes - 5).coerceAtLeast(8),
        averageCalories = (totalCalories - 35).coerceAtLeast(80),
    )
}

fun List<WorkoutHistoryDay>.replaceTodayWith(record: WorkoutHistoryDay): List<WorkoutHistoryDay> {
    val existingIndex = indexOfLast { it.epochDay == record.epochDay }
    val updated = if (existingIndex >= 0) {
        toMutableList().also { it[existingIndex] = record }
    } else {
        this + record
    }
    return updated.sortedBy { it.epochDay }.takeLast(7)
}

fun WorkoutHistoryDay.totalMinutes(): Int =
    items.sumOf { it.durationMinutes }

fun WorkoutHistoryDay.totalCalories(): Int =
    items.sumOf { it.calories }

/** 홈 요약에 올릴 리포트 종류 — 실제 판정에서 나온 관찰 문장만. clean/unjudged/reference/recovered 와 kind 없는 구버전·목업 기록은 제외. */
private val homeSummaryKinds = setOf("habit", "drift", "violation")

fun WorkoutHistoryDay.summaryText(): String {
    // TRACK 은 focus 가 문장이 아니라 "n렙 · 템포" 요약이고, 모집단 판정을 지적으로 보이지 않는 모드라(§29) 넘어간다
    val corrected = items.firstOrNull { it.postureCorrection?.let { pc -> pc.mode != "track" && pc.kind in homeSummaryKinds } == true }
    if (corrected != null) {
        return "${corrected.workoutName}에서 ${corrected.postureCorrection?.focus.orEmpty()}."
    }

    val minuteDiff = totalMinutes() - averageMinutes
    val calorieDiff = totalCalories() - averageCalories
    val minuteText = if (minuteDiff >= 0) {
        "평소보다 ${minuteDiff}분 더 운동하고"
    } else {
        "평소보다 ${-minuteDiff}분 적게 운동하고"
    }
    val calorieText = if (calorieDiff >= 0) {
        "${calorieDiff}kcal 더 소모했어요."
    } else {
        "${-calorieDiff}kcal 덜 소모했어요."
    }
    return "$minuteText $calorieText"
}

private fun List<Workout>.rotate(offset: Int): List<Workout> {
    if (isEmpty()) return emptyList()
    val start = offset % size
    return drop(start) + take(start)
}

fun Calendar.koreanDayOfWeek(): String = when (get(Calendar.DAY_OF_WEEK)) {
    Calendar.MONDAY -> "월"
    Calendar.TUESDAY -> "화"
    Calendar.WEDNESDAY -> "수"
    Calendar.THURSDAY -> "목"
    Calendar.FRIDAY -> "금"
    Calendar.SATURDAY -> "토"
    else -> "일"
}
