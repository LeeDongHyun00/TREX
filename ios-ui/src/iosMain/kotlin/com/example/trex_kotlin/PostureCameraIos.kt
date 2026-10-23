@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.example.trex_kotlin.ios.IosRepTone
import com.example.trex_kotlin.ios.UserDefaultsStore
import com.example.trex_kotlin.ios.documentsPath
import com.example.trex_kotlin.ios.onMain
import com.example.trex_kotlin.posture.AnalyzerStats
import com.example.trex_kotlin.posture.FaceCrop
import com.example.trex_kotlin.posture.FaceTrack
import com.example.trex_kotlin.posture.InferencePolicy
import com.example.trex_kotlin.posture.LensInfo
import com.example.trex_kotlin.posture.ModeStore
import com.example.trex_kotlin.posture.PoseLandmarks
import com.example.trex_kotlin.posture.PoseModel
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.SCREEN_UP
import com.example.trex_kotlin.posture.SetLogStore
import com.example.trex_kotlin.posture.Vec3
import com.example.trex_kotlin.posture.faceYawDeg
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.awaitCancellation
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.thermalState
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import kotlin.concurrent.Volatile

/*
 * 자세 화면의 iPhone 플랫폼 쪽(§108c) — Android `PostureCameraAndroid.kt` 와 같은 이름의 짝.
 * 카메라 세션·미리보기·MediaPipe 추론은 Swift(`iosApp/Trex/PoseCameraBridge.swift`, MediaPipeTasksVision 팟)가 [IosCameraBridge] 로 맡고,
 * 그 결과를 공유 `PoseLandmarks.sample` 이 Android 와 같은 피처로 만든다. 중력(Core Motion)·열 상태·권한은 여기서 직접 읽는다.
 */

// ---- Swift 다리(앱 시작 때 Swift 가 IosCameraBridgeRegistry.bridge 에 넣는다)

/** 카메라 한 프레임(Swift 가 CVPixelBuffer 를 감싼다) — 분석 큐의 그 콜백 안에서만 유효하다. */
interface IosCameraFrame {
    val width: Int
    val height: Int
}

/** Swift 가 분석 큐에서 프레임마다 부른다 — 이 호출이 끝날 때까지 다음 프레임은 버려진다(최신만, Android KEEP_ONLY_LATEST). */
interface IosCameraFrameSink {
    fun onFrame(frame: IosCameraFrame)
}

/** 자세 추론 결과 — 정규화 xy 33×2, 가시성 = min(visibility, presence)(없으면 1) 33, 월드 좌표(m) 33×3. */
class IosPoseDetection(val xy: FloatArray, val visibility: FloatArray, val world: FloatArray)

/** 얼굴 추론 결과 — [matrix] = 4×4 자세 행렬(검출됐을 때). */
class IosFaceDetection(val found: Boolean, val matrix: FloatArray?)

interface IosCameraBridge {
    /** 미리보기 뷰(AVCaptureVideoPreviewLayer, 비율 유지 — FIT_CENTER). 한 번 만들어 계속 쓴다. */
    fun previewView(): UIView
    /** [front] 카메라로 세션을 시작한다(메인에서 부른다). 실패하면 이유 문구, 성공이면 null. 프레임은 [sink] 로 온다. */
    fun start(front: Boolean, sink: IosCameraFrameSink): String?
    fun stop()
    /** 분석 큐 — 자세 추론(VIDEO 모드, [timestampMs] 는 단조 증가). 사람이 없으면 null. 추론이 실패하면 null 이고 [modelError] 가 채워진다. */
    fun detectPose(frame: IosCameraFrame, timestampMs: Long): IosPoseDetection?
    /** 분석 큐 — 얼굴 메시(IMAGE 모드)를 머리 크롭에(기하 = 공유 FaceCrop). 모델이 없으면 null. */
    fun detectFace(frame: IosCameraFrame, cx: Float, cy: Float, rotationDeg: Float, scale: Float, outPx: Int): IosFaceDetection?
    /** 모델을 못 올렸으면 그 이유(화면이 "몸을 인식할 수 없어요" 를 보인다). */
    val modelError: String?
}

