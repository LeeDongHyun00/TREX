package com.trex.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 메신저로 보낸 루틴 하나(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 링크의 {@code code} 로 누구나 본다.
 * 운동 목록은 앱의 오늘 루틴 JSON 과 같은 칸({@code payload}). 보낸 사람(커뮤니티 프로필)이 탈퇴하면 지운다.
 */
@Entity
@Table(name = "SHARED_ROUTINES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharedRoutine {

    public static final int MAX_PAYLOAD_CHARS = 20_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 8)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private CommunityProfile owner;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(name = "total_seconds", nullable = false)
    private int totalSeconds;

    @Column(name = "exercise_count", nullable = false)
    private int exerciseCount;

    @Column(nullable = false, length = MAX_PAYLOAD_CHARS)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SharedRoutine(String code, CommunityProfile owner, String name, int totalSeconds, int exerciseCount, String payload, Instant now) {
        this.code = code;
        this.owner = owner;
        this.name = name;
        this.totalSeconds = totalSeconds;
        this.exerciseCount = exerciseCount;
        this.payload = payload;
        this.createdAt = now;
    }
}
