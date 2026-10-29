package com.trex.server.dto.community;

/** 댓글 하나. {@code hiddenReason} 은 숨긴 댓글을 작성자·관리자가 볼 때만. */
public record CommentDto(long id, ProfileDto author, String body, long createdAt, boolean hidden, String hiddenReason, boolean mine) {
}
