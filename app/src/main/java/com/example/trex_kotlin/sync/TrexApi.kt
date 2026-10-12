package com.example.trex_kotlin.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 서버가 준 로그인 결과. [expiresAtMillis] 가 지나면 다시 로그인한다. */
data class AuthAccount(
    val token: String,
    val expiresAtMillis: Long,
    val loginId: String,
    val name: String,
    val email: String?,
)

/** HTTP 오류 응답. 서버의 {"message": …} 를 [message] 로 담는다. */
class ApiException(val status: Int, message: String) : IOException(message)

/**
 * T-REX 서버 API(docs/SERVER_SYNC_HANDOFF.md 4절). 라이브러리 없이 HttpURLConnection 으로 부른다 — 호출이 다섯 개뿐이다.
 * 모든 함수는 IO 스레드에서 돈다.
 */
class TrexApi(private val baseUrl: String, private val token: () -> String?) {

    sealed interface PutResult {
        val item: RemoteItem
        data class Applied(override val item: RemoteItem) : PutResult
        /** 서버 값이 더 최신이라 반영되지 않았다. [item] 이 서버 값이다. */
        data class Conflict(override val item: RemoteItem) : PutResult
    }

    val configured: Boolean get() = baseUrl.isNotBlank()

    suspend fun googleLogin(idToken: String): AuthAccount {
        val (_, body) = call("POST", "/api/auth/oauth/google", JSONObject().put("idToken", idToken), auth = false)
        val o = JSONObject(body)
        val user = o.getJSONObject("user")
        return AuthAccount(
            token = o.getString("accessToken"),
            expiresAtMillis = System.currentTimeMillis() + o.getLong("expiresInMillis"),
            loginId = user.getString("loginId"),
            name = user.optString("name"),
            email = if (user.isNull("email")) null else user.getString("email"),
        )
    }

    suspend fun syncItems(): List<RemoteItem> {
        val (_, body) = call("GET", "/api/sync")
        val arr = JSONObject(body).getJSONArray("items")
        return List(arr.length()) { i -> item(arr.getJSONObject(i)) }
    }

    suspend fun putItem(key: String, payload: String, updatedAt: Long): PutResult {
        val (status, body) = call(
            "PUT", "/api/sync/$key",
            JSONObject().put("payload", payload).put("updatedAt", updatedAt),
            accept409 = true,
        )
        val item = item(JSONObject(body))
        return if (status == 409) PutResult.Conflict(item) else PutResult.Applied(item)
    }

    suspend fun deleteAccount() {
        call("DELETE", "/api/auth/me")
    }

    private fun item(o: JSONObject) = RemoteItem(o.getString("key"), o.getString("payload"), o.getLong("updatedAt"))

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        accept409: Boolean = false,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!configured) throw IOException("서버 주소가 설정되지 않았습니다")
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            if (auth) token()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status >= 400 && !(accept409 && status == 409)) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw ApiException(status, message?.takeIf { it.isNotBlank() } ?: "서버 오류($status)")
            }
            status to text
        } finally {
            conn.disconnect()
        }
    }
}
