package com.trex.server.dto.community;

import java.util.List;

/**
 * 피드·상세의 글 하나. {@code createdAt} 은 epoch ms. {@code hiddenReason} 은 숨긴 글을 작성자·관리자가 볼 때만 담긴다
 * (앱이 "숨김 처리됨 · 사유"를 보인다). 좋아요·댓글 수와 내 좋아요는 보는 사람 기준.
 */
public record PostDto(long id, ProfileDto author, String body, List<ImageDto> images, long likeCount, boolean likedByMe,
                      long commentCount, long createdAt, boolean hidden, String hiddenReason, boolean mine) {
}
