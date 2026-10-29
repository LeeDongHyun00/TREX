package com.trex.server.community;

import com.trex.server.entity.AuthProvider;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.oauth.GoogleTokenVerifier;
import com.trex.server.oauth.SocialIdentity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/** 커뮤니티 API 테스트 공통 설정 — 가짜 구글 검증기(SocialSyncApiTest 와 같은 토큰 형식)와 넘길 수 있는 시계. */
@TestConfiguration
public class CommunityTestConfig {

    @Bean
    @Primary
    GoogleTokenVerifier fakeGoogleTokenVerifier() {
        return idToken -> {
            String[] p = idToken.split("\\|", -1);
            if (p.length != 5 || !p[0].equals("ok")) {
                throw new InvalidCredentialsException("구글 로그인 정보를 확인하지 못했습니다");
            }
            return new SocialIdentity(AuthProvider.GOOGLE, p[1], p[2].isEmpty() ? null : p[2],
                    Boolean.parseBoolean(p[3]), p[4].isEmpty() ? null : p[4]);
        };
    }

    @Bean
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    Clock testClock(MutableClock clock) {
        return clock;
    }
}
