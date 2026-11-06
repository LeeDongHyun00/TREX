package com.trex.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple 로그인 설정(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §4·§9) — 값은 서버 .env 만.
 *
 * @param clientIds      앱 번들 ID(쉼표) — iPhone identity token 의 대상이자 그 code 교환의 client_id
 * @param teamId         동현님 Apple 개발자 계정의 Team ID
 * @param keyId          Sign in with Apple 키의 Key ID
 * @param privateKey     그 키의 .p8 내용(PEM) — .env 한 줄에 넣으려고 줄바꿈을 {@code \n} 두 글자로 적어도 된다. 비밀값
 * @param webClientId    Android 웹 로그인의 Services ID — 그 토큰의 대상이자 code 교환의 client_id. 비면 Android 로그인 토큰을 받지 않는다
 * @param webRedirectUri 그 Services ID 의 Return URL(= 이 서버의 /api/auth/oauth/apple/callback) — 웹 로그인 code 교환에 같이 보낸다.
 *                       앱 {@code trex.appleRedirectUri} 와 글자까지 같아야 한다
 * @param androidPackage 콜백이 되돌려 보낼 Android 앱 패키지(기본 com.example.trex_kotlin)
 */
@ConfigurationProperties("trex.oauth.apple")
public record AppleProperties(List<String> clientIds, String teamId, String keyId, String privateKey,
                              String webClientId, String webRedirectUri, String androidPackage) {

    static final String DEFAULT_ANDROID_PACKAGE = "com.example.trex_kotlin";

    public AppleProperties {
        clientIds = clientIds == null ? List.of() : clientIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        teamId = teamId == null ? "" : teamId.trim();
        keyId = keyId == null ? "" : keyId.trim();
        privateKey = privateKey == null ? "" : privateKey.trim();
        webClientId = webClientId == null ? "" : webClientId.trim();
        webRedirectUri = webRedirectUri == null ? "" : webRedirectUri.trim();
        androidPackage = androidPackage == null || androidPackage.isBlank() ? DEFAULT_ANDROID_PACKAGE : androidPackage.trim();
    }

    /** code 교환·연결 끊기를 할 수 있는가 — 키 셋이 다 있고 번들 ID 가 있다. 없으면 로그인만 된다. */
    public boolean keysConfigured() {
        return !clientIds.isEmpty() && !teamId.isEmpty() && !keyId.isEmpty() && !privateKey.isEmpty();
    }

    /** 기본 client_id — 첫 번들 ID. 어느 client 로 받았는지 모르는 예전 refresh token 도 이 값으로 끊는다. */
    public String clientId() {
        return clientIds.isEmpty() ? "" : clientIds.get(0);
    }

    /** identity token 의 대상으로 받아 주는 값 — 번들 ID 들과 Android 웹 로그인 Services ID. */
    public List<String> audiences() {
        List<String> all = new ArrayList<>(clientIds);
        if (!webClientId.isEmpty() && !all.contains(webClientId)) all.add(webClientId);
        return List.copyOf(all);
    }
}
