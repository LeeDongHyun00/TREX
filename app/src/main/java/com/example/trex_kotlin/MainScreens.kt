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
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.WbSunny
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
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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

internal val tabContentPadding: PaddingValues
    @Composable get() = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = LocalTrexNavSpace.current)

// ============================================================= HOME

@Composable
fun HomeScreen(app: AppViewModel, onGoWorkout: () -> Unit, onGoDiet: () -> Unit, onRecordMeal: () -> Unit) {
    val c = Trex.c
    val today = LocalDate.now()
    val activeDays = app.workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()
    val total = app.dietFor(0).values.flatten().totalNutrition()
    val goal = app.targetGoal
    val overview = remember(app.workoutPlan) { routineOverview(app.workoutPlan) }
    LazyColumn(Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = LocalTrexNavSpace.current),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("${today.monthValue}월 ${today.dayOfMonth}일 ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}",
                color = c.text2, fontSize = 14.sp)
            Text("운동한 날", color = c.text2, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (6 downTo 0).forEach { offset ->
                    val date = today.minusDays(offset.toLong())
                    val attended = date.toEpochDay() in activeDays
                    val isToday = date == today
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "${date.monthValue}월 ${date.dayOfMonth}일, ${if(attended) "운동 기록 있음" else "운동 기록 없음"}${if(isToday) ", 오늘" else ""}"
                        }) {
                        Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN), color = c.text2, fontSize = 11.sp)
                        Box(Modifier.padding(top = 8.dp).size(36.dp).clip(CircleShape)
                            .background(if (attended) c.primary else c.surface)
                            .border(if(isToday) 2.dp else 0.dp, if(isToday)c.primaryText else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text("${date.dayOfMonth}", color = if(attended) Color.White else c.text,
                                fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                        Text(if(isToday) "오늘" else if(attended) "✓" else "", color = c.primaryText, fontSize = 10.sp,
                            modifier = Modifier.height(18.dp))
                    }
                }
            }
        }
        item { HomeNutrition(total, goal, onGoDiet) }
        item { HomeActionCards(overview, onGoWorkout, onRecordMeal) }
        item { HomeActivityCards(app) }
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
                    MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime)
                    MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.warn)
                }
            }
            Text("목표 ${goal.kcal} kcal", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 22.dp))
            DietRecordLink(onOpenRecord)
        }
        items(mealMetas.size, key = { mealMetas[it].id }) { i ->
            val meal = mealMetas[i]; val entries = foods[meal.id].orEmpty()
            Surface(onClick = { onOpenManual(meal.id) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = "${meal.label} 식단 수정" }, shape = RoundedCornerShape(22.dp), color = c.surface) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MealSymbol(meal.id, Modifier.size(58.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(meal.label, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(if (entries.isEmpty()) "기록 전" else "${entries.totalNutrition().kcal} kcal", color = c.text2, fontSize = 13.sp)
                }
                if (entries.isNotEmpty()) Text(entries.joinToString(" · ") { if (it.qty > 1) "${it.name} ×${it.qty}" else it.name },
                    color = c.text2, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 9.dp))
            }
            }
            }
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
    "breakfast" -> Icons.Rounded.WbTwilight
    "lunch" -> Icons.Rounded.WbSunny
    "snack" -> Icons.Rounded.Coffee
    "dinner" -> Icons.Rounded.DarkMode
    else -> Icons.Rounded.Restaurant
}

fun profileGoalLabel(goal: String): String = when (goal) {
    "muscle" -> "근육 증가"
    "diet" -> "다이어트"
    "stamina" -> "체력 향상"
    "maintain" -> "유지"
    else -> "일반"
}
