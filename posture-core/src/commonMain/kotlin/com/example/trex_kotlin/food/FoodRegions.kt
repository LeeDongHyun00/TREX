// Android 정본 동기화 — tools/sync_ios_core.py
package com.example.trex_kotlin.food

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2단계 인식의 순수 계산부 — 안드로이드 의존 없이 JVM 테스트로 검증한다.
 *
 * 1단계: 음식 위치 모델(YOLOE-11S, "food·bowl·plate…" 고정 어휘, 종류는 모른다)이 그릇을 찾는다.
 * 2단계: 그 자리를 잘라 342종 모델에 넣어 이름을 붙인다.
 *
 * 근거는 docs/FOOD_EVAL_RESULTS.md §8·§8.1·§8.2. 한 장에 음식 1개짜리 사진만 배운 342종 모델은 식탁 사진 16장에서
 * 사진 전체로는 9/25(36%) 를 잡는다. 이 구성(YOLOE-S 탐지 전용 + 회색 여백 30% 자르기)은 PC 에서 12~13/25(48~52%) 이고,
 * 1단계는 기준 박스(YOLO-World L, 사람이 세어 음식 52개 중 약 47개에 박스)의 91% 를 다시 찾는다. 앱 안에서는 아직 재지 않았다.
 * 정리 규칙(NMS·큰 박스 버리기·포함 박스 합치기)과 자르기 방식은 실험 코드
 * `training/two_stage_eval/two_stage.py` 의 `tidy`·`crop_gray` 와 같다 — 한쪽을 바꾸면 다른 쪽도 바꾼다.
 */

/** 사진 좌표계(픽셀)의 박스. */
data class PixelBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    fun intersection(other: PixelBox): Float =
        max(0f, min(right, other.right) - max(left, other.left)) * max(0f, min(bottom, other.bottom) - max(top, other.top))

    fun iou(other: PixelBox): Float {
        val inter = intersection(other)
        return inter / (area + other.area - inter + 1e-9f)
    }
}

/** 1단계가 찾은 자리 하나. [score] 는 "음식·그릇일 확률"이지 무슨 음식인지와는 무관하다. */
data class RegionHit(val box: PixelBox, val score: Float)

/**
 * 모델 입력에 사진을 넣은 방식(letterbox). 모델 좌표를 사진 좌표로 되돌리는 데 쓴다.
 * [scale] 은 사진 → 입력 배율, [padX]·[padY] 는 입력 안의 회색 여백.
 */
data class Letterbox(val inputSize: Int, val scale: Float, val padX: Int, val padY: Int) {
    companion object {
        fun of(imageWidth: Int, imageHeight: Int, inputSize: Int): Letterbox {
            val scale = min(inputSize.toFloat() / imageWidth, inputSize.toFloat() / imageHeight)
            val scaledW = (imageWidth * scale).toInt().coerceAtLeast(1)
            val scaledH = (imageHeight * scale).toInt().coerceAtLeast(1)
            return Letterbox(inputSize, scale, (inputSize - scaledW) / 2, (inputSize - scaledH) / 2)
        }
    }
}

object FoodRegions {

    /** 2단계 이름 임계값. 전체 사진 1회 경로와 같은 값이다(FoodDetector) — 밥 8종 평가에서 Top-1 과 검출률이 같아 낮출 근거가 없다(FOOD_EVAL §5 D). */
    const val NAME_THRESHOLD = 0.40f

    /** 2단계에서 자리마다 남기는 후보 수. 정답이 3위 안에 드는 경우가 16~20/25 였다(§8). */
    const val TOP_PER_REGION = 3

    /**
     * 2단계 1위가 이 값보다 낮으면 "비어 보이는 자리" 로 본다 — 342종 모델이 그 자리에서 **아무 음식 모양도 못 본** 것이다
     * (실기기 로그에서 이런 자리는 상위 3개가 전부 0.00 이었다). 화면은 이런 자리를 기본으로 숨기고 개수만 알린다.
     *
     * 식탁 사진 16장 실측(FOOD_EVAL §8.3): 이름 없는 자리 96곳 중 53곳이 0.02 미만이었고, 사람이 보니 그중 약 46곳이
     * 빈 그릇·컵·물잔·소스 종지, 약 7곳이 단무지·생강·김치 같은 작은 곁들이였다. 그래서 지우지 않고 숨기며 다시 펼 수 있게 둔다.
     */
    const val LOOKS_EMPTY_BELOW = 0.02f

