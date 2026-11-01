package com.example.trex_kotlin

import com.example.trex_kotlin.food.FoodMemoryPolicy
import com.example.trex_kotlin.food.FoodRegion
import com.example.trex_kotlin.food.PixelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사진 식단 결과 줄의 연산(`PhotoFoodRows.kt`) — 줄 합치기·자리 이름 붙이기·줄 바꾸기·한 자리 떼어 바꾸기에서
 * 사용자가 직접 고른 자리([RecognizedItem.userRegionIds])가 그 자리만 따라가는지, 사진 이름표·목록 표시·기억 저장이 그것을 쓰는지.
 */
class PhotoFoodRowsTest {

    private val n = Nutrition(100, 10.0, 5.0, 2.0)

    private fun region(id: Int, vararg top: Pair<String, Float>, remembered: List<Pair<String, Float>> = emptyList()) =
        FoodRegion(id, 0, PixelBox(0f, 0f, 0.1f, 0.1f), 0.5f, top.toList(), remembered)

    private val kimchi = region(1, "김치" to 0.8f)
    private val autoRice = region(5, "밥" to 0.3f, remembered = listOf("현미밥" to 0.9f))

    /** 분석 직후 — 모델이 이름 붙인 김치 자리 1, 기억이 자동으로 현미밥을 붙인 자리 5. */
    private fun initial() = listOf(
        RecognizedItem("김치", n, FoodSource.Detected(0.8f, 0), regionIds = setOf(1)),
        RecognizedItem("현미밥", n, FoodSource.Remembered(0.9f, 0), regionIds = setOf(5)),
    )

    @Test
    fun namingASpotConfirmsOnlyThatSpot() {
        val rice = initial().withPicked("현미밥", n, regionId = 3).single { it.name == "현미밥" }
        assertEquals(setOf(5, 3), rice.regionIds)
        assertEquals(setOf(3), rice.userRegionIds)
        assertEquals(2.0, rice.qty, 0.0)
    }

    @Test
    fun addingWithoutASpotConfirmsNoSpot() {
        val kimchiRow = initial().withPicked("김치", n, regionId = null).single { it.name == "김치" }
        assertEquals(setOf(1), kimchiRow.regionIds)
        assertEquals(emptySet<Int>(), kimchiRow.userRegionIds)
    }

    @Test
    fun replacedRowMergingWithAMemoryRowDoesNotConfirmTheMemorySpot() {
        // 목록에서 김치 줄을 현미밥으로 바꾸면, 기억이 붙인 현미밥 줄과 한 줄이 되고 바꾼 줄이 앞에 선다.
        val rows = initial().withReplaced(0, "현미밥", n, regionId = null)
        val rice = rows.single()
        assertEquals(setOf(1, 5), rice.regionIds)
        assertEquals(setOf(1), rice.userRegionIds)
        assertEquals(LabelKind.USER, rice.labelKindFor(kimchi))
        assertEquals("기억이 붙인 자리는 기억 이름표 그대로", LabelKind.MEMORY, rice.labelKindFor(autoRice))
        assertTrue("목록도 기억이 섞였다고 밝힌다", rice.includesMemorySpot(listOf(kimchi, autoRice)))
        val saved = FoodMemoryPolicy.spotsToRemember(listOf(kimchi, autoRice), rows.memoryRows())
        assertEquals(listOf(1), saved.map { it.region.id })
        assertEquals("김치", saved.single().correctedFrom)
    }

    @Test
    fun splittingOneSpotOffMovesItsConfirmation() {
        val start = listOf(RecognizedItem("깍두기", n, FoodSource.Detected(0.7f, 0), regionIds = setOf(1, 2)))
        val renamed = start.withReplaced(0, "김치", n, regionId = null).single()
        assertEquals("줄 전체를 바꾸면 그 줄의 자리 모두 사용자가 고른 것", setOf(1, 2), renamed.userRegionIds)
        val split = listOf(renamed).withReplaced(0, "단무지", n, regionId = 2)
        val left = split.single { it.name == "김치" }
        val moved = split.single { it.name == "단무지" }
        assertEquals(setOf(1), left.regionIds)
        assertEquals(setOf(1), left.userRegionIds)
        assertEquals(setOf(2), moved.regionIds)
        assertEquals(setOf(2), moved.userRegionIds)
    }

    @Test
    fun modelAndMemoryRowsAreNotUserChoices() {
        val rows = initial()
        assertEquals(LabelKind.MODEL, rows[0].labelKindFor(kimchi))
        assertEquals(LabelKind.MEMORY, rows[1].labelKindFor(autoRice))
        assertFalse(rows[0].includesMemorySpot(listOf(kimchi, autoRice)))
        assertEquals(emptyList<FoodMemoryPolicy.Spot>(), FoodMemoryPolicy.spotsToRemember(listOf(kimchi, autoRice), rows.memoryRows()))
    }
}
