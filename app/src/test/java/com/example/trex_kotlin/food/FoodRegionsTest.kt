package com.example.trex_kotlin.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 2단계 인식의 순수 계산부.
 *
 * 핵심은 실험 코드(`training/two_stage_eval/two_stage.py`)와 **같은 자리를 내는지**다 — 실험의 수치(검출 36→56%,
 * 그릇 약 90%)는 그 코드로 잰 것이라, 앱이 다르게 정리하면 그 수치를 약속할 수 없다.
 * 고정 데이터는 위치 모델의 실제 원출력(사진 두 장, 점수 0.05 이상 앵커만)과 파이썬 정리 결과다.
 */
class FoodRegionsTest {

    private class Fixture(
        val name: String,
        val width: Int,
        val height: Int,
        val letterbox: Letterbox,
        val anchors: Int,
        val output: FloatArray,
        val expected: List<PixelBox>,
    )

    private fun loadFixture(): List<Fixture> {
        val lines = javaClass.classLoader!!.getResourceAsStream("food_region_fixture.txt")!!
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val fixtures = ArrayList<Fixture>()
        var i = 0
        while (i < lines.size) {
            val h = lines[i].split(" ")
            check(h[0] == "photo") { "머리줄이 아니다: ${lines[i]}" }
            val width = h[2].toInt()
            val height = h[3].toInt()
            val letterbox = Letterbox(h[4].toInt(), h[5].toFloat(), h[6].toInt(), h[7].toInt())
            val anchors = h[8].toInt()
            val kept = h[9].toInt()
            val tidy = h[10].toInt()
            val channels = 13
            val output = FloatArray(channels * anchors)
            for (k in 1..kept) {
                val v = lines[i + k].split(" ")
                val a = v[1].toInt()
                for (c in 0 until channels) output[c * anchors + a] = v[2 + c].toFloat()
            }
            val expected = (1..tidy).map { k ->
                val v = lines[i + kept + k].split(" ").drop(1).map { it.toFloat() }
                PixelBox(v[0], v[1], v[2], v[3])
            }
            fixtures += Fixture(h[1], width, height, letterbox, anchors, output, expected)
            i += 1 + kept + tidy
        }
        return fixtures
    }

    @Test
    fun `실험 코드와 같은 자리를 낸다`() {
        val fixtures = loadFixture()
        assertEquals(2, fixtures.size)
        for (f in fixtures) {
            val hits = FoodRegions.decode(f.output, 13, f.anchors, f.letterbox, f.width, f.height)
            val tidy = FoodRegions.tidy(hits, f.width, f.height)
            val expected = f.expected.take(FoodRegions.MAX_REGIONS_PER_PHOTO)
            assertEquals("${f.name} 자리 수", expected.size, tidy.size)
            expected.zip(tidy).forEach { (want, got) ->
                // float32 계산 순서 차이만 허용한다(1픽셀 미만).
                listOf(want.left to got.box.left, want.top to got.box.top, want.right to got.box.right, want.bottom to got.box.bottom)
                    .forEach { (w, g) -> assertTrue("${f.name} 좌표 $want vs ${got.box}", abs(w - g) < 1f) }
            }
        }
    }

    @Test
    fun `letterbox 는 앱 전처리와 같은 여백을 계산한다`() {
        // 1400×1050 사진을 640 에 넣으면 가로가 맞고 세로 480 → 위아래 80 씩 회색.
        val lb = Letterbox.of(1400, 1050, 640)
        assertEquals(0, lb.padX)
        assertEquals(80, lb.padY)
        assertEquals(640f / 1400f, lb.scale, 1e-6f)
    }

