package com.trex.server.controller;

import com.trex.server.dto.GoogleLoginRequest;
import com.trex.server.dto.LoginRequest;
import com.trex.server.dto.SignupRequest;
import com.trex.server.dto.TokenResponse;
import com.trex.server.dto.UserResponse;
import com.trex.server.dto.AppleLoginRequest;
import com.trex.server.oauth.AppleTokenVerifier;
import com.trex.server.oauth.GoogleTokenVerifier;
import com.trex.server.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final AppleTokenVerifier appleTokenVerifier;

    public AuthController(AuthService authService, GoogleTokenVerifier googleTokenVerifier, AppleTokenVerifier appleTokenVerifier) {
        this.authService = authService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.appleTokenVerifier = appleTokenVerifier;
    }

    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        TokenResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** 앱이 구글 로그인으로 받은 ID 토큰을 검증하고 우리 JWT 를 발급한다. 처음이면 회원을 만든다. */
    @PostMapping("/oauth/google")
    public ResponseEntity<TokenResponse> google(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(authService.socialLogin(googleTokenVerifier.verify(request.idToken())));
    }

    /** iPhone 이 Apple 로그인으로 받은 identity token 을 검증하고 우리 JWT 를 발급한다. 처음이면 회원을 만든다. */
    @PostMapping("/oauth/apple")
    public ResponseEntity<TokenResponse> apple(@Valid @RequestBody AppleLoginRequest request) {
        return ResponseEntity.ok(authService.appleLogin(
                appleTokenVerifier.verify(request.identityToken(), request.nonce()), request.authorizationCode()));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal String loginId) {
        return ResponseEntity.ok(authService.getByLoginId(loginId));
    }

    /** 회원 탈퇴 — 회원과 모든 데이터를 지운다. */
    @DeleteMapping("/me")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String loginId) {
        authService.deleteAccount(loginId);
        return ResponseEntity.noContent().build();
    }
}
