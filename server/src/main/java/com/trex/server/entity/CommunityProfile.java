package com.trex.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 커뮤니티에서 보이는 나(docs/superpowers/specs/2026-10-28-community-design.md §3·§5).
 *
 * <p>회원(User)과 1:1. 다른 사용자에게는 {@code publicId}·닉네임·프로필 사진만 보인다 — 구글 실명·이메일·loginId(구글 sub 포함)는
 * 커뮤니티 응답에 나가지 않는다. 닉네임 중복은 대소문자를 가리지 않는다({@code nicknameKey}).
 */
@Entity
@Table(name = "COMMUNITY_PROFILES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "public_id", nullable = false, unique = true, length = 16)
    private String publicId;

    // 12자는 코드포인트 기준이라 이모지가 섞이면 UTF-16 으로는 더 길다 — 열은 넉넉히.
    @Column(nullable = false, length = 40)
    private String nickname;

    @Column(name = "nickname_key", nullable = false, unique = true, length = 40)
    private String nicknameKey;

    @Column(name = "avatar_key", length = 200)
    private String avatarKey;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CommunityProfile(User user, String publicId, String nickname, String nicknameKey, Instant now) {
        this.user = user;
        this.publicId = publicId;
        this.nickname = nickname;
        this.nicknameKey = nicknameKey;
        this.createdAt = now;
    }

    public void rename(String nickname, String nicknameKey) {
        this.nickname = nickname;
        this.nicknameKey = nicknameKey;
    }

    public void changeAvatar(String avatarKey) {
        this.avatarKey = avatarKey;
    }

    public void acceptTerms(String version, Instant now) {
        this.termsVersion = version;
        this.termsAcceptedAt = now;
    }

    public boolean hasAccepted(String version) {
        return termsAcceptedAt != null && version.equals(termsVersion);
    }

    public void suspend(Instant now) {
        this.suspendedAt = now;
    }

    public void unsuspend() {
        this.suspendedAt = null;
    }

    public boolean isSuspended() {
        return suspendedAt != null;
    }
}
