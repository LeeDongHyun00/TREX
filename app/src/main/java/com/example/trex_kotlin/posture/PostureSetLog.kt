package com.example.trex_kotlin.posture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 재보정 데이터 수집용 세트 로그 (spec §9 / §14).
 *
 * 한 세트(기록 구간)의 프레임 피처·가시성·추론 통계·규칙 판정을 JSON 한 줄(JSON Lines)로 남긴다.
 * 오프라인 도구 `research/aihub_fitness/calibrate_from_logs.py` 가 이 로그와 코치 라벨(set_id 기준 CSV)을 읽어
 * 규칙 임계값을 재적합한다. 따라서 **프레임 피처는 원본 그대로**(집계 전) 기록한다 — 집계 창 정의를 바꿔도 재계산 가능.
 *
 * 스키마 `trex.posture.setlog/1`:
 * ```
 * {"schema":"trex.posture.setlog/1","set_id":"...","created_at":"2026-08-22T01:02:03Z","subject_id":null,
 *  "exercise":"바벨 스쿼트","rules_version":"mp_v0","model":"full","delegate":"GPU","front_camera":true,
 *  "up_from_gravity":true,"tilt_deg":3.2,"sample_interval_ms":300,"note":null,
 *  "frames":[{"t_ms":0,"infer_ms":63,"visible":22,"vis":[...33],"features":{"knee_L":112.3,...}}, ...],
 *  "results":[{"rule_id":"바벨 스쿼트|발과 무릎의 방향 일치","verdict":"VIOLATION","value":0.004,"n":18}]}
 * ```
 * org.json 은 Android 유닛 테스트에서 스텁이라 직접 직렬화한다 (PostureCoreParityTest 와 같은 이유).
 */

data class SetLogFrame(
    val tMs: Long,
    val inferMs: Long,
    val visibleJointCount: Int,
    /** 33개 랜드마크 가시성(min(visibility, presence)). null 이면 기록 생략. */
    val visibility: FloatArray?,
    val features: Map<String, Float>,
)

data class SetLogResult(
    val ruleId: String,
    val verdict: String,
    val value: Float?,
    val sampleCount: Int,
)

