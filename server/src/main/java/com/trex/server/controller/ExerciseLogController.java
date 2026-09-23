package com.trex.server.controller;

import com.trex.server.dto.ExerciseLogRequest;
import com.trex.server.dto.ExerciseLogResponse;
import com.trex.server.service.ExerciseLogService;
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
@RequestMapping("/api/exercise-logs")
public class ExerciseLogController {

    private final ExerciseLogService exerciseLogService;

    public ExerciseLogController(ExerciseLogService exerciseLogService) {
        this.exerciseLogService = exerciseLogService;
    }

    @PostMapping
    public ResponseEntity<ExerciseLogResponse> create(
            @AuthenticationPrincipal String loginId,
            @Valid @RequestBody ExerciseLogRequest request
    ) {
        ExerciseLogResponse response = exerciseLogService.create(loginId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ExerciseLogResponse>> list(@AuthenticationPrincipal String loginId) {
        return ResponseEntity.ok(exerciseLogService.getMine(loginId));
    }
}
