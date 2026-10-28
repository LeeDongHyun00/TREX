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

/** 댓글 하나(한 단계, 답글 없음). 숨김은 글과 같은 규칙 — 작성자·관리자만 본다. */
@Entity
@Table(name = "COMMENTS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private CommunityProfile author;

    // 500자는 코드포인트 기준으로 서비스가 검사한다.
    @Column(nullable = false, length = 1000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "hidden_reason", length = 40)
    private String hiddenReason;

    public Comment(Post post, CommunityProfile author, String body, Instant now) {
        this.post = post;
        this.author = author;
        this.body = body;
        this.createdAt = now;
    }

    public void hide(String reason, Instant now) {
        this.hiddenAt = now;
        this.hiddenReason = reason;
    }

    public void restore() {
        this.hiddenAt = null;
        this.hiddenReason = null;
    }

    public boolean isHidden() {
        return hiddenAt != null;
    }
}
