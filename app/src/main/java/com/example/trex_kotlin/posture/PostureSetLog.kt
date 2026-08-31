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
 *  "results":[{"rule_id":"바벨 스쿼트|발과 무릎의 방향 일치","verdict":"VIOLATION","value":0.004,"n":18,
 *              "baseline_applied":false,"value_rel":null}]}   // value 는 항상 절대값, verdict 는 재배치 반영
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
    /** **항상 절대값**(기준선 차감 전). 재보정 도구가 임계값과 직접 대조하는 값이라 좌표계를 고정한다. */
    val value: Float?,
    val sampleCount: Int,
    /** 판정에 기준선 재배치가 적용됐는가 (verdict 는 상대 판정). 스키마 호환 추가 필드. */
    val baselineApplied: Boolean = false,
    /** 재배치 적용 시의 상대값(value − 기준선중앙값). 미적용이면 null. */
    val valueRel: Float? = null,
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
    /** up 자가검증으로 뒤집어 보정한 프레임 수 / 방향을 검증한 프레임 수 (진단용, 스키마 호환 추가 필드). */
    val upFlippedFrames: Int = 0,
    val upVerifiedFrames: Int = 0,
    /** 자동 렙 카운트 (spec §27, 스키마 호환 추가 필드). null = 카운터 미적용 종목. */
    val repCount: Int? = null,
    val repTimesMs: List<Long>? = null,
    val repSignal: String? = null,
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
            repCount: Int? = null,
            repTimesMs: List<Long>? = null,
            repSignal: String? = null,
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
                results = results.map {
                    // 로그의 value 는 절대 좌표로 고정 — 재배치 세트와 비재배치 세트가 섞여도 해석이 갈리지 않는다
                    SetLogResult(it.rule.id, it.verdict.name, it.rawValue ?: it.value, it.sampleCount, it.baselineApplied, if (it.baselineApplied) it.value else null)
                },
                note = note,
                upFlippedFrames = samples.count { it.upFlipped },
                upVerifiedFrames = samples.count { it.upVerified },
                repCount = repCount,
                repTimesMs = repTimesMs,
                repSignal = repSignal,
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
        sb.append("\"up_flipped_frames\":").append(log.upFlippedFrames).append(',')
        sb.append("\"up_verified_frames\":").append(log.upVerifiedFrames).append(',')
        field(sb, "note", log.note)
        // 자동 렙 카운트 — 카운터가 돌았던 세트만 기록 (미적용 세트와 구분: 필드 부재 = 미적용)
        if (log.repCount != null) {
            sb.append("\"reps\":{")
            sb.append("\"count\":").append(log.repCount).append(',')
            field(sb, "signal", log.repSignal)
            sb.append("\"t_ms\":[")
            log.repTimesMs.orEmpty().forEachIndexed { i, t -> if (i > 0) sb.append(','); sb.append(t) }
            sb.append("]},")
        }
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
            sb.append("\"n\":").append(r.sampleCount).append(',')
            sb.append("\"baseline_applied\":").append(r.baselineApplied).append(',')
            sb.append("\"value_rel\":").append(num(r.valueRel))
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
 * 설치 단위 익명 수행자 ID — 재보정에서 **GroupKFold 그룹**으로 쓴다.
 * 같은 사람의 세트가 학습/검증에 갈라져 들어가면 성능이 부풀려지므로, 사람 구분자가 반드시 필요하다.
 * 기기·계정과 무관한 난수라 개인 식별 정보가 아니다.
 */
object SubjectId {
    private const val PREFS = "trex_posture"
    private const val KEY = "subject_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = "s-" + UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString(KEY, id).apply()
        return id
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
