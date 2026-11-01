@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.example.trex_kotlin.food.FoodDetectionResult
import com.example.trex_kotlin.food.FoodLog
import com.example.trex_kotlin.food.FoodMemory
import com.example.trex_kotlin.food.FoodModels
import com.example.trex_kotlin.food.FoodPipeline
import com.example.trex_kotlin.food.FoodInputs
import com.example.trex_kotlin.food.InputPlacement
import com.example.trex_kotlin.food.MemoryEntry
import com.example.trex_kotlin.food.PixelBox
import com.example.trex_kotlin.food.RegionOutput
import com.example.trex_kotlin.ios.appSupportPath
import com.example.trex_kotlin.ios.readSecureTextFile
import com.example.trex_kotlin.ios.toByteArray
import com.example.trex_kotlin.ios.writeSecureTextFile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSData
import platform.UIKit.UIView
import platform.posix.memcpy
import kotlin.concurrent.Volatile

/*
 * 사진 식단의 iPhone 플랫폼 쪽(§108d) — Android `PhotoFoodAndroid.kt` 와 같은 이름의 짝.
 * 사진 보관함·카메라·TFLite(TrexFoodRuntime)는 Swift(`iosApp/Trex/FoodBridge.swift`)가 [IosFoodModelBridge]·[IosFoodPhotoSource] 로 맡고,
 * 입력 배치(letterbox·30% 자리·특징값 자리)와 판정 흐름은 Android 와 같은 공유 Kotlin(`FoodInputs`·`FoodPipeline`)이다.
 */

// ---- Swift 다리(앱 시작 때 Swift 가 IosFoodBridgeRegistry 에 넣는다)

/** 사진 한 장(Swift 가 CGImage 를 감싼다) — 방향은 이미 위쪽, 크기 = 픽셀. */
interface IosFoodImage {
    val width: Int
    val height: Int
    /** RGBA 8888(미리 곱한 알파, sRGB) 픽셀 — 화면 그림을 만든다. */
    fun rgba(): NSData
    fun scaled(maxDimension: Int): IosFoodImage
    fun cropped(left: Int, top: Int, width: Int, height: Int): IosFoodImage
}

/** 갤러리에서 고른 사진 한 장 — [loadImage] 는 블로킹(분석 스레드에서 부른다), 최대 변 1280·방향 반영. 실패하면 null. */
interface IosFoodImageLoader {
    fun loadImage(): IosFoodImage?
}

interface IosFoodModelBridge {
    /**
     * 모델 하나를 불러 둔다("yolov8n_food"·"food_region"·"food_embed") — 성공 null, 번들에 파일이 없으면 [MODEL_MISSING], 그 밖은 이유.
     * 입력 float32·4차원과 모델별 형태(위치: 출력 3차원, 특징값: 입력 [1, 224, 224, 3])를 여기서 확인한다. 한 번 정한 결과는 다시 시도하지 않는다.
     */
    fun prepare(name: String): String?
    /** 입력 텐서 모양 — [1, H, W, 3] 또는 [1, 3, H, W]. */
    fun inputShape(name: String): IntArray
    /** 출력 텐서 모양(첫 차원 포함). */
    fun outputShape(name: String): IntArray
    /**
     * 모델 한 번: 회색(114) 입력 캔버스에 사진의 [srcLeft]..[srcBottom] 픽셀 영역을 [dstLeft]..[dstBottom] 자리로 그려(보간) 0~1 float32 로 바꾼다.
     * [imageNet] 이면 ImageNet 평균·표준편차로 정규화한다(채널 마지막 입력에만 — Android 와 같다).
     * [reduceClasses] > 0 이면 출력 [1, 4+C, B]·[1, B, 4+C] 에서 클래스별 최고 점수 C 개만(형태가 맞지 않으면 실패), 0 이면 출력 그대로. 실패하면 null.
     */
    fun run(name: String, image: IosFoodImage, srcLeft: Float, srcTop: Float, srcRight: Float, srcBottom: Float,
            dstLeft: Float, dstTop: Float, dstRight: Float, dstBottom: Float, imageNet: Boolean, reduceClasses: Int): NSData?
}

