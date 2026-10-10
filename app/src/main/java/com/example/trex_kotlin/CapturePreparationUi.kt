package com.example.trex_kotlin

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** 준비 전용 상태다. 라이브 엔진/개인 기준에는 프레임을 전달하지 않는다. */
/**
 * 같은 운동의 긴 준비 안내는 한 번만(§89) — 준비 패널은 세트마다 새로 만들어져 remember 가 사라지므로 프로세스 범위에 둔다.
 * 30분이 지나면(다른 날·다른 세션) 다시 말한다. 두 번째 세트부터는 안내 없이 범위가 잡히면 바로 3초를 센다. 메인 스레드에서만 쓴다.
 */
/** 이유가 아닌 준비 문구 — 화면의 이유 줄에 띄우지 않는다. */
private val GENERIC_PREPARATION_MESSAGES = setOf(PreparationState().message, "촬영 범위가 확인됐어요.", "곧 시작해요.")

/** 화면 고정 약속 문구(§101) — 카운트다운이 말과 분리됐음을 밝힌다. */
internal const val START_PROMISE = "몸이 화면에 잡히고 멈추면 3초를 셉니다. 설명 중에도 시작해요."

internal object PreparationIntro {
    private const val REPEAT_AFTER_MS = 30 * 60_000L
    private val spokenAt = HashMap<String, Long>()
    fun due(name: String, now: Long): Boolean = spokenAt[name]?.let { now - it > REPEAT_AFTER_MS } ?: true
    fun mark(name: String, now: Long) { spokenAt[name] = now }
}

