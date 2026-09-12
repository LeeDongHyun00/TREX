package com.example.trex_kotlin

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.TrexText as Text

/** 두 방식의 이름을 직접 고른다. 선택 변화는 표시 정책만 바꾸며 세트를 초기화하지 않는다. */
@Composable
internal fun CoachModeControl(mode: CoachMode, preparing: Boolean, onSelect: (CoachMode) -> Unit) {
    val c = Trex.c
    val description = if (mode == CoachMode.TRACK) "처음 자세와의 변화를 비교해룡" else "운동 자세 교정을 안내해룡"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(4.dp)) {
            val width = maxWidth / 2
            val offset by animateDpAsState(if (mode == CoachMode.TRACK) width else 0.dp, tween(200), label = "coach-mode")
            Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.offset(x = offset).width(width).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(c.surface))
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    listOf(CoachMode.COACH to "자세 교정", CoachMode.TRACK to "기록 모드").forEach { (value, label) ->
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .selectable(selected = mode == value, role = Role.Tab, onClick = { if (mode != value) onSelect(value) })
                            .semantics {
                                contentDescription = label
                                stateDescription = if (value == CoachMode.TRACK) "처음 자세의 변화 비교" else "운동 자세 교정 안내"
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(label, modifier = Modifier.clearAndSetSemantics {}, color = if (mode == value) c.primaryText else c.text2,
                                fontSize = 14.sp, fontWeight = if (mode == value) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        if (preparing) Text(description, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
