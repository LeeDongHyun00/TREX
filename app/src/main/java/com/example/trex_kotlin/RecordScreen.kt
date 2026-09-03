package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.FormLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 주간 운동 기록 화면 (리디자인 — 기록 탭 대신 리모컨/프로필에서 진입).
 * 주간 요약 카드(막대 그래프 탭 → 해당 일 선택) + 일별 상세 카드.
 */
@Composable
fun RecordScreen(app: AppViewModel, onBack: () -> Unit) {
    val c = Trex.c
    val today = LocalDate.now()
    val weekDates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val days = weekDates.map { d -> d to app.workoutHistory.firstOrNull { it.epochDay == d.toEpochDay() } }
    var selectedDay by rememberSaveable { mutableIntStateOf(days.indexOfLast { it.second?.items?.isNotEmpty() == true }.coerceAtLeast(0)) }

    val totalCount = days.sumOf { it.second?.items?.size ?: 0 }
    val accDays = days.mapNotNull { it.second }.flatMap { it.items }.mapNotNull { it.accuracy }
    val avgAcc = if (accDays.isEmpty()) null else accDays.average().toInt()
    val activeDayCount = days.count { it.second?.items?.isNotEmpty() == true }
    val totalMinutes = days.sumOf { it.second?.totalMinutes() ?: 0 }
    val maxItems = days.maxOf { it.second?.items?.size ?: 0 }.coerceAtLeast(1)
    val rangeLabel = "${weekDates.first().monthValue}월 ${weekDates.first().dayOfMonth}일 – ${weekDates.last().dayOfMonth}일"

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 112.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                DCard(radius = 26.dp) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Kicker("이번 주 완료")
                                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.Bottom) {
                                    Text("$totalCount", color = c.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
                                    Text("개", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
                                }
                            }
                            if (avgAcc != null) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.primaryWash).padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.TrendingUp, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(12.dp))
                                    Text("평균 $avgAcc%", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                        Row(
                            Modifier.padding(top = 18.dp).height(112.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            days.forEachIndexed { i, (date, record) ->
                                val n = record?.items?.size ?: 0
                                val sel = selectedDay == i
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { selectedDay = i },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(((n / maxItems.toFloat()) * 84).coerceAtLeast(4f).dp)
                                                .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                                .background(if (n == 0) c.track else if (sel) c.primary else c.primarySoftLine),
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .width(24.dp).height(20.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (sel) c.primary else Color.Transparent),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN),
                                            color = if (sel) Color.White else c.text3, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                        Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(1.dp).background(c.line))
                        Row(Modifier.padding(top = 15.dp).fillMaxWidth()) {
                            listOf(
                                "${activeDayCount}일" to "운동한 날",
                                "${totalMinutes}분" to "총 운동 시간",
                                "${app.attendanceStreak()}일" to "최장 연속",
                            ).forEach { (v, label) ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(v, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                                    Text(label, color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("일별 상세")
                    Spacer(Modifier.weight(1f))
                    Text("막대를 누르면 하루가 선택돼룡", color = c.text3, fontSize = 11.sp)
                }
            }

            items(count = days.size) { i ->
                val (date, record) = days[i]
                val sel = selectedDay == i
                val has = record?.items?.isNotEmpty() == true
                val dayAcc = record?.items?.mapNotNull { it.accuracy }?.takeIf { it.isNotEmpty() }?.average()?.toInt()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = if (has) c.surface else Color.Transparent,
                    contentColor = c.text,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (sel && has) c.primarySoftLine else c.line),
                    shadowElevation = if (sel && has) 2.dp else 0.dp,
                ) {
                    Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (has) (if (sel) c.primary else c.primaryWash) else c.surface2),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN),
                                    color = (if (has) (if (sel) Color.White else c.primaryText) else c.text3).copy(alpha = 0.75f),
                                    fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${date.dayOfMonth}",
                                    color = if (has) (if (sel) Color.White else c.primaryText) else c.text3,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    text = if (has) dayTitle(record!!) else if (date >= today) "예정" else "휴식",
                                    color = if (has) c.text else c.text3,
                                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (has) "${record!!.items.size}개 운동 · ${record.totalMinutes()}분" else if (date >= today) "아직 기록 전" else "기록 없음",
                                    color = c.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            if (has && dayAcc != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("$dayAcc%", color = c.primaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("정확도", color = c.text3, fontSize = 9.5.sp)
                                }
                            }
                        }
                        if (has) {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                record!!.items.forEach { item ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(c.surface2)
                                            .border(1.dp, c.line, RoundedCornerShape(18.dp))
                                            .padding(horizontal = 14.dp, vertical = 13.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(9.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) { Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(13.dp)) }
                                            Text(item.workoutName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp).weight(1f))
                                            Text(item.reps, color = c.text3, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                                            item.accuracy?.let { acc ->
                                                Text(
                                                    "$acc%",
                                                    color = if (acc >= 92) c.primaryText else c.text3,
                                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier
                                                        .padding(start = 8.dp)
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(if (acc >= 92) c.primaryWash else c.surface)
                                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                        item.postureCorrection?.let { pc ->
                                            val (line1, line2) = postureLines(pc)
                                            val selfLabel = selfLabelText(pc)
                                            val appReps = pc.repsValid?.let { it + (pc.repsPartial ?: 0) }
                                            Row(Modifier.padding(top = 10.dp).height(IntrinsicSize.Min)) {
                                                // 막대 색은 판정 종류로 — 정확도 문턱(92%)은 TRACK 에서 accuracy 가 null 이라 기준이 못 된다
                                                Box(
                                                    Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(999.dp))
                                                        .background(postureBarColor(c, pc)),
                                                )
                                                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                                    Text(line1, color = c.text2, fontSize = 11.5.sp, lineHeight = 16.sp)
                                                    line2?.let { Text(it, color = c.text3, fontSize = 11.5.sp, lineHeight = 16.sp) }
                                                    if (selfLabel != null) {
                                                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                selfLabel,
                                                                color = c.text2, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(999.dp))
                                                                    .background(c.surface)
                                                                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                                                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                                            )
                                                            // 앱 카운트와 사용자 라벨이 다르면 그대로 드러낸다 — 카운터 오차를 숨기지 않는다
                                                            if (pc.actualReps != null && appReps != null && appReps != pc.actualReps) {
                                                                Text("(앱 $appReps)", color = c.text3, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 상단 글래스 헤더
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            color = c.navGlass,
            contentColor = c.text,
        ) {
            Column {
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 46.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, onClick = onBack, contentDescription = "뒤로")
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Kicker("운동 기록")
                        Text(rangeLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                    }
                    WashPill("주간")
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            }
        }
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
        "unjudged" -> "자세 판정 없음 — 화면에 충분히 잡히지 않았어요" to null
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
