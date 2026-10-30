package com.example.trex_kotlin.sync

/**
 * iPhone Apple 로그인이 돌려준 값(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §4) — 서버가 검증한다.
 *
 * @param identityToken Apple 이 서명한 JWT
 * @param authorizationCode 서버가 refresh token 으로 바꿔 탈퇴 때 Apple 연결을 끊는 데 쓴다(5분 안에 한 번). 없을 수 있다
 * @param nonce 이 기기가 만든 원문 — Apple 요청에는 SHA-256 을 넣었고, 서버는 토큰의 nonce 와 비교한다
 */
data class AppleCredential(val identityToken: String, val authorizationCode: String?, val nonce: String)

/**
 * 설정의 계정 줄 이름 — 로그인 방법을 앞에 붙인다("아이디 · trex_user", "Google · 이메일", "Apple · 이메일"). 소셜 회원은 이메일
 * (Apple 은 가린 전달 주소일 수 있다), 없으면 이름, 둘 다 없으면 "Apple 계정"·"Google 계정". 구글 계정을 Apple 계정으로 알고
 * Apple 만 탈퇴해 구글 쪽 댓글이 남은 일(10-29)이 있어 방법을 늘 보인다.
 * 서버는 `google_`·`apple_`·`kakao_` 로 시작하는 아이디로 가입하지 못하게 막으므로 접두어로 가른다.
 */
fun accountLabel(account: AuthAccount): String {
    val method = SOCIAL_METHODS.entries.firstOrNull { account.loginId.startsWith(it.key) }?.value
        ?: return "아이디 · ${account.loginId}"
    val detail = account.email ?: account.name.takeIf { it.isNotBlank() && method != "Apple" }
    return if (detail != null) "$method · $detail" else "$method 계정"
}

private val SOCIAL_METHODS = linkedMapOf("google_" to "Google", "apple_" to "Apple", "kakao_" to "카카오")
