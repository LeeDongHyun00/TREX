package com.trex.server.entity;

/**
 * 회원이 어디로 가입했는지. 소셜 회원은 (provider, providerId) 로 찾는다.
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE,
    KAKAO
}
