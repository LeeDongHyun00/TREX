package com.trex.server.storage;

/**
 * 커뮤니티 사진 저장소 경계 — 운영은 비공개 S3 버킷, 테스트·로컬은 메모리.
 * 키 형식: 글 사진 {@code posts/{postId}/{position}-{uuid}.jpg}, 프로필 사진 {@code avatars/{publicId}-{uuid}.jpg}.
 */
public interface ImageStorage {

    /** JPEG 한 장을 저장한다. */
    void put(String key, byte[] jpeg);

    /** 지운다. 없는 키여도 예외를 내지 않는다. */
    void delete(String key);

    /** 앱이 사진을 받을 주소(운영은 수명 1시간 서명 주소). */
    String url(String key);
}
