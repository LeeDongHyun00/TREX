package com.example.trex_kotlin

import android.app.Application
import android.content.Context
import com.example.trex_kotlin.posture.SetLabelStore
import com.example.trex_kotlin.posture.SetSelfLabel
import com.example.trex_kotlin.sync.BinaryResponse
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.HttpResponse
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * 공유 앱 상태가 쓰는 플랫폼 기능의 Android 구현(§108). Android API 는 이 파일에 모으고, 공유 파일(ios-ui/android-sources.txt)은
 * 경계 인터페이스([AppPlatform]·[KeyValueStore]·[HttpTransport])만 본다. 동작은 경계를 만들기 전과 같다.
 * 이 파일은 iPhone 이 컴파일하지 않는다.
 */

class AndroidAppPlatform(private val app: Application) : AppPlatform {
    override fun preferences(name: String): KeyValueStore = SharedPreferencesStore(app, name)
    override val http: HttpTransport = HttpUrlConnectionTransport
    override val binary: BinaryTransport = HttpUrlConnectionBinaryTransport
    override val apiBaseUrl: String get() = BuildConfig.TREX_API_BASE_URL
    override val googleWebClientId: String get() = BuildConfig.GOOGLE_WEB_CLIENT_ID
    /** Apple 웹 로그인(설계 §9) — Services ID·Return URL 이 설정된 빌드만. 비어 있으면 눌러도 안 되는 버튼을 그리지 않는다. */
    override val appleSignInAvailable: Boolean get() = BuildConfig.APPLE_SERVICES_ID.isNotBlank() && BuildConfig.APPLE_REDIRECT_URI.isNotBlank()
    /** 저장소 빌드는 끝에 "-dev" 가 붙는다(§116) — 배포 빌드와 설정 화면에서도 갈린다. */
    override val appVersion: String get() = BuildConfig.VERSION_NAME
    override val appBuild: String get() = BuildConfig.VERSION_CODE.toString()
    override fun muscleLoadDatabase(): MuscleLoadDatabase = MuscleLoadStore(app)

    override fun appendSetLabel(label: SetSelfLabel) {
        val labelStore = SetLabelStore(app)
        // 파일 IO 는 메인 스레드 밖에서 — SetLogStore 저장과 같은 이유
        Thread { runCatching { labelStore.append(label) } }.start()
    }
}

/** 기존 호출(`TrexStore(context)`)을 그대로 둔다 — SharedPreferences `trex_store`(테스트는 다른 이름). */
fun TrexStore(context: Context, preferenceName: String = "trex_store"): TrexStore =
    TrexStore(SharedPreferencesStore(context, preferenceName))

/** Android 저장값은 AES-256-GCM으로 보호한다. 기존 호출과 KeyValueStore 계약은 유지한다. */
class SharedPreferencesStore(context: Context, name: String) : KeyValueStore by
    com.example.trex_kotlin.security.EncryptedPreferences(context, name)

/** HttpURLConnection 한 번 주고받기 — 경계를 만들기 전 TrexApi.call 의 연결 부분 그대로. IOException 은 [NetworkException] 으로 바꾼다. */
object HttpUrlConnectionTransport : HttpTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                    if (body != null) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = conn.responseCode
                    val stream = if (status < 400) conn.inputStream else conn.errorStream
                    HttpResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                throw NetworkException(e.message, e)
            }
        }
}

/** [BinaryTransport] 의 Android 구현 — 커뮤니티 사진 올리기·받기. 사진은 글자보다 커서 읽기 시간을 30 s 로 둔다. */
object HttpUrlConnectionBinaryTransport : BinaryTransport {
    override suspend fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinaryResponse =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 30_000
                    headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                    if (body != null) {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(body.size)
                        conn.outputStream.use { it.write(body) }
                    }
                    val status = conn.responseCode
                    val stream = if (status < 400) conn.inputStream else conn.errorStream
                    BinaryResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                throw NetworkException(e.message, e)
            }
        }
}
