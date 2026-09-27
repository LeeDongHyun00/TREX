package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 표시와 저장이 같은 선택값을 읽는다. 비동기 콜백을 거쳐 별도 초안에 복제하지 않는다. */
internal class WorkoutNumberWheelState(initialValue: Int, val range: IntRange) {
    private val initial = initialValue.coerceIn(range)
    val listState = LazyListState(firstVisibleItemIndex = initial - range.first)
    val value: Int by derivedStateOf {
        val layout = listState.layoutInfo
        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
        val index = layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
        if (index == null) initial else (range.first + index).coerceIn(range)
    }
}

/** 아래로 당기면 증가한다. 가운데 줄이 선택값이며 손을 떼면 줄 중앙에 맞춘다. */
@Composable
internal fun WorkoutNumberWheel(label: String, wheel: WorkoutNumberWheelState, unit: String,
    modifier: Modifier = Modifier) {
    val c = Trex.c
    val state = wheel.listState
    val range = wheel.range
    val value = wheel.value
    val scope = rememberCoroutineScope()
    fun select(next: Int) {
        scope.launch { state.animateScrollToItem(next.coerceIn(range) - range.first) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 12.dp))
        Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(44.dp).background(c.primaryWash))
            LazyColumn(state = state, reverseLayout = true,
                flingBehavior = rememberSnapFlingBehavior(state),
                contentPadding = PaddingValues(vertical = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = label
                    stateDescription = "$value$unit"
                    customActions = listOf(
                        CustomAccessibilityAction("늘리기") { select(wheel.value + 1); true },
                        CustomAccessibilityAction("줄이기") { select(wheel.value - 1); true },
                    )
                }) {
                items(range.last - range.first + 1, key = { it }) { index ->
                    val number = range.first + index
                    Box(Modifier.fillMaxWidth().height(44.dp).clickable { select(number) }, contentAlignment = Alignment.Center) {
                        Text("$number", fontSize = if (number == value) 26.sp else 19.sp,
                            fontWeight = if (number == value) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (number == value) c.text else c.text2.copy(alpha = .4f))
                    }
                }
            }
        }
        Text(unit, color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
    }
}
