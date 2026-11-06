package com.example.trex_kotlin.ios

import com.example.trex_kotlin.sync.SignInException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * iPhone 구글 로그인 — Android `requestGoogleIdToken`(Credential Manager)의 짝.
 * 구글 로그인 창은 Swift(`iosApp/Trex/GoogleSignInBridge.swift` — iOS 기본 ASWebAuthenticationSession 으로 OAuth 인가 코드 + PKCE)가 맡고,
 * 앱 시작 때 [IosGoogleSignInRegistry] 에 넣는다. GoogleSignIn SDK 는 MediaPipe 와 링크 때 부딪혀 쓰지 않는다.
 * 서버가 확인하는 ID 토큰을 받아 오는 데까지가 이 다리의 일이고, 서버 로그인·동기화는 Android 와 같은 공유 앱 상태(`TrexAppState`)가 한다.
 */

/** Swift 가 구현하는 구글 로그인 다리. */
interface IosGoogleSignInBridge {
    /**
     * 구글 로그인 창을 띄운다(메인에서 부른다). [serverClientId] 는 서버가 확인하는 웹 클라이언트 ID 다.
     * 결과는 [done](메인)에 — 성공이면 (ID 토큰, null), 사용자가 창을 닫았으면 (null, [IosGoogleSignInRegistry.CANCELLED]), 그 밖은 (null, 이유).
     */
    fun signIn(serverClientId: String, done: (String?, String?) -> Unit)
}

object IosGoogleSignInRegistry {
    /** [IosGoogleSignInBridge.signIn] 의 '사용자가 닫음'. */
    const val CANCELLED = "cancelled"

    @Volatile var bridge: IosGoogleSignInBridge? = null
}

/** 구글 ID 토큰 한 번 — 닫으면 [SignInException.Cancelled](안내하지 않음), 그 밖의 실패는 [SignInException.Failed](이유를 화면에). */
internal suspend fun requestIosGoogleIdToken(serverClientId: String): String {
    val bridge = IosGoogleSignInRegistry.bridge ?: throw SignInException.Failed("구글 로그인을 준비하지 못했어요")
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            bridge.signIn(serverClientId) { idToken, error ->
                if (!continuation.isActive) return@signIn
                when {
                    idToken != null -> continuation.resume(idToken)
                    error == IosGoogleSignInRegistry.CANCELLED -> continuation.resumeWithException(SignInException.Cancelled())
                    else -> continuation.resumeWithException(SignInException.Failed(error ?: "구글 로그인에 실패했어요"))
                }
            }
        }
    }
}
