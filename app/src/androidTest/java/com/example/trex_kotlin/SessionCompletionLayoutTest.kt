package com.example.trex_kotlin

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** PC 에뮬레이터에 논리 창 크기를 주어 콘텐츠 높이와 가장자리 입력을 검증한다. */
class SessionCompletionLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fittingContentStaysStillAndShortWideWindowsScrollFromGutter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".replay") && (Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic")))
        lateinit var app: AppViewModel
        instrumentation.runOnMainSync { app = AppViewModel(context.applicationContext as Application) }
        var viewport by mutableStateOf(DpSize(390.dp, 1100.dp))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TrexAppTheme(ThemeMode.Dark) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(viewport).testTag("completion-window")) {
                            SessionCompleteScreen(emptyList(), 0,
                                fatigueContent = { SessionMuscleMap(app, "layout-test") }, onDone = {})
                        }
                    }
                }
            }
        }
        fun scroll() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
        fun range() = scroll().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.waitForIdle()
        compose.onNodeWithText("사용 근육").assertIsDisplayed()
        compose.onNodeWithText("오늘 운동 사용 근육").assertDoesNotExist()
        assertEquals(0f, range().maxValue(), .01f)
        scroll().performTouchInput { swipeUp() }
        assertEquals(0f, range().value(), .01f)

        for (size in listOf(DpSize(700.dp, 360.dp), DpSize(800.dp, 600.dp))) {
            compose.runOnIdle { viewport = size }
            compose.waitForIdle()
            assertTrue("작은 가용 높이에서 스크롤이 열려야 함: $size", range().maxValue() > 0)
            scroll().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10000f) }
            // 세로 스크롤 영역 왼쪽 10px는 카드 밖의 여백에 있다.
            scroll().performTouchInput {
                down(Offset(10f, height * .85f))
                moveTo(Offset(10f, height * .15f), 500)
                up()
            }
            assertTrue("모델 바깥 여백에서 세로 스크롤이 동작해야 함: $size", range().value() > 0)
            scroll().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
            compose.waitForIdle()
            assertEquals(range().maxValue(), range().value(), 1f)
        }
        compose.runOnIdle { viewport = DpSize(390.dp, 1100.dp) }
        compose.waitForIdle()
        assertEquals(0f, range().maxValue(), .01f)
        assertEquals(0f, range().value(), .01f)
    }
}
