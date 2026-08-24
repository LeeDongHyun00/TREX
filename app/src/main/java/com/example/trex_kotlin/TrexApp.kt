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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trex_kotlin.posture.BaselineGuideScreen
import com.example.trex_kotlin.posture.PostureLabScreen
import kotlinx.coroutines.delay

/**
 * TREX 리디자인 루트 — 방향성 슬라이드 라우팅 + 모핑 내비게이션 (디자인 캔버스 "TREX Redesign").
 * 화면 순서(guide→auth→find→onboarding→main→record→session→complete)상 앞으로 가면 오른쪽에서,
 * 뒤로 가면 왼쪽에서 들어온다.
 */
private enum class RootRoute { Guide, Auth, Find, Onboarding, Main, Record, PostureSession, TimerSession, Complete, PostureLab, BaselineGuide }

/** 메인 하단 시트. */
sealed class MainSheet {
    data class Alt(val workout: Workout) : MainSheet()
    data class Sets(val draft: SetDraft) : MainSheet()
    data object Goals : MainSheet()
    data class Manual(val slot: String) : MainSheet()
    data object Photo : MainSheet()
    data object AddWorkout : MainSheet()
}

data class SetDraft(val id: String, val name: String, val count: Int, val unit: String, val sets: Int)

fun Workout.setDraft(): SetDraft {
    val spec = repsSpec()
    val unit = when {
        reps.contains("초") -> "초"
        reps.contains("분") && !reps.contains("회") -> "분"
        else -> "회"
    }
    return SetDraft(id = id, name = name, count = spec.count, unit = unit, sets = if (unit == "분") 0 else spec.sets)
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
            }
        }

        var selectedTab by rememberSaveable { mutableStateOf(TrexTab.Home) }
        var subScreen by rememberSaveable { mutableStateOf("none") } // none | find | guide | record | postureLab | baselineGuide
        var sessionIndex by rememberSaveable { mutableIntStateOf(-1) }
        var sessionDone by rememberSaveable { mutableStateOf(false) }
        var sessionTimeLeft by rememberSaveable { mutableIntStateOf(0) }
        var sessionElapsed by rememberSaveable { mutableIntStateOf(0) }
        var sessionPaused by rememberSaveable { mutableStateOf(false) }
        val appPaused = rememberTrexLifecyclePaused()
        val pausedState = rememberUpdatedState(sessionPaused || appPaused)
        val plan = app.workoutPlan

        fun sessionSeconds(w: Workout): Int = (w.durationMinutes() * 60).coerceAtLeast(30)

        fun startSession() {
            val start = plan.indexOfFirst { !it.done }.takeIf { it >= 0 } ?: 0
            if (plan.isEmpty()) return
            sessionIndex = start
            sessionDone = false
            sessionElapsed = 0
            sessionPaused = false
            sessionTimeLeft = sessionSeconds(plan[start])
        }

        fun nextSession() {
            val idx = sessionIndex
            if (idx < 0) return
            plan.getOrNull(idx)?.let { app.markWorkoutDone(it.id) }
            val next = idx + 1
            if (next >= plan.size) {
                app.recordCompletedSession(sessionElapsed)
                sessionIndex = -1
                sessionDone = true
            } else {
                sessionIndex = next
                sessionTimeLeft = sessionSeconds(plan[next])
                sessionPaused = false
            }
        }

        fun exitSession() {
            sessionIndex = -1
            sessionDone = false
            sessionPaused = false
            selectedTab = TrexTab.Home
        }

        LaunchedEffect(sessionIndex) {
            while (sessionIndex >= 0) {
                delay(1000)
                if (!pausedState.value) {
                    sessionElapsed += 1
                    if (sessionTimeLeft > 0) sessionTimeLeft -= 1
                }
            }
        }

        val sessionWorkout = plan.getOrNull(sessionIndex.coerceAtMost(plan.lastIndex))
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
                if (sessionWorkout.posture && sessionWorkout.postureSupported()) RootRoute.PostureSession else RootRoute.TimerSession
            subScreen == "record" -> RootRoute.Record
            else -> RootRoute.Main
        }

        Box(Modifier.fillMaxSize().background(c.bg)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    if (forward) {
                        (slideInHorizontally(tween(360)) { it / 3 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(360)) { -it / 4 } + fadeOut(tween(240)))
                    } else {
                        (slideInHorizontally(tween(360)) { -it / 3 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(360)) { it / 4 } + fadeOut(tween(240)))
                    }
                },
                label = "trex-route",
            ) { r ->
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
                        onOpenGuide = { subScreen = "guide" },
                        onOpenPostureLab = { subScreen = "postureLab" },
                        onOpenBaselineGuide = { subScreen = "baselineGuide" },
                    )

                    RootRoute.Find -> FindAccountScreen(onBack = { subScreen = "none" })

                    RootRoute.Onboarding -> OnboardingScreen(onDone = { profile -> app.completeOnboarding(profile) })

                    RootRoute.Complete -> SessionCompleteScreen(
                        plan = plan,
                        elapsedSeconds = sessionElapsed,
                        onDone = { exitSession() },
                    )

                    RootRoute.PostureSession -> sessionWorkout?.let { w ->
                        PostureLiveSessionScreen(
                            workout = w,
                            index = sessionIndex,
                            total = plan.size,
                            timeLeft = sessionTimeLeft,
                            totalSeconds = sessionSeconds(w),
                            paused = sessionPaused || appPaused,
                            onTogglePause = { sessionPaused = !sessionPaused },
                            onNext = { nextSession() },
                            onExit = { exitSession() },
                        )
                    }

                    RootRoute.TimerSession -> sessionWorkout?.let { w ->
                        TimerSessionScreen(
                            workout = w,
                            index = sessionIndex,
                            total = plan.size,
                            timeLeft = sessionTimeLeft,
                            totalSeconds = sessionSeconds(w),
                            paused = sessionPaused || appPaused,
                            onTogglePause = { sessionPaused = !sessionPaused },
                            onNext = { nextSession() },
                            onExit = { exitSession() },
                        )
                    }

                    RootRoute.Record -> RecordScreen(app = app, onBack = { subScreen = "none" })

                    RootRoute.PostureLab -> PostureLabScreen(onClose = { subScreen = "none" })
                    RootRoute.BaselineGuide -> BaselineGuideScreen(onClose = { subScreen = "none" })

                    RootRoute.Main -> MainTabs(
                        app = app,
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        onStartWorkout = { startSession() },
                        onOpenRecord = { subScreen = "record" },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainTabs(
    app: AppViewModel,
    selectedTab: TrexTab,
    onTabSelected: (TrexTab) -> Unit,
    onStartWorkout: () -> Unit,
    onOpenRecord: () -> Unit,
) {
    val c = Trex.c
    var navExpanded by rememberSaveable { mutableStateOf(false) }
    var sheet by androidx.compose.runtime.remember { mutableStateOf<MainSheet?>(null) }
    var lastTabOrdinal by rememberSaveable { mutableIntStateOf(selectedTab.ordinal) }

    // 운동/식단 탭 진입 260ms 후 리모컨으로 확장 (디자인의 armExpand)
    LaunchedEffect(selectedTab) {
        navExpanded = false
        if (selectedTab == TrexTab.Workout || selectedTab == TrexTab.Diet) {
            delay(260)
            navExpanded = true
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                if (forward) {
                    (slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(340)) { -it / 4 } + fadeOut(tween(220)))
                } else {
                    (slideInHorizontally(tween(340)) { -it / 3 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(340)) { it / 4 } + fadeOut(tween(220)))
                }
            },
            label = "tab-content",
        ) { tab ->
            when (tab) {
                TrexTab.Home -> HomeScreen(
                    app = app,
                    onGoWorkout = { onTabSelected(TrexTab.Workout) },
                    onGoDiet = { onTabSelected(TrexTab.Diet) },
                )

                TrexTab.Workout -> WorkoutTabScreen(
                    app = app,
                    onOpenAlt = { sheet = MainSheet.Alt(it) },
                    onOpenSets = { sheet = MainSheet.Sets(it.setDraft()) },
                    onAddWorkout = { sheet = MainSheet.AddWorkout },
                )

                TrexTab.Diet -> DietTabScreen(
                    app = app,
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
        SideEffect { lastTabOrdinal = selectedTab.ordinal }

        MorphNav(
            selectedTab = selectedTab,
            expanded = navExpanded,
            onTab = { tab ->
                if (tab == selectedTab && (tab == TrexTab.Workout || tab == TrexTab.Diet)) {
                    navExpanded = !navExpanded
                } else {
                    onTabSelected(tab)
                }
            },
            onCollapse = { navExpanded = false },
            onPrimary = {
                if (selectedTab == TrexTab.Diet) sheet = MainSheet.Photo else onStartWorkout()
            },
            onSecondary = {
                if (selectedTab == TrexTab.Diet) sheet = MainSheet.Manual(currentMealId()) else onOpenRecord()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
    val remoteOn = expanded && (selectedTab == TrexTab.Workout || selectedTab == TrexTab.Diet)
    val isDiet = selectedTab == TrexTab.Diet
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
            val primaryIdx = if (isDiet) 2 else 1
            val secondaryIdx = if (isDiet) 1 else 2
            when (i) {
                3 -> Slot(
                    weight = 1f, bg = c.surface, fg = c.text2, line = c.line, alpha = 1f,
                    icon = Icons.AutoMirrored.Rounded.ArrowForward, label = "뒤로", showLabel = false,
                    onClick = onCollapse, elevated = false,
                )
                primaryIdx -> Slot(
                    weight = 3.1f, bg = c.primary, fg = Color.White, line = c.primary, alpha = 1f,
                    icon = if (isDiet) Icons.Rounded.PhotoCamera else Icons.Rounded.PlayArrow,
                    label = if (isDiet) "사진 기록" else "운동 시작", showLabel = true,
                    onClick = onPrimary, elevated = true,
                )
                secondaryIdx -> Slot(
                    weight = 2.4f, bg = c.surface, fg = c.primaryText, line = c.line, alpha = 1f,
                    icon = if (isDiet) Icons.Rounded.Edit else Icons.Rounded.BarChart,
                    label = if (isDiet) "직접 입력" else "기록", showLabel = true,
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

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        slots.forEachIndexed { i, slot ->
            val weight by animateFloatAsState(slot.weight, tween(400), label = "nav-w$i")
            val alpha by animateFloatAsState(slot.alpha, tween(260), label = "nav-a$i")
            val h by animateDpAsState(if (remoteOn) 56.dp else 54.dp, tween(300), label = "nav-h$i")
            Surface(
                onClick = slot.onClick,
                enabled = slot.alpha > 0.1f,
                modifier = Modifier
                    .weight(weight.coerceAtLeast(0.0001f))
                    .height(h)
                    .alpha(alpha),
                shape = RoundedCornerShape(999.dp),
                color = slot.bg,
                contentColor = slot.fg,
                border = androidx.compose.foundation.BorderStroke(1.dp, slot.line),
                shadowElevation = if (slot.elevated) 8.dp else 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
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
