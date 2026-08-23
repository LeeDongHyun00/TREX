package com.example.trex_kotlin.posture

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 개인 기준선(personal baseline) — spec §15/§16/§17.
 *
 * 연구 결과: 개인별 임계값 재적합은 이득이 0 이지만, **level 피처(mean/min/max)** 를 쓰는 일부 규칙은
 * "값 − 사용자 정자세 기준선" 으로 판정하면 좋아진다(`personal_baseline.eligible`). 기준선은 사용자가
 * 앱에서 **정자세로 k(=3)세트** 를 찍으면 세트별 집계값의 중앙값으로 만든다 — 라벨도, 오류 세트도 필요 없다.
 * 중앙값이라 세트 1개가 잘못돼도 버틴다(§16 실험 4).
 *
 * 기준선이 있으면 eligible 규칙은 `rule.baselineThresholdRel` 과 비교한다(`PostureRuleSet.evaluate(baseline=…)`).
 */

/** 한 종목의 기준선: feature(base__stat) → 정자세 세트 통계값의 중앙값. */
data class ExerciseBaseline(
    val exercise: String,
    val values: Map<String, Float>,
    /** 세트별 원값 (투명성·재계산용). */
    val setValues: Map<String, List<Float>>,
    val nSets: Int,
    val createdAtIso: String,
)

/** 사용자 전체 기준선 (종목별). */
class BaselineProfile(private val map: MutableMap<String, ExerciseBaseline> = LinkedHashMap()) {
    fun get(exercise: String): ExerciseBaseline? = map[exercise]
    fun valuesFor(exercise: String): Map<String, Float>? = map[exercise]?.values
    fun put(b: ExerciseBaseline) { map[b.exercise] = b }
    fun remove(exercise: String) { map.remove(exercise) }
    fun has(exercise: String): Boolean = map.containsKey(exercise)
    val exercises: List<String> get() = map.keys.toList()
    fun all(): List<ExerciseBaseline> = map.values.toList()
    fun copy(): BaselineProfile = BaselineProfile(LinkedHashMap(map))
}

/**
 * 기준선 수집 세션: 세트마다 eligible 피처의 집계값을 모으고, k세트가 차면 중앙값으로 기준선을 만든다.
 * @param features rule.feature 목록 (예: "torso_incl__mean")
 */
class BaselineCollector(
    val exercise: String,
    val features: List<String>,
    val requiredSets: Int = DEFAULT_SETS,
) {
    private val sets = ArrayList<Map<String, Float>>()

    val completedSets: Int get() = sets.size
    val isComplete: Boolean get() = sets.size >= requiredSets

    fun addSet(values: Map<String, Float>) { sets += values }
    fun removeLast() { if (sets.isNotEmpty()) sets.removeAt(sets.size - 1) }
    fun reset() { sets.clear() }
    fun perSet(): List<Map<String, Float>> = sets.toList()

    /** 피처별 중앙값. 값이 있는 세트가 2개 미만인 피처는 제외(그 규칙은 기준선 없이 절대 임계값으로 판정). */
    fun build(nowIso: String = SetLog.nowIso()): ExerciseBaseline {
        val values = LinkedHashMap<String, Float>()
        val sv = LinkedHashMap<String, List<Float>>()
        for (f in features) {
            val xs = sets.mapNotNull { it[f] }.filter { it.isFinite() }
            if (xs.size >= 2) {
                values[f] = median(xs)
                sv[f] = xs
            }
        }
        return ExerciseBaseline(exercise, values, sv, sets.size, nowIso)
    }

    companion object {
        const val DEFAULT_SETS = 3

        fun median(xs: List<Float>): Float {
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
        }

        /** 집계기에서 feature(base__stat) 들의 세트 통계값을 뽑는다. 프레임이 minFrames 미만이면 그 피처는 빠진다. */
        fun setValues(agg: FeatureAggregator, features: List<String>, minFrames: Int = 8): Map<String, Float> {
            val out = LinkedHashMap<String, Float>()
            for (f in features) {
                val i = f.lastIndexOf("__")
                if (i <= 0) continue
                val base = f.substring(0, i)
                val stat = f.substring(i + 2)
                if (agg.count(base) >= minFrames) {
                    agg.stat(base, stat)?.let { if (it.isFinite()) out[f] = it }
                }
            }
            return out
        }
    }
}

/**
 * 기준선 저장소 — `filesDir/posture_baseline.tsv`, org.json 비의존.
 * 한 줄 = 종목 1개: `exercise<TAB>nSets<TAB>createdAt<TAB>feature=median:v1,v2,v3;feature2=...`
 */
class BaselineStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "posture_baseline.tsv"))

    val path: File get() = file

    fun load(): BaselineProfile {
        val p = BaselineProfile()
        if (!file.exists()) return p
        file.useLines(Charsets.UTF_8) { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 4) continue
                val exercise = cols[0]
                val nSets = cols[1].toIntOrNull() ?: 0
                val created = cols[2]
                val values = LinkedHashMap<String, Float>()
                val sv = LinkedHashMap<String, List<Float>>()
                for (entry in cols[3].split(';')) {
                    if (entry.isBlank()) continue
                    val eq = entry.indexOf('=')
                    if (eq <= 0) continue
                    val feature = entry.substring(0, eq)
                    val rest = entry.substring(eq + 1)
                    val colon = rest.indexOf(':')
                    val med = (if (colon >= 0) rest.substring(0, colon) else rest).toFloatOrNull() ?: continue
                    values[feature] = med
                    if (colon >= 0) sv[feature] = rest.substring(colon + 1).split(',').mapNotNull { it.toFloatOrNull() }
                }
                if (values.isNotEmpty()) p.put(ExerciseBaseline(exercise, values, sv, nSets, created))
            }
        }
        return p
    }

    fun save(p: BaselineProfile) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append("# trex posture baseline v1 — exercise\\tnSets\\tcreatedAt\\tfeature=median:set1,set2,...;...\n")
        for (b in p.all()) {
            sb.append(b.exercise.replace('\t', ' ')).append('\t').append(b.nSets).append('\t').append(b.createdAtIso).append('\t')
            var first = true
            for ((f, v) in b.values) {
                if (!first) sb.append(';')
                first = false
                sb.append(f).append('=').append(fmt(v))
                val xs = b.setValues[f]
                if (!xs.isNullOrEmpty()) sb.append(':').append(xs.joinToString(",") { fmt(it) })
            }
            sb.append('\n')
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    fun put(b: ExerciseBaseline): BaselineProfile {
        val p = load()
        p.put(b)
        save(p)
        return p
    }

    fun remove(exercise: String): BaselineProfile {
        val p = load()
        p.remove(exercise)
        save(p)
        return p
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.6g", v)
}
