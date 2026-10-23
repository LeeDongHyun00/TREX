package com.trex.server.dto;

import jakarta.validation.constraints.NotBlank;

/** 앱이 구글 로그인(Credential Manager)으로 받은 ID 토큰. 서버가 직접 검증한다. */
public record GoogleLoginRequest(
        @NotBlank(message = "구글 ID 토큰은 필수입니다")
        String idToken
) {
}
