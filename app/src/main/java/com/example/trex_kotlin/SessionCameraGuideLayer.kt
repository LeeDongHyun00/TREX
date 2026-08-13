package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.camera.PoseCameraError
import com.example.trex_kotlin.camera.PoseCameraPreview
import com.example.trex_kotlin.camera.PoseCameraStatus
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.placement.PlacementCameraState
import com.example.trex_kotlin.pose.placement.PlacementCoachDisplay
import com.example.trex_kotlin.pose.placement.PlacementCoachDisplayPolicy
import com.example.trex_kotlin.pose.placement.PlacementCoachGoal
import com.example.trex_kotlin.pose.placement.PlacementCoachGuidanceStabilizer
import com.example.trex_kotlin.pose.placement.PlacementCoachStage
import com.example.trex_kotlin.pose.placement.toPlacementObservedSignal

/**
 * Camera backdrop for a timer session with the camera guide enabled.
 *
 * It renders what the camera sees — preview, skeleton, framing guidance — and nothing about how
 * the exercise is performed. The timer chrome above it owns sets, counts and audio; observations
 * made here never influence them. The rules are the display-only policy document pinned by
 * [PlacementCoachDisplayPolicy.POLICY_DOCUMENT_SHA256].
 *
 * Camera permission is requested once on first use; if it is declined the session simply keeps
 * its usual backdrop and continues — the guide degrades, the workout never blocks.
 */
@Composable
internal fun SessionCameraGuideLayer(
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val permission = rememberCameraPermissionController()

    Box(modifier = modifier) {
        when (permission.state) {
            CameraPermissionUiState.GRANTED -> SessionCameraGuideContent(paused = paused)
            CameraPermissionUiState.UNKNOWN -> SessionGuideNote(
                text = "카메라 준비 중이에요",
            )
            CameraPermissionUiState.DENIED -> SessionGuideNote(
                text = "카메라 권한이 없어 배치 안내 없이 진행해요",
            )
            CameraPermissionUiState.PERMANENTLY_DENIED -> SessionGuideNote(
                text = "설정에서 카메라 권한을 켜면 배치 안내를 볼 수 있어요",
            )
        }
    }
}

@Composable
private fun SessionCameraGuideContent(paused: Boolean) {
    var cameraStatus by remember { mutableStateOf(PoseCameraStatus.Initializing) }
    var cameraError by remember { mutableStateOf<PoseCameraError?>(null) }
    var display by remember {
        mutableStateOf(PlacementCoachDisplayPolicy.initial(PlacementCoachGoal.FULL_BODY))
    }
    val frameState = remember { mutableStateOf<PoseFrame?>(null) }
    val stabilizer = remember { PlacementCoachGuidanceStabilizer() }

    val cameraState = when {
        cameraError != null -> PlacementCameraState.UNAVAILABLE
        cameraStatus == PoseCameraStatus.Ready -> PlacementCameraState.RUNNING
        else -> PlacementCameraState.STARTING
    }
    val liveCameraState = rememberUpdatedState(cameraState)

    LaunchedEffect(cameraState) {
        // Observations stop arriving the moment the camera stops (pause, background, error), so
        // the callback below can never clear its own last chip. A stale "camera sees your whole
        // body" over a frozen frame is exactly the false reassurance this track forbids.
        if (cameraState != PlacementCameraState.RUNNING) {
            display = PlacementCoachDisplayPolicy.resolve(
                goal = PlacementCoachGoal.FULL_BODY,
                cameraState = cameraState,
                observed = null,
            )
            frameState.value = null
            stabilizer.reset()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraFeedBackground()
        PoseCameraPreview(
            modifier = Modifier.fillMaxSize(),
            active = !paused,
            onPoseObservation = { update ->
                frameState.value = update.displayFrame
                val resolved = PlacementCoachDisplayPolicy.resolve(
                    goal = PlacementCoachGoal.FULL_BODY,
                    cameraState = liveCameraState.value,
                    observed = update.toPlacementObservedSignal(),
                )
                val stabilized = stabilizer.stabilize(update.observation.frame.timestampMs, resolved)
                if (stabilized != display) {
                    display = stabilized
                }
            },
            onError = { error -> cameraError = error },
            onStatusChanged = { status ->
                cameraStatus = status
                // A later successful bind (for example after resting) must not stay masked by a
                // stale error from the previous camera attempt.
                if (status == PoseCameraStatus.Ready) {
                    cameraError = null
                }
            },
        )
        // The timer chrome renders large light text over this layer; the gradient keeps it
        // readable against a bright room without hiding the person.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.62f),
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.62f),
                        ),
                    ),
                ),
        )
        SessionGuideSkeleton(frameState = frameState, visible = display.skeletonVisible)
        SessionGuideChip(
            display = display,
            cameraError = cameraError,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun SessionGuideSkeleton(frameState: MutableState<PoseFrame?>, visible: Boolean) {
    // Reading the frame here keeps per-frame recomposition away from the timer chrome.
    PoseSkeletonOverlay(
        frame = if (visible) frameState.value else null,
        trackingLost = !visible,
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.55f)
            .semantics { contentDescription = "카메라가 인식한 관절 위치" },
    )
}

@Composable
private fun SessionGuideChip(
    display: PlacementCoachDisplay,
    cameraError: PoseCameraError?,
    modifier: Modifier = Modifier,
) {
    val reached = display.stage == PlacementCoachStage.REACHED
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            AnimatedContent(
                targetState = display.guidance,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "session-guide-chip",
            ) { guidance ->
                Text(
                    text = if (reached) "카메라가 전신을 보고 있어요" else guidance.headline,
                    color = if (reached) TrexLime else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            cameraError?.let { error ->
                Text(
                    text = error.userMessage(),
                    color = TrexWarning,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Policy A8: the non-verdict disclosure is always visible and never replaced.
            Text(
                text = PlacementCoachDisplayPolicy.NON_VERDICT_DISCLOSURE,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SessionGuideNote(text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
