package com.example.trex_kotlin

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
@Composable
internal fun CapturePreparationPanel(
    profile: ExerciseProfile, sample: PoseSample, sampleAt: Long, paused: Boolean,
    frontCamera: Boolean, muted: Boolean, onMute: () -> Unit, onCamera: () -> Unit,
    speech: SpeechCoach, onStart: (skipped: Boolean) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier,
    cameraError: String? = null, onFallback: () -> Unit = {},
    modeControl: @Composable () -> Unit = {},
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
    fun cancel() { controller.cancel(); state = controller.state; speech.stop() }
    LaunchedEffect(frontCamera, paused, userPaused) {
        cancel(); direction.clear()
        if (!paused && !userPaused) {
            if (!introductionGiven) {
                if (!speech.muted) {
                    speech.speak(profile.preparationInstruction, flush = true)
                    val deadline = SystemClock.elapsedRealtime() + 12000L
                    while (!speech.muted && controller.state.phase == PreparationPhase.IDLE && speech.isSpeaking && SystemClock.elapsedRealtime() < deadline) delay(50)
                    // TTS 콜백 누락에도 준비가 무한히 멈추지 않는다.
                    if (controller.state.phase == PreparationPhase.IDLE && speech.isSpeaking) speech.stop()
                }
                introductionGiven = true
            }
            // 직접 시작을 누른 경우 그 카운트를 다시 초기화하지 않는다.
            if (controller.state.phase == PreparationPhase.IDLE) {
                controller.arm(SystemClock.elapsedRealtime()); state=controller.state
            }
        }
    }
    DisposableEffect(Unit) { onDispose { controller.cancel(); speech.stop() } }
    LaunchedEffect(sampleAt) {
        if (!latestPaused && !userPaused && sampleAt > 0L) {
            controller.observe(sampleAt, direction.check(CaptureFraming.inspect(sample, profile.capture, profile.floor),sample.features))
            state = controller.state
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            delay(100)
            if (latestPaused || userPaused) { controller.cancel(); state = controller.state; continue }
            state = controller.tick(SystemClock.elapsedRealtime())
            if (state.phase == PreparationPhase.STARTED && !delivered) { delivered = true; start(false) }
        }
    }
    LaunchedEffect(state.phase, state.seconds, state.trackingHold, muted) {
        if (!muted && !paused && !state.trackingHold && state.phase == PreparationPhase.COUNTDOWN && state.seconds in 1..5) {
            if (speech.ready) speech.speak(state.seconds.toString(), flush = true)
            else runCatching { tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100) }
        }
    }
    LaunchedEffect(state.message, state.phase, muted) {
        if (state.phase == PreparationPhase.WAITING && !muted) {
            delay(3500) // 방향 안내를 끊거나 흔들리는 관측을 연달아 발화하지 않는다.
            if (!latestPaused) speech.speak(state.message, flush = true)
        }
    }
    val counting = state.phase == PreparationPhase.COUNTDOWN
    val active = state.phase != PreparationPhase.IDLE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("운동 준비", color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(if(userPaused) "준비를 잠시 멈췄어요." else profile.preparationInstruction, color = c.text2, fontSize = 12.sp)
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
                if(active && state.message != "몸이 화면에 잡히면 5초 뒤 시작해요.") Text(state.message, color=c.text2, fontSize=12.sp, lineHeight=17.sp)
                Text(if (profile.floor) "무릎을 대거나 편하게 앉아 준비하세요." else "휴대폰은 고정 · 몸만 움직여 주세요.",
                    color = c.text2, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        if (cameraError != null) {
            Text(cameraError, color = c.text2, fontSize = 13.sp)
            TextButton(onClick = { cancel(); onFallback() }) { Text("자세 비교 없이 계속", color = c.text) }
        }
        if(state.restarts>=2) Text("인식이 계속 끊기면 준비를 건너뛰고 시작할 수 있어요.",color=c.text2,fontSize=12.sp)
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
            PreparationPauseAction(userPaused, !paused && !delivered,
                { userPaused = !userPaused; if (userPaused) cancel() }, Modifier.width(104.dp))
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
