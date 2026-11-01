package com.example.trex_kotlin.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** 아이디·비밀번호 가입·로그인(docs/SERVER_SYNC_HANDOFF.md D13) — 서버와 같은 규칙, 서버 호출, 칸별 오류 문구, 계정 줄 이름. */
class PasswordAccountTest {

    private class FakeHttp(private val status: Int = 200, private val response: String = OK) : HttpTransport {
        var url = ""
        var body: String? = null
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            this.url = url
            this.body = body
            return HttpResponse(status, response)
        }

        companion object {
            const val OK = """{"accessToken":"jwt","expiresInMillis":1000,"user":{"id":1,"loginId":"trex_user","name":"지민","email":"jimin@trex.app"}}"""
        }
    }

    private val good = SignupForm(
        name = "지민", loginId = "trex_user", password = "password123", passwordConfirm = "password123",
        email = "jimin@trex.app", agreed = true,
    )

    @Test
    fun 아이디는_영문_숫자_밑줄_4에서_20자() {
        assertNull(loginIdProblem("trex_user"))
        assertNull(loginIdProblem("T1_" + "a".repeat(17)))
        for (bad in listOf("abc", "한글아이디", "abc-def", "abc def", "a".repeat(21))) {
            assertEquals(bad, "아이디는 영문·숫자·밑줄 4~20자예요", loginIdProblem(bad))
        }
    }

    @Test
    fun 비밀번호는_영문과_숫자를_섞은_8에서_64자() {
        assertNull(passwordProblem("password123"))
        assertNull(passwordProblem("pass word 123!"))
        assertEquals("비밀번호는 8~64자예요", passwordProblem("pass123"))
        assertEquals("비밀번호는 8~64자예요", passwordProblem("a1".repeat(33)))
        assertEquals("비밀번호는 영문과 숫자를 섞어 주세요", passwordProblem("password"))
        assertEquals("비밀번호는 영문과 숫자를 섞어 주세요", passwordProblem("12345678"))
    }

    @Test
    fun 가입_양식은_위에서부터_첫_문제를_알려준다() {
        assertNull(signupProblem(good))
        assertEquals("이름을 적어 주세요", signupProblem(good.copy(name = "  ")))
        assertEquals("이름은 20자까지예요", signupProblem(good.copy(name = "가".repeat(21))))
        assertEquals("아이디는 영문·숫자·밑줄 4~20자예요", signupProblem(good.copy(loginId = "ab", password = "x")))
        assertEquals("비밀번호는 영문과 숫자를 섞어 주세요", signupProblem(good.copy(password = "password", passwordConfirm = "password")))
        assertEquals("비밀번호가 서로 달라요", signupProblem(good.copy(passwordConfirm = "password124")))
        assertEquals("이메일 형식을 확인해 주세요", signupProblem(good.copy(email = "jimin@trex")))
        assertEquals("개인정보 처리방침에 동의해 주세요", signupProblem(good.copy(agreed = false)))
    }

    @Test
    fun 가입은_네_값을_다듬어_보내고_계정을_읽는다() = runBlocking {
        val http = FakeHttp(201)
        val account = TrexApi("https://api.example/", http) { null }
            .signup(good.copy(name = " 지민 ", loginId = " trex_user ", email = " jimin@trex.app "))
        assertEquals("https://api.example/api/auth/signup", http.url)
        val sent = JSONObject(http.body!!)
        assertEquals("trex_user", sent.getString("loginId"))
        assertEquals("password123", sent.getString("password"))
        assertEquals("지민", sent.getString("name"))
        assertEquals("jimin@trex.app", sent.getString("email"))
        assertEquals("trex_user", account.loginId)
        assertEquals("jwt", account.token)
    }

    @Test
    fun 로그인은_아이디와_비밀번호를_보낸다() = runBlocking {
        val http = FakeHttp()
        val account = TrexApi("https://api.example", http) { null }.passwordLogin(" trex_user ", " pw 123 ")
        assertEquals("https://api.example/api/auth/login", http.url)
        val sent = JSONObject(http.body!!)
        assertEquals("trex_user", sent.getString("loginId"))
        assertEquals(" pw 123 ", sent.getString("password"))
        assertEquals("trex_user", account.loginId)
    }

    @Test
    fun 서버가_칸별_오류를_주면_그_문구를_보인다() = runBlocking {
        val http = FakeHttp(400, """{"message":"입력값이 올바르지 않습니다","fieldErrors":{"loginId":"아이디는 영문·숫자·밑줄 4~20자여야 합니다"}}""")
        try {
            TrexApi("https://api.example", http) { null }.signup(good)
            fail("400 은 ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
            assertEquals("아이디는 영문·숫자·밑줄 4~20자여야 합니다", e.message)
        }
    }

    @Test
    fun 아이디_회원의_계정_줄은_아이디다() {
        assertEquals("아이디 · trex_user", accountLabel(AuthAccount("t", 0, "trex_user", "지민", "jimin@trex.app")))
        assertEquals("Apple · dino@icloud.com", accountLabel(AuthAccount("t", 0, "apple_x", "", "dino@icloud.com")))
        assertEquals("Google 계정", accountLabel(AuthAccount("t", 0, "google_x", "", null)))
    }
}
