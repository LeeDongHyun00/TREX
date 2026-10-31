package com.example.trex_kotlin.ios

import org.json.JSONArray
import org.json.JSONObject

/** Swift의 CryptoKit/Keychain 경계. NSError나 예외를 언어 경계에서 놓치지 않도록 결과를 명시한다. */
class IosStorageResult(val value: String?, val error: String?)

interface IosSecureStorageBridge {
    fun execute(operation: String, path: String, value: String?): IosStorageResult
}

object IosSecureStorageRegistry {
    var bridge: IosSecureStorageBridge? = null
}

/** 다리가 없거나 인증이 실패하면 평문/빈 저장소로 폴백하지 않는다. */
internal fun secureStorage(operation: String, path: String, value: String? = null): String? {
    val bridge = checkNotNull(IosSecureStorageRegistry.bridge) { "암호화 저장소가 준비되지 않았습니다." }
    val result = bridge.execute(operation, path, value)
    check(result.error == null) { result.error ?: "암호화 저장소 오류입니다." }
    return result.value
}

internal fun readSecureTextFile(path: String): String? = secureStorage("read", path)
internal fun writeSecureTextFile(path: String, text: String) { secureStorage("write", path, text) }
internal fun secureFiles(directory: String): List<String> {
    val items = JSONArray(checkNotNull(secureStorage("list", directory)))
    return List(items.length()) { items.getString(it) }
}

/** NSUserDefaults의 자료형과 Int64 전체 범위를 보존하는 언어 간 형식(§125). */
internal object SecurePreferenceValues {
    fun decode(json: String): Map<String, Any> {
        val root = JSONObject(json)
        return root.keys().asSequence().associateWith { key ->
            val entry = root.getJSONObject(key)
            when (entry.getString("t")) {
                "s" -> entry.getString("v")
                "b" -> entry.getBoolean("v")
                "l" -> entry.getString("v").toLong()
                "ss" -> entry.getJSONArray("v").let { values -> List(values.length()) { values.getString(it) }.toSet() }
                else -> error("저장 데이터 형식을 확인하지 못했습니다.")
            }
        }
    }

    fun encode(values: Map<String, Any>): String = JSONObject().also { root ->
        values.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is String -> entry.put("t", "s").put("v", value)
                is Boolean -> entry.put("t", "b").put("v", value)
                is Long -> entry.put("t", "l").put("v", value.toString())
                is Set<*> -> entry.put("t", "ss").put("v", JSONArray().also { array ->
                    value.map { it as String }.sorted().forEach { array.put(it) }
                })
                else -> error("지원하지 않는 저장 자료형입니다.")
            }
            root.put(key, entry)
        }
    }.toString()
}
