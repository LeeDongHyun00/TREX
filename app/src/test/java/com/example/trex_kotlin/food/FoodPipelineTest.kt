package com.example.trex_kotlin.food

import androidx.compose.ui.graphics.ImageBitmap
import com.example.trex_kotlin.FoodPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사진 식단의 판정 흐름(§108d `FoodPipeline` — 전에는 Android `FoodDetector` 안에 있었고, 이제 iPhone 도 같은 코드를 돈다)을
 * 가짜 모델로 고정한다: 모델 없음·깨진 모델, 전체 사진 1회의 합치기·임계·바닥·개수, 2단계의 자리 이름·이름 없는 사진의 전체 1회,
 * 내 음식 기억(비었으면 특징값을 계산하지 않는다), 위치 모델 실패 → 전체 1회.
 */
class FoodPipelineTest {

    private class FakePhoto(override val width: Int = 640, override val height: Int = 640) : FoodPhoto {
        override val image: ImageBitmap get() = error("판정 흐름은 화면 그림을 쓰지 않는다")
        override fun scaledToMax(maxDimension: Int): FoodPhoto = this
        override fun crop(left: Int, top: Int, width: Int, height: Int): FoodPhoto = this
        override fun jpeg(maxDimension: Int, quality: Int): ByteArray = ByteArray(0)
    }

    private val labels = listOf("#배경", "김치", "밥", "된장국", "콩나물", "현미밥", "깍두기")
    private fun scores(vararg named: Pair<String, Float>) = FloatArray(labels.size).also { s -> named.forEach { (n, v) -> s[labels.indexOf(n)] = v } }

    private class FakeModels(
        private val labels: List<String>?,
        private val regions: Map<FoodPhoto, List<Pair<PixelBox, Float>>>? = null,
        private val crops: (FoodPhoto, PixelBox) -> FloatArray = { _, _ -> error("자리 점수 없음") },
        private val whole: (FoodPhoto) -> FloatArray = { error("전체 점수 없음") },
        private val embedding: ((FoodPhoto, PixelBox) -> FloatArray)? = null,
        private val brokenClassifier: Boolean = false,
        private val brokenRegions: Boolean = false,
    ) : FoodModels {
        val wholeCalls = ArrayList<FoodPhoto>()
        val cropCalls = ArrayList<PixelBox>()
        val embedCalls = ArrayList<PixelBox>()
        override fun classifier(): List<String>? { if (brokenClassifier) error("깨진 모델"); return labels }
        override fun regionModel(): Boolean = regions != null
        override fun embedModel(): Boolean = embedding != null
        /** 앵커마다 (cx, cy, w, h, 점수) — 입력 640, 사진 640×640 이라 letterbox 가 그대로다. */
        override fun regionOutput(photo: FoodPhoto): RegionOutput {
            if (brokenRegions) error("위치 모델 실패")
            val boxes = regions!![photo].orEmpty()
            val anchors = maxOf(1, boxes.size)
            val flat = FloatArray(5 * anchors)
            boxes.forEachIndexed { a, (b, s) ->
                flat[a] = (b.left + b.right) / 2; flat[anchors + a] = (b.top + b.bottom) / 2
                flat[2 * anchors + a] = b.width; flat[3 * anchors + a] = b.height; flat[4 * anchors + a] = s
            }
            return RegionOutput(flat, 5, anchors, 640)
        }
        override fun classScores(photo: FoodPhoto, crop: PixelBox?): FloatArray =
            if (crop == null) { wholeCalls += photo; whole(photo) } else { cropCalls += crop; crops(photo, crop) }
        override fun embed(photo: FoodPhoto, box: PixelBox): FloatArray { embedCalls += box; return embedding!!(photo, box) }
    }

    private val silent = object : FoodLog {
        override fun d(message: String) {}
        override fun w(message: String, error: Throwable?) {}
        override fun e(message: String, error: Throwable?) {}
    }

    private fun pipeline(models: FoodModels, memory: FoodMemory = FoodMemory()) = FoodPipeline(models, { memory }, silent, clock = { 0L })

    private fun FoodDetectionResult.success() = this as FoodDetectionResult.Success

    @Test
    fun missingOrBrokenModelIsReportedNotGuessed() {
        val photo = FakePhoto()
        assertSame(FoodDetectionResult.ModelMissing, pipeline(FakeModels(labels = null)).detect(listOf(photo)))
        assertSame(FoodDetectionResult.Error, pipeline(FakeModels(labels, brokenClassifier = true)).detect(listOf(photo)))
        assertSame(FoodDetectionResult.Error, pipeline(FakeModels(labels)).detect(emptyList()))
    }

    @Test
    fun wholePhotoPathKeepsBestPerNameAndSplitsByThreshold() {
        val p0 = FakePhoto(); val p1 = FakePhoto()
        val models = FakeModels(labels, whole = { p ->
            if (p === p0) scores("#배경" to 0.99f, "김치" to 0.9f, "밥" to 0.3f)
            else scores("김치" to 0.95f, "된장국" to 0.5f, "콩나물" to 0.05f)
        })
        val r = pipeline(models).detect(listOf(p0, p1)).success()
        // 같은 이름은 최고 점수와 그 사진, 0.40 이상만 결과
        assertEquals(listOf(DetectedFood("김치", 0.95f, 1), DetectedFood("된장국", 0.5f, 1)), r.foods)
        // 임계 미만은 후보로만 — 바닥(0.10) 아래와 '#' 라벨은 어디에도 없다
        assertEquals(listOf(DetectedFood("밥", 0.3f, 0)), r.candidates)
        assertTrue(r.regions.isEmpty())
    }

