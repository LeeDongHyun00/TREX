package com.example.trex_kotlin.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
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
 * 온디바이스 음식 인식 결과. 사진 원본은 어떤 경우에도 기기 밖으로 전송하지 않는다.
 */
sealed interface FoodDetectionResult {
    /**
     * [foods] 는 임계값을 넘어 결과로 보여줄 음식.
     *
     * [candidates] 는 **임계값에 못 미쳐 결과에서 뺀 것**이다. 기록에 자동으로 들어가지 않고,
     * 사용자가 "빠진 음식 추가"를 눌렀을 때 고를 거리로만 쓴다 — 모델이 판정하지 못한 것을
     * 판정한 것처럼 내놓지 않으면서, 이미 계산해 둔 신호를 버리지 않기 위한 절충이다.
     *
     * 이 모델은 "음식 없음"을 배운 적이 없어(학습 이미지 전부가 음식 1개짜리다) 무엇을 넣든
     * 늘 무언가를 뱉는다. 그래서 낮은 점수는 확률이 아니라 **순서**로만 의미가 있다.
     */
    data class Success(
        val foods: List<DetectedFood>,
        val candidates: List<DetectedFood> = emptyList(),
    ) : FoodDetectionResult

    /** assets에 yolov8n_food.tflite 가 없다(빌드에서 빠졌을 때). 파일을 넣으면 그대로 실추론으로 전환된다. */
    data object ModelMissing : FoodDetectionResult

    data object Error : FoodDetectionResult
}

/** [photoIndex] 는 여러 장 중 이 음식의 최고 점수가 나온 사진의 인덱스 — 결과 화면에서 어느 사진에서 잡혔는지 보여준다. */
data class DetectedFood(val name: String, val confidence: Float, val photoIndex: Int)

/**
 * YOLOv8 TFLite 모델을 앱 수명 동안 1회만 로드해 재사용하는 음식 인식기.
 *
 * - 모델: assets/models/yolov8n_food.tflite (AI Hub 한식 이미지로 파인튜닝한 YOLOv8n → TFLite, float32 입출력)
 * - 라벨: assets/models/food_labels.txt — 학습 시 클래스 인덱스 순서대로 한 줄에 하나.
 *   '#'으로 시작하는 줄은 음식이 아닌 클래스로 취급해 인식 결과에서 제외한다.
 *
 * detect()·warmUp()은 블로킹 호출이므로 반드시 백그라운드 디스패처에서 부른다.
 */
object FoodDetector {

    private const val TAG = "FoodDetector"
    private const val MODEL_PATH = "models/yolov8n_food.tflite"
    private const val LABELS_PATH = "models/food_labels.txt"
    private const val CONFIDENCE_THRESHOLD = 0.40f
    private const val MAX_FOODS_PER_ANALYSIS = 5

    /**
     * 후보로도 내놓지 않는 바닥값. 이 아래는 순서에도 의미가 없는 잡음으로 본다.
     * 임계값(0.40)과 달리 근거가 약한 값이다 — 실사용 사진으로 재보고 조정할 것.
     */
    private const val CANDIDATE_FLOOR = 0.10f

    /** 후보 목록의 최대 개수. 훑어보는 목록이라 길면 오히려 고르기 어렵다. */
    private const val MAX_CANDIDATES = 8
    private const val NUM_THREADS = 4
    private const val LETTERBOX_GRAY = 114

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var modelMissing = false

    /** 시트를 열 때 미리 불러, 첫 분석이 모델 로딩 지연까지 떠안지 않게 한다. */
    fun warmUp(context: Context) {
        synchronized(lock) {
            try {
                loadInterpreter(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "모델 예열 실패", e)
            }
        }
    }

    fun detect(context: Context, bitmaps: List<Bitmap>): FoodDetectionResult {
        if (bitmaps.isEmpty()) return FoodDetectionResult.Error
        synchronized(lock) {
            val engine = try {
                loadInterpreter(context.applicationContext) ?: return FoodDetectionResult.ModelMissing
            } catch (e: Exception) {
                Log.e(TAG, "모델 로딩 실패", e)
                return FoodDetectionResult.Error
            }
            return try {
                // 사진 여러 장이면 인식된 음식을 라벨 기준으로 합치고, 같은 음식은 최고 confidence(와 그 사진)만 남긴다.
                val merged = LinkedHashMap<String, DetectedFood>()
                bitmaps.forEachIndexed { index, bitmap ->
                    runInference(engine, bitmap).forEach { (name, confidence) ->
                        val previous = merged[name]
                        if (previous == null || confidence > previous.confidence) {
                            merged[name] = DetectedFood(name, confidence, index)
                        }
                    }
                }
                val ranked = merged.values.sortedByDescending { it.confidence }
                val foods = ranked
                    .filter { it.confidence >= CONFIDENCE_THRESHOLD }
                    .take(MAX_FOODS_PER_ANALYSIS)
                // 결과에 이미 든 것은 후보에서 뺀다 — 같은 이름을 두 곳에 보여줄 이유가 없다.
                val shown = foods.mapTo(HashSet()) { it.name }
                val candidates = ranked
                    .filter { it.confidence < CONFIDENCE_THRESHOLD && it.name !in shown }
                    .take(MAX_CANDIDATES)
                FoodDetectionResult.Success(foods, candidates)
            } catch (e: Exception) {
                Log.e(TAG, "추론 실패", e)
                FoodDetectionResult.Error
            }
        }
    }

