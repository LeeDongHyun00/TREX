# 피트니스 자세 데이터 감사

## 결론

이 데이터는 24관절 시퀀스 기반 운동 판정과 조건별 오류 분류의 보정 자료로 사용할 수 있다. 하지만 현재 추출본만으로 스쿼트·런지 이미지 pose estimator를 재학습하거나 검증할 수는 없다. 원천 JPG가 Day04·17에만 있고 해당 촬영일의 13개 운동에 스쿼트·런지가 없기 때문이다.

앱에서는 검증된 범용 MediaPipe Pose Landmarker를 사용하고, 이 데이터의 정제된 관절 시퀀스로 운동별 각도·시간 임계값과 이후의 작은 temporal classifier를 보정하는 구성이 맞다.

## 전체 인벤토리

| 형식 | 파일 수 | 크기 |
|---|---:|---:|
| 전체 | 393,789 | 76,253,090,589 bytes, 약 71.016 GiB |
| JPG | 323,868 | 약 65.61 GB |
| JSON | 69,898 | 약 10.21 GB |
| ZIP | 23 | 약 427 MB |

- Training 원천 JPG: Day04 151,099장, Day17 172,769장
- Validation: 원천 JPG 없이 라벨 ZIP만 존재
- Test split: 없음
- Training 바벨·덤벨 ZIP 15개는 이미 풀린 JSON의 보관본이므로 함께 집계하면 중복된다.
- `furniture_01.zip`과 `furniture_01(1).zip`, 문서 ZIP의 원본과 `(1)` 복사본은 SHA-256이 같은 완전 중복이다.

대용량 원천 데이터는 앱 자산과 Git에서 제외한다. 런타임에는 pose 모델과 정제된 규칙/작은 모델만 포함한다.

## 2D·3D 라벨 짝

### Training

| 상태 | 수량 | 위치 |
|---|---:|---|
| 2D JSON | 34,468 | 여러 Training 촬영일 |
| 3D JSON | 35,430 | 여러 Training 촬영일 |
| 정확한 2D↔3D pair | 33,349 | category/day/basename 일치 |
| 2D only | 1,119 | 전부 Day36 |
| 3D only | 2,081 | Day37 1,020 + Day38 1,061 |

3D 파일에는 `type_info`가 없다. 3D-only 파일은 파일명의 type code를 검증된 catalog와 결합해야 하며, 임의로 운동 라벨을 추정하면 안 된다.

### Validation

| ZIP | 촬영일 | 완전한 pair |
|---|---|---:|
| `babel_01.zip` | Day07 | 949 |
| `body_01.zip` | Day32 | 1,120 |
| `furniture_01.zip` | Day19 | 1,070 |
| 합계 |  | 3,139 |

Validation의 2D/3D pair는 완전하지만 대응 원천 JPG는 없다. Training/Validation 2D sequence 비율은 약 91.7%/8.3%다.

## JSON 스키마

2D sequence:

```text
frames[T]
  view1..view5
    pts[24] = {x, y}
    active
    img_key
type
type_info
  key, type, pose, exercise
  conditions[] = {condition, value}
  description
```

3D sequence:

```text
frames[T]
  pts[24] = {x, y, z}
```

관절은 다음 24개다.

```text
Nose
Left/Right Eye, Ear
Left/Right Shoulder, Elbow, Wrist, Palm
Left/Right Hip, Knee, Ankle, Foot
Neck, Back, Waist
```

대부분 한 sequence는 16 frame이고, 각 frame에는 5개 카메라 view가 있다. `view1..5`는 Day04·17의 모든 159,345개 참조에서 각각 `A..E` 카메라와 일치했다. 보통 원본 32장 중 홀수 frame `1, 3, ..., 31`을 참조한다.

예외도 파서에서 허용해야 한다.

- Day17 type 794 로잉머신, type 767 케이블 크런치: `frames=[]`
- type 752: 13 frame
- 바벨 컬 type 409·424·432: 15 frame
- 바벨 컬 type 410·423·431: 17 frame

