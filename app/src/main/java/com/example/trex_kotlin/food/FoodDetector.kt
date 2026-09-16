package com.example.trex_kotlin.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 온디바이스 음식 인식 결과. 사진 원본은 어떤 경우에도 기기 밖으로 전송하지 않는다(Privacy-by-Design).
 */
sealed interface FoodDetectionResult {
    data class Success(val foods: List<DetectedFood>) : FoodDetectionResult

    /** assets에 모델 파일이 아직 없음. yolov8n_food.tflite가 준비되면 파일만 넣으면 실추론으로 전환된다. */
    data object ModelMissing : FoodDetectionResult

    data object Error : FoodDetectionResult
}

data class DetectedFood(val name: String, val confidence: Float)

/**
 * YOLOv8 TFLite 모델을 앱 수명 동안 1회만 로드해 재사용하는 음식 인식기.
 *
 * - 모델: assets/models/yolov8n_food.tflite (한식 파인튜닝 YOLOv8n → TFLite 변환본)
 * - 라벨: assets/models/food_labels.txt — 학습 시 클래스 인덱스 순서대로 한 줄에 하나.
 *   '#'으로 시작하는 줄은 음식이 아닌 클래스로 취급해 인식 결과에서 제외한다.
 *
 * detect()는 블로킹 호출이므로 반드시 백그라운드 디스패처에서 부른다.
 */
object FoodDetector {

    private const val TAG = "FoodDetector"
    private const val MODEL_PATH = "models/yolov8n_food.tflite"
    private const val LABELS_PATH = "models/food_labels.txt"
    private const val CONFIDENCE_THRESHOLD = 0.40f
    private const val MAX_FOODS_PER_ANALYSIS = 5
    private const val NUM_THREADS = 4

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var modelMissing = false

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
                // 사진 여러 장이면 인식된 음식을 라벨 기준으로 합치고, 같은 음식은 최고 confidence만 남긴다.
                val merged = LinkedHashMap<String, Float>()
                bitmaps.forEach { bitmap ->
                    runInference(engine, bitmap).forEach { (name, confidence) ->
                        merged.merge(name, confidence, ::maxOf)
                    }
                }
                val foods = merged.entries
                    .sortedByDescending { it.value }
                    .take(MAX_FOODS_PER_ANALYSIS)
                    .map { DetectedFood(it.key, it.value) }
                FoodDetectionResult.Success(foods)
            } catch (e: Exception) {
                Log.e(TAG, "추론 실패", e)
                FoodDetectionResult.Error
            }
        }
    }

    private fun loadInterpreter(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (modelMissing) return null
        val modelBytes = try {
            context.assets.open(MODEL_PATH).use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            modelMissing = true
            Log.w(TAG, "$MODEL_PATH 가 assets에 없어 모델 없이 동작한다")
            return null
        }
        labels = context.assets.open(LABELS_PATH).bufferedReader().readLines()
            .map { it.trim().removePrefix("\uFEFF") } // 첫 줄 UTF-8 BOM 제거
            .filter { it.isNotEmpty() }
        val buffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        buffer.put(modelBytes)
        buffer.rewind()
        val engine = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(NUM_THREADS) })
        // preprocess가 float32 입력을 전제한다. Ultralytics의 INT8 export도 입출력은 float32로
        // 유지되므로 호환되지만, 입출력까지 int8로 변환된 모델이 들어오면 여기서 명확히 걸러낸다.
        val inputType = engine.getInputTensor(0).dataType()
        if (inputType != DataType.FLOAT32) {
            engine.close()
            throw IllegalStateException(
                "모델 입력 타입이 $inputType 이다. float32 입출력을 유지한 TFLite 변환본을 사용하라"
            )
        }
        return engine.also { interpreter = it }
    }

    private fun runInference(engine: Interpreter, bitmap: Bitmap): Map<String, Float> {
        val inputShape = engine.getInputTensor(0).shape() // [1, H, W, 3]
        val inputHeight = inputShape[1]
        val inputWidth = inputShape[2]
        val input = preprocess(bitmap, inputWidth, inputHeight)

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

        // 현재 UX는 "무슨 음식인지"만 쓰므로 박스 좌표·NMS 없이 클래스별 최고 confidence만 뽑는다.
        val detected = LinkedHashMap<String, Float>()
        for (c in labels.indices) {
            val name = labels[c]
            if (name.startsWith("#")) continue
            var best = 0f
            for (b in 0 until boxes) {
                val score = if (channelFirst) output[0][4 + c][b] else output[0][b][4 + c]
                if (score > best) best = score
            }
            if (best >= CONFIDENCE_THRESHOLD) detected[name] = best
        }
        return detected
    }

    private fun preprocess(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val buffer = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        pixels.forEach { pixel ->
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
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
 * 갤러리 Uri를 추론용 Bitmap으로 디코딩한다. 저사양 기기 OOM을 피하기 위해 최대 변을 제한하고,
 * EXIF 회전을 반영한다. 실패하면 null.
 */
fun decodeScaledBitmap(context: Context, uri: Uri, maxDimension: Int = 1280): Bitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (bitmap == null) {
        null
    } else {
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        }
    }
} catch (e: Exception) {
    Log.w("FoodDetector", "이미지 디코딩 실패: $uri", e)
    null
}
