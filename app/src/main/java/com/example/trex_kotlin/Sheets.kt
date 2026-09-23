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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        MainSheet.Photo -> PhotoFoodSheet(app, onClose)
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
internal fun ManualSheet(app: AppViewModel, initialSlot: String, onClose: () -> Unit) {
    val c = Trex.c
    var slot by remember { mutableStateOf(initialSlot) }
    var query by remember { mutableStateOf("") }
    val slotFoods = app.dietFor(0)[slot].orEmpty()
    val total = slotFoods.totalNutrition()
    val goal = app.targetGoal
    val slotIndex = mealMetas.indexOfFirst { it.id == slot }.coerceAtLeast(0)
    // 한 글자마다 본문이 다시 돌므로 기록·내 음식이 바뀔 때만 다시 계산한다.
    val matches = remember(query, app.customFoods) { app.searchFoods(query) }
    val frequent = remember(app.dietByDay, app.customFoods) { app.frequentFoods() }

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
                Box(Modifier.padding(top = 18.dp)) { FoodSearchField(query) { query = it } }
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    // 검색어가 비면 자주 먹는 음식을 보여 준다. 늘 먹는 밥·국을 매번 치게 하지 않으려는 것이고,
                    // 사진 기록의 고르기 창과 같은 규칙이다 — 여기가 직접 기록의 주 경로다.
                    val listed = if (query.isBlank()) {
                        frequent.map { (name, n) -> Triple(name, n, name in app.customFoods) }
                    } else {
                        matches
                    }
                    when {
                        query.isBlank() && listed.isEmpty() ->
                            Text(
                                "음식 이름을 검색해 보세룡", color = c.text3, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
                            )
                        query.isBlank() ->
                            Text("자주 먹는 음식", color = c.text3, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        listed.isEmpty() ->
                            // 기본 DB 에도 내 음식에도 없으면 직접 등록한다 — 등록해 두면 다음부터 검색으로 바로 담을 수 있다.
                            CustomFoodForm(name = query.trim()) { nutrition ->
                                app.addCustomFood(query.trim(), nutrition)
                                app.appendFoods(0, slot, listOf(FoodEntry(query.trim(), nutrition)))
                                query = ""
                            }
                    }
                    listed.forEach { (name, n, isCustom) ->
                        val added = slotFoods.any { it.name == name }
                        FoodRow(
                            name, n, isCustom,
                            badgeIcon = if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                            badgeFilled = added,
                            badgeDescription = "담기",
                        ) { app.appendFoods(0, slot, listOf(FoodEntry(name, n))) }
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

/** 음식 검색칸. 직접 기록 시트와 음식 고르기 창이 같은 모양을 쓴다. */
@Composable
private fun FoodSearchField(query: String, onQuery: (String) -> Unit) {
    val c = Trex.c
    BasicTextField(
        value = query,
        onValueChange = onQuery,
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

/**
 * 음식을 하나 골라 돌려주는 창.
 *
 * 사진 결과 화면의 "빠진 음식 추가"와 "잘못 잡힌 이름 바꾸기"가 이 화면을 함께 쓴다 —
 * 둘 다 "무슨 음식인지 고른다"는 같은 일이라 UI 를 나눌 이유가 없다.
 *
 * 검색어가 비어 있으면 자주 먹는 음식을 먼저 보여준다. 실사용 평가에서 사진 한 장당
 * 손보는 횟수가 1.8회였는데(docs/FOOD_EVAL_RESULTS.md), 늘 먹는 밥·국을 매번 이름으로
 * 찾게 하는 것이 그중 큰 몫이었다.
 *
 * 검색해도 없으면 [CustomFoodForm] 으로 이어져 그 자리에서 등록하고 바로 고를 수 있다.
 *
 * [candidates] 는 **모델이 봤지만 임계값에 못 미쳐 결과에서 뺀 것**이다. 결과로 단정하지 않되
 * 이미 계산된 신호를 버리지 않으려고 여기서만 보여준다 — 고르는 것은 사용자이고, 고른 순간
 * 그 항목은 모델 판정이 아니라 사용자 선택으로 기록된다.
 */
@Composable
internal fun FoodPicker(
    app: AppViewModel,
    title: String,
    candidates: List<Pair<String, Float>> = emptyList(),
    onPick: (String, Nutrition) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Trex.c
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    // 한 글자 칠 때마다 이 본문이 다시 도는데, 빈도 집계는 기록 전체를 훑는다.
    // 검색 중에는 쓰지도 않으므로 기록·내 음식이 바뀔 때만 다시 센다.
    val matches = remember(trimmed, app.customFoods) { app.searchFoods(trimmed) }
    val frequent = remember(app.dietByDay, app.customFoods) { app.frequentFoods() }

    Dialog(
        onDismissRequest = onDismiss,
        // 화면의 유일한 입력칸이 이 안에 있다. 인셋을 창이 알아서 맞추게 두면 키보드가 올라올 때
        // 목록 아래쪽과 "등록하고 담기" 버튼이 가린다. SheetHost 와 같은 조합으로 직접 맞춘다.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.sheet),
            ) {
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Kicker("음식 고르기")
                        Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                    }
                    SheetClose(onDismiss)
                }
                Box(Modifier.padding(horizontal = 20.dp)) { FoodSearchField(query) { query = it } }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    when {
                        trimmed.isEmpty() && frequent.isEmpty() && candidates.isEmpty() ->
                            Text(
                                "음식 이름을 검색해 보세룡", color = c.text3, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
                            )
                        trimmed.isEmpty() -> {
                            if (candidates.isNotEmpty()) {
                                Text("사진에서 비슷하게 본 것", color = c.text3, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "확실하지 않아 결과에는 넣지 않았어요. 맞는 게 있으면 골라 주세요.",
                                    color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp),
                                )
                                candidates.forEach { (name, _) ->
                                    val n = app.findFood(name)
                                    if (n != null) {
                                        FoodRow(
                                            name, n, isCustom = name in app.customFoods,
                                            badgeIcon = Icons.Rounded.Check, badgeFilled = false, badgeDescription = "고르기",
                                        ) { onPick(name, n) }
                                    }
                                }
                            }
                            if (frequent.isNotEmpty()) {
                                Text(
                                    "자주 먹는 음식", color = c.text3, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = if (candidates.isEmpty()) Modifier else Modifier.padding(top = 6.dp),
                                )
                            }
                            frequent.forEach { (name, n) ->
                                FoodRow(
                                    name, n, isCustom = name in app.customFoods,
                                    badgeIcon = Icons.Rounded.Check, badgeFilled = false, badgeDescription = "고르기",
                                ) { onPick(name, n) }
                            }
                        }
                        matches.isEmpty() ->
                            // 기본 DB 에도 내 음식에도 없다. 등록하면 바로 고른 것으로 친다 —
                            // 등록만 하고 다시 찾게 하면 방금 한 일을 한 번 더 시키는 셈이다.
                            CustomFoodForm(name = trimmed) { nutrition ->
                                app.addCustomFood(trimmed, nutrition)
                                onPick(trimmed, nutrition)
                            }
                        else -> matches.forEach { (name, n, isCustom) ->
                            FoodRow(
                                name, n, isCustom,
                                badgeIcon = Icons.Rounded.Check, badgeFilled = false, badgeDescription = "고르기",
                            ) { onPick(name, n) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 음식 한 줄. 직접 기록 시트와 음식 고르기 창이 같이 쓴다.
 *
 * 오른쪽 배지만 다르다 — 직접 기록은 "담기"(이미 담겼으면 채운 체크), 고르기 창은 "고르기".
 */
@Composable
private fun FoodRow(
    name: String,
    n: Nutrition,
    isCustom: Boolean,
    badgeIcon: ImageVector,
    badgeFilled: Boolean,
    badgeDescription: String,
    onClick: () -> Unit,
) {
    val c = Trex.c
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        contentColor = c.text,
        border = BorderStroke(1.dp, c.line),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    // 직접 등록한 음식은 영양값이 사용자가 적은 값이라 기본 DB 와 구분해 표시한다.
                    if (isCustom) Text("내 음식", color = c.primaryText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                }
                Text(
                    "${n.kcal} kcal · 탄 ${n.carb.toInt()} · 단 ${n.protein.toInt()} · 지 ${n.fat.toInt()}",
                    color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (badgeFilled) c.primary else c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    badgeIcon, contentDescription = badgeDescription,
                    tint = if (badgeFilled) Color.White else c.primaryText, modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
/**
 * 기본 DB 에 없는 음식을 사용자가 등록하는 폼. 등록한 값은 기기에만 저장되고 검색에서 "내 음식" 으로 뜬다.
 * 칼로리만 필수다 — 탄단지를 모르면 비워 두고 0 으로 기록한다(모르는 값을 지어내지 않는다).
 */
@Composable
private fun CustomFoodForm(name: String, onAdd: (Nutrition) -> Unit) {
    val c = Trex.c
    var kcal by remember(name) { mutableStateOf("") }
    var carb by remember(name) { mutableStateOf("") }
    var protein by remember(name) { mutableStateOf("") }
    var fat by remember(name) { mutableStateOf("") }
    val kcalValue = kcal.toIntOrNull()

    DCard(modifier = Modifier.padding(top = 6.dp), radius = 20.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("\"$name\" 을(를) 직접 등록할까룡?", color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "1인분 기준으로 적어 주세룡. 등록하면 다음부터 검색으로 바로 담을 수 있어룡",
                color = c.text3, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(kcal, { kcal = it }, "칼로리", Modifier.weight(1f))
                NumberField(carb, { carb = it }, "탄수(g)", Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(protein, { protein = it }, "단백질(g)", Modifier.weight(1f))
                NumberField(fat, { fat = it }, "지방(g)", Modifier.weight(1f))
            }
            Cta(
                text = "등록하고 담기",
                icon = Icons.Rounded.Add,
                enabled = kcalValue != null,
                height = 48.dp,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                onClick = {
                    val v = kcalValue ?: return@Cta
                    onAdd(
                        Nutrition(
                            kcal = v,
                            carb = carb.toDoubleOrNull() ?: 0.0,
                            protein = protein.toDoubleOrNull() ?: 0.0,
                            fat = fat.toDoubleOrNull() ?: 0.0,
                        ),
                    )
                },
            )
        }
    }
}

/** 숫자만 받는 작은 입력칸. 빈 값은 "모름" 으로 두고 0 으로 기록한다. */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = Trex.c
    BasicTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter { it.isDigit() || it == '.' }.take(6)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = TextStyle(color = c.text, fontSize = 13.sp),
        cursorBrush = SolidColor(c.primary),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.field)
                    .border(1.dp, c.fieldLine, RoundedCornerShape(13.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = c.text3, fontSize = 12.5.sp)
                inner()
            }
        },
    )
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
