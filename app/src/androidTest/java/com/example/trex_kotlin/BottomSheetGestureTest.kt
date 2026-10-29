package com.example.trex_kotlin

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import android.view.WindowManager

class BottomSheetGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestActivityVisible() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setTurnScreenOn(true)
            activity.setShowWhenLocked(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun tallSheetStartsLowExpandsAndDismissesWithoutRebounding() {
        var open by mutableStateOf(true)
        var dismissals = 0
        var screenHeight = 0f
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    screenHeight = maxHeight.value
                    if (open) SheetHost(onDismiss = { dismissals++; open = false }, startPartiallyExpanded = true) {
                        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("sheet-body")) {
                            Text("Sheet heading", Modifier.padding(24.dp))
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                repeat(60) { Text("Item $it", Modifier.padding(20.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("sheet-body", useUnmergedTree = true)
        val initial = sheet.getUnclippedBoundsInRoot().top.value
        assertTrue("Initial top=$initial screen=$screenHeight", initial > screenHeight * .3f)
        sheet.performTouchInput { swipe(Offset(centerX, 20f), Offset(centerX, -height * .35f), 600) }
        compose.waitForIdle()
        assertTrue(sheet.getUnclippedBoundsInRoot().top.value < initial - 40)
        // Move back down through the partial anchor, then dismiss.
        repeat(2) {
            if (open) {
                sheet.performTouchInput { swipe(Offset(centerX, 20f), Offset(centerX, height * .85f), 600) }
                compose.waitForIdle()
            }
        }
        assertFalse(open)
        assertEquals(1, dismissals)
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("sheet-body", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun numberWheelOwnsItsSwipeWhileSheetHeaderStillDrags() {
        var open by mutableStateOf(true)
        val wheel = WorkoutNumberWheelState(20, 1..20)
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                if (open) SheetHost({ open = false }, startPartiallyExpanded = true) {
                    Column(Modifier.fillMaxHeight(.92f).testTag("wheel-sheet")) {
                        Text("Heading", Modifier.fillMaxWidth().padding(20.dp).testTag("header"))
                        WorkoutNumberWheel("Sets", wheel, "sets", Modifier.width(120.dp))
                    }
                }
            }
        }
        val sheet = compose.onNodeWithTag("wheel-sheet", useUnmergedTree = true)
        val initial = sheet.getUnclippedBoundsInRoot().top
        compose.onNodeWithContentDescription("Sets").performTouchInput { swipeDown(durationMillis = 500) }
        compose.runOnIdle { assertEquals(20, wheel.value); assertTrue(open) }
        assertEquals(initial.value, sheet.getUnclippedBoundsInRoot().top.value, 2f)
        compose.onNodeWithContentDescription("Sets").performTouchInput { swipeUp(durationMillis = 500) }
        compose.runOnIdle { assertTrue("Wheel value=${wheel.value}", wheel.value < 20) }
        assertEquals(initial.value, sheet.getUnclippedBoundsInRoot().top.value, 2f)
        compose.onNodeWithContentDescription("Sets").performTouchInput { down(center); cancel() }
        compose.onNodeWithTag("header").performTouchInput {
            swipe(center, Offset(centerX, -height * 6f), 500)
        }
        assertTrue(sheet.getUnclippedBoundsInRoot().top < initial)
        repeat(3) {
            compose.onNodeWithContentDescription("Sets").performTouchInput { swipeDown(durationMillis = 500) }
            compose.runOnIdle { assertTrue(open) }
        }
        compose.runOnIdle { assertEquals(20, wheel.value) }
    }

    @Test fun replacementTabShowsSourceHeadingsAndSelectableExerciseNames() {
        val current = Workout("test", "런지", "12회 × 3세트", "3분", false, "하체")
        var picked: String? = null
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                WorkoutCatalogBrowser(current, { picked = it.name }, Modifier.fillMaxSize(),
                    replacementRecommendationGroups(current.name, listOf("푸쉬업"), listOf("덤벨 컬")))
            }
        }
        compose.onNodeWithText("날씨 기반 추천 운동").assertIsDisplayed()
        compose.onNodeWithText("근육 피로도 기반 추천 운동").assertIsDisplayed()
        compose.onNodeWithText("푸쉬업").assertIsDisplayed()
        compose.onNodeWithText("추천 대체 운동").assertDoesNotExist()
        compose.onNodeWithText("덤벨 컬").performClick()
        compose.runOnIdle { assertEquals("덤벨 컬", picked) }
    }

    @Test fun complexSheetStartsExpandedAndStillDismissesByDragging() {
        var open by mutableStateOf(true)
        var screenHeight = 0f
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    screenHeight = maxHeight.value
                    if (open) SheetHost({ open = false }) {
                        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("complex-sheet")) {
                            Text("Complex controls", Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
        val sheet = compose.onNodeWithTag("complex-sheet")
        assertTrue(sheet.getUnclippedBoundsInRoot().top.value < screenHeight * .3f)
        sheet.performTouchInput { swipe(Offset(centerX, 20f), Offset(centerX, height * .85f), 600) }
        compose.runOnIdle { assertFalse(open) }
    }

    @Test fun compactMenuExpandsWhenOpeningDetailedContent() {
        var detailed by mutableStateOf(false)
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                SheetHost({}, startPartiallyExpanded = true, expandContent = detailed) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("progressive-sheet")) {
                        Text(if (detailed) "Detail controls" else "Entry choices", Modifier.padding(24.dp))
                    }
                }
            }
        }
        val sheet = compose.onNodeWithTag("progressive-sheet")
        val initial = sheet.getUnclippedBoundsInRoot().top
        compose.runOnIdle { detailed = true }
        assertTrue(sheet.getUnclippedBoundsInRoot().top < initial - 40.dp)
        compose.onNodeWithText("Detail controls").assertIsDisplayed()
        val expanded = sheet.getUnclippedBoundsInRoot().top
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(expanded.value, sheet.getUnclippedBoundsInRoot().top.value, 2f)
    }
}
