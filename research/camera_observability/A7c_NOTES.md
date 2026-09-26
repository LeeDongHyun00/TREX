# A7c — MediaPipe 가 발 랜드마크를 만드는 경로 (코드·문서·이슈·문헌)

작성 2026-09-25. 앱 전제: `tasks-vision 0.10.14`(`gradle/libs.versions.toml`), `pose_landmarker_full.task`, `RunningMode.VIDEO`, numPoses 1,
detection/presence/tracking 0.5, 세그멘테이션 off(`PostureAnalyzer.kt:121-131`), 480×640 세로, 300 ms 간격(`InferencePolicy.kt:21`).
소스는 **v0.10.14 태그 원문**을 받아 읽었다(요약 아님). 수치 중 "직접 계산" 표시는 코드 파라미터로 계산한 것이지 기기 측정이 아니다.

앱의 `toe_out` 은 이미지 2D 가 아니라 **월드 3D** 발목→발끝(31/32) 벡터를 중력 수평면에 눕힌 `atan2(x, z)` 다(`PostureCore.kt:275-281`).
따라서 아래 Q3 의 "z 는 관측이 아니라 합성 피팅" 이 이 검사에 직접 걸린다.

## Q1. VIDEO 모드의 시간 평활 — 있고, 기본 ON, 앱에서 못 끈다 (확신 높음: 코드)

- `pose_landmarker_graph.cc`: `num_poses == 1` 이면 `smooth_landmarks = base_options.use_stream_mode()`. Java `PoseLandmarker.java:486`
  이 `useStreamMode = runningMode != IMAGE` 로 넣는다 → VIDEO/LIVE_STREAM 은 **항상 평활**. `PoseLandmarkerOptions.Builder` 에 smooth 스위치가
  없고 공식 Android 가이드 옵션 표에도 없다. 끄는 유일한 길은 IMAGE 모드(= 트래킹도 꺼지고 매 프레임 검출기 224×224 가 돈다).
- 파라미터(`pose_landmarks_detector_graph.cc` MultiplePoseLandmarksDetectorGraph 끝):
  - 정규화 랜드마크: OneEuro `min_cutoff 0.05, beta 80, derivate_cutoff 1.0`, 값 스케일 = 1/ROI 픽셀 크기(`OBJECT_SCALE_ROI` = 다음 프레임 ROI).
  - 월드 랜드마크: `0.1 / 40 / 1.0`, 스케일 없음(미터). 가시성: LowPass **alpha 0.1, 프레임 기반**(presence 는 안 건드림).
  - 보조 랜드마크(ROI 용 33·34)는 **평활하지 않는다**(레거시 `pose_landmark_filtering.pbtxt` 는 0.01/10 으로 강하게 했었다).
- 주파수: `one_euro_filter.cc::Apply` 가 타임스탬프 차이로 `frequency_` 를 매번 재계산 → 300 ms 면 3.33 Hz. 정적일 때 alpha =
  1/(1+τ/Δt), τ = 1/(2π·0.05) = 3.18 s → **0.086/프레임**(시상수 ≈ 3.2 s, 프레임률과 무관). 속도항은 (Δpx/ROI)·f 라 물리 속도 기준.
- 스텝 응답 시뮬레이션(직접 계산, ROI 500 px, 300 ms 간격; 값 = 반영 비율, k 프레임 뒤):

  | 스텝 | 1 | 2 | 3 | 4 | 8 |
  |---|---|---|---|---|---|
  | 1 px(발끝 ≈3°) | 0.43 | 0.57 | 0.63 | 0.67 | 0.77 |
  | 2 px | 0.58 | 0.73 | 0.79 | 0.81 | 0.87 |
  | 7 px(≈20°) | 0.82 | 0.93 | 0.96 | 0.97 | 0.98 |
  | 1프레임 10 px 스파이크 | 8.7 px 통과 → 1.6 → 0.6 | | | | |

  → 큰 회전(≥7 px)은 1–2 프레임 안에 따라오고, **작은 회전(1–2 px)은 1 초 뒤에도 57–73 %** 만 반영, 나머지는 수 초. 단일 프레임 이상치는 거의
  그대로 통과한다(OneEuro 는 속도가 크면 컷오프를 올리므로 튐을 못 막는다). 가시성은 alpha 0.1 → 63 % 반영에 9.5 프레임 = **2.9 s** — 앱의
  `min(visibility, presence) ≥ 0.5` 게이트(`PostureAnalyzer.kt:27,202`)에서 visibility 쪽이 3 초 지연된 값이다.