interface IosFoodPhotoSource {
    /** 사진 보관함(PHPicker, 최대 [maxItems]장, 고른 순서) — 고른 사진의 로더를 [done](메인)에. 취소하면 빈 목록. */
    fun pickPhotos(maxItems: Int, done: (List<IosFoodImageLoader>) -> Unit)
    /** 시트 안 카메라 미리보기(화면 채움 — Android FILL_CENTER) — 부를 때마다 새 뷰(자세 화면 `IosCameraBridge.makePreviewView` 와 같은 이유). 놓을 때 [releaseCameraView]. */
    fun makeCameraView(): UIView
    fun releaseCameraView(view: UIView)
    /** 후면 카메라 시작(메인에서) — 실패하면 이유, 성공이면 null. */
    fun startCamera(): String?
    fun stopCamera()
    /** 한 장 찍는다 — 최대 변 1280·방향 반영 사진을 [done](메인)에, 실패하면 null. */
    fun capture(done: (IosFoodImage?) -> Unit)
}

object IosFoodBridgeRegistry {
    @Volatile var models: IosFoodModelBridge? = null
    @Volatile var photos: IosFoodPhotoSource? = null
}

/** [IosFoodModelBridge.prepare] 의 '번들에 파일 없음'. */
const val MODEL_MISSING = "missing"

// ---- 사진

/** iPhone 사진. 화면 그림은 처음 그릴 때 한 번 만든다. */
class IosFoodPhoto(val native: IosFoodImage) : FoodPhoto {
    override val width: Int get() = native.width
    override val height: Int get() = native.height
    override val image: ImageBitmap by lazy {
        // 크기는 Bitmap 블록(apply) 밖에서 정한다 — 안에서 width·height 는 빈 Bitmap 자신의 0 이 되어 0×0 그림이 됐고,
        // 그 그림을 그리던 결과 화면이 Compose 크기 검사(Painter inset)에 걸려 촬영 직후 앱이 죽었다(10-23 실기기)
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        bitmap.installPixels(native.rgba().toByteArray())
        bitmap.setImmutable()
        bitmap.asComposeImageBitmap()
    }
    override fun scaledToMax(maxDimension: Int): FoodPhoto = if (maxOf(width, height) <= maxDimension) this else IosFoodPhoto(native.scaled(maxDimension))
    override fun crop(left: Int, top: Int, width: Int, height: Int): FoodPhoto = IosFoodPhoto(native.cropped(left, top, width, height))
    override fun jpeg(maxDimension: Int, quality: Int): ByteArray {
        // Swift 가 줄인 픽셀(RGBA)로 Skia 래스터를 만들어 JPEG 로 — 원본 파일이 아니니 EXIF 가 없다(Android 와 같은 결과)
        val source = if (maxOf(width, height) <= maxDimension) native else native.scaled(maxDimension)
        val info = ImageInfo(source.width, source.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val raster = org.jetbrains.skia.Image.makeRaster(info, source.rgba().toByteArray(), source.width * 4)
        try {
            val data = raster.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, quality)
                ?: throw IllegalStateException("JPEG 로 만들지 못했어요")
            try {
                return data.bytes
            } finally {
                data.close()
            }
        } finally {
            raster.close()
        }
    }
}

/** 화면에서 뜬 그림(오운완 카드)을 JPEG 로 — Android `PhotoFoodAndroid.kt` 의 같은 이름 함수와 같은 결과. */
fun encodeJpeg(image: ImageBitmap, quality: Int = 90): ByteArray {
    val skia = org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap())
    try {
        val data = skia.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, quality)
            ?: throw IllegalStateException("JPEG 로 만들지 못했어요")
        try {
            return data.bytes
        } finally {
            data.close()
        }
    } finally {
        skia.close()
    }
}

