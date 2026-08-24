package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trex_kotlin.posture.BaselineGuideScreen
import com.example.trex_kotlin.posture.PostureLabScreen
import kotlinx.coroutines.delay

@Composable
fun TrexApp(app: AppViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableStateOf(TrexTab.Home) }
    // 진행 플래그(guideDone/loggedIn/onboarded)는 AppViewModel 이 소유·영속화한다.
    // 아래 둘은 개발용 진입점이라 세션 한정 UI 상태로 남긴다.
    var postureLab by rememberSaveable { mutableStateOf(false) }
    var baselineGuide by rememberSaveable { mutableStateOf(false) }
    var sessionIndex by rememberSaveable { mutableIntStateOf(-1) }
    var sessionDone by rememberSaveable { mutableStateOf(false) }
    val workoutPlan = app.workoutPlan
    var sessionElapsedSeconds by rememberSaveable { mutableIntStateOf(0) }
    var sessionPaused by rememberSaveable { mutableStateOf(false) }
    var sessionNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val appPaused = rememberTrexLifecyclePaused()
    val appPausedState = rememberUpdatedState(appPaused)
    val sessionPausedState = rememberUpdatedState(sessionPaused)

    fun startSession() {
        if (workoutPlan.isEmpty()) return
        sessionIndex = 0
        sessionDone = false
        sessionElapsedSeconds = 0
        sessionPaused = false
        sessionNotice = null
    }

    fun advanceSession() {
        if (sessionIndex < 0) return
        sessionNotice = null
        if (sessionIndex + 1 >= workoutPlan.size) {
            app.recordCompletedSession(sessionElapsedSeconds)
            sessionIndex = -1
            sessionDone = true
            sessionPaused = false
        } else {
            sessionIndex += 1
        }
    }

    fun exitSession() {
        sessionIndex = -1
        sessionDone = false
        sessionPaused = false
        sessionNotice = null
        selectedTab = TrexTab.Home
    }

    fun fallbackToTimerSession(id: String) {
        app.updatePlan(
            workoutPlan.map { workout ->
                if (workout.id == id) workout.copy(posture = false) else workout
            },
        )
        sessionNotice = "카메라 권한이 거부되어 자세 교정 OFF 모드로 전환했어요."
    }

    LaunchedEffect(sessionIndex, sessionDone) {
        while (sessionIndex >= 0 && !sessionDone) {
            delay(1000)
            if (!appPausedState.value && !sessionPausedState.value) {
                sessionElapsedSeconds += 1
            }
        }
    }

    ScreenScaffold {
        AnimatedContent(
            targetState = AppRoute(
                loggedIn = app.loggedIn,
                guideDone = app.guideDone,
                onboarded = app.onboarded,
                sessionIndex = sessionIndex.coerceAtMost(workoutPlan.lastIndex),
                sessionDone = sessionDone,
                postureLab = postureLab,
                baselineGuide = baselineGuide,
            ),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "trex-route",
        ) { route ->
            when {
                route.baselineGuide -> BaselineGuideScreen(onClose = { baselineGuide = false })
                route.postureLab -> PostureLabScreen(onClose = { postureLab = false })
                !route.guideDone -> GuideBookScreen(onLogin = { app.completeGuide() })
                !route.loggedIn -> LoginScreen(
                    onLogin = { app.completeLogin() },
                    onOpenPostureLab = { postureLab = true },
                    onOpenBaselineGuide = { baselineGuide = true },
                )
                !route.onboarded -> OnboardingScreen(onDone = { profile -> app.completeOnboarding(profile) })
                route.sessionDone -> SessionCompleteScreen(
                    onDone = {
                        sessionDone = false
                        selectedTab = TrexTab.Home
                    },
                )

                route.sessionIndex >= 0 -> {
                    val workout = workoutPlan[route.sessionIndex]
                    val nextWorkout = workoutPlan.getOrNull(route.sessionIndex + 1)
                    if (workout.posture) {
                        PostureSessionScreen(
                            workout = workout,
                            index = route.sessionIndex,
                            total = workoutPlan.size,
                            nextWorkout = nextWorkout,
                            elapsedSeconds = sessionElapsedSeconds,
                            onCameraDenied = { fallbackToTimerSession(workout.id) },
                            onPausedChange = { sessionPaused = it },
                            onNext = ::advanceSession,
                            onExit = ::exitSession,
                        )
                    } else {
                        TimerSessionScreen(
                            workout = workout,
                            index = route.sessionIndex,
                            total = workoutPlan.size,
                            nextWorkout = nextWorkout,
                            elapsedSeconds = sessionElapsedSeconds,
                            notice = sessionNotice,
                            onNoticeConsumed = { sessionNotice = null },
                            onPausedChange = { sessionPaused = it },
                            onNext = ::advanceSession,
                            onExit = ::exitSession,
                        )
                    }
                }

                else -> MainTabs(
                    app = app,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onStartWorkout = ::startSession,
                )
            }
        }
    }
}

