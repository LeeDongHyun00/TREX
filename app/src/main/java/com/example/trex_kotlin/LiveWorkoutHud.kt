package com.example.trex_kotlin

import com.example.trex_kotlin.posture.RepValidation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.TrexText as Text

/**
 * 무대 아래 띠(docs/LIVE_SCREEN_REDESIGN.md §4) — 2.5 m 에서 읽히는 것은 큰 숫자 하나와 색뿐이라 글자를 교정 한 줄과 숫자로 줄였다.
 * 큰 숫자 = **남은 수**(목표 − 전달된 횟수. COACH 는 정확 수 기준 — 자세로 뺀 회는 [countNote] 가 밝힌다). 카드 배경 없이 아래로 짙어지는 그늘만.
 * @param message 교정 한 줄(없으면 비운다 — "움직임을 비교하고 있어요" 같은 상태 문구는 쓰지 않는다).
 * @param countNote 목표 옆의 짧은 횟수 표기 — 좌우 짝 단위 종목의 "좌우 한 번씩 = 1회" / "반대쪽 차례", 자세로 뺀 회. 화면 전용. [countNoteActive] 면 강조색.
 * @param hideCount 렙 검증 모드 — 반복 수 대신 "검증 중" 을 보인다.
 * @param sideCount 쪽별로 세는 종목(런지, §63)의 큰 글자 — 쪽마다 남은 수("왼 7 · 오 8"). null 이면 남은 수.
 * @param elapsedSec 세트 경과(보조 정보 — 반복 운동의 시간은 끝내는 조건이 아니다, THUMB_FIRST §1).
 * @param referenceNote beta 검사의 '참고' 칩 — 말하지 않는 판정은 작게, 호박색(원칙 #2).
 */
@Composable
internal fun LiveWorkoutHud(workout: Workout, repetitions: Int, timeLeft: Int, totalSeconds: Int,
    setLabel: String, paused: Boolean, compact: Boolean, message: String?,
    countNote: String? = null, countNoteActive: Boolean = false, hideCount: Boolean = false, sideCount: String? = null,
    elapsedSec: Int? = null, referenceNote: String? = null, modeLabel: String? = null) {
    val duration = workout.resolvedTarget() is WorkoutTarget.Duration
    val target = workout.resolvedTarget().amount
    val lime = Color(0xFFB8DD83)
    val amber = Color(0xFFFFC24B)
    val dim = Color.White.copy(alpha = .7f)
    Column(Modifier.fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))))
        .padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(setLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(workout.name, color = dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (modeLabel != null) Text(modeLabel, color = lime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(99.dp)).padding(horizontal = 9.dp, vertical = 2.dp))
            if (!duration && elapsedSec != null) Text(elapsedSec.asClock(), color = dim, fontSize = 14.sp, maxLines = 1)
        }
        val line = if (paused) "일시정지" else message
        if (line != null) Text(line, color = if (paused) amber else Color.White,
            fontSize = if (compact) 17.sp else 22.sp, lineHeight = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        val big = if (compact) 44.sp else 72.sp
        val bigLine = if (compact) 48.sp else 76.sp
        Row(Modifier.padding(top = if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                duration -> {
                    Canvas(Modifier.size(if (compact) 22.dp else 30.dp).padding(bottom = 0.dp).align(Alignment.CenterVertically)) {
                        val stroke = 3.dp.toPx()
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val origin = Offset(stroke / 2, stroke / 2)
                        drawArc(Color.White.copy(alpha = .2f), -90f, 360f, false, origin, arcSize, style = Stroke(stroke))
                        drawArc(lime, -90f, 360f * (1f - timeLeft.toFloat() / totalSeconds.coerceAtLeast(1)).coerceIn(0f, 1f),
                            false, origin, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Text(timeLeft.asClock(), color = Color.White, fontSize = big, lineHeight = bigLine, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("/ ${totalSeconds.asClock()}", color = dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = if (compact) 8.dp else 14.dp))
                }
                // 렙 검증 모드(spec §61) — 집계자가 앱 숫자에 끌려가지 않게 숫자 대신 '검증 중'
                hideCount -> Text(RepValidation.HIDDEN_COUNT, color = Color.White, fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                sideCount != null -> {
                    Text(sideCount, color = Color.White, fontSize = if (compact) 30.sp else 44.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("남음", color = dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = if (compact) 4.dp else 8.dp))
                }
                else -> {
                    Text("${(target - repetitions).coerceAtLeast(0)}", color = Color.White, fontSize = big, lineHeight = bigLine, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("남음", color = dim, fontSize = 18.sp, modifier = Modifier.padding(bottom = if (compact) 8.dp else 14.dp))
                    Text("$repetitions / $target", color = dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = if (compact) 8.dp else 14.dp))
                }
            }
        }
        if (!duration && countNote != null) Text(countNote, color = if (countNoteActive) lime else amber, fontSize = 13.sp,
            fontWeight = if (countNoteActive) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (referenceNote != null) Text("참고 · $referenceNote", color = amber, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).border(1.dp, amber.copy(alpha = .6f), RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}