/** 메모리 부족 — Kotlin/Native 의 OutOfMemoryError(2단계 인식·기억 비교가 앱을 죽이지 않고 물러서는 조건). */
fun isOutOfMemory(error: Throwable): Boolean = error::class.simpleName == "OutOfMemoryError"

// ---- 모델(입력 배치는 Android 와 같은 공유 `FoodInputs` — 그리기·TFLite 만 Swift)

private const val CLASSIFIER = "yolov8n_food"
private const val REGION = "food_region"
private const val EMBED = "food_embed"
private const val LABELS = "food_labels.txt"

private fun NSData.toFloatArray(): FloatArray {
    val out = FloatArray(length.toInt() / 4)
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** [FoodPipeline] 의 잠금 안에서만 불린다. */
private class IosFoodModels(private val bridge: IosFoodModelBridge) : FoodModels {
    private var labels: List<String>? = null
    private var classifierMissing = false
    private var regionReady: Boolean? = null
    private var embedReady: Boolean? = null

    override fun classifier(): List<String>? {
        labels?.let { return it }
        if (classifierMissing) return null
        when (val failure = bridge.prepare(CLASSIFIER)) {
            null -> Unit
            MODEL_MISSING -> {
                classifierMissing = true
                IosFoodLog.w("$CLASSIFIER 가 번들에 없어 모델 없이 동작한다")
                return null
            }
            else -> error(failure)
        }
        // Android 와 같다 — 줄마다 앞뒤 공백, 첫 줄 UTF-8 BOM 제거, 빈 줄 제외
        val text = checkNotNull(readBundleBytes(LABELS)) { "$LABELS 가 번들에 없다" }.decodeToString()
        return text.lines().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.also { labels = it }
    }

    override fun regionModel(): Boolean = regionReady ?: available(REGION).also { regionReady = it }

    override fun embedModel(): Boolean = embedReady ?: available(EMBED).also { embedReady = it }

    /** 위치·특징값 모델은 인식을 돕는 것이라 실패를 예외로 올리지 않는다 — 없으면 전체 사진 1회·기억 없이 간다(Android 와 같다). */
    private fun available(name: String): Boolean = when (val failure = bridge.prepare(name)) {
        null -> true
        else -> { IosFoodLog.w("$name 를 쓰지 않는다: $failure"); false }
    }

    /** 입력 크기 — NHWC [1, H, W, 3] 또는 NCHW [1, 3, H, W](Android `inputGeometry`). */
    private fun inputSize(name: String): Pair<Int, Int> {
        val shape = bridge.inputShape(name)
        val channelsFirst = shape[1] == 3 && shape[3] != 3
        return (if (channelsFirst) shape[3] else shape[2]) to (if (channelsFirst) shape[2] else shape[1])
    }

    /** 모델 한 번 — [placed] 대로 그린 입력(Swift), 실패하면 예외(판정 흐름이 오류·물러서기로 받는다). */
    private fun run(name: String, photo: IosFoodPhoto, placed: InputPlacement, imageNet: Boolean, reduce: Int): FloatArray {
        val (src, dst) = placed.src to placed.dst
        return bridge.run(name, photo.native, src.left, src.top, src.right, src.bottom, dst.left, dst.top, dst.right, dst.bottom, imageNet, reduce)
            ?.toFloatArray() ?: error("$name 실행 실패")
    }

    /** 사진 전체 letterbox(Android `letterboxInput`). */
    private fun letterbox(name: String, photo: IosFoodPhoto, reduce: Int): FloatArray {
        val (inW, inH) = inputSize(name)
        return run(name, photo, FoodInputs.letterbox(photo.width, photo.height, inW, inH), imageNet = false, reduce = reduce)
    }

    override fun regionOutput(photo: FoodPhoto): RegionOutput {
        val p = photo as IosFoodPhoto
        val raw = letterbox(REGION, p, 0)
        // [1, 4+클래스, 앵커] 가 기본이다. 반대 배치면 앵커가 더 많은 쪽으로 가린다(Android 와 같다)
        val shape = bridge.outputShape(REGION)
        val channelFirst = shape[1] < shape[2]
        val channels = if (channelFirst) shape[1] else shape[2]
        val anchors = if (channelFirst) shape[2] else shape[1]
        val flat = if (channelFirst) raw else FloatArray(channels * anchors).also { f ->
            for (c in 0 until channels) for (a in 0 until anchors) f[c * anchors + a] = raw[a * channels + c]
        }
        return RegionOutput(flat, channels, anchors, inputSize(REGION).first)
    }

    override fun classScores(photo: FoodPhoto, crop: PixelBox?): FloatArray {
        val p = photo as IosFoodPhoto
        val classes = checkNotNull(labels) { "분류 모델이 없다" }.size
        if (crop == null) return letterbox(CLASSIFIER, p, classes)
        // 2단계 입력: 자리만 잘라 회색 캔버스 가운데에 cropPlacement 크기로(Android `cropInput`)
        val (inW, inH) = inputSize(CLASSIFIER)
        return run(CLASSIFIER, p, FoodInputs.crop(crop, inW, inH), imageNet = false, reduce = classes)
    }

    /** 자리를 둘레 10% 넓혀 자르고 짧은 변 224 가운데(Android `embedWith`), ImageNet 정규화. */
    override fun embed(photo: FoodPhoto, box: PixelBox): FloatArray {
        val p = photo as IosFoodPhoto
        return run(EMBED, p, FoodInputs.embed(box, p.width, p.height), imageNet = true, reduce = 0)
    }
}

private object IosFoodLog : FoodLog {
    override fun d(message: String) = println("[FoodDetector] $message")
    override fun w(message: String, error: Throwable?) = println("[FoodDetector] W $message${error?.let { " — ${it.message}" }.orEmpty()}")
    override fun e(message: String, error: Throwable?) = println("[FoodDetector] E $message${error?.let { " — ${it.message}" }.orEmpty()}")
}

/**
 * 내 음식 기억 — Android `FoodMemoryStore` 와 같은 텍스트 형식(`FoodMemory.encode`). 앱 지원 폴더 `TREX/food-memory.tsv`
 * (§107g SwiftUI 판이 쓰던 파일을 그대로 이어 읽는다). 사진은 저장하지 않는다 — 이름과 특징값만.
 */
private object IosFoodMemoryStore {
    private val lock = SynchronizedObject()
    private var cached: FoodMemory? = null
    /** 기록 시트가 닫힌 뒤에도 저장이 끝나도록 화면 수명과 무관한 범위에서 돈다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val path: String get() = appSupportPath("TREX") + "/food-memory.tsv"

    fun load(): FoodMemory = locked(lock) { loadLocked() }

    private fun loadLocked(): FoodMemory = cached ?: run {
        // 인증/읽기 실패는 빈 기억으로 바꾸지 않는다. 준비 단계와 저장소 오류 화면이 원본을 보호한다.
        val memory = readSecureTextFile(path)?.let { FoodMemory.decode(it) } ?: FoodMemory()
        memory.also { cached = it }
    }

    /** 사용자가 사진의 자리에 직접 붙인 이름을 기억한다(Android `rememberAsync`) — 특징값 계산이 무거워 백그라운드에서 하고, 끝나면 한 번에 쓴다. */
    fun rememberAsync(pipeline: FoodPipeline, spots: List<FoodMemorySpot>) {
        if (spots.isEmpty()) return
        scope.launch {
            val vectors = spots.mapNotNull { spot -> pipeline.embed(spot.photo, spot.box)?.let { spot to it } }
            if (vectors.isEmpty()) return@launch
            locked(lock) {
                var memory = loadLocked()
                val now = nowMillis()
                vectors.forEachIndexed { i, (spot, v) ->
                    memory = memory.plus(MemoryEntry(FoodMemory.clean(spot.name), v, now + i, spot.correctedFrom?.let(FoodMemory::clean)))
                }
                try {
                    writeSecureTextFile(path, memory.encode())
                    cached = memory
                } catch (e: Exception) {
                    IosFoodLog.e("기억 저장 실패", e)
                }
            }
            IosFoodLog.d("기억 ${vectors.size}개 추가: ${vectors.joinToString { (spot, _) -> spot.name + (spot.correctedFrom?.let { " (모델: $it)" } ?: "") }}")
        }
    }
}

private object IosFoodRecognition {
    /** 앱 수명 하나(Android `FoodDetector`) — Swift 다리가 없으면(개발용 확인 등) 모델 없음으로 답한다. */
    val pipeline: FoodPipeline? by lazy {
        IosFoodBridgeRegistry.models?.let { FoodPipeline(IosFoodModels(it), memory = { IosFoodMemoryStore.load() }, log = IosFoodLog) }
    }
}

/** 개발용 확인(--food-probe, `ios/FoodProbe.kt`)이 특징값까지 보려고 쓴다. */
internal fun iosFoodPipeline(): FoodPipeline? = IosFoodRecognition.pipeline

private class IosFoodRecognizer : FoodRecognizer {
    override fun warmUp() { IosFoodRecognition.pipeline?.warmUp() }
    override fun detect(photos: List<FoodPhoto>): FoodDetectionResult = IosFoodRecognition.pipeline?.detect(photos) ?: FoodDetectionResult.ModelMissing
    override fun remember(spots: List<FoodMemorySpot>) { IosFoodRecognition.pipeline?.let { IosFoodMemoryStore.rememberAsync(it, spots) } }
}

@Composable
fun rememberFoodRecognizer(): FoodRecognizer = remember { IosFoodRecognizer() }

/** 사진 보관함(최대 [maxItems]장) — 돌려준 함수를 부르면 연다. 고른 사진은 분석 스레드에서 불러온다. */
@Composable
fun rememberFoodGallery(maxItems: Int, onPicked: (List<FoodPhotoLoader>) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onPicked)
    return remember(maxItems) {
        {
            IosFoodBridgeRegistry.photos?.pickPhotos(maxItems) { loaders ->
                latest(loaders.map { loader -> FoodPhotoLoader { loader.loadImage()?.let { IosFoodPhoto(it) } } })
            }
        }
    }
}

