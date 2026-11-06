package com.trex.server.controller;

import com.trex.server.config.AppleProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Android 의 Apple 로그인(웹 흐름)이 앱으로 돌아오는 길(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §9).
 * Android 에는 Apple 로그인 창이 없어 Apple 웹 로그인을 쓰는데, Apple 은 결과를 앱이 아니라 Services ID 에 등록한 Return URL(이 주소)로
 * form_post 한다. 그래서 받은 값을 앱 딥링크({@code intent://callback?…#Intent;scheme=trexapple;package=…;end})로 돌려보낸다.
 *
 * <p>여기서는 검증하지 않는다 — 앱은 자기가 보낸 state 일 때만 받고, 토큰을 /api/auth/oauth/apple 로 보내면 서버가 서명·대상·nonce 원문까지
 * 확인한다(nonce 원문은 앱만 안다). 넘기는 값은 정한 넷뿐이고 길이를 넘으면 뺀다. 토큰·code 는 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/auth/oauth/apple")
public class AppleWebCallbackController {

    /** 앱 AndroidManifest 의 콜백 스킴(AppleSignInCallbackActivity)과 같아야 한다. */
    static final String APP_SCHEME = "trexapple";

    /** 앱으로 넘기는 값과 최대 길이 — Apple 이 첫 로그인 때 같이 보내는 user(이름·이메일 JSON)는 넘기지 않는다(커뮤니티는 닉네임만 쓴다). */
    private static final Map<String, Integer> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("code", 1024);
        FIELDS.put("id_token", 8192);
        FIELDS.put("state", 256);
        FIELDS.put("error", 128);
    }

    private final AppleProperties properties;

    public AppleWebCallbackController(AppleProperties properties) {
        this.properties = properties;
    }

    @PostMapping(value = "/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callback(@RequestParam MultiValueMap<String, String> form) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, appRedirect(form, properties.androidPackage()))
                .build();
    }

    /** 앱으로 돌아가는 intent 주소. id_token 도 error 도 없으면(잘못 온 요청) error=invalid_response 로 앱이 실패를 알린다. */
    static String appRedirect(MultiValueMap<String, String> form, String androidPackage) {
        StringJoiner query = new StringJoiner("&");
        boolean answered = false;
        for (Map.Entry<String, Integer> field : FIELDS.entrySet()) {
            String value = form.getFirst(field.getKey());
            if (value == null || value.isBlank() || value.length() > field.getValue()) continue;
            query.add(field.getKey() + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            if (field.getKey().equals("id_token") || field.getKey().equals("error")) answered = true;
        }
        if (!answered) query.add("error=invalid_response");
        return "intent://callback?" + query + "#Intent;scheme=" + APP_SCHEME + ";package=" + androidPackage + ";end";
    }
}
