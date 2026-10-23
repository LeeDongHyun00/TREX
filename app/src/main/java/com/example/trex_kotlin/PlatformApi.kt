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

/** 장식 그림의 작은 LRU(개수 기준) — android.util.LruCache 와 같은 의미(꺼내면 최근으로, 넘치면 가장 오래 안 쓴 것부터 버린다). */
internal class AssetImageCache(private val maxSize: Int) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? = synchronized(lock) { entries.remove(key)?.also { entries[key] = it } }

    fun put(key: String, value: ImageBitmap) = synchronized(lock) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maxSize) entries.remove(entries.keys.first())
    }
}

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
