package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.repository.BlockRepository;
import com.trex.server.repository.CommunityProfileRepository;
import com.trex.server.storage.ImageStorage;
import com.trex.server.storage.InMemoryImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회원 탈퇴 때 커뮤니티 데이터·사진도 지운다 — App Store 5.1.1(v): 남에게 보인 글·사진까지(설계 §5 탈퇴). */
class CommunityDeletionTest extends CommunityApiTestSupport {

    @Autowired
    ImageStorage storage;

    @Autowired
    CommunityProfileRepository profileRepository;

    @Autowired
    BlockRepository blockRepository;

    private String report(String type, String targetId) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":\"" + targetId + "\",\"reason\":\"SPAM\"}";
    }

    @Test
    void 탈퇴하면_글_사진_댓글_좋아요_차단_신고가_모두_지워진다() throws Exception {
        String a = ready(uniq());
        String b = ready(uniq());
        String c = ready(uniq());
        String d = ready(uniq());
        String admin = ready("admin-sub");
        String aId = publicIdOf(a);
        CommunityProfile aProfile = profileRepository.findByPublicId(aId).orElseThrow();

        String created = createPost(a, "A의 사진 글", jpeg(4, 4)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long aPost = ((Number) JsonPath.read(created, "$.id")).longValue();
        String imageKey = ((String) JsonPath.read(created, "$.images[0].url")).substring("memory://".length());
        long bPost = createdPostId(b, "B의 글");
        long dPost = createdPostId(d, "D의 글");

        postJson(a, "/api/community/posts/" + bPost + "/comments", "{\"body\":\"A의 댓글\"}").andExpect(status().isCreated());
        putReq(a, "/api/community/posts/" + bPost + "/like").andExpect(status().isNoContent());
        putReq(a, "/api/community/blocks/" + publicIdOf(c)).andExpect(status().isNoContent());
        postJson(a, "/api/community/reports", report("POST", String.valueOf(dPost))).andExpect(status().isCreated());
        postJson(b, "/api/community/posts/" + aPost + "/comments", "{\"body\":\"B가 A 글에\"}").andExpect(status().isCreated());
        putReq(b, "/api/community/posts/" + aPost + "/like").andExpect(status().isNoContent());
        postJson(b, "/api/community/reports", report("USER", aId)).andExpect(status().isCreated());
        assertTrue(((InMemoryImageStorage) storage).has(imageKey));

        deleteReq(a, "/api/auth/me").andExpect(status().isNoContent());

        getJson(b, "/api/community/posts/" + aPost).andExpect(status().isNotFound());
        getJson(b, "/api/community/posts/" + bPost).andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.likeCount").value(0));
        getJson(b, "/api/community/users/" + aId).andExpect(status().isNotFound());
        getJson(admin, "/api/community/admin/reports")
                .andExpect(jsonPath("$[*].targetId", not(hasItem(String.valueOf(dPost)))))
                .andExpect(jsonPath("$[*].targetId", not(hasItem(aId))));
        assertFalse(profileRepository.findByPublicId(aId).isPresent());
        assertTrue(blockRepository.listByBlocker(aProfile.getId()).isEmpty());
        assertFalse(((InMemoryImageStorage) storage).has(imageKey), "사진도 저장소에서 지운다");
    }

    @Test
    void 커뮤니티를_안_쓴_사용자의_탈퇴는_그대로_된다() throws Exception {
        deleteReq(bearer(uniq()), "/api/auth/me").andExpect(status().isNoContent());
        String onlyNickname = bearer(uniq());
        putJson(onlyNickname, "/api/community/me/profile", "{\"nickname\":\"" + nick() + "\"}").andExpect(status().isOk());
        deleteReq(onlyNickname, "/api/auth/me").andExpect(status().isNoContent());
    }
}
