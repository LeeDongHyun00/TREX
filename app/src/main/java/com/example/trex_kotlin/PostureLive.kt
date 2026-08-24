package com.example.trex_kotlin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.posture.AnalyzerStats
import com.example.trex_kotlin.posture.CoachEvent
import com.example.trex_kotlin.posture.FeatureAggregator
import com.example.trex_kotlin.posture.GravityTracker
import com.example.trex_kotlin.posture.InferencePhase
import com.example.trex_kotlin.posture.InferencePolicy
import com.example.trex_kotlin.posture.LiveCoach
import com.example.trex_kotlin.posture.MP_LANDMARK_COUNT
import com.example.trex_kotlin.posture.POSE_CONNECTIONS
import com.example.trex_kotlin.posture.PoseModel
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.PostureAnalyzer
import com.example.trex_kotlin.posture.PostureRuleSet
import com.example.trex_kotlin.posture.SCREEN_UP
import com.example.trex_kotlin.posture.SpeechCoach
import com.example.trex_kotlin.posture.ThermalMonitor
import com.example.trex_kotlin.posture.Verdict
import com.example.trex_kotlin.posture.gravityUpInWorld
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 실시간 자세 평가 세션 (실 엔진).
 *
 * PostureLabScreen(개발용)과 같은 파이프라인 — CameraX 640×480 분석 스트림 → 열/단계 인지
 * 스케줄러(300ms) → MediaPipe PoseLandmarker(GPU 폴백 CPU) → IMU 중력축 체간 좌표계 피처
 * → rules_mp_v0 규칙 평가 → LiveCoach 가 '처음부터/점점 흐트러짐/교정됨' 을 판별해
 * 화면 카드와 음성으로 안내한다. 시뮬레이션이던 세션 자세 평가의 실제 구현이다.
 */

/** 앱 운동명 → AIHub 규칙 종목 매핑 — 여기 있는 운동만 자세 교정을 켤 수 있다. */
val postureExerciseMap: Map<String, String> = mapOf(
    "기본 스쿼트" to "바벨 스쿼트",
    "바벨 스쿼트" to "바벨 스쿼트",
    "런지" to "스텝 포워드 다이나믹 런지",
    "바벨 런지" to "바벨 런지",
    "사이드 런지" to "사이드 런지",
    "크로스 런지" to "크로스 런지",
    "바벨 데드리프트" to "바벨 데드리프트",
    "굿모닝" to "굿모닝",
    "딥스" to "딥스",
    "오버헤드 프레스" to "오버 헤드 프레스",
    "덤벨 컬" to "덤벨 컬",
    "바벨 컬" to "바벨 컬",
    "사이드 레터럴 레이즈" to "사이드 레터럴 레이즈",
    "프런트 레이즈" to "프런트 레이즈",
    "랫풀 다운" to "랫풀 다운",
    "업라이트로우" to "업라이트로우",
    "스탠딩 사이드 크런치" to "스탠딩 사이드 크런치",
    "스탠딩 니업" to "스탠딩 니업",
    "행잉 레그 레이즈" to "행잉 레그 레이즈",
)

fun Workout.postureSupported(): Boolean = postureExerciseMap.containsKey(name)

