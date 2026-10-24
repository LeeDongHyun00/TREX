package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** §108: 공통 MD5 판 [nameUuidFromBytes] 가 java.util.UUID.nameUUIDFromBytes 와 같은가 — 구기록 부하 세트 ID 가 이 값이다. */
class NameUuidTest {

    private fun check(bytes: ByteArray, label: String) =
        assertEquals(label, UUID.nameUUIDFromBytes(bytes).toString(), nameUuidFromBytes(bytes))

    @Test
    fun legacyLoadSetKeys() {
        listOf(
            "legacy:20383:0:기본 스쿼트:0", "legacy:20383:1:플랭크:2", "legacy:19999:12:사이드 레터럴 레이즈:98", "",
        ).forEach { check(it.encodeToByteArray(), it) }
    }

    @Test
    fun everyPaddingBoundary() {
        // MD5 패딩이 블록을 넘기는 길이(55·56·63·64·119·120 …)를 전부 지난다
        for (length in 0..200) check(ByteArray(length) { (it * 31 + 7).toByte() }, "len=$length")
        for (length in 0..70) check("가".repeat(length).encodeToByteArray(), "korean=$length")
    }

    @Test
    fun randomBytes() {
        val random = Random(108)
        repeat(500) { i -> check(random.nextBytes(random.nextInt(0, 700)), "random#$i") }
    }
}
