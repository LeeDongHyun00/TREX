@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.json

import kotlin.native.HiddenFromObjC

/*
 * Android org.json 의 공통 판(§108, 손으로 쓴 파일 — 생성본 아님). iPhone 이 Android 정본 파일(TrexStore·TrexApi·LoadSetJson …)을
 * `import org.json.*` 그대로 컴파일하도록 그 파일들이 쓰는 API 만 같은 이름·같은 의미로 둔다. Android 앱은 이 모듈을 쓰지 않는다
 * (플랫폼 org.json 이 있다). 의미는 Android 구현(libcore org.json)을 따른다 — 키 순서 보존, opt* 의 강제 변환,
 * put(null) 은 지움, 정수인 실수는 "65" 로, '/' 는 "\/" 로 쓴다. 같은 값을 내는지는 OrgJsonTest 가 본다.
 * Swift 는 쓰지 않으므로 ObjC 헤더에서 뺀다 — `JSONObject.NULL` 이 C 매크로 NULL 과 부딪혀 헤더가 깨졌다.
 */

@HiddenFromObjC
class JSONException(message: String?) : Exception(message)

@HiddenFromObjC
class JSONObject() {
    private val values = LinkedHashMap<String, Any?>()

    /** Java 처럼 null 도 받는다(정본 코드가 `String?` 를 그대로 넘긴다) — 읽기에서 실패한다. */
    constructor(json: String?) : this() {
        val parsed = JsonReader(json).readValue()
        if (parsed !is JSONObject) throw typeMismatch(parsed, "JSONObject")
        values.putAll(parsed.values)
    }

    fun length(): Int = values.size
    fun has(name: String): Boolean = values.containsKey(name)
    fun isNull(name: String): Boolean = values[name].let { it == null || it === NULL }
    fun keys(): Iterator<String> = values.keys.iterator()
    fun remove(name: String): Any? = values.remove(name)
    fun opt(name: String): Any? = values[name]
    fun get(name: String): Any = values[name] ?: throw JSONException("No value for $name")

    fun put(name: String, value: Boolean): JSONObject = apply { values[name] = value }
    fun put(name: String, value: Int): JSONObject = apply { values[name] = value }
    fun put(name: String, value: Long): JSONObject = apply { values[name] = value }
    fun put(name: String, value: Double): JSONObject = apply { values[name] = checkDouble(value) }

    /** null 이면 이름을 지운다(Android 와 같다). */
    fun put(name: String, value: Any?): JSONObject = apply {
        if (value == null) values.remove(name) else values[name] = checkValue(value)
    }

    fun getBoolean(name: String): Boolean = toBoolean(get(name)) ?: throw typeMismatch(name, get(name), "boolean")
    fun optBoolean(name: String, fallback: Boolean = false): Boolean = toBoolean(opt(name)) ?: fallback
    fun getDouble(name: String): Double = toDouble(get(name)) ?: throw typeMismatch(name, get(name), "double")
    fun optDouble(name: String, fallback: Double = Double.NaN): Double = toDouble(opt(name)) ?: fallback
    fun getInt(name: String): Int = toInt(get(name)) ?: throw typeMismatch(name, get(name), "int")
    fun optInt(name: String, fallback: Int = 0): Int = toInt(opt(name)) ?: fallback
    fun getLong(name: String): Long = toLong(get(name)) ?: throw typeMismatch(name, get(name), "long")
    fun optLong(name: String, fallback: Long = 0L): Long = toLong(opt(name)) ?: fallback

    /** 문자열이 아니면 문자열로 바꾼다 — JSON null 은 "null" 이다(Android 와 같다). */
    fun getString(name: String): String = toStringValue(get(name))!!
    fun optString(name: String, fallback: String = ""): String = toStringValue(opt(name)) ?: fallback

    fun getJSONObject(name: String): JSONObject = get(name) as? JSONObject ?: throw typeMismatch(name, get(name), "JSONObject")
    fun optJSONObject(name: String): JSONObject? = opt(name) as? JSONObject
    fun getJSONArray(name: String): JSONArray = get(name) as? JSONArray ?: throw typeMismatch(name, get(name), "JSONArray")
    fun optJSONArray(name: String): JSONArray? = opt(name) as? JSONArray

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()

