package com.example.trex_kotlin.validation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StudioUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun reviewZeroIsExplicitAndPredictionsStayHiddenUntilSaved() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudyStore(context)
        val person="SYNTHETIC-${newId().take(8)}"
        val dir=store.create(person,"2026-09-23","calibration","바벨 스쿼트","SIMULTANEOUS","정면",false,false,"synthetic_test")
        StudyPipelineTest().makeVideo(File(dir,"video.mp4"));store.finishVideo(dir)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("바벨 스쿼트 · $person").performScrollTo().performClick()
                compose.onNodeWithText("같은 좌표로 엔진 비교").assertIsNotEnabled()
                compose.onNodeWithText("비교 결과 열기").assertDoesNotExist()
                compose.onNodeWithText("영상 보며 정답 표시").performScrollTo().performClick()
                compose.onNodeWithText("평가자 코드").performScrollTo().performTextInput("R-SYNTHETIC")
                compose.onNode(isToggleable()).performScrollTo().performClick()
                compose.onNodeWithText("정답 개정 저장").performScrollTo().performClick()
                compose.waitUntil(10000) { store.latest(dir,"labels") != null }
                val truth=store.truth(store.latest(dir,"labels")!!)
                assertTrue(truth.repsReviewed);assertTrue(truth.reps.isEmpty());assertFalse(truth.predictionExposed)
                compose.onNodeWithText("관절 좌표 추출").performScrollTo().performClick()
                compose.waitUntil(120000) {store.latest(dir,"extractions") != null}
                compose.waitUntil(10000) {compose.onAllNodes(hasText("같은 좌표로 엔진 비교") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("같은 좌표로 엔진 비교").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(30000) {store.latest(dir,"runs") != null}
                compose.waitUntil(10000) {compose.onAllNodes(hasText("비교 결과 열기") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("비교 결과 열기").performScrollTo().performClick()
                compose.onNodeWithText("동일 입력 비교 결과").performScrollTo().assertIsDisplayed()
                assertTrue(File(dir,"prediction-exposed.json").exists())
                val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.cacheDir,"studio-report.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
        } finally {check(dir.parentFile!!.canonicalFile == store.root.canonicalFile);dir.deleteRecursively()}
    }
}