@Composable
internal fun CapturePreparationPanel(
    profile: ExerciseProfile, sample: PoseSample, sampleAt: Long, paused: Boolean,
    frontCamera: Boolean, muted: Boolean, onMute: () -> Unit, onCamera: () -> Unit,
    speech: SpeechCoach, onStart: (skipped: Boolean) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier,
    cameraError: String? = null, onFallback: () -> Unit = {},
    modeControl: @Composable () -> Unit = {},
    onShowGuide: (() -> Unit)? = null,
    guideName: String = profile.name,
    onTogglePause: () -> Unit = {},
) {
    val c = Trex.c
    val tone = remember { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60) }.getOrNull() }
    DisposableEffect(tone) { onDispose { runCatching { tone?.release() } } }
    val controller = remember(profile.name) { CapturePreparationController(profile.floor) }
    var state by remember { mutableStateOf(controller.state) }
    var userPaused by rememberSaveable { mutableStateOf(false) }
    val direction = remember(profile.name, frontCamera) { CaptureDirectionWindow(profile.capture, profile.floor) }
    val latestPaused by rememberUpdatedState(paused)
    val start by rememberUpdatedState(onStart)
    var delivered by remember { mutableStateOf(false) }
    var introductionGiven by remember(profile.name) { mutableStateOf(false) }
    // §101: 안내 문장이 나오는 중(계획 루프 안) — 카운트다운 진입 때 숫자 대신 톤을 고르고, 멈춤·재시작 이유는 문장 끝을 기다린다
    var introPlaying by remember(profile.name) { mutableStateOf(false) }
    var reasonSpoken by remember(profile.name) { mutableStateOf(false) }
    var startedNormally by remember(profile.name) { mutableStateOf(false) }
    var frameReady by remember(profile.name) { mutableStateOf(false) }
    fun cancel() { controller.cancel(); state = controller.state; speech.stop() }
    LaunchedEffect(frontCamera, paused, userPaused) {
        // 첫 진입은 직전 세트의 꼬리(마지막 교정 문장, §101)를 지키고 묵은 대기만 버린다. 일시정지·카메라 전환으로 다시 돌면 종전처럼 전부 멈춘다
        if (!introductionGiven) { controller.cancel(); state = controller.state; speech.stopKeepingTail() } else cancel()
        direction.clear()
        if (!paused && !userPaused) {
            // §101(10-08): 카운트다운은 말과 분리됐다 — 범위·안정·방향 근거가 서면 설명 중에도 3초를 센다(숫자 대신 톤). 안내는 우선순위 문장 계획(ESS → PLACE → DET)으로,
            // 문장 하나가 끝난 뒤 다음을 고른다. 시스템이 문장 중간을 자르지 않는다(감시 시한만). 종전엔 한 덩어리 안내(13.6~26.5 s)가 12 s 마감에 잘리고 그때야 '3' 이 났다
            controller.arm(SystemClock.elapsedRealtime()); state = controller.state
            val now = SystemClock.elapsedRealtime()
            speech.traceFeedback("prep", "armed ${profile.name}")
            if (!introductionGiven && !speech.muted && PreparationIntro.due(profile.name, now)) {
                val lines = profile.preparationLines()
                var i = 0; var first = true
                while (!speech.muted && controller.isAutomatic && !reasonSpoken) {
                    val st = controller.state
                    val counting = st.phase == PreparationPhase.COUNTDOWN
                    val t = SystemClock.elapsedRealtime()
                    // 가장 이른 시작: 카운트 중이면 남은 초, 아니면 안정 0.4 s + 방향 0.68 s + 3 s
                    val earliest = t + (if (counting) st.seconds * 1000L else 400L + 680L + CapturePreparationController.COUNTDOWN_MS)
                    val idx = PreparationVoicePlan.nextLine(lines, i, t, settledRecommended = counting || frameReady, counting = counting,
                        started = st.phase == PreparationPhase.STARTED, earliestStartMs = earliest) ?: break
                    val line = lines[idx]; i = idx + 1
                    introPlaying = true
                    speech.speak(line.text, flush = first); first = false
                    val deadline = SystemClock.elapsedRealtime() + PrepSpeech.watchdogMs(line.text) + speech.tailRemainingMs
                    while (!speech.muted && controller.isAutomatic && speech.isSpeaking && SystemClock.elapsedRealtime() < deadline) delay(50)
                    // TTS 콜백 누락에도 준비가 무한히 멈추지 않는다(감시 시한 = 예측 1.5배 + 2 s). 직접 시작을 누른 경우 그 카운트는 건드리지 않는다
                    if (controller.isAutomatic && speech.isSpeaking && SystemClock.elapsedRealtime() >= deadline) speech.stop()
                    if (line.kind == PrepKind.ESS) PreparationIntro.mark(profile.name, SystemClock.elapsedRealtime())
                }
                introPlaying = false
            }
            introductionGiven = true
        }
    }
    // 정상 시작(카운트 완료)은 진행 중 문장을 자르지 않는다(§101 — 넘치는 말은 ≤ 0.3 s, `TrexApp.nextSession` 이 꼬리로 넘긴다). 나가기·건너뛰기는 종전처럼 멈춘다
    DisposableEffect(Unit) { onDispose { controller.cancel(); if (!startedNormally) speech.stop() } }
    LaunchedEffect(sampleAt) {
        if (!latestPaused && !userPaused && sampleAt > 0L) {
            val framed = CaptureFraming.inspect(sample, profile.capture, profile.floor, profile.framingRegion, profile.lateralReach)
            frameReady = framed.ready
            // 방향 확인은 카운트다운 전에만 — 카운트 중 방향 흔들림으로 "1" 에서 막혀 처음으로 돌아갔다(§89 후속). 카운트 중 돌아서면 움직임 판단이 멈춘다
            controller.observe(sampleAt, if (controller.state.phase == PreparationPhase.COUNTDOWN) framed else direction.check(framed, sample.features, sampleAt))
            state = controller.state
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            delay(100)
            if (latestPaused || userPaused) { controller.cancel(); state = controller.state; continue }
            state = controller.tick(SystemClock.elapsedRealtime())
            if (state.phase == PreparationPhase.STARTED && !delivered) { delivered = true; startedNormally = true; speech.traceFeedback("prep", "started"); start(false) }
        }
    }
    // 카운트 음성 방식은 **진입 때** 정한다(§101 `CountdownVoice`) — 설명 중이거나 직전 세트 꼬리가 돌면 그 카운트는 끝까지 톤(숫자 TTS 는 설명을 자른다), 아니면 숫자.
    // 초가 바뀔 때만 말한다(회복 때 같은 숫자를 되풀이하지 않는다 — 10-08 크로스 런지 '3' 뒤 280 ms 에 '2' 로 끊김)
    var countMode by remember { mutableStateOf(CountdownVoice.Mode.NONE) }
    var lastSaidSecond by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.phase) {
        if (state.phase == PreparationPhase.COUNTDOWN) { countMode = CountdownVoice.mode(introPlaying || speech.tailActive, speech.ready, muted); lastSaidSecond = -1; speech.traceFeedback("prep", "countdown $countMode") }
    }
    LaunchedEffect(state.phase, state.seconds, muted) {
        if (!muted && !paused && state.phase == PreparationPhase.COUNTDOWN && state.seconds in 1..5 && state.seconds != lastSaidSecond) {
            lastSaidSecond = state.seconds
            when (countMode) {
                CountdownVoice.Mode.SPEAK -> speech.speak(state.seconds.toString(), flush = true)
                CountdownVoice.Mode.TONE -> runCatching { tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100) }
                CountdownVoice.Mode.NONE -> {}
            }
        }
    }
    // 마지막으로 말한 준비 안내 — 처음으로 돌아간 이유를 바로 말한 뒤 같은 문장을 3.5 s 뒤에 되풀이하지 않게
    var lastSpokenGuide by remember { mutableStateOf("") }
    LaunchedEffect(state.message, state.phase, muted, introductionGiven) {
        // 안내 음성이 끝난 뒤에만 — 음성 중에 바뀐 범위 안내가 긴 안내를 끊지 않게(§89)
        if (state.phase == PreparationPhase.WAITING && !muted && introductionGiven && state.message != lastSpokenGuide) {
            delay(3500) // 방향 안내를 끊거나 흔들리는 관측을 연달아 발화하지 않는다.
            if (!latestPaused) { speech.speak(state.message, flush = true); lastSpokenGuide = state.message }
        }
    }
    // 카운트다운이 멈추면 무엇이 안 보였는지(움직였는지) 말한다 — 한 프레임 튐은 말하지 않게 0.6 s 이어질 때만(§89 후속)
    LaunchedEffect(state.trackingHold, muted) {
        if (state.trackingHold && !muted && !paused) {
            delay(600)
            // 설명 문장이 나오는 중이면 그 끝(감시 시한 안)까지 기다린 뒤 조건을 다시 본다 — ESS 를 문장 중간에서 자르지 않는다(§101)
            while (introPlaying && speech.isSpeaking) delay(100)
            val now = controller.state
            if (now.trackingHold && now.phase == PreparationPhase.COUNTDOWN && !latestPaused) { reasonSpoken = true; speech.speak(now.message, flush = true); lastSpokenGuide = now.message }
        }
    }
    // 처음으로 돌아가면 그 이유를 바로 말한다("왼손이 안 보여요 … 다시 자리 잡으면 3초를 셉니다")
    LaunchedEffect(state.restarts) {
        if (state.restarts > 0 && !muted && !latestPaused && state.phase == PreparationPhase.WAITING) {
            while (introPlaying && speech.isSpeaking) delay(100)
            reasonSpoken = true; speech.speak(state.message, flush = true); lastSpokenGuide = state.message
        }
    }
    val counting = state.phase == PreparationPhase.COUNTDOWN
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("운동 준비", color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (onShowGuide != null) ExerciseGuideButton(guideName, { cancel(); onShowGuide() })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 114.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (counting) RingGauge(state.progress, 110.dp, 5.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${state.seconds}", color = c.text, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                    Text(if(state.trackingHold) "잠시 확인 중" else "시작까지", color = c.text2, fontSize = 11.sp)
                }
            } else CaptureDirectionDemo(profile.capture, frontCamera, Modifier.size(114.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.capture.placement, color = c.text, fontSize = 15.sp, lineHeight = 21.sp)
                // 약속 문장은 화면 고정 문구(§101 — 음성에서 뺐다. 종전 안내 맨 끝에 있어 8/10 세트에서 들리지 않았다)
                Text(START_PROMISE, color = c.text3, fontSize = 12.sp, lineHeight = 17.sp)
                // 멈춘·처음으로 돌아간·아직 못 잡은 이유를 화면에도 — 배치 안내만 떠 있으면 무엇이 문제인지 모른다(§89 후속)
                val reason = state.message.takeIf {
                    (state.trackingHold || state.phase == PreparationPhase.WAITING) && it !in GENERIC_PREPARATION_MESSAGES
                }
                if (reason != null) Text(reason, color = if (reason == CapturePreparationController.READY_MESSAGE) c.text else c.warn, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                // 세는 조건 전문(ESS + DET, §101) — 음성이 못 다 말한 내용은 화면이 가진다(원칙 #5). 그 아래 바닥 범위 문장(spec §99, PostureScope.FLOOR_LINES)
                profile.countingConditions?.let { Text(it, color = c.text2, fontSize = 13.sp, lineHeight = 18.sp) }
                com.example.trex_kotlin.posture.PostureScope.floorLine(profile.referenceExercise)?.let {
                    Text(it, color = c.text3, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        if (cameraError != null) {
            Text(cameraError, color = c.text2, fontSize = 13.sp)
            TextButton(onClick = { cancel(); onFallback() }) { Text("자세 비교 없이 계속", color = c.text) }
        }
        }
        modeControl()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (!paused) { userPaused=false; speech.stop(); controller.manual(SystemClock.elapsedRealtime(),5);state=controller.state }
            }, enabled = !paused && !userPaused && (!counting || state.trackingHold), modifier = Modifier.weight(1f)) {
                Text("5초 후 시작", color = c.text2, fontSize = 13.sp)
            }
            SessionTool("음성", if (muted) "음성 안내 켜기" else "음성 안내 끄기",
                if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                onMute, Modifier.width(48.dp))
            SessionTool("카메라", "카메라 전환", Icons.Rounded.Cameraswitch, { cancel(); onCamera() }, Modifier.width(48.dp))
            PreparationPauseAction(userPaused || paused, !delivered,
                {
                    if (paused) { userPaused = false; onTogglePause() }
                    else { userPaused = !userPaused; if (userPaused) cancel() }
                }, Modifier.width(104.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("나가기", { cancel(); onExit() }, Modifier.weight(1f))
            Cta("준비 건너뛰기", {
                // 준비만 종료한다. 운동 완료/세트 건너뛰기 콜백을 호출하지 않는다.
                if (!paused && !delivered) {
                    delivered=true; cancel(); start(true)
                }
            }, Modifier.weight(1.8f), enabled = !paused && !delivered)

        }
    }
}