    internal fun writeTo(out: StringBuilder) {
        out.append('{')
        var first = true
        for ((name, value) in values) {
            if (!first) out.append(',')
            first = false
            writeString(out, name)
            out.append(':')
            writeValue(out, value)
        }
        out.append('}')
    }

    companion object {
        /** JSON null. `equals(null)` 도 참이다(Android 와 같다). */
        val NULL: Any = object {
            override fun equals(other: Any?): Boolean = other === this || other == null
            override fun hashCode(): Int = 0
            override fun toString(): String = "null"
        }

        /** JSON 문자열 리터럴(따옴표 포함) — null 은 `""`. Android `JSONObject.quote` 와 같다. */
        fun quote(data: String?): String = if (data == null) "\"\"" else StringBuilder().also { writeString(it, data) }.toString()

        /** 정수인 실수는 정수로 쓴다(65.0 → "65"), -0.0 은 "-0". Android `JSONObject.numberToString` 과 같다. */
        fun numberToString(number: Number): String {
            val asDouble = checkDouble(number.toDouble())
            if (number is Double && asDouble == 0.0 && 1.0 / asDouble < 0) return "-0"
            val asLong = number.toLong()
            return if (asDouble == asLong.toDouble()) asLong.toString() else number.toString()
        }
    }
}

@HiddenFromObjC
class JSONArray() {
    private val values = ArrayList<Any?>()

    constructor(json: String?) : this() {
        val parsed = JsonReader(json).readValue()
        if (parsed !is JSONArray) throw typeMismatch(parsed, "JSONArray")
        values.addAll(parsed.values)
    }

    /** 컬렉션의 값들로 만든다(Android `JSONArray(Collection)`). */
    constructor(copyFrom: Collection<*>) : this() {
        copyFrom.forEach { put(it) }
    }

    fun length(): Int = values.size
    fun opt(index: Int): Any? = values.getOrNull(index)

    fun get(index: Int): Any {
        if (index !in values.indices) throw JSONException("Index $index out of range [0..${values.size})")
        return values[index] ?: throw JSONException("Value at $index is null.")
    }

    fun put(value: Boolean): JSONArray = apply { values.add(value) }
    fun put(value: Int): JSONArray = apply { values.add(value) }
    fun put(value: Long): JSONArray = apply { values.add(value) }
    fun put(value: Double): JSONArray = apply { values.add(checkDouble(value)) }
    fun put(value: Any?): JSONArray = apply { values.add(value?.let(::checkValue)) }

    fun getBoolean(index: Int): Boolean = toBoolean(get(index)) ?: throw typeMismatch(index, get(index), "boolean")
    fun getDouble(index: Int): Double = toDouble(get(index)) ?: throw typeMismatch(index, get(index), "double")
    fun getInt(index: Int): Int = toInt(get(index)) ?: throw typeMismatch(index, get(index), "int")
    fun getLong(index: Int): Long = toLong(get(index)) ?: throw typeMismatch(index, get(index), "long")
    fun getString(index: Int): String = toStringValue(get(index))!!
    fun optString(index: Int, fallback: String = ""): String = toStringValue(opt(index)) ?: fallback
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw typeMismatch(index, get(index), "JSONObject")
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw typeMismatch(index, get(index), "JSONArray")
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()

    internal fun writeTo(out: StringBuilder) {
        out.append('[')
        values.forEachIndexed { i, value ->
            if (i > 0) out.append(',')
            writeValue(out, value)
        }
        out.append(']')
    }
}

// ---- Android libcore org.json 의 JSON 클래스(강제 변환)와 JSONStringer(쓰기)를 옮긴 것

private fun checkDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) throw JSONException("Forbidden numeric value: $value")
    return value
}

private fun checkValue(value: Any): Any {
    if (value is Number) checkDouble(value.toDouble())
    return value
}

private fun toBoolean(value: Any?): Boolean? = when {
    value is Boolean -> value
    value is String && value.equals("true", ignoreCase = true) -> true
    value is String && value.equals("false", ignoreCase = true) -> false
    else -> null
}

