package com.trex.server.controller;

import com.trex.server.dto.community.AdminReportDto;
import com.trex.server.dto.community.PostDto;
import com.trex.server.dto.community.ProfileDto;
import com.trex.server.dto.community.ResolveRequest;
import com.trex.server.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 관리자 API — 앱 안 신고함(내 정보 탭, 관리자에게만 보인다). 관리자가 아니면 모두 403. */
@RestController
@RequestMapping("/api/community/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/reports")
    public List<AdminReportDto> reports(@AuthenticationPrincipal String loginId,
                                        @RequestParam(defaultValue = "true") boolean pendingOnly) {
        return admin.reports(loginId, pendingOnly);
    }

    @PostMapping("/reports/resolve")
    public ResponseEntity<Void> resolve(@AuthenticationPrincipal String loginId, @RequestBody ResolveRequest request) {
        admin.resolve(loginId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{id}/hide")
    public ResponseEntity<Void> hidePost(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        admin.hidePost(loginId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{id}/restore")
    public ResponseEntity<Void> restorePost(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        admin.restorePost(loginId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{id}/hide")
    public ResponseEntity<Void> hideComment(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        admin.hideComment(loginId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{id}/restore")
    public ResponseEntity<Void> restoreComment(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        admin.restoreComment(loginId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hidden")
    public List<PostDto> hidden(@AuthenticationPrincipal String loginId) {
        return admin.hiddenPosts(loginId);
    }

    @PostMapping("/users/{publicId}/suspend")
    public ResponseEntity<Void> suspend(@AuthenticationPrincipal String loginId, @PathVariable String publicId) {
        admin.suspend(loginId, publicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{publicId}/unsuspend")
    public ResponseEntity<Void> unsuspend(@AuthenticationPrincipal String loginId, @PathVariable String publicId) {
        admin.unsuspend(loginId, publicId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{publicId}/avatar")
    public ResponseEntity<Void> clearAvatar(@AuthenticationPrincipal String loginId, @PathVariable String publicId) {
        admin.clearAvatar(loginId, publicId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/suspended")
    public List<ProfileDto> suspended(@AuthenticationPrincipal String loginId) {
        return admin.suspended(loginId);
    }
}
