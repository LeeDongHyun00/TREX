package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 피드 상태 — 쪽 붙이기·좋아요 낙관적 갱신·지운 글(계획 3 Review Focus 2·3). */
class FeedStateTest {
    private val author = CommunityProfile("abc123def456", "티렉스", null)
    private fun post(id: Long, likes: Long = 0, liked: Boolean = false) =
        Post(id, author, "글 $id", emptyList(), likes, liked, 0, 1_000L * id, false, null, false)

    private fun ids(state: FeedState) = state.posts.map { it.id }

    @Test
    fun 다음_쪽을_붙여도_같은_글은_한_번() {
        // 넘기는 사이 앞쪽 글이 지워지면 커서 뒤 쪽에 이미 본 글이 다시 온다
        val first = FeedState().withFirstPage(PostPage(listOf(post(5), post(4), post(3)), nextBefore = 3), now = 0)
        val next = first.withNextPage(PostPage(listOf(post(3), post(2)), nextBefore = null))
        assertEquals(listOf(5L, 4L, 3L, 2L), ids(next))
        assertTrue(next.end)
        assertFalse(first.end)
    }

    @Test
    fun 첫_쪽은_예전_목록을_바꾼다() {
        val old = FeedState().withFirstPage(PostPage(listOf(post(2), post(1)), nextBefore = 1), now = 0)
        val fresh = old.withFirstPage(PostPage(listOf(post(3), post(2)), nextBefore = 2), now = 10)
        assertEquals(listOf(3L, 2L), ids(fresh))
        assertEquals(2L, fresh.nextBefore)
        assertEquals(10L, fresh.loadedAt)
    }

    @Test
    fun 좋아요는_바로_바뀌고_실패하면_되돌린다() {
        val feed = FeedState().withFirstPage(PostPage(listOf(post(1, likes = 3)), null), now = 0)
        val tapped = feed.tapLike(1)
        assertEquals(true, tapped.shown(1)?.likedByMe)
        assertEquals(4L, tapped.shown(1)?.likeCount)
        assertEquals(true, tapped.pendingLike(1))

        val failed = tapped.likeFailed(1)
        assertEquals(false, failed.shown(1)?.likedByMe)
        assertEquals(3L, failed.shown(1)?.likeCount)
        assertEquals(null, failed.pendingLike(1))
    }

    @Test
    fun 좋아요를_빠르게_여러_번_눌러도_마지막_상태로_맞는다() {
        val feed = FeedState().withFirstPage(PostPage(listOf(post(1, likes = 3)), null), now = 0)
        // 누름(좋아요) → 서버에 보내는 중 다시 누름(취소) → 또 누름(좋아요)
        val taps = feed.tapLike(1).tapLike(1)
        assertEquals(false, taps.pendingLike(1)) // 바라는 값은 남겨 둔다 — 보내는 중인 좋아요가 돌아오면 취소를 이어서 보내야 한다
        assertEquals(null, taps.likeToSend(1)) // 서버가 아는 값과 같으니 지금 보낼 것은 없다
        assertEquals(3L, taps.shown(1)?.likeCount)
        assertEquals(null, taps.settleLike(1).pendingLike(1)) // 보내는 중인 것이 없으면 정리한다
        val third = taps.tapLike(1)
        // 첫 요청(좋아요)이 성공으로 돌아왔다 — 보낼 것이 없어지고 수는 한 번만 오른다
        val confirmed = third.likeConfirmed(1, liked = true)
        assertEquals(null, confirmed.pendingLike(1))
        assertEquals(true, confirmed.shown(1)?.likedByMe)
        assertEquals(4L, confirmed.shown(1)?.likeCount)
    }

    @Test
    fun 보내는_중에_다시_누르면_확인_뒤에도_보낼_것이_남는다() {
        val feed = FeedState().withFirstPage(PostPage(listOf(post(1, likes = 3)), null), now = 0)
        val sending = feed.tapLike(1) // 좋아요를 보내는 중
        val retapped = sending.tapLike(1) // 화면은 취소 상태
        val confirmed = retapped.likeConfirmed(1, liked = true)
        assertEquals(false, confirmed.likeToSend(1)) // 취소를 이어서 보낸다
        assertEquals(false, confirmed.shown(1)?.likedByMe)
        assertEquals(3L, confirmed.shown(1)?.likeCount)
    }

    @Test
    fun 지운_글은_빠진다() {
        val feed = FeedState().withFirstPage(PostPage(listOf(post(2), post(1)), null), now = 0).tapLike(2)
        val removed = feed.removed(2)
        assertEquals(listOf(1L), ids(removed))
        assertEquals(null, removed.pendingLike(2))
    }

    @Test
    fun 바뀐_글로_갈아_끼운다() {
        val feed = FeedState().withFirstPage(PostPage(listOf(post(2), post(1)), null), now = 0)
        val updated = feed.replaced(post(1).copy(commentCount = 5))
        assertEquals(5L, updated.shown(1)?.commentCount)
        assertEquals(listOf(2L, 1L), ids(updated))
    }

    @Test
    fun 댓글_수만_바꾸고_보내는_중인_좋아요는_그대로() {
        // 화면 값(shown)을 되써 넣으면 아직 확인 안 된 좋아요가 확인된 것처럼 굳는다(상세 화면에서 댓글을 달 때)
        val feed = FeedState().withFirstPage(PostPage(listOf(post(1, likes = 3)), null), now = 0).tapLike(1)
        val commented = feed.commentsChanged(1, +1)
        assertEquals(1L, commented.shown(1)?.commentCount)
        assertEquals(true, commented.likeToSend(1))
        assertEquals(3L, commented.likeFailed(1).shown(1)?.likeCount)
        assertEquals(0L, commented.commentsChanged(1, -5).shown(1)?.commentCount)
    }

    @Test
    fun 오십분이_지나면_사진_주소가_곧_만료되니_새로_받는다() {
        val feed = FeedState().withFirstPage(PostPage(listOf(post(1)), null), now = 0)
        assertFalse(feed.isStale(now = 49 * 60_000L))
        assertTrue(feed.isStale(now = 50 * 60_000L))
        assertTrue(FeedState().isStale(now = 0)) // 받은 적 없다
    }
}
