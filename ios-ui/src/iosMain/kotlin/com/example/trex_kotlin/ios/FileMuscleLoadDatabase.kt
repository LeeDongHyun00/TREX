package com.example.trex_kotlin.ios

import com.example.trex_kotlin.LoadSetJson
import com.example.trex_kotlin.MuscleLoadDatabase
import com.example.trex_kotlin.trainingload.LoadLedger
import com.example.trex_kotlin.trainingload.LoadSet
import com.example.trex_kotlin.trainingload.Muscle
import com.example.trex_kotlin.trainingload.MuscleDose
import com.example.trex_kotlin.trainingload.MuscleLoadEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 근육 부하 장부의 iPhone 구현(§108 Phase 2) — 앱 지원 폴더의 JSON 파일 하나. Android SQLite(MuscleLoadStore)와 같은 의미다:
 *  - 구기록(legacy)은 처음 한 번만 들인다(`legacy_v1`).
 *  - 같은 세트(id)를 다시 넣으면 원본 JSON 이 같을 때는 건너뛰고, 다르면 지우고 새로 넣는다(SQLite REPLACE — 부하도 다시 잰다).
 *  - 보정은 자세 세트 id 가 같은 세트의 실제 횟수만 바꿔 다시 넣는다. 보관 기간(90일)보다 오래된 세트는 지운다.
 *  - 돌려주는 세트는 원본 JSON 에서 다시 읽은 값이고 끝난 시각 순이다. 부하가 없는 세트는 부하 표에 없다.
 * 쓰기는 한 번에(임시 파일 → 바꾸기) — 실패하면 던진다(앱 상태가 '불러오지 못했습니다' 로 밝힌다).
 */
class FileMuscleLoadDatabase(private val path: String) : MuscleLoadDatabase {

    private class Row(val raw: String, val set: LoadSet, val doses: List<MuscleDose>)

    override fun sync(current: List<LoadSet>, legacy: List<LoadSet>, now: Long, corrections: Map<String, Double?>): LoadLedger {
        val file = readTextFile(path)?.let(::JSONObject)
        var legacyImported = file?.optBoolean("legacy_v1", false) ?: false
        val rows = LinkedHashMap<String, Row>()
        file?.optJSONArray("rows")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val raw = o.getString("raw")
                val doses = o.getJSONArray("doses").let { d -> List(d.length()) { j -> d.getJSONObject(j).let { MuscleDose(Muscle.valueOf(it.getString("m")), it.getString("s"), it.getDouble("d")) } } }
                val set = LoadSetJson.decode(JSONObject(raw))
                rows[set.id] = Row(raw, set, doses)
            }
        }

        fun put(set: LoadSet) {
            val raw = LoadSetJson.encode(set).toString()
            if (rows[set.id]?.raw == raw) return
            rows.remove(set.id)
            rows[set.id] = Row(raw, LoadSetJson.decode(JSONObject(raw)), MuscleLoadEngine.doses(set))
        }

        if (!legacyImported) {
            legacy.forEach(::put)
            legacyImported = true
        }
        current.forEach(::put)
        corrections.forEach { (postureSetId, actual) ->
            rows.values.filter { it.set.postureSetId == postureSetId }.map { it.set.copy(actualReps = actual) }.forEach(::put)
        }
        rows.values.removeAll { it.set.endedAt < now - MuscleLoadEngine.RETENTION_MS }

        val out = JSONObject().put("version", 1).put("legacy_v1", legacyImported).put("rows", JSONArray().also { arr ->
            rows.values.forEach { row ->
                val doses = JSONArray()
                row.doses.forEach { d -> doses.put(JSONObject().put("m", d.muscle.name).put("s", d.side).put("d", d.dose)) }
                arr.put(JSONObject().put("raw", row.raw).put("doses", doses))
            }
        })
        writeTextFile(path, out.toString())

        val sorted = rows.values.sortedBy { it.set.endedAt }
        return LoadLedger(sorted.map { it.set }, sorted.filter { it.doses.isNotEmpty() }.associate { it.set.id to it.doses })
    }
}
