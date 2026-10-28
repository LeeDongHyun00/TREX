package com.example.trex_kotlin.community

import com.example.trex_kotlin.LruCache
import com.example.trex_kotlin.sync.BinaryResponse
import com.example.trex_kotlin.sync.BinaryTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 원격 사진 — 안정 키로 캐시(서명 주소는 바뀐다), 같은 키는 한 번만 받기, 만료 주소 403 은 실패(계획 2 Review Focus 1·5). */
class RemoteImageLoaderTest {

    /** 주소별 응답을 돌려주고 받은 횟수를 센다. [gate] 가 있으면 그것이 풀릴 때까지 기다린다. */
    private class FakeBinary(private val status: (String) -> Int, private val gate: CompletableDeferred<Unit>? = null) : BinaryTransport {
        val calls = AtomicInteger()
        override suspend fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinaryResponse {
            calls.incrementAndGet()
            gate?.await()
            return BinaryResponse(status(url), url.encodeToByteArray())
        }
    }

    /** 가짜 디코더 — 그림 대신 받은 바이트(주소)를 문자열로. */
    private val decode: (ByteArray, Int) -> String? = { bytes, _ -> bytes.decodeToString() }

    @Test
    fun 캐시에_있으면_다시_받지_않는다() = runBlocking {
        val binary = FakeBinary({ 200 })
        val loader = RemoteImageLoader(binary, decode)
        assertEquals("https://s3/a?sig=1", loader.load("post:1:0", "https://s3/a?sig=1", 1080))
        assertEquals("https://s3/a?sig=1", loader.load("post:1:0", "https://s3/a?sig=2", 1080))
        assertEquals(1, binary.calls.get())
    }

    @Test
    fun 같은_키는_한_번만_받는다() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val binary = FakeBinary({ 200 }, gate)
        val loader = RemoteImageLoader(binary, decode)
        val first = async { loader.load("post:2:0", "https://s3/b", 1080) }
        val second = async { loader.load("post:2:0", "https://s3/b", 1080) }
        while (binary.calls.get() == 0) kotlinx.coroutines.yield()
        gate.complete(Unit)
        assertEquals("https://s3/b", first.await())
        assertEquals("https://s3/b", second.await())
        assertEquals(1, binary.calls.get())
    }

    @Test
    fun 만료된_주소의_403은_실패로_끝난다() = runBlocking {
        val binary = FakeBinary({ url -> if (url.endsWith("old")) 403 else 200 })
        val loader = RemoteImageLoader(binary, decode)
        assertNull(loader.load("post:3:0", "https://s3/c?old", 1080))
        assertNull(loader.cached("post:3:0"))
        assertEquals("https://s3/c?new", loader.load("post:3:0", "https://s3/c?new", 1080))
        assertEquals(2, binary.calls.get())
    }

    @Test
    fun LruCache_넘치면_가장_오래_안_쓴_것부터() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
    }
}
