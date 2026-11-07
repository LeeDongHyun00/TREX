package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.FormLabel
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.math.roundToInt

/**
 * 운동 기록(§131, 디자인 P1) — 달력(월 단위, 운동한 날·계획된 휴식·오늘)과 통계(이번 주·이번 달·올해). 보관 기간이 제한 없음이면 지난 달로도 간다.
 * 볼륨은 사용자가 확정한 횟수로만 계산하고, 카메라 횟수만 있는 세트는 '추정' 으로 센다(원칙 #1).
 */
@Composable
fun RecordScreen(app: AppViewModel, onBack: () -> Unit, onOpenExercise: (String) -> Unit = {}, onAddManual: (Long) -> Unit = {},
    onOpenMuscleLoad: () -> Unit = {}, onStartWorkout: () -> Unit = {}) {
    val c = Trex.c
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "기록 뒤로가기", tint = c.text) }
            Text("운동 기록", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        SegmentedTabs(listOf("달력", "통계"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        if (tab == 0) RecordCalendar(app, onOpenExercise, onAddManual, onStartWorkout) else RecordStatsView(app, onOpenExercise, onOpenMuscleLoad)
    }
}

// ============================================================= 달력

@Composable
private fun RecordCalendar(app: AppViewModel, onOpenExercise: (String) -> Unit, onAddManual: (Long) -> Unit, onStartWorkout: () -> Unit) {
    val c = Trex.c
    val today = LocalDate.fromEpochDays(app.calendarDay)
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableIntStateOf(today.month.number) }
    var selected by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val workoutDays = remember(app.workoutHistory) { app.workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet() }
    val first = LocalDate(year, month, 1)
    val daysInMonth = daysIn(year, month)
    val leading = first.dayOfWeek.isoDayNumber - 1   // 월요일 시작
    val weekStart = today.toEpochDay() - (today.dayOfWeek.isoDayNumber - 1)
    val weekGoal = DayOfWeek.entries.count { app.isTargetDay(it) }
    val weekDone = (weekStart..weekStart + 6).count { it in workoutDays }
    val record = app.workoutHistory.firstOrNull { it.epochDay == selected }
    val monthHasRecords = workoutDays.any { LocalDate.fromEpochDays(it).let { d -> d.year == year && d.month.number == month } }
    var editDays by rememberSaveable { mutableStateOf(false) }
    if (editDays) WorkoutDaysSheet(app, onClose = { editDays = false })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { if (month == 1) { year -= 1; month = 12 } else month -= 1 }) { Icon(Icons.Rounded.ChevronLeft, "이전 달", tint = c.text2) }
                        Text("${year}년 ${month}월", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { if (month == 12) { year += 1; month = 1 } else month += 1 }) { Icon(Icons.Rounded.ChevronRight, "다음 달", tint = c.text2) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf("월", "화", "수", "목", "금", "토", "일").forEach { Text(it, color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
                    }
                    // 달 넘김은 200 ms 슬라이드 — 어느 쪽으로 갔는지 보인다(UI 감사 A22, K4 방향 신호). 점·칸은 등장 장식 없이 그대로
                    // 키 = year*12 + (month-1) — month 를 그대로 더하면 12월이 key%12 == 0 으로 풀려 LocalDate(…, 0, 1) 에서 죽는다(11-07 실기기)
                    AnimatedContent(year * 12 + (month - 1), transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(tween(200)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(160))) togetherWith
                            (slideOutHorizontally(tween(200)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(120)))
                    }, label = "month") { key ->
                    val y = key / 12; val m = key % 12 + 1
                    val firstOf = LocalDate(y, m, 1)
                    val days = daysIn(y, m)
                    val lead = firstOf.dayOfWeek.isoDayNumber - 1
                    val cells = lead + days
                    val rows = (cells + 6) / 7
                    Column {
                    repeat(rows) { r ->
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            repeat(7) { col ->
                                val index = r * 7 + col
                                val day = index - lead + 1
                                if (day < 1 || day > days) { Spacer(Modifier.weight(1f).height(44.dp)); return@repeat }
                                val date = LocalDate(y, m, day)
                                val epoch = date.toEpochDay()
                                val worked = epoch in workoutDays
                                val future = epoch > today.toEpochDay()
                                val rest = !worked && !future && plannedRest(date, app::isTargetDay)
                                val isSelected = epoch == selected
                                val isToday = epoch == today.toEpochDay()
                                Column(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) c.text else Color.Transparent)
                                    .border(if (isToday && !isSelected) 1.5.dp else 0.dp, if (isToday) c.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable(enabled = !future) { selected = epoch }
                                    .semantics { contentDescription = "${m}월 ${day}일" + (if (worked) ", 운동함" else if (rest) ", 계획된 휴식" else "") + (if (isToday) ", 오늘" else "") },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("$day", color = if (isSelected) c.bg else if (future) c.text3 else c.text, fontSize = 14.sp)
                                    Box(Modifier.padding(top = 3.dp).size(6.dp).clip(CircleShape)
                                        .background(when { isSelected && worked -> c.lime; worked -> c.primary; rest -> c.primarySoftLine; else -> Color.Transparent }))
                                }
                            }
                        }
                    }
                    }
                    }
                    Row(Modifier.padding(top = 10.dp, start = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend(c.primary, "운동한 날"); Legend(c.primarySoftLine, "계획된 휴식")
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.text, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("연속 운동 · 목표 요일 기준", color = c.bg.copy(alpha = .72f), fontSize = 12.sp); Text("${app.attendanceStreak()}일", color = c.bg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("이번 주 목표", color = c.bg.copy(alpha = .72f), fontSize = 12.sp)
                        Text(if (weekGoal > 0) "$weekDone / ${weekGoal}일" else "${weekDone}일", color = c.bg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        // 운동 요일(§139) — 위 두 숫자와 달력의 '계획된 휴식' 이 따르는 요일. 누르면 고친다
        item { WorkoutDaysCard(app, onEdit = { editDays = true }) }
        item {
            val date = LocalDate.fromEpochDays(selected)
            Text("${date.monthValue}월 ${date.day}일 (${koreanDayOfWeek(date.dayOfWeek)})" + if (selected == today.toEpochDay()) " · 오늘" else "",
                color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    if (record?.items?.isNotEmpty() != true) {
                        if (!monthHasRecords) {
                            // 빈 상태(디자인) — 비어 있는 이유 한 줄 + 다음 행동
                            Text("아직 이 달 기록이 없어요", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                            Text("운동을 마치면 날짜에 점이 찍혀요. 지난 운동은 지금 적어도 돼요.", color = c.text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton("지난 운동 적기", { onAddManual(selected) }, Modifier.weight(1f))
                                Cta("운동 시작하기", onStartWorkout, Modifier.weight(1f), height = 50.dp)
                            }
                        } else Text("이날 저장된 운동이 없어요.", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    record?.items?.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        RecordItemRow(item, onOpen = { onOpenExercise(item.workoutName) })
                    }
                    GhostButton("기록 추가하기", { onAddManual(selected) }, Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
            }
        }
    }
}

/** 운동 요일 한 줄(§139, 사용자 요청 2026-11-07 "초반에 정한 운동 요일을 운동 기록에서 수정") — 목표 요일 일곱 칸 + 수정. */
@Composable
private fun WorkoutDaysCard(app: AppViewModel, onEdit: () -> Unit) {
    val c = Trex.c
    val days = DayOfWeek.entries.filter { app.isTargetDay(it) }
    Surface(onClick = onEdit, shape = RoundedCornerShape(20.dp), color = c.surface,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "운동 요일 " + (if (days.isEmpty()) "정하지 않음" else days.joinToString(", ") { koreanDayOfWeek(it) }) + ", 눌러서 수정"
        }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("운동 요일", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (days.isEmpty()) "정하지 않으면 매일이 목표" else "주 ${days.size}일", color = c.text2, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp).weight(1f))
                Text("수정", color = c.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Rounded.ChevronRight, null, tint = c.primaryText, modifier = Modifier.size(18.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                DayOfWeek.entries.forEach { d ->
                    val on = d in days
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) c.primary else c.surface2), contentAlignment = Alignment.Center) {
                        Text(koreanDayOfWeek(d), color = if (on) Color.White else c.text3, fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/**
 * 운동 요일 고치기(§139) — 온보딩의 "운동할 요일" 과 같은 값(UserProfile.dayMask)이다. 고른 요일이 목표 요일이 된다(연속 운동·이번 주 목표·계획된 휴식·운동 요일 알림).
 * 주간 루틴이 든 요일은 작은 점 — 그 요일을 끄면 그 요일 루틴도 빠진다(루틴이 든 요일은 목표 요일이라서). 온보딩처럼 1일 이상이어야 저장된다.
 */
@Composable
private fun WorkoutDaysSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val reminders = rememberReminderScheduler()
    val initial = remember { DayOfWeek.entries.fold(0) { m, d -> if (app.isTargetDay(d)) m or dayBit(d) else m } }
    var mask by rememberSaveable { mutableIntStateOf(initial) }
    val count = mask.countOneBits()
    val dropped = routineDaysTurnedOff(mask, app.weeklyRoutine)
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("운동 요일", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SheetClose(onClose)
            }
            Text("처음 시작할 때 고른 요일이에요. 고른 요일이 목표 요일이 돼요 — 연속 운동·이번 주 목표·달력의 계획된 휴식이 이 요일을 따라요.",
                color = c.text2, fontSize = 13.sp, lineHeight = 19.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DayOfWeek.entries.forEach { d ->
                    val on = mask and dayBit(d) != 0
                    val routine = app.weeklyRoutine.isTarget(d)
                    Surface(onClick = { mask = withDay(mask, d, !on) }, shape = CircleShape,
                        color = if (on) c.primary else c.surface, border = BorderStroke(1.dp, if (on) c.primary else c.line),
                        shadowElevation = if (on) 3.dp else 0.dp,
                        modifier = Modifier.size(44.dp).semantics {
                            contentDescription = koreanDayOfWeekFull(d) + if (routine) ", 주간 루틴 있음" else ""
                            stateDescription = if (on) "운동" else "휴식"
                        }) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(koreanDayOfWeek(d), color = if (on) Color.White else c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            // 주간 루틴이 든 요일 — 글을 읽지 않아도 보이게 점(루틴 화면의 요일 고르기와 같은 표시)
                            if (routine) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(5.dp)
                                .background(if (on) Color.White.copy(alpha = .85f) else c.text3, CircleShape))
                        }
                    }
                }
            }
            Text(if (count == 0) "최소 1일 이상 골라 주세요." else "주 ${count}일 · " + DayOfWeek.entries.filter { mask and dayBit(it) != 0 }.joinToString(" · ") { koreanDayOfWeek(it) },
                color = if (count == 0) c.text3 else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (app.weeklyRoutine.slots.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).background(c.surface2, CircleShape), contentAlignment = Alignment.BottomCenter) { Box(Modifier.padding(bottom = 1.dp).size(3.dp).background(c.text3, CircleShape)) }
                Text("주간 루틴이 든 요일", color = c.text2, fontSize = 12.sp)
            }
            if (dropped.isNotEmpty()) WashBanner("${dropped.joinToString("·") { koreanDayOfWeek(it) }}요일을 끄면 그 요일의 주간 루틴도 빠져요(휴식).", Icons.Rounded.Info, warnTone = true)
            if (hasThreeConsecutiveDays(mask)) WashBanner("연속 운동일이 3일 이상이면 부상 위험이 높아집니다. 휴식일을 중간에 넣는 것을 추천합니다.", Icons.Rounded.Warning, warnTone = true)
            Cta("저장", {
                app.setWorkoutDays(mask)
                // 운동 요일 알림은 목표 요일에 건다 — 켜져 있으면 바뀐 요일로 다시 예약(설정의 알림 시트와 같은 식)
                val prefs = app.notifyPrefs
                if (prefs.workoutDays) reminders.scheduleWorkoutDays(DayOfWeek.entries.filter { app.isTargetDay(it) }.map { it.isoDayNumber }.toSet(), prefs.hour, prefs.minute)
                onClose()
            }, Modifier.fillMaxWidth(), enabled = count > 0 && mask != initial)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color)); Text(label, color = Trex.c.text2, fontSize = 12.sp)
    }
}

