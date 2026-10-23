package com.example.trex_kotlin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.example.trex_kotlin.posture.RepValidation
import com.example.trex_kotlin.posture.isOn
import com.example.trex_kotlin.posture.SpeechCoach
import com.example.trex_kotlin.sync.requestGoogleIdToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * 공유 화면이 쓰는 플랫폼 기능의 Android 구현(§108). 같은 이름·같은 시그니처의 iPhone 짝은 ios-ui/src/iosMain/.../PlatformUi.kt 다.
 * 아래 함수들은 공유 파일에서 옮겨 온 그대로이거나(접힘·회전·화면 켜짐·WebView·GIF) 그 자리의 Android 호출을 감싼 것이다 —
 * Android 동작은 옮기기 전과 같다. 이 파일은 공유 목록(ios-ui/android-sources.txt)에 넣지 않는다.
 */

/** 이 플랫폼에 카메라 자세 화면이 있는가 — [postureSupported] 가 본다. */
const val PLATFORM_POSTURE_CAMERA = true

/** 앱 진입 — 앱 상태는 Application 으로 만든다(공유 [TrexApp] 은 상태를 인자로 받는다). */
@Composable
fun TrexApp() = TrexApp(viewModel { AppViewModel(AndroidAppPlatform(checkNotNull(this[APPLICATION_KEY]))) })

fun Context.findTrexActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 상태·내비게이션 막대 색을 화면 바탕에 맞춘다. */
@Composable
fun TrexSystemBars(c: TrexColors) {
    val context = LocalContext.current
    SideEffect {
        @Suppress("DEPRECATION")
        context.findTrexActivity()?.window?.let { w ->
            w.statusBarColor = c.bg.toArgb()
            w.navigationBarColor = c.bg.toArgb()
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = !c.isDark
                isAppearanceLightNavigationBars = !c.isDark
            }
        }
    }
}

/** 세션 스코프 스피커(spec §30) — TrexApp 이 소유하고 세션을 나갈 때 shutdown 한다. */
@Composable
fun rememberSpeechCoach(): SpeechCoach {
    val context = LocalContext.current
    return remember { SpeechCoach(context) }
}

/** 운동 시작 톤 — 부를 때마다 짧은 확인음(TONE_PROP_ACK 150 ms). */
@Composable
fun rememberStartTone(): () -> Unit {
    val startTone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 50) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { startTone?.release() } }
    return remember(startTone) { { startTone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) } }
}

/** 단조 시계(ms) — SpeechCoach 의 세트 끝 꼬리와 같은 시계여야 한다. */
fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

/** 화면 높이(dp) — 라이브 HUD 의 좁은 높이 배치(§108c 전 `LocalConfiguration.current.screenHeightDp` 그대로). */
@Composable
fun currentScreenHeightDp(): Int = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

/** 시스템 애니메이션이 켜져 있는가 — 꺼져 있으면(개발자 옵션·접근성) 준비 화면의 회전 표시를 멈춘다. */
fun animatorsEnabled(): Boolean = android.animation.ValueAnimator.areAnimatorsEnabled()

/** 렙 검증 모드(spec §61) — 앱 전용 폴더의 표시 파일로 켠다. [key] 가 바뀔 때마다 다시 읽는다. */
@Composable
fun rememberRepValidation(key: Any?): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        on = withContext(Dispatchers.IO) { RepValidation.isOn(context.getExternalFilesDir(null)) }
    }
    return on
}

@Composable
fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

/** 구글 계정 선택(Credential Manager) — 계정 선택 화면은 액티비티가 띄운다. */
@Composable
fun rememberGoogleIdTokenRequest(): suspend (String) -> String {
    val context = LocalContext.current
    return remember(context) { { clientId -> requestGoogleIdToken(context.findTrexActivity() ?: context, clientId) } }
}

@Composable
fun rememberSystemSettingsLinks(): SystemSettingsLinks {
    val context = LocalContext.current
    return remember(context) {
        object : SystemSettingsLinks {
            override fun openNotifications() =
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            override fun openAppDetails() =
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}

@Composable
fun rememberAssetImageDecoder(): AssetImageDecoder {
    val assets = LocalContext.current.applicationContext.assets
    return remember(assets) {
        AssetImageDecoder { path, sampleSize ->
            assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })!!.asImageBitmap() }
        }
    }
}

