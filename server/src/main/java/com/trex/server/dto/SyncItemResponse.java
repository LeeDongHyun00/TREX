package com.trex.server.dto;

import com.trex.server.entity.SyncItem;

import java.util.List;

public record SyncItemResponse(
        String key,
        String payload,
        long updatedAt
) {
    public static SyncItemResponse from(SyncItem item) {
        return new SyncItemResponse(item.getItemKey(), item.getPayload(), item.getClientUpdatedAt());
    }

    /** GET /api/sync 응답. */
    public record Items(List<SyncItemResponse> items) {
    }
}
