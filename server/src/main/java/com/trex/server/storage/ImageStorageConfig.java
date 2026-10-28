package com.trex.server.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 사진 저장소 고르기 — 버킷이 있으면 S3(운영, 8단계에서 연결), 없으면 메모리. */
@Configuration
public class ImageStorageConfig {

    @Bean
    public ImageStorage imageStorage() {
        return new InMemoryImageStorage();
    }
}
