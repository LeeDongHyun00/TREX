package com.example.trex_kotlin

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter

/**
 * AI 모델이 이미지를 분석하여 '어떤 음식인지' 판별한 결과를 담습니다.
 * 기획 변경에 따라 면적 계산이 빠졌으므로, 인식된 음식의 이름(String)만 반환합니다.
 */
data class AnalysisResult(
    val isSuccess: Boolean,
    val detectedFoodName: String? = null,
    val errorMessage: String = ""
)

class YoloSegmentationHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    
    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            // TODO: 실제 프로젝트에서는 assets 폴더에 넣은 TFLite 모델을 로드합니다.
            // val modelBuffer = FileUtil.loadMappedFile(context, "yolo_model.tflite")
            // interpreter = Interpreter(modelBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 촬영된 사진(Bitmap)을 분석하여 음식 종류를 알아냅니다.
     */
    fun analyzeImage(bitmap: Bitmap): AnalysisResult {
        return try {
            // TODO: 실제 TFLite 추론 코드가 들어갈 자리입니다.
            // 현재는 UI 및 로직 테스트를 위해 임의로 "닭가슴살"을 찾았다고 가정한 더미 데이터를 반환합니다.
            
            AnalysisResult(
                isSuccess = true,
                detectedFoodName = "닭가슴살" // AI가 찾은 음식 이름
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnalysisResult(isSuccess = false, errorMessage = "음식 인식에 실패했습니다.")
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}