@Composable
private fun RecordItemRow(item: WorkoutHistoryItem, onOpen: () -> Unit) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // '타이머·기록 전용·자세 교정' 칩은 뺐다(사용자 요청 2026-11-07) — 출처는 세부 줄의 '자세 지적' 과 종목 상세가 말한다
            Text(item.workoutName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            val line = item.sets.summaryLine(bodyweight = exerciseLoadKind(item.workoutName).kgLabel() == "+kg").ifBlank { item.reps }
            val pc = item.postureCorrection
            val detail = buildList {
                add(line)
                pc?.let { p -> if (p.mode == "coach" && p.kind in setOf("habit", "drift", "violation")) add("${p.bodyPart ?: "자세"} 지적") }
                item.sets.mapNotNull { it.rpe }.lastOrNull()?.let { add("RPE $it") }
                if (item.sets.any { !it.memo.isNullOrBlank() }) add("메모")
            }
            Text(detail.joinToString(" · "), color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            pc?.let { p ->
                val self = selfLabelText(p)
                if (self != null) Text(self, color = c.primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(20.dp))
    }
}

private fun daysIn(year: Int, month: Int): Int = when (month) {
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** "내 평가 · 좋았음 · 실제 12회" — 자가 라벨이 하나도 없으면 null (칩 줄 자체를 그리지 않는다). */
private fun selfLabelText(pc: PostureCorrection): String? {
    val parts = buildList {
        FormLabel.from(pc.formLabel)?.let { add(it.displayName) }
        pc.actualReps?.let { add("실제 ${it}회") }
    }
    return if (parts.isEmpty()) null else (listOf("내 평가") + parts).joinToString(" · ")
}

// ============================================================= 통계

@Composable
private fun RecordStatsView(app: AppViewModel, onOpenExercise: (String) -> Unit, onOpenMuscleLoad: () -> Unit) {
    val c = Trex.c
    var period by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.fromEpochDays(app.calendarDay)
    val from = when (period) {
        0 -> today.toEpochDay() - (today.dayOfWeek.isoDayNumber - 1)
        1 -> LocalDate(today.year, today.month.number, 1).toEpochDay()
        else -> LocalDate(today.year, 1, 1).toEpochDay()
    }
    val stats = remember(app.workoutHistory, from, app.calendarDay) { app.workoutHistory.stats(from, today.toEpochDay()) }
    val week = (from..today.toEpochDay()).takeIf { period == 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FilterChipRow(listOf("이번 주", "이번 달", "올해"), listOf("이번 주", "이번 달", "올해")[period], { period = listOf("이번 주", "이번 달", "올해").indexOf(it) }) }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        StatCell("운동일 · 세트", "${stats.days}일 · ${stats.sets}세트", Modifier.weight(1f))
                        // 추정 세트는 글 대신 호박 칩 — 호박 = 참고/카메라 값(K3·K8, UI 감사 A23)
                        StatCell("볼륨", "${stats.volume.kg.roundToInt()} kg", Modifier.weight(1f), note = if (stats.volume.estimatedSets > 0) "추정 ${stats.volume.estimatedSets}세트" else null)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatCell("운동 시간", stats.seconds.let { s -> if (s >= 3600) "${s / 3600}시간 ${s % 3600 / 60}분" else "${s / 60}분" }, Modifier.weight(1f))
                        StatCell("소모 · 추정", "${stats.kcal} kcal", Modifier.weight(1f))
                    }
                }
            }
        }
        if (week != null) item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("날짜별 세트", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val counts = week.map { d -> d to (app.workoutHistory.firstOrNull { it.epochDay == d }?.items?.sumOf { if (it.sets.isEmpty()) parseReps(it.reps).sets else it.sets.count { s -> s.done } } ?: 0) }
                    val max = counts.maxOf { it.second }.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        counts.forEach { (d, n) ->
                            val date = LocalDate.fromEpochDays(d)
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (n > 0) "$n" else "", color = c.text2, fontSize = 11.sp)
                                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.BottomCenter) {
                                    // 막대는 300 ms 로 자란다 — 기간을 바꾸면 값이 바뀌었다는 피드백(UI 감사 A23, K4). 처음 열 때도 0 에서 한 번
                                    val h by animateDpAsState((70f * n / max).coerceAtLeast(2f).dp, tween(300), label = "bar$d")
                                    Box(Modifier.widthIn(max = 28.dp).fillMaxWidth().height(h).clip(RoundedCornerShape(5.dp))
                                        .background(if (n == 0) c.track else if (d == today.toEpochDay()) c.primaryText else c.primary))
                                }
                                Text(koreanDayOfWeek(date.dayOfWeek), color = if (d == today.toEpochDay()) c.text else c.text2, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("부위 비중", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (stats.partShare.isEmpty()) Text("기록이 쌓이면 부위 비중이 나타나요", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    else {
                        val colors = listOf(c.primary, c.primaryText, c.lime, c.warn)
                        val parts = stats.partShare.entries.sortedByDescending { it.value }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(12.dp).clip(RoundedCornerShape(999.dp))) {
                            parts.forEachIndexed { i, (_, pct) -> Box(Modifier.weight(pct.coerceAtLeast(1).toFloat()).fillMaxHeight().background(colors[i % colors.size])) }
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            parts.forEachIndexed { i, (name, pct) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors[i % colors.size])); Text("$name $pct%", color = c.text2, fontSize = 12.sp)
                            } }
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("많이 한 종목", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text("세트 기준", color = c.text2, fontSize = 12.sp)
                    }
                    if (stats.topExercises.isEmpty()) Text("아직 기록이 없어요", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                    stats.topExercises.forEach { (name, sets, best) ->
                        HorizontalDivider(color = c.line)
                        Row(Modifier.fillMaxWidth().clickable { onOpenExercise(name) }.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = c.text, fontSize = 15.sp)
                            Text(listOf("${sets}세트", best).filter { it.isNotBlank() }.joinToString(" · ") + " ›", color = c.text2, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        item { MuscleLoadHomeCard(app, onOpenMuscleLoad) }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier, note: String? = null) {
    Column(modifier) {
        Text(label, color = Trex.c.text2, fontSize = 12.sp)
        Text(value, color = Trex.c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        if (note != null) Text(note, color = Trex.c.warn, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).background(Trex.c.warnWash).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