object IosCameraBridgeRegistry {
    @Volatile var bridge: IosCameraBridge? = null
}

/** 개발용 확인(실행 인자 --posture-probe) — 번들 그림 한 장을 분석 큐에서 추론한다(Swift 다리가 함께 구현한다). */
interface IosStillDetector {
    fun detectStill(bundleFile: String, timestampMs: Long): IosStillDetection?
}

class IosStillDetection(val width: Int, val height: Int, val pose: IosPoseDetection?)

/**
 * 개발용 확인 수치(--posture-probe) — 넘어온 프레임·카메라가 버린 프레임(Swift)·추론·검출·피처가 계산된 프레임 수와
 * 시간 합(µs: 프레임 콜백 전체 = 공유 루프, MediaPipe, 공유 후처리). 분석 큐 하나만 쓴다.
 */
object IosCameraCounters {
    @Volatile var frames = 0L
    @Volatile var dropped = 0L
    @Volatile var analyses = 0L
    @Volatile var detected = 0L
    @Volatile var featured = 0L
    @Volatile var loopUs = 0L
    @Volatile var detectUs = 0L
    @Volatile var postUs = 0L
    fun reset() { frames = 0; dropped = 0; analyses = 0; detected = 0; featured = 0; loopUs = 0; detectUs = 0; postUs = 0 }
    fun line(): String = "frames=$frames dropped=$dropped analyses=$analyses detected=$detected featured=$featured " +
        "loop_ms=${loopUs / 1000} detect_avg_ms=${if (analyses > 0) detectUs / analyses / 1000.0 else 0.0} post_avg_ms=${if (detected > 0) postUs / detected / 1000.0 else 0.0}"
}

internal fun uptimeUs(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000.0).toLong()

// ---- PoseCamera

private class IosFrame(val native: IosCameraFrame) : PoseFrame {
    override fun close() {}   // 버퍼는 Swift 콜백이 끝나면 놓인다
}

/**
 * iPhone 카메라 + MediaPipe iOS. 추론·얼굴 시간 관리·통계는 Android `PostureAnalyzer` 와 같은 순서로 하고(단조 시각, EMA, 얼굴 300 ms 격자),
 * 추론 이후는 공유 [PoseLandmarks.sample]. 세로 고정 앱이라 영상은 늘 세로(회전 0)다.
 */
private class IosPoseCamera : PoseCamera {
    private val lock = SynchronizedObject()
    private val motion = CMMotionManager()
    private var thermalObserver: NSObjectProtocol? = null
    private val faceTrack = FaceTrack()
    @Volatile private var closed = false
    private var lastTimestampMs = 0L
    @Volatile private var inferCount = 0L
    @Volatile private var emaMs = 0f
    @Volatile private var lastMs = 0L

    private val bridge: IosCameraBridge? get() = IosCameraBridgeRegistry.bridge

    @Volatile override var faceEnabled: Boolean = false
    /** iPhone 은 렌즈 물리 사양(초점거리 mm·센서 크기)을 주지 않는다 — 배치 지문은 거리·높이 없이 피치·롤·주기만(§91). */
    override val lens: LensInfo? = null
    override val thermalSupported: Boolean = true

    override fun setDisplayRotation(rotation: Int) {}

    override suspend fun stream(front: Boolean, label: String, onFrame: (PoseFrame) -> Unit) {
        val b = bridge ?: error("카메라 연결이 없습니다")
        val failure = b.start(front, object : IosCameraFrameSink {
            override fun onFrame(frame: IosCameraFrame) {
                val t0 = uptimeUs()
                IosCameraCounters.frames++
                onFrame(IosFrame(frame))
                IosCameraCounters.loopUs += uptimeUs() - t0
            }
        })
        if (failure != null) error(failure)
        try {
            awaitCancellation()
        } finally {
            b.stop()
        }
    }

