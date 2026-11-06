package com.example.trex_kotlin.posture

import com.example.trex_kotlin.trainingload.*
import com.example.trex_kotlin.food.*

/** 기록 부하 계산도 Android 정본을 공유한다. 자세 점수를 운동 부하에 곱하지 않는다. */
class IosLoad {
    @Throws(Exception::class) fun snapshot(input: String, nowMs: Long, availableEquipment: String): String {
        val root = Json.parse(input) as List<*>
        val sets = root.map { value ->
            @Suppress("UNCHECKED_CAST") val o = JSONObject(value as Map<String,Any?>)
            val p = MuscleLoadCatalog.find(o.getString("name")) ?: error("운동 부하 종목 없음")
            LoadSet(o.getString("id"), o.getString("id"), p.id, o.getLong("endedAt"),
                reps=o.optDouble("reps",0.0),seconds=o.optDouble("duration",0.0),unit=p.unit,
                weightKg=o.optDouble("weight",65.0),heightCm=o.optDouble("height",170.0),
                left=o.optDouble("left").takeIf(Double::isFinite),right=o.optDouble("right").takeIf(Double::isFinite),
                actualReps=o.optDouble("actualReps").takeIf(Double::isFinite))
        }
        val result=MuscleLoadEngine.snapshot(sets,nowMs)
        val equipment=(Json.parse(availableEquipment) as List<*>).mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet()
        return Json.encode(mapOf("values" to result.muscles.filter { it.known }.flatMap { listOf(it.muscle.name+"_L" to it.left,it.muscle.name+"_R" to it.right) }.toMap(),
            "muscles" to result.muscles.filter { it.known }.map { mapOf("name" to it.muscle.label,"left" to it.left,"right" to it.right) },
            "recommendations" to MuscleLoadEngine.recommendations(result,equipment).map { it.name }))
    }
}

