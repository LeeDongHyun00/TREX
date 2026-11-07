package com.example.trex_kotlin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
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
 * 기록 추가·수정 시트(§131, 디자인 P2) — 카메라·타이머 없이 적는 기록. 자세 판정은 남지 않는다. 날짜(오늘 이전만) · 종목 · 세트(kg·회·완료) · RPE·메모(접힘).
 * 저장하면 그날 첫 수동 기록은 +20 XP, 개인 기록이면 저장 뒤 알려 준다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRecordSheet(app: AppViewModel, initialEpochDay: Long, initialExercise: String? = null, onClose: () -> Unit) {
    val c = Trex.c
    val today = todayEpochDay()
    var epochDay by remember { mutableLongStateOf(initialEpochDay.coerceAtMost(today)) }
    var exercise by remember { mutableStateOf(initialExercise) }
    var picking by remember { mutableStateOf(initialExercise == null) }
    val template = exercise?.let { name -> app.catalogTemplates().firstOrNull { it.name == name } }
    val timed = template?.reps?.contains("초") == true
    val drafts = remember(exercise) { mutableStateListOf<SetRowDraft>().also { list ->
        val last = exercise?.let(app::lastSets)?.second
        val n = template?.let { parseReps(it.reps).sets } ?: 3
        repeat(n) { i -> list += SetRowDraft(i + 1, last?.getOrNull(i)?.kg?.let(::trimKg) ?: "", last?.getOrNull(i)?.let { (it.reps ?: it.seconds)?.toString() } ?: "", done = false) }
    } }
    var rpe by remember { mutableStateOf<Int?>(null) }
    var memo by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var savedPr by remember { mutableStateOf<SetEntry?>(null) }
    val date = LocalDate.fromEpochDays(epochDay)
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(horizontal = 22.dp).padding(bottom = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (picking) "종목 고르기" else "기록 추가", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SheetClose(onClose)
            }
            if (savedPr != null) {
                Column(Modifier.weight(1f, false).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WashBanner("개인 기록 갱신! $exercise ${savedPr!!.kg?.let(::trimKg)} kg × ${savedPr!!.reps}회", Icons.Rounded.Info)
                    Cta("확인", onClose, Modifier.fillMaxWidth())
                }
                return@Column
            }
            if (picking) {
                ExercisePickerList(app, onPick = { exercise = it; picking = false }, modifier = Modifier.weight(1f, false).padding(top = 12.dp))
                return@Column
            }
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WashBanner("카메라·타이머 없이 적는 기록이에요. 자세 판정은 남지 않아요.", Icons.Rounded.Info)
                Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("날짜", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { epochDay -= 1 }) { Icon(Icons.Rounded.ChevronLeft, "하루 전", tint = c.text2) }
                            Text("${date.monthValue}월 ${date.day}일 (${koreanDayOfWeek(date.dayOfWeek)})" + if (epochDay == today) " · 오늘" else "", color = c.text, fontSize = 15.sp)
                            IconButton(onClick = { epochDay += 1 }, enabled = epochDay < today) { Icon(Icons.Rounded.ChevronRight, "하루 뒤", tint = if (epochDay < today) c.text2 else c.track) }
                        }
                        HorizontalDivider(color = c.line)
                        Row(Modifier.fillMaxWidth().clickable { picking = true }.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("종목", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(exercise ?: "고르기", color = c.text, fontSize = 15.sp)
                            if (exercise != null) Box(Modifier.padding(start = 8.dp)) { WashPill(if (postureSupported(exercise!!)) "자세 교정 지원" else "기록 전용") }
                            Icon(Icons.Rounded.ChevronRight, null, tint = c.text2, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 맨몸 종목은 kg 이 추가 중량(+kg) — 체중을 kg 칸에 적게 하지 않는다(ExerciseLoadKind)
                        val kgLabel = exercise?.let { exerciseLoadKind(it).kgLabel() } ?: "kg"
                        SetRowHeader(timed, kgLabel)
                        drafts.forEach { SetRow(it, timed, kgLabel = kgLabel) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("세트 추가하기", { drafts += SetRowDraft(drafts.size + 1, drafts.lastOrNull()?.kg ?: "", drafts.lastOrNull()?.count ?: "") }, Modifier.weight(1f))
                            if (drafts.size > 1) GhostButton("마지막 세트 빼기", { drafts.removeAt(drafts.lastIndex) }, Modifier.weight(1f))
                        }
                    }
                }
                RpeMemoExpander(rpe, { rpe = it }, memo, { memo = it })
                message?.let { Text(it, color = c.err, fontSize = 13.sp) }
            }
            Cta("저장하기", onClick = {
                val name = exercise ?: run { message = "종목을 골라 주세요"; return@Cta }
                val sets = drafts.map { it.toEntry(timed, rpe, memo) }
                app.recordManualWorkout(epochDay, name, sets).fold(
                    onSuccess = { pr -> if (pr != null) savedPr = pr else onClose() },
                    onFailure = { message = it.message ?: "저장하지 못했어요" },
                )
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("그날 첫 기록은 +${Rewards.XP_MANUAL_FIRST_OF_DAY} XP · 개인 기록이면 저장 뒤 알려 드려요", color = c.text2, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** 종목 고르기 목록 — 검색 + 카탈로그·내 종목. 자세 교정 지원은 칩으로 밝힌다. */
@Composable
fun ExercisePickerList(app: AppViewModel, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Trex.c
    var query by remember { mutableStateOf("") }
    val templates = remember(app.customExercises) { app.catalogTemplates() }
    val shown = templates.filter { query.isBlank() || it.name.contains(query.trim(), true) }
    Column(modifier) {
        DField(query, { query = it }, "종목 검색", modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown.size, key = { shown[it].name }) { i ->
                val t = shown[i]
                Surface(onClick = { onPick(t.name) }, shape = RoundedCornerShape(14.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${catalogRegionOf(t.name, t.category)} · ${catalogEquipmentOf(t.name)}", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        WashPill(if (postureSupported(t.name)) "자세 교정" else "기록 전용")
                    }
                }
            }
            if (shown.isEmpty()) item { Text("'${query.trim()}' 로 찾은 종목이 없어요. 운동 목록에서 직접 추가하면 내 기록에 쓸 수 있어요.", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
        }
    }
}
