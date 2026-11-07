package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SportsGymnastics
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * 업적(§131, 디자인 P6) — 레벨·XP, 연속 운동(목표 요일 기준, 최장), 이번 주 목표, 연속 잠시 멈추기(최대 14일, 돈·아이템 없음), 배지 진행, XP 내역.
 * XP 는 운동을 마치고 기록할 때만 쌓인다. 자세 점수는 XP 와 무관하다.
 */
@Composable
fun AchievementsScreen(app: AppViewModel, onBack: () -> Unit, onGoWorkout: () -> Unit) {
    val c = Trex.c
    val total = app.totalXp
    val badges = remember(app.workoutHistory, app.xpLedger, app.seenGuides, app.dietByDay, app.streakPause, app.weeklyRoutine, app.waterByDay, app.bodyWeights) { app.badges() }
    val today = LocalDate.fromEpochDays(app.calendarDay)
    val weekStart = today.toEpochDay() - (today.dayOfWeek.isoDayNumber - 1)
    val workoutDays = app.workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()
    val goal = DayOfWeek.entries.count { app.isTargetDay(it) }
    val done = (weekStart..weekStart + 6).count { it in workoutDays }
    var pauseSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Text("업적", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = c.text, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("레벨", color = c.bg.copy(alpha = .72f), fontSize = 12.sp); Text("Lv ${Rewards.level(total)}", color = c.bg, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                            Column(horizontalAlignment = Alignment.End) { Text("다음 레벨까지", color = c.bg.copy(alpha = .72f), fontSize = 12.sp); Text("${Rewards.toNextLevel(total)} XP", color = c.bg, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                        }
                        TrackBar((total % Rewards.XP_PER_LEVEL).toFloat() / Rewards.XP_PER_LEVEL, color = c.lime, modifier = Modifier.padding(top = 12.dp), animate = true)
                        Text("XP 는 운동을 마치고 기록한 사실에만 붙어요.", color = c.bg.copy(alpha = .72f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("연속 운동", color = c.text2, fontSize = 12.sp); Text("${app.attendanceStreak()}일", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("최장 ${app.longestStreak()}일 · 목표 요일 기준", color = c.text2, fontSize = 12.sp) }
                            Column(horizontalAlignment = Alignment.End) { Text("이번 주 · 목표 ${if (goal > 0) "${goal}일" else "없음"}", color = c.text2, fontSize = 12.sp); Text(if (goal > 0) "$done / $goal" else "$done", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            DayOfWeek.entries.forEach { dow ->
                                val epoch = weekStart + dow.ordinal
                                val worked = epoch in workoutDays
                                val target = app.isTargetDay(dow)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (worked) c.primary else if (target) c.primaryWash else c.surface2, modifier = Modifier.size(30.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Text(koreanDayOfWeek(dow), color = if (worked) c.bg else c.text2, fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                        val pause = app.streakPause
                        Surface(onClick = { pauseSheet = true }, shape = RoundedCornerShape(14.dp), color = c.surface2, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PauseCircle, null, tint = c.primaryText, modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(if (pause != null && pause.covers(today.toEpochDay())) "연속 멈춤 중 · ${LocalDate.fromEpochDays(pause.untilEpochDay).let { "${it.monthValue}월 ${it.day}일" }}까지" else "연속 잠시 멈추기", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("부상·여행 때 최대 ${StreakPause.MAX_DAYS}일. 멈춘 동안은 연속이 줄지 않고, 돈이나 아이템은 필요 없어요.", color = c.text2, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("배지", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text("${badges.count { it.earned }} / ${badges.size} 획득", color = c.text2, fontSize = 12.sp) }
                        if (badges.none { it.earned }) Column(Modifier.padding(top = 10.dp)) {
                            Text("첫 운동을 마치면 첫 배지예요", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("배지와 XP 는 운동을 마치고 기록한 사실에만 붙어요.", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            GhostButton("오늘 루틴 보기", onGoWorkout, Modifier.fillMaxWidth().padding(top = 10.dp))
                        }
                        badges.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { b ->
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        // 배지 그림(§138) — 글자 대신 아이콘, 사다리(10/50/100 등)는 제목이 단을 말한다. 못 얻은 배지는 흐리게
                                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (b.earned) c.primary else c.surface2, modifier = Modifier.size(56.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Icon(badgeIcon(b.id), b.short, tint = if (b.earned) c.bg else c.text3, modifier = Modifier.size(26.dp)) }
                                        }
                                        Text(b.title, color = if (b.earned) c.text else c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                                        if (!b.earned && b.progress > 0) Text("${b.progress} / ${b.goal}", color = c.text3, fontSize = 11.sp)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("XP 내역", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                        val entries = app.xpLedger.filter { it.amount > 0 }.sortedByDescending { it.at }.take(12)
                        if (entries.isEmpty()) Text("아직 XP 가 없어요", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                        entries.forEach { e ->
                            HorizontalDivider(color = c.line)
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${monthDaySlashLabel(e.at)} · ${e.reason}", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("+${e.amount}", color = c.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item { WashBanner("배지와 XP 는 '했다·기록했다' 에만 걸려요. 카메라가 덜 봤다고 유리해지거나, 자세 판정으로 깎이는 일은 없어요.", Icons.Rounded.Info) }
        }
    }
    if (pauseSheet) SheetHost(onDismiss = { pauseSheet = false }) {
        var days by remember { mutableIntStateOf(app.streakPause?.days ?: 7) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("연속 잠시 멈추기", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("오늘부터 며칠 멈출까요? 멈춘 날은 연속이 줄지 않아요.", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center) {
                StepperControl("${days}일", { days = (days - 1).coerceAtLeast(1) }, { days = (days + 1).coerceAtMost(StreakPause.MAX_DAYS) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (app.streakPause != null) GhostButton("멈춤 해제", { app.resumeStreak(); pauseSheet = false }, Modifier.weight(1f))
                Cta("멈추기", { app.pauseStreak(days); pauseSheet = false }, Modifier.weight(1.4f))
            }
        }
    }
}

/** 배지 id → 아이콘(§138). 표에 없는 id 는 트로피 — 사다리(운동 일수·연속·식단)는 같은 그림이고 제목이 단을 말한다. */
internal fun badgeIcon(id: String): ImageVector = when (id.substringBefore('_')) {
    "first" -> Icons.Rounded.Star
    "days" -> Icons.Rounded.FitnessCenter
    "streak" -> Icons.Rounded.LocalFireDepartment
    "perfect" -> if (id == "perfect_month") Icons.Rounded.CalendarMonth else Icons.Rounded.EventAvailable
    "morning" -> Icons.Rounded.WbSunny
    "night" -> Icons.Rounded.Bedtime
    "squat" -> Icons.Rounded.Accessibility
    "pushup" -> Icons.Rounded.SportsGymnastics
    "plank" -> Icons.Rounded.Timer
    "volume" -> Icons.Rounded.Scale
    "coach" -> Icons.Rounded.Videocam
    "guides" -> Icons.Rounded.MenuBook
    "diet" -> Icons.Rounded.Restaurant
    "water" -> Icons.Rounded.WaterDrop
    "weigh" -> Icons.Rounded.MonitorWeight
    "routine" -> Icons.Rounded.Share
    else -> Icons.Rounded.EmojiEvents
}
