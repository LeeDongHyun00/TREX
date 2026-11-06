// Android 정본 동기화 — tools/sync_ios_core.py
package com.example.trex_kotlin.food

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
