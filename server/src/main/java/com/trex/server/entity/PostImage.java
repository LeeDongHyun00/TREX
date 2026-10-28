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

/** 글의 사진 한 장 — 저장소 키와 JPEG 헤더에서 읽은 크기(앱이 자리를 미리 잡는다). */
@Entity
@Table(name = "POST_IMAGES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(nullable = false)
    private int position;

    @Column(name = "storage_key", nullable = false, length = 200)
    private String storageKey;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    public PostImage(Post post, int position, String storageKey, int width, int height) {
        this.post = post;
        this.position = position;
        this.storageKey = storageKey;
        this.width = width;
        this.height = height;
    }
}
