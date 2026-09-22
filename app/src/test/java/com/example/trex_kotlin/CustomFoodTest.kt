package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사용자가 직접 등록한 음식의 검색·우선순위 규칙.
 *
 * AppViewModel 은 Android Application 을 요구해 JVM 테스트에서 만들 수 없으므로, 같은 규칙을
 * 순수 함수로 재현해 검증한다. 규칙이 바뀌면 [AppViewModel.searchFoods]·[AppViewModel.findFood] 와
 * 이 테스트를 함께 고친다.
 */
class CustomFoodTest {

    private fun search(custom: Map<String, Nutrition>, query: String): List<Triple<String, Nutrition, Boolean>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val mine = custom.filterKeys { it.contains(q) }.map { (n, v) -> Triple(n, v, true) }
        val base = foodDatabase.filterKeys { it.contains(q) && it !in custom }.map { (n, v) -> Triple(n, v, false) }
        return mine + base
    }

    private fun find(custom: Map<String, Nutrition>, name: String): Nutrition? = custom[name] ?: foodDatabase[name]

    private val myRamen = Nutrition(700, 90.0, 15.0, 25.0)

    @Test
    fun `등록한 음식이 검색 결과 앞에 온다`() {
        val result = search(mapOf("엄마표 김치찌개" to myRamen), "김치")
        assertTrue("등록 음식이 하나도 없다", result.isNotEmpty())
        assertEquals("엄마표 김치찌개", result.first().first)
        assertTrue("사용자 등록 표시가 없다", result.first().third)
    }

    @Test
    fun `같은 이름이면 사용자 값이 기본 DB 를 덮는다`() {
        val name = foodDatabase.keys.first()
        val custom = mapOf(name to myRamen)
        assertEquals(myRamen, find(custom, name))
        // 검색 결과에도 기본 DB 쪽이 중복으로 끼지 않는다
        assertEquals(1, search(custom, name).count { it.first == name })
    }

    @Test
    fun `등록하지 않은 이름은 기본 DB 에서 찾는다`() {
        val name = foodDatabase.keys.first()
        assertEquals(foodDatabase[name], find(emptyMap(), name))
    }

    @Test
    fun `어디에도 없는 이름은 null 이다`() {
        assertNull(find(emptyMap(), "존재하지 않는 음식 이름 12345"))
    }

    @Test
    fun `빈 검색어는 결과가 없다`() {
        assertTrue(search(mapOf("내 음식" to myRamen), "   ").isEmpty())
    }
}
