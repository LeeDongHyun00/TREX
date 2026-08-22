package com.example.trex_kotlin.posture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
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

    val aggregator = remember { FeatureAggregator() }
    val phaseRef = remember { arrayOf(LabPhase.IDLE) }
    phaseRef[0] = phase
    val lastSampleAt = remember { longArrayOf(0L) }

    val analyzer = remember {
        try {
            PostureAnalyzer(context)
        } catch (t: Throwable) {
            analyzerError = "모델 로드 실패: ${t.message}"
            null
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analyzer?.close()
            executor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(granted, useFrontCamera, analyzer) {
        if (!granted || analyzer == null) return@LaunchedEffect
        val provider = context.awaitCameraProvider()
        provider.unbindAll()
        // 프리뷰와 분석의 종횡비를 4:3 으로 맞춰야 골격 오버레이가 화면과 정렬된다
        val aspect43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(480, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(aspect43)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(analysisSelector)
            .build()
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = System.currentTimeMillis()
                val s = analyzer.analyze(image, now)
                sample = s
                if (phaseRef[0] == LabPhase.RECORDING && s.detected && now - lastSampleAt[0] >= SAMPLE_INTERVAL_MS) {
                    lastSampleAt[0] = now
                    aggregator.add(s.features)
                    sampledFrames = aggregator.frameCount
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
            Text(
                text = "폰을 세로로 세워 거치 · 정면~전방 45°에서 촬영 · 전신이 프레임에 들어오게",
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
                            lastSampleAt[0] = 0L
                            phase = LabPhase.RECORDING
                        },
                        modifier = Modifier.weight(1f),
                        enabled = granted && ruleSet != null && activeRules.isNotEmpty(),
                    )

                    LabPhase.RECORDING -> TrexButton(
                        text = "세트 종료 ($sampledFrames 프레임)",
                        icon = Icons.Rounded.Stop,
                        onClick = {
                            results = ruleSet?.evaluate(exercise, aggregator, includeBeta, MIN_FRAMES_FOR_VERDICT).orEmpty()
                            phase = LabPhase.RESULT
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
                            phase = LabPhase.IDLE
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
        Verdict.VIOLATION -> "위반" to TrexError
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
                "  (기준 ${rule.op} ${PostureRule.fmt(rule.threshold)}, 샘플 ${result.sampleCount})",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
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
