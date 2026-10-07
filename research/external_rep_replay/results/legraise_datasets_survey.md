# 레그 레이즈 — 연속 영상 대체 데이터 조사

조사일 2026-10-07. 앞선 조사 `rep_datasets_survey.md`(2026-09-24)에 이어서 했다. 문제는 이렇다. AIHub '라잉 레그 레이즈' 416클립은 클립당 16프레임이 여러 렙에 걸쳐 성기게 뽑혀 있어 연속 동작이 아니다. MM-Fit·REHAB24-6 에는 레그 레이즈가 없다.

**✓** 표시는 이 PC 에서 직접 확인한 값이다. 나머지는 논문·공식 페이지를 인용한 값이다.

## 0. 결론

- **누워서 양다리를 드는 레그 레이즈의 연속 영상은 공개 데이터에 사실상 없다.** 반복 정답이 붙고 상업 이용까지 되는 것은 더더욱 없다.
- 대신 **누워서 한 다리를 곧게 드는 동작(ASLR/SLR)** 은 두 곳에 있다.
  - **FMS 데이터셋**: CC0 ✓ 이고, 45명이 바닥 매트에서 했다.
  - **UCO 재활 데이터셋**: 치료대 위에서 4회를 연속으로 했고, OptiTrack 정답이 있다. 이메일로 신청해야 한다.
- 양다리 동작은 Kaggle 영상 21개뿐이다. 유튜브가 출처이고 CC BY-NC-SA 라 검증에만 쓴다.
- 레그 레이즈는 C 등급이다. 완료 기준이 "폰 한 세트로 횟수 확인"(`docs/EXERCISE_TIERS.md`)이라 외부 데이터가 관문은 아니다. 외부 데이터가 답할 수 있는 것은 아래 세 가지다.
  1. **누운 몸에서 MediaPipe 가 모집단 단위로 얼마나 검출하는가.** 가장 큰 위험이다. FMS 논문 스스로 ASLR 을 Kinect 골격 소실이 가장 많은 동작으로 꼽았다. UCO 논문에서는 MediaPipe 가 누운 자세에서 실패했다.
  2. **`hip_ang` 스윙 25° 가 준비 자세 유지 중에 헛카운트를 내는가.** FMS 는 에피소드당 정답이 1회라서 이것을 잴 수 있다.
  3. **다리를 들어 올린 높이에 대한 전문가 등급.** FMS 0~3점이 그것이다.

## 1. 후보

| 데이터 | 동작 | 연속성·정답 | 카메라 | 라이선스 | 용량 |
|---|---|---|---|---|---|
| **FMS** (Xing 2022, Sci Data 9:104) | ASLR 왼(m09)·오(m10). 45명(18~59세) × 3에피소드 × 2쪽 ≈ 270 | 에피소드 = 한 번 들고 내리기. 앞뒤로 준비 자세를 유지한다. **30fps 1080p JPEG 연속 프레임 ✓**(`s21_m15_e3/sideLow_…_i0001_r7379.jpg`, i·r 연번). 전문가 0~3점(`Experts_score.json` 0.1MB) | 정면·측면 96cm, **측면 낮은 위치 24cm**, 후면. Azure Kinect 32관절(2D 픽셀 포함) | **CC0 ✓**(figshare API) | 측면 낮음 14.6GB ✓ / 측면 25.8GB ✓ / 골격 1.0GB ✓. 카메라마다 피험자 구간으로 나뉜 RAR 3개 |
| **UCO Physical Rehabilitation** (Sensors 2023, 23:8862) | "Lift the extended leg", 치료대에 누움, 27명(23~60세) | **영상당 4회 연속**(느리게), 평균 30.4초 | RGB 5대(그중 하나는 바닥에서 15cm), 25fps 720p. OptiTrack 고관절·무릎·발목 정답 | 논문은 CC BY. **데이터 조건은 명시 없음** → 검증 전용으로 본다 | 2,160시퀀스 전체 기준 |
| **Kaggle Workout/Exercises Video** (hasyimabdillah) | 'leg raises' **21개 ✓**(HF 미러 `34data/workout-vids` 집계). 양다리 | 영상당 1회 이상. 정답 없음 → 자가 라벨 | 제각각(유튜브) | **CC BY-NC-SA 4.0 ✓**(Kaggle API). 원 저작권은 유튜브 | 전체 4.9GB ✓ |

