package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 카드 ⋯ 메뉴 — 내 글·남의 글·정지된 계정·관리자(설계 §2 목표 7, §3 관리자). */
class PostMenuTest {
    private val author = CommunityProfile("abc123def456", "티렉스", null)
    private fun post(mine: Boolean, hidden: Boolean = false) = Post(1, author, "글", emptyList(), 0, false, 0, 0, hidden, null, mine)
    private fun labels(post: Post, gate: CommunityGate) = postMenu(post, gate) {}.map { it.label }

    @Test
    fun 내_글은_지우기만() {
        assertEquals(listOf("공유하기", "지우기"), labels(post(mine = true), CommunityGate.Ready(admin = true)))
    }

    @Test
    fun 남의_글은_신고와_차단_정지된_계정은_차단만() {
        assertEquals(listOf("공유하기", "신고", "티렉스님 차단"), labels(post(mine = false), CommunityGate.Ready(admin = false)))
        assertEquals(listOf("공유하기", "티렉스님 차단"), labels(post(mine = false), CommunityGate.Suspended(admin = false)))
    }

    @Test
    fun 관리자는_숨기기_지우기_작성자_정지가_더_있다() {
        assertEquals(
            listOf("공유하기", "신고", "티렉스님 차단", "관리자: 숨기기", "관리자: 지우기", "관리자: 작성자 정지"),
            labels(post(mine = false), CommunityGate.Ready(admin = true)),
        )
        // 이미 숨긴 글은 숨기기가 없다(복구는 신고함)
        assertEquals(
            listOf("공유하기", "신고", "티렉스님 차단", "관리자: 지우기", "관리자: 작성자 정지"),
            labels(post(mine = false, hidden = true), CommunityGate.Ready(admin = true)),
        )
    }
}