/** 모델 출력의 자리 정리와 30% 회색 여백 자르기는 Android와 같은 코드다. */
class IosFoodMath {
    @Throws(Exception::class) fun regions(input: String): String {
        val j=JSONObject(input)
        val floats=(j.values()["output"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
        val w=j.getInt("width");val h=j.getInt("height");val size=j.getInt("size")
        val decoded=FoodRegions.decode(floats,j.getInt("channels"),j.getInt("anchors"),Letterbox.of(w,h,size),w,h)
        val hits=FoodRegions.tidy(decoded,w,h)
        return Json.encode(hits.map { mapOf("left" to it.box.left,"top" to it.box.top,"right" to it.box.right,"bottom" to it.box.bottom,"score" to it.score) })
    }
}

/** 개발용 기준선의 세트 중앙값도 정본 수집기로 계산한다. 실시간 자동 보정에는 적용하지 않는다. */
class IosBaseline {
    @Throws(Exception::class) fun build(input: String, requiredSets: Int): String {
        val sets = (Json.parse(input) as List<*>).map { value ->
            @Suppress("UNCHECKED_CAST") val fields = value as Map<String, Any?>
            fields.mapNotNull { (key, number) -> (number as? Number)?.toFloat()?.takeIf(Float::isFinite)?.let { key to it } }.toMap()
        }
        val collector = BaselineCollector("ios", sets.flatMap { it.keys }.distinct(), requiredSets.coerceAtLeast(1))
        sets.forEach(collector::addSet)
        return Json.encode(if (collector.isComplete) collector.build().values else emptyMap<String, Float>())
    }
}

/**
 * 사진 식단의 **판정 정책**(Android `FoodDetector`·`FoodRegions`·`FoodMemory` 와 같은 규칙, `docs/FOOD_RECOGNITION.md`) — Swift 는 모델을 돌려 자리·점수·임베딩만 넘기고,
 * 이름 문턱(0.40)·후보(0.10 바닥, 8개)·같은 이름 합치기·기억의 자동 이름(0.77)/제안(0.45)·이름 없는 사진의 전체 1회 폴백은 여기서 정한다. 임계값을 Swift 에 두지 않는다(§107f 기준 1).
 */
class IosFood {
    /** Swift 가 넘긴 자리 목록 → [FoodRegion]. `top`·`remembered` 는 `[[이름, 점수], …]`. */
    private fun regions(json: Any?): List<FoodRegion> = (json as? List<*>).orEmpty().mapIndexed { i, value ->
        @Suppress("UNCHECKED_CAST") val o = JSONObject(value as Map<String, Any?>)
        fun pairs(key: String) = (o.values()[key] as? List<*>).orEmpty().mapNotNull { p -> (p as? List<*>)?.let { it[0].toString() to ((it[1] as? Number)?.toFloat() ?: 0f) } }
        val b = o.optJSONObject("box") ?: error("box 없음")
        FoodRegion(o.optInt("id", i), o.optInt("photoIndex", 0), PixelBox(b.getDouble("left").toFloat(), b.getDouble("top").toFloat(), b.getDouble("right").toFloat(), b.getDouble("bottom").toFloat()),
            o.optDouble("regionScore", 0.0).toFloat(), pairs("top"), pairs("remembered"))
    }
    private fun foods(json: Any?): List<DetectedFood> = (json as? List<*>).orEmpty().map { value ->
        @Suppress("UNCHECKED_CAST") val o = JSONObject(value as Map<String, Any?>)
        DetectedFood(o.getString("name"), o.optDouble("confidence", 0.0).toFloat(), o.optInt("photoIndex", 0))
    }
    private fun encode(list: List<DetectedFood>) = list.map { mapOf("name" to it.name, "confidence" to it.confidence, "photoIndex" to it.photoIndex) }

    /** 이름이 붙은 자리(기억 이름 포함)가 없는 사진 번호 — Swift 가 그 사진만 전체 1회를 더 돌린다. */
    @Throws(Exception::class) fun photosWithoutNames(regionsJson: String, photoCount: Int): String =
        Json.encode(regions(Json.parse(regionsJson)).photosWithoutNames(photoCount))

    /**
     * 자리 목록 + 폴백(전체 1회) → `{foods, candidates, regions:[{id, name, rememberedName, looksEmpty, confidence, photoIndex}]}`.
     * foods 는 모델 이름(0.40 이상, 기억이 고친 자리는 제외)과 폴백, candidates 는 그 밖의 상위 이름(최대 8).
     */
    @Throws(Exception::class) fun resultLists(regionsJson: String, fallbackJson: String): String {
        val rs = regions(Json.parse(regionsJson))
        val (foods, candidates) = rs.toResultLists(foods(Json.parse(fallbackJson)), MAX_CANDIDATES)
        return Json.encode(mapOf("foods" to encode(foods), "candidates" to encode(candidates),
            "regions" to rs.map { r -> mapOf("id" to r.id, "photoIndex" to r.photoIndex, "name" to r.name, "rememberedName" to r.rememberedName, "looksEmpty" to r.looksEmpty,
                "confidence" to r.confidence, "rememberedScore" to r.remembered.firstOrNull()?.second) }))
    }

    /** 자리 모델이 없거나 자리를 못 찾았을 때 — 사진별 전체 1회 점수(`[{name, confidence, photoIndex}]`)를 같은 이름 최고 점수로 합쳐 foods(≥ 0.40, 최대 5)·candidates(< 0.40, 최대 8). */
    @Throws(Exception::class) fun wholeLists(scoresJson: String): String {
        val merged = LinkedHashMap<String, DetectedFood>()
        for (f in foods(Json.parse(scoresJson))) { val p = merged[f.name]; if (p == null || f.confidence > p.confidence) merged[f.name] = f }
        val ranked = merged.values.sortedByDescending { it.confidence }
        val foods = ranked.filter { it.confidence >= FoodRegions.NAME_THRESHOLD }.take(MAX_FOODS_PER_ANALYSIS)
        val shown = foods.mapTo(HashSet()) { it.name }
        val candidates = ranked.filter { it.confidence < FoodRegions.NAME_THRESHOLD && it.confidence >= CANDIDATE_FLOOR && it.name !in shown }.take(MAX_CANDIDATES)
        return Json.encode(mapOf("foods" to encode(foods), "candidates" to encode(candidates), "regions" to emptyList<Any>()))
    }

    companion object {
        /** Android `FoodDetector` 와 같은 값 — 후보 바닥·개수는 아직 근거 없이 잡은 값이다(`FOOD_RECOGNITION.md`). */
        const val MAX_FOODS_PER_ANALYSIS = 5
        const val CANDIDATE_FLOOR = 0.10f
        const val MAX_CANDIDATES = 8
        const val TOP_PER_REGION = FoodRegions.TOP_PER_REGION
        const val LOOKS_EMPTY_BELOW = FoodRegions.LOOKS_EMPTY_BELOW
        const val NAME_THRESHOLD = FoodRegions.NAME_THRESHOLD
    }
}

/**
 * 내 음식 기억 — 공통 `FoodMemory`(이름당 5장·전체 300장·고침 기억·코사인) 를 Android `FoodMemoryStore` 와 같은 텍스트 형식(`encode`)으로 든다. Swift 는 파일 읽기/쓰기만.
 * 호출은 음식 추론 큐 한 곳에서만.
 */
class IosFoodMemory(text: String = "") {
    private var memory = FoodMemory.decode(text)
    val isEmpty: Boolean get() = memory.isEmpty
    val size: Int get() = memory.entries.size
    fun encode(): String = memory.encode()
    fun hasCorrectionOf(modelName: String): Boolean = memory.hasCorrectionOf(modelName)
    private fun vector(json: String): FloatArray = (Json.parse(json) as List<*>).map { (it as? Number)?.toFloat() ?: Float.NaN }.toFloatArray()
    /** 비슷한 기억 `[[이름, 유사도], …]`(유사도순, 최대 3) — 제안 문턱(0.45) 이상만. [correctedFrom] 이 있으면 그 이름을 고친 기억하고만 견준다. */
    @Throws(Exception::class) fun match(vectorJson: String, correctedFrom: String?): String =
        Json.encode(memory.match(vector(vectorJson), correctedFrom = correctedFrom).filter { it.second >= FoodMemory.SUGGEST_AT }.map { listOf(it.first, it.second) })
    /** 기억을 더한다 — 벡터 길이·유한값은 호출자가 보장하지 않아도 여기서 거른다(거르면 false). */
    @Throws(Exception::class) fun plus(name: String, vectorJson: String, savedAtMs: Long, correctedFrom: String?): Boolean {
        val v = vector(vectorJson)
        if (v.isEmpty() || v.any { !it.isFinite() }) return false
        memory = memory.plus(MemoryEntry(FoodMemory.clean(name), v, savedAtMs, correctedFrom?.let(FoodMemory::clean)))
        return true
    }
    fun forget(name: String) { memory = memory.forget(name) }
}
