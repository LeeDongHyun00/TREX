package com.trex.server.community;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커뮤니티 API 테스트 바탕. 테스트끼리 DB·시계를 공유하므로 사용자(sub)·닉네임은 매번 새로 만든다.
 * 가짜 구글 토큰 형식은 {@code SocialSyncApiTest} 와 같다: {@code ok|<sub>|<email>|<email_verified>|<name>}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CommunityTestConfig.class)
public abstract class CommunityApiTestSupport {

    protected static final String TERMS = "2026-10-28";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    protected static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 닉네임으로 쓸 만한 새 이름(10자). */
    protected static String nick() {
        return "n" + uniq().substring(0, 9);
    }

    protected String bearer(String sub) throws Exception {
        String body = mvc.perform(post("/api/auth/oauth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"ok|" + sub + "||false|테스트\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    /** 닉네임을 정하고 이용규칙에 동의한 사용자 — 글을 쓸 수 있다. */
    protected String ready(String sub) throws Exception {
        String auth = bearer(sub);
        putJson(auth, "/api/community/me/profile", "{\"nickname\":\"" + nick() + "\"}").andExpect(status().isOk());
        postJson(auth, "/api/community/me/terms", "{\"version\":\"" + TERMS + "\"}").andExpect(status().isNoContent());
        return auth;
    }

    protected String publicIdOf(String auth) throws Exception {
        return JsonPath.read(getJson(auth, "/api/community/me").andReturn().getResponse().getContentAsString(), "$.profile.publicId");
    }

    protected ResultActions getJson(String auth, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", auth));
    }

    protected ResultActions putJson(String auth, String url, String json) throws Exception {
        return mvc.perform(put(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions postJson(String auth, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions deleteReq(String auth, String url) throws Exception {
        return mvc.perform(delete(url).header("Authorization", auth));
    }

    protected ResultActions putReq(String auth, String url) throws Exception {
        return mvc.perform(put(url).header("Authorization", auth));
    }

    protected ResultActions upload(String auth, HttpMethod method, String url, String part, byte[] bytes) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart(method, url);
        req.file(new MockMultipartFile(part, "a.jpg", "image/jpeg", bytes));
        return mvc.perform(req.header("Authorization", auth));
    }

    /** 글 쓰기 — 본문이 null 이면 보내지 않는다. 같은 사람이 이어 쓰도록 시계를 2분 넘긴다(1분 1개 제한). */
    protected ResultActions createPost(String auth, String body, byte[]... images) throws Exception {
        clock.advance(Duration.ofMinutes(2));
        MockMultipartHttpServletRequestBuilder req = multipart("/api/community/posts");
        for (byte[] image : images) req.file(new MockMultipartFile("images", "a.jpg", "image/jpeg", image));
        if (body != null) req.param("body", body);
        return mvc.perform(req.header("Authorization", auth));
    }

    protected long createdPostId(String auth, String body, byte[]... images) throws Exception {
        String json = createPost(auth, body, images).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    protected static byte[] jpeg(int w, int h) {
        return image(w, h, "jpg");
    }

    protected static byte[] png(int w, int h) {
        return image(w, h, "png");
    }

    private static byte[] image(int w, int h, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
