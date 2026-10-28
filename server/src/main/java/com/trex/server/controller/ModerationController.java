package com.trex.server.controller;

import com.trex.server.dto.community.MyReportDto;
import com.trex.server.dto.community.ProfileDto;
import com.trex.server.dto.community.ReportRequest;
import com.trex.server.service.ModerationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 차단·신고 API — 카드 ⋯ 메뉴의 신고·차단(따로), 내 정보의 차단 목록·내 신고(설계 §5). */
@RestController
@RequestMapping("/api/community")
public class ModerationController {

    private final ModerationService moderation;

    public ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @PutMapping("/blocks/{publicId}")
    public ResponseEntity<Void> block(@AuthenticationPrincipal String loginId, @PathVariable String publicId) {
        try {
            moderation.block(loginId, publicId);
        } catch (DataIntegrityViolationException e) {
            // 같은 사람이 동시에 두 번 눌러 유니크에 걸렸다 — 이미 차단 상태다(멱등).
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/blocks/{publicId}")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal String loginId, @PathVariable String publicId) {
        moderation.unblock(loginId, publicId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/blocks")
    public List<ProfileDto> blocks(@AuthenticationPrincipal String loginId) {
        return moderation.blocks(loginId);
    }

    @PostMapping("/reports")
    public ResponseEntity<Void> report(@AuthenticationPrincipal String loginId, @RequestBody ReportRequest request) {
        moderation.report(loginId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/me/reports")
    public List<MyReportDto> myReports(@AuthenticationPrincipal String loginId) {
        return moderation.myReports(loginId);
    }
}
