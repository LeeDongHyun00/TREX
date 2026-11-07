package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** 체중·신체 기록(§131, 디자인 P7) — 최근 값, 7일·30일 전 대비, 기간별 추이, 기록 목록(체지방은 선택), 오늘 기록하기. 체중이 바뀌면 영양 목표와 근육 부하의 체격 계수가 최신 값을 쓴다. */
@Composable
fun BodyWeightScreen(app: AppViewModel, onBack: () -> Unit) {
    val c = Trex.c
    val list = app.bodyWeights.sortedByDescending { it.epochDay * 10 + if (it.slot == "evening") 1 else 0 }
    val latest = app.latestBodyWeight
    var range by remember { mutableIntStateOf(90) }
    var recording by remember { mutableStateOf(false) }
    val today = todayEpochDay()
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Text("체중 기록", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (app.account != null) Text(if (app.sync.lastSyncedAt > 0) "동기화 · ${monthDaySlashLabel(app.sync.lastSyncedAt)}" else "", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(latest?.let { "최근 · ${LocalDate.fromEpochDays(it.epochDay).let { d -> "${d.monthValue}월 ${d.day}일" }} ${it.slotLabel}" } ?: "아직 기록이 없어요", color = c.text2, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                            Text(latest?.let { trimKg(it.kg) } ?: trimKg(app.profile.weightKg), color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                            Text(" kg" + if (latest == null) " · 프로필 값" else "", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        if (latest != null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            listOf(7 to "7일 전보다", 30 to "30일 전보다").forEach { (d, label) ->
                                val diff = app.bodyWeights.changeSince(latest, d)
                                Column { Text(label, color = c.text2, fontSize = 12.sp); Text(diff?.let { (if (it > 0) "+" else if (it < 0) "−" else "") + trimKg(abs(it)) + " kg" } ?: "기록 없음", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
            item {
                FilterChipRow(listOf("30일", "90일", "1년"), when (range) { 30 -> "30일"; 365 -> "1년"; else -> "90일" }, { range = when (it) { "30일" -> 30; "1년" -> 365; else -> 90 } })
            }
            item {
                val points = app.bodyWeights.filter { it.epochDay >= today - range }.sortedBy { it.epochDay }
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        if (points.isEmpty()) Text("체중을 기록하면 추이가 나타나요", color = c.text2, fontSize = 13.sp)
                        else {
                            val min = points.minOf { it.kg }; val max = points.maxOf { it.kg }; val span = (max - min).coerceAtLeast(0.5)
                            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                points.takeLast(40).forEach { e ->
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                                        // 값이 모두 같으면(기록 1건 포함) 가운데 높이 — 바닥에 붙은 막대가 '낮다' 로 읽히지 않게. 막대 폭은 상한을 둬 1건이 화면을 다 덮지 않는다.
                                        Box(Modifier.widthIn(max = 28.dp).fillMaxWidth().height((if (max == min) 65.0 else 20 + 90 * (e.kg - min) / span).dp).clip(RoundedCornerShape(3.dp)).background(c.primary))
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LocalDate.fromEpochDays(points.first().epochDay).let { "${it.monthValue}/${it.day}" }, color = c.text3, fontSize = 11.sp)
                                Text(if (max == min) "${trimKg(min)} kg" else "${trimKg(min)} ~ ${trimKg(max)} kg", color = c.text3, fontSize = 11.sp)
                                if (points.size > 1) Text(LocalDate.fromEpochDays(points.last().epochDay).let { "${it.monthValue}/${it.day}" }, color = c.text3, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            item { WashBanner("체중이 바뀌면 영양 목표와 근육 부하의 체격 계수가 최신 값을 써요.", Icons.Rounded.Info) }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("기록 ${list.size}건", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text("체지방은 선택 입력", color = c.text2, fontSize = 12.sp) }
                        list.take(30).forEach { e ->
                            HorizontalDivider(color = c.line)
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(LocalDate.fromEpochDays(e.epochDay).let { "${it.monthValue}월 ${it.day}일 ${e.slotLabel}" } + if (e.fasting) " · 공복" else "", color = c.text, fontSize = 14.sp)
                                    Text("${trimKg(e.kg)} kg" + (e.bodyFatPercent?.let { " · 체지방 ${trimKg(it)}%" } ?: ""), color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                                IconButton(onClick = { app.deleteBodyWeight(e) }) { Icon(Icons.Rounded.DeleteOutline, "삭제", tint = c.text3) }
                            }
                        }
                    }
                }
            }
        }
        Cta("오늘 체중 기록하기", { recording = true }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth())
    }
    if (recording) BodyWeightSheet(app, onClose = { recording = false })
}

@Composable
private fun BodyWeightSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    var kg by remember { mutableStateOf(app.latestBodyWeight?.kg?.let(::trimKg) ?: trimKg(app.profile.weightKg)) }
    var fat by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf(if (currentHour() < 12) "morning" else "evening") }
    var fasting by remember { mutableStateOf(false) }
    val valid = kg.toDoubleOrNull()?.let { it in 20.0..350.0 } == true && (fat.isBlank() || fat.toDoubleOrNull()?.let { it in 1.0..70.0 } == true)
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("오늘 체중", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            DField(kg, { kg = it.decimalOnly().take(6) }, "체중", label = "체중 · kg", keyboardType = KeyboardType.Decimal)
            DField(fat, { fat = it.decimalOnly().take(5) }, "선택", label = "체지방 · % (선택)", keyboardType = KeyboardType.Decimal)
            FilterChipRow(listOf("아침", "저녁"), if (slot == "morning") "아침" else "저녁", { slot = if (it == "아침") "morning" else "evening" })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fasting, onCheckedChange = { fasting = it }, colors = postureSwitchColors())
                Text("공복에 쟀어요", color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("취소", onClose, Modifier.weight(1f))
                Cta("저장", { app.recordBodyWeight(kg.toDouble(), fat.toDoubleOrNull(), slot, fasting); onClose() }, Modifier.weight(1.6f), enabled = valid)
            }
        }
    }
}
