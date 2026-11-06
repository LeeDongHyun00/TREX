package com.example.trex_kotlin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.TrexText as Text
import kotlin.math.PI
import kotlin.math.sin

/*
 * 운동 준비·진행 제어판의 공통 모양(스펙 §115) — 흰 둥근 판 위 손잡이, 세 칸 수치(왼쪽 작은 수치 · 가운데 큰 시간/횟수 · 오른쪽 세트),
 * 그 아래 물결 무늬 위의 둥근 아이콘 버튼 줄. 주요 동작 하나만 채운 원으로 강조한다.
 * 동작·문구·접근성 이름은 예전 버튼 그대로 둔다 — 화면 읽기와 UI 테스트가 같은 이름으로 찾는다.
 */

/** 원형 버튼·작은 칩 하나의 내용 — 화면마다 다른 도구(음성·카메라 전환·숫자 읽기)를 공통 줄에 끼운다. */
class SessionToolSpec(val label: String, val description: String, val icon: ImageVector, val onClick: () -> Unit)

private val SET_FRACTION = Regex("(\\d+)\\s*/\\s*(\\d+)")

/** 세트 칸의 값 — "1 / 2 세트" → "1/2". 형식이 다르면 받은 글자를 그대로 쓴다. */
internal fun setFraction(setLabel: String): String {
    val core = setLabel.trim().removeSuffix("세트").trim()
    return SET_FRACTION.matchEntire(core)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: setLabel
}

/**
 * 판 맨 위 손잡이. [onCollapse] 가 있으면 누르거나 아래로 끌어 판을 접는다 — 접근성 이름은 예전 '화면 넓게' 버튼과 같은 "제어판 접기".
 * 없으면(준비 단계) 모양만 있다.
 */
@Composable
internal fun SessionPanelHandle(onCollapse: (() -> Unit)? = null, enabled: Boolean = true) {
    val c = Trex.c
    val bar: @Composable () -> Unit = {
        Box(Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape)
            .background(c.text3.copy(alpha = if (onCollapse == null || enabled) .5f else .22f)))
    }
    if (onCollapse == null) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), contentAlignment = Alignment.Center) { bar() }
        return
    }
    val collapse by rememberUpdatedState(onCollapse)
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .clickable(enabled = enabled, role = Role.Button) { collapse() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var dragged = 0f
                detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged > 24.dp.toPx()) collapse() }) { change, dy ->
                    dragged += dy; change.consume()
                }
            }
            .semantics { contentDescription = "제어판 접기" },
        contentAlignment = Alignment.Center,
    ) { bar() }
}

/** 세 칸 수치 줄 — 왼쪽·오른쪽은 이름과 값, 가운데는 [center](큰 시간·횟수, 준비의 카운트다운)다. */
@Composable
internal fun SessionStatsRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String,
    modifier: Modifier = Modifier, center: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SessionStat(leftLabel, leftValue, Alignment.Start, Modifier.weight(1f))
        Column(Modifier.weight(1.6f), horizontalAlignment = Alignment.CenterHorizontally, content = center)
        SessionStat(rightLabel, rightValue, Alignment.End, Modifier.weight(1f))
    }
}

@Composable
private fun SessionStat(label: String, value: String, align: Alignment.Horizontal, modifier: Modifier) {
    val c = Trex.c
    Column(modifier, horizontalAlignment = align) {
        Text(label, color = c.text2, fontSize = 12.sp, maxLines = 1)
        Text(value, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 가운데 큰 수치 — 남은 시간이나 센 횟수. [suffix] 는 옆의 작은 글자("/ 12회"), [caption] 은 아래 한 줄. */
@Composable
internal fun SessionBigValue(value: String, suffix: String? = null, caption: String? = null) {
    val c = Trex.c
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = c.text, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (suffix != null) Text(suffix, color = c.text2, fontSize = 15.sp, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
    }
    if (caption != null) Text(caption, color = c.text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 물결 무늬 위의 원형 버튼 줄 — 버튼은 [SessionCircleButton] 에 `Modifier.weight(1f)` 를 주어 같은 폭으로 놓는다. */
@Composable
internal fun SessionActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Box(modifier.fillMaxWidth()) {
        SessionWaves(Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, content = content)
    }
}

/** 버튼 줄 뒤의 장식 물결 — 그림만 있고 읽히지 않는다. 크기가 바뀔 때만 다시 그린다. */
@Composable
private fun SessionWaves(modifier: Modifier) {
    val c = Trex.c
    Canvas(modifier) {
        val stroke = Stroke(1.5.dp.toPx())
        val middle = size.height * .36f   // 원의 가운데 높이쯤
        listOf(Triple(0f, .10f, .17f), Triple(1.4f, .14f, .11f), Triple(2.8f, .18f, .07f)).forEach { (phase, amplitude, alpha) ->
            val path = Path()
            val steps = 60
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val x = size.width * t
                val y = middle + size.height * amplitude * sin(2.0 * PI * 1.4 * t + phase).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, c.primary.copy(alpha = alpha), style = stroke)
        }
    }
}

/**
 * 원형 아이콘 버튼과 아래 이름. 누르는 자리는 원과 이름을 합친 칸 전체다(이름 글자를 눌러도 같은 동작).
 * [highlighted] 는 그 화면의 주요 동작 하나 — 크게, 주색으로 채운다. [description] 이 화면 읽기 이름이다.
 */
@Composable
internal fun SessionCircleButton(label: String, description: String, icon: ImageVector, onClick: () -> Unit,
    modifier: Modifier = Modifier, highlighted: Boolean = false, destructive: Boolean = false, enabled: Boolean = true) {
    val c = Trex.c
    val circle = if (highlighted) 60.dp else 50.dp
    val tint = when { highlighted -> Color.White; destructive -> c.err; else -> c.text }
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else .45f)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(circle)
                    .then(if (highlighted) Modifier.shadow(6.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(if (highlighted) c.primary else c.surface2)
                    .then(if (highlighted) Modifier else Modifier.border(1.dp, if (destructive) c.errLine else c.line, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(if (highlighted) 28.dp else 22.dp))
            }
        }
        Text(label, color = if (destructive) c.err else if (highlighted) c.primaryText else c.text2, fontSize = 11.sp,
            lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** 버튼 줄 아래의 작은 도구 칩 줄 — 넓이가 모자라면 다음 줄로 넘긴다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionChipRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 작은 도구 칩 — 횟수 수정·숫자 읽기·운동 방법처럼 덜 자주 쓰는 동작. */
@Composable
internal fun SessionChip(label: String, description: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Trex.c
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = description },
        shape = CircleShape, color = c.surface2, border = BorderStroke(1.dp, c.line)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, null, tint = c.primaryText, modifier = Modifier.size(16.dp))
            Text(label, color = c.text, fontSize = 13.sp, maxLines = 1)
        }
    }
}
