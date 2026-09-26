package com.example.trex_kotlin.posture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

/**
 * 세트 로그(JSONL) 내보내기 — Android 공유 시트로 파일을 전달한다 (adb 없이 회수).
 * FileProvider 는 AndroidManifest 의 `${applicationId}.fileprovider` + res/xml/file_paths.xml 에 선언되어 있다.
 */
object SetLogExport {

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** 저장된 로그 파일 전부를 공유 시트로 보낸다. 파일이 없으면 false. */
    fun share(context: Context, store: SetLogStore): Boolean {
        val files = store.files()
        if (files.isEmpty()) return false
        val uris = ArrayList<Uri>(files.size)
        for (f in files) uris += FileProvider.getUriForFile(context, authority(context), f)
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        send.type = "application/json"
        send.putExtra(Intent.EXTRA_SUBJECT, "TREX 자세 세트 로그 (${store.totalSets()}세트, ${files.size}파일)")
        send.putExtra(Intent.EXTRA_TEXT, "trex.posture.setlog/1 — research/aihub_fitness/calibrate_from_logs.py 로 재보정")
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "세트 로그 내보내기")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return true
    }
}
