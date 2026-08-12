package com.example.trex_kotlin

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.example.trex_kotlin.camera.FULL_BODY_LATERAL_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.PoseCameraError
import com.example.trex_kotlin.camera.PoseCameraPreview
import com.example.trex_kotlin.camera.PoseCameraStatus
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.devcapture.DevPoseCapture
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.formcheck.FormCheckCadence
import com.example.trex_kotlin.pose.formcheck.FormCheckExercise
import com.example.trex_kotlin.pose.formcheck.FormCheckStartAnnouncer
import com.example.trex_kotlin.pose.formcheck.FormCheckStartState
import com.example.trex_kotlin.pose.formcheck.FormCheckUiState
import com.example.trex_kotlin.pose.formcheck.HeuristicFormCheckDeclaration
import com.example.trex_kotlin.pose.formcheck.HeuristicFormCheckSession
import com.example.trex_kotlin.pose.placement.PlacementCameraState
import com.example.trex_kotlin.pose.placement.PlacementCoachDisplayPolicy
import com.example.trex_kotlin.pose.placement.PlacementCoachGoal
import com.example.trex_kotlin.pose.placement.PlacementCoachGuidanceStabilizer
import com.example.trex_kotlin.pose.placement.toPlacementObservedSignal

/**
 * Camera backdrop for a timer session with the heuristic form check (beta) enabled.
 *
 * The placement half reuses the lateral placement guidance so the user first gets into a
 * sideways, fully framed pose; the evaluation half feeds the attested observation into the
 * heuristic session and renders its rep count and observations. Everything it says is
 * observational and the beta disclosure never leaves the screen; the contract is
 * `docs/pose-heuristic-form-check.v1.md`.
 */
@Composable
internal fun SessionFormCheckLayer(
    exercise: AiHubExercise,
    paused: Boolean,
    /** Bump to start a fresh count — the host passes the current set number. */
    attemptResetKey: Int,
    /** The host speaks; this track never touches audio itself. */
    onAnnounce: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = FormCheckExercise.of(exercise)
    if (spec == null) {
        // Unsupported exercises never reach this layer through the toggle; render nothing
        // rather than guessing.
        Box(modifier = modifier)
        return
    }
    val permission = rememberCameraPermissionController()

    Box(modifier = modifier) {
        when (permission.state) {
            CameraPermissionUiState.GRANTED -> FormCheckContent(
                spec = spec,
                paused = paused,
                attemptResetKey = attemptResetKey,
                onAnnounce = onAnnounce,
            )
            CameraPermissionUiState.UNKNOWN -> FormCheckNote("카메라 준비 중이에요")
            CameraPermissionUiState.DENIED ->
                FormCheckNote("카메라 권한이 없어 자세 체크 없이 진행해요")
            CameraPermissionUiState.PERMANENTLY_DENIED ->
                FormCheckNote("설정에서 카메라 권한을 켜면 자세 체크를 쓸 수 있어요")
        }
    }
}

