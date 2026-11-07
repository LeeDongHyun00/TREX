package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.trainingload.MuscleLoadCatalog
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/**
 * 종목 상세(§131, 디자인 P4) — 부위·기구·자세 교정 지원, 최고 무게·추정 1RM(Epley, 10회 이하), 최고 무게 추이(최근 8세션), 횟수 추이(확정 횟수),
 * 세트 이력, 사용 근육·근거(부하 프로필), 가이드, 오늘 루틴에 넣기. 카메라가 뺀 회는 그래프에 넣지 않는다(원칙 #1).
 */
@Composable
fun ExerciseDetailScreen(app: AppViewModel, name: String, onBack: () -> Unit, onAddToPlan: () -> Unit, onAddManual: () -> Unit) {
    val c = Trex.c
    val uri = LocalUriHandler.current
    val template = app.catalogTemplates().firstOrNull { it.name == name }
    val profile = MuscleLoadCatalog.find(name)
    val custom = app.customExercises.firstOrNull { it.name == name }
    val sets = remember(app.workoutHistory) { app.workoutHistory.datedSets().filter { it.workoutName == name }.sortedByDescending { it.epochDay } }
    val byDay = sets.groupBy { it.epochDay }.entries.sortedByDescending { it.key }
    val best = app.workoutHistory.bestKg(name)
    val oneRm = best?.set?.let { s -> s.kg?.let { kg -> s.reps?.let { estimatedOneRepMax(kg, it) } } }
    val kgTrend = byDay.take(8).reversed().map { (day, list) -> day to (list.mapNotNull { it.set.kg }.maxOrNull() ?: 0.0) }
    val repTrend = byDay.take(8).reversed().map { (day, list) -> day to list.filter { !it.set.estimated }.mapNotNull { it.set.countedReps } }
    var showAll by remember { mutableStateOf(false) }
    var evidenceOpen by remember { mutableStateOf(false) }
    val region = catalogRegionOf(name, template?.category ?: "")
    val guide = ExerciseGuides.forName(name)
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Column { Text("$region · ${custom?.equipment ?: catalogEquipmentOf(name)}", color = c.text2, fontSize = 12.sp); Text(name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 머리 그림(UI 감사 A11, K1·K6) — 가이드가 있는 16종은 시범 첫 프레임, 나머지는 부위 글리프. 그림은 자세 정답이 아니다(ExerciseGuides 머리말)
            item {
                Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                    if (guide != null) AssetArtwork(guide.asset, Modifier.fillMaxSize().padding(6.dp))
                    else BodyPartGlyph(bodyPartOf(region), 96.dp, background = c.surface)
                    Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (postureSupported(name)) PostureSupportPill() else if (custom != null) WashPill("내가 추가")
                        if (app.workoutPlan.any { it.name == name }) WashPill("오늘 루틴에 있음")
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("최고 무게", color = c.text2, fontSize = 12.sp)
                            Text(best?.set?.kg?.let { "${trimKg(it)} kg" } ?: "—", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Text(best?.let { b -> LocalDate.fromEpochDays(b.epochDay).let { "${it.monthValue}월 ${it.day}일 · ${b.set.reps}회" } } ?: "기록이 쌓이면 나타나요", color = c.text2, fontSize = 12.sp)
                        }
                    }
                    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("추정 1RM", color = c.text2, fontSize = 12.sp)
                            Text(oneRm?.let { "${it.roundToInt()} kg" } ?: "—", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Text(if (oneRm != null) "${trimKg(best!!.set.kg!!)} kg × ${best.set.reps} · Epley · 추정" else "10회 이하 세트에서 추정해요", color = c.text2, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (kgTrend.any { it.second > 0 }) item { TrendCard("최고 무게 추이", "최근 ${kgTrend.size}세션", kgTrend.map { it.first to it.second }, unit = "kg") }
            if (repTrend.any { it.second.isNotEmpty() }) item {
                val avg = repTrend.flatMap { it.second }.let { if (it.isEmpty()) 0.0 else it.average() }
                val dropped = sets.sumOf { d -> d.set.cameraReps?.let { cam -> d.set.reps?.let { r -> (cam - r).coerceAtLeast(0) } } ?: 0 }
                TrendCard("횟수 추이 · 확정 횟수", if (sets.any { it.set.cameraReps != null }) "자세 교정 세트" else "직접 기록",
                    repTrend.map { it.first to (it.second.maxOrNull() ?: 0).toDouble() }, unit = "회",
                    note = "최근 세트 평균 ${formatOneDecimal(avg.toFloat())}회." + if (dropped > 0) " 카메라 값과 다르게 고친 ${dropped}회는 그래프에 넣지 않아요." else "")
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("세트 이력", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                        if (byDay.isEmpty()) Text("아직 이 종목의 기록이 없어요", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                        byDay.take(if (showAll) byDay.size else 6).forEach { (day, list) ->
                            HorizontalDivider(color = c.line)
                            val date = LocalDate.fromEpochDays(day)
                            val entries = list.map { it.set }.sortedBy { it.index }
                            val isBest = best != null && best.epochDay == day && entries.any { it.kg == best.set.kg }
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${date.monthValue}월 ${date.day}일", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(entries.summaryLine() + (entries.mapNotNull { it.rpe }.lastOrNull()?.let { " · RPE $it" } ?: "") + (if (entries.any { !it.memo.isNullOrBlank() }) " · 메모" else ""),
                                        color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                                    entries.mapNotNull { it.memo }.distinct().forEach { Text("\"$it\"", color = c.text3, fontSize = 12.sp) }
                                }
                                if (isBest) WashPill("PR")
                            }
                        }
                        if (byDay.size > 6 && !showAll) GhostButton("지난 기록 더 보기", { showAll = true }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("사용 근육", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            // 근거·각주는 ⓘ 뒤로(UI 감사 A11, K2 글은 둘째 자리) — 그림(근육도)과 이름 한 줄이 1차
                            if (profile != null) IconButton(onClick = { evidenceOpen = !evidenceOpen }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Info, if (evidenceOpen) "근거 접기" else "근거 보기", tint = c.text3, modifier = Modifier.size(18.dp)) }
                        }
                        if (profile == null) Text("근육 부하 추정 없음", color = c.text2, fontSize = 13.sp)
                        else {
                            // 완료 화면의 '사용 근육' 과 같은 2D 근육도(사용 = primary) — 주 근육(가중치 ≥ .75)만 칠하고 보조는 글로
                            val keys = remember(profile.id) { profile.primary.flatMap { m -> listOf("${m.name}_L", "${m.name}_R") }.joinToString(",", "[", "]") { "\"$it\"" } }
                            Surface(shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.graphics.Color(0xFF222527), modifier = Modifier.fillMaxWidth()) {
                                Box(Modifier.fillMaxWidth().height(260.dp)) {
                                    MuscleAtlasWebView("window.setUsedMuscles && window.setUsedMuscles($keys);", true, "$name 의 주 근육. 왼쪽 뒷면, 오른쪽 앞면", Modifier.fillMaxSize())
                                }
                            }
                            val primary = profile.primary.map { it.label }
                            val secondary = profile.weights.filterValues { it < .75 }.entries.sortedByDescending { it.value }.map { it.key.label }
                            Text("주요 · " + primary.joinToString(" · "), color = c.text, fontSize = 14.sp)
                            if (secondary.isNotEmpty()) Text("보조·안정 · " + secondary.joinToString(" · "), color = c.text2, fontSize = 13.sp)
                            if (evidenceOpen) {
                                Text(profile.evidence, color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                                if (profile.sources.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    profile.sources.take(3).forEachIndexed { i, url -> TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("근거 ${i + 1}", color = c.primaryText, fontSize = 12.sp) } }
                                }
                                Text("역할 가중치는 EMG 비율이 아니라 제품 초기값이에요(docs/MUSCLE_LOAD_IMPLEMENTATION.md).", color = c.text3, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
            item { ExerciseGuidePreview(name) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("기록 추가", onAddManual, Modifier.weight(1f))
            Cta("오늘 루틴에 넣기", onAddToPlan, Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun TrendCard(title: String, subtitle: String, points: List<Pair<Long, Double>>, unit: String, note: String? = null) {
    val c = Trex.c
    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = c.text2, fontSize = 12.sp) }
            val max = points.maxOf { it.second }.coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                points.forEach { (day, v) ->
                    val date = LocalDate.fromEpochDays(day)
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (v > 0) trimKg(v) else "", color = c.text2, fontSize = 10.sp)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.widthIn(max = 26.dp).fillMaxWidth().height((64 * v / max).coerceAtLeast(2.0).dp).clip(RoundedCornerShape(5.dp)).background(if (v > 0) c.primary else c.track))
                        }
                        Text("${date.monthValue}/${date.day}", color = c.text3, fontSize = 10.sp)
                    }
                }
            }
            Text("단위 $unit", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            note?.let { Text(it, color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
