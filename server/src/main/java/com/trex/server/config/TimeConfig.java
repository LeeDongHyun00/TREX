package com.trex.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 지금 시각 — 속도 제한·작성 시각을 테스트가 바꿔 끼울 수 있게 빈으로 둔다. */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
