package com.example.trex_kotlin.pose.contract

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stable length-prefixed SHA-256 used by signed pose contracts.
 *
 * Callers own field order and schema versioning. Length-prefixing UTF-8 values prevents delimiter
 * ambiguity; the lowercase hexadecimal result is portable between Android and offline tooling.
 */
internal fun canonicalFieldsSha256(fields: List<Pair<String, String>>): String {
    val canonicalPayload = buildString {
        fields.forEach { (name, value) ->
            val byteCount = value.toByteArray(StandardCharsets.UTF_8).size
            append(name).append(':').append(byteCount).append(':').append(value).append('\n')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
    val alphabet = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}
