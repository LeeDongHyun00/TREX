package com.trex.server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Android 의 Apple 로그인이 앱으로 돌아오는 길(설계 §9) — Apple 의 form_post 를 앱 딥링크(intent://)로 바꾼다.
 * 로그인 없이 부르고(인증 진입점), 정한 넷만 길이 안에서 넘기며, 없으면 앱이 실패를 알 수 있게 error 를 단다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppleWebCallbackControllerTest {

    private static final String TAIL = "#Intent;scheme=trexapple;package=com.example.trex_kotlin;end";

    @Autowired MockMvc mvc;

    @Test
    void 로그인_결과를_앱_딥링크로_넘기고_user_는_넘기지_않는다() throws Exception {
        mvc.perform(post("/api/auth/oauth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "c-1").param("id_token", "h.p.s").param("state", "st-1")
                        .param("user", "{\"name\":{\"firstName\":\"T\"},\"email\":\"a@b.c\"}"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "intent://callback?code=c-1&id_token=h.p.s&state=st-1" + TAIL));
    }

    @Test
    void 사용자가_취소하면_error_를_넘긴다() throws Exception {
        mvc.perform(post("/api/auth/oauth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("error", "user_cancelled_authorize").param("state", "st-1"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "intent://callback?state=st-1&error=user_cancelled_authorize" + TAIL));
    }

    @Test
    void 토큰도_오류도_없으면_invalid_response() throws Exception {
        mvc.perform(post("/api/auth/oauth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("state", "st-1"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "intent://callback?state=st-1&error=invalid_response" + TAIL));
    }

    @Test
    void 너무_긴_값은_빼고_특수문자는_인코딩한다() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id_token", "x".repeat(8193));
        form.add("state", "a#b;c&d=e");
        String url = AppleWebCallbackController.appRedirect(form, "com.example.trex_kotlin");
        assertFalse(url.contains("id_token="));
        // 값 안의 # ; & = 가 그대로 남으면 intent 주소의 다른 칸을 바꿀 수 있다
        assertTrue(url.startsWith("intent://callback?state=a%23b%3Bc%26d%3De&error=invalid_response#Intent;"));
        assertEquals(1, url.chars().filter(ch -> ch == '#').count());
    }
}
