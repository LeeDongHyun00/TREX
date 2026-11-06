package com.example.trex_kotlin

/**
 * `java.util.UUID.nameUUIDFromBytes` 와 같은 버전 3(MD5) UUID 문자열(§108).
 * 구기록 부하 세트의 ID 가 이 값이라, 기기·플랫폼이 달라도 같은 기록이 같은 ID 를 가져야 장부가 겹치지 않는다.
 * Kotlin 공통 표준에 MD5 가 없어 RFC 1321 을 그대로 옮겼다 — java 판과 같은지는 NameUuidTest 가 본다.
 */
fun nameUuidFromBytes(name: ByteArray): String {
    val hash = md5(name)
    hash[6] = (hash[6].toInt() and 0x0f or 0x30).toByte()   // 버전 3
    hash[8] = (hash[8].toInt() and 0x3f or 0x80).toByte()   // IETF 변형
    val hex = hash.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private val SHIFTS = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

// floor(|sin(i + 1)| · 2³²) — 부동소수 계산이 플랫폼마다 끝자리가 다를 수 있어 표로 둔다.
private val CONSTANTS = longArrayOf(
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
).let { table -> IntArray(table.size) { table[it].toInt() } }

private fun md5(message: ByteArray): ByteArray {
    // 0x80 하나, 길이 ≡ 56 (mod 64) 까지 0, 마지막 8바이트에 비트 길이(리틀 엔디언)
    val paddedLength = ((message.size + 8) / 64 + 1) * 64
    val padded = message.copyOf(paddedLength)
    padded[message.size] = 0x80.toByte()
    val bitLength = message.size.toLong() * 8
    for (i in 0 until 8) padded[paddedLength - 8 + i] = (bitLength ushr (8 * i)).toByte()

    var a0 = 0x67452301
    var b0 = 0xefcdab89.toInt()
    var c0 = 0x98badcfe.toInt()
    var d0 = 0x10325476
    val words = IntArray(16)
    for (chunk in 0 until paddedLength step 64) {
        for (j in 0 until 16) {
            val at = chunk + j * 4
            words[j] = (padded[at].toInt() and 0xff) or ((padded[at + 1].toInt() and 0xff) shl 8) or
                ((padded[at + 2].toInt() and 0xff) shl 16) or ((padded[at + 3].toInt() and 0xff) shl 24)
        }
        var a = a0
        var b = b0
        var c = c0
        var d = d0
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> { f = (b and c) or (b.inv() and d); g = i }
                1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
            }
            val rotated = (f + a + CONSTANTS[i] + words[g]).rotateLeft(SHIFTS[i])
            a = d
            d = c
            c = b
            b += rotated
        }
        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }
    val digest = ByteArray(16)
    intArrayOf(a0, b0, c0, d0).forEachIndexed { w, v -> for (i in 0 until 4) digest[w * 4 + i] = (v ushr (8 * i)).toByte() }
    return digest
}
