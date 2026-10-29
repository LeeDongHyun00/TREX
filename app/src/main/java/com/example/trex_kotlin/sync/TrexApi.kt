package com.example.trex_kotlin.sync

import com.example.trex_kotlin.nowMillis
import org.json.JSONObject

/** 서버가 준 로그인 결과. [expiresAtMillis] 가 지나면 다시 로그인한다. */
data class AuthAccount(
    val token: String,
    val expiresAtMillis: Long,
    val loginId: String,
    val name: String,
    val email: String?,
)

/**
 * 서버에 닿지 못했다(주소 없음·연결·시간 초과·읽기 실패). 플랫폼 전송([HttpTransport])이 자기 IO 예외를 이것으로 바꿔 던진다(§108).
 * 예전의 java.io.IOException 자리를 그대로 대신한다.
 */
open class NetworkException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/** HTTP 오류 응답. 서버의 {"message": …} 를 [message] 로 담는다. */
class ApiException(val status: Int, message: String) : NetworkException(message)

/** HTTP 응답 한 건. 4xx·5xx 도 응답이다 — 판정은 [TrexApi] 가 한다. */
class HttpResponse(val status: Int, val body: String)

/** HTTP 한 번 주고받기(§108). Android 는 HttpURLConnection(10 s 연결·20 s 읽기). 연결·읽기 실패는 [NetworkException] 으로 던진다. */
fun interface HttpTransport {
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/**
 * T-REX 서버 API(docs/SERVER_SYNC_HANDOFF.md 4절). 라이브러리 없이 플랫폼 전송으로 부른다 — 호출이 네 개뿐이다.
 * 전송은 IO 스레드에서 돈다.
 */
class TrexApi(private val baseUrl: String, private val transport: HttpTransport, private val token: () -> String?) {

    sealed interface PutResult {
        val item: RemoteItem
        data class Applied(override val item: RemoteItem) : PutResult
        /** 서버 값이 더 최신이라 반영되지 않았다. [item] 이 서버 값이다. */
        data class Conflict(override val item: RemoteItem) : PutResult
    }

    val configured: Boolean get() = baseUrl.isNotBlank()

    /** 어느 서버인지. 같은 계정이라도 서버가 다르면 백업도 다르다(로컬 개발 서버 ↔ 운영 서버). */
    val serverId: String get() = baseUrl.trimEnd('/')

    suspend fun googleLogin(idToken: String): AuthAccount =
        account(call("POST", "/api/auth/oauth/google", JSONObject().put("idToken", idToken), auth = false).second)

    /** iPhone Apple 로그인(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md) — 응답은 구글과 같은 모양. */
    suspend fun appleLogin(credential: AppleCredential): AuthAccount {
        val body = JSONObject().put("identityToken", credential.identityToken).put("nonce", credential.nonce)
        credential.authorizationCode?.let { body.put("authorizationCode", it) }
        return account(call("POST", "/api/auth/oauth/apple", body, auth = false).second)
    }

    /** 아이디·비밀번호 가입(D13) — 가입하면 바로 로그인된 계정을 준다. 아이디·이름·이메일은 앞뒤 공백을 지워 보낸다. */
    suspend fun signup(form: SignupForm): AuthAccount {
        val body = JSONObject()
            .put("loginId", form.loginId.trim())
            .put("password", form.password)
            .put("name", form.name.trim())
            .put("email", form.email.trim())
        return account(call("POST", "/api/auth/signup", body, auth = false).second)
    }

    /** 아이디·비밀번호 로그인(D13). 비밀번호는 적은 그대로 보낸다. */
    suspend fun passwordLogin(loginId: String, password: String): AuthAccount {
        val body = JSONObject().put("loginId", loginId.trim()).put("password", password)
        return account(call("POST", "/api/auth/login", body, auth = false).second)
    }

    private fun account(body: String): AuthAccount {
        val o = JSONObject(body)
        val user = o.getJSONObject("user")
        return AuthAccount(
            token = o.getString("accessToken"),
            expiresAtMillis = nowMillis() + o.getLong("expiresInMillis"),
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

    /** 서버 오류의 문구 — 칸별 오류(가입 규칙 등)가 있으면 그중 하나, 없으면 "message". */
    private fun errorMessage(text: String): String? = runCatching {
        val o = JSONObject(text)
        val fields = o.optJSONObject("fieldErrors")
        val field = fields?.keys()?.asSequence()?.map { fields.optString(it) }?.firstOrNull { it.isNotBlank() }
        field ?: o.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        accept409: Boolean = false,
    ): Pair<Int, String> {
        if (!configured) throw NetworkException("서버 주소가 설정되지 않았습니다")
        val headers = buildMap {
            put("Accept", "application/json")
            if (auth) token()?.let { put("Authorization", "Bearer $it") }
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        }
        val response = transport.send(method, baseUrl.trimEnd('/') + path, headers, body?.toString())
        val status = response.status
        val text = response.body
        if (status >= 400 && !(accept409 && status == 409)) {
            throw ApiException(status, errorMessage(text) ?: "서버 오류($status)")
        }
        return status to text
    }
}
