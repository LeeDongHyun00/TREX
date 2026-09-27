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
     *
     * [regions] 는 2단계 인식(그릇 찾기 → 잘라서 이름)이 돌았을 때만 찬다. 비어 있으면 위치 모델이 없거나 실패했거나
     * 그릇을 하나도 못 찾아 **전체 사진 1회** 경로로 돌아간 결과다 — 화면은 예전처럼 목록만 보여준다.
     * 차 있으면 [foods] 는 이름이 붙은 자리를 이름별로 합친 것이고, 이름이 없는("?") 자리는 [regions] 에만 있다.
     */
    data class Success(
        val foods: List<DetectedFood>,
        val candidates: List<DetectedFood> = emptyList(),
        val regions: List<FoodRegion> = emptyList(),
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
 * - 위치 모델: assets/models/food_region.tflite (YOLOE-11S 탐지 전용, "food·bowl·plate…" 고정 어휘, 8비트 동적 양자화).
 *   있으면 2단계 인식(그릇을 찾아 잘라서 이름)을 하고, 없거나 실패하면 전체 사진 1회 경로로 돌아간다 — 인식 자체가 막히지는 않는다.
 *   근거·측정은 docs/FOOD_EVAL_RESULTS.md §8·§8.1·§8.2.
 *
 * detect()·warmUp()은 블로킹 호출이므로 반드시 백그라운드 디스패처에서 부른다.
 */
object FoodDetector {

    private const val TAG = "FoodDetector"
    private const val MODEL_PATH = "models/yolov8n_food.tflite"
    private const val REGION_MODEL_PATH = "models/food_region.tflite"
    private const val LABELS_PATH = "models/food_labels.txt"
    private const val CONFIDENCE_THRESHOLD = FoodRegions.NAME_THRESHOLD
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
    private var regionInterpreter: Interpreter? = null

    /** 위치 모델을 쓰지 않기로 한 이유(파일 없음·로딩 실패). 한 번 정해지면 앱 수명 동안 다시 시도하지 않는다. */
    private var regionUnavailable: String? = null

    /** 시트를 열 때 미리 불러, 첫 분석이 모델 로딩 지연까지 떠안지 않게 한다. */
    fun warmUp(context: Context) {
        synchronized(lock) {
            try {
                loadInterpreter(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "모델 예열 실패", e)
            }
            loadRegionInterpreter(context.applicationContext)
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
            loadRegionInterpreter(context.applicationContext)?.let { regionEngine ->
                // 2단계가 실패해도 사용자는 인식 결과를 받아야 한다 — 전체 사진 1회로 내려간다.
                val regions = try {
                    detectRegions(regionEngine, engine, bitmaps)
                } catch (e: Exception) {
                    Log.e(TAG, "2단계 인식 실패 — 전체 사진 1회로 대신한다", e)
                    emptyList()
                } catch (e: OutOfMemoryError) {
                    // 자리 수만큼 자르기를 돌리는 경로라 저사양 기기에서 메모리가 모자랄 수 있다. 앱을 죽이지 않고 1회 경로로 간다.
                    Log.e(TAG, "2단계 인식 중 메모리 부족 — 전체 사진 1회로 대신한다", e)
                    emptyList()
                }
                if (regions.isNotEmpty()) {
                    // 이름이 하나도 안 붙은 사진은 전체 사진 1회로도 본다 — 그 사진의 음식이 통째로 빠지지 않게.
                    // 여기서 나온 음식은 자리가 없어 사진 위 박스 없이 목록에만 들어간다.
                    val fallback = try {
                        regions.photosWithoutNames(bitmaps.size).flatMap { index ->
                            runInference(engine, bitmaps[index]).map { (name, confidence) -> DetectedFood(name, confidence, index) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "자리 없는 사진의 전체 1회 인식 실패", e)
                        emptyList()
                    }
                    val (foods, candidates) = regions.toResultLists(fallback, MAX_CANDIDATES)
                    return FoodDetectionResult.Success(foods, candidates, regions)
                }
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
            openModel(context, MODEL_PATH)
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
    private fun loadRegionInterpreter(context: Context): Interpreter? {
        regionInterpreter?.let { return it }
        if (regionUnavailable != null) return null
        return try {
            val model = openModel(context, REGION_MODEL_PATH)
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

    /**
     * 2단계 인식. 사진마다 그릇 자리를 찾아([FoodRegions.decode]·[FoodRegions.tidy]) 자리마다 잘라 342종 모델에 넣고
     * 상위 [FoodRegions.TOP_PER_REGION] 개를 남긴다. 자리 좌표는 사진 대비 0~1 로 바꿔 둔다(화면 사진은 크기가 다르다).
     */
    private fun detectRegions(regionEngine: Interpreter, foodEngine: Interpreter, bitmaps: List<Bitmap>): List<FoodRegion> {
        val startedAt = System.nanoTime()
        val input = inputGeometry(regionEngine)
        val outputShape = regionEngine.getOutputTensor(0).shape()
        // [1, 4+클래스, 앵커] 가 기본이다. 반대 배치면 앵커가 더 많은 쪽으로 가린다(앵커 8400 ≫ 채널 13).
        val channelFirst = outputShape[1] < outputShape[2]
        val channels = if (channelFirst) outputShape[1] else outputShape[2]
        val anchors = if (channelFirst) outputShape[2] else outputShape[1]
        val regions = ArrayList<FoodRegion>()
        var crops = 0
        bitmaps.forEachIndexed { photoIndex, bitmap ->
            val letterbox = Letterbox.of(bitmap.width, bitmap.height, input.width)
            val raw = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            regionEngine.run(letterboxInput(bitmap, input), raw)
            val flat = FloatArray(channels * anchors)
            for (c in 0 until channels) for (a in 0 until anchors) {
                flat[c * anchors + a] = if (channelFirst) raw[0][c][a] else raw[0][a][c]
            }
            val hits = FoodRegions.tidy(
                FoodRegions.decode(flat, channels, anchors, letterbox, bitmap.width, bitmap.height),
                bitmap.width, bitmap.height,
            )
            for (hit in hits) {
                val scores = classScores(foodEngine, cropInput(bitmap, hit.box, inputGeometry(foodEngine)))
                val top = labels.indices
                    .filter { !labels[it].startsWith("#") }
                    .sortedByDescending { scores[it] }
                    .take(FoodRegions.TOP_PER_REGION)
                    .map { labels[it] to scores[it] }
                val box = hit.box
                regions += FoodRegion(
                    id = regions.size,
                    photoIndex = photoIndex,
                    box = PixelBox(box.left / bitmap.width, box.top / bitmap.height, box.right / bitmap.width, box.bottom / bitmap.height),
                    regionScore = hit.score,
                    top = top,
                )
                crops++
            }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        // 실기기에서 "그릇은 찾았는데 이름이 틀렸나 / 그릇을 못 찾았나" 를 가리려고 자리마다 상위 후보를 남긴다.
        Log.d(
            TAG,
            "2단계 ${bitmaps.size}장 · 자리 ${regions.size}곳 · 이름 ${regions.count { it.name != null }}곳 · ${elapsedMs}ms · " +
                regions.joinToString(" | ") { r -> "#${r.photoIndex}:" + r.top.joinToString { "${it.first} ${"%.2f".format(it.second)}" } },
        )
        return regions
    }

    /**
     * .tflite 는 AGP 기본 noCompress 목록에 있어 보통 mmap 으로 열린다(힙 복사 없음).
     * 압축돼 들어간 빌드면 openFd 가 실패하므로 힙으로 읽는 경로로 내려간다. 파일 자체가 없으면 FileNotFoundException.
     */
    private fun openModel(context: Context, path: String): ByteBuffer {
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

    private fun runInference(engine: Interpreter, bitmap: Bitmap): Map<String, Float> {
        val best = classScores(engine, letterboxInput(bitmap, inputGeometry(engine)))
        // 임계값과 무관하게 상위 5개를 남긴다 — "왜 못 잡았나"(근소 미달 vs 엉뚱한 클래스)를 실기기 로그로 가리기 위해서다.
        val ranked = labels.indices
            .filter { !labels[it].startsWith("#") }
            .sortedByDescending { best[it] }
            .take(5)
            .joinToString { "${labels[it]} ${"%.2f".format(best[it])}" }
        Log.d(TAG, "전체 사진 1회 · 상위 점수: $ranked (임계 $CONFIDENCE_THRESHOLD)")

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

    /**
     * Ultralytics 학습·평가와 같은 letterbox: 비율을 유지해 맞추고 남는 영역은 회색(114)으로 채운다.
     * 정사각형으로 늘리면(stretch) 학습 분포와 달라져 confidence 가 떨어진다.
     */
    private fun letterboxInput(bitmap: Bitmap, input: InputGeometry): ByteBuffer {
        val scale = minOf(input.width.toFloat() / bitmap.width, input.height.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return toInputBuffer(input) { canvas ->
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true),
                ((input.width - scaledW) / 2).toFloat(),
                ((input.height - scaledH) / 2).toFloat(),
                null,
            )
        }
    }

    /** 2단계 입력: [box] 자리만 잘라 회색 캔버스 가운데에 [FoodRegions.cropPlacement] 크기로 그린다. */
    private fun cropInput(bitmap: Bitmap, box: PixelBox, input: InputGeometry): ByteBuffer {
        val size = minOf(input.width, input.height)
        val dest = FoodRegions.cropPlacement(box, size)
        val offsetX = (input.width - size) / 2f
        val offsetY = (input.height - size) / 2f
        return toInputBuffer(input) { canvas ->
            // 원본 좌표는 버림한다 — 실험 코드(crop_gray 의 int())와 같다. 1픽셀 미만 차이다.
            canvas.drawBitmap(
                bitmap,
                Rect(box.left.toInt(), box.top.toInt(), box.right.toInt().coerceAtLeast(box.left.toInt() + 1), box.bottom.toInt().coerceAtLeast(box.top.toInt() + 1)),
                RectF(dest.left + offsetX, dest.top + offsetY, dest.right + offsetX, dest.bottom + offsetY),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    // 입력 캔버스·픽셀·버퍼를 크기별로 한 벌만 두고 돌려 쓴다. 2단계는 자리마다(사진 5장 × 최대 20곳) 입력을 만드는데,
    // 매번 새로 만들면 1회에 약 6.5MB 씩 할당돼 저사양 기기에서 GC·메모리 부족을 부른다. [lock] 안에서만 쓰고,
    // run() 이 버퍼를 다 읽은 뒤에 다음 입력을 만들므로 한 벌로 충분하다.
    private var scratchBitmap: Bitmap? = null
    private var scratchPixels: IntArray? = null
    private var scratchBuffer: ByteBuffer? = null

    /** 회색으로 채운 입력 크기 캔버스에 [draw] 로 그린 뒤 0~1 float 버퍼로 옮긴다. 반환 버퍼는 다음 호출에서 덮어써진다. */
    private inline fun toInputBuffer(input: InputGeometry, draw: (Canvas) -> Unit): ByteBuffer {
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
        if (input.channelsFirst) {
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
