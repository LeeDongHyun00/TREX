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
 * 설정의 계정 줄 이름 — 이메일(Apple 은 가린 전달 주소일 수 있다), 없으면 Apple 회원은 "Apple 계정",
 * 구글 회원은 이름이나 "Google 계정". 예전에는 이름이 비면 늘 "Google 계정"이라 Apple 사용자가 헷갈렸다.
 */
fun accountLabel(account: AuthAccount): String = when {
    account.email != null -> account.email
    account.loginId.startsWith("apple_") -> "Apple 계정"
    account.name.isNotBlank() -> account.name
    else -> "Google 계정"
}
