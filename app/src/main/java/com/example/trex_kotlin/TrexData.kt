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
    /** null이면 종목별 기본 속도/휴식. 기존 저장 계획과 호환한다. */
    val secondsPerRep: Int? = null,
    val restSeconds: Int? = null,
    /** 목표 단위는 실행 종료 조건이다. null은 기존 문자열 계획을 읽는 호환 경로다. */
    val target: WorkoutTarget? = null,
    /** null이면 종목 프로필의 기본 수행 방식. 구버전 계획은 이 기본값을 사용한다. */
    val repMovementPattern: com.example.trex_kotlin.posture.RepMovementPattern? = null,
)

@Immutable
data class WorkoutAlt(
    val name: String,
    val reps: String,
)

/**
 * 기록 항목에 접혀 들어가는 세트 리포트 요약 (spec §30). [focus] 는 관찰 문장(끝 마침표 없음) —
 * CLEAN·RECOVERED도 세트 리포트의 판정 범위와 관측 회복 표현을 유지한다. 전체 자세 정상이나 교정 완료를 뜻하지 않는다.
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
    /**
     * 엔진이 관측한 사용자 기준 좌우 횟수. 수동 입력 [actualReps]와 독립이며, 미관측·구버전 값은 0이 아닌 null이다.
     * 양쪽 동시 1회는 left/right 각각 1회와 both 1회로 표현하므로 네 값을 더해서 총횟수로 쓰지 않는다.
     */
    val observedLeftReps: Int? = null,
    val observedRightReps: Int? = null,
    val observedBothReps: Int? = null,
    val observedUnknownReps: Int? = null,
    /** 관측 당시 수행 방식의 enum 이름. null은 기록에 방식 정보가 없음을 뜻한다. */
    val repMovementPattern: String? = null,
)

/**
 * 세트 리포트 → 기록 항목. posture 패키지는 app 에 의존할 수 없어서 확장함수는 이쪽에 둔다.
 * kind 는 verdict 소문자를 쓰되 ISSUE 만 헤드라인의 onset 종류(habit/drift/violation)로 세분한다 — 기록 화면이 라벨 색을 고르는 키.
 */
