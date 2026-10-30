package com.example.trex_kotlin.sync

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Android 의 Apple 로그인(웹 흐름, 설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §9)의 순수 계산 —
 * Apple 웹 로그인 주소를 만들고, 서버 콜백이 되돌려 보낸 딥링크(`trexapple://callback?…`)를 읽는다. 창·딥링크 연결은 [AppleWebSignIn].
 */
object AppleWebAuth {
    /** 서버 AppleWebCallbackController 가 되돌려 보내는 스킴·호스트(AndroidManifest 의 AppleSignInCallbackActivity). */
    const val CALLBACK_SCHEME = "trexapple"
    const val CALLBACK_HOST = "callback"

    private const val CANCELLED = "user_cancelled_authorize"

    /** 무작위 16진 — nonce 원문·state. */
    fun randomHex(bytes: Int, random: SecureRandom = SecureRandom()): String =
        ByteArray(bytes).also(random::nextBytes).toHex()

    /** 서버(AppleJwksTokenVerifier.sha256Hex)·iPhone(CryptoKit)과 같은 소문자 16진 — 다르면 모든 로그인이 nonce 불일치로 실패한다. */
    fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Apple 웹 로그인 주소. 이름·이메일 범위를 달라고 하면 response_mode 가 form_post 여야 해서 결과는 [redirectUri](서버)로 간다.
     * nonce 에는 원문의 SHA-256 을 넣는다(iPhone 과 같다) — 원문은 앱만 들고 있다가 서버 로그인 때 보낸다.
     */
    fun authorizeUrl(servicesId: String, redirectUri: String, state: String, rawNonce: String): String {
        val params = linkedMapOf(
            "client_id" to servicesId,
            "redirect_uri" to redirectUri,
            "response_type" to "code id_token",
            "scope" to "name email",
            "response_mode" to "form_post",
            "state" to state,
            "nonce" to sha256Hex(rawNonce),
        )
        return "https://appleid.apple.com/auth/authorize?" + params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * 콜백 딥링크를 읽는다. 기다리던 [state] 와 다르면 null — 다른 요청이거나 위조라 무시한다. 사용자가 Apple 화면에서 취소했으면
     * [SignInException.Cancelled], 그 밖의 오류나 토큰이 없으면 [SignInException.Failed].
     */
    fun parseCallback(uri: String, state: String, rawNonce: String): AppleCredential? {
        val params = uri.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.associate { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        if (params["state"] != state) return null
        when (params["error"]) {
            null -> Unit
            CANCELLED -> throw SignInException.Cancelled()
            else -> throw SignInException.Failed("Apple 로그인 응답을 받지 못했어요")
        }
        val token = params["id_token"]?.takeIf { it.isNotBlank() } ?: throw SignInException.Failed("Apple 로그인 정보를 받지 못했어요")
        return AppleCredential(token, params["code"]?.takeIf { it.isNotBlank() }, rawNonce)
    }
}
