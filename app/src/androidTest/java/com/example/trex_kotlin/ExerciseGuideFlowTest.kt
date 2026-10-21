package com.example.trex_kotlin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.posture.toDinoCopy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 별도 replay 앱에서 실제 루트·준비·타이머를 확인한다. 일반 앱의 기록은 건드리지 않는다. */
@RunWith(AndroidJUnit4::class)
class ExerciseGuideFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.refresh()) return
            add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String) = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString() in setOf(text, text.toDinoCopy()) || it.contentDescription?.toString() == text)
    }
    private fun await(text: String) {
        val until = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < until) {
            if (find(text) != null) return
            Thread.sleep(100)
        }
        capture("failure")
        error("찾지 못한 화면: $text / " + nodes().joinToString { "${it.text} [${it.contentDescription}]" })
    }
    private fun click(text: String) {
        await(text)
        val until = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < until) {
            var node = find(text)
            while (node != null) {
                if (node.isEnabled && node.isClickable) {
                    // 카메라 재구성 중 Compose 접근성 클릭이 false를 반환하면 실제 터치를 보낸다.
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (clicked || (!bounds.isEmpty && tap(bounds))) {
                        instrumentation.waitForIdleSync()
                        Thread.sleep(750)
                        return
                    }
                }
                node = node.parent
            }
            Thread.sleep(100)
        }
        capture("failure-click")
        error("클릭할 수 없음: $text / " + nodes().joinToString { "${it.text} [${it.contentDescription}] enabled=${it.isEnabled} clickable=${it.isClickable}" })
    }
    private fun tap(bounds: Rect): Boolean {
        val now = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long) = MotionEvent.obtain(now, time, action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val down = event(MotionEvent.ACTION_DOWN, now)
        val up = event(MotionEvent.ACTION_UP, now + 60)
        return try {
            val pressed = instrumentation.uiAutomation.injectInputEvent(down, true)
            instrumentation.uiAutomation.injectInputEvent(up, true) && pressed
        } finally { down.recycle(); up.recycle() }
    }
    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir("exercise-guide-qa"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun isolated(posture: Boolean, block: () -> Unit) {
        check(context.packageName.endsWith(".replay"))
        val stores = listOf("trex_store", "exercise_guides").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val backups = stores.map { it.all.toMap() }
        try {
            stores[1].edit().clear().commit()
            TrexStore(context).apply {
                guideDone = true; loggedIn = true; onboarded = true
                planDoneEpochDay = java.time.LocalDate.now().toEpochDay()
                savePlan(listOf(Workout("guide-test", "덤벨 컬", "20초 × 2세트", "1분", posture, "상체",
                    target = WorkoutTarget.Duration(20), restSeconds = 30)))
            }
            ActivityScenario.launch(MainActivity::class.java).use { block() }
        } finally {
            stores.zip(backups).forEach { (store, values) ->
                val edit = store.edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
                } }
                edit.commit()
            }
        }
    }

    @Test fun firstGuideAndReopenedGuideHoldPreparationAndWorkout() = isolated(false) {
        ExerciseGuides.all.forEach { guide ->
            if (guide.asset.endsWith(".gif")) {
                @Suppress("DEPRECATION") val movie = context.assets.open(guide.asset).use(Movie::decodeStream)
                assertNotNull(guide.name, movie)
                assertTrue(movie!!.duration() > 0)
            } else {
                val bitmap = context.assets.open(guide.asset).use { BitmapFactory.decodeStream(it) }
                assertNotNull(guide.name, bitmap)
                assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            }
        }
        click("운동")
        await("덤벨 컬 운동 방법")
        capture("01-list")
        click("덤벨 컬 운동 방법")
        await("덤벨 컬 동작 예시")
        assertNull(find("시범 멈추기"))
        assertNull(find("시범 재생"))
        assertNull(find("코치의 코멘트"))
        capture("02-guide")
        click("닫기")
        await("덤벨 컬 수정")
        click("운동 시작")
        await("준비 계속하기")
        Thread.sleep(5500)
        await("준비 계속하기")
        click("준비 계속하기")
        await("바로 시작")
        click("바로 시작")
        await("시간 측정")
        click("덤벨 컬 운동 방법")
        Thread.sleep(5500)
        click("운동 화면으로 돌아가기")
        await("일시정지")
        val before = nodes().mapNotNull { it.text?.toString() }.first { it.matches(Regex("\\d+:\\d{2}")) }
        Thread.sleep(1500)
        assertNotNull(find(before))
        capture("03-paused")
        click("재개")
        await("시간 측정")
        Thread.sleep(1500)
        assertNull(find(before))
    }

    @Test fun cameraPreparationCanResumeAfterClosingGuide() = isolated(true) {
        click("운동")
        click("운동 시작")
        await("준비 계속하기")
        click("준비 계속하기")
        await("5초 후 시작")
        click("덤벨 컬 운동 방법")
        click("운동 화면으로 돌아가기")
        await("재개")
        capture("04-camera-preparation-paused")
        click("재개")
        await("일시정지")
        capture("04b-camera-preparation-resumed")
        // 이 TextButton은 카메라 화면에서 접근성 부모를 찾지 못하므로 표시된 글자 위치를 누른다.
        val manualStart = checkNotNull(find("5초 후 시작"))
        assertTrue(manualStart.isEnabled)
        assertTrue(tap(Rect().also(manualStart::getBoundsInScreen)))
        // 작은 화면에서는 카운트다운이 설명 스크롤 아래로 가려질 수 있다. 실제 운동 진입을 확인한다.
        await("덤벨 컬 · 1 / 2 세트")
        assertNull(find("준비 건너뛰기"))
        capture("05-camera-workout")
    }
}
