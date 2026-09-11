package com.example.trex_kotlin

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.rounded.DragIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.rememberPostureScope
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

internal val tabContentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 50.dp, bottom = 110.dp)

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
            DCard(modifier = Modifier.clickable(onClickLabel = "식단 보기", onClick = onGoDiet), radius = 28.dp) {
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

// ============================================================= DIET TAB

@Composable
fun DietTabScreen(app: AppViewModel, onOpenGoals: () -> Unit, onOpenPhoto: () -> Unit, onOpenManual: (String) -> Unit, onOpenRecord: () -> Unit) {
    val c = Trex.c
    val foods = app.dietFor(0)
    val total = foods.values.flatten().totalNutrition()
    val goal = app.targetGoal
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = tabContentPadding) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("오늘 식단", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onOpenGoals) {
                    Icon(Icons.Rounded.Edit, contentDescription = "영양 목표 수정", tint = c.primaryText)
                }
            }
            Row(Modifier.padding(vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                RingGauge(if (goal.kcal > 0) total.kcal.toFloat() / goal.kcal else 0f, 118.dp, 7.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${total.kcal}", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text("kcal", color = c.text2, fontSize = 12.sp)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MacroBar("탄수", total.carb.toInt(), goal.carb.toInt(), c.primary)
                    MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.primary)
                    MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.primary)
                }
            }
            Text("목표 ${goal.kcal} kcal", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 22.dp))
            DietRecordLink(onOpenRecord)
        }
        items(mealMetas.size, key = { mealMetas[it].id }) { i ->
            val meal = mealMetas[i]; val entries = foods[meal.id].orEmpty()
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(meal.label, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(if (entries.isEmpty()) "기록 전" else "${entries.totalNutrition().kcal} kcal", color = c.text2, fontSize = 13.sp)
                }
                if (entries.isNotEmpty()) Text(entries.joinToString(" · ") { if (it.qty > 1) "${it.name} ×${it.qty}" else it.name },
                    color = c.text2, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 9.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        }
    }
}

// ============================================================= PROFILE TAB

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
