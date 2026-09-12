package com.example.trex_kotlin

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt

val LocalTrexNavSpace = staticCompositionLocalOf { 110.dp }

val LocalTrexFold = staticCompositionLocalOf<ContentRect?> { null }

/** 테스트에서는 같은 CompositionLocal에 합성 힌지를 전달할 수 있다. */
@Composable
fun rememberTrexFold(): ContentRect? {
    val activity = LocalContext.current.findTrexActivity()
    var fold by remember(activity) { mutableStateOf<ContentRect?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
                    ?.bounds?.let { ContentRect(it.left, it.top, it.right, it.bottom) }
            }
        }
    }
    return fold
}

private fun ContentRect.localTo(origin: Offset) = ContentRect(
    left - origin.x.roundToInt(), top - origin.y.roundToInt(),
    right - origin.x.roundToInt(), bottom - origin.y.roundToInt(),
)

/** 단일 화면은 힌지를 피한 한 영역 안에 둔다. 내용 인스턴스는 크기 변경 중에도 유지한다. */
@Composable
fun TrexContentFrame(maxWidth: Dp = 840.dp, useWholeWindow: Boolean = false, content: @Composable () -> Unit) {
    val fold = LocalTrexFold.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        content = { Box(Modifier.fillMaxSize()) { content() } },
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() },
    ) { children, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val region = if (useWholeWindow) ContentRect(0, 0, w, h)
            else singleContentRegion(w, h, fold?.localTo(origin), maxWidth.roundToPx())
        val child = children.single().measure(Constraints.fixed(region.width, region.height))
        layout(w, h) { child.place(region.left, region.top) }
    }
}

/** 카메라와 패널을 같은 Composition 위치에서 측정·배치해 전환 중 View 중복 연결을 막는다. */
@Composable
fun PostureAdaptiveLayout(camera: @Composable () -> Unit, controls: @Composable () -> Unit) {
    val fold = LocalTrexFold.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        content = {
            Box(Modifier.fillMaxSize()) { camera() }
            Box(Modifier.fillMaxSize()) { controls() }
        },
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() },
    ) { children, constraints ->
        val regions = sessionRegions(constraints.maxWidth, constraints.maxHeight, fold?.localTo(origin), density)
        val cameraView = children[0].measure(Constraints.fixed(regions.camera.width, regions.camera.height))
        val controlView = children[1].measure(Constraints.fixed(regions.controls.width, regions.controls.height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            cameraView.place(regions.camera.left, regions.camera.top)
            controlView.place(regions.controls.left, regions.controls.top)
        }
    }
}

/** 180도 회전·디스플레이 전환도 configuration 재생성 없이 받는다. */
@Composable
fun rememberTrexDisplayRotation(): Int {
    val view = LocalView.current
    val context = LocalContext.current
    var rotation by remember(view) { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view, context) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { rotation = view.display?.rotation ?: rotation }
            override fun onDisplayRemoved(displayId: Int) { rotation = view.display?.rotation ?: rotation }
            override fun onDisplayChanged(displayId: Int) { rotation = view.display?.rotation ?: rotation }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return rotation
}
