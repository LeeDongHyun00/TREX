package com.trex.server.oauth;

import com.trex.server.entity.AuthProvider;

/**
 * 소셜 로그인 제공자가 **검증해 준** 사용자 정보. 앱이 보낸 값을 그대로 믿지 않고 토큰 검증을 거친 것만 담는다.
 *
 * @param providerId    제공자 안에서 바뀌지 않는 사용자 번호(구글 sub, 카카오 id)
 * @param email         없을 수 있다(카카오 이메일 미동의)
 * @param emailVerified 제공자가 이메일 소유를 확인했는지 — 확인 안 된 이메일은 회원 이메일로 쓰지 않는다
 * @param name          없을 수 있다
 */
public record SocialIdentity(
        AuthProvider provider,
        String providerId,
        String email,
        boolean emailVerified,
        String name
) {
}
