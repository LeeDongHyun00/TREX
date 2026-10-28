package com.example.trex_kotlin.food

import com.example.trex_kotlin.food.FoodMemoryPolicy.Row
import com.example.trex_kotlin.food.FoodMemoryPolicy.Spot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 기록할 때 내 음식 기억에 남길 자리(FOOD_IMPROVEMENT_PLAN 4단계): 사용자가 그 자리에 직접 고른 이름만 남긴다.
 * 모델 점수가 낮아 빈 그릇처럼 보인 자리도 사용자가 이름을 붙였으면 남기되 후보 전용으로, 모델·기억이 붙인 이름과 기록하지 않는 줄은 남기지 않는다.
 * 줄이 합쳐지고 나뉠 때 사용자가 고른 자리가 따라가는 것은 `PhotoFoodRowsTest`.
 */
class FoodMemoryPolicyTest {

    private fun region(id: Int, vararg top: Pair<String, Float>, remembered: List<Pair<String, Float>> = emptyList()) =
        FoodRegion(id, 0, PixelBox(0f, 0f, 0.1f, 0.1f), 0.5f, top.toList(), remembered)

    private fun row(name: String, vararg userRegionIds: Int, recordable: Boolean = true) = Row(name, recordable, userRegionIds.toSet())

    @Test
    fun userNamedLowScoreSpotIsRememberedAsSuggestionOnly() {
        val side = region(3, "김치" to 0.01f)
        assertEquals(
            listOf(Spot(side, "단무지", correctedFrom = null, suggestOnly = true)),
            FoodMemoryPolicy.spotsToRemember(listOf(side), listOf(row("단무지", 3))),
        )
    }

    @Test
    fun replacingTheModelNameRemembersWhatTheModelSaid() {
        val dish = region(1, "김치" to 0.8f)
        assertEquals(
            listOf(Spot(dish, "깍두기", correctedFrom = "김치", suggestOnly = false)),
            FoodMemoryPolicy.spotsToRemember(listOf(dish), listOf(row("깍두기", 1))),
        )
    }

    @Test
    fun pickingTheNameMemorySuggestedIsStillTheUsersChoice() {
        // 기억이 자동으로 붙인 줄을 지우고 같은 이름을 직접 고른 자리 — 사용자가 확인한 것이다.
        val spot = region(4, "밥" to 0.3f, remembered = listOf("현미밥" to 0.9f))
        assertEquals(
            listOf(Spot(spot, "현미밥", correctedFrom = null, suggestOnly = false)),
            FoodMemoryPolicy.spotsToRemember(listOf(spot), listOf(row("현미밥", 4))),
        )
    }

    @Test
    fun spotsNotPickedByTheUserAreNotRememberedEvenUnderTheSameRowName() {
        val model = region(1, "김치" to 0.8f)
        val auto = region(5, "밥" to 0.3f, remembered = listOf("현미밥" to 0.9f))
        val empty = region(7, "김치" to 0.01f)
        // 모델 이름 그대로인 줄, 기억이 붙인 줄 — 줄 이름이 같아도 사용자가 고른 자리가 아니다. 빈 그릇 7은 아무도 이름을 붙이지 않았다.
        val spots = FoodMemoryPolicy.spotsToRemember(listOf(model, auto, empty), listOf(row("김치"), row("현미밥")))
        assertEquals(emptyList<Spot>(), spots)
    }

    @Test
    fun pickingTheModelsOwnNameAddsNothing() {
        val dish = region(1, "김치" to 0.8f)
        assertEquals(emptyList<Spot>(), FoodMemoryPolicy.spotsToRemember(listOf(dish), listOf(row("김치", 1))))
    }

    @Test
    fun rowThatIsNotRecordedIsNotRemembered() {
        val side = region(3, "김치" to 0.01f)
        assertEquals(emptyList<Spot>(), FoodMemoryPolicy.spotsToRemember(listOf(side), listOf(row("모르는음식", 3, recordable = false))))
    }
}
