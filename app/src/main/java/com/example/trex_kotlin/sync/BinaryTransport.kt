package com.example.trex_kotlin.sync

/** 바이트 응답 한 건 — 4xx·5xx 도 응답이다(판정은 부르는 쪽). */
class BinaryResponse(val status: Int, val body: ByteArray)

/**
 * HTTP 한 번 주고받기, 바이트 그대로(§108) — 커뮤니티 사진 올리기(multipart)와 사진 받기(서명 주소).
 * 글자만 오가는 [HttpTransport] 와 짝이고, 연결·읽기 실패는 같은 [NetworkException] 이다.
 * Android 는 HttpURLConnection(연결 10 s·읽기 30 s), iPhone 은 NSURLSession.
 */
fun interface BinaryTransport {
    suspend fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinaryResponse
}