    /**
     * 1단계 점수 바닥값. 실험에서 0.05 가 0.10·0.20 보다 그릇을 더 많이 찾았다(L 박스 재현 91% vs 84%·76%, §8.1).
     * 낮춰서 늘어나는 박스는 대부분 컵·빈 그릇이고, 2단계에서 이름이 붙지 않으면 "?" 로만 남아 기록에 들어가지 않는다.
     */
    const val REGION_SCORE_FLOOR = 0.05f

    /** 같은 그릇으로 보는 겹침 기준(IoU). */
    const val NMS_IOU = 0.5f

    /** 화면을 이보다 많이 덮는 박스는 식탁·쟁반 전체로 보고 버린다. */
    const val MAX_AREA_FRACTION = 0.6f

    /** 작은 박스가 큰 박스 안에 이만큼 들어가 있으면 같은 그릇(그릇과 그 안의 음식)으로 본다. */
    const val CONTAINED_FRACTION = 0.85f

    /**
     * 사진 한 장에서 2단계로 넘길 최대 자리 수. 평균은 7~8곳이지만 반찬이 많은 한 상(photo04)은 18곳이었다 —
     * 10 으로 자르면 점수 낮은 반찬 그릇부터 빠져 "전부 잡기" 가 깨진다. 2단계 1회가 폰에서 약 27ms 라 20곳이면 0.5초,
     * 5장 최악 2.7초다(모델 시간만의 추정, 앱 안에서는 미측정). 그 이상은 기다림만 늘린다. 점수 높은 순으로 자른다.
     */
    const val MAX_REGIONS_PER_PHOTO = 20

    /**
     * 2단계 입력에서 박스(긴 변 기준 정사각형)가 차지할 면적 비율. 342종 학습 사진의 박스 면적 중앙값이 29% 라,
     * 잘라낸 그릇을 화면 가득 채우면(100%) 학습 분포에서 벗어나 점수가 떨어진다. 회색 여백 30% 가
     * 정답이 후보 3위 안에 드는 경우가 가장 많았다(20/25, §8).
     */
    const val CROP_AREA_FRACTION = 0.30f

    /**
     * 1단계 출력 `[4 + 클래스수, 앵커수]`(채널 우선, [output] 은 그 평탄화)를 사진 좌표 박스로 바꾼다.
     * 클래스는 "food·bowl·plate…" 같은 동의어 묶음이라 종류를 묻지 않고 최고 점수만 쓴다.
     * 좌표가 0~1 로 정규화돼 나오는 변환본도 있어 입력 크기로 되돌린다.
     */
    fun decode(
        output: FloatArray,
        channels: Int,
        anchors: Int,
        letterbox: Letterbox,
        imageWidth: Int,
        imageHeight: Int,
        floor: Float = REGION_SCORE_FLOOR,
    ): List<RegionHit> {
        require(output.size == channels * anchors) { "출력 크기 ${output.size} 가 $channels × $anchors 와 다르다" }
        require(channels > 4) { "클래스 채널이 없다: $channels" }
        var maxCoord = 0f
        for (a in 0 until anchors) maxCoord = max(maxCoord, output[a])
        val unit = if (maxCoord <= 1.5f) letterbox.inputSize.toFloat() else 1f
        val hits = ArrayList<RegionHit>()
        for (a in 0 until anchors) {
            var best = 0f
            for (c in 4 until channels) best = max(best, output[c * anchors + a])
            if (best < floor) continue
            val cx = output[a] * unit
            val cy = output[anchors + a] * unit
            val w = output[2 * anchors + a] * unit
            val h = output[3 * anchors + a] * unit
            val box = PixelBox(
                ((cx - w / 2 - letterbox.padX) / letterbox.scale).coerceIn(0f, imageWidth.toFloat()),
                ((cy - h / 2 - letterbox.padY) / letterbox.scale).coerceIn(0f, imageHeight.toFloat()),
                ((cx + w / 2 - letterbox.padX) / letterbox.scale).coerceIn(0f, imageWidth.toFloat()),
                ((cy + h / 2 - letterbox.padY) / letterbox.scale).coerceIn(0f, imageHeight.toFloat()),
            )
            if (box.area > 0f) hits += RegionHit(box, best)
        }
        return hits
    }

