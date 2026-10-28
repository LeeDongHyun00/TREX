package com.trex.server.controller;

import com.trex.server.dto.community.PostDto;
import com.trex.server.dto.community.PostPage;
import com.trex.server.dto.community.UserProfileDto;
import com.trex.server.service.PostService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 커뮤니티 글 API — 피드·쓰기(multipart: 글 {@code body}, 사진 {@code images} 0~5장)·상세·삭제·사람별 글. */
@RestController
@RequestMapping("/api/community")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/posts")
    public PostPage feed(@AuthenticationPrincipal String loginId,
                         @RequestParam(required = false) Long before,
                         @RequestParam(defaultValue = "20") int limit) {
        return postService.feed(loginId, before, limit);
    }

    @PostMapping(value = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PostDto> create(@AuthenticationPrincipal String loginId,
                                          @RequestParam(required = false) String body,
                                          @RequestParam(required = false) List<MultipartFile> images) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postService.create(loginId, body, images));
    }

    @GetMapping("/posts/{id}")
    public PostDto get(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        return postService.get(loginId, id);
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String loginId, @PathVariable long id) {
        postService.delete(loginId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{publicId}")
    public UserProfileDto user(@AuthenticationPrincipal String loginId, @PathVariable String publicId) {
        return postService.user(loginId, publicId);
    }

    @GetMapping("/users/{publicId}/posts")
    public PostPage userPosts(@AuthenticationPrincipal String loginId, @PathVariable String publicId,
                              @RequestParam(required = false) Long before) {
        return postService.userPosts(loginId, publicId, before);
    }
}
