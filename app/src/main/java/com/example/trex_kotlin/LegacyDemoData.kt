package com.example.trex_kotlin

/** 구버전 데모의 정확한 전체 지문. 사용자 기록과 일부 값만 같다는 이유로 지우지 않는다. */
internal object LegacyDemoData {
    fun isSampleDay(day: WorkoutHistoryDay): Boolean = legacyHistory.any {
        day.items == it.items && day.averageMinutes == it.averageMinutes && day.averageCalories == it.averageCalories
    }

    fun isSampleDiet(slots: Map<String, List<FoodEntry>>): Boolean = slots == legacyFoods()

private fun legacyFoods(): Map<String, List<FoodEntry>> = mapOf(
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

// 현재 추천 루틴·칼로리 계산이 바뀌어도 구버전 지문은 변하면 안 된다.
private val legacyItems = listOf(
    WorkoutHistoryItem("기본 스쿼트", "12회 x 3세트", 8, 56),
    WorkoutHistoryItem("플랭크", "60초 x 3세트", 5, 25),
    WorkoutHistoryItem("런지", "10회 x 3세트", 10, 70),
    WorkoutHistoryItem("푸쉬업 입문", "8회 x 3세트", 6, 36),
    WorkoutHistoryItem("마무리 스트레칭", "전신 6분", 6, 24),
)

private val legacyHistory = (0..6).map { index ->
        val start = index % legacyItems.size
        val items = (legacyItems.drop(start) + legacyItems.take(start)).take(2 + index % 3)
        WorkoutHistoryDay(
            epochDay = 0,
            dayLabel = "",
            dateLabel = "",
            items = items,
            averageMinutes = (items.sumOf { it.durationMinutes } - 4 - index % 2).coerceAtLeast(8),
            averageCalories = (items.sumOf { it.calories } - 28 - index * 2).coerceAtLeast(80),
        )
    }
}
