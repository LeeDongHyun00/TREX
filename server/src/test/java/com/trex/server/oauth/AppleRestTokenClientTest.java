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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return new AppleProperties(List.of(BUNDLE_ID), "TEAM123456", "KEY1234567", privateKey);
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
        assertEquals(Optional.of("rt-1"), f.client().exchangeCode("code-1"));
        f.server().verify();
    }

    @Test
    void 교환이_실패하면_비어_있다() {
        // code 는 5분 안에 한 번만 쓸 수 있다 — 만료·재사용이면 Apple 이 400 invalid_grant
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));
        assertEquals(Optional.empty(), f.client().exchangeCode("old-code"));
    }

    @Test
    void revoke_는_refresh_token_을_보낸다() {
        Fixture f = fixture(props(pem));
        f.server().expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("client_id", BUNDLE_ID, "token", "rt-1", "token_type_hint", "refresh_token")))
                .andRespond(withSuccess());
        f.client().revoke("rt-1");
        f.server().verify();

        Fixture failing = fixture(props(pem));
        failing.server().expect(requestTo("https://appleid.apple.com/auth/revoke")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        failing.client().revoke("rt-1"); // 예외 없이 로그만 — 탈퇴는 이어진다
    }

    @Test
    void 키가_없으면_부르지_않는다() {
        Fixture f = fixture(new AppleProperties(List.of(BUNDLE_ID), "", "", ""));
        assertFalse(f.client().configured());
        assertEquals(Optional.empty(), f.client().exchangeCode("code-1"));
        f.client().revoke("rt-1");
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
