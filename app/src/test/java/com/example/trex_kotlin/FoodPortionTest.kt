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
        assertEquals(20.0, FoodPortion.step(19.8, up = true)!!, 0.0)
        // 예전 앱은 상한이 없었다 — 25 에서 + 를 눌러도 줄어들지 않고, - 는 한 칸 내려간다
        assertEquals(25.0, FoodPortion.step(25.0, up = true)!!, 0.0)
        assertEquals(24.5, FoodPortion.step(25.0, up = false)!!, 0.0)
    }

    @Test
    fun merging_capsAtMax_butNeverShrinksOldRecords() {
        assertEquals(1.5, FoodPortion.add(1.0, 0.5), 0.0)
        assertEquals(20.0, FoodPortion.add(15.0, 10.0), 0.0)
        assertEquals(25.0, FoodPortion.add(25.0, 1.0), 0.0)
        assertEquals(0.3, FoodPortion.add(0.1, 0.2), 0.0)
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
        assertEquals(20.0, FoodPortion.parseTyped("20")!!, 0.0)
        assertEquals(1.0, FoodPortion.parseTyped("1.")!!, 0.0)           // 소수점까지만 친 중간 상태
        assertEquals(0.5, FoodPortion.parseTyped(".5")!!, 0.0)
        assertNull("소수 둘째 자리는 몰래 반올림하지 않는다", FoodPortion.parseTyped("1.55"))
        assertNull(FoodPortion.parseTyped("0.25"))
        assertNull("0 은 받지 않는다(지우는 길은 스테퍼뿐)", FoodPortion.parseTyped("0"))
        assertNull(FoodPortion.parseTyped(""))
        assertNull(FoodPortion.parseTyped("."))
        assertNull(FoodPortion.parseTyped("20.1"))
        assertNull(FoodPortion.parseTyped("abc"))
        assertNull(FoodPortion.parseTyped("100"))
    }

    @Test
    fun inputField_acceptsOnlyOneDecimalShapes() {
        listOf("", "1", "12", "1.", "1.5", "12.5", ".5", "0,3").forEach { assertTrue(it, FoodPortion.isTypable(it)) }
        listOf("123", "1.55", "1..", "a", "-1", "1.5.").forEach { assertFalse(it, FoodPortion.isTypable(it)) }
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
    fun storage_readsExactPortion_andTrustsQtyOnMismatch() {
        assertEquals("예전 기록", 2.0, FoodPortion.fromStored(2, null), 0.0)
        assertEquals(0.5, FoodPortion.fromStored(1, 0.5), 0.0)
        assertEquals(1.5, FoodPortion.fromStored(2, 1.5), 0.0)
        // portion 의 근사와 qty 가 어긋난 손상된 줄 — qty 를 믿는다(예전 앱은 portion 을 통째로 버려 정상 경로로는 생기지 않는다)
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