data class SetLog(
    val setId: String,
    val createdAtIso: String,
    /** 수행자 식별자(선택). 재보정 시 GroupKFold 그룹으로 쓰이므로 같은 사람이면 같은 값을 넣는다. */
    val subjectId: String?,
    val exercise: String,
    val rulesVersion: String,
    val model: String,
    val delegate: String,
    val frontCamera: Boolean,
    val upFromGravity: Boolean,
    val tiltDeg: Float?,
    val sampleIntervalMs: Long,
    val frames: List<SetLogFrame>,
    val results: List<SetLogResult>,
    val note: String? = null,
) {
    companion object {
        const val SCHEMA = "trex.posture.setlog/1"

        fun newSetId(now: Date = Date()): String {
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
            return stamp + "-" + UUID.randomUUID().toString().substring(0, 8)
        }

        fun nowIso(now: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

        /**
         * 랩 화면의 기록 결과로부터 세트 로그를 만든다.
         * @param samples 기록 구간에서 추론된 프레임들 (detected 가 false 인 프레임은 features 가 비어 있어도 남긴다)
         * @param sampleTimesMs 각 프레임의 타임스탬프(ms). null 이면 샘플 간격으로 합성
         */
        fun build(
            exercise: String,
            samples: List<PoseSample>,
            results: List<RuleResult>,
            rulesVersion: String,
            model: String,
            delegate: String,
            frontCamera: Boolean,
            sampleIntervalMs: Long,
            sampleTimesMs: List<Long>? = null,
            subjectId: String? = null,
            note: String? = null,
            includeVisibility: Boolean = true,
            now: Date = Date(),
        ): SetLog {
            val frames = samples.mapIndexed { i, s ->
                SetLogFrame(
                    tMs = sampleTimesMs?.getOrNull(i) ?: (i * sampleIntervalMs),
                    inferMs = s.inferMs,
                    visibleJointCount = s.visibleJointCount,
                    visibility = if (includeVisibility && s.detected) s.visibility else null,
                    features = if (s.detected) s.features else emptyMap(),
                )
            }
            val upFromGravity = samples.any { it.upFromGravity }
            val tilt = samples.lastOrNull { it.upFromGravity }?.let { tiltFromScreenUpDegrees(it.up) }
            return SetLog(
                setId = newSetId(now),
                createdAtIso = nowIso(now),
                subjectId = subjectId,
                exercise = exercise,
                rulesVersion = rulesVersion,
                model = model,
                delegate = delegate,
                frontCamera = frontCamera,
                upFromGravity = upFromGravity,
                tiltDeg = tilt,
                sampleIntervalMs = sampleIntervalMs,
                frames = frames,
                results = results.map { SetLogResult(it.rule.id, it.verdict.name, it.value, it.sampleCount) },
                note = note,
            )
        }
    }
}

/** 의존성 없는 JSON 직렬화 (문자열 이스케이프, NaN/Inf → null). */
object SetLogJson {

    fun encode(log: SetLog): String {
        val sb = StringBuilder(16 * 1024)
        sb.append('{')
        field(sb, "schema", SetLog.SCHEMA)
        field(sb, "set_id", log.setId)
        field(sb, "created_at", log.createdAtIso)
        field(sb, "subject_id", log.subjectId)
        field(sb, "exercise", log.exercise)
        field(sb, "rules_version", log.rulesVersion)
        field(sb, "model", log.model)
        field(sb, "delegate", log.delegate)
        sb.append("\"front_camera\":").append(log.frontCamera).append(',')
        sb.append("\"up_from_gravity\":").append(log.upFromGravity).append(',')
        sb.append("\"tilt_deg\":").append(num(log.tiltDeg)).append(',')
        sb.append("\"sample_interval_ms\":").append(log.sampleIntervalMs).append(',')
        field(sb, "note", log.note)
        sb.append("\"frames\":[")
        log.frames.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"t_ms\":").append(f.tMs).append(',')
            sb.append("\"infer_ms\":").append(f.inferMs).append(',')
            sb.append("\"visible\":").append(f.visibleJointCount).append(',')
            sb.append("\"vis\":")
            val vis = f.visibility
            if (vis == null) sb.append("null") else {
                sb.append('[')
                vis.forEachIndexed { k, v -> if (k > 0) sb.append(','); sb.append(num(v, 3)) }
                sb.append(']')
            }
            sb.append(',')
            sb.append("\"features\":{")
            var first = true
            for ((k, v) in f.features) {
                if (!first) sb.append(',')
                first = false
                str(sb, k); sb.append(':').append(num(v))
            }
            sb.append("}}")
        }
        sb.append("],")
        sb.append("\"results\":[")
        log.results.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            field(sb, "rule_id", r.ruleId)
            field(sb, "verdict", r.verdict)
            sb.append("\"value\":").append(num(r.value)).append(',')
            sb.append("\"n\":").append(r.sampleCount)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun field(sb: StringBuilder, key: String, value: String?) {
        str(sb, key); sb.append(':')
        if (value == null) sb.append("null") else str(sb, value)
        sb.append(',')
    }

    internal fun num(v: Float?, decimals: Int = 5): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "null"
        // 고정 소수: 과학적 표기/지역화(콤마) 방지
        val s = String.format(Locale.US, "%.${decimals}f", v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }.let { if (it == "-0") "0" else it }
    }

    internal fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}

/**
 * 세트 로그 저장소: `<externalFilesDir>/posture_logs/sets-yyyyMMdd.jsonl` 에 한 줄씩 추가.
 * 외부 앱 전용 저장소라 권한이 필요 없고, `adb pull` 또는 공유 시트로 꺼내 재보정 도구에 넣는다.
 */
class SetLogStore(private val dir: File) {

    constructor(context: Context) : this(File(context.getExternalFilesDir(null) ?: context.filesDir, "posture_logs"))

    val directory: File get() = dir

    fun append(log: SetLog, now: Date = Date()): File {
        dir.mkdirs()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val file = File(dir, "sets-$day.jsonl")
        file.appendText(SetLogJson.encode(log) + "\n", Charsets.UTF_8)
        return file
    }

    fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()

    fun totalSets(): Int = files().sumOf { f -> f.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } } }

    fun totalBytes(): Long = files().sumOf { it.length() }

    fun clear() {
        files().forEach { it.delete() }
    }
}
