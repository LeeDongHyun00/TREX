package com.trex.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Apple 로그인 설정(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §4) — 값은 서버 .env 만.
 *
 * @param clientIds  앱 번들 ID(쉼표) — identity token 의 대상이자 code 교환의 client_id
 * @param teamId     동현님 Apple 개발자 계정의 Team ID
 * @param keyId      Sign in with Apple 키의 Key ID
 * @param privateKey 그 키의 .p8 내용(PEM) — .env 한 줄에 넣으려고 줄바꿈을 {@code \n} 두 글자로 적어도 된다. 비밀값
 */
@ConfigurationProperties("trex.oauth.apple")
public record AppleProperties(List<String> clientIds, String teamId, String keyId, String privateKey) {

    public AppleProperties {
        clientIds = clientIds == null ? List.of() : clientIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        teamId = teamId == null ? "" : teamId.trim();
        keyId = keyId == null ? "" : keyId.trim();
        privateKey = privateKey == null ? "" : privateKey.trim();
    }

    /** code 교환·연결 끊기를 할 수 있는가 — 키 셋이 다 있고 번들 ID 가 있다. 없으면 로그인만 된다. */
    public boolean keysConfigured() {
        return !clientIds.isEmpty() && !teamId.isEmpty() && !keyId.isEmpty() && !privateKey.isEmpty();
    }

    /** code 교환의 client_id — 첫 번들 ID. */
    public String clientId() {
        return clientIds.isEmpty() ? "" : clientIds.get(0);
    }
}
