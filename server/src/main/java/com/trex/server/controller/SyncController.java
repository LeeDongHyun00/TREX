package com.trex.server.controller;

import com.trex.server.dto.SyncItemResponse;
import com.trex.server.dto.SyncPutRequest;
import com.trex.server.service.SyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 데이터 통째 백업 API(docs/SERVER_SYNC_HANDOFF.md 4절).
 * PUT 이 409 면 서버 값이 더 최신이라는 뜻이고, 본문에 서버 값이 담겨 있다 — 앱은 그 값을 받아들인다.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping
    public ResponseEntity<SyncItemResponse.Items> list(@AuthenticationPrincipal String loginId) {
        return ResponseEntity.ok(new SyncItemResponse.Items(syncService.getMine(loginId)));
    }

    @PutMapping("/{key}")
    public ResponseEntity<SyncItemResponse> put(
            @AuthenticationPrincipal String loginId,
            @PathVariable String key,
            @Valid @RequestBody SyncPutRequest request
    ) {
        SyncService.PutResult result = syncService.put(loginId, key, request.payload(), request.updatedAt());
        return ResponseEntity.status(result.applied() ? HttpStatus.OK : HttpStatus.CONFLICT).body(result.item());
    }
}