    private fun loadInterpreter(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (modelMissing) return null
        val model = try {
            openModel(context)
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
     * .tflite 는 AGP 기본 noCompress 목록에 있어 보통 mmap 으로 열린다(힙 복사 없음).
     * 압축돼 들어간 빌드면 openFd 가 실패하므로 힙으로 읽는 경로로 내려간다. 파일 자체가 없으면 FileNotFoundException.
     */
    private fun openModel(context: Context): ByteBuffer {
        try {
            context.assets.openFd(MODEL_PATH).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: FileNotFoundException) {
            // openFd 는 파일이 없을 때와 압축돼 있을 때 모두 이 예외를 던진다 — 아래 open 으로 존재 여부를 가린다.
        }
        val bytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    private fun runInference(engine: Interpreter, bitmap: Bitmap): Map<String, Float> {
        // 입력은 NHWC [1, H, W, 3](Ultralytics 기본) 또는 NCHW [1, 3, H, W](ONNX 경유 변환본) 둘 다 온다.
        // 실측: 2026-09-09 변환본은 NCHW 였고, NHWC 로 가정하면 640×3 짜리 버퍼를 만들어 run() 이 예외로 죽는다.
        val inputShape = engine.getInputTensor(0).shape()
        val channelsFirst = inputShape[1] == 3 && inputShape[3] != 3
        val inputHeight = if (channelsFirst) inputShape[2] else inputShape[1]
        val inputWidth = if (channelsFirst) inputShape[3] else inputShape[2]
        val input = preprocess(bitmap, inputWidth, inputHeight, channelsFirst)

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
        val startedAt = System.nanoTime()
        engine.run(input, output)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // 현재 UX는 "무슨 음식인지"만 쓰므로 박스 좌표·NMS 없이 클래스별 최고 confidence만 뽑는다.
        val best = FloatArray(labels.size)
        for (c in labels.indices) {
            for (b in 0 until boxes) {
                val score = if (channelFirst) output[0][4 + c][b] else output[0][b][4 + c]
                if (score > best[c]) best[c] = score
            }
        }
        // 임계값과 무관하게 상위 5개를 남긴다 — "왜 못 잡았나"(근소 미달 vs 엉뚱한 클래스)를 실기기 로그로 가리기 위해서다.
        val ranked = labels.indices
            .filter { !labels[it].startsWith("#") }
            .sortedByDescending { best[it] }
            .take(5)
            .joinToString { "${labels[it]} ${"%.2f".format(best[it])}" }
        Log.d(TAG, "추론 ${elapsedMs}ms · 상위 점수: $ranked (임계 $CONFIDENCE_THRESHOLD)")

        // 임계값이 아니라 바닥값으로 자른다. 임계 미만은 결과에 넣지 않지만 후보로는 쓰므로,
        // 여기서 버리면 detect() 가 나눌 수 없다.
        val scored = LinkedHashMap<String, Float>()
        for (c in labels.indices) {
            val name = labels[c]
            if (name.startsWith("#")) continue
            if (best[c] >= CANDIDATE_FLOOR) scored[name] = best[c]
        }
        return scored
    }

    private fun preprocess(bitmap: Bitmap, width: Int, height: Int, channelsFirst: Boolean): ByteBuffer {
        // Ultralytics 학습·평가와 같은 letterbox: 비율을 유지해 맞추고 남는 영역은 회색(114)으로 채운다.
        // 정사각형으로 늘리면(stretch) 학습 분포와 달라져 confidence 가 떨어진다.
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val boxed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(boxed).apply {
            drawColor(Color.rgb(LETTERBOX_GRAY, LETTERBOX_GRAY, LETTERBOX_GRAY))
            drawBitmap(
                Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true),
                ((width - scaledW) / 2).toFloat(),
                ((height - scaledH) / 2).toFloat(),
                null,
            )
        }
        val buffer = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        boxed.getPixels(pixels, 0, width, 0, 0, width, height)
        if (channelsFirst) {
            // NCHW: R 평면 전체 → G 평면 → B 평면 순으로 채운다.
            for (shift in intArrayOf(16, 8, 0)) {
                pixels.forEach { pixel -> buffer.putFloat(((pixel shr shift) and 0xFF) / 255f) }
            }
        } else {
            // NHWC: 픽셀마다 R, G, B.
            pixels.forEach { pixel ->
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                buffer.putFloat((pixel and 0xFF) / 255f)
            }
        }
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
