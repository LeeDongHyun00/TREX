package com.example.trex_kotlin

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 도메인 계산 단일 소스.
 *
 * 기존에는 "12회 x 3세트" 같은 표시 문자열을 TrexData / MainScreens / SessionScreens 가
 * 각자 정규식으로 파싱했고, 끼니 시간대 판정과 권장 영양 계산도 화면 파일마다 사본이 있었다.
 * 규칙이 바뀔 때 세 곳이 어긋나는 구조라 여기로 모은다.
 */

/** reps 표시 문자열("12회 x 3세트", "60초 x 3세트", "전신 6분")의 구조화 해석. */
data class RepsSpec(
    val count: Int,
    val sets: Int,
    /** 세트 목표 표기 — "12회" / "60초" / "전신 6분" 같은 원문 유지형. */
    val targetLabel: String,
)

fun parseReps(reps: String): RepsSpec {
    val numbers = Regex("\\d+").findAll(reps).map { it.value.toInt() }.toList()
    val sets = if (reps.contains("세트") && numbers.size >= 2) numbers.last().coerceAtLeast(1) else 1
    val count = numbers.firstOrNull()?.coerceAtLeast(1) ?: 1
    val targetLabel = when {
        reps.contains("초") -> "${count}초"
        reps.contains("분") && !reps.contains("회") -> reps
        else -> "${count}회"
    }
    return RepsSpec(count = count, sets = sets, targetLabel = targetLabel)
}

fun formatReps(count: Int, sets: Int): String =
    "${count.coerceIn(1, 999)}회 x ${sets.coerceIn(1, 99)}세트"

fun Workout.repsSpec(): RepsSpec = parseReps(reps)

fun Workout.durationMinutes(): Int =
    Regex("\\d+").find(duration)?.value?.toIntOrNull()?.coerceAtLeast(1) ?: 6

fun Workout.estimatedCalories(): Int {
    val multiplier = when (category) {
        "유산소" -> 8
        "하체" -> 7
        "상체" -> 6
        "코어", "복근" -> 5
        else -> 4
    }
    return (durationMinutes() * multiplier).coerceAtLeast(24)
}

/** 세션 화면이 쓰는 실행 스펙. 휴식 시간은 아직 전 종목 공통 30초. */
data class ExerciseSpec(
    val targetReps: Int,
    val targetLabel: String,
    val totalSets: Int,
    val restSeconds: Int,
)

fun Workout.exerciseSpec(): ExerciseSpec {
    val spec = repsSpec()
    return ExerciseSpec(
        targetReps = spec.count,
        targetLabel = spec.targetLabel,
        totalSets = spec.sets,
        restSeconds = 30,
    )
}

fun Workout.loadLabel(): String = when (category) {
    "하체", "상체" -> "체중"
    "코어", "복근" -> "매트"
    "유산소" -> "심박"
    "회복" -> "가동범위"
    else -> "자율"
}

// ---- 끼니 시간대

/** 현재 시각이 속하는 끼니 슬롯 id ("breakfast"/"lunch"/"snack"/"dinner"). */
fun currentMealId(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when {
    hour < 10 -> "breakfast"
    hour < 14 -> "lunch"
    hour < 18 -> "snack"
    else -> "dinner"
}

data class MealTimeInfo(
    val id: String,
    val label: String,
    val timeHint: String,
)

fun currentMealInfo(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): MealTimeInfo = when {
    hour < 10 -> MealTimeInfo("breakfast", "아침 식사", "08:00 ~ 10:00")
    hour < 14 -> MealTimeInfo("lunch", "점심 식사", "12:00 ~ 14:00")
    hour < 18 -> MealTimeInfo("snack", "오후 간식", "15:00 ~ 17:00")
    else -> MealTimeInfo("dinner", "저녁 식사", "18:00 ~ 20:00")
}

// ---- 권장 영양 목표 (Mifflin-St Jeor)

fun recommendedNutritionGoal(profile: UserProfile): Nutrition {
    val weight = profile.weightKg
    val height = profile.heightCm
    val age = profile.age
    val genderOffset = if (profile.gender == "female") -161.0 else 5.0
    val bmr = 10 * weight + 6.25 * height - 5 * age + genderOffset
    val kcal = ((bmr * profile.activityFactor) / 10).toInt() * 10
    val protein = (weight * 1.8).roundToInt().toDouble()
    val fat = (kcal * 0.25 / 9.0).roundToInt().toDouble()
    val carb = ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(0).toDouble()
    return Nutrition(kcal = kcal, carb = carb, protein = protein, fat = fat)
}

fun Nutrition.normalizedGoal(): Nutrition = Nutrition(
    kcal = kcal.coerceIn(800, 5000),
    carb = carb.coerceIn(0.0, 800.0),
    protein = protein.coerceIn(0.0, 400.0),
    fat = fat.coerceIn(0.0, 250.0),
)

// ---- 문자열 입력 필터 (파일마다 있던 numericText/digitsOnly/decimalOnly 사본 통합)

fun String.digitsOnly(): String = filter(Char::isDigit)

fun String.decimalOnly(): String {
    var dotSeen = false
    return filter { char ->
        when {
            char.isDigit() -> true
            char == '.' && !dotSeen -> {
                dotSeen = true
                true
            }
            else -> false
        }
    }
}

fun Int.asClock(): String {
    val minute = this / 60
    val second = this % 60
    return minute.toString().padStart(2, '0') + ":" + second.toString().padStart(2, '0')
}