private val loginAnimationFrames = intArrayOf(
    R.drawable.login_animation_frame_01, R.drawable.login_animation_frame_02,
    R.drawable.login_animation_frame_03, R.drawable.login_animation_frame_04,
    R.drawable.login_animation_frame_05, R.drawable.login_animation_frame_06,
    R.drawable.login_animation_frame_07, R.drawable.login_animation_frame_08,
    R.drawable.login_animation_frame_09, R.drawable.login_animation_frame_10,
    R.drawable.login_animation_frame_11, R.drawable.login_animation_frame_12,
    R.drawable.login_animation_frame_13, R.drawable.login_animation_frame_14,
    R.drawable.login_animation_frame_15, R.drawable.login_animation_frame_16,
)

/** 로그인 로고 애니메이션의 [index] 번째 프레임(720×1280 투명 캔버스). */
@Composable
fun loginAnimationFrame(index: Int): ImageBitmap = ImageBitmap.imageResource(loginAnimationFrames[index])

/** 입력칸이 있는 전체 화면 대화상자 — 인셋은 내용이 직접 맞춘다(Sheets.FoodPicker). */
fun fullScreenDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

/** 소수 한 자리(Locale.US) — String.format("%.1f") 그대로. */
fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)

/** 운동 시범 그림 — GIF 는 움직이는 시범, 그 밖은 정지 이미지. 디코딩은 화면 밖에서 하고, [playing] 이 거짓이면 멈춘다. */
@Composable
internal fun GuideVisualView(asset: String, description: String, playing: Boolean, modifier: Modifier,
    placeholder: @Composable (failed: Boolean) -> Unit) {
    val context = LocalContext.current
    var failed by remember(asset) { mutableStateOf(false) }
    val visual by produceState<GuideVisual?>(null, asset) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(asset).use { stream ->
                    if (asset.endsWith(".gif", ignoreCase = true)) {
                        Movie.decodeStream(stream)?.let { GuideVisual.Animation(it) }
                    } else {
                        BitmapFactory.decodeStream(stream)?.let { GuideVisual.Still(it) }
                    }
                }
            }.getOrNull()
        }
        failed = decoded == null
        value = decoded
    }
    when (val decoded = visual) {
        is GuideVisual.Animation -> AndroidView(
            factory = { ExerciseGifView(it, decoded.movie).apply { contentDescription = description } },
            update = { it.playing = playing },
            modifier = modifier,
        )
        is GuideVisual.Still -> Image(
            bitmap = decoded.bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
        null -> placeholder(failed)
    }
}

/** 테스트에서는 같은 CompositionLocal에 합성 힌지를 전달할 수 있다. */
@Composable
fun rememberTrexFold(): ContentRect? {
    val activity = LocalContext.current.findTrexActivity()
    var fold by remember(activity) { mutableStateOf<ContentRect?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
                    ?.bounds?.let { ContentRect(it.left, it.top, it.right, it.bottom) }
            }
        }
    }
    return fold
}

/** 180도 회전·디스플레이 전환도 configuration 재생성 없이 받는다. */
@Composable
fun rememberTrexDisplayRotation(): Int {
    val view = LocalView.current
    val context = LocalContext.current
    var rotation by remember(view) { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view, context) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { rotation = view.display?.rotation ?: rotation }
            override fun onDisplayRemoved(displayId: Int) { rotation = view.display?.rotation ?: rotation }
            override fun onDisplayChanged(displayId: Int) { rotation = view.display?.rotation ?: rotation }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return rotation
}

