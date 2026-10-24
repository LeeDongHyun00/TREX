package com.example.trex_kotlin.food

import com.example.trex_kotlin.FoodPhoto
import com.example.trex_kotlin.elapsedRealtimeMs
import com.example.trex_kotlin.isOutOfMemory
import com.example.trex_kotlin.locked
import kotlinx.atomicfu.locks.SynchronizedObject

/**
 * 모델 한 벌(§108d) — 플랫폼이 모델 파일·픽셀·TFLite 를 다룬다(Android `FoodDetector` 의 인터프리터, iPhone TrexFoodRuntime).
 * 사진은 플랫폼 사진 그대로([FoodPhoto]). [FoodPipeline] 의 잠금 안에서만 부른다.
 */
interface FoodModels {
    /** 342종 분류 모델을 불러오고(한 번만) 라벨을 돌려준다. 파일이 없으면 null(모델 없음), 깨졌거나 입출력이 맞지 않으면 예외(오류). */
    fun classifier(): List<String>?
    /** 위치 모델을 불러온다 — 없거나 못 불러오면 false(전체 사진 1회 경로). 한 번 정하면 앱 수명 동안 다시 시도하지 않는다. */
    fun regionModel(): Boolean
    /** 특징값 모델(내 음식 기억) — 없거나 못 불러오면 false(기억을 쓰지 않는다). */
    fun embedModel(): Boolean
    /** 위치 모델 한 번(letterbox 입력) — 출력을 채널 우선 [channels × anchors] 로 편 것과 입력 한 변. */
    fun regionOutput(photo: FoodPhoto): RegionOutput
    /**
     * 342종 모델 한 번 → 클래스(라벨)별 최고 점수. [crop] 이 null 이면 사진 전체 letterbox, 아니면 그 픽셀 자리를
     * [FoodRegions.cropPlacement] 로 놓은 입력이다. 출력 형태가 라벨 수와 맞지 않으면 예외.
     */
    fun classScores(photo: FoodPhoto, crop: PixelBox?): FloatArray
    /** 자리(사진 대비 0~1) 특징값 — 둘레 10% 여유, 짧은 변을 224 로 맞춘 가운데 224×224([FoodInputs.embed]), ImageNet 정규화. */
    fun embed(photo: FoodPhoto, box: PixelBox): FloatArray
}

/** 위치 모델 출력 — [flat] 은 채널 우선(`[c * anchors + a]`), [inputSize] 는 입력 한 변(letterbox 계산). */
class RegionOutput(val flat: FloatArray, val channels: Int, val anchors: Int, val inputSize: Int)

/** 모델 입력 캔버스에 사진을 놓는 자리 — [src] 는 사진의 픽셀 영역(정수 값), [dst] 는 입력 캔버스의 픽셀 자리. 남는 곳은 회색(114)이다. */
class InputPlacement(val src: PixelBox, val dst: PixelBox)

/**
 * 세 입력의 배치(§108d) — Android `FoodDetector` 의 그리기와 iPhone `IosFoodModels` 가 같은 이 식을 쓴다(그리기·보간만 플랫폼).
 * 식은 §108d 전 `FoodDetector` 안의 계산을 그대로 옮긴 것이다.
 */
object FoodInputs {
    /** 특징값 모델(DINOv2-small) 입력 한 변. */
    const val EMBED_SIZE = 224

    /** 특징값을 뽑을 때 자리 둘레에 더하는 여유(자리 크기 대비). 실험(memory_eval.py best_spot)과 같다. */
    const val EMBED_PAD = 0.10f

