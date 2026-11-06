package com.trex.server.dto.community;

/** 커뮤니티 진입 상태 — 프로필(없으면 null), 이용규칙 동의, 관리자, 정지. */
public record MeResponse(ProfileDto profile, boolean termsAccepted, boolean admin, boolean suspended) {
}
