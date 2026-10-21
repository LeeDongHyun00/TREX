package com.trex.server.oauth;

/**
 * 구글 ID 토큰 검증. 실제 구현은 {@link GoogleJwksTokenVerifier}, 테스트는 가짜 구현으로 바꿔 끼운다.
 */
public interface GoogleTokenVerifier {

    /**
     * 토큰이 유효하면 사용자 정보를 돌려준다. 서명·발급자·대상 클라이언트·만료 중 하나라도 맞지 않으면
     * {@link com.trex.server.exception.InvalidCredentialsException}.
     */
    SocialIdentity verify(String idToken);
}