### FMS 를 읽을 때 따라가야 할 것

- **한 다리 동작이다.** 앱 바닥 경로의 `hip_ang` 은 어깨·골반·무릎의 **양측 중점**으로 잰다(`PostureFloor.kt`). 그래서 한 다리를 80° 들면 중점 기준 스윙은 약 40° 다.
  - 25° 문턱에 대한 보수적 시험은 된다.
  - 양다리 진폭 분포는 아니다. 측별 `hip_ang_L/R` 를 같이 본다.
- **연속 반복이 아니다.** 1에피소드에 1회라서 "세트 중 놓친 렙"은 못 본다. 볼 수 있는 것은 셋이다.
  - 1회를 셌는지
  - 준비 자세 유지 중에 헛카운트가 났는지
  - 검출이 끊겼는지
- **Kinect 골격은 ASLR 에서 가장 많이 비었다.** 논문 원문은 "the movement that contains the most no-body skeleton episodes" 다. 골격 1GB 만 받아 하는 보조 실험은 이 종목에서 약하다. 컬러 프레임에 MediaPipe 를 돌려야 한다.
- **RAR 은 항목마다 압축돼 있어 HTTP 범위 읽기로 프레임 하나를 꺼낼 수는 없다 ✓.** 대신 비솔리드라서 두 가지가 된다.
  - 받은 아카이브에서는 `bsdtar --include '*_m09_*' --include '*_m10_*'` 로 ASLR 만 18초에 풀린다 ✓.
  - 받는 도중의 부분 파일에서도 앞쪽 항목은 풀린다 ✓.
- **카메라 배치 — 이름과 실제 구도가 다르다 ✓ (2026-10-07, 프레임을 직접 봄).**
  - ASLR 은 m01~m03 이 아니라서 피험자가 S-Kinect 쪽을 향한다. 그래서 몸의 길이 방향이 측면·측면 낮은 카메라를 향한다.
  - **측면 낮은 카메라(24cm)는 ASLR 에서 몸을 발 또는 머리 끝에서 본다**(끝-방향). 앱의 '몸 옆에서' 배치가 아니다. 사용자가 폰을 발·머리 쪽에 둔 경우의 시험으로만 쓴다.
  - **후면 카메라(96cm)가 몸을 옆에서 본다.** 위에서 비스듬히 내려다보는 측면이고, 배경에 서 있는 사람이 있다(한 명 검출이 그쪽을 잡을 위험).
  - 정면 카메라(96cm)도 후면의 반대쪽에서 옆모습일 것으로 보이지만 미확인이다.
- **점수.** FMS 표준 ASLR 채점은 들어 올린 발목이 반대쪽 허벅지 어디까지 오는지(중간 허벅지~ASIS / 무릎선~중간 허벅지 / 무릎선 아래)로 매긴다. 사실상 **들어 올린 높이 등급**이다. 데이터셋 논문에는 일반 0~3 정의만 있다.

### UCO 에서만 볼 수 있는 것

- 4회 연속 + 고관절 각의 광학식 정답이 있다. MediaPipe `hip_ang` 오차를 직접 잴 수 있는 유일한 자료다.
- 논문 결과:
  - 대부분의 포즈 추정기가 누운 자세에서 실패한다.
  - **영상을 돌려 서 있는 것처럼 만들면 오차가 유의하게 줄었다.**
  - 앱 바닥 경로의 가설 후보다. 미검증.
- 신청은 `inforeha@uco.es` 로 한다. 이름·소속·연구 목적을 적는다. 상업 이용 조건을 같이 물어야 한다.

## 2. 제외