    @Test
    fun `정규화 좌표 출력도 픽셀로 되돌린다`() {
        // 앵커 1개, 채널 5(좌표 4 + 클래스 1). 좌표가 0~1 이면 입력 크기를 곱해 해석한다.
        val lb = Letterbox(640, 1f, 0, 0)
        val out = floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f, 0.9f)
        val hits = FoodRegions.decode(out, 5, 1, lb, 640, 640)
        assertEquals(1, hits.size)
        assertEquals(PixelBox(240f, 240f, 400f, 400f), hits[0].box)
    }

    @Test
    fun `바닥값 미만 앵커는 버린다`() {
        val lb = Letterbox(640, 1f, 0, 0)
        val out = floatArrayOf(320f, 320f, 100f, 100f, 0.04f)
        assertTrue(FoodRegions.decode(out, 5, 1, lb, 640, 640).isEmpty())
    }

    @Test
    fun `식탁 전체를 덮는 박스는 버린다`() {
        val table = RegionHit(PixelBox(0f, 0f, 100f, 90f), 0.9f)
        val bowl = RegionHit(PixelBox(10f, 10f, 30f, 30f), 0.5f)
        assertEquals(listOf(bowl), FoodRegions.tidy(listOf(table, bowl), 100, 100))
    }

    @Test
    fun `그릇 안의 음식 박스는 그릇 하나로 합친다`() {
        val bowl = RegionHit(PixelBox(10f, 10f, 50f, 50f), 0.4f)
        // IoU 0.25 라 NMS 로는 안 걸리고, 포함 규칙(85% 이상 안에 있음)으로 걸러진다.
        val food = RegionHit(PixelBox(20f, 20f, 40f, 40f), 0.8f)
        val side = RegionHit(PixelBox(60f, 60f, 80f, 80f), 0.3f)
        assertEquals(listOf(bowl, side), FoodRegions.tidy(listOf(bowl, food, side), 100, 100))
    }

    @Test
    fun `겹치는 박스는 점수 높은 쪽만 남긴다`() {
        val a = RegionHit(PixelBox(10f, 10f, 50f, 50f), 0.9f)
        val b = RegionHit(PixelBox(12f, 12f, 52f, 52f), 0.5f)
        assertEquals(listOf(a), FoodRegions.tidy(listOf(b, a), 100, 100))
    }

    @Test
    fun `자른 자리는 입력의 30퍼센트 면적으로 가운데에 놓인다`() {
        val dest = FoodRegions.cropPlacement(PixelBox(100f, 200f, 300f, 300f), 640)
        val longest = maxOf(dest.width, dest.height)
        assertEquals(640 * sqrt(0.30f), longest, 0.01f)
        assertEquals(dest.width / 2, dest.height, 0.01f) // 가로세로 비율 유지(200×100)
        assertEquals(320f, (dest.left + dest.right) / 2, 0.01f)
        assertEquals(320f, (dest.top + dest.bottom) / 2, 0.01f)
    }

    private fun region(id: Int, photo: Int, vararg top: Pair<String, Float>) =
        FoodRegion(id, photo, PixelBox(0f, 0f, 0.1f, 0.1f), 0.5f, top.toList())

    @Test
    fun `임계값 미만 자리는 이름이 없다`() {
        assertNull(region(0, 0, "쌀밥" to 0.39f).name)
        assertEquals("쌀밥", region(0, 0, "쌀밥" to 0.40f).name)
    }

    @Test
    fun `같은 이름은 한 줄로, 최고 점수 자리의 사진을 따른다`() {
        val (foods, _) = listOf(
            region(0, 0, "쌀밥" to 0.6f),
            region(1, 1, "쌀밥" to 0.87f),
            region(2, 1, "미소된장국" to 0.7f),
        ).toResultLists()
        assertEquals(listOf("쌀밥", "미소된장국"), foods.map { it.name })
        assertEquals(1, foods[0].photoIndex)
        assertEquals(0.87f, foods[0].confidence)
    }

    @Test
    fun `후보는 이름 붙은 음식을 빼고 점수순이다`() {
        val (_, candidates) = listOf(
            region(0, 0, "돈가스" to 0.87f, "고로케" to 0.2f),
            region(1, 0, "단무지무침" to 0.1f, "돈가스" to 0.05f, "깍두기" to 0.3f),
        ).toResultLists()
        assertEquals(listOf("깍두기", "고로케", "단무지무침"), candidates.map { it.name })
    }

    @Test
    fun `이름 붙은 자리가 없는 사진만 전체 1회로 다시 본다`() {
        // 0번 사진은 이름이 붙었고, 1번은 "?" 만, 2번은 자리를 하나도 못 찾았다.
        val regions = listOf(region(0, 0, "쌀밥" to 0.8f), region(1, 1, "쌀밥" to 0.2f))
        assertEquals(listOf(1, 2), regions.photosWithoutNames(3))
    }

    @Test
    fun `전체 1회로 본 사진의 음식도 결과와 후보에 들어간다`() {
        val regions = listOf(region(0, 0, "쌀밥" to 0.8f, "잡곡밥" to 0.1f))
        val fallback = listOf(
            DetectedFood("피자", 0.7f, 1),      // 임계 이상 → 결과
            DetectedFood("쌀밥", 0.9f, 1),      // 이미 결과에 있는 이름 → 최고 점수로 한 줄
            DetectedFood("양념치킨", 0.3f, 1),  // 임계 미만 → 후보
        )
        val (foods, candidates) = regions.toResultLists(fallback)
        assertEquals(listOf("쌀밥", "피자"), foods.map { it.name })
        assertEquals(1, foods[0].photoIndex)
        assertEquals(listOf("양념치킨", "잡곡밥"), candidates.map { it.name })
    }

    @Test
    fun `이름이 하나도 안 붙어도 결과는 빈 목록이지 실패가 아니다`() {
        val (foods, candidates) = listOf(region(0, 0, "쌀밥" to 0.2f)).toResultLists()
        assertTrue(foods.isEmpty())
        assertEquals(listOf("쌀밥"), candidates.map { it.name })
    }
}
