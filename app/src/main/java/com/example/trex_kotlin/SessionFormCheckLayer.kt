package com.example.trex_kotlin

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
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
import com.example.trex_kotlin.camera.rememberDeviceGravity
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.devcapture.DevPoseCapture
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.formcheck.FormCheckBaselineRelation
import com.example.trex_kotlin.pose.formcheck.FormCheckCadence
import com.example.trex_kotlin.pose.formcheck.FormCheckCountAnnouncer
import com.example.trex_kotlin.pose.formcheck.FormCheckExercise
import com.example.trex_kotlin.pose.formcheck.FormCheckJointGroup
import com.example.trex_kotlin.pose.formcheck.FormCheckLiveReading
import com.example.trex_kotlin.pose.formcheck.FormCheckRepEventKind
import com.example.trex_kotlin.pose.formcheck.FormCheckRepMark
import com.example.trex_kotlin.pose.formcheck.FormCheckSetSummary
import com.example.trex_kotlin.pose.formcheck.FormCheckStartAnnouncer
import com.example.trex_kotlin.pose.formcheck.FormCheckStartState
import com.example.trex_kotlin.pose.formcheck.FormCheckUiState
import com.example.trex_kotlin.pose.formcheck.FormCheckUncountedAnnouncer
import com.example.trex_kotlin.pose.formcheck.FormCheckView
import com.example.trex_kotlin.pose.formcheck.HeuristicFormCheckDeclaration
import com.example.trex_kotlin.pose.formcheck.HeuristicFormCheckSession
import com.example.trex_kotlin.pose.placement.PlacementCameraState
import com.example.trex_kotlin.pose.placement.PlacementCoachDisplayPolicy
import com.example.trex_kotlin.pose.placement.PlacementCoachGoal
import com.example.trex_kotlin.pose.placement.PlacementCoachGuidanceStabilizer
import com.example.trex_kotlin.pose.placement.toPlacementObservedSignal
import kotlinx.coroutines.delay

/**
 * Camera backdrop for a timer session with the heuristic form check (beta) enabled.
 *
 * The contract is `docs/pose-heuristic-form-check.v1.md`; what follows is why the surface looks
 * the way it does.
 *
 * **Nobody reads this screen during a set.** The phone is two to three metres away and the policy
 * asks the user to stand side-on to it, so anything under about twenty sp is optically absent and
 * a sentence is unreachable in either sense. The previous surface stacked six rows of nine-to-
 * thirteen sp text into one chip, which is why it read as a demo. So the set is split into three
 * moments and each gets the channel that can actually carry it:
 *
 * - **While the set runs**, the screen says only three things — whether the camera can see the
 *   joint (the on-body frame), how many repetitions there are (one large numeral), and, briefly,
 *   what the last one measured. Everything else is silence.
 * - **Speech carries the observation**, because it is the only channel that reaches somebody
 *   facing sideways. The count now travels with the set's own comparison, and the first uncounted
 *   excursion says why it was not counted.
 * - **The rest period carries the detail.** Standing still and close to the phone is the only
 *   moment the prose is legible, and the feedback literature puts retention there rather than
 *   mid-movement anyway.
 *
 * Colour means tense, never quality: lime is what is being measured now, dim white is seen but
 * not measured, and absence is not seen. No error colour is painted here at all — abstention is
 * not a fault, and a red skeleton would hand the user a verdict about their body that this track
 * is not entitled to make.
 */
