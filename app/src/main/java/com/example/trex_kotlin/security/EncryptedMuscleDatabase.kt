package com.example.trex_kotlin.security

import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.SecureRandom

/** SQLCipher의 AES-256 페이지 암호화·HMAC을 사용한다. DB 비밀번호는 Keystore로 암호화한 별도 파일에만 둔다. */
internal object EncryptedMuscleDatabase {
    const val NAME = "muscle_load.aes.db"
    private const val LEGACY = "muscle_load.db"
    // 기본 손상 처리기는 DB를 삭제할 수 있으므로 사용하지 않는다.
    val preserveOnError = DatabaseErrorHandler { _, _ -> throw IllegalStateException("근육 기록 DB를 열지 못했습니다. 원본을 보존합니다") }

    @Synchronized
    fun prepare(context: Context): ByteArray {
        System.loadLibrary("sqlcipher")
        val target = context.getDatabasePath(NAME)
        val keyFile = File(context.noBackupFilesDir, "trex_security/muscle_db_key")
        val files = AndroidKeyCipher.files
        if (!files.exists(keyFile)) {
            check(!target.exists()) { "근육 기록 DB의 암호키를 찾지 못했습니다" }
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            files.writeText(keyFile, bytes.joinToString("") { "%02x".format(it) })
            bytes.fill(0)
        }
        val password = files.readText(keyFile).also { check(it.matches(Regex("[0-9a-f]{64}"))) }.toByteArray(Charsets.US_ASCII)
        val legacy = context.getDatabasePath(LEGACY)
        if (legacy.exists()) migrate(context, legacy, target, password)
        if (target.exists()) open(target, password).use(::verify)
        return password
    }

    private fun open(file: File, password: ByteArray) = SQLiteDatabase.openDatabase(
        file.path, password, null, SQLiteDatabase.OPEN_READWRITE, preserveOnError, null)

    private fun migrate(context: Context, legacy: File, target: File, password: ByteArray) {
        val temp = File(target.path + ".migrating")
        val destination = if (target.exists()) target else temp
        // 이 임시 DB는 원본이 살아 있을 때만 다시 만든다. 원본/완료된 DB는 실패 시 삭제하지 않는다.
        if (!target.exists()) listOf(temp, File(temp.path + "-wal"), File(temp.path + "-shm"), File(temp.path + "-journal"))
            .forEach { if (it.exists()) check(it.delete()) }
        // ATTACH가 새 암호화 DB를 만들 수 있도록 CREATE를 허용한다. 원본 존재는 호출 전에 확인했다.
        SQLiteDatabase.openDatabase(legacy.path, byteArrayOf(), null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, preserveOnError, null).use { source ->
            source.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { while (it.moveToNext()) check(it.getInt(0) == 0) }
            source.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(destination.path, password.toString(Charsets.US_ASCII)))
            try {
                if (!target.exists()) {
                    source.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { check(it.moveToFirst()) }
                    source.execSQL("PRAGMA encrypted.user_version = ${source.version}")
                }
                check(source.rawQuery("PRAGMA encrypted.user_version", null).use { it.moveToFirst(); it.getInt(0) } == source.version)
                val tables = source.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use {
                    buildList { while (it.moveToNext()) add(it.getString(0)) }
                }
                for (table in tables) {
                    val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                    for ((left, right) in listOf("main" to "encrypted", "encrypted" to "main")) {
                        source.rawQuery("SELECT * FROM $left.$quoted EXCEPT SELECT * FROM $right.$quoted", null).use {
                            check(!it.moveToFirst()) { "근육 기록 이관 검증에 실패했습니다" }
                        }
                    }
                    source.rawQuery("SELECT (SELECT COUNT(*) FROM main.$quoted) = (SELECT COUNT(*) FROM encrypted.$quoted)", null).use {
                        check(it.moveToFirst() && it.getInt(0) == 1)
                    }
                }
            } finally { source.execSQL("DETACH DATABASE encrypted") }
        }
        open(destination, password).use(::verify)
        if (!target.exists()) {
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE)
        }
        AndroidKeyCipher.syncDirectory(target.parentFile!!)
        // 검증된 암호화 DB가 정식 경로에 도착한 뒤에만 평문 DB·WAL을 정리한다.
        check(context.deleteDatabase(LEGACY)) { "이관한 평문 DB를 정리하지 못했습니다" }
        AndroidKeyCipher.syncDirectory(target.parentFile!!)
    }

    private fun verify(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
        db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(!it.moveToFirst()) { "DB 암호문 인증에 실패했습니다" } }
    }
}
