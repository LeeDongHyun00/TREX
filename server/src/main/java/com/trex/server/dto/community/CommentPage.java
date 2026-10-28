package com.trex.server.dto.community;

import java.util.List;

/** 댓글 한 쪽 — 오래된 순. 더 오래된 댓글을 받을 커서({@code before}), 없으면 null. */
public record CommentPage(List<CommentDto> items, Long nextBefore) {
}
