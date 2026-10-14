package com.example.trex_kotlin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 앱 내 업데이트(체험판, `docs/ANDROID_PREVIEW_RELEASE_1_3_0_6.md`) — GitHub 릴리스에 새 체험판이 올라오면 앱이 알리고, 한 번 눌러 내려받아 설치한다.
 *
 * - 출처는 저장소의 **사전 릴리스**(`/releases`, 초안 제외 — `/releases/latest` 는 사전 릴리스를 건너뛴다). 릴리스의 `BUILD_INFO.json` 이 정본이다:
 *   `versionCode`(설치본보다 커야 새 버전), `apkSHA256`(내려받은 파일 검증), `certificateSHA256`(설치본과 같아야 삭제 없이 업데이트된다 — 다르면 설치 대신 경고).
 * - 확인은 앱을 열 때 [CHECK_INTERVAL_MS] 마다 한 번(네트워크 실패는 조용히 넘긴다). "나중에" 는 [SNOOZE_MS] 동안 같은 태그를 다시 묻지 않는다.
 * - 내려받기는 앱 전용 외부 폴더 `updates/`(FileProvider `file_paths.xml`), SHA-256 이 맞을 때만 설치 화면을 연다. 설치는 시스템 패키지 설치기가 하므로
 *   Android 8+ 의 "출처를 알 수 없는 앱" 허용이 필요하다 — 없으면 그 설정 화면을 먼저 연다.
 * - 운동 중(세션·하위 화면)에는 묻지 않는다.
 *
 * 순수 로직(버전 비교·BUILD_INFO 파싱·해시·확인 주기)은 [AppUpdateLogic] 에 있어 JVM 테스트가 잠근다(`org.json` 은 유닛 테스트에서 스텁이라 여기서만 쓴다).
 */
object AppUpdate {
    private const val TAG = "AppUpdate"
    const val OWNER = "LeeDongHyun00"
    const val REPO = "TREX"
    private const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_SNOOZE_TAG = "snooze_tag"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val SNOOZE_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 15_000

    /** 릴리스 하나(사전 릴리스 포함, 초안 제외). */
    data class Release(
        val tag: String, val name: String, val body: String, val htmlUrl: String,
        val apkName: String, val apkUrl: String, val apkSize: Long, val buildInfoUrl: String?,
    )

    /** 설치본보다 새로운 릴리스 + 그 BUILD_INFO. */
    data class Available(val release: Release, val info: AppUpdateLogic.BuildInfo, val sameCertificate: Boolean) {
        val versionName: String get() = info.versionName
    }

