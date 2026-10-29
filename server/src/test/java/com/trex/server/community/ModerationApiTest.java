package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportTargetType;
import com.trex.server.service.DiscordReportNotifier;
import com.trex.server.service.ReportNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 차단·신고·자동 숨김·신고 알림 — App Store 1.2·Play UGC 필수(설계 §4·§5 신고·차단·관리). */
@Import(ModerationApiTest.Recording.class)
class ModerationApiTest extends CommunityApiTestSupport {

    record Filed(ReportTargetType type, String targetId, ReportReason reason, long count, boolean autoHidden) {
    }

    @TestConfiguration
    static class Recording {
        @Bean
        RecordingNotifier recordingNotifier() {
            return new RecordingNotifier();
        }

        @Bean
        @Primary
        ReportNotifier reportNotifier(RecordingNotifier recording) {
            return recording;
        }
    }

    static class RecordingNotifier implements ReportNotifier {
        final List<Filed> filed = new CopyOnWriteArrayList<>();

        @Override
        public void reportFiled(ReportTargetType type, String targetId, ReportReason reason, long reportCount, boolean autoHidden) {
            filed.add(new Filed(type, targetId, reason, reportCount, autoHidden));
        }
    }

    @Autowired
    RecordingNotifier notifier;

    private String report(String type, String targetId, String reason) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":\"" + targetId + "\",\"reason\":\"" + reason + "\"}";
    }

    private long commentId(String auth, long postId, String text) throws Exception {
        String json = postJson(auth, "/api/community/posts/" + postId + "/comments", "{\"body\":\"" + text + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void 차단하면_피드_댓글_프로필_글에서_사라지고_풀면_돌아온다() throws Exception {
        String me = ready(uniq());
        String rude = ready(uniq());
        String other = ready(uniq());
        String rudeId = publicIdOf(rude);
        long rudePost = createdPostId(rude, "차단될 사람의 글");
        long otherPost = createdPostId(other, "다른 사람 글");
        long rudeComment = commentId(rude, otherPost, "차단될 사람의 댓글");

        putReq(me, "/api/community/blocks/" + rudeId).andExpect(status().isNoContent());
        putReq(me, "/api/community/blocks/" + rudeId).andExpect(status().isNoContent());
        getJson(me, "/api/community/posts").andExpect(jsonPath("$.items[*].id", not(hasItem((int) rudePost))));
        getJson(me, "/api/community/posts/" + rudePost).andExpect(status().isNotFound());
        getJson(me, "/api/community/posts/" + otherPost + "/comments")
                .andExpect(jsonPath("$.items[*].id", not(hasItem((int) rudeComment))));
        getJson(me, "/api/community/users/" + rudeId).andExpect(jsonPath("$.blockedByMe").value(true));
        getJson(me, "/api/community/users/" + rudeId + "/posts").andExpect(jsonPath("$.items", empty()));
        getJson(me, "/api/community/me/blocks").andExpect(jsonPath("$[*].publicId", contains(rudeId)));

        deleteReq(me, "/api/community/blocks/" + rudeId).andExpect(status().isNoContent());
        getJson(me, "/api/community/posts/" + rudePost).andExpect(status().isOk());
        getJson(me, "/api/community/posts/" + otherPost + "/comments").andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void 자기_자신_차단과_내_글_신고는_400() throws Exception {
        String me = ready(uniq());
        putReq(me, "/api/community/blocks/" + publicIdOf(me)).andExpect(status().isBadRequest());
        long mine = createdPostId(me, "내 글");
        postJson(me, "/api/community/reports", report("POST", String.valueOf(mine), "SPAM")).andExpect(status().isBadRequest());
        postJson(me, "/api/community/reports", report("USER", publicIdOf(me), "SPAM")).andExpect(status().isBadRequest());
        postJson(me, "/api/community/reports", report("POST", "999999999", "SPAM")).andExpect(status().isNotFound());
        postJson(me, "/api/community/reports", report("POST", "숫자아님", "SPAM")).andExpect(status().isNotFound());
        postJson(me, "/api/community/reports", report("POST", String.valueOf(mine), "없는사유")).andExpect(status().isBadRequest());
    }

    @Test
    void 같은_대상을_두_번_신고하면_409() throws Exception {
        String author = ready(uniq());
        String reporter = ready(uniq());
        long id = createdPostId(author, "글");
        postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "SPAM")).andExpect(status().isCreated());
        postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "ABUSE")).andExpect(status().isConflict());
    }

    @Test
    void 서로_다른_세_명이_신고하면_자동_숨김_작성자에게만_hidden_true와_사유가_보인다() throws Exception {
        String author = ready(uniq());
        long id = createdPostId(author, "신고될 글");
        long cid = commentId(author, id, "신고될 댓글");
        String viewer = ready(uniq());
        for (int i = 0; i < 3; i++) {
            String reporter = ready(uniq());
            postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "ABUSE")).andExpect(status().isCreated());
            postJson(reporter, "/api/community/reports", report("COMMENT", String.valueOf(cid), "ABUSE")).andExpect(status().isCreated());
        }
        getJson(author, "/api/community/posts/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.hiddenReason").value("신고 누적"));
        getJson(viewer, "/api/community/posts/" + id).andExpect(status().isNotFound());
        getJson(viewer, "/api/community/posts").andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));
        Filed last = notifier.filed.stream().filter(f -> f.targetId().equals(String.valueOf(id))).reduce((a, b) -> b).orElseThrow();
        assertEquals(3, last.count());
        assertTrue(last.autoHidden());
        assertTrue(notifier.filed.stream().anyMatch(f -> f.type() == ReportTargetType.COMMENT && f.targetId().equals(String.valueOf(cid)) && f.autoHidden()));
    }

    @Test
    void 신고하면_알림이_가고_메시지에_loginId와_이름이_없다() throws Exception {
        String author = ready(uniq());
        String reporter = ready(uniq());
        long id = createdPostId(author, "알림 글");
        postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "ABUSE")).andExpect(status().isCreated());
        assertTrue(notifier.filed.contains(new Filed(ReportTargetType.POST, String.valueOf(id), ReportReason.ABUSE, 1, false)));

        String message = DiscordReportNotifier.message(ReportTargetType.POST, String.valueOf(id), ReportReason.ABUSE, 3, true);
        assertTrue(message.contains("글 #" + id) && message.contains("욕설·혐오") && message.contains("3건") && message.contains("자동 숨김"), message);
        assertFalse(message.contains("google_") || message.contains("테스트"), message);
    }

    @Test
    void 내_신고_목록은_PENDING으로_보인다() throws Exception {
        String author = ready(uniq());
        String reporter = ready(uniq());
        long id = createdPostId(author, "글");
        postJson(reporter, "/api/community/reports", report("USER", publicIdOf(author), "SPAM")).andExpect(status().isCreated());
        postJson(reporter, "/api/community/reports", report("POST", String.valueOf(id), "OTHER")).andExpect(status().isCreated());
        getJson(reporter, "/api/community/me/reports").andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].targetType").value("POST"))
                .andExpect(jsonPath("$[1].targetType").value("USER"));
    }
}
