package com.example.trex_kotlin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.posture.AnalyzerStats
import com.example.trex_kotlin.posture.GravityTracker
import com.example.trex_kotlin.posture.LensInfo
import com.example.trex_kotlin.posture.ModeStore
import com.example.trex_kotlin.posture.PoseModel
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.PostureAnalyzer
import com.example.trex_kotlin.posture.SCREEN_UP
import com.example.trex_kotlin.posture.SetLogStore
import com.example.trex_kotlin.posture.SubjectId
import com.example.trex_kotlin.posture.ThermalMonitor
import com.example.trex_kotlin.posture.Vec3
import com.example.trex_kotlin.posture.gravityUpInWorld
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/*
 * 자세 화면의 Android 플랫폼 쪽(§108c) — `PostureLiveApi.kt` 경계의 구현. 전부 §108c 전 `PostureLive.kt` 에 있던 코드를 그대로 옮겼다
 * (CameraX 바인딩·해상도·회전, MediaPipe 분석기·단일 분석 스레드, 중력·열 센서, ToneGenerator, 카메라 권한, 자산·저장소).
 * 분석 루프 자체는 공유 `PostureLiveSessionScreen` 에 남았다 — iPhone 은 ios-ui 의 같은 이름 함수가 같은 자리를 채운다.
 */

/** ToneGenerator 그대로 — 종류 값은 Android 상수 자체다(RepTone.TONE_PROP_* = ToneGenerator.TONE_PROP_*). */
private class AndroidRepTone(private val generator: ToneGenerator) : RepTone {
    override fun startTone(toneType: Int, durationMs: Int): Boolean = generator.startTone(toneType, durationMs)
    override fun release() = generator.release()
}

/** 짧은 확인음 — 화면 수명. 만들 수 없는 기기면 null(호출부가 runCatching 으로 감싼다). */
@Composable
fun rememberRepTone(volume: Int): RepTone? {
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.getOrNull()?.let { AndroidRepTone(it) } }
    DisposableEffect(tone) { onDispose { runCatching { tone?.release() } } }
    return tone
}

/** 카메라 권한 — 없으면 처음 컴포지션에 한 번 묻는다. */
@Composable
fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    val permission = remember {
        CameraPermission(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission.granted = it
        permission.asked = true
    }
    LaunchedEffect(Unit) { if (!permission.granted) launcher.launch(Manifest.permission.CAMERA) }
    return permission
}

private class AndroidPostureHost(private val context: Context) : PostureHost {
    override fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
    override fun profileHeightCm(): Float? = TrexStore(context).loadProfile()?.heightCm?.toFloat()
    override fun modeStore(): ModeStore = ModeStore(context)
    override fun setLogStore(): SetLogStore = SetLogStore(context)
    override fun subjectId(): String = SubjectId.get(context)
    @Suppress("DEPRECATION")
    override fun appVersion(): String? = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
}

@Composable
fun rememberPostureHost(): PostureHost {
    val context = LocalContext.current
    return remember { AndroidPostureHost(context) }
}

@Composable
fun rememberPoseCamera(): PoseCamera {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember { AndroidPoseCamera(context, lifecycleOwner) }
}

private class CameraXFrame(val image: ImageProxy) : PoseFrame {
    override fun close() = image.close()
}

/**
 * CameraX + MediaPipe(§2·§94). 분석기는 단일 분석 스레드에서 만들고 돌린다(GPU 컨텍스트가 생성 스레드에 묶인다).
 * 닫기는 [stop] 이 표시만 하고 실제 해제는 분석 스레드에 줄 세운다 — 메인에서 닫으면 첫 프레임의 모델 준비(GPU, 수 초)를 기다리며 화면이 멈췄다(검토 2026-09-26).
 */
private class AndroidPoseCamera(private val context: Context, private val lifecycleOwner: LifecycleOwner) : PoseCamera {
    private val analyzer = PostureAnalyzer(context, PoseModel.FULL, preferGpu = true)
    private val executor = Executors.newSingleThreadExecutor()
    private val thermal = ThermalMonitor(context)
    private val gravity = GravityTracker(context)
    // FIT_CENTER: 카메라가 보는 **전체**를 보여준다. FILL_CENTER 는 4:3 영상을 긴 화면에 채우느라
    // 좌우(세로 모드) 또는 상하(가로 모드)를 잘라내, 사용자가 실제 분석 범위보다 좁게 보고 프레이밍을 그르쳤다.
    private val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    // ImageAnalysis 는 바인딩 시점의 회전값을 갖고 있어, 회전 후에는 직접 갱신해야 이미지가 바로 선다.
    private var rotation = android.view.Surface.ROTATION_0
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

    override var faceEnabled: Boolean
        get() = analyzer.faceEnabled
        set(value) { analyzer.faceEnabled = value }

    // 렌즈 사양은 세트 로그의 배치 지문(§91)에 들어간다 — 카메라를 바꾸면 다시 읽는다
    @Volatile override var lens: LensInfo? = null
        private set

    override val thermalSupported: Boolean get() = thermal.supported

    override fun setDisplayRotation(rotation: Int) {
        this.rotation = rotation
        analysisUseCase?.targetRotation = rotation
        previewUseCase?.targetRotation = rotation
    }

    override suspend fun stream(front: Boolean, label: String, onFrame: (PoseFrame) -> Unit) {
        val provider = context.awaitCameraProviderLive()
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(previewSelector).setTargetRotation(rotation).build()
        previewUseCase = preview
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSelector)
            .setTargetRotation(rotation)
            .build()
        analysisUseCase = analysis   // 회전 시 targetRotation 갱신용
        analysis.setAnalyzer(executor) { image -> onFrame(CameraXFrame(image)) }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            lens = readLensInfo(provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis))
            android.util.Log.d("TrexCamera", "bind $label")
            kotlinx.coroutines.awaitCancellation()
        } finally {
            analysis.clearAnalyzer()
            provider.unbind(preview, analysis)
        }
    }

    override fun analyze(frame: PoseFrame, now: Long, up: Vec3): PoseSample = analyzer.analyze((frame as CameraXFrame).image, now, up)

    override fun gravityUp(rotation: Int, front: Boolean): Vec3 =
        gravity.gravityDevice
            ?.let { gravityUpInWorld(it, rotation, front) }
            ?: SCREEN_UP

    override fun stats(): AnalyzerStats = analyzer.stats()

    override fun start(onThermal: (Int) -> Unit) {
        gravity.start()
        thermal.start(ContextCompat.getMainExecutor(context), onThermal)
    }

    override fun stop() {
        gravity.stop()
        thermal.stop()
        // 분석기 닫기는 분석 스레드에서 — 진행 중 추론 뒤로 줄 세운다. 메인에서 닫으면 첫 프레임의 모델 준비(GPU, 수 초)를 기다리며 화면이 멈췄다(검토 2026-09-26)
        analyzer.markClosed()
        runCatching { executor.execute { analyzer.close() } }.onFailure { analyzer.close() }
        executor.shutdown()
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        AndroidView(factory = { previewView }, modifier = modifier)
    }
}

private suspend fun Context.awaitCameraProviderLive(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
