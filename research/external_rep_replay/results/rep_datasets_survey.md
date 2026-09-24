# 반복 정답이 있는 공개 운동 영상 데이터셋 조사

조사일 2026-09-24. 목적은 **폰 실사용 검증을 대신할 외부 자료 찾기** — 렙 카운터(beta, §31a 에서 마지막 렙을 구조적으로 누락)를 사람 손 라벨 없이 대량 검증할 수 있는 영상·라벨 쌍이 있는가.

이 문서의 숫자 중 **표시가 붙은 것은 이 PC 에서 실제 라벨 파일을 받아 집계한 값**이고, 나머지는 논문·공식 페이지 인용이다. 둘을 섞어 읽으면 안 된다.

## 0. 결론 먼저

- **렙 카운터 검증에 바로 쓸 수 있는 데이터셋은 없다.** 라벨이 좋은 것(RepCount)은 영상 배포가 학술 연구 한정이고, 라이선스가 열린 것(MM-Fit CC BY 4.0)은 이미 이 저장소가 쓰고 있다.
- **상업 이용 가능한 유일한 후보였던 InfiniteRep(CC BY 4.0)은 배포처가 사라졌다.** 남은 미러는 세그멘테이션 마스크 PNG 만 있고 RGB 프레임도 렙 라벨도 없다(§5 에서 실제로 열어 확인).
- **Countix·OVR 은 영상을 주지 않는다**(YouTube ID 만). 개별 YouTube 다운로드는 이번 작업 범위 밖이라 제외.
- TREX 5종목 중 **런지와 덤벨/바이셉 컬의 반복 정답 영상은 어디에도 사실상 없다.** 런지는 Countix 에만 있고(157개, 영상 없음), 컬은 InfiniteRep 에만 있었다(소실).
- 실질적으로 남는 선택지는 **① MM-Fit 확대(이미 사용 중, CC BY 4.0, 39.1GB) ② UCF101 경유 UCFRep(스쿼트 21개) ③ RepCount-A 학술 목적 한정 사용** 셋이다.

## 1. 한눈에 보기

| 데이터셋 | 영상 수 | 라벨 형식 | 영상 제공 | 라이선스 / 상업 이용 | 용량 |
|---|---:|---|---|---|---:|
| **RepCount** (TransRAC, CVPR 2022) | **1,041** | 개수 + **반복별 시작·끝 프레임**(L1~L302) + fps | OneDrive/BaiduNetdisk(암호), HF 비공식 미러 | 학술 연구 한정 — **상업 불가** | 미러 기준 약 **9.7GB** |
| **Countix** (Google/RepNet) | 8,375 (train 4,414 · val 1,406 · test 2,555) | **개수만** + 반복 구간 시작·끝(초) | ✗ YouTube ID 만 | 라벨 CC BY 4.0(Kinetics 계열), 영상은 YouTube 약관 | 라벨 153KB |
| **OVR** (DeepMind, 2024) | 21,898(Kinetics) + 50,665(Ego4D) | 개수 + 반복 구간(초) + 자유서술 | ✗ Kinetics/Ego4D 원본 필요 | 코드 Apache-2.0, 자료 CC BY 4.0 | 라벨 39MB |
| **UCFRep** (CVPR 2020) | **526** | 개수 + **반복별 경계 프레임** + 프레임 수 | ✗ UCF101 에서 가져와야 함 | UCF101 = 연구용 | 라벨 229KB, UCF101 약 6.5GB |
| **Fit3D** (IMAR) | 611 시퀀스 / 8~13명 / 4 RGB 카메라 | 종목당 5회 반복 고정 + MoCap 3D | 요청 승인 후 | **비상업 한정, 상업 학습도 금지** | 미기재(3M+ 이미지) |
| **FLAG3D** (CVPR 2023) | 180K 시퀀스 / 60종목 | 3D 키포인트·SMPL·언어 지시 | 요청 | 학술 | 미기재 |
| **FLEX / MyoMechanix** (2025) | 7,500+ / 20종목 / 38명 / **5뷰** / 각 10회 | 전문가 오류 라벨 + sEMG + 3D 포즈 | 신청 폼 승인 필요 | **학술 한정, 상업 불가** | 미기재 |
| **InfiniteRep** (합성) | 1,000 (미러 700) | 프레임별 렙 카운트 + 키포인트 + 마스크 | **배포 중단** | **CC BY 4.0 — 상업 가능** | 미러 1.13GB(마스크만) |
| **MM-Fit** (이미 사용 중) | 20 세션 | 반복 수 + 다중 뷰 RGB-D + IMU | ✓ Zenodo 직접 | **CC BY 4.0 — 상업 가능** | **39.1GB** (21 파일) |

## 2. TREX 관심 5종목의 영상 수

