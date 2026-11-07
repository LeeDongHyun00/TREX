package com.example.trex_kotlin

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
        reps.contains("분") && !reps.contains("회") -> reps.replace(Regex("\\s*[×xX]\\s*\\d+세트.*"), "")
        else -> "${count}회"
    }
    return RepsSpec(count = count, sets = sets, targetLabel = targetLabel)
}

fun formatReps(count: Int, sets: Int): String =
    "${count.coerceIn(1, 999)}회 x ${sets.coerceIn(1, 99)}세트"

fun Workout.repsSpec(): RepsSpec = parseReps(reps)

fun Workout.durationMinutes(): Int = timing().minutes

/**
 * 종목의 소모 kcal 추정 — MET(`ExerciseMet`, 2024 Compendium) × 3.5 × [bodyKg] / 200 × 분(§134b). [activitySeconds] 는 **휴식을 포함한** 활동 시간이다
 * (Compendium 값이 휴식을 포함한 활동 평균이라) — null 이면 계획 시간(가동 + 세트 사이 휴식, `timing().totalSeconds`).
 */
fun Workout.estimatedCalories(activitySeconds: Int?, bodyKg: Double): Int =
    estimatedKcal(ExerciseMet.of(name, category).met, bodyKg, activitySeconds ?: timing().totalSeconds)

/**
 * 세션의 종목별 kcal — 세션 총 경과([elapsedSeconds]: 준비·휴식 포함, 일시정지 제외)를 종목의 **가동 시간 비율**로 나눠 각 종목의 MET 를 적용한다.
 * 세션은 휴식을 종목별로 재지 않으므로(`workDurations` 는 WORK 칸만) 총 시간을 나눠 쓴다 — 준비·전환 시간도 들어가지만 Compendium 의 세션 평균 MET 역시
 * 그런 시간을 포함해 잰 값이다. 가동 시간이 전부 0 이면(한 세트도 안 함) 전부 0.
 */
fun sessionKcalByWorkout(plan: List<Workout>, elapsedSeconds: Int, elapsedByWorkout: Map<String, Int>, bodyKg: Double): Map<String, Int> {
    val work = plan.associate { it.id to (elapsedByWorkout[it.id] ?: 0).coerceAtLeast(0) }
    val totalWork = work.values.sum()
    if (totalWork <= 0) return plan.associate { it.id to 0 }
    val total = elapsedSeconds.coerceAtLeast(totalWork)
    return plan.associate { w -> w.id to w.estimatedCalories((total.toDouble() * work.getValue(w.id) / totalWork).roundToInt(), bodyKg) }
}

/**
 * 세션 시간이 없는 기록(직접 적은 기록·옛 기록)의 활동 시간 — 세트 가동 초([workSeconds], 0 이면 **세트당 30초** 가정) + 세트 사이 설정 휴식(`WorkoutPacing.restSeconds`).
 * 30초 가정은 화면 ⓘ 가 밝힌다(근거 감사 §3).
 */
fun offlineActivitySeconds(name: String, category: String, sets: Int, workSeconds: Int): Int {
    val n = sets.coerceAtLeast(1)
    return (if (workSeconds > 0) workSeconds else n * 30) + (n - 1) * WorkoutPacing.restSeconds(name, category)
}

/** §134b 이전의 kcal 식(분류별 분당 상수 × 가동 분) — 옛 기록을 알아보는 데만 쓴다(`migrateLegacyCalories`). 새 계산에 쓰지 않는다. */
internal fun legacyCategoryCalories(category: String?, seconds: Int): Int {
    val multiplier = when (category) { "유산소" -> 8; "하체" -> 7; "상체" -> 6; "코어", "복근" -> 5; else -> 4 }
    return (seconds.coerceAtLeast(0) / 60.0 * multiplier).roundToInt().coerceAtLeast(0)
}

/** 세션 화면이 쓰는 실행 스펙. 설정한 세트 사이 휴식을 포함한다. */
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
        restSeconds = timing().restSeconds,
    )
}


// ---- 끼니 시간대

/** 현재 시각이 속하는 끼니 슬롯 id ("breakfast"/"lunch"/"snack"/"dinner"). */
fun currentMealId(hour: Int = currentHour()): String = when {
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

fun currentMealInfo(hour: Int = currentHour()): MealTimeInfo = when {
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
