package com.example.trex_kotlin.ios

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 iOS Kotlin 저장 코드를 실행한다. 네이티브 암호화는 LocalEncryptionTests.swift의 별도 책임이다. */
class StorageContractTest {
    private class Backend : IosSecureStorageBridge {
        val values = mutableMapOf<String, String>()
        var writes = 0
        var failWrite = false
        var failRead = false
        override fun execute(operation: String, path: String, value: String?): IosStorageResult = when (operation) {
            "preferences-read" -> if (failRead) IosStorageResult(null, "인증 실패") else IosStorageResult(values[path] ?: "{}", null)
            "preferences-write" -> if (failWrite) IosStorageResult(null, "저장 실패") else {
                values[path] = checkNotNull(value); writes++; IosStorageResult(null, null)
            }
            else -> error("알 수 없는 연산")
        }
    }
    private lateinit var backend: Backend
    private lateinit var name: String
    @Before fun setUp() {
        backend = Backend(); name = UUID.randomUUID().toString()
        IosSecureStorageRegistry.bridge = backend
    }
    private fun fails(block: () -> Unit) {
        try { block(); fail("실패해야 하는 작업입니다.") } catch (_: IllegalStateException) { }
    }

    @Test fun roundTripRetainsTypesUnicodeAndFullLongRange() {
        val values = mapOf("문자" to "한글\n\"덤벨\"", "빈문자" to "", "거짓" to false, "참" to true,
            "최대" to Long.MAX_VALUE, "최소" to Long.MIN_VALUE, "집합" to setOf("바벨", "덤벨"), "빈집합" to emptySet<String>())
        assertEquals(values, SecurePreferenceValues.decode(SecurePreferenceValues.encode(values)))
    }
    @Test fun nativeMigrationFormatCanBeRead() {
        backend.values[name] = """{"token":{"t":"s","v":"토큰"},"n":{"t":"l","v":"9223372036854775807"},"flag":{"t":"b","v":false},"equipment":{"t":"ss","v":["덤벨"]}}"""
        val store = UserDefaultsStore(name)
        assertEquals("토큰", store.getString("token", null)); assertEquals(Long.MAX_VALUE, store.getLong("n", 0))
        assertFalse(store.getBoolean("flag", true)); assertEquals(setOf("덤벨"), store.getStringSet("equipment", null))
    }
    @Test fun sameStoreSharesValuesAndChangedKeyNotifications() {
        val first = UserDefaultsStore(name); val second = UserDefaultsStore(name); val changed = mutableListOf<String>()
        first.setChangeListener { changed += it }
        second.edit().putString("profile", "기록").putLong("version", 7).commit()
        assertEquals("기록", first.getString("profile", null)); assertEquals(listOf("version", "profile"), changed)
        assertEquals(1, backend.writes)
    }
    @Test fun sameValuesAndReorderedSetDoNotTriggerSyncOrWrite() {
        val store = UserDefaultsStore(name); val changed = mutableListOf<String>()
        store.edit().putStringSet("set", linkedSetOf("a", "b")).apply()
        store.setChangeListener { changed += it }
        store.edit().putStringSet("set", linkedSetOf("b", "a")).commit()
        assertTrue(changed.isEmpty()); assertEquals(1, backend.writes)
    }
    @Test fun failedCommitPreservesMemoryDiskAndDoesNotNotify() {
        val store = UserDefaultsStore(name); val changed = mutableListOf<String>()
        store.edit().putString("history", "원본").commit(); store.setChangeListener { changed += it }
        val before = backend.values[name]; backend.failWrite = true
        fails { store.edit().clear().putString("history", "다른값").commit() }
        assertEquals("원본", store.getString("history", null)); assertEquals(before, backend.values[name]); assertTrue(changed.isEmpty())
    }
    @Test fun failedApplyDoesNotPublishUnsavedMemory() {
        val store = UserDefaultsStore(name); backend.failWrite = true
        fails { store.edit().putBoolean("logged_in", true).apply() }
        assertFalse(store.contains("logged_in"))
    }
    @Test fun authenticatedReadFailureDoesNotCreateEmptyStore() {
        backend.failRead = true
        fails { UserDefaultsStore(name) }
        assertEquals(0, backend.writes)
    }
    @Test fun missingBridgeDoesNotFallBackToPlaintext() {
        IosSecureStorageRegistry.bridge = null
        fails { UserDefaultsStore(name) }
    }
    @Test fun clearRemoveNullAndListenerReplacement() {
        val store = UserDefaultsStore(name); val changed = mutableListOf<String>()
        store.edit().putString("a", "1").putString("b", "2").commit()
        store.setChangeListener { fail("교체된 리스너가 불렸습니다.") }; store.setChangeListener { changed += it }
        store.edit().putString("a", null).commit(); assertFalse(store.contains("a")); assertEquals(listOf("a"), changed)
        store.edit().clear().putLong("c", 3).commit()
        assertFalse(store.contains("b")); assertEquals(3L, store.getLong("c", 0))
    }
    @Test fun concurrentEditorsDoNotLoseUnrelatedKeys() {
        val store = UserDefaultsStore(name); val workers = Executors.newFixedThreadPool(4); val latch = CountDownLatch(40)
        repeat(40) { index -> workers.execute { try { store.edit().putLong("k$index", index.toLong()).commit() } finally { latch.countDown() } } }
        assertTrue(latch.await(15, TimeUnit.SECONDS)); workers.shutdown()
        repeat(40) { assertEquals(it.toLong(), store.getLong("k$it", -1)) }
        assertEquals(40, SecurePreferenceValues.decode(checkNotNull(backend.values[name])).size)
    }
}