    fun installedVersionCode(context: Context): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    /** 설치본 서명 인증서 SHA-256(소문자 hex). 못 읽으면 null. */
    fun installedCertificateSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val signer = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        signer?.let { AppUpdateLogic.sha256Hex(it.toByteArray()) }
    }.getOrNull()

    /** 확인 주기가 됐으면 서버를 묻는다. 새 버전이 없거나 미룬 태그면 null. 호출은 IO 스레드에서. */
    fun checkIfDue(context: Context, now: Long = System.currentTimeMillis(), force: Boolean = false): Available? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && !AppUpdateLogic.checkDue(now, prefs.getLong(KEY_LAST_CHECK, 0L), CHECK_INTERVAL_MS)) return null
        val latest = runCatching { fetchLatest() }.onFailure { Log.i(TAG, "릴리스 확인 실패: ${it.message}") }.getOrNull()
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (latest == null) return null
        val installed = installedVersionCode(context)
        if (!AppUpdateLogic.isNewer(latest.info.versionCode, installed)) { Log.i(TAG, "최신 ${latest.release.tag}(코드 ${latest.info.versionCode}) — 설치본 $installed 이 최신"); return null }
        if (!force && prefs.getString(KEY_SNOOZE_TAG, null) == latest.release.tag && !AppUpdateLogic.promptDue(now, prefs.getLong(KEY_SNOOZE_UNTIL, 0L))) return null
        val cert = installedCertificateSha256(context)
        val same = latest.info.certificateSha256 == null || cert == null || latest.info.certificateSha256.equals(cert, ignoreCase = true)
        Log.i(TAG, "새 버전 ${latest.release.tag}(코드 ${latest.info.versionCode}) — 설치본 $installed, 같은 서명 $same")
        return Available(latest.release, latest.info, same)
    }

    fun snooze(context: Context, tag: String, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SNOOZE_TAG, tag).putLong(KEY_SNOOZE_UNTIL, now + SNOOZE_MS).apply()
    }

    /** 가장 최근의 초안 아닌 릴리스 중 APK 가 있는 것 + BUILD_INFO. 없으면 null. 네트워크 예외는 그대로 던진다. */
    fun fetchLatest(): Available? {
        val arr = JSONArray(get(RELEASES_URL, accept = "application/vnd.github+json"))
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft", false)) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: Triple<String, String, Long>? = null; var buildInfo: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j); val name = a.optString("name")
                if (AppUpdateLogic.isApk(name) && apk == null) apk = Triple(name, a.optString("browser_download_url"), a.optLong("size"))
                if (name == "BUILD_INFO.json") buildInfo = a.optString("browser_download_url")
            }
            val (apkName, apkUrl, apkSize) = apk ?: continue
            val release = Release(r.optString("tag_name"), r.optString("name"), r.optString("body"), r.optString("html_url"), apkName, apkUrl, apkSize, buildInfo)
            val info = buildInfo?.let { AppUpdateLogic.parseBuildInfo(get(it)) } ?: continue   // BUILD_INFO 없는 릴리스는 버전을 모른다 — 건너뛴다
            return Available(release, info, sameCertificate = true)
        }
        return null
    }

    /** APK 를 앱 전용 `updates/` 에 내려받고 SHA-256 을 검증한다. [onProgress] 0..1. 검증 실패면 파일을 지우고 예외. */
    fun download(context: Context, a: Available, onProgress: (Float) -> Unit): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != a.release.apkName }?.forEach { it.delete() }   // 지난 업데이트 파일 정리
        val out = File(dir, a.release.apkName)
        val conn = open(a.release.apkUrl, accept = "application/octet-stream")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: a.release.apkSize
        val digest = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    os.write(buf, 0, n); digest.update(buf, 0, n); done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        val sha = AppUpdateLogic.toHex(digest.digest())
        if (a.info.apkSha256 != null && !sha.equals(a.info.apkSha256, ignoreCase = true)) { out.delete(); throw IllegalStateException("내려받은 파일의 해시가 릴리스와 다릅니다") }
        return out
    }

    /** 설치 화면을 연다. "출처를 알 수 없는 앱" 허용이 없으면 그 설정 화면을 열고 false. */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS; c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", accept); c.setRequestProperty("User-Agent", "TREX-Android")
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw IllegalStateException("HTTP $code $url") }
        return c
    }

    private fun get(url: String, accept: String = "*/*"): String = open(url, accept).let { c -> c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() } }
}

/** 업데이트 대화상자 — 앱을 열 때 확인하고, 새 버전이면 묻는다. [enabled] 가 거짓(운동 중)이면 확인도 표시도 하지 않는다. */
@Composable
fun AppUpdatePrompt(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<AppUpdate.Available?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled || checked) return@LaunchedEffect
        checked = true
        available = withContext(Dispatchers.IO) { runCatching { AppUpdate.checkIfDue(context) }.getOrNull() }
    }
    val a = available ?: return
    if (!enabled) return
    val sizeMb = a.release.apkSize / 1_000_000
    AlertDialog(
        onDismissRequest = { if (progress == null) { AppUpdate.snooze(context, a.release.tag); available = null } },
        title = { Text("새 버전 ${a.versionName}") },
        text = {
            Column {
                Text(AppUpdateLogic.summary(a.release.body))
                Spacer(Modifier.height(8.dp))
                Text(if (a.sameCertificate) "${sizeMb}MB · 기록과 설정은 그대로 남아요" else "서명이 달라 업데이트로 설치되지 않아요. 기록을 백업한 뒤 지우고 설치해야 해요")
                error?.let { Spacer(Modifier.height(8.dp)); Text(it) }
                progress?.let { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null && a.sameCertificate, onClick = {
                error = null; progress = 0f
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { AppUpdate.download(context, a) { p -> progress = p } } }
                    progress = null
                    result.onSuccess { file -> if (AppUpdate.install(context, file)) available = null else error = "'이 출처의 앱 허용' 을 켜고 다시 눌러 주세요" }
                        .onFailure { error = "내려받지 못했어요: ${it.message}" }
                }
            }) { Text(if (progress != null) "내려받는 중 ${((progress ?: 0f) * 100).toInt()}%" else "업데이트") }
        },
        dismissButton = { TextButton(enabled = progress == null, onClick = { AppUpdate.snooze(context, a.release.tag); available = null }) { Text("나중에") } },
    )
}
