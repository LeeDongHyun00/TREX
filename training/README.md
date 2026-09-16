# 모델 학습 스크립트

앱에 탑재할 온디바이스 AI 모델을 만드는 코드 모음. 학습은 Colab에서 하고, 산출물(.tflite)만 앱 assets에 넣는다 — 서버에는 어떤 AI 코드도 두지 않는다 (CLAUDE.md).

- `train_food_yolov8_colab.ipynb` — AI Hub "한국 음식 이미지" 데이터셋으로 YOLOv8n을 파인튜닝하고
  `yolov8n_food.tflite`(INT8, 입출력 float32) + `food_labels.txt`를 생성하는 Colab 노트북.
  사용법은 노트북 첫 셀 참조. 산출물은 `app/src/main/assets/models/`에 덮어쓴다.
