package com.example.trex_kotlin.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.ThemeMode
import com.example.trex_kotlin.TrexApp
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIViewController
import kotlin.time.TimeSource

/**
 * iPhone 앱 화면(§108 Phase 2) — Android 와 같은 [TrexApp]. 앱 상태는 iPhone 플랫폼 구현([IosAppPlatform]) 위에서 앱 수명 동안 하나다.
 * 키보드는 Compose 의 imePadding 이 맞춘다 — UIKit 이 화면을 한 번 더 올리면 두 번 밀린다(이전 CMP 시도에서 확인).
 */
fun TrexAppViewController(): UIViewController = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
    val app = remember { AppViewModel(IosAppPlatform()) }
    TrexApp(app)
}

/** 개발용 디자인 부품 화면(Phase 0 스파이크). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    var mode by remember { mutableStateOf(ThemeMode.System) }
    ComposeProbe(mode) { mode = it }
}

/**
 * 실기기 확인용(실행 인자 --compose-probe): 라이트·다크 화면을 앱 Documents 에 PNG 로 남긴다.
 * Mac 이 devicectl 로 가져가 Android 화면과 비교한다 — 이 인텔 Mac 은 CMP 시뮬레이터를 못 돌린다.
 */
fun ProbeCaptureViewController(): UIViewController = ComposeUIViewController {
    var mode by remember { mutableStateOf(ThemeMode.Light) }
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }) { ComposeProbe(mode) { mode = it } }
    LaunchedEffect(Unit) {
        val started = TimeSource.Monotonic.markNow()
        for (next in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            mode = next
            delay(1500)
            val png = Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG)?.bytes ?: continue
            writeDocument("compose-probe-${next.name.lowercase()}.png", png)
        }
        writeDocument("compose-probe-done.txt", "captured in ${started.elapsedNow()}\n".encodeToByteArray())
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeDocument(name: String, bytes: ByteArray) {
    val dir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToFile("$dir/$name", atomically = true)
    }
}
