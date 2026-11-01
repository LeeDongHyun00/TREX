package com.trex.server.community;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 — 신고함·숨김·복구·삭제·정지(설계 §3 관리자·§5 신고·차단·관리, Review Focus 5). 관리자 loginId 는 테스트 설정의 google_admin-sub. */
class AdminApiTest extends CommunityApiTestSupport {

    private String admin() throws Exception {
        return ready("admin-sub");
    }

    private String report(String type, String targetId, String reason) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":\"" + targetId + "\",\"reason\":\"" + reason + "\"}";
    }

    private String resolve(String type, String targetId, String resolution) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":\"" + targetId + "\",\"resolution\":\"" + resolution + "\"}";
    }

    @Test
    void 관리자가_아니면_admin_API는_모두_403() throws Exception {
        String user = ready(uniq());
        long id = createdPostId(user, "글");
        getJson(user, "/api/community/admin/reports").andExpect(status().isForbidden());
        postJson(user, "/api/community/admin/posts/" + id + "/hide", "{}").andExpect(status().isForbidden());
        postJson(user, "/api/community/admin/users/" + publicIdOf(user) + "/suspend", "{}").andExpect(status().isForbidden());
        getJson(user, "/api/community/admin/hidden").andExpect(status().isForbidden());
        postJson(user, "/api/community/admin/reports/resolve", resolve("POST", String.valueOf(id), "KEPT")).andExpect(status().isForbidden());
    }

    @Test
    void 자동_숨김된_글을_KEPT로_처리하면_다시_피드에_보인다() throws Exception {
        String admin = admin();
        String author = ready(uniq());
        String viewer = ready(uniq());
        long id = createdPostId(author, "억울한 글");
        String firstReporter = null;
        for (int i = 0; i < 3; i++) {
            String reporter = ready(uniq());
            if (firstReporter == null) firstReporter = reporter;
            postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "SPAM")).andExpect(status().isCreated());
        }
        getJson(viewer, "/api/community/posts/" + id).andExpect(status().isNotFound());
        getJson(admin, "/api/community/admin/reports")
                .andExpect(jsonPath("$[?(@.targetId == '" + id + "')].reportCount", hasItem(3)))
                .andExpect(jsonPath("$[?(@.targetId == '" + id + "')].hidden", hasItem(true)))
                .andExpect(jsonPath("$[?(@.targetId == '" + id + "')].preview", hasItem("억울한 글")));

        postJson(admin, "/api/community/admin/reports/resolve", resolve("POST", String.valueOf(id), "KEPT")).andExpect(status().isNoContent());
        getJson(viewer, "/api/community/posts/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(false));
        getJson(admin, "/api/community/admin/reports").andExpect(jsonPath("$[*].targetId", not(hasItem(String.valueOf(id)))));
        getJson(firstReporter, "/api/community/me/reports")
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].resolution").value("KEPT"));
    }

    @Test
    void REMOVED로_처리하면_글이_지워진다() throws Exception {
        String admin = admin();
        String author = ready(uniq());
        String reporter = ready(uniq());
        long id = createdPostId(author, "지워질 글", jpeg(4, 4));
        postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "SEXUAL_VIOLENT")).andExpect(status().isCreated());
        postJson(admin, "/api/community/admin/reports/resolve", resolve("POST", String.valueOf(id), "REMOVED")).andExpect(status().isNoContent());
        getJson(author, "/api/community/posts/" + id).andExpect(status().isNotFound());
    }

    @Test
    void 정지된_사용자는_쓰기_403_읽기는_된다() throws Exception {
        String admin = admin();
        String bad = ready(uniq());
        String other = ready(uniq());
        long otherPost = createdPostId(other, "남의 글");
        postJson(admin, "/api/community/admin/users/" + publicIdOf(bad) + "/suspend", "{}").andExpect(status().isNoContent());

        getJson(bad, "/api/community/me").andExpect(jsonPath("$.suspended").value(true));
        createPost(bad, "정지 중 글").andExpect(status().isForbidden());
        postJson(bad, "/api/community/posts/" + otherPost + "/comments", "{\"body\":\"댓글\"}").andExpect(status().isForbidden());
        putReq(bad, "/api/community/posts/" + otherPost + "/like").andExpect(status().isForbidden());
        postJson(bad, "/api/community/reports", report("POST", String.valueOf(otherPost), "SPAM")).andExpect(status().isForbidden());
        getJson(bad, "/api/community/posts").andExpect(status().isOk());
        getJson(bad, "/api/community/posts/" + otherPost).andExpect(status().isOk());
    }

    @Test
    void 정지된_사용자의_글은_남에게_안_보이고_본인에게는_보인다() throws Exception {
        String admin = admin();
        String bad = ready(uniq());
        String viewer = ready(uniq());
        long id = createdPostId(bad, "정지 전에 쓴 글");
        String badId = publicIdOf(bad);
        postJson(admin, "/api/community/admin/users/" + badId + "/suspend", "{}").andExpect(status().isNoContent());

        getJson(viewer, "/api/community/posts/" + id).andExpect(status().isNotFound());
        getJson(viewer, "/api/community/posts").andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));
        getJson(viewer, "/api/community/users/" + badId).andExpect(jsonPath("$.postCount").value(0));
        getJson(bad, "/api/community/posts/" + id).andExpect(status().isOk());
        getJson(admin, "/api/community/admin/suspended").andExpect(jsonPath("$[*].publicId", hasItem(badId)));
    }

    @Test
    void 정지를_풀면_다시_쓸_수_있다() throws Exception {
        String admin = admin();
        String user = ready(uniq());
        String userId = publicIdOf(user);
        postJson(admin, "/api/community/admin/users/" + userId + "/suspend", "{}").andExpect(status().isNoContent());
        createPost(user, "막힘").andExpect(status().isForbidden());
        postJson(admin, "/api/community/admin/users/" + userId + "/unsuspend", "{}").andExpect(status().isNoContent());
        createPost(user, "다시 씀").andExpect(status().isCreated());
        getJson(admin, "/api/community/admin/suspended").andExpect(jsonPath("$[*].publicId", not(hasItem(userId))));
    }

    @Test
    void 관리자는_숨기고_복구하고_숨긴_글_목록을_본다() throws Exception {
        String admin = admin();
        String author = ready(uniq());
        long id = createdPostId(author, "관리자가 숨길 글");
        postJson(admin, "/api/community/admin/posts/" + id + "/hide", "{}").andExpect(status().isNoContent());
        getJson(author, "/api/community/posts/" + id).andExpect(jsonPath("$.hiddenReason").value("관리자"));
        getJson(admin, "/api/community/admin/hidden").andExpect(jsonPath("$[*].id", hasItem((int) id)));
        postJson(admin, "/api/community/admin/posts/" + id + "/restore", "{}").andExpect(status().isNoContent());
        getJson(admin, "/api/community/admin/hidden").andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
    }
}
