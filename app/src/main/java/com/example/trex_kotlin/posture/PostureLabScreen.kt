package com.example.trex_kotlin.posture

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.TrexButton
import com.example.trex_kotlin.TrexDark
import com.example.trex_kotlin.TrexDarkAlt
import com.example.trex_kotlin.TrexError
import com.example.trex_kotlin.TrexLime
import com.example.trex_kotlin.TrexWarning
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.core.content.ContextCompat

/**
 * 자세 교정 실험실 — 연구 결과(rules_mp_v0)를 실제 카메라에서 검증하는 화면.
 *
 * 흐름: 종목 선택 → 권장 뷰로 폰 거치 → 세트 시작 → 3.3fps 로 피처 샘플링 → 세트 종료 → 규칙 판정 리포트.
 * 임계값은 AIHub 스튜디오 분포 기준이므로, 여기서 나온 값이 곧 재보정 데이터가 된다(spec §9).
 */
private const val SAMPLE_INTERVAL_MS = 300L
private const val MIN_FRAMES_FOR_VERDICT = 8

private enum class LabPhase { IDLE, RECORDING, RESULT }

@Composable
fun PostureLabScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var ruleSet by remember { mutableStateOf<PostureRuleSet?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            ruleSet = PostureRuleSet.load(context)
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
        }
    }

    var exercise by remember { mutableStateOf("바벨 스쿼트") }
    var useFrontCamera by remember { mutableStateOf(false) }
    var includeBeta by remember { mutableStateOf(true) }

    var sample by remember { mutableStateOf(PoseSample.empty()) }
    var phase by remember { mutableStateOf(LabPhase.IDLE) }
    var sampledFrames by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<RuleResult>>(emptyList()) }
    var analyzerError by remember { mutableStateOf<String?>(null) }

    // ---- 세트 로그(재보정 데이터, spec §14): 기록 구간의 프레임 샘플을 모아 세트 종료 시 JSONL 로 남긴다.
    //      recordedSamples 는 분석 스레드에서 추가되므로 synchronized 로 접근한다.
    var saveLogs by rememberSaveable { mutableStateOf(true) }
    val recordedSamples = remember { ArrayList<PoseSample>() }
    val recordedTimesMs = remember { ArrayList<Long>() }
    val logStore = remember { SetLogStore(context) }
    var savedSets by remember { mutableIntStateOf(0) }
    var lastSavedNote by remember { mutableStateOf<String?>(null) }
    // 개인 기준선 저장소 (BaselineGuideScreen 이 기록, 여기서는 읽기만)
    val baselineStore = remember { BaselineStore(context) }

    // ---- 실시간 코칭 (PostureCoach): 초반 창 vs 최근 창 → '처음부터' / '점점 흐트러짐' 을 음성으로. 분석 스레드에서 갱신.
    var voiceCoach by rememberSaveable { mutableStateOf(true) }
    val speech = remember { SpeechCoach(context) }
    DisposableEffect(speech) { onDispose { speech.shutdown() } }
    val coachRef = remember { arrayOfNulls<LiveCoach>(1) }
    var coachBanner by remember { mutableStateOf<CoachEvent?>(null) }
    var coachStates by remember { mutableStateOf<List<OnsetState>>(emptyList()) }
    var onsetSummary by remember { mutableStateOf<List<OnsetState>>(emptyList()) }

    val aggregator = remember { FeatureAggregator() }
    val phaseRef = remember { arrayOf(LabPhase.IDLE) }
    phaseRef[0] = phase

    // ---- 엔진 선택 (발열 대응): 모델 full/lite, GPU 우선/CPU. 바꾸면 랜드마커를 새로 만든다.
    var poseModel by rememberSaveable { mutableStateOf(PoseModel.FULL) }
    var preferGpu by rememberSaveable { mutableStateOf(true) }
    val analyzer = remember(poseModel, preferGpu) { PostureAnalyzer(context, poseModel, preferGpu) }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    var stats by remember { mutableStateOf<AnalyzerStats?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    // ---- 추론 스케줄러: 단계별 간격 × 열 상태 배수. 쉬지 않는 추론이 발열의 1차 원인이었다.
    val policy = remember { InferencePolicy(sampleIntervalMs = SAMPLE_INTERVAL_MS) }
    val lastInferAt = remember { longArrayOf(0L) }
    val thermal = remember { ThermalMonitor(context) }
    var thermalStatus by remember { mutableIntStateOf(InferencePolicy.THERMAL_NONE) }
    val thermalRef = remember { intArrayOf(InferencePolicy.THERMAL_NONE) }
    // 듀티(추론에 쓴 시간 비율) 측정용: [busyNanos, windowStartNanos]
    val dutyAcc = remember { longArrayOf(0L, 0L) }
    var dutyPct by remember { mutableIntStateOf(0) }

    // IMU 중력축으로 up 을 잡는다 (폰이 기울어도 높이/수직 피처가 유지됨)
    val gravity = remember { GravityTracker(context) }
    DisposableEffect(Unit) {
        gravity.start()
        executor.execute { savedSets = logStore.totalSets() }   // 파일 IO 는 분석 스레드에서
        thermal.start(ContextCompat.getMainExecutor(context)) { s ->
            thermalStatus = s
            thermalRef[0] = s
        }
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
    // 분석 스레드에서 매 프레임 읽으므로 상태가 아닌 참조로 전달
    val frontRef = remember { booleanArrayOf(false) }
    frontRef[0] = useFrontCamera

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(granted, useFrontCamera, analyzer) {
        if (!granted) return@LaunchedEffect
        val provider = context.awaitCameraProvider()
        provider.unbindAll()
        // 프리뷰와 분석의 종횡비를 4:3 으로 맞춰야 골격 오버레이가 화면과 정렬된다.
        // 프리뷰는 1280×960 까지만 (화면 폭 1080 에 충분) — 더 큰 스트림은 GPU/메모리 대역폭만 쓴다.
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        // 분석 스트림은 640×480 (센서 방향 표기). 모델 입력은 256px 라 그 이상은 변환·회전 비용만 늘린다.
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        // 출력 포맷은 기본 YUV: RGBA 로 받으면 CameraX 가 '전달되는 모든 프레임'(30fps)을 CPU 변환한다.
        // 우리는 스케줄러가 고른 프레임만 변환한다.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSelector)
            .build()
        analysis.setAnalyzer(executor) { image ->
            val now = System.currentTimeMillis()
            val ph = when (phaseRef[0]) {
                LabPhase.IDLE -> InferencePhase.IDLE
                LabPhase.RECORDING -> InferencePhase.RECORDING
                LabPhase.RESULT -> InferencePhase.RESULT
            }
            // 스케줄러: 간격이 안 됐으면 프레임을 즉시 반환 (변환·추론 비용 0)
            if (!policy.shouldInfer(now, lastInferAt[0], ph, thermalRef[0])) {
                image.close()
                return@setAnalyzer
            }
            lastInferAt[0] = now
            val t0 = System.nanoTime()
            try {
                val up = gravity.gravityDevice
                    ?.let { gravityUpInWorld(it, displayRotation, frontRef[0]) }
                    ?: SCREEN_UP
                val s = analyzer.analyze(image, now, up)
                sample = s
                stats = analyzer.stats()
                // RECORDING 에서는 추론 1회 = 샘플 1개 (간격이 곧 샘플 간격)
                if (phaseRef[0] == LabPhase.RECORDING && s.detected) {
                    aggregator.add(s.features)
                    sampledFrames = aggregator.frameCount
                    synchronized(recordedSamples) {
                        recordedSamples.add(s)
                        recordedTimesMs.add(now)
                    }
                    // 실시간 코칭: 프레임 누적 → 최근 창 평가 → 말할 이벤트가 있으면 배너 + 음성
                    coachRef[0]?.let { coach ->
                        coach.onFrame(s.features)
                        val ev = coach.evaluate(now)
                        coachStates = coach.lastStates
                        if (ev != null) {
                            coachBanner = ev
                            if (voiceCoach) speech.speak(ev.message)
                        }
                    }
                }
            } catch (t: Throwable) {
                analyzerError = t.message
            } finally {
                image.close()
                // 듀티 = 2초 창에서 추론(변환+모델+피처)에 쓴 시간 비율
                val t1 = System.nanoTime()
                if (dutyAcc[1] == 0L) dutyAcc[1] = t0
                dutyAcc[0] += (t1 - t0)
                val window = t1 - dutyAcc[1]
                if (window >= 2_000_000_000L) {
                    dutyPct = (100L * dutyAcc[0] / window).toInt().coerceIn(0, 100)
                    dutyAcc[0] = 0L
                    dutyAcc[1] = t1
                }
            }
        }
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (t: Throwable) {
            analyzerError = "카메라 바인딩 실패: ${t.message}"
        }
    }

    val activeRules = ruleSet?.rulesFor(exercise, includeBeta).orEmpty()
    val recommendedViews = activeRules.map { it.view }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        .joinToString(", ") { "${it.key}(${it.value})" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        // ---------- 카메라 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.52f)
                .background(Color.Black),
        ) {
            if (granted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                PoseOverlay(sample = sample, mirror = useFrontCamera, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("카메라 권한이 필요합니다", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    TrexButton("권한 허용", onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("자세 교정 실험실", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = ruleSet?.let { "규칙 ${it.version} · ${it.generated}" } ?: (loadError ?: "규칙 로딩 중…"),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
                LabIconButton(Icons.Rounded.Cameraswitch, onClick = { useFrontCamera = !useFrontCamera })
            }

            // 검출 상태 배지
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = buildString {
                        append(if (sample.detected) "검출 O" else "검출 X")
                        append("  가시 ${sample.visibleJointCount}/33")
                        append("  ${sample.inferMs}ms")
                        if (sample.imageWidth > 0) append("  ${sample.imageWidth}×${sample.imageHeight}")
                        append(
                            if (sample.upFromGravity) {
                                "  중력축 기울기 ${"%.0f".format(tiltFromScreenUpDegrees(sample.up))}°"
                            } else {
                                "  중력센서 X(화면축 가정)"
                            },
                        )
                        if (sample.upFlipped) append("  ⚠ up반전 자동보정")
                        else if (sample.detected && !sample.upVerified) append("  (up 미검증)")
                    },
                    color = if (sample.detected) TrexLime else TrexError,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }

        // ---------- 컨트롤 + 결과 ----------
        Column(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxWidth()
                .background(TrexDark)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(12.dp))
            ExercisePicker(
                exercises = ruleSet?.exercises.orEmpty(),
                selected = exercise,
                onSelect = {
                    exercise = it
                    phase = LabPhase.IDLE
                    aggregator.reset()
                    sampledFrames = 0
                    results = emptyList()
                    synchronized(recordedSamples) { recordedSamples.clear(); recordedTimesMs.clear() }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "규칙 ${activeRules.size}개" + if (recommendedViews.isNotEmpty()) " · 권장 뷰 $recommendedViews" else "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (includeBeta) "beta 포함" else "ship만",
                    color = if (includeBeta) TrexWarning else TrexLime,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { includeBeta = !includeBeta }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // ---- 엔진 상태 + 발열 대응 토글 (모델 full/lite, GPU 우선/CPU)
            val st = stats
            val intervalNow = policy.intervalMs(
                when (phase) { LabPhase.IDLE -> InferencePhase.IDLE; LabPhase.RECORDING -> InferencePhase.RECORDING; LabPhase.RESULT -> InferencePhase.RESULT },
                thermalStatus,
            )
            val thermalMul = policy.thermalMultiplier(thermalStatus)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = buildString {
                        append("엔진 ")
                        append(st?.let { "${it.model}·${it.delegate}" } ?: "${poseModel.label}·대기")
                        append(" · 간격 ${intervalNow}ms")
                        st?.takeIf { it.inferCount > 0 }?.let { append(" · 추론 ${"%.0f".format(it.emaInferMs)}ms") }
                        append(" · 듀티 $dutyPct%")
                        append(" · 열 ${InferencePolicy.thermalLabel(thermalStatus)}")
                        if (thermalMul > 1f) append("(×${"%.1f".format(thermalMul)} 감속)")
                    },
                    color = if (thermalStatus >= InferencePolicy.THERMAL_MODERATE) TrexWarning else Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = poseModel.label,
                    color = TrexLime,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { poseModel = if (poseModel == PoseModel.FULL) PoseModel.LITE else PoseModel.FULL }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = if (preferGpu) "GPU" else "CPU",
                    color = TrexLime,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { preferGpu = !preferGpu }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            st?.error?.let { Text(it, color = TrexError, fontSize = 10.sp) }
            Text(
                text = "정면~전방 45°에서 촬영 · 전신이 프레임에 들어오게" +
                    if (sample.upFromGravity) " · 높이 축은 IMU 중력축 사용" else " · 중력센서 없음: 폰을 세로로 세워 거치",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                when (phase) {
                    LabPhase.IDLE -> TrexButton(
                        text = "세트 시작",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = {
                            aggregator.reset()
                            sampledFrames = 0
                            results = emptyList()
                            lastSavedNote = null
                            synchronized(recordedSamples) { recordedSamples.clear(); recordedTimesMs.clear() }
                            // 실시간 코치 준비 (이 세트의 종목·규칙·기준선로 고정)
                            coachBanner = null
                            coachStates = emptyList()
                            onsetSummary = emptyList()
                            coachRef[0] = ruleSet?.let {
                                LiveCoach(it, exercise, includeBeta, baselineStore.load().valuesFor(exercise), minFrames = MIN_FRAMES_FOR_VERDICT)
                            }
                            lastInferAt[0] = 0L   // 세트 시작 즉시 첫 샘플
                            phase = LabPhase.RECORDING
                        },
                        modifier = Modifier.weight(1f),
                        enabled = granted && ruleSet != null && activeRules.isNotEmpty(),
                    )

                    LabPhase.RECORDING -> TrexButton(
                        text = "세트 종료 ($sampledFrames 프레임)",
                        icon = Icons.Rounded.Stop,
                        onClick = {
                            // 개인 기준선(BaselineGuideScreen 에서 설정)이 있으면 eligible 규칙은 (값 − 기준선) 으로 판정
                            val baselineValues = baselineStore.load().valuesFor(exercise)
                            val evaluated = ruleSet?.evaluate(exercise, aggregator, includeBeta, MIN_FRAMES_FOR_VERDICT, baselineValues).orEmpty()
                            results = evaluated
                            // 세트 요약: 규칙별로 '처음부터 / 점점 흐트러짐 / 교정됨' (전반 창 vs 후반 창)
                            onsetSummary = coachRef[0]?.summarize().orEmpty()
                            coachRef[0] = null
                            speech.stop()
                            phase = LabPhase.RESULT
                            // 세트 로그 저장 (spec §14-1): 프레임 샘플 원본 + 판정 → JSONL. 파일 IO 는 분석 스레드에서.
                            if (saveLogs) {
                                val samples = synchronized(recordedSamples) { ArrayList(recordedSamples) }
                                val times = synchronized(recordedSamples) { ArrayList(recordedTimesMs) }
                                val t0 = times.firstOrNull() ?: 0L
                                val log = SetLog.build(
                                    exercise = exercise,
                                    samples = samples,
                                    results = evaluated,
                                    rulesVersion = ruleSet?.version ?: "",
                                    model = poseModel.label,
                                    delegate = stats?.delegate ?: "-",
                                    frontCamera = useFrontCamera,
                                    sampleIntervalMs = SAMPLE_INTERVAL_MS,
                                    sampleTimesMs = times.map { it - t0 },
                                )
                                executor.execute {
                                    try {
                                        logStore.append(log)
                                        savedSets = logStore.totalSets()
                                        lastSavedNote = "세트 로그 저장됨 · ${samples.size}프레임 · 누적 ${savedSets}세트"
                                    } catch (t: Throwable) {
                                        lastSavedNote = "세트 로그 저장 실패: ${t.message}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        container = TrexError,
                        contentColor = Color.White,
                    )

                    LabPhase.RESULT -> TrexButton(
                        text = "다시 측정",
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            aggregator.reset()
                            sampledFrames = 0
                            results = emptyList()
                            synchronized(recordedSamples) { recordedSamples.clear(); recordedTimesMs.clear() }
                            phase = LabPhase.IDLE
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // ---- 실시간 코칭 배너 (기록 중): 어디가 / 처음부터인지 점점인지
            if (phase == LabPhase.RECORDING) {
                val ev = coachBanner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when (ev?.kind) {
                        OnsetKind.HABIT -> TrexError.copy(alpha = 0.25f)
                        OnsetKind.DRIFT -> TrexWarning.copy(alpha = 0.25f)
                        OnsetKind.RECOVERED -> TrexLime.copy(alpha = 0.2f)
                        null -> Color.White.copy(alpha = 0.06f)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = ev?.let {
                                (when (it.kind) { OnsetKind.HABIT -> "처음부터 · "; OnsetKind.DRIFT -> "점점 흐트러짐 · "; OnsetKind.RECOVERED -> "교정됨 · " }) +
                                    it.rule.condition + (if (it.direction == Direction.OPPOSITE) " (반대측)" else "")
                            } ?: "코칭 대기 — 초반 ${MIN_FRAMES_FOR_VERDICT}프레임 후 판정 시작",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = ev?.message ?: (if (!speech.ready) (speech.lastError ?: "음성 준비 중…") else if (voiceCoach) "음성 안내 ON" else "음성 안내 OFF (화면만)"),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                        val habitN = coachStates.count { it.kind == OnsetKind.HABIT }
                        val driftN = coachStates.count { it.kind == OnsetKind.DRIFT }
                        if (coachStates.isNotEmpty()) {
                            Text(
                                text = "현재 창: 처음부터 $habitN · 흐트러짐 $driftN · 정상 ${coachStates.count { it.kind == null && it.recent == Verdict.OK }}",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
            // ---- 세트 로그(재보정 데이터) 컨트롤: 저장 토글 · 누적 건수 · 내보내기(공유) · 지우기 · 음성 코칭
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = if (voiceCoach) "음성 코칭 ON" else "음성 코칭 OFF",
                    color = if (voiceCoach) TrexLime else Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { voiceCoach = !voiceCoach; speech.muted = !voiceCoach; if (!voiceCoach) speech.stop() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = if (saveLogs) "로그 저장 ON" else "로그 저장 OFF",
                    color = if (saveLogs) TrexLime else Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { saveLogs = !saveLogs }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = "누적 ${savedSets}세트",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "내보내기",
                    color = if (savedSets > 0) TrexLime else Color.White.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(enabled = savedSets > 0) {
                            if (!SetLogExport.share(context, logStore)) lastSavedNote = "내보낼 로그가 없습니다"
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = "지우기",
                    color = if (savedSets > 0) TrexWarning else Color.White.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(enabled = savedSets > 0) {
                            executor.execute {
                                logStore.clear()
                                savedSets = logStore.totalSets()
                                lastSavedNote = "세트 로그를 지웠습니다"
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            lastSavedNote?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp) }
            Spacer(Modifier.height(10.dp))

            val message = analyzerError ?: loadError
            if (message != null) {
                Text(message, color = TrexError, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (phase) {
                    LabPhase.RESULT -> {
                        val violated = results.count { it.verdict == Verdict.VIOLATION }
                        val abstained = results.count { it.verdict == Verdict.ABSTAIN }
                        Text(
                            text = "판정: 위반 $violated · 정상 ${results.size - violated - abstained} · 유보 $abstained  (샘플 $sampledFrames 프레임)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 세트 내 변화: 전반 창 vs 후반 창 — 처음부터 / 점점 흐트러짐 / 교정됨
                        val changed = onsetSummary.filter { it.kind != null }
                        if (changed.isNotEmpty()) {
                            Text("세트 내 변화 (전반 → 후반)", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            changed.forEach { st ->
                                Text(
                                    text = "• ${st.label} — ${st.rule.condition}" +
                                        (st.earlyValue?.let { e -> st.recentValue?.let { r -> "  (${PostureRule.fmt(e)} → ${PostureRule.fmt(r)})" } } ?: ""),
                                    color = when (st.kind) {
                                        OnsetKind.HABIT -> TrexError
                                        OnsetKind.DRIFT -> TrexWarning
                                        else -> TrexLime
                                    },
                                    fontSize = 10.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        results.forEach { RuleResultRow(it) }
                    }

                    else -> {
                        Text(
                            text = if (phase == LabPhase.RECORDING) "수집 중 — 실시간 값" else "실시간 값 (세트 시작 전 미리보기)",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        activeRules.forEach { rule ->
                            LiveRuleRow(
                                rule = rule,
                                liveValue = sample.features[rule.baseFeature],
                                aggValue = if (sampledFrames > 0) aggregator.stat(rule.baseFeature, rule.stat) else null,
                                sampleCount = aggregator.count(rule.baseFeature),
                            )
                        }
                        if (activeRules.isEmpty() && ruleSet != null) {
                            Text("이 종목에는 활성 규칙이 없습니다.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LabIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ExercisePicker(exercises: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { if (exercises.isNotEmpty()) open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = TrexDarkAlt,
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .background(TrexDarkAlt)
                .heightIn(max = 380.dp),
        ) {
            exercises.forEach { ex ->
                DropdownMenuItem(
                    text = { Text(ex, color = if (ex == selected) TrexLime else Color.White, fontSize = 13.sp) },
                    onClick = {
                        onSelect(ex)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveRuleRow(rule: PostureRule, liveValue: Float?, aggValue: Float?, sampleCount: Int) {
    val live = liveValue?.let { PostureRule.fmt(it) } ?: "—"
    val agg = aggValue?.let { PostureRule.fmt(it) } ?: "—"
    val wouldViolate = aggValue != null && rule.isViolated(aggValue)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rule.condition + (rule.subtype?.let { " [$it]" } ?: ""),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(rule)
        }
        Text(
            text = "${rule.feature}  현재 $live  ·  집계($sampleCount) $agg  ·  기준 ${rule.op} ${PostureRule.fmt(rule.threshold)}",
            color = if (wouldViolate) TrexError else Color.White.copy(alpha = 0.62f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun RuleResultRow(result: RuleResult) {
    val rule = result.rule
    val (label, color) = when (result.verdict) {
        Verdict.VIOLATION -> (if (result.direction == Direction.OPPOSITE) "위반(반대측)" else "위반") to TrexError
        Verdict.OK -> "정상" to TrexLime
        Verdict.ABSTAIN -> "유보" to Color.White.copy(alpha = 0.45f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rule.condition + (rule.subtype?.let { " [$it]" } ?: ""),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "${rule.feature} = ${result.value?.let { PostureRule.fmt(it) } ?: "—"}" +
                "  (기준 ${rule.op} ${PostureRule.fmt(rule.threshold)}, 샘플 ${result.sampleCount})" +
                (rule.oppositeGuard?.let { g -> " · 반대측 ${g.op} ${PostureRule.fmt(g.threshold)}" } ?: ""),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (result.direction == Direction.OPPOSITE) {
            val g = rule.oppositeGuard
            Text(
                text = "반대 방향 위반: ${g?.desc ?: "반대측"}" + (if (g?.validated == false) " · 정상분포 기반 경계(검출률 미보증)" else ""),
                color = TrexWarning.copy(alpha = 0.9f),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "권장 뷰 ${rule.view} · 연구 AUC ${"%.2f".format(rule.cvAuc)} / 균형정확도 ${"%.2f".format(rule.cvBalacc)}" +
                if (!rule.mirrorSafe) " · 좌우 미러 주의" else "",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (rule.cautions.isNotEmpty()) {
            Text(
                text = "⚠ " + rule.cautions.joinToString(" / "),
                color = TrexWarning.copy(alpha = 0.85f),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(rule: PostureRule) {
    val (text, color) = when (rule.status) {
        RuleStatus.SHIP -> "ship" to TrexLime
        RuleStatus.BETA -> "beta" to TrexWarning
        RuleStatus.EXCLUDE -> "excl" to TrexError
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Text(text, color = color, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 골격 오버레이. 정규화 좌표를 PreviewView(FILL_CENTER) 기준으로 매핑. */
@Composable
private fun PoseOverlay(sample: PoseSample, mirror: Boolean, modifier: Modifier = Modifier) {
    val lime = TrexLime
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
            val nx = sample.normalizedXy[i * 2]
            val ny = sample.normalizedXy[i * 2 + 1]
            val px = dx + nx * drawW
            val py = dy + ny * drawH
            return Offset(if (mirror) size.width - px else px, py)
        }

        POSE_CONNECTIONS.forEach { (a, b) ->
            if (sample.visibility[a] >= 0.5f && sample.visibility[b] >= 0.5f) {
                drawLine(
                    color = lime.copy(alpha = 0.85f),
                    start = point(a),
                    end = point(b),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        for (i in 0 until MP_LANDMARK_COUNT) {
            val v = sample.visibility[i]
            if (v < 0.5f) continue
            drawCircle(color = Color.White, radius = 5f, center = point(i))
        }
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
