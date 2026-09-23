package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자주 먹는 음식 집계 규칙.
 *
 * AppViewModel 은 Android Application 을 요구해 JVM 테스트에서 만들 수 없다. 그래서 규칙을
 * 복제하는 대신 [frequentFoodsOf] 를 밖으로 빼 두고 **프로덕션과 같은 함수를 부른다** —
 * 복제본은 어긋난다(CustomFoodTest 의 복제본이 실제로 별칭 분기를 놓쳤다).
 *
 * 이름 해석만 [AppViewModel.findFood] 와 같은 순서로 재현한다.
 */
class FrequentFoodTest {

    /** [AppViewModel.findFood] 와 같은 순서: 내 음식 → 기본 DB → 별칭. */
    private fun resolve(custom: Map<String, Nutrition>): (String) -> Nutrition? = { name ->
        custom[name] ?: foodDatabase[name] ?: foodNameAliases[name]?.let { foodDatabase[it] }
    }

    private fun frequent(
        diet: Map<Long, Map<String, List<FoodEntry>>>,
        custom: Map<String, Nutrition> = emptyMap(),
        limit: Int = 8,
    ): List<Pair<String, Nutrition>> = frequentFoodsOf(diet, limit, resolve(custom))

    private val any = Nutrition(100, 1.0, 1.0, 1.0)

    /** 하루치 기록. 같은 이름이 두 번 오지 않게 끼니를 나눈다 — appendFoods 가 늘 합치기 때문이다. */
    private fun days(vararg namesPerDay: List<String>): Map<Long, Map<String, List<FoodEntry>>> =
        namesPerDay.mapIndexed { d, names ->
            d.toLong() to names.mapIndexed { i, n ->
                "slot$i" to listOf(FoodEntry(n, foodDatabase[n] ?: any))
            }.toMap()
        }.toMap()

    /** foodDatabase 에 실제로 있는 이름을 쓴다 — 없는 이름은 규칙상 결과에서 빠지기 때문이다. */
    private val real = foodDatabase.keys.take(3).toList()

    @Test
    fun `많이 담은 순으로 나온다`() {
        val a = real[0]
        val b = real[1]
        val result = frequent(days(listOf(a, b), listOf(a), listOf(a))).map { it.first }
        assertEquals("가장 많이 담은 음식이 앞이 아니다", a, result.first())
        assertTrue("두 번째 음식이 빠졌다", b in result)
    }

    @Test
    fun `같은 횟수면 이름순으로 안정적이다`() {
        val names = real.take(2).sorted()
        assertEquals(names, frequent(days(names)).map { it.first })
    }

    @Test
    fun `영양값을 찾을 수 없는 이름은 뺀다`() {
        // 기록한 뒤 내 음식에서 지운 경우. 그대로 내놓으면 눌러도 담기지 않는 항목이 된다.
        val gone = "지워진 내 음식 ${System.nanoTime()}"
        val result = frequent(days(listOf(gone, real[0]), listOf(gone))).map { it.first }
        assertTrue("사라진 음식이 그대로 남았다", gone !in result)
        assertEquals(listOf(real[0]), result)
    }

    @Test
    fun `별칭으로 기록된 이름도 살아남는다`() {
        // findFood 가 별칭을 타고 영양값을 찾으므로 집계에서도 빠지면 안 된다.
        val alias = foodNameAliases.keys.first { foodDatabase.containsKey(foodNameAliases.getValue(it)) }
        val result = frequent(days(listOf(alias))).map { it.first }
        assertEquals("별칭 이름이 집계에서 사라졌다", listOf(alias), result)
    }

    @Test
    fun `내 음식도 집계에 들어간다`() {
        val mine = "엄마표 김치찌개"
        val result = frequent(days(listOf(mine), listOf(mine)), custom = mapOf(mine to any))
        assertEquals(mine, result.first().first)
        assertEquals(any, result.first().second)
    }

    @Test
    fun `limit 을 넘지 않는다`() {
        assertEquals(3, frequent(days(*foodDatabase.keys.take(10).map { listOf(it) }.toTypedArray()), limit = 3).size)
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
            0L to mapOf("slot0" to listOf(FoodEntry(once, foodDatabase.getValue(once), qty = 3))),
            1L to mapOf("slot0" to listOf(FoodEntry(twice, foodDatabase.getValue(twice)))),
            2L to mapOf("slot0" to listOf(FoodEntry(twice, foodDatabase.getValue(twice)))),
        )
        assertEquals(twice, frequent(diet).first().first)
    }
}
