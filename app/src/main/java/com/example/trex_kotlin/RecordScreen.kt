package com.example.trex_kotlin

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.FormLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** 날짜는 접힌 목록으로 시작한다. 숫자 요약과 실제 저장 내용을 분리한다. */
@Composable
fun RecordScreen(app: AppViewModel, onBack: () -> Unit) {
    val c = Trex.c
    val today = LocalDate.ofEpochDay(app.calendarDay)
    val days = (0..6).map { today.minusDays(it.toLong()) }.map { date ->
        date to app.workoutHistory.firstOrNull { it.epochDay == date.toEpochDay() }
    }
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activeDays = days.count { it.second?.items?.isNotEmpty() == true }
    val minutes = days.sumOf { it.second?.totalMinutes() ?: 0 }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "기록 뒤로가기", tint = c.text) }
            Text("운동 기록", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "summary") {
                Text("최근 7일", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text("${days.last().first.monthValue}.${days.last().first.dayOfMonth} – ${today.monthValue}.${today.dayOfMonth}",
                    color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    RecordMetric("${activeDays}일", "운동한 날")
                    RecordMetric("${minutes}분", "운동 시간")
                }
                RecordWeekChart(days.reversed(), expanded) { day ->
                    expanded = day
                    scope.launch { listState.animateScrollToItem(1 + days.indexOfFirst { it.first.toEpochDay() == day }) }
                }
            }
            items(days.size, key = { days[it].first.toEpochDay() }) { index ->
                val (date, record) = days[index]
                val open = expanded == date.toEpochDay()
                val chevron by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "record-chevron")
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else date.toEpochDay() }
                            .semantics { contentDescription = "${date.monthValue}월 ${date.dayOfMonth}일 기록"; stateDescription = if (open) "펼침" else "접힘" }
                            .padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), color = c.text,
                                        fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Text("${date.monthValue}.${date.dayOfMonth}", color = c.text2, fontSize = 14.sp)
                                    if (date == today) Text("오늘", color = c.primaryText, fontSize = 12.sp)
                                }
                                Text(if (record?.items?.isNotEmpty() == true) "${record.items.size}세트 · ${record.totalMinutes()}분" else "기록 없음",
                                    color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(20.dp).rotate(chevron))
                        }
                        AnimatedVisibility(open, enter = expandVertically(tween(250)) + fadeIn(tween(180)),
                            exit = shrinkVertically(tween(220)) + fadeOut(tween(120))) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                                HorizontalDivider(color = c.line)
                                if (record?.items?.isNotEmpty() != true) Text("이날 저장된 운동이 없어요.", color = c.text2,
                                    fontSize = 14.sp, modifier = Modifier.padding(vertical = 20.dp))
                                record?.items?.forEachIndexed { i, item ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                                        Text(item.workoutName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(item.reps, color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                                        item.postureCorrection?.let { pc ->
                                            val (line1, line2) = postureLines(pc)
                                            Column(Modifier.padding(top = 10.dp)) {
                                                Text(line1, color = c.text2, fontSize = 13.sp, lineHeight = 20.sp)
                                                line2?.let { Text(it, color = c.text2, fontSize = 13.sp, lineHeight = 20.sp) }
                                                selfLabelText(pc)?.let { Text(it, color = c.primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                                                val detected = pc.repsValid?.let { it + (pc.repsPartial ?: 0) }
                                                if (pc.actualReps != null && detected != null && pc.actualReps != detected)
                                                    Text("앱 검출 ${detected}회", color = c.text3, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    if (i < record.items.lastIndex) HorizontalDivider(color = c.line)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 막대 높이는 실제 저장된 완료 세트 수다. 기록 없는 날은 바닥 선만 남긴다. */
@Composable
private fun RecordWeekChart(days: List<Pair<LocalDate, WorkoutHistoryDay?>>, selected: Long?, onSelect: (Long) -> Unit) {
    val c = Trex.c
    val maximum = days.maxOf { it.second?.items?.size ?: 0 }.coerceAtLeast(1)
    Text("완료 세트", color = c.text2, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 22.dp).height(144.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { (date, record) ->
            val count = record?.items?.size ?: 0
            val fraction by animateFloatAsState(count.toFloat() / maximum, tween(280), label = "record-bar")
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(date.toEpochDay()) }
                .semantics { contentDescription = "${date.monthValue}월 ${date.dayOfMonth}일 그래프 · ${count}세트" },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$count", color = c.text2, fontSize = 11.sp)
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.widthIn(max = 28.dp).fillMaxWidth().height((88f * fraction).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(5.dp)).background(if (count == 0) c.track else if (selected == date.toEpochDay()) c.primaryText else c.primary))
                }
                Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN), color = c.text2, fontSize = 12.sp)
                Text("${date.dayOfMonth}", color = c.text3, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RecordMetric(value: String, label: String) {
    Column {
        Text(value, color = Trex.c.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Trex.c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * 기록 카드의 자세 문구 — (첫 줄: 관찰, 둘째 줄: 교정, 없으면 null).
 * 판정하지 않은 것을 판정한 것처럼 말하지 않는다: UNJUDGED 는 "없음"이라고 말하고, 베타 참고는 "검증 중"임을 밝힌다.
 */
private fun postureLines(pc: PostureCorrection): Pair<String, String?> {
    // TRACK 은 판정이 아니라 측정값(summaryLine)만 — 모집단 기준을 숙련자에게 지적으로 보이지 않는다(spec §29)
    if (pc.mode == "track") return pc.focus to null
    return when (pc.kind) {
        "clean" -> pc.focus to null   // "자세 깨끗했어요" 또는 베타만 판정된 세트의 "검증 중인 항목 기준으로는 이상 없었어요"
        "unjudged" -> pc.focus.ifBlank { "자세 판정 없음" } to null
        "habit", "drift", "violation", "recovered" -> pc.focus to pc.fix?.takeIf { it.isNotBlank() }?.let { "다음엔 $it" }
        "reference" -> "참고: ${pc.focus} (검증 중인 항목)" to null
        // kind 없는 항목은 TrexStore 가 로드 시 버린다(§30 이전 목업) — 남아 있어도 지어낸 지적 문구는 쓰지 않는다
        else -> pc.focus to null
    }
}

/** 왼쪽 세로 막대 — 깨끗/교정됨은 primary, 지적은 warn, 참고·유보·구 데이터는 연한 선. TRACK 은 세트 내 변화(점점/교정)만 색을 준다(§29). */
private fun postureBarColor(c: TrexColors, pc: PostureCorrection): Color = when {
    pc.mode == "track" -> when (pc.kind) { "drift" -> c.warn; "recovered" -> c.primary; else -> c.primarySoftLine }
    pc.kind == "clean" || pc.kind == "recovered" -> c.primary
    pc.kind == "habit" || pc.kind == "drift" || pc.kind == "violation" -> c.warn
    else -> c.primarySoftLine
}

/** "내 평가 · 좋았음 · 실제 12회" — 자가 라벨이 하나도 없으면 null (칩 줄 자체를 그리지 않는다). */
private fun selfLabelText(pc: PostureCorrection): String? {
    val parts = buildList {
        FormLabel.from(pc.formLabel)?.let { add(it.displayName) }
        pc.actualReps?.let { add("실제 ${it}회") }
    }
    return if (parts.isEmpty()) null else (listOf("내 평가") + parts).joinToString(" · ")
}

private fun dayTitle(record: WorkoutHistoryDay): String {
    val cats = record.items.map { it.workoutName }
    return when {
        cats.size >= 4 -> "전신 루틴"
        cats.any { it.contains("스쿼트") || it.contains("런지") } && cats.any { it.contains("플랭크") || it.contains("버드독") } -> "하체 + 코어"
        cats.any { it.contains("스쿼트") || it.contains("런지") } -> "하체 루틴"
        cats.any { it.contains("플랭크") } -> "코어 루틴"
        cats.any { it.contains("스트레칭") } -> "가벼운 스트레칭"
        else -> "운동 루틴"
    }
}
