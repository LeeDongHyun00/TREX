package com.example.trex_kotlin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests the production sheet in an empty activity without changing user records or preferences. */
class ExerciseGuideCatalogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "guide-verification").apply { mkdirs() }

    @Before fun showTestActivity() {
        compose.activityRule.scenario.onActivity {
            it.setTurnScreenOn(true)
            it.setShowWhenLocked(true)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun allSeventyTwoGifsDecodeAndEveryFrameDiffers() {
        val results = mutableListOf<String>()
        ExerciseGuides.all.forEach { guide ->
            val bytes = context.assets.open(guide.asset).use { it.readBytes() }
            val movie = requireNotNull(Movie.decodeByteArray(bytes, 0, bytes.size)) { guide.name }
            assertEquals(guide.name, 640, movie.width())
            assertEquals(guide.name, 640, movie.height())
            val ends = requireNotNull(gifFrameEnds(bytes))
            assertEquals(guide.name, ends.last(), movie.duration())
            val hashes = ends.indices.map { frame ->
                val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                movie.setTime(frame * 900 + 450)
                movie.draw(Canvas(bitmap), 0f, 0f)
                val pixels = IntArray(640 * 640)
                bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
                bitmap.recycle()
                pixels.contentHashCode()
            }
            assertEquals("Frozen GIF: ${guide.name}", ends.size, hashes.toSet().size)
            results += "${guide.name}\t${ends.size}\t${movie.duration()}\t${hashes.joinToString()}"
        }
        assertEquals(72, results.size)
        File(output, "decoded-72.tsv").writeText(results.joinToString("\n"))
    }

    @Test fun allSeventyTwoSheetsShowCopyCautionsAndMuscleSources() {
        var current by mutableStateOf(ExerciseGuides.all.first())
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                key(current.name) { ExerciseGuideSheet(current, onClose = {}) }
            }
        }
        val results = mutableListOf<String>()
        val samples = setOf("기본 스쿼트", "원암 덤벨 로우", "I-Y-T 레이즈", "플랭크", "바이시클 크런치", "러시안 트위스트")
        ExerciseGuides.all.forEach { guide ->
            compose.runOnIdle { current = guide }
            compose.waitUntil(15_000) {
                visualBounds("${guide.name} 동작 예시") != null
            }
            assertFalse(guide.name, visualBounds("${guide.name} 동작 예시")!!.isEmpty)
            compose.onNodeWithText("시범을 불러오지 못했어요.").assertDoesNotExist()
            if (guide.name in samples) capture(guide.asset.substringAfterLast('/').substringBefore('.') + "-guide")
            compose.onNodeWithText(guide.setup.first()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(guide.steps.last()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(guide.breathing).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(guide.visualNote).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("주의사항").performScrollTo().performClick()
            compose.onNodeWithText(guide.cautions.last()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("근육·근거").performScrollTo().performClick()
            compose.onNodeWithText("근육의 역할").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("지지·안정근").performScrollTo().assertIsDisplayed()
            val source = "${guide.sources.size}. ${guide.sources.last().label}"
            compose.onNodeWithText(source).performScrollTo().assertIsDisplayed()
            if (guide.name in samples) capture(guide.asset.substringAfterLast('/').substringBefore('.') + "-sources")
            compose.onNodeWithText("닫기").assertIsDisplayed()
            results += guide.name
        }
        assertEquals(72, results.size)
        File(output, "sheets-72.txt").writeText(results.joinToString("\n"))
    }

    @Test fun actualSheetAnimationChangesOnScreen() {
        val guide = ExerciseGuides.forName("I-Y-T 레이즈")!!
        compose.setContent { TrexAppTheme(ThemeMode.Dark) { ExerciseGuideSheet(guide, onClose = {}) } }
        compose.waitUntil(15_000) {
            visualBounds("${guide.name} 동작 예시") != null
        }
        val hashes = mutableSetOf<Int>()
        repeat(7) { frame ->
            val bounds = requireNotNull(visualBounds("${guide.name} 동작 예시"))
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val crop = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            val pixels = IntArray(crop.width * crop.height)
            crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
            hashes += pixels.contentHashCode()
            File(output, "iyt-playback-$frame.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            crop.recycle()
            screenshot.recycle()
            Thread.sleep(500)
        }
        assertTrue("Displayed GIF did not animate: $hashes", hashes.size >= 3)
        File(output, "playback.txt").writeText("I-Y-T: ${hashes.size} distinct on-screen frames")
    }

    // GIF is a native AndroidView, so use the accessibility tree, not Compose-only semantics.
    private fun visualBounds(description: String): Rect? {
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            if (node.isVisibleToUser && node.contentDescription?.toString() == description) {
                return Rect().also(node::getBoundsInScreen)
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun capture(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
