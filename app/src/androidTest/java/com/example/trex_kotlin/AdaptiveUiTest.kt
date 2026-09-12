package com.example.trex_kotlin

import android.app.Application
import android.os.ParcelFileDescriptor
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.posture.SpeechCoach
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 접힘은 합성 좌표로 주입한다. 실카메라·실기기 센서의 정확도를 검증하는 테스트는 아니다. */
@RunWith(AndroidJUnit4::class)
class AdaptiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun grantCameraForTest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant $packageName android.permission.CAMERA"),
        ).use { it.readBytes() }
    }

    private fun snapshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getExternalFilesDir("layout_qa"), "$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun reflowAndFoldKeepCameraCompositionAlive() {
        var window by mutableStateOf(DpSize(360.dp, 720.dp))
        var foldDp by mutableStateOf<ContentRect?>(null)
        var starts = 0
        var disposes = 0
        compose.setContent {
            val scale = LocalDensity.current.density
            val fold = foldDp?.let { ContentRect((it.left * scale).toInt(), (it.top * scale).toInt(), (it.right * scale).toInt(), (it.bottom * scale).toInt()) }
            CompositionLocalProvider(LocalTrexFold provides fold) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Box(Modifier.size(window)) {
                        PostureAdaptiveLayout(
                            camera = {
                                DisposableEffect(Unit) { starts++; onDispose { disposes++ } }
                                Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera"))
                            },
                            controls = { Box(Modifier.fillMaxSize().background(Color.Green).testTag("controls")) },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("camera").assertIsDisplayed()
        compose.runOnIdle { window = DpSize(840.dp, 720.dp) }
        compose.waitForIdle()
        val camera = compose.onNodeWithTag("camera").getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("controls").getUnclippedBoundsInRoot()
        assertTrue(camera.right <= controls.left)
        compose.runOnIdle { foldDp = ContentRect(0, 300, 840, 320) }
        compose.waitForIdle()
        assertEquals(300.dp, compose.onNodeWithTag("camera").getUnclippedBoundsInRoot().bottom)
        assertEquals(320.dp, compose.onNodeWithTag("controls").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, disposes) }
        snapshot("fold-layout")
    }

    @Test fun workoutShortTapEditsAndArrowStillExpands() {
        val app = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        var edited: String? = null
        compose.setContent { TrexAppTheme(ThemeMode.Light) {
            WorkoutTabScreen(app, {}, { edited = it.id }, {})
        } }
        compose.onNodeWithText("기본 스쿼트").performClick()
        compose.runOnIdle { assertEquals("squat", edited); edited = null }
        compose.onNodeWithText("기본 스쿼트").performTouchInput { longClick() }
        compose.runOnIdle { assertNull(edited) }
        compose.onAllNodesWithContentDescription("운동 상세 펼치기")[0].performClick()
        compose.onNodeWithText("대체 운동").assertIsDisplayed()
        compose.onNodeWithText("세트 수정").assertDoesNotExist()
        compose.runOnIdle { assertNull(edited) }
        snapshot("workout-edit")
    }

    @Test fun loginLogoIsVisibleAndFieldsSurviveResize() {
        var width by mutableStateOf(360.dp)
        compose.setContent { TrexAppTheme(ThemeMode.Light) {
            Box(Modifier.size(width, 800.dp)) {
                AuthScreen({}, {}, {})
            }
        } }
        compose.onNodeWithContentDescription("TREX 로고").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("layout-test")
        compose.runOnIdle { width = 600.dp }
        compose.onNodeWithText("layout-test").assertExists()
        snapshot("login-logo")
    }

    @Test fun largeTextAndLowWindowKeepTimerActionsVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TrexAppTheme(ThemeMode.Light) {
                    Box(Modifier.size(600.dp, 360.dp)) {
                        TimerSessionScreen(todayPlan.first(), 0, 5, 120, 300, false, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("다음 운동").assertIsDisplayed()
        compose.onNodeWithContentDescription("일시정지").assertIsDisplayed()
        snapshot("timer-large-text")
    }

    @Test fun freshStoreIsEmptyAndRealRecordRoundTrips() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "trex_layout_qa_store"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val store = TrexStore(context, name)
        assertNull(store.loadHistory())
        assertNull(store.loadDiet())
        val plan = todayPlan.take(1).map { it.copy(done = true) }
        val record = createWorkoutHistoryDay(plan, 65, elapsedByWorkout = mapOf("squat" to 65))
        store.saveHistory(listOf(record))
        assertEquals(record, TrexStore(context, name).loadHistory()!!.single())
        assertEquals(65, TrexStore(context, name).loadHistory()!!.single().items.single().durationSeconds)
    }

    @Test fun tapOpensEditorAndLargeTextCanReachSave() {
        val app = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        var sheet by mutableStateOf<MainSheet?>(null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TrexAppTheme(ThemeMode.Light) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        WorkoutTabScreen(app, {}, { sheet = MainSheet.Sets(it.setDraft()) }, {})
                        sheet?.let { MainSheetHost(app, it, { sheet = null }) }
                    }
                }
            }
        }
        compose.onNodeWithText("기본 스쿼트").performClick()
        compose.onNodeWithText("세트 수정").assertIsDisplayed()
        compose.onNodeWithText("저장").performScrollTo().assertIsDisplayed()
        snapshot("editor-large-text")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("세트 수정").assertDoesNotExist()
    }

    @Test fun guideFitsLowWindowAndAllPagesAreReachable() {
        var finished = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TrexAppTheme(ThemeMode.Light) {
                    Box(Modifier.size(360.dp, 360.dp)) { GuideBookScreen { finished = true } }
                }
            }
        }
        repeat(3) { compose.onNodeWithText("다음").assertIsDisplayed().performClick() }
        compose.onNodeWithText("시작하기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test fun actualPostureScreenKeepsActionsAcrossResize() {
        val speech = SpeechCoach(ApplicationProvider.getApplicationContext<Context>()).apply { muted = true }
        var window by mutableStateOf(DpSize(360.dp, 360.dp))
        var paused by mutableStateOf(false)
        var finished = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TrexAppTheme(ThemeMode.Light) {
                    Box(Modifier.size(window)) {
                        PostureLiveSessionScreen(todayPlan[1], 0, 1, 120, 300, paused,
                            { paused = !paused }, { finished = true }, {}, speech = speech)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("일시정지").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(paused); window = DpSize(840.dp, 700.dp) }
        compose.onNodeWithContentDescription("완료").assertIsDisplayed()
        snapshot("posture-wide")
        compose.runOnIdle { window = DpSize(360.dp, 360.dp) }
        compose.onNodeWithContentDescription("완료").assertIsDisplayed()
        snapshot("posture-compact")
        compose.onNodeWithContentDescription("완료").performClick()
        compose.runOnIdle { assertTrue(finished) }
        speech.shutdown()
    }

    @Test fun demoMigrationBacksUpAndPreservesRealHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "trex_layout_qa_migration"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = TrexStore(context, name)
        val sample = WorkoutHistoryDay(20000, "목", "10/3", listOf(
            WorkoutHistoryItem("기본 스쿼트", "12회 x 3세트", 8, 56),
            WorkoutHistoryItem("플랭크", "60초 x 3세트", 5, 25),
        ), 9, 80)
        val real = createWorkoutHistoryDay(listOf(todayPlan[0].copy(done = true)), 65, elapsedByWorkout = mapOf("squat" to 65))
        store.saveHistory(listOf(sample, real))
        val original = prefs.getString("history", null)
        prefs.edit().remove("demo_cleanup_v1").commit()
        assertEquals(listOf(real), TrexStore(context, name).loadHistory())
        assertEquals(original, prefs.getString("before_demo_cleanup_history", null))
        assertEquals(listOf(real), TrexStore(context, name).loadHistory())
    }
}
