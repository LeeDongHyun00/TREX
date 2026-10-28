package com.example.trex_kotlin.food

/**
 * 기록할 때 내 음식 기억([FoodMemory])에 남길 자리를 고르는 규칙 — 화면(PhotoFoodSheet)과 떼어 JVM 테스트로 고정한다.
 *
 * **사용자가 그 자리에 직접 고른 이름만 남긴다.** 모델이 붙인 이름과 기억이 자동으로 붙인 이름은 남기지 않는다 — 사용자가
 * 확인했다고 볼 근거가 약하고, 틀린 자동 이름이 다시 기억이 되면 같은 틀림이 스스로 굳는다. 그래서 줄 이름과 모델 이름을 견줘
 * 짐작하지 않고, 사용자가 고른 자리를 줄이 직접 들고 온다([Row.userRegionIds]). 짐작하면 사용자가 고른 이름이 같은 이름으로
 * 합쳐진 다른 자리(기억이 붙인 자리)까지 사용자 확인으로 번진다.
 *
 * 모델 점수가 낮아 비어 보인 자리([FoodRegion.looksEmpty])도 사용자가 이름을 붙였으면 남긴다 — 단무지·생강 같은 작은 곁들이가
 * 여기에 섞인다(FOOD_EVAL §8.3). 다음 분석은 그 자리를 후보로만 견준다([FoodRegion.memorySuggestionOnly], FOOD_IMPROVEMENT_PLAN 4단계).
 */
object FoodMemoryPolicy {
    /**
     * 결과 한 줄에서 이 규칙이 보는 것. [recordable] 은 기록되는 줄인지(영양값이 있는지), [userRegionIds] 는 이 줄 이름을
     * 사용자가 직접 고른 자리([FoodRegion.id]) — 줄이 합쳐지거나 나뉘어도 자리를 따라간다.
     */
    class Row(val name: String, val recordable: Boolean, val userRegionIds: Set<Int>)

    /** 남길 자리 하나. [correctedFrom] 은 모델이 그 자리에 붙였던 이름(고침 기억), "?" 자리면 null. */
    data class Spot(val region: FoodRegion, val name: String, val correctedFrom: String?)

    fun spotsToRemember(regions: List<FoodRegion>, rows: List<Row>): List<Spot> = regions.mapNotNull { region ->
        val row = rows.firstOrNull { it.recordable && region.id in it.userRegionIds } ?: return@mapNotNull null
        // 모델이 이미 그 이름을 붙인 자리다 — 고친 것이 없으니 남길 것도 없다.
        if (row.name == region.name) return@mapNotNull null
        Spot(region, row.name, correctedFrom = region.name)
    }
}
