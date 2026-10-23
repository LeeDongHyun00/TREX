package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음식 인분 규칙(FoodPortion) — 스테퍼 0.5 씩, 직접 입력 소수 한 자리, 예전 앱과 호환되는 저장. */
class FoodPortionTest {

    @Test
    fun stepper_movesByHalf_andRemovesBelowHalf() {
        assertEquals(1.5, FoodPortion.step(1.0, up = true)!!, 0.0)
        assertEquals(0.5, FoodPortion.step(1.0, up = false)!!, 0.0)
        assertNull("0.5 에서 한 칸 내리면 뺀다", FoodPortion.step(0.5, up = false))
        // 직접 넣은 0.3 에서 내리면 0 아래라 뺀다, 올리면 0.8
        assertNull(FoodPortion.step(0.3, up = false))
        assertEquals(0.8, FoodPortion.step(0.3, up = true)!!, 0.0)
        assertEquals("위로는 20 에서 멈춘다", 20.0, FoodPortion.step(20.0, up = true)!!, 0.0)
    }

    @Test
    fun deleteIcon_showsOnlyWhenNextStepRemoves() {
        assertTrue(FoodPortion.removesOnStepDown(0.5))
        assertTrue(FoodPortion.removesOnStepDown(0.3))
        assertFalse(FoodPortion.removesOnStepDown(1.0))
        assertFalse(FoodPortion.removesOnStepDown(0.8))
    }

    @Test
    fun label_dropsZeroDecimal() {
        assertEquals("1", FoodPortion.label(1.0))
        assertEquals("0.5", FoodPortion.label(0.5))
        assertEquals("2.3", FoodPortion.label(2.3))
        assertEquals("0.3", FoodPortion.label(0.1 + 0.2))   // 부동소수 찌꺼기가 보이지 않는다
        assertEquals("12", FoodPortion.label(12.0))
    }

    @Test
    fun typedInput_acceptsOneDecimalInRange() {
        assertEquals(0.3, FoodPortion.parseTyped("0.3")!!, 0.0)
        assertEquals(1.5, FoodPortion.parseTyped(" 1,5 ")!!, 0.0)          // 쉼표도 소수점
        assertEquals(0.3, FoodPortion.parseTyped("0.25")!!, 0.0)          // 소수 한 자리로 맞춘다
        assertEquals(20.0, FoodPortion.parseTyped("20")!!, 0.0)
        assertNull("0 은 받지 않는다(지우는 길은 스테퍼뿐)", FoodPortion.parseTyped("0"))
        assertNull(FoodPortion.parseTyped("0.04"))                       // 0.0 으로 맞춰져 범위 밖
        assertNull(FoodPortion.parseTyped(""))
        assertNull(FoodPortion.parseTyped("."))
        assertNull(FoodPortion.parseTyped("20.1"))
        assertNull(FoodPortion.parseTyped("abc"))
    }

    @Test
    fun storage_keepsOldJsonForWholePortions_andNeverZeroForOldApps() {
        // 정수 인분은 예전과 똑같이 qty 만 쓴다
        assertNull(FoodPortion.storedPortion(1.0))
        assertNull(FoodPortion.storedPortion(3.0))
        assertEquals(0.5, FoodPortion.storedPortion(0.5)!!, 0.0)
        // 예전 앱이 읽는 qty 는 0 이 되지 않는다 — 반 공기를 0 kcal 로 보이지 않게
        assertEquals(1, FoodPortion.legacyCount(0.5))
        assertEquals(1, FoodPortion.legacyCount(0.3))
        assertEquals(2, FoodPortion.legacyCount(1.5))
        assertEquals(2, FoodPortion.legacyCount(2.4))
        assertEquals(3, FoodPortion.legacyCount(3.0))
    }

    @Test
    fun storage_readsExactPortion_unlessOldAppChangedQty() {
        assertEquals("예전 기록", 2.0, FoodPortion.fromStored(2, null), 0.0)
        assertEquals(0.5, FoodPortion.fromStored(1, 0.5), 0.0)
        assertEquals(1.5, FoodPortion.fromStored(2, 1.5), 0.0)
        // 예전 앱이 그 줄을 0.5(근사 1)에서 3 으로 올렸다 — portion 이 남아 있어도 qty 를 믿는다
        assertEquals(3.0, FoodPortion.fromStored(3, 0.5), 0.0)
        assertEquals(1.0, FoodPortion.fromStored(1, Double.NaN), 0.0)
    }

    @Test
    fun totals_multiplyByPortion() {
        val rice = Nutrition(300, 66.0, 6.0, 1.0)
        val half = FoodEntry("쌀밥", rice, 0.5)
        assertEquals(150, half.totalKcal())
        val total = listOf(half, FoodEntry("김치", Nutrition(15, 2.0, 1.0, 0.0), 2.0)).totalNutrition()
        assertEquals(180, total.kcal)
        assertEquals(37.0, total.carb, 1e-9)
    }
}
