package com.example.trex_kotlin.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.FoodPhoto
import com.example.trex_kotlin.FoodRecognizer
import com.example.trex_kotlin.IosFoodBridgeRegistry
import com.example.trex_kotlin.IosFoodImage
import com.example.trex_kotlin.IosFoodPhoto
import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.MuscleLoadDatabase
import com.example.trex_kotlin.PhotoFoodSheet
import com.example.trex_kotlin.posture.SetSelfLabel
import com.example.trex_kotlin.ThemeMode
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexAppTheme
import com.example.trex_kotlin.UserProfile
import com.example.trex_kotlin.elapsedRealtimeMs
import com.example.trex_kotlin.food.FoodDetectionResult
import com.example.trex_kotlin.food.PixelBox
import com.example.trex_kotlin.posture.SpeechEngine
import com.example.trex_kotlin.weather.KoreanRegion
import com.example.trex_kotlin.FoodEntry
import com.example.trex_kotlin.Nutrition
import com.example.trex_kotlin.TrexApp
import com.example.trex_kotlin.iosFoodPipeline
import com.example.trex_kotlin.rememberFoodRecognizer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSFileManager
import platform.UIKit.UIViewController
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 개발용 실기기 확인(실행 인자 --food-probe, §108d): Swift 가 그린 합성 접시 그림 한 장(1600×1200 PNG → 축소 디코딩)으로 공유 사진 식단 경로를 돈다 —
 * ① 세 모델 불러오기·입출력 모양, ② 판정(`FoodPipeline` — 위치 → 자리 이름 → 이름 없는 사진의 전체 1회), ③ 특징값, ④ 사진 그림·축소·자르기와
 * 픽셀 확인(왼쪽 위 빨강·오른쪽 아래 파랑 표지 — 채널 순서·위아래), ⑤ 공유 사진 시트 첫 화면 PNG, ⑥ 결과 화면과 같은 그리기 경로(사진 축소본을
 * Compose Image 로 — 10-23 촬영 직후 종료가 여기서 났다) PNG, ⑦ 세션 스피커가 고르는 한국어 음성과 이 기기의 한국어 음성 목록,
 * ⑧ 세션 스피커 사건(소리 없이) — 시작·끝·끊김 사건이 낸 id 로 돌아오는지(10-23 교정 문장이 세트 내내 보류되던 결함),
 * ⑨ 홈 화면 전체(§108f, 내비 포함) — 서울 날씨를 실제로 받아(NSURLSession·공통 org.json) 날짜 아래 날씨 한 줄과 함께 그린 PNG(home.png).
 * 합성 그림이라 인식 이름은 의미가 없다 — 이 기기에서 모델이 실제로 돌고 모양·시간이 맞는지만 본다. 내 음식 기억은 쓰지 않는다.
 * 결과는 Documents/food-probe/ (report.txt·sheet.png·photo.png·done.txt).
 */
@OptIn(ExperimentalForeignApi::class)
fun FoodProbeViewController(image: IosFoodImage?): UIViewController = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
    val app = remember { probeApp() }
    val recognizer = rememberFoodRecognizer()
    var showSheet by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var showWeather by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }) {
        if (showSheet) TrexAppTheme(ThemeMode.Light) {
            Box(Modifier.fillMaxSize().background(Trex.c.bg).safeDrawingPadding()) { PhotoFoodSheet(app, onClose = {}) }
        }
        shown?.let { androidx.compose.foundation.Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (showWeather) TrexApp(app)
    }
    LaunchedEffect(Unit) {
        val dir = documentsPath() + "/food-probe"
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)   // 지난 결과(done.txt)를 지운다
        val report = StringBuilder()
        withContext(Dispatchers.Default) {
            runCatching { runPipeline(image, recognizer, report) }.onFailure { report.append("[error] ${it::class.simpleName}: ${it.message}\n") }
        }
        showSheet = true
        delay(2_500)
        Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes?.let { writeBytesFile("$dir/sheet.png", it) }
        // 결과 화면과 같은 그리기 경로 — 사진 축소본(PREVIEW 1024)을 Compose Image 로 그린다
        showSheet = false
        shown = image?.let { IosFoodPhoto(it).scaledToMax(1024).image }
        delay(1_500)
        Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes?.let { writeBytesFile("$dir/photo.png", it) }
        report.append("[draw] photo ${shown?.width}x${shown?.height} drawn\n")
        val chosen = bestKoreanVoice()
        report.append("[voice] chosen=${chosen?.name}/${chosen?.let(::voiceQualityLabel)} rate=$SPEECH_RATE installed=${koreanVoices().joinToString { "${it.name}/${voiceQualityLabel(it)}" }}\n")
        // 두 문장을 잇달아 → 하나가 끝나기 전에 끊고 들어오는 문장. 기대: a 시작·끝, b 시작·끝, c 시작·끊김, d 시작·끝
        val engine = IosSpeechEngine().also { it.silent = true }
        val seen = ArrayList<String>()
        engine.start(object : SpeechEngine.Events {
            override fun onInit(ready: Boolean, error: String?, unavailable: String?) { seen += "init:$ready" }
            override fun onStart(id: String?) { seen += "start:$id" }
            override fun onFinished(id: String?, kind: String, detail: String) { seen += "$kind:$id" }
        })
        engine.speak("하나", flush = false, id = "a")
        engine.speak("둘", flush = false, id = "b")
        delay(3_000)
        engine.speak("셋 넷 다섯 여섯 일곱 여덟", flush = false, id = "c")
        delay(400)
        engine.speak("끝", flush = true, id = "d")
        delay(3_000)
        engine.shutdown()
        engine.abandonFocus()
        report.append("[speech-events] ${seen.joinToString()}\n")
        // 홈 화면 전체(내비 포함) — 서울(저장 지역 기본값) 날씨를 받아 날짜 아래 한 줄로
        shown = null
        showSheet = false
        showWeather = true
        delay(6_000)
        Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes?.let { writeBytesFile("$dir/home.png", it) }
        val seoul = app.weather.repository.peekCachedWeather(KoreanRegion.SEOUL.latitude, KoreanRegion.SEOUL.longitude)
        report.append("[weather] seoul=${seoul?.let { "${it.temperature}°C code=${it.weatherCode} day=${it.isDay} rain=${it.precipitation}mm" } ?: "none"}\n")
        writeTextFile("$dir/report.txt", report.toString())
        writeTextFile("$dir/done.txt", "ok\n")
    }
}

