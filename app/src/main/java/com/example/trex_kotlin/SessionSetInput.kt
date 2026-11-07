package com.example.trex_kotlin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDate

/**
 * 세트 사이 휴식의 세트 입력(§131, 디자인 P3) — 방금 끝낸 세트의 kg·회(카메라 값은 호박색, 고치면 사용자 값이 기준)·완료, 지난번 "그대로 채우기", RPE·메모(접힘).
 * 적은 값은 [AppViewModel.sessionSets] 에 들어가 기록 저장 때 접혀 들어간다. 아직 할 세트는 회색이다.
 */
@Composable
fun SessionSetInputCard(app: AppViewModel, originalId: String, workoutName: String, totalSets: Int, finishedSet: Int, cameraReps: Int?, initialReps: Int? = null, timed: Boolean, finishedSeconds: Int?,
    /** 참이면 방금 끝낸 세트 한 줄만 펼치고 나머지는 '전체 세트' 뒤에 둔다 — 휴식 화면의 1차는 남은 시간(UI 감사 A14, K1). */
    compact: Boolean = false) {
    val c = Trex.c
    var allRows by remember(originalId) { mutableStateOf(!compact) }
    val last = remember(workoutName) { app.lastSets(workoutName) }
    val kgLabel = remember(workoutName) { exerciseLoadKind(workoutName).kgLabel() }
    val showKg = remember(workoutName) { exerciseShowsKg(workoutName) }
    val drafts = remember(originalId) { mutableStateListOf<SetRowDraft>().also { list ->
        for (i in 1..totalSets) {
            val saved = app.sessionSets["$originalId::set:$i"]
            list += saved?.toDraft() ?: SetRowDraft(i, "", if (i == finishedSet) (if (timed) finishedSeconds?.toString() ?: "" else (cameraReps ?: initialReps)?.toString() ?: "") else "", done = i < finishedSet, cameraReps = if (i == finishedSet) cameraReps else null)
        }
    } }
    var rpe by remember { mutableStateOf(app.sessionSets["$originalId::set:$finishedSet"]?.rpe) }
    var memo by remember { mutableStateOf(app.sessionSets["$originalId::set:$finishedSet"]?.memo ?: "") }
    fun commit() {
        drafts.forEach { d -> if (d.index <= finishedSet) app.sessionSets["$originalId::set:${d.index}"] = d.toEntry(timed, if (d.index == finishedSet) rpe else app.sessionSets["$originalId::set:${d.index}"]?.rpe, if (d.index == finishedSet) memo else app.sessionSets["$originalId::set:${d.index}"]?.memo) }
    }
    // 값이 바뀔 때마다 상태에 쓴다 — 휴식이 끝나는 순간(자동 전환)에 놓치지 않게
    LaunchedEffect(drafts.map { Triple(it.kg, it.count, it.done) }, rpe, memo) { commit() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        last?.let { (day, sets) ->
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("지난번 · ${LocalDate.fromEpochDays(day).let { "${it.monthValue}월 ${it.day}일" }}", color = c.text2, fontSize = 12.sp)
                        Text(sets.summaryLine(bodyweight = kgLabel == "+kg"), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
                    // 폭을 주지 않으면 GhostButton 의 fillMaxWidth 가 왼쪽 글 칸을 한 글자 폭으로 밀어낸다(11-07 실기기 — '+30초' 와 같은 함정)
                    GhostButton("그대로 채우기", {
                        drafts.forEach { d -> sets.getOrNull(d.index - 1)?.let { s -> if (d.kg.isBlank()) d.kg = s.kg?.let(::trimKg) ?: ""; if (d.count.isBlank()) d.count = (s.reps ?: s.seconds)?.toString() ?: "" } }
                    }, Modifier.width(124.dp), height = 44.dp)
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SetRowHeader(timed, if (showKg) kgLabel else "—", countLabelFor(workoutName, timed))
                drafts.forEach { d ->
                    if (!allRows && d.index != finishedSet) return@forEach
                    SetRow(d, timed, enabled = d.index <= finishedSet, showKg = showKg, kgLabel = kgLabel)
                    if (d.index == finishedSet && cameraReps != null && !d.edited) CameraCountNote(cameraReps)
                }
                if (compact && totalSets > 1) TextButton(onClick = { allRows = !allRows }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (allRows) "방금 세트만" else "전체 ${totalSets}세트 보기", color = c.primaryText, fontSize = 12.sp)
                }
            }
        }
        RpeMemoExpander(rpe, { rpe = it }, memo, { memo = it }, lastHint = last?.second?.let { s -> listOfNotNull(s.mapNotNull { it.rpe }.lastOrNull()?.let { "지난번 RPE $it" }, s.mapNotNull { it.memo }.lastOrNull()?.let { "메모 \"$it\"" }).joinToString(" · ").ifBlank { null } })
    }
}

/**
 * 완료 화면의 세트 기록 확인·수정(PR 65 리뷰) — 종목마다 오늘 기록의 세트 전부를 편집 행으로. 마지막 세트(휴식 화면이 없는 세트)와 1세트짜리 종목은 여기가 유일한 입력 자리다.
 * 고치면 바로 [AppViewModel.updateTodaySet] 으로 오늘 기록에 들어간다. [plan] 은 기록한 세트별 운동(`…::set:N`).
 */
@Composable
fun SessionFinalSetsCard(app: AppViewModel, plan: List<Workout>) {
    val c = Trex.c
    val names = remember(plan) { plan.map { it.name }.distinct() }
    val record = app.todayRecord ?: return
    if (names.isEmpty()) return
    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("세트 기록 확인", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("마지막 세트의 kg·회는 여기서 적어요. 고친 값이 기록 기준이에요.", color = c.text2, fontSize = 12.sp)
            names.forEach { name -> FinalSetsRows(app, name, record.items.filter { it.workoutName == name }.flatMap { it.sets }.sortedBy { it.index },
                timed = plan.first { it.name == name }.resolvedTarget() is WorkoutTarget.Duration) }
        }
    }
}

@Composable
private fun FinalSetsRows(app: AppViewModel, name: String, sets: List<SetEntry>, timed: Boolean) {
    val c = Trex.c
    if (sets.isEmpty()) return
    val drafts = remember(name) { mutableStateListOf<SetRowDraft>().also { l -> sets.forEach { l += it.toDraft() } } }
    val rpeMemo = remember(name) { sets.associate { it.index to (it.rpe to it.memo) } }
    LaunchedEffect(drafts.map { Triple(it.kg, it.count, it.done) }) {
        drafts.forEach { d -> val (rpe, memo) = rpeMemo[d.index] ?: (null to null); app.updateTodaySet(name, d.toEntry(timed, rpe, memo)) }
    }
    val kgLabel = remember(name) { exerciseLoadKind(name).kgLabel() }
    val showKg = remember(name) { exerciseShowsKg(name) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        SetRowHeader(timed, if (showKg) kgLabel else "—", countLabelFor(name, timed))
        drafts.forEach { d -> SetRow(d, timed, showKg = showKg, kgLabel = kgLabel) }
    }
}
