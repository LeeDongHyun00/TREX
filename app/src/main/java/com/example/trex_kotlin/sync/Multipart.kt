package com.example.trex_kotlin.sync

import kotlin.random.Random

/**
 * multipart/form-data 본문을 바이트로 만든다 — 커뮤니티 글쓰기(본문 + 사진)·프로필 사진 올리기(설계 §5).
 * 전송([BinaryTransport])은 바이트만 보낸다. 두 플랫폼이 같은 이 코드로 본문을 만든다(§108, `android-sources.txt`).
 */
object Multipart {

    sealed interface Part {
        /** 글자 칸 — UTF-8 로 보낸다(서버가 한글을 깨뜨리지 않게 charset 을 밝힌다). */
        data class Text(val name: String, val value: String) : Part

        /** 파일 칸 — 바이트를 손대지 않고 그대로. */
        class File(val name: String, val fileName: String, val contentType: String, val bytes: ByteArray) : Part
    }

    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** 본문에 나오지 않을 만큼 긴 무작위 경계. */
    fun boundary(): String = "TrexBoundary-" + (1..24).map { CHARS[Random.nextInt(CHARS.length)] }.joinToString("")

    fun contentType(boundary: String): String = "multipart/form-data; boundary=$boundary"

    fun body(boundary: String, parts: List<Part>): ByteArray {
        val out = ArrayList<ByteArray>()
        for (part in parts) {
            when (part) {
                is Part.Text -> {
                    out += "--$boundary\r\nContent-Disposition: form-data; name=\"${part.name}\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n".encodeToByteArray()
                    out += part.value.encodeToByteArray()
                }
                is Part.File -> {
                    out += ("--$boundary\r\nContent-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.fileName}\"\r\n" +
                        "Content-Type: ${part.contentType}\r\n\r\n").encodeToByteArray()
                    out += part.bytes
                }
            }
            out += "\r\n".encodeToByteArray()
        }
        out += "--$boundary--\r\n".encodeToByteArray()
        val result = ByteArray(out.sumOf { it.size })
        var at = 0
        for (chunk in out) {
            chunk.copyInto(result, at)
            at += chunk.size
        }
        return result
    }
}
