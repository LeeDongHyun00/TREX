package com.example.trex_kotlin

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * 로그인 화면·로그인 로딩 화면(SignInUi.kt, 스펙 §113·§117) — 그림 하나와 같은 높이의 버튼 셋(아이디 → Apple → Google)과 작은 "로그인 없이 사용".
 * "아이디로 로그인"은 칸을 펼치고 아이디에 커서를 두며, 펼친 높이만큼 공룡이 작아져 스크롤 없이 한 화면에 든다. 로딩 화면은 로딩바 하나로 뒤 버튼을 막는다.
 */
class SignInScreensUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun snapshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.getExternalFilesDir("sign_in_qa"), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun loginShowsOnlyDinoAndThreeEqualButtonsInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            TrexAppTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { AuthScreen(false, null, { _, _ -> }, {}, {}, {}, appleAvailable = true) }
            }
        }
        compose.onNodeWithContentDescription("TREX 로고").assertIsDisplayed()
        // 아이디가 맨 위, Apple·Google 이 같은 크기로 그 아래 — "로그인 없이 사용"은 작은 글자 버튼
        val tops = listOf("아이디로 로그인", "Apple로 계속하기", "Google로 계속하기").map {
            compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsEqualTo(SignInButtonHeight).getUnclippedBoundsInRoot().top
        }
        assertTrue("버튼 순서 $tops", tops.zipWithNext().all { (a, b) -> a < b })
        val without = compose.onNodeWithText("로그인 없이 사용").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("작은 버튼 ${without.height}", without.height < SignInButtonHeight && without.top > tops.last())
        compose.onNodeWithText("TREX").assertDoesNotExist()
        compose.onNodeWithText("움직임을 보고 함께 운동해룡").assertDoesNotExist()
        snapshot("login-light")
        compose.runOnIdle { dark = true }
        compose.onNodeWithText("Apple로 계속하기").assertIsDisplayed()
        snapshot("login-dark")
    }

    @Test fun busyLoginDisablesButtons() {
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { AuthScreen(true, null, { _, _ -> }, {}, {}, {}, appleAvailable = true) }
            }
        }
        listOf("아이디로 로그인", "Apple로 계속하기", "Google로 계속하기", "로그인 없이 사용").forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
    }

    @Test fun idLoginOpensFieldsFocusesIdAndShrinksDinoWithoutScrolling() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    AuthScreen(false, null, onPasswordLogin = { id, pw -> submitted = id to pw }, onSignup = {}, onGoogle = {},
                        onWithoutAccount = {}, appleAvailable = true)
                }
            }
        }
        val folded = compose.onNodeWithContentDescription("TREX 로고").getUnclippedBoundsInRoot().height
        compose.onNodeWithText("아이디로 로그인").performClick()
        compose.waitForIdle()
        // 칸이 펼쳐지고 아이디 칸에 커서, 버튼은 "로그인"(칸을 채우기 전에는 누를 수 없다)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].assertIsFocused()
        compose.onNodeWithText("로그인").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("회원가입").assertIsDisplayed()
        // 펼친 높이만큼 공룡이 작아지고 아래 버튼까지 한 화면(844dp) 안에 든다 — 스크롤 없음
        val opened = compose.onNodeWithContentDescription("TREX 로고").getUnclippedBoundsInRoot().height
        assertTrue("공룡 $folded → $opened", opened < folded)
        val bottom = compose.onNodeWithText("로그인 없이 사용").getUnclippedBoundsInRoot().bottom
        assertTrue("화면 밖 $bottom", bottom <= 844.dp)
        snapshot("login-id-open")
        fields[0].performTextInput("trex_user")
        fields[1].performTextInput("abc12345")
        compose.onNodeWithText("로그인").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("trex_user" to "abc12345", submitted) }
        // 접으면 공룡이 원래 크기로
        compose.onNodeWithText("접기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("아이디로 로그인").assertIsDisplayed()
        assertEquals(folded, compose.onNodeWithContentDescription("TREX 로고").getUnclippedBoundsInRoot().height)
        assertEquals(0, compose.onAllNodesWithText("회원가입").fetchSemanticsNodes().size)
    }

    @Test fun loadingScreenCoversAndBlocksTheButtonsBehind() {
        var taps = 0
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    AuthScreen(false, null, onPasswordLogin = { _, _ -> taps++ }, onSignup = {}, onGoogle = { taps++ }, onWithoutAccount = { taps++ })
                    SignInLoadingScreen()
                }
            }
        }
        // 공룡 애니메이션 없이 로딩바 하나(2026-10-30) — 화면 읽기에는 "로그인하는 중"
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithContentDescription("로그인하는 중").assertIsDisplayed()
        compose.onNodeWithText("로그인 없이 사용").performClick()
        compose.runOnIdle { assertEquals(0, taps) }
        snapshot("sign-in-loading")
    }
}
