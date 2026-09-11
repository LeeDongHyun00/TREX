package com.example.trex_kotlin

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

internal fun List<Workout>.moveWorkout(id: String, targetId: String): List<Workout> {
    val from = indexOfFirst { it.id == id }; val to = indexOfFirst { it.id == targetId }
    if (from < 0 || to < 0 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** 길게 누르면 순서 변경, 왼쪽 스와이프는 삭제 확인, 나머지 조작은 행 안에서 처리한다. */
@Composable
fun WorkoutTabScreen(app: AppViewModel, onOpenAlt: (Workout) -> Unit, onOpenSets: (Workout) -> Unit, onAddWorkout: () -> Unit) {
    val c = Trex.c
    val state = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    var plan by remember { mutableStateOf(app.workoutPlan) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var centerY by remember { mutableFloatStateOf(0f) }
    var draggedHeight by remember { mutableFloatStateOf(0f) }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var settleBlock by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var swipeReset by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(app.workoutPlan) { if (draggedId == null) plan = app.workoutPlan }
    LaunchedEffect(settleBlock) { if (settleBlock) { delay(280); settleBlock = false } }
    fun reorderAtPointer() {
        val id = draggedId ?: return
        val from = plan.indexOfFirst { it.id == id }
        val info = state.layoutInfo
        // 이동 애니메이션의 화면 좌표가 아니라 다음 배치의 슬롯 좌표를 사용한다.
        // 순서 변경 직후 아직 옛 배치를 읽는 한 프레임에는 다시 교환하지 않는다.
        val visible = info.visibleItemsInfo.filter { item -> plan.any { it.id == item.key } }
        if (visible.any { item -> item.index != plan.indexOfFirst { it.id == item.key } + 1 }) return
        val target = visible.filter { item ->
            val i = plan.indexOfFirst { it.id == item.key }
            val middle = item.offset + info.beforeContentPadding + item.size / 2f
            item.key != id && if (i > from) centerY >= middle - 2f else centerY <= middle + 2f
        }.minByOrNull { kotlin.math.abs(centerY - (it.offset + info.beforeContentPadding + it.size / 2f)) }
        if (target != null) plan = plan.moveWorkout(id, target.key as String)
    }
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val info = state.layoutInfo
            val usableBottom = info.viewportEndOffset + info.beforeContentPadding - info.afterContentPadding
            val distance = when {
                centerY < edge -> ((centerY-edge)*.15f).coerceAtLeast(-20f)
                centerY > usableBottom-edge -> ((centerY-usableBottom+edge)*.15f).coerceAtMost(20f)
                else -> 0f
            }
            if (distance != 0f) { state.scrollBy(distance); reorderAtPointer() }
            delay(16)
        }
    }
    fun finishDrag(save: Boolean) {
        if (draggedId != null) {
            if (save) app.updatePlan(plan) else plan = app.workoutPlan
            settleBlock = true; draggedId = null; swipeReset++
        }
    }
    fun moveAccessibly(id: String, delta: Int): Boolean {
        val i=plan.indexOfFirst { it.id==id };val target=plan.getOrNull(i+delta) ?: return false
        plan=plan.moveWorkout(id,target.id);app.updatePlan(plan);return true
    }
    val displayedPlan = plan
    val canClick = draggedId == null && !settleBlock
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { listOrigin=it.positionInRoot() }.pointerInput(state) {
            // Initial 단계에서 길게 누르기를 인계받는다. 버튼/스와이프가 같은 손가락을 다시 클릭으로 처리하지 못하게 한다.
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false, pass=PointerEventPass.Initial)
                val releasedOrMoved=withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var stopped=false
                    while(!stopped) {
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        val change=event.changes.firstOrNull { it.id==down.id }
                        stopped=change==null || !change.pressed || (change.position-down.position).getDistance()>viewConfiguration.touchSlop
                    }
                    true
                }
                if(releasedOrMoved==true) return@awaitEachGesture
                val visible=state.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                val picked=plan.firstOrNull { it.id in visible && bounds[it.id]?.contains(down.position+listOrigin)==true }
                    ?: return@awaitEachGesture
                val rect=bounds[picked.id] ?: return@awaitEachGesture
                draggedId=picked.id;draggedHeight=rect.height;centerY=rect.center.y-listOrigin.y
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                var dropped=false
                try {
                    while(true) {
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        val change=event.changes.firstOrNull { it.id==down.id } ?: break
                        val delta=change.positionChange();change.consume()
                        if(!change.pressed) { dropped=true;break }
                        centerY+=delta.y;reorderAtPointer()
                    }
                } finally { finishDrag(dropped) }
            }
        }, state=state, contentPadding=tabContentPadding) {
            item(key="workout-header") {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("오늘 운동",color=c.text,fontSize=30.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    IconButton(onClick=onAddWorkout,enabled=canClick) { Icon(Icons.Rounded.Add,"운동 추가",tint=c.primaryText) }
                }
                val completed=plan.count { it.done }
                Text(if(completed>0) "${plan.size}종목 중 ${completed}종목 완료" else "${plan.size}종목 · ${plan.sumOf { it.repsSpec().sets }}세트",
                    color=c.text2,fontSize=14.sp,modifier=Modifier.padding(top=8.dp,bottom=26.dp))
            }
            if(plan.isEmpty()) item(key="empty") { Text("오늘 할 운동을 추가해 보세요.",color=c.text2,modifier=Modifier.padding(vertical=32.dp)) }
            itemsIndexed(displayedPlan,key={_,w->w.id}) { index,workout ->
                val dragging=draggedId==workout.id
                val first=workout.id==plan.firstOrNull()?.id
                val last=workout.id==plan.lastOrNull()?.id
                val rowShape=RoundedCornerShape(topStart=if(first)20.dp else 0.dp,topEnd=if(first)20.dp else 0.dp,
                    bottomStart=if(last)20.dp else 0.dp,bottomEnd=if(last)20.dp else 0.dp)
                Box(Modifier.animateItem(placementSpec=if(dragging)null else spring(stiffness=600f))
                    .onGloballyPositioned { coordinates ->
                        val origin=coordinates.positionInRoot()
                        bounds[workout.id]=Rect(origin,androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(),coordinates.size.height.toFloat()))
                    }.semantics {
                        customActions=listOf(CustomAccessibilityAction("위로 이동"){moveAccessibly(workout.id,-1)},
                            CustomAccessibilityAction("아래로 이동"){moveAccessibly(workout.id,1)},CustomAccessibilityAction("삭제"){deleteId=workout.id;true})
                    }) {
                    if(dragging) Spacer(Modifier.fillMaxWidth().height(with(density){draggedHeight.toDp()}).clearAndSetSemantics { })
                    else key(first,last) { WorkoutSwipeRow(workout,rowShape,swipeReset,gesturesEnabled=canClick,last=last,
                        onEdit={if(draggedId==null && !settleBlock)app.workoutPlan.firstOrNull{it.id==workout.id}?.let(onOpenSets)},
                        onDelete={if(draggedId==null && !settleBlock)deleteId=workout.id},
                        onPosture={enabled->if(draggedId==null && !settleBlock)app.updatePlan(app.workoutPlan.map{if(it.id==workout.id)it.copy(posture=enabled)else it})}) }
                }
            }
        }
        // 떠 있는 행과 목록의 자리를 분리한다. 이동 애니메이션에 수동 translation을 더하지 않는다.
        plan.firstOrNull{it.id==draggedId}?.let { floating ->
            Box(Modifier.fillMaxWidth().padding(horizontal=20.dp).offset { IntOffset(0,(centerY-draggedHeight/2).roundToInt()) }
                .graphicsLayer { shadowElevation=8.dp.toPx();shape=RoundedCornerShape(20.dp) }.clearAndSetSemantics { }) {
                WorkoutSwipeRow(floating,RoundedCornerShape(20.dp),swipeReset,false,true,{},{},{})
            }
        }
    }
    val deleting = plan.firstOrNull { it.id == deleteId }
    if (deleting != null) AlertDialog(onDismissRequest = { deleteId = null; swipeReset++ }, containerColor = c.sheet,
        shape = RoundedCornerShape(26.dp), icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.err) },
        title = { Text("운동을 삭제할까요?", fontWeight = FontWeight.SemiBold) },
        text = { Text("오늘 운동에서 ${deleting.name} 항목을 삭제해요.") },
        dismissButton = { TextButton(onClick = { deleteId = null; swipeReset++ }) { Text("취소", color = c.text2) } },
        confirmButton = { TextButton(onClick = {
            plan = app.workoutPlan.filterNot { it.id == deleting.id }; app.updatePlan(plan); deleteId = null; swipeReset++
        }) { Text("삭제", color = c.err, fontWeight = FontWeight.SemiBold) } })
}

