package com.example.trex_kotlin.security

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 버전·난수 IV·인증 태그를 함께 보관한다. 저장 위치도 인증하여 값 바꿔치기를 거부한다. */
internal class AesGcm(private val key: (create: Boolean) -> SecretKey) {
    fun encrypt(plain: ByteArray, purpose: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)) // IV는 공급자가 매번 새로 만든다.
        check(cipher.iv.size == 12)
        cipher.updateAAD(aad(purpose))
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(encoded: ByteArray, purpose: String): ByteArray {
        if (encoded.size < 29 || encoded[0] != 1.toByte()) throw GeneralSecurityException("지원하지 않는 암호문입니다")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(aad(purpose))
        return cipher.doFinal(encoded, 13, encoded.size - 13)
    }

    private fun aad(purpose: String) = "TREX/AES256/GCM/v1/$purpose".toByteArray(Charsets.UTF_8)
}