- 역사: 0.10.0–0.10.6 은 평활 없음(#4507 2023-06, #4670 2023-08), 0.10.7 릴리스 노트 "Smooth pose landmarks" / "Fixed Pose Landmarker
  jittering issue". #4507 은 아직 open(JS 에서 여전히 지터 보고). 0.10.14 는 포함.

## Q2. ROI 추적 — 검출기 생략 조건·기하·발 픽셀 (확신 높음: 코드 + `.task` 파싱, 픽셀 수치는 직접 계산)

- 스트림 모드: `PreviousLoopbackCalculator` 가 이전 프레임의 `pose_rects_next_frame` 을 되돌리고, 개수 ≥ numPoses 면 `DisallowIf` 로
  검출기를 **생략**한다. 타임아웃 없음 — 300 ms 든 3 s 든 이전 처리 프레임의 ROI 를 쓴다.
- 추적 상실 기준은 `min_tracking_confidence` 가 **아니다**. 랜드마크 모델의 pose_flag(presence) 를 `ThresholdingCalculator` 가
  `minPosePresenceConfidence`(0.5) 로 자르고, 미달이면 GateCalculator 가 텐서를 막아 그 프레임 랜드마크·ROI 가 없고 다음 프레임에 검출기가
  돈다. `min_tracking_confidence` 는 `AssociationNormRectCalculator.min_similarity_threshold`(IoU) 로만 쓰인다 — 문서 문구("tracking 성공
  최소 신뢰")와 다르다.
- ROI 기하: 보조 랜드마크 33(엉덩이 중점)·34(몸 외접원 반지름 점, BlazePose 논문 §2.2·§2.6) → 박스 한 변 = 2×거리(`AlignmentPointsRects`),
  33→34 가 90° 되게 회전, ×1.25, 정사각(`RectTransformation`). 모델 텐서(`.task` 안 tflite 파싱): 검출기 입력 224×224; 랜드마크 입력
  **256×256**, 출력 195(=39×5), 1(flag), 256×256×1(seg), **64×64×39 히트맵**, 117(=39×3 월드). 히트맵 정제 kernel 7, min_conf 0.5(모든 점 동일).
  전처리는 keep_aspect_ratio 레터박스.
- 발 픽셀(직접 계산): 몸 0.62×640 = 397 px, 서 있을 때 엉덩이→발 ≈ 210 px 가 외접 반지름 → ROI ≈ 2×210×1.25 = **525 px**(프레임 폭 480 보다
  커서 좌우는 0 패딩). 축척 256/525 = 0.49 → 발 15–25 px → **크롭 7–12 px**, 히트맵 셀 1 개 = 4 크롭 px = 8 프레임 px → 발 **1.8–3 셀**.
  바닥 자세에서는 반지름이 줄어 ROI ≈ 300–375 px → 발 13–21 크롭 px. 즉 **발끝을 재는 서 있는 구간이 가장 저해상도**다.
- 300 ms 어긋남: ROI 중심 = 이전 프레임 엉덩이. 하강 0.5 m/s 면 300 ms 에 ≈35 px, 발 아래 여유 ≈ 52 px → 발 잘림은 계산상 없다. 다만 ROI 가 매
  프레임 **raw 보조점**으로 다시 정해지므로 크롭 위치·축척 자체가 흔들리고 발 좌표가 같이 흔들린다(모델카드: 10 % 이동/축척, 8° 롤 허용).

## Q3. 발 랜드마크의 출처 (모델 구조 확신 높음 · 데이터 품질은 공개 정보 없음 = 미확인)

- 33점 + 보조점 = 39점을 **한 회귀 헤드**(195)와 히트맵 헤드(64×64×39)로 낸다. 발 전용 처리는 그래프에 없다. 모델카드: z 는 "GHUM 합성 데이터를
  2D 주석에 피팅해 얻음, **not metric, up to scale**"; 월드 좌표도 같은 GHUM 리프터(GHUM Holistic 2022: 2D 주석 + 깊이 순서 주석으로 피팅,
  MPJPE 121 mm / PA 78 mm). 즉 **발끝의 z(앞뒤)는 관측이 아니라 프라이어**다 — 앱 `toe_out` 의 분모가 이 z 다.
- 학습 데이터: 60K 일반 + 25K 피트니스, 사람 주석(BlazePose 2020); 모델카드는 85K 피트니스 + 30K 동의 AR 사진. "정의하기 어려운 점은
  **best guess 와 default pose** 로 주석". 발 회전 다양성·발 주석 품질 언급은 어디에도 없다(미확인). 평가는 PDJ@0.2 몸통 지름(≈ 발 길이보다 큰
  허용) — 발 각도 정확도는 보고된 적 없다. 토폴로지 목적: "face, hands, feet 에 회전·크기·위치 추정에 최소한의 점"(논문 §2.3).
