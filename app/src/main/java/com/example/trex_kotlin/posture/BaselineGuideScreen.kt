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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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

/**
 * 자세 기준선 설정 화면 (spec §15~§17).
 *
 * 개인 기준선이 검증된 종목(`personal_baseline.eligible`, 6종목)만 보여 주고, 종목마다 **정자세로 k(=3)세트**를
 * 찍게 안내한다. 세트별 eligible 피처의 집계값을 모아 중앙값을 기준선으로 저장하면, 이후 판정에서 그 규칙은
 * (값 − 기준선) 을 상대 임계값과 비교한다. 라벨도 오류 세트도 필요 없다.
 *
 * 가드(§16 실험 4): 세트가 절대 임계값 기준으로 '위반'이면 정자세였는지 확인하라고만 알리고 강제 거부하지 않는다
 * (중앙값이 1개 오염까지 흡수). 세트 로그도 `note=baseline` 으로 남겨 나중에 기준선-상대 임계값을 재보정할 수 있게 한다.
 */

private const val BASELINE_SAMPLE_INTERVAL_MS = 300L
private const val BASELINE_MIN_FRAMES = 8

private enum class BaselinePhase { IDLE, RECORDING, REVIEW, DONE }

@Composable
fun BaselineGuideScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var ruleSet by remember { mutableStateOf<PostureRuleSet?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            ruleSet = PostureRuleSet.load(context)
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
        }
    }
    val store = remember { BaselineStore(context) }
    var profile by remember { mutableStateOf(store.load()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val rs = ruleSet
    when {
        rs == null -> Box(Modifier.fillMaxSize().background(TrexDark), contentAlignment = Alignment.Center) {
            Text(loadError ?: "규칙 로딩 중…", color = if (loadError != null) TrexError else Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        selected == null -> BaselineExerciseList(
            ruleSet = rs,
            profile = profile,
            onSelect = { selected = it },
            onReset = { ex -> profile = store.remove(ex) },
            onClose = onClose,
        )
        else -> BaselineCaptureView(
            ruleSet = rs,
            exercise = selected!!,
            existing = profile.get(selected!!),
            onBack = { selected = null },
            onSaved = { b ->
                profile = store.put(b)
                selected = null
            },
        )
    }
}

// ---------------------------------------------------------------- 목록
@Composable
private fun BaselineExerciseList(
    ruleSet: PostureRuleSet,
    profile: BaselineProfile,
    onSelect: (String) -> Unit,
    onReset: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuideIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onClick = onClose)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("자세 기준선 설정", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "정자세 세트 몇 개로 '내 기준'을 만듭니다 · 규칙 ${ruleSet.version}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = TrexDarkAlt, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("왜 기준선인가", color = TrexLime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "사람마다 몸통 기울기·머리 높이 같은 '자세 수준'이 달라서, 일부 규칙은 인구 평균이 아니라 " +
                            "본인의 정자세를 기준으로 판정할 때 더 정확합니다(연구 검증, 아래 종목만 해당). " +
                            "오류 세트나 라벨은 필요 없고, 평소 정자세로 ${BaselineCollector.DEFAULT_SETS}세트(3~4렙씩)만 찍으면 됩니다. " +
                            "세트 중 하나가 흔들려도 중앙값이라 버팁니다.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val exercises = ruleSet.baselineExercises
            if (exercises.isEmpty()) {
                Text("기준선 대상 규칙이 없습니다 (rules JSON 에 personal_baseline.threshold_rel 이 없음).", color = TrexWarning, fontSize = 12.sp)
            }
            exercises.forEach { ex ->
                val rules = ruleSet.baselineRulesFor(ex)
                val k = ruleSet.baselineSetsFor(ex)
                val b = profile.get(ex)
                val views = rules.map { it.view }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("/")
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = TrexDarkAlt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable { onSelect(ex) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ex, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (b != null) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = TrexLime, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("설정됨", color = TrexLime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("미설정 · 정자세 ${k}세트", color = TrexWarning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "기준선 규칙 ${rules.size}개 · " + rules.joinToString(", ") { it.condition } +
                                (if (views.isNotEmpty()) " · 권장 뷰 $views" else ""),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                        if (b != null) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${b.nSets}세트 · ${b.createdAtIso.take(10)} · " +
                                        b.values.entries.joinToString("  ") { "${shortFeature(it.key)}=${PostureRule.fmt(it.value)}" },
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 9.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "초기화",
                                    color = TrexError,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clickable { onReset(ex) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------- 촬영·수집
@Composable
private fun BaselineCaptureView(
    ruleSet: PostureRuleSet,
    exercise: String,
    existing: ExerciseBaseline?,
    onBack: () -> Unit,
    onSaved: (ExerciseBaseline) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rules = remember(exercise) { ruleSet.baselineRulesFor(exercise) }
    val features = remember(exercise) { ruleSet.baselineFeaturesFor(exercise) }
    val requiredSets = remember(exercise) { ruleSet.baselineSetsFor(exercise) }
    val collector = remember(exercise) { BaselineCollector(exercise, features, requiredSets) }
    val recommendedViews = remember(exercise) { rules.map { it.view }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("/") }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    var phase by remember { mutableStateOf(BaselinePhase.IDLE) }
    var sample by remember { mutableStateOf(PoseSample.empty()) }
    var sampledFrames by remember { mutableIntStateOf(0) }
    var completedSets by remember { mutableIntStateOf(0) }
    var lastSetValues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var lastWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var finalBaseline by remember { mutableStateOf<ExerciseBaseline?>(null) }
    var analyzerError by remember { mutableStateOf<String?>(null) }

    val aggregator = remember { FeatureAggregator() }
    val recordedSamples = remember { ArrayList<PoseSample>() }
    val recordedTimesMs = remember { ArrayList<Long>() }
    val phaseRef = remember { arrayOf(BaselinePhase.IDLE) }
    phaseRef[0] = phase
    val frontRef = remember { booleanArrayOf(false) }
    frontRef[0] = useFrontCamera

    val analyzer = remember { PostureAnalyzer(context, PoseModel.FULL, preferGpu = true) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val policy = remember { InferencePolicy(sampleIntervalMs = BASELINE_SAMPLE_INTERVAL_MS) }
    val lastInferAt = remember { longArrayOf(0L) }
    val gravity = remember { GravityTracker(context) }
    val logStore = remember { SetLogStore(context) }
    DisposableEffect(Unit) {
        gravity.start()
        onDispose {
            gravity.stop()
            analyzer.close()
            executor.shutdown()
        }
    }
    val displayRotation = remember(context) {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(granted, useFrontCamera) {
        if (!granted) return@LaunchedEffect
        val provider = context.awaitCameraProviderForBaseline()
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
            val ph = if (phaseRef[0] == BaselinePhase.RECORDING) InferencePhase.RECORDING else InferencePhase.IDLE
            if (!policy.shouldInfer(now, lastInferAt[0], ph, InferencePolicy.THERMAL_NONE)) {
                image.close()
                return@setAnalyzer
            }
            lastInferAt[0] = now
            try {
                val up = gravity.gravityDevice?.let { gravityUpInWorld(it, displayRotation, frontRef[0]) } ?: SCREEN_UP
                val s = analyzer.analyze(image, now, up)
                sample = s
                if (phaseRef[0] == BaselinePhase.RECORDING && s.detected) {
                    aggregator.add(s.features)
                    sampledFrames = aggregator.frameCount
                    synchronized(recordedSamples) {
                        recordedSamples.add(s)
                        recordedTimesMs.add(now)
                    }
                }
            } catch (t: Throwable) {
                analyzerError = t.message
            } finally {
                image.close()
            }
        }
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (t: Throwable) {
            analyzerError = "카메라 바인딩 실패: ${t.message}"
        }
    }

    fun resetSet() {
        aggregator.reset()
        sampledFrames = 0
        synchronized(recordedSamples) { recordedSamples.clear(); recordedTimesMs.clear() }
    }

    Column(Modifier.fillMaxSize().background(TrexDark)) {
        // ---------- 카메라 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color.Black),
        ) {
            if (granted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                BaselineSkeletonOverlay(sample = sample, mirror = useFrontCamera, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
                GuideIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("기준선 · $exercise", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (existing != null) "이미 설정됨(${existing.nSets}세트) — 다시 찍으면 덮어씁니다" else "정자세 ${requiredSets}세트 · 3~4렙씩",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
                GuideIconButton(Icons.Rounded.Cameraswitch, onClick = { useFrontCamera = !useFrontCamera })
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = buildString {
                        append(if (sample.detected) "검출 O" else "검출 X")
                        append("  가시 ${sample.visibleJointCount}/33")
                        if (!sample.upFromGravity) append("  중력센서 X") else append("  기울기 ${"%.0f".format(tiltFromScreenUpDegrees(sample.up))}°")
                    },
                    color = if (sample.detected) TrexLime else TrexError,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }

        // ---------- 안내 + 컨트롤 ----------
        Column(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxWidth()
                .background(TrexDark)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(10.dp))
            // 진행 칩
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until requiredSets) {
                    val done = i < completedSets
                    val current = i == completedSets && phase != BaselinePhase.DONE
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            done -> TrexLime
                            current -> TrexLime.copy(alpha = 0.25f)
                            else -> Color.White.copy(alpha = 0.08f)
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            text = "세트 ${i + 1}" + if (done) " ✓" else "",
                            color = if (done) TrexDark else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = when (phase) {
                        BaselinePhase.IDLE -> "준비"
                        BaselinePhase.RECORDING -> "기록 중 · $sampledFrames 프레임"
                        BaselinePhase.REVIEW -> "세트 확인"
                        BaselinePhase.DONE -> "완료"
                    },
                    color = if (phase == BaselinePhase.RECORDING) TrexError else Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "• 평소처럼 **정자세로** 3~4렙 (일부러 잘하려 하지 말 것 — 이게 '내 기준'이 됩니다)\n" +
                    "• 폰은 " + (if (recommendedViews.isNotEmpty()) "권장 뷰 $recommendedViews" else "정면~전방 45°") + ", 허리 높이, 세로 거치 · 전신이 프레임 안에\n" +
                    "• '세트 시작' → 렙 수행 → '세트 종료' → 값 확인 후 저장",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))

            // 컨트롤
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (phase) {
                    BaselinePhase.IDLE -> TrexButton(
                        text = "세트 ${completedSets + 1} 시작",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = {
                            resetSet()
                            lastInferAt[0] = 0L
                            phase = BaselinePhase.RECORDING
                        },
                        modifier = Modifier.weight(1f),
                        enabled = granted && features.isNotEmpty(),
                    )
                    BaselinePhase.RECORDING -> TrexButton(
                        text = "세트 종료 ($sampledFrames 프레임)",
                        icon = Icons.Rounded.Stop,
                        onClick = {
                            val values = BaselineCollector.setValues(aggregator, features, BASELINE_MIN_FRAMES)
                            lastSetValues = values
                            // 느슨한 가드: 절대 임계값으로 '위반'이면 정자세였는지 확인만 요청 (강제 거부 X)
                            val absolute = ruleSet.evaluate(exercise, aggregator, includeBeta = true, minFrames = BASELINE_MIN_FRAMES, baseline = null)
                            val warns = ArrayList<String>()
                            if (sampledFrames < BASELINE_MIN_FRAMES) warns += "프레임 부족($sampledFrames) — 3~4렙을 온전히 기록해 주세요"
                            val missing = features.filter { it !in values }
                            if (missing.isNotEmpty()) warns += "값 계산 불가: " + missing.joinToString(", ") { shortFeature(it) } + " (관절 가림/프레이밍 확인)"
                            absolute.filter { r -> r.rule.supportsBaseline && r.verdict == Verdict.VIOLATION }
                                .forEach { warns += "'${it.rule.condition}' 규칙이 인구 기준으로 위반 — 정자세였나요? 아니면 다시 찍어 주세요" }
                            lastWarnings = warns
                            phase = BaselinePhase.REVIEW
                        },
                        modifier = Modifier.weight(1f),
                        container = TrexError,
                        contentColor = Color.White,
                    )
                    BaselinePhase.REVIEW -> {
                        TrexButton(
                            text = "다시 찍기",
                            icon = Icons.Rounded.Refresh,
                            onClick = {
                                resetSet()
                                phase = BaselinePhase.IDLE
                            },
                            modifier = Modifier.weight(1f),
                            container = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        TrexButton(
                            text = "이 세트 저장",
                            icon = Icons.Rounded.Check,
                            onClick = {
                                collector.addSet(lastSetValues)
                                completedSets = collector.completedSets
                                // 세트 로그(재보정용): 프레임 원본 + note=baseline
                                val samples = synchronized(recordedSamples) { ArrayList(recordedSamples) }
                                val times = synchronized(recordedSamples) { ArrayList(recordedTimesMs) }
                                val t0 = times.firstOrNull() ?: 0L
                                val log = SetLog.build(
                                    exercise = exercise, samples = samples, results = emptyList(),
                                    rulesVersion = ruleSet.version, model = PoseModel.FULL.label, delegate = analyzer.stats().delegate,
                                    frontCamera = useFrontCamera, sampleIntervalMs = BASELINE_SAMPLE_INTERVAL_MS,
                                    sampleTimesMs = times.map { it - t0 }, note = "baseline ${collector.completedSets}/$requiredSets",
                                )
                                executor.execute { try { logStore.append(log) } catch (_: Throwable) {} }
                                resetSet()
                                if (collector.isComplete) {
                                    finalBaseline = collector.build()   // 영속화는 DONE 화면의 '기준선 저장' 에서
                                    phase = BaselinePhase.DONE
                                } else {
                                    phase = BaselinePhase.IDLE
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = lastSetValues.isNotEmpty(),
                        )
                    }
                    BaselinePhase.DONE -> {
                        TrexButton(
                            text = "처음부터",
                            icon = Icons.Rounded.Refresh,
                            onClick = {
                                collector.reset()
                                completedSets = 0
                                finalBaseline = null
                                resetSet()
                                phase = BaselinePhase.IDLE
                            },
                            modifier = Modifier.weight(1f),
                            container = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        TrexButton(
                            text = "기준선 저장",
                            icon = Icons.Rounded.Check,
                            onClick = { finalBaseline?.let(onSaved) },
                            modifier = Modifier.weight(1f),
                            enabled = finalBaseline != null && finalBaseline!!.values.isNotEmpty(),
                        )
                    }
                }
            }
            analyzerError?.let { Text(it, color = TrexError, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(10.dp))

            // 값 표: 규칙별 라이브 값 / 세트값들 / (완료 시) 기준선
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (phase == BaselinePhase.REVIEW && lastWarnings.isNotEmpty()) {
                    lastWarnings.forEach { Text("⚠ $it", color = TrexWarning, fontSize = 10.sp, lineHeight = 14.sp) }
                    Spacer(Modifier.height(6.dp))
                }
                if (phase == BaselinePhase.DONE) {
                    Text("기준선 (세트 ${completedSets}개 중앙값)", color = TrexLime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                }
                val perSet = collector.perSet()
                rules.forEach { rule ->
                    val live = sample.features[rule.baseFeature]
                    val setVals = perSet.mapNotNull { it[rule.feature] }
                    val reviewVal = if (phase == BaselinePhase.REVIEW) lastSetValues[rule.feature] else null
                    val base = finalBaseline?.values?.get(rule.feature)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.condition, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${rule.feature} · 인구 임계값 ${rule.op} ${PostureRule.fmt(rule.threshold)} · 기준선 상대 ${rule.op} ${rule.baselineThresholdRel?.let { PostureRule.fmt(it) } ?: "-"}",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 9.sp,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = when {
                                    base != null -> "기준선 ${PostureRule.fmt(base)}"
                                    reviewVal != null -> "이 세트 ${PostureRule.fmt(reviewVal)}"
                                    live != null -> "지금 ${PostureRule.fmt(live)}"
                                    else -> "—"
                                },
                                color = if (base != null) TrexLime else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (setVals.isNotEmpty()) {
                                Text(
                                    text = "세트값 " + setVals.joinToString(", ") { PostureRule.fmt(it) },
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun GuideIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.45f),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** 오버레이: 정규화 좌표의 33점 골격을 선으로 그린다 (프리뷰와 분석 스트림이 모두 4:3 FILL_CENTER 라 근사 정렬). */
@Composable
private fun BaselineSkeletonOverlay(sample: PoseSample, mirror: Boolean, modifier: Modifier = Modifier) {
    if (!sample.detected) return
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun pt(i: Int): Offset? {
            if (sample.visibility[i] < 0.5f) return null
            val x = sample.normalizedXy[i * 2]
            val y = sample.normalizedXy[i * 2 + 1]
            val xs = if (mirror) 1f - x else x
            return Offset(xs * w, y * h)
        }
        for ((a, b) in POSE_CONNECTIONS) {
            val pa = pt(a) ?: continue
            val pb = pt(b) ?: continue
            drawLine(TrexLime.copy(alpha = 0.85f), pa, pb, strokeWidth = 4f, cap = StrokeCap.Round)
        }
        for (i in 0 until MP_LANDMARK_COUNT) {
            val p = pt(i) ?: continue
            drawCircle(Color.White, radius = 4f, center = p)
        }
    }
}

private fun shortFeature(f: String): String = f.replace("__", ".")

private suspend fun Context.awaitCameraProviderForBaseline(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
