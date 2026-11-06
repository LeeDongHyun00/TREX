package com.example.trex_kotlin.community

import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.BinaryResponse
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.HttpResponse
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 커뮤니티 API 클라이언트 — 서버(`/api/community` 아래)와 같은 경로·본문·오류(설계 §6, 계획 1 의 서버 구현). */
class CommunityApiTest {

    private class Call(val method: String, val url: String, val headers: Map<String, String>, val text: String?, val bytes: ByteArray?)

    private class FakeHttp(var status: Int = 200, var reply: String = "{}", var failing: Boolean = false) : HttpTransport {
        val calls = ArrayList<Call>()
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            calls += Call(method, url, headers, body, null)
            if (failing) throw NetworkException("연결 실패")
            return HttpResponse(status, reply)
        }
    }

    private class FakeBinary(var status: Int = 201, var reply: String = "{}") : BinaryTransport {
        val calls = ArrayList<Call>()
        override suspend fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinaryResponse {
            calls += Call(method, url, headers, null, body)
            return BinaryResponse(status, reply.encodeToByteArray())
        }
    }

    private val http = FakeHttp()
    private val binary = FakeBinary()
    private val api = CommunityApi("https://api.example/", http, binary) { "t" }

    private fun post(id: Long, hidden: Boolean = false) = """
        {"id":$id,"author":{"publicId":"P1","nickname":"공룡","avatarUrl":null},"body":null,
         "images":[{"url":"https://s3/x.jpg?sig","width":1080,"height":810}],
         "likeCount":3,"likedByMe":true,"commentCount":2,"createdAt":1700000000000,
         "hidden":$hidden,"hiddenReason":${if (hidden) "\"신고 누적\"" else "null"},"mine":false}
    """.trimIndent()

    @Test
    fun 피드는_before를_쿼리로_보내고_글을_읽는다() = runBlocking {
        http.reply = """{"items":[${post(12)},${post(11, hidden = true)}],"nextBefore":null}"""
        val page = api.feed(before = 13)
        val call = http.calls.single()
        assertEquals("GET", call.method)
        assertEquals("https://api.example/api/community/posts?before=13", call.url)
        assertEquals("Bearer t", call.headers["Authorization"])
        assertNull(page.nextBefore)
        val first = page.items[0]
        assertEquals(12L, first.id)
        assertNull(first.body)
        assertNull(first.author.avatarUrl)
        assertNull(first.hiddenReason)
        assertEquals(PostImage("https://s3/x.jpg?sig", 1080, 810), first.images.single())
        assertTrue(first.likedByMe)
        assertEquals("신고 누적", page.items[1].hiddenReason)
    }

    @Test
    fun 글쓰기는_multipart로_본문과_사진을_보낸다() = runBlocking {
        binary.reply = post(20)
        val created = api.createPost("오늘 오운완", listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertEquals(20L, created.id)
        val call = binary.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.example/api/community/posts", call.url)
        assertEquals("Bearer t", call.headers["Authorization"])
        val type = call.headers["Content-Type"]!!
        assertTrue(type, type.startsWith("multipart/form-data; boundary="))
        val text = call.bytes!!.decodeToString()
        assertTrue(text.contains("name=\"body\""))
        assertTrue(text.contains("오늘 오운완"))
        assertTrue(text.contains("name=\"images\"; filename=\"photo0.jpg\""))
        assertTrue(text.contains("name=\"images\"; filename=\"photo1.jpg\""))
    }

    @Test
    fun 글만_쓰면_사진_칸이_없다() = runBlocking {
        binary.reply = post(21)
        api.createPost("글만", emptyList())
        assertFalse(binary.calls.single().bytes!!.decodeToString().contains("name=\"images\""))
    }

    @Test
    fun 서버_오류_문구를_그대로_올린다() = runBlocking {
        binary.status = 429
        binary.reply = """{"message":"잠시 뒤에 다시 올려 주세요"}"""
        try {
            api.createPost("또", emptyList())
            fail("ApiException 이어야 한다")
        } catch (e: ApiException) {
            assertEquals(429, e.status)
            assertEquals("잠시 뒤에 다시 올려 주세요", e.message)
        }
    }

    @Test
    fun 연결_실패는_NetworkException() = runBlocking {
        http.failing = true
        try {
            api.me()
            fail("NetworkException 이어야 한다")
        } catch (e: NetworkException) {
            assertFalse(e is ApiException)
        }
    }

    @Test
    fun 신고는_대상과_사유를_JSON으로() = runBlocking {
        http.status = 201
        http.reply = ""
        api.report(ReportTarget.POST, "5", ReportReason.ABUSE)
        val call = http.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.example/api/community/reports", call.url)
        val sent = JSONObject(call.text!!)
        assertEquals("POST", sent.getString("targetType"))
        assertEquals("5", sent.getString("targetId"))
        assertEquals("ABUSE", sent.getString("reason"))
    }

    @Test
    fun 내_상태와_이용규칙_동의() = runBlocking {
        http.reply = """{"profile":null,"termsAccepted":false,"admin":true,"suspended":false}"""
        val me = api.me()
        assertNull(me.profile)
        assertTrue(me.admin)
        http.status = 204
        http.reply = ""
        api.acceptTerms()
        val sent = JSONObject(http.calls.last().text!!)
        assertEquals(CommunityApi.TERMS_VERSION, sent.getString("version"))
    }

    @Test
    fun 좋아요는_PUT_취소는_DELETE() = runBlocking {
        http.status = 204
        http.reply = ""
        api.like(7)
        api.unlike(7)
        assertEquals(listOf("PUT", "DELETE"), http.calls.map { it.method })
        assertEquals("https://api.example/api/community/posts/7/like", http.calls[0].url)
    }

    @Test
    fun 관리자_댓글_숨김과_복구_프로필_사진_지우기() = runBlocking {
        http.status = 204
        http.reply = ""
        api.hideComment(9)
        api.restoreComment(9)
        api.adminClearAvatar("P1")
        assertEquals(listOf("POST", "POST", "DELETE"), http.calls.map { it.method })
        assertEquals(
            listOf(
                "https://api.example/api/community/admin/comments/9/hide",
                "https://api.example/api/community/admin/comments/9/restore",
                "https://api.example/api/community/admin/users/P1/avatar",
            ),
            http.calls.map { it.url },
        )
    }
}
