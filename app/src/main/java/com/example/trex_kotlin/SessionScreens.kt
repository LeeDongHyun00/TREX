package com.example.trex_kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

/**
 * 세션 화면 (리디자인) — 자세교정 세션은 카메라 영역 + 실시간 피드백 카드,
 * 일반 세션은 링 타이머. 분석은 [PostureAnalysisEngine] 뒤에 있고(현재 시뮬레이션),
 * 실제 카메라+포즈 추정 엔진이 같은 인터페이스로 교체된다.
 */

// ============================================================= 자세교정 세션

@Composable
fun PostureSessionScreen(
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
    val spec = remember(workout.id) { workout.exerciseSpec() }
    var permission by remember(workout.id) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionAsked by remember(workout.id) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        permissionAsked = true
    }
    LaunchedEffect(workout.id) {
        if (!permission) launcher.launch(Manifest.permission.CAMERA)
    }

    val engine: PostureAnalysisEngine = remember(workout.id) { SimulatedPostureAnalysisEngine(spec.targetReps) }
    var repCount by remember(workout.id) { mutableIntStateOf(0) }
    var score by remember(workout.id) { mutableIntStateOf(94) }
    var cue by remember(workout.id) { mutableStateOf("자세를 유지해 주세요") }
    var muted by remember(workout.id) { mutableStateOf(false) }
    val feedback = rememberWorkoutFeedback(muted = muted)
    val pausedState = rememberUpdatedState(paused)

    LaunchedEffect(workout.id, permission) {
        if (!permission) return@LaunchedEffect
        engine.beginSet(1)
        feedback.speak("${workout.name} 시작합니다")
        while (true) {
            val analysis = engine.awaitNextRep(repCount) { pausedState.value }
            repCount += 1
            score = analysis.score
            analysis.cue?.let {
                cue = it
                feedback.speak(it)
            }
        }
    }
    LaunchedEffect(engine.trackingLost) {
        if (engine.trackingLost) feedback.speak("관절이 화면 밖으로 벗어났어요. 한 걸음 뒤로 이동해 주세요")
    }

    if (!permission && permissionAsked) {
        // 권한 거부 — 일반 타이머 세션으로 안내
        Column(
            Modifier.fillMaxSize().background(c.bg).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(82.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = c.warn, modifier = Modifier.size(34.dp))
            }
            Text("카메라 권한이 필요해룡", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Text(
                "자세 교정 없이 타이머로 이어가려면 계속을 눌러주세요.",
                color = c.text2, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Cta("타이머로 계속", onClick = onNext, modifier = Modifier.padding(top = 28.dp).fillMaxWidth())
            GhostButton("나가기", onClick = onExit, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.bg).padding(top = 50.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("진행중 · ${index + 1}/$total", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text(workout.name, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            }
            RoundIcon(Icons.Rounded.Close, onClick = onExit, size = 38.dp, contentDescription = "종료")
        }

        // 카메라 영역 (실엔진 연결 전 스트라이프 플레이스홀더)
        Box(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                .fillMaxWidth()
                .height(272.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(c.stripeA, c.stripeB))),
        ) {
            Text(
                "camera feed\n자세 인식 화면",
                color = c.text3, fontSize = 11.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x8C0C1008))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pulse by animateFloatAsState(if (paused) 0.35f else 1f, tween(600), label = "pulse")
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(c.lime.copy(alpha = pulse)),
                )
                Text(
                    if (engine.trackingLost) "관절 이탈" else if (paused) "일시정지" else "자세 인식 중",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.primary)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text("정확도 $score%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = c.surface,
                contentColor = c.text,
                border = BorderStroke(1.dp, c.line),
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(13.dp)) {
                    Text("바로 고치고", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                    Text(cue, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text("남은 시간", color = c.text3, fontSize = 11.sp)
                Text(timeLeft.asClock(), color = c.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 42.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("반복", color = c.text3, fontSize = 11.sp)
                Text(
                    "$repCount / ${spec.targetReps}${spec.targetLabel.filter { !it.isDigit() }}",
                    color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
            TrackBar(progress = 1f - (timeLeft / totalSeconds.toFloat()), height = 7.dp)
        }

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.padding(bottom = 26.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.Rounded.RestartAlt, onClick = { muted = !muted }, size = 50.dp, contentDescription = "음성 안내 전환")
            Surface(
                onClick = onTogglePause,
                modifier = Modifier.size(66.dp),
                shape = CircleShape,
                color = c.primary,
                contentColor = Color.White,
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = "일시정지", modifier = Modifier.size(26.dp))
                }
            }
            RoundIcon(Icons.Rounded.Check, onClick = onNext, size = 50.dp, contentDescription = "다음")
        }
    }
}

// ============================================================= 타이머 세션

@Composable
fun TimerSessionScreen(
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
    Column(Modifier.fillMaxSize().background(c.bg).padding(start = 20.dp, end = 20.dp, top = 50.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("진행중 · ${index + 1}/$total", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text(workout.name, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            }
            RoundIcon(Icons.Rounded.Close, onClick = onExit, size = 38.dp, contentDescription = "종료")
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RingGauge(progress = 1f - (timeLeft / totalSeconds.toFloat()), size = 224.dp, stroke = 12.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("남은 시간", color = c.text3, fontSize = 11.sp)
                    Text(timeLeft.asClock(), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, lineHeight = 46.sp, modifier = Modifier.padding(top = 5.dp))
                    Text(workout.reps, color = c.primaryText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(
                Modifier
                    .padding(top = 22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(14.dp))
                Text("${workout.category} · 자세 교정 미사용", color = c.text2, fontSize = 11.5.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(
            Modifier.padding(bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RoundIcon(
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                onClick = onTogglePause,
                size = 52.dp,
                contentDescription = "일시정지",
            )
            Cta("다음 운동", icon = Icons.Rounded.Check, onClick = onNext, height = 56.dp, modifier = Modifier.weight(1f))
        }
    }
}

// ============================================================= 완료

@Composable
fun SessionCompleteScreen(
    plan: List<Workout>,
    elapsedSeconds: Int,
    onDone: () -> Unit,
) {
    val c = Trex.c
    val doneCount = plan.count { it.done }
    val kcal = plan.filter { it.done }.sumOf { it.estimatedCalories() }
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(modifier = Modifier.size(82.dp), shape = CircleShape, color = c.primary, contentColor = Color.White, shadowElevation = 8.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(38.dp)) }
        }
        Text("DONE", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, modifier = Modifier.padding(top = 20.dp))
        Text("오늘도 정확하게 끝냈어룡", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "조금씩 좋아지고 있어요.\n내일 같은 시간에 만나룡.",
            color = c.text2, fontSize = 13.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "$doneCount" to "완료 운동",
                "${(elapsedSeconds / 60).coerceAtLeast(1)}분" to "총 시간",
                "${kcal}kcal" to "소모 칼로리",
            ).forEach { (v, label) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(18.dp))
                        .padding(vertical = 13.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(v, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                    Text(label, color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        Cta("홈으로", icon = Icons.Rounded.Home, onClick = onDone, modifier = Modifier.padding(top = 26.dp).fillMaxWidth())
    }
}

// ============================================================= 공용 유틸 (기존 유지)

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

@Composable
fun rememberTrexLifecyclePaused(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> paused = true
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> paused = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return paused
}

data class WorkoutFeedback(
    val beep: () -> Unit,
    val speak: (String) -> Unit,
)

@Composable
fun rememberWorkoutFeedback(muted: Boolean): WorkoutFeedback {
    val context = LocalContext.current
    val mutedState = rememberUpdatedState(muted)
    val ttsState = remember { mutableStateOf<TextToSpeech?>(null) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 62) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
            }
        }
        ttsState.value = engine
        onDispose {
            ttsState.value = null
            engine.shutdown()
            toneGenerator.release()
        }
    }

    return remember {
        WorkoutFeedback(
            beep = {
                if (!mutedState.value) {
                    runCatching { toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
                }
            },
            speak = { text ->
                if (!mutedState.value) {
                    ttsState.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trex-${System.nanoTime()}")
                }
            },
        )
    }
}
