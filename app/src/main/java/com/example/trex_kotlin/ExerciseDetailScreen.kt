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
    // 1RM(Epley)은 외부 중량 종목에만 — 맨몸 + 추가 중량은 체중 비율이 섞여 추정하지 않는다(근거 감사 F5)
    val kind = exerciseLoadKind(name)
    val oneRm = if (kind.supportsOneRepMax()) best?.set?.let { s -> s.kg?.let { kg -> s.reps?.let { estimatedOneRepMax(kg, it) } } } else null
    val kgTrend = byDay.take(8).reversed().map { (day, list) -> day to (list.mapNotNull { it.set.kg }.maxOrNull() ?: 0.0) }
    val repTrend = byDay.take(8).reversed().map { (day, list) -> day to list.filter { !it.set.estimated }.mapNotNull { it.set.countedReps } }
    var showAll by remember { mutableStateOf(false) }
    var evidenceOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        // 이름만 — '등 · 맨몸' 부위·기구 줄은 뺐다(사용자 요청 2026-11-07, 카탈로그 행이 이미 말한다)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Text(name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 맨 위는 사용 근육(사용자 요청 2026-11-07 — 머리 그림 카드는 뺐다). 지원·루틴 표시는 이 카드 머리줄에
            item { UsedMusclesCard(app, name, profile, custom != null, evidenceOpen, { evidenceOpen = !evidenceOpen }, uri) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (kind == ExerciseLoadKind.EXTERNAL) "최고 무게" else "최고 추가 중량", color = c.text2, fontSize = 12.sp)
                            Text(best?.set?.kg?.let { "${if (kind == ExerciseLoadKind.EXTERNAL) "" else "+"}${trimKg(it)} kg" } ?: "—", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Text(best?.let { b -> LocalDate.fromEpochDays(b.epochDay).let { "${it.monthValue}월 ${it.day}일 · ${b.set.reps}회" } } ?: if (kind == ExerciseLoadKind.EXTERNAL) "기록이 쌓이면 나타나요" else "맨몸 · 조끼·벨트를 더하면 적어요", color = c.text2, fontSize = 12.sp)
                        }
                    }
                    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (kind == ExerciseLoadKind.EXTERNAL) "추정 1RM" else "최다 횟수", color = c.text2, fontSize = 12.sp)
                            // 맨몸 종목은 1RM 대신 한 세트 최다 횟수 — 체중 비율을 지어내지 않는다
                            val bestReps = if (kind == ExerciseLoadKind.EXTERNAL) null else sets.mapNotNull { it.set.reps }.maxOrNull()
                            Text(oneRm?.let { "${it.roundToInt()} kg" } ?: bestReps?.let { "${it}회" } ?: "—", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Text(when {
                                oneRm != null -> "${trimKg(best!!.set.kg!!)} kg × ${best.set.reps} · Epley · 추정"
                                kind == ExerciseLoadKind.EXTERNAL -> "10회 이하 세트에서 추정해요"
                                kind == ExerciseLoadKind.BODYWEIGHT_FULL -> "볼륨은 체중 + 추가 중량 · 체중 기록 기준"
                                else -> "맨몸 · 1RM 은 추정하지 않아요"
                            }, color = c.text2, fontSize = 12.sp)
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
            item { ExerciseGuidePreview(name) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("기록 추가", onAddManual, Modifier.weight(1f))
            Cta("오늘 루틴에 넣기", onAddToPlan, Modifier.weight(1.4f))
        }
    }
}

/** 사용 근육 카드 — 2D 근육도(주 근육 = primary) + 주요/보조 한 줄, 근거·각주는 ⓘ 뒤(UI 감사 A11, K2). 머리줄 오른쪽에 자세 교정 지원·내가 추가·오늘 루틴 표시. */
@Composable
private fun UsedMusclesCard(app: AppViewModel, name: String, profile: com.example.trex_kotlin.trainingload.ExerciseLoadProfile?, mine: Boolean,
    evidenceOpen: Boolean, onToggleEvidence: () -> Unit, uri: androidx.compose.ui.platform.UriHandler) {
    val c = Trex.c
    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("사용 근육", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // 근거가 약한 가중치(다른 종목에서 옮김·출처 없음)는 '추정' 으로 밝힌다(원칙 #2, 근거 감사 F2)
                if (profile?.evidenceWeak == true) Text("추정", color = c.warn, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.warnWash).padding(horizontal = 8.dp, vertical = 3.dp))
                if (postureSupported(name)) PostureSupportPill() else if (mine) WashPill("내가 추가")
                if (app.workoutPlan.any { it.name == name }) WashPill("오늘 루틴에 있음")
                if (profile != null) IconButton(onClick = onToggleEvidence, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Info, if (evidenceOpen) "근거 접기" else "근거 보기", tint = c.text3, modifier = Modifier.size(18.dp)) }
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
