package com.example.trex_kotlin

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/*
 * 화면이 쓰는 플랫폼 기능의 경계 타입(§108). 같은 이름·같은 시그니처의 구현이 짝으로 있다 — Android `PlatformUi.kt`(공유 목록 밖)와
 * iPhone `ios-ui/src/iosMain/.../PlatformUi.kt`. 한쪽 시그니처를 바꾸면 다른 쪽 빌드가 깨진다(그것이 짝 검사다).
 */

/** 앱 자산 그림을 디코딩한다 — [sampleSize] 는 축소 배율(Android inSampleSize, 2·4). 화면 밖(IO)에서 부른다. */
fun interface AssetImageDecoder {
    fun decode(path: String, sampleSize: Int): ImageBitmap
}

/** 받은 사진 바이트를 디코딩한다 — 긴 변이 [maxDimension] 근처가 되게 줄여 읽고(Android inSampleSize), 깨졌으면 null. 화면 밖에서 부른다. */
fun interface BytesImageDecoder {
    fun decode(bytes: ByteArray, maxDimension: Int): ImageBitmap?
}

/**
 * 작은 LRU — android.util.LruCache 와 같은 의미(꺼내면 최근으로, 넘치면 가장 오래 안 쓴 것부터 버린다).
 * 크기는 [sizeOf] 의 합으로 잰다(기본은 개수). 받은 사진 캐시는 바이트로 재서 개수가 적어도 큰 그림이 메모리를 넘기지 않게 한다.
 */
class LruCache<K : Any, V : Any>(private val maxSize: Int, private val sizeOf: (V) -> Int = { 1 }) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<K, V>()
    private var size = 0

    fun get(key: K): V? = synchronized(lock) { entries.remove(key)?.also { entries[key] = it } }

    fun put(key: K, value: V) = synchronized(lock) {
        entries.remove(key)?.let { size -= sizeOf(it) }
        // 예산보다 큰 하나는 담지 않는다 — 그것 하나 때문에 캐시 전체가 비지 않게
        if (sizeOf(value) > maxSize) return@synchronized
        entries[key] = value
        size += sizeOf(value)
        while (size > maxSize && entries.isNotEmpty()) {
            val eldest = entries.keys.first()
            entries.remove(eldest)?.let { size -= sizeOf(it) }
        }
    }
}

/** 장식 그림의 작은 LRU. */
internal typealias AssetImageCache = LruCache<String, ImageBitmap>

/** 시스템 설정 화면 열기 — Android 는 알림·앱 정보 화면, iPhone 은 둘 다 이 앱의 설정 화면이다. */
interface SystemSettingsLinks {
    fun openNotifications()
    fun openAppDetails()
}

/** 음성 안내 목소리 — [name] 은 화면 표기(예: "유나 · 기본 음질"), [natural] 은 고품질(향상·프리미엄) 음성인가. */
class VoiceStatus(val name: String, val natural: Boolean)

/**
 * 음성 안내 목소리 안내(§108d 후속) — iPhone 만 있다. iPhone 의 기본 품질 한국어 음성은 기계음이 강하고, 고품질 음성은 앱이 받을 수 없어
 * 사용자가 iPhone 설정에서 받는다 — 설정 화면이 지금 목소리와 받는 길을 보여 준다. Android 는 기기 TTS(구글 음성)를 그대로 써서 없다(null).
 */
interface VoiceAdvisor {
    /** 세션 스피커가 지금 쓸 목소리 — 한국어 음성이 없으면 null. */
    fun status(): VoiceStatus?
    /** 그 목소리로 짧은 안내 문장을 들려준다. */
    fun preview()
}
