package com.example.trex_kotlin

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.camera.PoseCameraError
import com.example.trex_kotlin.camera.PoseCameraPreview
import com.example.trex_kotlin.camera.PoseCameraStatus
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.placement.PlacementCameraState
import com.example.trex_kotlin.pose.placement.PlacementCoachAttemptRecorder
import com.example.trex_kotlin.pose.placement.PlacementCoachDisplay
import com.example.trex_kotlin.pose.placement.PlacementCoachDisplayPolicy
import com.example.trex_kotlin.pose.placement.PlacementCoachGoal
import com.example.trex_kotlin.pose.placement.PlacementCoachGuidanceStabilizer
import com.example.trex_kotlin.pose.placement.PlacementCoachStage
import com.example.trex_kotlin.pose.placement.toPlacementObservedSignal
import kotlinx.coroutines.delay

private const val GOAL_ADVANCE_DELAY_MS = 1_600L

/**
 * Display-only camera placement coach.
 *
 * The screen shows what the camera can see and how to improve the framing. It opens no evaluation
 * session, reads no criterion and renders no outcome, so it needs no release authorization; the
 * rules it follows are written down in `docs/pose-nonverdict-display-policy.v1.md`.
 */
@Composable
fun PlacementCoachScreen(onExit: () -> Unit) {
    val permission = rememberCameraPermissionController()
    BackHandler(onBack = onExit)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (permission.state) {
            CameraPermissionUiState.GRANTED -> PlacementCoachCameraStage(onExit = onExit)
            else -> PlacementCoachPermissionStage(
                state = permission.state,
                onRequest = permission.request,
                onOpenSettings = permission.openSettings,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun PlacementCoachCameraStage(onExit: () -> Unit) {
    val context = LocalContext.current
    val diagnosticsVisible = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val appPaused = rememberTrexLifecyclePaused()
    KeepScreenOn(enabled = !appPaused)

    var goal by rememberSaveable { mutableStateOf(PlacementCoachGoal.FULL_BODY) }
    var cameraStatus by remember { mutableStateOf(PoseCameraStatus.Initializing) }
    var cameraError by remember { mutableStateOf<PoseCameraError?>(null) }
    var display by remember { mutableStateOf(PlacementCoachDisplayPolicy.initial(PlacementCoachGoal.FULL_BODY)) }
    val frameState = remember { mutableStateOf<PoseFrame?>(null) }
    val stabilizer = remember { PlacementCoachGuidanceStabilizer() }
    val recorder = remember { PlacementCoachAttemptRecorder() }

    // The preview keeps the callback it was given, so read the live goal rather than a captured one.
    val currentGoal = rememberUpdatedState(goal)
    val cameraState = when {
        cameraError != null -> PlacementCameraState.UNAVAILABLE
        cameraStatus == PoseCameraStatus.Ready -> PlacementCameraState.RUNNING
        else -> PlacementCameraState.STARTING
    }
    val liveCameraState = rememberUpdatedState(cameraState)

    LaunchedEffect(cameraState, goal) {
        if (cameraState != PlacementCameraState.RUNNING) {
            display = PlacementCoachDisplayPolicy.resolve(goal, cameraState, observed = null)
        }
    }

    LaunchedEffect(display.goal, display.stage) {
        if (display.stage == PlacementCoachStage.REACHED && display.goal == PlacementCoachGoal.FULL_BODY) {
            delay(GOAL_ADVANCE_DELAY_MS)
            goal = PlacementCoachGoal.LATERAL
        }
    }

    PoseCameraPreview(
        modifier = Modifier.fillMaxSize(),
        active = !appPaused,
        onPoseObservation = { update ->
            frameState.value = update.displayFrame
            val resolved = PlacementCoachDisplayPolicy.resolve(
                goal = currentGoal.value,
                cameraState = liveCameraState.value,
                observed = update.toPlacementObservedSignal(),
            )
            val stabilized = stabilizer.stabilize(update.observation.frame.timestampMs, resolved)
            recorder.accept(update.observation.frame.timestampMs, stabilized)
            if (stabilized != display) {
                display = stabilized
            }
        },
        onError = { error -> cameraError = error },
        onStatusChanged = { status -> cameraStatus = status },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f)),
    )

    PlacementSkeletonLayer(frameState = frameState, visible = display.skeletonVisible)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        PlacementCoachTopBar(goal = display.goal, onExit = onExit)
        Spacer(Modifier.height(10.dp))
        NonVerdictDisclosure()
        Spacer(Modifier.weight(1f))
        if (diagnosticsVisible) {
            PlacementDiagnostics(recorder = recorder)
            Spacer(Modifier.height(8.dp))
        }
        PlacementGuidanceCard(display = display, cameraError = cameraError)
    }
}

