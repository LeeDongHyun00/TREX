package com.example.trex_kotlin.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.example.trex_kotlin.AndroidFoodPhoto
import com.example.trex_kotlin.FoodPhoto
import androidx.exifinterface.media.ExifInterface
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * YOLOv8 TFLite 모델을 앱 수명 동안 1회만 로드해 재사용하는 음식 인식기.
 *
 * - 모델: assets/models/yolov8n_food.tflite (AI Hub 한식 이미지로 파인튜닝한 YOLOv8n → TFLite, float32 입출력)
 * - 라벨: assets/models/food_labels.txt — 학습 시 클래스 인덱스 순서대로 한 줄에 하나.
 *   '#'으로 시작하는 줄은 음식이 아닌 클래스로 취급해 인식 결과에서 제외한다.
 *
 * - 위치 모델: assets/models/food_region.tflite (YOLOE-11S 탐지 전용, "food·bowl·plate…" 고정 어휘, 8비트 동적 양자화).
 *   있으면 2단계 인식(그릇을 찾아 잘라서 이름)을 하고, 없거나 실패하면 전체 사진 1회 경로로 돌아간다 — 인식 자체가 막히지는 않는다.
 *   근거·측정은 docs/FOOD_EVAL_RESULTS.md §8·§8.1·§8.2.
 *
 * detect()·warmUp()은 블로킹 호출이므로 반드시 백그라운드 디스패처에서 부른다.
 *
 * 판정 흐름(2단계·내 음식 기억·이름 없는 사진의 전체 1회·결과 목록)은 공유 [FoodPipeline] 이다(§108d — iPhone 이 같은 코드를 돈다).
 * 여기서는 TFLite 인터프리터와 Bitmap 입력만 다룬다([AndroidFoodModels]).
 */
object FoodDetector {
    @Volatile private var pipeline: FoodPipeline? = null

    private fun pipeline(context: Context): FoodPipeline = pipeline ?: synchronized(this) {
        pipeline ?: context.applicationContext.let { app ->
            FoodPipeline(AndroidFoodModels(app), memory = { FoodMemoryStore.load(app) }, log = AndroidFoodLog).also { pipeline = it }
        }
    }

    /** 시트를 열 때 미리 불러, 첫 분석이 모델 로딩 지연까지 떠안지 않게 한다. */
    fun warmUp(context: Context) = pipeline(context).warmUp()

    fun detect(context: Context, photos: List<FoodPhoto>): FoodDetectionResult = pipeline(context).detect(photos)

    /**
     * [photo] 의 [box](사진 대비 0~1) 자리 특징값. 내 음식 기억을 저장할 때 쓴다. 모델이 없거나 실패하면 null.
     * 블로킹 호출이다 — 백그라운드에서 부른다.
     */
    fun embed(context: Context, photo: Bitmap, box: PixelBox): FloatArray? = pipeline(context).embed(AndroidFoodPhoto(photo), box)
}

private const val TAG = "FoodDetector"

private object AndroidFoodLog : FoodLog {
    override fun d(message: String) { Log.d(TAG, message) }
    override fun w(message: String, error: Throwable?) { if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error) }
    override fun e(message: String, error: Throwable?) { if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error) }
}

private const val MODEL_PATH = "models/yolov8n_food.tflite"
private const val REGION_MODEL_PATH = "models/food_region.tflite"

/**
 * 내 음식 기억의 특징값 모델: DINOv2-small(ViT-S/14, 224, 8비트 동적 양자화 22.7MB). 입력 [1,224,224,3] ImageNet 정규화, 출력 [1,384].
 * 기억이 하나도 없으면 불러오지도 돌리지도 않는다 — 쓰지 않는 사람에게는 비용이 없다. 입력 배치(둘레 여유·224 가운데)는 공유 [FoodInputs.embed].
 */
private const val EMBED_MODEL_PATH = "models/food_embed.tflite"
private val EMBED_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val EMBED_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
private const val LABELS_PATH = "models/food_labels.txt"

private const val NUM_THREADS = 4
private const val LETTERBOX_GRAY = 114

/** TFLite 인터프리터 셋(342종·위치·특징값)과 Bitmap → 입력 버퍼. [FoodPipeline] 의 잠금 안에서만 불린다. */
private class AndroidFoodModels(private val context: Context) : FoodModels {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var modelMissing = false
    private var regionInterpreter: Interpreter? = null

