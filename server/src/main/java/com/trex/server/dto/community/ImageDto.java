package com.trex.server.dto.community;

/** 글 사진 한 장 — 주소(운영은 1시간짜리 서명 주소)와 크기. */
public record ImageDto(String url, int width, int height) {
}
