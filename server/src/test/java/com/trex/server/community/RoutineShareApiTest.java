package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 루틴 공유 API(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 공유는 커뮤니티 준비가 된 회원만, 조회는 누구나.
 * 이름 1~30자·금칙어, 운동 1~30개, 회원마다 10분에 10개. 탈퇴하면 공유 루틴도 지운다.
 */
class RoutineShareApiTest extends CommunityApiTestSupport {

    private static final String EXERCISE = "{\"name\":\"기본 스쿼트\",\"reps\":\"3세트 x 12회\",\"duration\":\"5분\",\"posture\":true,"
            + "\"category\":\"하체\",\"restSeconds\":60,\"targetKind\":\"repetitions\",\"targetAmount\":12}";

    private static String routineJson(String name, int exerciseCount) {
        return "{\"name\":\"" + name + "\",\"totalSeconds\":1500,\"exercises\":["
                + String.join(",", Collections.nCopies(exerciseCount, EXERCISE)) + "]}";
    }

    private String share(String auth, String name, int count) throws Exception {
        String body = postJson(auth, "/api/routines/share", routineJson(name, count))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    @Test
    void shareThenAnyoneCanRead() throws Exception {
        String auth = ready(uniq());
        String body = postJson(auth, "/api/routines/share", routineJson("하체 불태우기", 3))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");
        assertTrue(code.matches("[A-Za-z0-9]{8}"), code);
        assertEquals("https://trex-api.duckdns.org/r/" + code, JsonPath.read(body, "$.url"));
        mvc.perform(get("/api/routines/shared/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("하체 불태우기"))
                .andExpect(jsonPath("$.totalSeconds").value(1500))
                .andExpect(jsonPath("$.exercises.length()").value(3))
                .andExpect(jsonPath("$.exercises[0].restSeconds").value(60))
                .andExpect(jsonPath("$.exercises[0].targetKind").value("repetitions"))
                .andExpect(jsonPath("$.senderNickname").isString());
    }

    @Test
    void unknownCodeIs404() throws Exception {
        mvc.perform(get("/api/routines/shared/AAAAAAAA"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("없어졌거나 잘못된 링크예요"));
    }

    @Test
    void shareNeedsLoginAndCommunitySetup() throws Exception {
        mvc.perform(post("/api/routines/share").contentType("application/json").content(routineJson("하체", 1)))
                .andExpect(status().isUnauthorized());
        postJson(bearer(uniq()), "/api/routines/share", routineJson("하체", 1)).andExpect(status().isForbidden());
    }

    @Test
    void shareRejectsOutOfRangeInput() throws Exception {
        String auth = ready(uniq());
        postJson(auth, "/api/routines/share", routineJson("하체", 0)).andExpect(status().isBadRequest());
        postJson(auth, "/api/routines/share", routineJson("하체", 31)).andExpect(status().isBadRequest());
        postJson(auth, "/api/routines/share", routineJson("가".repeat(31), 1)).andExpect(status().isBadRequest());
        postJson(auth, "/api/routines/share", routineJson("   ", 1)).andExpect(status().isBadRequest());
        postJson(auth, "/api/routines/share", routineJson("씨발 루틴", 1)).andExpect(status().isBadRequest());
        postJson(auth, "/api/routines/share", routineJson("가".repeat(30), 30)).andExpect(status().isCreated());
    }

    @Test
    void shareIsRateLimited() throws Exception {
        String auth = ready(uniq());
        for (int i = 0; i < 10; i++) share(auth, "루틴 " + i, 1);
        postJson(auth, "/api/routines/share", routineJson("열한 번째", 1)).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofMinutes(10));
        share(auth, "다시", 1);
    }

    @Test
    void deletingAccountDeletesSharedRoutines() throws Exception {
        String auth = ready(uniq());
        String code = share(auth, "지울 루틴", 2);
        deleteReq(auth, "/api/auth/me").andExpect(status().isNoContent());
        mvc.perform(get("/api/routines/shared/" + code)).andExpect(status().isNotFound());
    }
}
