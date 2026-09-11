package com.example.trex_kotlin

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 루트 화면·설정 저장·타이머 전환을 검사한다. 사용자 앱에는 테스트 계획을 쓰지 않는다. */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString()==text || it.contentDescription?.toString()==text }
    private fun await(text: String, timeout: Long = 12000) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { if(find(text)!=null)return; Thread.sleep(100) }
        error("화면에 '$text' 없음: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        await(text)
        var node=find(text)
        while(node!=null) {
            if(node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node=node.parent
        }
        error("클릭 불가: $text")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(500) // 접근성 트리가 갱신된 뒤 실제 화면 그리기까지 기다린다.
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(context.getExternalFilesDir(null),"$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    @Test fun configuredSetsRestPauseSkipAndAutoAdvanceUseRealApp() {
        check(context.packageName.endsWith(".replay")) { "분리된 재생 앱에서만 실행" }
        val prefs=context.getSharedPreferences("trex_store",Context.MODE_PRIVATE)
        val before=prefs.all.toMap()
        try {
            val store=TrexStore(context)
            store.guideDone=true;store.loggedIn=true;store.onboarded=true
            store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
            val plan=listOf(Workout("flow-a","기본 스쿼트","2회 × 2세트","99분",false,"하체",secondsPerRep=3,restSeconds=4),
                Workout("flow-b","플랭크","12초 × 1세트","99분",true,"코어"))
            store.savePlan(plan)
            assertEquals(3,TrexStore(context).loadPlan()!!.first().secondsPerRep)
            assertEquals(4,TrexStore(context).loadPlan()!!.first().restSeconds)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("운동");click("운동 시작")
                await("운동 준비")
                // 5초 준비도 자동으로 운동 화면으로 진행한다.
                await("세트 완료")
                click("일시정지")
                capture("session-timer")
                val clock=nodes().mapNotNull { it.text?.toString() }.first { Regex("\\d+:\\d{2}").matches(it) }
                Thread.sleep(1200)
                assertNotNull(find(clock))
                click("일시정지")
                await("세트 사이 휴식")
                assertNotNull(find("2 / 2 세트 · 2회"))
                await("세트 완료") // 휴식도 0에서 자동 진행
                click("건너뛰기")
                await("촬영 위치 · 몸 옆 · 낮게")
                capture("session-prepare")
                click("준비 건너뛰고 시작")
                // 권한은 기기 실행 명령에서 grant하며 일반 앱의 데이터는 건드리지 않는다.
                await("측정 상세 · 촬영 안내",20000)
                capture("session-posture")
                assertFalse(nodes().any { it.text?.contains("REC ")==true })
                click("건너뛰기")
                await("완료 세트")
                assertEquals(1,TrexStore(context).loadHistory()!!.last().items.size)
                assertTrue(TrexStore(context).loadPlan()!!.none { it.done })
            }
        } finally {
            val edit=prefs.edit().clear()
            before.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value); is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value); is Long -> edit.putLong(key,value); is Float -> edit.putFloat(key,value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>) }
            } }
            edit.commit()
        }
    }
}
