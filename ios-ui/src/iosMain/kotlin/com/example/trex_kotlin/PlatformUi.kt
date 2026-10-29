@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package com.example.trex_kotlin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.DialogProperties
import com.example.trex_kotlin.ios.IosSpeechEngine
import com.example.trex_kotlin.ios.readTextFile
import com.example.trex_kotlin.ios.IosAppleSignInRegistry
import com.example.trex_kotlin.ios.requestIosAppleCredential
import com.example.trex_kotlin.ios.requestIosGoogleIdToken
import com.example.trex_kotlin.sync.AppleCredential
import com.example.trex_kotlin.ios.toByteArray
import com.example.trex_kotlin.posture.SpeechCoach
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.accessibilityLabel
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/*
 * 공유 화면이 쓰는 플랫폼 기능의 iPhone 구현(§108 Phase 2) — Android `app/.../PlatformUi.kt` 와 같은 이름·같은 시그니처의 짝이다.
 * 자세 화면의 카메라·추론·권한·확인음·자원은 PostureCameraIos.kt(§108c Phase 3), 소리는 ios/IosAudio.kt.
 * 사진 식단의 TFLite·보관함·카메라는 PhotoFoodIos.kt(§108d). 아직 없는 기능은 없다고 밝힌다: 구글 로그인·앱 내 업데이트(App Store 몫).
 */

/** iPhone 도 카메라 자세 화면을 돈다(§108c) — 공유 PostureLive + Swift 카메라·MediaPipe 다리(PoseCameraBridge.swift). */
const val PLATFORM_POSTURE_CAMERA = true

/** iPhone 시트 관례 — 아래로 끌어 내리면 닫힌다(§108d, SwiftUI 판 `trexSheet` 와 같은 동작). 왼쪽 가장자리 스와이프 뒤로도 시트를 닫는다([PlatformBackHandler]). */
const val PLATFORM_SHEET_DRAG_DISMISS = true

/** 상태 막대 글자색을 화면 테마에 맞춘다(창의 라이트·다크). */
@Composable
fun TrexSystemBars(c: TrexColors) {
    SideEffect {
        @Suppress("DEPRECATION")
        UIApplication.sharedApplication.keyWindow?.overrideUserInterfaceStyle =
            if (c.isDark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
}

/** 세션 스피커 — 공유 정책(SpeechCoach) 위에 AVSpeechSynthesizer 엔진(§108c). */
@Composable
fun rememberSpeechCoach(): SpeechCoach = remember { SpeechCoach(IosSpeechEngine()) }

/** 음성 안내 목소리 안내 — 지금 목소리·받는 길·미리 듣기(§108d 후속, iPhone 만). */
@Composable
fun rememberVoiceAdvisor(): VoiceAdvisor? = remember { com.example.trex_kotlin.ios.IosVoiceAdvisor() }

/** 운동 시작 톤 — 시스템 짧은 확인음. */
@Composable
fun rememberStartTone(): () -> Unit = remember { { AudioServicesPlaySystemSound(1057u) } }

/** 단조 시계(ms, 부팅 뒤 흐른 시간) — iPhone SpeechCoach 와 같은 시계. */
fun elapsedRealtimeMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

/** 화면 높이(dp) — 라이브 HUD 의 좁은 높이 배치. Android `screenHeightDp` 자리(창 높이). */
@Composable
fun currentScreenHeightDp(): Int = with(androidx.compose.ui.platform.LocalDensity.current) {
    androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
}

/** 시스템 애니메이션이 켜져 있는가 — '동작 줄이기' 가 켜져 있으면 준비 화면의 회전 표시를 멈춘다(Android 애니메이터 꺼짐과 같은 자리). */
fun animatorsEnabled(): Boolean = !platform.UIKit.UIAccessibilityIsReduceMotionEnabled()

/** 화면 회전(Surface.ROTATION_* 값) — iPhone 앱은 세로 고정(Info.plist)이라 늘 0. */
@Composable
fun rememberTrexDisplayRotation(): Int = 0

/** 렙 검증 모드(spec §61)는 Android 연구 도구다 — iPhone 에는 없다. */
@Composable
fun rememberRepValidation(key: Any?): Boolean = false

/**
 * 뒤로 — iPhone 은 화면 왼쪽(오른쪽) 가장자리에서 밀어 넣는 스와이프가 Android 시스템 뒤로 자리다(§108d). Compose 공통 BackHandler 에
 * 등록하면 Compose 가 UIKit 가장자리 팬 제스처를 같은 처리기로 보낸다 — 화면·시트·온보딩 단계가 Android 뒤로와 같은 일을 한다.
 * CMP 1.11 에서 deprecated 지만 NavigationEventHandler 와 같은 디스패처에 붙는 얇은 감싸개라 동작은 같다.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.compose.ui.backhandler.BackHandler(enabled, onBack)

/** 구글 ID 토큰 — Swift 다리(`ios/IosGoogleSignIn.kt`, ASWebAuthenticationSession)가 로그인 창을 띄운다. Android 는 Credential Manager. */
@Composable
fun rememberGoogleIdTokenRequest(): suspend (String) -> String =
    remember { { serverClientId: String -> requestIosGoogleIdToken(serverClientId) } }

/**
 * 받은 사진 디코더(Skia) — 긴 변을 [maxDimension] 으로 줄여 그린다(Android 와 같은 크기). 서버는 한 변 4096 까지 받으니
 * 원본 크기로 펼치면 피드가 메모리를 넘긴다(리뷰 2026-10-29). 깨졌으면 null. 원본 Skia 그림은 바로 닫는다.
 */
@Composable
fun rememberBytesImageDecoder(): BytesImageDecoder = remember {
    BytesImageDecoder { bytes, maxDimension ->
        runCatching {
            // Skia 객체는 AutoCloseable 이 아니라 try/finally 로 닫는다. toComposeImageBitmap 은 픽셀을 복사하니 닫아도 된다.
            val source = Image.makeFromEncoded(bytes)
            try {
                val (w, h) = com.example.trex_kotlin.community.UploadSizing.fit(source.width, source.height, maxDimension)
                if (w == source.width && h == source.height) {
                    source.toComposeImageBitmap()
                } else {
                    val surface = Surface.makeRasterN32Premul(w, h)
                    try {
                        surface.canvas.drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()))
                        val snapshot = surface.makeImageSnapshot()
                        try {
                            snapshot.toComposeImageBitmap()
                        } finally {
                            snapshot.close()
                        }
                    } finally {
                        surface.close()
                    }
                }
            } finally {
                source.close()
            }
        }.getOrNull()
    }
}

