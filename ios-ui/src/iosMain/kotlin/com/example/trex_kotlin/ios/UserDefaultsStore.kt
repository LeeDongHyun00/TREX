package com.example.trex_kotlin.ios

import com.example.trex_kotlin.KeyValueStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [KeyValueStore] 의 iPhone 구현(§125). 이름/API는 유지하며 저장은 CryptoKit AES-256-GCM 파일이다.
 * 옛 NSUserDefaults `trex.<이름>`은 Swift 준비 단계에서 검증 후 이관한다. 쓰기 성공 뒤에만 메모리와 리스너에 반영한다.
 * Android SharedPreferences 와 같게 맞춘 점:
 *  - 같은 이름은 같은 저장소다. 핸들은 여럿이어도 리스너는 이름별로 모여서, 다른 핸들의 쓰기도 듣는다.
 *  - 값이 실제로 바뀐 키만 알린다(SharedPreferencesImpl) — 동기화가 안 바뀐 쓰기를 '이 기기에서 바꿈' 으로 세지 않는다.
 *  - 알림은 메인 스레드다. 메인에서 쓰면 apply·commit 안에서 바로 부른다(SyncManager.applyRemote 가 이것에 기댄다).
 *  - 편집기의 clear 는 다른 변경보다 먼저 적용하고, 알림은 바뀐 키를 거꾸로 부른다(SharedPreferencesImpl 순서).
 */
class UserDefaultsStore(name: String) : KeyValueStore {
    private val hub = Hub.of(name)
    private var listener: ((String) -> Unit)? = null

    override fun getString(key: String, defValue: String?): String? =
        hub.read(key)?.let { it as String } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        hub.read(key)?.let { it as Boolean } ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        hub.read(key)?.let { it as Long } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (hub.read(key) as? Set<String>)?.toSet() ?: defValues

    override fun contains(key: String): Boolean = hub.read(key) != null

    override fun edit(): KeyValueStore.Editor = Editor(hub)

    override fun setChangeListener(onChange: (String) -> Unit) {
        listener?.let(hub::removeListener)
        listener = onChange.also(hub::addListener)
    }

    private sealed interface Change {
        val key: String
        data class Put(override val key: String, val value: Any) : Change
        data class Remove(override val key: String) : Change
    }

    private class Editor(private val hub: Hub) : KeyValueStore.Editor {
        private val changes = LinkedHashMap<String, Change>()
        private var clear = false

        override fun putString(key: String, value: String?): KeyValueStore.Editor {
            changes[key] = if (value == null) Change.Remove(key) else Change.Put(key, value)
            return this
        }
        override fun putBoolean(key: String, value: Boolean): KeyValueStore.Editor { changes[key] = Change.Put(key, value); return this }
        override fun putLong(key: String, value: Long): KeyValueStore.Editor { changes[key] = Change.Put(key, value); return this }
        override fun putStringSet(key: String, values: Set<String>?): KeyValueStore.Editor {
            changes[key] = if (values == null) Change.Remove(key) else Change.Put(key, values.toSet())
            return this
        }
        override fun remove(key: String): KeyValueStore.Editor { changes[key] = Change.Remove(key); return this }
        override fun clear(): KeyValueStore.Editor { clear = true; return this }

        // 보안 저장은 apply도 디스크 기록을 확정한다. 실패를 성공 알림으로 바꾸지 않는다.
        override fun apply() = hub.write(clear, changes.values.toList())
        override fun commit(): Boolean { hub.write(clear, changes.values.toList()); return true }
    }

    /** 이름 하나의 저장소와 그 리스너들. */
    private class Hub(private val name: String) {
        private var values = SecurePreferenceValues.decode(checkNotNull(secureStorage("preferences-read", name)))
        private val lock = SynchronizedObject()
        private val listeners = mutableListOf<(String) -> Unit>()

        fun read(key: String): Any? = synchronized(lock) { values[key] }

        fun addListener(listener: (String) -> Unit) = synchronized(lock) { listeners += listener }
        fun removeListener(listener: (String) -> Unit) = synchronized(lock) { listeners -= listener }

        fun write(clear: Boolean, changes: List<Change>) {
            val modified = synchronized(lock) {
                val next = if (clear) linkedMapOf() else values.toMutableMap()
                val keys = mutableListOf<String>()
                for (change in changes) {
                    val before = next[change.key]
                    when (change) {
                        is Change.Remove -> if (before != null) { next.remove(change.key); keys += change.key }
                        is Change.Put -> if (before != change.value) {
                            next[change.key] = change.value; keys += change.key
                        }
                    }
                }
                if (next != values) {
                    secureStorage("preferences-write", name, SecurePreferenceValues.encode(next))
                    values = next
                }
                keys.reversed() to listeners.toList()
            }
            val (keys, targets) = modified
            if (keys.isEmpty() || targets.isEmpty()) return
            val notify = { keys.forEach { key -> targets.forEach { it(key) } } }
            if (NSThread.isMainThread) notify() else dispatch_async(dispatch_get_main_queue()) { notify() }
        }

        companion object {
            private val lock = SynchronizedObject()
            private val hubs = mutableMapOf<String, Hub>()
            fun of(name: String): Hub = synchronized(lock) { hubs.getOrPut(name) { Hub(name) } }
        }
    }
}
