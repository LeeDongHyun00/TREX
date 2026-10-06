package com.example.trex_kotlin.posture

import kotlin.math.*

/** 플랫폼의 자릿수/로케일 차이는 화면 표시에만 적용한다. 판정은 원본 실수값을 쓴다. */
internal object Math {
    const val PI = kotlin.math.PI
    fun toDegrees(v: Double) = v * 180 / PI
    fun toRadians(v: Double) = v * PI / 180
    fun round(v: Float): Int = floor(v + .5f).toInt()
    fun cos(v: Double): Double = kotlin.math.cos(v)
    fun sin(v: Double): Double = kotlin.math.sin(v)
}

internal object PortableFormat {
    fun format(pattern: String, vararg args: Any?): String {
        var i = 0
        return Regex("%([+]?)(0?)([0-9]*)(?:\\.([0-9]+))?([fsxd%])").replace(pattern) { m ->
            val type = m.groupValues[5]
            if (type == "%") "%" else {
                val arg = args[i++]
                val raw = when (type) {
                    "s" -> arg.toString()
                    "x" -> (arg as Number).toLong().toString(16)
                    "d" -> (arg as Number).toLong().toString()
                    else -> {
                        val value = (arg as Number).toDouble()
                        val digits = m.groupValues[4].toIntOrNull() ?: 6
                        if (!value.isFinite()) value.toString() else {
                            val factor = 10.0.pow(digits)
                            val n = floor(abs(value) * factor + .5).toLong()
                            val whole = (n / factor.toLong()).toString()
                            val fraction = if (digits == 0) "" else "." + (n % factor.toLong()).toString().padStart(digits, '0')
                            (if (value < 0) "-" else if (m.groupValues[1] == "+") "+" else "") + whole + fraction
                        }
                    }
                }
                raw.padStart(m.groupValues[3].toIntOrNull() ?: 0, if (m.groupValues[2] == "0") '0' else ' ')
            }
        }
    }
}

/** 규칙 JSON에 필요한 org.json 읽기 API의 공통 구현. 파일/Android 의존성이 없다. */
internal class JSONObject internal constructor(private val data: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST") constructor(text: String) : this(Json.parse(text) as Map<String, Any?>)
    fun has(key: String) = data.containsKey(key)
    fun isNull(key: String) = data[key] == null
    fun getString(key: String) = data[key] as? String ?: error("문자열 없음: $key")
    fun optString(key: String, fallback: String = "") = data[key] as? String ?: fallback
    fun getDouble(key: String) = (data[key] as? Number)?.toDouble() ?: error("숫자 없음: $key")
    fun optDouble(key: String, fallback: Double = Double.NaN) = (data[key] as? Number)?.toDouble() ?: fallback
    fun getInt(key: String) = getDouble(key).toInt()
    fun optInt(key: String, fallback: Int = 0) = (data[key] as? Number)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0) = (data[key] as? Number)?.toLong() ?: fallback
    fun getLong(key: String) = getDouble(key).toLong()
    fun optBoolean(key: String, fallback: Boolean = false) = data[key] as? Boolean ?: fallback
    @Suppress("UNCHECKED_CAST") fun optJSONObject(key: String) = (data[key] as? Map<String, Any?>)?.let(::JSONObject)
    fun optJSONArray(key: String) = (data[key] as? List<Any?>)?.let(::JSONArray)
    fun getJSONArray(key: String) = optJSONArray(key) ?: error("배열 없음: $key")
    fun values(): Map<String, Any?> = data
}
internal class JSONArray(private val data: List<Any?>) {
    fun length() = data.size
    @Suppress("UNCHECKED_CAST") fun getJSONObject(i: Int) = JSONObject(data[i] as Map<String, Any?>)
    fun getString(i: Int) = data[i] as String
}

internal object Json {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + buildString { value.forEach { c -> append(when (c) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c.code < 32) "\\u" + c.code.toString(16).padStart(4, '0') else c.toString()
        }) } } + "\""
        is Number -> if (value.toDouble().isFinite()) value.toString() else "null"
        is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { encode(it.key.toString()) + ":" + encode(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> error("지원하지 않는 JSON 값")
    }
    fun parse(text: String): Any? = Parser(text).parse()
    private class Parser(val text: String) {
        var pos = 0
        fun space() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun parse(): Any? { val v = value(); space(); require(pos == text.length); return v }
        fun value(): Any? {
            space(); require(pos < text.length)
            return when (text[pos]) {
                '{' -> { pos++; val map = linkedMapOf<String, Any?>(); space()
                    if (text[pos] != '}') while (true) { space(); val k = string(); space(); require(text[pos++] == ':'); map[k] = value(); space(); if (text[pos] != ',') break; pos++ }
                    require(text[pos++] == '}'); map }
                '[' -> { pos++; val list = mutableListOf<Any?>(); space()
                    if (text[pos] != ']') while (true) { list += value(); space(); if (text[pos] != ',') break; pos++ }
                    require(text[pos++] == ']'); list }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> { val start = pos; while (pos < text.length && text[pos] !in ",]} \n\r\t") pos++; text.substring(start, pos).toDouble() }
            }
        }
        fun literal(word: String, result: Any?): Any? { require(text.startsWith(word, pos)); pos += word.length; return result }
        fun string(): String {
            require(text[pos++] == '"')
            return buildString { while (true) {
                val c = text[pos++]; if (c == '"') break
                if (c != '\\') append(c) else append(when (val e = text[pos++]) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                    'u' -> text.substring(pos, pos + 4).toInt(16).toChar().also { pos += 4 }
                    '"', '\\', '/' -> e
                    else -> error("JSON 이스케이프 오류")
                })
            } }
        }
    }
}

const val MP_LANDMARK_COUNT = 33
/** Android와 동일한 분석 입력. 영상/이미지 자체는 저장하지 않는다. */
class PoseSample(
    val detected: Boolean, val normalizedXy: FloatArray, val visibility: FloatArray,
    val features: Map<String, Float>, val visibleJointCount: Int, val inferMs: Long,
    val imageWidth: Int, val imageHeight: Int,
    val up: Vec3 = SCREEN_UP, val upFromGravity: Boolean = false,
    val upFlipped: Boolean = false, val upVerified: Boolean = false, val world: FloatArray? = null,
) {
    companion object {
        fun empty(inferMs: Long = 0, w: Int = 0, h: Int = 0, up: Vec3 = SCREEN_UP, fromGravity: Boolean = false) =
            PoseSample(false, FloatArray(66), FloatArray(33), emptyMap(), 0, inferMs, w, h, up, fromGravity)
    }
}
