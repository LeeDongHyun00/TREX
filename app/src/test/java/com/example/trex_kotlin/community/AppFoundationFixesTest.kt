package com.example.trex_kotlin.community

import com.example.trex_kotlin.LruCache
import com.example.trex_kotlin.sync.BinaryResponse
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.HttpResponse
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** 계획 2 전체 리뷰(2026-10-29) 수정 — 각 테스트가 리뷰 지적 하나를 재현한다. */
class AppFoundationFixesTest {

    // I1: 받은 사진 캐시는 개수가 아니라 바이트 예산으로 자른다
    @Test
    fun LruCache는_크기_합으로_자른다() {
        val cache = LruCache<String, Int>(maxSize = 10, sizeOf = { it })
        cache.put("a", 4)
        cache.put("b", 4)
        cache.put("c", 4)
        assertNull("합이 12 > 10 이라 가장 오래된 a 가 빠진다", cache.get("a"))
        assertEquals(4, cache.get("b"))
        cache.put("huge", 11)
        assertNull("예산보다 큰 하나는 담지 않는다", cache.get("huge"))
        assertEquals(4, cache.get("c"))
    }

    // I1: 로더가 디코더에 원하는 크기를 넘기고, 캐시 예산을 그림 크기로 센다
    @Test
    fun 로더는_디코더에_크기를_넘기고_예산을_그림_크기로_센다() = runBlocking {
        val asked = ArrayList<Int>()
        val binary = BinaryTransport { _, url, _, _ -> BinaryResponse(200, url.encodeToByteArray()) }
        val loader = RemoteImageLoader(binary, { bytes, max -> asked += max; bytes.decodeToString() }, maxBytes = 10, sizeOf = { 6 })
        loader.load("https://s3/a.jpg?sig=1", 320)
        loader.load("https://s3/b.jpg?sig=1", 320)
        assertEquals(listOf(320, 320), asked)
        assertNull("6 + 6 > 10 이라 먼저 받은 a 는 빠진다", loader.cached("https://s3/a.jpg?sig=9", 320))
    }

    // M1·M8: 키는 서명 주소의 경로(객체 키) + 크기 — 다시 서명해도 같고, 사진을 바꾸면(새 객체 키) 다르다
    @Test
    fun 사진_키는_서명을_뺀_경로와_크기다() {
        assertEquals("https://b.s3/avatars/P-1.jpg@1080", remoteImageKey("https://b.s3/avatars/P-1.jpg?X-Amz-Signature=abc", 1080))
        assertEquals(remoteImageKey("https://b.s3/posts/7/0-u.jpg?sig=1", 1080), remoteImageKey("https://b.s3/posts/7/0-u.jpg?sig=2", 1080))
        assert(remoteImageKey("https://b.s3/posts/7/0-u.jpg?sig=1", 1080) != remoteImageKey("https://b.s3/posts/7/0-u.jpg?sig=1", 320))
    }

    // M3: 전송의 예상 밖 예외는 실패 그림으로 끝난다(화면까지 올라가 앱이 죽지 않게)
    @Test
    fun 전송이_다른_예외를_던져도_실패로_끝난다() = runBlocking {
        val binary = BinaryTransport { _, _, _, _ -> throw IllegalStateException("예상 밖") }
        val loader = RemoteImageLoader(binary, { bytes, _ -> bytes.decodeToString() })
        assertNull(loader.load("https://s3/x.jpg?sig=1", 1080))
    }

    // M4: 200 이지만 JSON 이 아닌 응답(공용 와이파이 로그인 페이지)은 NetworkException
    @Test
    fun 읽을_수_없는_응답은_NetworkException() = runBlocking {
        val http = HttpTransport { _, _, _, _ -> HttpResponse(200, "<html>로그인하세요</html>") }
        val api = CommunityApi("https://api.example", http, { _, _, _, _ -> BinaryResponse(200, ByteArray(0)) }) { "t" }
        try {
            api.feed(null)
            fail("NetworkException 이어야 한다")
        } catch (e: NetworkException) {
            assertEquals("서버 응답을 읽지 못했습니다", e.message)
        }
    }

    // M4: 서버가 신고 사유를 늘려도 옛 앱의 "내 신고"가 죽지 않는다
    @Test
    fun 모르는_신고_사유는_기타로_읽는다() = runBlocking {
        val http = HttpTransport { _, _, _, _ ->
            HttpResponse(200, """[{"id":1,"targetType":"POST","targetId":"5","reason":"NEW_REASON","createdAt":1,"status":"PENDING","resolution":null}]""")
        }
        val api = CommunityApi("https://api.example", http, { _, _, _, _ -> BinaryResponse(200, ByteArray(0)) }) { "t" }
        assertEquals(ReportReason.OTHER, api.myReports().single().reason)
    }
}
