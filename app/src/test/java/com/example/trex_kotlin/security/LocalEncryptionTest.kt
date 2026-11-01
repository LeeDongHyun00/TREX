package com.example.trex_kotlin.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec

class LocalEncryptionTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
    private val cipher = AesGcm { key }
    private val files = EncryptedTextFiles(cipher)
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun gcmRoundTripUsesFreshIvAndRejectsWrongKeyPurposeAndChanges() {
        val text = "로그인 토큰과 건강 기록".toByteArray()
        val first = cipher.encrypt(text, "auth/token")
        val second = cipher.encrypt(text, "auth/token")
        assertFalse(first.contentEquals(second))
        assertArrayEquals(text, cipher.decrypt(first, "auth/token"))
        fails { cipher.decrypt(first, "store/profile") }
        fails { AesGcm { SecretKeySpec(ByteArray(32), "AES") }.decrypt(first, "auth/token") }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        fails { cipher.decrypt(first, "auth/token") }
        fails { cipher.decrypt(first.copyOf(15), "auth/token") }
        fails { cipher.decrypt(first.copyOf().also { it[0] = 2 }, "auth/token") }
    }

    @Test fun decryptionNeverRequestsAReplacementKey() {
        val encrypted = cipher.encrypt(byteArrayOf(1), "test")
        var requested: Boolean? = null
        fails { AesGcm { create -> requested = create; error("키 없음") }.decrypt(encrypted, "test") }
        assertEquals(false, requested)
    }

    @Test fun allPreferenceTypesAndLongKoreanJsonRetainExactValues() {
        listOf("", "한글\n\u0000".repeat(100_000), true, false, Long.MIN_VALUE, Long.MAX_VALUE,
            setOf("토큰", "", "a,b"), emptySet<String>(), Int.MIN_VALUE, 3.5f).forEach {
            assertEquals(it, PreferenceValue.decode(PreferenceValue.encode(it)))
        }
        fails { PreferenceValue.decode(byteArrayOf(1, 127, 127, 127, 127)) }
    }

    @Test fun migrationPreservesAllBytesIncludingCrLfAndNoFinalNewline() {
        val file = folder.newFile("sets.jsonl")
        val text = "한글,😀\r\n".repeat(30_000) + "마지막 줄"
        file.writeText(text)
        files.migrate(file)
        assertFalse(file.exists())
        assertTrue(File(file.path + ".aes").exists())
        assertEquals(text, files.readText(file))
        files.migrate(file)
        assertEquals(text, files.readText(file))
        assertFalse(File(file.path + ".aes").readText().contains("마지막 줄"))
    }

    @Test fun crashAfterVerifiedCopyBeforePlaintextRemovalCanResume() {
        val file = folder.newFile("baseline.tsv").apply { writeText("원본\n") }
        files.migrate(file)
        file.writeText("원본\n") // 교체 직후 삭제 전에 중단된 상태
        files.migrate(file)
        assertFalse(file.exists())
        assertEquals("원본\n", files.readText(file))
    }

    @Test fun disagreementBetweenOriginalAndEncryptedCopyPreservesBoth() {
        val file = folder.newFile("memory.tsv").apply { writeText("원본") }
        files.migrate(file)
        file.writeText("다른 내용")
        val before = File(file.path + ".aes").readBytes()
        fails { files.migrate(file) }
        assertEquals("다른 내용", file.readText())
        assertArrayEquals(before, File(file.path + ".aes").readBytes())
    }

    @Test fun interruptedEncryptionDoesNotRemoveOriginalAndRetryPreservesIt() {
        val file = folder.newFile("sets.jsonl").apply { writeText("a".repeat(200_000)) }
        var calls = 0
        val failing = EncryptedTextFiles(AesGcm { if (++calls > 1) error("저장 중단"); key })
        fails { failing.migrate(file) }
        assertEquals(200_000, file.length().toInt())
        assertFalse(File(file.path + ".aes").exists())
        files.migrate(file)
        assertEquals("a".repeat(200_000), files.readText(file))
    }

    @Test fun truncationTamperingAndRenamingCannotBecomeEmptyOrPlaintext() {
        val file = File(folder.root, "protected.tsv")
        files.writeText(file, "보존해야 할 기록")
        val encoded = File(file.path + ".aes")
        val original = encoded.readBytes()
        encoded.writeBytes(original.copyOf(original.size - 1))
        fails { files.readText(file) }
        fails { files.writeText(file, "새 데이터") }
        assertArrayEquals(original.copyOf(original.size - 1), encoded.readBytes())
        encoded.writeBytes(original)
        val other = File(folder.root, "other.tsv")
        encoded.copyTo(File(other.path + ".aes"))
        fails { files.readText(other) }
        encoded.appendBytes(byteArrayOf(1))
        fails { files.readText(file) }
    }

    @Test fun appendIsSerializedAcrossStoreInstancesAndSurvivesReopen() {
        val file = File(folder.root, "labels.jsonl")
        files.writeText(file, "처음\n")
        val pool = Executors.newFixedThreadPool(4)
        try {
            (0..19).map { i -> pool.submit { EncryptedTextFiles(cipher).appendText(file, "$i\n") } }.forEach { it.get() }
        } finally { pool.shutdown() }
        val lines = EncryptedTextFiles(cipher).readText(file).lines().filter(String::isNotBlank)
        assertEquals(21, lines.size)
        assertEquals((0..19).map(Int::toString).toSet(), lines.drop(1).toSet())
        assertEquals(listOf(file), files.list(folder.root))
    }

    @Test fun emptyFileIsAuthenticatedAndExplicitDeletionRemovesCiphertext() {
        val file = folder.newFile("empty.tsv")
        files.migrate(file)
        assertEquals("", files.readText(file))
        files.delete(file)
        assertFalse(files.exists(file))
    }
}
