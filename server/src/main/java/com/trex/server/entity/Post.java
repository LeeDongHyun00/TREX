package com.trex.server.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 커뮤니티 글 하나 — 본문(없을 수 있다)과 사진 0~5장(설계 §2 목표 3). 숨김({@code hiddenAt})은 신고 누적·관리자가 걸고,
 * 숨긴 글은 작성자·관리자에게만 보인다. 사진 파일은 저장소({@code ImageStorage})에 있고 여기에는 키만 둔다.
 */
@Entity
@Table(name = "POSTS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private CommunityProfile author;

    // 2,000자는 코드포인트 기준으로 서비스가 검사한다 — 열은 이모지(UTF-16 두 칸)를 감안해 넉넉히.
    @Column(length = 4000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "hidden_reason", length = 40)
    private String hiddenReason;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 50)
    private List<PostImage> images = new ArrayList<>();

    public Post(CommunityProfile author, String body, Instant now) {
        this.author = author;
        this.body = body;
        this.createdAt = now;
    }

    public void addImage(PostImage image) {
        images.add(image);
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
