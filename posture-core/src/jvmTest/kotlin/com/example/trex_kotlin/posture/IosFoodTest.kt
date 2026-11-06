package com.example.trex_kotlin.posture

import com.example.trex_kotlin.food.FoodMemory
import org.junit.Assert.*
import org.junit.Test

/** iOS 사진 식단 경계 — 이름 문턱·후보·기억 정책이 Android 와 같은 공통 코드에서 나온다. */
class IosFoodTest {
    private fun region(id: Int, photo: Int, top: List<Pair<String, Float>>, remembered: List<Pair<String, Float>> = emptyList()) =
        mapOf("id" to id, "photoIndex" to photo, "box" to mapOf("left" to 0.1, "top" to 0.1, "right" to 0.4, "bottom" to 0.4), "regionScore" to 0.8,
            "top" to top.map { listOf(it.first, it.second) }, "remembered" to remembered.map { listOf(it.first, it.second) })

    @Test fun namedRegionsBecomeFoodsAndLowScoresBecomeCandidates() {
        val food = IosFood()
        val regions = Json.encode(listOf(region(0, 0, listOf("김치찌개" to 0.9f, "된장찌개" to 0.3f)), region(1, 0, listOf("쌀밥" to 0.2f, "잡곡밥" to 0.1f))))
        val r = JSONObject(food.resultLists(regions, "[]"))
        val foods = r.getJSONArray("foods"); assertEquals(1, foods.length()); assertEquals("김치찌개", foods.getJSONObject(0).getString("name"))
        val names = r.getJSONArray("candidates").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(listOf("된장찌개", "쌀밥", "잡곡밥"), names)
        assertEquals("[]", food.photosWithoutNames(regions, 1))
        assertEquals("[1]", food.photosWithoutNames(regions, 2))
    }
    @Test fun rememberedNameOverridesModelAndIsNotListedAsModelFood() {
        val food = IosFood()
        val regions = Json.encode(listOf(region(0, 0, listOf("김치찌개" to 0.9f), remembered = listOf("집된장찌개" to 0.9f))))
        val r = JSONObject(food.resultLists(regions, "[]"))
        assertEquals(0, r.getJSONArray("foods").length())
        assertEquals("집된장찌개", r.getJSONArray("regions").getJSONObject(0).getString("rememberedName"))
    }
    @Test fun wholePhotoFallbackUsesSameThresholdsAsAndroid() {
        val r = JSONObject(IosFood().wholeLists(Json.encode(listOf(mapOf("name" to "쌀밥", "confidence" to 0.5, "photoIndex" to 0), mapOf("name" to "쌀밥", "confidence" to 0.7, "photoIndex" to 1),
            mapOf("name" to "김", "confidence" to 0.05, "photoIndex" to 0), mapOf("name" to "계란말이", "confidence" to 0.2, "photoIndex" to 0)))))
        assertEquals(1, r.getJSONArray("foods").length()); assertEquals(0.7, r.getJSONArray("foods").getJSONObject(0).getDouble("confidence"), 1e-6)
        assertEquals(listOf("계란말이"), r.getJSONArray("candidates").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } })
    }
    @Test fun memoryRoundTripsThroughAndroidTextFormatAndFiltersSuggestions() {
        val m = IosFoodMemory()
        val v = Json.encode(List(8) { if (it == 0) 1.0 else 0.0 })
        assertTrue(m.plus("집된장찌개", v, 1000, correctedFrom = "김치찌개"))
        assertFalse(m.plus("x", "[1, null]", 1000, null))
        val reloaded = IosFoodMemory(m.encode())
        assertEquals(1, reloaded.size); assertTrue(reloaded.hasCorrectionOf("김치찌개")); assertFalse(reloaded.hasCorrectionOf("쌀밥"))
        val hit = JSONObject("{\"a\":" + reloaded.match(v, "김치찌개") + "}").getJSONArray("a")
        assertEquals(1, hit.length()); assertEquals("집된장찌개", (hit.values()[0] as List<*>)[0])
        assertEquals("[]", reloaded.match(Json.encode(List(8) { if (it == 1) 1.0 else 0.0 }), null))   // 직교 → 0 < 0.45
        assertEquals(FoodMemory.SUGGEST_AT, 0.45f, 0f)
    }
}
