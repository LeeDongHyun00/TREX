package com.trex.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 커뮤니티 설정(docs/superpowers/specs/2026-10-28-community-design.md).
 *
 * @param s3Bucket         사진 버킷. 비면 메모리 저장소(테스트·로컬) — 운영 프로필은 비울 수 없다
 * @param s3Region         버킷 리전(기본 시드니)
 * @param adminLoginIds    관리자 loginId 목록(쉼표) — 앱 안 신고함과 숨김·삭제·정지 권한
 * @param reportWebhookUrl 새 신고를 알릴 디스코드 웹후크. 비면 알리지 않는다
 * @param requireS3        운영 — 버킷이 비면 서버를 띄우지 않는다(메모리 저장소로 조용히 돌면 재시작마다 사진이 사라진다)
 */
@ConfigurationProperties("trex.community")
public record CommunityProperties(String s3Bucket, String s3Region, List<String> adminLoginIds, String reportWebhookUrl,
                                  boolean requireS3) {

    public CommunityProperties {
        s3Bucket = s3Bucket == null ? "" : s3Bucket.trim();
        s3Region = s3Region == null || s3Region.isBlank() ? "ap-southeast-2" : s3Region.trim();
        adminLoginIds = adminLoginIds == null ? List.of()
                : adminLoginIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        reportWebhookUrl = reportWebhookUrl == null ? "" : reportWebhookUrl.trim();
    }
}