/**
 * 운동 세션 동안 화면 방향을 폰을 둔 방향(센서)에 맞춘다(2026-10-10 보고 "바닥 운동할 때 폰을 가로로 돌리는데 화면이 세로 기준이라 보기 힘들다").
 * 원인은 앱이 아니라 폰의 **자동 회전 꺼짐**(`adb shell settings get system accelerometer_rotation` = 0) — 이 앱은 configChanges 로 회전을 받고 가로 배치(`sessionRegions`: 영상 왼쪽·조작부 오른쪽)도 있지만
 * 시스템이 돌리지 않으면 아무것도 안 바뀐다. `SCREEN_ORIENTATION_FULL_SENSOR` 는 사용자의 회전 잠금과 무관하게 센서를 따른다(카메라 앱과 같은 동작). 세션이 끝나면 사용자 설정(UNSPECIFIED)으로 돌아간다.
 * 회전해도 Activity 는 유지되고 `PostureLive` 가 회전값을 다시 읽어 프리뷰·분석 회전을 맞춘다([rememberTrexDisplayRotation]). 세션 밖(목록·기록·완료)은 손대지 않는다.
 */
@Composable
fun SessionOrientation(follow: Boolean) {
    val activity = LocalContext.current.findTrexActivity()
    DisposableEffect(activity, follow) {
        if (follow) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose { if (follow) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = context.findTrexActivity()?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** assets/guid_img 의 공룡 SVG 를 WebView 로 렌더 (복잡한 SVG 라 VectorDrawable 변환 불가). */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GuideSvgImage(assetName: String, modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { context ->
            android.webkit.WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                isClickable = false
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
        },
        update = { webView ->
            if (webView.tag != assetName) {
            webView.tag = assetName
            val html = """
                <!doctype html>
                <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                  html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}
                  body{display:flex;align-items:center;justify-content:center}
                  img{width:100%;height:100%;object-fit:cover;object-position:50% 35%;display:block}
                </style></head>
                <body><img src="$assetName"></body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL("file:///android_asset/guid_img/", html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun MuscleAtlasWebView(command: String, sessionMode: Boolean, description: String, modifier: Modifier) {
    val script by rememberUpdatedState(command)
    AndroidView(modifier=modifier,factory={context -> WebView(context).apply {
        setBackgroundColor(android.graphics.Color.rgb(34,37,39))
        contentDescription=description
        setOnTouchListener { view, event ->
            // 근육도 안에서 시작한 드래그는 회전에 사용하고, 바깥 영역은 페이지 스크롤로 남긴다.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.blockNetworkLoads=true;settings.domStorageEnabled=false
        webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val path=request.url.path.orEmpty().removePrefix("/muscle_load/")
                if(request.url.host!="appassets.androidplatform.net" || path !in setOf("index.html","viewer.js","atlas.js","three.min.js"))
                    return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),null)
                val mime=if(path.endsWith(".html"))"text/html" else "application/javascript"
                return WebResourceResponse(mime,"UTF-8",context.assets.open("muscle_load/$path"))
            }
            override fun onPageFinished(view:WebView,url:String) { view.evaluateJavascript(script,null) }
        }
        loadUrl("https://appassets.androidplatform.net/muscle_load/index.html" + if (sessionMode) "?mode=session" else "")
    }},update={it.evaluateJavascript(script,null)},onRelease={it.stopLoading();it.destroy()})
}

/** 움직이는 시범은 GIF로, 유지 자세는 정지 이미지로 같은 영역에 표시한다. */
private sealed interface GuideVisual {
    data class Animation(val movie: Movie) : GuideVisual
    data class Still(val bitmap: Bitmap) : GuideVisual
}

/** API 26에서도 외부 플레이어 없이 로컬 GIF를 재생한다. 백그라운드에서는 진행하지 않는다. */
@Suppress("DEPRECATION")
private class ExerciseGifView(context: Context, private val movie: Movie) : View(context) {
    private var elapsed = 0L
    private var lastFrame = SystemClock.uptimeMillis()
    var playing = true
        set(value) {
            if (field == value) return
            field = value
            lastFrame = SystemClock.uptimeMillis()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (playing) elapsed += (now - lastFrame).coerceAtMost(100L)
        lastFrame = now
        movie.setTime((elapsed % movie.duration().coerceAtLeast(1)).toInt())
        val scale = minOf(width.toFloat() / movie.width().coerceAtLeast(1), height.toFloat() / movie.height().coerceAtLeast(1))
        canvas.save()
        canvas.translate((width - movie.width() * scale) / 2f, (height - movie.height() * scale) / 2f)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
        if (playing && isShown) postInvalidateOnAnimation()
    }
}