@Composable
internal fun SessionFormCheckLayer(
    exercise: AiHubExercise,
    paused: Boolean,
    /** Bump to start a fresh count — the host passes the current set number. */
    attemptResetKey: Int,
    /** The host speaks; this track never touches audio itself. */
    onAnnounce: (String) -> Unit,
    /** The host renders the set's review during rest; this track only assembles it. */
    onSetObserved: (FormCheckSetSummary) -> Unit = {},
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
                onSetObserved = onSetObserved,
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
    onSetObserved: (FormCheckSetSummary) -> Unit,
) {
    // The placement the guidance aims at follows the exercise, so a coronal-plane movement is
    // never told to turn sideways — the one placement it cannot be read from.
    val placementGoal = when (spec.view) {
        FormCheckView.LATERAL -> PlacementCoachGoal.LATERAL
        FormCheckView.FRONTAL -> PlacementCoachGoal.FRONTAL
    }
    // Sampled only for the exercises whose identity depends on which way the body points; every
    // other exercise never reads it and a device without the sensor simply never registers.
    val gravity = rememberDeviceGravity(active = spec.posture != null && !paused)
    var cameraStatus by remember { mutableStateOf(PoseCameraStatus.Initializing) }
    var cameraError by remember { mutableStateOf<PoseCameraError?>(null) }
    var placementDisplay by remember {
        mutableStateOf(PlacementCoachDisplayPolicy.initial(placementGoal))
    }
    val frameState = remember { mutableStateOf<PoseFrame?>(null) }
    // The live angle moves every camera frame. Kept in its own state and read only inside the
    // Canvas so it cannot recompose the text around it at frame rate.
    val liveState = remember { mutableStateOf<FormCheckLiveReading?>(null) }
    val stabilizer = remember { PlacementCoachGuidanceStabilizer() }
    // Keyed on the set number so each set starts a fresh count; a cumulative figure next to the
    // host's per-set chrome would misread as this set's repetitions.
    val session = remember(spec, attemptResetKey) { HeuristicFormCheckSession(spec) }
    var formState by remember(spec, attemptResetKey) { mutableStateOf(session.initialSnapshot()) }
    val announcer = remember(spec, attemptResetKey) { FormCheckStartAnnouncer() }
    val countAnnouncer = remember(spec, attemptResetKey) { FormCheckCountAnnouncer() }
    val uncountedAnnouncer = remember(spec, attemptResetKey) { FormCheckUncountedAnnouncer() }
    val announce = rememberUpdatedState(onAnnounce)
    val reportSummary = rememberUpdatedState(onSetObserved)

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
                goal = placementGoal,
                cameraState = cameraState,
                observed = null,
            )
            frameState.value = null
            liveState.value = null
            stabilizer.reset()
            formState = session.initialSnapshot()
            announcer.reset()
        }
    }

    // A paused camera stops delivering frames, so the last reading would otherwise stay drawn on
    // a body that has since walked away. Clearing it is the same promise as the engine's
    // abstention: an unobserved joint shows nothing rather than its last known angle.
    LaunchedEffect(paused) {
        if (paused) liveState.value = null
    }

    // The band waits out one flap before it appears. A marginal person lock in a lateral stance
    // can drop and return inside a second; strobing "사람을 놓쳐서…" at that rhythm reads as the
    // app panicking. Counting still abstains from the first lost frame — this debounce delays
    // only the wording, and a camera fault skips it entirely. Cancellation does the timing: a
    // state change restarts the effect, so the delay only ever completes for a stable situation.
    var statusBandVisible by remember(spec, attemptResetKey) { mutableStateOf(false) }
    LaunchedEffect(formState.started, cameraError != null) {
        if (cameraError != null) {
            statusBandVisible = true
            return@LaunchedEffect
        }
        if (formState.started) {
            statusBandVisible = false
            return@LaunchedEffect
        }
        delay(STATUS_BAND_STABILITY_MS)
        statusBandVisible = true
    }

    // Speaking is driven off the state rather than the frame callback so a muted or repeated
    // situation stays silent, and so the announcer never runs on the analysis thread. The loop
    // exists because a situation becomes speakable by *persisting*, not only by changing: the
    // announcer refuses anything that has not held for its stability window, and asks to be
    // polled again once the window could have elapsed. A state change cancels and restarts the
    // effect, which is the debounce working — a flapping person lock restarts the wait forever
    // and is never spoken.
    LaunchedEffect(formState.startState, formState.missingJoints, formState.preferredViewSuggested, paused) {
        if (paused) return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val phrase = announcer.onState(timestampMs = now, spec = spec, state = formState)
            if (phrase != null) {
                announce.value(phrase)
            }
            val retry = announcer.retryDelayMs(SystemClock.elapsedRealtime()) ?: return@LaunchedEffect
            delay(retry)
        }
    }

    // Nobody mid-squat is reading the screen, and somebody standing side-on to it cannot even
    // glance. Speech is the only channel that reaches them, so the count carries the one
    // comparison this track can make honestly — against the set's own opening repetitions
    // (§4.4). A count reached while paused is consumed silently rather than announced late.
    LaunchedEffect(formState.repCount, paused) {
        val latest = formState.repMarks.lastOrNull()?.takeIf {
            it.kind == FormCheckRepEventKind.COUNTED
        }
        countAnnouncer.onCount(
            repCount = formState.repCount,
            relation = latest?.baselineRelation ?: FormCheckBaselineRelation.SAME,
            vocabulary = spec.vocabulary,
            muted = paused,
        )?.let { phrase -> announce.value(phrase) }
    }

    // Once per set, on the first excursion that did not count. Silence is not a usable signal in
    // a gym or with the volume down, and an uncounted repetition that says nothing is
    // indistinguishable from a crash; saying it every time would turn an observation into
    // nagging, which is how a track that makes no judgement starts to feel like one.
    LaunchedEffect(formState.uncountedAttemptCount, paused) {
        uncountedAnnouncer.onUncounted(
            uncountedCount = formState.uncountedAttemptCount,
            phrase = formState.repMarks.lastOrNull()
                ?.takeIf { it.kind != FormCheckRepEventKind.COUNTED }
                ?.observation,
            muted = paused,
        )?.let { phrase -> announce.value(phrase) }
    }

    // The host holds this for the rest period. Recomputed only when the snapshot changes, which
    // is a handful of times per set, and it is dropped by the host when the set number moves.
    LaunchedEffect(formState) {
        reportSummary.value(session.summary())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraFeedBackground()
        PoseCameraPreview(
            modifier = Modifier.fillMaxSize(),
            active = !paused,
            onPoseObservation = { update ->
                frameState.value = update.displayFrame
                val resolved = PlacementCoachDisplayPolicy.resolve(
                    goal = placementGoal,
                    cameraState = liveCameraState.value,
                    observed = update.toPlacementObservedSignal(),
                )
                val stabilized = stabilizer.stabilize(update.observation.frame.timestampMs, resolved)
                if (stabilized != placementDisplay) {
                    placementDisplay = stabilized
                }
                // Evaluation consumes the attested observation, never the display frame.
                val observed = update.observation
                // The exercise's own placement, not always the lateral one: a coronal-plane
                // movement seen from the side travels along the camera's depth axis, which is
                // where a monocular estimate is weakest.
                val preferredQualified = observed.isViewQualified(spec.view.contractId)
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
                    preferredViewQualified = preferredQualified,
                    frame = observed.frame,
                    gravity = gravity.value,
                )
                // Mirrors the engine's own field, including its nulls: the engine clears it on
                // every path that stops evaluating, so the drawing inherits abstention for free.
                liveState.value = session.liveReading
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
        FormCheckObservationOverlay(
            spec = spec,
            frameState = frameState,
            liveState = liveState,
            skeletonVisible = placementDisplay.skeletonVisible,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = HeuristicFormCheckDeclaration.OVERLAY_DESCRIPTION },
        )
        FormCheckStatusBand(
            spec = spec,
            formState = formState,
            cameraError = cameraError,
            visible = statusBandVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 132.dp, start = 20.dp, end = 20.dp),
        )
        FormCheckCountSlab(
            spec = spec,
            formState = formState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 116.dp)
                .navigationBarsPadding(),
        )
    }
}

