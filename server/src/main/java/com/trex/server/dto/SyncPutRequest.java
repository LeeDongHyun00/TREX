package com.trex.server.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 항목 하나 올리기. {@code payload} 는 앱이 저장한 JSON 을 문자열 그대로, {@code updatedAt} 은 앱에서 그 항목을 바꾼 시각(epoch ms).
 */
public record SyncPutRequest(
        @NotNull(message = "payload 는 필수입니다")
        String payload,
        @NotNull(message = "updatedAt 은 필수입니다")
        @PositiveOrZero(message = "updatedAt 은 0 이상이어야 합니다")
        Long updatedAt
) {
}
