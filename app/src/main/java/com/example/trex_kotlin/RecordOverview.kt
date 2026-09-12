package com.example.trex_kotlin

/** 구 기록은 카탈로그의 정확한 종목명으로만 보완한다. 알 수 없는 종목은 전신으로 남긴다. */
internal fun recordWorkoutFocus(day: WorkoutHistoryDay): RoutineFocus {
    val plan = day.items.mapIndexed { index, item ->
        val category = item.category ?: workoutCatalog.entries.firstOrNull { (_, entries) -> entries.any { it.name == item.workoutName } }?.key
            ?: todayPlan.firstOrNull { it.name == item.workoutName }?.category ?: "전신"
        Workout("record-$index", item.workoutName, "1회 x 1세트", "", false, category,
            target = WorkoutTarget.Duration((item.durationSeconds ?: (item.durationMinutes * 60)).coerceAtLeast(1)), restSeconds = 0)
    }
    return routineOverview(plan).focus
}

/** 일일 평가는 저장된 수행/관측에 한정한다. 유보·베타·기록 모드를 정상 점수로 승격하지 않는다. */
internal fun dayWorkoutAssessment(day: WorkoutHistoryDay): String {
    if (day.items.isEmpty()) return "이날 저장된 운동이 없어룡."
    val base = "${day.items.map { it.workoutName }.distinct().size}개 종목을 기록했어룡."
    val corrections = day.items.mapNotNull { it.postureCorrection }
    val judged = corrections.filter { it.mode != "track" && !it.beta && (it.judged ?: 0) > 0 }
    val issues = judged.count { it.kind in setOf("habit", "drift", "violation") }
    return base + " " + when {
        issues > 0 -> "평가한 ${judged.size}개 기록 중 ${issues}개에서 교정할 부분이 있었어룡."
        judged.any { it.kind == "recovered" } -> "관찰한 자세가 세트 후반에 개선된 기록이 있어룡."
        judged.isNotEmpty() && judged.all { it.kind == "clean" } -> "평가 가능한 항목에서는 교정 신호가 없었어룡."
        corrections.any { it.mode == "track" } -> "처음 자세와의 변화는 각 운동 기록에서 확인해 주세룡."
        else -> "자세를 평가할 기록은 충분하지 않아룡."
    }
}
