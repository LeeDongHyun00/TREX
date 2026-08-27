package com.example.trex_kotlin

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 메인 하단 시트 라우터 (리디자인). */
@Composable
fun MainSheetHost(app: AppViewModel, sheet: MainSheet, onClose: () -> Unit) {
    when (sheet) {
        is MainSheet.Alt -> AltSheet(app, sheet.workout, onClose)
        is MainSheet.Sets -> SetsSheet(app, sheet.draft, onClose)
        MainSheet.Goals -> GoalsSheet(app, onClose)
        is MainSheet.Manual -> ManualSheet(app, sheet.slot, onClose)
        MainSheet.Photo -> PhotoSheet(app, onClose)
        MainSheet.AddWorkout -> AddWorkoutSheet(app, onClose)
    }
}

@Composable
private fun SheetTitleRow(kicker: String, title: String, onClose: () -> Unit) {
    val c = Trex.c
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Kicker(kicker, color = c.primaryText)
            Text(title, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        }
        SheetClose(onClose)
    }
}

// ============================================================= 대체 운동

private val altFallbacks = mapOf(
    "하체" to listOf(WorkoutAlt("글루트 브릿지", "12회 × 3세트"), WorkoutAlt("카프 레이즈", "15회 × 3세트")),
    "코어" to listOf(WorkoutAlt("버드독", "10회 × 3세트"), WorkoutAlt("사이드 플랭크", "30초 × 3세트")),
    "복근" to listOf(WorkoutAlt("데드버그", "12회 × 3세트"), WorkoutAlt("사이드 플랭크", "30초 × 3세트")),
    "상체" to listOf(WorkoutAlt("니 푸쉬업", "10회 × 3세트"), WorkoutAlt("밴드 로우", "12회 × 3세트")),
    "유산소" to listOf(WorkoutAlt("제자리 걷기", "60초 × 4세트"), WorkoutAlt("스텝업", "12회 × 3세트")),
    "회복" to listOf(WorkoutAlt("캣카우 스트레칭", "전신 5분"), WorkoutAlt("차일드 포즈", "전신 4분")),
)

/** 대체 운동 칩의 첫 항목 — 카테고리가 아니라 "추천 묶음"을 고르는 자리다. */
private const val ALT_RECOMMEND_TAB = "추천"

@Composable
private fun AltSheet(app: AppViewModel, workout: Workout, onClose: () -> Unit) {
    val c = Trex.c
    val tabs = remember { listOf(ALT_RECOMMEND_TAB) + workoutCatalog.keys }

    // 추천 = 이 운동에 붙어 있는 대체안 + 같은 카테고리 대안. 지금 하고 있는 운동은 뺀다.
    val recommended = remember(workout.name, workout.category, workout.alt) {
        buildList {
            workout.alt?.let(::add)
            addAll(altFallbacks[workout.category].orEmpty())
        }.filter { it.name != workout.name }.distinctBy { it.name }
    }
    // 추천할 게 없으면(직접 추가한 종목 등) 같은 카테고리 목록부터 보여준다
    var tab by remember(workout.id) {
        mutableStateOf(
            when {
                recommended.isNotEmpty() -> ALT_RECOMMEND_TAB
                workout.category in workoutCatalog -> workout.category
                else -> tabs[1]
            },
        )
    }
    val picks: List<AltPick> = remember(tab, workout.name, recommended) {
        if (tab == ALT_RECOMMEND_TAB) {
            recommended.mapIndexed { i, alt ->
                val template = catalogByName[alt.name]
                AltPick(
                    name = alt.name,
                    reps = alt.reps,
                    duration = template?.duration,
                    best = i == 0,
                    postureReady = postureExerciseMap.containsKey(alt.name),
                )
            }
        } else {
            workoutCatalog[tab].orEmpty()
                .filter { it.name != workout.name }
                .map { AltPick(it.name, it.reps, it.duration, best = false, postureReady = it.posture) }
        }
    }

    SheetHost(onDismiss = onClose) {
        // 탭마다 목록 길이가 달라 높이를 고정하면 "추천"에서 빈 공간이 크게 남는다 —
        // 내용에 맞춰 늘었다 줄었다 하되 그 변화는 부드럽게 이어준다.
        Column(
            Modifier
                .animateContentSize(tween(280))
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 20.dp),
        ) {
            SheetTitleRow("운동 교체", workout.name, onClose)
            Text(
                "${workout.category} · ${workout.reps} 대신 할 운동을 골라봐룡",
                color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(Modifier.height(14.dp))
            FilterChipRow(options = tabs, selected = tab, onSelect = { tab = it })

            LazyColumn(
                Modifier.padding(top = 12.dp).heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (picks.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "여기엔 바꿀 만한 운동이 없어룡", color = c.text3, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp), textAlign = TextAlign.Center,
                        )
                    }
                }
                items(count = picks.size, key = { picks[it].name }) { i ->
                    val pick = picks[i]
                    AltPickRow(
                        pick = pick,
                        onPick = {
                            app.updatePlan(
                                app.workoutPlan.map {
                                    if (it.id == workout.id) it.replacedWith(pick.name, pick.reps) else it
                                },
                            )
                            onClose()
                        },
                    )
                }
            }
            GhostButton("그대로 유지", onClick = onClose, modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
        }
    }
}