    override fun analyze(frame: PoseFrame, now: Long, up: Vec3): PoseSample = locked(lock) {
        val fromGravity = up !== SCREEN_UP
        val b = bridge
        val f = (frame as IosFrame).native
        if (closed || b == null || b.modelError != null) return@locked PoseSample.empty(up = up, fromGravity = fromGravity)
        val ts = maxOf(now, lastTimestampMs + 1)
        lastTimestampMs = ts
        val started = monotonicMs()
        val t0 = uptimeUs()
        val det = runCatching { b.detectPose(f, ts) }.getOrNull()
        IosCameraCounters.detectUs += uptimeUs() - t0
        val inferMs = monotonicMs() - started
        recordStat(inferMs)
        IosCameraCounters.analyses++
        val w = f.width
        val h = f.height
        if (det == null || det.xy.size < 66 || det.visibility.size < 33 || det.world.size < 99) return@locked PoseSample.empty(inferMs, w, h, up, fromGravity)
        IosCameraCounters.detected++
        val t1 = uptimeUs()
        PoseLandmarks.sample(det.xy, det.visibility, det.world, w, h, inferMs, up,
            addFace = if (faceEnabled) { out -> faceFeatures(b, f, det.xy, det.visibility, ts, out) } else null)
            .also { IosCameraCounters.postUs += uptimeUs() - t1; if (it.features.isNotEmpty()) IosCameraCounters.featured++ }
    }

    /** 얼굴 피처(§101d) — Android `PostureAnalyzer.faceFeatures` 와 같은 순서(격자마다 한 번, 머리가 안 보이면 그 칸은 건너뜀). */
    private fun faceFeatures(b: IosCameraBridge, f: IosCameraFrame, xy: FloatArray, vis: FloatArray, ts: Long, out: HashMap<String, Float>) {
        if (!faceEnabled) return
        if (faceTrack.due(ts)) {
            val geometry = FaceCrop.of(xy, vis, f.width, f.height)
            if (geometry != null) {
                val started = monotonicMs()
                val r = runCatching { b.detectFace(f, geometry.cx, geometry.cy, geometry.rotationDeg, geometry.scale, com.example.trex_kotlin.posture.FACE_CROP_PX) }.getOrNull()
                val ms = monotonicMs() - started
                if (r != null) {
                    val yaw = if (r.found) r.matrix?.let { faceYawDeg(it) } ?: Float.NaN else Float.NaN
                    faceTrack.record(ts, r.found, yaw, ms, geometry)
                } else faceTrack.skip(ts)   // 얼굴 모델이 없다 — 키를 넣지 않는다(시선 '측정 중')
            } else {
                faceTrack.skip(ts)
            }
        }
        faceTrack.apply(ts, out)
    }

    private fun recordStat(ms: Long) {
        inferCount += 1
        lastMs = ms
        emaMs = if (inferCount == 1L) ms.toFloat() else emaMs * 0.8f + ms * 0.2f
    }

    override fun stats(): AnalyzerStats =
        AnalyzerStats("CPU", PoseModel.FULL.label, bridge != null && bridge?.modelError == null, inferCount, emaMs, lastMs, bridge?.modelError)

    /**
     * Core Motion 중력 → 앱 월드 up(§4). 앱 좌표는 영상 오른쪽 x·위 y·카메라 쪽 z 이고, Core Motion 중력은 기기 좌표(오른쪽 x·위 y·화면 밖 z)의 **아래** 방향이다.
     * 후면 카메라는 영상 축 = 기기 축이라 up = −g, 전면(미러 전 영상)은 x·z 가 뒤집혀 (gx, −gy, gz). 세로 고정이라 [rotation] 은 0 뿐이다.
     */
    override fun gravityUp(rotation: Int, front: Boolean): Vec3 {
        val g = motion.deviceMotion?.gravity ?: return SCREEN_UP
        val v = g.useContents { if (front) Vec3(x.toFloat(), (-y).toFloat(), z.toFloat()) else Vec3((-x).toFloat(), (-y).toFloat(), (-z).toFloat()) }
        return v.unit() ?: SCREEN_UP
    }