- GitHub 이슈(검색 8회): #3496(2022-07, 트레드밀 달리기 뒷발 heel/toe 어긋남 — 답변 없이 닫힘), #4281(2023-04, Google 담당자:
  "mediapipe detects feet at right angle of the legs" — 가려진 발은 다리와 직각으로 붙는 프라이어), #2203(2021, z 편향). "발이 다리 방향을
  따라간다/붙는다" 는 정확한 문구의 이슈는 **못 찾음**. 커뮤니티 근거: ju1ce VR 트래커 v0.2 "Foot rotation! Doesn't work perfectly and not at
  all when you aren't facing the camera", v0.6.1 "feet were flipping direction if smoothing window was disabled".

## Q4. 정면 2D 로 FPA 를 재는 타당도 (문헌 확신 중간 — 직접 검증 연구는 못 찾음)

- FPA 정의 = **바닥면**에서 발축(종골→2중족골두)과 진행 방향의 각, 기준은 광학 마커 3D(Wouda 2021 JNER; IMU 대안 오차 ≤2.6°). 카메라 1대
  2D 로 FPA 를 검증한 연구·MediaPipe 로 FPA 를 잰 연구는 **못 찾음**. 2D 발 영상 연구는 뒤/앞에서 rearfoot 내·외번을 잰다.
- Wade 2023 PLOS One(OpenPose, 정면 카메라, 보행): 정면 발목각 bias 1.6 ± 4.2°, LoA **±12.3°**, "should not currently be used in clinical or
  sporting applications". 무릎·엉덩이는 마커 수준.
- Yamamoto 2022 Sci Rep(OpenPose, 시상면 3 m·대전자 높이): FPA 50° 조건에서 발목 MAE 배굴 7.4→12.3°, 저굴 5.2→**17.3°** — 평면 밖 회전이
  오차를 키운다. 권장 위치는 "위에서" 라기보다 FPA 자체가 바닥면 양이라 **바닥에 수직인 시점(위)** 이나 3D 가 필요하다는 뜻.
- 원근(직접 계산, 핀홀): 카메라 높이 H, 발이 광축에서 옆으로 X 떨어져 **똑바로 앞을 향해도** 이미지에서는 소실점 방향으로 기울어 보인다.
  카메라가 수평이면 이미지 각 = atan(X/H) (거리·초점거리 무관). 엉덩이(0.9 m)를 조준한 경우:

  | H \ X | 0.10 | 0.20 | 0.30 | 0.40 |
  |---|---|---|---|---|
  | 0.3 m | 18° | 33° | 44° | 52° |
  | 0.6 m | 9° | 18° | 26° | 33° |
  | 0.9 m | 6° | 13° | 18° | 24° |

  관측 (1) "넓게만 서면 +20~35°" 와 부호·크기가 맞는다(폰 0.6 m 에서 X 0.15→0.40 m 는 +9→+33°). 이미지 x 가 이렇게 밀리면 모델이 월드 x 도
  같이 밀고 z 는 프라이어라, 월드 `atan2(x,z)` 도 벌어진다 — 인과는 추정(데이터로 볼 것: 두 발 대칭·|발목 x − 화면 중심| 과 상관).
  같은 프레이밍에서 20° 실제 발끝 회전의 이미지 가로 이동 ≈ 20 px(≈1 px/°)이고 똑바른 발의 이미지 길이는 H 0.3–1.2 m 에서 7–34 px 뿐이다.

## Q5. 발 랜드마크 없이 / 다르게 보는 방법 — 근거·한계

