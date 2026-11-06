package com.trex.server.controller;

import com.trex.server.dto.community.CommentDto;
import com.trex.server.dto.community.CommentPage;
import com.trex.server.dto.community.CommentRequest;
import com.trex.server.service.CommentService;
import org.springframework.http.HttpStatus;
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

/** 댓글 API — 글 상세에서 보고 쓴다(설계 §5). */
@RestController
@RequestMapping("/api/community")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/posts/{postId}/comments")
    public CommentPage list(@AuthenticationPrincipal String loginId, @PathVariable long postId,
                            @RequestParam(required = false) Long before) {
        return commentService.list(loginId, postId, before);
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentDto> add(@AuthenticationPrincipal String loginId, @PathVariable long postId,
                                          @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commentService.add(loginId, postId, request.body()));
    }

    @DeleteMapping("/comments/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        commentService.delete(loginId, id);
        return ResponseEntity.noContent().build();
    }
}
