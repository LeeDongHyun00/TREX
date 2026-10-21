package com.trex.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "USERS",
        // 같은 구글·카카오 계정은 한 회원이다. LOCAL 회원은 providerId 가 없다(null 은 유니크 비교에서 빠진다).
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // 요청 안에서 사용자를 찾는 내부 식별자. 소셜 회원은 "google_<sub>" 처럼 만든다 —
    // 모든 서비스가 loginId 로 사용자를 찾으므로 소셜 회원도 같은 길을 쓴다(docs/SERVER_SYNC_HANDOFF.md D2).
    @Column(nullable = false, unique = true)
    private String loginId;

    // 소셜 회원은 비밀번호가 없다. null 이면 아이디·비밀번호 로그인을 받지 않는다.
    @Column
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    // 카카오는 이메일 동의를 안 할 수 있고, 이미 다른 회원이 쓰는 이메일이면 비워 둔다(D4).
    @Column(unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuthProvider provider;

    @Column(name = "provider_id")
    private String providerId;

    @Builder
    public User(String loginId, String passwordHash, String name, String email, AuthProvider provider, String providerId) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.email = email;
        this.provider = provider == null ? AuthProvider.LOCAL : provider;
        this.providerId = providerId;
    }
}
