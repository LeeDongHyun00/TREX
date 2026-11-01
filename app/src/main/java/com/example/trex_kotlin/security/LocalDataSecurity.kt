package com.example.trex_kotlin.security

import android.content.Context
import com.example.trex_kotlin.MuscleLoadStore
import java.io.File

/** 앱 상태·동기화가 생성되기 전에 실행한다. 실패 시 기존 기록을 유지한 채 진입을 보류한다. */
internal object LocalDataSecurity {
    fun prepare(context: Context) {
        val app = context.applicationContext
        AndroidKeyCipher.checkExistingKey(app)
        listOf("trex_auth", "trex_store", "trex_sync", "trex_posture", "exercise_guides",
            "trex_weather_cache_prefs", "trex_weather_location_prefs").forEach { EncryptedPreferences(app, it) }
        val files = AndroidKeyCipher.files
        listOf("food_memory.tsv", "food_memory.tsv.tmp", "posture_baseline.tsv").forEach { files.migrate(File(app.filesDir, it)) }
        listOfNotNull(app.getExternalFilesDir(null), app.filesDir).distinct().forEach { root ->
            val dir = File(root, "posture_logs")
            dir.walkTopDown().filter { it.isFile && (it.name.endsWith(".jsonl") || it.name.endsWith(".csv") || it.name.endsWith(".aes")) }
                .map { if (it.name.endsWith(".aes")) File(it.path.removeSuffix(".aes")) else it }
                .toList().distinct().forEach(files::migrate)
        }
        MuscleLoadStore(app).use { it.writableDatabase }
        // 명시적으로 내보낸 평문 사본은 하루 뒤 앱 시작에서 정리한다. 원본 저장소에는 평문을 쓰지 않는다.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        File(app.cacheDir, "posture_export").walkBottomUp().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
    }
}
