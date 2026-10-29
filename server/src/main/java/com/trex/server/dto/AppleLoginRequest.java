package com.trex.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * iPhone 이 Apple 로그인으로 받은 값(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §4). 서버가 직접 검증한다.
 *
 * @param identityToken     Apple 이 서명한 JWT
 * @param authorizationCode 5분 안에 한 번 쓸 수 있는 코드 — 키가 설정돼 있으면 refresh token 으로 바꿔 탈퇴 때 연결을 끊는다. 없어도 된다
 * @param nonce             앱이 만든 원문 — 토큰의 nonce 는 이 값의 SHA-256 이어야 한다
 */
public record AppleLoginRequest(
        @NotBlank(message = "Apple identity token 은 필수입니다")
        String identityToken,
        String authorizationCode,
        @NotBlank(message = "nonce 는 필수입니다")
        String nonce
) {
}
