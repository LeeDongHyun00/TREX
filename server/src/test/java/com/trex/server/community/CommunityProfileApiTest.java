package com.trex.server.community;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 커뮤니티 프로필 — 닉네임·이용규칙 동의·프로필 사진·관리자 여부(설계 §3·§5 "처음 들어올 때"). */
class CommunityProfileApiTest extends CommunityApiTestSupport {

    @Test
    void 처음_me는_프로필_없음_admin_false() throws Exception {
        getJson(bearer(uniq()), "/api/community/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value(nullValue()))
                .andExpect(jsonPath("$.termsAccepted").value(false))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.suspended").value(false));
    }

    @Test
    void 닉네임을_정하면_publicId가_생기고_loginId는_노출되지_않는다() throws Exception {
        String auth = bearer(uniq());
        String nickname = nick();
        putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + nickname + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nickname))
                .andExpect(jsonPath("$.publicId").value(org.hamcrest.Matchers.hasLength(12)))
                .andExpect(content().string(not(containsString("google_"))));
        getJson(auth, "/api/community/me").andExpect(jsonPath("$.profile.nickname").value(nickname))
                .andExpect(content().string(not(containsString("google_"))));
    }

    @Test
    void 닉네임_대소문자_공백만_다른_중복은_409() throws Exception {
        String base = "Tx" + uniq().substring(0, 6);
        putJson(bearer(uniq()), "/api/community/me/profile", "{\"nickname\":\"" + base + "\"}").andExpect(status().isOk());
        putJson(bearer(uniq()), "/api/community/me/profile", "{\"nickname\":\" " + base.toLowerCase() + " \"}")
                .andExpect(status().isConflict());
    }

    @Test
    void 닉네임_규칙_위반은_400() throws Exception {
        String auth = bearer(uniq());
        for (String bad : new String[]{"a", "1234567890123", "   ", "공\\u200B룡", "씨발이"}) {
            putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + bad + "\"}").andExpect(status().isBadRequest());
        }
    }

    @Test
    void 내_닉네임은_다시_정할_수_있다() throws Exception {
        String auth = bearer(uniq());
        putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + nick() + "\"}").andExpect(status().isOk());
        String publicId = publicIdOf(auth);
        String renamed = nick();
        putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + renamed + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId))
                .andExpect(jsonPath("$.nickname").value(renamed));
    }

    @Test
    void 이용규칙_버전이_다르면_400_맞으면_me에_반영() throws Exception {
        String auth = bearer(uniq());
        postJson(auth, "/api/community/me/terms", "{\"version\":\"" + TERMS + "\"}").andExpect(status().isForbidden());
        putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + nick() + "\"}").andExpect(status().isOk());
        postJson(auth, "/api/community/me/terms", "{\"version\":\"2026-01-01\"}").andExpect(status().isBadRequest());
        postJson(auth, "/api/community/me/terms", "{\"version\":\"" + TERMS + "\"}").andExpect(status().isNoContent());
        getJson(auth, "/api/community/me").andExpect(jsonPath("$.termsAccepted").value(true));
    }

    @Test
    void 아바타_JPEG는_avatarUrl_PNG는_400() throws Exception {
        String auth = ready(uniq());
        upload(auth, HttpMethod.PUT, "/api/community/me/avatar", "image", jpeg(4, 4)).andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(startsWith("memory://avatars/")));
        upload(auth, HttpMethod.PUT, "/api/community/me/avatar", "image", png(4, 4)).andExpect(status().isBadRequest());
        deleteReq(auth, "/api/community/me/avatar").andExpect(status().isNoContent());
        getJson(auth, "/api/community/me").andExpect(jsonPath("$.profile.avatarUrl").value(nullValue()));
    }

    @Test
    void 관리자_목록의_loginId는_admin_true() throws Exception {
        getJson(bearer("admin-sub"), "/api/community/me").andExpect(jsonPath("$.admin").value(true));
    }
}
