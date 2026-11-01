package com.example.trex_kotlin.posture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 세트 로그(JSONL) 내보내기 — Android 공유 시트로 파일을 전달한다 (adb 없이 회수).
 * FileProvider 는 AndroidManifest 의 `${applicationId}.fileprovider` + res/xml/file_paths.xml 에 선언되어 있다.
 */
object SetLogExport {

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** 저장된 로그 파일 전부를 공유 시트로 보낸다. 파일이 없으면 false. */
    suspend fun share(context: Context, store: SetLogStore): Boolean {
        val chooser = withContext(Dispatchers.IO) {
            val files = store.exportFiles()
            if (files.isEmpty()) return@withContext null
            val uris = ArrayList<Uri>(files.size)
            // 사용자가 내보내기를 선택할 때만 복호화한다. 매번 별도 캐시 경로를 써 기존 공유 URI를 덮지 않는다.
            val exportDir = File(context.cacheDir, "posture_export/${UUID.randomUUID()}").apply { mkdirs() }
            for (f in files) {
                val copy = File(exportDir, f.relativeTo(store.directory).path).apply { parentFile?.mkdirs() }
                copy.writeText(store.readText(f), Charsets.UTF_8)
                uris += FileProvider.getUriForFile(context, authority(context), copy)
            }
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }
            send.type = if (files.any { it.name.endsWith(".csv") }) "application/octet-stream" else "application/json"
            send.putExtra(Intent.EXTRA_SUBJECT, "TREX 자세 세트 로그 (${store.totalSets()}세트, ${files.size}파일)")
            send.putExtra(Intent.EXTRA_TEXT, "직접 내보낸 파일은 암호화되지 않은 자세 로그입니다. trex.posture.setlog/1 — research/aihub_fitness/calibrate_from_logs.py 로 재보정")
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Intent.createChooser(send, "세트 로그 내보내기").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } ?: return false
        withContext(Dispatchers.Main) { context.startActivity(chooser) }
        return true
    }
}
