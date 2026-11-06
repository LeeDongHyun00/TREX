package com.trex.server.dto.community;

/** 다른 사용자에게 보이는 나 — publicId·닉네임·프로필 사진 주소(없으면 null)만. */
public record ProfileDto(String publicId, String nickname, String avatarUrl) {
}
