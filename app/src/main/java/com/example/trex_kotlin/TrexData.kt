package com.example.trex_kotlin

import androidx.compose.runtime.Immutable
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
)

@Immutable
data class WorkoutAlt(
    val name: String,
    val reps: String,
)

@Immutable
data class PostureCorrection(
    val focus: String,
)

@Immutable
data class WorkoutHistoryItem(
    val workoutName: String,
    val reps: String,
    val durationMinutes: Int,
    val calories: Int,
    val postureCorrection: PostureCorrection? = null,
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
    fold(Nutrition(0, 0.0, 0.0, 0.0)) { acc, entry -> acc.plus(entry.nutrition) }

/** 첫 실행 데모용 시드 기록 (백엔드 연동 전). 저장소가 비어 있을 때만 쓰인다. */
fun seedWorkoutHistory(plan: List<Workout> = todayPlan): List<WorkoutHistoryDay> {
    val postureHints = listOf(
        "무릎이 안쪽으로 모이는 자세",
        "상체가 앞으로 무너지는 자세",
        "골반이 한쪽으로 기우는 자세",
        "어깨가 올라가는 자세",
    )
    val calendar = Calendar.getInstance()
    val todayEpoch = LocalDate.now().toEpochDay()

    return (0..6).map { index ->
        val dayCalendar = calendar.clone() as Calendar
        dayCalendar.add(Calendar.DAY_OF_MONTH, index - 6)
        val selected = plan.rotate(index).take(2 + index % 3)
        val items = selected.mapIndexed { itemIndex, workout ->
            val duration = workout.durationMinutes()
            val hasCorrection = workout.posture && (index + itemIndex) % 2 == 0
            WorkoutHistoryItem(
                workoutName = workout.name,
                reps = workout.reps,
                durationMinutes = duration,
                calories = workout.estimatedCalories(),
                postureCorrection = if (hasCorrection) {
                    PostureCorrection(postureHints[(index + itemIndex) % postureHints.size])
                } else {
                    null
                },
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

fun createWorkoutHistoryDay(plan: List<Workout>, elapsedSeconds: Int): WorkoutHistoryDay {
    val calendar = Calendar.getInstance()
    val items = plan.mapIndexed { index, workout ->
        val duration = workout.durationMinutes()
        WorkoutHistoryItem(
            workoutName = workout.name,
            reps = workout.reps,
            durationMinutes = duration,
            calories = workout.estimatedCalories(),
            postureCorrection = if (workout.posture && index == plan.indexOfFirst { it.posture }) {
                PostureCorrection(defaultPostureFocus(workout.category))
            } else {
                null
            },
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

fun WorkoutHistoryDay.summaryText(): String {
    val corrected = items.firstOrNull { it.postureCorrection != null }
    if (corrected != null) {
        return "${corrected.workoutName}에서 ${corrected.postureCorrection?.focus.orEmpty()}가 부족했어요."
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

private fun defaultPostureFocus(category: String): String = when (category) {
    "하체" -> "무릎 정렬과 골반 중심"
    "상체" -> "어깨 긴장과 팔꿈치 각도"
    "코어", "복근" -> "허리 중립과 복부 긴장"
    "유산소" -> "착지 균형과 상체 흔들림"
    else -> "동작 마지막 구간의 안정성"
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
