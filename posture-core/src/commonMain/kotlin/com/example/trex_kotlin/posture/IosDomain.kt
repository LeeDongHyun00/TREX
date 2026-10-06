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
