package com.example.trex_kotlin.posture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 잠금 화면과 독립인 짧은 실제 단말 추론 검사. 평가 앱의 engine_probe에 JPG 3장을 먼저 복사한다.
 * 저장 이미지 3장을 반복하므로 실제 사람의 반복 정확도·카메라 지연·장시간 발열 검증이 아니다.
 * MediaPipe GPU 생성·추론·해제를 같은 스레드에서 수행하고 이미지/영상은 결과 JSON에 복사하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class EngineDeviceProbeTest {
    @Test(timeout = 60_000)
    fun storedImagesRunRealEngineAndReportShortDeviceProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".replay")) { "평가 전용 패키지에서만 실행하세요" }
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "engine_probe").apply { mkdirs() }
        val started = SystemClock.elapsedRealtime()
        val result = JSONObject()
            .put("schema", "trex-engine-device-probe/1")
            .put("passed", false)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android_release", Build.VERSION.RELEASE).put("sdk_int", Build.VERSION.SDK_INT))
            .put("package", context.packageName)
            .put("model", PoseModel.FULL.label)
            .put("gpu_preferred", true)
            .put("thread_id", Thread.currentThread().id)
            .put("timestamp_source", "SystemClock.elapsedRealtime, actual inference start")
            .put("input_sequence", "three stored JPEG files in name order, cyclic reuse")
            .put("accuracy_claim", false)
            .put("camera_pipeline_measured", false)
            .put("camera_x_yuv_conversion_included", false)
            .put("decode_and_resize_in_inference_timing", false)
            .put("input_long_edge_limit_px", 640)
            .put("long_duration_thermal_claim", false)
            .put("before", snapshot(context))
        val bitmaps = ArrayList<Bitmap>()
        var analyzer: PostureAnalyzer? = null
        try {
            val files = dir.listFiles().orEmpty().filter {
                it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg")
            }.sortedBy { it.name }
            check(files.size == 3) { "engine_probe에 JPG/JPEG 파일이 정확히 3개 필요합니다: ${files.size}" }
            val inputs = JSONArray()
            for (file in files) {
                val loadStarted = SystemClock.elapsedRealtimeNanos()
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                    ?: error("이미지 디코딩 실패: ${file.name}")
                val decodeMs = (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1_000_000.0
                val originalWidth = decoded.width
                val originalHeight = decoded.height
                val resizeStarted = SystemClock.elapsedRealtimeNanos()
                val ratio = minOf(1.0, 640.0 / maxOf(originalWidth, originalHeight))
                val bitmap = try {
                    if (ratio < 1.0) Bitmap.createScaledBitmap(decoded,
                        (originalWidth * ratio).toInt().coerceAtLeast(1),
                        (originalHeight * ratio).toInt().coerceAtLeast(1), true) else decoded
                } catch (failure: Throwable) {
                    decoded.recycle()
                    throw failure
                }
                if (bitmap !== decoded) decoded.recycle()
                bitmaps += bitmap
                val resizeMs = (SystemClock.elapsedRealtimeNanos() - resizeStarted) / 1_000_000.0
                inputs.put(JSONObject().put("name", file.name).put("sha256", sha256(file))
                    .put("original_width", originalWidth).put("original_height", originalHeight)
                    .put("input_width", bitmap.width).put("input_height", bitmap.height)
                    .put("decode_ms", decodeMs).put("resize_ms", resizeMs))
            }
            result.put("inputs", inputs).put("after_decode", snapshot(context))
            val warningsBefore = analyzerWarnings()
            val engine = PostureAnalyzer(context, PoseModel.FULL, preferGpu = true)
            analyzer = engine
            val initStarted = SystemClock.elapsedRealtimeNanos()
            val ready = engine.ensureReady()
            result.put("init_ms", (SystemClock.elapsedRealtimeNanos() - initStarted) / 1_000_000.0)
                .put("ready", ready).put("delegate", engine.stats().delegate)
                .put("init_error", engine.stats().error ?: JSONObject.NULL)
            check(ready) { engine.stats().error ?: "모델 초기화 실패" }

            val profile = requireNotNull(ExerciseRepProfiles.forExercise("스탠딩 사이드 크런치"))
            val tracker = requireNotNull(profile.createTracker())
            val recentView = RecentRepView()
            val featureKeys = sortedSetOf<String>()
            val warmFrames = JSONArray()
            val measuredFrames = JSONArray()
            val inferTimes = ArrayList<Double>()
            val wallTimes = ArrayList<Double>()
            var lastTimestamp = -1L
            var detectedCount = 0
            var featureCount = 0
            var acceptedCount = 0
            fun analyze(index: Int, measured: Boolean): JSONObject {
                // 같은 밀리초에 끝난 경우에도 실제 시계가 진행하기를 기다린다. 합성 간격을 더하지 않는다.
                var now = SystemClock.elapsedRealtime()
                while (now <= lastTimestamp) { SystemClock.sleep(1); now = SystemClock.elapsedRealtime() }
                lastTimestamp = now
                val wallStarted = SystemClock.elapsedRealtimeNanos()
                val sample = engine.analyzeBitmap(bitmaps[index % bitmaps.size], now)
                val wallMs = (SystemClock.elapsedRealtimeNanos() - wallStarted) / 1_000_000.0
                val frame = JSONObject().put("index", index).put("input", files[index % files.size].name)
                    .put("elapsed_realtime_ms", now).put("infer_ms", sample.inferMs).put("analyze_wall_ms", wallMs)
                    .put("detected", sample.detected).put("visible_joint_count", sample.visibleJointCount)
                    .put("feature_count", sample.features.size)
                if (measured) {
                    if (sample.detected) detectedCount++
                    if (sample.features.isNotEmpty()) featureCount++
                    featureKeys += sample.features.keys
                    inferTimes += sample.inferMs.toDouble()
                    wallTimes += wallMs
                    val view = recentView.add(now, sample.features)
                    val observation = profile.observe(sample.features, profile.defaultPattern, view,
                        qualityOk = sample.features.isNotEmpty())
                    if (observation.accepted) acceptedCount++
                    val events = tracker.onFrame(now, observation.features)
                    val selectedFeatures = JSONObject()
                    profile.requiredFeatures().sorted().forEach { key ->
                        selectedFeatures.put(key, sample.features[key]?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
                    }
                    frame.put("view", view?.name ?: JSONObject.NULL)
                        .put("profile_accepted", observation.accepted)
                        .put("profile_reason", observation.reason ?: JSONObject.NULL)
                        .put("selected_features", selectedFeatures)
                        .put("diagnostic_event_sides", JSONArray(events.map { it.side.name }))
                }
                return frame
            }
            result.put("warm_frames", warmFrames).put("measured_frames", measuredFrames)
            repeat(3) { warmFrames.put(analyze(it, measured = false)) }
            result.put("after_warm", snapshot(context))
            repeat(30) { measuredFrames.put(analyze(it, measured = true)) }
            val stats = engine.stats()
            val failures = (analyzerWarnings() - warningsBefore).filter { it.contains("detect 실패:") }
            val counts = tracker.counts
            result.put("measured_count", 30).put("detected_count", detectedCount)
                .put("frames_with_features", featureCount).put("profile_accepted_count", acceptedCount)
                .put("observed_feature_keys", JSONArray(featureKeys.toList()))
                .put("infer_ms", summarize(inferTimes)).put("analyze_wall_ms", summarize(wallTimes))
                .put("after_measured", snapshot(context))
                .put("engine_infer_count", stats.inferCount).put("engine_error", stats.error ?: JSONObject.NULL)
                .put("detect_failures", JSONArray(failures))
                .put("diagnostic_counts", JSONObject().put("exercise", profile.exercise)
                    .put("pattern", tracker.pattern.name).put("total", counts.total)
                    .put("left", counts.left).put("right", counts.right).put("both", counts.both)
                    .put("unknown", counts.unknown).put("valid_rep_accuracy", JSONObject.NULL))
            check(stats.ready && stats.error == null) { stats.error ?: "추론 중 모델이 준비되지 않은 상태가 됐습니다" }
            check(failures.isEmpty()) { "실제 추론 예외가 발생했습니다: ${failures.joinToString()}" }
            check(featureCount > 0) { "측정 30프레임에서 실제 자세 피처가 하나도 생성되지 않았습니다" }
            result.put("passed", true)
        } catch (failure: Throwable) {
            result.put("failure_type", failure.javaClass.name).put("failure_message", failure.message ?: "")
            throw failure
        } finally {
            analyzer?.close()
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            result.put("after_close", snapshot(context))
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(dir, "probe-results.json").writeText(result.toString(2))
        }
    }

    /** 실제 한국어 TTS의 완료 콜백을 확인한다. 소리를 들었거나 내용을 이해했다는 검증은 아니다. */
    @Test(timeout = 45_000)
    fun lockedSpeechReportsDeliveryCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".replay")) { "평가 전용 패키지에서만 실행하세요" }
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "engine_probe").apply { mkdirs() }
        val started = SystemClock.elapsedRealtime()
        val result = JSONObject().put("schema", "trex-speech-device-probe/1").put("passed", false)
            .put("model", Build.MODEL).put("android_release", Build.VERSION.RELEASE)
            .put("audibility_verified", false).put("listener_understanding_verified", false)
        var speech: SpeechCoach? = null
        val delivered = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        try {
            instrumentation.runOnMainSync { speech = SpeechCoach(context) }
            val coach = requireNotNull(speech)
            val readyDeadline = SystemClock.elapsedRealtime() + 10_000
            while (!coach.ready && coach.unavailableReason == null && SystemClock.elapsedRealtime() < readyDeadline) {
                SystemClock.sleep(50)
            }
            result.put("ready", coach.ready).put("init_wait_ms", SystemClock.elapsedRealtime() - started)
                .put("initial_error", coach.lastError ?: JSONObject.NULL)
                .put("unavailable_reason", coach.unavailableReason ?: JSONObject.NULL)
            check(coach.ready) { coach.unavailableReason ?: coach.lastError ?: "10초 내 TTS 초기화 완료 없음" }
            val text = "트렉스 음성 테스트입니다"
            val requestedAt = SystemClock.elapsedRealtime()
            result.put("text", text).put("requested_elapsed_realtime_ms", requestedAt)
            instrumentation.runOnMainSync {
                coach.muted = false
                coach.speak(text, onDelivered = {
                    callbackCount.incrementAndGet()
                    delivered.countDown()
                })
            }
            val completed = delivered.await(15, TimeUnit.SECONDS)
            result.put("delivered", completed).put("callback_count", callbackCount.get())
                .put("delivery_wait_ms", SystemClock.elapsedRealtime() - requestedAt)
                .put("last_error", coach.lastError ?: JSONObject.NULL)
            check(completed) { coach.lastError ?: "15초 내 TTS onDone 전달 콜백 없음" }
            check(callbackCount.get() == 1) { "TTS 완료 콜백이 중복됐습니다" }
            result.put("passed", true)
        } catch (failure: Throwable) {
            result.put("failure_type", failure.javaClass.name).put("failure_message", failure.message ?: "")
            throw failure
        } finally {
            instrumentation.runOnMainSync { speech?.shutdown() }
            result.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(dir, "speech-probe-results.json").writeText(result.toString(2))
        }
    }

    /** 앱 프로세스의 엔진 경고만 읽는다. 시스템 전체 로그를 수집하거나 지우지 않는다. */
    private fun analyzerWarnings(): Set<String> {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d --pid=${Process.myPid()} -v epoch -s PostureAnalyzer:W")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines().toSet() }
    }

    private fun snapshot(context: Context): JSONObject {
        val runtime = Runtime.getRuntime()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
        fun batteryExtra(key: String): Any = if (battery?.hasExtra(key) == true) battery.getIntExtra(key, -1) else JSONObject.NULL
        return JSONObject().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("thermal_status", thermal ?: JSONObject.NULL)
            .put("battery_temperature_tenths_c", batteryExtra(BatteryManager.EXTRA_TEMPERATURE))
            .put("battery_level", batteryExtra(BatteryManager.EXTRA_LEVEL))
            .put("battery_scale", batteryExtra(BatteryManager.EXTRA_SCALE))
            .put("battery_plugged", batteryExtra(BatteryManager.EXTRA_PLUGGED))
            .put("battery_voltage_mv", batteryExtra(BatteryManager.EXTRA_VOLTAGE))
            .put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("java_heap_committed_bytes", runtime.totalMemory())
            .put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun summarize(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        return JSONObject().put("count", sorted.size).put("mean", sorted.average())
            .put("p50", percentile(.5)).put("p95", percentile(.95)).put("max", sorted.last())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
