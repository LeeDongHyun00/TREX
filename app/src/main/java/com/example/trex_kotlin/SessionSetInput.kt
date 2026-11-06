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
fun SessionSetInputCard(app: AppViewModel, originalId: String, workoutName: String, totalSets: Int, finishedSet: Int, cameraReps: Int?, initialReps: Int? = null, timed: Boolean, finishedSeconds: Int?) {
    val c = Trex.c
    val last = remember(workoutName) { app.lastSets(workoutName) }
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
                        Text(sets.summaryLine(), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
                    GhostButton("그대로 채우기", {
                        drafts.forEach { d -> sets.getOrNull(d.index - 1)?.let { s -> if (d.kg.isBlank()) d.kg = s.kg?.let(::trimKg) ?: ""; if (d.count.isBlank()) d.count = (s.reps ?: s.seconds)?.toString() ?: "" } }
                    }, height = 44.dp)
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SetRowHeader(timed)
                drafts.forEach { d ->
                    SetRow(d, timed, enabled = d.index <= finishedSet)
                    if (d.index == finishedSet && cameraReps != null && !d.edited) CameraCountNote(cameraReps)
                }
            }
        }
        RpeMemoExpander(rpe, { rpe = it }, memo, { memo = it }, lastHint = last?.second?.let { s -> listOfNotNull(s.mapNotNull { it.rpe }.lastOrNull()?.let { "지난번 RPE $it" }, s.mapNotNull { it.memo }.lastOrNull()?.let { "메모 \"$it\"" }).joinToString(" · ").ifBlank { null } })
    }
}
