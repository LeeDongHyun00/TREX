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

/** 좋아요 하나 — 한 사람이 한 글에 한 번(유니크). 두 번 눌러도 하나다. */
@Entity
@Table(name = "POST_LIKES", uniqueConstraints = @UniqueConstraint(columnNames = {"post_id", "profile_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private CommunityProfile profile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PostLike(Post post, CommunityProfile profile, Instant now) {
        this.post = post;
        this.profile = profile;
        this.createdAt = now;
    }
}
