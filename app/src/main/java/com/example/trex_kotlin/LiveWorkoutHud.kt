package com.example.trex_kotlin

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

/** 얼굴 앞을 비우고 하단의 낮은 표시줄에 진행 정보를 모은다. */
@Composable
internal fun LiveWorkoutHud(workout: Workout, repetitions: Int, timeLeft: Int, totalSeconds: Int,
    setLabel: String, paused: Boolean, compact: Boolean, message: String?, repDetail: String? = null,
    evaluationEngineLabel: String? = null) {
    val duration = workout.resolvedTarget() is WorkoutTarget.Duration
    Surface(color = Color(0xEB111610), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            evaluationEngineLabel?.let {
                Text(it, color = Color(0xFFB8DD83), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (duration) timeLeft.asClock() else "${repetitions}회", color = Color.White,
                    fontSize = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
                        Text(if (duration) "목표 ${totalSeconds.asClock()}" else "목표 ${workout.resolvedTarget().amount}회",
                            color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            repDetail?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White.copy(alpha = .85f), fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (paused || message != null) Text(if (paused) "일시정지" else message.orEmpty(),
                color = if (paused) Color(0xFFFFD08A) else Color.White.copy(alpha = .9f),
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