    /**
     * Ultralytics 학습·평가와 같은 letterbox: 비율을 유지해 맞추고 가운데 정수 자리에 둔다.
     * 정사각형으로 늘리면(stretch) 학습 분포와 달라져 confidence 가 떨어진다.
     */
    fun letterbox(photoWidth: Int, photoHeight: Int, inputWidth: Int, inputHeight: Int): InputPlacement {
        val scale = minOf(inputWidth.toFloat() / photoWidth, inputHeight.toFloat() / photoHeight)
        val scaledW = (photoWidth * scale).toInt().coerceAtLeast(1)
        val scaledH = (photoHeight * scale).toInt().coerceAtLeast(1)
        val x = ((inputWidth - scaledW) / 2).toFloat()
        val y = ((inputHeight - scaledH) / 2).toFloat()
        return InputPlacement(PixelBox(0f, 0f, photoWidth.toFloat(), photoHeight.toFloat()), PixelBox(x, y, x + scaledW, y + scaledH))
    }

    /**
     * 2단계 입력: [box](사진 픽셀) 자리만 잘라 입력 가운데 정사각에 [FoodRegions.cropPlacement] 크기로 둔다.
     * 원본 좌표는 버림한다 — 실험 코드(crop_gray 의 int())와 같다. 1픽셀 미만 차이다.
     */
    fun crop(box: PixelBox, inputWidth: Int, inputHeight: Int): InputPlacement {
        val size = minOf(inputWidth, inputHeight)
        val dest = FoodRegions.cropPlacement(box, size)
        val offsetX = (inputWidth - size) / 2f
        val offsetY = (inputHeight - size) / 2f
        val left = box.left.toInt()
        val top = box.top.toInt()
        return InputPlacement(
            PixelBox(left.toFloat(), top.toFloat(), box.right.toInt().coerceAtLeast(left + 1).toFloat(), box.bottom.toInt().coerceAtLeast(top + 1).toFloat()),
            PixelBox(dest.left + offsetX, dest.top + offsetY, dest.right + offsetX, dest.bottom + offsetY),
        )
    }

    /**
     * 특징값 입력: [box](사진 대비 0~1)를 둘레 [EMBED_PAD] 만큼 넓혀 자르고, 짧은 변을 [EMBED_SIZE] 로 맞춘 뒤 가운데 정사각을 쓴다
     * (학습 전처리 timm: resize + center crop). 원본 자리는 정수로 버린다.
     */
    fun embed(box: PixelBox, photoWidth: Int, photoHeight: Int): InputPlacement {
        val w = photoWidth.toFloat()
        val h = photoHeight.toFloat()
        val padX = box.width * EMBED_PAD
        val padY = box.height * EMBED_PAD
        val left = ((box.left - padX) * w).coerceIn(0f, w - 1)
        val top = ((box.top - padY) * h).coerceIn(0f, h - 1)
        val right = ((box.right + padX) * w).coerceIn(left + 1, w)
        val bottom = ((box.bottom + padY) * h).coerceIn(top + 1, h)
        val scale = EMBED_SIZE / minOf(right - left, bottom - top)
        val dw = (right - left) * scale
        val dh = (bottom - top) * scale
        val dx = (EMBED_SIZE - dw) / 2
        val dy = (EMBED_SIZE - dh) / 2
        return InputPlacement(
            PixelBox(left.toInt().toFloat(), top.toInt().toFloat(), right.toInt().toFloat(), bottom.toInt().toFloat()),
            PixelBox(dx, dy, dx + dw, dy + dh),
        )
    }
}

/** 인식기 개발 기록(Android Log 태그 "FoodDetector") — 플랫폼마다. */
interface FoodLog {
    fun d(message: String)
    fun w(message: String, error: Throwable? = null)
    fun e(message: String, error: Throwable? = null)
}

/**
 * 온디바이스 음식 인식의 판정 흐름 — 두 플랫폼이 같은 이 코드를 돈다(§108d, 전에는 Android `FoodDetector` 안에 있었다).
 * 2단계(그릇 찾기 → 잘라서 이름 → 내 음식 기억 → 이름 없는 사진의 전체 1회) → 결과 목록, 위치 모델이 없거나 실패하면 전체 사진 1회.
 * 근거·측정은 docs/FOOD_EVAL_RESULTS.md §8·§8.1·§8.2. [detect]·[warmUp]·[embed] 는 블로킹이라 백그라운드에서 부른다.
 */
