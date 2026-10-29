package com.example.trex_kotlin.routines

import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.sync.HttpResponse
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.sync.TrexApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 앱 주소 해석과 루틴 공유 API(docs/superpowers/specs/2026-10-29-routine-share-design.md §4). */
class AppLinkTest {

    private class FakeHttp(private val status: Int, private val response: String) : HttpTransport {
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var body: String? = null
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            this.url = url
            this.headers = headers
            this.body = body
            return HttpResponse(status, response)
        }
    }

    private val squat = Workout("x1", "기본 스쿼트", "3세트 x 12회", "5분", true, "하체")

    @Test
    fun parsesSchemeAndWebLinks() {
        assertEquals(AppLink.Routine("Ab3dE6gH"), parseAppLink("trex://r/Ab3dE6gH"))
        assertEquals(AppLink.Post(42), parseAppLink("trex://p/42"))
        assertEquals(AppLink.Routine("Ab3dE6gH"), parseAppLink("https://trex-api.duckdns.org/r/Ab3dE6gH"))
        assertEquals(AppLink.Post(7), parseAppLink("https://trex-api.duckdns.org/p/7"))
        assertNull(parseAppLink("trex://r/short"))
        assertNull(parseAppLink("trex://r/Ab3dE6g!"))
        assertNull(parseAppLink("trex://p/-1"))
        assertNull(parseAppLink("trex://p/0"))
        assertNull(parseAppLink("https://x.com/q/1"))
        assertNull(parseAppLink(""))
    }

    @Test
    fun shareSendsRoutineAndReturnsUrl() = runBlocking {
        val http = FakeHttp(201, """{"code":"Ab3dE6gH","url":"https://trex-api.duckdns.org/r/Ab3dE6gH"}""")
        val url = TrexApi("https://api.example", http) { "tok" }.shareRoutine("하체", 1500, listOf(squat))
        assertEquals("https://api.example/api/routines/share", http.url)
        assertEquals("Bearer tok", http.headers["Authorization"])
        val sent = JSONObject(http.body!!)
        assertEquals("하체", sent.getString("name"))
        assertEquals(1500, sent.getInt("totalSeconds"))
        assertEquals(1, sent.getJSONArray("exercises").length())
        assertEquals("https://trex-api.duckdns.org/r/Ab3dE6gH", url)
    }

    @Test
    fun sharedRoutineIsReadWithoutToken() = runBlocking {
        val ex = """{"name":"기본 스쿼트","reps":"3세트 x 12회","duration":"5분","posture":true,"category":"하체"}"""
        val http = FakeHttp(200, """{"name":"하체 불태우기","senderNickname":"윤성","totalSeconds":1500,"exercises":[$ex],"createdAt":"2026-10-29T00:00:00Z"}""")
        val r = TrexApi("https://api.example/", http) { "tok" }.sharedRoutine("Ab3dE6gH")
        assertEquals("https://api.example/api/routines/shared/Ab3dE6gH", http.url)
        assertNull(http.headers["Authorization"])
        assertEquals("하체 불태우기", r.name)
        assertEquals("윤성", r.senderNickname)
        assertEquals(1500, r.totalSeconds)
        assertEquals("recv-0", r.workouts.single().id)
    }

    @Test
    fun postShareUrl() {
        assertEquals("https://api.example/p/42", TrexApi("https://api.example/", FakeHttp(200, "{}")) { null }.postShareUrl(42))
    }
}
