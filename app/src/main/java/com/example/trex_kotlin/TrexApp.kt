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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.example.trex_kotlin.store.TrexSnapshot
import com.example.trex_kotlin.store.rememberTrexStore
import com.example.trex_kotlin.store.toPersistedHistory
import com.example.trex_kotlin.store.toPersistedPlan
import com.example.trex_kotlin.store.toWorkoutHistory
import com.example.trex_kotlin.store.toWorkoutPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * How long an edit rests before it reaches the disk. Long enough that a drag-reorder or a held
 * stepper writes once rather than once per frame, short enough that an ordinary tap is durable
 * before the user can reach the system back gesture.
 *
 * Nothing rides on the exact value for correctness: disposal, backgrounding and session completion
 * each flush unconditionally, so the only way to lose an edit inside this window is a kill that
 * runs no lifecycle callback at all.
 */
private const val PERSIST_DEBOUNCE_MILLIS = 250L

@Composable
fun TrexApp() {
    val store = rememberTrexStore()
    // One synchronous, sub-kilobyte read on the first composition, deliberately. The three gate
    // flags below decide which screen renders at all, so loading them asynchronously would show
    // the guide or login screen for a frame and then jump. Re-runs on a fresh composition, which
    // is what serves rotation: every mutation is written through, so the reload is current.
    val restored = remember(store) { store.load() }

    var selectedTab by rememberSaveable { mutableStateOf(TrexTab.Home) }
    var guideDone by rememberSaveable { mutableStateOf(restored?.guideDone == true) }
    var loggedIn by rememberSaveable { mutableStateOf(restored?.loggedIn == true) }
    var onboarding by remember { mutableStateOf(restored?.onboarding) }
    // Derived rather than stored beside its payload. The flag and the answers are set by the same
    // callback and can never legitimately disagree, but two separate sources could: a saveable
    // flag restores from the instance-state bundle while the answers restore from disk, so a
    // bundle could assert "onboarded" over answers that were never written. The next snapshot
    // would then cement `onboarded = true, onboarding = null` — and because the screen never shows
    // again, the body metrics could never be collected a second time. One source of truth removes
    // the whole class of failure instead of reconciling it.
    val onboarded = onboarding != null
    var sessionIndex by rememberSaveable { mutableIntStateOf(-1) }
    var sessionDone by rememberSaveable { mutableStateOf(false) }
    var workoutPlan by remember {
        mutableStateOf(restored?.plan?.toWorkoutPlan()?.takeIf { it.isNotEmpty() } ?: todayPlan)
    }
    // Starts empty rather than seeded. `seedWorkoutHistory` fabricates a week by rotating today's
    // plan over past dates; once history is durable, the first finished session would write that
    // fiction to disk as the user's own record. An empty history screen is the honest first launch.
    var workoutHistory by remember {
        mutableStateOf(restored?.history?.toWorkoutHistory() ?: emptyList())
    }
    var sessionElapsedSeconds by rememberSaveable { mutableIntStateOf(0) }
    var sessionPaused by rememberSaveable { mutableStateOf(false) }
    var sessionNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var placementCoachOpen by rememberSaveable { mutableStateOf(false) }
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
            workoutHistory = workoutHistory.replaceTodayWith(
                createWorkoutHistoryDay(workoutPlan, sessionElapsedSeconds),
            )
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

    fun currentSnapshot(): TrexSnapshot = TrexSnapshot(
        guideDone = guideDone,
        loggedIn = loggedIn,
        // Written from the derived value, so the file can never carry the inconsistent pair.
        onboarded = onboarded,
        onboarding = onboarding,
        plan = workoutPlan.toPersistedPlan(),
        history = workoutHistory.toPersistedHistory(),
    )

    // Every ordinary edit is caught here rather than at each call site: `snapshotFlow` records the
    // state reads made inside `currentSnapshot()`, so the five plan-edit paths in the workout list
    // and the history write in `advanceSession` are all covered without being touched. The debounce
    // stops a drag-reorder from writing once per frame; the flow conflates identical snapshots, so
    // a recomposition that changes nothing persists nothing.
    LaunchedEffect(store) {
        snapshotFlow { currentSnapshot() }
            .drop(1)
            .collectLatest { snapshot ->
                delay(PERSIST_DEBOUNCE_MILLIS)
                withContext(Dispatchers.IO) { store.save(snapshot) }
            }
    }

    // The flush that actually has to hold, and the reason it is synchronous rather than a
    // coroutine. A configuration change runs pause, stop and destroy inside a single main-thread
    // message: no frame is drawn between them, so a lifecycle observer's state write never causes
    // a recomposition and any coroutine launched from one never gets to start. `NonCancellable`
    // does not help — it protects a block that has already begun, and this one never would. An
    // `onDispose` body is the last code guaranteed to run, so the write happens there, on the main
    // thread. The payload is a few hundred bytes and the matching load is synchronous too.
    DisposableEffect(store) {
        onDispose { store.save(currentSnapshot()) }
    }

    // Backgrounding without disposal — home button, screen off — never reaches `onDispose`, and it
    // is the likeliest moment for the process to be killed. This is the coroutine case where the
    // frame does run, so NonCancellable is meaningful here.
    LaunchedEffect(sessionDone, appPaused) {
        if (sessionDone || appPaused) {
            withContext(NonCancellable + Dispatchers.IO) { store.save(currentSnapshot()) }
        }
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
                loggedIn = loggedIn,
                guideDone = guideDone,
                onboarded = onboarded,
                sessionIndex = sessionIndex,
                sessionDone = sessionDone,
                placementCoachOpen = placementCoachOpen,
            ),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "trex-route",
        ) { route ->
            when {
                !route.guideDone -> GuideBookScreen(onLogin = { guideDone = true })
                !route.loggedIn -> LoginScreen(onLogin = { loggedIn = true })
                !route.onboarded -> OnboardingScreen(onDone = { answers -> onboarding = answers })
                route.sessionDone -> SessionCompleteScreen(
                    onDone = {
                        sessionDone = false
                        selectedTab = TrexTab.Home
                    },
                )

                route.sessionIndex >= 0 -> {
                    // The session cursor is saveable while the plan is restored from disk, so a
                    // cursor recovered from an instance-state bundle can outlive the plan it
                    // indexed — a shorter restored plan would make the raw subscript that used to
                    // be here throw on the launch path. Falling out of the session is the only
                    // sane recovery.
                    val workout = workoutPlan.getOrNull(route.sessionIndex)
                    val nextWorkout = workoutPlan.getOrNull(route.sessionIndex + 1)
                    if (workout == null) {
                        LaunchedEffect(Unit) { exitSession() }
                    } else if (workout.canUsePostureSession()) {
                        PostureSessionScreen(
                            workout = workout,
                            index = route.sessionIndex,
                            total = workoutPlan.size,
                            nextWorkout = nextWorkout,
                            elapsedSeconds = sessionElapsedSeconds,
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

                // A running session always outranks the coach, which is a display-only utility.
                route.placementCoachOpen -> PlacementCoachScreen(
                    onExit = { placementCoachOpen = false },
                )

                else -> MainTabs(
                    selectedTab = selectedTab,
                    workoutPlan = workoutPlan,
                    workoutHistory = workoutHistory,
                    onWorkoutPlanChange = { workoutPlan = it },
                    onTabSelected = { selectedTab = it },
                    onStartWorkout = ::startSession,
                    onOpenPlacementCoach = { placementCoachOpen = true },
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
    val placementCoachOpen: Boolean,
)

@Composable
private fun MainTabs(
    selectedTab: TrexTab,
    workoutPlan: List<Workout>,
    workoutHistory: List<WorkoutHistoryDay>,
    onWorkoutPlanChange: (List<Workout>) -> Unit,
    onTabSelected: (TrexTab) -> Unit,
    onStartWorkout: () -> Unit,
    onOpenPlacementCoach: () -> Unit,
) {
    var tabOverlayVisible by rememberSaveable { mutableStateOf(false) }
    var workoutNavigation by rememberSaveable { mutableStateOf(WorkoutNavigationTab.Schedule) }
    var dietRecordRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var dietRecordLaunchAction by rememberSaveable { mutableStateOf(DietRecordLaunchAction.Camera) }
    var dietRecentFoodNames by remember { mutableStateOf<List<String>>(emptyList()) }

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
                TrexTab.Home -> HomeScreen()
                TrexTab.Workout -> when (workoutNavigation) {
                    WorkoutNavigationTab.Schedule -> WorkoutListScreen(
                        plan = workoutPlan,
                        onPlanChange = onWorkoutPlanChange,
                        onSheetVisibleChange = { tabOverlayVisible = it },
                        onOpenPlacementCoach = onOpenPlacementCoach,
                    )
                    WorkoutNavigationTab.History -> WorkoutHistoryScreen(records = workoutHistory)
                }
                TrexTab.Diet -> DietScreen(
                    recordRequestToken = dietRecordRequestToken,
                    recordLaunchAction = dietRecordLaunchAction,
                    onSheetVisibleChange = { tabOverlayVisible = it },
                    onRecentFoodsChange = { dietRecentFoodNames = it },
                )
                TrexTab.Profile -> ProfileScreen()
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

