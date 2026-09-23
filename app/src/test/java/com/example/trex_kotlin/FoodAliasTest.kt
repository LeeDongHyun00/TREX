package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 별칭 표가 실제로 검색을 살리는지 본다.
 *
 * 실사용 사진 16장 대조에서 "흰쌀밥"을 쳐도 "쌀밥"이 안 나오는 문제가 드러났다
 * (docs/FOOD_COVERAGE_FINDINGS.md). AppViewModel 은 JVM 테스트에서 만들 수 없어
 * 같은 규칙을 재현해 검증한다 — 규칙이 바뀌면 [AppViewModel.searchFoods] 와 함께 고친다.
 */
class FoodAliasTest {

    private fun search(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val viaAlias = foodNameAliases.filterKeys { it.contains(q) }.values.toSet()
        return foodDatabase.keys.filter { it.contains(q) || it in viaAlias }
    }

    @Test
    fun `별칭이 가리키는 음식이 모두 실재한다`() {
        val dangling = foodNameAliases.filterValues { it !in foodDatabase }
        assertEquals("가리키는 음식이 foodDatabase 에 없다 — 검색해도 아무것도 안 나온다: $dangling", emptyMap<String, String>(), dangling)
    }

    @Test
    fun `별칭 자체가 실제 음식 이름과 겹치지 않는다`() {
        // 겹치면 별칭이 원래 음식을 가려 엉뚱한 결과를 준다
        val shadowed = foodNameAliases.keys.filter { it in foodDatabase }
        assertEquals("별칭 이름이 실제 음식과 겹친다: $shadowed", emptyList<String>(), shadowed)
    }

    @Test
    fun `흰쌀밥으로 쌀밥을 찾는다`() {
        assertTrue("흰쌀밥 검색에 쌀밥이 없다", search("흰쌀밥").contains("쌀밥"))
    }

    @Test
    fun `돈까스와 계란국도 찾는다`() {
        assertTrue(search("돈까스").contains("돈가스"))
        assertTrue(search("계란국").contains("달걀국"))
    }

    @Test
    fun `별칭이 없어도 부분일치는 그대로 동작한다`() {
        assertTrue("부분일치가 깨졌다", search("김치").isNotEmpty())
    }
}