/** 교체 후보 한 줄에 필요한 것만 모은 표시용 모델. */
private data class AltPick(
    val name: String,
    val reps: String,
    val duration: String?,
    val best: Boolean,
    val postureReady: Boolean,
)

@Composable
private fun AltPickRow(pick: AltPick, onPick: () -> Unit) {
    val c = Trex.c
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        contentColor = c.text,
        border = BorderStroke(1.dp, if (pick.best) c.primarySoftLine else c.line),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (pick.best) c.primary else c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.FitnessCenter, contentDescription = null,
                    tint = if (pick.best) Color.White else c.primaryText, modifier = Modifier.size(17.dp),
                )
            }
            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                Text(pick.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pick.reps + (pick.duration?.let { " · $it" } ?: ""),
                        color = c.text3, fontSize = 11.sp,
                    )
                    if (pick.postureReady) {
                        Text(
                            "자세교정", color = c.primaryText, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.primaryWash)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (pick.best) {
                Text(
                    "가장 추천", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.primary).padding(horizontal = 9.dp, vertical = 5.dp),
                )
            } else {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = "교체", tint = c.primaryText, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/**
 * 대체 운동으로 갈아끼우기.
 *
 * 이름/반복만 바꾸면 시간·카테고리·자세교정 플래그가 이전 종목 값으로 남는다.
 * 카탈로그에 있는 종목이면 그 값까지 함께 맞추고, 자세 평가는 규칙 엔진이 지원할 때만 유지한다.
 */
private fun Workout.replacedWith(name: String, reps: String): Workout {
    val template = catalogByName[name]
    return copy(
        name = name,
        reps = reps,
        duration = template?.duration ?: duration,
        category = template?.category ?: category,
        posture = posture && postureExerciseMap.containsKey(name),
    )
}

// ============================================================= 세트 수정

@Composable
private fun SetsSheet(app: AppViewModel, initial: SetDraft, onClose: () -> Unit) {
    val c = Trex.c
    var draft by remember { mutableStateOf(initial) }
    val step = if (draft.unit == "초") 5 else 1
    val minCount = if (draft.unit == "초") 10 else 1
    val summary = if (draft.sets > 0) {
        val per = if (draft.unit == "초") draft.count else draft.count * 3
        "예상 소요 약 ${((per * draft.sets + 45 * draft.sets) / 60).coerceAtLeast(1)}분 · 세트 사이 45초 휴식"
    } else {
        "예상 소요 약 ${draft.count}분"
    }

    SheetHost(onDismiss = onClose) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            SheetHandle()
            Row(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("세트 수정")
                    Text(draft.name, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                }
                SheetClose(onClose)
            }

            DCard(radius = 24.dp) {
                Column {
                    val rows = buildList {
                        add(Triple("count", if (draft.unit == "분") "시간" else "반복", if (draft.unit == "분") "1분 단위로 조절" else if (draft.unit == "초") "5초 단위로 조절" else "1회 단위로 조절"))
                        if (draft.sets > 0) add(Triple("sets", "세트", "1세트 단위로 조절"))
                    }
                    rows.forEachIndexed { i, (key, label, hint) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(hint, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            val isCount = key == "count"
                            StepperControl(
                                valueLabel = if (isCount) "${draft.count}${draft.unit}" else "${draft.sets}세트",
                                onDec = {
                                    draft = if (isCount) draft.copy(count = (draft.count - step).coerceAtLeast(minCount))
                                    else draft.copy(sets = (draft.sets - 1).coerceAtLeast(1))
                                },
                                onInc = {
                                    draft = if (isCount) draft.copy(count = draft.count + step)
                                    else draft.copy(sets = draft.sets + 1)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            WashBanner(summary, Icons.Rounded.Timer)

            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "되돌리기", color = c.text3, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { draft = initial }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
                Cta(
                    "저장",
                    icon = Icons.Rounded.Check,
                    onClick = {
                        val reps = if (draft.sets > 0) "${draft.count}${draft.unit} × ${draft.sets}세트" else "전신 ${draft.count}분"
                        val per = if (draft.unit == "초") draft.count else draft.count * 3
                        val duration = if (draft.sets > 0) "${((per * draft.sets + 45 * draft.sets) / 60).coerceAtLeast(1)}분" else "${draft.count}분"
                        app.updatePlan(app.workoutPlan.map { if (it.id == draft.id) it.copy(reps = reps, duration = duration) else it })
                        onClose()
                    },
                    height = 52.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ============================================================= 영양 목표

@Composable
private fun GoalsSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val g = app.targetGoal
    SheetHost(onDismiss = onClose) {
        Column(Modifier.padding(20.dp)) {
            SheetTitleRow("영양 목표 수정", "하루 목표를 맞춰봐룡", onClose)
            Spacer(Modifier.height(16.dp))
            DCard(radius = 22.dp) {
                Column {
                    data class GoalRow(val label: String, val hint: String, val unit: String, val v: Int, val onSet: (Int) -> Unit, val stepN: Int)
                    val rows = listOf(
                        GoalRow("하루 칼로리", "50 kcal 단위", "kcal", g.kcal, { app.setTargetGoal(g.copy(kcal = it)) }, 50),
                        GoalRow("탄수화물", "5g 단위", "g", g.carb.toInt(), { app.setTargetGoal(g.copy(carb = it.toDouble())) }, 5),
                        GoalRow("단백질", "5g 단위", "g", g.protein.toInt(), { app.setTargetGoal(g.copy(protein = it.toDouble())) }, 5),
                        GoalRow("지방", "5g 단위", "g", g.fat.toInt(), { app.setTargetGoal(g.copy(fat = it.toDouble())) }, 5),
                    )
                    rows.forEachIndexed { i, row ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(row.hint, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            StepperControl(
                                valueLabel = "${row.v}",
                                onDec = { row.onSet((row.v - row.stepN).coerceAtLeast(0)) },
                                onInc = { row.onSet(row.v + row.stepN) },
                                valueMinWidth = 56.dp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            WashBanner("탄단지 합이 칼로리 목표와 크게 다르면 알려줄게룡", Icons.Rounded.Info)
            Cta("목표 저장", icon = Icons.Rounded.Check, onClick = onClose, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
        }
    }
}

// ============================================================= 직접 기록 (수량 스테퍼 + 검색)

@Composable
private fun ManualSheet(app: AppViewModel, initialSlot: String, onClose: () -> Unit) {
    val c = Trex.c
    var slot by remember { mutableStateOf(initialSlot) }
    var query by remember { mutableStateOf("") }
    val slotFoods = app.dietFor(0)[slot].orEmpty()
    val total = slotFoods.totalNutrition()
    val goal = app.targetGoal
    val slotIndex = mealMetas.indexOfFirst { it.id == slot }.coerceAtLeast(0)
    val matches = foodDatabase.keys.filter { it.contains(query.trim()) }

    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            SheetHandle()
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("직접 기록")
                    Text("${mealMetas[slotIndex].label} 기록", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                }
                SheetClose(onClose)
            }
            Box(Modifier.padding(horizontal = 20.dp)) {
                SegmentedTabs(
                    options = mealMetas.map { it.label },
                    selected = slotIndex,
                    onSelect = { slot = mealMetas[it].id },
                    height = 38.dp,
                    filled = true,
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 16.dp),
            ) {
                DCard(radius = 24.dp) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f)) {
                                Kicker("이 끼니 합계")
                                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                                    Text("${total.kcal}", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
                                    Text(" kcal", color = c.text3, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                                }
                            }
                            Text("음식 ${slotFoods.sumOf { it.qty }}개", color = c.text3, fontSize = 11.5.sp)
                        }
                        Column(Modifier.padding(top = 15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MacroBar("탄수", total.carb.toInt(), goal.carb.toInt(), c.primary)
                            MacroBar("단백질", total.protein.toInt(), goal.protein.toInt(), c.lime)
                            MacroBar("지방", total.fat.toInt(), goal.fat.toInt(), c.warn)
                        }
                    }
                }

                if (slotFoods.isEmpty()) {
                    Column(
                        Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(c.surface)
                            .border(1.dp, c.fieldLine, RoundedCornerShape(22.dp))
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.RestaurantMenu, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
                        }
                        Text("아래에서 음식을 골라 담아보세룡", color = c.text2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 11.dp))
                    }
                } else {
                    DCard(modifier = Modifier.padding(top = 16.dp), radius = 22.dp) {
                        Column {
                            slotFoods.forEachIndexed { i, f ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${f.nutrition.kcal * f.qty} kcal · 탄 ${(f.nutrition.carb * f.qty).toInt()} · 단 ${(f.nutrition.protein * f.qty).toInt()} · 지 ${(f.nutrition.fat * f.qty).toInt()}",
                                            color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                    StepperControl(
                                        valueLabel = "${f.qty}",
                                        onDec = { app.changeFoodQty(0, slot, i, -1) },
                                        onInc = { app.changeFoodQty(0, slot, i, +1) },
                                        decIcon = if (f.qty > 1) Icons.Rounded.Remove else Icons.Rounded.Delete,
                                        valueMinWidth = 24.dp,
                                    )
                                }
                            }
                        }
                    }
                }

                // 검색 + 음식 DB
                Box(Modifier.padding(top = 18.dp)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(c.primary),
                        decorationBox = { inner ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(c.field)
                                    .border(1.dp, c.fieldLine, RoundedCornerShape(15.dp))
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = c.text3, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(9.dp))
                                Box(Modifier.weight(1f)) {
                                    if (query.isBlank()) Text("음식 이름 검색", color = c.text3, fontSize = 13.5.sp)
                                    inner()
                                }
                            }
                        },
                    )
                }
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (matches.isEmpty()) {
                        Text(
                            "검색 결과가 없어룡", color = c.text3, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
                        )
                    }
                    matches.forEach { name ->
                        val n = foodDatabase.getValue(name)
                        val added = slotFoods.any { it.name == name }
                        Surface(
                            onClick = { app.appendFoods(0, slot, listOf(FoodEntry(name, n))) },
                            shape = RoundedCornerShape(18.dp),
                            color = c.surface,
                            contentColor = c.text,
                            border = BorderStroke(1.dp, c.line),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${n.kcal} kcal · 탄 ${n.carb.toInt()} · 단 ${n.protein.toInt()} · 지 ${n.fat.toInt()}",
                                        color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(if (added) c.primary else c.surface2),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                                        contentDescription = "담기",
                                        tint = if (added) Color.White else c.primaryText,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 고정 하단 저장 바
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "모두 비우기", color = c.text3, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { app.clearSlot(0, slot) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
                Cta("기록 완료", icon = Icons.Rounded.Check, onClick = onClose, height = 52.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ============================================================= 사진 식단 기록

private val photoDetected = listOf(
    Triple(FoodEntry("현미밥", Nutrition(220, 46.0, 5.0, 1.7)), 96, "220 kcal · 탄수 46g · 단백질 5g · 지방 1.7g"),
    Triple(FoodEntry("닭가슴살", Nutrition(165, 0.0, 31.0, 3.6)), 93, "165 kcal · 탄수 0g · 단백질 31g · 지방 3.6g"),
    Triple(FoodEntry("샐러드", Nutrition(120, 8.0, 4.0, 7.0)), 88, "120 kcal · 탄수 8g · 단백질 4g · 지방 7g"),
)

@Composable
private fun PhotoSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    var stage by remember { mutableStateOf("choose") }

    LaunchedEffect(stage) {
        if (stage == "analyzing") {
            delay(1800)
            stage = "result"
        }
    }

    SheetHost(onDismiss = onClose) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            SheetTitleRow(
                kicker = when (stage) {
                    "result" -> "분석 완료"
                    "analyzing" -> "AI 분석 중"
                    else -> "사진 식단 기록"
                },
                title = when (stage) {
                    "result" -> "3가지 음식을 찾았어룡"
                    "analyzing" -> "잠시만 기다려주세룡"
                    else -> "사진으로 빠르게"
                },
                onClose = onClose,
            )

            when (stage) {
                "choose" -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(
                        onClick = { stage = "analyzing" },
                        shape = RoundedCornerShape(20.dp),
                        color = c.primary,
                        contentColor = Color.White,
                        shadowElevation = 5.dp,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(19.dp))
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("사진 찍기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("카메라로 바로 촬영", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    Surface(
                        onClick = { stage = "analyzing" },
                        shape = RoundedCornerShape(20.dp),
                        color = c.surface,
                        contentColor = c.text,
                        border = BorderStroke(1.dp, c.line),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Image, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(19.dp))
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("갤러리에서 선택", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("앨범에서 음식 사진 가져오기", fontSize = 11.sp, color = c.text3, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    WashBanner("여러 음식이 한 접시에 있어도 자동으로 분리해서 인식해룡", Icons.Rounded.AutoAwesome)
                }

                "analyzing" -> Column(Modifier.padding(top = 16.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(c.stripeA),
                    ) {
                        Text(
                            "food photo", color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(c.sheet.copy(alpha = 0.92f)).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c.primary))
                                Text("음식을 인식하고 있어룡…", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                            LinearProgressIndicator(
                                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                                color = c.primary,
                                trackColor = c.track,
                            )
                        }
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("인식", "분류", "영양 계산").forEachIndexed { i, label ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.surface2)
                                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("${i + 1}단계", color = c.text3, fontSize = 10.sp)
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }

                else -> Column(Modifier.padding(top = 16.dp)) {
                    val totalK = photoDetected.sumOf { it.first.nutrition.kcal }
                    DCard(radius = 24.dp) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Kicker("총 칼로리")
                                    Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.Bottom) {
                                        Text("$totalK", color = c.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
                                        Text(" kcal", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                                    }
                                }
                                WashPill(mealMetas.first { it.id == currentMealId() }.label)
                            }
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "탄수" to photoDetected.sumOf { it.first.nutrition.carb }.toInt(),
                                    "단백질" to photoDetected.sumOf { it.first.nutrition.protein }.toInt(),
                                    "지방" to photoDetected.sumOf { it.first.nutrition.fat }.toInt(),
                                ).forEach { (label, v) ->
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(9.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(label, color = c.text3, fontSize = 10.sp)
                                        Text("${v}g", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                                    }
                                }
                            }
                        }
                    }
                    Text("인식된 음식", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        photoDetected.forEach { (food, conf, detail) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(c.surface)
                                    .border(1.dp, c.line, RoundedCornerShape(16.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(food.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(7.dp))
                                        WashPill("$conf%")
                                    }
                                    Text(detail, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                                Box(Modifier.size(26.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                    Cta(
                        "식단에 저장",
                        icon = Icons.Rounded.Check,
                        onClick = {
                            app.appendFoods(0, currentMealId(), photoDetected.map { it.first })
                            onClose()
                        },
                        modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ============================================================= 운동 추가

private data class WorkoutTemplate(val name: String, val reps: String, val duration: String, val category: String, val posture: Boolean)

/**
 * 운동 카탈로그 — posture 플래그는 규칙 엔진이 실제로 지원하는 종목(postureExerciseMap)에만 켠다.
 * 지원 종목은 rules_mp_v0(서서 하는 종목) + rules_floor_v0.1(바닥 종목, 전부 beta) 기준이다.
 * 바이시클 크런치는 MP 충실도 게이트(spec §25a) 후 남은 규칙이 없어 posture=false.
 */
private val workoutCatalog = mapOf(
    "하체" to listOf(
        WorkoutTemplate("기본 스쿼트", "12회 × 3세트", "8분", "하체", true),
        WorkoutTemplate("바벨 스쿼트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("바벨 런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("사이드 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("크로스 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("바벨 데드리프트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("굿모닝", "12회 × 3세트", "8분", "하체", true),
        WorkoutTemplate("힙 쓰러스트", "12회 × 3세트", "8분", "하체", true),
        WorkoutTemplate("불가리안 스플릿 스쿼트", "10회 × 3세트", "9분", "하체", false),
        WorkoutTemplate("글루트 브릿지", "12회 × 3세트", "7분", "하체", false),
        WorkoutTemplate("월 싯", "45초 × 3세트", "6분", "하체", false),
        WorkoutTemplate("카프 레이즈", "15회 × 3세트", "6분", "하체", false),
    ),
    "상체" to listOf(
        WorkoutTemplate("오버헤드 프레스", "10회 × 3세트", "9분", "상체", true),
        WorkoutTemplate("랫풀 다운", "12회 × 3세트", "8분", "상체", true),
        WorkoutTemplate("딥스", "10회 × 3세트", "8분", "상체", true),
        WorkoutTemplate("덤벨 컬", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("바벨 컬", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("사이드 레터럴 레이즈", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("프런트 레이즈", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("업라이트로우", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("푸쉬업", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("니 푸쉬업", "10회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("Y 레이즈", "12회 × 3세트", "6분", "상체", true),
        WorkoutTemplate("인클라인 푸쉬업", "12회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("벽 푸쉬업", "12회 × 3세트", "6분", "상체", false),
        WorkoutTemplate("밴드 로우", "12회 × 3세트", "8분", "상체", false),
    ),
    "코어" to listOf(
        WorkoutTemplate("플랭크", "45초 × 3세트", "6분", "코어", true),
        WorkoutTemplate("사이드 플랭크", "30초 × 3세트", "6분", "코어", false),
        WorkoutTemplate("플랭크 숄더탭", "16회 × 3세트", "7분", "코어", false),
        WorkoutTemplate("버드독", "10회 × 3세트", "7분", "코어", false),
        WorkoutTemplate("데드버그", "12회 × 3세트", "7분", "코어", false),
        WorkoutTemplate("할로우 홀드", "30초 × 3세트", "6분", "코어", false),
        WorkoutTemplate("힙 브릿지 홀드", "40초 × 3세트", "6분", "코어", false),
    ),
    "복근" to listOf(
        WorkoutTemplate("스탠딩 사이드 크런치", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("스탠딩 니업", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("행잉 레그 레이즈", "10회 × 3세트", "8분", "복근", true),
        WorkoutTemplate("크런치", "15회 × 3세트", "6분", "복근", true),
        WorkoutTemplate("리버스 크런치", "12회 × 3세트", "6분", "복근", false),
        WorkoutTemplate("바이시클 크런치", "20회 × 3세트", "7분", "복근", false),
        WorkoutTemplate("레그 레이즈", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("시저 크로스", "20회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("러시안 트위스트", "20회 × 3세트", "7분", "복근", false),
    ),
    "유산소" to listOf(
        WorkoutTemplate("제자리 걷기", "60초 × 4세트", "8분", "유산소", false),
        WorkoutTemplate("하이 니", "30초 × 4세트", "7분", "유산소", false),
        WorkoutTemplate("마운틴 클라이머", "20회 × 3세트", "8분", "유산소", false),
        WorkoutTemplate("점핑잭", "30회 × 3세트", "7분", "유산소", false),
        WorkoutTemplate("스텝업", "12회 × 3세트", "9분", "유산소", false),
        WorkoutTemplate("스키터 점프", "20회 × 3세트", "7분", "유산소", false),
        WorkoutTemplate("섀도 복싱", "60초 × 3세트", "8분", "유산소", false),
        WorkoutTemplate("버피", "10회 × 3세트", "8분", "유산소", false),
    ),
    "회복" to listOf(
        WorkoutTemplate("마무리 스트레칭", "전신 6분", "6분", "회복", false),
        WorkoutTemplate("캣카우 스트레칭", "전신 5분", "5분", "회복", false),
        WorkoutTemplate("차일드 포즈", "전신 4분", "4분", "회복", false),
        WorkoutTemplate("폼롤러 마무리", "전신 5분", "5분", "회복", false),
        WorkoutTemplate("햄스트링 스트레칭", "전신 5분", "5분", "회복", false),
        WorkoutTemplate("흉추 회전 스트레칭", "전신 4분", "4분", "회복", false),
    ),
)

/** 이름으로 카탈로그를 찾는다 — 대체 운동으로 갈아끼울 때 시간/카테고리까지 함께 맞추려고 쓴다. */
private val catalogByName: Map<String, WorkoutTemplate> =
    workoutCatalog.values.flatten().associateBy { it.name }

@Composable
private fun AddWorkoutSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val categories = remember { workoutCatalog.keys.toList() }
    var category by remember { mutableStateOf(categories.first()) }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxHeight(0.86f).padding(20.dp)) {
            SheetTitleRow("운동 추가", "카테고리에서 선택", onClose)
            Spacer(Modifier.height(14.dp))
            // 카테고리가 6개라 세그먼트로는 좁다 — 대체 운동 시트와 같은 필터 칩을 쓴다
            FilterChipRow(options = categories, selected = category, onSelect = { category = it })
            LazyColumn(
                Modifier.padding(top = 14.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val templates = workoutCatalog[category].orEmpty()
                items(count = templates.size) { i ->
                    val t = templates[i]
                    Surface(
                        onClick = {
                            app.updatePlan(
                                app.workoutPlan + Workout(
                                    id = "custom-${System.currentTimeMillis()}-${t.name.hashCode()}",
                                    name = t.name, reps = t.reps, duration = t.duration,
                                    posture = t.posture, category = t.category,
                                ),
                            )
                            onClose()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = c.surface,
                        contentColor = c.text,
                        border = BorderStroke(1.dp, c.line),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(17.dp))
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${t.reps} · ${t.duration}", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            Box(Modifier.size(28.dp).clip(CircleShape).background(c.primaryWash), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Add, contentDescription = "추가", tint = c.primaryText, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