@Composable
fun PostureLiveSessionScreen(
    workout: Workout,
    index: Int,
    total: Int,
    timeLeft: Int,
    totalSeconds: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val c = Trex.c
    KeepScreenOn()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val aihubExercise = postureExerciseMap[workout.name] ?: workout.name

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(granted) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        if (asked) {
            Column(
                Modifier.fillMaxSize().background(c.bg).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(82.dp).clip(CircleShape).background(c.surface),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = c.warn, modifier = Modifier.size(34.dp)) }
                Text("카메라 권한이 필요해룡", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                Text(
                    "자세 평가 없이 타이머로 이어가려면 계속을 눌러주세요.",
                    color = c.text2, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Cta("타이머로 계속", onClick = onNext, modifier = Modifier.padding(top = 28.dp).fillMaxWidth())
                GhostButton("나가기", onClick = onExit, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
            }
        }
        return
    }

    // ---- 실 엔진 파이프라인
    var ruleSet by remember { mutableStateOf<PostureRuleSet?>(null) }
    LaunchedEffect(Unit) {
        runCatching { PostureRuleSet.load(context) }.onSuccess { ruleSet = it }
    }
    val speech = remember { SpeechCoach(context) }
    DisposableEffect(speech) { onDispose { speech.shutdown() } }
    var muted by remember { mutableStateOf(false) }
    LaunchedEffect(muted) { speech.muted = muted }

    val analyzer = remember { PostureAnalyzer(context, PoseModel.FULL, preferGpu = true) }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val policy = remember { InferencePolicy(sampleIntervalMs = 300L) }
    val lastInferAt = remember { longArrayOf(0L) }
    val thermal = remember { ThermalMonitor(context) }
    val thermalRef = remember { intArrayOf(InferencePolicy.THERMAL_NONE) }
    val gravity = remember { GravityTracker(context) }
    DisposableEffect(Unit) {
        gravity.start()
        thermal.start(ContextCompat.getMainExecutor(context)) { s -> thermalRef[0] = s }
        onDispose {
            gravity.stop()
            thermal.stop()
            executor.shutdown()
        }
    }
    val displayRotation = remember(context) {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

    var useFrontCamera by remember { mutableStateOf(true) }
    val frontRef = remember { booleanArrayOf(true) }
    frontRef[0] = useFrontCamera
    val pausedRef = remember { booleanArrayOf(false) }
    pausedRef[0] = paused

    var sample by remember { mutableStateOf(PoseSample.empty()) }
    var stats by remember { mutableStateOf<AnalyzerStats?>(null) }
    var everDetected by remember { mutableStateOf(false) }
    var coachBanner by remember { mutableStateOf<CoachEvent?>(null) }
    var scorePct by remember { mutableStateOf<Int?>(null) }
    val coachRef = remember { arrayOfNulls<LiveCoach>(1) }
    LaunchedEffect(ruleSet, workout.id) {
        val rs = ruleSet ?: return@LaunchedEffect
        coachRef[0] = LiveCoach(rs, aihubExercise)
        if (!muted) speech.speak("${workout.name} 자세 평가를 시작합니다. 전신이 화면에 들어오게 서 주세요")
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(useFrontCamera) {
        val provider = context.awaitCameraProviderLive()
        provider.unbindAll()
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSelector)
            .build()
        analysis.setAnalyzer(executor) { image ->
            val now = System.currentTimeMillis()
            val phase = if (pausedRef[0]) InferencePhase.IDLE else InferencePhase.RECORDING
            if (!policy.shouldInfer(now, lastInferAt[0], phase, thermalRef[0])) {
                image.close()
                return@setAnalyzer
            }
            lastInferAt[0] = now
            try {
                val up = gravity.gravityDevice
                    ?.let { gravityUpInWorld(it, displayRotation, frontRef[0]) }
                    ?: SCREEN_UP
                val s = analyzer.analyze(image, now, up)
                sample = s
                stats = analyzer.stats()
                if (s.detected) everDetected = true
                if (s.detected && !pausedRef[0]) {
                    coachRef[0]?.let { coach ->
                        coach.onFrame(s.features)
                        val ev = coach.evaluate(now)
                        if (ev != null) {
                            coachBanner = ev
                            speech.speak(ev.message)
                        }
                        val states = coach.lastStates
                        val ok = states.count { it.recent == Verdict.OK }
                        val bad = states.count { it.recent == Verdict.VIOLATION }
                        if (ok + bad > 0) scorePct = 100 * ok / (ok + bad)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis) }
    }

    // ---- UI: 풀블리드 카메라 + 플로팅 글래스 패널 (애플 피트니스 스타일)
    val glass = if (c.isDark) Color(0xE61B2115) else Color(0xF2FFFFFF)
    val onCam = Color.White

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        LivePoseOverlay(sample = sample, mirror = useFrontCamera, tint = c.lime, modifier = Modifier.fillMaxSize())

        // 상단 스크림 + 컨트롤
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))),
        )
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(Icons.Rounded.Close, contentDescription = "종료", onClick = onExit)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    "진행중 · ${index + 1}/$total",
                    color = onCam.copy(alpha = 0.75f), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                )
                Text(workout.name, color = onCam, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
            GlassIcon(Icons.Rounded.Cameraswitch, contentDescription = "카메라 전환", onClick = { useFrontCamera = !useFrontCamera })
        }

        // 인식 상태 칩
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 64.dp, end = 16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x8C0C1008))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pulse by animateFloatAsState(if (paused || !sample.detected) 0.35f else 1f, tween(500), label = "live-pulse")
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.lime.copy(alpha = pulse)))
            Text(
                text = when {
                    paused -> "일시정지"
                    !analyzer.isReady && stats?.error != null -> "엔진 오류"
                    sample.detected -> "자세 인식 중 · ${sample.visibleJointCount}/33"
                    everDetected -> "관절 이탈 — 프레임 안으로"
                    else -> "전신을 화면에 맞춰주세요"
                },
                color = onCam, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        // 하단 글래스 패널
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = glass,
                contentColor = c.text,
                border = BorderStroke(1.dp, c.line),
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("바로 보고, 바로 고치고", color = c.primaryText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                    AnimatedContent(
                        targetState = coachBanner?.message
                            ?: if (sample.detected) "좋아요, 자세를 유지해 주세요" else "전신과 주요 관절이 보이면 평가를 시작해룡",
                        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                        label = "coach-cue",
                    ) { msg ->
                        Text(
                            msg,
                            fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }

                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("남은 시간", color = c.text3, fontSize = 10.5.sp)
                            Text(timeLeft.asClock(), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 18.dp)) {
                            Text("자세 점수", color = c.text3, fontSize = 10.5.sp)
                            Text(
                                scorePct?.let { "$it%" } ?: "—",
                                color = when {
                                    scorePct == null -> c.text3
                                    scorePct!! >= 85 -> c.primaryText
                                    else -> c.warn
                                },
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("목표", color = c.text3, fontSize = 10.5.sp)
                            Text(workout.reps, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    Box(Modifier.padding(top = 12.dp)) {
                        TrackBar(progress = 1f - (timeLeft / totalSeconds.toFloat()), height = 6.dp)
                    }

                    Row(
                        Modifier.padding(top = 14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    ) {
                        RoundIcon(
                            if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            onClick = { muted = !muted },
                            size = 48.dp,
                            contentDescription = "음성 안내",
                        )
                        Surface(
                            onClick = onTogglePause,
                            modifier = Modifier.size(62.dp),
                            shape = CircleShape,
                            color = c.primary,
                            contentColor = Color.White,
                            shadowElevation = 8.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = "일시정지", modifier = Modifier.size(25.dp))
                            }
                        }
                        RoundIcon(Icons.Rounded.Check, onClick = onNext, size = 48.dp, contentDescription = "완료")
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = Color(0x660C1008),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun LivePoseOverlay(sample: PoseSample, mirror: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (!sample.detected || sample.imageWidth <= 0) return@Canvas
        val imgW = sample.imageWidth.toFloat()
        val imgH = sample.imageHeight.toFloat()
        val scale = maxOf(size.width / imgW, size.height / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val dx = (size.width - drawW) / 2f
        val dy = (size.height - drawH) / 2f

        fun point(i: Int): Offset {
            val px = dx + sample.normalizedXy[i * 2] * drawW
            val py = dy + sample.normalizedXy[i * 2 + 1] * drawH
            return Offset(if (mirror) size.width - px else px, py)
        }

        POSE_CONNECTIONS.forEach { (a, b) ->
            if (sample.visibility[a] >= 0.5f && sample.visibility[b] >= 0.5f) {
                drawLine(
                    color = tint.copy(alpha = 0.55f),
                    start = point(a),
                    end = point(b),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        for (i in 0 until MP_LANDMARK_COUNT) {
            if (sample.visibility[i] < 0.5f) continue
            drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 4f, center = point(i))
        }
    }
}

private suspend fun Context.awaitCameraProviderLive(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
