package com.trex.server.controller;

import com.trex.server.dto.DietLogRequest;
import com.trex.server.dto.DietLogResponse;
import com.trex.server.service.DietLogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/diet-logs")
public class DietLogController {

    private final DietLogService dietLogService;

    public DietLogController(DietLogService dietLogService) {
        this.dietLogService = dietLogService;
    }

    @PostMapping
    public ResponseEntity<DietLogResponse> create(
            @AuthenticationPrincipal String loginId,
            @Valid @RequestBody DietLogRequest request
    ) {
        DietLogResponse response = dietLogService.create(loginId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<DietLogResponse>> list(@AuthenticationPrincipal String loginId) {
        return ResponseEntity.ok(dietLogService.getMine(loginId));
    }
}
