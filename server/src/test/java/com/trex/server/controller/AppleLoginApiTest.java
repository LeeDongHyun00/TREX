package com.trex.server.controller;

import com.jayway.jsonpath.JsonPath;
import com.trex.server.entity.AuthProvider;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.oauth.AppleTokenClient;
import com.trex.server.oauth.AppleTokenVerifier;
import com.trex.server.oauth.SocialIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Apple 로그인 API(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §3·§4) — 검증기와 Apple 토큰 클라이언트는
 * 가짜. 로그인·재로그인·refresh token 저장·탈퇴 때 연결 끊기·실패해도 탈퇴·키 없을 때를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppleLoginApiTest {

    /** 기록하는 가짜 Apple 토큰 클라이언트 — 테스트마다 스위치를 바꾼다. */
    static class RecordingAppleTokenClient implements AppleTokenClient {
        boolean configured = true;
        String exchangeResult = "rt-1";
        boolean exchangeThrows = false;
        boolean revokeThrows = false;
        final List<String> revoked = new ArrayList<>();

        @Override public boolean configured() { return configured; }

        @Override public Optional<String> exchangeCode(String code) {
            if (!configured) return Optional.empty();
            if (exchangeThrows) throw new IllegalStateException("Apple 서버 오류");
            return Optional.ofNullable(exchangeResult);
        }

        @Override public void revoke(String refreshToken) {
            revoked.add(refreshToken);
            if (revokeThrows) throw new IllegalStateException("Apple 서버 오류");
        }
    }

    @TestConfiguration
    static class FakeApple {
        @Bean
        @Primary
        AppleTokenVerifier fakeAppleTokenVerifier() {
            // 토큰 "ok|sub|email|verified", nonce 원문 "n" 만 통과
            return (identityToken, rawNonce) -> {
                String[] p = identityToken.split("\\|", -1);
                if (p.length != 4 || !p[0].equals("ok") || !"n".equals(rawNonce)) {
                    throw new InvalidCredentialsException("Apple 로그인 정보를 확인하지 못했습니다");
                }
                return new SocialIdentity(AuthProvider.APPLE, p[1], p[2].isEmpty() ? null : p[2], Boolean.parseBoolean(p[3]), null);
            };
        }

        @Bean
        @Primary
        RecordingAppleTokenClient fakeAppleTokenClient() {
            return new RecordingAppleTokenClient();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RecordingAppleTokenClient apple;

    @BeforeEach
    void reset() {
        apple.configured = true;
        apple.exchangeResult = "rt-1";
        apple.exchangeThrows = false;
        apple.revokeThrows = false;
        apple.revoked.clear();
    }

    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ResultActions apple(String sub, String code) throws Exception {
        String codeJson = code == null ? "" : ",\"authorizationCode\":\"" + code + "\"";
        return mvc.perform(post("/api/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identityToken\":\"ok|" + sub + "|" + sub + "@privaterelay.appleid.com|true\",\"nonce\":\"n\"" + codeJson + "}"));
    }

    private String bearer(ResultActions login) throws Exception {
        return "Bearer " + JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private Integer userId(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.user.id");
    }

    @Test
    void 첫_apple_로그인은_apple_회원을_만들고_다시_로그인하면_같은_회원이다() throws Exception {
        String sub = uniq();
        ResultActions first = apple(sub, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.loginId").value("apple_" + sub))
                .andExpect(jsonPath("$.user.email").value(sub + "@privaterelay.appleid.com"));
        Integer id = userId(first);
        assertEquals(id, userId(apple(sub, null).andExpect(status().isOk())));
    }

    @Test
    void 키가_있으면_code_를_교환해_refresh_token_을_저장한다() throws Exception {
        String auth = bearer(apple(uniq(), "code-1").andExpect(status().isOk()));
        mvc.perform(delete("/api/auth/me").header("Authorization", auth)).andExpect(status().isNoContent());
        assertEquals(List.of("rt-1"), apple.revoked);
    }

    @Test
    void code_교환이_실패해도_로그인은_된다() throws Exception {
        apple.exchangeResult = null; // 만료·재사용(invalid_grant)
        apple(uniq(), "old-code").andExpect(status().isOk());
        apple.exchangeThrows = true;
        apple(uniq(), "code-2").andExpect(status().isOk());
    }

    @Test
    void refresh_token_이_없으면_revoke_없이_탈퇴된다() throws Exception {
        // 동현님 키를 넣기 전에 가입한 사람
        apple.configured = false;
        String auth = bearer(apple(uniq(), "code-1").andExpect(status().isOk()));
        apple.configured = true;
        mvc.perform(delete("/api/auth/me").header("Authorization", auth)).andExpect(status().isNoContent());
        assertEquals(List.of(), apple.revoked);
    }

    @Test
    void revoke_가_실패해도_탈퇴된다() throws Exception {
        String sub = uniq();
        ResultActions login = apple(sub, "code-1").andExpect(status().isOk());
        Integer id = userId(login);
        apple.revokeThrows = true;
        mvc.perform(delete("/api/auth/me").header("Authorization", bearer(login))).andExpect(status().isNoContent());
        assertEquals(List.of("rt-1"), apple.revoked);
        assertNotEquals(id, userId(apple(sub, null).andExpect(status().isOk()))); // 새 회원
    }

    @Test
    void 잘못된_apple_토큰은_401_nonce_가_없으면_400() throws Exception {
        mvc.perform(post("/api/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identityToken\":\"forged\",\"nonce\":\"n\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identityToken\":\"ok|" + uniq() + "||true\",\"nonce\":\"other\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identityToken\":\"ok|" + uniq() + "||true\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void apple_내부_아이디_모양으로는_가입할_수_없다() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"apple_123456\",\"password\":\"password123\",\"name\":\"x\",\"email\":\"a" + uniq() + "@example.com\"}"))
                .andExpect(status().isConflict());
    }
}