    @Test
    fun wholePhotoPathCapsFoodsAndCandidates() {
        val many = (1..20).map { "음식$it" }
        val models = FakeModels(many, whole = { FloatArray(20) { i -> if (i < 7) 0.9f - i * 0.01f else 0.39f - (i - 7) * 0.01f } })
        val r = pipeline(models).detect(listOf(FakePhoto())).success()
        assertEquals(FoodPipeline.MAX_FOODS_PER_ANALYSIS, r.foods.size)
        assertEquals(FoodPipeline.MAX_CANDIDATES, r.candidates.size)
        assertTrue("결과에 든 이름은 후보에 없다", r.candidates.none { c -> r.foods.any { it.name == c.name } })
    }

    @Test
    fun twoStageNamesSpotsAndSeesUnnamedPhotosWhole() {
        val p0 = FakePhoto(); val p1 = FakePhoto()
        val box = PixelBox(150f, 150f, 250f, 250f)
        val models = FakeModels(
            labels,
            regions = mapOf(p0 to listOf(box to 0.9f), p1 to emptyList()),
            crops = { _, _ -> scores("김치" to 0.8f, "밥" to 0.1f, "된장국" to 0.05f) },
            whole = { scores("된장국" to 0.6f, "콩나물" to 0.2f) },
        )
        val r = pipeline(models).detect(listOf(p0, p1)).success()
        assertEquals(listOf(box), models.cropCalls)
        assertEquals("이름이 붙은 자리가 없는 사진만 전체 1회", listOf(p1), models.wholeCalls)
        val region = r.regions.single()
        assertEquals(0, region.photoIndex)
        assertEquals(PixelBox(150f / 640, 150f / 640, 250f / 640, 250f / 640), region.box)
        assertEquals(0.9f, region.regionScore)
        assertEquals(listOf("김치" to 0.8f, "밥" to 0.1f, "된장국" to 0.05f), region.top)
        assertEquals(listOf(DetectedFood("김치", 0.8f, 0), DetectedFood("된장국", 0.6f, 1)), r.foods)
        assertEquals(listOf(DetectedFood("콩나물", 0.2f, 1), DetectedFood("밥", 0.1f, 0)), r.candidates)
        assertTrue("기억이 비었으면 특징값을 계산하지 않는다", models.embedCalls.isEmpty())
    }

    @Test
    fun memoryNamesAnUnnamedSpotButNotAnEmptyOne() {
        val p0 = FakePhoto()
        val dish = PixelBox(100f, 100f, 200f, 200f)
        val cup = PixelBox(400f, 400f, 450f, 450f)
        val models = FakeModels(
            labels,
            regions = mapOf(p0 to listOf(dish to 0.9f, cup to 0.8f)),
            crops = { _, b -> if (b == dish) scores("밥" to 0.3f, "현미밥" to 0.2f) else scores("밥" to 0.01f) },
            embedding = { _, _ -> floatArrayOf(1f, 0.05f, 0f) },
        )
        val memory = FoodMemory(listOf(MemoryEntry("현미밥", floatArrayOf(1f, 0f, 0f), 1L)))
        val r = pipeline(models, memory).detect(listOf(p0)).success()
        val named = r.regions.first { it.top.first().first == "밥" && it.confidence == 0.3f }
        assertEquals("현미밥", named.rememberedName)
        assertTrue(named.remembered.first().second >= FoodMemory.AUTO_NAME_AT)
        assertEquals("비어 보이는 자리(1위 < 0.02)는 기억과 견주지 않는다", 1, models.embedCalls.size)
        assertTrue("기억이 이름을 붙인 사진은 전체 1회를 다시 돌리지 않는다", models.wholeCalls.isEmpty())
    }

    @Test
    fun correctionMemoryRenamesAModelNamedSpot() {
        val p0 = FakePhoto()
        val dish = PixelBox(100f, 100f, 200f, 200f)
        val models = FakeModels(
            labels,
            regions = mapOf(p0 to listOf(dish to 0.9f)),
            crops = { _, _ -> scores("김치" to 0.8f) },
            embedding = { _, _ -> floatArrayOf(0f, 1f, 0f) },
        )
        val memory = FoodMemory(listOf(MemoryEntry("깍두기", floatArrayOf(0f, 1f, 0.02f), 1L, correctedFrom = "김치")))
        val r = pipeline(models, memory).detect(listOf(p0)).success()
        assertEquals("깍두기", r.regions.single().rememberedName)
        assertTrue("고침 기억이 바꾼 자리의 모델 이름은 결과에서 뺀다", r.foods.none { it.name == "김치" })
    }

    @Test
    fun regionModelFailureFallsBackToWholePhoto() {
        val p0 = FakePhoto()
        val models = FakeModels(labels, regions = mapOf(p0 to emptyList()), brokenRegions = true, whole = { scores("밥" to 0.7f) })
        val r = pipeline(models).detect(listOf(p0)).success()
        assertEquals(listOf(DetectedFood("밥", 0.7f, 0)), r.foods)
        assertTrue(r.regions.isEmpty())
    }

    @Test
    fun logScoresKeepTwoDecimals() {
        assertEquals("0.40", FoodPipeline.twoDecimals(0.4f))
        assertEquals("0.07", FoodPipeline.twoDecimals(0.0749f))
        assertEquals("1.00", FoodPipeline.twoDecimals(0.996f))
    }
}
