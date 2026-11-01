package com.example.trex_kotlin.security

import android.content.Context
import android.content.SharedPreferences
import com.example.trex_kotlin.KeyValueStore
import java.util.Base64

/** 값 전체를 AES-GCM으로 보호한다. 키 이름은 동기화 리스너 계약 때문에 유지한다. */
internal class EncryptedPreferences(context: Context, private val name: String) : KeyValueStore {
    private val cipher = AndroidKeyCipher.cipher
    private val prefs = context.applicationContext.getSharedPreferences(name + SUFFIX, Context.MODE_PRIVATE)
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init { synchronized(LOCK) {
        val legacy = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (!prefs.contains(MARKER)) {
            check(prefs.all.isEmpty()) { "암호화 저장소의 이관 표식이 없습니다" }
            val edit = prefs.edit()
            legacy.all.forEach { (key, value) -> check(key != MARKER); if (value != null) edit.putString(key, seal(key, value)) }
            edit.putString(MARKER, seal(MARKER, 1L))
            check(edit.commit()) { "암호화 저장소를 기록하지 못했습니다" }
        }
        check(read(MARKER) == 1L)
        prefs.all.keys.forEach { read(it) } // 키 분실·변조를 빈 값으로 바꾸지 않는다.
        legacy.all.forEach { (key, value) -> check(read(key) == value) { "이관 결과가 원본과 다릅니다" } }
        if (legacy.all.isNotEmpty()) {
            // commit 실패도 SharedPreferences 메모리는 바꾼다. 같은 프로세스의 재시도에서 표식만 믿지 않고
            // 새 IV의 표식을 써 암호화 스냅샷 전체를 디스크에 다시 확정한 뒤 평문을 정리한다.
            check(prefs.edit().putString(MARKER, seal(MARKER, 1L)).commit()) { "암호화 저장소를 확정하지 못했습니다" }
            check(legacy.edit().clear().commit()) { "이관한 평문을 정리하지 못했습니다" }
        }
    } }

    private fun seal(key: String, value: Any) = Base64.getEncoder().encodeToString(cipher.encrypt(PreferenceValue.encode(value), "preferences/$name/$key"))
    private fun read(key: String): Any? = prefs.getString(key, null)?.let {
        PreferenceValue.decode(cipher.decrypt(Base64.getDecoder().decode(it), "preferences/$name/$key"))
    }

    override fun getString(key: String, defValue: String?) = read(key)?.let { it as String } ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = read(key)?.let { it as Boolean } ?: defValue
    override fun getLong(key: String, defValue: Long) = read(key)?.let { it as Long } ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = read(key)?.let { (it as Set<String>).toSet() } ?: defValues
    override fun contains(key: String) = key != MARKER && prefs.contains(key)
    override fun edit(): KeyValueStore.Editor = Editor(prefs.edit())
    override fun setChangeListener(onChange: (String) -> Unit) {
        listener?.let(prefs::unregisterOnSharedPreferenceChangeListener)
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null && key != MARKER) onChange(key) }
            .also(prefs::registerOnSharedPreferenceChangeListener)
    }

    private inner class Editor(private val edit: SharedPreferences.Editor) : KeyValueStore.Editor {
        private fun put(key: String, value: Any?): KeyValueStore.Editor {
            require(key != MARKER)
            if (value == null) edit.remove(key) else {
                // 같은 값을 다시 저장하면 변경 알림을 보내지 않는 기존 SharedPreferences 의미를 유지한다.
                val encoded = if (read(key) == value) prefs.getString(key, null)!! else seal(key, value)
                edit.putString(key, encoded)
            }
            return this
        }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())
        override fun remove(key: String) = put(key, null)
        override fun clear(): KeyValueStore.Editor { edit.clear().putString(MARKER, seal(MARKER, 1L)); return this }
        override fun apply() = edit.apply()
        override fun commit() = edit.commit()
    }

    companion object {
        internal const val SUFFIX = "_aes256"
        private const val MARKER = "__trex_aes256_version__"
        private val LOCK = Any()
    }
}
