package com.trex.server.dto.community;

import java.util.List;

/** 글 한 쪽 — 다음 쪽을 받을 커서({@code before} 로 보낸다), 없으면 null. */
public record PostPage(List<PostDto> items, Long nextBefore) {
}
