package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 전체 리뷰(2026-10-29)의 Important 수정 — 각 테스트가 리뷰 지적 하나를 재현한다. */
class ReviewFixesApiTest extends CommunityApiTestSupport {

    private String admin() throws Exception {
        return ready("admin-sub");
    }

    private String report(String type, String targetId) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":\"" + targetId + "\",\"reason\":\"SPAM\"}";
    }

    /** 같은 요청을 동시에 [n]번 보내고 상태 코드들을 돌려준다. */
    private List<Integer> concurrently(int n, Callable<Integer> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) codes.add(f.get());
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Important 2: 보이지 않는 닉네임·사칭 닉네임

    @Test
    void 보이지_않는_닉네임은_400() throws Exception {
        String auth = bearer(uniq());
        for (String bad : new String[]{"\\u00A0\\u00A0", "\\u3164\\u3164", "\\u2800\\u2800", "공룡\\u00A0"}) {
            putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + bad + "\"}").andExpect(status().isBadRequest());
        }
    }

    @Test
    void 전각으로_흉내_낸_닉네임은_같은_이름으로_본다() throws Exception {
        String base = "Tx" + uniq().substring(0, 5);
        putJson(bearer(uniq()), "/api/community/me/profile", "{\"nickname\":\"" + base + "\"}").andExpect(status().isOk());
        StringBuilder fullWidth = new StringBuilder();
        for (char ch : base.toCharArray()) fullWidth.append((char) (ch + 0xFEE0));
        putJson(bearer(uniq()), "/api/community/me/profile", "{\"nickname\":\"" + fullWidth + "\"}").andExpect(status().isConflict());
    }

    // ---- Important 3: 동시 요청

    @Test
    void 좋아요_취소와_차단_해제를_동시에_보내도_모두_204() throws Exception {
        String owner = ready(uniq());
        String fan = ready(uniq());
        long id = createdPostId(owner, "동시 취소");
        putReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        List<Integer> unlikes = concurrently(8, () -> deleteReq(fan, "/api/community/posts/" + id + "/like").andReturn().getResponse().getStatus());
        assertEquals(List.of(204, 204, 204, 204, 204, 204, 204, 204), unlikes);

        String ownerId = publicIdOf(owner);
        List<Integer> blocks = concurrently(8, () -> putReq(fan, "/api/community/blocks/" + ownerId).andReturn().getResponse().getStatus());
        assertEquals(List.of(204, 204, 204, 204, 204, 204, 204, 204), blocks);
        List<Integer> unblocks = concurrently(8, () -> deleteReq(fan, "/api/community/blocks/" + ownerId).andReturn().getResponse().getStatus());
        assertEquals(List.of(204, 204, 204, 204, 204, 204, 204, 204), unblocks);
    }

    // ---- Important 4: 나를 차단한 사람의 글에는 댓글·좋아요를 못 한다, 댓글 수는 목록과 같다

    @Test
    void 나를_차단한_사람의_글에는_댓글과_좋아요를_못_한다() throws Exception {
        String owner = ready(uniq());
        String rude = ready(uniq());
        long id = createdPostId(owner, "내 글");
        putReq(owner, "/api/community/blocks/" + publicIdOf(rude)).andExpect(status().isNoContent());
        postJson(rude, "/api/community/posts/" + id + "/comments", "{\"body\":\"괴롭힘\"}").andExpect(status().isNotFound());
        putReq(rude, "/api/community/posts/" + id + "/like").andExpect(status().isNotFound());
    }

    @Test
    void 댓글_수는_보는_사람의_댓글_목록과_같다() throws Exception {
        String owner = ready(uniq());
        String other = ready(uniq());
        long id = createdPostId(owner, "글");
        postJson(other, "/api/community/posts/" + id + "/comments", "{\"body\":\"차단 전 댓글\"}").andExpect(status().isCreated());
        postJson(owner, "/api/community/posts/" + id + "/comments", "{\"body\":\"내 댓글\"}").andExpect(status().isCreated());
        putReq(owner, "/api/community/blocks/" + publicIdOf(other)).andExpect(status().isNoContent());
        getJson(owner, "/api/community/posts/" + id).andExpect(jsonPath("$.commentCount").value(1));
        getJson(owner, "/api/community/posts/" + id + "/comments").andExpect(jsonPath("$.items.length()").value(1));
    }

    // ---- Important 5: 정지된 사람의 프로필 사진·닉네임

    @Test
    void 정지되면_닉네임과_사진을_못_바꾸고_남에게는_사진이_안_보인다() throws Exception {
        String admin = admin();
        String bad = ready(uniq());
        String viewer = ready(uniq());
        upload(bad, HttpMethod.PUT, "/api/community/me/avatar", "image", jpeg(4, 4)).andExpect(status().isOk());
        String badId = publicIdOf(bad);
        postJson(admin, "/api/community/admin/users/" + badId + "/suspend", "{}").andExpect(status().isNoContent());

        putJson(bad, "/api/community/me/profile", "{\"nickname\":\"" + nick() + "\"}").andExpect(status().isForbidden());
        upload(bad, HttpMethod.PUT, "/api/community/me/avatar", "image", jpeg(4, 4)).andExpect(status().isForbidden());
        getJson(viewer, "/api/community/users/" + badId).andExpect(jsonPath("$.profile.avatarUrl").value(nullValue()));
    }

    @Test
    void 관리자는_프로필_사진을_지울_수_있다() throws Exception {
        String admin = admin();
        String user = ready(uniq());
        upload(user, HttpMethod.PUT, "/api/community/me/avatar", "image", jpeg(4, 4)).andExpect(status().isOk());
        String userId = publicIdOf(user);
        deleteReq(user, "/api/community/admin/users/" + userId + "/avatar").andExpect(status().isForbidden());
        deleteReq(admin, "/api/community/admin/users/" + userId + "/avatar").andExpect(status().isNoContent());
        getJson(user, "/api/community/me").andExpect(jsonPath("$.profile.avatarUrl").value(nullValue()));
    }

    // ---- Important 6: 신고 대상 id 정규화·신고 속도 제한

    @Test
    void 같은_글을_다른_표기로_두_번_신고해도_409() throws Exception {
        String author = ready(uniq());
        String reporter = ready(uniq());
        long id = createdPostId(author, "글");
        postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id))).andExpect(status().isCreated());
        postJson(reporter, "/api/community/reports", report("POST", "0" + id)).andExpect(status().isConflict());
        postJson(reporter, "/api/community/reports", report("POST", "+" + id)).andExpect(status().isConflict());
        String json = getJson(reporter, "/api/community/me/reports").andReturn().getResponse().getContentAsString();
        assertEquals(String.valueOf(id), JsonPath.read(json, "$[0].targetId"));
    }

    @Test
    void 신고는_1분에_10건까지() throws Exception {
        String reporter = ready(uniq());
        for (int i = 0; i < 10; i++) {
            String target = ready(uniq());
            postJson(reporter, "/api/community/reports", report("USER", publicIdOf(target))).andExpect(status().isCreated());
        }
        String eleventh = ready(uniq());
        postJson(reporter, "/api/community/reports", report("USER", publicIdOf(eleventh))).andExpect(status().isTooManyRequests());
    }

    // ---- 재등급 Important(Minor 7): 아주 큰 크기로 선언한 사진

    @Test
    void 한_변이_4096을_넘는_사진은_400() throws Exception {
        String auth = ready(uniq());
        createPost(auth, "너무 긴 사진", jpeg(4097, 1)).andExpect(status().isBadRequest());
        upload(auth, HttpMethod.PUT, "/api/community/me/avatar", "image", jpeg(1, 4097)).andExpect(status().isBadRequest());
        createPost(auth, "괜찮은 사진", jpeg(4096, 1)).andExpect(status().isCreated());
    }
}
