package com.trex.server.controller;

import com.trex.server.dto.ShareRoutineRequest;
import com.trex.server.dto.ShareRoutineResponse;
import com.trex.server.dto.SharedRoutineResponse;
import com.trex.server.service.RoutineShareService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 루틴 공유 API — 공유는 로그인한 커뮤니티 회원, 받은 루틴 조회는 로그인 없이(보안 설정에서 GET 만 연다). */
@RestController
@RequestMapping("/api/routines")
public class RoutineShareController {

    private final RoutineShareService service;

    public RoutineShareController(RoutineShareService service) {
        this.service = service;
    }

    @PostMapping("/share")
    public ResponseEntity<ShareRoutineResponse> share(@AuthenticationPrincipal String loginId, @RequestBody ShareRoutineRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.share(loginId, request));
    }

    @GetMapping("/shared/{code}")
    public SharedRoutineResponse shared(@PathVariable String code) {
        return service.get(code);
    }
}
