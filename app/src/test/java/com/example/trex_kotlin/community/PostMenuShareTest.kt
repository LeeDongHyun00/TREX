package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 게시글 ⋯ 메뉴 맨 앞의 "공유하기"(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 내 글·남의 글 모두. */
class PostMenuShareTest {
    private val author = CommunityProfile("abc123def456", "티렉스", null)
    private fun post(mine: Boolean) = Post(1, author, "글", emptyList(), 0, false, 0, 1_000L, false, null, mine)

    @Test
    fun shareIsFirstForAnyPost() {
        var opened: CommunityAction? = null
        val mine = postMenu(post(mine = true), CommunityGate.Ready(false)) { opened = it }
        val other = postMenu(post(mine = false), CommunityGate.Ready(false)) { opened = it }
        assertEquals("공유하기", mine.first().label)
        assertEquals("공유하기", other.first().label)
        other.first().onClick()
        assertEquals(CommunityAction.SharePost(post(mine = false)), opened)
    }
}
