package com.trex.server.community;

import com.trex.server.storage.ImageStorage;
import com.trex.server.storage.InMemoryImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 저장소(S3)가 사진을 못 지워도 글 삭제·탈퇴는 성공한다 — 고아 사진은 로그만(설계 Review Focus 2). */
@Import(PostDeleteStorageFailureTest.FailingDeletes.class)
class PostDeleteStorageFailureTest extends CommunityApiTestSupport {

    @TestConfiguration
    static class FailingDeletes {
        @Bean
        @Primary
        ImageStorage failingDeleteStorage() {
            return new InMemoryImageStorage() {
                @Override
                public void delete(String key) {
                    throw new IllegalStateException("S3 삭제 실패(테스트)");
                }
            };
        }
    }

    @Test
    void 글_삭제_시_사진_삭제가_실패해도_204() throws Exception {
        String auth = ready(uniq());
        long id = createdPostId(auth, "사진 글", jpeg(4, 4));
        deleteReq(auth, "/api/community/posts/" + id).andExpect(status().isNoContent());
        getJson(auth, "/api/community/posts/" + id).andExpect(status().isNotFound());
    }

    @Test
    void 사진_삭제가_실패해도_탈퇴는_성공한다() throws Exception {
        String auth = ready(uniq());
        createdPostId(auth, "사진 글", jpeg(4, 4));
        upload(auth, org.springframework.http.HttpMethod.PUT, "/api/community/me/avatar", "image", jpeg(4, 4)).andExpect(status().isOk());
        deleteReq(auth, "/api/auth/me").andExpect(status().isNoContent());
    }
}
