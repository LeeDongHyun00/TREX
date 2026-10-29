package com.trex.server.community;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트가 넘기는 시계 — 속도 제한(1분 1개 등)을 기다리지 않고 확인한다. 테스트끼리 공유하므로 앞으로만 넘긴다. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T00:00:00Z"));

    public void advance(Duration duration) {
        now.updateAndGet(t -> t.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
