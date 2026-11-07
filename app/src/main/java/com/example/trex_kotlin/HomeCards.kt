package com.example.trex_kotlin

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            // 루틴 사진과 가이드 첫 프레임(종목 상세 머리 그림, UI 감사 A11)은 크게 보여 절반 축소만
            decorativeImages.get(path) ?: decoder.decode(path, if (path.startsWith("routine/") || path.startsWith("exercise_guides/")) 2 else 4)
                .also { decorativeImages.put(path, it) }
        }
    }
    bitmap?.let { Image(it, null, modifier, contentScale = scale) }
}

/**
 * 홈 '오늘 한눈' 띠(UI 감사 2026-11-07 A1, K1) — 운동 세트·섭취 kcal·물 잔의 도넛 셋. 도넛은 막대와 함께 가장 빨리 읽히는 채움(Blascheck 2018 ~160 ms).
 * 운동은 오늘 루틴의 세트 수 기준(완료한 운동의 세트), 섭취는 목표 대비, 물은 8잔 대비. 물 도넛의 + 는 한 잔 더(값 변화는 링이 보여 준다).
 * 셋을 누르면 각 탭으로 간다. 점수·자세와 무관한 '했다' 의 양만 그린다(원칙 #1).
 */
@Composable
internal fun HomeGlanceStrip(app: AppViewModel, total: Nutrition, goal: Nutrition, onWorkout: () -> Unit, onDiet: () -> Unit) {
    val c = Trex.c
    val plan = app.workoutPlan
    val totalSets = plan.sumOf { it.repsSpec().sets }
    val doneSets = plan.filter { it.done }.sumOf { it.repsSpec().sets }
    val cups = app.waterFor(0)
    Surface(shape = RoundedCornerShape(22.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp)) {
            GlanceDonut(if (totalSets > 0) doneSets.toFloat() / totalSets else 0f, if (plan.isEmpty()) "—" else "$doneSets/$totalSets", "운동 세트",
                done = totalSets > 0 && doneSets >= totalSets, onClick = onWorkout)
            // 섭취 — 숫자 아래 목표, 오른쪽에 탄·단·지 작은 링 셋(사용자 요청 2026-11-07 — 아래 탄단지 카드는 뺐다)
            GlanceDonut(if (goal.kcal > 0) total.kcal.toFloat() / goal.kcal else 0f, "${total.kcal}", "섭취 kcal", sub = "/ ${goal.kcal}", onClick = onDiet, weight = 1.45f) {
                Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MacroRing("탄", total.carb, goal.carb, c.primary)
                    MacroRing("단", total.protein, goal.protein, c.lime)
                    MacroRing("지", total.fat, goal.fat, c.warn)
                }
            }
            GlanceDonut(cups.toFloat() / WATER_GOAL_CUPS, "$cups/$WATER_GOAL_CUPS", "물 잔", done = cups >= WATER_GOAL_CUPS, onClick = onDiet, action = { app.addWater(0) }, actionLabel = "물 한 잔 더")
        }
    }
}