@Composable
private fun FormCheckContent(
    spec: FormCheckExercise,
    paused: Boolean,
    attemptResetKey: Int,
    onAnnounce: (String) -> Unit,
) {
    var cameraStatus by remember { mutableStateOf(PoseCameraStatus.Initializing) }
    var cameraError by remember { mutableStateOf<PoseCameraError?>(null) }
    var placementDisplay by remember {
        mutableStateOf(PlacementCoachDisplayPolicy.initial(PlacementCoachGoal.LATERAL))
    }
    val frameState = remember { mutableStateOf<PoseFrame?>(null) }
    val stabilizer = remember { PlacementCoachGuidanceStabilizer() }
    // Keyed on the set number so each set starts a fresh count; a cumulative figure next to the
    // host's per-set chrome would misread as this set's repetitions.
    val session = remember(spec, attemptResetKey) { HeuristicFormCheckSession(spec) }
    var formState by remember(spec, attemptResetKey) { mutableStateOf(session.initialSnapshot()) }
    val announcer = remember(spec, attemptResetKey) { FormCheckStartAnnouncer() }
    val announce = rememberUpdatedState(onAnnounce)

    // Developer capture. Inert in every shipped build: the release variant links a no-op twin,
    // and the debug one records nothing until a developer opts in and rebuilds.
    val context = LocalContext.current
    DisposableEffect(spec, attemptResetKey) {
        DevPoseCapture.begin(context, spec.name)
        onDispose { DevPoseCapture.end() }
    }

    val cameraState = when {
        cameraError != null -> PlacementCameraState.UNAVAILABLE
        cameraStatus == PoseCameraStatus.Ready -> PlacementCameraState.RUNNING
        else -> PlacementCameraState.STARTING
    }
    val liveCameraState = rememberUpdatedState(cameraState)

    LaunchedEffect(cameraState) {
        if (cameraState != PlacementCameraState.RUNNING) {
            placementDisplay = PlacementCoachDisplayPolicy.resolve(
                goal = PlacementCoachGoal.LATERAL,
                cameraState = cameraState,
                observed = null,
            )
            frameState.value = null
            stabilizer.reset()
            formState = session.initialSnapshot()
            announcer.reset()
        }
    }

    // Speaking is driven off the state rather than the frame callback so a muted or repeated
    // situation stays silent, and so the announcer never runs on the analysis thread.
    LaunchedEffect(formState.startState, formState.missingJoints, formState.sideViewPreferred, paused) {
        if (paused) return@LaunchedEffect
        announcer.onState(
            timestampMs = SystemClock.elapsedRealtime(),
            spec = spec,
            state = formState,
        )?.let { phrase -> announce.value(phrase) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraFeedBackground()
        PoseCameraPreview(
            modifier = Modifier.fillMaxSize(),
            active = !paused,
            onPoseObservation = { update ->
                frameState.value = update.displayFrame
                val resolved = PlacementCoachDisplayPolicy.resolve(
                    goal = PlacementCoachGoal.LATERAL,
                    cameraState = liveCameraState.value,
                    observed = update.toPlacementObservedSignal(),
                )
                val stabilized = stabilizer.stabilize(update.observation.frame.timestampMs, resolved)
                if (stabilized != placementDisplay) {
                    placementDisplay = stabilized
                }
                // Evaluation consumes the attested observation, never the display frame.
                val observed = update.observation
                val lateralQualified = observed.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID)
                DevPoseCapture.record(
                    timestampMs = observed.frame.timestampMs,
                    hasPrimaryPersonLock = observed.hasPrimaryPersonLock,
                    lateralViewQualified = lateralQualified,
                    frame = observed.frame,
                )
                val nextForm = session.accept(
                    timestampMs = observed.frame.timestampMs,
                    hasPrimaryPersonLock = observed.hasPrimaryPersonLock,
                    lateralViewQualified = lateralQualified,
                    frame = observed.frame,
                )
                if (nextForm != formState) {
                    formState = nextForm
                }
            },
            onError = { error -> cameraError = error },
            onStatusChanged = { status ->
                cameraStatus = status
                if (status == PoseCameraStatus.Ready) {
                    cameraError = null
                }
            },
        )
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
        PoseSkeletonOverlay(
            frame = if (placementDisplay.skeletonVisible) frameState.value else null,
            trackingLost = !placementDisplay.skeletonVisible,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.55f)
                .semantics { contentDescription = "카메라가 인식한 관절 위치" },
        )
        FormCheckChip(
            spec = spec,
            formState = formState,
            cameraError = cameraError,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun FormCheckChip(
    spec: FormCheckExercise,
    formState: FormCheckUiState,
    cameraError: PoseCameraError?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (!formState.started) {
                // Say exactly which of this exercise's joints are still missing, rather than
                // asking for a whole-body pose the measurement never needed.
                AnimatedContent(
                    targetState = startHeadline(spec, formState),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "form-check-start",
                ) { headline ->
                    Text(
                        text = headline,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = spec.setupHint,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // An isometric exercise has no repetitions to show, so it counts the seconds
                    // it is holding right now instead.
                    val holding = spec.cadence == FormCheckCadence.HOLD
                    Text(
                        text = if (holding) {
                            "${formState.holdSeconds}"
                        } else {
                            "${formState.repCount}"
                        },
                        color = TrexLime,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (holding) "초 유지" else "회 감지",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
                formState.headline?.let { headline ->
                    Text(
                        text = headline,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                formState.suggestion?.let { suggestion ->
                    Text(
                        text = suggestion,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (formState.sideViewPreferred) {
                    Text(
                        text = "옆모습으로 서면 " +
                            "${FormCheckStartAnnouncer.sideViewSubject(spec)} 더 잘 보여요",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
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
            // The beta disclosure is always visible and never replaced.
            Text(
                text = HeuristicFormCheckDeclaration.BETA_DISCLOSURE,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
            // Only where the threshold actually came from that dataset; crediting it on an
            // uncalibrated exercise would claim a provenance the constant does not have.
            if (spec.provenance.requiresDataAttribution) {
                Text(
                    text = HeuristicFormCheckDeclaration.DATA_ATTRIBUTION,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

private fun startHeadline(spec: FormCheckExercise, state: FormCheckUiState): String =
    when (state.startState) {
        FormCheckStartState.WAITING_FOR_CAMERA -> "카메라를 준비하고 있어요"
        FormCheckStartState.WAITING_FOR_PERSON -> "화면에 한 사람만 보이게 서 주세요"
        FormCheckStartState.WAITING_FOR_JOINTS -> {
            val missing = state.missingJoints
            if (missing.isEmpty()) {
                spec.setupHint
            } else {
                val names = missing.joinToString(", ") { it.label }
                "${names}${FormCheckStartAnnouncer.subjectParticle(names)} 화면에 보이게 서 주세요"
            }
        }

        FormCheckStartState.STARTED -> ""
    }

@Composable
private fun FormCheckNote(text: String) {
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