2D는 1920×1080 픽셀 좌표이며 x는 오른쪽, y는 아래 방향이다. Day04·17에서 좌표 이상은 `D17-6-733.json`, frame 13, view5, Left Foot `(1008, 1094)` 한 점뿐이었다.

3D 좌표의 절대 원점과 단위는 제공 문서에 명시되지 않았다. pelvis 중심 이동과 신체 크기 정규화 없이 MediaPipe world 좌표나 실제 거리와 직접 비교하면 안 된다.

Windows에서는 JSON과 XLSX 관련 텍스트를 UTF-8로 명시해 읽어야 한다. PowerShell 기본 인코딩에 맡기면 한글이 깨질 수 있다.

## JPG 참조 무결성

| 촬영일 | 2D sequence | `img_key` 참조 | 실제 존재 |
|---|---:|---:|---:|
| Day04 | 945 | 75,600 | 100% |
| Day17 | 1,049 | 83,745 | 100% |
| 합계 | 1,994 | 159,345 | 100% |

Day17의 빈 sequence 2개는 참조가 0개다. 전체 JPG 중 164,523장은 JSON에서 참조되지 않으며, 대부분 짝수 frame과 Day17의 추가 촬영분이다.

Raw view-directory 10,125개의 frame 수는 다음과 같다.

| 장수 | 디렉터리 수 |
|---:|---:|
| 25 | 5 |
| 30 | 15 |
| 31 | 96 |
| 32 | 9,990 |
| 33 | 9 |
| 34 | 10 |

번호 중간 누락은 없었다. 각 view-directory 대표 JPEG 10,125개는 모두 1920×1080 RGB JPEG였고 헤더 오류와 0-byte 파일은 없었다.

## 운동 분포

표기는 `type 수 / 2D sequence 수`다.

### 맨몸

| 운동 | type / sequence | 운동 | type / sequence |
|---|---:|---|---:|
| 스탠딩 사이드 크런치 | 32 / 1,611 | 스탠딩 니업 | 16 / 1,172 |
| 버피 테스트 | 32 / 1,605 | 스텝 포워드 다이나믹 런지 | 32 / 1,618 |
| 스텝 백워드 다이나믹 런지 | 32 / 1,458 | 사이드 런지 | 32 / 1,404 |
| 크로스 런지 | 8 / 350 | 굿모닝 | 8 / 348 |
| 라잉 레그 레이즈 | 16 / 416 | 크런치 | 16 / 416 |
| 바이시클 크런치 | 8 / 207 | 시저크로스 | 32 / 832 |
| 힙쓰러스트 | 8 / 208 | 플랭크 | 8 / 207 |
| 푸시업 | 32 / 832 | 니푸쉬업 | 32 / 829 |
| Y - Exercise | 8 / 204 |  |  |

### 바벨·덤벨

| 운동 | type / sequence | 운동 | type / sequence |
|---|---:|---|---:|
| 프런트 레이즈 | 8 / 352 | 업라이트로우 | 8 / 351 |
| 바벨 스티프 데드리프트 | 8 / 344 | 바벨 로우 | 32 / 1,410 |
| 덤벨 벤트오버 로우 | 32 / 1,437 | 바벨 데드리프트 | 32 / 1,371 |
| 바벨 스쿼트 | 16 / 720 | 바벨 런지 | 32 / 1,440 |
| 오버 헤드 프레스 | 16 / 710 | 사이드 레터럴 레이즈 | 32 / 1,438 |
| 바벨 컬 | 32 / 1,437 | 덤벨 컬 | 32 / 1,439 |
| 덤벨 체스트 플라이 | 8 / 506 | 덤벨 인클라인 체스트 플라이 | 8 / 504 |
| 덤벨 풀 오버 | 16 / 1,008 | 라잉 트라이셉스 익스텐션 | 32 / 2,022 |

### 기구

