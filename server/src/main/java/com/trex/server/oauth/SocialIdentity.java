package com.trex.server.oauth;

import com.trex.server.entity.AuthProvider;

/**
 * 소셜 로그인 제공자가 **검증해 준** 사용자 정보. 앱이 보낸 값을 그대로 믿지 않고 토큰 검증을 거친 것만 담는다.
 *
 * @param providerId    제공자 안에서 바뀌지 않는 사용자 번호(구글 sub, 카카오 id)
 * @param email         없을 수 있다(카카오 이메일 미동의)
 * @param emailVerified 제공자가 이메일 소유를 확인했는지 — 확인 안 된 이메일은 회원 이메일로 쓰지 않는다
 * @param name          없을 수 있다
 * @param clientId      토큰의 대상(aud) 가운데 우리 설정에 있는 값 — Apple 은 번들 ID(iPhone) 또는 Services ID(Android 웹 로그인)이고,
 *                      code 교환·연결 끊기를 같은 값으로 불러야 한다. 쓰지 않는 제공자(구글)는 null
 */
public record SocialIdentity(
        AuthProvider provider,
        String providerId,
        String email,
        boolean emailVerified,
        String name,
        String clientId
) {
    /** 토큰 대상을 따로 쓰지 않는 제공자(구글). */
    public SocialIdentity(AuthProvider provider, String providerId, String email, boolean emailVerified, String name) {
        this(provider, providerId, email, emailVerified, name, null);
    }
}
