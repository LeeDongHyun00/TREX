package com.trex.server.storage;

import com.trex.server.config.CommunityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 사진 저장소 고르기 — 버킷이 있으면 S3(운영), 없으면 메모리(테스트·로컬). 운영({@code require-s3})에서 버킷이 비면 뜨지 않는다. */
@Configuration
public class ImageStorageConfig {

    @Bean
    public ImageStorage imageStorage(CommunityProperties properties) {
        if (properties.s3Bucket().isEmpty()) {
            if (properties.requireS3()) {
                throw new IllegalStateException("COMMUNITY_S3_BUCKET 이 비어 있다 — 운영은 사진을 S3 에 둔다(docs/SERVER_SYNC_HANDOFF.md 9절)");
            }
            return new InMemoryImageStorage();
        }
        Region region = Region.of(properties.s3Region());
        return new S3ImageStorage(S3Client.builder().region(region).build(), S3Presigner.builder().region(region).build(),
                properties.s3Bucket());
    }
}
