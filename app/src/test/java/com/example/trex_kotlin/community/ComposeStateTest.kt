package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 글쓰기 입력 — 사진 0~5장·본문 2,000자·실패해도 입력 유지(설계 §5 글쓰기, 계획 3 Review Focus 4). */
class ComposeStateTest {
    @Test
    fun 사진도_글도_없으면_못_올린다() {
        assertFalse(ComposeState<String>().canPost)
        assertFalse(ComposeState<String>().withBody("   \n").canPost)
        assertTrue(ComposeState<String>().withBody("오운완").canPost)
        assertTrue(ComposeState<String>().addPhotos(listOf("a")).canPost)
    }

    @Test
    fun 여섯_번째_사진은_더하지_않는다() {
        val four = ComposeState<String>().addPhotos(listOf("a", "b", "c", "d"))
        val full = four.addPhotos(listOf("e", "f"))
        assertEquals(listOf("a", "b", "c", "d", "e"), full.photos)
        assertEquals(0, full.photosLeft)
        assertEquals(listOf("a", "c", "d", "e"), full.removePhoto(1).photos)
    }

    @Test
    fun 본문은_서버처럼_글자_수로_2000자까지() {
        // 이모지는 UTF-16 두 칸이지만 서버(codePointCount)는 한 글자로 센다
        val emoji = "💪".repeat(2000)
        assertEquals(2000, codePointLength(emoji))
        assertTrue(ComposeState<String>().withBody(emoji).canPost)
        val over = ComposeState<String>().withBody("가".repeat(2001))
        assertEquals(2000, codePointLength(over.body))
    }

    @Test
    fun 올리는_중에는_다시_못_올린다() {
        val posting = ComposeState<String>().withBody("글").started()
        assertTrue(posting.posting)
        assertFalse(posting.canPost)
    }

    @Test
    fun 실패해도_입력은_남는다() {
        val failed = ComposeState<String>().withBody("오늘 하체").addPhotos(listOf("a", "b")).started().failed("잠시 뒤에 다시 올려 주세요")
        assertEquals("오늘 하체", failed.body)
        assertEquals(listOf("a", "b"), failed.photos)
        assertEquals("잠시 뒤에 다시 올려 주세요", failed.error)
        assertFalse(failed.posting)
        assertTrue(failed.canPost)
        assertNull(failed.withBody("오늘 하체!").error) // 고치기 시작하면 문구를 내린다
    }

    @Test
    fun 보낼_본문은_앞뒤_공백을_뺀다() {
        assertEquals("오운완", ComposeState<String>().withBody("  오운완\n").bodyToSend)
        assertNull(ComposeState<String>().withBody(" \n ").bodyToSend)
    }
}
