package com.trex.server.oauth;

import java.util.Optional;

/**
 * Sign in with Apple REST API — authorization code 를 refresh token 으로 바꾸고, 탈퇴 때 연결을 끊는다(App Store 탈퇴 규칙).
 * 실제 구현은 {@link AppleRestTokenClient}, 테스트는 가짜로 바꿔 끼운다. 어느 쪽도 예외를 던지지 않는다 — 실패는 로그.
 */
public interface AppleTokenClient {

    /** 키가 설정돼 있어 Apple 을 부를 수 있는가. 아니면 아래 둘은 아무것도 하지 않는다. */
    boolean configured();

    /** code(5분 안에 한 번)를 refresh token 으로. 설정 없음·실패면 비어 있다. */
    Optional<String> exchangeCode(String authorizationCode);

    /** refresh token 을 무효로 — Apple 쪽 연결 끊기. 실패는 로그만 남긴다. */
    void revoke(String refreshToken);
}
