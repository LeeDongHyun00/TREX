package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.trainingload.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import com.example.trex_kotlin.TrexText as Text

@Composable
internal fun MuscleLoadHomeCard(app: AppViewModel, onOpen: () -> Unit) {
    val c=Trex.c; val peak=app.muscleLoad.peak
    Surface(onClick=onOpen, shape=RoundedCornerShape(22.dp), color=c.surface, modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.AccessibilityNew,null,tint=c.primaryText,modifier=Modifier.size(30.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("근육 피로도",color=c.text,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                Text(when { app.muscleLoadLoading -> "운동 기록을 불러오는 중"
                    app.muscleLoadError != null -> "기록을 다시 불러와 주세요"
                    peak == null -> "운동 기록으로 부위별 부하를 확인하세요"
                    else -> "${peak.muscle.label} ${peak.value.roundToInt()}점 · 기록 기반 추정" },color=c.text2,fontSize=12.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,"근육 피로도 확인",tint=c.text2)
        }
    }
}

/** 표시 목록만 거른다. 추천·기록 계산에서 쓰는 전체 근육 스냅샷은 유지한다. */
internal fun rankedMuscles(snapshot: LoadSnapshot): List<MuscleStatus> =
    snapshot.muscles.filter { it.known && it.value > 0.0 }
        .sortedWith(compareByDescending<MuscleStatus> { it.value }.thenBy { it.muscle.ordinal })

internal fun muscleScoreLabel(value: Double): String =
    if (value > 0.0 && value < 1.0) "1점 미만" else "${value.roundToInt()}점"

@Composable
internal fun MuscleLoadScreen(app: AppViewModel, onBack: () -> Unit) {
    val c = Trex.c
    val snapshot = app.muscleLoad
    var selectedName by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 30초 새로고침(TrexApp)은 계산 시각(calculatedAt)만 바뀐 스냅샷도 새로 만든다 — 쓰는 값(근육 목록)이 같으면 다시 계산하지 않는다(스펙 §115).
    val ranked = remember(snapshot.muscles) { rankedMuscles(snapshot) }
    val selected = ranked.firstOrNull { it.muscle.name == selectedName }?.muscle ?: ranked.firstOrNull()?.muscle
    LaunchedEffect(Unit) { app.refreshMuscleLoad() }
    val recommendations = remember(snapshot.muscles, app.profile) { MuscleLoadEngine.recommendations(snapshot, app.profile.loadEquipment()) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Text("근육 피로도", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text("기록으로 보는 오늘의 근육", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("기록 기반 추정 · 0–100점", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (app.muscleLoadLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.primaryText) }
            if (app.muscleLoadError != null) item {
                Text(app.muscleLoadError!!, color = c.text2, fontSize = 14.sp)
                TextButton(onClick = app::syncMuscleLoad) { Text("다시 불러오기", color = c.primaryText) }
            }
            item {
                // 근육도 WebView 는 화면 밖으로 스크롤돼도 폐기하지 않는다 — 돌아올 때마다 WebView 를 새로 만들고 페이지·SVG 를
                // 다시 읽던 비용(과 "불러오는 중" 깜빡임)을 없앤다(스펙 §115). 항목 키는 예전 그대로(순서)다.
                KeepComposedInLazyList()
                Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF222527)) {
                    Column {
                        MuscleAtlas(snapshot, if (tab == 0) selected?.name.orEmpty() else "", Modifier.fillMaxWidth().height(366.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFFDEDED9), Color(0xFFC7E26B), Color(0xFF759848)))))
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("낮음", color = Color(0xFFE3E8DD), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("높음", color = Color(0xFFE3E8DD), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = c.surface) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("근육 상태", "추천 운동").forEachIndexed { index, title ->
                            Surface(onClick = { tab = index }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                                color = if (tab == index) c.primary else Color.Transparent) {
                                Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                    Text(title, color = if (tab == index) Color.White else c.text2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            if (tab == 0) {
                item { Text("피로도 높은 순", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                if (ranked.isEmpty() && !app.muscleLoadLoading && app.muscleLoadError == null) item {
                    Text("현재 표시할 근육 피로도가 없어요. 운동 기록을 저장하면 사용한 근육이 나타납니다.", color = c.text2, fontSize = 14.sp)
                }
                items(ranked, key = { it.muscle.name }) { status ->
                    val expanded = selected == status.muscle
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { selectedName = status.muscle.name }, shape = RoundedCornerShape(16.dp),
                            color = if (expanded) c.primary.copy(alpha = .12f) else c.surface) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(status.muscle.label, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(muscleScoreLabel(status.value), color = c.primaryText, fontSize = 16.sp)
                            }
                        }
                        if (expanded) MuscleDetailCard(status, snapshot)
                    }
                }
            } else {
                item { Text("최근 부하와 덜 겹치는 운동", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                if (recommendations.isEmpty()) item {
                    Text(if (snapshot.peak == null) "운동 기록을 저장하면 추천이 나타납니다" else "지금은 부하가 적게 겹치는 후보가 없습니다", color = c.text2, fontSize = 14.sp)
                }
                items(recommendations, key = { "recommend_" + it.id }) { p ->
                    Surface(shape = RoundedCornerShape(18.dp), color = c.surface) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.primary.joinToString(" · ") { it.label }, color = c.text2, fontSize = 13.sp)
                            if (p.primary.any { m -> !snapshot.muscles.first { it.muscle == m }.known })
                                Text("주요 부위에 기록 없는 근육이 포함됩니다", color = c.text2, fontSize = 12.sp)
                            val already = app.workoutPlan.any { it.name == p.name && !it.done }
                            OutlinedButton(onClick = { app.addMuscleRecommendation(p.name) }, enabled = !already) {
                                Text(if (already) "운동 목록에 있음" else "운동 목록에 추가", color = if (already) c.text2 else c.primaryText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MuscleDetailCard(status: MuscleStatus, snapshot: LoadSnapshot) {
    val c = Trex.c
    val related = remember(snapshot.sets, status.muscle) {
        snapshot.sets.filter { set -> MuscleLoadEngine.doses(set).any { it.muscle == status.muscle && it.dose > 0 } }
            .sortedByDescending { it.endedAt }.take(3)
    }
    Surface(shape = RoundedCornerShape(22.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status.muscle.label, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (status.known) muscleScoreLabel(status.value) else "기록 없음", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (status.known) {
                LinearProgressIndicator(progress = { (status.value / 100).toFloat() }, modifier = Modifier.fillMaxWidth().height(5.dp), color = c.primaryText, trackColor = c.bg)
                Text("왼쪽 ${status.left.roundToInt()} · 오른쪽 ${status.right.roundToInt()}  |  최근 ${loadDate(status.lastAt!!)}", color = c.text2, fontSize = 12.sp)
                HorizontalDivider(color = c.bg)
                Text("최근 반영 기록", color = c.text2, fontSize = 12.sp)
                related.forEach { set ->
                    Text("${loadDate(set.endedAt)} · ${MuscleLoadCatalog.find(set.exerciseId)?.name} · ${loadAmount(set)}", color = c.text, fontSize = 13.sp)
                }
            } else Text("이 부위에 반영할 수행 기록이 아직 없어요", color = c.text2, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun SessionMuscleMap(app: AppViewModel, sessionId: String) {
    val c = Trex.c
    val sets = app.muscleLoad.sets
    val calculated by produceState<Triple<String, List<LoadSet>, Set<String>>?>(null, sets, sessionId) {
        value = withContext(Dispatchers.Default) { Triple(sessionId, sets, sessionUsedMuscles(sets, sessionId)) }
    }
    val used = calculated?.takeIf { it.first == sessionId && it.second == sets }?.third
    val keys = JSONArray((used ?: emptySet()).sorted()).toString()
    // 앞·뒷면 2D 근육도는 회전하지 않으며 위에서 시작한 터치도 페이지 스크롤에 사용한다.
    Box(Modifier.fillMaxWidth().padding(top = 20.dp).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
            Text("사용 근육", color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 2.dp, bottom = 12.dp))
            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF222527)) {
                Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                    key(sessionId) {
                        MuscleAtlasWebView("window.setUsedMuscles && window.setUsedMuscles($keys);", true,
                            "이번 운동에서 사용한 근육. 왼쪽 뒷면, 오른쪽 앞면", Modifier.fillMaxSize())
                    }
                    if (app.muscleLoadLoading || used == null) CircularProgressIndicator(color = c.primaryText)
                    if (app.muscleLoadError != null) {
                        Surface(color = Color(0xEE222527), shape = RoundedCornerShape(16.dp)) {
                            TextButton(onClick = app::syncMuscleLoad) { Text("기록 다시 불러오기", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

private fun loadDate(ms: Long) = monthDaySlashLabel(ms)
private fun loadAmount(s: LoadSet): String = when(s.unit) {
    LoadUnit.SECONDS -> "${s.seconds.roundToInt()}초"
    // 좌우 값은 지역 값으로 읽는다 — iPhone 에서는 LoadSet 이 다른 모듈(posture-core)이라 속성 스마트 캐스트가 안 된다(§108)
    LoadUnit.PAIR -> { val left = s.left; val right = s.right
        if(left!=null && right!=null && s.actualReps==null) "왼쪽 ${left.roundToInt()} · 오른쪽 ${right.roundToInt()}회" + (if(s.unknownSideSteps>0 || s.extraSteps>0) " · 일부 좌우 추정" else "") else "좌우 ${(s.actualReps?:s.reps).roundToInt()}회씩 · 배분 추정" }
    LoadUnit.EACH -> "${(s.actualReps?:s.reps).roundToInt()}사이클 · 좌우 배분 추정"
    else -> "${(s.actualReps?:s.reps).roundToInt()}회"
}

@Composable
private fun MuscleAtlas(snapshot: LoadSnapshot, selected: String, modifier: Modifier) {
    // 근육 값이 같으면 다시 만들지 않는다 — 명령이 같으면 MuscleAtlasWebView 도 JS 를 다시 돌리지 않는다(스펙 §115)
    val colors=remember(snapshot.muscles) { JSONObject().apply { snapshot.muscles.filter { it.known }.forEach {
        put(it.muscle.name+"_L",it.left);put(it.muscle.name+"_R",it.right)
    } }.toString() }
    MuscleAtlasWebView("window.setFatigue && window.setFatigue($colors,${JSONObject.quote(selected)});", false,
        "운동 기록 기반 근육 피로도. 왼쪽 뒷면, 오른쪽 앞면. 상세 값은 아래 목록에서 확인", modifier)
}

