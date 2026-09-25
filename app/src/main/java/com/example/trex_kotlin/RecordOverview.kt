package com.example.trex_kotlin

/**
 * 카탈로그에서 뺀 종목의 원래 분류(spec §56 이전 카탈로그·오늘의 운동 기본값 그대로).
 * 분류를 저장하지 않던 때의 기록은 종목명으로만 초점을 되찾는다. 이 표가 없으면 §56 업데이트만으로
 * 사용자가 이미 한 운동(예: 기본 스쿼트 = 하체)이 기록 화면에서 전부 '전신'으로 바뀌어 보인다.
 * 지난 기록의 표시에만 쓴다 — 새로 고를 수 있는 종목이 아니고, 현재 카탈로그와 이름이 겹치지 않는다.
 */
internal val retiredWorkoutCategories: Map<String, String> = mapOf(
    // "기본 스쿼트" 는 2026-09-25 부터 살아 있는 카탈로그 이름(바벨 스쿼트의 앱 이름) — 퇴역 표에서 뺐다(카탈로그 분류가 정본)
    "불가리안 스플릿 스쿼트" to "하체", "글루트 브릿지" to "하체", "월 싯" to "하체", "카프 레이즈" to "하체",
    "인클라인 푸쉬업" to "상체", "벽 푸쉬업" to "상체", "밴드 로우" to "상체", "푸쉬업 입문" to "상체",
    "사이드 플랭크" to "코어", "플랭크 숄더탭" to "코어", "버드독" to "코어", "데드버그" to "코어", "할로우 홀드" to "코어",
    "힙 브릿지 홀드" to "코어",
    "리버스 크런치" to "복근", "바이시클 크런치" to "복근", "러시안 트위스트" to "복근",
    "제자리 걷기" to "유산소", "하이 니" to "유산소", "마운틴 클라이머" to "유산소", "점핑잭" to "유산소", "스텝업" to "유산소",
    "스키터 점프" to "유산소", "섀도 복싱" to "유산소", "버피" to "유산소",
    "마무리 스트레칭" to "회복", "캣카우 스트레칭" to "회복", "차일드 포즈" to "회복", "폼롤러 마무리" to "회복",
    "햄스트링 스트레칭" to "회복", "흉추 회전 스트레칭" to "회복",
)

/**
 * 구 기록은 종목명으로만 분류를 보완한다 — 카탈로그 → 오늘의 운동 → 카탈로그에서 뺀 종목 표 순서.
 * 셋 다 모르는 종목은 전신으로 남긴다.
 */
internal fun recordWorkoutFocus(day: WorkoutHistoryDay): RoutineFocus {
    val plan = day.items.mapIndexed { index, item ->
        val category = item.category ?: workoutCatalog.entries.firstOrNull { (_, entries) -> entries.any { it.name == item.workoutName } }?.key
            ?: todayPlan.firstOrNull { it.name == item.workoutName }?.category
            ?: retiredWorkoutCategories[item.workoutName] ?: "전신"
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
