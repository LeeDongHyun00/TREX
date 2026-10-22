package com.example.trex_kotlin.ios

import com.example.trex_kotlin.sync.HttpResponse
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpTransport] 의 iPhone 구현(§108 Phase 2) — NSURLSession. Android(HttpURLConnection)와 같게: 쿠키·캐시를 남기지 않고(임시 세션),
 * 4xx·5xx 도 응답으로 돌려주며(판정은 TrexApi), 연결·읽기 실패만 [NetworkException] 이다. 시간 제한은 유휴 20 s 하나다
 * (NSURLSession 에는 연결 시간 제한이 따로 없다 — Android 는 연결 10 s·읽기 20 s).
 */
object UrlSessionTransport : HttpTransport {
    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.ephemeralSessionConfiguration
        config.timeoutIntervalForRequest = 20.0
        NSURLSession.sessionWithConfiguration(config)
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val address = NSURL.URLWithString(url)
            if (address == null) {
                cont.resumeWithException(NetworkException("서버 주소가 잘못됐습니다: $url"))
                return@suspendCancellableCoroutine
            }
            val request = NSMutableURLRequest.requestWithURL(address)
            request.setHTTPMethod(method)
            headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
            if (body != null) request.setHTTPBody(body.encodeToByteArray().toNSData())
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                if (error != null) {
                    cont.resumeWithException(NetworkException(error.localizedDescription))
                } else {
                    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    cont.resume(HttpResponse(status, data?.toByteArray()?.decodeToString().orEmpty()))
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}
