package com.trex.server.service;

import com.trex.server.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비밀번호 대입 막기(docs/SERVER_SYNC_HANDOFF.md D13) — 같은 아이디로 {@link #MAX_FAILURES}번 연속 틀리면 {@link #LOCK} 동안 맞는 비밀번호도 받지 않는다.
 * 마지막으로 틀린 지 {@link #LOCK} 이 지나면 틀린 횟수는 사라지고, 로그인에 성공하면 처음부터 다시 센다.
 * 없는 아이디도 똑같이 센다 — 막히는지로 회원이 있는지 알 수 없게. 서버 한 대의 메모리에만 두므로 재시작하면 풀린다.
 */
@Component
public class LoginAttemptGuard {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(10);
    /** 이보다 많이 쌓이면 지난 기록을 치운다 — 아무 아이디나 넣어 메모리를 채우지 못하게. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Attempts(int failures, Instant lastFailure, Instant lockedUntil) {
        boolean lockedAt(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        boolean staleAt(Instant now) {
            return !lockedAt(now) && !now.isBefore(lastFailure.plus(LOCK));
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptGuard(Clock clock) {
        this.clock = clock;
    }

    /** 막힌 아이디면 429. 비밀번호를 확인하기 전에 부른다. */
    public void checkAllowed(String loginId) {
        Attempts a = attempts.get(loginId);
        if (a != null && a.lockedAt(clock.instant())) {
            throw new TooManyRequestsException("비밀번호를 여러 번 틀렸습니다. 10분 뒤에 다시 시도해 주세요");
        }
    }

    public void recordFailure(String loginId) {
        Instant now = clock.instant();
        attempts.compute(loginId, (id, a) -> {
            int failures = a == null || a.staleAt(now) ? 1 : a.failures() + 1;
            return failures >= MAX_FAILURES
                    ? new Attempts(0, now, now.plus(LOCK))
                    : new Attempts(failures, now, null);
        });
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.values().removeIf(a -> a.staleAt(now));
        }
    }

    public void recordSuccess(String loginId) {
        attempts.remove(loginId);
    }
}
