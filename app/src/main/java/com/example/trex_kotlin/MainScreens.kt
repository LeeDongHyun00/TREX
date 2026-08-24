package com.example.trex_kotlin

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FreeBreakfast
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LunchDining
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** 종목 카테고리별 코칭 팁 (운동 카드 펼침 영역). */
val categoryTips = mapOf(
    "하체" to "무릎이 발끝을 넘지 않게, 뒤꿈치로 바닥을 눌러주세룡",
    "코어" to "허리를 편 상태로 배에 힘을 유지해주세룡",
    "복근" to "허리를 편 상태로 배에 힘을 유지해주세룡",
    "상체" to "어깨를 내리고 팔꿈치는 몸통 쪽으로 붙여주세룡",
    "회복" to "호흡을 길게 내쉬면서 천천히 늘려주세룡",
    "유산소" to "리듬을 일정하게, 착지는 부드럽게 해주세룡",
)

private val tabContentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 50.dp, bottom = 110.dp)

// ============================================================= HOME

@Composable
fun HomeScreen(
    app: AppViewModel,
    onGoWorkout: () -> Unit,
    onGoDiet: () -> Unit,
) {
    val c = Trex.c
    val today = LocalDate.now()
    val plan = app.workoutPlan
    val doneCount = plan.count { it.done }
    val weekDays = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val activeDays = app.workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()
    val meal = currentMealInfo()
    val todayFoods = app.dietFor(0)
    val mealLogged = todayFoods[meal.id].orEmpty().isNotEmpty()
    val total = todayFoods.values.flatten().totalNutrition()
    val goal = app.targetGoal
    val kcalPct = if (goal.kcal > 0) (total.kcal * 100 / goal.kcal) else 0
    val burnedToday = app.todayRecord?.totalCalories() ?: 0
    val weekKcal = weekDays.map { d -> app.workoutHistory.firstOrNull { it.epochDay == d.toEpochDay() }?.totalCalories() ?: 0 }
    val maxKcal = (weekKcal.maxOrNull() ?: 0).coerceAtLeast(1)
    val streak = app.attendanceStreak()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = tabContentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${today.monthValue}월 ${today.dayOfMonth}일 ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}",
                        color = c.text3, fontSize = 11.5.sp,
                    )
                    Text("안녕하세룡!", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
                RoundIcon(Icons.Rounded.Notifications, onClick = {}, size = 40.dp, contentDescription = "알림")
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weekDays.forEach { date ->
                    val attended = date.toEpochDay() in activeDays
                    val isToday = date == today
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN), color = c.text3, fontSize = 10.sp)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (attended) c.primary else if (isToday) c.surface else Color.Transparent)
                                .border(1.dp, if (attended) c.primary else if (isToday) c.primarySoftLine else c.line, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                color = if (attended) Color.White else if (isToday) c.text else c.text3,
                                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // 오늘 섭취 — 칼로리 링 + 탄단지 (리디자인의 홈 메인 카드)
        item {
            DCard(radius = 28.dp) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Kicker("오늘 섭취")
                        Spacer(Modifier.weight(1f))
                        WashPill("$kcalPct%")
                    }
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RingGauge(progress = if (goal.kcal > 0) total.kcal / goal.kcal.toFloat() else 0f, size = 104.dp, stroke = 9.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${total.kcal}",
                                    color = c.text,
                                    fontSize = if (total.kcal >= 10_000) 19.sp else 25.sp,
                                    fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, maxLines = 1,
                                )
                                Text("/ ${goal.kcal}", color = c.text3, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MacroBar("탄수", total.carb.toInt(), goal.carb.toInt(), c.primary)
                            MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime)
                            MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.warn)
                        }
                    }
                }
            }
        }

        // 두 액션 카드 — 운동하기 / 식사 기록
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onGoWorkout,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = c.primary,
                    contentColor = Color.White,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.height(26.dp))
                        Text("운동하기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (doneCount == 0) "${plan.size}개 · 약 ${plan.sumOf { it.durationMinutes() }}분" else "$doneCount/${plan.size} 완료",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Surface(
                    onClick = onGoDiet,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = c.surface,
                    contentColor = c.text,
                    border = BorderStroke(1.dp, c.line),
                    shadowElevation = 2.dp,
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(c.primaryWash),
                            contentAlignment = Alignment.Center,
                        ) { Icon(mealIcon(meal.id), contentDescription = null, tint = c.primaryText, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.height(26.dp))
                        val mealShort = mealMetas.firstOrNull { it.id == meal.id }?.label ?: meal.label
                        Text(if (mealLogged) "$mealShort 수정" else "$mealShort 기록", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(meal.timeHint, fontSize = 11.sp, color = c.text3, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }

        // 통계 — 오늘 소모 / 연속 출석
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DCard(modifier = Modifier.weight(1f), radius = 22.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("오늘 소모", color = c.text3, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = c.warn, modifier = Modifier.size(15.dp))
                        }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text("$burnedToday", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                            Text(" kcal", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Row(
                            Modifier.padding(top = 12.dp).height(26.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            weekKcal.forEachIndexed { i, v ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height((26 * (v / maxKcal.toFloat())).coerceAtLeast(3f).dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (i == weekKcal.lastIndex) c.primary else c.track),
                                )
                            }
                        }
                    }
                }
                DCard(modifier = Modifier.weight(1f), radius = 22.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("연속 출석", color = c.text3, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(15.dp))
                        }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text("$streak", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                            Text(" 일", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Row(
                            Modifier.padding(top = 12.dp).height(26.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(7) { i ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (i < streak.coerceAtMost(7)) c.primary else c.track),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================= WORKOUT TAB

@Composable
fun WorkoutTabScreen(
    app: AppViewModel,
    onOpenAlt: (Workout) -> Unit,
    onOpenSets: (Workout) -> Unit,
    onAddWorkout: () -> Unit,
) {
    val c = Trex.c
    val plan = app.workoutPlan
    val doneCount = plan.count { it.done }
    var openWorkout by remember { mutableStateOf(plan.firstOrNull { !it.done }?.id) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = tabContentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Kicker("오늘의 운동")
                    TitleBig(
                        if (plan.isNotEmpty() && doneCount >= plan.size) "오늘 루틴 완료"
                        else "${plan.size}개 루틴, 약 ${plan.sumOf { it.durationMinutes() }}분",
                    )
                    Text("${doneCount}개 완료 · 남은 ${plan.size - doneCount}개", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
                RoundIcon(Icons.Rounded.Add, onClick = onAddWorkout, size = 40.dp, contentDescription = "운동 추가")
            }
            Spacer(Modifier.height(14.dp))
            TrackBar(progress = if (plan.isEmpty()) 0f else doneCount / plan.size.toFloat())
        }

        // 날씨 카드 — 중립 서피스 + 소프트 아이콘 버블 + 실내 추천 칩 (경고 워시 대신 차분한 톤)
        item {
            DCard(radius = 22.dp) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(c.surface2),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Cloud, contentDescription = null, tint = c.text2, modifier = Modifier.size(19.dp)) }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("비 예보", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("6.4 mm/h", color = c.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 1.dp))
                        }
                        Text("오늘은 실내 루틴이 좋아룡", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    WashPill("실내 추천")
                }
            }
        }

        items(count = plan.size, key = { plan[it].id }) { i ->
            val w = plan[i]
            WorkoutExpandCard(
                workout = w,
                index = i,
                open = openWorkout == w.id,
                onToggleOpen = { openWorkout = if (openWorkout == w.id) null else w.id },
                onOpenAlt = { onOpenAlt(w) },
                onOpenSets = { onOpenSets(w) },
                onTogglePosture = { app.togglePosture(w.id) },
            )
        }
    }
}

@Composable
private fun WorkoutExpandCard(
    workout: Workout,
    index: Int,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onOpenAlt: () -> Unit,
    onOpenSets: () -> Unit,
    onTogglePosture: () -> Unit,
) {
    val c = Trex.c
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(300), label = "chev")
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(320)),
        shape = RoundedCornerShape(24.dp),
        color = c.surface,
        contentColor = c.text,
        border = BorderStroke(1.dp, if (open) c.primarySoftLine else c.line),
        shadowElevation = 2.dp,
    ) {
        Column {
            Surface(onClick = onToggleOpen, color = Color.Transparent, contentColor = c.text) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (workout.done) c.primary else if (open) c.primaryWash else c.surface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (index + 1).toString().padStart(2, '0'),
                            color = if (workout.done) Color.White else if (open) c.primaryText else c.text3,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(workout.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${workout.reps} · ${workout.duration}", color = c.text3, fontSize = 11.5.sp)
                            if (workout.posture && workout.postureSupported()) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.primaryWash).padding(horizontal = 7.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(9.dp))
                                    Text("자세교정", color = c.primaryText, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp))
                                }
                            }
                        }
                    }
                    Icon(
                        Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.text3,
                        modifier = Modifier.size(17.dp).rotate(chevron),
                    )
                }
            }
            if (open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workout.category,
                            color = c.text2, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                        Text(
                            categoryTips[workout.category].orEmpty(),
                            color = c.text3, fontSize = 11.5.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        GhostButton("대체 운동", onClick = onOpenAlt, icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f), height = 44.dp)
                        GhostButton("세트 수정", onClick = onOpenSets, icon = Icons.Rounded.Edit, modifier = Modifier.weight(1f), height = 44.dp)
                    }
                    // 자세 교정 스위치 — 규칙 엔진 지원 종목에만. 미지원이면 안내만.
                    if (workout.postureSupported()) {
                        val on = workout.posture
                        Surface(
                            onClick = onTogglePosture,
                            shape = RoundedCornerShape(15.dp),
                            color = if (on) c.primaryWash else c.surface2,
                            contentColor = if (on) c.primaryText else c.text2,
                            border = BorderStroke(1.dp, if (on) c.primarySoftLine else c.line),
                        ) {
                            Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text("자세 교정 사용", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (on) "카메라로 실시간 자세 평가를 해줘룡" else "켜면 카메라로 자세를 평가해룡",
                                        fontSize = 10.sp, color = if (on) c.primaryText.copy(alpha = 0.8f) else c.text3,
                                    )
                                }
                                Box(
                                    Modifier
                                        .width(38.dp).height(22.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (on) c.primary else c.track)
                                        .padding(2.dp),
                                    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                                ) {
                                    Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(15.dp))
                                .background(c.surface2)
                                .border(1.dp, c.line, RoundedCornerShape(15.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = c.text3, modifier = Modifier.size(15.dp))
                            Text(
                                "이 운동의 자세 평가는 준비 중이에룡",
                                color = c.text3, fontSize = 11.5.sp,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================= DIET TAB

@Composable
fun DietTabScreen(
    app: AppViewModel,
    onOpenGoals: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenManual: (String) -> Unit,
) {
    val c = Trex.c
    val foods = app.dietFor(0)
    val total = foods.values.flatten().totalNutrition()
    val goal = app.targetGoal
    val kcalPct = if (goal.kcal > 0) total.kcal * 100 / goal.kcal else 0
    val remain = goal.kcal - total.kcal

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = tabContentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Kicker("오늘의 식단")
            TitleBig("바로 보고, 바로 채우고", size = 21)
        }

        item {
            DCard(radius = 28.dp) {
                Column {
                    Column(Modifier.padding(20.dp)) {
                        // 헤더에 % 를 빼고 링 안에는 수치만 — 텍스트가 링 밖으로 넘치지 않게
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Kicker("오늘 섭취")
                            Spacer(Modifier.weight(1f))
                            WashPill("목표 ${goal.kcal} kcal · $kcalPct%")
                        }
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RingGauge(progress = if (goal.kcal > 0) total.kcal / goal.kcal.toFloat() else 0f, size = 118.dp, stroke = 11.dp) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${total.kcal}",
                                        color = c.text,
                                        fontSize = if (total.kcal >= 10_000) 21.sp else 27.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 28.sp,
                                        maxLines = 1,
                                    )
                                    Text("kcal", color = c.text3, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            Spacer(Modifier.width(20.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                MacroBar("탄수", total.carb.toInt(), goal.carb.toInt(), c.primary, barHeight = 7.dp)
                                MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime, barHeight = 7.dp)
                                MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.warn, barHeight = 7.dp)
                            }
                        }
                        Text(
                            text = if (remain > 0) "$remain kcal 더 먹을 수 있어룡" else "오늘 목표를 채웠어룡",
                            color = c.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Surface(onClick = onOpenGoals, color = c.surface2, contentColor = c.text) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Tune, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(14.dp)) }
                            Text("영양 목표 수정", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 11.dp).weight(1f))
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        item {
            Surface(
                onClick = onOpenPhoto,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = c.surface,
                contentColor = c.text,
                border = BorderStroke(1.dp, c.line),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("사진으로 식단 기록", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("찍으면 음식·영양정보를 자동으로 채워줘룡", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(17.dp))
                }
            }
        }

        items(count = mealMetas.size, key = { mealMetas[it].id }) { i ->
            val meta = mealMetas[i]
            val list = foods[meta.id].orEmpty()
            val empty = list.isEmpty()
            DCard(radius = 20.dp) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (empty) c.surface2 else c.primaryWash),
                            contentAlignment = Alignment.Center,
                        ) { Icon(mealIcon(meta.id), contentDescription = null, tint = if (empty) c.text3 else c.primaryText, modifier = Modifier.size(18.dp)) }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(meta.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (empty) "기록 전" else "${list.totalNutrition().kcal} kcal · ${list.sumOf { it.qty }}개",
                                color = c.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Surface(
                            onClick = { onOpenManual(meta.id) },
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = if (empty) c.primary else c.surface2,
                            contentColor = if (empty) Color.White else c.primaryText,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (empty) Icons.Rounded.Add else Icons.Rounded.Edit, contentDescription = "기록", modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                    if (!empty) {
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            list.take(4).forEach {
                                Text(
                                    if (it.qty > 1) "${it.name} ×${it.qty}" else it.name,
                                    color = c.text2, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Kicker("추천 식단")
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(c.primaryWash)
                        .border(1.dp, c.primarySoftLine, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Restaurant, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("초보자용 · 약 520 kcal", color = c.text3, fontSize = 11.sp)
                        Text("고단백 저녁 한끼", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                        Text("연어 스테이크 · 퀴노아 · 브로콜리", color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

// ============================================================= PROFILE TAB

@Composable
fun ProfileTabScreen(
    app: AppViewModel,
    onOpenRecord: () -> Unit,
    onLogout: () -> Unit,
) {
    val c = Trex.c
    val profile = app.profile
    val settingGroups = listOf(
        listOf(
            Triple(Icons.Rounded.Person, "프로필 편집", "이름 · 사진 · 목표"),
            Triple(Icons.Rounded.ManageAccounts, "계정 관리", "이메일 · 비밀번호 · 연결된 계정"),
        ),
        listOf(
            Triple(Icons.Rounded.Notifications, "알림", "운동 · 식단 리마인더"),
            Triple(Icons.Rounded.Language, "언어", "한국어"),
            Triple(Icons.Rounded.Security, "개인정보 보호", "카메라 · 기록 데이터"),
        ),
        listOf(
            Triple(Icons.AutoMirrored.Rounded.Help, "도움말", "자세 인식이 안 될 때"),
            Triple(Icons.Rounded.Description, "약관 및 정책", "서비스 이용약관"),
        ),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = tabContentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Kicker("내 정보")
            TitleBig("설정", size = 21)
        }

        item {
            DCard(radius = 22.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    Column(Modifier.padding(start = 13.dp).weight(1f)) {
                        Text("사용자", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${profile.heightCm.toInt()}cm · ${profile.weightKg.toInt()}kg · ${profile.age}세",
                            color = c.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                        Box(Modifier.padding(top = 7.dp)) { WashPill("${profileGoalLabel(profile.goal)} 루틴 진행중") }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(17.dp))
                }
            }
        }

        // 화면 모드
        item {
            DCard(radius = 20.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingIcon(
                            when (app.themeMode) {
                                ThemeMode.Dark -> Icons.Rounded.DarkMode
                                ThemeMode.Light -> Icons.Rounded.LightMode
                                ThemeMode.System -> Icons.Rounded.Brightness4
                            },
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("화면 모드", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when (app.themeMode) {
                                    ThemeMode.System -> "시스템 설정에 맞춰룡"
                                    ThemeMode.Dark -> "항상 다크로 보여룡"
                                    ThemeMode.Light -> "항상 라이트로 보여룡"
                                },
                                color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    SegmentedTabs(
                        options = listOf("라이트", "다크", "시스템"),
                        selected = when (app.themeMode) {
                            ThemeMode.Light -> 0
                            ThemeMode.Dark -> 1
                            ThemeMode.System -> 2
                        },
                        onSelect = { app.setTheme(listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.System)[it]) },
                        height = 38.dp,
                        filled = true,
                    )
                }
            }
        }

        item {
            DCard(radius = 20.dp) {
                Surface(onClick = onOpenRecord, color = Color.Transparent, contentColor = c.text) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingIcon(Icons.Rounded.BarChart)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("운동 기록", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("일주일 기록과 정확도 보기", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        items(count = settingGroups.size) { gi ->
            DCard(radius = 20.dp) {
                Column {
                    settingGroups[gi].forEachIndexed { ri, (icon, label, sub) ->
                        if (ri > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Surface(onClick = {}, color = Color.Transparent, contentColor = c.text) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                SettingIcon(icon)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                    Text(sub, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Surface(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = c.errWash,
                    contentColor = c.err,
                    border = BorderStroke(1.dp, c.errLine),
                ) {
                    Row(
                        Modifier.padding(15.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("로그아웃", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    "TREX v1.0.0",
                    color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// ============================================================= 공용 조각

/** 기본 서피스 카드. */
@Composable
fun DCard(modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 24.dp, content: @Composable () -> Unit) {
    val c = Trex.c
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        color = c.surface,
        contentColor = c.text,
        border = BorderStroke(1.dp, c.line),
        shadowElevation = 2.dp,
    ) { content() }
}

/** primaryWash 톤 알약 라벨. */
@Composable
fun WashPill(text: String) {
    val c = Trex.c
    Text(
        text,
        color = c.primaryText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.primaryWash).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    val c = Trex.c
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(c.surface2),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(15.dp)) }
}

fun mealIcon(mealId: String): ImageVector = when (mealId) {
    "breakfast" -> Icons.Rounded.FreeBreakfast
    "lunch" -> Icons.Rounded.LunchDining
    "snack" -> Icons.Rounded.BakeryDining
    "dinner" -> Icons.Rounded.DinnerDining
    else -> Icons.Rounded.Restaurant
}

fun profileGoalLabel(goal: String): String = when (goal) {
    "muscle" -> "근육 증가"
    "diet" -> "다이어트"
    "stamina" -> "체력 향상"
    "maintain" -> "유지"
    else -> "일반"
}
