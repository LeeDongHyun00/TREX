package com.example.trex_kotlin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 순서 바꾸기 중 촉각 피드백이 필요한 지점. */
enum class ReorderFeedback { Lift, Move, Drop }

/**
 * LazyColumn 안에서 "길게 눌러 들어 올린 뒤 끌어서 순서 바꾸기".
 *
 * 들고 있는 아이템은 **LazyColumn 아이템 key** 로 따라간다. 순서가 바뀌면 인덱스는 그 자리에서
 * 달라지지만 key 는 그대로라, 스왑 직후 한 프레임 동안 엉뚱한 카드가 들려 보이는 일이 없다.
 * 위치는 레이아웃을 건드리지 않고 `translationY` 로만 띄우므로 스크롤/재사용과 충돌하지 않는다.
 *
 * 옮길 수 있는 구간은 `canDrag`(절대 인덱스) 가 정하고 — 운동 탭처럼 헤더가 섞인 리스트를 위한 것이다 —
 * 실제 목록 변경은 `onMove` 가 맡는다(상태 소유자는 계속 AppViewModel).
 */
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val canDragAt: (Int) -> Boolean,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onFeedback: (ReorderFeedback) -> Unit,
) {

    /** 지금 들려 있는 아이템의 key. 손을 뗀 뒤 착지 애니메이션이 끝날 때까지 유지된다. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** 들어 올린 순간의 슬롯 오프셋 — 손가락 아래 화면 위치를 고정하는 기준점. */
    private var anchorOffset by mutableIntStateOf(0)
    private var dragDelta by mutableFloatStateOf(0f)

    /** 마지막 드래그 방향 부호 — 자리 바꿈 판정은 방향에 따라 위/아래 이웃만 본다. */
    private var dragDirection = 0f

    private var settling by mutableStateOf(false)
    private val settle = Animatable(0f)
    private var settleJob: Job? = null

    internal val autoScroll = Channel<Float>(Channel.CONFLATED)

    private val draggingInfo: LazyListItemInfo?
        get() = draggingKey?.let { key -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /** 원래 슬롯에서 얼마나 떠 있는지(px) — 카드의 translationY 값. */
    val draggingOffset: Float
        get() = when {
            settling -> settle.value
            else -> draggingInfo?.let { anchorOffset + dragDelta - it.offset } ?: 0f
        }

    fun isDragging(key: Any): Boolean = draggingKey == key

    internal fun start(key: Any) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        if (!canDragAt(info.index)) return
        settleJob?.cancel()
        settling = false
        draggingKey = key
        anchorOffset = info.offset
        dragDelta = 0f
        dragDirection = 0f
        onFeedback(ReorderFeedback.Lift)
    }

    internal fun drag(deltaY: Float) {
        if (draggingKey == null) return
        dragDelta += deltaY
        if (deltaY != 0f) dragDirection = deltaY
        if (swapIfNeeded()) return

        // 위/아래 끝에 닿으면 그만큼 리스트를 밀어준다 (카드는 손가락 자리에 그대로 떠 있는다)
        val info = draggingInfo ?: return
        val top = info.offset + draggingOffset
        val bottom = top + info.size
        val layout = listState.layoutInfo
        val overscroll = when {
            deltaY > 0f -> (bottom - layout.viewportEndOffset).coerceAtLeast(0f)
            deltaY < 0f -> (top - layout.viewportStartOffset).coerceAtMost(0f)
            else -> 0f
        }
        if (overscroll != 0f) autoScroll.trySend(overscroll)
    }

    internal fun stop(cancelled: Boolean) {
        if (draggingKey == null) return
        val landed = draggingOffset
        if (!cancelled) onFeedback(ReorderFeedback.Drop)
        settleJob = scope.launch {
            settling = true
            settle.snapTo(landed)
            settle.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            settling = false
            draggingKey = null
            dragDelta = 0f
            anchorOffset = 0
        }
    }

    internal suspend fun consumeAutoScroll() {
        for (delta in autoScroll) {
            if (draggingKey == null) continue
            listState.scrollBy(delta)
            swapIfNeeded()
        }
    }

    /**
     * 이웃 카드를 절반 넘게 덮으면 그 자리와 바꾼다.
     *
     * "겹치면 바꾼다"가 아니라 "이웃의 중점을 지나면 바꾼다"로 판정한다 — 펼쳐진 카드처럼 높이가
     * 제각각일 때 겹침 기준은 바꾼 직후 다시 조건을 만족해 앞뒤로 튀기 때문이다.
     * 한 번에 여러 칸을 지나갔으면 가장 멀리 있는 대상까지 한 번에 옮긴다.
     */
    private fun swapIfNeeded(): Boolean {
        val info = draggingInfo ?: return false
        val from = info.index
        val top = info.offset + draggingOffset
        val bottom = top + info.size
        val items = listState.layoutInfo.visibleItemsInfo
        val target = when {
            dragDirection > 0f -> items.lastOrNull {
                it.index > from && canDragAt(it.index) && bottom > it.offset + it.size / 2f
            }
            dragDirection < 0f -> items.firstOrNull {
                it.index < from && canDragAt(it.index) && top < it.offset + it.size / 2f
            }
            else -> null
        } ?: return false

        // 첫 보이는 아이템이 끼어 있으면 스크롤 위치가 튄다 — 옮긴 뒤 같은 자리로 되돌린다
        val firstVisible = listState.firstVisibleItemIndex
        val keepScroll = from == firstVisible || target.index == firstVisible
        val scrollOffset = listState.firstVisibleItemScrollOffset

        onMove(from, target.index)
        if (keepScroll) scope.launch { listState.scrollToItem(firstVisible, scrollOffset) }
        onFeedback(ReorderFeedback.Move)
        return true
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    canDrag: (index: Int) -> Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onFeedback: (ReorderFeedback) -> Unit = {},
): ReorderState {
    val scope = rememberCoroutineScope()
    val canDragNow by rememberUpdatedState(canDrag)
    val onMoveNow by rememberUpdatedState(onMove)
    val onFeedbackNow by rememberUpdatedState(onFeedback)
    val state = remember(listState) {
        ReorderState(
            listState = listState,
            scope = scope,
            canDragAt = { canDragNow(it) },
            onMove = { from, to -> onMoveNow(from, to) },
            onFeedback = { onFeedbackNow(it) },
        )
    }
    LaunchedEffect(state) { state.consumeAutoScroll() }
    return state
}

/**
 * 카드에 붙이는 "길게 눌러 끌기" 제스처 + 들림 오프셋.
 *
 * [key] 는 LazyColumn 아이템 key 와 같아야 한다 — 순서가 바뀌어도 제스처가 끊기지 않는다.
 */
fun Modifier.reorderable(state: ReorderState, key: Any): Modifier = this
    .zIndex(if (state.isDragging(key)) 1f else 0f)
    .graphicsLayer { translationY = if (state.isDragging(key)) state.draggingOffset else 0f }
    .pointerInput(key, state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key) },
            onDrag = { _, amount -> state.drag(amount.y) },
            onDragEnd = { state.stop(cancelled = false) },
            onDragCancel = { state.stop(cancelled = true) },
        )
    }

/** [from] 자리의 원소를 [to] 자리로 옮긴 새 목록. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}
