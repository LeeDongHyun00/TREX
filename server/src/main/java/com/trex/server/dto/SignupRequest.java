package com.trex.server.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 아이디·비밀번호 가입(docs/SERVER_SYNC_HANDOFF.md D13) — 앱의 회원가입 화면과 같은 규칙. */
public record SignupRequest(

        @NotBlank(message = "아이디는 필수입니다")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$", message = "아이디는 영문·숫자·밑줄 4~20자여야 합니다")
        String loginId,

        @NotBlank(message = "비밀번호는 필수입니다")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상이어야 합니다")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).*$", message = "비밀번호는 영문과 숫자를 섞어야 합니다")
        String password,

        @NotBlank(message = "이름은 필수입니다")
        @Size(max = 20, message = "이름은 20자 이하여야 합니다")
        String name,

        @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        String email
) {
}