@Composable
fun rememberSystemSettingsLinks(): SystemSettingsLinks = remember {
    object : SystemSettingsLinks {
        override fun openNotifications() = openAppSettings()
        override fun openAppDetails() = openAppSettings()
    }
}

/** 이 앱의 설정 화면 — iPhone 은 한 번 거절한 권한을 앱이 다시 물을 수 없어 여기로 보낸다. */
internal fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
}

@Composable
fun rememberAssetImageDecoder(): AssetImageDecoder = remember { AssetImageDecoder { path, sampleSize -> decodeBundleImage(path, sampleSize) } }

private val loginFrameLock = SynchronizedObject()
private val loginFrames = mutableMapOf<Int, ImageBitmap>()

/** 로그인 로고 애니메이션의 [index] 번째 프레임 — Android res/drawable-nodpi 의 같은 PNG 를 번들에서 읽는다. */
@Composable
fun loginAnimationFrame(index: Int): ImageBitmap = synchronized(loginFrameLock) {
    loginFrames.getOrPut(index) { decodeBundleImage("drawable-nodpi/login_animation_frame_${(index + 1).toString().padStart(2, '0')}.png", 1) }
}

/** 입력칸이 있는 전체 화면 대화상자 — 인셋은 내용이 직접 맞춘다(Sheets.FoodPicker 의 imePadding). */
fun fullScreenDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)

/** 소수 한 자리 — 반올림은 0.5 에서 올린다(Android String.format 과 같은 자리 수). */
fun formatOneDecimal(value: Float): String {
    val tenths = kotlin.math.round(kotlin.math.abs(value.toDouble()) * 10).toLong()
    return (if (value < 0 && tenths != 0L) "-" else "") + "${tenths / 10}.${tenths % 10}"
}

/** 운동 시범 그림 — GIF 는 WebView 의 <img>(움직이는 시범), 그 밖은 정지 이미지. */
@Composable
internal fun GuideVisualView(asset: String, description: String, playing: Boolean, modifier: Modifier,
    placeholder: @Composable (failed: Boolean) -> Unit) {
    if (asset.endsWith(".gif", ignoreCase = true)) {
        if (readBundleBytes(asset) == null) placeholder(true)
        else BundleHtmlView(
            html = "<img src=\"${asset.substringAfterLast('/')}\" alt=\"$description\" " +
                "style=\"width:100%;height:100%;object-fit:contain;display:block\">",
            baseDirectory = asset.substringBeforeLast('/'), description = description, modifier = modifier,
        )
        return
    }
    var failed by remember(asset) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, asset) {
        value = withContext(Dispatchers.IO) { runCatching { decodeBundleImage(asset, 1) }.getOrNull() }
        failed = value == null
    }
    image?.let { Image(it, contentDescription = description, contentScale = ContentScale.Fit, modifier = modifier) } ?: placeholder(failed)
}

/** assets/guid_img 의 공룡 SVG — Android 와 같은 HTML 을 WebView 로 그린다. */
@Composable
internal fun GuideSvgImage(assetName: String, modifier: Modifier = Modifier) {
    BundleHtmlView(
        html = "<img src=\"$assetName\" style=\"width:100%;height:100%;object-fit:cover;object-position:50% 35%;display:block\">",
        baseDirectory = "guid_img", description = null, modifier = modifier,
    )
}

