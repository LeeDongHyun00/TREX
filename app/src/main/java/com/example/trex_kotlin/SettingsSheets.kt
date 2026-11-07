package com.example.trex_kotlin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/*
 * 설정 하위 시트(§131) — 기록 보관 기간(P14), 기록 내보내기(P15), 알림(P16). 전부 기기 안의 일이다 — 서버는 관여하지 않는다.
 */

/** 기록 보관 기간 — 근육 피로도는 어느 쪽이든 최근 7일만 계산한다. 기간을 줄이면 그 밖의 기록이 이 기기와 백업에서 지워진다. */
@Composable
fun RetentionSheet(app: AppViewModel, onClose: () -> Unit, onExport: () -> Unit) {
    val c = Trex.c
    var choice by remember { mutableIntStateOf(app.retentionDays) }
    val shrinking = choice != RetentionOptions.UNLIMITED && (app.retentionDays == RetentionOptions.UNLIMITED || choice < app.retentionDays)
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("기록 보관 기간", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); SheetClose(onClose) }
            Text("운동 기록을 이 기기와 백업에 얼마나 둘지 정해요. 근육 피로도는 어느 쪽이든 최근 ${RECORD_WINDOW_DAYS}일만 계산해요.", color = c.text2, fontSize = 13.sp, lineHeight = 19.sp)
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    RetentionOptions.choices.forEachIndexed { i, days ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        Row(Modifier.fillMaxWidth().clickable { choice = days }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(RetentionOptions.label(days), color = c.text, fontSize = 15.sp)
                                    if (days == RetentionOptions.UNLIMITED) WashPill("추천")
                                }
                                Text(when (days) { 7 -> "예전 기본값"; RetentionOptions.UNLIMITED -> "달력·추이·연속 일수가 온전해요"; else -> "" }, color = c.text2, fontSize = 12.sp)
                            }
                            RadioButton(selected = choice == days, onClick = { choice = days }, colors = RadioButtonDefaults.colors(selectedColor = c.primary))
                        }
                    }
                }
            }
            if (shrinking) WashBanner("기간을 줄이면 그 밖의 운동 기록이 이 기기와 백업에서 지워져요. 지우기 전에 내보내기를 권해요.", Icons.Rounded.Warning, warnTone = true)
            if (shrinking) GhostButton("먼저 내보내기", onExport, Modifier.fillMaxWidth())
            Cta("저장하기", { app.chooseRetentionDays(choice); onClose() }, Modifier.fillMaxWidth())
        }
    }
}

/** 기록 내보내기 — CSV/JSON · 전체/올해/최근 90일 · 운동·체중·식단. 자세 판정 원본(관절 좌표)과 음식 사진은 넣지 않는다 — 기기 밖으로 내보내지 않는다. */
@Composable
fun ExportSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val share = rememberTextSharer()
    var json by remember { mutableStateOf(false) }
    var period by remember { mutableIntStateOf(0) }
    var body by remember { mutableStateOf(true) }
    var diet by remember { mutableStateOf(false) }
    val today = localToday()
    val setCount = app.workoutHistory.sumOf { d -> d.items.sumOf { if (it.sets.isEmpty()) parseReps(it.reps).sets else it.sets.size } }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("기록 내보내기", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); SheetClose(onClose) }
            Text("형식", color = c.text2, fontSize = 13.sp); FilterChipRow(listOf("CSV", "JSON"), if (json) "JSON" else "CSV", { json = it == "JSON" })
            Text("CSV 열: 날짜 · 종목 · 세트 · kg · 회 · 카메라 횟수 · 초 · RPE · 출처 · 메모", color = c.text3, fontSize = 11.sp)
            Text("기간", color = c.text2, fontSize = 13.sp); FilterChipRow(listOf("전체", "올해", "최근 90일"), listOf("전체", "올해", "최근 90일")[period], { period = listOf("전체", "올해", "최근 90일").indexOf(it) })
            Text("포함", color = c.text2, fontSize = 13.sp)
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    IncludeRow("운동 기록", "${app.workoutHistory.count { it.items.isNotEmpty() }}일 · ${setCount}세트", true, null)
                    HorizontalDivider(color = c.line)
                    IncludeRow("체중 기록", "${app.bodyWeights.size}건", body) { body = it }
                    HorizontalDivider(color = c.line)
                    IncludeRow("식단 기록", "끼니·영양", diet) { diet = it }
                }
            }
            WashBanner("자세 판정 원본(관절 좌표)과 음식 사진은 포함되지 않아요 — 기기 밖으로 내보내지 않습니다.", Icons.Rounded.Info)
            Cta("내보내기", {
                val from = when (period) { 1 -> LocalDate(today.year, 1, 1).toEpochDay(); 2 -> today.toEpochDay() - 89; else -> null }
                share(app.exportRecords(json, from, body, diet))
                onClose()
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun IncludeRow(title: String, detail: String, checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = c.text, fontSize = 15.sp); Text(detail, color = c.text2, fontSize = 12.sp) }
        if (onChange == null) Icon(Icons.Rounded.Check, null, tint = c.primaryText) else Switch(checked = checked, onCheckedChange = onChange, colors = postureSwitchColors())
    }
}