TREX 가 물은 스쿼트·런지·덤벨/바이셉 컬·프런트 레이즈·푸시업 기준. \*\*표시는 이 PC 에서 라벨 파일을 집계한 값.

| | 스쿼트 | 런지 | 덤벨/바이셉 컬 | 프런트 레이즈 | 푸시업 |
|---|---:|---:|---:|---:|---:|
| RepCount\*\* | **135** (squat 105 + squant 30) | 0 | 0 | **130** (front_raise 97 + frontraise 33) | **123** (push_up 98 + pushups 25) |
| Countix\*\* (train+val 한정) | 96 | **157** | 0 (`exercising arm` 148 이 가장 근접) | 170 | 154 |
| UCFRep\*\* | 21 (BodyWeightSquats) | 0 | 0 | 0 | 24 (HandStandPushups, 물구나무) |
| OVR\*\* (서술문 키워드) | 16(Kinetics)/5(Ego4D) | 9/0 | 42/6 | 43/5 | 90/4 |
| InfiniteRep 미러\*\* | 70 | 0 | **70** | 70 (`armraise`) | 70 |
| Fit3D | 있음 | 미확인 | 있음(dumbbell biceps) | 미확인 | 미확인 |

- Countix test(2,555개)는 **클래스 라벨이 비공개**라 위 표에서 빠졌다. 공식 CSV 헤더 자체에 `class` 열이 없다(확인함).
- RepCount 의 `squat`/`squant`, `push_up`/`pushups` 같은 쌍은 오타가 아니라 **파일명 접두사가 다른 두 묶음**이다: `squat` 105개는 전부 `stu*`(학교 촬영), `squant` 30개는 전부 `train*/val*/test*`(YouTube). 논문의 Part-A/Part-B 구분과 이 접두사가 일대일인지는 공식 문서로 확인하지 못했다 — 전체 1,041행 중 `stu*` 가 855행인데 논문은 Part-A 를 1,041개라고 쓴다. **이 불일치는 미해결이다.**

## 3. 라벨 형식

- **RepCount** `,type,name,count,L1..L302,fps` — L 은 짝으로 읽는다(L1=1렙 시작, L2=1렙 끝, L3=2렙 시작…). fps 가 같은 파일에 있어 초 단위 변환이 된다. 1,041개 중 **반복 경계가 비어 있는 것은 3개뿐**이다(benchpressing·push_up·squat 각 1). TREX 에 필요한 "반복별 시작·끝"을 **유일하게 제대로 주는 데이터셋**이다.
- **Countix** `video_id,class,kinetics_start,kinetics_end,repetition_start,repetition_end,count` — 반복 **구간 전체**의 시작·끝만 주고 개별 렙 경계는 없다. 렙 카운터의 "몇 개 셌나"는 볼 수 있어도 "어느 렙을 놓쳤나"는 못 본다.
- **UCFRep** `name,counts,num_frames,start_frame,end_frame,L1..` — RepCount 와 같은 L 규약.
- **OVR** JSON: `{video_id, ovr_annotations:[{count, description, start_time, end_time, split, duration}]}`. 자유서술이 있어 종목 필터가 가능하지만 개별 렙 경계는 없다.

## 4. 편집·다인·짧은 클립 문제 (측정치)

영상을 안 받고도 라벨만으로 잴 수 있는 것을 쟀다.

**Countix — 클립이 너무 짧다.**

| 클래스 | 영상 | 반복 구간(s) 중앙 | 반복당(s) 중앙 | **반복 수 중앙** | 반복구간<5s 비율 |
|---|---:|---:|---:|---:|---:|
| squat | 96 | 7.9 | 2.93 | **2** | 0.16 |
| lunge | 157 | 8.9 | 2.49 | **3** | 0.13 |
| front raises | 170 | 8.1 | 2.03 | **3** | 0.20 |
| push up | 154 | 7.1 | 1.94 | **3** | 0.27 |
| bench pressing | 92 | 7.9 | 2.27 | **3** | 0.16 |

Kinetics 가 10초 클립이라 **한 영상에 렙이 2~3개뿐**이다. TREX 렙 카운터의 알려진 결함(마지막 렙 구조적 누락)은 여기서 **33~50% 오차로 나타난다** — 신호가 아니라 잡음이 된다. Countix 는 렙 카운터 검증에 부적합하다.

**RepCount — 종목마다 규칙성이 크게 다르다.** 사이클 길이의 변동계수(CV) 중앙값과, 한 영상 안에서 가장 긴 사이클이 가장 짧은 사이클의 2배를 넘는 영상 비율:

