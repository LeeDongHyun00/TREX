package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 글 쓰기·사진·피드·상세·삭제·사용자 프로필(설계 §2 목표 2·3·6, §5 피드·글쓰기). */
class PostApiTest extends CommunityApiTestSupport {

    /** 시계를 넘기지 않고 바로 쓴다 — 속도 제한 확인용. */
    private ResultActions rawPost(String auth, String body) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/community/posts");
        req.param("body", body);
        return mvc.perform(req.header("Authorization", auth));
    }

    private List<Long> ids(ResultActions page) throws Exception {
        List<Number> raw = JsonPath.read(page.andReturn().getResponse().getContentAsString(), "$.items[*].id");
        return raw.stream().map(Number::longValue).toList();
    }

    private Long nextBefore(ResultActions page) throws Exception {
        Number n = JsonPath.read(page.andReturn().getResponse().getContentAsString(), "$.nextBefore");
        return n == null ? null : n.longValue();
    }

    @Test
    void 프로필_없거나_이용규칙_미동의면_글쓰기_403() throws Exception {
        String auth = bearer(uniq());
        createPost(auth, "안녕").andExpect(status().isForbidden());
        putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + nick() + "\"}").andExpect(status().isOk());
        createPost(auth, "안녕").andExpect(status().isForbidden());
        getJson(auth, "/api/community/posts").andExpect(status().isForbidden());
    }

    @Test
    void 글만_사진만_올리기는_201_둘_다_없으면_400() throws Exception {
        String auth = ready(uniq());
        createPost(auth, "오늘 오운완!").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("오늘 오운완!"))
                .andExpect(jsonPath("$.images", hasSize(0)))
                .andExpect(jsonPath("$.mine").value(true));
        createPost(auth, null, jpeg(4, 4)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.images", hasSize(1)));
        createPost(auth, null).andExpect(status().isBadRequest());
        createPost(auth, "   ").andExpect(status().isBadRequest());
        createPost(auth, "가".repeat(2001)).andExpect(status().isBadRequest());
        createPost(auth, "너 바 보 아니고 씨발").andExpect(status().isBadRequest());
    }

    @Test
    void 사진_6장은_400_PNG는_400_2MB_넘으면_413() throws Exception {
        String auth = ready(uniq());
        byte[] one = jpeg(4, 4);
        createPost(auth, "여섯 장", one, one, one, one, one, one).andExpect(status().isBadRequest());
        createPost(auth, "png", png(4, 4)).andExpect(status().isBadRequest());
        byte[] big = Arrays.copyOf(one, 2 * 1024 * 1024 + 1);
        createPost(auth, "큰 사진", big).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void 피드는_최신순_20개와_nextBefore() throws Exception {
        String auth = ready(uniq());
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 22; i++) mine.add(createdPostId(auth, "글 " + i));
        List<Long> newestFirst = new ArrayList<>(mine);
        Collections.reverse(newestFirst);

        ResultActions first = getJson(auth, "/api/community/posts").andExpect(status().isOk());
        List<Long> page1 = ids(first);
        assertEquals(newestFirst.subList(0, 20), page1);
        assertEquals(page1.get(19), nextBefore(first));

        ResultActions second = getJson(auth, "/api/community/posts?before=" + nextBefore(first)).andExpect(status().isOk());
        assertEquals(newestFirst.subList(20, 22), ids(second).subList(0, 2));

        String publicId = publicIdOf(auth);
        getJson(auth, "/api/community/users/" + publicId).andExpect(jsonPath("$.postCount").value(22))
                .andExpect(jsonPath("$.blockedByMe").value(false));
        ResultActions userPage1 = getJson(auth, "/api/community/users/" + publicId + "/posts");
        ResultActions userPage2 = getJson(auth, "/api/community/users/" + publicId + "/posts?before=" + nextBefore(userPage1));
        assertEquals(newestFirst.subList(20, 22), ids(userPage2));
        assertEquals(null, nextBefore(userPage2));
    }

    @Test
    void 피드_페이지_사이에_글이_지워져도_중복_누락_없음() throws Exception {
        String auth = ready(uniq());
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 25; i++) mine.add(createdPostId(auth, "글 " + i));
        String base = "/api/community/users/" + publicIdOf(auth) + "/posts";

        ResultActions first = getJson(auth, base);
        List<Long> page1 = ids(first);
        long deletedFromPage1 = page1.get(5);
        long deletedFromPage2 = mine.get(1);
        deleteReq(auth, "/api/community/posts/" + deletedFromPage1).andExpect(status().isNoContent());
        deleteReq(auth, "/api/community/posts/" + deletedFromPage2).andExpect(status().isNoContent());
        List<Long> page2 = ids(getJson(auth, base + "?before=" + nextBefore(first)));

        List<Long> seen = new ArrayList<>(page1);
        seen.addAll(page2);
        Set<Long> unique = new HashSet<>(seen);
        assertEquals(seen.size(), unique.size(), "중복 없음");
        Set<Long> expected = new HashSet<>(mine);
        expected.remove(deletedFromPage2);
        assertEquals(expected, unique, "남은 글은 정확히 한 번씩(1쪽에서 받은 뒤 지운 글은 1쪽에 남아 있다)");
    }

    @Test
    void 일분_안에_네_번째_글은_429_24시간_31번째도_429() throws Exception {
        // 1분 3개(사용자 결정 2026-10-29 — 사진 글 뒤 바로 오운완을 올리면 1분 1개에 막혔다)
        String auth = ready(uniq());
        createPost(auth, "첫 글").andExpect(status().isCreated());
        rawPost(auth, "바로 둘째").andExpect(status().isCreated());
        rawPost(auth, "바로 셋째").andExpect(status().isCreated());
        rawPost(auth, "바로 넷째").andExpect(status().isTooManyRequests());

        String busy = ready(uniq());
        for (int i = 0; i < 30; i++) createPost(busy, "글 " + i).andExpect(status().isCreated());
        createPost(busy, "31번째").andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofHours(24));
        createPost(busy, "하루 뒤").andExpect(status().isCreated());
    }

    @Test
    void 남의_글_삭제는_403_내_글은_204_그_뒤_404() throws Exception {
        String owner = ready(uniq());
        String other = ready(uniq());
        long id = createdPostId(owner, "내 글", jpeg(4, 4));
        getJson(other, "/api/community/posts/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.mine").value(false));
        deleteReq(other, "/api/community/posts/" + id).andExpect(status().isForbidden());
        deleteReq(owner, "/api/community/posts/" + id).andExpect(status().isNoContent());
        getJson(other, "/api/community/posts/" + id).andExpect(status().isNotFound());
        getJson(other, "/api/community/posts/999999999").andExpect(status().isNotFound());
    }

    @Test
    void 사진_주소와_크기가_응답에_담긴다() throws Exception {
        String auth = ready(uniq());
        createPost(auth, "사진", jpeg(5, 3)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.images[0].url").value(startsWith("memory://posts/")))
                .andExpect(jsonPath("$.images[0].width").value(5))
                .andExpect(jsonPath("$.images[0].height").value(3))
                .andExpect(jsonPath("$.author.publicId").value(publicIdOf(auth)));
    }

    @Test
    void 사진은_올린_순서대로_돌아온다() throws Exception {
        String auth = ready(uniq());
        clock.advance(Duration.ofMinutes(2));
        MockMultipartHttpServletRequestBuilder req = multipart("/api/community/posts");
        req.file(new MockMultipartFile("images", "a.jpg", "image/jpeg", jpeg(1, 1)));
        req.file(new MockMultipartFile("images", "b.jpg", "image/jpeg", jpeg(2, 2)));
        req.file(new MockMultipartFile("images", "c.jpg", "image/jpeg", jpeg(3, 3)));
        String json = mvc.perform(req.header("Authorization", auth)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        List<Integer> widths = JsonPath.read(json, "$.images[*].width");
        assertEquals(List.of(1, 2, 3), widths);
        List<String> urls = JsonPath.read(json, "$.images[*].url");
        assertTrue(urls.get(0).contains("/0-") && urls.get(2).contains("/2-"));
    }
}
