package com.example.trex_kotlin.security

import java.io.*

/** 기존 SharedPreferences의 자료형도 암호문 안에 둔다. 긴 JSON에 writeUTF의 64 KiB 제한을 적용하지 않는다. */
internal object PreferenceValue {
    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            when (value) {
                is String -> { out.writeByte(1); out.text(value) }
                is Boolean -> { out.writeByte(2); out.writeBoolean(value) }
                is Long -> { out.writeByte(3); out.writeLong(value) }
                is Set<*> -> { out.writeByte(4); out.writeInt(value.size); value.map { it as String }.sorted().forEach { out.text(it) } }
                is Int -> { out.writeByte(5); out.writeInt(value) }
                is Float -> { out.writeByte(6); out.writeFloat(value) }
                else -> error("지원하지 않는 저장 자료형입니다")
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): Any = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val value = when (input.readUnsignedByte()) {
            1 -> input.text()
            2 -> input.readBoolean()
            3 -> input.readLong()
            4 -> { val count = input.readInt(); check(count in 0..(input.available() / 4)); buildSet { repeat(count) { add(input.text()) } } }
            5 -> input.readInt()
            6 -> input.readFloat()
            else -> error("지원하지 않는 저장 자료형입니다")
        }
        check(input.available() == 0)
        value
    }

    private fun DataOutputStream.text(text: String) { val bytes = text.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.text(): String {
        val size = readInt(); check(size in 0..available())
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
}
