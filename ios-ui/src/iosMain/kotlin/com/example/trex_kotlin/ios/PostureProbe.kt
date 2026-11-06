package com.example.trex_kotlin.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.trex_kotlin.IosCameraBridgeRegistry
import com.example.trex_kotlin.IosCameraCounters
import com.example.trex_kotlin.IosStillDetector
import com.example.trex_kotlin.PostureLiveSessionScreen
import com.example.trex_kotlin.ThemeMode
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexAppTheme
import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.WorkoutTarget
import com.example.trex_kotlin.posture.PoseLandmarks
import com.example.trex_kotlin.posture.SCREEN_UP
import com.example.trex_kotlin.posture.SpeechCoach
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Image
import platform.UIKit.UIViewController

/**
 * 개발용 실기기 확인(실행 인자 --posture-probe, §108c): ① 번들 그림 두 장을 iPhone MediaPipe → 공유 후처리(PoseLandmarks)로 돌려 관절·피처를 적고,
 * ② 공유 자세 화면(PostureLiveSessionScreen)을 준비 단계 6 s → 운동 단계 8 s 띄워 카메라 프레임·추론·검출 수를 세고 Compose 층을 PNG 로 남긴다.
 * 결과는 Documents/posture-probe/ (report.txt·*.png·done.txt). 음성은 꺼 둔다. 미리보기 영상(UIKit 층)은 PNG 에 검게 남는다.
 */
@OptIn(ExperimentalForeignApi::class)
fun PostureProbeViewController(): UIViewController = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
    val speech = remember { SpeechCoach(IosSpeechEngine()).also { it.muted = true } }
    val workout = remember { Workout(id = "probe-squat::set:1", name = "기본 스쿼트", reps = "10회", duration = "", posture = true, category = "하체",
        target = WorkoutTarget.Repetitions(10)) }
    var stage by remember { mutableIntStateOf(0) }   // 0 그림, 1 준비, 2 운동, 3 끝
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }) {
        if (stage in 1..2) TrexAppTheme(ThemeMode.Light) {
            Box(Modifier.fillMaxSize().background(Trex.c.bg)) {
                PostureLiveSessionScreen(workout, index = 0, total = 1, timeLeft = 60, totalSeconds = 60, paused = false,
                    onTogglePause = {}, onNext = {}, onExit = {}, speech = speech, preparing = stage == 1)
            }
        }
    }
    LaunchedEffect(Unit) {
        val dir = documentsPath() + "/posture-probe"
        platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(dir, error = null)   // 지난 결과(done.txt)를 지운다
        val report = StringBuilder()
        // ① 그림 — 카메라 없이 MediaPipe iOS + 공유 후처리
        val still = IosCameraBridgeRegistry.bridge as? IosStillDetector
        var ts = 1_000L
        for (file in listOf("exercise_guides/squat.gif", "exercise_guides/forearm-plank.png")) {
            val r = runCatching { still?.detectStill(file, ts) }.getOrNull()
            ts += 1_000L
            val pose = r?.pose
            if (r == null || pose == null) { report.append("[still] $file detected=false\n"); continue }
            val s = PoseLandmarks.sample(pose.xy, pose.visibility, pose.world, r.width, r.height, 0L, SCREEN_UP)
            val keys = s.features.keys.sorted()
            report.append("[still] $file ${r.width}x${r.height} detected=true visible=${s.visibleJointCount} features=${keys.size} upVerified=${s.upVerified} upFlipped=${s.upFlipped}\n")
            for (k in keys.filter { it.startsWith("knee") || it.startsWith("hip") || it.startsWith("torso") }.take(12)) {
                report.append("  $k=${s.features[k]}\n")
            }
        }
        // ③ 소리 — 소리 없이 경로만: 확인음 합성·재생(볼륨 0, 첫 회에서야 처음 도는 경로), 한국어 음성 준비
        IosRepTone(0).startTone(com.example.trex_kotlin.RepTone.TONE_PROP_ACK, 60)
        IosRepTone(0).startTone(com.example.trex_kotlin.RepTone.TONE_PROP_BEEP2, 70)
        IosRepTone(0).startTone(com.example.trex_kotlin.RepTone.TONE_PROP_NACK, 70)
        delay(400)
        report.append("[tone] played 3 (silent)\n")
        report.append("[speech] ready=${speech.ready} unavailable=${speech.unavailableReason}\n")
        // ② 공유 자세 화면 — 준비 단계, 운동 단계
        IosCameraCounters.reset()
        stage = 1
        delay(6_000)
        capture(layer, "$dir/01-preparing.png")
        report.append("[prep] ${IosCameraCounters.line()}\n")
        IosCameraCounters.reset()
        stage = 2
        delay(8_000)
        capture(layer, "$dir/02-working.png")
        report.append("[work] ${IosCameraCounters.line()}\n")
        report.append("[model] error=${IosCameraBridgeRegistry.bridge?.modelError}\n")
        stage = 3
        delay(500)
        writeTextFile("$dir/report.txt", report.toString())
        writeTextFile("$dir/done.txt", "ok\n")
    }
}

private suspend fun capture(layer: androidx.compose.ui.graphics.layer.GraphicsLayer, path: String) {
    val png = Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: return
    writeBytesFile(path, png)
}
