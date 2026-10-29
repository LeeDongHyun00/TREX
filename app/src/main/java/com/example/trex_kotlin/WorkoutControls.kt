package com.example.trex_kotlin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    SheetHost(onDismiss = onClose) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("설정", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("화면 모드", color = c.text2)
            val modes = listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.System)
            SegmentedTabs(listOf("라이트", "다크", "시스템"), modes.indexOf(app.themeMode), { app.setTheme(modes[it]) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("로그아웃", { onClose(); app.logout() }, Modifier.weight(1f))
                Cta("닫기", onClose, modifier = Modifier.weight(1.6f))
            }
        }
    }
}

/** 운동 목표만 보여 주는 공통 계기판. 시간은 원형 진행률, 횟수는 목표 대비 숫자다. */
@Composable
fun WorkoutGoalDisplay(workout: Workout, count: Int, timeLeft: Int, totalSeconds: Int, compact: Boolean = false) {
    val c = Trex.c
    val goal = workout.resolvedTarget()
    if (goal is WorkoutTarget.Duration) {
        RingGauge(progress = (1f - timeLeft.toFloat() / totalSeconds.coerceAtLeast(1)).coerceIn(0f, 1f),
            size = if (compact) 104.dp else 224.dp, stroke = if (compact) 5.dp else 9.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(timeLeft.asClock(), color = c.text, fontSize = if (compact) 25.sp else 44.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("목표 ${goal.amount.asClock()}", color = c.text2, fontSize = if (compact) 10.sp else 13.sp)
            }
        }
    } else {
        Column(horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(count.toString(), color = c.text, fontSize = if (compact) 42.sp else 72.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(" / ${goal.amount}회", color = c.text2, fontSize = if (compact) 18.sp else 24.sp,
                    modifier = Modifier.padding(bottom = if (compact) 6.dp else 12.dp))
            }
        }
    }
}

