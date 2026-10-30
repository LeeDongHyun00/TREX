package com.trex.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공유 링크 설정(docs/superpowers/specs/2026-10-29-routine-share-design.md §4).
 *
 * @param baseUrl           링크 앞부분 — {@code {baseUrl}/r/{code}}, {@code {baseUrl}/p/{id}}
 * @param androidInstallUrl 웹 페이지의 "앱 설치하기"(Android) — GitHub 최신 릴리스
 * @param iosInstallUrl     같은 버튼(iPhone) — App Store 주소. 비면 "App Store 출시 준비 중"
 */
@ConfigurationProperties("trex.share")
public record ShareProperties(String baseUrl, String androidInstallUrl, String iosInstallUrl) {

    public ShareProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://trex-api.duckdns.org" : baseUrl.trim().replaceAll("/+$", "");
        androidInstallUrl = androidInstallUrl == null || androidInstallUrl.isBlank()
                ? "https://github.com/LeeDongHyun00/TREX/releases/latest" : androidInstallUrl.trim();
        iosInstallUrl = iosInstallUrl == null ? "" : iosInstallUrl.trim();
    }
}