/**
 * What the camera can see, drawn on the body.
 *
 * Three layers with three different jobs. The context dots say "a person is there" and stay up
 * even while the track abstains, so a lost joint does not read as a crashed camera. The driver
 * chain says "this is the joint the number comes from" — the honest visual form of the policy's
 * own limitation that only one side of one chain is ever measured (§6). The corner frame is the
 * abstention signal: it is the only element large enough to register in peripheral vision from
 * three metres, and its disappearance is the message.
 *
 * Nothing here draws a threshold. No target tick, no ideal band, no colour change when a line is
 * crossed — for any exercise. Six of the eighteen may never be urged toward more range (§4.2),
 * and a target drawn only on the other twelve would make the seal visible as a missing feature;
 * removing it everywhere is the only version where the sealed exercises look identical to the
 * rest, because there is nothing to be missing.
 */
@Composable
private fun FormCheckObservationOverlay(
    spec: FormCheckExercise,
    frameState: State<PoseFrame?>,
    liveState: State<FormCheckLiveReading?>,
    skeletonVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val measuring = liveState.value != null && skeletonVisible
    // Short on the way out on purpose: a long fade leaves the last known position of a joint the
    // camera can no longer see glowing on screen, which is the stale value the policy forbids.
    val emphasis by animateFloatAsState(
        targetValue = if (measuring) 1f else 0f,
        animationSpec = tween(durationMillis = if (measuring) 140 else 120, easing = LinearEasing),
        label = "form-check-emphasis",
    )

    Canvas(modifier = modifier) {
        val frame = frameState.value?.takeIf { skeletonVisible } ?: return@Canvas
        val sourceAspect = frame.imageAspectRatio.toFloat()
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
            val landmark = frame.landmarks[joint] ?: return null
            if (landmark.confidence < 0.5) return null
            val normalizedX = if (frame.isMirrored) 1.0 - landmark.x else landmark.x
            if (normalizedX !in -0.15..1.15 || landmark.y !in -0.15..1.15) return null
            return Offset(
                x = offsetX + normalizedX.toFloat() * drawnWidth,
                y = offsetY + landmark.y.toFloat() * drawnHeight,
            )
        }

        // Layer one: perception. Present whenever a person is, including through an abstention.
        PoseJoint.entries.forEach { joint ->
            val point = screenPoint(joint) ?: return@forEach
            drawCircle(
                color = Color.White.copy(alpha = 0.24f),
                radius = 2.5.dp.toPx(),
                center = point,
            )
        }

        if (emphasis <= 0.01f) return@Canvas
        val reading = liveState.value ?: return@Canvas
        fun chainPoint(group: FormCheckJointGroup): Offset? = screenPoint(group.joint(reading.side))
        val first = chainPoint(spec.driver.first) ?: return@Canvas
        val vertex = chainPoint(spec.driver.vertex) ?: return@Canvas
        val second = chainPoint(spec.driver.second) ?: return@Canvas

        // Layer two: what is being measured. Quieter as the chain's confidence falls, so a
        // marginal reading looks marginal instead of looking certain.
        val credibility = ((reading.chainConfidence - 0.55) / 0.30).coerceIn(0.0, 1.0).toFloat()
        val chainAlpha = emphasis * (0.45f + 0.55f * credibility)
        listOf(first to vertex, vertex to second).forEach { (start, end) ->
            drawLine(
                color = TrexLime.copy(alpha = chainAlpha),
                start = start,
                end = end,
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        listOf(first, vertex, second).forEach { point ->
            drawCircle(
                color = Color.Black.copy(alpha = 0.34f * emphasis),
                radius = 9.dp.toPx(),
                center = point,
            )
            drawCircle(
                color = TrexLime.copy(alpha = chainAlpha),
                radius = if (point == vertex) 7.dp.toPx() else 5.dp.toPx(),
                center = point,
            )
        }

        // Layer three: the distance-legible "you are being seen" signal. Corner brackets rather
        // than a closed rectangle, so the body stays unobstructed.
        val pad = 26.dp.toPx()
        val left = minOf(first.x, vertex.x, second.x) - pad
        val right = maxOf(first.x, vertex.x, second.x) + pad
        val top = minOf(first.y, vertex.y, second.y) - pad
        val bottom = maxOf(first.y, vertex.y, second.y) + pad
        val arm = 20.dp.toPx()
        val bracket = TrexLime.copy(alpha = 0.55f * emphasis)
        val bracketWidth = 3.dp.toPx()
        listOf(
            Triple(Offset(left, top), Offset(left + arm, top), Offset(left, top + arm)),
            Triple(Offset(right, top), Offset(right - arm, top), Offset(right, top + arm)),
            Triple(Offset(left, bottom), Offset(left + arm, bottom), Offset(left, bottom - arm)),
            Triple(Offset(right, bottom), Offset(right - arm, bottom), Offset(right, bottom - arm)),
        ).forEach { (corner, horizontal, vertical) ->
            drawLine(bracket, corner, horizontal, bracketWidth, StrokeCap.Round)
            drawLine(bracket, corner, vertical, bracketWidth, StrokeCap.Round)
        }
    }
}

/**
 * The one first-class sentence on the live screen: why the track is not counting.
 *
 * Two situations share this band and they need different words. Before the exercise ever starts,
 * it is setup guidance. After it has started, telling somebody already in position to "서 주세요"
 * asks for something they have done; what actually happened is that the camera lost the joint and
 * the count stopped, and naming that is what turns a frozen screen into a careful one.
 *
 * A camera fault outranks both, and is rendered in the same white as everything else here. An
 * alarm colour on this surface would be read as a verdict about the body in front of it.
 */
@Composable
private fun FormCheckStatusBand(
    spec: FormCheckExercise,
    formState: FormCheckUiState,
    cameraError: PoseCameraError?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val headline = cameraError?.userMessage() ?: statusHeadline(spec, formState)
    AnimatedVisibility(
        visible = visible && headline != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.62f),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = headline.orEmpty(),
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                    label = "form-check-status",
                ) { text ->
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 24.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                if (formState.observationPaused && cameraError == null) {
                    Text(
                        text = HeuristicFormCheckDeclaration.PAUSED_RESUME,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * The count, and the disclosures that are not allowed to leave its side.
 *
 * Rendered unconditionally, including before the first repetition. The beta disclosure has to
 * accompany every number this track shows (§1.1-1), and the cheapest way to make that structural
 * rather than incidental is to put them in one Column that no state can split — a surface that
 * appears only once counting begins is a surface whose disclosure can be argued away.
 *
 * The numeral is white rather than lime because the host draws "SET n/m" in lime a few
 * centimetres above it, and two large lime numerals on one screen invite the reading that the
 * camera is deciding the set — which it never does (§5-4).
 */
@Composable
private fun FormCheckCountSlab(
    spec: FormCheckExercise,
    formState: FormCheckUiState,
    modifier: Modifier = Modifier,
) {
    val holding = spec.cadence == FormCheckCadence.HOLD
    // Bound to the mark rather than to the engine's headline: the headline survives an
    // abstention, so rendering it would leave a claim about a repetition on screen while the
    // camera has lost the joint. A mark is an event, and events do not persist.
    var recent by remember(spec) { mutableStateOf<FormCheckRepMark?>(null) }
    LaunchedEffect(formState.repMarks.size, formState.started) {
        val mark = formState.repMarks.lastOrNull()
        // Empty covers a fresh set as well as a set that has not moved yet: the host rebuilds the
        // session per set, and the previous set's last repetition must not survive into this one.
        if (!formState.started || mark == null) {
            recent = null
            return@LaunchedEffect
        }
        recent = mark
        delay(2_600)
        recent = null
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.58f),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 210.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (holding) "${formState.holdSeconds}" else "${formState.repCount}",
                    color = Color.White,
                    fontSize = 72.sp,
                    lineHeight = 76.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // Kept as an observation rather than shortened to a bare unit: the track
                    // reports what it saw, and "감지" is the difference between that and a claim
                    // to have counted the set.
                    text = if (holding) "초 유지" else "회 감지",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            AnimatedVisibility(
                visible = recent != null,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(240)),
            ) {
                Text(
                    text = recent?.observation.orEmpty(),
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (formState.preferredViewSuggested) {
                Text(
                    text = "${spec.view.noteSubject} " +
                        "${FormCheckStartAnnouncer.viewNoteSubject(spec)} 더 잘 보여요",
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // The beta disclosure is always visible and never replaced.
            Text(
                text = HeuristicFormCheckDeclaration.BETA_DISCLOSURE,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Only where a constant actually came from that dataset; crediting an uncalibrated
            // exercise would claim a provenance it does not have. The guard counts — a fitted
            // guard on an otherwise-default exercise still owes the credit.
            if (spec.requiresDataAttribution) {
                Text(
                    text = HeuristicFormCheckDeclaration.DATA_ATTRIBUTION,
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * The set's review, shown while the user rests.
 *
 * This is the only moment the prose can be read: standing still, unloaded, close to the phone.
 * The observations are verbatim the strings the engine built during the set, so nothing here is
 * new wording — the summary changes when the sentences are legible, not what they say.
 *
 * The host owns placement and lifetime; it drops the summary when the set number moves, so
 * nothing outlives the set and nothing reaches the workout record (§5-2).
 */
@Composable
internal fun FormCheckSetSummaryCard(
    summary: FormCheckSetSummary,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.07f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = HeuristicFormCheckDeclaration.SUMMARY_TITLE,
                color = TrexLime,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!summary.hasObservations) {
                Text(
                    text = HeuristicFormCheckDeclaration.SUMMARY_EMPTY,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                val headline = if (summary.cadence == FormCheckCadence.HOLD) {
                    "${summary.holdSeconds}${HeuristicFormCheckDeclaration.SUMMARY_HOLD_SUFFIX}"
                } else {
                    "${summary.repCount}${HeuristicFormCheckDeclaration.SUMMARY_COUNT_SUFFIX}"
                }
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    // The tail of the set: what the user just did is what they can still feel.
                    summary.marks.takeLast(SUMMARY_MARK_LIMIT).forEach { mark ->
                        Text(
                            text = mark.observation,
                            color = if (mark.kind == FormCheckRepEventKind.COUNTED) {
                                Color.White.copy(alpha = 0.88f)
                            } else {
                                Color.White.copy(alpha = 0.62f)
                            },
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                        )
                    }
                    if (summary.marks.isEmpty()) {
                        summary.lastObservation?.let { observation ->
                            Text(
                                text = observation,
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                            )
                        }
                    }
                }
            }
            Text(
                text = summary.provenanceNote,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = HeuristicFormCheckDeclaration.BETA_DISCLOSURE,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (summary.requiresDataAttribution) {
                Text(
                    text = HeuristicFormCheckDeclaration.DATA_ATTRIBUTION,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * Shown once, before the first set of a form-check session.
 *
 * Everything else on this surface is non-verbal — dots, brackets, a numeral, a silence — and none
 * of it explains itself. Three sentences, said once: which joint this exercise reads, that it
 * goes quiet and says so when it cannot see that joint, and that it measures rather than judges.
 */
@Composable
internal fun FormCheckIntroCard(
    exercise: AiHubExercise,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = FormCheckExercise.of(exercise) ?: return
    val joint = spec.driver.vertex.label
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = TrexDarkAlt.copy(alpha = 0.97f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = HeuristicFormCheckDeclaration.INTRO_TITLE,
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = HeuristicFormCheckDeclaration.INTRO_MEASURES_PREFIX + joint +
                    HeuristicFormCheckDeclaration.INTRO_MEASURES_SUFFIX,
                color = TrexLime,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = HeuristicFormCheckDeclaration.INTRO_SILENCE,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = HeuristicFormCheckDeclaration.INTRO_NOT_A_VERDICT,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = HeuristicFormCheckDeclaration.BETA_DISCLOSURE,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            TrexButton(
                text = HeuristicFormCheckDeclaration.INTRO_DISMISS,
                onClick = onDismiss,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

/**
 * The band's sentence, or null when the exercise is running and there is nothing to say.
 *
 * The started case returns null deliberately: an exercise that is being observed does not need a
 * status line, and leaving one up would compete with the only two things the screen is for.
 */
private fun statusHeadline(spec: FormCheckExercise, state: FormCheckUiState): String? =
    when (state.startState) {
        FormCheckStartState.WAITING_FOR_CAMERA -> "카메라를 준비하고 있어요"

        FormCheckStartState.WAITING_FOR_PERSON ->
            if (state.hasEverStarted) {
                HeuristicFormCheckDeclaration.PAUSED_PERSON
            } else {
                "화면에 한 사람만 보이게 서 주세요"
            }

        FormCheckStartState.WAITING_FOR_JOINTS -> {
            val missing = state.missingJoints
            if (missing.isEmpty()) {
                spec.setupHint
            } else {
                val names = missing.joinToString(", ") { it.label }
                val particle = FormCheckStartAnnouncer.subjectParticle(names)
                if (state.hasEverStarted) {
                    HeuristicFormCheckDeclaration.PAUSED_JOINT_PREFIX + names + particle +
                        HeuristicFormCheckDeclaration.PAUSED_JOINT_SUFFIX
                } else {
                    "${names}$particle 화면에 보이게 서 주세요"
                }
            }
        }

        FormCheckStartState.STARTED -> null
    }

@Composable
private fun FormCheckNote(text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 132.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** How many of a set's observations the rest card lists. The tail is the part still felt. */
private const val SUMMARY_MARK_LIMIT = 6

/** How long a waiting situation must hold before the status band names it. */
private const val STATUS_BAND_STABILITY_MS = 650L
