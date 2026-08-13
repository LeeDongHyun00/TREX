package com.example.trex_kotlin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.camera.PoseCameraError
import com.example.trex_kotlin.camera.PoseCameraPreview
import com.example.trex_kotlin.camera.PoseCameraStatus
import com.example.trex_kotlin.pose.PoseFeedback
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.formcheck.FormCheckSetSummary
import com.example.trex_kotlin.pose.release.PostureCorrectionLifecycle
import com.example.trex_kotlin.pose.release.PostureCorrectionRuntimeFacade
import java.util.Locale
import kotlinx.coroutines.delay

private enum class PosturePhase {
    CameraCheck,
    Stabilizing,
    Countdown,
    Active,
    SetComplete,
    Rest,
}

private enum class TimerPhase {
    Countdown,
    Active,
    ActualInput,
    Rest,
}

private data class ExerciseSpec(
    val targetReps: Int,
    val targetLabel: String,
    val totalSets: Int,
    val restSeconds: Int,
)

private data class WorkoutFeedback(
    val beep: () -> Unit,
    val speak: (String) -> Unit,
)

private const val CAMERA_READY_FRAME_COUNT = 8
private const val POSE_FRAME_STALE_AFTER_MS = 1_200L
private const val POSE_FRAME_FRESHNESS_POLL_MS = 250L

