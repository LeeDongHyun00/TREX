package com.example.trex_kotlin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱이 싣고 있는 음식 인식 모델의 라벨과 [foodDatabase] 가 어긋나지 않는지 본다.
 *
 * 2026-09-19 에 실제로 어긋난 적이 있다 — 새 모델(214클래스)에 맞춰 DB 를 교체했는데 assets 에는
 * 아직 구 모델(30클래스)이 들어 있어서, 인식 결과 30종 중 21종이 "영양 정보 없음"으로 떨어졌다.
 * 컴파일도 기존 테스트도 이걸 잡지 못했으므로 여기서 막는다.
 */
class FoodLabelDatabaseTest {

    private val labels: List<String> =
        File("src/main/assets/models/food_labels.txt")
            .readLines()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() && !it.startsWith("#") } // '#' 은 음식이 아닌 클래스

    @Test
    fun `라벨 파일이 비어 있지 않다`() {
        assertTrue("food_labels.txt 를 읽지 못했거나 유효한 라벨이 없다", labels.isNotEmpty())
    }

    @Test
    fun `모델 라벨은 모두 영양 정보를 가진다`() {
        val missing = labels.filterNot { foodDatabase.containsKey(it) }
        assertEquals(
            "라벨은 있는데 foodDatabase 에 없는 음식이 있다 — 인식돼도 영양값 없이 기록에서 제외된다. " +
                "모델·라벨·DB 는 함께 갱신한다(assets/models/README.md): $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `라벨에 중복이 없다`() {
        val dupes = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("같은 라벨이 여러 줄에 있다 — 클래스 인덱스가 밀린다: $dupes", emptySet<String>(), dupes)
    }

    @Test
    fun `추정값 목록은 실제 DB 항목만 가리킨다`() {
        val unknown = approximateNutritionNames.filterNot { foodDatabase.containsKey(it) }
        assertEquals(
            "approximateNutritionNames 에 foodDatabase 에 없는 이름이 있다 — 고지 분기가 헛돈다: $unknown",
            emptyList<String>(),
            unknown,
        )
    }
}