/** 근육 부하 3D(three.js) — 번들의 muscle_load 를 그대로 띄우고 페이지가 뜬 뒤·바뀔 때 [command] 를 실행한다. 회전 끌기는 WebView 가 모두 가진다. */
@Composable
internal fun MuscleAtlasWebView(command: String, sessionMode: Boolean, description: String, modifier: Modifier) {
    val delegate = remember { ScriptOnLoad(command) }
    delegate.script = command
    UIKitView(
        factory = {
            WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration()).apply {
                setOpaque(false)
                backgroundColor = UIColor(red = 34 / 255.0, green = 37 / 255.0, blue = 39 / 255.0, alpha = 1.0)
                scrollView.scrollEnabled = false
                accessibilityLabel = description
                navigationDelegate = delegate
                val dir = bundlePath("muscle_load")
                loadHTMLString(readTextFile("$dir/index.html").orEmpty(),
                    baseURL = NSURL.URLWithString("file://$dir/index.html" + if (sessionMode) "?mode=session" else ""))
            }
        },
        update = { it.evaluateJavaScript(command, completionHandler = null) },
        onRelease = { it.stopLoading() },
        modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true),
    )
}

private class ScriptOnLoad(var script: String) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        webView.evaluateJavaScript(script, completionHandler = null)
    }
}

/** 투명 바탕 WebView 에 번들 폴더 기준 HTML 한 조각을 그린다(스크롤·터치 없음). */
@Composable
private fun BundleHtmlView(html: String, baseDirectory: String, description: String?, modifier: Modifier) {
    val page = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
        "<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}" +
        "body{display:flex;align-items:center;justify-content:center}</style></head><body>$html</body></html>"
    UIKitView(
        factory = {
            WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration()).apply {
                setOpaque(false)
                backgroundColor = UIColor.clearColor
                scrollView.backgroundColor = UIColor.clearColor
                scrollView.scrollEnabled = false
                userInteractionEnabled = false
                description?.let { accessibilityLabel = it }
                loadHTMLString(page, baseURL = NSURL.fileURLWithPath(bundlePath(baseDirectory) + "/", isDirectory = true))
            }
        },
        modifier = modifier,
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = description != null),
    )
}

/** 화면이 켜진 동안 자동 잠금을 막는다. */
@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    DisposableEffect(enabled) {
        if (enabled) UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { if (enabled) UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

/** 접는 화면은 없다. */
@Composable
fun rememberTrexFold(): ContentRect? = null

/** iPhone 앱은 세로 고정이다(IOS_PORT — 세션 중 방향 추종 없음). */
@Composable
fun SessionOrientation(follow: Boolean) {}

/** 앱 내 업데이트는 Android 체험판 전용이다 — iPhone 은 App Store·TestFlight 가 맡는다. */
@Composable
fun AppUpdatePrompt(enabled: Boolean) {}

// ---- 번들 자산

internal fun bundlePath(relative: String): String = (NSBundle.mainBundle.resourcePath ?: "") + "/" + relative

internal fun readBundleBytes(relative: String): ByteArray? = NSData.dataWithContentsOfFile(bundlePath(relative))?.toByteArray()

/** 번들 그림을 디코딩한다 — [sampleSize] 배로 줄인다(Android inSampleSize 와 같은 크기). */
internal fun decodeBundleImage(relative: String, sampleSize: Int): ImageBitmap {
    val image = Image.makeFromEncoded(checkNotNull(readBundleBytes(relative)) { "번들에 없는 자산: $relative" })
    if (sampleSize <= 1) return image.toComposeImageBitmap()
    val width = (image.width / sampleSize).coerceAtLeast(1)
    val height = (image.height / sampleSize).coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

/** Apple 로그인 한 번(Swift `AppleSignInBridge`) — Android 판은 늘 실패한다(버튼이 없다). */
@Composable
fun rememberAppleCredentialRequest(): suspend () -> AppleCredential = remember { { requestIosAppleCredential() } }

/**
 * Apple 지침의 기본 버튼("Apple로 계속하기", 검정) — Swift 가 만든 `ASAuthorizationAppleIDButton` 을 끼운다. 다리가 없으면(프로브 등)
 * 그리지 않는다. 높이 50dp, 폭은 부모를 채운다.
 */
@Composable
fun AppleSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bridge = IosAppleSignInRegistry.bridge ?: return
    val latest by androidx.compose.runtime.rememberUpdatedState(onClick)
    UIKitView(
        factory = { bridge.makeButton { latest() } },
        modifier = modifier.fillMaxWidth().height(50.dp),
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true),
    )
}

/** 폰 공유 창에 글을 넘긴다(UIActivityViewController) — 가장 위에 떠 있는 화면에 띄운다. iPad 에서는 팝오버 기준을 그 화면으로. */
@Composable
fun rememberTextSharer(): (String) -> Unit = remember {
    { text ->
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        sheet.popoverPresentationController?.sourceView = top?.view
        top?.presentViewController(sheet, animated = true, completion = null)
    }
}
