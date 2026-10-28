package com.example.trex_kotlin.community

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.LruCache
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.rememberBytesImageDecoder
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 커뮤니티 원격 사진 — 글 사진·프로필 사진을 받아 그린다(설계 §2 피드·프로필).
 *
 * 주소는 S3 서명 주소(수명 1시간)라 피드를 새로 받을 때마다 바뀐다 — 그래서 주소가 아니라 **안정 키**(`post:<글 id>:<순서>`,
 * `avatar:<publicId>`)로 캐시한다. 인증 헤더는 붙이지 않는다(서명 주소에 권한이 있다). 만료된 주소의 403 은 그림 실패로 끝나고
 * 캐시에 남지 않는다 — 피드를 새로 받으면 새 주소로 다시 받는다. 같은 키를 여러 카드가 동시에 그려도 한 번만 받는다.
 * [T] 는 그림 타입(앱은 ImageBitmap, 테스트는 문자열) — 디코딩만 플랫폼([com.example.trex_kotlin.BytesImageDecoder]).
 */
class RemoteImageLoader<T : Any>(
    private val binary: BinaryTransport,
    private val decode: (ByteArray, Int) -> T?,
    maxEntries: Int = 40,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val cache = LruCache<String, T>(maxEntries)
    private val lock = SynchronizedObject()
    private val inFlight = HashMap<String, Deferred<T?>>()

    fun cached(key: String): T? = cache.get(key)

    suspend fun load(key: String, url: String, maxDimension: Int): T? {
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
        } catch (e: NetworkException) {
            return null
        }
        if (response.status != 200) return null
        val image = runCatching { decode(response.body, maxDimension) }.getOrNull() ?: return null
        cache.put(key, image)
        return image
    }
}

/** 화면 하나에 로더 하나 — 플랫폼 디코더를 붙인다. */
@Composable
fun rememberRemoteImageLoader(binary: BinaryTransport): RemoteImageLoader<ImageBitmap> {
    val decoder = rememberBytesImageDecoder()
    return remember(binary, decoder) { RemoteImageLoader(binary, { bytes, max -> decoder.decode(bytes, max) }) }
}

/** 원격 사진 한 장 — 받는 동안 회색 자리, 못 받으면 "사진을 불러오지 못했어요". */
@Composable
fun RemoteImage(
    loader: RemoteImageLoader<ImageBitmap>,
    key: String,
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimension: Int = 1080,
) {
    val c = Trex.c
    var image by remember(key) { mutableStateOf(loader.cached(key)) }
    var failed by remember(key, url) { mutableStateOf(false) }
    LaunchedEffect(key, url) {
        if (image == null) {
            val loaded = loader.load(key, url, maxDimension)
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
