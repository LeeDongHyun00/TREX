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
import com.example.trex_kotlin.TrexText as Text
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
        is MainSheet.WorkoutEditor -> WorkoutEditorSheet(app, sheet.selectedId, onClose)
        is MainSheet.Alt -> WorkoutEditorSheet(app, sheet.workout.id, onClose, initialMode = "replace")
        is MainSheet.Sets -> WorkoutEditorSheet(app, sheet.draft.id, onClose)
        MainSheet.Goals -> GoalsSheet(app, onClose)
        is MainSheet.Manual -> ManualSheet(app, sheet.slot, onClose)
        MainSheet.Photo -> PhotoSheet(app, onClose)
        MainSheet.AddWorkout -> WorkoutEditorSheet(app, null, onClose, initialMode = "add")
        MainSheet.ProfileSettings -> ProfileSettingsSheet(app, onClose)
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

private val altFallbacks get() = workoutCatalog.mapValues { (_, entries) ->
    entries.map { WorkoutAlt(it.name, it.reps) }
}

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
        }.filter { it.name != workout.name && it.name in catalogByName }.distinctBy { it.name }
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
        secondsPerRep = null,
        restSeconds = null,
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
        val per = when (draft.unit) { "초" -> draft.count; "분" -> draft.count * 60; else -> draft.count * draft.secondsPerRep }
        "1세트 ${per.asClock()} · 총 ${(per * draft.sets + draft.restSeconds * (draft.sets - 1)).asClock()}" +
            if (draft.sets > 1) " · 세트 사이 ${draft.restSeconds}초 휴식" else ""
    } else {
        "예상 소요 약 ${draft.count}분"
    }

    SheetHost(onDismiss = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            SheetHandle()
            Row(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("운동 설정")
                    Text(draft.name, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                }
                SheetClose(onClose)
            }

            DCard(radius = 24.dp) {
                Column {
                    val rows = buildList {
                        add(Triple("count", if (draft.unit == "분") "시간" else "반복", if (draft.unit == "분") "1분 단위로 조절" else if (draft.unit == "초") "5초 단위로 조절" else "1회 단위로 조절"))
                        if (draft.sets > 0) add(Triple("sets", "세트", "1세트 단위로 조절"))
                        if (draft.unit == "회") add(Triple("pace", "1회 소요 시간", "횟수 × 이 시간으로 세트 타이머를 계산해요"))
                        if (draft.sets > 1) add(Triple("rest", "세트 사이 휴식", "0초면 바로 다음 세트로 이어져요"))
                    }
                    rows.forEachIndexed { i, (key, label, hint) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(hint, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            StepperControl(
                                valueLabel = when(key){"count"->"${draft.count}${draft.unit}";"sets"->"${draft.sets}세트";"pace"->"${draft.secondsPerRep}초";else->"${draft.restSeconds}초"},
                                onDec = {
                                    draft = when(key){
                                        "count" -> draft.copy(count=(draft.count-step).coerceAtLeast(minCount))
                                        "sets" -> draft.copy(sets=(draft.sets-1).coerceAtLeast(1))
                                        "pace" -> draft.copy(secondsPerRep=(draft.secondsPerRep-1).coerceAtLeast(1))
                                        else -> draft.copy(restSeconds=(draft.restSeconds-15).coerceAtLeast(0))
                                    }
                                },
                                onInc = {
                                    draft = when(key){
                                        "count" -> draft.copy(count=(draft.count+step).coerceAtMost(if(draft.unit=="분")60 else if(draft.unit=="초")3600 else 999))
                                        "sets" -> draft.copy(sets=(draft.sets+1).coerceAtMost(20))
                                        "pace" -> draft.copy(secondsPerRep=(draft.secondsPerRep+1).coerceAtMost(15))
                                        else -> draft.copy(restSeconds=(draft.restSeconds+15).coerceAtMost(600))
                                    }
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
                        app.updatePlan(app.workoutPlan.map { if (it.id == draft.id) {
                            val updated=it.copy(reps=reps,secondsPerRep=draft.secondsPerRep,restSeconds=draft.restSeconds)
                            updated.copy(duration="${updated.timing().minutes}분")
                        } else it })
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GoalsSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    var goal by remember { mutableStateOf(app.targetGoal) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = c.sheet,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("영양 목표 수정", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
            data class GoalRow(val label: String, val value: Int, val step: Int, val unit: String, val update: (Int) -> Unit)
            listOf(
                GoalRow("하루 칼로리", goal.kcal, 50, "kcal") { goal = goal.copy(kcal = it.coerceIn(800, 5000)) },
                GoalRow("탄수화물", goal.carb.toInt(), 5, "g") { goal = goal.copy(carb = it.coerceIn(0, 800).toDouble()) },
                GoalRow("단백질", goal.protein.toInt(), 5, "g") { goal = goal.copy(protein = it.coerceIn(0, 400).toDouble()) },
                GoalRow("지방", goal.fat.toInt(), 5, "g") { goal = goal.copy(fat = it.coerceIn(0, 250).toDouble()) },
            ).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.label, color = c.text, fontSize = 16.sp)
                        Text(row.unit, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    StepperControl("${row.value}", { row.update(row.value - row.step) }, { row.update(row.value + row.step) }, label = row.label, valueMinWidth = 62.dp)
                }
            }
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("취소", onClose, Modifier.weight(1f))
                Cta("목표 저장", { app.setTargetGoal(goal); onClose() }, modifier = Modifier.weight(1.7f))
            }
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

/** 분석기가 연결되기 전에는 고정 음식·정확도를 분석 결과처럼 저장하지 않는다. */
@Composable
private fun PhotoSheet(app: AppViewModel, onClose: () -> Unit) {
    var manual by remember { mutableStateOf(false) }
    if (manual) {
        ManualSheet(app, currentMealId(), onClose)
        return
    }
    val c = Trex.c
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(22.dp).verticalScroll(rememberScrollState())) {
            SheetTitleRow("사진 식단 기록", "직접 기록으로 이어가룡", onClose)
            Text("사진 분석은 아직 연결되지 않았어룡. 먹은 음식을 직접 선택해 기록해 주세룡.",
                color = c.text2, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 22.dp))
            Cta("음식 직접 선택", { manual = true }, Modifier.fillMaxWidth())
        }
    }
}

// ============================================================= 운동 추가

internal data class WorkoutTemplate(val name: String, val reps: String, val duration: String, val category: String, val posture: Boolean)

/**
 * 운동 카탈로그 — posture 플래그는 규칙 엔진이 실제로 지원하는 종목(postureExerciseMap)에만 켠다.
 * 지원 종목은 rules_mp_v0(서서 하는 종목) + rules_floor_v0.1(바닥 종목, 전부 beta) 기준이다.
 * 바이시클 크런치는 MP 충실도 게이트(spec §25a) 후 남은 규칙이 없어 posture=false.
 */
internal val workoutCatalog = mapOf(
    "하체" to listOf(
        WorkoutTemplate("바벨 스쿼트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("바벨 런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("사이드 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("크로스 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("바벨 데드리프트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("굿모닝", "12회 × 3세트", "8분", "하체", true),
        WorkoutTemplate("힙 쓰러스트", "12회 × 3세트", "8분", "하체", true),
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
    ),
    "코어" to listOf(
        WorkoutTemplate("플랭크", "45초 × 3세트", "6분", "코어", true),
    ),
    "복근" to listOf(
        WorkoutTemplate("스탠딩 사이드 크런치", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("스탠딩 니업", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("행잉 레그 레이즈", "10회 × 3세트", "8분", "복근", true),
        WorkoutTemplate("크런치", "15회 × 3세트", "6분", "복근", true),
        WorkoutTemplate("레그 레이즈", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("시저 크로스", "20회 × 3세트", "7분", "복근", true),
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
                                Text("${t.reps} · ${Workout("preview",t.name,t.reps,t.duration,false,t.category).timing().totalSeconds.asClock()}", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
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
