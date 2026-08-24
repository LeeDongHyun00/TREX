package com.example.trex_kotlin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.FreeBreakfast
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LunchDining
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(app: AppViewModel) {
    val today = LocalDate.now()
    val plan = app.workoutPlan
    val todayRecord = app.todayRecord
    val doneNames = todayRecord?.items?.map { it.workoutName }?.toSet().orEmpty()
    val doneCount = plan.count { it.name in doneNames }
    val weekDays = (6 downTo 0).map { back -> today.minusDays(back.toLong()) }
    val activeDays = app.workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()
    val attendedCount = weekDays.count { it.toEpochDay() in activeDays }
    val meal = currentMealInfo()
    val mealLogged = app.dietFor(0)[meal.id].orEmpty().isNotEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenTitle("${today.year}년 ${today.monthValue}월 ${today.dayOfMonth}일", color = Color.White)
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = TrexLime,
                contentColor = TrexDark,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "이번 주 출석",
                            color = TrexDark.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Pill("$attendedCount/7일", background = TrexDark, color = TrexLime)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        weekDays.forEach { date ->
                            val attended = date.toEpochDay() in activeDays
                            val isToday = date == today
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN),
                                    color = TrexDark.copy(alpha = 0.58f),
                                    fontSize = 10.sp,
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                attended -> TrexDark
                                                isToday -> Color.White
                                                else -> TrexDark.copy(alpha = 0.1f)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        color = if (attended) TrexLime else TrexDark.copy(alpha = 0.78f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                contentColor = TrexDark,
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionLabel("오늘 운동 리스트", color = TrexTextSecondary)
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(doneCount.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(" / ${plan.size} 완료", color = TrexTextSecondary, fontSize = 14.sp)
                    }
                    TrackProgress(
                        progress = if (plan.isEmpty()) 0f else doneCount / plan.size.toFloat(),
                        modifier = Modifier.padding(top = 13.dp),
                        track = TrexBackground,
                        fill = TrexGreen,
                    )
                    Column(
                        modifier = Modifier.padding(top = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        plan.take(4).forEach { workout ->
                            TodayWorkoutRow(
                                workout = workout,
                                done = workout.name in doneNames,
                            )
                        }
                        if (plan.size > 4) {
                            Text(
                                text = "외 ${plan.size - 4}개 — 운동 탭에서 전체 보기",
                                color = TrexTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = TrexLime,
                contentColor = TrexDark,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(icon = mealIcon(meal.id), active = true)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(meal.timeHint, color = TrexDark.copy(alpha = 0.68f), fontSize = 11.sp)
                        Text(
                            text = if (mealLogged) "${meal.label} 기록 완료!" else "${meal.label} 시간이에요",
                            color = TrexDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "오늘 소모",
                    value = (todayRecord?.totalCalories() ?: 0).toString(),
                    suffix = "kcal",
                    icon = Icons.Rounded.LocalFireDepartment,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "연속 출석",
                    value = app.attendanceStreak().toString(),
                    suffix = "일",
                    modifier = Modifier.weight(1f),
                    dark = true,
                )
            }
        }
    }
}

@Composable
fun WorkoutListScreen(
    plan: List<Workout>,
    onPlanChange: (List<Workout>) -> Unit,
    onSheetVisibleChange: (Boolean) -> Unit = {},
) {
    var editTarget by remember { mutableStateOf<Workout?>(null) }
    var replaceTarget by remember { mutableStateOf<Workout?>(null) }
    var addingWorkout by remember { mutableStateOf(false) }
    var draggingWorkoutId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val reorderDistancePx = with(LocalDensity.current) { 92.dp.toPx() }
    val heavyRain = true

    LaunchedEffect(editTarget != null, replaceTarget != null, addingWorkout) {
        onSheetVisibleChange(editTarget != null || replaceTarget != null || addingWorkout)
    }
    DisposableEffect(Unit) {
        onDispose { onSheetVisibleChange(false) }
    }

    fun togglePosture(id: String) {
        onPlanChange(plan.map { workout ->
            if (workout.id == id) workout.copy(posture = !workout.posture) else workout
        })
    }

    fun clearDrag() {
        draggingWorkoutId = null
        dragOffsetY = 0f
    }

    fun finishDrag() {
        val draggedId = draggingWorkoutId ?: return
        val fromIndex = plan.indexOfFirst { it.id == draggedId }
        if (fromIndex == -1) {
            clearDrag()
            return
        }
        val steps = (dragOffsetY / reorderDistancePx).roundToInt()
        val toIndex = (fromIndex + steps).coerceIn(plan.indices)
        if (toIndex != fromIndex) {
            onPlanChange(plan.moved(fromIndex, toIndex))
        }
        clearDrag()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(TrexDark),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 174.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ScreenTitle("총 ${plan.size}개 · 약 ${plan.sumOf { it.durationMinutes() }}분")
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = if (heavyRain) TrexLime else Color.White.copy(alpha = 0.05f),
                    contentColor = if (heavyRain) TrexDark else Color.White,
                    border = if (heavyRain) null else dimBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBubble(icon = Icons.Rounded.Cloud, active = heavyRain)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text("현재 예상 강수량", fontSize = 11.sp, color = if (heavyRain) TrexDark.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.68f))
                            Text("6.4 mm/h", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Pill("실내 권장", background = TrexDark, color = TrexLime)
                    }
                }
            }

            item {
                TrexButton(
                    text = "운동 추가",
                    onClick = { addingWorkout = true },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Add,
                    container = TrexLime,
                    contentColor = TrexDark,
                )
            }

            items(plan, key = { it.id }) { workout ->
                val isDragging = draggingWorkoutId == workout.id
                WorkoutRow(
                    workout = workout,
                    index = plan.indexOf(workout) + 1,
                    dragging = isDragging,
                    onEdit = { editTarget = workout },
                    onReplace = { replaceTarget = workout },
                    onPostureToggle = { togglePosture(workout.id) },
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = if (isDragging) dragOffsetY.roundToInt() else 0,
                            )
                        }
                        .pointerInput(workout.id, plan) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingWorkoutId = workout.id
                                    dragOffsetY = 0f
                                },
                                onDrag = { _, dragAmount ->
                                    if (draggingWorkoutId == workout.id) {
                                        dragOffsetY += dragAmount.y
                                    }
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { clearDrag() },
                            )
                        },
                )
            }
        }

        editTarget?.let { workout ->
            WorkoutPlanEditSheet(
                workout = workout,
                modeAdd = false,
                onSave = { name, count, sets ->
                    onPlanChange(plan.map {
                        if (it.id == workout.id) {
                            it.copy(name = name, reps = formatReps(count, sets))
                        } else {
                            it
                        }
                    })
                },
                onClose = { editTarget = null },
            )
        }

        replaceTarget?.let { workout ->
            AltSuggestSheet(
                workout = workout,
                onApply = { alt ->
                    onPlanChange(plan.map {
                        if (it.id == workout.id) {
                            it.copy(name = alt.name, reps = alt.reps)
                        } else {
                            it
                        }
                    })
                },
                onClose = { replaceTarget = null },
            )
        }

        if (addingWorkout) {
            WorkoutCatalogSheet(
                onAdd = { template ->
                    onPlanChange(
                        plan + template.toWorkout(),
                    )
                    addingWorkout = false
                },
                onClose = { addingWorkout = false },
            )
        }
    }
}

