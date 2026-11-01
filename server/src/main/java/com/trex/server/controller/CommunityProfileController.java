package com.trex.server.controller;

import com.trex.server.dto.community.MeResponse;
import com.trex.server.dto.community.NicknameRequest;
import com.trex.server.dto.community.ProfileDto;
import com.trex.server.dto.community.TermsRequest;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.service.CommunityProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 커뮤니티 프로필 API — 처음 들어올 때 닉네임 → 이용규칙 동의 → 피드(설계 §5). */
@RestController
@RequestMapping("/api/community")
public class CommunityProfileController {

    private final CommunityProfileService profileService;

    public CommunityProfileController(CommunityProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal String loginId) {
        return profileService.me(loginId);
    }

    @PutMapping("/me/profile")
    public ProfileDto setNickname(@AuthenticationPrincipal String loginId, @RequestBody NicknameRequest request) {
        return profileService.setNickname(loginId, request.nickname());
    }

    @PostMapping("/me/terms")
    public ResponseEntity<Void> acceptTerms(@AuthenticationPrincipal String loginId, @RequestBody TermsRequest request) {
        profileService.acceptTerms(loginId, request.version());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/avatar")
    public ProfileDto setAvatar(@AuthenticationPrincipal String loginId, @RequestParam("image") MultipartFile image) throws IOException {
        if (image.isEmpty()) throw new InvalidRequestException("사진이 비어 있어요");
        return profileService.setAvatar(loginId, image.getBytes());
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<Void> clearAvatar(@AuthenticationPrincipal String loginId) {
        profileService.clearAvatar(loginId);
        return ResponseEntity.noContent().build();
    }
}
