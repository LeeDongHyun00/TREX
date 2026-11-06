package com.example.trex_kotlin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

private val decorativeImages = AssetImageCache(6)

/** 장식 이미지는 화면 밖에서 디코딩한다. 사용자 식사 사진이나 자세 정답으로 표시하지 않는다. */
@Composable
internal fun AssetArtwork(path: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit) {
    val decoder = rememberAssetImageDecoder()
    val bitmap by produceState(decorativeImages.get(path), path) {
        value = withContext(Dispatchers.IO) {
            decorativeImages.get(path) ?: decoder.decode(path, if (path.startsWith("routine/")) 2 else 4)
                .also { decorativeImages.put(path, it) }
        }
    }
    bitmap?.let { Image(it, null, modifier, contentScale = scale) }
}

/**
 * 오늘의 섭취 요약(§108f, 사용자 요청 — 제목·'목표까지 … 남았어룡' 칸·'식단 보기' 를 빼고 높이를 최대한 줄임) — kcal 고리와 탄수화물·단백질·지방.
 * 카드를 누르면 식단 탭(예전 '식단 보기' 자리). 좁은 창·큰 글씨에서는 영양소를 세로로 쌓는다.
 */
@Composable
internal fun HomeNutrition(total: Nutrition, goal: Nutrition, onOpen: () -> Unit) {
    val c = Trex.c
    Surface(onClick = onOpen, shape = RoundedCornerShape(22.dp), color = c.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingGauge(if (goal.kcal > 0) total.kcal.toFloat() / goal.kcal else 0f, 68.dp, 6.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${total.kcal}", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("/ ${goal.kcal} kcal", color = c.text2, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                if (stacked) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MacroBar("탄수화물", total.carb.toInt(), goal.carb.toInt(), c.primary, barHeight = 5.dp, showPercent = true)
                    MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime, barHeight = 5.dp, showPercent = true)
                    MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.warn, barHeight = 5.dp, showPercent = true)
                } else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactMacro("탄수화물", total.carb.toInt(), goal.carb.toInt(), c.primary, Modifier.weight(1f))
                    CompactMacro("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime, Modifier.weight(1f))
                    CompactMacro("지방", total.fat.toInt(), goal.fat.toInt(), c.warn, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 영양소 한 칸(가로 셋) — 이름·퍼센트, 섭취/목표 g, 막대. */
@Composable
private fun CompactMacro(label: String, value: Int, goal: Int, color: Color, modifier: Modifier) {
    val c = Trex.c
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = c.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (goal > 0) Text("${value * 100 / goal}%", color = c.primaryText, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        }
        Text("$value/${goal}g", color = c.text3, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        TrackBar(progress = if (goal > 0) value / goal.toFloat() else 0f, color = color, height = 5.dp, modifier = Modifier.padding(top = 5.dp))
    }
}

/** 좁은 창과 큰 글씨에서는 카드 자체를 세로로 바꿔 버튼과 숫자를 자르지 않는다. */
@Composable
internal fun HomeActionCards(overview: RoutineOverview, onWorkout: () -> Unit, onMeal: () -> Unit, routineName: String? = null, onChangeRoutine: (() -> Unit)? = null) {
    val c = Trex.c
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 330.dp || LocalDensity.current.fontScale > 1.3f
        val workout: @Composable (Modifier) -> Unit = { modifier ->
            Surface(onClick = onWorkout, shape = RoundedCornerShape(25.dp), color = c.primary, modifier = modifier) {
                Box(Modifier.heightIn(min = 196.dp)) {
                    AssetArtwork("routine/${overview.focus.image}.png", Modifier.matchParentSize(), ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x18385027), Color(0xAB385027), Color(0xF5385027)))))
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("오늘 운동", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            // 주간 루틴(§131 P11)으로 — 오늘 요일의 루틴을 바꾼다
                            if (onChangeRoutine != null) Text("루틴 바꾸기", color = c.lime, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onChangeRoutine).padding(4.dp))
                        }
                        Text(routineName?.let { "$it · ${overview.focus.title}" } ?: overview.focus.title, color = Color.White, fontSize = 13.sp)
                        Text(if (overview.count > 0) "${overview.count}개 운동 · 약 ${overview.minutes}분" else "운동을 추가해 주세룡", color = Color.White.copy(alpha = .85f), fontSize = 11.sp)
                        Surface(onClick = onWorkout, shape = RoundedCornerShape(50), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                            Text("운동하기", color = c.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        val meal: @Composable (Modifier) -> Unit = { modifier ->
            Surface(onClick = onMeal, shape = RoundedCornerShape(25.dp), color = c.surface, modifier = modifier) {
                Column(Modifier.heightIn(min = 196.dp).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    MealSymbol(currentMealId(), Modifier.size(58.dp))
                    Text("${mealMetas.first { it.id == currentMealId() }.label} 식사 기록", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Surface(onClick = onMeal, color = c.primaryWash, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                        Text("기록하기", color = c.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { workout(Modifier.fillMaxWidth()); meal(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { workout(Modifier.weight(1.3f)); meal(Modifier.weight(1f)) }
    }
}

@Composable
internal fun HomeActivityCards(app: AppViewModel, onOpenAchievements: () -> Unit = {}, onOpenRecord: () -> Unit = {}) {
    val c = Trex.c
    // §131 P18 — 연속 운동(목표 요일 기준)·이번 주 볼륨(kg × 확정 회, 추정 세트 제외)
    val weekStart = app.calendarDay - kotlinx.datetime.LocalDate.fromEpochDays(app.calendarDay).dayOfWeek.ordinal
    val volume = app.workoutHistory.volume(weekStart, app.calendarDay)
    val weekSets = app.workoutHistory.filter { it.epochDay in weekStart..app.calendarDay }.sumOf { d -> d.items.sumOf { if (it.sets.isEmpty()) parseReps(it.reps).sets else it.sets.count { s -> s.done } } }
    val weekDays = app.workoutHistory.count { it.epochDay in weekStart..app.calendarDay && it.items.isNotEmpty() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Triple("연속 운동", "${app.attendanceStreak()}일", "목표 요일 기준"), Triple("이번 주 볼륨", "${volume.kg.toInt()} kg", "${weekDays}일 · ${weekSets}세트" + if (volume.estimatedSets > 0) " · 추정 ${volume.estimatedSets}" else "")).forEachIndexed { index, (label, value, sub) ->
            Surface(onClick = if (index == 0) onOpenAchievements else onOpenRecord, shape = RoundedCornerShape(24.dp), color = c.surface, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(18.dp)) {
                    Text(label, color = c.text2, fontSize = 12.sp)
                    Text(value, color = c.text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    Text(sub, color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        (6 downTo 0).forEach { offset ->
                            val record = app.workoutHistory.firstOrNull { it.epochDay == app.calendarDay - offset }
                            val count = if (index == 0) (record?.items?.size ?: 0) else record?.items?.sumOf { it.sets.sumOf { s -> (s.volumeKg ?: 0.0).toInt() } } ?: 0
                            val max = if (index == 0) 1 else app.workoutHistory.maxOfOrNull { d -> d.items.sumOf { it.sets.sumOf { s -> (s.volumeKg ?: 0.0).toInt() } } }?.coerceAtLeast(1) ?: 1
                            Box(Modifier.weight(1f).height(if (index == 0) 15.dp else (3 + 22f * count / max).dp)
                                .clip(RoundedCornerShape(4.dp)).background(if (count > 0) c.primary else c.track))
                        }
                    }
                }
            }
        }
    }
}