fun PostureSetReport.toCorrection(): PostureCorrection {
    // REFERENCE 는 non-beta 헤드라인이 없으므로 첫 후보(베타)를 대표로 — beta 플래그가 같이 실려 UI 가 "참고" 로 낮춘다
    val lead = headline ?: candidates.firstOrNull()
    val focus = when {
        (exercise in com.example.trex_kotlin.posture.FloorTemporal.exercises || judged == 0) && measurements.isNotEmpty() -> measurements.joinToString(" · ")
        mode == CoachMode.TRACK -> (listOf(summaryLine) + measurements).joinToString(" · ")
        verdict == SetVerdict.CLEAN -> summaryLine
        verdict == SetVerdict.UNJUDGED -> "자세 판정 없음"
        verdict == SetVerdict.RECOVERED -> summaryLine
        else -> lead?.observation ?: summaryLine
    }
    val kind = if (mode == CoachMode.TRACK) {
        if (judged == 0) "unjudged" else "reference"
    } else when (verdict) {
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
        fix = lead?.fix?.takeIf { mode != CoachMode.TRACK && it.isNotBlank() && verdict != SetVerdict.RECOVERED },
        note = (listOfNotNull(lead?.note) + measurements.filterNot { focus.contains(it) }).joinToString(" · ").takeIf { it.isNotBlank() },
        beta = lead?.beta ?: false,
        setId = setId,
        mode = if (mode == CoachMode.TRACK) "track" else "coach",
        judged = judged,
        abstained = abstained,
        repsValid = repsValid,
        repsPartial = repsPartial,
        tempoMs = tempoMs,
        observedLeftReps = observedReps?.left,
        observedRightReps = observedReps?.right,
        observedBothReps = observedReps?.both,
        observedUnknownReps = observedReps?.unknown,
        repMovementPattern = observedReps?.pattern,
        actualReps = userEnteredReps,
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
    val category: String? = null,
    val durationSeconds: Int? = null,
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
    Workout("squat", "바벨 스쿼트", "10회 × 3세트", "8분", true, "하체"),
    Workout("plank", "플랭크", "30초 × 3세트", "6분", true, "코어"),
    Workout("lunge", "스텝 포워드 다이나믹 런지", "10회 × 3세트", "8분", true, "하체"),
    Workout("pushup", "니푸쉬업", "8회 × 3세트", "6분", true, "상체"),
)

val onboardingGoals = listOf(
    GoalItem("lower", "건강한 하체 만들어룡!", "스쿼트 · 런지 중심"),
    GoalItem("diet", "다이어트를 목표로 해룡!", "유산소 + 식단 관리"),
    GoalItem("simple", "간단하게 운동만 하고 싶어룡!", "하루 10분 루틴"),
    GoalItem("core", "탄탄한 코어 잡고싶어룡!", "플랭크 · 복근 루틴"),
    GoalItem("posture", "자세부터 바로잡고 싶어룡!", "운동 중 움직임 관측"),
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

/** @param reports workoutId → 이번 세션의 세트 리포트. 자세를 켠 운동이라도 리포트가 없으면 항목의 자세 칸은 null 이다(판정 안 한 것을 지어내지 않는다). */
fun createWorkoutHistoryDay(
    plan: List<Workout>,
    elapsedSeconds: Int,
    reports: Map<String, PostureSetReport> = emptyMap(),
    elapsedByWorkout: Map<String, Int> = emptyMap(),
): WorkoutHistoryDay {
    val calendar = Calendar.getInstance()
    val items = plan.map { workout ->
        val report = reports[workout.id]
        val seconds = elapsedByWorkout[workout.id]?.coerceAtLeast(0) ?: 0
        WorkoutHistoryItem(
            workoutName = workout.name,
            reps = if(report?.observationEngine == true && (report.observedReps != null || report.userEnteredReps != null) && workout.resolvedTarget() is WorkoutTarget.Repetitions)
                "${report.userEnteredReps ?: report.observedReps?.total ?: 0}회 × 1세트" else workout.reps,
            durationMinutes = seconds / 60,
            durationSeconds = seconds,
            calories = workout.estimatedCalories(seconds),
            postureCorrection = report?.toCorrection(),
            accuracy = report?.accuracy,
            category = workout.category,
        )
    }

    return WorkoutHistoryDay(
        epochDay = LocalDate.now().toEpochDay(),
        dayLabel = calendar.koreanDayOfWeek(),
        dateLabel = "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}",
        items = items,
        averageMinutes = 0,
        averageCalories = 0,
    )
}

fun List<WorkoutHistoryDay>.replaceTodayWith(record: WorkoutHistoryDay): List<WorkoutHistoryDay> {
    val existingIndex = indexOfLast { it.epochDay == record.epochDay }
    val updated = if (existingIndex >= 0) {
        toMutableList().also { it[existingIndex] = record }
    } else {
        this + record
    }
    return updated.retainVisibleWorkoutHistory(record.epochDay).sortedBy { it.epochDay }
}

fun WorkoutHistoryDay.totalMinutes(): Int =
    items.sumOf { it.durationSeconds ?: (it.durationMinutes * 60) } / 60

fun WorkoutHistoryDay.totalCalories(): Int =
    items.sumOf { it.calories }

/** 데이터가 없는 비교 기준을 만들지 않는다. */
fun WorkoutHistoryDay.summaryText(): String = dayWorkoutAssessment(this)

fun WorkoutHistoryDay.durationLabel(): String {
    val seconds = items.sumOf { it.durationSeconds ?: it.durationMinutes * 60 }
    return if (seconds < 60) "${seconds}초" else "${seconds / 60}분"
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