    /**
     * 같은 그릇을 가리키는 박스를 하나로 정리한다.
     * ① 점수순 NMS ② 화면 [MAX_AREA_FRACTION] 초과 박스 버림 ③ 더 큰 박스 안에 [CONTAINED_FRACTION] 이상 들어간 박스 버림
     * ④ 점수 높은 순 [MAX_REGIONS_PER_PHOTO] 개.
     */
    fun tidy(hits: List<RegionHit>, imageWidth: Int, imageHeight: Int): List<RegionHit> {
        val kept = ArrayList<RegionHit>()
        for (hit in hits.sortedByDescending { it.score }) {
            if (kept.none { it.box.iou(hit.box) >= NMS_IOU }) kept += hit
        }
        val imageArea = imageWidth.toFloat() * imageHeight
        val sized = kept.filter { it.box.area <= MAX_AREA_FRACTION * imageArea }
        val outer = sized.filter { small ->
            sized.none { big -> big !== small && big.box.area > small.box.area && big.box.intersection(small.box) / small.box.area >= CONTAINED_FRACTION }
        }
        return outer.take(MAX_REGIONS_PER_PHOTO)
    }

    /**
     * 2단계 입력(정사각 [inputSize])에 잘라낸 자리를 어디에 그릴지. 박스를 긴 변 기준으로 [CROP_AREA_FRACTION] 만큼만
     * 차지하게 줄여 가운데에 두고 나머지는 회색으로 둔다 — 주변 음식을 섞지 않으면서 학습 사진의 구도를 흉내 낸다.
     * 반환값은 입력 캔버스 안의 목적지 사각형.
     */
    fun cropPlacement(box: PixelBox, inputSize: Int, areaFraction: Float = CROP_AREA_FRACTION): PixelBox {
        val longest = max(box.width, box.height).coerceAtLeast(1f)
        val scale = inputSize * sqrt(areaFraction) / longest
        val w = box.width * scale
        val h = box.height * scale
        val left = (inputSize - w) / 2
        val top = (inputSize - h) / 2
        return PixelBox(left, top, left + w, top + h)
    }
}

/**
 * 2단계까지 마친 자리 하나.
 *
 * [box] 는 사진 대비 0~1 비율이라 화면 크기와 무관하게 그린다. [top] 은 342종 모델의 상위 3개(이름, 점수) — 점수순.
 * 이름([name])은 1위가 [FoodRegions.NAME_THRESHOLD] 이상일 때만 붙는다. 못 미치면 "?" 자리로 남고,
 * 사용자가 눌러 [top] 중에서 고르거나 검색한다 — 판정하지 않은 것을 판정한 것처럼 내놓지 않는다.
 */
data class FoodRegion(
    val id: Int,
    val photoIndex: Int,
    val box: PixelBox,
    val regionScore: Float,
    val top: List<Pair<String, Float>>,
    /**
     * 내 음식 기억([FoodMemory])에서 이 자리와 비슷한 이름들(이름, 유사도) — 유사도순, [FoodMemory.SUGGEST_AT] 이상만.
     * 이름이 없고 비어 보이지 않는 자리는 모든 기억과 견준다. 모델이 이름을 붙인 자리는 **사용자가 그 이름을 고친 기억**
     * (고침 기억, [MemoryEntry.correctedFrom])하고만 견준다. 기억이 비어 있으면 늘 빈 목록이다.
     */
    val remembered: List<Pair<String, Float>> = emptyList(),
) {
    val name: String? get() = top.firstOrNull()?.takeIf { it.second >= FoodRegions.NAME_THRESHOLD }?.first
    val confidence: Float get() = top.firstOrNull()?.second ?: 0f

    /** [FoodRegions.LOOKS_EMPTY_BELOW] 참고. 이름이 붙은 자리는 비어 보일 수 없다. */
    val looksEmpty: Boolean get() = confidence < FoodRegions.LOOKS_EMPTY_BELOW

    /**
     * 기억이 매우 비슷하다고 한 이름([FoodMemory.AUTO_NAME_AT] 이상). 이름 없는 자리에는 이름을 붙이고,
     * 모델 이름이 붙은 자리에서는 **고침 기억**만 모델 이름을 바꾼다 — 모델이 같은 음식을 같은 이름으로 또 잘못 볼 때다.
     */
    val rememberedName: String?
        get() = remembered.firstOrNull()?.takeIf { it.second >= FoodMemory.AUTO_NAME_AT && it.first != name }?.first
}