private fun toDouble(value: Any?): Double? = when (value) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

private fun toInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.trim().toDoubleOrNull()?.toInt()
    else -> null
}

private fun toLong(value: Any?): Long? = when (value) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.trim().toDoubleOrNull()?.toLong()
    else -> null
}

private fun toStringValue(value: Any?): String? = when (value) {
    null -> null
    is String -> value
    else -> value.toString()
}

private fun typeMismatch(actual: Any?, requiredType: String) =
    JSONException(if (actual == null) "Value is null." else "Value $actual of type ${actual::class.simpleName} cannot be converted to $requiredType")

private fun typeMismatch(indexOrName: Any, actual: Any?, requiredType: String) =
    JSONException("Value $actual at $indexOrName of type ${actual?.let { it::class.simpleName }} cannot be converted to $requiredType")

private fun writeValue(out: StringBuilder, value: Any?) {
    when {
        value == null || value === JSONObject.NULL -> out.append("null")
        value is JSONObject -> value.writeTo(out)
        value is JSONArray -> value.writeTo(out)
        value is Boolean -> out.append(value)
        value is Number -> out.append(JSONObject.numberToString(value))
        else -> writeString(out, value.toString())
    }
}

private fun writeString(out: StringBuilder, value: String) {
    out.append('"')
    for (c in value) {
        when (c) {
            '"', '\\', '/' -> out.append('\\').append(c)
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\u000c' -> out.append("\\f")
            else -> if (c.code <= 0x1f) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
        }
    }
    out.append('"')
}

/** 표준 JSON 읽기. 숫자는 Android 처럼 Int → Long → Double 순으로 담는다(소수점·지수가 있으면 Double). */
private class JsonReader(source: String?) {
    // Android JSONTokener 처럼 앞의 BOM 을 건너뛴다
    private val text: String = (source ?: throw JSONException("Value is null.")).removePrefix("\uFEFF")
    private var pos = 0

    fun readValue(): Any? {
        skipWhitespace()
        if (pos >= text.length) throw JSONException("End of input at character $pos")
        return when (val c = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            else -> if (c == '-' || c.isDigit()) readNumber() else readLiteral()
        }
    }

    private fun readObject(): JSONObject {
        pos++
        val result = JSONObject()
        skipWhitespace()
        if (peek() == '}') { pos++; return result }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw syntax("Expected a name")
            val name = readString()
            skipWhitespace()
            if (next() != ':') throw syntax("Expected ':' after $name")
            result.put(name, readValue() ?: JSONObject.NULL)
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return result
                else -> throw syntax("Unterminated object")
            }
        }
    }

    private fun readArray(): JSONArray {
        pos++
        val result = JSONArray()
        skipWhitespace()
        if (peek() == ']') { pos++; return result }
        while (true) {
            result.put(readValue())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return result
                else -> throw syntax("Unterminated array")
            }
        }
    }

    private fun readString(): String {
        pos++
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) throw syntax("Unterminated string")
            when (val c = text[pos++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) throw syntax("Unterminated escape sequence")
                    when (val e = text[pos++]) {
                        'u' -> {
                            if (pos + 4 > text.length) throw syntax("Unterminated escape sequence")
                            val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: throw syntax("Invalid escape sequence")
                            out.append(code.toChar())
                            pos += 4
                        }
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        'f' -> out.append('\u000c')
                        else -> out.append(e)
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun readNumber(): Any {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        val literal = text.substring(start, pos)
        if (literal.none { it == '.' || it == 'e' || it == 'E' }) {
            literal.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        }
        return literal.toDoubleOrNull() ?: throw syntax("Invalid number: $literal")
    }

    private fun readLiteral(): Any? {
        for ((word, value) in listOf("true" to true, "false" to false, "null" to null)) {
            if (text.startsWith(word, pos)) { pos += word.length; return value ?: JSONObject.NULL }
        }
        throw syntax("Unexpected character")
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)
    private fun next(): Char? = text.getOrNull(pos++)
    private fun syntax(message: String) = JSONException("$message at character $pos of $text")
}
