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

/** 영상만 볼 때도 엄지 쪽의 일시정지를 즉시 사용할 수 있다 */
@Composable
internal fun LiveQuickActions(onOpen: () -> Unit, onPause: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(onClick = onOpen, modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = "제어판 열기" },
            shape = RoundedCornerShape(26.dp), color = Color(0xE6111610)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Tune, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("제어", color = Color.White, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(onClick = onPause, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), color = Color.White) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Pause, null, tint = Color(0xFF1A2314), modifier = Modifier.size(20.dp))
                Text("일시정지", color = Color(0xFF1A2314), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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

