package com.trex.server.service;

import com.trex.server.community.CommunityAccountCleaner;
import com.trex.server.dto.LoginRequest;
import com.trex.server.dto.SignupRequest;
import com.trex.server.dto.TokenResponse;
import com.trex.server.dto.UserResponse;
import com.trex.server.entity.AuthProvider;
import com.trex.server.entity.User;
import com.trex.server.exception.DuplicateResourceException;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.oauth.AppleTokenClient;
import com.trex.server.oauth.SocialIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trex.server.repository.DietLogRepository;
import com.trex.server.repository.ExerciseLogRepository;
import com.trex.server.repository.SyncItemRepository;
import com.trex.server.repository.UserProfileRepository;
import com.trex.server.repository.UserRepository;
import com.trex.server.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class AuthService {

    /** 소셜 제공자가 이름을 주지 않았을 때(카카오 닉네임 미동의 등). */
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final String DEFAULT_NAME = "T-REX 사용자";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final UserProfileRepository profileRepository;
    private final DietLogRepository dietLogRepository;
    private final ExerciseLogRepository exerciseLogRepository;
    private final SyncItemRepository syncItemRepository;
    private final CommunityAccountCleaner communityAccountCleaner;
    private final AppleTokenClient appleTokenClient;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider,
            UserProfileRepository profileRepository,
            DietLogRepository dietLogRepository,
            ExerciseLogRepository exerciseLogRepository,
            SyncItemRepository syncItemRepository,
            CommunityAccountCleaner communityAccountCleaner,
            AppleTokenClient appleTokenClient
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.profileRepository = profileRepository;
        this.dietLogRepository = dietLogRepository;
        this.exerciseLogRepository = exerciseLogRepository;
        this.syncItemRepository = syncItemRepository;
        this.communityAccountCleaner = communityAccountCleaner;
        this.appleTokenClient = appleTokenClient;
    }

    @Transactional
    public TokenResponse signup(SignupRequest request) {
        // "google_…"·"kakao_…" 는 소셜 회원의 내부 아이디다(D2). 미리 가입해 두면 그 소셜 계정이 가입하지 못한다.
        String lower = request.loginId().toLowerCase(Locale.ROOT);
        for (AuthProvider p : AuthProvider.values()) {
            if (p != AuthProvider.LOCAL && lower.startsWith(p.name().toLowerCase(Locale.ROOT) + "_")) {
                throw new DuplicateResourceException("사용할 수 없는 아이디입니다");
            }
        }
        if (userRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateResourceException("이미 사용 중인 아이디입니다");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("이미 사용 중인 이메일입니다");
        }

        User user = User.builder()
                .loginId(request.loginId())
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name())
                .email(request.email())
                .provider(AuthProvider.LOCAL)
                .build();

        User saved = userRepository.save(user);
        return issueToken(saved);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다"));

        // 소셜 회원은 비밀번호가 없다 — 내부 loginId("google_…")를 알아도 비밀번호 로그인은 받지 않는다.
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다");
        }

        return issueToken(user);
    }

    /**
     * 검증된 소셜 계정으로 로그인한다. 처음이면 회원을 만든다(docs/SERVER_SYNC_HANDOFF.md D2~D4).
     *
     * <p>이메일이 이미 다른 회원에게 있으면 **계정을 합치지 않고** 새 회원의 이메일을 비워 둔다 — 남의 이메일을 넣은
     * 소셜 계정으로 기존 회원을 가로채는 길을 막는다. 제공자가 확인하지 않은 이메일도 쓰지 않는다.
     */
    @Transactional
    public TokenResponse socialLogin(SocialIdentity identity) {
        return issueToken(findOrCreate(identity));
    }

    /**
     * Apple 로그인(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §4) — 구글과 같은 길로 회원을 찾거나 만들고,
     * 서버에 Apple 키가 있으면 authorization code 를 refresh token 으로 바꿔 기억한다(탈퇴 때 연결 끊기용).
     * 교환은 토큰이 발급된 client(iPhone 번들 ID·Android Services ID)로 한다. 교환이 실패해도(만료·재사용) 로그인은 성공한다.
     */
    @Transactional
    public TokenResponse appleLogin(SocialIdentity identity, String authorizationCode) {
        User user = findOrCreate(identity);
        if (appleTokenClient.configured() && authorizationCode != null && !authorizationCode.isBlank()) {
            try {
                appleTokenClient.exchangeCode(authorizationCode, identity.clientId())
                        .ifPresent(refreshToken -> user.rememberAppleRefreshToken(refreshToken, identity.clientId()));
            } catch (RuntimeException e) {
                log.warn("Apple code 교환 중 예외(로그인은 그대로): {}", e.getClass().getSimpleName());
            }
        }
        return issueToken(user);
    }

    private User findOrCreate(SocialIdentity identity) {
        if (identity.providerId() == null || identity.providerId().isBlank()) {
            throw new InvalidCredentialsException("소셜 계정 정보를 확인하지 못했습니다");
        }
        return userRepository.findByProviderAndProviderId(identity.provider(), identity.providerId())
                .orElseGet(() -> {
                    String email = identity.emailVerified() && identity.email() != null
                            && !userRepository.existsByEmail(identity.email()) ? identity.email() : null;
                    String name = identity.name() == null || identity.name().isBlank() ? DEFAULT_NAME : identity.name();
                    return userRepository.save(User.builder()
                            .loginId(identity.provider().name().toLowerCase(Locale.ROOT) + "_" + identity.providerId())
                            .name(name)
                            .email(email)
                            .provider(identity.provider())
                            .providerId(identity.providerId())
                            .build());
                });
    }

    public UserResponse getByLoginId(String loginId) {
        return UserResponse.from(userRepository.getByLoginIdOrThrow(loginId));
    }

    /**
     * 회원 탈퇴 — 회원과 그 회원의 모든 데이터(프로필·식단·운동 기록·동기화 백업·커뮤니티 글·사진·댓글·좋아요·신고·차단)를 지운다.
     * 건강·식단 정보를 보관하므로 사용자가 언제든 지울 수 있어야 한다.
     */
    @Transactional
    public void deleteAccount(String loginId) {
        User user = userRepository.getByLoginIdOrThrow(loginId);
        if (user.getProvider() == AuthProvider.APPLE && user.getAppleRefreshToken() != null) {
            // Apple 연결 끊기(App Store 탈퇴 규칙). 실패해도 탈퇴는 진행한다 — 탈퇴를 못 하는 쪽이 더 나쁘다
            try {
                appleTokenClient.revoke(user.getAppleRefreshToken(), user.getAppleClientId());
            } catch (RuntimeException e) {
                log.warn("Apple 연결 끊기 중 예외(탈퇴는 그대로): {}", e.getClass().getSimpleName());
            }
        }
        Integer id = user.getId();
        syncItemRepository.deleteByUserId(id);
        dietLogRepository.deleteByUserId(id);
        exerciseLogRepository.deleteByUserId(id);
        profileRepository.deleteByUserId(id);
        communityAccountCleaner.deleteAllFor(user);
        userRepository.delete(user);
    }

    private TokenResponse issueToken(User user) {
        String token = tokenProvider.createToken(user.getLoginId());
        return TokenResponse.of(token, tokenProvider.getExpirationMillis(), UserResponse.from(user));
    }
}