@Composable
fun WorkoutHistoryScreen(
    records: List<WorkoutHistoryDay>,
) {
    val weeklyMinutes = records.sumOf { it.totalMinutes() }
    val weeklyCalories = records.sumOf { it.totalCalories() }
    val completedDays = records.count { it.items.isNotEmpty() }
    val orderedRecords = records.asReversed()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .padding(top = 42.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            SectionLabel("최근 7일", color = TrexLime)
            ScreenTitle("운동 기록")
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "운동한 날",
                    value = completedDays.toString(),
                    suffix = "일",
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Check,
                )
                StatCard(
                    label = "총 소모 칼로리",
                    value = weeklyCalories.toString(),
                    suffix = "kcal",
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.LocalFireDepartment,
                    dark = true,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = TrexLime,
                contentColor = TrexDark,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(icon = Icons.Rounded.FitnessCenter, active = true)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text("이번 주 누적 칼로리", color = TrexDark.copy(alpha = 0.68f), fontSize = 11.sp)
                        Text(
                            text = "${weeklyCalories}kcal 소모 · ${weeklyMinutes}분 운동",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val cardWidth = maxWidth - 64.dp
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(orderedRecords, key = { "${it.dateLabel}-${it.dayLabel}" }) { record ->
                    WorkoutHistoryDayCard(
                        record = record,
                        modifier = Modifier
                            .width(cardWidth)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
fun DietScreen(
    app: AppViewModel,
    recordRequestToken: Int = 0,
    recordLaunchAction: DietRecordLaunchAction = DietRecordLaunchAction.Camera,
    onSheetVisibleChange: (Boolean) -> Unit = {},
) {
    var editing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var recordVisible by remember { mutableStateOf(false) }
    var activeRecordLaunchAction by remember { mutableStateOf(recordLaunchAction) }
    var dateOffset by rememberSaveable { mutableIntStateOf(0) }
    var toastVisible by remember { mutableStateOf(false) }
    /** 방금 기록한 (슬롯, 추가 개수) — 실행 취소용. */
    var pendingUndo by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var handledRecordRequestToken by rememberSaveable { mutableIntStateOf(recordRequestToken) }
    val foodsBySlot = app.dietFor(dateOffset)
    val canEditSelectedDate = dateOffset in AppViewModel.DIET_MIN_OFFSET..AppViewModel.DIET_MAX_OFFSET
    val meals = remember(foodsBySlot) {
        mealMetas.map { meta ->
            val foods = foodsBySlot[meta.id].orEmpty()
            Triple(meta, foods, foods.totalNutrition())
        }
    }
    val total = remember(meals) { meals.flatMap { it.second }.totalNutrition() }
    val recentFoods = remember(app.dietByDay) { app.recentFoodsForCurrentMeal() }

    LaunchedEffect(recordRequestToken) {
        if (recordRequestToken > 0 && recordRequestToken != handledRecordRequestToken) {
            handledRecordRequestToken = recordRequestToken
            if (canEditSelectedDate) {
                activeRecordLaunchAction = recordLaunchAction
                recordVisible = true
            }
        }
    }

    LaunchedEffect(recordVisible, editing != null) {
        onSheetVisibleChange(recordVisible || editing != null)
    }
    DisposableEffect(Unit) {
        onDispose { onSheetVisibleChange(false) }
    }

    LaunchedEffect(toastVisible, pendingUndo) {
        if (toastVisible) {
            delay(3000)
            toastVisible = false
            pendingUndo = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        DietMainSummaryScreen(
            meals = meals,
            total = total,
            targetGoal = app.targetGoal,
            recommendedGoal = app.recommendedGoal,
            dateOffset = dateOffset,
            waterCups = app.waterFor(dateOffset),
            canGoPreviousDate = dateOffset > AppViewModel.DIET_MIN_OFFSET,
            canGoNextDate = dateOffset < AppViewModel.DIET_MAX_OFFSET,
            canEditSelectedDate = canEditSelectedDate,
            onDateOffset = { dateOffset = it.coerceIn(AppViewModel.DIET_MIN_OFFSET, AppViewModel.DIET_MAX_OFFSET) },
            onOpenRecord = {
                if (canEditSelectedDate) {
                    activeRecordLaunchAction = DietRecordLaunchAction.Manual
                    recordVisible = true
                }
            },
            onOpenMeal = { slot, addMode ->
                if (canEditSelectedDate) {
                    editing = slot to addMode
                }
            },
            onTargetGoalChange = { app.setTargetGoal(it) },
            onWater = {
                if (canEditSelectedDate) {
                    app.addWater(dateOffset)
                }
            },
        )

        if (recordVisible) {
            DietRecordRoute(
                targetGoal = app.targetGoal.kcal,
                recentFoods = recentFoods,
                launchAction = activeRecordLaunchAction,
                onRecord = { slot, foods ->
                    app.appendFoods(dateOffset, slot, foods)
                    pendingUndo = slot to foods.size
                    toastVisible = true
                    recordVisible = false
                },
                onClose = { recordVisible = false },
            )
        }

        editing?.let { (slot, addMode) ->
            ManualFoodLogSheet(
                modeAdd = addMode,
                initialSlot = slot,
                initialFoods = foodsBySlot[slot].orEmpty(),
                onSave = { saveSlot, foods ->
                    app.replaceSlot(dateOffset, saveSlot, foods)
                    editing = null
                },
                onDelete = {
                    app.clearSlot(dateOffset, slot)
                    editing = null
                },
                onClose = { editing = null },
            )
        }

        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        ) {
            DietUndoToast(
                onUndo = {
                    pendingUndo?.let { (slot, count) ->
                        app.undoAppend(dateOffset, slot, count)
                    }
                    pendingUndo = null
                    toastVisible = false
                },
            )
        }
    }
}

@Composable
fun ProfileScreen(
    profile: UserProfile,
    onLogout: () -> Unit,
) {
    val groups = listOf(
        listOf(
            ProfileRowData("프로필 편집", Icons.Rounded.Person),
            ProfileRowData("계정 관리", Icons.Rounded.Person, "이메일 · 비밀번호 · 연결된 계정"),
        ),
        listOf(
            ProfileRowData("알림", Icons.Rounded.Notifications, "운동 · 식단 리마인더"),
            ProfileRowData("언어", Icons.Rounded.Language, "한국어"),
            ProfileRowData("개인정보 보호", Icons.Rounded.Security),
        ),
        listOf(
            ProfileRowData("도움말", Icons.AutoMirrored.Rounded.Help),
            ProfileRowData("약관 및 정책", Icons.Rounded.Description),
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionLabel("내 정보")
            ScreenTitle("설정")
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.06f),
                border = dimBorder(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(TrexLime),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = TrexDark, modifier = Modifier.size(30.dp))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text("사용자", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${profileGoalLabel(profile.goal)} · ${profile.heightCm.toInt()}cm · ${profile.weightKg.toInt()}kg · ${profile.age}세",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconCircleButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        onClick = {},
                        size = 34.dp,
                        background = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White.copy(alpha = 0.62f),
                        contentDescription = "프로필",
                    )
                }
            }
        }

        items(groups) { rows ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.04f),
                border = dimBorder(),
            ) {
                Column {
                    rows.forEachIndexed { index, row ->
                        ProfileRow(row)
                        if (index != rows.lastIndex) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color.White.copy(alpha = 0.06f)),
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = TrexError.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, TrexError.copy(alpha = 0.3f)),
                contentColor = Color(0xFFFF8585),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text("로그아웃", fontSize = 14.sp, modifier = Modifier.padding(start = 7.dp))
                }
            }
            Text(
                text = "TREX v1.0.0",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WorkoutHistoryDayCard(
    record: WorkoutHistoryDay,
    modifier: Modifier = Modifier,
) {
    val hasPostureCorrection = record.items.any { it.postureCorrection != null }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        contentColor = TrexDark,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${record.dateLabel} (${record.dayLabel})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${record.totalMinutes()}분 · ${record.totalCalories()}kcal",
                        color = TrexTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(top = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                record.items.forEach { item ->
                    WorkoutHistoryItemRow(item = item)
                }
            }

            Surface(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = if (hasPostureCorrection) TrexLime.copy(alpha = 0.72f) else TrexBackground,
                contentColor = TrexDark,
            ) {
                Row(
                    modifier = Modifier.padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (hasPostureCorrection) Icons.Rounded.Visibility else Icons.Rounded.LocalFireDepartment,
                        contentDescription = null,
                        tint = TrexDark,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = record.summaryText(),
                        color = TrexDark,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutHistoryItemRow(item: WorkoutHistoryItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = TrexBackground,
        contentColor = TrexDark,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (item.postureCorrection != null) TrexLime else Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (item.postureCorrection != null) Icons.Rounded.Visibility else Icons.Rounded.FitnessCenter,
                    contentDescription = null,
                    tint = TrexGreenDeep,
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Text(
                    text = item.workoutName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.reps,
                    color = TrexTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${item.durationMinutes}분", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${item.calories}kcal", color = TrexTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TodayWorkoutRow(workout: Workout, done: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = TrexBackground,
        contentColor = TrexDark,
    ) {
        Row(
            modifier = Modifier.padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(icon = if (done) Icons.Rounded.Check else Icons.Rounded.FitnessCenter, active = done)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Text(
                    text = workout.name,
                    fontSize = 13.sp,
                    color = if (done) TrexTextSecondary else TrexDark,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(workout.reps, color = TrexTextSecondary, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccessTime, contentDescription = null, tint = TrexDark, modifier = Modifier.size(12.dp))
                    Text(workout.duration, color = TrexDark, fontSize = 12.sp, modifier = Modifier.padding(start = 3.dp))
                }
                Text(if (done) "완료" else workout.category, color = TrexTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun WorkoutRow(
    workout: Workout,
    index: Int,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    onEdit: () -> Unit,
    onReplace: () -> Unit,
    onPostureToggle: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (dragging) Color(0xFFF8FFE4) else Color.White,
        contentColor = TrexDark,
        border = if (dragging) BorderStroke(2.dp, TrexLime) else null,
        shadowElevation = if (dragging) 10.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(index.toString().padStart(2, '0'), background = TrexDark, color = TrexLime)
                    Pill(workout.category, background = TrexBackground, color = TrexGreenDeep)
                    if (workout.posture) {
                        Pill("자세교정", background = TrexLime, color = TrexDark, icon = Icons.Rounded.Visibility)
                    }
                }
                Text(workout.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp))
                Text("${workout.reps} · ${workout.duration}", color = TrexTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SmallSquareButton(icon = Icons.Rounded.Edit, onClick = onEdit, contentDescription = "수정")
                SmallSquareButton(icon = Icons.Rounded.Refresh, onClick = onReplace, contentDescription = "교체")
                SmallSquareButton(
                    icon = Icons.Rounded.Visibility,
                    onClick = onPostureToggle,
                    active = workout.posture,
                    contentDescription = "자세 교정",
                )
            }
        }
    }
}

private data class WorkoutCategoryOption(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val workouts: List<WorkoutTemplate>,
)

private data class WorkoutTemplate(
    val name: String,
    val reps: String,
    val duration: String,
    val category: String,
    val posture: Boolean,
)

@Composable
private fun WorkoutCatalogSheet(
    onAdd: (WorkoutTemplate) -> Unit,
    onClose: () -> Unit,
) {
    val categories = remember { workoutCatalog() }
    var selectedCategory by remember { mutableStateOf<WorkoutCategoryOption?>(null) }

    SheetSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("운동 추가", color = TrexLime)
                    ScreenTitle("카테고리에서 선택")
                }
                CloseButton(onClick = onClose)
            }
            Text(
                text = "카테고리를 먼저 고르면 추가 가능한 운동을 보여드려요.",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { category ->
                            WorkoutCategoryCard(
                                category = category,
                                selected = selectedCategory?.label == category.label,
                                onClick = { selectedCategory = category },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Text(
                text = selectedCategory?.let { "${it.label} 운동" } ?: "카테고리를 선택해주세요",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                val templates = selectedCategory?.workouts.orEmpty()
                items(templates, key = { it.name }) { template ->
                    WorkoutTemplateCard(
                        template = template,
                        onClick = { onAdd(template) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutCategoryCard(
    category: WorkoutCategoryOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) TrexLime else Color.White.copy(alpha = 0.08f),
        contentColor = if (selected) TrexDark else Color.White,
        border = if (selected) null else dimBorder(0.12f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (selected) TrexDark else TrexLime.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(category.icon, contentDescription = null, tint = if (selected) TrexLime else TrexLime, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(category.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    category.description,
                    color = if (selected) TrexDark.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.48f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkoutTemplateCard(
    template: WorkoutTemplate,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        contentColor = TrexDark,
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(TrexBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = TrexGreenDeep, modifier = Modifier.size(18.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(template.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${template.reps} · ${template.duration}", color = TrexTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Pill("추가", background = TrexLime, color = TrexDark, icon = Icons.Rounded.Add)
        }
    }
}

@Composable
private fun WorkoutPlanEditSheet(
    workout: Workout?,
    modeAdd: Boolean,
    onSave: (name: String, count: Int, sets: Int) -> Unit,
    onClose: () -> Unit,
) {
    val initialDraft = remember(workout?.id, modeAdd) { workout?.repsSpec() ?: RepsSpec(count = 12, sets = 3, targetLabel = "12회") }
    var name by remember(workout?.id, modeAdd) { mutableStateOf(workout?.name.orEmpty()) }
    var countInput by remember(workout?.id, modeAdd) { mutableStateOf(initialDraft.count.toString()) }
    var setsInput by remember(workout?.id, modeAdd) { mutableStateOf(initialDraft.sets.toString()) }
    val cleanName = name.trim()
    val count = countInput.toIntOrNull()
    val sets = setsInput.toIntOrNull()
    val canSave = cleanName.isNotEmpty() &&
        count != null &&
        count in 1..999 &&
        sets != null &&
        sets in 1..99

    fun updateCount(delta: Int) {
        val current = countInput.toIntOrNull() ?: initialDraft.count
        countInput = (current + delta).coerceIn(1, 999).toString()
    }

    fun updateSets(delta: Int) {
        val current = setsInput.toIntOrNull() ?: initialDraft.sets
        setsInput = (current + delta).coerceIn(1, 99).toString()
    }

    SheetSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(if (modeAdd) "운동 추가" else "운동 수정", color = TrexLime)
                    ScreenTitle(if (modeAdd) "새 운동 만들기" else workout?.name.orEmpty())
                }
                CloseButton(onClick = onClose)
            }
            Text(
                text = if (modeAdd) {
                    "운동명과 목표 횟수, 세트 수를 입력해주세요."
                } else {
                    "${workout?.category.orEmpty()} · 현재 ${workout?.reps.orEmpty()}"
                },
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            if (modeAdd) {
                TrexTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "운동 이름",
                    leadingIcon = Icons.Rounded.FitnessCenter,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkoutCounterField(
                    label = "횟수",
                    value = countInput,
                    onValueChange = { countInput = it.digitsOnly().take(3) },
                    onMinus = { updateCount(-1) },
                    onPlus = { updateCount(1) },
                    modifier = Modifier.weight(1f),
                )
                WorkoutCounterField(
                    label = "세트",
                    value = setsInput,
                    onValueChange = { setsInput = it.digitsOnly().take(2) },
                    onMinus = { updateSets(-1) },
                    onPlus = { updateSets(1) },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "저장 후 ${formatReps(count ?: initialDraft.count, sets ?: initialDraft.sets)}로 표시됩니다.",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrexButton(
                    text = if (modeAdd) "운동 추가" else "수정 완료",
                    onClick = {
                        val savedCount = countInput.toIntOrNull()?.coerceIn(1, 999) ?: return@TrexButton
                        val savedSets = setsInput.toIntOrNull()?.coerceIn(1, 99) ?: return@TrexButton
                        onSave(cleanName, savedCount, savedSets)
                        onClose()
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    icon = if (modeAdd) Icons.Rounded.Add else Icons.Rounded.Check,
                )
                IconCircleButton(
                    icon = Icons.Rounded.Close,
                    onClick = onClose,
                    size = 52.dp,
                    background = TrexError.copy(alpha = 0.12f),
                    contentColor = Color(0xFFFF8A8A),
                    contentDescription = "닫기",
                )
            }
        }
    }
}

@Composable
private fun WorkoutCounterField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CounterIconButton(icon = Icons.Rounded.Remove, onClick = onMinus, contentDescription = "$label 줄이기")
            TrexTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = "0",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                textColor = Color.White,
            )
            CounterIconButton(icon = Icons.Rounded.Add, onClick = onPlus, contentDescription = "$label 늘리기")
        }
    }
}

@Composable
private fun CounterIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.08f),
        contentColor = Color.White,
        border = dimBorder(0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(17.dp))
        }
    }
}

private fun WorkoutTemplate.toWorkout(): Workout =
    Workout(
        id = "custom-${System.currentTimeMillis()}-${name.hashCode()}",
        name = name,
        reps = reps,
        duration = duration,
        posture = posture,
        category = category,
    )

private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

private fun workoutCatalog(): List<WorkoutCategoryOption> = listOf(
    WorkoutCategoryOption(
        label = "상체",
        description = "팔 · 가슴 · 등",
        icon = Icons.Rounded.FitnessCenter,
        workouts = listOf(
            WorkoutTemplate("니 푸쉬업", "10회 x 3세트", "7분", "상체", posture = true),
            WorkoutTemplate("벽 푸쉬업", "12회 x 3세트", "6분", "상체", posture = false),
            WorkoutTemplate("밴드 로우", "12회 x 3세트", "8분", "상체", posture = false),
            WorkoutTemplate("숄더 탭", "16회 x 3세트", "7분", "상체", posture = true),
        ),
    ),
    WorkoutCategoryOption(
        label = "복근",
        description = "코어 안정화",
        icon = Icons.Rounded.Person,
        workouts = listOf(
            WorkoutTemplate("플랭크", "45초 x 3세트", "6분", "복근", posture = false),
            WorkoutTemplate("데드버그", "12회 x 3세트", "7분", "복근", posture = true),
            WorkoutTemplate("크런치", "15회 x 3세트", "6분", "복근", posture = false),
            WorkoutTemplate("버드독", "10회 x 3세트", "7분", "복근", posture = true),
        ),
    ),
    WorkoutCategoryOption(
        label = "하체",
        description = "스쿼트 · 런지",
        icon = Icons.Rounded.AccessTime,
        workouts = listOf(
            WorkoutTemplate("기본 스쿼트", "12회 x 3세트", "8분", "하체", posture = true),
            WorkoutTemplate("런지", "10회 x 3세트", "10분", "하체", posture = true),
            WorkoutTemplate("글루트 브릿지", "12회 x 3세트", "7분", "하체", posture = false),
            WorkoutTemplate("카프 레이즈", "15회 x 3세트", "6분", "하체", posture = false),
        ),
    ),
    WorkoutCategoryOption(
        label = "유산소",
        description = "심박수 올리기",
        icon = Icons.Rounded.LocalFireDepartment,
        workouts = listOf(
            WorkoutTemplate("제자리 걷기", "60초 x 4세트", "8분", "유산소", posture = false),
            WorkoutTemplate("마운틴 클라이머", "20회 x 3세트", "8분", "유산소", posture = true),
            WorkoutTemplate("점핑잭", "30회 x 3세트", "7분", "유산소", posture = false),
            WorkoutTemplate("스텝업", "12회 x 3세트", "9분", "유산소", posture = true),
        ),
    ),
)

@Composable
private fun ProfileRow(row: ProfileRowData) {
    Surface(
        onClick = {},
        color = Color.Transparent,
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(row.icon, contentDescription = null, tint = TrexLime, modifier = Modifier.size(16.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(row.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.sub != null) {
                    Text(row.sub, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dark: Boolean = false,
) {
    Surface(
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (dark) Color.White.copy(alpha = 0.05f) else Color.White,
        contentColor = if (dark) Color.White else TrexDark,
        border = if (dark) dimBorder() else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = if (dark) Color.White.copy(alpha = 0.6f) else TrexTextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = TrexWarning, modifier = Modifier.size(17.dp))
                }
            }
            Row(
                modifier = Modifier.padding(top = 15.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(suffix, color = if (dark) Color.White.copy(alpha = 0.6f) else TrexTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun IconBubble(icon: ImageVector, active: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) TrexDark else Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) TrexLime else TrexGreenDeep, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SmallSquareButton(
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    contentDescription: String,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (active) TrexLime else TrexBackground,
        contentColor = if (active) TrexDark else TrexGreenDeep,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun TrackProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    track: Color,
    fill: Color,
) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(999.dp)),
        color = fill,
        trackColor = track,
    )
}

private data class ProfileRowData(
    val label: String,
    val icon: ImageVector,
    val sub: String? = null,
)

private fun profileGoalLabel(goal: String): String = when (goal) {
    "muscle" -> "근육 증가"
    "diet" -> "다이어트"
    "stamina" -> "체력 향상"
    "maintain" -> "유지"
    else -> "일반 루틴"
}

private fun mealIcon(mealId: String): ImageVector = when (mealId) {
    "breakfast" -> Icons.Rounded.FreeBreakfast
    "lunch" -> Icons.Rounded.LunchDining
    "snack" -> Icons.Rounded.BakeryDining
    "dinner" -> Icons.Rounded.DinnerDining
    else -> Icons.Rounded.Restaurant
}

