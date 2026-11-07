package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.routines.PresetFilter
import com.example.trex_kotlin.routines.RoutinePreset
import com.example.trex_kotlin.routines.filtered
import com.example.trex_kotlin.routines.routinePresets
import com.example.trex_kotlin.routines.routineTopics
import com.example.trex_kotlin.routines.toWorkouts
import com.example.trex_kotlin.trainingload.MuscleLoadEngine
import kotlinx.datetime.DayOfWeek

/**
 * 루틴(§131, 디자인 P10·P11·P12) — 주간 루틴(요일 슬롯), 추천(자료집 프리셋 60개 — 관심 목표·장비·초보 필터, 오늘의 제안은 근육 피로도), 보관함(내 루틴·받은 루틴).
 * 프리셋은 팀이 쓴 분할 템플릿이다. '당신에게 맞다' 고 단정하지 않고 자세 교정 지원 종목을 앞에 둔다.
 */
@Composable
fun RoutinesScreen(app: AppViewModel, initialTab: Int = 0, onBack: () -> Unit, onOpenCatalog: () -> Unit, onStartWorkout: () -> Unit) {
    val c = Trex.c
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var preview by remember { mutableStateOf<RoutinePreset?>(null) }
    var assignRef by remember { mutableStateOf<Pair<String, String>?>(null) }   // (ref, 이름) — 요일 고르기 시트
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Text("루틴", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        SegmentedTabs(listOf("주간 루틴", "추천", "보관함 (${app.savedRoutines.size})"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        when (tab) {
            0 -> WeeklyTab(app, onOpenCatalog, onStartWorkout, onPickRoutine = { tab = 1 })
            1 -> PresetsTab(app, onPreview = { preview = it }, onAssign = { assignRef = it.ref to it.displayName })
            else -> LibraryTab(app, onAssign = { assignRef = it })
        }
    }
    preview?.let { p ->
        RoutinePreviewSheet(app, p, onClose = { preview = null }, onAssign = { assignRef = p.ref to p.displayName; preview = null },
            onApplyToday = { app.applyRoutine(p.displayName, p.toWorkouts(nowMillis())); preview = null; onBack() },
            onSave = { app.saveRoutine(p.displayName, p.toWorkouts(nowMillis()), null) })
    }
    assignRef?.let { (ref, name) -> AssignDaysSheet(app, ref, name, onClose = { assignRef = null }) }
}

// ============================================================= 주간 루틴(P11)

@Composable
private fun WeeklyTab(app: AppViewModel, onOpenCatalog: () -> Unit, onStartWorkout: () -> Unit, onPickRoutine: () -> Unit) {
    val c = Trex.c
    val today = localToday().dayOfWeek
    var day by rememberSaveable { mutableIntStateOf(today.isoDayNumber) }
    val dow = DayOfWeek.entries[day - 1]
    val ref = app.weeklyRoutine.ref(dow)
    val routine = ref?.let { app.routineWorkouts(it) }
    val isToday = dow == today
    val workouts = if (isToday) app.workoutPlan else routine?.second.orEmpty()
    val overview = routineOverview(workouts)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { d ->
                val on = d == dow
                val has = app.weeklyRoutine.isTarget(d)
                Surface(onClick = { day = d.isoDayNumber }, shape = CircleShape, color = if (on) c.text else if (has) c.primaryWash else c.surface, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(koreanDayOfWeek(d), color = if (on) c.bg else c.text, fontSize = 14.sp, fontWeight = if (has) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
        }
        Text("${koreanDayOfWeekFull(dow)}${if (isToday) " · 오늘" else ""} · ${if (ref == null && !isToday) "휴식" else overview.focus.title}", color = c.text2, fontSize = 13.sp)
        if (ref == null && !isToday) {
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("${koreanDayOfWeekFull(dow)}은 휴식이에요", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("쉬는 날은 연속 운동이 끊기지 않아요. 루틴을 넣으면 목표 요일이 돼요.", color = c.text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Cta("추천에서 고르기", onPickRoutine, Modifier.weight(1f), height = 48.dp)
                        GhostButton("직접 만들기", onOpenCatalog, Modifier.weight(1f))
                    }
                }
            }
        } else {
            Surface(shape = RoundedCornerShape(20.dp), color = c.text, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ref?.let { app.routineName(it) } ?: "오늘 운동", color = c.bg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${overview.count}종목 · ${overview.totalSets}세트 · 약 ${overview.minutes}분", color = c.bg.copy(alpha = .8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    TextButton(onClick = onPickRoutine) { Text("바꾸기", color = c.lime, fontSize = 14.sp) }
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (workouts.isEmpty()) Text("오늘 할 운동을 추가해 보세요.", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                    workouts.forEachIndexed { i, w ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        val last = app.lastSets(w.name)?.second?.lastOrNull()?.kg
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(w.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    if (w.postureSupported()) PostureSupportPill()
                                }
                                Text("${w.repsSpec().sets}세트 × ${w.repsSpec().targetLabel}" + (last?.let { " · ${trimKg(it)} kg" } ?: ""), color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
            if (isToday) Text("행 편집·순서·삭제는 운동 탭에서 해요. 화·일처럼 루틴이 없는 요일은 휴식이라 연속 운동이 끊기지 않아요.", color = c.text2, fontSize = 12.sp, lineHeight = 17.sp)
            if (ref != null) GhostButton("이 요일 루틴 빼기(휴식으로)", { app.assignWeeklyRoutine(dow, null) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("운동 추가하기", onOpenCatalog, Modifier.weight(1f))
                if (isToday) Cta("운동 시작하기", onStartWorkout, Modifier.weight(1.3f)) else Cta("오늘 루틴으로", { routine?.let { app.applyRoutine(it.first, it.second) } }, Modifier.weight(1.3f), enabled = routine != null)
            }
        }
    }
}

// ============================================================= 추천(P10)

/**
 * 추천 루틴(§132 — 그림 중심, `RoutinePresetUi.kt` 머리말의 참조 넷).
 * 고르는 순서는 수준 → 장비 → 부위 — **초보/전체 토글**(사용자 요청 2026-11-07)이 맨 위에 있고 초보에서는 상한이 중급인 루틴이 숨는다([RoutinePreset.beginnerFriendly]).
 * 처음 쓰는 사람(운동한 날 5일 미만)은 초보로 시작한다. 목록은 목표별 묶음(제목 + 주 N회)이라 장비를 고르면 목표마다 카드 하나가 남는다 — 같은 제목 넷이 줄지어 보이지 않게.
 */
@Composable
private fun PresetsTab(app: AppViewModel, onPreview: (RoutinePreset) -> Unit, onAssign: (RoutinePreset) -> Unit) {
    val c = Trex.c
    val newcomer = remember { app.workoutHistory.count { it.items.isNotEmpty() } < 5 }
    var beginner by rememberSaveable { mutableStateOf(newcomer) }
    var equipment by rememberSaveable { mutableStateOf<String?>(null) }
    var part by rememberSaveable { mutableStateOf<String?>(null) }
    val list = routinePresets.filtered(PresetFilter(equipment = equipment, beginner = beginner, part = part))
    val hiddenByLevel = if (beginner) routinePresets.filtered(PresetFilter(equipment = equipment, part = part)).size - list.size else 0
    // 목표별 묶음 — 초보 보기에서는 쉬운 루틴이 있는 묶음이 먼저, 부위별 기본(R01~R16)은 '부위별 기본 루틴' 으로 맨 뒤
    val groups = list.groupBy { it.topic }.entries.sortedWith(compareBy({ it.key == null }, { e -> if (beginner) e.value.minOf { it.levelRank } else 0 }, { e -> routineTopics.indexOfFirst { it.id == e.key } }))
    val heavy = remember(app.muscleLoad) { app.muscleLoad.muscles.filter { it.known && it.value >= 55.0 }.sortedByDescending { it.value }.take(2) }
    val suggestion = remember(app.muscleLoad, app.profile) { MuscleLoadEngine.recommendations(app.muscleLoad, app.profile.loadEquipment()).firstOrNull() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SegmentedTabs(listOf("초보", "전체"), if (beginner) 0 else 1, { beginner = it == 0 }, Modifier.width(168.dp), height = 36.dp, filled = true)
                Text(if (beginner) "${list.size}개" + (if (hiddenByLevel > 0) " · 중급 ${hiddenByLevel}개 숨김" else "") else "전체 ${list.size}개",
                    color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        item { EquipmentTiles(equipment) { equipment = it } }
        item { PartChips(part) { part = it } }
        if (heavy.isNotEmpty()) item {
            Surface(shape = RoundedCornerShape(18.dp), color = c.primaryWash, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("오늘의 제안 · 근육 피로도", color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${heavy.joinToString("·") { it.muscle.label }}이 아직 무거워요." + (suggestion?.let { " 오늘은 ${it.name} 같은 종목이 덜 겹쳐요." } ?: ""), color = c.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("기록 기반 추정이에요 — 운동 가능·안전 판정이 아니에요.", color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (list.isEmpty()) item {
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(if (beginner) "이 조건에 초보 루틴이 없어요" else "이 조건의 루틴이 없어요", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (beginner) "전체로 바꾸면 중급 루틴까지 보여요." else "장비나 부위를 '전체' 로 돌려 보세요.", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (beginner) GhostButton("전체 수준 보기", { beginner = false }, Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        }
        groups.forEach { (topicId, presets) ->
            val tp = topicId?.let { t -> routineTopics.firstOrNull { it.id == t } }
            item(key = "head-${topicId ?: "R"}") {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(tp?.title ?: "부위별 기본 루틴", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(tp?.schedule ?: "부위 하나를 집중하는 짧은 루틴 — 주간 루틴의 빈 요일에 넣기 좋아요", color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            items(presets.size, key = { presets[it].id }) { i ->
                val p = presets[i]
                val days = app.weeklyRoutine.slots.filterValues { it == p.ref }.keys.sorted().map { koreanDayOfWeek(DayOfWeek.entries[it - 1]) }
                PresetCard(p, days, showEquipment = equipment == null, onPreview = { onPreview(p) }, onAssign = { onAssign(p) })
            }
        }
        item { WashBanner("프리셋은 자료집(2026-11-07)의 분할 템플릿이에요. '당신에게 맞다' 고 단정하지 않고, 자세 교정 지원 종목을 앞에 둡니다. 세트·반복·휴식은 시작안이에요. 카드를 누르면 종목을 미리 봐요.", Icons.Rounded.Info) }
    }
}

/** 루틴 미리 보기 시트(P12) — 종목·세트·부위 비중, 요일에 넣기·오늘 루틴으로·보관함에 저장. */
@Composable
private fun RoutinePreviewSheet(app: AppViewModel, p: RoutinePreset, onClose: () -> Unit, onAssign: () -> Unit, onApplyToday: () -> Unit, onSave: () -> String?) {
    val c = Trex.c
    val workouts = remember(p.id) { p.toWorkouts(0L) }
    val overview = routineOverview(workouts)
    var notice by remember { mutableStateOf<String?>(null) }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 22.dp).padding(bottom = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { Kicker("추천 루틴 · ${p.topic?.let { t -> routineTopics.first { it.id == t }.title } ?: p.part}", c.primaryText); Text(p.displayName, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
                SheetClose(onClose)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(p); WashPill(p.equipment)
                Text("${p.items.size}종목 · ${overview.totalSets}세트 · 약 ${overview.minutes}분", color = c.text2, fontSize = 13.sp)
            }
            if (p.postureCount > 0) Box(Modifier.padding(top = 6.dp)) { PostureSupportPill("자세 교정 지원 ${p.postureCount} / ${p.items.size}") }
            if (p.required.isNotBlank()) Text("필수 장비 · ${p.required}", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            p.topic?.let { t -> routineTopics.firstOrNull { it.id == t } }?.let { tp -> Text("${tp.schedule} · 근거 ${tp.evidence}", color = c.text3, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                p.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = c.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(item.name, color = c.text, fontSize = 15.sp); if (postureSupported(item.name)) PostureSupportPill() }
                        }
                        Text("${item.sets} × ${if (item.min == item.max) "${item.max}" else "${item.min}~${item.max}"}${item.unit}${if (item.perSide) "/한쪽" else ""}", color = c.text2, fontSize = 13.sp)
                    }
                }
                if (p.note.isNotBlank()) Text(p.note, color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
                Text("무게는 처음엔 비어 있어요. 첫 세트에서 적으면 다음부터 '지난번' 으로 채워 드려요.", color = c.text3, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                notice?.let { Text(it, color = c.primaryText, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("요일에 넣기", onAssign, Modifier.weight(1f))
                Cta("오늘 루틴으로", onApplyToday, Modifier.weight(1.2f), height = 50.dp)
            }
            GhostButton("보관함에 저장", { notice = onSave() ?: "보관함에 저장했어요" }, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** 요일 고르기 — 여러 요일에 같은 루틴을 넣을 수 있다. 오늘 요일이면 오늘 루틴에도 바로 얹는다. */
@Composable
private fun AssignDaysSheet(app: AppViewModel, ref: String, name: String, onClose: () -> Unit) {
    val c = Trex.c
    val picked = remember { mutableStateListOf<DayOfWeek>().also { l -> l += app.weeklyRoutine.slots.filterValues { it == ref }.keys.map { DayOfWeek.entries[it - 1] } } }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("요일에 넣기", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(name, color = c.text2, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DayOfWeek.entries.forEach { d ->
                    val on = d in picked
                    val other = app.weeklyRoutine.ref(d)?.takeIf { it != ref }
                    Surface(onClick = { if (on) picked.remove(d) else picked.add(d) }, shape = CircleShape, color = if (on) c.primary else if (other != null) c.surface2 else c.surface, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(koreanDayOfWeek(d), color = if (on) c.bg else c.text, fontSize = 14.sp) }
                    }
                }
            }
            Text("회색 요일에는 다른 루틴이 있어요 — 고르면 바꿔요. 고르지 않은 요일은 그대로예요.", color = c.text2, fontSize = 12.sp)
            Cta("저장", {
                DayOfWeek.entries.forEach { d ->
                    val had = app.weeklyRoutine.ref(d) == ref
                    if (d in picked && !had) app.assignWeeklyRoutine(d, ref) else if (d !in picked && had) app.assignWeeklyRoutine(d, null)
                }
                onClose()
            }, Modifier.fillMaxWidth())
        }
    }
}

// ============================================================= 보관함

@Composable
private fun LibraryTab(app: AppViewModel, onAssign: (Pair<String, String>) -> Unit) {
    val c = Trex.c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (app.savedRoutines.isEmpty()) item { Text("저장한 루틴이 없어요. 추천 루틴을 보관함에 저장하거나, 운동 탭에서 오늘 루틴을 저장해 보세요.", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 20.dp)) }
        items(app.savedRoutines.size, key = { app.savedRoutines[it].id }) { i ->
            val r = app.savedRoutines[i]
            val days = app.weeklyRoutine.slots.filterValues { it == "saved:${r.id}" }.keys.sorted().map { koreanDayOfWeek(DayOfWeek.entries[it - 1]) }
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(r.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(routineOverview(r.workouts).detail + (r.from?.let { " · $it 님이 보냄" } ?: ""), color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WashPill(if (days.isEmpty()) "미배정" else days.joinToString(" · "))
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onAssign("saved:${r.id}" to r.name) }) { Text("요일에 넣기", color = c.primaryText, fontSize = 13.sp) }
                        TextButton(onClick = { app.applyRoutine(r.name, r.workouts) }) { Text("오늘 루틴으로", color = c.primaryText, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}
