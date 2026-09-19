package com.example.trex_kotlin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.posture.RepMovementPattern
import com.example.trex_kotlin.posture.toDinoCopy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File

/** 실제 편집/저장/재진입 흐름. .replay 앱에서만 실행하고 기존 환경설정 전체를 복원한다. */
@RunWith(AndroidJUnit4::class)
class RepModeFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var lastClick: JSONObject? = null
    private var lastSelection: JSONObject? = null

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun matches(node: AccessibilityNodeInfo, text: String) =
        node.text?.toString() in setOf(text, text.toDinoCopy()) || node.contentDescription?.toString() == text

    private fun visible(text: String) = nodes().firstOrNull { it.isVisibleToUser && matches(it, text) }

    /** UI 선택 실패의 원인을 구분하기 위한 원시 접근성 상태. 선택 대기/재시도는 하지 않는다. */
    private fun nodeState(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject().put("text", node.text?.toString() ?: JSONObject.NULL)
            .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
            .put("class", node.className?.toString() ?: JSONObject.NULL)
            .put("view_id", node.viewIdResourceName ?: JSONObject.NULL)
            .put("window_id", node.windowId).put("selected", node.isSelected)
            .put("checkable", node.isCheckable).put("checked", node.isChecked)
            .put("clickable", node.isClickable).put("enabled", node.isEnabled)
            .put("visible", node.isVisibleToUser).put("focused", node.isFocused)
            .put("bounds", bounds.flattenToString())
            .put("actions", JSONArray(node.actionList.map { it.id }))
    }

    private fun captureState(name: String, immediateSelection: Boolean? = null) {
        val dir = requireNotNull(context.getExternalFilesDir(null))
        val state = JSONObject().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("immediate_selected_result", immediateSelection ?: JSONObject.NULL)
            .put("last_click", lastClick ?: JSONObject.NULL)
            .put("last_selection", lastSelection ?: JSONObject.NULL)
            .put("nodes", JSONArray(nodes().map(::nodeState)))
            // 시트 안의 변경은 저장 전 초안이다. 저장값과 초안 화면을 섞지 않도록 따로 기록한다.
            .put("persisted_plan", JSONArray(TrexStore(context).loadPlan().orEmpty().map {
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("rep_pattern", it.repMovementPattern?.name ?: JSONObject.NULL)
                    .put("resolved_pattern", it.resolvedRepPattern()?.name ?: JSONObject.NULL)
            }))
        File(dir, "rep-mode-$name.json").writeText(state.toString(2))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(dir, "rep-mode-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    private fun await(text: String) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) {
            if (visible(text) != null) return
            Thread.sleep(100)
        }
        error("화면에 '$text' 없음: " + nodes().joinToString(" | ") { "${it.text} [${it.contentDescription}]" })
    }

    private fun click(text: String) {
        await(text)
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) {
            for (candidate in nodes().filter { it.isVisibleToUser && !it.isEditable && matches(it, text) }) {
                var node: AccessibilityNodeInfo? = candidate
                while (node != null) {
                    val clickState = nodeState(node)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        lastClick = JSONObject().put("requested_text", text)
                            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                            .put("matched_candidate", nodeState(candidate)).put("accepted_action_node", clickState)
                        instrumentation.waitForIdleSync()
                        Thread.sleep(400)
                        return
                    }
                    node = node.parent
                }
            }
            Thread.sleep(100)
        }
        error("클릭 불가: $text")
    }

    private fun scrollTo(text: String) {
        repeat(12) {
            if (visible(text) != null) return
            nodes().filter { it.isVisibleToUser && it.isScrollable }
                .forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            Thread.sleep(150)
        }
        await(text)
    }

    /** parent는 캐시 노드일 수 있으므로 매 단계 refresh한다. 모르는 선택 상태를 false로 간주하지 않는다. */
    private fun selected(text: String): Boolean {
        await(text)
        val path = JSONArray()
        val trace = JSONObject().put("requested_text", text).put("ancestry", path)
        lastSelection = trace
        var node: AccessibilityNodeInfo? = visible(text)
        while (node != null) {
            check(node.refresh()) { "선택 상태 노드를 새로고침하지 못했습니다: $text · $trace" }
            path.put(nodeState(node))
            if (node.isCheckable) {
                trace.put("source", "checked").put("result", node.isChecked)
                return node.isChecked
            }
            if (node.isSelected) {
                trace.put("source", "selected").put("result", true)
                return true
            }
            check(!node.isClickable) { "클릭 노드에 확인 가능한 선택 상태가 없습니다: $text · $trace" }
            node = node.parent
        }
        error("선택 상태를 제공하는 노드가 없습니다: $text · $trace")
    }

    private fun isolated(block: (TrexStore) -> Unit) {
        check(context.packageName.endsWith(".replay")) { "사용자 앱에는 테스트 계획을 쓰지 않습니다." }
        val prefs = context.getSharedPreferences("trex_store", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        try {
            check(prefs.edit().clear().putBoolean("demo_cleanup_v2", true).commit())
            block(TrexStore(context))
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        edit.putStringSet(key, value as Set<String>)
                    }
                }
            }
            check(edit.commit()) { "테스트 이전 환경설정을 복원하지 못했습니다." }
        }
    }

    private fun seed(store: TrexStore, name: String) {
        store.guideDone = true
        store.loggedIn = true
        store.onboarded = true
        store.themeMode = ThemeMode.Light
        store.planDoneEpochDay = LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("rep-mode-flow", name, "12회 × 1세트", "1분", false,
            if (name == "덤벨 컬") "상체" else "하체")))
    }

    @Test fun alternatingCurlPersistsAndIsSelectedAfterActivityReentry() = isolated { store ->
        seed(store, "덤벨 컬")
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동")
            click("덤벨 컬 수정")
            await("운동 수정")
            scrollTo("양쪽 함께")
            assertTrue(selected("양쪽 함께"))
            captureState("curl-before")
            click("좌우 각각")
            val selectedAfterClick = selected("좌우 각각")
            captureState("curl-after", selectedAfterClick)
            assertTrue(selectedAfterClick)
            assertFalse(selected("양쪽 함께"))
            scrollTo("한쪽 왕복이 1회 · 목표는 좌우 합계")
            click("저장")
            assertEquals(RepMovementPattern.ALTERNATING_EACH,
                TrexStore(context).loadPlan()!!.single().repMovementPattern)
            assertEquals(12, TrexStore(context).loadPlan()!!.single().resolvedTarget().amount)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동")
            click("덤벨 컬 수정")
            scrollTo("좌우 각각")
            captureState("curl-reentry")
            assertTrue(selected("좌우 각각"))
            assertFalse(selected("양쪽 함께"))
            scrollTo("한쪽 왕복이 1회 · 목표는 좌우 합계")
            click("취소")
        }
    }

    @Test fun lungeExplainsUnattributedTotalAndUserSelectedSupportingLeg() = isolated { store ->
        seed(store, "바벨 런지")
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동")
            click("바벨 런지 수정")
            scrollTo("전체 횟수")
            assertTrue(selected("전체 횟수"))
            scrollTo("내려갔다 복귀하면 1회 · 좌우는 구분하지 않고 합계로 기록")
            captureState("lunge-before")
            click("왼쪽 기준")
            val selectedAfterClick = selected("왼쪽 기준")
            captureState("lunge-after", selectedAfterClick)
            assertTrue(selectedAfterClick)
            scrollTo("선택한 왼쪽 앞·지지 다리 기준 · 내려갔다 복귀하면 1회")
            click("저장")
            assertEquals(RepMovementPattern.LEFT_ONLY,
                TrexStore(context).loadPlan()!!.single().repMovementPattern)
            click("바벨 런지 수정")
            scrollTo("왼쪽 기준")
            captureState("lunge-reentry")
            assertTrue(selected("왼쪽 기준"))
            scrollTo("선택한 왼쪽 앞·지지 다리 기준 · 내려갔다 복귀하면 1회")
            click("취소")
        }
    }
}
