package com.example.trex_kotlin.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.MuscleLoadStore
import com.example.trex_kotlin.trainingload.LoadSet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 고유 테스트 저장소만 사용한다. 기존 사용자 DB·토큰·키를 삭제하거나 초기화하지 않는다. */
@RunWith(AndroidJUnit4::class)
class LocalEncryptionDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun name() = "crypto_test_${UUID.randomUUID()}"

    @Test fun preferencesKeepLegacyTypesThenPersistOnlyCiphertextAndReopen() {
        val name = name()
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val secret = "가상 사용자 기록\n".repeat(10_000)
        assertTrue(legacy.edit().putString("profile", secret).putString("token", "synthetic-token")
            .putBoolean("onboarded", true).putLong("when", Long.MAX_VALUE).putStringSet("set", setOf("한글", "a")).commit())
        val store = EncryptedPreferences(context, name)
        assertEquals(secret, store.getString("profile", null))
        assertEquals("synthetic-token", store.getString("token", null))
        assertTrue(store.getBoolean("onboarded", false))
        assertEquals(Long.MAX_VALUE, store.getLong("when", 0))
        assertEquals(setOf("한글", "a"), store.getStringSet("set", null))
        assertTrue(legacy.all.isEmpty())
        val raw = context.getSharedPreferences(name + EncryptedPreferences.SUFFIX, Context.MODE_PRIVATE)
        assertFalse(raw.all.toString().contains("synthetic-token"))
        assertTrue(store.edit().putString("token", "new-token").remove("set").commit())
        val reopened = EncryptedPreferences(context, name)
        assertEquals("new-token", reopened.getString("token", null))
        assertFalse(reopened.contains("set"))
        assertTrue(reopened.edit().clear().commit())
        assertEquals("default", EncryptedPreferences(context, name).getString("profile", "default"))
    }

    @Test fun corruptPreferenceFailsWithoutClearingOrReplacingStoredValue() {
        val name = name()
        EncryptedPreferences(context, name).edit().putString("token", "synthetic").commit()
        val raw = context.getSharedPreferences(name + EncryptedPreferences.SUFFIX, Context.MODE_PRIVATE)
        raw.edit().putString("token", "broken-ciphertext").commit()
        assertTrue(runCatching { EncryptedPreferences(context, name) }.isFailure)
        assertEquals("broken-ciphertext", raw.getString("token", null))
    }

    @Test fun failedPreferenceCommitRetriesDurablyBeforeDeletingLegacyValues() {
        val name = name()
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("token", "keep-me").commit()
        val target = context.getSharedPreferences(name + EncryptedPreferences.SUFFIX, Context.MODE_PRIVATE)
        var commits = 0
        val wrapped = object : SharedPreferences by target {
            override fun edit(): SharedPreferences.Editor {
                val editor = target.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this // 연결 호출도 실패를 모사하는 래퍼를 지나야 한다.
                    }
                    override fun commit(): Boolean {
                        commits++
                        if (commits == 1) { editor.apply(); return false } // 메모리 갱신 뒤 디스크 실패를 모사한다.
                        return editor.commit()
                    }
                }
            }
        }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                if (key == name + EncryptedPreferences.SUFFIX) wrapped else super.getSharedPreferences(key, mode)
        }
        assertTrue(runCatching { EncryptedPreferences(isolated, name) }.isFailure)
        assertEquals("keep-me", legacy.getString("token", null))
        assertEquals("keep-me", EncryptedPreferences(isolated, name).getString("token", null))
        assertEquals(2, commits)
        assertTrue(legacy.all.isEmpty())
    }

    @Test fun keystoreFileMigrationAndAppendSurviveNewStorageInstance() {
        val root = File(context.cacheDir, name()).apply { mkdirs() }
        try {
            val original = File(root, "baseline.tsv").apply { writeText("기준선\r\n마지막") }
            AndroidKeyCipher.files.migrate(original)
            assertFalse(original.exists())
            EncryptedTextFiles(AndroidKeyCipher.cipher).appendText(original, "\n추가")
            assertEquals("기준선\r\n마지막\n추가", AndroidKeyCipher.files.readText(original))
        } finally { root.deleteRecursively() }
    }

    @Test fun externalPostureLogsCanBeMigratedAndSyncedWithoutPlaintext() {
        val root = File(context.getExternalFilesDir(null)!!, name()).apply { mkdirs() }
        try {
            val original = File(root, "sets.jsonl").apply { writeText("외부 자세 로그\n") }
            AndroidKeyCipher.files.migrate(original)
            AndroidKeyCipher.files.appendText(original, "추가\n")
            assertFalse(original.exists())
            assertEquals("외부 자세 로그\n추가\n", AndroidKeyCipher.files.readText(original))
        } finally { root.deleteRecursively() }
    }

    @Test fun identicalPlainValuesDoNotNotifySyncListenersAgain() {
        val store = EncryptedPreferences(context, name())
        val notifications = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            store.setChangeListener { notifications += it }
            store.edit().putString("history", "same-json").commit()
            store.edit().putString("history", "same-json").commit()
            assertEquals(listOf("history"), notifications)
            store.edit().putString("history", "changed-json").commit()
            assertEquals(listOf("history", "history"), notifications)
        }
    }

    @Test fun encryptedMuscleStoreRetainsNormalInsertCorrectionAndReloadBehavior() {
        val isolated = isolatedContext()
        try {
            val now = System.currentTimeMillis()
            val set = LoadSet("s", "session", "squat", now, reps = 12.0, postureSetId = "p")
            val expected = MuscleLoadStore(isolated).use { it.sync(listOf(set), emptyList(), now, emptyMap()) }
            MuscleLoadStore(isolated).use { store ->
                val reloaded = store.sync(emptyList(), emptyList(), now, emptyMap())
                assertEquals(expected, reloaded)
                val corrected = store.sync(emptyList(), emptyList(), now, mapOf("p" to 10.0))
                assertEquals(10.0, corrected.sets.single().actualReps!!, 0.0)
            }
        } finally { isolated.filesDir.deleteRecursively() }
    }

    @Test fun migrationIncludesCommittedRowsStillInLegacyWal() {
        val isolated = isolatedContext()
        try {
            val source = File(isolated.filesDir, "snapshot.db")
            val legacy = isolated.getDatabasePath("muscle_load.db").apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.enableWriteAheadLogging()
                db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
                db.execSQL("CREATE TABLE load_sets (id TEXT PRIMARY KEY, raw TEXT)")
                db.execSQL("CREATE TABLE muscle_doses (set_id TEXT, dose REAL)")
                db.execSQL("CREATE TABLE load_meta (key TEXT PRIMARY KEY, value TEXT)")
                db.version = 1
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                db.execSQL("INSERT INTO load_sets VALUES('wal-only','WAL에만 있는 기록')")
                source.copyTo(legacy)
                val wal = File(source.path + "-wal")
                assertTrue(wal.length() > 0)
                wal.copyTo(File(legacy.path + "-wal"))
            }
            MuscleLoadStore(isolated).use { store ->
                store.writableDatabase.rawQuery("SELECT raw FROM load_sets WHERE id='wal-only'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("WAL에만 있는 기록", it.getString(0))
                }
            }
            assertFalse(legacy.exists())
            assertFalse(File(legacy.path + "-wal").exists())
        } finally { isolated.filesDir.deleteRecursively() }
    }

    @Test fun sqliteMigrationPreservesRowsMetadataAndCanResumeBeforeLegacyCleanup() {
        val isolated = isolatedContext()
        try {
            val legacy = isolated.getDatabasePath("muscle_load.db")
            legacy.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(legacy, null).use { db ->
                db.execSQL("CREATE TABLE load_sets (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, exercise_id TEXT NOT NULL, ended_at INTEGER NOT NULL, posture_set_id TEXT, model_version INTEGER NOT NULL, raw TEXT NOT NULL)")
                db.execSQL("CREATE INDEX load_sets_time ON load_sets(ended_at)")
                db.execSQL("CREATE INDEX load_sets_posture ON load_sets(posture_set_id)")
                db.execSQL("CREATE TABLE muscle_doses (set_id TEXT NOT NULL REFERENCES load_sets(id) ON DELETE CASCADE, muscle_id TEXT NOT NULL, side TEXT NOT NULL, dose REAL NOT NULL, PRIMARY KEY(set_id,muscle_id,side))")
                db.execSQL("CREATE TABLE load_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO load_sets VALUES('s','session','exercise',123,'posture',1,'원본 JSON')")
                db.execSQL("INSERT INTO muscle_doses VALUES('s','QUADS','L',3.5)")
                db.execSQL("INSERT INTO load_meta VALUES('legacy_v1','done')")
                db.version = 1
            }
            val backup = legacy.readBytes()
            repeat(2) { round ->
                if (round == 1) legacy.writeBytes(backup) // 검증 완료 직후 평문 정리 전의 중단 상태
                MuscleLoadStore(isolated).use { store ->
                    val db = store.writableDatabase
                    assertEquals(1, db.version)
                    db.rawQuery("SELECT raw FROM load_sets", null).use { assertTrue(it.moveToFirst()); assertEquals("원본 JSON", it.getString(0)) }
                    db.rawQuery("SELECT dose FROM muscle_doses", null).use { assertTrue(it.moveToFirst()); assertEquals(3.5, it.getDouble(0), 0.0) }
                    db.rawQuery("SELECT value FROM load_meta", null).use { assertTrue(it.moveToFirst()); assertEquals("done", it.getString(0)) }
                    assertFalse(legacy.exists())
                }
            }
            val encrypted = isolated.getDatabasePath(EncryptedMuscleDatabase.NAME)
            assertFalse(encrypted.readBytes().take(16).toByteArray().toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
            assertFalse(encrypted.readText().contains("원본 JSON"))
            assertTrue(runCatching {
                net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(encrypted.path, "wrong-key", null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY, EncryptedMuscleDatabase.preserveOnError, null).use { db ->
                    db.rawQuery("SELECT * FROM load_sets", null).use { it.moveToFirst() }
                }
            }.isFailure)
            assertTrue(encrypted.exists())
            MuscleLoadStore(isolated).use { assertEquals(1, it.writableDatabase.version) }
        } finally { isolated.filesDir.deleteRecursively() }
    }

    @Test fun missingDatabaseKeyDoesNotOverwriteExistingDatabase() {
        val isolated = isolatedContext()
        try {
            MuscleLoadStore(isolated).use { it.writableDatabase }
            val file = isolated.getDatabasePath(EncryptedMuscleDatabase.NAME)
            val before = file.readBytes()
            AndroidKeyCipher.files.delete(File(isolated.noBackupFilesDir, "trex_security/muscle_db_key"))
            assertTrue(runCatching { MuscleLoadStore(isolated) }.isFailure)
            assertArrayEquals(before, file.readBytes())
        } finally { isolated.filesDir.deleteRecursively() }
    }

    private fun isolatedContext(): Context {
        val root = File(context.cacheDir, name()).apply { mkdirs() }
        return object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name")
            override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
    }
}
