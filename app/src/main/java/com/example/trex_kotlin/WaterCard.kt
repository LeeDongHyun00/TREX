package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDate

/** 한 잔 250 ml, 목표 2.0 L(8잔) — 식단 탭 물 카드(§131 P17). 저장은 예전부터 있던 `water` 항목(잔 수)을 그대로 쓴다. */
const val WATER_CUP_ML = 250
const val WATER_GOAL_CUPS = 8

@Composable
fun WaterCard(app: AppViewModel) {
    val c = Trex.c
    val cups = app.waterFor(0)
    val liters = cups * WATER_CUP_ML / 1000.0
    Surface(shape = RoundedCornerShape(22.dp), color = c.surface, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("물 · 오늘", color = c.text2, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatOneDecimal(liters.toFloat()).let { if (it.endsWith(".0")) it else it }, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(" / ${WATER_GOAL_CUPS * WATER_CUP_ML / 1000.0} L", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text("컵 ${cups}잔 · 한 잔 ${WATER_CUP_ML} ml", color = c.text2, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = { app.removeWater(0) }, enabled = cups > 0, shape = CircleShape, color = c.surface2, modifier = Modifier.size(44.dp).semantics { contentDescription = "한 잔 빼기" }) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, null, tint = if (cups > 0) c.text else c.text3) } }
                    Surface(onClick = { app.addWater(0) }, shape = CircleShape, color = c.primary, modifier = Modifier.size(44.dp).semantics { contentDescription = "한 잔 더" }) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = c.bg) } }
                }
            }
            TrackBar((cups.toFloat() / WATER_GOAL_CUPS).coerceIn(0f, 1f), color = c.primary, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                (6 downTo 0).forEach { offset ->
                    val n = app.waterFor(-offset)
                    val date = LocalDate.fromEpochDays(app.calendarDay - offset)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(when { n >= WATER_GOAL_CUPS -> c.primary; n > 0 -> c.primarySoftLine; else -> c.track }))
                        Text(koreanDayOfWeek(date.dayOfWeek), color = c.text3, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}
