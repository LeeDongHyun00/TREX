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
    val original = remember(initialId) { app.workoutPlan.firstOrNull { it.id == initialId } }
    var selected by remember(initialId) { mutableStateOf(original) }
    var picking by remember(initialId, initialMode) { mutableStateOf(initialMode != "edit" || original == null) }
    val timed = selected?.resolvedTarget() is WorkoutTarget.Duration
    val wheelKey = Triple(selected?.id, selected?.name, timed)
    val setWheel = remember(wheelKey) { WorkoutNumberWheelState(selected?.repsSpec()?.sets ?: 1, 1..20) }
    val goalWheel = remember(wheelKey) { WorkoutNumberWheelState(selected?.resolvedTarget()?.amount ?: 12, if (timed) 1..3600 else 1..999) }
    val restWheel = remember(wheelKey) { WorkoutNumberWheelState(selected?.timing()?.restSeconds ?: 60, 0..600) }
    fun editedWorkout() = selected?.withEditorValues(timed, setWheel.value, goalWheel.value, restWheel.value)
    fun update(change: (Workout) -> Workout) { selected = selected?.let { change(it).copy(done = false) } }
    fun pick(template: WorkoutTemplate) {
        focus.clearFocus()
        keyboard?.hide()
        val before = editedWorkout()
        val next = Workout(original?.id ?: before?.id ?: java.util.UUID.randomUUID().toString(), template.name,
            template.reps, template.duration,
            (before?.posture ?: template.posture) && ExerciseProfiles.forName(template.name)?.cameraEnabled == true, template.category,
            restSeconds = before?.restSeconds)
        selected = next.withGoal(next.resolvedTarget(), before?.repsSpec()?.sets ?: next.repsSpec().sets)
        picking = false
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.sheet, sheetGesturesEnabled = picking,
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
                        Spacer(Modifier.height(18.dp))
                        SegmentedTabs(listOf("횟수", "시간"), if (timed) 1 else 0, onSelect = { index ->
                            if ((index == 1) != timed) {
                                val target = if (index == 1) WorkoutTarget.Duration(30) else WorkoutTarget.Repetitions(12)
                                update { it.withEditorValues(timed, setWheel.value, goalWheel.value, restWheel.value)
                                    .withGoal(target, setWheel.value) }
                            }
                        })
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkoutNumberWheel("세트", setWheel, "세트", Modifier.weight(1f))
                            WorkoutNumberWheel(if (timed) "시간" else "횟수", goalWheel, if (timed) "초" else "회", Modifier.weight(1f))
                            WorkoutNumberWheel("세트 간 휴식", restWheel, "초", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("자세 비교", color = c.text, fontSize = 16.sp)
                                val profile = ExerciseProfiles.forName(selected.name)
                                Text(when {
                                    // 시험 단계 종목(PostureTrial)은 촬영 방향 앞에 밝힌다
                                    selected.postureSupported() -> (if (selected.postureTrial()) "자세 교정 시험 단계 · " else "") + profile?.capture?.title.orEmpty()
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
                        editedWorkout()?.let { edited ->
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

/** 저장 버튼을 누른 순간의 세 숫자를 함께 확정한다. 0초도 명시적인 설정으로 보존한다. */
internal fun Workout.withEditorValues(timed: Boolean, sets: Int, amount: Int, rest: Int): Workout =
    withGoal(if (timed) WorkoutTarget.Duration(amount.coerceIn(1, 3600)) else WorkoutTarget.Repetitions(amount.coerceIn(1, 999)), sets)
        .copy(restSeconds = rest.coerceIn(0, 600), done = false)
