package com.trex.server.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.trex.server.config.AppleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Sign in with Apple REST API 호출. client_secret 은 부를 때마다 .p8 키로 서명한 ES256 JWT(수명 5분) —
 * iss = Team ID, sub = client_id(번들 ID 또는 Android 웹 로그인 Services ID), aud = {@value #AUDIENCE}, 헤더 kid = Key ID.
 * 웹 로그인 code 는 처음 요청의 redirect_uri 를 같이 보내야 교환된다. refresh token·client_secret 은 로그에 남기지 않는다.
 */
@Component
public class AppleRestTokenClient implements AppleTokenClient {

    private static final Logger log = LoggerFactory.getLogger(AppleRestTokenClient.class);
    static final String AUDIENCE = "https://appleid.apple.com";
    static final String TOKEN_URI = "https://appleid.apple.com/auth/token";
    static final String REVOKE_URI = "https://appleid.apple.com/auth/revoke";

    private final AppleProperties properties;
    private final RestClient client;
    private final Clock clock;

    @Autowired
    public AppleRestTokenClient(AppleProperties properties, Clock clock) {
        this(properties, RestClient.builder().requestFactory(timeouts()), clock);
    }

    /** 테스트에서 가짜 Apple 서버(MockRestServiceServer)를 묶은 builder 를 넣는다. */
    AppleRestTokenClient(AppleProperties properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.client = builder.build();
        this.clock = clock;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        // 로그인·탈퇴 요청 스레드가 Apple 응답을 끝없이 기다리지 않게
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Override
    public boolean configured() {
        return properties.keysConfigured();
    }

    @Override
    public Optional<String> exchangeCode(String authorizationCode, String clientId) {
        if (!configured() || authorizationCode == null || authorizationCode.isBlank()) return Optional.empty();
        try {
            String issuedTo = client(clientId);
            MultiValueMap<String, String> form = form(issuedTo);
            form.add("code", authorizationCode);
            form.add("grant_type", "authorization_code");
            // 웹 로그인(Android)의 code 는 authorize 요청에 넣은 redirect_uri 를 같이 보낸다(Apple REST API: Token validation)
            if (issuedTo.equals(properties.webClientId())) form.add("redirect_uri", properties.webRedirectUri());
            Map<?, ?> body = client.post().uri(TOKEN_URI).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            Object refresh = body == null ? null : body.get("refresh_token");
            return refresh instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Apple code 교환 실패(로그인은 그대로): {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void revoke(String refreshToken, String clientId) {
        if (!configured() || refreshToken == null || refreshToken.isBlank()) return;
        try {
            MultiValueMap<String, String> form = form(client(clientId));
            form.add("token", refreshToken);
            form.add("token_type_hint", "refresh_token");
            client.post().uri(REVOKE_URI).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
            log.info("Apple 연결 끊기 완료");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Apple 연결 끊기 실패(탈퇴는 그대로): {}", e.getClass().getSimpleName());
        }
    }

    /** 토큰을 받은 client — 설정에 있는 값만 쓰고, 모르면(예전 회원) 기본 번들 ID. */
    private String client(String clientId) {
        return clientId != null && properties.audiences().contains(clientId) ? clientId : properties.clientId();
    }

    private MultiValueMap<String, String> form(String clientId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret(clientId));
        return form;
    }

    /** 기본 client(첫 번들 ID)의 client_secret. */
    String clientSecret() {
        return clientSecret(properties.clientId());
    }

    /** .p8 키로 서명한 client_secret(ES256, 수명 5분) — sub 는 부르는 client_id 와 같아야 한다. */
    String clientSecret(String clientId) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.teamId())
                .subject(clientId)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.keyId()).build(), claims);
        try {
            jwt.sign(new ECDSASigner(privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Apple client_secret 서명 실패", e);
        }
        return jwt.serialize();
    }

    private ECPrivateKey privateKey() {
        // .env 한 줄에 넣으려고 줄바꿈을 \n 두 글자로 적었으면 되돌린다
        String pem = properties.privateKey().replace("\\n", "\n");
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 키 내용은 로그에 남기지 않는다
            throw new IllegalStateException("APPLE_PRIVATE_KEY 를 읽지 못했다 — .p8 파일 내용 전체(BEGIN/END 줄 포함)를 넣는다", e);
        }
    }
}