@Composable
private fun WorkoutSwipeRow(workout: Workout, shape: RoundedCornerShape, reset: Int, gesturesEnabled: Boolean,
    last: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onPosture: (Boolean) -> Unit) {
    val c = Trex.c
    val reveal = with(LocalDensity.current) { 80.dp.toPx() }
    var offset by remember(workout.id) { mutableFloatStateOf(0f) }
    var swiping by remember { mutableStateOf(false) }
    val displayed by animateFloatAsState(offset, tween(if (swiping) 0 else 220), label = "workout-swipe")
    LaunchedEffect(reset, gesturesEnabled) { offset = 0f }
    Box(Modifier.fillMaxWidth().clip(shape).background(if (displayed < -1f) c.err else c.surface)) {
        if (displayed < -1f) IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterEnd).width(80.dp)) {
            Icon(Icons.Rounded.DeleteOutline, "${workout.name} 삭제", tint = Color.White)
        }
        Column(Modifier.fillMaxWidth().graphicsLayer { translationX = displayed }.background(if(workout.done)c.primary.copy(alpha=.10f)else c.surface)
            .pointerInput(workout.id, gesturesEnabled) {
                if (gesturesEnabled) detectHorizontalDragGestures(
                    onDragStart = { swiping = true },
                    onHorizontalDrag = { change, amount -> change.consume(); offset = (offset + amount).coerceIn(-reveal * 1.6f, 0f) },
                    onDragEnd = { swiping = false; if (offset < -reveal * .65f) { offset = -reveal; onDelete() } else offset = 0f },
                    onDragCancel = { swiping = false; offset = 0f })
            }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(workout.done) Icon(Icons.Rounded.CheckCircle,"${workout.name} 완료",tint=c.primaryText,modifier=Modifier.size(21.dp))
                        Text(workout.name, color = if(workout.done)c.primaryText else c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(workout.reps + if (workout.repsSpec().sets > 1) " · 휴식 ${workout.timing().restSeconds}초" else "",
                        color = c.text2, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 7.dp))

                }
                Column(Modifier.padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = workout.posture && workout.postureSupported(), onCheckedChange = onPosture,
                            colors = postureSwitchColors(), enabled = gesturesEnabled && workout.postureSupported(),
                            modifier = Modifier.semantics {
                                contentDescription = "${workout.name} 자세 교정 사용"
                                stateDescription = if (!workout.postureSupported()) "미지원" else if (workout.posture) "사용 중" else "꺼짐"
                            })
                        IconButton(onClick = onEdit, enabled = gesturesEnabled, modifier = Modifier.padding(start = 4.dp)) {
                            Icon(Icons.Rounded.Edit, "${workout.name} 수정", tint = c.primaryText, modifier = Modifier.size(21.dp))
                        }
                    }
                    Text(if (workout.postureSupported()) "자세 교정" else "미지원", color = c.text2,
                        fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(52.dp))
                }
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 18.dp), color = c.line)
        }
    }
}

@Composable
internal fun postureSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Trex.c.primary, checkedThumbColor = Color.White,
    uncheckedTrackColor = Trex.c.track, uncheckedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    disabledUncheckedTrackColor = Trex.c.track.copy(alpha = Trex.c.track.alpha * .5f),
    disabledUncheckedBorderColor = Color.Transparent,
    disabledUncheckedThumbColor = Trex.c.surface,
)
