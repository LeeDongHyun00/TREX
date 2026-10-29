package com.trex.server.controller;

import com.trex.server.community.CommunityTestConfig;
import com.trex.server.community.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 아이디·비밀번호 가입과 로그인(docs/SERVER_SYNC_HANDOFF.md D13). 아이디는 영문·숫자·밑줄 4~20자, 비밀번호는 영문과 숫자를 섞은 8~64자.
 * 같은 아이디로 5번 연속 틀리면 10분 동안 맞는 비밀번호도 받지 않는다(비밀번호 대입 막기). 시계는 테스트가 넘긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CommunityTestConfig.class)
class PasswordAccountApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ResultActions signup(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\",\"name\":\"지민\",\"email\":\"" + uniq() + "@example.com\"}"));
    }

    private ResultActions login(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
    }

    /** 새 회원(비밀번호 password123)의 아이디. */
    private String member() throws Exception {
        String id = "u" + uniq();
        signup(id, "password123").andExpect(status().isCreated());
        return id;
    }

    @Test
    void 아이디는_영문_숫자_밑줄_4에서_20자만_가입된다() throws Exception {
        for (String bad : new String[]{"abc", "한글아이디입니다", "abc-def", "abc def", "a".repeat(21)}) {
            signup(bad, "password123").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.loginId").value("아이디는 영문·숫자·밑줄 4~20자여야 합니다"));
        }
        signup("T_" + uniq(), "password123").andExpect(status().isCreated());
    }

    @Test
    void 비밀번호는_영문과_숫자를_섞은_8자_이상이어야_한다() throws Exception {
        signup("u" + uniq(), "password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("비밀번호는 영문과 숫자를 섞어야 합니다"));
        signup("u" + uniq(), "12345678").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("비밀번호는 영문과 숫자를 섞어야 합니다"));
        signup("u" + uniq(), "pass123").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("비밀번호는 8자 이상이어야 합니다"));
        signup("u" + uniq(), "pass word 123!").andExpect(status().isCreated());
    }

    @Test
    void 가입한_아이디와_비밀번호로_로그인된다() throws Exception {
        String id = member();
        login(id, "password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.loginId").value(id));
        login(id, "password124").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다"));
    }

    @Test
    void 같은_아이디로_5번_연속_틀리면_10분_동안_맞는_비밀번호도_막는다() throws Exception {
        String id = member();
        for (int i = 0; i < 5; i++) {
            login(id, "wrong" + i + "xx").andExpect(status().isUnauthorized());
        }
        login(id, "password123").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("비밀번호를 여러 번 틀렸습니다. 10분 뒤에 다시 시도해 주세요"));

        clock.advance(Duration.ofMinutes(9));
        login(id, "password123").andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofMinutes(1));
        login(id, "password123").andExpect(status().isOk());
    }

    @Test
    void 로그인에_성공하면_틀린_횟수를_처음부터_다시_센다() throws Exception {
        String id = member();
        for (int i = 0; i < 4; i++) login(id, "wrong" + i + "xx").andExpect(status().isUnauthorized());
        login(id, "password123").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login(id, "wrong" + i + "xx").andExpect(status().isUnauthorized());
        login(id, "password123").andExpect(status().isOk());
    }

    @Test
    void 마지막으로_틀린_지_10분이_지나면_틀린_횟수가_사라진다() throws Exception {
        String id = member();
        for (int i = 0; i < 4; i++) login(id, "wrong" + i + "xx").andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(10));
        login(id, "wrongagain1").andExpect(status().isUnauthorized());
        login(id, "password123").andExpect(status().isOk());
    }

    @Test
    void 막힘은_아이디마다_따로고_없는_아이디도_똑같이_막힌다() throws Exception {
        String id = member();
        String ghost = "ghost" + uniq();
        for (int i = 0; i < 5; i++) login(ghost, "wrong" + i + "xx").andExpect(status().isUnauthorized());
        login(ghost, "password123").andExpect(status().isTooManyRequests());
        login(id, "password123").andExpect(status().isOk());
    }
}
