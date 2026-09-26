package com.example.trex_kotlin

import com.example.trex_kotlin.posture.RepValidation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.TrexText as Text

/**
 * 얼굴 앞을 비우고 하단의 낮은 표시줄에 진행 정보를 모은다.
 * @param countNote 목표 옆의 짧은 횟수 표기(반복 목표에서만) — 좌우 짝 단위 종목의 "좌우 한 번씩 = 1회" / "반대쪽 차례"(사용자 결정 2026-09-24).
 *   화면 전용이다. [countNoteActive] 면 강조색(사용자가 할 다음 동작).
 * @param hideCount 렙 검증 모드 — 반복 수 대신 "검증 중" 을 보인다.
 * @param sideCount 쪽별로 세는 종목(런지, §63)의 큰 글자 — 쪽마다 남은 수("왼 7 · 오 8"). null 이면 "N회".
 */
@Composable
internal fun LiveWorkoutHud(workout: Workout, repetitions: Int, timeLeft: Int, totalSeconds: Int,
    setLabel: String, paused: Boolean, compact: Boolean, message: String?,
    countNote: String? = null, countNoteActive: Boolean = false, hideCount: Boolean = false, sideCount: String? = null) {
    val duration = workout.resolvedTarget() is WorkoutTarget.Duration
    Surface(color = Color(0xEB111610), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // hideCount: 렙 검증 모드(spec §61) — 집계자가 앱 숫자에 끌려가지 않게 숫자 대신 '검증 중'
                Text(if (duration) timeLeft.asClock() else if (hideCount) RepValidation.HIDDEN_COUNT else sideCount ?: "${repetitions}회", color = Color.White,
                    fontSize = if (sideCount != null && !duration && !hideCount) (if (compact) 22.sp else 26.sp) else if (compact) 28.sp else 32.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${workout.name} · $setLabel", color = Color.White.copy(alpha = .75f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (duration) Canvas(Modifier.size(18.dp)) {
                            val stroke = 3.dp.toPx()
                            val arcSize = Size(size.width - stroke, size.height - stroke)
                            val origin = Offset(stroke / 2, stroke / 2)
                            drawArc(Color.White.copy(alpha = .2f), -90f, 360f, false, origin, arcSize, style = Stroke(stroke))
                            drawArc(Color(0xFFB8DD83), -90f,
                                360f * (1f - timeLeft.toFloat() / totalSeconds.coerceAtLeast(1)).coerceIn(0f, 1f),
                                false, origin, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                        // 검증 모드는 앱이 센 수를 보이지 않는다(§61) — 목표 단위만
                        Text(if (duration) "목표 ${totalSeconds.asClock()}"
                            else if (sideCount != null) "좌우 각 ${workout.resolvedTarget().amount}회" + (if (hideCount) "" else " · ${repetitions}회 완료")
                            else "목표 ${workout.resolvedTarget().amount}회",
                            color = Color.White, fontSize = 14.sp)
                        if (!duration && countNote != null) Text(countNote,
                            color = if (countNoteActive) Color(0xFFB8DD83) else Color.White.copy(alpha = .75f),
                            fontSize = 12.sp, fontWeight = if (countNoteActive) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (paused || message != null) Text(if (paused) "일시정지" else message.orEmpty(),
                color = if (paused) Color(0xFFFFD08A) else Color.White.copy(alpha = .9f),
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
