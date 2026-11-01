package com.example.trex_kotlin

import androidx.compose.ui.graphics.Color
import com.example.trex_kotlin.posture.toDinoCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 로그인 버튼(SignInUi.kt) — 색은 각 회사 지침 그대로, 문구는 두 버튼이 같은 꼴이고 공룡 말투로 바뀌지 않는다. */
class SignInUiTest {

    @Test
    fun apple_은_밝은_배경에_검정_어두운_배경에_흰_버튼() {
        assertEquals(SignInButtonColors(Color.Black, Color.White, null), signInButtonColors(SignInBrand.Apple, dark = false))
        assertEquals(SignInButtonColors(Color.White, Color.Black, null), signInButtonColors(SignInBrand.Apple, dark = true))
    }

    @Test
    fun google_은_지침의_light_dark_색() {
        assertEquals(SignInButtonColors(Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF747775)), signInButtonColors(SignInBrand.Google, dark = false))
        assertEquals(SignInButtonColors(Color(0xFF131314), Color(0xFFE3E3E3), Color(0xFF8E918F)), signInButtonColors(SignInBrand.Google, dark = true))
        assertNull(signInButtonColors(SignInBrand.Apple, dark = false).border)
    }

    @Test
    fun 두_버튼_문구는_같은_꼴이고_공룡_말투로_바뀌지_않는다() {
        assertEquals("Apple로 계속하기", signInButtonTitle(SignInBrand.Apple))
        assertEquals("Google로 계속하기", signInButtonTitle(SignInBrand.Google))
        SignInBrand.entries.forEach { brand ->
            val title = signInButtonTitle(brand)
            assertEquals(brand.name + "로 계속하기", title)
            assertEquals(title, title.toDinoCopy())
        }
    }
}
