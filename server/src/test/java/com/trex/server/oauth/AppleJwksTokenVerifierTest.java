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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apple identity token 검증 규칙(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §3·§4).
 * Apple 공개키 대신 테스트 키쌍으로 서명해 같은 검증기를 돌린다 — 구글 검증기 테스트와 같은 방식.
 */
class AppleJwksTokenVerifierTest {

    private static final String BUNDLE_ID = "com.leedonghyun.trex.ios";
    private static final String RAW_NONCE = "raw-1";
    private static RSAPrivateKey privateKey;
    private static RSAPrivateKey otherKey;
    private static AppleJwksTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        otherKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        verifier = new AppleJwksTokenVerifier(List.of(BUNDLE_ID), () -> NimbusJwtDecoder.withPublicKey(publicKey).build());
    }

    private static String token(RSAPrivateKey key, Consumer<JWTClaimsSet.Builder> edit) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://appleid.apple.com")
                .audience(BUNDLE_ID)
                .subject("001234.abcdef.0987")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("email", "dino@icloud.com")
                .claim("email_verified", true)
                .claim("nonce", AppleJwksTokenVerifier.sha256Hex(RAW_NONCE));
        edit.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void 올바른_토큰은_apple_회원_정보를_준다() throws Exception {
        SocialIdentity id = verifier.verify(token(privateKey, c -> { }), RAW_NONCE);
        assertEquals(AuthProvider.APPLE, id.provider());
        assertEquals("001234.abcdef.0987", id.providerId());
        assertEquals("dino@icloud.com", id.email());
        assertTrue(id.emailVerified());
        assertNull(id.name()); // 이름은 토큰에 없다
    }

    @Test
    void email_verified_문자열도_확인된_이메일이다() throws Exception {
        // Apple 은 email_verified 를 문자열로 줄 때가 있고, "이메일 가리기"면 전달 주소다
        SocialIdentity id = verifier.verify(token(privateKey, c -> c
                .claim("email_verified", "true")
                .claim("email", "abc123@privaterelay.appleid.com")), RAW_NONCE);
        assertTrue(id.emailVerified());
        assertEquals("abc123@privaterelay.appleid.com", id.email());
    }

    @Test
    void 다른_키_서명_발급자_대상_만료는_거절한다() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        for (String bad : new String[] {
                token(otherKey, c -> { }),
                token(privateKey, c -> c.issuer("https://accounts.google.com")),
                token(privateKey, c -> c.audience("com.other.app")),
                token(privateKey, c -> c.issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(600)))),
        }) {
            assertThrows(InvalidCredentialsException.class, () -> verifier.verify(bad, RAW_NONCE));
        }
    }

    @Test
    void nonce_가_없거나_다르면_거절한다() throws Exception {
        // 다른 데서 받은 토큰을 다시 쓰지 못하게 — 앱이 보낸 원문의 SHA-256 이 토큰의 nonce 와 같아야 한다
        String noNonce = token(privateKey, c -> c.claim("nonce", null));
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify(noNonce, RAW_NONCE));
        String good = token(privateKey, c -> { });
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify(good, "raw-2"));
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify(good, ""));
        assertThrows(InvalidCredentialsException.class, () -> verifier.verify(good, null));
    }

    @Test
    void client_ids_가_비면_거절한다() throws Exception {
        AppleJwksTokenVerifier unconfigured = new AppleJwksTokenVerifier(List.of(" "), () -> { throw new AssertionError("디코더를 만들지 않는다"); });
        String good = token(privateKey, c -> { });
        assertThrows(InvalidCredentialsException.class, () -> unconfigured.verify(good, RAW_NONCE));
    }

    @Test
    void sha256_은_소문자_16진이다() {
        // iPhone(CryptoKit) 쪽과 같은 표기 — 다르면 모든 로그인이 nonce 불일치로 실패한다
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppleJwksTokenVerifier.sha256Hex("abc"));
    }
}
