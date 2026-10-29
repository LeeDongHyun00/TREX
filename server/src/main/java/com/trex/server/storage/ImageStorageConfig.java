package com.trex.server.storage;

import com.trex.server.config.CommunityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

import java.util.regex.Pattern;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 사진 저장소 고르기 — 버킷이 있으면 S3(운영), 없으면 메모리(테스트·로컬). 운영({@code require-s3})에서 버킷이 비면 뜨지 않는다. */
@Configuration
public class ImageStorageConfig {

    /** S3 범용 버킷 이름 — 소문자·숫자·점·하이픈 3~63자, 처음과 끝은 소문자나 숫자. */
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

    @Bean
    public ImageStorage imageStorage(CommunityProperties properties) {
        if (properties.s3Bucket().isEmpty()) {
            if (properties.requireS3()) {
                throw new IllegalStateException("COMMUNITY_S3_BUCKET 이 비어 있다 — 운영은 사진을 S3 에 둔다(docs/SERVER_SYNC_HANDOFF.md 9절)");
            }
            return new InMemoryImageStorage();
        }
        if (!BUCKET_NAME.matcher(properties.s3Bucket()).matches()) {
            // 사진을 올릴 때에야 "서버 오류"로 드러나지 않게 기동 때 까닭을 말하고 멈춘다(2026-10-29 운영: 안내 문구의 꺾쇠를 남겼다)
            throw new IllegalStateException("COMMUNITY_S3_BUCKET 이 버킷 이름 형식이 아니다 — 꺾쇠(<>)·s3://·arn 없이 소문자 이름만 넣는다");
        }
        Region region = Region.of(properties.s3Region());
        return new S3ImageStorage(S3Client.builder().region(region).build(), S3Presigner.builder().region(region).build(),
                properties.s3Bucket());
    }
}
