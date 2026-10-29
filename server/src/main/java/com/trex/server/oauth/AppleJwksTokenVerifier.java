package com.trex.server.oauth;

import com.trex.server.entity.AuthProvider;
import com.trex.server.exception.InvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Apple identity token 을 Apple 공개키(JWKS)로 직접 검증한다 — {@link GoogleJwksTokenVerifier} 와 같은 방식.
 *
 * <p>확인하는 것: 서명(Apple 공개키), 만료·발급 시각, 발급자({@value #ISSUER}), 대상(aud)이 우리 앱 번들 ID 인지,
 * 그리고 nonce — 앱이 만든 원문의 SHA-256(소문자 16진)이 토큰의 {@code nonce} 와 같아야 한다. 다른 데서 받은 토큰을
 * 다시 쓰는 것을 막는다.
 *
 * <p>번들 ID 는 설정 {@code trex.oauth.apple.client-ids}(쉼표로 여러 개). 공개키는 첫 로그인 때 받아 온다.
 */
@Component
public class AppleJwksTokenVerifier implements AppleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(AppleJwksTokenVerifier.class);
    static final String JWKS_URI = "https://appleid.apple.com/auth/keys";
    static final String ISSUER = "https://appleid.apple.com";

    private final List<String> clientIds;
    private final Supplier<NimbusJwtDecoder> decoderFactory;
    private volatile JwtDecoder decoder;

    @Autowired
    public AppleJwksTokenVerifier(@Value("${trex.oauth.apple.client-ids:}") List<String> clientIds) {
        this(clientIds, () -> NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build());
    }

    /** 테스트에서 공개키를 직접 넣은 디코더로 같은 검증 규칙을 돌리기 위한 생성자. */
    AppleJwksTokenVerifier(List<String> clientIds, Supplier<NimbusJwtDecoder> decoderFactory) {
        this.clientIds = clientIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.decoderFactory = decoderFactory;
    }

    static OAuth2TokenValidator<Jwt> validator(List<String> clientIds) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),   // 만료·발급 시각(허용 오차 60초)
                new JwtClaimValidator<Object>("iss", iss -> iss != null && ISSUER.equals(iss.toString())),
                new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.stream().anyMatch(clientIds::contains))
        );
    }

    /** nonce 해시 표기 — iPhone(CryptoKit SHA256)과 같은 소문자 16진. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder d = decoder;
        if (d == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder created = decoderFactory.get();
                    created.setJwtValidator(validator(clientIds));
                    decoder = created;
                }
                d = decoder;
            }
        }
        return d;
    }

    @Override
    public SocialIdentity verify(String identityToken, String rawNonce) {
        if (clientIds.isEmpty()) {
            // 설정 누락을 "토큰이 틀렸다" 로 숨기지 않는다
            log.error("trex.oauth.apple.client-ids 가 비어 있어 Apple 로그인을 받을 수 없다");
            throw new InvalidCredentialsException("Apple 로그인이 서버에 설정되지 않았습니다");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(identityToken);
        } catch (JwtException e) {
            log.debug("Apple identity token 검증 실패: {}", e.getMessage());
            throw new InvalidCredentialsException("Apple 로그인 정보를 확인하지 못했습니다");
        }
        String nonce = jwt.getClaimAsString("nonce");
        if (rawNonce == null || rawNonce.isBlank() || nonce == null || !nonce.equals(sha256Hex(rawNonce))) {
            log.debug("Apple identity token nonce 불일치");
            throw new InvalidCredentialsException("Apple 로그인 정보를 확인하지 못했습니다");
        }
        Object verified = jwt.getClaims().get("email_verified");
        return new SocialIdentity(
                AuthProvider.APPLE,
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified)),
                null   // Apple 은 이름을 토큰에 넣지 않는다(첫 로그인 때 앱에만 준다) — 커뮤니티는 닉네임을 쓴다
        );
    }
}
