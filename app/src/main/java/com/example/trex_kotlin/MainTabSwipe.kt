package com.example.trex_kotlin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 탭을 넘기는 기준(px) — 충분히 민 거리, 또는 빠르게 튕긴 속도(px/초)와 그때 필요한 짧은 거리. */
internal class TabSwipeThresholds(val distance: Float, val flingDistance: Float, val flingVelocity: Float)

/**
 * 화면 끝에서는 멈춘다. 왼쪽으로 밀면 내비게이션의 다음 탭이다. [distance]·[velocity] 는 왼쪽이 음수다.
 * 충분히 밀었거나, 빠르게 튕겼으면 짧아도 넘긴다 — 튕긴 방향이 민 방향과 같을 때만.
 */
internal fun swipedTab(current: TrexTab, distance: Float, velocity: Float, thresholds: TabSwipeThresholds): TrexTab? {
    val flung = abs(velocity) >= thresholds.flingVelocity && abs(distance) >= thresholds.flingDistance &&
        (velocity < 0f) == (distance < 0f)
    val step = when {
        distance <= -thresholds.distance || (flung && distance < 0f) -> 1
        distance >= thresholds.distance || (flung && distance > 0f) -> -1
        else -> return null
    }
    return TrexTab.entries.getOrNull(current.ordinal + step)
}

/**
 * 메인 탭 좌우 스와이프. 자식이 먼저 잡은 가로 제스처(운동 행 삭제·사진 넘김·칩 줄)는 가져오지 않고, 처음 움직임이 세로에 가까우면
 * 목록 스크롤에 남긴다. 미는 동안 화면이 손가락을 조금 따라오고(넘길 탭이 없는 끝에서는 더 무겁게), 손을 떼면 넘기거나 제자리로 돌아온다.
 * 가장자리 16dp 는 시스템 뒤로 동작에 남긴다.
 *
 * 처음엔 64dp 이상 밀어야만 넘어갔다 — 실기기·에뮬레이터에서 빠르게 튕기는 40~53dp 가 넘어가지 않았다(2026-10-29 사용자 보고 "잘 안된다").
 */
@Composable
internal fun Modifier.mainTabSwipe(current: TrexTab, enabled: Boolean, onSelect: (TrexTab) -> Unit): Modifier {
    val select by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val thresholds = remember(density) { with(density) { TabSwipeThresholds(56.dp.toPx(), 16.dp.toPx(), 400.dp.toPx()) } }
    val edge = with(density) { 16.dp.toPx() }
    val follow = with(density) { 40.dp.toPx() }
    val shift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 손가락 좌표는 밀리기 전(바깥) 좌표로 읽는다 — 화면을 옮기는 graphicsLayer 가 안쪽이라 이동량에 끼어들지 않는다
    return pointerInput(current, enabled, thresholds, edge) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            val velocity = VelocityTracker().apply { addPointerInputChange(down) }
            var distance = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                val moved = change.position - down.position
                // 세로에 가까우면 잡지 않는다 — 목록 스크롤이 가져간다
                if (abs(moved.x) >= abs(moved.y)) {
                    change.consume()
                    distance = over
                }
            } ?: return@awaitEachGesture
            velocity.addPointerInputChange(drag)
            val completed = horizontalDrag(drag.id) { change ->
                distance += change.positionChange().x
                velocity.addPointerInputChange(change)
                change.consume()
                val pull = if (TrexTab.entries.getOrNull(current.ordinal + if (distance < 0f) 1 else -1) != null) 0.35f else 0.12f
                scope.launch { shift.snapTo((distance * pull).coerceIn(-follow, follow)) }
            }
            val target = if (completed) swipedTab(current, distance, velocity.calculateVelocity().x, thresholds) else null
            scope.launch {
                if (target != null) shift.animateTo(0f, tween(200))
                else shift.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
            target?.let(select)
        }
    }.graphicsLayer { translationX = shift.value }
}