| 데이터 | 이유 |
|---|---|
| QEVD (Qualcomm) | 148종에 레그 레이즈가 있는지 확인되지 않았다. 논문 표기는 CC BY-NC-ND, 공식은 Qualcomm 연구 라이선스. 클립이 2~10초다 |
| FineRehab (CVPRW 2024) | 누워서 한 다리 SLR 이 있다. 하지만 공식 페이지가 "will be released soon" 이다(미공개) |
| EgoExo-Fitness (ECCV 2024) | 12종에 레그 레이즈가 없다. 가장 가까운 것은 "Knee Raise and Abdominal Muscles Contract" 다. 라이선스 동의서가 필요하다 |
| Pexels 등 스톡 영상 | Pexels 는 ML 학습·평가 이용을 명시적으로 금지한다 |
| MM-Fit · REHAB24-6 | 레그 레이즈가 없다. MM-Fit 윗몸일으키기는 '누운 몸 검출'의 대리로만 쓸 수 있다 |
| Countix · RepCount · UCFRep · OVR (`data/rep_datasets/` 로컬 라벨) | 로컬에서 grep ✓ 한 결과 Ego4D 서술문 1건("#c c does leg raises exercise")뿐이다. 1인칭이라 쓸 수 없다 |
| Fit3D · FLAG3D | 학술 한정(`rep_datasets_survey.md` §6) |
| AIHub 다른 데이터 | 검색으로 연속 레그 레이즈 데이터를 찾지 못했다 |

## 3. 권고 순서

1. **FMS 측면 낮은 위치 첫 아카이브**(`sideLow_s01-s21.rar`, 5.1GB, figshare file 32585330)
   - ASLR 은 약 126에피소드다.
   - 순서: JPEG 열 → `extract_mediapipe.py`(영상 입력이라 프레임 열 입력을 붙여야 함) → 재생기.
   - 표에 낼 것: 검출률, 측별·중점 `hip_ang` 스윙, 에피소드당 카운트(정답 1), 준비 자세 중 헛카운트, 점수별 최대 스윙.
   - 쓸 만하면 나머지 두 아카이브를 받는다.
2. **UCO 신청.** 연속 4회 카운트와 `hip_ang` 오차를 광학 정답에 대어 볼 수 있다. 회전 가설도 시험할 수 있다.
3. **Kaggle 21개.** 양다리 동작을 소량으로 검증한다. 자가 라벨이 필요하다. 상업 불가라 임계값 보정에는 쓰지 않는다.
4. 양다리·바닥·폰 조건은 결국 폰 세트로만 확인된다. C 등급 완료 기준과 같다.

**진행 (2026-10-07)**: 1번을 했고(`results/fms_aslr/README.md`), 거기서 나온 세 문제를 spec §99 로 반영했다(바닥 준비 기준·양다리 판별 — 판별 문턱은 AIHub 416클립을 새로 추론해 정했다. 복귀형 v2 는 같은 날 사용자 결정으로 되돌렸다, `return_v2.patch`). 측면 낮은 카메라는 ASLR 에서 끝-방향이라, 후면 첫 아카이브(`Back_s01-s21.rar`, 6.3GB, MD5 일치)도 받아 옆모습으로 돌렸다.

## 출처

- FMS — 논문 <https://pmc.ncbi.nlm.nih.gov/articles/PMC8956653/>, 데이터 <https://doi.org/10.25452/figshare.plus.c.5774969>. 파생 LLM-FMS(키프레임 이미지만): <https://pmc.ncbi.nlm.nih.gov/articles/PMC11896072/>
- UCO — 논문 <https://pmc.ncbi.nlm.nih.gov/articles/PMC10648737/>, 저장소 <https://github.com/AVAuco/ucophyrehab>
- Kaggle — <https://www.kaggle.com/datasets/hasyimabdillah/workoutfitness-video>, 미러 <https://huggingface.co/datasets/34data/workout-vids>
- QEVD — <https://arxiv.org/html/2407.08101v1>, <https://www.qualcomm.com/developer/software/qevd-dataset>
- FineRehab — <https://bsu3dvlab.github.io/FineRehab>
- EgoExo-Fitness — <https://arxiv.org/abs/2406.08877>, <https://github.com/iSEE-Laboratory/EgoExo-Fitness>
- Pexels — <https://help.pexels.com/hc/de-de/articles/27292485713945>
