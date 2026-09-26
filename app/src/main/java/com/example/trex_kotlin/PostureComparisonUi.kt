package com.example.trex_kotlin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.ComparisonSnapshot
import com.example.trex_kotlin.posture.ComparisonState
import com.example.trex_kotlin.posture.NormalPoseMatch
import kotlin.math.abs

/** 화면상의 변화 막대와 수치는 동일 스냅샷에서 읽는다. 부위의 실제 화면 이동 방향으로 오인할 화살표는 그리지 않는다. */
@Composable
internal fun PostureComparisonPanel(
    snapshot: ComparisonSnapshot, mode: CoachMode, normal: List<NormalPoseMatch>, floor: Boolean,
    referenceView: String?, onReferenceView: (String?) -> Unit, onReset: () -> Unit,
    textColor: Color, mutedColor: Color,
) {
    val accent = Color(0xFFC48A12)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("처음 자세와 비교 · 참고", color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onReset) { Text("기준 다시 측정", fontSize = 11.sp) }
        }
        if (mode == CoachMode.COACH) Text(snapshot.message, color = mutedColor, fontSize = 11.sp)
        snapshot.values.take(2).forEach { item ->
            Text(item.detail, fontSize = 11.sp, color = if (item.changed) accent else textColor)
            ComparisonBand(item.initial - item.tolerance, item.initial + item.tolerance, item.current, item.initial,
                if (item.changed) accent else Color(0xFF4A9684))
        }
        if (snapshot.values.isNotEmpty()) Text("막대: 초반 기준의 비교 허용 범위 · 점: 현재 측정값 · 각도 외 수치는 신체 길이로 나눈 비율이에요. 거치 위치를 바꾸면 기준을 다시 측정해 주세요",
            fontSize = 10.sp, color = mutedColor)
        if (mode == CoachMode.COACH && floor) {
            var open by remember { mutableStateOf(false) }
            // 사선 B/D/E는 사용자 위치만으로 특정할 수 없다. 확인 가능한 측면만 연결한다(§39).
            val views = listOf(null to "선택 안 함 / 그 외 방향", "C" to "몸의 옆(측면)")
            Column {
                TextButton(onClick = { open = true }) { Text("정상 표본 비교 · 촬영 방향: ${views.firstOrNull { it.first == referenceView }?.second ?: "선택 안 함"}", fontSize = 11.sp) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    views.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onReferenceView(key); open = false }) }
                }
            }
            when {
                referenceView == null -> Text("현재 촬영 방향을 선택하면 같은 방향의 정상 표본과 비교해요", fontSize = 10.sp, color = mutedColor)
                normal.isEmpty() -> Text("동작 양 끝이 보이는 반복과 해당 방향의 표본이 필요해요", fontSize = 10.sp, color = mutedColor)
                else -> {
                    normal.take(2).forEach { item ->
                        Text(item.detail, fontSize = 11.sp, color = if (item.outside) accent else textColor)
                        ComparisonBand(item.band.lower, item.band.upper, item.current, null, if (item.outside) accent else Color(0xFF4A9684))
                    }
                    Text("스튜디오 높이에서 찍은 소수 표본이에요. 현재 폰 높이와 다를 수 있어 범위 밖이라는 이유만으로 잘못된 자세로 판정하지 않아요", fontSize = 10.sp, color = mutedColor)
                }
            }
        }
        if (snapshot.state == ComparisonState.RECOVERED) Text("처음 범위로 복귀", color = Color(0xFF4A9684), fontSize = 11.sp)
    }
}

@Composable
private fun ComparisonBand(low: Float, high: Float, current: Float, initial: Float?, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 5.dp)) {
        val padding = maxOf(abs(high-low) * .25f, .001f)
        val left = minOf(low, current) - padding; val right = maxOf(high, current) + padding
        fun x(value: Float) = (value-left) / (right-left) * size.width
        val y = size.height / 2
        drawLine(color.copy(alpha = .15f), Offset(0f, y), Offset(size.width, y), 3f)
        drawLine(color.copy(alpha = .35f), Offset(x(low), y), Offset(x(high), y), 9f, StrokeCap.Round)
        initial?.let { drawLine(color.copy(alpha = .6f), Offset(x(it), y-6f), Offset(x(it), y+6f), 2f) }
        drawCircle(color, 5f, Offset(x(current), y))
    }
}
