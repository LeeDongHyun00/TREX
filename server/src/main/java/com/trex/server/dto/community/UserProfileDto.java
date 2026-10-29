package com.trex.server.dto.community;

/** 다른 사람 프로필 — 보이는 글 수와 내가 차단했는지. */
public record UserProfileDto(ProfileDto profile, long postCount, boolean blockedByMe) {
}
