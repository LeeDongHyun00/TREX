package com.example.trex_kotlin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 세트 입력 행(§131, 디자인 P2·P3) — 세트 · kg · 회(또는 초) · 완료. 카메라가 센 값은 호박색 칸으로 구분하고 "다르면 고쳐 주세요 — 고친 값이 기록 기준" 을 밝힌다.
 * 숫자 칸은 44×48 이상(디자인 범례의 탭 대상 크기). 이 파일은 iPhone 도 그대로 컴파일한다.
 */

/** 편집 중인 세트 한 줄의 글자 상태 — 저장할 때 [toEntry] 로 숫자가 된다. */
@Stable
class SetRowDraft(index: Int, kg: String = "", count: String = "", done: Boolean = false, val cameraReps: Int? = null) {
    val index by mutableIntStateOf(index)
    var kg by mutableStateOf(kg)
    var count by mutableStateOf(count)
    var done by mutableStateOf(done)
    /** 카메라 값을 사용자가 고쳤는가 — 고치면 사용자 값이 기록 기준. */
    val edited: Boolean get() = cameraReps != null && count.toIntOrNull() != cameraReps

    fun toEntry(timed: Boolean, rpe: Int?, memo: String?): SetEntry = SetEntry(
        index = index, kg = kg.toDoubleOrNull()?.takeIf { it >= 0 },
        reps = if (timed) null else count.toIntOrNull()?.takeIf { it >= 0 }?.let { if (cameraReps != null && !edited) null else it },
        seconds = if (timed) count.toIntOrNull()?.takeIf { it >= 0 } else null,
        cameraReps = cameraReps, rpe = rpe, memo = memo?.takeIf { it.isNotBlank() }, done = done,
    )
}

fun SetEntry.toDraft(): SetRowDraft = SetRowDraft(index, kg?.let(::trimKg) ?: "", (reps ?: cameraReps ?: seconds)?.toString() ?: "", done, cameraReps)

@Composable
fun SetRowHeader(timed: Boolean) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("세트", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
        Text("kg", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text(if (timed) "초" else "회", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text("완료", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
    }
}

/** [enabled] 거짓이면 아직 할 세트(회색). [showKg] 거짓이면 kg 칸 대신 '—'(맨몸·시간 종목도 kg 을 적을 수 있어 기본 참). */
@Composable
fun SetRow(draft: SetRowDraft, timed: Boolean, enabled: Boolean = true, showKg: Boolean = true) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${draft.index}", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
        if (showKg) NumberCell(draft.kg, { draft.kg = it.decimalOnly().take(6) }, "kg", enabled, false, Modifier.weight(1f), "${draft.index}세트 kg")
        else Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) { Text("—", color = c.text3) }
        NumberCell(draft.count, { draft.count = it.digitsOnly().take(4) }, if (timed) "초" else "회", enabled, draft.cameraReps != null && !draft.edited, Modifier.weight(1f),
            "${draft.index}세트 ${if (timed) "초" else "회"}" + (draft.cameraReps?.let { ", 카메라가 센 값 $it" } ?: ""))
        Surface(onClick = { draft.done = !draft.done }, enabled = enabled, shape = RoundedCornerShape(12.dp),
            color = if (draft.done) c.primary else c.surface, border = BorderStroke(1.5.dp, if (!enabled) c.track else if (draft.done) c.primary else c.primarySoftLine),
            modifier = Modifier.size(44.dp).semantics { contentDescription = "${draft.index}세트 완료" + if (draft.done) " 됨" else "" }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = if (draft.done) c.bg else if (enabled) c.primaryText else c.track, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun NumberCell(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean, auto: Boolean, modifier: Modifier, description: String) {
    val c = Trex.c
    Box(modifier.height(48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (!enabled) c.bg else if (auto) c.warnWash else c.surface)
        .border(1.dp, if (auto) c.warnLine else c.fieldLine, RoundedCornerShape(12.dp))
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = if (enabled) c.text else c.text3, fontSize = 17.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = if (placeholder == "kg") KeyboardType.Decimal else KeyboardType.Number),
            cursorBrush = SolidColor(c.primaryText), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (value.isEmpty()) Text(placeholder, color = c.text3, fontSize = 15.sp); inner() } })
    }
}

/** 카메라 값 안내 한 줄 — 호박색 칸이 있을 때만. */
@Composable
fun CameraCountNote(count: Int) {
    val c = Trex.c
    Row(Modifier.padding(start = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.warnWash).border(1.dp, c.warnLine, RoundedCornerShape(3.dp)))
        Text("${count}회는 카메라가 센 값이에요. 다르면 고쳐 주세요 — 고친 값이 기록 기준이 됩니다.", color = c.text2, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** RPE · 메모 — 접어 둔 고급 입력(점진적 공개). [lastHint] 는 "지난번 RPE 8 · 메모 "벨트 착용"" 같은 안내. */
@Composable
fun RpeMemoExpander(rpe: Int?, onRpe: (Int?) -> Unit, memo: String, onMemo: (String) -> Unit, lastHint: String? = null) {
    val c = Trex.c
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RPE · 메모", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(rpe?.let { "RPE $it" }, memo.takeIf { it.isNotBlank() }?.let { "메모 \"$it\"" }).joinToString(" · ").ifBlank { lastHint ?: "힘든 정도와 메모를 남길 수 있어요" },
                        color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(Icons.Rounded.ExpandMore, if (open) "접기" else "펼치기", tint = c.text2, modifier = Modifier.rotate(if (open) 180f else 0f))
            }
            if (open) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RPE · 세트 끝에 느낀 힘든 정도(6 가벼움 ~ 10 더 못 함)", color = c.text2, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (6..10).forEach { v ->
                        Surface(onClick = { onRpe(if (rpe == v) null else v) }, shape = RoundedCornerShape(12.dp), color = if (rpe == v) c.text else c.surface2,
                            border = BorderStroke(1.dp, c.line), modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("$v", color = if (rpe == v) c.bg else c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                DField(memo, { onMemo(it.take(60)) }, "메모 · 벨트 착용, 무릎 불편 등", label = "메모")
            }
        }
    }
}
