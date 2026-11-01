package com.example.trex_kotlin.ios

import com.example.trex_kotlin.sync.AppleCredential
import com.example.trex_kotlin.sync.SignInException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * iPhone Apple 로그인(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md) — 구글 다리(IosGoogleSignIn.kt)와 같은 모양.
 * 로그인 창은 Swift(`iosApp/Trex/AppleSignInBridge.swift` — AuthenticationServices)가 맡고, 앱 시작 때
 * [IosAppleSignInRegistry] 에 넣는다. 서버가 확인할 identity token·authorization code·nonce 원문을 받아 오는 데까지가 이 다리의 일이다.
 * 버튼은 구글 버튼과 같은 Compose 버튼(공유 `SignInUi.kt`)이라 다리가 만들지 않는다.
 */

/** Swift 가 구현하는 Apple 로그인 다리. */
interface IosAppleSignInBridge {
    /**
     * Apple 로그인 창을 띄운다(메인에서 부른다). 결과는 [done](메인)에 — 성공이면 (토큰, 코드 또는 null, nonce 원문, null),
     * 사용자가 닫았으면 (null, null, null, [IosAppleSignInRegistry.CANCELLED]), 그 밖은 (null, null, null, 이유).
     */
    fun signIn(done: (identityToken: String?, authorizationCode: String?, rawNonce: String?, error: String?) -> Unit)
}

object IosAppleSignInRegistry {
    /** [IosAppleSignInBridge.signIn] 의 '사용자가 닫음'. */
    const val CANCELLED = "cancelled"

    @Volatile var bridge: IosAppleSignInBridge? = null
}

/** Apple 로그인 한 번 — 닫으면 [SignInException.Cancelled](안내하지 않음), 그 밖의 실패는 [SignInException.Failed](이유를 화면에). */
internal suspend fun requestIosAppleCredential(): AppleCredential {
    val bridge = IosAppleSignInRegistry.bridge ?: throw SignInException.Failed("Apple 로그인을 준비하지 못했어요")
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            bridge.signIn { identityToken, authorizationCode, rawNonce, error ->
                if (!continuation.isActive) return@signIn
                when {
                    identityToken != null && rawNonce != null -> continuation.resume(AppleCredential(identityToken, authorizationCode, rawNonce))
                    error == IosAppleSignInRegistry.CANCELLED -> continuation.resumeWithException(SignInException.Cancelled())
                    else -> continuation.resumeWithException(SignInException.Failed(error ?: "Apple 로그인에 실패했어요"))
                }
            }
        }
    }
}