| 종목 | 영상 | 사이클 중앙(s) | 사이클 CV 중앙 | max/min>2 비율 |
|---|---:|---:|---:|---:|
| squat (stu*) | 100 | 2.14 | 0.104 | 0.14 |
| squant (YouTube) | 28 | 3.03 | 0.124 | 0.04 |
| front_raise | 95 | 2.96 | 0.110 | 0.17 |
| frontraise | 31 | 2.33 | 0.076 | 0.03 |
| push_up | 97 | 1.83 | 0.177 | **0.47** |
| pull_up | 99 | 2.09 | 0.218 | **0.51** |
| bench_pressing | 98 | 2.22 | 0.192 | **0.42** |
| situp | 126 | 2.45 | 0.105 | 0.25 |

푸시업·풀업·벤치프레스는 **영상의 40~50% 가 사이클 길이 2배 이상 요동**한다. 컷 편집이거나 실제로 사람이 지쳐 느려진 것인데, 라벨만으로는 구분이 안 된다. 반대로 스쿼트·프런트 레이즈는 CV 0.08~0.12 로 안정적이다 — **RepCount 를 쓴다면 이 두 종목부터**가 맞다.

**다인 영상 비율은 측정하지 못했다.** 라벨에 사람 수 정보가 없고, 영상을 받지 않아 세어볼 수 없었다. Part-A 가 YouTube 크롤이라 다인·편집이 섞여 있다는 것은 논문 서술("action interruption", "inconsistency between action periods")로만 안다.

## 5. 실제로 받은 것 / 받지 않은 것

