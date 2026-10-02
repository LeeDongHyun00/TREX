package com.trex.server.controller;

import com.jayway.jsonpath.JsonPath;
import com.trex.server.entity.AuthProvider;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.oauth.GoogleTokenVerifier;
import com.trex.server.oauth.SocialIdentity;
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

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구글 로그인·동기화·탈퇴 API(docs/SERVER_SYNC_HANDOFF.md 4절). 구글 토큰 검증 자체는
 * {@code GoogleJwksTokenVerifierTest} 가 보고, 여기서는 검증 결과를 받은 뒤의 흐름만 본다.
 *
 * <p>가짜 토큰 형식: {@code ok|<sub>|<email>|<email_verified>|<name>}. 그 밖의 문자열은 검증 실패.
 * 테스트끼리 DB 를 공유하므로 sub·아이디는 매번 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SocialSyncApiTest {

    @TestConfiguration
    static class FakeGoogle {
        @Bean
        @Primary
        GoogleTokenVerifier fakeGoogleTokenVerifier() {
            return idToken -> {
                String[] p = idToken.split("\\|", -1);
                if (p.length != 5 || !p[0].equals("ok")) {
                    throw new InvalidCredentialsException("구글 로그인 정보를 확인하지 못했습니다");
                }
                return new SocialIdentity(AuthProvider.GOOGLE, p[1], p[2].isEmpty() ? null : p[2],
                        Boolean.parseBoolean(p[3]), p[4].isEmpty() ? null : p[4]);
            };
        }
    }

    @Autowired
    MockMvc mvc;

    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ResultActions google(String sub, String email, boolean verified, String name) throws Exception {
        String token = String.join("|", "ok", sub, email == null ? "" : email, String.valueOf(verified), name == null ? "" : name);
        return mvc.perform(post("/api/auth/oauth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"" + token + "\"}"));
    }

    private String bearer(String sub) throws Exception {
        String body = google(sub, null, false, "테스트").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    /** {@code payloadJson} 은 JSON 값 그대로(문자열이면 따옴표 포함). */
    private ResultActions putItem(String auth, String key, String payloadJson, long updatedAt) throws Exception {
        return mvc.perform(put("/api/sync/" + key)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":" + payloadJson + ",\"updatedAt\":" + updatedAt + "}"));
    }

    private ResultActions postJson(String auth, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void 첫_구글_로그인은_회원을_만들고_다시_로그인하면_같은_회원이다() throws Exception {
        String sub = uniq();
        String email = sub + "@gmail.com";
        String first = google(sub, email, true, "공룡").andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.loginId").value("google_" + sub))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("공룡"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(first, "$.user.id");

        google(sub, email, true, "이름 바뀜").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(id));
    }

    @Test
    void 이름이_없으면_기본_이름을_쓰고_확인_안_된_이메일은_저장하지_않는다() throws Exception {
        String sub = uniq();
        google(sub, sub + "@gmail.com", false, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("T-REX 사용자"))
                .andExpect(jsonPath("$.user.email").value(nullValue()));
    }

    @Test
    void 이미_가입된_이메일이면_계정을_합치지_않고_이메일을_비운다() throws Exception {
        String local = "u" + uniq();
        String email = local + "@example.com";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + local + "\",\"password\":\"password123\",\"name\":\"기존\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated());

        google(uniq(), email, true, "새 구글").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.loginId").value(startsWith("google_")))
                .andExpect(jsonPath("$.user.email").value(nullValue()));
    }

    @Test
    void 잘못된_구글_토큰은_401_빈_토큰은_400() throws Exception {
        mvc.perform(post("/api/auth/oauth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"forged\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/oauth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 소셜_회원은_내부_아이디로_비밀번호_로그인을_할_수_없다() throws Exception {
        String sub = uniq();
        google(sub, null, false, "x").andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"google_" + sub + "\",\"password\":\"anything1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 소셜_내부_아이디_모양으로는_가입할_수_없다() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"Kakao_123456\",\"password\":\"password123\",\"name\":\"x\",\"email\":\"k" + uniq() + "@example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 동기화는_올린_항목을_돌려주고_오래된_쓰기는_409로_서버_값을_준다() throws Exception {
        String auth = bearer(uniq());

        mvc.perform(get("/api/sync").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        putItem(auth, "diet", "\"{\\\"v\\\":1}\"", 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("diet"))
                .andExpect(jsonPath("$.updatedAt").value(1000));
        putItem(auth, "diet", "\"{\\\"v\\\":2}\"", 2000).andExpect(status().isOk());

        // 다른 기기가 더 옛날에 바꾼 값 — 반영하지 않고 서버 값을 돌려준다
        putItem(auth, "diet", "\"{\\\"v\\\":0}\"", 1500).andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload").value("{\"v\":2}"))
                .andExpect(jsonPath("$.updatedAt").value(2000));

        putItem(auth, "profile", "\"p\"", 10).andExpect(status().isOk());
        mvc.perform(get("/api/sync").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].key").value("diet"))
                .andExpect(jsonPath("$.items[0].payload").value("{\"v\":2}"))
                .andExpect(jsonPath("$.items[1].key").value("profile"));
    }

    @Test
    void 동기화_항목은_회원마다_따로다() throws Exception {
        String a = bearer(uniq());
        String b = bearer(uniq());
        putItem(a, "water", "\"a\"", 1).andExpect(status().isOk());
        mvc.perform(get("/api/sync").header("Authorization", b))
                .andExpect(jsonPath("$.items.length()").value(0));
        // a 의 updatedAt 보다 작아도 b 에게는 첫 쓰기다
        putItem(b, "water", "\"b\"", 0).andExpect(status().isOk());
    }

    @Test
    void 동기화_잘못된_요청은_400_토큰_없으면_401() throws Exception {
        String auth = bearer(uniq());
        putItem(auth, "Bad-Key", "\"x\"", 1).andExpect(status().isBadRequest());
        putItem(auth, "k", "null", 1).andExpect(status().isBadRequest());
        putItem(auth, "k", "\"x\"", -1).andExpect(status().isBadRequest());
        String tooBig = "\"" + "a".repeat(2 * 1024 * 1024 + 1) + "\"";
        putItem(auth, "big", tooBig, 1).andExpect(status().isBadRequest());

        mvc.perform(get("/api/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴하면_회원과_모든_데이터가_지워지고_다시_로그인하면_새_회원이다() throws Exception {
        String sub = uniq();
        String body = google(sub, null, false, "x").andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(body, "$.user.id");
        String auth = "Bearer " + JsonPath.read(body, "$.accessToken");

        // 지울 데이터를 종류별로 하나씩 — 외래키로 묶인 하위 행이 남으면 탈퇴가 실패한다
        putItem(auth, "diet", "\"d\"", 1).andExpect(status().isOk());
        postJson(auth, "/api/profiles", """
                {"fitnessGoal":"근력","workoutDays":[1,3],"place":"헬스장","gender":"M",
                 "availableEquip":["바벨"],"height":175,"weight":70,"age":25}""")
                .andExpect(status().is2xxSuccessful());
        postJson(auth, "/api/diet-logs", """
                {"logDate":"2026-10-01","targetCal":2000,"targetCarb":250,"targetProtein":120,"targetFat":60,
                 "actualCal":500,"actualCarb":60,"actualProtein":30,"actualFat":10,
                 "meals":[{"mealType":"LUNCH","foodItems":[{"foodName":"김밥","calories":500,"carb":60,"protein":30,"fat":10}]}]}""")
                .andExpect(status().is2xxSuccessful());
        postJson(auth, "/api/exercise-logs", """
                {"logDate":"2026-10-01","totalCalories":300,"totalTimeMin":40,"isPostureChecked":true,
                 "setScoresJson":[80,90],"routines":[{"exerciseName":"기본 스쿼트","sets":3,"reps":10}]}""")
                .andExpect(status().is2xxSuccessful());

        mvc.perform(delete("/api/auth/me").header("Authorization", auth)).andExpect(status().isNoContent());
        // 지워진 회원의 토큰은 더 이상 통하지 않는다
        mvc.perform(get("/api/auth/me").header("Authorization", auth)).andExpect(status().isUnauthorized());

        String again = google(sub, null, false, "x").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNotEquals(id, JsonPath.<Integer>read(again, "$.user.id"));
        mvc.perform(get("/api/sync").header("Authorization", "Bearer " + JsonPath.read(again, "$.accessToken")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
