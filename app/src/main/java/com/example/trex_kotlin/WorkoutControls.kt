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
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.sheet) {
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
            workout.repCountExplanation()?.let { Text(it, color = c.text2, fontSize = 12.sp) }
        }
    }
}

/** 오른쪽은 주요 동작, 왼쪽은 횟수 수정. 메뉴를 열면 측정과 시계를 함께 멈춘다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSessionActions(
    workout: Workout, count: Int, automatic: Boolean, paused: Boolean,
    onTogglePause: () -> Unit, onRepetitions: (Int) -> Unit, onPartial: () -> Unit,
    onSkip: () -> Unit, onExit: () -> Unit,
    directTools: (@Composable RowScope.() -> Unit)? = null,
    onExpandCamera: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
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
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SessionTool("종료", "운동 종료", Icons.Rounded.Close, onExit, Modifier.weight(1f), destructive = true)
        directTools?.invoke(this)
        SessionTool("건너뛰기", "이 세트 건너뛰기", Icons.Rounded.SkipNext, { open("skip") }, Modifier.weight(1f))
    }
    if (onComplete != null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(repetitions) TextButton({open("count")}) { Text(if(automatic) "횟수 수정" else "횟수 기록",color=c.text2) }
            GhostButton(if(paused) "재개" else "일시정지",onTogglePause,Modifier.weight(1f))
            Cta("세트 완료",onComplete,modifier=Modifier.weight(1.25f),height=56.dp)
        }
    } else if (onExpandCamera != null) {
        if (repetitions) TextButton(onClick = { open("count") }) {
            Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
            Text(if (automatic) "횟수 수정" else "횟수 기록", modifier = Modifier.padding(start = 8.dp), color = c.text)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CameraExpandAction(onExpandCamera, !paused, Modifier.weight(1f))
            Cta(if (paused) "운동 계속" else "일시정지", onTogglePause,
                icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                modifier = Modifier.weight(1.25f).semantics { contentDescription = if (paused) "재개" else "일시정지" }, height = 56.dp)
        }
    } else {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (repetitions) GhostButton(if (automatic) "횟수 수정" else if (paused) "재개" else "일시정지",
            onClick = { if (repetitions && automatic) open("count") else onTogglePause() }, modifier = Modifier.width(100.dp))
        Cta(if (repetitions && !automatic) "횟수 기록" else if (paused) "재개" else "일시정지",
            onClick = { if (repetitions && !automatic) open("count") else onTogglePause() },
            icon = if (repetitions && !automatic) Icons.Rounded.Edit else if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            modifier = Modifier.weight(1f), height = 56.dp)
    }
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
        ModalBottomSheet(onDismissRequest = { close() }, containerColor = c.sheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 510.dp).padding(horizontal = 24.dp).padding(bottom = 22.dp)) {
                Text(if (overlay == "count") "실제 수행 횟수" else workout.name, color = c.text,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (overlay == "count") {
                        workout.repCountExplanation()?.let { Text(it, color = c.text2, fontSize = 13.sp) }
                        if (automatic) Text("수정한 총횟수와 카메라의 좌우 관측 기록은 따로 보존돼요.", color = c.text2, fontSize = 12.sp)
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
                        Cta(if (onComplete != null) "적용" else if (draft >= workout.resolvedTarget().amount) "기록 완료" else if (automatic) "적용" else "여기까지 기록",
                            onClick = {
                                onRepetitions(draft)
                                if (onComplete == null && !automatic && draft < workout.resolvedTarget().amount) { close(false); onPartial() }
                                else { close(false); if (paused) onTogglePause() }
                            }, modifier = Modifier.weight(1.5f))
                    } else Cta("돌아가기", onClick = { close() }, modifier = Modifier.weight(1.5f))
                }
            }
        }
    }
}

/** 자주 쓰는 카메라 조작은 손이 닿는 하단에서 아이콘과 짧은 이름을 함께 보여 준다. */
@Composable
internal fun SessionTool(label: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val tint = if (destructive) Trex.c.err else Trex.c.text2
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 58.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
            Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
        }
    }
}