/** 실제 분석 이미지의 여백만 표시한다. 체형을 맞추는 실루엣이나 올바른 자세 표식은 아니다. */
@Composable
internal fun PreparationFramingOverlay(sample: PoseSample, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (sample.imageWidth <= 0 || sample.imageHeight <= 0) return@Canvas
        val factor = minOf(size.width/sample.imageWidth, size.height/sample.imageHeight)
        val width = sample.imageWidth*factor; val height = sample.imageHeight*factor
        val x = (size.width-width)/2 + width*.04f; val y = (size.height-height)/2 + height*.04f
        val right = x+width*.92f; val bottom = y+height*.92f
        val length = 13.dp.toPx(); val color = androidx.compose.ui.graphics.Color.White.copy(alpha=.65f)
        for ((corner,sign) in listOf(Offset(x,y) to Offset(1f,1f),Offset(right,y) to Offset(-1f,1f),
            Offset(x,bottom) to Offset(1f,-1f),Offset(right,bottom) to Offset(-1f,-1f))) {
            drawLine(color,corner,corner+Offset(sign.x*length,0f),2.dp.toPx(),StrokeCap.Round)
            drawLine(color,corner,corner+Offset(0f,sign.y*length),2.dp.toPx(),StrokeCap.Round)
        }
    }
}

