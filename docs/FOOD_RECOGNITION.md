# 음식 인식 (사진 식단 기록) — 설계 근거

사진 한 장(또는 갤러리 최대 5장)에서 **무슨 음식인지**를 기기 안에서 알아내고, 사용자가 확인·수정한 뒤 현재 끼니에 저장한다. 양(그램·인분) 추정과 영양 DB 연동은 범위 밖이다.

## 흐름

```
MainSheet.Photo → PhotoFoodSheet
  Pick(촬영 / 갤러리 / 직접 입력)
  → Camera(CameraX ImageCapture)  또는  Photo Picker(≤5장)
  → Analyzing: Dispatchers.Default 에서 FoodDetector.detect()
  → Result: 끼니 SegmentedTabs · 항목별 qty StepperControl · "확신 NN%" · 근사치 고지
       → appendFoods(0, slot, entries)  (직접 기록 시트와 같은 저장 경로)
  → Failed: 원인별 제목/문구 (검출 0건 · 모델 부재 · 분석 오류) + 다시 촬영/선택 · 직접 입력
```

| 위치 | 내용 |
|---|---|
| `app/src/main/java/com/example/trex_kotlin/PhotoFoodSheet.kt` | 시트 UI·단계 상태·저장 |
| `app/src/main/java/com/example/trex_kotlin/food/FoodDetector.kt` | 모델 로딩(mmap)·letterbox 전처리·추론·라벨 매핑, 이미지 디코딩/회전 헬퍼 |
| `app/src/main/assets/models/yolov8n_food.tflite`, `food_labels.txt` | AI Hub 한식 이미지로 파인튜닝한 YOLOv8n(30클래스), float32 입출력 |
| `TrexData.kt` `foodDatabase` | 라벨 30종과 1:1 — 1인분 기준 **근사** 영양값(식약처 DB 연동 전 임시) |
| `training/train_food_yolov8_colab.ipynb` | 학습·TFLite 변환·앱 호환성 검증 노트북 |

## 왜 이렇게 했나

- **온디바이스만.** 사진은 네트워크로 나가지 않고 디스크에도 쓰지 않는다(촬영은 메모리 `ImageProxy`, 갤러리는 읽기 전용 Picker). 저장소에 INTERNET 권한이 없고, 화면 문구("기기 안에서만 분석")가 코드와 일치한다.
- **시뮬레이션 결과 없음.** 예전 스텁은 고정 음식을 결과처럼 보여줬다. 지금은 모델이 없거나 추론이 실패하면 결과를 만들지 않고 원인별 실패 화면으로 간다(CLAUDE.md 원칙 1 — 판정하지 않은 것을 판정처럼 말하지 않는다).
- **confidence 를 그대로 보여준다.** 0.40 미만은 결과에 넣지 않고, 넣은 항목에는 "확신 NN%"를 붙인다. 이 값은 보정되지 않은 raw score 라 확률로 읽으면 안 되지만, 숨기는 것보다 드러내는 쪽이 원칙에 가깝다고 봤다.
- **영양값은 근사치라고 화면에 적는다.** 30종 값은 큐레이션된 기존 9종과 같은 확신으로 표시하면 안 된다(원칙 2). 결과 화면 배너로 고지하고, 식약처 영양 DB 연동 때 교체한다.
- **양은 qty 스테퍼로 통일.** 조원 브랜치(`feature/food`)의 인분(0.5~5) 슬라이더는 채택하지 않았다 — 직접 기록 시트가 이미 qty(정수)를 쓰고 있어 사진 기록만 다르게 가면 저장 구조가 둘로 갈린다. 인분이 필요하면 두 시트를 함께 바꾼다.
- **클래스별 최고 confidence, NMS 없음.** 현재 UX는 "무슨 음식인지"만 쓰므로 박스 좌표가 필요 없다. 같은 음식이 여러 개 있어도 1개로 잡히며, 수량은 사용자가 스테퍼로 맞춘다.
- **letterbox 전처리.** Ultralytics 학습·평가와 같은 방식(비율 유지 + 회색 114 패딩). 정사각형으로 늘리면 학습 분포와 달라져 confidence 가 떨어진다.
- **메모리 정책.** 촬영 원본은 축소 후 회전(원본 크기 복사본을 만들지 않는다), 갤러리는 스트림을 한 번만 읽어 디코딩·EXIF 에 같이 쓰고 최대 변 1280px 로 정확히 맞춘다. 화면에는 첫 장만 보이므로 나머지 비트맵은 상태에 두지 않는다. 모델은 mmap 으로 열고 시트를 열 때 예열한다.
- **카메라 실패는 앱을 죽이지 않는다.** 후면 카메라 없음·다른 앱 점유·촬영 실패는 각각 배너로 안내하고 갤러리/직접 입력으로 우회한다.

## 미검증·한계 (2026-09-16)

- **실기기 촬영·인식 정확도 미검증.** JVM 테스트와 빌드만 통과했다. 모델의 mAP 는 학습 노트북 출력에만 있고 저장소에 기록되지 않았다.
- 학습 데이터가 클래스 폴더 기준(사진 1장 = 음식 1종)이면 한 접시에 여러 반찬이 있는 사진의 다중 인식은 약할 수 있다. AI Hub 74번(박스 어노테이션 포함)으로 재학습하면 나아진다 — 노트북 4번 셀 재작성 필요.
- 임계값 0.40 은 초기값이다. 오탐/미탐 사례가 쌓이면 조정한다.
- CAMERA 권한 요청 블록이 `PostureLive`·`BaselineGuideScreen`·`PostureLabScreen` 과 중복된다. 공용 `rememberCameraPermission()` 으로 묶는 것이 맞지만 자세 화면을 건드리는 작업이라 이 파트에서 하지 않았다.

## 모델 교체 절차

`training/train_food_yolov8_colab.ipynb` 실행 → `yolov8n_food.tflite` + `food_labels.txt` 를 `assets/models/` 에 덮어쓰기 → `foodDatabase` 에 새 라벨의 영양값 추가(라벨명 = DB 키) → 빌드. `FoodDetector` 는 입력 float32·출력 `[1, 4+클래스수, 박스수]` 를 로드 시 검사하므로 형식이 어긋나면 로그에 이유가 남는다.