@Composable
private fun PlacementSkeletonLayer(frameState: MutableState<PoseFrame?>, visible: Boolean) {
    // Reading the frame inside this composable keeps per-frame recomposition off the guidance card.
    PoseSkeletonOverlay(
        frame = if (visible) frameState.value else null,
        trackingLost = !visible,
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.88f)
            .semantics { contentDescription = "카메라가 인식한 관절 위치" },
    )
}

@Composable
private fun PlacementCoachTopBar(goal: PlacementCoachGoal, onExit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "카메라 배치 확인",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (goal) {
                    PlacementCoachGoal.FULL_BODY -> "1단계 · 전신이 화면에 들어오기"
                    PlacementCoachGoal.LATERAL -> "2단계 · 옆모습으로 서기"
                },
                color = TrexLime,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onExit) {
            Text("닫기", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun NonVerdictDisclosure() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = PlacementCoachDisplayPolicy.NON_VERDICT_DISCLOSURE,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PlacementGuidanceCard(display: PlacementCoachDisplay, cameraError: PoseCameraError?) {
    val reached = display.stage == PlacementCoachStage.REACHED
    val settling = display.stage == PlacementCoachStage.HOLDING

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.62f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlacementStatusBadge(reached = reached, settling = settling)
                Spacer(Modifier.height(0.dp))
                AnimatedContent(
                    targetState = display.guidance,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "placement-guidance",
                ) { guidance ->
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = guidance.headline,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = guidance.detail,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            cameraError?.let { error ->
                Text(
                    text = error.userMessage(),
                    color = TrexWarning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * Status is encoded three ways at once — icon glyph, colour and the guidance text beside it — so
 * colour alone never carries the meaning.
 */
@Composable
private fun PlacementStatusBadge(reached: Boolean, settling: Boolean) {
    val transition = rememberInfiniteTransition(label = "placement-badge")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "placement-badge-pulse",
    )
    // A settling badge pulses without ever promising when it will finish; the runtime does not
    // publish how much of its dwell remains and inventing a countdown would be a fiction.
    val alpha = if (settling) pulse else 1f
    val color = if (reached) TrexLime else Color.White
    Text(
        text = if (reached) "✓" else "…",
        color = color,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.alpha(alpha),
    )
}

@Composable
private fun PlacementDiagnostics(recorder: PlacementCoachAttemptRecorder) {
    val aggregate = recorder.snapshot()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("내부 진단 (debug 빌드)", color = TrexLime, fontSize = 11.sp)
            Text(
                text = "frames=${aggregate.acceptedFrameCount} " +
                    "elapsed=${aggregate.attemptDurationMs}ms " +
                    "gaps=${aggregate.discontinuityCount}",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
            )
            Text(
                text = "full-body=${aggregate.fullBodyReachedAfterMs ?: "-"}ms " +
                    "lateral=${aggregate.lateralReachedAfterMs ?: "-"}ms",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PlacementCoachPermissionStage(
    state: CameraPermissionUiState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "카메라 권한이 필요해요",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "배치 확인은 화면에 보이는 모습만 사용해요. 영상이나 관절 정보를 저장하거나 전송하지 않아요.",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(22.dp))
        when (state) {
            CameraPermissionUiState.PERMANENTLY_DENIED -> Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = TrexLime, contentColor = TrexDark),
            ) {
                Text("설정에서 권한 켜기")
            }

            else -> Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = TrexLime, contentColor = TrexDark),
            ) {
                Text("카메라 권한 허용하기")
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onExit) {
            Text("나중에 하기", color = Color.White.copy(alpha = 0.8f))
        }
    }
}
