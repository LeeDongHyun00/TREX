package com.example.trex_kotlin.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.AuthScreen
import com.example.trex_kotlin.DietRecordScreen
import com.example.trex_kotlin.DietTabScreen
import com.example.trex_kotlin.FoodEntry
import com.example.trex_kotlin.GuideBookScreen
import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.MuscleLoadDatabase
import com.example.trex_kotlin.MuscleLoadScreen
import com.example.trex_kotlin.Nutrition
import com.example.trex_kotlin.OnboardingScreen
import com.example.trex_kotlin.ProfileTabScreen
import com.example.trex_kotlin.RecordScreen
import com.example.trex_kotlin.ThemeMode
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexApp
import com.example.trex_kotlin.TrexAppTheme
import com.example.trex_kotlin.UserProfile
import com.example.trex_kotlin.WorkoutTabScreen
import com.example.trex_kotlin.posture.SetSelfLabel
import com.example.trex_kotlin.trainingload.MuscleLoadCatalog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSFileManager
import platform.UIKit.UIViewController

/**
 * 개발용 실기기 화면 둘러보기(실행 인자 --screen-tour, §108 Phase 2): 공유 화면을 하나씩 그려 Documents/screen-tour/ 에 PNG 로 남긴다.
 * 표본 기록은 `tour.` 이름공간에만 쓴다(사용자 기록은 건드리지 않는다). WebView 로 그리는 것(가이드북 SVG·3D 근육도)은
 * Compose 층이 아니라 PNG 에서 빈칸이다 — 그 부분은 기기 화면으로 본다.
 */
fun ScreenTourViewController(): UIViewController = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
    val app = remember { tourApp() }
    // (이름, 앱 셸 없이 그릴지, 화면)
    val screens = remember {
        listOf<Triple<String, Boolean, @Composable () -> Unit>>(
            Triple("01-home", false) { TrexApp(app) },
            Triple("02-workout", true) { WorkoutTabScreen(app, onOpenAlt = {}, onOpenSets = {}, onAddWorkout = {}) },
            Triple("03-diet", true) { DietTabScreen(app, onOpenGoals = {}, onOpenPhoto = {}, onOpenManual = {}, onOpenRecord = {}) },
            Triple("04-settings", true) { ProfileTabScreen(app, onOpenRecord = {}, onLogout = {}) },
            Triple("05-record", true) { RecordScreen(app, onBack = {}) },
            Triple("06-diet-record", true) { DietRecordScreen(app, onBack = {}) },
            Triple("07-muscle-load", true) { MuscleLoadScreen(app, onBack = {}) },
            Triple("08-guidebook", true) { GuideBookScreen(onDone = {}) },
            Triple("09-auth", true) { AuthScreen(busy = false, message = null, onPasswordLogin = { _, _ -> }, onSignup = {}, onGoogle = {}, onWithoutAccount = {}) },
            Triple("10-onboarding", true) { OnboardingScreen(onDone = {}) },
            Triple("11-home-dark", false) { TrexApp(app) },
        )
    }
    var index by remember { mutableIntStateOf(0) }
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }) {
        val (_, wrap, screen) = screens[index]
        key(index) {
            if (wrap) TrexAppTheme(app.themeMode) {
                Box(Modifier.fillMaxSize().background(Trex.c.bg).safeDrawingPadding()) { screen() }
            } else screen()
        }
    }
    LaunchedEffect(Unit) {
        val dir = documentsPath() + "/screen-tour"
        for (i in screens.indices) {
            if (screens[i].first.endsWith("-dark")) app.setTheme(ThemeMode.Dark)
            index = i
            delay(2_500)
            val png = Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: continue
            writeBytesFile("$dir/${screens[i].first}.png", png)
        }
        app.setTheme(ThemeMode.Light)
        writeTextFile("$dir/done.txt", "ok\n")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun tourApp(): AppViewModel {
    listOf("trex_store", "trex_auth", "trex_sync", "exercise_guides").forEach { UserDefaultsStore("tour.$it").edit().clear().commit() }
    val root = documentsPath() + "/screen-tour-data"
    NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    val app = AppViewModel(object : IosAppPlatform() {
        override fun preferences(name: String): KeyValueStore = UserDefaultsStore("tour.$name")
        override fun muscleLoadDatabase(): MuscleLoadDatabase = FileMuscleLoadDatabase("$root/muscle_load.json")
        override fun appendSetLabel(label: SetSelfLabel) {}
    })
    app.setTheme(ThemeMode.Light)
    app.completeGuide()
    app.continueWithoutAccount()
    app.completeOnboarding(UserProfile(goal = "muscle", dayMask = 0b0010101, heightCm = 172.0, weightKg = 68.0, age = 29))
    app.appendFoods(0, "lunch", listOf(
        FoodEntry("현미밥", Nutrition(310, 65.0, 6.0, 2.5)),
        FoodEntry("닭가슴살", Nutrition(165, 0.0, 31.0, 3.6)),
    ))
    app.addWater(0)
    val workout = app.workoutPlan.first { MuscleLoadCatalog.find(it.name) != null }
    app.recordCompletedSession(420, completedPlan = listOf(workout.copy(done = true)), elapsedByWorkout = mapOf(workout.id to 420))
    return app
}