    override fun start(onThermal: (Int) -> Unit) {
        if (motion.deviceMotionAvailable) {
            motion.deviceMotionUpdateInterval = 0.05
            motion.startDeviceMotionUpdates()
        }
        onThermal(thermalLevel())
        thermalObserver = NSNotificationCenter.defaultCenter.addObserverForName(NSProcessInfoThermalStateDidChangeNotification, null,
            NSOperationQueue.mainQueue) { _ -> onThermal(thermalLevel()) }
    }

    override fun stop() {
        motion.stopDeviceMotionUpdates()
        thermalObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        thermalObserver = null
        closed = true   // 진행 중 추론은 락이 끝낸 뒤 — 다음 프레임부터 빈 샘플
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        val b = bridge
        if (b != null) UIKitView(factory = { b.previewView() }, modifier = modifier,
            properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false))
    }
}

/** iPhone 열 상태 → Android PowerManager 단계(InferencePolicy 배수): 보통 → 약간 높음, 심각 → 심한 발열(×2), 위험 → 위험(×3). */
private fun thermalLevel(): Int = when (NSProcessInfo.processInfo.thermalState) {
    NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> InferencePolicy.THERMAL_LIGHT
    NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> InferencePolicy.THERMAL_SEVERE
    NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> InferencePolicy.THERMAL_CRITICAL
    else -> InferencePolicy.THERMAL_NONE
}

private fun monotonicMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

@Composable
fun rememberPoseCamera(): PoseCamera = remember { IosPoseCamera() }

/** 카메라 권한 — 아직 묻지 않았으면 처음 한 번 묻는다. 거절·제한이면 바로 거절 화면(직접 기록으로 잇기). */
@Composable
fun rememberCameraPermission(): CameraPermission {
    val permission = remember { CameraPermission(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized) }
    LaunchedEffect(Unit) {
        if (permission.granted) return@LaunchedEffect
        if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { ok -> onMain { permission.granted = ok; permission.asked = true } }
        } else {
            permission.asked = true
        }
    }
    return permission
}

@Composable
fun rememberRepTone(volume: Int): RepTone? = remember { IosRepTone(volume) }

private class IosPostureHost : PostureHost {
    /** Android assets 경로(`posture/…`) — 번들에서는 같은 상대 경로, 없으면 파일 이름만(project.yml 이 자세 자산을 번들 맨 위에 둔다). */
    override fun readAsset(path: String): String =
        checkNotNull(readBundleBytes(path) ?: readBundleBytes(path.substringAfterLast('/'))) { "번들에 없는 자산: $path" }.decodeToString()
    override fun profileHeightCm(): Float? = TrexStore(UserDefaultsStore("trex_store")).loadProfile()?.heightCm?.toFloat()
    override fun modeStore(): ModeStore = ModeStore(UserDefaultsStore("trex_posture"))
    override fun setLogStore(): SetLogStore = SetLogStore(documentsPath() + "/posture_logs")
    /** 설치 단위 익명 수행자 ID — Android `SubjectId` 와 같은 저장소 이름·키·형식("s-" + UUID 앞 8자리). */
    override fun subjectId(): String {
        val prefs = UserDefaultsStore("trex_posture")
        prefs.getString("subject_id", null)?.let { return it }
        val id = "s-" + newUuid().substring(0, 8)
        prefs.edit().putString("subject_id", id).apply()
        return id
    }
    override fun appVersion(): String? = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
}

@Composable
fun rememberPostureHost(): PostureHost = remember { IosPostureHost() }
