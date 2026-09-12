package com.example.trex_kotlin

/** 처방이 아닌 루틴 요약. 카탈로그의 주 부위와 실제 설정한 운동 시간만 사용한다. */
enum class RoutineFocus(val title: String, val image: String) {
    LOWER("하체 중심", "lower"), UPPER("상체 중심", "upper"), CORE("코어 중심", "core"),
    CARDIO("유산소 중심", "cardio"), RECOVERY("회복 중심", "recovery"), FULL("전신 운동", "full"),
    EMPTY("오늘의 운동을 골라 보세요", "full"),
}

data class RoutineOverview(val focus: RoutineFocus, val count: Int, val totalSeconds: Int, val totalSets: Int = 0) {
    val minutes: Int get() = (totalSeconds + 59) / 60
    val detail: String get() = if (count == 0) "목록에 운동을 추가할 수 있어요" else "${count}개 운동 · ${totalSets}세트 · 약 ${minutes}분"
}

fun routineOverview(plan: List<Workout>): RoutineOverview {
    if (plan.isEmpty()) return RoutineOverview(RoutineFocus.EMPTY, 0, 0)
    fun focus(category: String) = when (category) {
        "하체" -> RoutineFocus.LOWER
        "상체", "가슴", "등", "어깨", "팔" -> RoutineFocus.UPPER
        "코어", "복근" -> RoutineFocus.CORE
        "유산소" -> RoutineFocus.CARDIO
        "회복", "스트레칭" -> RoutineFocus.RECOVERY
        else -> RoutineFocus.FULL
    }
    val weights = plan.groupBy { focus(it.category) }.mapValues { (_, workouts) ->
        workouts.sumOf { it.timing().let { t -> t.workSeconds.toLong() * t.sets } }
    }
    // 마무리 스트레칭은 본운동의 중심을 바꾸지 않는다. 회복만 있으면 회복 루틴이다.
    val main = weights.filterKeys { it != RoutineFocus.RECOVERY }.ifEmpty { weights }
    val ordered = main.entries.sortedByDescending { it.value }
    val lead = ordered.first(); val sum = main.values.sum(); val second = ordered.getOrNull(1)?.value ?: 0L
    // 근소한 차이로 특정 부위라고 단정하지 않는다. 동률은 순서와 무관하게 전신이다.
    val result = if (lead.value * 100 >= sum * 45 && (lead.value - second) * 100 >= sum * 10)
        lead.key else RoutineFocus.FULL
    return RoutineOverview(result, plan.size, plan.sumOf { it.timing().totalSeconds }, plan.sumOf { it.timing().sets })
}
