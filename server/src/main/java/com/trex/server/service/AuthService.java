package com.trex.server.service;

import com.trex.server.dto.LoginRequest;
import com.trex.server.dto.SignupRequest;
import com.trex.server.dto.TokenResponse;
import com.trex.server.dto.UserResponse;
import com.trex.server.entity.User;
import com.trex.server.exception.DuplicateResourceException;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.repository.UserRepository;
import com.trex.server.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public TokenResponse signup(SignupRequest request) {
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
                .build();

        User saved = userRepository.save(user);
        return issueToken(saved);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다");
        }

        return issueToken(user);
    }

    public UserResponse getByLoginId(String loginId) {
        return UserResponse.from(userRepository.getByLoginIdOrThrow(loginId));
    }

    private TokenResponse issueToken(User user) {
        String token = tokenProvider.createToken(user.getLoginId());
        return TokenResponse.of(token, tokenProvider.getExpirationMillis(), UserResponse.from(user));
    }
}