/**
 * 이름이 붙은 자리가 하나도 없는 사진의 번호. 이 사진들은 전체 사진 1회로도 본다([toResultLists] 의 fallback).
 *
 * 자리를 못 찾았거나(클로즈업 한 그릇은 화면 60% 를 넘어 버려진다) 컵·빈 그릇만 "?" 로 잡힌 사진을 그냥 두면,
 * 예전 경로라면 잡았을 음식이 그 사진에서 통째로 빠지고 화면은 "여기엔 음식이 없다" 고 판정한 것처럼 보인다.
 */
fun List<FoodRegion>.photosWithoutNames(photoCount: Int): List<Int> {
    // 기억이 자동으로 이름을 붙인 사진도 이름 붙은 사진으로 친다. 안 그러면 전체 1회가 같은 음식을 박스 없는 줄로
    // 한 번 더 내놓아 한 끼가 두 번 기록된다(코드 리뷰 2026-09-27).
    val named = filter { it.name != null || it.rememberedName != null }.mapTo(HashSet()) { it.photoIndex }
    return (0 until photoCount).filter { it !in named }
}

/**
 * 자리 목록(+ 자리 없이 전체 사진 1회로 본 결과 [fallback])을 결과 화면이 쓰는 두 목록으로 나눈다.
 *
 * - 결과: 이름이 붙은 자리와 [fallback] 중 임계값 이상을 이름별로 하나씩(여러 장·여러 자리에 같은 이름이면 최고 점수와 그 사진).
 *   수량은 늘리지 않는다 — 같은 그릇을 두 장에 찍은 것과 두 그릇을 구별할 수 없어서, 늘리면 한 끼를 두 번 적게 된다.
 *   개수 상한(전체 사진 1회 경로의 5개)은 두지 않는다 — 이름이 붙은 자리는 사진 위에 박스로 보이므로, 목록에서 빼면 박스와 목록이 어긋난다.
 * - 후보: 결과에 들지 않은 이름 중 어느 자리의 상위 3개에든 들었거나 [fallback] 에서 임계 미만인 것, 점수순 [maxCandidates] 개.
 *   "빠진 음식 추가"에서만 보인다.
 */
fun List<FoodRegion>.toResultLists(
    fallback: List<DetectedFood> = emptyList(),
    maxCandidates: Int = 8,
): Pair<List<DetectedFood>, List<DetectedFood>> {
    fun keepBest(map: LinkedHashMap<String, DetectedFood>, food: DetectedFood) {
        val previous = map[food.name]
        if (previous == null || food.confidence > previous.confidence) map[food.name] = food
    }
    val named = LinkedHashMap<String, DetectedFood>()
    for (region in this) {
        val name = region.name ?: continue
        // 고침 기억이 모델 이름을 바꾼 자리는 모델 결과에서 뺀다 — 화면이 기억 이름으로 줄을 만든다.
        if (region.rememberedName != null) continue
        keepBest(named, DetectedFood(name, region.confidence, region.photoIndex))
    }
    fallback.filter { it.confidence >= FoodRegions.NAME_THRESHOLD }.forEach { keepBest(named, it) }
    val others = LinkedHashMap<String, DetectedFood>()
    for (region in this) {
        for ((name, score) in region.top) {
            if (name !in named) keepBest(others, DetectedFood(name, score, region.photoIndex))
        }
    }
    fallback.filter { it.confidence < FoodRegions.NAME_THRESHOLD && it.name !in named }.forEach { keepBest(others, it) }
    return named.values.sortedByDescending { it.confidence } to
        others.values.sortedByDescending { it.confidence }.take(maxCandidates)
}
