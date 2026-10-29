package com.example.trex_kotlin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.community.UploadSizing
import com.example.trex_kotlin.food.FoodDetectionResult
import com.example.trex_kotlin.food.FoodDetector
import com.example.trex_kotlin.food.FoodMemoryStore
import com.example.trex_kotlin.food.decodeScaledBitmap
import com.example.trex_kotlin.food.rotated
import com.example.trex_kotlin.food.scaledToMax
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/*
 * 사진 식단의 Android 플랫폼 쪽(§108d) — `PhotoFoodApi.kt` 경계의 구현. §108d 전 `PhotoFoodSheet.kt` 에 있던 코드를 그대로 옮겼다
 * (갤러리 선택기, CameraX 촬영·미리보기, Bitmap). 인식은 `FoodDetector`(TFLite) → 공유 `FoodPipeline`.
 */

/** Android Bitmap 사진. */
class AndroidFoodPhoto(val bitmap: Bitmap) : FoodPhoto {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val image: ImageBitmap by lazy { bitmap.asImageBitmap() }
    override fun scaledToMax(maxDimension: Int): FoodPhoto = bitmap.scaledToMax(maxDimension).let { if (it === bitmap) this else AndroidFoodPhoto(it) }
    override fun crop(left: Int, top: Int, width: Int, height: Int): FoodPhoto = AndroidFoodPhoto(Bitmap.createBitmap(bitmap, left, top, width, height))
    override fun jpeg(maxDimension: Int, quality: Int): ByteArray {
        val (w, h) = UploadSizing.fit(bitmap.width, bitmap.height, maxDimension)
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            return ByteArrayOutputStream().use { out ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG 로 만들지 못했어요" }
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}

/**
 * 화면에서 뜬 그림(오운완 카드, 커뮤니티 글쓰기)을 JPEG 로 — iPhone 은 ios-ui 의 같은 이름 함수. 그래픽 레이어 그림은 HARDWARE
 * 비트맵이라 압축할 수 없어 소프트웨어 사본을 만든다. IO 스레드에서 부른다.
 */
fun encodeJpeg(image: ImageBitmap, quality: Int = 90): ByteArray {
    val source = image.asAndroidBitmap()
    val soft = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    try {
        return ByteArrayOutputStream().use { out ->
            check(soft.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG 로 만들지 못했어요" }
            out.toByteArray()
        }
    } finally {
        if (soft !== source) soft.recycle()
    }
}

/** 메모리 부족 — 2단계 인식·기억 비교가 앱을 죽이지 않고 물러서는 조건(`FoodPipeline`). */
fun isOutOfMemory(error: Throwable): Boolean = error is OutOfMemoryError

private class AndroidFoodRecognizer(private val context: Context) : FoodRecognizer {
    override fun warmUp() = FoodDetector.warmUp(context)
    override fun detect(photos: List<FoodPhoto>): FoodDetectionResult = FoodDetector.detect(context, photos)
    override fun remember(spots: List<FoodMemorySpot>) =
        FoodMemoryStore.rememberAsync(context, spots.map { FoodMemoryStore.Spot(it.name, (it.photo as AndroidFoodPhoto).bitmap, it.box, it.correctedFrom) })
}

@Composable
fun rememberFoodRecognizer(): FoodRecognizer {
    val context = LocalContext.current
    return remember { AndroidFoodRecognizer(context) }
}

/**
 * 갤러리(사진 선택기, 최대 [maxItems]장) — 돌려준 함수를 부르면 연다. 고른 사진은 분석 스레드에서 디코딩한다.
 * 한 장이면 단일 선택기 — `PickMultipleVisualMedia` 는 최대 1장을 받지 않고 생성자에서 앱을 멈춘다(커뮤니티 프로필 사진, 2026-10-29 실기기).
 */
@Composable
fun rememberFoodGallery(maxItems: Int, onPicked: (List<FoodPhotoLoader>) -> Unit): () -> Unit {
    val context = LocalContext.current
    fun loaders(uris: List<android.net.Uri>) = uris.map { uri -> FoodPhotoLoader { decodeScaledBitmap(context, uri)?.let { AndroidFoodPhoto(it) } } }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    if (maxItems <= 1) {
        val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(loaders(listOfNotNull(uri))) }
        return { single.launch(request) }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxItems),
    ) { uris -> onPicked(loaders(uris)) }
    return { launcher.launch(request) }
}

@Composable
fun rememberFoodCamera(): FoodCamera {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { AndroidFoodCamera(context, lifecycleOwner) }
    DisposableEffect(Unit) {
        onDispose { camera.release() }
    }
    return camera
}

private class AndroidFoodCamera(private val context: Context, private val lifecycleOwner: LifecycleOwner) : FoodCamera {
    private val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    // 촬영 결과(수천만 화소 JPEG)의 디코딩·축소·회전은 메인 스레드에서 하지 않는다.
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraReleased = false
    override var error by mutableStateOf<String?>(null) // 카메라를 열지 못함 — 촬영 불가

    fun release() {
        cameraReleased = true
        cameraProvider?.unbindAll()
        captureExecutor.shutdown()
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val view = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    // 시트가 먼저 닫힌 뒤 리스너가 실행되면 바인딩하지 않는다(카메라 점유 누수 방지).
                    if (cameraReleased) return@addListener
                    try {
                        val provider = providerFuture.get()
                        cameraProvider = provider
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(view.surfaceProvider)
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        error = null
                    } catch (e: Exception) {
                        // 후면 카메라가 없거나 다른 앱이 점유 중인 경우 — 앱을 죽이지 않고 갤러리/직접 입력으로 안내한다.
                        Log.w("PhotoFoodSheet", "카메라 열기 실패", e)
                        error = "카메라를 열 수 없어요. 다른 앱이 카메라를 쓰고 있지 않은지 확인하거나 갤러리에서 선택해 주세요."
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
    }

    override fun capture(onPhoto: (FoodPhoto) -> Unit, onError: () -> Unit) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // 축소를 먼저 하고 회전한다 — 원본 크기 복사본을 하나 더 만들지 않기 위해서다(저사양 기기 OOM 방지).
                    val bitmap = image.use { proxy ->
                        proxy.toBitmap().scaledToMax(1280).rotated(proxy.imageInfo.rotationDegrees)
                    }
                    mainExecutor.execute { onPhoto(AndroidFoodPhoto(bitmap)) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w("PhotoFoodSheet", "촬영 실패", exception)
                    mainExecutor.execute { onError() }
                }
            },
        )
    }
}
