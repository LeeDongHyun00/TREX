package com.example.trex_kotlin

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // 계측 테스트는 -PpostureReplay 의 분리된 앱(.replay)에서 돈다 — 접미사를 떼고 본다
        assertEquals("com.example.trex_kotlin", appContext.packageName.removeSuffix(".replay"))
    }
}