| 운동 | type / sequence | 운동 | type / sequence |
|---|---:|---|---:|
| 딥스 | 16 / 566 | 풀업 | 16 / 574 |
| 행잉 레그 레이즈 | 8 / 278 | 랫풀 다운 | 16 / 567 |
| 페이스 풀 | 8 / 283 | 케이블 크런치 | 8 / 287 |
| 케이블 푸시 다운 | 16 / 569 | 로잉머신 | 32 / 1,138 |

전체 816 type은 type당 23~96 sequence, 운동당 204~2,022 sequence로 불균형하다. 학습 시 exercise/type 균형 sampler 또는 class weight가 필요하다.

## 스쿼트·런지 라벨 품질

### 바벨 스쿼트

- type 313~328
- 16 type × 45 sequence = 720
- 조건: 척추 중립, 고개 정면, 발-무릎 방향 일치, 발바닥 고정
- 4조건의 16개 truth-vector 조합이 중복 없이 균형적
- 각 조건 true 360 / false 360
- 정자세 type 313

이 라벨은 비교적 깨끗하지만 앱의 맨몸 `기본 스쿼트`와는 도메인이 다르다. 공통적인 척추·무릎 정렬 기준만 1차 규칙 보정에 사용한다.

### 런지

주요 5조건은 앞무릎 90도, 몸통·발·무릎 방향, 뒤무릎 90도, 척추 중립, 상체의 과도한 숙임/젖힘이다. 그러나 type과 truth-vector가 일대일로 대응하지 않는다.

| 운동 | type 수 | 고유 truth-vector |
|---|---:|---:|
| 스텝 포워드 다이나믹 | 32 | 17 |
| 스텝 백워드 다이나믹 | 32 | 17 |
| 사이드 | 32 | 16 |
| 크로스 | 8 | 6 |
| 바벨 런지 | 32 | 18 |

확인된 명백한 충돌 예:

- type 101: description은 상체 뒤로 젖힘, 5조건은 모두 true
- type 109: description은 앞다리 다 펴기, 5조건은 모두 true
- type 062 버피: description은 몸을 옆으로 틀고 손을 머리 위에 놓기, 5조건은 모두 true

각 type이 51 sequence라 최소 153 sequence가 영향을 받는다. 런지 학습 전 description/type 기반 override catalog와 수작업 표본 검수가 필요하다.

`active`는 correctness 라벨이 아니다. Day04·17에서 view 간 값은 같지만 패턴이 234종이고 1,167/1,994 sequence가 전 frame `Yes`여서 정오자세 target으로 사용할 수 없다.

## 구현용 정제 규칙

1. ZIP 보관본과 풀린 JSON을 중복 집계하지 않는다.
2. `*-3d.json`과 2D JSON을 category/day/basename으로 결합한다.
3. 빈 frames는 제외하고, 길이가 16이 아닌 sequence는 mask와 phase resampling으로 처리한다.
4. `view1..5`를 각각 sample로 펼쳐도 원 sequence의 모든 view는 같은 split에 둔다.
5. frame/view random split을 금지하고 최소 Day, 가능하면 `Day + Z subject`로 분리한다.
6. directory 이름보다 `type_info.type`과 catalog를 우선한다. 바벨·덤벨 폴더에도 맨몸 type이 포함돼 있다.
7. `conditions.value`와 description/type이 충돌하면 자동 학습 대상에서 격리한다.
8. 3D는 pelvis 중심으로 이동하고 torso length 또는 shoulder/hip 폭 조합으로 정규화한다.
9. MediaPipe 33관절과 공통인 몸통·사지 14~17관절부터 사용한다.
10. 원 데이터에는 confidence가 없으므로 학습 시 관절 dropout, 좌표 jitter, 가림 augmentation을 넣는다.

권장 1차 feature는 양쪽 knee/hip 각도, torso inclination, knee-foot 정렬, 좌우 대칭 차이, 관절의 1·2차 속도, visibility mask다. 한 반복은 16 phase로 resample하면 원 데이터 구조와 맞추기 쉽다.

데이터 폴더에서 별도의 이용 조건 문서는 확인되지 않았다. 정제 데이터나 학습 모델을 배포하기 전 원 AI Hub 데이터셋의 이용 조건을 별도로 확인해야 한다.
