package com.example.trex_kotlin.sync

import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.nowMillis

/**
 * 서버 로그인 토큰 보관. `trex_auth` 는 자동 백업에서 뺀다(backup_rules) — 새 기기에서는 다시 로그인한다.
 * 토큰이 없거나 만료됐으면 [current] 가 null 이다. 저장소는 `trex_auth` 키-값 저장소다(§108).
 */
class AuthSession(private val prefs: KeyValueStore) {

    fun current(now: Long = nowMillis()): AuthAccount? {
        val token = prefs.getString("token", null) ?: return null
        val expires = prefs.getLong("expires_at", 0L)
        if (expires <= now) return null
        return AuthAccount(
            token = token,
            expiresAtMillis = expires,
            loginId = prefs.getString("login_id", null) ?: return null,
            name = prefs.getString("name", null).orEmpty(),
            email = prefs.getString("email", null),
        )
    }

    val token: String? get() = current()?.token

    fun save(account: AuthAccount) {
        prefs.edit()
            .putString("token", account.token)
            .putLong("expires_at", account.expiresAtMillis)
            .putString("login_id", account.loginId)
            .putString("name", account.name)
            .putString("email", account.email)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * 플랫폼 로그인 창(구글 계정 선택)의 실패 쪽(§108). 앱 상태가 플랫폼 예외 타입을 몰라도 같은 안내를 낸다 —
 * Android 는 Credential Manager 예외를 이것으로 바꿔 던진다(GoogleSignIn.kt).
 */
sealed class SignInException(message: String?) : Exception(message) {
    /** 사용자가 계정 선택을 닫았다 — 안내하지 않는다. */
    class Cancelled : SignInException(null)

    /** 이 기기에 구글 계정이 없다. */
    class NoAccount : SignInException(null)

    /** 그 밖의 실패(콘솔 설정 오류 등). [reason] 을 화면에 보인다. */
    class Failed(val reason: String) : SignInException(reason)
}
