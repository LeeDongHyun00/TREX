package com.example.trex_kotlin

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val decorativeImages = LruCache<String, ImageBitmap>(6)

/** 장식 이미지는 화면 밖에서 디코딩한다. 사용자 식사 사진이나 자세 정답으로 표시하지 않는다. */
@Composable
internal fun AssetArtwork(path: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit) {
    val assets = LocalContext.current.applicationContext.assets
    val bitmap by produceState(decorativeImages.get(path), path) {
        value = withContext(Dispatchers.IO) {
            decorativeImages.get(path) ?: assets.open(path).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (path.startsWith("routine/")) 2 else 4 })!!.asImageBitmap()
            }.also { decorativeImages.put(path, it) }
        }
    }
    bitmap?.let { Image(it, null, modifier, contentScale = scale) }
}

@Composable
internal fun HomeNutrition(total: Nutrition, goal: Nutrition, onOpen: () -> Unit) {
    val c = Trex.c
    Surface(shape = RoundedCornerShape(26.dp), color = c.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("오늘의 섭취", color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text("식단 보기", color = c.text2, fontSize = 12.sp) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                val stacked = maxWidth < 290.dp || LocalDensity.current.fontScale > 1.4f
                val ring: @Composable () -> Unit = {
                    RingGauge(if (goal.kcal > 0) total.kcal.toFloat() / goal.kcal else 0f, if (stacked) 128.dp else 116.dp, 8.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${total.kcal}", color = c.text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                            Text("/ ${goal.kcal} kcal", color = c.text2, fontSize = 11.sp)
                        }
                    }
                }
                val macros: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        MacroBar("탄수화물", total.carb.toInt(), goal.carb.toInt(), c.primary, showPercent = true)
                        MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime, showPercent = true)
                        MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.warn, showPercent = true)
                    }
                }
                if (stacked) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)) { ring(); macros() }
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ring(); Box(Modifier.weight(1f)) { macros() }
                }
            }
            Text(when {
                goal.kcal <= 0 -> "영양 목표를 설정해 주세룡"
                total.kcal < goal.kcal -> "목표까지 ${goal.kcal - total.kcal} kcal 남았어룡"
                total.kcal == goal.kcal -> "칼로리 목표에 도달했어룡"
                else -> "목표보다 ${total.kcal - goal.kcal} kcal 더 기록했어룡"
            }, color = c.primaryText, fontSize = 11.sp,
                modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.primaryWash).padding(horizontal = 10.dp, vertical = 7.dp))
        }
    }
}

/** 좁은 창과 큰 글씨에서는 카드 자체를 세로로 바꿔 버튼과 숫자를 자르지 않는다. */
@Composable
internal fun HomeActionCards(overview: RoutineOverview, onWorkout: () -> Unit, onMeal: () -> Unit) {
    val c = Trex.c
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 330.dp || LocalDensity.current.fontScale > 1.3f
        val workout: @Composable (Modifier) -> Unit = { modifier ->
            Surface(onClick = onWorkout, shape = RoundedCornerShape(25.dp), color = c.primary, modifier = modifier) {
                Box(Modifier.heightIn(min = 220.dp)) {
                    AssetArtwork("routine/${overview.focus.image}.png", Modifier.matchParentSize(), ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x18385027), Color(0xAB385027), Color(0xF5385027)))))
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("오늘 운동", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(overview.focus.title, color = Color.White, fontSize = 13.sp)
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
                Column(Modifier.heightIn(min = 220.dp).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
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
internal fun HomeActivityCards(app: AppViewModel) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("운동 소모 · 추정" to "${app.todayRecord?.totalCalories() ?: 0} kcal", "연속 운동" to "${app.attendanceStreak()}일").forEach { (label, value) ->
            Surface(shape = RoundedCornerShape(24.dp), color = c.surface, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(18.dp)) {
                    Text(label, color = c.text2, fontSize = 12.sp)
                    Text(value, color = c.text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 15.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        (6 downTo 0).forEach { offset ->
                            val record = app.workoutHistory.firstOrNull { it.epochDay == app.calendarDay - offset }
                            val calories = label.startsWith("운동")
                            val count = if (calories) record?.totalCalories() ?: 0 else record?.items?.size ?: 0
                            val max = app.workoutHistory.maxOfOrNull { if (calories) it.totalCalories() else it.items.size }?.coerceAtLeast(1) ?: 1
                            Box(Modifier.weight(1f).height(if (label.startsWith("운동")) (3 + 22f * count / max).dp else 15.dp)
                                .clip(RoundedCornerShape(4.dp)).background(if (count > 0) c.primary else c.track))
                        }
                    }
                }
            }
        }
    }
}
