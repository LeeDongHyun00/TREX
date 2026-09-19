package com.example.trex_kotlin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trex_kotlin.posture.BaselineGuideScreen
import com.example.trex_kotlin.posture.PostureLabScreen
import com.example.trex_kotlin.posture.SpeechCoach
import kotlinx.coroutines.delay

/**
 * TREX 리디자인 루트 — 방향성 슬라이드 라우팅 + 모핑 내비게이션 (디자인 캔버스 "TREX Redesign").
 * 화면 순서(guide→auth→find→onboarding→main→record→session→complete)상 앞으로 가면 오른쪽에서,
 * 뒤로 가면 왼쪽에서 들어온다.
 */
private enum class RootRoute { Guide, Auth, Find, Onboarding, Main, Record, DietRecord, TransitionSession, PostureSession, TimerSession, Complete, PostureLab, BaselineGuide }

/** 메인 하단 시트. */
sealed class MainSheet {
    data class WorkoutEditor(val selectedId: String? = null) : MainSheet()
    data class Alt(val workout: Workout) : MainSheet()
    data class Sets(val draft: SetDraft) : MainSheet()
    data object Goals : MainSheet()
    data class Manual(val slot: String) : MainSheet()
    data object Photo : MainSheet()
    data object AddWorkout : MainSheet()
    data object ProfileSettings : MainSheet()
}

data class SetDraft(val id: String, val name: String, val count: Int, val unit: String, val sets: Int,
    val secondsPerRep: Int = 3, val restSeconds: Int = 45)

fun Workout.setDraft(): SetDraft {
    val spec = repsSpec()
    val unit = when {
        reps.contains("초") -> "초"
        reps.contains("분") && !reps.contains("회") -> "분"
        else -> "회"
    }
    return SetDraft(id = id, name = name, count = spec.count, unit = unit, sets = spec.sets,
        secondsPerRep = timing().secondsPerRep, restSeconds = timing().restSeconds)
}

