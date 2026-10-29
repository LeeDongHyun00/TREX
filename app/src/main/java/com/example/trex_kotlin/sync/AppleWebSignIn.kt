package com.example.trex_kotlin.sync

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Apple로 계속하기"(Android) — Android 에는 Apple 로그인 창이 없어 Apple 웹 로그인(Custom Tab)을 연다(설계
 * docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §9). Apple 은 결과를 서버 콜백으로 보내고, 서버가 앱 딥링크로 되돌려
 * 보내면 [AppleSignInCallbackActivity][com.example.trex_kotlin.AppleSignInCallbackActivity] 가 [deliver] 한다. 받은 토큰은 iPhone 과
 * 같은 /api/auth/oauth/apple 로 간다.
 *
 * 창을 닫고 돌아오면(결과 없이 앱이 다시 보이면) 취소로 본다 — 결과가 있으면 앱이 다시 보이기 전에 도착한다.
 */
object AppleWebSignIn {
    private class Pending(val state: String, val rawNonce: String, val result: CompletableDeferred<AppleCredential>)

    @Volatile private var pending: Pending? = null

    suspend fun request(activity: ComponentActivity, servicesId: String, redirectUri: String): AppleCredential =
        withContext(Dispatchers.Main) {
            val waiting = Pending(AppleWebAuth.randomHex(16), AppleWebAuth.randomHex(32), CompletableDeferred())
            pending = waiting
            var leftApp = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> leftApp = true
                    // 결과 없이 돌아왔다 — 사용자가 창을 닫았다. 결과 딥링크가 막 도착하는 중일 수 있어 잠깐 기다린다
                    Lifecycle.Event.ON_RESUME -> if (leftApp) activity.lifecycleScope.launch {
                        delay(700)
                        waiting.result.completeExceptionally(SignInException.Cancelled())
                    }
                    else -> Unit
                }
            }
            activity.lifecycle.addObserver(observer)
            try {
                val url = AppleWebAuth.authorizeUrl(servicesId, redirectUri, waiting.state, waiting.rawNonce)
                CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
                waiting.result.await()
            } catch (_: ActivityNotFoundException) {
                throw SignInException.Failed("브라우저를 열지 못했어요")
            } finally {
                activity.lifecycle.removeObserver(observer)
                if (pending === waiting) pending = null
            }
        }

    /** 콜백 딥링크 — 기다리던 요청의 state 와 같을 때만 넘긴다. 다른 링크는 무시한다. */
    fun deliver(uri: String) {
        val waiting = pending ?: return
        try {
            AppleWebAuth.parseCallback(uri, waiting.state, waiting.rawNonce)?.let { waiting.result.complete(it) }
        } catch (e: SignInException) {
            waiting.result.completeExceptionally(e)
        }
    }
}
