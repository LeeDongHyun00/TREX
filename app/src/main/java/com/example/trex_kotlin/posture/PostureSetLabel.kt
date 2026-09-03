package com.example.trex_kotlin.posture

import android.content.Context
import java.io.File

/**
 * 세트 자가 라벨 (spec §30) — 완료 화면에서 사용자가 남긴 "실제 렙 수"와 "폼 자평".
 *
 * 두 파일에 쓴다(SetLogStore 와 같은 posture_logs 아래라 adb pull 한 번에 같이 나온다):
 *  - `labels/set_labels.jsonl`: 라벨 전부(렙·폼 어느 쪽이 없어도) 한 줄씩 — 앱 쪽 진실 기록.
 *    **하위 폴더**에 두는 이유: SetLogStore.files()/clear()/totalSets() 와 pull_logs.py 가 `*.jsonl` 로 세트 로그를 고르므로
 *    같은 폴더에 두면 랩의 "지우기"가 라벨을 삭제하고 세트 수에 라벨 줄이 섞인다.
 *  - `rep_truth.csv`: actualReps 가 있을 때만 한 줄 — research/aihub_fitness/rep_replay.py 가
 *    `set_id,reps_min,reps_max` 컬럼을 int() 로 읽으므로 렙이 없는 행은 넣지 않는다(파싱이 깨진다).
 *    사용자 라벨은 단일 값이라 reps_min == reps_max. `source` 열은 edited(사용자가 고친 값) / confirmed(앱 카운트를 보고
 *    "맞아요" 로 확인한 값) — 확인만 한 값은 앱 카운트와 같으므로 재생 검증에서 순환 참조가 될 수 있어 구분해 둔다.
 * org.json 은 유닛 테스트에서 스텁이라 SetLogJson 과 같은 방식으로 직접 직렬화한다.
 */
data class SetSelfLabel(
    val setId: String,
    val exercise: String,
    val actualReps: Int?,
    /** "edited" | "confirmed" | null(렙 라벨 없음). */
    val repsSource: String?,
    val form: FormLabel?,
    val createdAtIso: String,
)

class SetLabelStore(private val dir: File) {

    constructor(context: Context) : this(File(context.getExternalFilesDir(null) ?: context.filesDir, "posture_logs"))

    val directory: File get() = dir
    val labelsFile: File get() = File(File(dir, LABELS_DIR), LABELS_FILE)
    val truthFile: File get() = File(dir, TRUTH_FILE)

    /** 저장마다 새 스레드에서 불리므로 헤더 검사·두 파일 쓰기를 한 임계구역에 둔다(헤더가 두 번 들어가면 rep_replay 가 죽는다). */
    fun append(label: SetSelfLabel) {
        synchronized(LOCK) {
            labelsFile.parentFile?.mkdirs()
            labelsFile.appendText(encodeJson(label) + "\n", Charsets.UTF_8)
            val reps = label.actualReps ?: return
            val truth = truthFile
            // 헤더는 파일이 없거나 비어 있을 때만 — 중간에 헤더가 또 들어가면 csv.DictReader 가 데이터 행으로 읽는다
            if (!truth.exists() || truth.length() == 0L) truth.appendText(TRUTH_HEADER + "\n", Charsets.UTF_8)
            val row = listOf(
                label.setId, reps.toString(), reps.toString(), label.exercise,
                label.form?.key ?: "", label.repsSource ?: "", label.createdAtIso,
            )
            truth.appendText(row.joinToString(",") { csv(it) } + "\n", Charsets.UTF_8)
        }
    }

    /** set_labels.jsonl 의 줄 수. */
    fun count(): Int {
        val f = labelsFile
        if (!f.exists()) return 0
        return f.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } }
    }

    companion object {
        const val LABELS_DIR = "labels"
        const val LABELS_FILE = "set_labels.jsonl"
        const val TRUTH_FILE = "rep_truth.csv"
        const val TRUTH_HEADER = "set_id,reps_min,reps_max,exercise,form,source,created_at"
        private val LOCK = Any()

        internal fun encodeJson(label: SetSelfLabel): String {
            val sb = StringBuilder(192)
            sb.append('{')
            sb.append("\"set_id\":"); SetLogJson.str(sb, label.setId); sb.append(',')
            sb.append("\"exercise\":"); SetLogJson.str(sb, label.exercise); sb.append(',')
            sb.append("\"actual_reps\":").append(label.actualReps?.toString() ?: "null").append(',')
            sb.append("\"reps_source\":")
            if (label.repsSource == null) sb.append("null") else SetLogJson.str(sb, label.repsSource)
            sb.append(',')
            sb.append("\"form\":")
            if (label.form == null) sb.append("null") else SetLogJson.str(sb, label.form.key)
            sb.append(',')
            sb.append("\"created_at\":"); SetLogJson.str(sb, label.createdAtIso)
            sb.append('}')
            return sb.toString()
        }

        /** RFC 4180: 콤마·따옴표·줄바꿈이 있으면 따옴표로 감싸고 내부 따옴표는 두 번. */
        internal fun csv(v: String): String =
            if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}
