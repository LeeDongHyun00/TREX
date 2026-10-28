package com.example.trex_kotlin.food

import com.example.trex_kotlin.food.FoodMemoryPolicy.Row
import com.example.trex_kotlin.food.FoodMemoryPolicy.Spot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 기록할 때 내 음식 기억에 남길 자리(FOOD_IMPROVEMENT_PLAN 4단계): 사용자가 그 자리에 직접 고른 이름만 남긴다.
 * 모델 점수가 낮아 빈 그릇처럼 보인 자리도 사용자가 이름을 붙였으면 남기고, 모델·기억이 붙인 이름과 기록하지 않는 줄은 남기지 않는다.
 */
class FoodMemoryPolicyTest {

    private fun region(id: Int, vararg top: Pair<String, Float>, remembered: List<Pair<String, Float>> = emptyList()) =
        FoodRegion(id, 0, PixelBox(0f, 0f, 0.1f, 0.1f), 0.5f, top.toList(), remembered)

    private fun row(name: String, vararg userRegionIds: Int, recordable: Boolean = true) = Row(name, recordable, userRegionIds.toSet())

    @Test
    fun userNamedLowScoreSpotIsRemembered() {
        val side = region(3, "김치" to 0.01f)
        assertEquals(listOf(Spot(side, "단무지", correctedFrom = null)), FoodMemoryPolicy.spotsToRemember(listOf(side), listOf(row("단무지", 3))))
    }

    @Test
    fun replacingTheModelNameRemembersWhatTheModelSaid() {
        val dish = region(1, "김치" to 0.8f)
        assertEquals(listOf(Spot(dish, "깍두기", correctedFrom = "김치")), FoodMemoryPolicy.spotsToRemember(listOf(dish), listOf(row("깍두기", 1))))
    }

    @Test
    fun spotMergedIntoAUserRowIsNotConfirmedByTheUser() {
        val picked = region(2, "밥" to 0.3f)
        // 기억이 자동으로 현미밥을 붙인 자리 — 사용자가 다른 자리에 고른 현미밥과 한 줄로 합쳐졌을 뿐 사용자가 확인한 것은 아니다.
        val auto = region(5, "밥" to 0.3f, remembered = listOf("현미밥" to 0.9f))
        val spots = FoodMemoryPolicy.spotsToRemember(listOf(picked, auto), listOf(row("현미밥", 2)))
        assertEquals(listOf(Spot(picked, "현미밥", correctedFrom = null)), spots)
    }

    @Test
    fun namesTheUserDidNotPickAreNotRemembered() {
        val model = region(1, "김치" to 0.8f)
        val auto = region(5, "밥" to 0.3f, remembered = listOf("현미밥" to 0.9f))
        val empty = region(7, "김치" to 0.01f)
        // 모델 이름 그대로 기록한 줄, 기억이 붙인 줄, 아무도 이름을 붙이지 않은(또는 고른 뒤 지운) 빈 그릇.
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
