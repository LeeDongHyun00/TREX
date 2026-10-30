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

    // Apple 회원의 refresh token — 탈퇴 때 Apple 연결을 끊는 데 쓴다(App Store 탈퇴 규칙). 비밀값이라 응답·로그에 내보내지 않는다.
    // 서버에 Apple 키가 없을 때 가입했으면 비어 있고, 그때는 연결 끊기를 건너뛴다.
    @Column(name = "apple_refresh_token", length = 1024)
    private String appleRefreshToken;

    // 그 refresh token 을 받은 client_id(iPhone 번들 ID 또는 Android 웹 로그인 Services ID) — 연결 끊기도 같은 client 로 불러야 Apple 이 받는다.
    // 이 열이 생기기 전 회원은 비어 있고, 그때는 기본 번들 ID 로 끊는다.
    @Column(name = "apple_client_id")
    private String appleClientId;

    @Builder
    public User(String loginId, String passwordHash, String name, String email, AuthProvider provider, String providerId) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.email = email;
        this.provider = provider == null ? AuthProvider.LOCAL : provider;
        this.providerId = providerId;
    }

    /** Apple 로그인 때 code 를 바꿔 받은 refresh token 과 그 client 를 기억한다(다시 로그인하면 새 것으로). */
    public void rememberAppleRefreshToken(String refreshToken, String clientId) {
        this.appleRefreshToken = refreshToken;
        this.appleClientId = clientId;
    }
}
