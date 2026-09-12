package com.example.trex_kotlin

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 합성 창/힌지와 독립 저장소를 검증한다. 물리 폴더블 센서 검증은 아니다. */
class RestorationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.getExternalFilesDir("restoration_qa"), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun modeChoiceIsExplicitAndSurvivesLargeTextAndLiveTransition() {
        var mode by mutableStateOf(com.example.trex_kotlin.posture.CoachMode.COACH)
        var preparing by mutableStateOf(true)
        var fontScale by mutableStateOf(1f)
        var changes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TrexAppTheme(ThemeMode.Light) {
                    Box(Modifier.width(296.dp)) { CoachModeControl(mode, preparing) { mode = it; changes++ } }
                }
            }
        }
        compose.onNodeWithContentDescription("자세 교정").assertIsSelected()
        compose.onNodeWithContentDescription("기록 모드").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("처음 자세와의 변화를 비교해룡").assertIsDisplayed()
        compose.onNodeWithContentDescription("기록 모드").performClick()
        compose.runOnIdle { assertEquals(1, changes); fontScale = 2f }
        compose.onNodeWithContentDescription("기록 모드").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        snapshot("mode-choice-large")
        compose.runOnIdle { preparing = false; fontScale = 1f }
        compose.onNodeWithText("처음 자세와의 변화를 비교해룡").assertDoesNotExist()
        compose.onNodeWithContentDescription("기록 모드").assertIsSelected()
        compose.onNodeWithContentDescription("자세 교정").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("기록 모드").performClick().assertIsSelected()
    }
    @Test fun foldReflowKeepsSingleCameraAndAvoidsOffCenterHinge() {
        var dimensions by mutableStateOf(DpSize(360.dp, 640.dp))
        var horizontal by mutableStateOf(false)
        var origin by mutableStateOf(IntOffset.Zero)
        var starts=0; var disposes=0
        compose.setContent {
            val density=LocalDensity.current
            Box(Modifier.requiredSize(dimensions).onGloballyPositioned { origin=IntOffset(it.positionInWindow().x.toInt(),it.positionInWindow().y.toInt()) }) {
                val fold=if(horizontal) with(density) { ContentRect(origin.x,origin.y+270.dp.roundToPx(),origin.x+dimensions.width.roundToPx(),origin.y+290.dp.roundToPx()) } else null
                CompositionLocalProvider(LocalTrexFold provides fold) {
                    PostureAdaptiveLayout(
                        camera={ DisposableEffect(Unit) { starts++;onDispose{disposes++} };Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera")) },
                        controls={ Box(Modifier.fillMaxSize().background(Color.Green).testTag("controls")) })
                }
            }
        }
        compose.runOnIdle { dimensions=DpSize(840.dp,640.dp) }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("camera").getUnclippedBoundsInRoot().right <= compose.onNodeWithTag("controls").getUnclippedBoundsInRoot().left)
        compose.runOnIdle { horizontal=true }
        compose.waitForIdle()
        val a=compose.onNodeWithTag("camera").getUnclippedBoundsInRoot()
        val b=compose.onNodeWithTag("controls").getUnclippedBoundsInRoot()
        assertEquals(270f,a.height.value,.5f); assertEquals(20f,(b.top-a.bottom).value,.5f)
        compose.runOnIdle { horizontal=false;dimensions=DpSize(360.dp,640.dp) }
        compose.waitForIdle();assertEquals(1,starts);assertEquals(0,disposes)
    }
    @Test fun pipExpandsWithoutRemountingAndKeepsHudInPlace() {
        var progress by mutableStateOf(1f)
        var starts = 0
        compose.setContent {
            Box(Modifier.requiredSize(360.dp,640.dp)) {
                PostureAdaptiveLayout(immersive = true, panelProgress = progress,
                    header = { androidx.compose.material3.Text("3회", Modifier.testTag("persistent-count")) },
                    camera = {
                        DisposableEffect(Unit) { starts++; onDispose {} }
                        Box(Modifier.fillMaxSize().testTag("immersive-camera")) {
                        }
                    },
                    controls = { Box(Modifier.fillMaxSize().testTag("moving-panel")) })
            }
        }
        val before = compose.onNodeWithTag("immersive-camera").getUnclippedBoundsInRoot()
        val hudBefore = compose.onNodeWithTag("persistent-count").getUnclippedBoundsInRoot()
        assertTrue(hudBefore.top >= before.bottom)
        assertTrue(before.bottom <= compose.onNodeWithTag("moving-panel").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { progress = 0f }
        compose.onNodeWithTag("persistent-count").assertIsDisplayed()
        compose.onNodeWithTag("moving-panel").assertIsNotDisplayed()
        assertTrue(before.height < compose.onNodeWithTag("immersive-camera").getUnclippedBoundsInRoot().height)
        val hudAfter = compose.onNodeWithTag("persistent-count").getUnclippedBoundsInRoot()
        assertTrue(hudAfter.top > hudBefore.top)
        compose.runOnIdle { progress = 1f }
        compose.onNodeWithTag("moving-panel").assertIsDisplayed()
        assertEquals(1, starts)
    }
    @Test fun centeredGoalAndPauseActionsRemainReadableAtLargeText() {
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TrexAppTheme(ThemeMode.Light) {
                    Column(Modifier.width(320.dp)) {
                        LiveWorkoutHud(todayPlan[1].copy(target=WorkoutTarget.Duration(90)),0,64,90,"1 / 2 세트",false,false,null)
                        Row { CameraExpandAction({},true,Modifier.weight(1f)); PreparationPauseAction(false,true,{},Modifier.width(104.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithText("01:04").assertIsDisplayed()
        compose.onNodeWithText("목표 01:30").assertIsDisplayed()
        snapshot("pip-centered-goal")
        compose.runOnIdle { fontScale=2f }
        compose.onNodeWithText("01:04").assertIsDisplayed()
        compose.onNodeWithText("목표 01:30").assertIsDisplayed()
        compose.onNodeWithContentDescription("제어판 접기").assertHeightIsAtLeast(48.dp)
        snapshot("pip-goal-large")
    }
    @Test fun logoAndInputSurviveNarrowToWideResize() {
        var width by mutableStateOf(320.dp)
        compose.setContent { TrexAppTheme(ThemeMode.Light) { Box(Modifier.requiredSize(width,640.dp)) { AuthScreen({},{}) } } }
        compose.onNodeWithContentDescription("TREX 로고").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("layout-test")
        compose.runOnIdle { width=600.dp }
        compose.onNodeWithText("layout-test").assertExists()
        snapshot("login-wide")
    }
    @Test fun nutritionAndGuideRemainReachableAtLargeText() {
        var guide by mutableStateOf(false)
        var done=false
        compose.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) { TrexAppTheme(ThemeMode.Light) {
                Box(Modifier.requiredSize(360.dp,480.dp)) {
                    if(guide) GuideBookScreen { done=true }
                    else HomeNutrition(Nutrition(200,20.0,10.0,5.0),Nutrition(2000,250.0,120.0,60.0),{})
                }
            } }
        }
        compose.onNodeWithText("탄수화물").assertExists()
        compose.runOnIdle { guide=true }
        repeat(3) { compose.onNodeWithText("다음").assertIsDisplayed().performClick() }
        compose.onNodeWithText("시작하기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(done) }
        snapshot("guide-large")
    }
    @Test fun migrationPreservesRealDataAndBacksUpExactSamples() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="restoration_migration"
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=TrexStore(context,name)
        assertNull(store.loadHistory());assertNull(store.loadDiet())
        val date=java.time.LocalDate.now().toEpochDay()
        val sample=WorkoutHistoryDay(date-1,"금","9/11",listOf(
            WorkoutHistoryItem("기본 스쿼트","12회 x 3세트",4,13),WorkoutHistoryItem("플랭크","60초 x 3세트",5,15)),8,80)
        val real=createWorkoutHistoryDay(listOf(todayPlan[0].copy(done=true)),32,elapsedByWorkout=mapOf("squat" to 32))
        store.saveHistory(listOf(sample,real))
        val meal=mapOf("breakfast" to listOf(FoodEntry("내 식사",Nutrition(320,30.0,15.0,8.0))))
        store.saveDiet(mapOf(date to meal))
        val stored=org.json.JSONArray(prefs.getString("history",null))
        stored.getJSONObject(1).put("futureMetadata","keep-me")
        prefs.edit().putString("history",stored.toString()).commit()
        val original=prefs.getString("history",null)
        prefs.edit().remove("demo_cleanup_v2").commit()
        val clean=TrexStore(context,name)
        assertEquals(listOf(real),clean.loadHistory())
        assertEquals(original,prefs.getString("before_demo_cleanup_history",null))
        assertEquals("keep-me",org.json.JSONArray(prefs.getString("history",null)).getJSONObject(0).getString("futureMetadata"))
        assertEquals(meal,clean.loadDiet()!![date])
        assertEquals(listOf(real),TrexStore(context,name).loadHistory())
        prefs.edit().clear().commit()
    }
}
