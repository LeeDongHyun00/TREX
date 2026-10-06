package com.example.trex_kotlin

import java.security.MessageDigest

/** 앱 내 업데이트([AppUpdate])의 순수 로직 — Android·`org.json` 없이 JVM 테스트가 잠근다. */
object AppUpdateLogic {
    /** 릴리스의 `BUILD_INFO.json`(정본). 해시·인증서는 없을 수 있다(옛 릴리스). */
    data class BuildInfo(val versionName: String, val versionCode: Long, val apkSha256: String?, val certificateSha256: String?)

    fun isNewer(latestCode: Long, installedCode: Long): Boolean = latestCode > installedCode
    fun isApk(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)
    fun checkDue(now: Long, lastCheckAt: Long, intervalMs: Long): Boolean = now - lastCheckAt >= intervalMs || now < lastCheckAt
    fun promptDue(now: Long, snoozedUntil: Long): Boolean = now >= snoozedUntil

    /**
     * BUILD_INFO.json 의 네 필드만 정규식으로 읽는다 — 유닛 테스트에서 `org.json` 이 스텁이라 파서를 쓰지 않는다. `versionCode` 가 없으면 null(버전을 모르는 릴리스는 건너뛴다).
     */
    fun parseBuildInfo(text: String): BuildInfo? {
        fun str(key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        val code = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return BuildInfo(str("versionName") ?: "", code, str("apkSHA256")?.lowercase(), str("certificateSHA256")?.lowercase())
    }

    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** 릴리스 본문의 앞부분 — 제목 줄·빈 줄·링크를 걷고 글머리 몇 줄만(대화상자용). */
    fun summary(body: String, maxLines: Int = 6, maxChars: Int = 360): String {
        var fence = false
        val lines = body.lines().map { it.trim() }
            .filter { line -> if (line.startsWith("```")) { fence = !fence; false } else !fence }   // 코드 울타리 안은 통째로 뺀다
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") && !it.startsWith("|") }
            .map { it.removePrefix("- ").removePrefix("* ").replace("**", "").replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1") }
        val out = StringBuilder()
        for (l in lines.take(maxLines)) {
            if (out.length + l.length > maxChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append("· ").append(l)
        }
        return out.toString().ifEmpty { "새 체험판이 올라왔어요" }
    }
}
