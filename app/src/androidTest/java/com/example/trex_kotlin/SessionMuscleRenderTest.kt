package com.example.trex_kotlin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PC 에뮬레이터의 분리 앱에서 완료 레이아웃과 실제 WebView 합성을 검사한다. */
class SessionMuscleRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun completedSessionDisplaysColoredBodyInsideScrollablePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".replay") && (Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic")))
        val workout = Workout("render-test", "기본 스쿼트", "12회 × 1세트", "1분", false, "하체", done = true)
        lateinit var app: AppViewModel
        instrumentation.runOnMainSync {
            app = AppViewModel(context.applicationContext as Application)
            app.recordCompletedSession(30, listOf(workout), mapOf(workout.id to 30), "render-session")
        }
        compose.setContent {
            TrexAppTheme(ThemeMode.Dark) {
                SessionCompleteScreen(listOf(workout), 30, fatigueContent = { SessionMuscleMap(app, "render-session") }, onDone = {})
            }
        }
        var web: WebView? = null
        fun find(v: View): WebView? = if (v is WebView) v else (v as? ViewGroup)?.let { g ->
            (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) }
        }
        compose.waitUntil(20_000) {
            instrumentation.runOnMainSync { web = find(compose.activity.window.decorView) }
            web != null
        }
        fun js(script: String): String {
            val latch = CountDownLatch(1); var value = ""
            instrumentation.runOnMainSync { web!!.evaluateJavascript(script) { value = it; latch.countDown() } }
            check(latch.await(10, TimeUnit.SECONDS)); return value
        }
        val limit = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < limit && js("document.body.dataset.ready") != "\"true\"") Thread.sleep(100)
        val metrics = js("JSON.stringify({ready:document.body.dataset.ready,w:innerWidth,h:innerHeight,stageW:document.getElementById('stage').clientWidth,stageH:document.getElementById('stage').clientHeight,canvas:!!document.querySelector('canvas')})")
        File(context.getExternalFilesDir(null), "session-render-metrics.json").writeText(metrics)
        assertEquals("뷰어 초기화 실패: $metrics", "\"true\"", js("document.body.dataset.ready"))
        assertTrue("WebView 내부 높이 0: $metrics", js("document.getElementById('stage').clientHeight").toInt() >= 300)
        Thread.sleep(700)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        File(context.getExternalFilesDir(null), "session-render.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val position = IntArray(2); var width = 0; var height = 0
        instrumentation.runOnMainSync { web!!.getLocationOnScreen(position); width = web!!.width; height = web!!.height }
        var bodyPixels = 0; var greenPixels = 0
        for (y in (position[1] + 15).coerceAtLeast(0) until (position[1] + height - 15).coerceAtMost(bitmap.height)) {
            for (x in (position[0] + 15).coerceAtLeast(0) until (position[0] + width - 15).coerceAtMost(bitmap.width)) {
                val p = bitmap.getPixel(x, y); val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > 100 && g > 100 && b > 100) bodyPixels++
                if (g > 90 && g > r * 1.08 && g > b * 1.25) greenPixels++
            }
        }
        assertTrue("인체가 보이지 않음: $bodyPixels · $metrics", bodyPixels > 1000)
        assertTrue("사용 부위 강조가 보이지 않음: $greenPixels · $metrics", greenPixels > 300)
        val downAt = SystemClock.uptimeMillis()
        val dragY = (position[1] + height / 3).toFloat()
        for (i in 0..12) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                position[0] + width * (.8f - .6f * i / 12), dragY, 0)
            instrumentation.sendPointerSync(event); event.recycle(); Thread.sleep(25)
        }
        Thread.sleep(400)
        val after = instrumentation.uiAutomation.takeScreenshot()!!
        File(context.getExternalFilesDir(null), "session-render-drag.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val afterPosition = IntArray(2)
        instrumentation.runOnMainSync { web!!.getLocationOnScreen(afterPosition) }
        assertArrayEquals("모델 드래그가 페이지를 이동시킴", position, afterPosition)
        var changed = 0
        for (y in position[1].coerceAtLeast(0) until (position[1] + height).coerceAtMost(bitmap.height) step 3)
            for (x in position[0].coerceAtLeast(0) until (position[0] + width).coerceAtMost(bitmap.width) step 3)
                if (bitmap.getPixel(x, y) != after.getPixel(x, y)) changed++
        assertTrue("실제 WebView 드래그에서 회전하지 않음", changed > 300)
        after.recycle(); bitmap.recycle()
    }
}