private fun runPipeline(image: IosFoodImage?, recognizer: FoodRecognizer, report: StringBuilder) {
    val bridge = IosFoodBridgeRegistry.models
    report.append("[bridge] models=${bridge != null} photos=${IosFoodBridgeRegistry.photos != null} image=${image != null}\n")
    if (bridge == null || image == null) return
    for (name in listOf("yolov8n_food", "food_region", "food_embed")) {
        val t = elapsedRealtimeMs()
        val failure = bridge.prepare(name)
        report.append("[model] $name ${failure ?: "ok"} ${elapsedRealtimeMs() - t}ms in=${bridge.inputShape(name).toList()} out=${bridge.outputShape(name).toList()}\n")
    }
    var t = elapsedRealtimeMs()
    recognizer.warmUp()
    report.append("[warmUp] ${elapsedRealtimeMs() - t}ms\n")

    val photo = IosFoodPhoto(image)
    report.append("[photo] ${photo.width}x${photo.height}\n")
    t = elapsedRealtimeMs()
    when (val r = recognizer.detect(listOf(photo))) {
        is FoodDetectionResult.Success -> {
            report.append("[detect] success ${elapsedRealtimeMs() - t}ms foods=${r.foods.size} candidates=${r.candidates.size} regions=${r.regions.size}\n")
            r.foods.forEach { report.append("  food ${it.name} ${two(it.confidence)}\n") }
            r.candidates.take(5).forEach { report.append("  candidate ${it.name} ${two(it.confidence)}\n") }
            r.regions.forEach { g ->
                val b = g.box
                report.append("  region #${g.id} box=(${two(b.left)}, ${two(b.top)}, ${two(b.right)}, ${two(b.bottom)}) score=${two(g.regionScore)} " +
                    "top=${g.top.joinToString { "${it.first} ${two(it.second)}" }} empty=${g.looksEmpty}\n")
            }
        }
        else -> report.append("[detect] $r ${elapsedRealtimeMs() - t}ms\n")
    }
    t = elapsedRealtimeMs()
    val v = iosFoodPipeline()?.embed(photo, PixelBox(0.25f, 0.25f, 0.75f, 0.75f))
    report.append("[embed] size=${v?.size} norm=${v?.let { two(sqrt(it.sumOf { x -> (x * x).toDouble() }).toFloat()) }} ${elapsedRealtimeMs() - t}ms\n")

    // 화면 그림·축소·자르기 — 표지 색으로 채널 순서(RGBA)와 위아래·자르기 좌표(위 기준)를 본다
    val small = photo.scaledToMax(480)
    val topLeft = centerColor(photo.crop(0, 0, 16, 16))
    val bottomRight = centerColor(photo.crop(photo.width - 16, photo.height - 16, 16, 16))
    val ok = topLeft.red > 0.9f && topLeft.blue < 0.1f && bottomRight.blue > 0.9f && bottomRight.red < 0.1f
    report.append("[bitmap] image=${photo.image.width}x${photo.image.height} scaled=${small.width}x${small.height}/${small.image.width}x${small.image.height} " +
        "topLeft=${rgb(topLeft)} bottomRight=${rgb(bottomRight)} markers=${if (ok) "ok" else "WRONG"}\n")
}

private fun centerColor(photo: FoodPhoto): Color = photo.image.toPixelMap()[photo.width / 2, photo.height / 2]

private fun rgb(c: Color) = "(${(c.red * 255).roundToInt()}, ${(c.green * 255).roundToInt()}, ${(c.blue * 255).roundToInt()})"

private fun two(v: Float) = ((v * 100).roundToInt() / 100f).toString()

@OptIn(ExperimentalForeignApi::class)
private fun probeApp(): AppViewModel {
    listOf("trex_store", "trex_auth", "trex_sync", "exercise_guides").forEach { UserDefaultsStore("food-probe.$it").edit().clear().commit() }
    val root = documentsPath() + "/food-probe-data"
    NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    val app = AppViewModel(object : IosAppPlatform() {
        override fun preferences(name: String): KeyValueStore = UserDefaultsStore("food-probe.$name")
        override fun muscleLoadDatabase(): MuscleLoadDatabase = FileMuscleLoadDatabase("$root/muscle_load.json")
        override fun appendSetLabel(label: SetSelfLabel) {}
    })
    app.setTheme(ThemeMode.Light)
    app.completeGuide()
    app.continueWithoutAccount()
    app.completeOnboarding(UserProfile(goal = "muscle", dayMask = 0b0010101, heightCm = 172.0, weightKg = 68.0, age = 29))
    // 홈 섭취 요약이 빈 고리가 아니게 — 점심 두 가지
    app.appendFoods(0, "lunch", listOf(FoodEntry("현미밥", Nutrition(310, 65.0, 6.0, 2.5)), FoodEntry("닭가슴살", Nutrition(165, 0.0, 31.0, 3.6))))
    return app
}