@Composable
fun TrexApp(app: AppViewModel = viewModel()) {
    TrexAppTheme(mode = app.themeMode) {
        val c = Trex.c
        val context = LocalContext.current
        SideEffect {
            @Suppress("DEPRECATION")
            context.findTrexActivity()?.window?.let { w ->
                w.statusBarColor = c.bg.toArgb()
                w.navigationBarColor = c.bg.toArgb()
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = !c.isDark
                    isAppearanceLightNavigationBars = !c.isDark
                }
            }
        }

        var selectedTab by rememberSaveable { mutableStateOf(TrexTab.Home) }
        var subScreen by rememberSaveable { mutableStateOf("none") } // none | find | guide | record | postureLab | baselineGuide
        var progress by rememberSaveable(stateSaver = listSaver<SessionProgress, Any>(
            save = { listOf(it.index, it.remainingMs, it.elapsedMs, it.completed.joinToString(","), it.skipped.joinToString(","),
                it.repetitions, it.recordedCounts.entries.joinToString(",") { entry -> "${entry.key}:${entry.value}" },
                it.workMillis.entries.joinToString(",") { entry -> "${entry.key}:${entry.value}" }) },
            restore = { SessionProgress(it[0] as Int, it[1] as Long, it[2] as Long,
                (it[3] as String).split(',').mapNotNull(String::toIntOrNull).toSet(),
                (it[4] as String).split(',').mapNotNull(String::toIntOrNull).toSet(),
                it.getOrNull(5) as? Int ?: 0,
                (it.getOrNull(6) as? String).orEmpty().split(',').mapNotNull { pair ->
                    val parts = pair.split(':'); val key = parts.firstOrNull()?.toIntOrNull(); val value = parts.getOrNull(1)?.toIntOrNull()
                    if (key != null && value != null) key to value else null
                }.toMap(),
                (it.getOrNull(7) as? String).orEmpty().split(',').mapNotNull { pair ->
                    val parts = pair.split(':'); val key = parts.firstOrNull()?.toIntOrNull(); val value = parts.getOrNull(1)?.toLongOrNull()
                    if (key != null && value != null) key to value else null
                }.toMap()) },
        )) { mutableStateOf(SessionProgress(-1, 0)) }
        val sessionIndex = progress.index
        var sessionDone by rememberSaveable { mutableStateOf(false) }
        var sessionPlanKey by rememberSaveable { mutableStateOf("") }
        val sessionTimeLeft = progress.secondsLeft
        val sessionElapsed = (progress.elapsedMs / 1000).toInt()
        var sessionPaused by rememberSaveable { mutableStateOf(false) }
        // 카메라 권한을 거부한 운동 — 자세 평가 대신 **같은 운동을** 타이머로 돌린다(건너뛰지 않는다).
        // 세션 단위 상태라 다음 세션에서는 다시 권한을 물어본다.
        val postureFallback = remember { mutableStateListOf<String>() }
        // ✕ 종료 확인 — 완료한 운동이 있으면 "여기까지 기록" 을 물어본다(결정 3: 묻지 않고 버리지 않는다).
        var exitAsk by remember { mutableStateOf(false) }
        val appPaused = rememberTrexLifecyclePaused()
        LaunchedEffect(appPaused, sessionIndex >= 0) {
            if (!appPaused) while (true) {
                app.refreshCalendar(resetPlan = sessionIndex < 0)
                delay(30_000)
            }
        }
        val pausedState = rememberUpdatedState(sessionPaused || appPaused || exitAsk)
        val plan = app.workoutPlan
        val planKey = plan.map { it.copy(done = false) }.toString()
        val steps = remember(plan.map { it.copy(done = false) }) { buildSessionSteps(plan) }
        val step = steps.getOrNull(sessionIndex)
        val finalizers = remember { mutableMapOf<String, () -> Unit>() }

        // 세션 스코프 스피커 (spec §30): 라이브 화면이 소유하면 자세→타이머 전환마다 shutdown 이 세트 요약을 끊는다.
        // 여기서 만들어 라이브 화면·완료 화면이 같은 큐를 쓰고, 세션을 나갈 때 stop 한다.
        val speech = androidx.compose.runtime.remember { SpeechCoach(context) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speech.shutdown() } }
        val startTone = remember { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 50) }.getOrNull() }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { startTone?.release() } }

        fun startSession() {
            if (steps.isEmpty()) return
            val doneIds = plan.filter { it.done }.map { it.id }.toSet()
            val finished = doneIds.size == plan.size
            val completed = if (finished) emptySet() else steps.filter {
                it.phase == SessionPhase.WORK && it.originalId in doneIds
            }.map { it.token }.toSet() + if (sessionPlanKey == planKey) progress.completed else emptySet()
            val firstWork = steps.firstOrNull { it.phase == SessionPhase.WORK && it.token !in completed } ?: return
            val first = steps.getOrNull(firstWork.token - 1)?.takeIf { it.phase != SessionPhase.WORK } ?: firstWork
            if (finished) app.updatePlan(plan.map { it.copy(done = false) })
            val recordedCounts = if (finished || sessionPlanKey != planKey) emptyMap() else progress.recordedCounts
            app.clearSessionReports(keep = steps.filter { it.token in completed || it.token in recordedCounts }.map { it.workout.id })
            postureFallback.clear()
            exitAsk = false
            speech.stop()
            progress = SessionProgress(first.token, first.seconds * 1000L,
                elapsedMs = if (finished || sessionPlanKey != planKey) 0 else progress.elapsedMs, completed = completed,
                recordedCounts = recordedCounts)
            sessionPlanKey = planKey
            sessionDone = false
            sessionPaused = false
        }

        fun nextSession(expectedToken: Int, skip: Boolean) {
            val current = steps.getOrNull(progress.index) ?: return
            if (current.token != expectedToken) return
            // 다음 화면/기록 병합 전에 세트 리포트를 확정한다. 화면 소멸 콜백보다 먼저다.
            if (current.phase == SessionPhase.WORK) finalizers[current.workout.id]?.invoke()
            speech.stop()
            progress = progress.advance(steps, expectedToken, skip)
            progress.completedOriginalIds(steps).forEach { app.markWorkoutDone(it) }
            sessionPaused = false
            if (progress.index < 0) {
                app.recordCompletedSession((progress.elapsedMs / 1000).toInt(), progress.completedWorkouts(steps), progress.workDurations(steps))
                sessionDone = true
            }
        }

        fun exitSession() {
            speech.stop()
            exitAsk = false
            progress = progress.copy(index = -1)
            sessionDone = false
            sessionPaused = false
            selectedTab = TrexTab.Home
        }

        fun exitAndRecord() {
            app.recordCompletedSession(sessionElapsed, progress.completedWorkouts(steps), progress.workDurations(steps))
            exitSession()
        }

        fun requestExit() {
            steps.getOrNull(progress.index)?.let { current ->
                if (current.phase == SessionPhase.WORK) finalizers[current.workout.id]?.invoke()
                progress = progress.captureCount(current)
            }
            if (progress.completed.isNotEmpty() || progress.recordedCounts.isNotEmpty() ||
                app.sessionPostureReports.values.any { it.observationEngine && (it.userEnteredReps ?: it.observedReps?.total ?: 0)>0 }) exitAsk = true else exitSession()
        }

        val advanceLatest = rememberUpdatedState<(Int, Boolean) -> Unit> { token, skip -> nextSession(token, skip) }
        LaunchedEffect(sessionIndex, sessionPaused, appPaused, exitAsk) {
            val token = sessionIndex
            var last = android.os.SystemClock.elapsedRealtime()
            while (progress.index == token && token >= 0) {
                delay(100)
                val now = android.os.SystemClock.elapsedRealtime()
                progress = progress.tick(now - last, pausedState.value, timed = step?.timed == true,
                    trackElapsed = step?.phase != SessionPhase.PREPARE, trackWork = step?.phase == SessionPhase.WORK)
                last = now
                if (!pausedState.value && step?.phase == SessionPhase.REST && progress.targetReached(step)) advanceLatest.value(token, false)
            }
        }
        var goalAnnouncedToken by remember { mutableIntStateOf(-1) }
        LaunchedEffect(sessionIndex, progress.repetitions, progress.secondsLeft, sessionPaused, appPaused, exitAsk) {
            if (!pausedState.value && step?.phase == SessionPhase.WORK && progress.targetReached(step) && goalAnnouncedToken != step.token) {
                goalAnnouncedToken = step.token
                speech.speak("목표에 도달했어요. 세트 완료를 눌러 기록해 주세요.", flush = false)
            }
        }
        LaunchedEffect(sessionIndex) {
            if (step?.phase == SessionPhase.WORK && !speech.muted) startTone?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
            if (step?.phase == SessionPhase.REST) speech.speak("쉬는 시간이에요", flush = true)
        }

        val sessionWorkout = step?.workout
        val route = when {
            subScreen == "postureLab" -> RootRoute.PostureLab
            subScreen == "baselineGuide" -> RootRoute.BaselineGuide
            !app.loggedIn && !app.guideDone -> RootRoute.Guide
            !app.loggedIn && subScreen == "guide" -> RootRoute.Guide
            !app.loggedIn && subScreen == "find" -> RootRoute.Find
            !app.loggedIn -> RootRoute.Auth
            !app.onboarded -> RootRoute.Onboarding
            sessionDone -> RootRoute.Complete
            sessionIndex >= 0 && sessionWorkout != null ->
                if (step.phase == SessionPhase.REST) RootRoute.TransitionSession else if (sessionWorkout.posture && sessionWorkout.postureSupported() && sessionWorkout.id !in postureFallback) {
                    RootRoute.PostureSession
                } else if (step.phase == SessionPhase.PREPARE) RootRoute.TransitionSession else {
                    RootRoute.TimerSession
                }
            subScreen == "record" -> RootRoute.Record
            subScreen == "dietRecord" -> RootRoute.DietRecord
            else -> RootRoute.Main
        }

        androidx.activity.compose.BackHandler(enabled = sessionIndex >= 0 || subScreen != "none") {
            if (sessionIndex >= 0) requestExit() else subScreen = "none"
        }

        CompositionLocalProvider(LocalTrexFold provides rememberTrexFold()) {
            Box(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
                TrexContentFrame(maxWidth = if (route in setOf(RootRoute.Auth, RootRoute.Find, RootRoute.Onboarding)) 600.dp else 840.dp,
                useWholeWindow = route == RootRoute.PostureSession) {
                    AnimatedContent(
                    targetState = route to sessionIndex,
                    // 준비→운동은 같은 세트의 카메라·분석기를 유지하고 UI/기록 모드만 바꾼다.
                    contentKey = { (screen, token) ->
                        if (screen == RootRoute.PostureSession) screen to steps.getOrNull(token)?.workout?.id
                        else screen to token
                    },
                    transitionSpec = {
                        val sessionTransition = initialState.second >= 0 || targetState.second >= 0
                        val forward = targetState.first.ordinal >= initialState.first.ordinal
                        if (sessionTransition) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else if (forward) {
                            (slideInHorizontally(tween(360)) { it / 3 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(360)) { -it / 4 } + fadeOut(tween(240)))
                        } else {
                            (slideInHorizontally(tween(360)) { -it / 3 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(360)) { it / 4 } + fadeOut(tween(240)))
                        }
                    },
                    label = "trex-route",
                    ) { (r, renderedIndex) ->
                        val renderedStep = steps.getOrNull(renderedIndex)
                        when (r) {
                            RootRoute.Guide -> GuideBookScreen(
                            onDone = {
                                app.completeGuide()
                                subScreen = "none"
                            },
                            )

                            RootRoute.Auth -> AuthScreen(
                            onLogin = { app.completeLogin() },
                            onOpenFind = { subScreen = "find" },
                            )

                            RootRoute.Find -> FindAccountScreen(onBack = { subScreen = "none" })

                            RootRoute.Onboarding -> OnboardingScreen(onDone = { profile -> app.completeOnboarding(profile) })

                            RootRoute.Complete -> SessionCompleteScreen(
                            plan = progress.completedWorkouts(steps),
                            elapsedSeconds = sessionElapsed,
                            elapsedByWorkout = progress.workDurations(steps),
                            reports = app.sessionPostureReports,
                            onLabel = { setId, actualReps, repsSource, form -> app.labelPostureSet(setId, actualReps, repsSource, form) },
                            speak = { speech.speak(it, flush = false) },
                            onDone = { exitSession() },
                            )

                            RootRoute.TransitionSession -> renderedStep?.let { current ->
                                SessionTransitionScreen(current, sessionTimeLeft, sessionPaused || appPaused || exitAsk,
                                onTogglePause = { sessionPaused = !sessionPaused },
                                onNext = { nextSession(current.token, true) }, onExit = { requestExit() })
                            }

                            RootRoute.PostureSession -> renderedStep?.let { current -> key(current.workout.id) {
                                    val w = current.workout
                                    PostureLiveSessionScreen(
                                    workout = w, index = current.exerciseIndex, total = plan.size,
                                    setLabel = current.setLabel, timeLeft = sessionTimeLeft, totalSeconds = current.seconds,
                                    paused = sessionPaused || appPaused || exitAsk,
                                    onTogglePause = { sessionPaused = !sessionPaused },
                                    onNext = { nextSession(current.token, false) },
                                    onSkip = { nextSession(current.token, true) }, onExit = { requestExit() },
                                    repetitions = progress.repetitions,
                                    onRepetitions = { count -> progress = progress.setRepetitions(steps, current.token, count) },
                                    onRepDetected = { if (!pausedState.value) progress = progress.setRepetitions(steps, current.token, progress.repetitions + 1) },
                                    onPartial = { nextSession(current.token, true) },
                                    onSetReport = { app.addPostureReport(w.id, it) },
                                    registerFinalizer = { finish -> if (finish == null) finalizers.remove(w.id) else finalizers[w.id] = finish },
                                    onFallbackToTimer = { if (w.id !in postureFallback) postureFallback.add(w.id) },
                                    speech = speech,
                                    preparing = current.phase == SessionPhase.PREPARE,
                                    onPrepared = { nextSession(current.token, true) },
                                    )
                                } }

                            RootRoute.TimerSession -> renderedStep?.let { current -> key(current.workout.id) {
                                    TimerSessionScreen(
                                    workout = current.workout, index = current.exerciseIndex, total = plan.size,
                                    setLabel = current.setLabel, timeLeft = sessionTimeLeft, totalSeconds = current.seconds,
                                    paused = sessionPaused || appPaused || exitAsk,
                                    onTogglePause = { sessionPaused = !sessionPaused },
                                    onNext = { nextSession(current.token, false) },
                                    onSkip = { nextSession(current.token, true) }, onExit = { requestExit() },
                                    repetitions = progress.repetitions,
                                    onRepetitions = { count -> progress = progress.setRepetitions(steps, current.token, count) },
                                    onPartial = { nextSession(current.token, true) },
                                    )
                                } }

                            RootRoute.Record -> RecordScreen(app = app, onBack = { subScreen = "none" })
                            RootRoute.DietRecord -> DietRecordScreen(app = app, onBack = { subScreen = "none" })

                            RootRoute.PostureLab -> PostureLabScreen(onClose = { subScreen = "none" })
                            RootRoute.BaselineGuide -> BaselineGuideScreen(onClose = { subScreen = "none" })

                            RootRoute.Main -> MainTabs(
                            app = app,
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            onStartWorkout = { startSession() },
                            onOpenRecord = { subScreen = "record" },
                            onOpenDietRecord = { subScreen = "dietRecord" },
                            )
                        }
                    }

                    if (exitAsk) {
                        TrexContentFrame(maxWidth = 600.dp) {
                            SessionExitSheet(
                            doneCount = progress.completedWorkouts(steps).size,
                            onRecord = { exitAndRecord() },
                            onDiscard = { exitSession() },
                            onCancel = { exitAsk = false },
                            )
                        }
                    }
                }
            }
        }

    }
}