/** 탄·단·지 작은 링 — 수치 없이 채움만(16 dp), 왼쪽에 한 글자. 색은 식단 탭 막대와 같다(탄 primary · 단 lime · 지 warn). */
@Composable
private fun MacroRing(letter: String, value: Double, goal: Double, color: Color) {
    val c = Trex.c
    val p = if (goal > 0) (value / goal).toFloat().coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { contentDescription = "$letter ${value.toInt()} / ${goal.toInt()} g" }) {
        Text(letter, color = c.text3, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.size(16.dp)) {
            val sw = 3.dp.toPx()
            val arc = Size(size.width - sw, size.height - sw); val o = Offset(sw / 2, sw / 2)
            drawArc(c.track, -90f, 360f, false, o, arc, style = Stroke(sw))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, o, arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

/**
 * 도넛 하나 — 64 dp 링, 가운데 숫자 13 sp(+[sub] 9 sp), 아래 라벨. [done] 이면 가운데가 체크. [action] 이 있으면 오른쪽 아래 작은 + 배지(별도 탭 대상).
 * [side] 는 링 오른쪽에 붙는 내용(탄·단·지 작은 링).
 */
@Composable
private fun RowScope.GlanceDonut(progress: Float, center: String, label: String, sub: String? = null, done: Boolean = false, onClick: () -> Unit,
    action: (() -> Unit)? = null, actionLabel: String = "", weight: Float = 1f, side: (@Composable () -> Unit)? = null) {
    val c = Trex.c
    Column(Modifier.weight(weight).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                RingGauge(progress, 64.dp, 7.dp) {
                    if (done) Icon(Icons.Rounded.Check, null, tint = c.primaryText, modifier = Modifier.size(22.dp))
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(center, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (sub != null) Text(sub, color = c.text3, fontSize = 8.5.sp, maxLines = 1)
                    }
                }
                if (action != null) Surface(onClick = action, shape = CircleShape, color = c.primary, modifier = Modifier.align(Alignment.BottomEnd).size(22.dp).semantics { contentDescription = actionLabel }) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = c.bg, modifier = Modifier.size(14.dp)) }
                }
            }
            // 옆 내용은 링 높이(64 dp) 안에 가둔다 — 라벨 줄이 다른 도넛과 어긋나지 않게(11-07 실기기)
            if (side != null) Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) { side() }
        }
        Text(label, color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** 좁은 창과 큰 글씨에서는 카드 자체를 세로로 바꿔 버튼과 숫자를 자르지 않는다. */
@Composable
internal fun HomeActionCards(overview: RoutineOverview, onWorkout: () -> Unit, onMeal: () -> Unit, routineName: String? = null) {
    val c = Trex.c
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 330.dp || LocalDensity.current.fontScale > 1.3f
        val workout: @Composable (Modifier) -> Unit = { modifier ->
            Surface(onClick = onWorkout, shape = RoundedCornerShape(25.dp), color = c.primary, modifier = modifier) {
                Box(Modifier.heightIn(min = 196.dp)) {
                    AssetArtwork("routine/${overview.focus.image}.png", Modifier.matchParentSize(), ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x18385027), Color(0xAB385027), Color(0xF5385027)))))
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // '루틴 바꾸기' 글자는 뺐다(사용자 요청 2026-11-07) — 주간 루틴은 운동 탭의 달력 아이콘으로 간다
                        Text("오늘 운동", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
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

/**
 * 홈 아래 두 칸(§131 P18 → §132 → UI 감사 A3) — 왼쪽 **연속 운동**(목표 요일 기준, 누르면 운동 기록), 오른쪽 **근육 피로도**(가장 피로한 부위를 글리프에 색칠,
 * 누르면 근육 피로도 화면). 물은 '오늘 한눈' 띠(A1)로 올라갔고(+ 포함), 볼륨은 기록 화면 통계에 있다.
 */
@Composable
internal fun HomeActivityCards(app: AppViewModel, onOpenRecord: () -> Unit = {}, onOpenMuscle: () -> Unit = {}) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(onClick = onOpenRecord, shape = RoundedCornerShape(24.dp), color = c.surface, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(18.dp)) {
                Text("연속 운동", color = c.text2, fontSize = 12.sp)
                Text("${app.attendanceStreak()}일", color = c.text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text("목표 요일 기준", color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    (6 downTo 0).forEach { offset ->
                        val did = app.workoutHistory.any { it.epochDay == app.calendarDay - offset && it.items.isNotEmpty() }
                        Box(Modifier.weight(1f).height(15.dp).clip(RoundedCornerShape(4.dp)).background(if (did) c.primary else c.track))
                    }
                }
            }
        }
        val peak = app.muscleLoad.peak
        Surface(onClick = onOpenMuscle, shape = RoundedCornerShape(24.dp), color = c.surface, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(18.dp)) {
                Text("근육 피로도", color = c.text2, fontSize = 12.sp)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 가장 피로한 근육을 실루엣에 색칠(UI 감사 A3, K6) — 글을 읽기 전에 '어디' 가 보인다. 피로 = 경고 계열(A25 색 = 뜻)
                    MuscleGlyph(peak?.let { muscleKeys(listOf(it.muscle)) } ?: emptySet(), 48.dp, highlight = c.warn, description = peak?.let { "${it.muscle.label} 피로" } ?: "근육 피로도")
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(peak?.let { "${it.value.toInt()}점" } ?: "—", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(when { app.muscleLoadLoading -> "불러오는 중"; app.muscleLoadError != null -> "다시 불러오기"; peak == null -> "기록이 쌓이면"; else -> peak.muscle.label },
                            color = c.text2, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Text("기록 기반 추정", color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
