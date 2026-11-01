package com.trex.server.community;

import com.trex.server.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 좋아요(사진 두 번 탭)·댓글(한 단계) — 설계 §2 목표 2·5, Review Focus 1. */
class LikeCommentApiTest extends CommunityApiTestSupport {

    @Autowired
    PostRepository postRepository;

    private String comment(String text) {
        return "{\"body\":\"" + text + "\"}";
    }

    private long commentId(String auth, long postId, String text) throws Exception {
        String json = postJson(auth, "/api/community/posts/" + postId + "/comments", comment(text))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void 좋아요_두_번_해도_204_수는_1_취소_두_번도_204() throws Exception {
        String owner = ready(uniq());
        String fan = ready(uniq());
        long id = createdPostId(owner, "좋아요 받을 글");
        putReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        putReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        getJson(fan, "/api/community/posts/" + id).andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByMe").value(true));
        deleteReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        deleteReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        getJson(fan, "/api/community/posts/" + id).andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.likedByMe").value(false));
    }

    @Test
    void 피드에_좋아요_수_내_좋아요_댓글_수가_담긴다() throws Exception {
        String owner = ready(uniq());
        String fan = ready(uniq());
        long id = createdPostId(owner, "피드 글");
        putReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        commentId(fan, id, "멋져요");
        commentId(fan, id, "저도 할래요");
        getJson(owner, "/api/community/posts").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")].likeCount", contains(1)))
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")].likedByMe", contains(false)))
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")].commentCount", contains(2)));
        getJson(fan, "/api/community/posts/" + id + "/comments").andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].body").value("멋져요"))
                .andExpect(jsonPath("$.items[1].mine").value(true));
    }

    @Test
    void 빈_댓글과_501자_댓글은_400() throws Exception {
        String auth = ready(uniq());
        long id = createdPostId(auth, "글");
        postJson(auth, "/api/community/posts/" + id + "/comments", comment("  ")).andExpect(status().isBadRequest());
        postJson(auth, "/api/community/posts/" + id + "/comments", comment("가".repeat(501))).andExpect(status().isBadRequest());
        postJson(auth, "/api/community/posts/" + id + "/comments", comment("가".repeat(500))).andExpect(status().isCreated());
    }

    @Test
    void 숨겨진_글에는_댓글을_못_쓴다_404() throws Exception {
        String owner = ready(uniq());
        String other = ready(uniq());
        long id = createdPostId(owner, "숨길 글");
        postRepository.findById(id).ifPresent(p -> {
            p.hide("테스트", clock.instant());
            postRepository.save(p);
        });
        postJson(other, "/api/community/posts/" + id + "/comments", comment("안녕")).andExpect(status().isNotFound());
        putReq(other, "/api/community/posts/" + id + "/like").andExpect(status().isNotFound());
    }

    @Test
    void 글쓴이는_내_글의_남_댓글을_지울_수_있고_제3자는_403() throws Exception {
        String owner = ready(uniq());
        String writer = ready(uniq());
        String stranger = ready(uniq());
        long id = createdPostId(owner, "글");
        long c1 = commentId(writer, id, "첫 댓글");
        long c2 = commentId(writer, id, "둘째 댓글");
        deleteReq(stranger, "/api/community/comments/" + c1).andExpect(status().isForbidden());
        deleteReq(owner, "/api/community/comments/" + c1).andExpect(status().isNoContent());
        deleteReq(writer, "/api/community/comments/" + c2).andExpect(status().isNoContent());
        getJson(owner, "/api/community/posts/" + id + "/comments").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void 댓글_1분에_6번째는_429() throws Exception {
        String auth = ready(uniq());
        long id = createdPostId(auth, "댓글 많은 글");
        for (int i = 0; i < 5; i++) commentId(auth, id, "댓글 " + i);
        postJson(auth, "/api/community/posts/" + id + "/comments", comment("여섯째")).andExpect(status().isTooManyRequests());
    }

    @Test
    void 댓글_달린_글도_지울_수_있다() throws Exception {
        String owner = ready(uniq());
        String fan = ready(uniq());
        long id = createdPostId(owner, "글");
        commentId(fan, id, "댓글");
        putReq(fan, "/api/community/posts/" + id + "/like").andExpect(status().isNoContent());
        deleteReq(owner, "/api/community/posts/" + id).andExpect(status().isNoContent());
        getJson(fan, "/api/community/posts/" + id).andExpect(status().isNotFound());
    }
}
