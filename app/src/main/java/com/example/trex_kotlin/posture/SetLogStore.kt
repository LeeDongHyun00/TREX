package com.example.trex_kotlin.posture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// 세트 로그의 Android 파일 쪽(§108c) — 형식(SetLog·SetLogJson)은 PostureSetLog.kt 를 두 플랫폼이 같이 쓰고, 저장은 플랫폼마다 따로다
// (iPhone: ios-ui 의 같은 이름 SetLogStore, Documents/posture_logs). 숫자 고정 소수(`formatFixed`)도 여기 — Android 는 String.format 그대로라 로그 바이트가 바뀌지 않는다.

/** 렙 검증 모드 표시 파일(spec §61) — [dir] = `context.getExternalFilesDir(null)`. 폴더가 없거나 읽을 수 없으면 꺼짐. */
fun RepValidation.isOn(dir: File?): Boolean = runCatching { dir != null && File(dir, FLAG_FILE).isFile }.getOrDefault(false)

/** 세트 로그 숫자의 고정 소수(§14) — 과학적 표기·지역화(콤마) 없이. Android 는 종전 `String.format(Locale.US, "%.Nf")` 그대로다. */
internal fun formatFixed(v: Float, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

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

    companion object {
        /**
         * 세트 로그 쓰기 전용 스레드(앱 수명) — 화면 수명과 무관하게 순서대로 쓴다. 세트마다 새 `Thread` 를 띄우면 화면이 사라지는 순간의
         * 기록이 다른 정리 작업과 경합했고(2026-09-26 13:57 — 그때는 분석기 크래시가 프로세스를 죽였다), 실패는 조용히 삼켜졌다.
         */
        val writer: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "set-log-writer").apply { isDaemon = false }
        }
    }
}
