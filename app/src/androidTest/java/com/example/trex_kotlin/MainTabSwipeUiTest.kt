package com.example.trex_kotlin

import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 메인 탭 좌우 스와이프(MainTabSwipe.kt, 스펙 §114) — 세로 목록 위에서 실제 손가락처럼 밀어 본다.
 * 길게 밀기·빠르게 튕기기는 넘기고, 느린 짧은 이동·세로 스크롤·가장자리·가로로 스크롤되는 자식·끝 탭은 넘기지 않는다.
 */
class MainTabSwipeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var tab by mutableStateOf(TrexTab.Workout)
    private val list = LazyListState()

    private fun show(start: TrexTab = TrexTab.Workout, chips: Boolean = false) {
        tab = start
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                Box(Modifier.requiredSize(390.dp, 760.dp).testTag("tabs").mainTabSwipe(tab, enabled = true) { tab = it }) {
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        if (chips) item {
                            Row(Modifier.fillMaxWidth().height(80.dp).horizontalScroll(rememberScrollState()).testTag("chips")) {
                                repeat(20) { Box(Modifier.size(80.dp)) }
                            }
                        }
                        items(80) { Text("항목 $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }
            }
        }
    }

    /** 화면 [fromX]·[fromY](비율)에서 시작해 ([dx], [dy]) 만큼 [ms] 동안 민다. */
    private fun drag(dx: Dp, dy: Dp = 0.dp, ms: Long, fromX: Float = 0.5f, fromY: Float = 0.6f, tag: String = "tabs") {
        compose.onNodeWithTag(tag).performTouchInput {
            val start = Offset(width * fromX, height * fromY)
            swipe(start, start + Offset(dx.toPx(), dy.toPx()), ms)
        }
        compose.waitForIdle()
    }

    @Test fun longDragMovesOneTabEachWayAndStopsAtTheEnds() {
        show()
        drag((-160).dp, ms = 500)
        assertEquals(TrexTab.Diet, tab)
        drag(160.dp, ms = 500)
        assertEquals(TrexTab.Workout, tab)
        drag(160.dp, ms = 500)
        assertEquals(TrexTab.Home, tab)
        drag(160.dp, ms = 500)
        assertEquals(TrexTab.Home, tab)
    }

    @Test fun lastTabDoesNotWrap() {
        show(TrexTab.Profile)
        drag((-200).dp, ms = 400)
        assertEquals(TrexTab.Profile, tab)
    }

    @Test fun quickShortFlickMovesTab() {
        show()
        drag((-40).dp, ms = 60)
        assertEquals(TrexTab.Diet, tab)
        drag(40.dp, ms = 60)
        assertEquals(TrexTab.Workout, tab)
    }

    @Test fun slowShortDragStays() {
        show()
        drag((-30).dp, ms = 700)
        assertEquals(TrexTab.Workout, tab)
    }

    @Test fun mostlyVerticalDragScrollsTheListInstead() {
        show()
        drag((-60).dp, (-320).dp, ms = 400, fromY = 0.85f)
        assertEquals(TrexTab.Workout, tab)
        assertTrue("목록이 스크롤돼야 한다", list.firstVisibleItemIndex > 0)
    }

    @Test fun slightlyDiagonalSwipeStillMovesTab() {
        show()
        drag((-180).dp, (-60).dp, ms = 400)
        assertEquals(TrexTab.Diet, tab)
    }

    @Test fun edgeIsLeftToTheSystemBackGesture() {
        show()
        // 오른쪽 끝 6dp 에서 시작한 왼쪽 밀기
        compose.onNodeWithTag("tabs").performTouchInput {
            val start = Offset(width - 6.dp.toPx(), height * 0.6f)
            swipe(start, start + Offset((-220).dp.toPx(), 0f), 400)
        }
        compose.waitForIdle()
        assertEquals(TrexTab.Workout, tab)
    }

    @Test fun horizontallyScrollingChildKeepsItsDrag() {
        show(chips = true)
        drag((-200).dp, ms = 400, tag = "chips", fromY = 0.5f)
        assertEquals(TrexTab.Workout, tab)
    }
}
