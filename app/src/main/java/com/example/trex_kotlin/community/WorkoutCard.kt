package com.example.trex_kotlin.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.TrexDark
import com.example.trex_kotlin.TrexLime
import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.asClock
import com.example.trex_kotlin.koreanDayOfWeekFull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * 오운완 카드에 들어갈 숫자(설계 §3 오운완 카드) — 날짜·운동 이름·완료 세트·총 시간·예상 kcal **만**. 자세 판정(헤드라인·점수·
 * 자세 블록)은 넣지 않는다 — 공개 이미지에서 판정 정직성 원칙을 다시 지켜야 하고 자세 문구는 동현님 영역이다.
 */
data class WorkoutCardData(
    val dateLabel: String,
    val workouts: List<String>,
    val doneSets: Int,
    val duration: String,
    val kcal: Int,
)

/** 완료 화면과 같은 숫자 — 완료 세트는 `done` 수, 시간은 세션 경과, [kcal] 은 완료 화면이 보인 값을 그대로 받는다. */
fun workoutCardData(plan: List<Workout>, elapsedSeconds: Int, kcal: Int, date: LocalDate): WorkoutCardData = WorkoutCardData(
    dateLabel = "${date.month.number}월 ${date.day}일 ${koreanDayOfWeekFull(date.dayOfWeek)}",
    workouts = plan.map { it.name }.distinct(),
    doneSets = plan.count { it.done },
    duration = elapsedSeconds.asClock(),
    kcal = kcal,
)

/** 오운완 카드 — 정사각형, 앱 색(어두운 바탕·라임). 글쓰기 화면이 이 그림을 떠서 첫 사진으로 올린다. */
@Composable
fun WorkoutCard(data: WorkoutCardData, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().aspectRatio(1f)
            .background(Brush.linearGradient(listOf(TrexDark, Color(0xFF2B3A1F)))),
    ) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = TrexLime, modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = TrexDark, modifier = Modifier.size(20.dp)) }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("오운완", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(data.dateLabel, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
            Text(
                data.workouts.joinToString(" · "), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 27.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("${data.doneSets}" to "완료 세트", data.duration to "총 시간", "${data.kcal}" to "예상 kcal").forEach { (value, label) ->
                    Column {
                        Text(value, color = TrexLime, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
            Text("T-REX", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        }
    }
}