class FoodPipeline(
    private val models: FoodModels,
    private val memory: () -> FoodMemory,
    private val log: FoodLog,
    /** 기록용 경과 시간의 단조 시계(ms) — 테스트는 고정값을 넣는다(Android 스텁 SystemClock 은 유닛 테스트에서 못 부른다). */
    private val clock: () -> Long = { elapsedRealtimeMs() },
) {
    private val lock = SynchronizedObject()

    /** 시트를 열 때 미리 불러, 첫 분석이 모델 로딩 지연까지 떠안지 않게 한다. */
    fun warmUp() {
        locked(lock) {
            try {
                models.classifier()
            } catch (e: Exception) {
                log.w("모델 예열 실패", e)
            }
            models.regionModel()
        }
    }

    fun detect(photos: List<FoodPhoto>): FoodDetectionResult {
        if (photos.isEmpty()) return FoodDetectionResult.Error
        return locked(lock) { detectLocked(photos) }
    }

    private fun detectLocked(photos: List<FoodPhoto>): FoodDetectionResult {
        val labels = try {
            models.classifier() ?: return FoodDetectionResult.ModelMissing
        } catch (e: Exception) {
            log.e("모델 로딩 실패", e)
            return FoodDetectionResult.Error
        }
        if (models.regionModel()) {
            // 2단계가 실패해도 사용자는 인식 결과를 받아야 한다 — 전체 사진 1회로 내려간다.
            var regions = try {
                detectRegions(labels, photos)
            } catch (e: Throwable) {
                // 자리 수만큼 자르기를 돌리는 경로라 저사양 기기에서 메모리가 모자랄 수 있다. 앱을 죽이지 않고 1회 경로로 간다.
                when {
                    e is Exception -> log.e("2단계 인식 실패 — 전체 사진 1회로 대신한다", e)
                    isOutOfMemory(e) -> log.e("2단계 인식 중 메모리 부족 — 전체 사진 1회로 대신한다", e)
                    else -> throw e
                }
                emptyList()
            }
            if (regions.isNotEmpty()) {
                regions = withMemory(regions, photos)
                // 이름이 하나도 안 붙은 사진은 전체 사진 1회로도 본다 — 그 사진의 음식이 통째로 빠지지 않게.
                // 여기서 나온 음식은 자리가 없어 사진 위 박스 없이 목록에만 들어간다.
                val fallback = try {
                    regions.photosWithoutNames(photos.size).flatMap { index ->
                        runInference(labels, photos[index]).map { (name, confidence) -> DetectedFood(name, confidence, index) }
                    }
                } catch (e: Exception) {
                    log.e("자리 없는 사진의 전체 1회 인식 실패", e)
                    emptyList()
                }
                val (foods, candidates) = regions.toResultLists(fallback, MAX_CANDIDATES)
                return FoodDetectionResult.Success(foods, candidates, regions)
            }
        }
        return try {
            // 사진 여러 장이면 인식된 음식을 라벨 기준으로 합치고, 같은 음식은 최고 confidence(와 그 사진)만 남긴다.
            val merged = LinkedHashMap<String, DetectedFood>()
            photos.forEachIndexed { index, photo ->
                runInference(labels, photo).forEach { (name, confidence) ->
                    val previous = merged[name]
                    if (previous == null || confidence > previous.confidence) {
                        merged[name] = DetectedFood(name, confidence, index)
                    }
                }
            }
            val ranked = merged.values.sortedByDescending { it.confidence }
            val foods = ranked
                .filter { it.confidence >= CONFIDENCE_THRESHOLD }
                .take(MAX_FOODS_PER_ANALYSIS)
            // 결과에 이미 든 것은 후보에서 뺀다 — 같은 이름을 두 곳에 보여줄 이유가 없다.
            val shown = foods.mapTo(HashSet()) { it.name }
            val candidates = ranked
                .filter { it.confidence < CONFIDENCE_THRESHOLD && it.name !in shown }
                .take(MAX_CANDIDATES)
            FoodDetectionResult.Success(foods, candidates)
        } catch (e: Exception) {
            log.e("추론 실패", e)
            FoodDetectionResult.Error
        }
    }

    /**
     * 2단계 인식. 사진마다 그릇 자리를 찾아([FoodRegions.decode]·[FoodRegions.tidy]) 자리마다 잘라 342종 모델에 넣고
     * 상위 [FoodRegions.TOP_PER_REGION] 개를 남긴다. 자리 좌표는 사진 대비 0~1 로 바꿔 둔다(화면 사진은 크기가 다르다).
     */
    private fun detectRegions(labels: List<String>, photos: List<FoodPhoto>): List<FoodRegion> {
        val startedAt = clock()
        val regions = ArrayList<FoodRegion>()
        photos.forEachIndexed { photoIndex, photo ->
            val output = models.regionOutput(photo)
            val letterbox = Letterbox.of(photo.width, photo.height, output.inputSize)
            val hits = FoodRegions.tidy(
                FoodRegions.decode(output.flat, output.channels, output.anchors, letterbox, photo.width, photo.height),
                photo.width, photo.height,
            )
            for (hit in hits) {
                val scores = models.classScores(photo, hit.box)
                val top = labels.indices
                    .filter { !labels[it].startsWith("#") }
                    .sortedByDescending { scores[it] }
                    .take(FoodRegions.TOP_PER_REGION)
                    .map { labels[it] to scores[it] }
                val box = hit.box
                regions += FoodRegion(
                    id = regions.size,
                    photoIndex = photoIndex,
                    box = PixelBox(box.left / photo.width, box.top / photo.height, box.right / photo.width, box.bottom / photo.height),
                    regionScore = hit.score,
                    top = top,
                )
            }
        }
        val elapsedMs = clock() - startedAt
        // 실기기에서 "그릇은 찾았는데 이름이 틀렸나 / 그릇을 못 찾았나" 를 가리려고 자리마다 상위 후보를 남긴다.
        log.d(
            "2단계 ${photos.size}장 · 자리 ${regions.size}곳 · 이름 ${regions.count { it.name != null }}곳 · ${elapsedMs}ms · " +
                regions.joinToString(" | ") { r -> "#${r.photoIndex}:" + r.top.joinToString { "${it.first} ${twoDecimals(it.second)}" } },
        )
        return regions
    }

    /**
     * 이름이 없고 비어 보이지 않는 자리마다 특징값을 뽑아 내 음식 기억과 견준다. 기억이 비었거나 특징값 모델이 없으면 그대로 돌려준다.
     * 실패해도 인식 결과는 그대로 내야 하므로 예외를 삼킨다.
     */
    private fun withMemory(regions: List<FoodRegion>, photos: List<FoodPhoto>): List<FoodRegion> {
        val memory = memory()
        if (memory.isEmpty) return regions
        if (!models.embedModel()) return regions
        val startedAt = clock()
        return try {
            regions.map { r ->
                val modelName = r.name
                when {
                    modelName == null && r.looksEmpty -> r
                    modelName == null -> {
                        val v = models.embed(photos[r.photoIndex], r.box)
                        r.copy(remembered = memory.match(v).filter { it.second >= FoodMemory.SUGGEST_AT })
                    }
                    // 모델 이름이 붙은 자리는 그 이름을 사용자가 고친 적이 있을 때만 본다.
                    memory.hasCorrectionOf(modelName) -> {
                        val v = models.embed(photos[r.photoIndex], r.box)
                        r.copy(remembered = memory.match(v, correctedFrom = modelName).filter { it.second >= FoodMemory.SUGGEST_AT })
                    }
                    else -> r
                }
            }.also { out ->
                log.d(
                    "기억 비교 ${out.count { (it.name == null && !it.looksEmpty) || (it.name != null && memory.hasCorrectionOf(it.name!!)) }}곳 · 기억 ${memory.entries.size}장 · " +
                        "${clock() - startedAt}ms · " +
                        out.filter { it.remembered.isNotEmpty() }.joinToString { r -> r.remembered.joinToString("/") { "${it.first} ${twoDecimals(it.second)}" } },
                )
            }
        } catch (e: Throwable) {
            when {
                e is Exception -> log.e("기억 비교 실패 — 기억 없이 보여준다", e)
                isOutOfMemory(e) -> log.e("기억 비교 중 메모리 부족 — 기억 없이 보여준다", e)
                else -> throw e
            }
            regions
        }
    }

    /**
     * [photo] 의 [box](사진 대비 0~1) 자리 특징값. 내 음식 기억을 저장할 때 쓴다. 모델이 없거나 실패하면 null.
     * 블로킹 호출이다 — 백그라운드에서 부른다.
     */
    fun embed(photo: FoodPhoto, box: PixelBox): FloatArray? = locked(lock) {
        if (!models.embedModel()) return@locked null
        try {
            models.embed(photo, box)
        } catch (e: Throwable) {
            when {
                e is Exception -> log.e("특징값 계산 실패", e)
                isOutOfMemory(e) -> log.e("특징값 계산 중 메모리 부족", e)
                else -> throw e
            }
            null
        }
    }

    /** 사진 전체 1회 — 클래스별 최고 점수 중 바닥값([CANDIDATE_FLOOR]) 이상을 이름 → 점수로. */
    private fun runInference(labels: List<String>, photo: FoodPhoto): Map<String, Float> {
        val best = models.classScores(photo, null)
        // 임계값과 무관하게 상위 5개를 남긴다 — "왜 못 잡았나"(근소 미달 vs 엉뚱한 클래스)를 실기기 로그로 가리기 위해서다.
        val ranked = labels.indices
            .filter { !labels[it].startsWith("#") }
            .sortedByDescending { best[it] }
            .take(5)
            .joinToString { "${labels[it]} ${twoDecimals(best[it])}" }
        log.d("전체 사진 1회 · 상위 점수: $ranked (임계 $CONFIDENCE_THRESHOLD)")

        // 임계값이 아니라 바닥값으로 자른다. 임계 미만은 결과에 넣지 않지만 후보로는 쓰므로,
        // 여기서 버리면 detect() 가 나눌 수 없다.
        val scored = LinkedHashMap<String, Float>()
        for (c in labels.indices) {
            val name = labels[c]
            if (name.startsWith("#")) continue
            if (best[c] >= CANDIDATE_FLOOR) scored[name] = best[c]
        }
        return scored
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = FoodRegions.NAME_THRESHOLD
        const val MAX_FOODS_PER_ANALYSIS = 5

        /**
         * 후보로도 내놓지 않는 바닥값. 이 아래는 순서에도 의미가 없는 잡음으로 본다.
         * 임계값(0.40)과 달리 근거가 약한 값이다 — 실사용 사진으로 재보고 조정할 것.
         */
        const val CANDIDATE_FLOOR = 0.10f

        /** 후보 목록의 최대 개수. 훑어보는 목록이라 길면 오히려 고르기 어렵다. */
        const val MAX_CANDIDATES = 8

        /** 기록용 소수 둘째 자리(Android 종전 "%.2f" 자리). */
        internal fun twoDecimals(v: Float): String {
            if (!v.isFinite()) return v.toString()
            val hundredths = kotlin.math.round(kotlin.math.abs(v) * 100f).toLong()
            return (if (v < 0f && hundredths != 0L) "-" else "") + "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
    }
}
