package com.example.trex_kotlin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.release.PostureCorrectionLifecycle
import com.example.trex_kotlin.pose.release.PostureCorrectionRuntimeFacade
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun HomeScreen() {
    val homeWorkouts = todayPlan.take(4)
    val doneCount = homeWorkouts.count { it.posture }
    val days = listOf("월", "화", "수", "목", "금", "토", "일")
    val dates = listOf(20, 21, 22, 23, 24, 25, 26)
    val completed = listOf(true, true, false, true, true, false, false)
    val meal = currentMeal()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenTitle("2026년 4월 XX일", color = Color.White)
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
                        Pill("4/7일", background = TrexDark, color = TrexLime)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        days.forEachIndexed { index, day ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(day, color = TrexDark.copy(alpha = 0.58f), fontSize = 10.sp)
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                completed[index] -> TrexDark
                                                index == 5 -> Color.White
                                                else -> TrexDark.copy(alpha = 0.1f)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = dates[index].toString(),
                                        color = if (completed[index]) TrexLime else TrexDark.copy(alpha = 0.78f),
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
                        Text(" / ${homeWorkouts.size} 완료", color = TrexTextSecondary, fontSize = 14.sp)
                    }
                    TrackProgress(
                        progress = doneCount / homeWorkouts.size.toFloat(),
                        modifier = Modifier.padding(top = 13.dp),
                        track = TrexBackground,
                        fill = TrexGreen,
                    )
                    Column(
                        modifier = Modifier.padding(top = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        homeWorkouts.forEachIndexed { index, workout ->
                            TodayWorkoutRow(
                                workout = workout,
                                done = index == 0 || index == 3,
                                time = if (index < 2) "08:${index * 15}".padEnd(5, '0') else "19:${(index - 2) * 15}".padEnd(5, '0'),
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
                    IconBubble(icon = meal.icon, active = true)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(meal.timeHint, color = TrexDark.copy(alpha = 0.68f), fontSize = 11.sp)
                        Text(
                            text = "${meal.label} 시간이에요",
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
                    value = "248",
                    suffix = "kcal",
                    icon = Icons.Rounded.LocalFireDepartment,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "연속 출석",
                    value = "5",
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
            if (workout.id == id) {
                workout.withPostureCorrection(!workout.posture)
            } else {
                workout
            }
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
                ScreenTitle("총 ${plan.size}개 · 약 35분")
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
                onSave = { count, sets ->
                    onPlanChange(plan.map {
                        if (it.id == workout.id) {
                            it.copy(reps = formatWorkoutReps(count, sets))
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
                            it.copy(
                                exercise = alt.exercise,
                                reps = alt.reps,
                                posture = false,
                                alt = null,
                            )
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
    recordRequestToken: Int = 0,
    recordLaunchAction: DietRecordLaunchAction = DietRecordLaunchAction.Camera,
    onSheetVisibleChange: (Boolean) -> Unit = {},
    onRecentFoodsChange: (List<String>) -> Unit = {},
) {
    var foodsByDate by remember { mutableStateOf(mapOf(0 to seedFoods())) }
    var editing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var recordVisible by remember { mutableStateOf(false) }
    var activeRecordLaunchAction by remember { mutableStateOf(recordLaunchAction) }
    var dateOffset by remember { mutableIntStateOf(0) }
    var waterCups by remember { mutableIntStateOf(4) }
    var toastVisible by remember { mutableStateOf(false) }
    var pendingUndo by remember { mutableStateOf<Pair<String, List<FoodEntry>>?>(null) }
    var handledRecordRequestToken by remember { mutableIntStateOf(recordRequestToken) }
    val recommendedTargetGoal = remember { recommendedNutritionGoal(heightCm = 170, weightKg = 65, activityFactor = 1.35) }
    var targetGoal by remember { mutableStateOf(recommendedTargetGoal) }
    val foodsBySlot = foodsByDate[dateOffset] ?: emptyDietSlots()
    val canEditSelectedDate = dateOffset in DietDateMinOffset..DietDateMaxOffset
    val meals by remember(foodsBySlot) {
        derivedStateOf {
            mealMetas.map { meta ->
                val foods = foodsBySlot[meta.id].orEmpty()
                Triple(meta, foods, foods.totalNutrition())
            }
        }
    }
    val total = remember(meals) { meals.flatMap { it.second }.totalNutrition() }
    val recentMealId = remember { currentMealId() }
    val recentFoods = remember(foodsByDate, recentMealId) {
        foodsByDate.recentFoodsForMeal(recentMealId)
    }

    LaunchedEffect(recordRequestToken) {
        if (recordRequestToken > 0 && recordRequestToken != handledRecordRequestToken) {
            handledRecordRequestToken = recordRequestToken
            if (canEditSelectedDate) {
                activeRecordLaunchAction = recordLaunchAction
                recordVisible = true
            }
        }
    }

    LaunchedEffect(recentFoods) {
        onRecentFoodsChange(recentFoods.map { it.name })
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
            targetGoal = targetGoal,
            recommendedGoal = recommendedTargetGoal,
            dateOffset = dateOffset,
            waterCups = waterCups,
            canGoPreviousDate = dateOffset > DietDateMinOffset,
            canGoNextDate = dateOffset < DietDateMaxOffset,
            canEditSelectedDate = canEditSelectedDate,
            onDateOffset = { dateOffset = it.coerceIn(DietDateMinOffset, DietDateMaxOffset) },
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
            onTargetGoalChange = { targetGoal = it },
            onWater = {
                if (canEditSelectedDate) {
                    waterCups += 1
                }
            },
        )

        if (recordVisible) {
            DietRecordRoute(
                targetGoal = targetGoal.kcal,
                recentFoods = recentFoods,
                launchAction = activeRecordLaunchAction,
                onRecord = { slot, foods ->
                    val current = foodsByDate[dateOffset] ?: emptyDietSlots()
                    val updated = current.toMutableMap().apply {
                        this[slot] = this[slot].orEmpty() + foods
                    }
                    foodsByDate = (foodsByDate + (dateOffset to updated)).filterDietHistory()
                    pendingUndo = slot to foods
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
                    val current = foodsByDate[dateOffset] ?: emptyDietSlots()
                    val updated = current.toMutableMap().apply {
                        this[saveSlot] = foods
                    }
                    foodsByDate = (foodsByDate + (dateOffset to updated)).filterDietHistory()
                    editing = null
                },
                onDelete = {
                    val current = foodsByDate[dateOffset] ?: emptyDietSlots()
                    val updated = current.toMutableMap().apply {
                        this[slot] = emptyList()
                    }
                    foodsByDate = (foodsByDate + (dateOffset to updated)).filterDietHistory()
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
                    pendingUndo?.let { (slot, foods) ->
                        val currentSlotFoods = foodsByDate[dateOffset] ?: emptyDietSlots()
                        val updated = currentSlotFoods.toMutableMap().apply {
                            val current = this[slot].orEmpty().toMutableList()
                            repeat(foods.size) {
                                if (current.isNotEmpty()) current.removeAt(current.lastIndex)
                            }
                            this[slot] = current
                        }
                        foodsByDate = (foodsByDate + (dateOffset to updated)).filterDietHistory()
                    }
                    pendingUndo = null
                    toastVisible = false
                },
            )
        }
    }
}

private const val DietDateMinOffset = -7
private const val DietDateMaxOffset = 0

private fun emptyDietSlots(): Map<String, List<FoodEntry>> =
    mealMetas.associate { it.id to emptyList() }

private fun Map<Int, Map<String, List<FoodEntry>>>.recentFoodsForMeal(mealId: String): List<FoodEntry> =
    entries
        .filter { it.key in DietDateMinOffset..DietDateMaxOffset }
        .sortedByDescending { it.key }
        .firstNotNullOfOrNull { (_, slots) ->
            slots[mealId]?.takeIf { it.isNotEmpty() }
        }
        .orEmpty()

private fun Map<Int, Map<String, List<FoodEntry>>>.filterDietHistory(): Map<Int, Map<String, List<FoodEntry>>> =
    filterKeys { it in DietDateMinOffset..DietDateMaxOffset }

@Composable
fun ProfileScreen() {
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
                        Text("user@trex.app", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
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
                onClick = {},
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
private fun TodayWorkoutRow(workout: Workout, done: Boolean, time: String) {
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
            IconBubble(icon = Icons.Rounded.FitnessCenter, active = done)
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
                    Text(time, color = TrexDark, fontSize = 12.sp, modifier = Modifier.padding(start = 3.dp))
                }
                Text(workout.duration, color = TrexTextSecondary, fontSize = 10.sp)
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
    val postureAvailability = PostureCorrectionRuntimeFacade.availability(workout.exercise)
    val postureSupported = postureAvailability.sessionOpenAllowed
    val postureAvailabilityLabel = when (postureAvailability.lifecycle) {
        PostureCorrectionLifecycle.UNSUPPORTED -> "자세 교정 미지원"
        PostureCorrectionLifecycle.CATALOG_ONLY ->
            "자세 기준 검증 중 · ${postureAvailability.catalogCriterionCount}개"
        PostureCorrectionLifecycle.SHADOW -> "자세 평가 내부 검증 중"
        PostureCorrectionLifecycle.OPT_IN_BETA ->
            "자세 교정 베타 · ${postureAvailability.releasedCriterionCount}/${postureAvailability.catalogCriterionCount}"
        PostureCorrectionLifecycle.GA ->
            "자세 교정 사용 · ${postureAvailability.releasedCriterionCount}/${postureAvailability.catalogCriterionCount}"
    }
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
                PostureCorrectionCheck(
                    checked = workout.posture,
                    enabled = postureSupported,
                    label = postureAvailabilityLabel,
                    onClick = onPostureToggle,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SmallSquareButton(icon = Icons.Rounded.Edit, onClick = onEdit, contentDescription = "수정")
                SmallSquareButton(icon = Icons.Rounded.Refresh, onClick = onReplace, contentDescription = "교체")
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
    val exercise: AiHubExercise,
    val reps: String,
    val duration: String,
) {
    val name: String get() = exercise.displayName
    val category: String get() = exercise.typeInfoType
}

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

private data class WorkoutRepsDraft(
    val count: Int,
    val sets: Int,
)

@Composable
private fun WorkoutPlanEditSheet(
    workout: Workout,
    onSave: (count: Int, sets: Int) -> Unit,
    onClose: () -> Unit,
) {
    val initialDraft = remember(workout.id) { workout.repsDraft() }
    var countInput by remember(workout.id) { mutableStateOf(initialDraft.count.toString()) }
    var setsInput by remember(workout.id) { mutableStateOf(initialDraft.sets.toString()) }
    val count = countInput.toIntOrNull()
    val sets = setsInput.toIntOrNull()
    val canSave = count != null &&
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
                    SectionLabel("운동 수정", color = TrexLime)
                    ScreenTitle(workout.name)
                }
                CloseButton(onClick = onClose)
            }
            Text(
                text = "${workout.category} · 현재 ${workout.reps}",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkoutCounterField(
                    label = "횟수",
                    value = countInput,
                    onValueChange = { countInput = it.numericText().take(3) },
                    onMinus = { updateCount(-1) },
                    onPlus = { updateCount(1) },
                    modifier = Modifier.weight(1f),
                )
                WorkoutCounterField(
                    label = "세트",
                    value = setsInput,
                    onValueChange = { setsInput = it.numericText().take(2) },
                    onMinus = { updateSets(-1) },
                    onPlus = { updateSets(1) },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "저장 후 ${formatWorkoutReps(count ?: initialDraft.count, sets ?: initialDraft.sets)}로 표시됩니다.",
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
                    text = "수정 완료",
                    onClick = {
                        val savedCount = countInput.toIntOrNull()?.coerceIn(1, 999) ?: return@TrexButton
                        val savedSets = setsInput.toIntOrNull()?.coerceIn(1, 99) ?: return@TrexButton
                        onSave(savedCount, savedSets)
                        onClose()
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Check,
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

private fun Workout.repsDraft(): WorkoutRepsDraft {
    val numbers = Regex("\\d+").findAll(reps).map { it.value.toInt() }.toList()
    val count = numbers.firstOrNull()?.coerceAtLeast(1) ?: 12
    val sets = if (reps.contains("세트") && numbers.size >= 2) {
        numbers.last().coerceAtLeast(1)
    } else {
        1
    }
    return WorkoutRepsDraft(count = count, sets = sets)
}

private fun formatWorkoutReps(count: Int, sets: Int): String =
    "${count.coerceIn(1, 999)}회 x ${sets.coerceIn(1, 99)}세트"

private fun estimateWorkoutDuration(sets: Int): String =
    "${(sets.coerceIn(1, 99) * 3).coerceIn(3, 30)}분"

private fun WorkoutTemplate.toWorkout(): Workout =
    Workout(
        exercise = exercise,
        reps = reps,
        duration = duration,
        posture = false,
        instanceId = "${exercise.id}:${System.currentTimeMillis()}",
    )

private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

private fun workoutCatalog(): List<WorkoutCategoryOption> {
    val categoryOrder = AiHubExercise.entries.map(AiHubExercise::typeInfoType).distinct()
    val exercisesByCategory = AiHubExercise.entries.groupBy(AiHubExercise::typeInfoType)
    return categoryOrder.mapNotNull { category ->
        val exercises = exercisesByCategory[category].orEmpty()
        if (exercises.isEmpty()) return@mapNotNull null
        WorkoutCategoryOption(
            label = category,
            description = "AI Hub ${exercises.size}개 운동",
            icon = when (category) {
                "기구" -> Icons.Rounded.AccessTime
                "바벨/덤벨" -> Icons.Rounded.FitnessCenter
                else -> Icons.Rounded.Person
            },
            workouts = exercises
                .sortedBy(AiHubExercise::displayName)
                .map { exercise ->
                    WorkoutTemplate(
                        exercise = exercise,
                        reps = if (exercise == AiHubExercise.PLANK) "45초 x 3세트" else "12회 x 3세트",
                        duration = "8분",
                    )
                },
        )
    }
}

@Composable
private fun MealCard(
    meta: MealMeta,
    foods: List<FoodEntry>,
    nutrition: Nutrition,
    onEdit: () -> Unit,
) {
    val empty = foods.isEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (empty) Color.White.copy(alpha = 0.05f) else Color.White,
        contentColor = if (empty) Color.White else TrexDark,
        border = if (empty) dimBorder() else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon = mealIcon(meta.id), active = !empty)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(meta.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (empty) "기록 전" else "${nutrition.kcal} kcal",
                        color = if (empty) Color.White.copy(alpha = 0.6f) else TrexTextSecondary,
                        fontSize = 11.sp,
                    )
                }
                IconCircleButton(
                    icon = if (empty) Icons.Rounded.Add else Icons.Rounded.Edit,
                    onClick = onEdit,
                    size = 34.dp,
                    background = if (empty) TrexLime else TrexDark,
                    contentColor = if (empty) TrexDark else TrexLime,
                    contentDescription = if (empty) "기록 추가" else "기록 수정",
                )
            }
            if (foods.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    foods.take(3).forEach {
                        Pill(it.name, background = TrexBackground, color = TrexGreenDeep)
                    }
                    if (foods.size > 3) {
                        Pill("+${foods.size - 3}", background = TrexBackground, color = TrexGreenDeep)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCalorieInlineCard(
    expanded: Boolean,
    recommendedGoal: Int,
    manualInput: String,
    manualMode: Boolean,
    onToggle: () -> Unit,
    onAutoGoal: () -> Unit,
    onManualMode: () -> Unit,
    onManualInput: (String) -> Unit,
    onApplyManual: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon = Icons.Rounded.LocalFireDepartment, active = true)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text("목표 칼로리 수정", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "추천 목표 ${recommendedGoal} kcal",
                        color = Color.White.copy(alpha = 0.64f),
                        fontSize = 11.sp,
                    )
                }
                IconCircleButton(
                    icon = if (expanded) Icons.Rounded.Close else Icons.Rounded.Edit,
                    onClick = onToggle,
                    size = 36.dp,
                    background = if (expanded) TrexError.copy(alpha = 0.14f) else TrexLime,
                    contentColor = if (expanded) Color(0xFFFF8A8A) else TrexDark,
                    contentDescription = if (expanded) "닫기" else "수정",
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InlineStep(title = "목표 설정 방식") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrexButton(
                                text = "체형별 자동 목표",
                                onClick = onAutoGoal,
                                modifier = Modifier.weight(1f),
                                height = 46.dp,
                            )
                            TrexButton(
                                text = "수동 입력",
                                onClick = onManualMode,
                                modifier = Modifier.weight(1f),
                                container = if (manualMode) TrexLime else Color.White.copy(alpha = 0.1f),
                                contentColor = if (manualMode) TrexDark else Color.White,
                                height = 46.dp,
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = manualMode,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        InlineStep(title = "수동 목표 칼로리") {
                            TrexTextField(
                                value = manualInput,
                                onValueChange = onManualInput,
                                placeholder = "추천 목표 ${recommendedGoal} kcal",
                                keyboardType = KeyboardType.Number,
                                leadingIcon = Icons.Rounded.LocalFireDepartment,
                            )
                            TrexButton(
                                text = "목표 적용",
                                onClick = onApplyManual,
                                enabled = manualInput.toIntOrNull()?.let { it > 0 } == true,
                                icon = Icons.Rounded.Check,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .fillMaxWidth(),
                                height = 46.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManualFoodInlineCard(
    expanded: Boolean,
    selectedSlot: String?,
    stagedFoodEntries: List<FoodEntry>,
    draftFoodName: String,
    manualKcal: String,
    manualCarb: String,
    manualProtein: String,
    manualFat: String,
    askingAddMoreFood: Boolean,
    onToggle: () -> Unit,
    onSelectSlot: (String) -> Unit,
    onFoodNameChange: (String) -> Unit,
    onRecordFood: () -> Unit,
    onAddAnotherFood: () -> Unit,
    onManualKcalChange: (String) -> Unit,
    onManualCarbChange: (String) -> Unit,
    onManualProteinChange: (String) -> Unit,
    onManualFatChange: (String) -> Unit,
    onFinish: (String, List<FoodEntry>) -> Unit,
) {
    val cleanFoodName = draftFoodName.trim()
    val autoNutrition = foodDatabase[cleanFoodName]
    val stagedTotal = stagedFoodEntries.totalNutrition()
    val canRecordFood = selectedSlot != null && cleanFoodName.isNotEmpty()
    val foodNameFocusRequester = remember { FocusRequester() }
    val manualKcalFocusRequester = remember { FocusRequester() }
    val foodStepRequester = remember { BringIntoViewRequester() }
    val nutritionStepRequester = remember { BringIntoViewRequester() }
    val addMoreStepRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(expanded, selectedSlot, askingAddMoreFood, stagedFoodEntries.size) {
        if (expanded && selectedSlot != null && !askingAddMoreFood && draftFoodName.isBlank()) {
            delay(220)
            foodStepRequester.bringIntoView()
            foodNameFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(expanded, selectedSlot, cleanFoodName, autoNutrition, askingAddMoreFood) {
        if (expanded && selectedSlot != null && cleanFoodName.isNotEmpty() && !askingAddMoreFood) {
            delay(if (autoNutrition == null) 650 else 220)
            nutritionStepRequester.bringIntoView()
            if (autoNutrition == null) {
                manualKcalFocusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(expanded, askingAddMoreFood, stagedFoodEntries.size) {
        if (expanded && askingAddMoreFood && stagedFoodEntries.isNotEmpty()) {
            delay(220)
            addMoreStepRequester.bringIntoView()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon = Icons.Rounded.Edit, active = true)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text("수동 식단 기록 추가", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (expanded) "순서대로 입력하면 다음 항목이 열려요" else "음식만 입력하면 영양정보 자동 추천",
                        color = Color.White.copy(alpha = 0.64f),
                        fontSize = 11.sp,
                    )
                }
                IconCircleButton(
                    icon = if (expanded) Icons.Rounded.Close else Icons.Rounded.Add,
                    onClick = onToggle,
                    size = 36.dp,
                    background = if (expanded) TrexError.copy(alpha = 0.14f) else TrexLime,
                    contentColor = if (expanded) Color(0xFFFF8A8A) else TrexDark,
                    contentDescription = if (expanded) "닫기" else "추가",
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    InlineStep(title = "어떤 끼니인가요?") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            mealMetas.forEach { meta ->
                                val active = selectedSlot == meta.id
                                Surface(
                                    onClick = { onSelectSlot(meta.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (active) TrexLime else Color.White.copy(alpha = 0.08f),
                                    contentColor = if (active) TrexDark else Color.White.copy(alpha = 0.82f),
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(mealIcon(meta.id), contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(meta.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = selectedSlot != null && !askingAddMoreFood,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Box(Modifier.bringIntoViewRequester(foodStepRequester)) {
                            InlineStep(title = "어떤 음식인가요?") {
                                if (stagedFoodEntries.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.padding(bottom = 9.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        stagedFoodEntries.take(3).forEach {
                                            Pill(it.name, background = TrexLime.copy(alpha = 0.16f), color = TrexLime)
                                        }
                                        if (stagedFoodEntries.size > 3) {
                                            Pill("+${stagedFoodEntries.size - 3}", background = TrexLime.copy(alpha = 0.16f), color = TrexLime)
                                        }
                                    }
                                }
                                TrexTextField(
                                    value = draftFoodName,
                                    onValueChange = onFoodNameChange,
                                    placeholder = "예: 닭가슴살, 바나나",
                                    leadingIcon = Icons.Rounded.Restaurant,
                                    focusRequester = foodNameFocusRequester,
                                )
                                if (autoNutrition != null) {
                                    Text(
                                        text = "DB에서 영양정보를 찾았어요",
                                        color = TrexLime,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 7.dp),
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = selectedSlot != null && cleanFoodName.isNotEmpty() && !askingAddMoreFood,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Box(Modifier.bringIntoViewRequester(nutritionStepRequester)) {
                            InlineStep(title = "영양 정보를 확인해주세요") {
                                if (autoNutrition != null) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = TrexLime,
                                        contentColor = TrexDark,
                                    ) {
                                        Column(Modifier.padding(13.dp)) {
                                            Text(cleanFoodName, fontSize = 11.sp, color = TrexDark.copy(alpha = 0.7f))
                                            Text("${autoNutrition.kcal} kcal", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "탄수 ${autoNutrition.carb.toInt()}g · 단백질 ${autoNutrition.protein.toInt()}g · 지방 ${autoNutrition.fat.toInt()}g",
                                                fontSize = 11.sp,
                                                color = TrexDark.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "정보가 없는 음식입니다 직접 영양 정보를 입력해주세요",
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(bottom = 9.dp),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TrexTextField(
                                            value = manualKcal,
                                            onValueChange = onManualKcalChange,
                                            placeholder = "칼로리",
                                            keyboardType = KeyboardType.Number,
                                            modifier = Modifier.weight(1f),
                                            focusRequester = manualKcalFocusRequester,
                                        )
                                        TrexTextField(
                                            value = manualCarb,
                                            onValueChange = onManualCarbChange,
                                            placeholder = "탄수(g)",
                                            keyboardType = KeyboardType.Decimal,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        TrexTextField(
                                            value = manualProtein,
                                            onValueChange = onManualProteinChange,
                                            placeholder = "단백질(g)",
                                            keyboardType = KeyboardType.Decimal,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TrexTextField(
                                            value = manualFat,
                                            onValueChange = onManualFatChange,
                                            placeholder = "지방(g)",
                                            keyboardType = KeyboardType.Decimal,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }

                                TrexButton(
                                    text = "기록 추가",
                                    onClick = onRecordFood,
                                    enabled = canRecordFood,
                                    icon = Icons.Rounded.Check,
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .fillMaxWidth(),
                                    height = 46.dp,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = askingAddMoreFood && stagedFoodEntries.isNotEmpty(),
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Box(Modifier.bringIntoViewRequester(addMoreStepRequester)) {
                            InlineStep(title = "음식을 추가하시겠습니까?") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = dimBorder(),
                                ) {
                                    Column(Modifier.padding(13.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            stagedFoodEntries.take(3).forEach {
                                                Pill(it.name, background = TrexLime.copy(alpha = 0.16f), color = TrexLime)
                                            }
                                            if (stagedFoodEntries.size > 3) {
                                                Pill("+${stagedFoodEntries.size - 3}", background = TrexLime.copy(alpha = 0.16f), color = TrexLime)
                                            }
                                        }
                                        Text(
                                            "현재 ${stagedFoodEntries.size}개 기록 대기 · ${stagedTotal.kcal} kcal",
                                            color = Color.White.copy(alpha = 0.74f),
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TrexButton(
                                        text = "예",
                                        onClick = onAddAnotherFood,
                                        modifier = Modifier.weight(1f),
                                        height = 46.dp,
                                    )
                                    TrexButton(
                                        text = "아니오",
                                        onClick = { selectedSlot?.let { onFinish(it, stagedFoodEntries) } },
                                        modifier = Modifier.weight(1f),
                                        container = Color.White.copy(alpha = 0.1f),
                                        contentColor = Color.White,
                                        height = 46.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineStep(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(13.dp),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

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
private fun PostureCorrectionCheck(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        !enabled -> TrexBackground.copy(alpha = 0.55f)
        checked -> TrexLime.copy(alpha = 0.45f)
        else -> TrexBackground
    }
    val foregroundColor = if (enabled) TrexDark else TrexTextSecondary

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = foregroundColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (checked && enabled) TrexGreenDeep else TrexTextSecondary.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (checked && enabled) TrexGreenDeep else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (enabled) TrexGreenDeep else TrexTextSecondary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked && enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = label,
                color = foregroundColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
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

private data class MealTime(
    val label: String,
    val timeHint: String,
    val icon: ImageVector,
)

private data class ProfileRowData(
    val label: String,
    val icon: ImageVector,
    val sub: String? = null,
)

private fun mealIcon(mealId: String): ImageVector = when (mealId) {
    "breakfast" -> Icons.Rounded.FreeBreakfast
    "lunch" -> Icons.Rounded.LunchDining
    "snack" -> Icons.Rounded.BakeryDining
    "dinner" -> Icons.Rounded.DinnerDining
    else -> Icons.Rounded.Restaurant
}

private fun currentMeal(): MealTime {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 10 -> MealTime("아침 식사", "08:00 ~ 10:00", Icons.Rounded.Restaurant)
        hour < 14 -> MealTime("점심 식사", "12:00 ~ 14:00", Icons.Rounded.Restaurant)
        hour < 18 -> MealTime("오후 간식", "15:00 ~ 17:00", Icons.Rounded.Restaurant)
        else -> MealTime("저녁 식사", "18:00 ~ 20:00", Icons.Rounded.Restaurant)
    }
}

private fun currentMealId(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 10 -> "breakfast"
        hour < 14 -> "lunch"
        hour < 18 -> "snack"
        else -> "dinner"
    }
}

private fun recommendedCalorieGoal(
    heightCm: Int,
    weightKg: Int,
    age: Int = 30,
    activityFactor: Double,
): Int {
    val bmr = 10 * weightKg + 6.25 * heightCm - 5 * age + 5
    return ((bmr * activityFactor) / 10).toInt() * 10
}

private fun recommendedNutritionGoal(
    heightCm: Int,
    weightKg: Int,
    age: Int = 30,
    activityFactor: Double,
): Nutrition {
    val kcal = recommendedCalorieGoal(heightCm, weightKg, age, activityFactor)
    val protein = (weightKg * 1.8).roundToInt().toDouble()
    val fat = (kcal * 0.25 / 9.0).roundToInt().toDouble()
    val carb = ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(0).toDouble()
    return Nutrition(kcal = kcal, carb = carb, protein = protein, fat = fat)
}

private fun String.numericText(): String = filter(Char::isDigit)

private fun String.decimalText(): String = filter { it.isDigit() || it == '.' }
