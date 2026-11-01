package com.trex.server.controller;

import com.trex.server.config.ShareProperties;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.service.RoutineShareService;
import com.trex.server.share.SharePages;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** 메신저로 보낸 링크가 여는 웹 페이지 — 루틴 {@code /r/{code}}, 게시글 {@code /p/{postId}}(로그인 없이, 설계 §4). */
@Controller
public class SharePageController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final RoutineShareService service;
    private final ShareProperties properties;

    public SharePageController(RoutineShareService service, ShareProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<String> routine(@PathVariable String code, @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String device = SharePages.device(userAgent);
        try {
            return html(HttpStatus.OK, SharePages.routine(service.get(code), code, device, properties));
        } catch (ResourceNotFoundException e) {
            return html(HttpStatus.NOT_FOUND, SharePages.notFound("없어졌거나 잘못된 링크예요", device, properties));
        }
    }

    @GetMapping("/p/{postId}")
    public ResponseEntity<String> post(@PathVariable long postId, @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String device = SharePages.device(userAgent);
        return service.postAuthorNickname(postId)
                .map(nickname -> html(HttpStatus.OK, SharePages.post(postId, nickname, device, properties)))
                .orElseGet(() -> html(HttpStatus.NOT_FOUND, SharePages.notFound("볼 수 없는 글이에요", device, properties)));
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML).body(body);
    }
}