| 후보 | 근거 | 한계 · 비용 |
|---|---|---|
| (a) **이미지 2D + 소실점 보정** — 발(발목→발끝) 이미지 벡터를 화면 수직 대신 "발 위치→전방 소실점 V" 방향과 비교. V 는 IMU 피치 φ 로 v_V = −f·tanφ, 폰 f 는 Camera2 특성 | 바닥 평행선은 모두 V 로 수렴 → 넓은 스탠스의 가짜 벌어짐이 1차로 사라진다(H·D 불필요). 가로 성분만 쓰면 감도 ≈ f·L/D ≈ 1 px/° | 사람이 카메라를 비스듬히 보면 V 가 돈다(뷰 추정 `view_cos/sin` 로 보정 필요). 발 길이 L 가정(0.25 m; 발목 기준이면 ~0.18) |
| (b) 발 길이 단축(뒤꿈치–발끝 이미지 길이) | 요(yaw) 에 따라 세로 성분이 변함 | 세로 성분이 H 에 크게 좌우(7–34 px)되고 뒤꿈치가 91 % 안 보임(RepForm 주석) → 약함 |
| (c) 세그멘테이션 마스크 주축 | `output_segmentation_masks=true` 로 256×256 마스크를 WarpAffine 으로 원본 크기에 되돌려 줌(코드), 0.10.14 Tasks 는 마스크 시간 평활 없음 | 마스크 유효 해상도 = 크롭 2 프레임 px/셀 → 발 8–13 마스크 px, 정강이·반대발·그림자 분리 필요; 추가 비용은 미측정(미확인) |
| (d) 정강이 회전(무릎 x 대 발목 x) | 이미 `knee_out` 로 있음 | 엉덩이 회전·외반 신호지 발 요가 아님 — 발끝만 돌리면 안 움직인다 |
| (e) IMAGE 모드 A/B | 평활·트래킹 모두 꺼져 프레임이 독립 → "발이 이전 값에 끌리는가" 를 분리해 볼 수 있음 | 매 프레임 검출기 추가(3.3 fps 라 감당 가능) · ROI 가 검출기 4 키포인트 기반이라 축척이 달라짐 |

정리: (1) 은 원근 기하로 대부분 설명되고 MediaPipe 결함이 필요 없다(가설, 데이터 확인 필요). (2) "실제 벌린 반복이 정상" 은 300 ms OneEuro 의
작은 변화 지연(1–2 px 는 1 초 뒤 57–73 %)·발 7–12 크롭 px·z 프라이어 셋 중 무엇이든 가능하며 로그의 좌표(`xy`)로 (a) 를 재계산해 보는 것이 가장
싼 검증이다.

## 출처
- 코드(v0.10.14): `mediapipe/tasks/cc/vision/pose_landmarker/pose_landmarker_graph.cc`, `pose_landmarks_detector_graph.cc`,
  `proto/pose_landmarker_graph_options.proto`, `mediapipe/framework/api2/stream/smoothing.cc`, `mediapipe/util/filtering/one_euro_filter.cc`,
  `calculators/util/landmarks_smoothing_calculator{.proto,_utils.cc}`, `visibility_smoothing_calculator.cc`,
  `refine_landmarks_from_heatmap_calculator{.cc,.proto}`, `alignment_points_to_rects_calculator.cc`,
  `modules/pose_landmark/pose_landmark_filtering.pbtxt`, `tasks/java/.../poselandmarker/PoseLandmarker.java` —
  https://github.com/google-ai-edge/mediapipe/tree/v0.10.14
- 이슈: https://github.com/google-ai-edge/mediapipe/issues/4507 · /issues/4670 · /issues/3496 · /issues/4281 · /issues/2203 ;
  릴리스 https://github.com/google-ai-edge/mediapipe/releases/tag/v0.10.7 ; 가이드
  https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android
- 모델카드 https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf ; BlazePose https://arxiv.org/abs/2006.10204 ;
  GHUM Holistic https://arxiv.org/abs/2206.11678
- ju1ce https://github.com/ju1ce/Mediapipe-VR-Fullbody-Tracking/releases
- Wade et al. 2023 https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0293917 ; Yamamoto et al. 2022
  https://pmc.ncbi.nlm.nih.gov/articles/PMC9586966/ ; Wouda et al. 2021 https://pmc.ncbi.nlm.nih.gov/articles/PMC7888122/
- 로컬: `app/src/main/assets/posture/pose_landmarker_full.task`(tflite 텐서 파싱), `PostureCore.kt`, `PostureAnalyzer.kt`, `RepForm.kt`