@Composable
fun rememberFoodCamera(): FoodCamera {
    val camera = remember { IosFoodCamera() }
    DisposableEffect(Unit) {
        onDispose { camera.release() }
    }
    return camera
}

private class IosFoodCamera : FoodCamera {
    override var error by mutableStateOf<String?>(null) // 카메라를 열지 못함 — 촬영 불가
    private var running = false

    @Composable
    override fun Preview(modifier: Modifier) {
        val source = IosFoodBridgeRegistry.photos
        DisposableEffect(source) {
            val failure = if (source == null) "카메라 다리 없음" else source.startCamera()
            running = failure == null
            if (failure != null) {
                // 후면 카메라가 없거나 다른 곳이 쓰고 있을 때 — 앱을 죽이지 않고 갤러리/직접 입력으로 안내한다(Android 와 같은 문구)
                IosFoodLog.w("카메라 열기 실패: $failure")
                error = "카메라를 열 수 없어요. 다른 앱이 카메라를 쓰고 있지 않은지 확인하거나 갤러리에서 선택해 주세요."
            } else {
                error = null
            }
            onDispose { release() }
        }
        if (source != null) UIKitView(factory = { source.makeCameraView() }, onRelease = { source.releaseCameraView(it) }, modifier = modifier,
            properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false))
    }

    override fun capture(onPhoto: (FoodPhoto) -> Unit, onError: () -> Unit) {
        val source = IosFoodBridgeRegistry.photos ?: return onError()
        source.capture { image -> if (image != null) onPhoto(IosFoodPhoto(image)) else onError() }
    }

    fun release() {
        if (!running) return
        running = false
        IosFoodBridgeRegistry.photos?.stopCamera()
    }
}
