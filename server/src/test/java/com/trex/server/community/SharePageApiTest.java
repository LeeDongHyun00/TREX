package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공유 링크 웹 페이지 — 로그인 없이 열린다. 게시글 페이지는 닉네임만, 숨긴 글·정지된 작성자·없는 글은 "볼 수 없는 글이에요". */
class SharePageApiTest extends CommunityApiTestSupport {

    private static final String ANDROID = "Mozilla/5.0 (Linux; Android 15; SM-F956N)";

    private String shareRoutine(String auth, String name) throws Exception {
        String ex = "{\"name\":\"플랭크\",\"reps\":\"3세트 x 40초\",\"duration\":\"3분\",\"posture\":false,\"category\":\"코어\"}";
        String body = postJson(auth, "/api/routines/share", "{\"name\":\"" + name + "\",\"totalSeconds\":300,\"exercises\":[" + ex + "]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    @Test
    void routineLinkServesHtml() throws Exception {
        String code = shareRoutine(ready(uniq()), "코어 루틴");
        mvc.perform(get("/r/" + code).header("User-Agent", ANDROID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("코어 루틴")))
                .andExpect(content().string(containsString("intent://r/" + code)));
    }

    @Test
    void unknownRoutineLinkIs404Page() throws Exception {
        mvc.perform(get("/r/AAAAAAAA"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("없어졌거나 잘못된 링크예요")));
    }

    @Test
    void postPageHidesHiddenAndSuspended() throws Exception {
        String writer = ready(uniq());
        String nickname = JsonPath.read(getJson(writer, "/api/community/me").andReturn().getResponse().getContentAsString(), "$.profile.nickname");
        long visible = createdPostId(writer, "비밀 본문 글자");
        mvc.perform(get("/p/" + visible))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(nickname + "님의 T-REX 커뮤니티 글")))
                .andExpect(content().string(not(containsString("비밀 본문 글자"))));

        String admin = ready("admin-sub");
        postJson(admin, "/api/community/admin/posts/" + visible + "/hide", "{}");
        mvc.perform(get("/p/" + visible))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("볼 수 없는 글이에요")))
                .andExpect(content().string(not(containsString(nickname))));

        String other = ready(uniq());
        long byOther = createdPostId(other, "다른 글");
        postJson(admin, "/api/community/admin/users/" + publicIdOf(other) + "/suspend", "{}");
        mvc.perform(get("/p/" + byOther)).andExpect(status().isNotFound());
        mvc.perform(get("/p/999999999")).andExpect(status().isNotFound());
    }

    @Test
    void ogImageIsPublic() throws Exception {
        mvc.perform(get("/share/og.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }
}