    /** 위치 모델을 쓰지 않기로 한 이유(파일 없음·로딩 실패). 한 번 정해지면 앱 수명 동안 다시 시도하지 않는다. */
    private var regionUnavailable: String? = null
    private var embedInterpreter: Interpreter? = null
    private var embedUnavailable = false

    override fun classifier(): List<String>? = loadInterpreter()?.let { labels }

    override fun regionModel(): Boolean = loadRegionInterpreter() != null

    override fun embedModel(): Boolean = loadEmbedInterpreter() != null

    /** 위치 모델 한 번 — [1, 4+클래스, 앵커] 가 기본이다. 반대 배치면 앵커가 더 많은 쪽으로 가린다(앵커 8400 ≫ 채널 13). */
    override fun regionOutput(photo: FoodPhoto): RegionOutput {
        val regionEngine = checkNotNull(regionInterpreter) { "위치 모델이 없다" }
        val bitmap = (photo as AndroidFoodPhoto).bitmap
        val input = inputGeometry(regionEngine)
        val outputShape = regionEngine.getOutputTensor(0).shape()
        val channelFirst = outputShape[1] < outputShape[2]
        val channels = if (channelFirst) outputShape[1] else outputShape[2]
        val anchors = if (channelFirst) outputShape[2] else outputShape[1]
        val raw = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        regionEngine.run(letterboxInput(bitmap, input), raw)
        val flat = FloatArray(channels * anchors)
        for (c in 0 until channels) for (a in 0 until anchors) {
            flat[c * anchors + a] = if (channelFirst) raw[0][c][a] else raw[0][a][c]
        }
        return RegionOutput(flat, channels, anchors, input.width)
    }

    override fun classScores(photo: FoodPhoto, crop: PixelBox?): FloatArray {
        val engine = checkNotNull(interpreter) { "분류 모델이 없다" }
        val bitmap = (photo as AndroidFoodPhoto).bitmap
        return classScores(engine, if (crop == null) letterboxInput(bitmap, inputGeometry(engine)) else cropInput(bitmap, crop, inputGeometry(engine)))
    }

    override fun embed(photo: FoodPhoto, box: PixelBox): FloatArray =
        embedWith(checkNotNull(embedInterpreter) { "특징값 모델이 없다" }, (photo as AndroidFoodPhoto).bitmap, box)

    private fun loadInterpreter(): Interpreter? {
        interpreter?.let { return it }
        if (modelMissing) return null
        val model = try {
            openModel(MODEL_PATH)
        } catch (e: FileNotFoundException) {
            modelMissing = true
            Log.w(TAG, "$MODEL_PATH 가 assets에 없어 모델 없이 동작한다")
            return null
        }
        labels = context.assets.open(LABELS_PATH).bufferedReader().readLines()
            .map { it.trim().removePrefix("﻿") } // 첫 줄 UTF-8 BOM 제거
            .filter { it.isNotEmpty() }
        val engine = Interpreter(model, Interpreter.Options().apply { setNumThreads(NUM_THREADS) })
        // preprocess가 float32 입력을 전제한다. Ultralytics의 INT8 export도 입출력은 float32로
        // 유지되므로 호환되지만, 입출력까지 int8로 변환된 모델이 들어오면 여기서 명확히 걸러낸다.
        val inputType = engine.getInputTensor(0).dataType()
        Log.i(
            TAG,
            "모델 로드: 입력 ${engine.getInputTensor(0).shape().contentToString()} $inputType, " +
                "출력 ${engine.getOutputTensor(0).shape().contentToString()}, 라벨 ${labels.size}종",
        )
        if (inputType != DataType.FLOAT32) {
            engine.close()
            throw IllegalStateException("모델 입력 타입이 $inputType 이다. float32 입출력을 유지한 TFLite 변환본을 사용하라")
        }
        return engine.also { interpreter = it }
    }

