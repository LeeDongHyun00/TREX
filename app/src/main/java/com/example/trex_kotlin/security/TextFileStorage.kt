package com.example.trex_kotlin.security

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.security.SecureRandom

/** 저장소가 쓰는 논리 파일명은 유지한다. Android 실사용은 암호화 구현만 주입한다. */
internal interface TextFileStorage {
    fun exists(file: File): Boolean
    fun readText(file: File): String
    fun writeText(file: File, text: String)
    fun appendText(file: File, text: String)
    fun delete(file: File)
    fun size(file: File): Long
    fun list(dir: File): List<File>
}

/** 플랫폼 없는 기존 형식 테스트용. Context 생성자에서는 사용하지 않는다. */
internal object PlainTextFiles : TextFileStorage {
    override fun exists(file: File) = file.exists()
    override fun readText(file: File) = file.readText(Charsets.UTF_8)
    override fun writeText(file: File, text: String) { file.parentFile?.mkdirs(); file.writeText(text, Charsets.UTF_8) }
    override fun appendText(file: File, text: String) { file.parentFile?.mkdirs(); file.appendText(text, Charsets.UTF_8) }
    override fun delete(file: File) { if (file.exists()) check(file.delete()) }
    override fun size(file: File) = file.length()
    override fun list(dir: File) = dir.listFiles()?.filter { it.isFile }.orEmpty()
}

/**
 * 큰 로그도 64 KiB씩 인증한다. 파일별 난수·조각 번호·끝 표식을 인증하므로 조각 교환/누락을 거부한다.
 * 쓰기는 암호화 임시 파일 → fsync → 재검증 → 원자적 교체. 검증 전에는 기존 파일을 지우지 않는다.
 * 평문은 최초 이관에서만 읽으며, 별도 .aes 파일로 구분해 깨진 암호문을 평문으로 오인하지 않는다.
 */
internal class EncryptedTextFiles(private val cipher: AesGcm, private val syncDirectory: (File) -> Unit = {}) : TextFileStorage {
    private fun encoded(file: File) = File(file.path + ".aes")
    private fun purpose(file: File) = "file/${file.canonicalPath}"

    override fun exists(file: File): Boolean = synchronized(LOCK) { file.exists() || encoded(file).exists() }
    override fun size(file: File): Long = synchronized(LOCK) { if (file.exists()) migrate(file); encoded(file).length() }
    override fun list(dir: File): List<File> = synchronized(LOCK) {
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".writing") }
            ?.map { if (it.name.endsWith(".aes")) File(it.path.removeSuffix(".aes")) else it }?.distinct().orEmpty()
    }

    fun migrate(file: File) = synchronized(LOCK) {
        val target = encoded(file)
        if (!file.exists()) {
            if (target.exists()) decode(target, purpose(file)) { }
            return@synchronized
        }
        // 중단 뒤 두 파일이 남았다면 내용이 같은 경우에만 평문을 정리한다.
        if (!target.exists()) replace(file) { emit -> file.inputStream().use { chunks(it, emit) } }
        val expected = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { chunks(it) { part -> expected.update(part) } }
        val actual = MessageDigest.getInstance("SHA-256")
        decode(target, purpose(file)) { actual.update(it) }
        check(MessageDigest.isEqual(expected.digest(), actual.digest())) { "이관 파일이 원본과 다릅니다" }
        syncDirectory(target.parentFile!!)
        check(file.delete()) { "이관한 평문을 정리하지 못했습니다" }
        syncDirectory(file.parentFile!!)
    }

    override fun readText(file: File): String = synchronized(LOCK) {
        if (file.exists()) migrate(file)
        val output = ByteArrayOutputStream()
        decode(encoded(file), purpose(file)) { output.write(it) }
        output.toString(Charsets.UTF_8.name())
    }

    override fun writeText(file: File, text: String) = synchronized(LOCK) {
        migrate(file) // 실패한 기존 파일을 새 값으로 덮어쓰지 않는다.
        replace(file) { emit -> text.byteInputStream(Charsets.UTF_8).use { chunks(it, emit) } }
    }

    override fun appendText(file: File, text: String) = synchronized(LOCK) {
        if (file.exists()) migrate(file)
        replace(file) { emit ->
            if (encoded(file).exists()) decode(encoded(file), purpose(file), emit)
            text.byteInputStream(Charsets.UTF_8).use { chunks(it, emit) }
        }
    }

    override fun delete(file: File) = synchronized(LOCK) {
        // 명시적인 삭제 요청에만 사용한다. 복호화 실패 경로에는 호출하지 않는다.
        listOf(file, encoded(file), File(encoded(file).path + ".writing")).forEach { if (it.exists()) check(it.delete()) }
    }

    private fun replace(file: File, source: ((ByteArray) -> Unit) -> Unit) {
        val target = encoded(file)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".writing")
        val expected = MessageDigest.getInstance("SHA-256")
        FileOutputStream(temp).use { raw ->
            val out = DataOutputStream(BufferedOutputStream(raw))
            out.write(MAGIC)
            val id = ByteArray(16).also(SecureRandom()::nextBytes)
            out.write(id)
            val scope = purpose(file) + "/" + id.joinToString("") { "%02x".format(it) }
            var index = 0L
            fun frame(bytes: ByteArray) {
                val encrypted = cipher.encrypt(bytes, "$scope/${index++}")
                out.writeInt(encrypted.size); out.write(encrypted)
            }
            source { bytes -> check(bytes.isNotEmpty() && bytes.size <= CHUNK); expected.update(bytes); frame(bytes) }
            frame(byteArrayOf()) // 마지막 조각은 인증된 끝 표식이다.
            out.flush(); raw.fd.sync()
        }
        val actual = MessageDigest.getInstance("SHA-256")
        decode(temp, purpose(file)) { actual.update(it) }
        check(MessageDigest.isEqual(expected.digest(), actual.digest())) { "암호화 저장 검증에 실패했습니다" }
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(target.parentFile!!)
    }

    private fun decode(file: File, purpose: String, consume: (ByteArray) -> Unit) {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            check(magic.contentEquals(MAGIC)) { "지원하지 않는 암호화 파일입니다" }
            val id = ByteArray(16).also(input::readFully)
            val scope = purpose + "/" + id.joinToString("") { "%02x".format(it) }
            var index = 0L
            while (true) {
                val length = input.readInt()
                check(length in 29..(CHUNK + 29)) { "암호화 파일 길이가 잘못되었습니다" }
                val plain = cipher.decrypt(ByteArray(length).also(input::readFully), "$scope/${index++}")
                if (plain.isEmpty()) { check(input.read() == -1) { "끝 표식 뒤에 데이터가 있습니다" }; break }
                consume(plain)
            }
        }
    }

    private fun chunks(input: InputStream, consume: (ByteArray) -> Unit) {
        val buffer = ByteArray(CHUNK)
        while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) consume(buffer.copyOf(n)) }
    }

    companion object {
        private val LOCK = Any()
        private const val CHUNK = 64 * 1024
        private val MAGIC = "TREXAES1".toByteArray(Charsets.US_ASCII)
    }
}
