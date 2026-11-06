package com.example.trex_kotlin.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** multipart/form-data 본문 — 커뮤니티 글쓰기(본문 + 사진 0~5장)·프로필 사진 올리기(설계 §5 글쓰기). */
class MultipartTest {

    private fun utf8(s: String) = s.encodeToByteArray()

    @Test
    fun 본문은_경계_헤더_순서가_정확하고_파일_바이트는_그대로다() {
        val photo0 = byteArrayOf(1, 2, 3)
        val photo1 = byteArrayOf(-1, 0, 13, 10)
        val body = Multipart.body(
            "b",
            listOf(
                Multipart.Part.Text("body", "오늘 오운완"),
                Multipart.Part.File("images", "photo0.jpg", "image/jpeg", photo0),
                Multipart.Part.File("images", "photo1.jpg", "image/jpeg", photo1),
            ),
        )
        val expected = utf8("--b\r\nContent-Disposition: form-data; name=\"body\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n") +
            utf8("오늘 오운완") + utf8("\r\n") +
            utf8("--b\r\nContent-Disposition: form-data; name=\"images\"; filename=\"photo0.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n") +
            photo0 + utf8("\r\n") +
            utf8("--b\r\nContent-Disposition: form-data; name=\"images\"; filename=\"photo1.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n") +
            photo1 + utf8("\r\n") +
            utf8("--b--\r\n")
        assertArrayEquals(expected, body)
    }

    @Test
    fun contentType은_경계를_담는다() {
        assertEquals("multipart/form-data; boundary=b", Multipart.contentType("b"))
    }

    @Test
    fun 경계는_매번_다르고_안전한_글자만_쓴다() {
        val a = Multipart.boundary()
        assertNotEquals(a, Multipart.boundary())
        assertTrue(a, a.matches(Regex("[A-Za-z0-9-]{20,70}")))
    }
}
