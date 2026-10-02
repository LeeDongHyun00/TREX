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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 구글 ID 토큰을 구글 공개키(JWKS)로 직접 검증한다(docs/SERVER_SYNC_HANDOFF.md D8).
 *
 * <p>확인하는 것: 서명(구글 공개키), 만료·발급 시각, 발급자(accounts.google.com), 대상(aud)이 우리 클라이언트 ID 인지.
 * 대상 확인이 빠지면 **다른 앱용으로 발급된 구글 토큰**으로도 로그인이 된다.
 *
 * <p>클라이언트 ID 는 설정 {@code trex.oauth.google.client-ids}(쉼표로 여러 개). 안드로이드 Credential Manager 는
 * 앱 요청에 넣은 <b>웹 클라이언트 ID</b>(serverClientId)를 aud 로 발급하므로 그 값을 넣는다.
 * 공개키는 첫 로그인 때 받아 온다 — 서버가 켜질 때 구글에 접속하지 않는다.
 */
@Component
public class GoogleJwksTokenVerifier implements GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleJwksTokenVerifier.class);
    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final List<String> clientIds;
    private final Supplier<NimbusJwtDecoder> decoderFactory;
    private volatile JwtDecoder decoder;

    @Autowired
    public GoogleJwksTokenVerifier(@Value("${trex.oauth.google.client-ids:}") List<String> clientIds) {
        this(clientIds, () -> NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build());
    }

    /** 테스트에서 공개키를 직접 넣은 디코더로 같은 검증 규칙을 돌리기 위한 생성자. */
    GoogleJwksTokenVerifier(List<String> clientIds, Supplier<NimbusJwtDecoder> decoderFactory) {
        this.clientIds = clientIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.decoderFactory = decoderFactory;
    }

    static OAuth2TokenValidator<Jwt> validator(List<String> clientIds) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),   // 만료·발급 시각(허용 오차 60초)
                new JwtClaimValidator<Object>("iss", iss -> iss != null && ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.stream().anyMatch(clientIds::contains))
        );
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
    public SocialIdentity verify(String idToken) {
        if (clientIds.isEmpty()) {
            // 설정 누락을 "토큰이 틀렸다" 로 숨기지 않는다 — 운영 배포에서 가장 먼저 걸릴 실수다.
            log.error("trex.oauth.google.client-ids 가 비어 있어 구글 로그인을 받을 수 없다");
            throw new InvalidCredentialsException("구글 로그인이 서버에 설정되지 않았습니다");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            log.debug("구글 ID 토큰 검증 실패: {}", e.getMessage());
            throw new InvalidCredentialsException("구글 로그인 정보를 확인하지 못했습니다");
        }
        Object verified = jwt.getClaims().get("email_verified");
        return new SocialIdentity(
                AuthProvider.GOOGLE,
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified)),
                jwt.getClaimAsString("name")
        );
    }
}
