package com.example.trex_kotlin

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 화면 끝에서는 멈춘다. 왼쪽으로 밀면 내비게이션의 다음 탭이다. */
internal fun swipedTab(current: TrexTab, distance: Float, threshold: Float): TrexTab? {
    val step = when {
        distance <= -threshold -> 1
        distance >= threshold -> -1
        else -> return null
    }
    return TrexTab.entries.getOrNull(current.ordinal + step)
}

/** 자식이 소비한 삭제·정렬 제스처는 가져오지 않는다. 가장자리는 시스템 뒤로 동작에 남긴다. */
@Composable
internal fun Modifier.mainTabSwipe(current: TrexTab, enabled: Boolean, onSelect: (TrexTab) -> Unit): Modifier {
    val select by rememberUpdatedState(onSelect)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    return pointerInput(current, enabled, threshold, edge) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            var distance = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                distance = over
            } ?: return@awaitEachGesture
            val completed = horizontalDrag(drag.id) { change ->
                distance += change.positionChange().x
                change.consume()
            }
            if (completed) swipedTab(current, distance, threshold)?.let(select)
        }
    }
}
