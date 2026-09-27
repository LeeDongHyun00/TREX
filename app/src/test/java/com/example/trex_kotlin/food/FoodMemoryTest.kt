package com.example.trex_kotlin.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 음식 기억의 순수 계산부 — 비교·개수 제한·파일 형식·자동 이름 조건. */
class FoodMemoryTest {

    private fun v(vararg x: Float) = x

    @Test
    fun `이름별 최고 유사도로 줄 세운다`() {
        val memory = FoodMemory(
            listOf(
                MemoryEntry("피자", v(1f, 0f, 0f), 1),
                MemoryEntry("피자", v(0.6f, 0.8f, 0f), 2),
                MemoryEntry("라멘", v(0f, 1f, 0f), 3),
            ),
        )
        val m = memory.match(v(0.6f, 0.8f, 0f))
        assertEquals(listOf("피자", "라멘"), m.map { it.first })
        assertEquals(1f, m[0].second, 1e-5f)
        assertEquals(0.8f, m[1].second, 1e-5f)
    }

    @Test
    fun `길이가 다른 옛 기억은 비교하지 않는다`() {
        val memory = FoodMemory(listOf(MemoryEntry("옛날", v(1f, 0f), 1), MemoryEntry("지금", v(1f, 0f, 0f), 2)))
        assertEquals(listOf("지금"), memory.match(v(1f, 0f, 0f)).filter { it.second > 0f }.map { it.first })
    }

    @Test
    fun `이름마다 최근 5장, 전체 300장까지만 남긴다`() {
        var memory = FoodMemory()
        repeat(7) { memory = memory.plus(MemoryEntry("가라아게", v(it.toFloat(), 1f), it.toLong())) }
        assertEquals(FoodMemory.PER_NAME, memory.entries.size)
        assertEquals((2L..6L).toList(), memory.entries.map { it.savedAt })
        repeat(400) { memory = memory.plus(MemoryEntry("음식$it", v(1f, 1f), 1000L + it)) }
        assertEquals(FoodMemory.TOTAL, memory.entries.size)
        assertEquals(1399L, memory.entries.last().savedAt)
    }

    @Test
    fun `파일 형식으로 저장했다 읽으면 같다`() {
        val memory = FoodMemory(
            listOf(MemoryEntry("크림 파스타", v(0.1f, -0.2f, 0.3f), 11), MemoryEntry("규동", v(1f, 2f, 3f), 12)),
        )
        assertEquals(memory.entries, FoodMemory.decode(memory.encode()).entries)
    }

    @Test
    fun `깨진 줄은 건너뛴다`() {
        val good = FoodMemory(listOf(MemoryEntry("라멘", v(1f, 0f), 5))).encode()
        val decoded = FoodMemory.decode("망가진 줄\n$good\n이름\t1\t!!!base64아님")
        assertEquals(listOf("라멘"), decoded.entries.map { it.name })
    }

    @Test
    fun `이름의 탭과 줄바꿈은 공백이 된다`() {
        assertEquals("치즈 돈가스", FoodMemory.clean("치즈\t돈가스\n"))
    }

    private fun region(name: Pair<String, Float>, vararg remembered: Pair<String, Float>) =
        FoodRegion(0, 0, PixelBox(0f, 0f, 0.1f, 0.1f), 0.5f, listOf(name), remembered.toList())

    @Test
    fun `매우 비슷할 때만 기억 이름을 자동으로 붙인다`() {
        assertEquals("피자", region("쌀밥" to 0.1f, "피자" to 0.80f).rememberedName)
        assertNull(region("쌀밥" to 0.1f, "피자" to 0.70f).rememberedName)
    }

    @Test
    fun `기억 이름이 붙은 사진은 전체 1회로 다시 보지 않는다`() {
        // 다시 보면 같은 음식이 박스 없는 줄로 한 번 더 나와 한 끼가 두 번 기록된다.
        val regions = listOf(region("쌀밥" to 0.1f, "엄마표 된장찌개" to 0.82f))
        assertTrue(regions.photosWithoutNames(1).isEmpty())
        assertEquals(listOf(0), listOf(region("쌀밥" to 0.1f, "피자" to 0.5f)).photosWithoutNames(1))
    }

    @Test
    fun `고침 기억은 모델이 같은 이름을 붙였을 때만 견준다`() {
        val memory = FoodMemory(
            listOf(
                MemoryEntry("생강", v(1f, 0f), 1, correctedFrom = "연어초밥"),
                MemoryEntry("피자", v(1f, 0f), 2),
            ),
        )
        // 모델이 "연어초밥" 이라고 한 자리 → 그 이름을 고친 기억(생강)만 본다. 피자는 모델이 맞게 본 음식을 덮을 수 있어 보지 않는다.
        assertEquals(listOf("생강"), memory.match(v(1f, 0f), correctedFrom = "연어초밥").map { it.first })
        assertTrue(memory.match(v(1f, 0f), correctedFrom = "돈가스").isEmpty())
        // 이름 없는 자리는 모든 기억과 견준다.
        assertEquals(setOf("생강", "피자"), memory.match(v(1f, 0f)).map { it.first }.toSet())
        assertTrue(memory.hasCorrectionOf("연어초밥"))
    }

    @Test
    fun `고침 기억은 모델 이름을 바꾸고 모델 결과에서 빠진다`() {
        val fixed = region("연어초밥" to 0.72f, "생강" to 0.85f)
        assertEquals("생강", fixed.rememberedName)
        val (foods, _) = listOf(fixed).toResultLists()
        assertTrue(foods.isEmpty())
        // 기억이 모델과 같은 이름이면 바꿀 것이 없다.
        assertNull(region("쌀밥" to 0.87f, "쌀밥" to 0.95f).rememberedName)
        assertTrue(FoodMemory.SUGGEST_AT < FoodMemory.AUTO_NAME_AT)
    }

    @Test
    fun `고침 기억도 파일로 저장했다 읽으면 같고, 세 칸짜리 옛 줄도 읽는다`() {
        val memory = FoodMemory(listOf(MemoryEntry("생강", v(0.5f, 0.5f), 7, correctedFrom = "연어초밥")))
        assertEquals(memory.entries, FoodMemory.decode(memory.encode()).entries)
        val old = FoodMemory(listOf(MemoryEntry("라멘", v(1f, 0f), 1))).encode()
        assertNull(FoodMemory.decode(old).entries.single().correctedFrom)
    }
}
