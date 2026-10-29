package com.example.trex_kotlin.community

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.LruCache
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.rememberBytesImageDecoder
import com.example.trex_kotlin.sync.BinaryTransport
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 받은 사진의 캐시 키 — 서명 주소에서 서명(쿼리)을 뺀 경로(= S3 객체 키)와 읽은 크기.
 * 주소는 피드를 받을 때마다 다시 서명돼 바뀌지만 경로는 같고, 사진을 바꾸면(서버가 새 객체 키를 만든다) 경로가 달라진다.
 * 크기를 넣는 이유: 같은 사진을 작은 썸네일로 읽은 것이 큰 자리에 흐릿하게 다시 쓰이지 않게(리뷰 2026-10-29).
 */
fun remoteImageKey(url: String, maxDimension: Int): String = url.substringBefore('?') + "@" + maxDimension

/**
 * 커뮤니티 원격 사진 — 글 사진·프로필 사진을 받아 그린다(설계 §2 피드·프로필). **앱에 하나** 둔다([ProvideRemoteImages]) —
 * 화면마다 두면 피드에서 받은 사진을 상세 화면이 다시 받다가 만료된 주소로 실패하고, 캐시가 화면 수만큼 곱해진다.
 *
 * 인증 헤더는 붙이지 않는다(서명 주소에 권한이 있다). 만료된 주소의 403·디코딩 실패·전송의 어떤 예외도 그림 실패(null)로 끝나고
 * 캐시에 남지 않는다 — 피드를 새로 받으면 새 주소로 다시 받는다. 같은 키를 여러 카드가 동시에 그려도 한 번만 받는다.
 * 캐시는 [maxBytes] 예산(그림 크기 [sizeOf] 의 합)으로 자른다. [T] 는 그림 타입(앱은 ImageBitmap, 테스트는 문자열).
 */
class RemoteImageLoader<T : Any>(
    private val binary: BinaryTransport,
    private val decode: (ByteArray, Int) -> T?,
    maxBytes: Int = 48 * 1024 * 1024,
    sizeOf: (T) -> Int = { 1 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val cache = LruCache<String, T>(maxBytes, sizeOf)
    private val lock = SynchronizedObject()
    private val inFlight = HashMap<String, Deferred<T?>>()

    fun cached(url: String, maxDimension: Int): T? = cache.get(remoteImageKey(url, maxDimension))

    suspend fun load(url: String, maxDimension: Int): T? {
        val key = remoteImageKey(url, maxDimension)
        cache.get(key)?.let { return it }
        val job = synchronized(lock) {
            inFlight[key] ?: scope.async { fetch(key, url, maxDimension) }.also { started ->
                inFlight[key] = started
                started.invokeOnCompletion { synchronized(lock) { if (inFlight[key] === started) inFlight.remove(key) } }
            }
        }
        return job.await()
    }

    private suspend fun fetch(key: String, url: String, maxDimension: Int): T? {
        val response = try {
            binary.exchange("GET", url, emptyMap(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 네트워크 실패뿐 아니라 어떤 예외도 그림 실패로 — 화면(LaunchedEffect)까지 올라가 앱이 죽지 않게(리뷰 2026-10-29).
            return null
        }
        if (response.status != 200) return null
        val image = runCatching { decode(response.body, maxDimension) }.getOrNull() ?: return null
        cache.put(key, image)
        return image
    }
}

/** 앱 전체의 원격 사진 로더 — 루트에서 [ProvideRemoteImages] 로 넣는다. */
val LocalRemoteImageLoader = staticCompositionLocalOf<RemoteImageLoader<ImageBitmap>?> { null }

/** 앱 루트에서 한 번 감싼다 — 로더(와 그 다운로드 범위)의 수명이 앱 수명과 같아진다. 캐시 예산 48MB(그림 w×h×4 바이트의 합). */
@Composable
fun ProvideRemoteImages(binary: BinaryTransport, content: @Composable () -> Unit) {
    val decoder = rememberBytesImageDecoder()
    val loader = remember(binary, decoder) {
        RemoteImageLoader(binary, { bytes, max -> decoder.decode(bytes, max) }, sizeOf = { it.width * it.height * 4 })
    }
    CompositionLocalProvider(LocalRemoteImageLoader provides loader, content = content)
}

/** 원격 사진 한 장 — 받는 동안 회색 자리, 못 받으면 "사진을 불러오지 못했어요". [maxDimension] 은 그릴 자리의 긴 변(px) 근처로. */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimension: Int = 1080,
) {
    val loader = checkNotNull(LocalRemoteImageLoader.current) { "RemoteImage 는 ProvideRemoteImages 안에서 쓴다" }
    val c = Trex.c
    val key = remoteImageKey(url, maxDimension)
    var image by remember(key) { mutableStateOf(loader.cached(url, maxDimension)) }
    var failed by remember(url, maxDimension) { mutableStateOf(false) }
    LaunchedEffect(url, maxDimension) {
        if (image == null) {
            val loaded = loader.load(url, maxDimension)
            if (loaded != null) image = loaded else failed = true
        }
    }
    val shown = image
    when {
        shown != null -> Image(bitmap = shown, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
        failed -> Box(modifier.background(c.surface2), contentAlignment = Alignment.Center) {
            Text("사진을 불러오지 못했어요", color = c.text3, fontSize = 12.sp)
        }
        else -> Box(modifier.background(c.surface2))
    }
}
