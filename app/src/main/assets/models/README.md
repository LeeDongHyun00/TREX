# 음식 인식 모델

- `yolov8n_food.tflite` — AI Hub 한식 이미지로 파인튜닝한 YOLOv8n의 TFLite 변환본(2026-09-09, 30클래스).
  입출력은 float32여야 하며(`FoodDetector`가 로드 시 검사), 학습·변환 절차는 `training/train_food_yolov8_colab.ipynb`.
- `food_labels.txt` — 모델 클래스 인덱스 순서와 정확히 같은 라벨 목록(한 줄에 하나). 첫 줄의 UTF-8 BOM은 로더가 제거한다.
  `#`으로 시작하는 줄은 음식이 아닌 클래스로 취급되어 인식 결과에서 제외된다.
- `food_region.tflite` — **음식 위치 모델**(2단계 인식의 1단계, 2026-09-27). YOLOE-11S 탐지 전용에 고정 어휘("food, bowl of food, plate of food, dish, bowl, plate, rice, soup, side dish")를 넣어
  ONNX → onnx2tf 로 바꾼 8비트 동적 양자화본(9.8MB). 입력 [1,640,640,3] float32, 출력 [1,13,8400](좌표 4 + 어휘 9). 무슨 음식인지는 모르고 "여기 음식·그릇이 있다"만 낸다.
  이 자리를 잘라 `yolov8n_food.tflite` 가 이름을 붙인다. 없으면 앱은 전체 사진 1회 인식으로 돌아간다.
  만드는 법은 `training/two_stage_eval/export_yoloe_det.py` → `onnx_to_tflite.py`, 근거·측정은 `docs/FOOD_EVAL_RESULTS.md` §8·§8.1·§8.2. 라이선스 AGPL-3.0(ultralytics 배포, YOLOv8 과 같다).
- `food_embed.tflite` — **내 음식 기억의 특징값 모델**(2026-09-27). DINOv2-small(ViT-S/14, timm `vit_small_patch14_dinov2.lvd142m`)을 224 입력으로 ONNX → onnxslim → onnx2tf 로 바꾼 8비트 동적 양자화본(22.7MB). GELU 는 tanh 근사로 바꿔 내보냈다(정확한 GELU 의 Erf 가 Flex 연산이 돼 폰에서 로딩이 실패했다). 원래 모델 대비 코사인 평균 0.998, 폰 CPU 28ms.
  입력 [1,224,224,3] ImageNet 정규화(짧은 변 224 + 가운데 자르기), 출력 [1,384]. 사용자가 자리에 직접 붙인 이름을 이 특징값과 함께 `files/food_memory.tsv` 에 남기고 다음 사진에서 견준다.
  기억이 하나도 없으면 불러오지 않는다. 만드는 법은 `training/two_stage_eval/export_dinov2.py`, 근거는 `docs/FOOD_EVAL_RESULTS.md` §9. 라이선스 Apache-2.0(DINOv2).
- 라벨 이름은 `TrexData.kt`의 `foodDatabase` 키와 1:1로 맞춰 영양 정보를 찾는다. 모델을 교체하면 두 파일과 DB를 함께 갱신한다.

추론은 전부 온디바이스에서 수행되고 사진 원본은 기기 밖으로 전송하지 않는다.
