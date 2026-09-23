# 음식 인식 모델

- `yolov8n_food.tflite` — AI Hub 한식 이미지로 파인튜닝한 YOLOv8n의 TFLite 변환본(2026-09-09, 30클래스).
  입출력은 float32여야 하며(`FoodDetector`가 로드 시 검사), 학습·변환 절차는 `training/train_food_yolov8_colab.ipynb`.
- `food_labels.txt` — 모델 클래스 인덱스 순서와 정확히 같은 라벨 목록(한 줄에 하나). 첫 줄의 UTF-8 BOM은 로더가 제거한다.
  `#`으로 시작하는 줄은 음식이 아닌 클래스로 취급되어 인식 결과에서 제외된다.
- 라벨 이름은 `TrexData.kt`의 `foodDatabase` 키와 1:1로 맞춰 영양 정보를 찾는다. 모델을 교체하면 두 파일과 DB를 함께 갱신한다.

추론은 전부 온디바이스에서 수행되고 사진 원본은 기기 밖으로 전송하지 않는다.
