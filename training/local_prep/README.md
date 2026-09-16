# AI Hub 74번 → YOLO 데이터셋 로컬 전처리

AI Hub 는 해외 IP 다운로드를 막아 Colab 에서 직접 받을 수 없다. 이 폴더의 도구로 **한국 PC** 에서 받아 640px 로 줄인 뒤
`dataset.zip`(3~5GB)만 Google Drive `MyDrive/trex/dataset.zip` 에 올리고, Colab 노트북(`../train_food_yolov8_colab.ipynb`)은 학습만 한다.

## 데이터 구조 (2026-09-16 확인)
- 원천: 12MP JPEG(3024×4032). Validation 원천은 대분류별 7묶음(`01`, `02_03_05`, `04`, `06_07_08`, `09_10`, `11`, `12_13_14_15_16`, 15~31GB씩)이라 음식군을 골라 받을 수 있다. Training 원천은 50덩어리(1.4TB)로 클래스와 무관하게 쪼개져 있어 쓰지 않는다.
- 라벨(`음식분류_라벨링_VAL_1223_add.zip`, 56MB): `txt/<8자리 코드>/<대분류>_<중분류>_<코드>_<id>.txt` — **이미 YOLO 형식**. 한 파일에 `0 …`(그릇) + `1 …`(음식) 두 줄. `xml/` 에 같은 내용의 VOC XML(width/height 포함). 클래스 400종, 클래스당 약 200장.
- 코드 → 음식명·영양: `44.음식분류 AI 데이터 영양DB.xlsx` 의 행 순서가 코드 정렬 순서와 1:1 (2번째 행 `-` 만 제외). `build_class_map.js` 가 대분류 경계 15곳을 출력해 검증한다 → `class_map.csv`(index, code, name, 1인분 중량, kcal, 탄/당/지/단, 나트륨).
- 대분류: 01 밥 · 02 면/만두 · 03 죽/스프 · 04 국/탕/찌개 · 05 찜 · 06 구이 · 07 전 · 08 볶음 · 09 조림 · 10 튀김 · 11 무침/나물 · 12 김치 · 13 젓갈/장 · 14 회 · 15 떡 · 16 한과.

## 절차
1. `aihubshell` 로 라벨 zip(44878)·영양DB(44887) 다운로드 → 해제. 키는 aihubshell 안내 페이지에서 발급(이메일).
2. `node build_class_map.js` → `class_map.json/csv`.
3. `bash pipeline.sh <API키>` — 묶음을 순서대로 다운로드 → 해제 → `prep_dataset.js add` → 원본 삭제. 속도가 약 0.7MB/s 라 묶음당 6~12시간, 전체 3일. `pipeline.log` 로 진행 확인. `images_<이름>/` 에 zip 을 직접(Innorix) 넣어 두면 다운로드를 건너뛴다.
4. `node prep_dataset.js finalize` → `dataset_final/`(포함된 클래스만 0..nc-1 재배열, data.yaml, food_labels.txt, nutrition.json).
5. `dataset_final` 을 zip → Drive 업로드 → Colab.

## 변환 규칙
- 그릇 박스(클래스 0)는 버리고 음식 박스만 전역 index 로 쓴다. EXIF 회전을 적용한 표시 크기가 XML 크기와 같은지 처음 5장으로 검사한다(어긋나면 중단).
- 최대 변 640px, JPEG 85. train/val 은 클래스별 15% 를 seed 로 고정 분할.
- 앱 `foodDatabase` 는 `nutrition.json`(1인분 기준 실제 값)으로 갱신한다 — 지금 앱의 30종 근사값 대체.
