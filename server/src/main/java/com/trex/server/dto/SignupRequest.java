package com.trex.server.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(

        @NotBlank(message = "아이디는 필수입니다")
        @Size(min = 4, max = 20, message = "아이디는 4~20자여야 합니다")
        String loginId,

        @NotBlank(message = "비밀번호는 필수입니다")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상이어야 합니다")
        String password,

        @NotBlank(message = "이름은 필수입니다")
        @Size(max = 20, message = "이름은 20자 이하여야 합니다")
        String name,

        @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        String email
) {
}
