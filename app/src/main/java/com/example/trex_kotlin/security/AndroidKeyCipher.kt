package com.example.trex_kotlin.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import java.io.File
import android.system.Os
import android.system.OsConstants
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** 설치별 256비트 비반출 키. 키가 사라진 암호문을 위해 새 키를 만들거나 저장소를 초기화하지 않는다. */
internal object AndroidKeyCipher {
    private const val ALIAS = "trex.local.aes256.v1"
    private var keyWasPresent = false
    val cipher = AesGcm { create -> synchronized(this) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            keyWasPresent = true
            store.getKey(ALIAS, null) as SecretKey
        } else {
            check(create && !keyWasPresent) { "저장된 기록의 암호키를 찾지 못했습니다" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey().also { keyWasPresent = true }
        }
    } }
    val files = EncryptedTextFiles(cipher, ::syncDirectory)

    /** 파일 fsync 뒤 이름 교체도 디스크에 확정한 다음 평문을 정리한다. */
    fun syncDirectory(dir: File) {
        listOfNotNull(dir, dir.parentFile).forEach { folder ->
            check(folder.isDirectory)
            val fd = Os.open(folder.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }

    fun checkExistingKey(context: Context) = synchronized(this) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return@synchronized
        val preferences = File(context.applicationInfo.dataDir, "shared_prefs")
        val encrypted = preferences.listFiles()?.any { it.name.endsWith("_aes256.xml") || it.name.endsWith("_aes256.xml.bak") } == true ||
            context.getDatabasePath(EncryptedMuscleDatabase.NAME).exists() ||
            listOfNotNull(context.filesDir, context.noBackupFilesDir, context.getExternalFilesDir(null)).any { root ->
                root.walkTopDown().any { it.isFile && (it.name.endsWith(".aes") || it.name.endsWith(".aes.writing")) }
            }
        check(!encrypted) { "기존 기록의 암호키가 없어 이관을 중단합니다" }
    }
}