받은 것 (`C:\Users\hp276\Desktop\trex\data\rep_datasets\`, 전부 라벨·주석 파일):

| 폴더 | 내용 | 크기 |
|---|---|---:|
| `countix/` | 공식 train/val/test CSV + README | 157KB |
| `ovr/` | Kinetics·Ego4D 주석 JSON | 39MB |
| `repcount_a/annotations/` | 반복별 경계 CSV 3개(EveryShotCounts 경유) | 728KB |
| `repcount_a/*.jsonl` | HF 미러 개수 라벨 | 27KB |
| `ucfrep/` | 반복별 경계 CSV 2개 | 229KB |
| `countix_fitness/` | Countix-Fitness 주석 2개 + 영상 안내 | 74KB |

합계 약 40MB. 영상 바이트는 하나도 남기지 않았다.

**영상은 받지 않았다.** 이유를 단계별로 적는다.

1. **Countix·OVR** — YouTube ID 만 제공. 개별 YouTube 다운로드는 하지 말라는 지시가 있어 제외.
2. **RepCount-A** — 공식은 OneDrive/BaiduNetdisk 이고 둘 다 암호가 걸려 있다. OneDrive 공유 링크는 Graph API 로 목록을 못 읽는다(`userContentMigrated` 오류). 유일한 프로그램적 경로는 비공식 HF 미러 `lmms-lab-eval/repcounta-lance` 인데 **Lance 컬럼 포맷 단일 파일**이라 클래스별로 잘라 받을 수 없다 — train 6.98GB + test 1.49GB + validation 1.24GB = **약 9.7GB 를 통째로** 받아야 스쿼트 135개가 나온다. "스쿼트·런지·컬 관련 클래스만" 이라는 조건과 맞지 않고, 이 PC 의 C: 는 **여유 55GB(사용률 95%)** 다. 그래서 받지 않았다.
   - 포맷만 HTTP Range 로 앞 64바이트를 읽어 확인했다: 파일 머리가 `ftypisom…isomiso2avc1mp41` 로 **MP4 블롭이 그대로 들어 있는 구조**다. 다만 행 단위로 꺼내려면 `pip install pylance` 가 필요하고, 그건 사용자 파이썬 환경을 건드리는 일이라 하지 않았다.
   - ⚠️ 이 미러는 **다운로드가 조용히 잘린다.** validation 파일을 한 번 받아 보니 API 가 보고한 1,236,373,231 바이트 중 469,468,400 바이트만 오고도 `curl` 이 종료코드 0 을 반환했다. 나중에 실제로 받을 때는 **받은 바이트 수를 API 의 `size` 와 대조**하고 `curl -C -` 로 이어받아야 한다. 잘린 파일은 지웠다.
3. **InfiniteRep** — 원 배포처(`marketplace.infinity.ai`)가 응답하지 않고 GitHub `toinfinityai/InfiniteRep` 은 404 다. 남은 미러 `FatimahEmadEldin/infiniterep-physiotherapy`(1.13GB, 클래스별 폴더라 squat·curl 만 받을 수 있었다)에서 squat 샘플 zip 을 열어보니 **786개 항목이 전부 `iseg`/`cseg` 세그멘테이션 마스크 PNG** 였다. RGB 프레임도 렙 라벨 JSON 도 없다. 받아도 쓸 수 없어 중단하고 지웠다.
4. **UCFRep** — UCF101 전체(약 6.5GB)를 받아야 하고, 그중 반복 라벨이 있는 스쿼트는 `BodyWeightSquats` 21개뿐이다. 비용 대비 수확이 낮아 이번엔 보류.
5. **Fit3D·FLAG3D·FLEX** — 전부 신청·승인이 필요하고 자동으로 받을 수 없다. Fit3D 와 FLEX 는 명시적으로 상업 이용 금지다.

## 6. 라이선스 — TREX 관점

TREX 는 배포되는 앱이라 "학술 연구 한정"은 **엔진 튜닝 근거로 쓰면 안 되는 자료**라는 뜻이다. 검증 지표를 뽑아 보는 것과 그 데이터로 임계값을 맞추는 것은 다르다.

| 데이터셋 | 상업 이용 | 근거 |
|---|---|---|
| MM-Fit | **가능** | Zenodo 레코드 `cc-by-4.0` (확인함) |
| InfiniteRep | 가능(했음) | CC BY 4.0 — 단 배포처 소실 |
| Countix / OVR 라벨 | 가능 | CC BY 4.0. 단 영상은 YouTube 약관 별도 |
| RepCount | **불가** | 공식 페이지 "usage of the dataset is limited to academic research" |
| Fit3D | **불가** | "any use for commercial purposes, is prohibited" + 상업용 모델 학습도 금지 |
| FLEX / MyoMechanix | **불가** | "academic and research use only" |
| UCF101(UCFRep) | 연구용 | UCF101 조건 |

## 7. 권고

1. **MM-Fit 을 끝까지 쓰는 것이 가장 실용적이다.** 이미 재생 파이프라인이 있고(`research/external_rep_replay/`), 라이선스가 유일하게 깨끗하고, 39.1GB 전량을 받아도 이 PC 에 들어간다. 지금 쓰는 w06 두 종목 외에 나머지 세션으로 넓히는 것이 새 데이터셋을 뚫는 것보다 싸다.
2. **RepCount 는 "검증 전용"으로만.** 쓴다면 스쿼트·프런트 레이즈부터 — §4 에서 사이클이 가장 규칙적인 두 종목이다. 반복별 경계가 있으니 "마지막 렙 누락"을 직접 잴 수 있는 유일한 자료다. 다만 **상업 불가라 임계값 보정에는 쓰면 안 된다**.
3. **런지·컬은 외부 데이터로 못 채운다.** 반복 정답이 있는 영상이 사실상 없다. 이 두 종목은 자가 라벨(`rep_truth.csv`) 경로를 유지하는 수밖에 없다.
4. **Countix 는 렙 카운터 검증에 쓰지 않는다.** 클립당 렙 2~3개라 한 개 오차가 33~50% 로 증폭된다.

## 출처

- RepCount / TransRAC — <https://svip-lab.github.io/dataset/RepCount_dataset.html>, <https://github.com/SvipRepetitionCounting/TransRAC>, 논문 <https://arxiv.org/pdf/2204.01018>
- RepCount 주석 미러 — <https://github.com/sinhasaptarshi/EveryShotCounts>, <https://huggingface.co/datasets/lmms-lab-eval/repcounta-lance>
- Countix / RepNet — <https://sites.google.com/view/repnet>, 논문 <https://openaccess.thecvf.com/content_CVPR_2020/papers/Dwibedi_Counting_Out_Time_Class_Agnostic_Video_Repetition_Counting_in_the_CVPR_2020_paper.pdf>
- OVR — <https://sites.google.com/view/openvocabreps/>, <https://arxiv.org/abs/2407.17085>
- UCFRep — <https://arxiv.org/pdf/2005.08465>, <https://github.com/Xiaodomgdomg/Deep-Temporal-Repetition-Counting>
- Fit3D — <https://fit3d.imar.ro/>, 라이선스 <https://fit3d.imar.ro/legal>
- FLAG3D — <https://andytang15.github.io/FLAG3D/>
- FLEX — <https://arxiv.org/pdf/2506.03198>, <https://github.com/HaoYin116/FLEX_AQA_Dataset>
- InfiniteRep — <https://medium.com/infinity-ai/infiniterep-an-open-source-synthetic-dataset-for-remote-fitness-and-pt-applications-906946643e74>, 미러 <https://huggingface.co/datasets/FatimahEmadEldin/infiniterep-physiotherapy>
- MM-Fit — <https://mmfit.github.io/>, <https://zenodo.org/records/7672767>

## 부록 — 2단계(폰 MediaPipe 방향별 오차)를 못 한 이유

`adb devices` 가 빈 목록을 반환했다(데몬은 정상 기동, `adb reconnect offline` 도 무응답). 폰이 연결돼 있지 않아 `run_device_replay.py` 경로 전체를 시작하지 못했다. 기존 작업 트리와 앱 설치 상태는 건드리지 않았다.