    /**
     * 위치 모델을 불러온다. 없거나 못 불러오면 null — 호출부는 전체 사진 1회로 간다.
     * 실패를 예외로 올리지 않는 이유: 위치 모델은 인식을 **돕는** 것이지 없으면 안 되는 것이 아니다.
     */
    private fun loadRegionInterpreter(): Interpreter? {
        regionInterpreter?.let { return it }
        if (regionUnavailable != null) return null
        return try {
            val model = openModel(REGION_MODEL_PATH)
            val engine = Interpreter(model, Interpreter.Options().apply { setNumThreads(NUM_THREADS) })
            val input = engine.getInputTensor(0)
            val output = engine.getOutputTensor(0)
            Log.i(TAG, "위치 모델 로드: 입력 ${input.shape().contentToString()} ${input.dataType()}, 출력 ${output.shape().contentToString()}")
            if (input.dataType() != DataType.FLOAT32 || output.shape().size != 3) {
                engine.close()
                throw IllegalStateException("위치 모델 입출력이 맞지 않는다: ${input.dataType()} ${output.shape().contentToString()}")
            }
            engine.also { regionInterpreter = it }
        } catch (e: FileNotFoundException) {
            regionUnavailable = "파일 없음"
            Log.w(TAG, "$REGION_MODEL_PATH 가 없어 전체 사진 1회로만 인식한다")
            null
        } catch (e: Exception) {
            regionUnavailable = "로딩 실패"
            Log.e(TAG, "위치 모델 로딩 실패 — 전체 사진 1회로만 인식한다", e)
            null
        }
    }

    private fun loadEmbedInterpreter(): Interpreter? {
        embedInterpreter?.let { return it }
        if (embedUnavailable) return null
        return try {
            val engine = Interpreter(openModel(EMBED_MODEL_PATH), Interpreter.Options().apply { setNumThreads(NUM_THREADS) })
            val shape = engine.getInputTensor(0).shape()
            if (!shape.contentEquals(intArrayOf(1, FoodInputs.EMBED_SIZE, FoodInputs.EMBED_SIZE, 3)) || engine.getInputTensor(0).dataType() != DataType.FLOAT32) {
                engine.close()
                throw IllegalStateException("특징값 모델 입력이 맞지 않는다: ${shape.contentToString()}")
            }
            Log.i(TAG, "특징값 모델 로드: 출력 ${engine.getOutputTensor(0).shape().contentToString()}")
            engine.also { embedInterpreter = it }
        } catch (e: FileNotFoundException) {
            embedUnavailable = true
            Log.w(TAG, "$EMBED_MODEL_PATH 가 없어 내 음식 기억을 쓰지 않는다")
            null
        } catch (e: Exception) {
            embedUnavailable = true
            Log.e(TAG, "특징값 모델 로딩 실패 — 내 음식 기억을 쓰지 않는다", e)
            null
        } catch (e: OutOfMemoryError) {
            // 22MB 모델이 압축돼 들어간 빌드는 힙으로 읽는다. 저사양 기기에서 앱을 죽이지 않고 기억만 끈다.
            embedUnavailable = true
            Log.e(TAG, "특징값 모델 로딩 중 메모리 부족 — 내 음식 기억을 쓰지 않는다", e)
            null
        }
    }

