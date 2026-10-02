package com.example.trex_kotlin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.TrexText as Text

@Composable
internal fun CameraExpandAction(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = Trex.c
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)
        .semantics { contentDescription = "제어판 접기" }, shape = RoundedCornerShape(18.dp),
        color = c.surface, border = BorderStroke(1.dp, c.line)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.text2, modifier = Modifier.size(20.dp))
            Text("화면 넓게", color = c.text2.copy(alpha = if (enabled) 1f else .45f), fontSize = 14.sp)
        }
    }
}

/**
 * 무대 아래 엄지 줄(docs/LIVE_SCREEN_REDESIGN.md §4.3) — 일시정지 · 세트 끝 · 제어판. 다가와서 누르는 상황이라 셋 다 64 dp.
 * 세트 끝은 지금까지 센 수로 세트를 마감한다 — 카운터가 마지막 회를 구조적으로 놓쳐 "1 남음" 에서 멈출 수 있어 큰 자리를 차지한다(§4.1).
 */
@Composable
internal fun LiveQuickActions(onOpen: () -> Unit, onPause: () -> Unit, onFinish: () -> Unit, paused: Boolean, modifier: Modifier = Modifier) {
    val glass = Color(0xB3000000)
    val edge = BorderStroke(1.dp, Color.White.copy(alpha = .28f))
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(onClick = onPause, modifier = Modifier.weight(1f).height(64.dp).semantics { contentDescription = if (paused) "재개" else "일시정지" },
            shape = RoundedCornerShape(20.dp), color = glass, border = edge) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Surface(onClick = onFinish, modifier = Modifier.weight(2f).height(64.dp).semantics { contentDescription = "세트 끝" },
            shape = RoundedCornerShape(20.dp), color = Color(0xE6FFFFFF)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("세트 끝", color = Color(0xFF1A2314), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(onClick = onOpen, modifier = Modifier.width(64.dp).height(64.dp).semantics { contentDescription = "제어판 열기" },
            shape = RoundedCornerShape(20.dp), color = glass, border = edge) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Tune, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
internal fun PreparationPauseAction(paused: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Trex.c
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)
        .semantics { contentDescription = if (paused) "재개" else "일시정지" },
        shape = RoundedCornerShape(16.dp), color = if (paused) c.primaryWash else c.surface2) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = c.primaryText, modifier = Modifier.size(20.dp))
            Text(if (paused) "준비 계속" else "준비 멈춤", color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