@Composable
fun TimerSessionScreen(
    workout: Workout,
    index: Int,
    total: Int,
    nextWorkout: Workout?,
    elapsedSeconds: Int,
    notice: String? = null,
    onNoticeConsumed: () -> Unit = {},
    onPausedChange: (Boolean) -> Unit = {},
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    KeepScreenOn()

    val spec = remember(workout.id) { workout.exerciseSpec() }
    val lifecyclePaused = rememberTrexLifecyclePaused()
    val haptic = LocalHapticFeedback.current
    var muted by remember(workout.id) { mutableStateOf(false) }
    val feedback = rememberWorkoutFeedback(muted = muted)
    var phase by remember(workout.id) { mutableStateOf(TimerPhase.Countdown) }
    var currentSet by remember(workout.id) { mutableIntStateOf(1) }
    var countdown by remember(workout.id, currentSet) { mutableIntStateOf(3) }
    var restSeconds by remember(workout.id) { mutableIntStateOf(spec.restSeconds) }
    var paused by remember(workout.id) { mutableStateOf(false) }
    var actualCountInput by remember(workout.id, currentSet) { mutableStateOf(spec.targetReps.toString()) }
    var actualRecords by remember(workout.id) { mutableStateOf<List<Int>>(emptyList()) }
    var onboardingVisible by remember(workout.id) { mutableStateOf(true) }
    var noticeVisible by remember(notice) { mutableStateOf(notice != null) }
    // Shown once per workout, before the first set: the camera surface is otherwise entirely
    // non-verbal and nothing on it explains itself.
    var formCheckIntroVisible by remember(workout.id) { mutableStateOf(workout.formCheck) }
    // The set's observations, held only for as long as the set lasts. Keyed on the set number so
    // a new set drops the previous one; nothing here reaches the workout record.
    var formCheckSummary by remember(workout.id, currentSet) {
        mutableStateOf<FormCheckSetSummary?>(null)
    }
    val blocked = paused || lifecyclePaused
    val blockedState = rememberUpdatedState(blocked)

    LaunchedEffect(blocked) {
        onPausedChange(blocked)
    }
    DisposableEffect(Unit) {
        onDispose { onPausedChange(false) }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            noticeVisible = true
            delay(4200)
            noticeVisible = false
            onNoticeConsumed()
        }
    }

    LaunchedEffect(phase, currentSet, workout.id) {
        if (phase == TimerPhase.Countdown) {
            feedback.speak("${currentSet}세트를 시작합니다")
            for (value in 3 downTo 1) {
                countdown = value
                feedback.beep()
                waitOneSecond { blockedState.value }
            }
            phase = TimerPhase.Active
        }
    }

    LaunchedEffect(phase, currentSet, workout.id) {
        if (phase == TimerPhase.Rest) {
            while (restSeconds > 0) {
                waitOneSecond { blockedState.value }
                restSeconds -= 1
                if (restSeconds in 1..10) {
                    feedback.beep()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            if (currentSet >= spec.totalSets) {
                onNext()
            } else {
                currentSet += 1
                countdown = 3
                phase = TimerPhase.Countdown
            }
        }
    }

    fun beginRest() {
        restSeconds = spec.restSeconds
        phase = TimerPhase.Rest
    }

    fun finishSetWithActualCount() {
        val actual = actualCountInput.toIntOrNull()?.coerceAtLeast(0) ?: spec.targetReps
        actualRecords = actualRecords + actual
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (currentSet >= spec.totalSets && nextWorkout == null) {
            onNext()
        } else {
            beginRest()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        // Hoisted above the Crossfade on purpose: phase transitions compose old and new
        // subtrees at once, and two live PoseCameraPreview instances would race to bind the
        // one front camera. One long-lived layer also keeps the permission answer and the
        // person lock across Countdown/Active/ActualInput; it only rests during Rest, whose
        // screen covers this backdrop anyway. At most one camera layer ever exists: the form
        // check supersedes the plain guide.
        if (workout.formCheck) {
            SessionFormCheckLayer(
                exercise = workout.exercise,
                // Observation stops the moment the user declares the set over, so the walk back
                // to the phone cannot be counted as part of it.
                paused = blocked || phase == TimerPhase.Rest ||
                    phase == TimerPhase.ActualInput || formCheckIntroVisible,
                attemptResetKey = currentSet,
                onAnnounce = { phrase -> feedback.speak(phrase) },
                onSetObserved = { summary -> formCheckSummary = summary },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (workout.cameraGuide) {
            SessionCameraGuideLayer(
                paused = blocked || phase == TimerPhase.Rest,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Crossfade(targetState = phase, label = "timer-session-phase") { visiblePhase ->
            when (visiblePhase) {
                TimerPhase.Rest -> RestScreen(
                    workout = workout,
                    nextWorkout = nextWorkout,
                    currentSet = currentSet,
                    totalSets = spec.totalSets,
                    restSeconds = restSeconds,
                    restTotal = spec.restSeconds,
                    elapsedSeconds = elapsedSeconds,
                    muted = muted,
                    paused = blocked,
                    // Standing still and close to the phone is the only moment the set's
                    // observations are legible, so this is where the sentences live.
                    formCheckSummary = formCheckSummary,
                    onToggleMute = { muted = !muted },
                    onTogglePause = { paused = !paused },
                    onSkip = onNext,
                )

                else -> TimerActiveScaffold(
                    workout = workout,
                    spec = spec,
                    index = index,
                    total = total,
                    currentSet = currentSet,
                    elapsedSeconds = elapsedSeconds,
                    completedSets = actualRecords.size,
                    countdown = countdown,
                    phase = visiblePhase,
                    muted = muted,
                    paused = blocked,
                    onToggleMute = { muted = !muted },
                    onTogglePause = { paused = !paused },
                    onSkip = onNext,
                    onSetComplete = {
                        actualCountInput = spec.targetReps.toString()
                        phase = TimerPhase.ActualInput
                    },
                    onExit = onExit,
                )
            }
        }

        AnimatedVisibility(
            visible = noticeVisible && notice != null,
            enter = fadeIn() + scaleIn(initialScale = 0.98f),
            exit = fadeOut() + scaleOut(targetScale = 0.98f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 54.dp),
        ) {
            NoticePill(text = notice.orEmpty())
        }

        if (phase == TimerPhase.ActualInput) {
            ActualCountDialog(
                setLabel = "${currentSet}세트 완료",
                planned = spec.targetLabel,
                value = actualCountInput,
                onValueChange = { actualCountInput = it.numericText().take(3) },
                onDismiss = { phase = TimerPhase.Active },
                onConfirm = ::finishSetWithActualCount,
            )
        }

        if (onboardingVisible) {
            SessionOnboardingOverlay(
                postureMode = false,
                onDone = { onboardingVisible = false },
            )
        }

        if (!onboardingVisible && formCheckIntroVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                FormCheckIntroCard(
                    exercise = workout.exercise,
                    onDismiss = { formCheckIntroVisible = false },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
fun PostureSessionScreen(
    workout: Workout,
    index: Int,
    total: Int,
    nextWorkout: Workout?,
    elapsedSeconds: Int,
    onPausedChange: (Boolean) -> Unit = {},
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val availability = PostureCorrectionRuntimeFacade.availability(workout.exercise)
    val notice = when (availability.lifecycle) {
        PostureCorrectionLifecycle.CATALOG_ONLY ->
            "AI Hub 자세 기준 ${availability.catalogCriterionCount}개를 검증 중이라 타이머 모드로 실행해요."
        PostureCorrectionLifecycle.SHADOW ->
            "자세 평가를 내부 검증 중이라 사용자 피드백 없이 타이머 모드로 실행해요."
        else ->
            "이 운동은 검증된 실시간 자세 평가를 지원하지 않아 타이머 모드로 실행해요."
    }
    TimerSessionScreen(
        workout = workout.withPostureCorrection(false),
        index = index,
        total = total,
        nextWorkout = nextWorkout,
        elapsedSeconds = elapsedSeconds,
        notice = notice,
        onPausedChange = onPausedChange,
        onNext = onNext,
        onExit = onExit,
    )
}

@Composable
fun SessionCompleteScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(TrexLime),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = TrexDark, modifier = Modifier.size(38.dp))
        }
        Text(
            text = "DONE",
            color = TrexLime,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 22.dp),
        )
        ScreenTitle(
            text = "오늘 운동을 끝냈어룡",
            color = Color.White,
        )
        Text(
            text = "오늘 완료한 운동을 기록에 반영했어요. 내일 같은 시간에 만나요.",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TrexButton(
            text = "홈으로",
            onClick = onDone,
            modifier = Modifier
                .padding(top = 32.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun TimerActiveScaffold(
    workout: Workout,
    spec: ExerciseSpec,
    index: Int,
    total: Int,
    currentSet: Int,
    elapsedSeconds: Int,
    completedSets: Int,
    countdown: Int,
    phase: TimerPhase,
    muted: Boolean,
    paused: Boolean,
    onToggleMute: () -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    onSetComplete: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // With a camera layer on, the backdrop is the hoisted layer behind this
                // scaffold; an opaque gradient here would hide it.
                if (workout.cameraGuide || workout.formCheck) {
                    Modifier
                } else {
                    Modifier.background(
                        Brush.verticalGradient(listOf(Color(0xFF0D1117), TrexDark)),
                    )
                },
            ),
    ) {
        if (!workout.cameraGuide && !workout.formCheck) {
            WorkoutIllustration(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(390.dp),
                active = phase == TimerPhase.Active && !paused,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 42.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            SessionTopControls(
                title = workout.name,
                subtitle = "운동 ${index + 1}/$total · ${elapsedSeconds.asClock()}",
                muted = muted,
                paused = paused,
                onToggleMute = onToggleMute,
                onTogglePause = onTogglePause,
                onSkip = onSkip,
                onExit = onExit,
            )

            MiniSetProgress(
                completed = if (phase == TimerPhase.Rest) currentSet else completedSets,
                total = spec.totalSets,
                modifier = Modifier.padding(top = 18.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "SET $currentSet/${spec.totalSets}",
                    color = TrexLime,
                    fontSize = 42.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = workout.name,
                    color = Color.White,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = "${workout.loadLabel()} · 목표 ${spec.targetLabel}",
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (phase == TimerPhase.Countdown) {
                    CountdownNumber(
                        value = countdown,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                } else {
                    StatusCapsule(
                        icon = Icons.Rounded.PlayArrow,
                        text = if (paused) "일시정지됨" else "운동 진행 중",
                        modifier = Modifier.padding(top = 28.dp),
                    )
                }
            }

            TrexButton(
                text = "${currentSet}세트 완료",
                onClick = onSetComplete,
                enabled = phase == TimerPhase.Active,
                icon = Icons.Rounded.Check,
                modifier = Modifier.fillMaxWidth(),
                height = 58.dp,
            )
        }
    }
}

@Composable
private fun PostureActiveScaffold(
    workout: Workout,
    spec: ExerciseSpec,
    index: Int,
    total: Int,
    currentSet: Int,
    currentRep: Int,
    countdown: Int,
    stabilizeSeconds: Int,
    scanStep: Int,
    phase: PosturePhase,
    trackingLost: Boolean,
    postureScore: Int?,
    setScores: List<Int>,
    elapsedSeconds: Int,
    muted: Boolean,
    paused: Boolean,
    poseFrame: PoseFrame?,
    poseFeedback: PoseFeedback?,
    cameraStatus: PoseCameraStatus,
    cameraError: PoseCameraError?,
    onDisplayFrame: (PoseFrame) -> Unit,
    onCameraError: (PoseCameraError) -> Unit,
    onCameraStatusChanged: (PoseCameraStatus) -> Unit,
    onToggleMute: () -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    onExit: () -> Unit,
) {
    val headline = when {
        cameraError != null -> cameraError.userMessage()
        cameraStatus == PoseCameraStatus.Initializing -> "자세 인식 모델을 준비하고 있어요"
        trackingLost -> "관절이 화면 밖으로 벗어났어요"
        phase == PosturePhase.CameraCheck -> "주요 관절 감지 중"
        phase == PosturePhase.Stabilizing -> "준비 자세 유지"
        phase == PosturePhase.Countdown -> "곧 시작합니다"
        phase == PosturePhase.Active -> poseFeedback?.message ?: "동작을 확인하고 있어요."
        else -> "세트 완료"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CameraFeedBackground()
        PoseCameraPreview(
            modifier = Modifier.fillMaxSize(),
            active = phase != PosturePhase.SetComplete,
            // Display landmarks are overlay-only. Evaluation must consume the attested observation.
            onPoseObservation = { update -> update.displayFrame?.let(onDisplayFrame) },
            onError = onCameraError,
            onStatusChanged = onCameraStatusChanged,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f)),
        )

        PoseSkeletonOverlay(
            frame = poseFrame,
            trackingLost = trackingLost,
            modifier = Modifier.fillMaxSize().alpha(0.88f),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(top = 38.dp, bottom = 22.dp)
                .navigationBarsPadding(),
        ) {
            SessionTopControls(
                title = workout.name,
                subtitle = "${currentSet}/${spec.totalSets}세트 · 운동 ${index + 1}/$total · ${elapsedSeconds.asClock()}",
                muted = muted,
                paused = paused,
                onToggleMute = onToggleMute,
                onTogglePause = onTogglePause,
                onSkip = onSkip,
                onExit = onExit,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RepCounter(
                    current = currentRep,
                    target = spec.targetReps,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.weight(1f))

            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "posture-center-message",
            ) { targetPhase ->
                when (targetPhase) {
                    PosturePhase.CameraCheck -> JointLegend(scanStep = scanStep)
                    PosturePhase.Stabilizing -> CenterStagePrompt(title = "준비 자세 유지", value = stabilizeSeconds.toString())
                    PosturePhase.Countdown -> CenterStagePrompt(title = "카운트다운", value = countdown.toString())
                    else -> Spacer(Modifier.height(0.dp))
                }
            }

            GlassPostureCard(
                headline = headline,
                score = postureScore,
                setScores = setScores,
                trackingLost = trackingLost,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun RestScreen(
    workout: Workout,
    nextWorkout: Workout?,
    currentSet: Int,
    totalSets: Int,
    restSeconds: Int,
    restTotal: Int,
    elapsedSeconds: Int,
    muted: Boolean,
    paused: Boolean,
    formCheckSummary: FormCheckSetSummary? = null,
    onToggleMute: () -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = ((restTotal - restSeconds) / restTotal.toFloat()).coerceIn(0f, 1f),
        label = "rest-progress",
    )
    val movingToNextWorkout = currentSet >= totalSets
    val targetWorkout = if (movingToNextWorkout) nextWorkout else workout
    val nextSet = if (movingToNextWorkout) 1 else currentSet + 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF080A0D), TrexDark))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp)
                .padding(top = 42.dp, bottom = 26.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("REST", color = TrexLime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "전체 경과 ${elapsedSeconds.asClock()}",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 12.sp,
                    )
                }
                IconCircleButton(
                    icon = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    onClick = onToggleMute,
                    size = 40.dp,
                    background = Color.White.copy(alpha = 0.1f),
                    contentDescription = "음소거",
                )
                Spacer(Modifier.width(8.dp))
                HiddenSkipButton(onClick = onSkip)
            }

            Spacer(Modifier.weight(0.7f))

            Box(modifier = Modifier.size(246.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.09f),
                        radius = size.minDimension / 2f - stroke.width,
                        style = stroke,
                    )
                    drawArc(
                        color = if (restSeconds <= 10) TrexWarning else TrexLime,
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = stroke,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (restSeconds <= 10) "준비 신호" else "휴식",
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = restSeconds.asClock(),
                        color = Color.White,
                        fontSize = 48.sp,
                        lineHeight = 56.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (paused) {
                        Text("일시정지됨", color = TrexWarning, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            formCheckSummary?.let { summary ->
                FormCheckSetSummaryCard(
                    summary = summary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            }

            WorkoutPreviewCard(
                title = if (movingToNextWorkout) "다음 운동" else "다음 세트",
                workout = targetWorkout,
                setLabel = if (targetWorkout == null) "운동 완료" else "SET $nextSet/${targetWorkout.exerciseSpec().totalSets}",
                modifier = Modifier.fillMaxWidth(),
            )

            TrexButton(
                text = if (paused) "휴식 재개" else "휴식 일시정지",
                onClick = onTogglePause,
                icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth(),
                container = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
            )
        }
    }
}

@Composable
private fun ActualCountDialog(
    setLabel: String,
    planned: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = TrexDarkAlt.copy(alpha = 0.98f),
            contentColor = Color.White,
            border = dimBorder(0.14f),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(setLabel, fontSize = 12.sp, color = TrexLime, fontWeight = FontWeight.SemiBold)
                        Text("실제 수행 횟수", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconCircleButton(
                        icon = Icons.Rounded.Close,
                        onClick = onDismiss,
                        size = 38.dp,
                        background = Color.White.copy(alpha = 0.08f),
                        contentDescription = "닫기",
                    )
                }
                Text(
                    text = "계획 $planned 와 다르면 실제 기록을 남겨요.",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TrexTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = "수행 횟수",
                    keyboardType = KeyboardType.Number,
                    leadingIcon = Icons.Rounded.FitnessCenter,
                    modifier = Modifier.padding(top = 18.dp),
                )
                TrexButton(
                    text = "기록하고 휴식",
                    onClick = onConfirm,
                    icon = Icons.Rounded.Check,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PostureSetCompleteDialog(
    set: Int,
    totalSets: Int,
    score: Int?,
    nextWorkout: Workout?,
    isLastSet: Boolean,
    onContinue: () -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF12161D).copy(alpha = 0.98f),
            contentColor = Color.White,
            border = dimBorder(0.16f),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularScoreGauge(score = score, size = 126)
                Text(
                    text = "$set/$totalSets 세트 완료",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = if (isLastSet && nextWorkout == null) {
                        "마지막 운동까지 완료했어요."
                    } else if (isLastSet) {
                        "휴식 후 ${nextWorkout?.name.orEmpty()}으로 이동해요."
                    } else {
                        if (score == null) {
                            "검증된 자세 점수 없이 세트만 완료했어요. 휴식 후 이어가요."
                        } else {
                            "세트 점수가 기록되었어요. 휴식 후 다음 세트로 이어가요."
                        }
                    },
                    color = Color.White.copy(alpha = 0.64f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TrexButton(
                    text = if (isLastSet && nextWorkout == null) "운동 완료" else "휴식 시작",
                    onClick = onContinue,
                    icon = Icons.Rounded.Check,
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionWarmupScreen(
    denied: Boolean,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (denied) Icons.Rounded.Visibility else Icons.Rounded.PhotoCamera,
                contentDescription = null,
                tint = if (denied) TrexWarning else TrexLime,
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            text = if (denied) "자세 교정 OFF로 전환 중" else "전면 카메라 준비 중",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = if (denied) {
                "카메라 권한이 없어 일반 운동 플로우로 이어갑니다."
            } else {
                "관절 감지를 위해 카메라 권한을 확인하고 있어요."
            },
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TrexButton(
            text = "나가기",
            onClick = onExit,
            modifier = Modifier
                .padding(top = 28.dp)
                .width(140.dp),
            container = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
        )
    }
}

@Composable
private fun SessionTopControls(
    title: String,
    subtitle: String,
    muted: Boolean,
    paused: Boolean,
    onToggleMute: () -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconCircleButton(
            icon = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            onClick = onToggleMute,
            size = 38.dp,
            background = Color.White.copy(alpha = 0.1f),
            contentDescription = "음소거",
        )
        Spacer(Modifier.width(8.dp))
        IconCircleButton(
            icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            onClick = onTogglePause,
            size = 38.dp,
            background = Color.White.copy(alpha = 0.1f),
            contentDescription = "일시정지",
        )
        Spacer(Modifier.width(8.dp))
        HiddenSkipButton(onClick = onSkip)
        Spacer(Modifier.width(8.dp))
        CloseButton(onClick = onExit)
    }
}

@Composable
private fun HiddenSkipButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .alpha(0.28f),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        contentColor = Color.White,
        border = dimBorder(0.08f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "운동 건너뛰기", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MiniSetProgress(
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val progress = (completed / total.toFloat()).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$completed/${total}세트 완료",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                color = TrexLime,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .padding(top = 7.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = TrexLime,
            trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

@Composable
private fun RepCounter(
    current: Int,
    target: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("COUNT", color = Color.White.copy(alpha = 0.56f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = current.toString(),
                color = Color.White,
                fontSize = 54.sp,
                lineHeight = 56.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "/$target",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 17.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun JointLegend(scanStep: Int) {
    val detected = when (scanStep) {
        0 -> 5
        1 -> 11
        else -> 13
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.44f),
        contentColor = Color.White,
        border = dimBorder(0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.AccessibilityNew, contentDescription = null, tint = TrexLime, modifier = Modifier.size(18.dp))
            Column {
                Text("주요 관절 감지", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("$detected/13 · 초록 감지, 빨강 미감지", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CenterStagePrompt(
    title: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
        CountdownNumber(value = value.toIntOrNull() ?: 0, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun CountdownNumber(
    value: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value.toString(),
        color = TrexLime,
        fontSize = 78.sp,
        lineHeight = 82.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
private fun StatusCapsule(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.36f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TrexLime, modifier = Modifier.size(14.dp))
        Text(text, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun NoticePill(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF161B22).copy(alpha = 0.94f))
            .border(1.dp, TrexWarning.copy(alpha = 0.36f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = TrexWarning, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

@Composable
private fun GlassPostureCard(
    headline: String,
    score: Int?,
    setScores: List<Int>,
    trackingLost: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = 0.14f),
        contentColor = Color.White,
        border = dimBorder(0.18f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (trackingLost) Icons.Rounded.Warning else Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = if (trackingLost) TrexWarning else TrexLime,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = if (trackingLost) "일시정지 안내" else "실시간 자세 피드백",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = if (setScores.isEmpty()) "세트 점수 기록 대기" else "기록된 세트 점수 ${setScores.joinToString("%, ")}%",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            CircularScoreGauge(score = score, size = 86)
        }
    }
}

@Composable
private fun CircularScoreGauge(
    score: Int?,
    size: Int,
) {
    val progress by animateFloatAsState(
        targetValue = ((score ?: 0) / 100f).coerceIn(0f, 1f),
        label = "score-gauge",
    )
    Box(modifier = Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = (size * 0.08f).dp.toPx(), cap = StrokeCap.Round)
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = this.size.minDimension / 2f - stroke.width,
                style = stroke,
            )
            drawArc(
                color = when {
                    score == null -> Color.White.copy(alpha = 0.22f)
                    score >= 85 -> TrexLime
                    else -> TrexWarning
                },
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "--", color = Color.White, fontSize = (size * 0.24f).sp, fontWeight = FontWeight.SemiBold)
            Text(if (score == null) "대기" else "%", color = Color.White.copy(alpha = 0.58f), fontSize = (size * 0.12f).sp)
        }
    }
}

@Composable
private fun WorkoutPreviewCard(
    title: String,
    workout: Workout?,
    setLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.1f),
        contentColor = Color.White,
        border = dimBorder(0.14f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF253145), TrexGreenDeep))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = TrexLime, modifier = Modifier.size(28.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 13.dp),
            ) {
                Text(title, color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
                Text(
                    text = workout?.name ?: "오늘 운동 완료",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = workout?.let { "${it.loadLabel()} · ${it.reps}" } ?: "마지막 세트까지 끝났어요",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Pill(setLabel, background = TrexLime, color = TrexDark)
        }
    }
}

@Composable
private fun SessionOnboardingOverlay(
    postureMode: Boolean,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.74f))
            .clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF141922).copy(alpha = 0.98f),
            contentColor = Color.White,
            border = dimBorder(0.14f),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = if (postureMode) "자세 교정 화면 안내" else "일반 운동 화면 안내",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                OnboardingRow(Icons.AutoMirrored.Rounded.VolumeUp, "음소거", "카운트다운과 음성 피드백을 켜고 끌 수 있어요.")
                OnboardingRow(Icons.Rounded.SkipNext, "건너뛰기", "작은 아이콘으로 숨겨져 있어 오터치를 줄여요.")
                if (postureMode) {
                    OnboardingRow(Icons.Rounded.Visibility, "스켈레톤", "초록색은 감지, 빨간색은 미감지 관절이에요.")
                    OnboardingRow(Icons.Rounded.Vibration, "자동 카운트", "횟수가 잡히면 진동으로 알려줘요.")
                } else {
                    OnboardingRow(Icons.Rounded.Check, "세트 완료", "세트 후 실제 수행 횟수를 기록해요.")
                    OnboardingRow(Icons.Rounded.Timer, "휴식 타이머", "마지막 10초는 준비 신호가 울려요.")
                }
                TrexButton(
                    text = "확인",
                    onClick = onDone,
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OnboardingRow(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TrexLime, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun CameraFeedBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF263348), Color(0xFF11161E), Color(0xFF050608)),
            ),
        )
        val stripeWidth = size.width / 9f
        for (i in 0..8) {
            drawRect(
                color = Color.White.copy(alpha = if (i % 2 == 0) 0.022f else 0.01f),
                topLeft = Offset(i * stripeWidth, 0f),
                size = androidx.compose.ui.geometry.Size(stripeWidth, size.height),
            )
        }
    }
}

@Composable
internal fun PoseSkeletonOverlay(
    frame: PoseFrame?,
    trackingLost: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val currentFrame = frame ?: return@Canvas
        val sourceAspect = currentFrame.imageAspectRatio.toFloat()
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        val targetAspect = size.width / size.height
        val drawnWidth: Float
        val drawnHeight: Float
        val offsetX: Float
        val offsetY: Float
        if (sourceAspect > targetAspect) {
            drawnWidth = size.width
            drawnHeight = size.width / sourceAspect
            offsetX = 0f
            offsetY = (size.height - drawnHeight) / 2f
        } else {
            drawnHeight = size.height
            drawnWidth = size.height * sourceAspect
            offsetX = (size.width - drawnWidth) / 2f
            offsetY = 0f
        }

        fun screenPoint(joint: PoseJoint): Offset? {
            val landmark = currentFrame.landmarks[joint] ?: return null
            if (landmark.confidence < 0.5) return null
            val normalizedX = if (currentFrame.isMirrored) 1.0 - landmark.x else landmark.x
            if (normalizedX !in -0.15..1.15 || landmark.y !in -0.15..1.15) return null
            return Offset(
                x = offsetX + normalizedX.toFloat() * drawnWidth,
                y = offsetY + landmark.y.toFloat() * drawnHeight,
            )
        }

        val links = listOf(
            PoseJoint.LEFT_EAR to PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_EAR to PoseJoint.RIGHT_SHOULDER,
            PoseJoint.LEFT_SHOULDER to PoseJoint.RIGHT_SHOULDER,
            PoseJoint.LEFT_SHOULDER to PoseJoint.LEFT_ELBOW,
            PoseJoint.LEFT_ELBOW to PoseJoint.LEFT_WRIST,
            PoseJoint.RIGHT_SHOULDER to PoseJoint.RIGHT_ELBOW,
            PoseJoint.RIGHT_ELBOW to PoseJoint.RIGHT_WRIST,
            PoseJoint.LEFT_SHOULDER to PoseJoint.LEFT_HIP,
            PoseJoint.RIGHT_SHOULDER to PoseJoint.RIGHT_HIP,
            PoseJoint.LEFT_HIP to PoseJoint.RIGHT_HIP,
            PoseJoint.LEFT_HIP to PoseJoint.LEFT_KNEE,
            PoseJoint.LEFT_KNEE to PoseJoint.LEFT_ANKLE,
            PoseJoint.RIGHT_HIP to PoseJoint.RIGHT_KNEE,
            PoseJoint.RIGHT_KNEE to PoseJoint.RIGHT_ANKLE,
            PoseJoint.LEFT_ANKLE to PoseJoint.LEFT_HEEL,
            PoseJoint.LEFT_HEEL to PoseJoint.LEFT_FOOT_INDEX,
            PoseJoint.RIGHT_ANKLE to PoseJoint.RIGHT_HEEL,
            PoseJoint.RIGHT_HEEL to PoseJoint.RIGHT_FOOT_INDEX,
        )
        val skeletonColor = if (trackingLost) TrexError else TrexLime
        links.forEach { (startJoint, endJoint) ->
            val start = screenPoint(startJoint) ?: return@forEach
            val end = screenPoint(endJoint) ?: return@forEach
            drawLine(
                color = skeletonColor.copy(alpha = 0.78f),
                start = start,
                end = end,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        PoseJoint.entries.forEach { joint ->
            val point = screenPoint(joint) ?: return@forEach
            drawCircle(
                color = Color.Black.copy(alpha = 0.36f),
                radius = 7.dp.toPx(),
                center = point,
            )
            drawCircle(
                color = skeletonColor,
                radius = 4.dp.toPx(),
                center = point,
            )
        }
    }
}

@Composable
private fun WorkoutIllustration(
    modifier: Modifier = Modifier,
    active: Boolean,
) {
    val motion by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        label = "workout-illustration-motion",
    )
    Canvas(modifier = modifier) {
        val centerX = size.width * 0.5f
        val baseY = size.height * 0.72f
        val dip = motion * 24.dp.toPx()
        val strokeWidth = 12.dp.toPx()
        val line = Color.White.copy(alpha = 0.18f)
        val accent = TrexLime.copy(alpha = 0.72f)

        drawCircle(accent.copy(alpha = 0.2f), radius = 120.dp.toPx(), center = Offset(centerX, baseY - 145.dp.toPx()))
        drawCircle(accent, radius = 30.dp.toPx(), center = Offset(centerX, baseY - 242.dp.toPx() + dip * 0.3f))
        drawLine(
            color = line,
            start = Offset(centerX, baseY - 210.dp.toPx() + dip * 0.3f),
            end = Offset(centerX - 6.dp.toPx(), baseY - 106.dp.toPx() + dip),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = line,
            start = Offset(centerX - 4.dp.toPx(), baseY - 176.dp.toPx() + dip * 0.4f),
            end = Offset(centerX - 92.dp.toPx(), baseY - 126.dp.toPx()),
            strokeWidth = strokeWidth * 0.78f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = line,
            start = Offset(centerX + 4.dp.toPx(), baseY - 176.dp.toPx() + dip * 0.4f),
            end = Offset(centerX + 92.dp.toPx(), baseY - 126.dp.toPx()),
            strokeWidth = strokeWidth * 0.78f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent,
            start = Offset(centerX - 6.dp.toPx(), baseY - 106.dp.toPx() + dip),
            end = Offset(centerX - 78.dp.toPx(), baseY - 18.dp.toPx()),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent,
            start = Offset(centerX - 5.dp.toPx(), baseY - 106.dp.toPx() + dip),
            end = Offset(centerX + 82.dp.toPx(), baseY - 20.dp.toPx()),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun KeepScreenOn(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = context.findActivity()?.window
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
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> paused = true

                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_START,
                -> paused = false

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return paused
}

@Composable
private fun rememberWorkoutFeedback(muted: Boolean): WorkoutFeedback {
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

private suspend fun waitOneSecond(paused: () -> Boolean) {
    var remaining = 1000
    while (remaining > 0) {
        delay(100)
        if (!paused()) {
            remaining -= 100
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Workout.exerciseSpec(): ExerciseSpec {
    val numbers = Regex("\\d+").findAll(reps).map { it.value.toInt() }.toList()
    val totalSets = when {
        reps.contains("세트") && numbers.size >= 2 -> numbers.last().coerceAtLeast(1)
        else -> 1
    }
    val target = numbers.firstOrNull()?.coerceAtLeast(1) ?: 1
    val targetLabel = when {
        reps.contains("초") -> "${target}초"
        reps.contains("분") && !reps.contains("회") -> reps
        else -> "${target}회"
    }
    return ExerciseSpec(
        targetReps = target,
        targetLabel = targetLabel,
        totalSets = totalSets,
        restSeconds = 30,
    )
}

private fun Workout.loadLabel(): String = when (exercise.typeInfoType) {
    "맨몸 운동" -> "체중"
    "바벨/덤벨" -> "중량"
    "기구" -> "기구"
    else -> error("Unknown AI Hub type_info.type: ${exercise.typeInfoType}")
}

internal fun PoseCameraError.userMessage(): String = when (this) {
    PoseCameraError.CameraPermissionMissing -> "카메라 권한을 확인해 주세요"
    PoseCameraError.FrontCameraUnavailable -> "전면 카메라를 사용할 수 없어요"
    is PoseCameraError.MissingModelAsset -> "자세 인식 모델을 불러올 수 없어요"
    is PoseCameraError.CameraInitializationFailed -> "카메라를 시작하지 못했어요"
    is PoseCameraError.LandmarkerInitializationFailed -> "자세 인식 엔진을 시작하지 못했어요"
    is PoseCameraError.ObserverArtifactVerificationFailed ->
        "검증된 자세 인식 구성을 불러오지 못했어요"
    is PoseCameraError.FrameAnalysisFailed -> "카메라 프레임을 분석하지 못했어요"
}

private fun String.numericText(): String = filter(Char::isDigit)

private fun Int.asClock(): String {
    val minute = this / 60
    val second = this % 60
    return minute.toString().padStart(2, '0') + ":" + second.toString().padStart(2, '0')
}