/** 알림 — 운동 요일(주간 루틴과 같은 요일)·휴식 끝·개인 기록/연속. 기기 안에서만 예약하고 서버 푸시가 아니다. 끊긴 연속을 돈으로 되살리는 기능은 없다. */
@Composable
fun NotifySheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val links = rememberSystemSettingsLinks()
    val reminders = rememberReminderScheduler()
    val prefs = app.notifyPrefs
    fun update(p: NotifyPrefs) {
        app.updateNotifyPrefs(p)
        reminders.scheduleWorkoutDays(if (p.workoutDays) DayOfWeek.entries.filter { app.isTargetDay(it) }.map { it.isoDayNumber }.toSet() else emptySet(), p.hour, p.minute)
    }
    val days = DayOfWeek.entries.filter { app.isTargetDay(it) }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("알림", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); SheetClose(onClose) }
            Surface(onClick = { links.openNotifications() }, shape = RoundedCornerShape(16.dp), color = c.surface2, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("알림 권한 · ${if (reminders.permitted()) "허용됨" else "확인 필요"}", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("시스템 설정 ›", color = c.primaryText, fontSize = 13.sp)
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    // 요일 = 목표 요일(운동 요일 ∪ 루틴 요일, §139) — 운동 기록의 '운동 요일' 에서 고친다
                    ToggleRow("운동 요일 알림", (if (days.isEmpty()) "운동 기록에서 운동 요일을 정하면 그 요일에" else days.joinToString("·") { koreanDayOfWeek(it) } + " ${prefs.timeLabel}") + " · 요일은 운동 요일과 같아요",
                        prefs.workoutDays) { on -> if (on) reminders.requestPermission { update(prefs.copy(workoutDays = true)) } else update(prefs.copy(workoutDays = false)) }
                    if (prefs.workoutDays) Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("시각", color = c.text2, fontSize = 13.sp)
                        StepperControl(prefs.timeLabel, { update(prefs.copy(hour = (prefs.hour + 23) % 24)) }, { update(prefs.copy(hour = (prefs.hour + 1) % 24)) }, valueMinWidth = 64.dp)
                        FilterChipRow(listOf("00", "30"), prefs.minute.toString().padStart(2, '0'), { update(prefs.copy(minute = it.toInt())) })
                    }
                    HorizontalDivider(color = c.line)
                    ToggleRow("휴식 끝", "세트 사이 휴식이 끝날 때, 화면이 꺼져 있으면", prefs.restEnd) { on -> if (on) reminders.requestPermission { update(prefs.copy(restEnd = true)) } else update(prefs.copy(restEnd = false)) }
                    HorizontalDivider(color = c.line)
                    ToggleRow("개인 기록 · 연속 운동", "PR 을 세웠을 때, 목표 요일 저녁에 \"오늘 운동하면 연속 ${app.attendanceStreak() + 1}일\" 한 번", prefs.prAndStreak) { on -> if (on) reminders.requestPermission { update(prefs.copy(prAndStreak = true)) } else update(prefs.copy(prAndStreak = false)) }
                    HorizontalDivider(color = c.line)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("커뮤니티", color = c.text, fontSize = 15.sp); Text("좋아요·댓글 알림은 아직 없어요", color = c.text2, fontSize = 12.sp) }
                        Text("없음", color = c.text3, fontSize = 13.sp)
                    }
                }
            }
            WashBanner("알림은 기기 안에서만 예약돼요. 서버가 보내는 푸시가 아니고, 끊긴 연속을 돈으로 되살리는 기능은 없어요." + if (!reminders.available) " 이 기기에서는 알림 예약을 아직 지원하지 않아요." else "", Icons.Rounded.Info)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = c.text, fontSize = 15.sp); Text(detail, color = c.text2, fontSize = 12.sp, lineHeight = 17.sp) }
        Switch(checked = checked, onCheckedChange = onChange, colors = postureSwitchColors())
    }
}
