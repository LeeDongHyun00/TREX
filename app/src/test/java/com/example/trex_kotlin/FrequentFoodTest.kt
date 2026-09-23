package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자주 먹는 음식 집계 규칙.
 *
 * AppViewModel 은 Android Application 을 요구해 JVM 테스트에서 만들 수 없으므로, 같은 규칙을
 * 순수 함수로 재현해 검증한다. 규칙이 바뀌면 [AppViewModel.frequentFoods] 와 이 테스트를 함께 고친다.
 */
class FrequentFoodTest {

    /** [AppViewModel.frequentFoods] 와 같은 규칙. custom 은 내 음식, 나머지는 foodDatabase 에서 찾는다. */
    private fun frequent(
        diet: Map<Long, Map<String, List<FoodEntry>>>,
        custom: Map<String, Nutrition> = emptyMap(),
        limit: Int = 8,
    ): List<Pair<String, Nutrition>> =
        diet.values
            .flatMap { slots -> slots.values.flatten() }
            .groupingBy { it.name }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { (name, _) -> (custom[name] ?: foodDatabase[name])?.let { name to it } }
            .take(limit)

    private val any = Nutrition(100, 1.0, 1.0, 1.0)

    private fun day(vararg names: String): Map<String, List<FoodEntry>> =
        mapOf("lunch" to names.map { FoodEntry(it, foodDatabase[it] ?: any) })

    /** foodDatabase 에 실제로 있는 이름을 쓴다 — 없는 이름은 규칙상 결과에서 빠지기 때문이다. */
    private val real = foodDatabase.keys.take(3).toList()

    @Test
    fun `많이 담은 순으로 나온다`() {
        val a = real[0]
        val b = real[1]
        val diet = mapOf(
            1L to day(a, b),
            2L to day(a),
            3L to day(a),
        )
        val result = frequent(diet).map { it.first }
        assertEquals("가장 많이 담은 음식이 앞이 아니다", a, result.first())
        assertTrue("두 번째 음식이 빠졌다", b in result)
    }

    @Test
    fun `같은 횟수면 이름순으로 안정적이다`() {
        val names = real.take(2).sorted()
        val diet = mapOf(1L to day(names[0], names[1]))
        assertEquals(names, frequent(diet).map { it.first })
    }

    @Test
    fun `영양값을 찾을 수 없는 이름은 뺀다`() {
        // 기록한 뒤 내 음식에서 지운 경우. 그대로 내놓으면 눌러도 담기지 않는 항목이 된다.
        val gone = "지워진 내 음식 ${System.nanoTime()}"
        val diet = mapOf(1L to day(gone, gone, real[0]))
        val result = frequent(diet).map { it.first }
        assertTrue("사라진 음식이 그대로 남았다", gone !in result)
        assertEquals(listOf(real[0]), result)
    }

    @Test
    fun `내 음식도 집계에 들어간다`() {
        val mine = "엄마표 김치찌개"
        val diet = mapOf(1L to day(mine, mine))
        val result = frequent(diet, custom = mapOf(mine to any))
        assertEquals(mine, result.first().first)
        assertEquals(any, result.first().second)
    }

    @Test
    fun `limit 을 넘지 않는다`() {
        val diet = foodDatabase.keys.take(10).mapIndexed { i, n -> i.toLong() to day(n) }.toMap()
        assertEquals(3, frequent(diet, limit = 3).size)
    }

    @Test
    fun `기록이 없으면 빈 목록이다`() {
        assertTrue(frequent(emptyMap()).isEmpty())
    }

    @Test
    fun `수량이 아니라 담은 횟수로 센다`() {
        // 3인분 한 번보다 1인분 두 번이 더 "자주" 먹은 것이다.
        val once = real[0]
        val twice = real[1]
        val diet = mapOf(
            1L to mapOf("lunch" to listOf(FoodEntry(once, foodDatabase.getValue(once), qty = 3))),
            2L to mapOf("lunch" to listOf(FoodEntry(twice, foodDatabase.getValue(twice)))),
            3L to mapOf("lunch" to listOf(FoodEntry(twice, foodDatabase.getValue(twice)))),
        )
        assertEquals(twice, frequent(diet).first().first)
    }
}
