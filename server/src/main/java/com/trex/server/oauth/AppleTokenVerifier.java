package com.trex.server.oauth;

/**
 * Apple identity token 검증(Apple 로그인, 설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md).
 * 실제 구현은 {@link AppleJwksTokenVerifier}, 테스트는 가짜 구현으로 바꿔 끼운다.
 */
public interface AppleTokenVerifier {
    /**
     * 토큰이 유효하면 사용자 정보를 돌려준다. 서명·발급자·대상(앱 번들 ID)·만료·nonce 중 하나라도 맞지 않으면
     * {@link com.trex.server.exception.InvalidCredentialsException}.
     *
     * @param rawNonce 앱이 만든 원문 — 앱은 Apple 요청에 이 값의 SHA-256 을 넣었고, 토큰의 {@code nonce} 가 그 값이어야 한다
     */
    SocialIdentity verify(String identityToken, String rawNonce);
}