/**
 * 세션 ✕ 확인 — 완료한 운동을 오늘 기록에 남길지 묻는다.
 * 자세 세트 로그(JSONL)는 어느 쪽이든 이미 저장돼 있고, 여기서 갈리는 건 **오늘 기록·출석**뿐이다.
 */
@Composable
private fun SessionExitSheet(
    doneCount: Int,
    onRecord: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Trex.c
    Box(Modifier.fillMaxSize().zIndex(50f)) {
        SheetSurface {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("운동을 끝낼까요?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "수행한 ${doneCount}세트를 기록할 수 있어요. 목표보다 적게 한 횟수도 남겨요.",
                    color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                )
                Cta("여기까지 기록하고 끝내기", onClick = onRecord, modifier = Modifier.padding(top = 6.dp).fillMaxWidth())
                GhostButton("기록 없이 끝내기", onClick = onDiscard, modifier = Modifier.fillMaxWidth())
                GhostButton("계속하기", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(
    app: AppViewModel,
    selectedTab: TrexTab,
    onTabSelected: (TrexTab) -> Unit,
    onStartWorkout: () -> Unit,
    onOpenRecord: () -> Unit,
    onOpenDietRecord: () -> Unit,
) {
    val c = Trex.c
    var navExpanded by rememberSaveable { mutableStateOf(false) }
    var sheet by androidx.compose.runtime.remember { mutableStateOf<MainSheet?>(null) }
    var lastTabOrdinal by rememberSaveable { mutableIntStateOf(selectedTab.ordinal) }

    androidx.activity.compose.BackHandler(enabled = sheet != null || navExpanded) {
        when { sheet != null -> sheet = null; else -> navExpanded = false }
    }

    // 탭과 조작부가 같은 입력에 반응한다. 지연 뒤 한 번 더 늘어나는 전환을 없앤다.
    LaunchedEffect(selectedTab) {
        navExpanded = selectedTab in setOf(TrexTab.Workout, TrexTab.Diet)
    }

    val density = LocalDensity.current
    var navSpace by remember { mutableStateOf(110.dp) }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        CompositionLocalProvider(LocalTrexNavSpace provides navSpace) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                if (forward) {
                    (slideInHorizontally(tween(200)) { 24 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(200)) { -16 } + fadeOut(tween(220)))
                } else {
                    (slideInHorizontally(tween(200)) { -24 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(200)) { 16 } + fadeOut(tween(220)))
                }
            },
            label = "tab-content",
        ) { tab ->
            when (tab) {
                TrexTab.Home -> HomeScreen(
                    app = app,
                    onGoWorkout = { onTabSelected(TrexTab.Workout) },
                    onGoDiet = { onTabSelected(TrexTab.Diet) },
                    onRecordMeal = { sheet = MainSheet.Manual(currentMealId()) },
                )

                TrexTab.Workout -> WorkoutTabScreen(
                    app = app,
                    onOpenAlt = { sheet = MainSheet.Alt(it) },
                    onOpenSets = { sheet = MainSheet.Sets(it.setDraft()) },
                    onAddWorkout = { sheet = MainSheet.AddWorkout },
                )

                TrexTab.Diet -> DietTabScreen(
                    app = app,
                    onOpenRecord = onOpenDietRecord,
                    onOpenGoals = { sheet = MainSheet.Goals },
                    onOpenPhoto = { sheet = MainSheet.Photo },
                    onOpenManual = { slot -> sheet = MainSheet.Manual(slot) },
                )

                TrexTab.Profile -> ProfileTabScreen(
                    app = app,
                    onOpenRecord = onOpenRecord,
                    onLogout = { app.logout() },
                )
            }
        }
        }
        SideEffect { lastTabOrdinal = selectedTab.ordinal }

        MorphNav(
            selectedTab = selectedTab,
            expanded = navExpanded,
            onTab = { tab ->
                if (tab == selectedTab && tab != TrexTab.Home) {
                    navExpanded = !navExpanded
                } else {
                    onTabSelected(tab)
                }
            },
            onCollapse = { navExpanded = false },
            onPrimary = {
                when (selectedTab) {
                    TrexTab.Diet -> sheet = MainSheet.Photo
                    TrexTab.Profile -> sheet = MainSheet.ProfileSettings
                    else -> onStartWorkout()
                }
            },
            onSecondary = {
                when (selectedTab) {
                    TrexTab.Diet -> sheet = MainSheet.Manual(currentMealId())
                    TrexTab.Profile -> onOpenRecord()
                    else -> onOpenRecord()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { navSpace = with(density) { it.height.toDp() } + 12.dp }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        )

        sheet?.let { s ->
            Box(Modifier.fillMaxSize().zIndex(40f)) {
                MainSheetHost(app = app, sheet = s, onClose = { sheet = null })
            }
        }

    }
}

/**
 * 모핑 내비게이션 — 4개 필이 같은 자리에서 리모컨(운동 시작·기록 / 사진 기록·직접 입력 + 뒤로)으로 변형.
 */
@Composable
private fun MorphNav(
    selectedTab: TrexTab,
    expanded: Boolean,
    onTab: (TrexTab) -> Unit,
    onCollapse: () -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Trex.c
    val remoteOn = expanded && selectedTab in setOf(TrexTab.Workout, TrexTab.Diet)
    val isDiet = selectedTab == TrexTab.Diet
    val isProfile = selectedTab == TrexTab.Profile
    val tabs = TrexTab.entries

    data class Slot(
        val weight: Float,
        val bg: Color,
        val fg: Color,
        val line: Color,
        val alpha: Float,
        val icon: ImageVector,
        val label: String,
        val showLabel: Boolean,
        val onClick: () -> Unit,
        val elevated: Boolean,
    )

    val slots = tabs.mapIndexed { i, tab ->
        val active = tab == selectedTab
        val baseIcon = when (tab) {
            TrexTab.Home -> Icons.Rounded.Home
            TrexTab.Workout -> Icons.Rounded.FitnessCenter
            TrexTab.Diet -> Icons.Rounded.Restaurant
            TrexTab.Profile -> Icons.Rounded.Person
        }
        if (!remoteOn) {
            Slot(
                weight = if (active) 2.1f else 1f,
                bg = if (active) c.primary else c.surface,
                fg = if (active) Color.White else c.text3,
                line = if (active) c.primary else c.line,
                alpha = 1f, icon = baseIcon, label = tab.label,
                showLabel = active,
                onClick = { onTab(tab) },
                elevated = active,
            )
        } else {
            val primaryIdx = 3
            val secondaryIdx = 1
            when (i) {
                0 -> Slot(
                    weight = 1f, bg = c.surface, fg = c.text2, line = c.line, alpha = 1f,
                    icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "뒤로가기", showLabel = false,
                    onClick = onCollapse, elevated = false,
                )
                primaryIdx -> Slot(
                    weight = 3.1f, bg = c.primary, fg = Color.White, line = c.primary, alpha = 1f,
                    icon = if (isProfile) Icons.Rounded.Person else if (isDiet) Icons.Rounded.PhotoCamera else Icons.Rounded.PlayArrow,
                    label = if (isProfile) "설정" else if (isDiet) "사진 기록" else "운동 시작", showLabel = true,
                    onClick = onPrimary, elevated = true,
                )
                secondaryIdx -> Slot(
                    weight = 2.4f, bg = c.surface, fg = c.primaryText, line = c.line, alpha = 1f,
                    icon = if (isDiet) Icons.Rounded.Edit else Icons.Rounded.BarChart,
                    label = if (isProfile) "운동 기록" else if (isDiet) "직접 입력" else "운동 기록", showLabel = true,
                    onClick = onSecondary, elevated = false,
                )
                else -> Slot(
                    weight = 0.0001f, bg = c.surface, fg = c.text3, line = c.line, alpha = 0f,
                    icon = baseIcon, label = tab.label, showLabel = false,
                    onClick = {}, elevated = false,
                )
            }
        }
    }

    val largeText = LocalDensity.current.fontScale > 1.3f
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        slots.forEachIndexed { i, slot ->
            val weight = slot.weight
            val alpha by animateFloatAsState(slot.alpha, tween(260), label = "nav-a$i")
            val h = 56.dp
            Surface(
                onClick = slot.onClick,
                enabled = slot.alpha > 0.1f,
                modifier = Modifier
                    .weight(weight.coerceAtLeast(0.0001f))
                    .heightIn(min = h)
                    .alpha(alpha),
                shape = RoundedCornerShape(999.dp),
                color = slot.bg,
                contentColor = slot.fg,
                border = androidx.compose.foundation.BorderStroke(1.dp, slot.line),
                shadowElevation = if (slot.elevated) 3.dp else 1.dp,
            ) {
                if (largeText) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(slot.icon, contentDescription = slot.label, modifier = Modifier.size(19.dp))
                        if (slot.showLabel) Text(slot.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center)
                    }
                } else Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 17.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(slot.icon, contentDescription = slot.label, modifier = Modifier.size(19.dp))
                    androidx.compose.animation.AnimatedVisibility(visible = slot.showLabel) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(7.dp))
                            Text(slot.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
            if (i != slots.lastIndex) Spacer(Modifier.width(if (slot.alpha > 0.1f) 8.dp else 0.dp))
        }
    }
}

fun Context.findTrexActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
