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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 로그인 화면·로그인 로딩 화면(SignInUi.kt, 스펙 §113) — 그림 하나와 같은 높이의 버튼 셋, 로딩 화면은 로딩바 하나로 뒤 버튼을 막는다. */
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
                Box(Modifier.requiredSize(390.dp, 844.dp)) { AuthScreen(false, null, {}, {}, appleAvailable = true) }
            }
        }
        compose.onNodeWithContentDescription("TREX 로고").assertIsDisplayed()
        listOf("Apple로 계속하기", "Google로 계속하기", "로그인 없이 사용").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsEqualTo(SignInButtonHeight)
        }
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
                Box(Modifier.requiredSize(390.dp, 844.dp)) { AuthScreen(true, null, {}, {}, appleAvailable = true) }
            }
        }
        listOf("Apple로 계속하기", "Google로 계속하기", "로그인 없이 사용").forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
    }

    @Test fun loadingScreenCoversAndBlocksTheButtonsBehind() {
        var taps = 0
        compose.setContent {
            TrexAppTheme(ThemeMode.Light) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    AuthScreen(false, null, onGoogle = { taps++ }, onWithoutAccount = { taps++ })
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
