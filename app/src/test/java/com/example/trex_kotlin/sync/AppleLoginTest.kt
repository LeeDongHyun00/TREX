package com.example.trex_kotlin.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Apple 로그인 — 서버에 세 값을 보내고 계정을 읽는다, 설정의 계정 줄 이름(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §3·§4). */
class AppleLoginTest {

    private class FakeHttp : HttpTransport {
        var url = ""
        var body: String? = null
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            this.url = url
            this.body = body
            return HttpResponse(200, """{"accessToken":"jwt","expiresInMillis":1000,"user":{"id":1,"loginId":"apple_x","name":"T-REX 사용자","email":null}}""")
        }
    }

    @Test
    fun apple_로그인은_세_값을_보내고_계정을_읽는다() = runBlocking {
        val http = FakeHttp()
        val account = TrexApi("https://api.example/", http) { null }.appleLogin(AppleCredential("id-token", "code-1", "raw-nonce"))
        assertEquals("https://api.example/api/auth/oauth/apple", http.url)
        val sent = JSONObject(http.body!!)
        assertEquals("id-token", sent.getString("identityToken"))
        assertEquals("code-1", sent.getString("authorizationCode"))
        assertEquals("raw-nonce", sent.getString("nonce"))
        assertEquals("apple_x", account.loginId)
        assertEquals("jwt", account.token)
        assertEquals(null, account.email)
    }

    @Test
    fun authorization_code_가_없으면_보내지_않는다() = runBlocking {
        val http = FakeHttp()
        TrexApi("https://api.example", http) { null }.appleLogin(AppleCredential("id-token", null, "raw-nonce"))
        assertFalse(JSONObject(http.body!!).has("authorizationCode"))
    }

    @Test
    fun 계정_줄_이름() {
        fun account(loginId: String, name: String, email: String?) = AuthAccount("t", 0, loginId, name, email)
        // 로그인 방법을 앞에 — 구글 계정을 Apple 계정으로 알고 Apple 만 탈퇴한 일(10-29)
        assertEquals("Apple · dino@icloud.com", accountLabel(account("apple_x", "", "dino@icloud.com")))
        assertEquals("Apple 계정", accountLabel(account("apple_x", "T-REX 사용자", null)))
        assertEquals("Google · 윤성", accountLabel(account("google_x", "윤성", null)))
        assertEquals("Google 계정", accountLabel(account("google_x", "", null)))
        assertEquals("카카오 · k@kakao.com", accountLabel(account("kakao_1", "", "k@kakao.com")))
    }
}
