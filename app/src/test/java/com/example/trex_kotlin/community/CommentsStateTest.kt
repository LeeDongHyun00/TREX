package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 글 상세의 댓글 — 최근 50개를 오래된 순으로, "이전 댓글 보기"는 앞에 붙인다(서버 CommentService.list, 계획 3 Task 4). */
class CommentsStateTest {
    private val author = CommunityProfile("abc123def456", "티렉스", null)
    private fun comment(id: Long) = Comment(id, author, "댓글 $id", 1_000L * id, false, null, false)
    private fun ids(state: CommentsState) = state.items.map { it.id }

    @Test
    fun 이전_댓글은_앞에_붙이고_같은_댓글은_한_번() {
        val first = CommentsState().withFirstPage(CommentPage(listOf(comment(8), comment(9), comment(10)), nextBefore = 8))
        assertTrue(first.hasOlder)
        val older = first.withOlderPage(CommentPage(listOf(comment(6), comment(7), comment(8)), nextBefore = null))
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L), ids(older))
        assertFalse(older.hasOlder)
    }

    @Test
    fun 내가_쓴_댓글은_끝에_붙고_지운_댓글은_빠진다() {
        val state = CommentsState().withFirstPage(CommentPage(listOf(comment(1), comment(2)), null))
        val added = state.added(comment(3))
        assertEquals(listOf(1L, 2L, 3L), ids(added))
        assertEquals(listOf(1L, 3L), ids(added.removed(2)))
        assertEquals(listOf(1L, 2L, 3L), ids(added.added(comment(3)))) // 두 번 와도 한 번
    }

    @Test
    fun 댓글은_500자까지() {
        assertTrue(canSendComment("좋아요"))
        assertFalse(canSendComment("  "))
        assertTrue(canSendComment("가".repeat(500)))
        assertFalse(canSendComment("가".repeat(501)))
    }
}
