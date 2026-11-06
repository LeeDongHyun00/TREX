package com.trex.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 신고 하나 — 한 사람이 같은 대상을 한 번만. 미처리 신고가 서로 다른 세 사람에게서 모이면 글·댓글을 자동 숨김한다.
 * 관리자가 처리하면 {@code resolvedAt}·{@code resolution} 이 찬다(정보통신망법 제44조의2 의 처리 기록).
 */
@Entity
@Table(name = "REPORTS", uniqueConstraints = @UniqueConstraint(columnNames = {"reporter_id", "target_type", "target_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private CommunityProfile reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 32)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReportReason reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ReportResolution resolution;

    public Report(CommunityProfile reporter, ReportTargetType targetType, String targetId, ReportReason reason, Instant now) {
        this.reporter = reporter;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.createdAt = now;
    }

    public void resolve(ReportResolution resolution, Instant now) {
        this.resolution = resolution;
        this.resolvedAt = now;
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }
}