    /**
     * 자리를 둘레 10% 넓혀 자르고, 짧은 변을 224 로 맞춘 뒤 가운데 224×224 를 쓴다(배치는 공유 [FoodInputs.embed]).
     * ImageNet 평균·표준편차로 정규화한다.
     */
    private fun embedWith(engine: Interpreter, photo: Bitmap, box: PixelBox): FloatArray {
        val placed = FoodInputs.embed(box, photo.width, photo.height)
        val input = toInputBuffer(InputGeometry(FoodInputs.EMBED_SIZE, FoodInputs.EMBED_SIZE, channelsFirst = false), EMBED_MEAN, EMBED_STD) { canvas ->
            canvas.drawBitmap(photo, placed.src.toRect(), placed.dst.toRectF(), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val out = Array(1) { FloatArray(engine.getOutputTensor(0).shape()[1]) }
        engine.run(input, out)
        return out[0]
    }

    /**
     * .tflite 는 AGP 기본 noCompress 목록에 있어 보통 mmap 으로 열린다(힙 복사 없음).
     * 압축돼 들어간 빌드면 openFd 가 실패하므로 힙으로 읽는 경로로 내려간다. 파일 자체가 없으면 FileNotFoundException.
     */
    private fun openModel(path: String): ByteBuffer {
        try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: FileNotFoundException) {
            // openFd 는 파일이 없을 때와 압축돼 있을 때 모두 이 예외를 던진다 — 아래 open 으로 존재 여부를 가린다.
        }
        val bytes = context.assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    /** 입력 텐서의 크기와 배치. */
    private class InputGeometry(val width: Int, val height: Int, val channelsFirst: Boolean)

    private fun inputGeometry(engine: Interpreter): InputGeometry {
        // 입력은 NHWC [1, H, W, 3](Ultralytics 기본) 또는 NCHW [1, 3, H, W](ONNX 경유 변환본) 둘 다 온다.
        // 실측: 2026-09-09 변환본은 NCHW 였고, NHWC 로 가정하면 640×3 짜리 버퍼를 만들어 run() 이 예외로 죽는다.
        val shape = engine.getInputTensor(0).shape()
        val channelsFirst = shape[1] == 3 && shape[3] != 3
        return InputGeometry(
            width = if (channelsFirst) shape[3] else shape[2],
            height = if (channelsFirst) shape[2] else shape[1],
            channelsFirst = channelsFirst,
        )
    }

    /** 342종 모델을 한 번 돌려 클래스별 최고 점수를 낸다. */
    private fun classScores(engine: Interpreter, input: ByteBuffer): FloatArray {
        // YOLOv8 TFLite export의 출력은 [1, 4+클래스수, 박스수]. 반대 배치도 방어적으로 처리한다.
        val outputShape = engine.getOutputTensor(0).shape()
        val expectedChannels = labels.size + 4
        val channelFirst = when (expectedChannels) {
            outputShape[1] -> true
            outputShape[2] -> false
            else -> throw IllegalStateException(
                "출력 형태 ${outputShape.contentToString()}가 라벨 수 ${labels.size}와 맞지 않는다"
            )
        }
        val boxes = if (channelFirst) outputShape[2] else outputShape[1]
        val output = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        engine.run(input, output)

        // 이 모델의 박스는 쓰지 않는다 — 위치는 위치 모델이 맡고, 여기서는 클래스별 최고 confidence 만 뽑는다.
        val best = FloatArray(labels.size)
        for (c in labels.indices) {
            for (b in 0 until boxes) {
                val score = if (channelFirst) output[0][4 + c][b] else output[0][b][4 + c]
                if (score > best[c]) best[c] = score
            }
        }
        return best
    }

    /** letterbox(공유 [FoodInputs.letterbox]) — 비율을 유지해 맞추고 남는 영역은 회색(114)으로 채운다. */
    private fun letterboxInput(bitmap: Bitmap, input: InputGeometry): ByteBuffer {
        val dst = FoodInputs.letterbox(bitmap.width, bitmap.height, input.width, input.height).dst
        return toInputBuffer(input) { canvas ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, dst.width.toInt(), dst.height.toInt(), true), dst.left, dst.top, null)
        }
    }

