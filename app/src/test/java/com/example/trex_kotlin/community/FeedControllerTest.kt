package com.example.trex_kotlin.community

import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 피드 컨트롤러 — 쪽 받기·좋아요 보내기(한 번에 하나, 마지막 값으로)·실패 문구(계획 3 Review Focus 2). */
class FeedControllerTest {
    private val author = CommunityProfile("abc123def456", "티렉스", null)
    private fun post(id: Long, likes: Long = 0, liked: Boolean = false) =
        Post(id, author, "글 $id", emptyList(), likes, liked, 0, 1_000L * id, false, null, false)

    /** 좋아요 요청을 붙잡아 두었다가 하나씩 풀어 주는 가짜 서버. */
    private class FakeLikes {
        val sent = ArrayList<Boolean>()
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var fail: Exception? = null
        suspend fun send(liked: Boolean) {
            sent += liked
            val gate = CompletableDeferred<Unit>().also { gates.addLast(it) }
            gate.await()
            fail?.let { throw it }
        }
        fun release() = gates.removeFirst().complete(Unit)
    }

    private fun controller(likes: FakeLikes, pages: Map<Long?, PostPage> = mapOf(null to PostPage(listOf(post(1, likes = 3)), null))) =
        FeedController(
            load = { before -> pages[before] ?: throw NetworkException("없음") },
            setLike = { _, liked -> likes.send(liked) },
            scope = CoroutineScope(Dispatchers.Unconfined),
            now = { 0L },
        )

    @Test
    fun 빠르게_세_번_눌러도_요청은_차례로_하나씩이고_마지막_값으로_맞는다() = runBlocking {
        val likes = FakeLikes()
        val feed = controller(likes)
        feed.refresh()
        feed.toggleLike(1) // 좋아요 보냄(붙잡힘)
        feed.toggleLike(1) // 취소 — 보내는 중이라 기다린다
        feed.toggleLike(1) // 다시 좋아요
        assertEquals(listOf(true), likes.sent)
        assertEquals(4L, feed.state.shown(1)?.likeCount)
        likes.release() // 첫 요청 성공 — 바라는 값(좋아요)과 같으니 더 보내지 않는다
        assertEquals(listOf(true), likes.sent)
        assertEquals(true, feed.state.shown(1)?.likedByMe)
        assertEquals(4L, feed.state.shown(1)?.likeCount)
        assertNull(feed.state.pendingLike(1))
    }

    @Test
    fun 보내는_중에_취소하면_돌아온_뒤_취소를_보낸다() = runBlocking {
        val likes = FakeLikes()
        val feed = controller(likes)
        feed.refresh()
        feed.toggleLike(1)
        feed.toggleLike(1)
        likes.release()
        assertEquals(listOf(true, false), likes.sent)
        likes.release()
        assertEquals(false, feed.state.shown(1)?.likedByMe)
        assertEquals(3L, feed.state.shown(1)?.likeCount)
    }

    @Test
    fun 좋아요_실패하면_되돌리고_서버_문구를_보인다() = runBlocking {
        val likes = FakeLikes().apply { fail = ApiException(403, "이용이 정지된 계정이에요") }
        val feed = controller(likes)
        feed.refresh()
        feed.toggleLike(1)
        likes.release()
        assertEquals(false, feed.state.shown(1)?.likedByMe)
        assertEquals(3L, feed.state.shown(1)?.likeCount)
        assertEquals("이용이 정지된 계정이에요", feed.message)
    }

    @Test
    fun 다음_쪽과_네트워크_실패() = runBlocking {
        val feed = controller(FakeLikes(), mapOf(null to PostPage(listOf(post(3), post(2)), 2), 2L to PostPage(listOf(post(1)), null)))
        feed.refresh()
        feed.loadMore()
        assertEquals(listOf(3L, 2L, 1L), feed.state.posts.map { it.id })
        feed.loadMore() // 끝 — 더 부르지 않는다
        assertEquals(3, feed.state.posts.size)

        val broken = controller(FakeLikes(), emptyMap())
        broken.refresh()
        assertEquals("네트워크에 연결하지 못했어요", broken.message)
    }
}
