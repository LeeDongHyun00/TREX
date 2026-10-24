package com.example.trex_kotlin.posture

// PostureSetLabel 의 SetSelfLabel·SetLabelFormat 정본 슬라이스(§108).
data class SetSelfLabel(
    val setId: String,
    val exercise: String,
    val actualReps: Int?,
    /** "edited" | "confirmed" | null(렙 라벨 없음). */
    val repsSource: String?,
    val form: FormLabel?,
    val createdAtIso: String,
    /**
     * 라벨을 적을 때 화면에 보인 횟수 단위(`RepUnit.key` — "cycle"|"side_pair"), 모르면 null(키를 쓰지 않는다 — 이전 줄과 같은 형식).
     * 런지류(좌우 짝, spec §59)의 [actualReps] 는 걸음이 아니라 **좌우 한 쌍**의 수다 — 세트 로그를 잇지 않고도 라벨을 읽을 수 있게 함께 적는다.
     * rep_truth.csv 에는 넣지 않는다(열이 고정이다 — 헤더가 바뀌면 기존 파일과 섞여 파싱이 깨진다).
     */
    val repUnit: String? = null,
)

/**
 * 라벨 두 줄의 형식(§108) — Android([SetLabelStore])와 iPhone 이 같은 줄을 쓰도록 형식만 따로 둔다(엔진 동기화가 이 부분을 잘라 간다).
 * JSON 문자열 이스케이프는 세트 로그(SetLogJson.str)와 같은 규칙이다 — org.json 과 달리 '/' 를 이스케이프하지 않는다.
 */
object SetLabelFormat {
    const val LABELS_DIR = "labels"
    const val LABELS_FILE = "set_labels.jsonl"
    const val TRUTH_FILE = "rep_truth.csv"
    const val TRUTH_HEADER = "set_id,reps_min,reps_max,exercise,form,source,created_at"

    fun encodeJson(label: SetSelfLabel): String {
        val sb = StringBuilder(192)
        sb.append('{')
        sb.append("\"set_id\":"); str(sb, label.setId); sb.append(',')
        sb.append("\"exercise\":"); str(sb, label.exercise); sb.append(',')
        sb.append("\"actual_reps\":").append(label.actualReps?.toString() ?: "null").append(',')
        sb.append("\"reps_source\":")
        if (label.repsSource == null) sb.append("null") else str(sb, label.repsSource)
        sb.append(',')
        sb.append("\"form\":")
        if (label.form == null) sb.append("null") else str(sb, label.form.key)
        sb.append(',')
        sb.append("\"created_at\":"); str(sb, label.createdAtIso)
        // 표시 단위는 알 때만 — 없으면 키 부재(이전 형식 그대로)
        if (label.repUnit != null) { sb.append(",\"rep_unit\":"); str(sb, label.repUnit) }
        sb.append('}')
        return sb.toString()
    }

    /** rep_truth.csv 의 한 줄. 렙이 없으면 null — research/aihub_fitness/rep_replay.py 가 int() 로 읽으므로 그 행은 넣지 않는다. */
    fun truthRow(label: SetSelfLabel): String? {
        val reps = label.actualReps ?: return null
        return listOf(
            label.setId, reps.toString(), reps.toString(), label.exercise,
            label.form?.key ?: "", label.repsSource ?: "", label.createdAtIso,
        ).joinToString(",") { csv(it) }
    }

    /** RFC 4180: 콤마·따옴표·줄바꿈이 있으면 따옴표로 감싸고 내부 따옴표는 두 번. */
    fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}