    /** 2단계 입력: [box] 자리만 잘라 회색 캔버스 가운데에 [FoodRegions.cropPlacement] 크기로 그린다(공유 [FoodInputs.crop]). */
    private fun cropInput(bitmap: Bitmap, box: PixelBox, input: InputGeometry): ByteBuffer {
        val placed = FoodInputs.crop(box, input.width, input.height)
        return toInputBuffer(input) { canvas ->
            canvas.drawBitmap(bitmap, placed.src.toRect(), placed.dst.toRectF(), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun PixelBox.toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    private fun PixelBox.toRectF() = RectF(left, top, right, bottom)

    // 입력 캔버스·픽셀·버퍼를 크기별로 한 벌만 두고 돌려 쓴다. 2단계는 자리마다(사진 5장 × 최대 20곳) 입력을 만드는데,
    // 매번 새로 만들면 1회에 약 6.5MB 씩 할당돼 저사양 기기에서 GC·메모리 부족을 부른다. [lock] 안에서만 쓰고,
    // run() 이 버퍼를 다 읽은 뒤에 다음 입력을 만들므로 한 벌로 충분하다.
    private var scratchBitmap: Bitmap? = null
    private var scratchPixels: IntArray? = null
    private var scratchBuffer: ByteBuffer? = null
    private var scratchFloats: FloatArray? = null

    /** 회색으로 채운 입력 크기 캔버스에 [draw] 로 그린 뒤 0~1 float 버퍼로 옮긴다. 반환 버퍼는 다음 호출에서 덮어써진다. */
    private inline fun toInputBuffer(
        input: InputGeometry,
        mean: FloatArray? = null,
        std: FloatArray? = null,
        draw: (Canvas) -> Unit,
    ): ByteBuffer {
        val width = input.width
        val height = input.height
        val boxed = scratchBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scratchBitmap = it }
        Canvas(boxed).also { canvas ->
            canvas.drawColor(Color.rgb(LETTERBOX_GRAY, LETTERBOX_GRAY, LETTERBOX_GRAY))
            draw(canvas)
        }
        val bytes = width * height * 3 * 4
        val buffer = scratchBuffer?.takeIf { it.capacity() == bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { scratchBuffer = it }
        buffer.clear()
        val pixels = scratchPixels?.takeIf { it.size == width * height } ?: IntArray(width * height).also { scratchPixels = it }
        boxed.getPixels(pixels, 0, width, 0, 0, width, height)
        // 값을 float 배열에 먼저 채우고 버퍼에는 한 번에 넣는다. 640×640 입력이면 putFloat 가 123만 번 불려,
        // 자리마다 입력을 만드는 2단계에서 실기기 한 자리당 약 100ms 가 걸렸다(모델만 27ms).
        val count = width * height
        val floats = scratchFloats?.takeIf { it.size == count * 3 } ?: FloatArray(count * 3).also { scratchFloats = it }
        if (input.channelsFirst) {
            // NCHW: R 평면 전체 → G 평면 → B 평면 순으로 채운다.
            for (i in 0 until count) {
                val pixel = pixels[i]
                floats[i] = ((pixel shr 16) and 0xFF) / 255f
                floats[count + i] = ((pixel shr 8) and 0xFF) / 255f
                floats[2 * count + i] = (pixel and 0xFF) / 255f
            }
        } else {
            // NHWC: 픽셀마다 R, G, B.
            for (i in 0 until count) {
                val pixel = pixels[i]
                floats[3 * i] = ((pixel shr 16) and 0xFF) / 255f
                floats[3 * i + 1] = ((pixel shr 8) and 0xFF) / 255f
                floats[3 * i + 2] = (pixel and 0xFF) / 255f
            }
        }
        // 특징값 모델은 ImageNet 정규화를 쓴다(342종·위치 모델은 0~1 그대로). 채널 우선 입력에는 쓰지 않는다.
        if (mean != null && std != null && !input.channelsFirst) {
            for (i in 0 until count) for (ch in 0..2) floats[3 * i + ch] = (floats[3 * i + ch] - mean[ch]) / std[ch]
        }
        buffer.asFloatBuffer().put(floats)
        buffer.rewind()
        return buffer
    }
}

/**
 * 최대 변이 [maxDimension]을 넘으면 비율을 유지한 채 축소한다. 이미 작으면 원본을 그대로 돌려준다.
 */
fun Bitmap.scaledToMax(maxDimension: Int): Bitmap {
    val largest = maxOf(width, height)
    if (largest <= maxDimension) return this
    val scale = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}

/**
 * 카메라 회전각(ImageInfo.rotationDegrees) 또는 EXIF 방향(회전 + 좌우 반전)을 적용한다.
 * 변환이 없으면 원본을 그대로 돌려준다. 촬영·갤러리 경로가 같은 헬퍼를 쓴다.
 */
fun Bitmap.rotated(degrees: Int, flipped: Boolean = false): Bitmap {
    if (degrees == 0 && !flipped) return this
    val matrix = Matrix()
    if (flipped) matrix.preScale(-1f, 1f)
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * 갤러리 Uri를 추론·표시용 Bitmap으로 디코딩한다. 스트림을 한 번만 읽어 크기 확인·디코딩·EXIF 에 같이 쓰고,
 * 최대 변을 [maxDimension] 이하로 정확히 맞춘 뒤(저사양 기기 OOM 방지) EXIF 회전·반전을 반영한다. 실패하면 null.
 */
fun decodeScaledBitmap(context: Context, uri: Uri, maxDimension: Int = 1280): Bitmap? = try {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    if (bytes == null) {
        null
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // inSampleSize 는 2의 거듭제곱이라 최대 변이 maxDimension 의 2배 미만까지만 보장된다 — 디코딩 뒤 scaledToMax 로 정확히 맞춘다.
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        if (decoded == null) {
            null
        } else {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            decoded.scaledToMax(maxDimension).rotated(exif.rotationDegrees, exif.isFlipped)
        }
    }
} catch (e: Exception) {
    Log.w("FoodDetector", "이미지 디코딩 실패: $uri", e)
    null
}
