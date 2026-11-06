package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.*
import org.json.JSONObject

// §108: MuscleLoadStore.kt(Android SQLite)에서 옮겼다 — 기록 저장(TrexStore)도 같은 형식을 써서 iPhone 에서도 컴파일돼야 한다.
/** 기존 기록 안에도 원본을 보관하여 SQLite 저장 실패/프로세스 재시작 때 같은 ID로 재전송한다. */
internal object LoadSetJson {
    fun encode(s: LoadSet) = JSONObject().apply {
        put("id", s.id); put("session", s.sessionId); put("exercise", s.exerciseId); put("ended", s.endedAt)
        put("uncertainty", s.timeUncertaintyMs); put("reps", s.reps); put("seconds", s.seconds); put("unit", s.unit.name)
        s.left?.let { put("left", it) }; s.right?.let { put("right", it) }
        put("weight", s.weightKg); put("height", s.heightCm); put("source", s.source)
        s.postureSetId?.let { put("postureSet", it) }; s.actualReps?.let { put("actual", it) }
        s.judged?.let { put("judged", it) }; s.abstained?.let { put("abstained", it) }; s.accuracy?.let { put("accuracy", it) }
        put("version", s.modelVersion)
        put("unknownSides",s.unknownSideSteps);put("extraSteps",s.extraSteps)
    }
    fun decode(o: JSONObject) = LoadSet(
        id=o.getString("id"), sessionId=o.getString("session"), exerciseId=o.getString("exercise"), endedAt=o.getLong("ended"),
        timeUncertaintyMs=o.optLong("uncertainty"), reps=o.optDouble("reps", 0.0), seconds=o.optDouble("seconds", 0.0),
        unit=LoadUnit.valueOf(o.getString("unit")), left=number(o,"left"), right=number(o,"right"),
        weightKg=o.optDouble("weight",70.0), heightCm=o.optDouble("height",170.0), source=o.getString("source"),
        postureSetId=o.optString("postureSet").takeIf { it.isNotEmpty() }, actualReps=number(o,"actual"),
        judged=number(o,"judged")?.toInt(), abstained=number(o,"abstained")?.toInt(), accuracy=number(o,"accuracy")?.toInt(),
        modelVersion=o.optInt("version",1),unknownSideSteps=o.optInt("unknownSides"),extraSteps=o.optInt("extraSteps"))
    private fun number(o: JSONObject, key: String) = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
}