private data class AppRoute(
    val guideDone: Boolean,
    val loggedIn: Boolean,
    val onboarded: Boolean,
    val sessionIndex: Int,
    val sessionDone: Boolean,
    val postureLab: Boolean = false,
    val baselineGuide: Boolean = false,
)

@Composable
private fun MainTabs(
    app: AppViewModel,
    selectedTab: TrexTab,
    onTabSelected: (TrexTab) -> Unit,
    onStartWorkout: () -> Unit,
) {
    var tabOverlayVisible by rememberSaveable { mutableStateOf(false) }
    var workoutNavigation by rememberSaveable { mutableStateOf(WorkoutNavigationTab.Schedule) }
    var dietRecordRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var dietRecordLaunchAction by rememberSaveable { mutableStateOf(DietRecordLaunchAction.Camera) }
    val dietRecentFoodNames = remember(app.dietByDay) { app.recentFoodsForCurrentMeal().map { it.name } }

    LaunchedEffect(selectedTab) {
        tabOverlayVisible = false
    }

    val navAlpha by animateFloatAsState(
        targetValue = if (tabOverlayVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "tab-nav-alpha",
    )
    val navOffsetY by animateDpAsState(
        targetValue = if (tabOverlayVisible) 20.dp else 0.dp,
        animationSpec = tween(durationMillis = 160),
        label = "tab-nav-offset-y",
    )

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = selectedTab, label = "tab-content") { tab ->
            when (tab) {
                TrexTab.Home -> HomeScreen(app = app)
                TrexTab.Workout -> when (workoutNavigation) {
                    WorkoutNavigationTab.Schedule -> WorkoutListScreen(
                        plan = app.workoutPlan,
                        onPlanChange = { app.updatePlan(it) },
                        onSheetVisibleChange = { tabOverlayVisible = it },
                    )
                    WorkoutNavigationTab.History -> WorkoutHistoryScreen(records = app.workoutHistory)
                }
                TrexTab.Diet -> DietScreen(
                    app = app,
                    recordRequestToken = dietRecordRequestToken,
                    recordLaunchAction = dietRecordLaunchAction,
                    onSheetVisibleChange = { tabOverlayVisible = it },
                )
                TrexTab.Profile -> ProfileScreen(profile = app.profile, onLogout = { app.logout() })
            }
        }

        BottomNav(
            selectedTab = selectedTab,
            workoutNavigation = workoutNavigation,
            dietRecentFoodNames = dietRecentFoodNames,
            onTabSelected = onTabSelected,
            onWorkoutNavigationSelected = { workoutNavigation = it },
            onStartWorkout = onStartWorkout,
            onStartDietRecord = { action ->
                dietRecordLaunchAction = action
                dietRecordRequestToken += 1
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(if (tabOverlayVisible) -1f else 1f)
                .offset(y = navOffsetY)
                .alpha(navAlpha)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun BottomNav(
    selectedTab: TrexTab,
    workoutNavigation: WorkoutNavigationTab,
    dietRecentFoodNames: List<String>,
    onTabSelected: (TrexTab) -> Unit,
    onWorkoutNavigationSelected: (WorkoutNavigationTab) -> Unit,
    onStartWorkout: () -> Unit,
    onStartDietRecord: (DietRecordLaunchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var photoActionsOpen by rememberSaveable { mutableStateOf(false) }
    val actionTab = selectedTab == TrexTab.Workout || selectedTab == TrexTab.Diet
    val workoutMenuVisible = selectedTab == TrexTab.Workout && expanded && !photoActionsOpen

    LaunchedEffect(selectedTab) {
        expanded = false
        photoActionsOpen = false
        if (actionTab) {
            delay(260)
            expanded = true
        }
    }

    val navHeight by animateDpAsState(
        targetValue = when {
            photoActionsOpen -> 232.dp
            workoutMenuVisible -> 126.dp
            else -> 68.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "nav-height",
    )
    val navCorner by animateDpAsState(
        targetValue = if (photoActionsOpen || workoutMenuVisible) 28.dp else 999.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "nav-corner",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(navHeight),
        shape = RoundedCornerShape(navCorner),
        color = TrexDarkAlt.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tabs = TrexTab.entries
            val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
            val tabWidth = maxWidth / tabs.size
            val collapsedSize = 28.dp
            val expandedCloseWidth = 58.dp
            val expandedWidth = maxWidth - expandedCloseWidth - 30.dp
            val expandedHeight = 44.dp
            val highlightSpec = spring<Dp>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
            val highlightX by animateDpAsState(
                targetValue = if (expanded) {
                    8.dp
                } else {
                    tabWidth * selectedIndex.toFloat() + (tabWidth / 2) - (collapsedSize / 2)
                },
                animationSpec = highlightSpec,
                label = "nav-highlight-x",
            )
            val highlightY by animateDpAsState(
                targetValue = when {
                    photoActionsOpen -> 8.dp
                    workoutMenuVisible -> 72.dp
                    expanded -> 12.dp
                    else -> 8.dp
                },
                animationSpec = highlightSpec,
                label = "nav-highlight-y",
            )
            val highlightWidth by animateDpAsState(
                targetValue = when {
                    photoActionsOpen -> maxWidth - 16.dp
                    expanded -> expandedWidth
                    else -> collapsedSize
                },
                animationSpec = highlightSpec,
                label = "nav-highlight-width",
            )
            val highlightHeight by animateDpAsState(
                targetValue = when {
                    photoActionsOpen -> 48.dp
                    expanded -> expandedHeight
                    else -> collapsedSize
                },
                animationSpec = highlightSpec,
                label = "nav-highlight-height",
            )
            val collapsedIconAlpha by animateFloatAsState(
                targetValue = if (expanded) 0f else 1f,
                animationSpec = tween(durationMillis = 120),
                label = "nav-collapsed-icon-alpha",
            )
            val actionContentAlpha by animateFloatAsState(
                targetValue = if (expanded) 1f else 0f,
                animationSpec = tween(durationMillis = 160, delayMillis = if (expanded) 90 else 0),
                label = "nav-action-alpha",
            )
            val photoBottomAlpha by animateFloatAsState(
                targetValue = if (photoActionsOpen) 1f else 0f,
                animationSpec = tween(durationMillis = 170, delayMillis = if (photoActionsOpen) 120 else 0),
                label = "photo-bottom-alpha",
            )

            AnimatedVisibility(
                visible = workoutMenuVisible,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(90)),
            ) {
                Row(
                    modifier = Modifier
                        .offset(x = 8.dp, y = 10.dp)
                        .fillMaxWidth()
                        .padding(end = 16.dp)
                        .height(44.dp)
                        .alpha(actionContentAlpha),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkoutNavButton(
                        text = WorkoutNavigationTab.Schedule.label,
                        selected = workoutNavigation == WorkoutNavigationTab.Schedule,
                        onClick = { onWorkoutNavigationSelected(WorkoutNavigationTab.Schedule) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkoutNavButton(
                        text = WorkoutNavigationTab.History.label,
                        selected = workoutNavigation == WorkoutNavigationTab.History,
                        onClick = { onWorkoutNavigationSelected(WorkoutNavigationTab.History) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (!expanded && !photoActionsOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val selected = selectedTab == tab
                        Surface(
                            onClick = { onTabSelected(tab) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            color = Color.Transparent,
                            contentColor = if (selected) TrexLime else Color.White.copy(alpha = 0.62f),
                        ) {
                            NavItem(tab = tab, selected = selected, hidden = false)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .offset(x = highlightX, y = highlightY)
                    .width(highlightWidth)
                    .height(highlightHeight)
                    .clip(RoundedCornerShape(999.dp))
                    .background(TrexLime)
                    .clickable(
                        enabled = expanded,
                        onClick = {
                            when {
                                selectedTab == TrexTab.Workout -> onStartWorkout()
                                selectedTab == TrexTab.Diet -> photoActionsOpen = true
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = navIcon(selectedTab),
                    contentDescription = null,
                    tint = TrexDark,
                    modifier = Modifier
                        .size(15.dp)
                        .alpha(collapsedIconAlpha),
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(actionContentAlpha),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = when {
                        selectedTab == TrexTab.Workout -> Icons.Rounded.PlayArrow
                        else -> Icons.Rounded.Restaurant
                    }
                    Icon(icon, contentDescription = null, tint = TrexDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selectedTab == TrexTab.Workout) "운동 시작" else "식단 기록하기",
                        color = TrexDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AnimatedVisibility(
                visible = photoActionsOpen,
                enter = fadeIn(tween(150, delayMillis = 80)) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(tween(90)) + slideOutVertically(targetOffsetY = { it / 4 }),
            ) {
                Column(
                    modifier = Modifier
                        .offset(x = 8.dp, y = 64.dp)
                        .fillMaxWidth()
                        .padding(end = 16.dp)
                        .alpha(photoBottomAlpha),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhotoNavButton(
                            text = "사진 촬영",
                            icon = Icons.Rounded.PhotoCamera,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                photoActionsOpen = false
                                onStartDietRecord(DietRecordLaunchAction.Camera)
                            },
                        )
                        PhotoNavButton(
                            text = "갤러리에서 선택",
                            icon = Icons.Rounded.Image,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                photoActionsOpen = false
                                onStartDietRecord(DietRecordLaunchAction.Gallery)
                            },
                        )
                    }
                    PhotoNavButton(
                        text = if (dietRecentFoodNames.isEmpty()) "최근 기록 다시 기록하기" else "최근 기록 다시 기록하기 · ${dietRecentFoodNames.take(3).joinToString(", ")}",
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            photoActionsOpen = false
                            onStartDietRecord(DietRecordLaunchAction.Recent)
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhotoNavButton(
                            text = "수동으로 입력하기",
                            icon = Icons.Rounded.Restaurant,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                photoActionsOpen = false
                                onStartDietRecord(DietRecordLaunchAction.Manual)
                            },
                        )
                        Surface(
                            onClick = { photoActionsOpen = false },
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = TrexError.copy(alpha = 0.14f),
                            contentColor = Color(0xFFFF8A8A),
                            border = dimBorder(0.14f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "닫기",
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded && !photoActionsOpen,
                modifier = Modifier
                    .align(if (workoutMenuVisible) Alignment.BottomEnd else Alignment.CenterEnd)
                    .padding(
                        end = 6.dp,
                        bottom = if (workoutMenuVisible) 10.dp else 0.dp,
                    ),
                enter = fadeIn(tween(120)) + scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    initialScale = 0.65f,
                ),
                exit = fadeOut(tween(80)) + scaleOut(targetScale = 0.65f),
            ) {
                Surface(
                    onClick = {
                        expanded = false
                        onTabSelected(TrexTab.Home)
                    },
                    modifier = Modifier
                        .width(expandedCloseWidth)
                        .height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = TrexError.copy(alpha = 0.16f),
                    contentColor = Color(0xFFFF8A8A),
                    border = dimBorder(0.12f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "닫기",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: TrexTab, selected: Boolean, hidden: Boolean) {
    val itemAlpha by animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "nav-item-alpha",
    )
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(itemAlpha),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = navIcon(tab),
            contentDescription = null,
            tint = if (selected) Color.Transparent else Color.White.copy(alpha = 0.68f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            color = when {
                selected -> TrexLime
                else -> Color.White.copy(alpha = 0.62f)
            },
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkoutNavButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) TrexLime else Color.White.copy(alpha = 0.1f),
        contentColor = if (selected) TrexDark else Color.White,
        border = if (selected) null else dimBorder(0.14f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PhotoNavButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.1f),
        contentColor = Color.White,
        border = dimBorder(0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun navIcon(tab: TrexTab): ImageVector = when (tab) {
    TrexTab.Home -> Icons.Rounded.Home
    TrexTab.Workout -> Icons.Rounded.FitnessCenter
    TrexTab.Diet -> Icons.Rounded.Restaurant
    TrexTab.Profile -> Icons.Rounded.Person
}

