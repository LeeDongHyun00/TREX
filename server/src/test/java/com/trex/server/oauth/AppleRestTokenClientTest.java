package com.trex.server.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.trex.server.config.AppleProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Apple 토큰 클라이언트 — 로그인 때 받은 authorization code 를 refresh token 으로 바꾸고, 탈퇴 때 연결을 끊는다
 * (설계 §3 연결 끊기, App Store 탈퇴 규칙). client_secret 은 동현님 계정의 .p8 키로 서명한 ES256 JWT 다.
 */
class AppleRestTokenClientTest {

    private static final String BUNDLE_ID = "com.leedonghyun.trex.ios";
    private static final String WEB_ID = "com.leedonghyun.trex.signin";
    private static final String REDIRECT = "https://trex-api.example/api/auth/oauth/apple/callback";
    private static final Instant NOW = Instant.parse("2026-10-29T05:00:00Z");
    private static KeyPair pair;
    private static String pem;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        pair = gen.generateKeyPair();
        pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private static AppleProperties props(String privateKey) {
        return new AppleProperties(List.of(BUNDLE_ID), "TEAM123456", "KEY1234567", privateKey, WEB_ID, REDIRECT, "");
    }

    private record Fixture(AppleRestTokenClient client, MockRestServiceServer server) { }

    private static Fixture fixture(AppleProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new AppleRestTokenClient(properties, builder, Clock.fixed(NOW, ZoneOffset.UTC)), server);
    }

    @Test
    void client_secret_은_ES256_으로_팀_키_번들_ID를_담는다() throws Exception {
        SignedJWT jwt = SignedJWT.parse(fixture(props(pem)).client().clientSecret());
        assertEquals(JWSAlgorithm.ES256, jwt.getHeader().getAlgorithm());
        assertEquals("KEY1234567", jwt.getHeader().getKeyID());
        assertTrue(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic())));
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals("TEAM123456", claims.getIssuer());
        assertEquals(BUNDLE_ID, claims.getSubject());
        assertEquals(List.of("https://appleid.apple.com"), claims.getAudience());
        assertEquals(NOW, claims.getIssueTime().toInstant());
        assertEquals(300, claims.getExpirationTime().toInstant().getEpochSecond() - claims.getIssueTime().toInstant().getEpochSecond());
    }

    @Test
    void code_를_refresh_token_으로_바꾼다() {
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("client_id", BUNDLE_ID, "code", "code-1", "grant_type", "authorization_code")))
                .andRespond(withSuccess("{\"access_token\":\"a\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"rt-1\",\"id_token\":\"x\"}",
                        MediaType.APPLICATION_JSON));
        assertEquals(Optional.of("rt-1"), f.client().exchangeCode("code-1", BUNDLE_ID));
        f.server().verify();
    }

    /** 보낸 폼 — 가짜 Apple 서버가 받은 본문을 읽는다. */
    private static Map<String, String> sentForm(org.springframework.http.client.ClientHttpRequest request) {
        String body = ((MockClientHttpRequest) request).getBodyAsString();
        return Arrays.stream(body.split("&")).map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8)));
    }

    @Test
    void iPhone_code_는_redirect_uri_없이_번들_ID_로_교환한다() {
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/token"))
                .andExpect(request -> {
                    Map<String, String> form = sentForm(request);
                    assertEquals(BUNDLE_ID, form.get("client_id"));
                    assertFalse(form.containsKey("redirect_uri"));
                    assertEquals(BUNDLE_ID, subjectOf(form.get("client_secret")));
                })
                .andRespond(withSuccess("{\"refresh_token\":\"rt-1\"}", MediaType.APPLICATION_JSON));
        assertEquals(Optional.of("rt-1"), f.client().exchangeCode("code-1", null)); // 모르면 기본 번들 ID
        f.server().verify();
    }

    @Test
    void Android_웹_로그인_code_는_Services_ID_와_redirect_uri_로_교환한다() {
        // Apple: 처음 authorize 요청에 redirect_uri 를 넣었으면 교환에도 같은 값을 넣는다. client_secret 의 sub 도 같은 client_id
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/token"))
                .andExpect(request -> {
                    Map<String, String> form = sentForm(request);
                    assertEquals(WEB_ID, form.get("client_id"));
                    assertEquals(REDIRECT, form.get("redirect_uri"));
                    assertEquals(WEB_ID, subjectOf(form.get("client_secret")));
                })
                .andRespond(withSuccess("{\"refresh_token\":\"rt-web\"}", MediaType.APPLICATION_JSON));
        assertEquals(Optional.of("rt-web"), f.client().exchangeCode("code-web", WEB_ID));
        f.server().verify();
    }

    @Test
    void 연결_끊기는_토큰을_받은_client_로_한다() {
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andExpect(content().formDataContains(Map.of("client_id", WEB_ID, "token", "rt-web")))
                .andRespond(withSuccess());
        f.client().revoke("rt-web", WEB_ID);
        f.server().verify();

        // 설정에 없는 client 는 믿지 않고 기본 번들 ID 로
        Fixture other = fixture(props(pem));
        other.server().expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andExpect(content().formDataContains(Map.of("client_id", BUNDLE_ID)))
                .andRespond(withSuccess());
        other.client().revoke("rt-1", "com.other.app");
        other.server().verify();
    }

    private static String subjectOf(String clientSecret) {
        try {
            return SignedJWT.parse(clientSecret).getJWTClaimsSet().getSubject();
        } catch (java.text.ParseException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void 교환이_실패하면_비어_있다() {
        // code 는 5분 안에 한 번만 쓸 수 있다 — 만료·재사용이면 Apple 이 400 invalid_grant
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));
        assertEquals(Optional.empty(), f.client().exchangeCode("old-code", BUNDLE_ID));
    }

    @Test
    void revoke_는_refresh_token_을_보낸다() {
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("client_id", BUNDLE_ID, "token", "rt-1", "token_type_hint", "refresh_token")))
                .andRespond(withSuccess());
        f.client().revoke("rt-1", BUNDLE_ID);
        f.server().verify();

        Fixture failing = fixture(props(pem));
        failing.server().expect(requestTo("https://appleid.apple.com/auth/revoke")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        failing.client().revoke("rt-1", BUNDLE_ID); // 예외 없이 로그만 — 탈퇴는 이어진다
    }

    @Test
    void 키가_없으면_부르지_않는다() {
        Fixture f = fixture(new AppleProperties(List.of(BUNDLE_ID), "", "", "", WEB_ID, REDIRECT, ""));
        assertFalse(f.client().configured());
        assertEquals(Optional.empty(), f.client().exchangeCode("code-1", BUNDLE_ID));
        f.client().revoke("rt-1", BUNDLE_ID);
        f.server().verify(); // 기대한 요청이 없다 = 한 번도 부르지 않았다
    }

    @Test
    void private_key_의_역슬래시_n_은_줄바꿈이다() throws Exception {
        // .env 한 줄에 넣으려고 줄바꿈을 \n 두 글자로 적은 PEM
        String oneLine = pem.replace("\n", "\\n");
        SignedJWT jwt = SignedJWT.parse(fixture(props(oneLine)).client().clientSecret());
        assertTrue(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic())));
    }
}
