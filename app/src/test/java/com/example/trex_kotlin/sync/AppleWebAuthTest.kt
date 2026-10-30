package com.example.trex_kotlin.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 의 Apple 로그인(웹 흐름, 설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md §9) — 주소 만들기와 콜백 읽기. */
class AppleWebAuthTest {

    private val servicesId = "com.leedonghyun.trex.signin"
    private val redirect = "https://trex-api.duckdns.org/api/auth/oauth/apple/callback"

    @Test
    fun 웹_로그인_주소는_form_post_로_이름_이메일을_달라고_하고_nonce_는_해시다() {
        val url = AppleWebAuth.authorizeUrl(servicesId, redirect, state = "st-1", rawNonce = "raw-1")
        assertTrue(url.startsWith("https://appleid.apple.com/auth/authorize?"))
        val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(servicesId, params["client_id"])
        assertEquals("https%3A%2F%2Ftrex-api.duckdns.org%2Fapi%2Fauth%2Foauth%2Fapple%2Fcallback", params["redirect_uri"])
        assertEquals("code%20id_token", params["response_type"])
        assertEquals("name%20email", params["scope"])
        assertEquals("form_post", params["response_mode"])
        assertEquals("st-1", params["state"])
        // 원문은 앱만 든다 — 서버가 받은 원문의 SHA-256 이 토큰의 nonce 와 같은지 본다
        assertEquals(AppleWebAuth.sha256Hex("raw-1"), params["nonce"])
    }

    @Test
    fun 해시는_서버_iPhone_과_같은_소문자_16진() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppleWebAuth.sha256Hex("abc"))
        assertEquals(64, AppleWebAuth.randomHex(32).length)
        assertTrue(AppleWebAuth.randomHex(16).all { it in "0123456789abcdef" })
    }

    @Test
    fun 콜백은_state_가_같을_때만_토큰과_code_를_준다() {
        val ok = AppleWebAuth.parseCallback("trexapple://callback?code=c-1&id_token=h.p.s&state=st-1", "st-1", "raw-1")
        assertEquals(AppleCredential("h.p.s", "c-1", "raw-1"), ok)
        // 다른 요청·위조 링크는 무시
        assertNull(AppleWebAuth.parseCallback("trexapple://callback?code=c-1&id_token=h.p.s&state=other", "st-1", "raw-1"))
        assertNull(AppleWebAuth.parseCallback("trexapple://callback?id_token=h.p.s", "st-1", "raw-1"))
        // code 가 없어도 로그인은 된다(서버가 연결 끊기만 못 한다)
        assertEquals(AppleCredential("h.p.s", null, "raw-1"), AppleWebAuth.parseCallback("trexapple://callback?id_token=h.p.s&state=st-1", "st-1", "raw-1"))
    }

    @Test
    fun 취소는_조용히_그_밖은_실패로() {
        try {
            AppleWebAuth.parseCallback("trexapple://callback?state=st-1&error=user_cancelled_authorize", "st-1", "raw-1")
            throw AssertionError("취소여야 한다")
        } catch (_: SignInException.Cancelled) { }
        for (uri in listOf("trexapple://callback?state=st-1&error=invalid_response", "trexapple://callback?state=st-1")) {
            try {
                AppleWebAuth.parseCallback(uri, "st-1", "raw-1")
                throw AssertionError("실패여야 한다: $uri")
            } catch (_: SignInException.Failed) { }
        }
    }

    @Test
    fun 서버가_인코딩한_값을_되돌린다() {
        // 서버(AppleWebCallbackController)는 URLEncoder 로 넣는다 — # ; & = 가 값 안에 있어도 그대로 돌아와야 한다
        val got = AppleWebAuth.parseCallback("trexapple://callback?id_token=a%2Bb%3Dc&state=s%23t%3B1", "s#t;1", "raw-1")
        assertEquals("a+b=c", got?.identityToken)
    }
}
