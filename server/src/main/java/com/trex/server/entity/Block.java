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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 차단 — 차단한 사람에게 차단당한 사람의 글·댓글이 보이지 않는다(설계 §5). 한 방향이다. */
@Entity
@Table(name = "BLOCKS", uniqueConstraints = @UniqueConstraint(columnNames = {"blocker_id", "blocked_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocker_id", nullable = false)
    private CommunityProfile blocker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id", nullable = false)
    private CommunityProfile blocked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Block(CommunityProfile blocker, CommunityProfile blocked, Instant now) {
        this.blocker = blocker;
        this.blocked = blocked;
        this.createdAt = now;
    }
}