/**
 * 세션 조작 — 물결 위 원형 버튼 줄(종료 · 도구 · 주요 동작 · 건너뛰기)과 작은 칩 줄(스펙 §115 제어판 디자인).
 * 메뉴(횟수·건너뛰기)를 열면 측정과 시계를 함께 멈춘다. [livePanel] 은 카메라 세션의 제어판 — 주요 동작이 늘 일시정지/재개이고
 * 횟수 수정은 칩으로 간다. 타이머 세션은 직접 횟수 기록이 주요 동작이다. [tools] 는 원형 줄의 주요 동작 양옆에, [moreTools] 는 칩 줄에 놓인다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSessionActions(
    workout: Workout, count: Int, automatic: Boolean, paused: Boolean,
    onTogglePause: () -> Unit, onRepetitions: (Int) -> Unit, onPartial: () -> Unit,
    onSkip: () -> Unit, onExit: () -> Unit,
    tools: List<SessionToolSpec> = emptyList(),
    moreTools: List<SessionToolSpec> = emptyList(),
    livePanel: Boolean = false,
    onShowGuide: (() -> Unit)? = null,
    belowActions: @Composable () -> Unit = {},
) {
    val c = Trex.c
    val repetitions = workout.resolvedTarget() is WorkoutTarget.Repetitions
    var overlay by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableIntStateOf(0) }
    var resumeAfter by remember { mutableStateOf(false) }
    fun open(kind: String) {
        resumeAfter = !paused
        if (!paused) onTogglePause()
        draft = if (!automatic && count == 0) workout.resolvedTarget().amount else count
        overlay = kind
    }
    fun close(resume: Boolean = true) {
        overlay = null
        if (resume && resumeAfter && paused) onTogglePause()
    }
    val pauseLabel = if (paused) "재개" else "일시정지"
    val pauseIcon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause
    // 타이머 세션의 직접 횟수 기록은 기록이 주요 동작이고 일시정지가 옆으로 간다(예전 '횟수 기록' 버튼 자리)
    val countIsPrimary = !livePanel && repetitions && !automatic
    val countLabel = if (automatic) "횟수 수정" else "횟수 기록"
    SessionActionRow(Modifier.padding(top = 4.dp)) {
        SessionCircleButton("종료", "운동 종료", Icons.Rounded.Close, onExit, Modifier.weight(1f), destructive = true)
        tools.take(1).forEach { SessionCircleButton(it.label, it.description, it.icon, it.onClick, Modifier.weight(1f)) }
        if (!livePanel && repetitions) {
            if (countIsPrimary) SessionCircleButton(pauseLabel, pauseLabel, pauseIcon, onTogglePause, Modifier.weight(1f))
            else SessionCircleButton(countLabel, countLabel, Icons.Rounded.Edit, { open("count") }, Modifier.weight(1f))
        }
        if (countIsPrimary) SessionCircleButton(countLabel, countLabel, Icons.Rounded.Edit, { open("count") }, Modifier.weight(1f), highlighted = true)
        else SessionCircleButton(pauseLabel, pauseLabel, pauseIcon, onTogglePause, Modifier.weight(1f), highlighted = true)
        tools.drop(1).forEach { SessionCircleButton(it.label, it.description, it.icon, it.onClick, Modifier.weight(1f)) }
        SessionCircleButton("건너뛰기", "이 세트 건너뛰기", Icons.Rounded.SkipNext, { open("skip") }, Modifier.weight(1f))
    }
    belowActions()
    val guide = onShowGuide?.takeIf { ExerciseGuides.forName(workout.name) != null }
    if ((livePanel && repetitions) || moreTools.isNotEmpty() || guide != null) SessionChipRow(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        if (livePanel && repetitions) SessionChip(countLabel, countLabel, Icons.Rounded.Edit, { open("count") })
        moreTools.forEach { SessionChip(it.label, it.description, it.icon, it.onClick) }
        if (guide != null) SessionChip("운동 방법", "${workout.name} 운동 방법", Icons.Rounded.PlayArrow, guide)
    }
    if (overlay == "skip") {
        AlertDialog(
            onDismissRequest = { close() },
            shape = RoundedCornerShape(26.dp),
            containerColor = c.sheet,
            titleContentColor = c.text,
            textContentColor = c.text2,
            title = { Text("이 세트를 건너뛸까요?", fontWeight = FontWeight.SemiBold) },
            text = { Text("현재 세트를 건너뛰고 다음 순서로 이동해요. 완료한 세트의 기록은 유지돼요.") },
            dismissButton = { TextButton(onClick = { close() }) { Text("취소", color = c.text2) } },
            confirmButton = {
                TextButton(onClick = { close(false); onSkip() }) {
                    Text("건너뛰기", color = c.text, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
    if (overlay != null && overlay != "skip") {
        SheetHost(onDismiss = { close() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 510.dp).padding(horizontal = 24.dp).padding(bottom = 22.dp)) {
                Text(if (overlay == "count") "실제 수행 횟수" else workout.name, color = c.text,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (overlay == "count") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center) {
                            StepperControl("${draft}회", { draft = (draft - 1).coerceAtLeast(0) }, { draft = (draft + 1).coerceAtMost(999) })
                        }
                        if (draft < workout.resolvedTarget().amount) Text("목표 ${workout.resolvedTarget().amount}회", color = c.text2)
                    } else {
                        if (automatic) Text("자동 횟수는 참고 측정이에요. 누락된 횟수는 수정할 수 있어요.", color = c.text2, fontSize = 13.sp)
                        GhostButton("이 세트 건너뛰기", onClick = { close(false); onSkip() }, modifier = Modifier.fillMaxWidth())
                        GhostButton("운동 종료", onClick = { close(false); onExit() }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("닫기", onClick = { close() }, modifier = Modifier.weight(1f))
                    if (overlay == "count") {
                        Cta(if (draft >= workout.resolvedTarget().amount) "기록 완료" else if (automatic) "적용" else "여기까지 기록",
                            onClick = {
                                onRepetitions(draft)
                                if (!automatic && draft < workout.resolvedTarget().amount) { close(false); onPartial() }
                                else { close(false); if (paused) onTogglePause() }
                            }, modifier = Modifier.weight(1.5f))
                    } else Cta("돌아가기", onClick = { close() }, modifier = Modifier.weight(1.5f))
                }
            }
        }
    }
}
