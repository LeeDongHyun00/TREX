package com.trex.server.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.trex.server.entity.AuthProvider;
import com.trex.server.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 구글 ID 토큰 검증 규칙(D8). 구글 공개키 대신 테스트 키쌍으로 서명해 같은 검증기를 돌린다 —
 * 운영과 다른 것은 공개키를 어디서 받느냐뿐이다.
 */
class GoogleJwksTokenVerifierTest {

    private static final String CLIENT_ID = "web-client.apps.googleusercontent.com";
    private static RSAPrivateKey privateKey;
    private static RSAPrivateKey otherKey;
    private static GoogleJwksTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        otherKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        verifier = new GoogleJwksTokenVerifier(List.of(CLIENT_ID),
                () -> NimbusJwtDecoder.withPublicKey(publicKey).build());
    }

    private static String token(RSAPrivateKey key, Consumer<JWTClaimsSet.Builder> edit) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .subject("1234567890")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", "dino@gmail.com")
                .claim("email_verified", true)
                .claim("name", "공룡");
        edit.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void 올바른_토큰은_구글_계정_정보로_바뀐다() throws Exception {
        SocialIdentity id = verifier.verify(token(privateKey, c -> { }));
        assertEquals(AuthProvider.GOOGLE, id.provider());
        assertEquals("1234567890", id.providerId());
        assertEquals("dino@gmail.com", id.email());
        assertTrue(id.emailVerified());
        assertEquals("공룡", id.name());
    }

    @Test
    void 발급자가_스킴_없는_형식이어도_받는다() throws Exception {
        assertEquals("1234567890", verifier.verify(token(privateKey, c -> c.issuer("accounts.google.com"))).providerId());
    }

    @Test
    void 이메일_확인_안_됨() throws Exception {
        assertFalse(verifier.verify(token(privateKey, c -> c.claim("email_verified", false))).emailVerified());
    }

    @Test
    void 다른_앱용_토큰은_거절한다() {
        assertThrows(InvalidCredentialsException.class,
                () -> verifier.verify(token(privateKey, c -> c.audience("someone-else.apps.googleusercontent.com"))));
    }

    @Test
    void 다른_발급자는_거절한다() {
        assertThrows(InvalidCredentialsException.class,
                () -> verifier.verify(token(privateKey, c -> c.issuer("https://evil.example.com"))));
    }

    @Test
    void 만료된_토큰은_거절한다() {
        Instant past = Instant.now().minusSeconds(7200);
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify(token(privateKey,
                c -> c.issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(3600))))));
    }

    @Test
    void 다른_키로_서명한_토큰과_형식이_깨진_토큰은_거절한다() {
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify(token(otherKey, c -> { })));
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify("not-a-jwt"));
    }

    @Test
    void 클라이언트_ID_설정이_비면_어떤_토큰도_받지_않는다() throws Exception {
        GoogleJwksTokenVerifier unconfigured = new GoogleJwksTokenVerifier(List.of(" "),
                () -> { throw new AssertionError("설정이 없으면 공개키를 받으러 가지도 않는다"); });
        String good = token(privateKey, c -> { });
        assertThrows(InvalidCredentialsException.class, () -> unconfigured.verify(good));
    }
}
