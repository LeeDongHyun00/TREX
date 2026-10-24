package com.example.trex_kotlin.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.CoachModeControl
import com.example.trex_kotlin.Cta
import com.example.trex_kotlin.DField
import com.example.trex_kotlin.FilterChipRow
import com.example.trex_kotlin.GhostButton
import com.example.trex_kotlin.Kicker
import com.example.trex_kotlin.MacroBar
import com.example.trex_kotlin.RingGauge
import com.example.trex_kotlin.SegmentedTabs
import com.example.trex_kotlin.StepperControl
import com.example.trex_kotlin.ThemeMode
import com.example.trex_kotlin.TitleBig
import com.example.trex_kotlin.TrackBar
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexAppTheme
import com.example.trex_kotlin.WashBanner
import com.example.trex_kotlin.WorkoutNumberWheel
import com.example.trex_kotlin.WorkoutNumberWheelState
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.sessionRegions
import com.example.trex_kotlin.TrexText as Text

/**
 * Phase 0 스파이크(§108): Android 정본 디자인 부품을 iPhone 에서 그대로 그린다.
 * 이 파일은 부품을 늘어놓기만 한다 — 화면에 보이는 것은 전부 app/ 의 Android 소스가 그린다.
 */
@Composable
fun ComposeProbe(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    TrexAppTheme(mode) {
        val c = Trex.c
        var coach by remember { mutableStateOf(CoachMode.COACH) }
        var sets by remember { mutableIntStateOf(3) }
        var meal by remember { mutableStateOf("점심") }
        var query by remember { mutableStateOf("") }
        val wheel = remember { WorkoutNumberWheelState(12, 1..50) }
        Column(
            Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Kicker("ANDROID COMPOSE · IPHONE")
            TitleBig("디자인 부품 확인")
            SegmentedTabs(listOf("라이트", "다크", "시스템"), mode.ordinal, { onMode(ThemeMode.entries[it]) })
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RingGauge(progress = 0.62f, size = 112.dp, stroke = 10.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("1402", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("/ 2260 kcal", color = c.text3, fontSize = 11.sp)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MacroBar("탄수화물", 182, 290, c.primary, showPercent = true)
                        MacroBar("단백질", 71, 133, c.lime, showPercent = true)
                        MacroBar("지방", 49, 63, c.warn, showPercent = true)
                    }
                }
            }
            WashBanner("목표까지 858 kcal 남았어요", Icons.Rounded.Info)
            FilterChipRow(listOf("아침", "점심", "간식", "저녁"), meal, { meal = it })
            DField(query, { query = it }, placeholder = "음식 이름", label = "한글 입력 확인")
            ProbeCard {
                CoachModeControl(coach, preparing = true, onSelect = { coach = it })
                StepperControl("$sets 세트", onDec = { if (sets > 1) sets-- }, onInc = { sets++ }, label = "세트")
                WorkoutNumberWheel("목표", wheel, "회", Modifier.fillMaxWidth())
                TrackBar(progress = sets / 10f)
            }
            Cta("운동 시작", onClick = {}, icon = Icons.Rounded.CameraAlt)
            GhostButton("기록만 하기", onClick = {}, icon = Icons.Rounded.CheckCircle)
            val regions = sessionRegions(1179, 2556, null, 3f)
            Text("자세 화면 배치(AdaptivePolicy): 카메라 ${regions.camera.width}×${regions.camera.height}",
                color = c.text3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProbeCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Trex.c.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}
