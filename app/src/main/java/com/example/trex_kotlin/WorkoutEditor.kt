package com.example.trex_kotlin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.ExerciseProfiles

/** 한 항목만 초안으로 편집한다. 추가는 마지막 회복 운동 앞에 넣는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditorSheet(app: AppViewModel, initialId: String?, onClose: () -> Unit, initialMode: String = "edit") {
    val c = Trex.c
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val original = remember { app.workoutPlan.firstOrNull { it.id == initialId } }
    var selected by remember { mutableStateOf(original) }
    var picking by remember { mutableStateOf(initialMode != "edit" || original == null) }
    fun update(change: (Workout) -> Workout) { selected = selected?.let { change(it).copy(done = false) } }
    fun pick(template: WorkoutTemplate) {
        focus.clearFocus()
        keyboard?.hide()
        val before = selected
        val next = Workout(original?.id ?: before?.id ?: java.util.UUID.randomUUID().toString(), template.name,
            template.reps, template.duration,
            (before?.posture ?: template.posture) && ExerciseProfiles.forName(template.name)?.cameraEnabled == true, template.category,
            restSeconds = before?.restSeconds)
        selected = next.withGoal(next.resolvedTarget(), before?.repsSpec()?.sets ?: next.repsSpec().sets)
        picking = false
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.sheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding().padding(horizontal = 22.dp).padding(bottom = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (picking) { if (original == null) "운동 추가" else "종목 변경" } else if (original == null) "운동 추가" else "운동 수정",
                    color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            if (picking) {
                WorkoutCatalogBrowser(if (original == null) null else selected, onPick = ::pick, modifier = Modifier.weight(1f))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("취소", onClose, Modifier.weight(1f))
                    if (selected != null) Cta("설정으로 돌아가기", { picking = false }, modifier = Modifier.weight(1.7f))
                }
            } else {
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(top = 20.dp)) {
                    selected?.let { selected ->
                        Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(selected.name, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text("종목 변경", color = c.primaryText, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = c.primaryText)
                        }
                        val goal = selected.resolvedTarget()
                        val timed = goal is WorkoutTarget.Duration
                        Spacer(Modifier.height(18.dp))
                        SegmentedTabs(listOf("횟수", "시간"), if (timed) 1 else 0, onSelect = { index ->
                            update { w ->
                                val target = if (index == 1) WorkoutTarget.Duration(30) else WorkoutTarget.Repetitions(12)
                                w.withGoal(target, w.repsSpec().sets)
                            }
                        })
                        EditorNumberRow(if (timed) "운동 시간" else "목표 횟수", if (timed) "${goal.amount}초" else "${goal.amount}회",
                            onDec = { update { w -> w.withGoal(if (timed) WorkoutTarget.Duration((goal.amount - 5).coerceAtLeast(1)) else WorkoutTarget.Repetitions((goal.amount - 1).coerceAtLeast(1)), w.repsSpec().sets) } },
                            onInc = { update { w -> w.withGoal(if (timed) WorkoutTarget.Duration((goal.amount + 5).coerceAtMost(3600)) else WorkoutTarget.Repetitions((goal.amount + 1).coerceAtMost(999)), w.repsSpec().sets) } })
                        EditorNumberRow("세트", "${selected.repsSpec().sets}세트",
                            onDec = { update { w -> w.withGoal(goal, (w.repsSpec().sets - 1).coerceAtLeast(1)) } },
                            onInc = { update { w -> w.withGoal(goal, (w.repsSpec().sets + 1).coerceAtMost(20)) } })
                        EditorNumberRow("세트 간 휴식", "${selected.timing().restSeconds}초",
                            onDec = { update { w -> w.copy(restSeconds = (w.timing().restSeconds - 5).coerceAtLeast(0)) } },
                            onInc = { update { w -> w.copy(restSeconds = (w.timing().restSeconds + 5).coerceAtMost(600)) } })
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("자세 비교", color = c.text, fontSize = 16.sp)
                                val profile = ExerciseProfiles.forName(selected.name)
                                Text(when {
                                    selected.postureSupported() -> profile?.capture?.title.orEmpty()
                                    profile == null -> "이 종목은 아직 비교를 지원하지 않아요."
                                    else -> "여러 동작이 섞여 있어 비교를 지원하지 않아요."
                                },
                                    color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Switch(checked = selected.posture && selected.postureSupported(), enabled = selected.postureSupported(),
                                colors = postureSwitchColors(),
                                modifier = Modifier.semantics { contentDescription = "${selected.name} 자세 비교" },
                                onCheckedChange = { enabled -> update { it.copy(posture = enabled) } })
                        }
                    }
                }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("취소", onClose, Modifier.weight(1f))
                    Cta(if (original == null) "추가" else "저장", {
                        selected?.let { edited ->
                            app.updatePlan(if (original == null) app.workoutPlan.insertBeforeRecovery(edited)
                                else app.workoutPlan.map { if (it.id == original.id) edited else it })
                        }
                        onClose()
                    }, enabled = selected != null, modifier = Modifier.weight(1.7f))
                }
            }
        }
    }
}

/** 표시 문자열과 명시적 목표를 함께 갱신해 오래된 화면/기록과의 호환을 유지한다. */
fun Workout.withGoal(goal: WorkoutTarget, sets: Int): Workout = copy(target = goal,
    reps = "${goal.amount}${if (goal is WorkoutTarget.Duration) "초" else "회"} × ${sets.coerceIn(1, 20)}세트")

@Composable
private fun EditorNumberRow(label: String, value: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Trex.c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        StepperControl(value, onDec, onInc, valueMinWidth = 62.dp, label = label)
    }
}