/** 고정된 휴대폰을 향해 인물이 회전한다. 좌우 표시는 사용자 본인의 어깨다. */
@Composable
internal fun CaptureDirectionDemo(capture: CapturePosition, mirror: Boolean, modifier: Modifier = Modifier) {
    val c = Trex.c
    val target = when (capture) {
        CapturePosition.FRONT -> 0f
        CapturePosition.RIGHT_FRONT, CapturePosition.FLOOR_FRONT -> -40f
        CapturePosition.LEFT_FRONT -> 40f
        CapturePosition.SIDE, CapturePosition.FLOOR_SIDE -> -82f
    }
    val turn = remember(capture) { Animatable(target) }
    LaunchedEffect(capture) {
        // 시스템의 동작 줄이기를 따른다. 정지 그림도 항상 목표 방향을 보여 준다.
        while (ValueAnimator.areAnimatorsEnabled()) {
            delay(1400); turn.animateTo(if (target == 0f) 35f else 0f, tween(650)); delay(300)
            turn.animateTo(target, tween(1800)); delay(1200)
        }
        turn.snapTo(target)
    }
    val theta = Math.toRadians(turn.value.toDouble())
    val floor = capture == CapturePosition.FLOOR_SIDE || capture == CapturePosition.FLOOR_FRONT
    Column(modifier.semantics { contentDescription = "${capture.title} 방향 시범 · 휴대폰은 고정하고 몸을 돌려 주세요" },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val scale = size.minDimension
            // x의 음수는 사용자 오른쪽. 전면 미리보기와 동일하게 좌우를 뒤집는다.
            fun p(x: Float, y: Float, z: Float = 0f): Offset {
                val px = x*cos(theta).toFloat()+z*sin(theta).toFloat()
                val depth = -x*sin(theta).toFloat()+z*cos(theta).toFloat()
                return Offset(size.width*.5f + (if (mirror) -px else px)*scale,
                    size.height*.40f + y*scale-depth*scale*.09f)
            }
            val gray = c.text2.copy(alpha = .55f)
            fun limb(a: Offset, b: Offset, color: androidx.compose.ui.graphics.Color, width: Float = .045f) =
                drawLine(color, a, b, scale*width, StrokeCap.Round)
            val head = if (floor) p(0f,-.14f,.19f) else p(0f,-.27f)
            drawCircle(c.text, scale*.06f, head)
            val sl=p(-.12f,if(floor)-.09f else -.15f,if(floor).10f else 0f)
            val sr=p(.12f,if(floor)-.09f else -.15f,if(floor).10f else 0f)
            val hl=p(-.07f,.10f,if(floor)-.15f else 0f);val hr=p(.07f,.10f,if(floor)-.15f else 0f)
            val torso=Path().apply { moveTo(sl.x,sl.y);lineTo(sr.x,sr.y);lineTo(hr.x,hr.y);lineTo(hl.x,hl.y);close() }
            drawPath(torso,c.text.copy(alpha=.8f))
            val near = if(capture==CapturePosition.LEFT_FRONT) c.text else c.primaryText
            for ((side,color) in listOf(-1f to near,1f to if(capture==CapturePosition.LEFT_FRONT)c.primaryText else gray)) {
                val shoulder=if(side<0)sl else sr;val hip=if(side<0)hl else hr
                val elbow=p(side*.16f,if(floor).13f else .015f,if(floor).15f else 0f)
                limb(shoulder,elbow,color);limb(elbow,p(side*.15f,.25f,if(floor).22f else 0f),color)
                val knee=p(side*.09f,if(floor).22f else .27f,if(floor)-.02f else 0f)
                limb(hip,knee,color,.055f);limb(knee,p(side*.10f,if(floor).24f else .41f,if(floor)-.27f else 0f),color,.05f)
            }
            // 휴대폰은 애니메이션 각도와 무관한 고정 좌표다.
            val phone=Offset(size.width*.5f-scale*.045f,size.height-scale*.12f)
            drawRoundRect(c.text2,phone,Size(scale*.09f,scale*.13f),CornerRadius(scale*.018f),style=Stroke(scale*.012f))
            drawCircle(c.primaryText,scale*.009f,phone+Offset(scale*.045f,scale*.025f))
        }
        Text(when(capture) {
            CapturePosition.RIGHT_FRONT -> "오른어깨가 가까이"
            CapturePosition.LEFT_FRONT -> "왼어깨가 가까이"
            else -> capture.title
        }, color = c.text2, fontSize = 10.sp)
    }
}
