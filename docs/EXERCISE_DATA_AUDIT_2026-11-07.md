# 종목 데이터 근거 감사 — 72종목 카탈로그 · 부하 프로필 · 기록 계산 (2026-11-07)

작성: 2026-11-07. 대상: `feature/exercise-expansion-46` HEAD `30255774` — **파일·행 번호는 전부 이 커밋 기준**이다. 감사 중 같은 작업 트리에 이 문서를 참조하는 **미커밋 변경**(`ExerciseLoadKind.kt` 신설, `SetEntryUi`·`ExerciseDetailScreen`·`RecordModels`·`SessionSetInput`·`ManualRecordSheet`·`TrexAppState`·`RecordScreen` 수정)이 생겼다 — 무엇을 덮고 무엇이 남는지는 §7. **이 감사는 코드를 바꾸지 않았다** — "근거 없이 말하는 것" 의 목록과 종목별 규칙 제안이다.

기준은 `CLAUDE.md` 원칙 #1(판정하지 않은 것을 판정한 것처럼 말하지 않는다), #2(검증된 것과 검증 중인 것을 같은 확신으로 말하지 않는다), #5(못 보는 것을 밝힌다). 여기서는 자세 엔진이 아니라 **기록·부하·칼로리 숫자**에 같은 기준을 댄다 — 화면에 kg·1RM·볼륨·kcal·피로 점수로 나오는 숫자 중 어떤 것이 측정이고, 어떤 것이 가정이며, 어떤 것은 뜻이 정해지지 않았는가.

## 0. 읽은 것 · 방법

| 읽은 파일 | 본 것 |
|---|---|
| `app/src/main/java/com/example/trex_kotlin/trainingload/MuscleLoad.kt` | 72 `p(id, name, sources, evidence, unit, body, equipment, muscles)` 프로필, `LoadSet`, `MuscleLoadEngine.doses/decay/score/recommendations` |
| `app/src/main/java/com/example/trex_kotlin/WorkoutCatalog.kt` | 72 `WorkoutTemplate(name, reps, duration, category, posture)` |
| `TrexData.kt` · `RecordModels.kt` · `WorkoutMath.kt` · `WorkoutSession.kt` | `Workout`·`UserProfile`·`WorkoutHistoryItem.calories`, `SetEntry`(kg·reps·seconds·cameraReps·rpe·memo·done)·`volumeKg`·`estimatedOneRepMax`(Epley)·`VolumeSummary`·`RecordStats.kcal`, `estimatedCalories`·`timing()`·`repsSpec()`·`resolvedTarget()`·`WorkoutTarget`·`timeOnlyNormalized()` |
| `SetEntryUi.kt` · `SessionSetInput.kt` · `ManualRecordSheet.kt` · `ExerciseDetailScreen.kt` · `SessionScreens.kt` · `TrexAppState.kt` · `MuscleLoadAdapter.kt` · `PostureSupport.kt` · `posture/ExerciseProfiles.kt` | kg 칸이 열리는 자리, 1RM·PR·볼륨 경로, kcal 이 보이는 자리, `LoadSet` 생성, 자세 교정 게이트(`cameraSupportedNames` 16) |
| `docs/MUSCLE_LOAD_IMPLEMENTATION.md`(§81) · `docs/MUSCLE_SESSION_FATIGUE_DESIGN.md`(§80) · `docs/MUSCLE_FATIGUE_FEASIBILITY.md` · `docs/EXERCISE_EXPANSION_46.md` §3 · 스펙 §80·§81·§130·§131 | 피로 상수의 출처 서술, 46종 판정 |

방법 — 스크래치 폴더의 파이썬 스크립트(저장소 밖, `audit.py`)가 두 Kotlin 파일을 정규식으로 읽어 §1 표와 §2 플래그 목록을 만들었다(72/72 일치, 카탈로그 `posture` 플래그와 `cameraSupportedNames` 16종 일치를 스크립트가 확인). §3~§6 은 코드를 직접 읽고 썼다. 웹 확인 두 건(Hevy 볼륨 규칙, 2011 Compendium MET)은 §5·§3 에 적었고, Hevy 도움말 페이지는 자동 접근이 막혀(403) **검색 요약으로만** 확인했다.

플래그 정의 —
- **F1 맨몸인데 kg** — `body=true` 인데 세트 입력의 kg 칸이 열려 있다. 이름으로 완전 체중 동작과 부분 체중 동작을 가른다(추정).
- **F2 근거 없음/약함** — `sources` 가 비었거나, `evidence` 에 추론·유추·적용·추정·편집·제품 초기값·유사 가 있거나, "동일 조건 EMG 미확보" 를 밝혔거나, 출처가 동료심사 아닌 자료(NASM·ACE·Mayo)뿐이다. evidence 원문을 그대로 인용한다.
- **F3 단위 불일치** — 카탈로그 `초` ↔ `LoadUnit.SECONDS`, PAIR/EACH ↔ 좌우 종목 이름, 교대 동작 ↔ PAIR/EACH 규칙.
- **F4 시간 종목인데 회 / 반대** — 버티기·운반 종목에 횟수 기본값, 또는 반대.
- **F5 kg 의미 없음** — `body=true` 인데 kg 을 적으면 Epley 1RM·PR·볼륨이 계산되는 종목(코드 경로 명시).

## 1. 72종목 표

열 설명 — **카탈로그 기본** `WorkoutCatalog.kt`의 `reps`; **자세 교정** `ExerciseProfiles.cameraSupportedNames`(= `postureSupported()`, 카탈로그 `posture` 플래그와 같은 16종 — 스크립트가 대조); **맨몸** `MuscleLoad.kt` `body=true`; **기구** `Equipment`; **단위** `LoadUnit`; **근육** 가중치 개수 · 최댓값; **근거 수** `sources` 개수(URL = PubMed 가 아닌 링크 수); **evidence** 앞 30자; **플래그** §2.

| # | 이름 | 카탈로그 기본 | 자세 교정 | 맨몸 | 기구 | 단위 | 근육 n · max | 근거 수 | evidence(30자) | 플래그 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 기본 스쿼트 | 10회 × 3세트 | ✓ | ✓ | NONE | REP | 7 · 1 | 1 | 바벨 스쿼트 연구를 맨몸 동작에 적용 | F1 F2 F5 |
| 2 | 런지 | 10회 × 3세트 | ✓ | ✓ | NONE | PAIR | 7 · 1 | 2 | 전방 런지 연구 · 부하/보폭 차이 | F1 F5 |
| 3 | 바벨 런지 | 10회 × 3세트 | ✓ | — | BARBELL | PAIR | 7 · 1 | 1 | 외부 중량 전방 런지 연구 · 중량 미입력 | — |
| 4 | 사이드 런지 | 10회 × 3세트 | ✓ | ✓ | NONE | PAIR | 6 · 0.9 | 2 | 측방 런지 관절역학 + 전방 런지 근활성에서 추론 | F1 F2 F5 |
| 5 | 크로스 런지 | 10회 × 3세트 | ✓ | ✓ | NONE | PAIR | 6 · 0.9 | 2 | 유사 런지 연구와 관절 작용에 근거한 추론 | F1 F2 F5 |
| 6 | 바벨 데드리프트 | 10회 × 3세트 | — | — | BARBELL | REP | 8 · 1 | 1 | 바벨 데드리프트 직접 연구 · 중량 미입력 | — |
| 7 | 굿모닝 | 12회 × 3세트 | ✓ | — | BARBELL | REP | 4 · 1 | 1 | 바벨 굿모닝 연구 · 중량/변형 차이 | — |
| 8 | 힙 쓰러스트 | 12회 × 3세트 | — | — | BENCH | REP | 5 · 1 | 1 | 바벨 힙 쓰러스트 연구 · 중량/벤치 조건 차이 | — |
| 9 | 의자 스쿼트 | 12회 × 3세트 | — | ✓ | NONE | REP | 7 · 1 | 1 | 앉았다 일어나기 EMG(Kim·Ju 2021) — 전경… | F1 F2 F5 |
| 10 | 고블릿 스쿼트 | 12회 × 3세트 | — | — | DUMBBELL | REP | 7 · 1 | 1 | 고블릿 스쿼트 직접 EMG(Collins 2021, 체… | F2 |
| 11 | 밴드 스쿼트 | 12회 × 3세트 | — | — | BAND | REP | 6 · 1 | 1 | 밴드 동일 조건 EMG 미확보 — 고블릿·바벨 스쿼트 … | F2 |
| 12 | 불가리안 스플릿 스쿼트 | 10회 × 3세트 | — | ✓ | NONE | PAIR | 7 · 1 | 1 | 불가리안 스플릿 스쿼트 EMG(2025, 훈련 여성 2… | F1 F3참고 F5 |
| 13 | 리버스 런지 | 10회 × 3세트 | — | ✓ | NONE | PAIR | 7 · 1 | 1(URL 1) | 맨몸 리버스 런지 EMG(2024, 20명) — 강화 … | F1 F2 F3참고 F5 |
| 14 | 스텝업 | 10회 × 3세트 | — | ✓ | NONE | PAIR | 7 · 1 | 1 | 측방 스텝업 EMG(Muyor 2020, 5RM 60%… | F1 F2 F3참고 F5 |
| 15 | 레그 프레스 | 12회 × 3세트 | — | — | LEG_PRESS | REP | 6 · 1 | 1 | 레그 프레스 변형 체계적 고찰(Martín-Fuente… | F2 |
| 16 | 덤벨 루마니안 데드리프트 | 12회 × 3세트 | — | — | DUMBBELL | REP | 7 · 1 | 1 | 바벨 RDL EMG(Coratella 2022, 80%… | F2 |
| 17 | 밴드 루마니안 데드리프트 | 12회 × 3세트 | — | — | BAND | REP | 6 · 1 | 1 | 밴드 동일 조건 EMG 미확보 — 바벨 RDL EMG(… | F2 |
| 18 | 시티드 레그 컬 | 15회 × 3세트 | — | — | LEG_CURL_MACHINE | REP | 2 · 1 | 1 | 시티드 레그 컬 훈련 MRI(Maeo 2021) — 햄… | F2 |
| 19 | 글루트 브릿지 | 15회 × 3세트 | — | ✓ | NONE | REP | 5 · 1 | 2 | 브리지 EMG(Khaiyat 2018 여성 12명; S… | F1 F5 |
| 20 | 싱글레그 글루트 브릿지 | 12회 × 3세트 | — | ✓ | NONE | PAIR | 5 · 1 | 1 | 싱글레그 브리지 EMG(Lehecka 2017, 28명… | F1 F3참고 F5 |
| 21 | 네발기기 힙 익스텐션 | 12회 × 3세트 | — | ✓ | NONE | PAIR | 5 · 1 | 1 | 네발기기 고관절 폄 세침 EMG(Selkowitz 20… | F1 F2 F3참고 F5 |
| 22 | 사이드 밴드 워크 | 15회 × 3세트 | — | — | BAND | PAIR | 5 · 1 | 2 | 밴드 사이드 스테핑 EMG(Lewis 2018, 22명… | F3참고 |
| 23 | 카프 레이즈 | 20회 × 3세트 | — | ✓ | NONE | REP | 3 · 1 | 1 | 스탠딩 카프 레이즈 훈련 MRI(Kinoshita·Ma… | F1 F2 F5 |
| 24 | 오버헤드 프레스 | 10회 × 3세트 | — | — | DUMBBELL | REP | 6 · 1 | 1 | 프레스 직접 연구 · 덤벨/바벨 및 방향 차이 | — |
| 25 | 랫풀 다운 | 12회 × 3세트 | — | — | LAT_MACHINE | REP | 5 · 1 | 1 | 랫풀 다운 직접 연구 · 그립 차이 | — |
| 26 | 딥스 | 10회 × 3세트 | — | ✓ | DIP_BAR | REP | 5 · 1 | 1 | 바 딥스 직접 연구 · 벤치 딥스와 구분 | F1(완전) F5 |
| 27 | 덤벨 컬 | 12회 × 3세트 | ✓ | — | DUMBBELL | REP | 2 · 1 | 2 | 컬 근활성 + 상완근 해부 연구 | — |
| 28 | 바벨 컬 | 12회 × 3세트 | ✓ | — | BARBELL | REP | 2 · 1 | 2 | 바벨/EZ바 컬 직접 연구 | — |
| 29 | 사이드 레터럴 레이즈 | 12회 × 3세트 | ✓ | — | DUMBBELL | REP | 3 · 1 | 1 | 측면 들기 직접 연구 · 어깨는 한 근육군으로 표시 | — |
| 30 | 프런트 레이즈 | 12회 × 3세트 | ✓ | — | DUMBBELL | REP | 4 · 1 | 1 | 전면 들기 직접 연구 · 어깨는 한 근육군으로 표시 | — |
| 31 | 업라이트로우 | 12회 × 3세트 | — | — | BARBELL | REP | 4 · 1 | 1 | 업라이트로우 직접 연구 · 그립 폭 차이 | — |
| 32 | 푸쉬업 | 12회 × 3세트 | ✓ | ✓ | NONE | REP | 5 · 1 | 2 | 일반 푸쉬업 직접 연구 | F1 F5 |
| 33 | 니 푸쉬업 | 10회 × 3세트 | — | ✓ | NONE | REP | 5 · 0.8 | 2 | 일반/무릎 플러스 변형 연구 · 일반 니 푸쉬업에 추론 | F1 F2 F5 |
| 34 | Y 레이즈 | 12회 × 3세트 | — | — | NONE | REP | 3 · 1 | 1 | 견갑면 팔 올리기 연구 · 자세/지지 조건에 따른 추론 | F1? F2 |
| 35 | 벽 푸쉬업 | 12회 × 3세트 | — | ✓ | NONE | REP | 7 · 0.75 | 2 | 벽 푸시업 플러스 EMG(Ludewig 2004 — 전… | F1 F2 F5 |
| 36 | 인클라인 푸쉬업 | 12회 × 3세트 | — | ✓ | NONE | REP | 7 · 0.8 | 1 | 인클라인 푸시업 포함 변형 고찰(Kowalski 202… | F1 F5 |
| 37 | 파이크 푸쉬업 | 10회 × 3세트 | — | ✓ | NONE | REP | 7 · 1 | 2(URL 1) | 파이크 푸시업 독립 EMG 미확보 — NASM 동작 설… | F1 F2 F5 |
| 38 | 덤벨 벤치 프레스 | 12회 × 3세트 | — | — | BENCH | REP | 5 · 1 | 1 | 덤벨 벤치 프레스 직접 EMG(Saeterbakken … | — |
| 39 | 덤벨 플로어 프레스 | 12회 × 3세트 | — | — | DUMBBELL | REP | 4 · 1 | 1 | 덤벨 벤치 EMG(Saeterbakken 2011)를 … | F2 |
| 40 | 머신 체스트 프레스 | 12회 × 3세트 | — | — | CHEST_PRESS_MACHINE | REP | 5 · 1 | 2(URL 1) | 시티드 머신 자체 실험 미확보 — NASM 설명 + 스… | F2 |
| 41 | 밴드 체스트 프레스 | 12회 × 3세트 | — | — | BAND | REP | 7 · 1 | 1 | 밴드 동일 조건 EMG 미확보 — 서서 케이블 프레스(… | F2 |
| 42 | 밴드 숄더 프레스 | 12회 × 3세트 | — | — | BAND | REP | 6 · 1 | 1 | 밴드 동일 조건 EMG 미확보 — 덤벨/바벨 숄더 프레… | F2 |
| 43 | 원암 덤벨 로우 | 12회 × 3세트 | — | — | DUMBBELL | PAIR | 9 · 1 | 2 | 원암 케이블 로우(Fenwick 2009)·시티드 로우… | F2 F3참고 |
| 44 | 밴드 로우 | 15회 × 3세트 | — | — | BAND | REP | 8 · 1 | 2 | 밴드 동일 조건 EMG 미확보 — 시티드/원암 로우 E… | F2 |
| 45 | 시티드 케이블 로우 | 12회 × 3세트 | — | — | ROW_MACHINE | REP | 8 · 1 | 1 | 시티드 로우 직접 EMG(Lehman 2004) — 광… | — |
| 46 | 인버티드 로우 | 12회 × 3세트 | — | ✓ | RACK | REP | 10 · 1 | 2(URL 1) | 인버티드 로우 직접 EMG(Fenwick 2009 — … | F1(완전) F5 |
| 47 | 풀업 | 8회 × 3세트 | — | ✓ | PULLUP_BAR | REP | 8 · 1 | 1 | 오버핸드 풀업 직접 EMG(Youdas 2010, 25… | F1(완전) F5 |
| 48 | 케이블 페이스 풀 | 15회 × 3세트 | — | — | CABLE | REP | 6 · 1 | 1(URL 1) | 로프 페이스 풀 독립 EMG 미확보 — NASM 동작 … | F2 |
| 49 | 밴드 리버스 플라이 | 15회 × 3세트 | — | — | BAND | REP | 5 · 1 | 1 | 밴드 리버스 플라이 직접 EMG(Bergquist 20… | F2 |
| 50 | I-Y-T 레이즈 | 6회 × 3세트 | — | ✓ | NONE | REP | 5 · 1 | 1(URL 1) | ACE 의뢰 등 운동 EMG(2018, I-Y-T 포함… | F1 F2 F5 |
| 51 | 케이블 트라이셉스 푸시다운 | 15회 × 3세트 | — | — | CABLE | REP | 4 · 1 | 1 | 삼두 푸시다운 직접 EMG(Hussain 2020, 2… | — |
| 52 | 플랭크 | 45초 × 3세트 | ✓ | ✓ | NONE | SECONDS | 6 · 1 | 1 | 플랭크 직접 연구 · 버틴 시간 기준 | F1 |
| 53 | 무릎 플랭크 | 25초 × 3세트 | — | ✓ | NONE | SECONDS | 6 · 0.8 | 2(URL 1) | 무릎 지지 직접 EMG 미확보 — 앞 브리지 EMG(G… | F1 F2 |
| 54 | 사이드 플랭크 | 30초 × 3세트 | — | ✓ | NONE | SECONDS | 7 · 1 | 2 | 사이드 브리지 직접 EMG(García-Vaquero … | F1 F3참고 |
| 55 | 무릎 사이드 플랭크 | 25초 × 3세트 | — | ✓ | NONE | SECONDS | 7 · 0.8 | 3(URL 1) | 무릎 지지 직접 EMG 미확보 — 사이드 브리지 EMG… | F1 F2 F3참고 |
| 56 | 데드버그 | 8회 × 3세트 | — | ✓ | NONE | PAIR | 4 · 1 | 1 | 데드버그 직접 EMG(Souza 2001, 12명) —… | F1 F3 F4참고 F5 |
| 57 | 버드독 | 8회 × 3세트 | — | ✓ | NONE | PAIR | 7 · 1 | 2 | 버드독 직접 EMG(Souza 2001; García-… | F1 F3 F5 |
| 58 | 팔로프 프레스 | 12회 × 3세트 | — | — | BAND | PAIR | 6 · 1 | 1(URL 1) | 팔로프 프레스 기술 해설(Mullane·Turner·B… | F2 F3참고 F4참고 |
| 59 | 파머스 캐리 | 40초 × 3세트 | — | — | DUMBBELL | SECONDS | 9 · 1 | 1 | 로디드 캐리 직접 EMG(Ellestad 2024) —… | F2 |
| 60 | 수트케이스 캐리 | 30초 × 3세트 | — | — | DUMBBELL | SECONDS | 10 · 1 | 2 | 로디드 캐리 EMG(Ellestad 2024) + 요방… | F3참고 |
| 61 | 싱글레그 애브도미널 프레스 | 15초 × 3세트 | — | ✓ | NONE | SECONDS | 4 · 0.8 | 1(URL 1) | 손-무릎 누르기 직접 EMG 미확보 — Mayo Cli… | F1 F2 F3참고 |
| 62 | 스탠딩 사이드 크런치 | 12회 × 3세트 | ✓ | ✓ | NONE | EACH | 3 · 1 | 1 | 체간 측굴 연구를 서서 수행하는 동작에 적용 | F1 F2 F5 |
| 63 | 스탠딩 니업 | 12회 × 3세트 | ✓ | ✓ | NONE | EACH | 4 · 1 | 2 | 다리 들기/고관절 굴곡 연구에서 추론 | F1 F2 F5 |
| 64 | 행잉 레그 레이즈 | 10회 × 3세트 | — | — | PULLUP_BAR | REP | 5 · 1 | 2 | 누운 다리 들기 연구 + 매달리기 관절 작용에서 추론 | F1? F2 |
| 65 | 크런치 | 15회 × 3세트 | ✓ | ✓ | NONE | REP | 2 · 1 | 1 | 컬업/체간 굴곡 직접 연구 | F1 F5 |
| 66 | 레그 레이즈 | 12회 × 3세트 | ✓ | ✓ | NONE | REP | 4 · 1 | 2 | 누운 다리 들기 직접 연구 | F1 F5 |
| 67 | 시저 크로스 | 20회 × 3세트 | — | ✓ | NONE | EACH | 5 · 0.8 | 2 | 다리 들기 연구 + 교차 내전 동작의 해부학적 추론 | F1 F2 F5 |
| 68 | 리버스 크런치 | 12회 × 3세트 | — | ✓ | NONE | REP | 3 · 1 | 1 | 리버스 크런치 직접 EMG(Escamilla 2006,… | F1 F5 |
| 69 | 바이시클 크런치 | 20회 × 3세트 | — | ✓ | NONE | EACH | 3 · 1 | 1(URL 1) | ACE/SDSU 복근 13종 EMG(2001, 30명)… | F1 F2 F5 |
| 70 | 짐볼 크런치 | 15회 × 3세트 | — | ✓ | STABILITY_BALL | REP | 2 · 1 | 1 | 짐볼 크런치 직접 EMG(Sternlicht 2007,… | F1 F5 |
| 71 | 캡틴체어 니 레이즈 | 12회 × 3세트 | — | ✓ | DIP_BAR | REP | 6 · 1 | 2(URL 1) | ACE/SDSU 2001 캡틴체어 + 스트랩 니업 EM… | F1(완전) F2 F5 |
| 72 | 러시안 트위스트 | 20회 × 3세트 | — | ✓ | NONE | EACH | 4 · 1 | 1(URL 1) | 발 지지·무중량 조건 직접 EMG 미확보 — NASM … | F1 F2 F5 |

### 집계

| 항목 | 수 |
|---|---|
| 종목 | 72 (하체 23 · 상체 28 · 코어 10 · 복근 11) |
| 자세 교정 지원(`cameraSupportedNames`) | 16 |
| 맨몸(`body=true`) | 38 |
| 단위 | EACH 5 · PAIR 14 · REP 46 · SECONDS 7 |
| 기구 | NONE 34 · DUMBBELL 10 · BAND 8 · BARBELL 5 · BENCH 2 · DIP_BAR 2 · PULLUP_BAR 2 · CABLE 2 · LAT_MACHINE 1 · LEG_PRESS 1 · LEG_CURL_MACHINE 1 · ROW_MACHINE 1 · CHEST_PRESS_MACHINE 1 · RACK 1 · STABILITY_BALL 1 |
| 출처가 PubMed 만인 종목 / URL 포함 / 비동료심사(NASM·ACE·Mayo)만 | 59 / 13 / 5 |
| 최대 가중치 < 1.0 인 종목 | 9 |

## 2. 플래그

### F1 `맨몸인데 kg` — body=true 인데 kg 칸이 열려 있음 (38종)

kg 칸은 `SetEntryUi.kt:69` `SetRow(showKg = true)` 가 기본이고 `showKg=false` 를 넘기는 호출이 **없다**(휴식 화면 `SessionSetInputCard`·완료 화면 `SessionFinalSetsCard`·수동 기록 `ManualRecordSheet` 전부). KDoc 이 "맨몸·시간 종목도 kg 을 적을 수 있어 기본 참" 이라고 적어 둔 **의도된 상태**다. 적은 kg 은 `SetEntry.volumeKg = kg × reps`(`RecordModels.kt:39`)·PR(`isPersonalRecord`)·최고 무게·1RM 에 그대로 들어가고, 체중은 더해지지 않는다. 근육 부하 엔진은 kg 입력을 읽지 않는다(`LoadSet.weightKg` 는 프로필 체중, `MuscleLoad.kt:237-239`).

- **완전 체중 동작(후보 — 이름으로 판단, 추정)**: 딥스 · 인버티드 로우 · 풀업 · 캡틴체어 니 레이즈
- **부분 체중 동작**: 기본 스쿼트 · 런지 · 사이드 런지 · 크로스 런지 · 의자 스쿼트 · 불가리안 스플릿 스쿼트 · 리버스 런지 · 스텝업 · 글루트 브릿지 · 싱글레그 글루트 브릿지 · 네발기기 힙 익스텐션 · 카프 레이즈 · 푸쉬업 · 니 푸쉬업 · 벽 푸쉬업 · 인클라인 푸쉬업 · 파이크 푸쉬업 · I-Y-T 레이즈 · 플랭크 · 무릎 플랭크 · 사이드 플랭크 · 무릎 사이드 플랭크 · 데드버그 · 버드독 · 싱글레그 애브도미널 프레스 · 스탠딩 사이드 크런치 · 스탠딩 니업 · 크런치 · 레그 레이즈 · 시저 크로스 · 리버스 크런치 · 바이시클 크런치 · 짐볼 크런치 · 러시안 트위스트
- **맨몸 여부 불일치 후보(`body=false` 인데 외부 중량이 없는 기구)**: Y 레이즈(`NONE`) · 행잉 레그 레이즈(`PULLUP_BAR`)

### F2 `근거 없음/약함` — evidence 에 추론·유추·적용·추정·편집·제품 초기값·유사 가 있거나, 동일 조건 EMG 미확보, 또는 출처가 비동료심사 자료뿐 (39종)

`sources` 가 빈 카탈로그 종목은 없다(직접 추가 종목 `customProfile` 만 `sources = emptyList()`, evidence "직접 추가한 종목 — 고른 주 근육으로만 대략 추정, 연구 출처 없음"). 아래는 evidence 원문 그대로다. 분류 — **a** 키워드(추론·적용·추정·유사 …), **b** "동일 조건 EMG 미확보" 명시, **c** 출처가 NASM·ACE·Mayo 뿐(동료심사 아님).

| 이름 | 분류 | 출처 | evidence(원문) |
|---|---|---|---|
| 기본 스쿼트 | a | PubMed 19075302 | 바벨 스쿼트 연구를 맨몸 동작에 적용 |
| 사이드 런지 | a | PubMed 23584319, PubMed 19321909 | 측방 런지 관절역학 + 전방 런지 근활성에서 추론 |
| 크로스 런지 | a | PubMed 32236133, PubMed 19321909 | 유사 런지 연구와 관절 작용에 근거한 추론 |
| 의자 스쿼트 | a | PubMed 33728287 | 앉았다 일어나기 EMG(Kim·Ju 2021) — 전경골근이 대퇴직근보다 높았다(모든 의자 높이); 대둔근은 미측정, 관절 작용 추론 |
| 고블릿 스쿼트 | a | PubMed 34341315 | 고블릿 스쿼트 직접 EMG(Collins 2021, 체중 30% 부하) — 광근·햄스트링 측정; 대둔근·내전근·몸통은 추론 · 중량 미입력 |
| 밴드 스쿼트 | ab | PubMed 34341315 | 밴드 동일 조건 EMG 미확보 — 고블릿·바벨 스쿼트 EMG(Collins 2021)에서 추론 · 밴드 장력 미입력 |
| 리버스 런지 | a | www.mdpi.com | 맨몸 리버스 런지 EMG(2024, 20명) — 강화 수준은 앞다리 대퇴직근·지지다리 중둔근뿐, 대둔근은 미달; 광근·내전근·몸통은 추론 |
| 스텝업 | a | PubMed 32236133 | 측방 스텝업 EMG(Muyor 2020, 5RM 60%)를 정면 스텝업에 적용 — 광근·중둔근·대둔근 순 |
| 레그 프레스 | a | PubMed 32605065 | 레그 프레스 변형 체계적 고찰(Martín-Fuentes 2020) — 광근·대퇴직근·대퇴이두근·비복근 종합; 둔근·내전근은 추론 · 머신 표시 중량 미입력 |
| 덤벨 루마니안 데드리프트 | a | PubMed 35162922 | 바벨 RDL EMG(Coratella 2022, 80% 1RM)를 덤벨에 적용 — 대둔근·중둔근·햄스트링·기립근 직접 · 중량 미입력 |
| 밴드 루마니안 데드리프트 | ab | PubMed 35162922 | 밴드 동일 조건 EMG 미확보 — 바벨 RDL EMG(Coratella 2022)에서 추론 · 밴드 장력 미입력 |
| 시티드 레그 컬 | a | PubMed 33009197 | 시티드 레그 컬 훈련 MRI(Maeo 2021) — 햄스트링 개별 체적; 비복근은 추론 · 머신 표시 중량 미입력 |
| 네발기기 힙 익스텐션 | a | PubMed 23160432 | 네발기기 고관절 폄 세침 EMG(Selkowitz 2013) — 중둔근·상부 대둔근·대퇴근막장근; 햄스트링·몸통은 추론 |
| 카프 레이즈 | a | PubMed 38156065 | 스탠딩 카프 레이즈 훈련 MRI(Kinoshita·Maeo 2023, 공동 제1저자) — 비복근 내외측두·가자미근 체적; 균형 근육은 추론 |
| 니 푸쉬업 | a | PubMed 20664364, PubMed 27693098 | 일반/무릎 플러스 변형 연구 · 일반 니 푸쉬업에 추론 |
| Y 레이즈 | a | PubMed 23068897 | 견갑면 팔 올리기 연구 · 자세/지지 조건에 따른 추론 |
| 벽 푸쉬업 | a | PubMed 14977678, PubMed 35599715 | 벽 푸시업 플러스 EMG(Ludewig 2004 — 전거근·상부 승모근만) + 푸시업 변형 고찰(Kowalski 2022); 대흉근·삼두근은 푸시업 문헌 적용, 가장 낮은 부하 |
| 파이크 푸쉬업 | ab | www.nasm.org, PubMed 35599715 | 파이크 푸시업 독립 EMG 미확보 — NASM 동작 설명 + 푸시업 변형 고찰에서 추론 |
| 덤벨 플로어 프레스 | a | PubMed 21225489 | 덤벨 벤치 EMG(Saeterbakken 2011)를 바닥이 제한하는 가동 범위에 적용 · 중량 미입력 |
| 머신 체스트 프레스 | ab | www.nasm.org, PubMed 21225489 | 시티드 머신 자체 실험 미확보 — NASM 설명 + 스미스/바벨/덤벨 프레스 EMG에서 추론 · 머신 표시 중량 미입력 |
| 밴드 체스트 프레스 | ab | PubMed 18076235 | 밴드 동일 조건 EMG 미확보 — 서서 케이블 프레스(Santana 2007)·푸시업 문헌에서 추론 · 밴드 장력 미입력 |
| 밴드 숄더 프레스 | ab | PubMed 23096062 | 밴드 동일 조건 EMG 미확보 — 덤벨/바벨 숄더 프레스 EMG(Saeterbakken 2013)에서 추론 · 밴드 장력 미입력 |
| 원암 덤벨 로우 | a | PubMed 19620925, PubMed 15228624 | 원암 케이블 로우(Fenwick 2009)·시티드 로우(Lehman 2004) EMG에서 추론 — 광배근·이두근·중부 승모근/능형근 · 중량 미입력 |
| 밴드 로우 | ab | PubMed 15228624, PubMed 19620925 | 밴드 동일 조건 EMG 미확보 — 시티드/원암 로우 EMG에서 추론 · 밴드 장력 미입력 |
| 케이블 페이스 풀 | bc | www.nasm.org | 로프 페이스 풀 독립 EMG 미확보 — NASM 동작 설명(후면 삼각근·능형근·중부 승모근·극하근) · 머신 표시 중량 미입력 |
| 밴드 리버스 플라이 | a | PubMed 29599855 | 밴드 리버스 플라이 직접 EMG(Bergquist 2018, 10RM) — 후면·중간 삼각근·상부 승모근; 능형근은 추론 · 밴드 장력 미입력 |
| I-Y-T 레이즈 | c | www.acefitness.org | ACE 의뢰 등 운동 EMG(2018, I-Y-T 포함, 19명) — 중·하부 승모근·극하근 직접; 동료심사 논문과 구분 |
| 무릎 플랭크 | ab | PubMed 22436839, www.mayoclinic.org | 무릎 지지 직접 EMG 미확보 — 앞 브리지 EMG(García-Vaquero 2012)에서 추론, 일반 플랭크보다 낮은 부하 |
| 무릎 사이드 플랭크 | ab | PubMed 22436839, PubMed 11415616, www.mayoclinic.org | 무릎 지지 직접 EMG 미확보 — 사이드 브리지 EMG(García-Vaquero 2012)·요방형근 세침(McGill 1996)에서 추론 |
| 팔로프 프레스 | a | doi.org | 팔로프 프레스 기술 해설(Mullane·Turner·Bishop 2021 SCJ) — 새 EMG 실험 아님; 회전 저항 역할을 정성 적용 · 밴드 장력 미입력 |
| 파머스 캐리 | a | PubMed 38665162 | 로디드 캐리 직접 EMG(Ellestad 2024) — 복직근·외복사근·최장근·다열근 표면 EMG; 그립·승모근·보행은 추론 · 중량 미입력 |
| 싱글레그 애브도미널 프레스 | bc | www.mayoclinic.org | 손-무릎 누르기 직접 EMG 미확보 — Mayo Clinic 동작 정의(복직근·복사근 등척, 고관절 굽힘근) |
| 스탠딩 사이드 크런치 | a | PubMed 12937449 | 체간 측굴 연구를 서서 수행하는 동작에 적용 |
| 스탠딩 니업 | a | PubMed 9118976, PubMed 34455371 | 다리 들기/고관절 굴곡 연구에서 추론 |
| 행잉 레그 레이즈 | a | PubMed 9118976, PubMed 27065536 | 누운 다리 들기 연구 + 매달리기 관절 작용에서 추론 |
| 시저 크로스 | a | PubMed 9118976, PubMed 20625774 | 다리 들기 연구 + 교차 내전 동작의 해부학적 추론 |
| 바이시클 크런치 | c | www.acefitness.org | ACE/SDSU 복근 13종 EMG(2001, 30명) — 복직근 1위·복사근 2위; 기관 보고, 동료심사 논문과 구분 |
| 캡틴체어 니 레이즈 | a | www.acefitness.org, PubMed 16649890 | ACE/SDSU 2001 캡틴체어 + 스트랩 니업 EMG(Escamilla 2006)에서 추론 — 고관절 굽힘근·복직근 · 기구는 딥스 바(캡틴체어 겸용) 보유로 가늠 |
| 러시안 트위스트 | bc | www.nasm.org | 발 지지·무중량 조건 직접 EMG 미확보 — NASM 동작 설명(외·내복사근·복직근) |

키워드별: '추론' 27 · '유추' 0 · '적용' 7 · '추정' 0 · '편집' 0 · '제품 초기값' 0 · '유사' 1 · '미확보' 12.

**F2 밖(evidence 에 키워드 없음, 직접 연구 주장)**: 런지 · 바벨 런지 · 바벨 데드리프트 · 굿모닝 · 힙 쓰러스트 · 불가리안 스플릿 스쿼트 · 글루트 브릿지 · 싱글레그 글루트 브릿지 · 사이드 밴드 워크 · 오버헤드 프레스 · 랫풀 다운 · 딥스 · 덤벨 컬 · 바벨 컬 · 사이드 레터럴 레이즈 · 프런트 레이즈 · 업라이트로우 · 푸쉬업 · 인클라인 푸쉬업 · 덤벨 벤치 프레스 · 시티드 케이블 로우 · 인버티드 로우 · 풀업 · 케이블 트라이셉스 푸시다운 · 플랭크 · 사이드 플랭크 · 데드버그 · 버드독 · 수트케이스 캐리 · 크런치 · 레그 레이즈 · 리버스 크런치 · 짐볼 크런치.

### F3 `단위 불일치` (엄격 2종 · 참고 12종)

검사 — ① 카탈로그 `초` ↔ `LoadUnit.SECONDS` 불일치, ② PAIR/EACH 인데 이름이 좌우 종목이 아님, ③ 좌우 종목인데 REP, ④ 교대 동작인데 PAIR(문서 §130 규칙 "한쪽씩 = PAIR, 교대 = EACH" 와 어긋남). ①~③ 은 **0건** — 카탈로그 `초` 7종이 정확히 SECONDS 7종이고, PAIR 14·EACH 5 는 전부 좌우 이름, REP 46 에 좌우 이름이 없다.

- **데드버그** — 교대인데 PAIR. 단위 `PAIR`, 카탈로그 `8회 × 3세트`. 같은 '8회' 를 적어도 PAIR 는 쪽당 8/12, 바이시클 크런치(EACH)는 쪽당 8/24 — 같은 교대 동작에 쪽당 부하가 2배 차이(`MuscleLoad.kt:245-246`).
- **버드독** — 교대인데 PAIR. 단위 `PAIR`, 카탈로그 `8회 × 3세트`. 같은 '8회' 를 적어도 PAIR 는 쪽당 8/12, 바이시클 크런치(EACH)는 쪽당 8/24 — 같은 교대 동작에 쪽당 부하가 2배 차이(`MuscleLoad.kt:245-246`).

**참고(불일치는 아니나 의미가 화면에 없음)** — 카메라 없는 PAIR 10종(아래 8 + 위 엄격 항목의 데드버그·버드독)은 '좌우 한 번씩 = 1회' 안내(`SIDE_PAIR_UNIT_HINT`, 런지류 `ExerciseProfiles.repUnit` 경로에만)가 없어, 사용자가 적는 '12회' 를 엔진은 **쪽당 12**(`l = r = n/12`)로 읽는다 — 합계로 적으면 2배: 불가리안 스플릿 스쿼트 · 리버스 런지 · 스텝업 · 싱글레그 글루트 브릿지 · 네발기기 힙 익스텐션 · 사이드 밴드 워크 · 원암 덤벨 로우 · 팔로프 프레스. SECONDS 좌우 종목(사이드 플랭크 · 무릎 사이드 플랭크 · 수트케이스 캐리 · 싱글레그 애브도미널 프레스)은 어느 쪽인지 몰라 좌우 같은 부하(문서 §130 명시) — 한쪽 30초 × 3세트를 양쪽 30초로 적는다.

### F4 `시간 종목인데 회` / 그 반대 (엄격 0종 · 참고 2종)

플랭크 3종·캐리 2종·싱글레그 애브도미널 프레스는 카탈로그가 `초` 이고 단위도 SECONDS — 엄격 불일치 **0건**. 참고: 데드버그(`8회 × 3세트`, 홀드 변형이 흔한 종목 — 회로 둔 것은 선택이지 오류가 아님) · 팔로프 프레스(`12회 × 3세트`, 홀드 변형이 흔한 종목 — 회로 둔 것은 선택이지 오류가 아님). 파머스 캐리 `40초 × 3세트` 는 시간 기준(거리 기준 관행과 다르지만 엔진 단위와는 일치).

### F5 `kg 의미 없음` — body=true 인데 kg 을 적으면 Epley 1RM 이 계산되는 종목 (33종)

코드 경로: `ExerciseDetailScreen.kt:37-38` `best = bestKg(name)` → `oneRm = estimatedOneRepMax(kg, reps)`(`RecordModels.kt:81`, 회 1~10 만) — **`bodyweight` 를 보지 않는다.** 같은 kg 이 '최고 무게' 타일·PR 띠(`isPersonalRecord`)·볼륨(`volumeKg`)에도 들어간다. 맨몸 종목에서 kg 은 '추가 중량' 인지 '체중' 인지 정의가 없다 — 푸쉬업에 체중 70 을 적으면 1RM 93 kg, 볼륨 700 kg 이 그대로 표시된다.

- 대상(REP/PAIR/EACH): 기본 스쿼트 · 런지 · 사이드 런지 · 크로스 런지 · 의자 스쿼트 · 불가리안 스플릿 스쿼트 · 리버스 런지 · 스텝업 · 글루트 브릿지 · 싱글레그 글루트 브릿지 · 네발기기 힙 익스텐션 · 카프 레이즈 · 딥스 · 푸쉬업 · 니 푸쉬업 · 벽 푸쉬업 · 인클라인 푸쉬업 · 파이크 푸쉬업 · 인버티드 로우 · 풀업 · I-Y-T 레이즈 · 데드버그 · 버드독 · 스탠딩 사이드 크런치 · 스탠딩 니업 · 크런치 · 레그 레이즈 · 시저 크로스 · 리버스 크런치 · 바이시클 크런치 · 짐볼 크런치 · 캡틴체어 니 레이즈 · 러시안 트위스트
- SECONDS 맨몸 5종(플랭크 · 무릎 플랭크 · 사이드 플랭크 · 무릎 사이드 플랭크 · 싱글레그 애브도미널 프레스)은 `reps` 가 null 이라 1RM·볼륨·PR 모두 계산되지 않고, 적은 kg 은 요약 줄(`summaryLine` "20 kg × 45초")에만 남는다.

## 3. kcal — `estimatedCalories` 가 하는 계산

`WorkoutMath.kt:40-50`:

```kotlin
fun Workout.estimatedCalories(elapsedSeconds: Int? = null): Int {
    val multiplier = when (category) { "유산소" -> 8; "하체" -> 7; "상체" -> 6; "코어", "복근" -> 5; else -> 4 }
    val timing = timing()
    return ((elapsedSeconds ?: (timing.workSeconds * timing.sets)).coerceAtLeast(0) / 60.0 * multiplier).roundToInt().coerceAtLeast(0)
}
```

- **식** = 분 × 분류별 상수(kcal/분). 상수는 분류 다섯 값뿐이고 **MET 표·체중·성별·나이·강도·휴식 어느 것도 들어가지 않는다.** 50 kg 과 100 kg 사용자가 같은 수를 본다. 하체 15분 = 105 kcal.
- **분** — 호출처가 전부 초를 넘긴다(계획 시간 폴백 `workSeconds × sets` 는 현재 호출처가 없다). 세션은 세트 **가동 시간**(`workDurations` — 준비·휴식 제외, `SessionScreens.kt:228`·`TrexData.kt:933`·`TrexApp.kt:493`), 수동 기록은 `seconds.coerceAtLeast(done.size * 30)`(`TrexAppState.kt:945`) — 시간을 적지 않은 횟수 세트는 **세트당 30초** 로 친다(하체 3세트 = 1.5분 × 7 ≈ 11 kcal). 이 30초 가정은 어디에도 적혀 있지 않다.
- **보이는 자리** — 완료 화면 타일 "예상 소모"(`SessionScreens.kt:273`), 기록 통계 "소모 · 추정"(`RecordScreen.kt:255`, `RecordStats.kcal` = Σ `WorkoutHistoryItem.calories`), 오운완 카드 "예상 kcal"(`community/WorkoutCard.kt:89`, 완료 화면 값을 그대로 받음). 홈에는 소모 kcal 이 없다(섭취 kcal 만). 라벨은 '예상/추정' 이지만 **근거를 설명하는 문장은 앱 어디에도 없다**(ⓘ 없음).
- **출처** — 없음. 상수는 커밋 `91989b69`(2026-04-27 "운동 기록과 식단 최근 기록 개선", 메시지에 근거 없음)에 처음 등장했고 `64e0a245`(2026-08-24)에서 `WorkoutMath.kt` 로 옮겨졌다. `docs/`·스펙에 `estimatedCalories`·MET 를 설명하는 절이 없다(스펙 1257행은 런지 페이스 변경으로 "칼로리 추정이 런지 몫만큼 두 배" 가 된다고만 적는다). **판정: 근거 없음.**
- **참고 비교(채택 제안이 아니라 자릿수 확인)** — 표준 계산은 kcal/분 = MET × 3.5 × 체중(kg) / 200 이고, 2011 Compendium(Ainsworth 2011, [PubMed 21681120](https://pubmed.ncbi.nlm.nih.gov/21681120/))의 저항운동 값은 격렬한 웨이트 6.0 MET(02050) · 8~15회 다종목 3.5 MET(02054) · 스쿼트 5.0 MET(02052). 70 kg 이면 4.3~7.4 kcal/분이라 지금 상수 5~7 은 **70 kg 한 사람의 자릿수 안**이지만, 50 kg 은 3.1~5.3, 100 kg 은 6.1~10.5 다. "하체 7 > 상체 6 > 코어 5" 의 분류 순서 자체는 인용 근거가 없다.

## 4. 피로 엔진 상수 — 값과 출처 상태

코드 정본 `MuscleLoad.kt`, 설계 `docs/MUSCLE_SESSION_FATIGUE_DESIGN.md`(§80), 구현 `docs/MUSCLE_LOAD_IMPLEMENTATION.md`(§81). §80 §2 가 **"12회 기준, 근육 가중치, 체격 보정 지수, 6/24시간 반감기, 0–100 변환 계수는 논문에서 검증된 상수가 아니다"** 라고 못박았고, §130 §3 은 감쇠·점수를 유지하기로 판정했다(여기서 다시 다투지 않는다 — `docs/EXERCISE_EXPANSION_46.md` §3). 아래는 그 판정 위에서 **상수마다 출처가 어디에 어떻게 적혀 있는가**다.

| 상수 | 값 | 코드 | 문서의 서술 | 상태 |
|---|---|---|---|---|
| 반복 기준량 | n / 12 | `:235` | §80 §4.1 "표시 스케일을 위한 기준 반복 수. 생리학적 최적 반복 수라는 뜻이 아니다" | 가정(출처 없음) |
| 시간 기준량 | 초 / 45 | `:235` | §81 §2 "상수 전부는 제품 초기값" (45 = 플랭크 카탈로그 기본 45초와 같은 수) | 가정 |
| 맨몸 체격 보정 | B = ((kg/70)·(cm/170))^0.25, clamp 0.85~1.15 | `:239` | §80 §4.2 "0.25 지수와 ±15% 제한, 70kg/170cm 기준은 설계 가정" | 가정 |
| 기구 운동 보정 | B = 1 | `:239` | §80 §4.2 "체중으로 덤벨 무게를 만들어내지 않는다" | 정책 |
| 체중·키 유효 범위 / 폴백 | 20~350 kg · 80~250 cm / 70 kg · 170 cm | `:237-238` | 없음 | 가정 — `UserProfile` 기본 체중은 **65 kg**(`TrexData.kt:39`), `LoadSet` 기본은 70(`:210`); 기본 프로필 사용자의 B = (65/70)^0.25 ≈ 0.98 |
| EACH 좌우 절반 · extra 걸음 절반 | /2 | `:244-246` | §81 단위 정책 3·5 "교대 동작으로 보고 양쪽에 절반씩 … 불확실성을 안내" | 가정(명시됨) |
| 감쇠 | K(h) = 0.35·2^(−h/6) + 0.65·2^(−h/24) | `:249` | §80 §4.4 "기본 a=0.35, hFast=6, hSlow=24. 반감기이며 완전 회복 시간은 아니다"; 방향 근거 5편(González-Badillo 2016, Morán-Navarro 2017, Pareja-Blanco 2020, Refalo 2023, Looft 2018); §130 §3 "6 h/24 h 반감기는 그 범위 안의 가정값" | **방향 출처 있음 · 값은 가정** |
| 점수 | F = 100·(1 − e^(−L/6)) | `:250` | §80 §2 "0–100 변환 계수는 … 검증된 상수가 아니다" | 가정 |
| 노력도 | E = 1 (설계의 0.7 / 1 / 1.3 시나리오) | 코드 없음 | §80 §4.5 "95% 신뢰구간도 개인차의 상·하한도 아니다"; RIR 입력 전 | 가정(미구현) |
| 역할 가중치 | 0.1~1.0, 종목당 2~10개 | `:36` 주석, 각 `p()` | "EMG %를 옮긴 값이 아니다. 주동/보조/안정 역할에 부여한 제품 초기값" | 가정(72종 전부) — 직접 EMG 가 있는 종목도 **수치**는 초기값 |
| 주 근육 문턱 | 가중치 ≥ 0.75 → `primary` | `:27` | 없음 (화면 '주요/보조' · 운동 방법 시트 범례의 기준) | 가정(문서 없음) |
| 추천 제외 · 개수 | 주동근 ≥ 55점 제외 · 최대 3 | `:270-272` | §81 §4 "55점은 제품 초기 정책값으로 의료적 경계값이 아니다" | 정책 |
| 계산 창 | `RETENTION_DAYS = 7` | `:229`, `MuscleLoadStore.kt:40` | §131 "근육 피로도 창은 그대로 7일"; **§81 §3 은 "부하 원장은 90일 보관"** | 문서 불일치(§81 가 옛 값) |
| 직접 추가 종목 가중치 | 첫 근육 1.0, 나머지 0.8 | `:197` | evidence "고른 주 근육으로만 대략 추정, 연구 출처 없음" | 가정(화면이 밝힘) |

화면의 고지: 피로도 화면 "기록 기반 추정 · 0–100점"(`MuscleLoadScreen.kt:76`), 홈 카드 "기록 기반 추정"(`:42`), 종목 상세 ⓘ 뒤 "역할 가중치는 EMG 비율이 아니라 제품 초기값이에요"(`ExerciseDetailScreen.kt:138`). 반감기·12회·체격 보정은 화면에 없다(설계 §80 §7 "상세에 사용 가정" 은 §82 에서 '계산 방법' UI 가 제거되며 사라졌다 — `MUSCLE_LOAD_IMPLEMENTATION.md` §7). 화면 이름 "근육 피로도" 가 지수를 측정값처럼 읽히게 한다는 지적은 §130 §3 (a) 에 사용자 판단으로 남아 있다.

## 5. 집계와 플래그별 규칙 제안

| 플래그 | 수 | 내용 |
|---|---|---|
| F1 맨몸인데 kg | **38** | 완전 체중 후보 4(딥스 · 풀업 · 인버티드 로우 · 캡틴체어 니 레이즈) + 부분 체중 34. 불일치 후보 2(행잉 레그 레이즈 `body=false`·`PULLUP_BAR`, Y 레이즈 `body=false`·`NONE`) |
| F2 근거 없음/약함 | **39** | a 키워드 34 · b 동일 조건 EMG 미확보 12 · c 비동료심사 출처만 5 (겹침 있음). `sources` 빈 카탈로그 종목 0 (직접 추가 종목만) |
| F3 단위 불일치 | 엄격 **2** · 참고 12 | 데드버그 · 버드독(교대인데 PAIR). 참고 = 단위 뜻이 화면에 없는 PAIR 8 + SECONDS 좌우 4 |
| F4 시간 종목인데 회 | 엄격 **0** · 참고 2 | 데드버그 · 팔로프 프레스(홀드 변형이 흔함 — 오류 아님) |
| F5 kg 의미 없음 | **33** | F1 중 REP/PAIR/EACH 종목 전부. SECONDS 맨몸 5 는 1RM·볼륨이 계산되지 않음 |
| kcal | 식 1 | 근거 없음(§3) |
| 피로 상수 | 13 | 가정 9 · 방향 출처 있음 1(감쇠) · 정책 2 · 문서 불일치 1(7일 vs 90일) |

### F1 → kg 칸은 맨몸 종목에서 숨기고, '+추가 중량' 을 켠 때만 연다

- `SetRow(showKg = …)` 는 이미 매개변수가 있다(`SetEntryUi.kt:69`, 거짓이면 '—'). 세 호출처(`SessionSetInputCard`·`SessionFinalSetsCard`·`ManualRecordSheet`)에 `showKg = profile?.bodyweight != true || extraWeight` 를 넘긴다. `extraWeight` 는 종목별 토글 "+추가 중량"(종목 상세·세트 행 머리에 한 번, 기억) — 켜면 kg 의 뜻은 **추가 중량**이고 머리글을 "+kg" 로 바꾼다. 판단 기준은 `MuscleLoadCatalog.find(name)?.bodyweight` 이지 카탈로그 기구 칩이 아니다(§6 — 밴드 8종이 '맨몸' 칩이다).
- 볼륨에 체중을 더하는 것은 **완전 체중 동작에 한정**한다. Hevy 는 "100 % 체중을 쓰는 동작(풀업·친업·딥스·핸드스탠드 푸쉬업)은 기록된 체중 + 추가 중량으로 볼륨을 계산하고, 푸쉬업·싯업처럼 일부만 쓰는 동작과 직접 만든 종목은 추가 중량만 센다" 는 규칙이다([도움말 1](https://help.hevyapp.com/hc/en-us/articles/38387131819799-Weighted-Bodyweight-Exercises-Is-Bodyweight-Included-in-Volume), [도움말 2](https://help.hevyapp.com/hc/en-us/articles/38386262243223-Bodyweight-Exercises-in-Hevy-Bodyweight-vs-Assisted-vs-Weighted) — 자동 접근 403, 검색 요약으로 확인). 우리 카탈로그에서 그 규칙에 분명히 드는 것은 **딥스 · 풀업** 둘이다. 인버티드 로우(바 높이로 부하가 변함 — evidence 가 그렇게 적음)·캡틴체어 니 레이즈·행잉 레그 레이즈(움직이는 질량은 다리뿐, 매달린 팔은 등척)는 판단이 필요하고 기본은 **더하지 않는다**. 더한 볼륨은 `VolumeSummary.estimatedSets` 와 같은 호박 '추정' 칩으로 표시하고, 체중 기록(`latestBodyWeight`, 없으면 프로필)이 있을 때만 더한다(Hevy 도 기록된 체중을 요구한다).
- 행잉 레그 레이즈 `body=false` 와 Y 레이즈 `body=false`(기구 NONE) 는 결정이 필요하다 — 행잉은 맨몸이면 `body=true` 로 고쳐 체격 보정 B 를 받게 되고(지금은 B = 1), Y 레이즈는 가벼운 덤벨 종목이면 `DUMBBELL` 로.

### F2 → 종목 상세·근육 화면에 '추정' 알약, 엔진은 그대로

- `ExerciseLoadProfile` 에 `tier`(DIRECT / INFERRED / NON_PEER) 를 **명시 필드**로 추가해 `p()` 에서 받는다(런타임 키워드 매칭은 evidence 문장이 바뀌면 깨진다 — 이번 감사의 키워드 목록은 첫 채움 값이다). 39종은 INFERRED 또는 NON_PEER.
- 종목 상세 '사용 근육' 머리에 알약 "추정" 과 ⓘ 안의 첫 줄 "이 종목은 같은 조건의 EMG 가 없어 비슷한 동작에서 옮겼어요"(evidence 문장 자체가 이미 그렇게 적혀 있으니 그대로 보인다). 피로도 화면의 '최근 관련 세트' 행에도 같은 알약. **부하 계산은 바꾸지 않는다** — §130 §3 판정대로 지수이지 측정이 아니고, 가중치 수치는 DIRECT 종목도 초기값이라 tier 로 가중치를 깎을 근거가 없다.
- c(NASM·ACE·Mayo 만) 5종은 ⓘ 에 "동료심사 논문이 아닌 기관 자료" 를 그대로 보인다(evidence 가 이미 "동료심사 논문과 구분" 이라고 적은 종목이 둘).

### F3 → 데드버그·버드독은 EACH 로, PAIR 종목에는 단위 한 줄

- **데드버그 · 버드독 → `LoadUnit.EACH`.** 둘 다 팔다리를 번갈아 뻗는 교대 동작이라 §130 규칙("교대 = EACH", 바이시클·러시안과 같은 급)에 맞다. 카탈로그 `8회 × 3세트` 는 **합계 8** 로 읽히게 된다(쪽당 4). PAIR 를 유지하려면 카탈로그를 "8회(쪽당)" 로 적어야 하고, 지금은 같은 '8회' 가 바이시클보다 쪽당 2배다. `MuscleLoadTest` 에 좌우 이름 → PAIR/EACH, `초` → SECONDS 의 표 검사를 추가한다(지금은 2종만 잠근다).
- **카메라 없는 PAIR 10종**(불가리안 · 리버스 런지 · 스텝업 · 싱글레그 글루트 브릿지 · 네발기기 힙 익스텐션 · 사이드 밴드 워크 · 원암 덤벨 로우 · 팔로프 프레스 + 위 둘) — 세트 입력 머리글 '회' 를 **'회(쪽당)'** 로, 수동 기록·종목 상세에 "좌우 각각 N회 = N회" 한 줄(`SIDE_PAIR_UNIT_HINT` 와 같은 자리). 엔진이 `l = r = n/12` 로 읽는 뜻을 화면이 말해야 한다.
- **SECONDS 좌우 4종**(사이드 플랭크 · 무릎 사이드 플랭크 · 수트케이스 캐리 · 싱글레그 애브도미널 프레스) — 머리글 '초' 를 '초(한쪽)' 로, 요약에 "양쪽 같은 부하로 기록" — 문서 §130 의 결정을 화면에 옮기는 것이지 계산 변경이 아니다.

### F4 → 고칠 것 없음

버티기·운반 7종은 카탈로그 `초` 와 SECONDS 가 맞는다. 데드버그·팔로프 프레스의 홀드 변형은 별도 종목이 생길 때의 일이다.

### F5 → 맨몸 종목은 1RM 을 건너뛰고, PR 은 '추가 중량 PR' 로만

- `ExerciseDetailScreen.kt:38` — `oneRm = if (profile?.bodyweight == true) null else …`, 타일 문구 "맨몸 종목은 1RM 을 추정하지 않아요". Epley 는 외부 중량의 식이라 추가 중량만으로 계산해도 뜻이 없다(체중이 빠진 10 kg × 8회 → 13 kg).
- `isPersonalRecord`·`bestKg`(`RecordModels.kt:93-102`) — F1 토글이 생기기 전까지 `bodyweight == true` 면 PR 을 매기지 않는다(지금은 푸쉬업에 70 을 적으면 PR 띠 "70 kg × 10"). 토글 뒤에는 "추가 중량 PR" 로 이름을 바꾼다.
- `summaryLine`(`:67-76`) — `bodyweight == true` 면 kg 앞에 "+" ("+10 kg × 8 · 8").

### kcal → 근거 없는 상수를 숨기거나, 체중이 드는 식으로 바꾸고 출처를 적는다

최소: 완료 화면·기록 통계의 "예상 소모" 에 ⓘ 하나 — "분당 고정값(부위별 5~7 kcal)으로 어림한 값이에요. 체중·강도를 반영하지 않아요." 원칙 #2 는 이것만으로도 지켜진다. 바꾼다면: kcal/분 = MET × 3.5 × 체중 / 200 에 2011 Compendium 저항운동 코드(02054 3.5 · 02052 5.0 · 02050 6.0)를 분류별로 붙이고 ⓘ 에 그 출처를 적는다 — 그래도 '추정' 이다. 수동 기록의 세트당 30초 가정은 어느 쪽이든 문장으로 밝힌다.

### 피로 상수 → 값은 두고, 문서와 고지만

§130 §3 대로 상수는 바꾸지 않는다. 할 것: (1) `MUSCLE_LOAD_IMPLEMENTATION.md` §3 의 "90일 보관" 을 7일(`RETENTION_DAYS`)로 고친다, (2) 기본 체중 65(프로필) vs 70(부하 기준)을 한 곳에 적는다, (3) 피로도 화면 ⓘ 에 "반감기 6 h/24 h · 12회 기준 · 체격 보정 ±15 % 는 가정값" 한 줄(§82 에서 사라진 '계산 방법' 의 최소판), (4) "근육 피로도" 이름은 사용자 결정으로 둔다.

## 6. 기타 관찰(플래그 밖)

- `catalogEquipmentOf`(`RecordModels.kt:318-329`)는 `BAND`·`PULLUP_BAR`·`DIP_BAR`·`RACK`·`STABILITY_BALL` 을 **'맨몸' 칩**으로 접는다 — 밴드 8종이 카탈로그·수동 기록에서 맨몸으로 보인다. F1 규칙을 칩에 걸면 밴드 종목의 kg 칸이 닫힌다(밴드는 어차피 kg 이 아니라 장력이지만, 뜻이 다르다).
- `Workout.loadLabel()`(`WorkoutMath.kt:70`)은 하체·상체 전부에 "체중" 을 돌려준다 — 호출처가 없다(죽은 코드).
- 카탈로그 `posture` 플래그 16 = `cameraSupportedNames` 16 — 일치. `MuscleLoadCatalog.find` 는 "바벨 스쿼트" 별칭을 받는다(세트 로그 이름).
- `LoadSet` 생성(`MuscleLoadAdapter.kt:10`)은 **`workout.reps` 문자열의 첫 숫자**를 횟수로 쓴다 — 세션은 `completedWorkouts` 가 기록 횟수로 다시 쓴 문자열이라 맞지만, 사용자가 휴식 화면에 적은 `SetEntry.reps` 는 부하 엔진에 들어가지 않는다(`LoadSet.actualReps` 는 완료 화면 자가 라벨 `SetSelfLabel` 경로만 — `TrexAppState.kt:743-744`). 기록 기준(사용자 값)과 부하 기준(카메라/목표 값)이 다를 수 있다.
- `MuscleLoadTest` 가 46종 중 단위를 잠그는 것은 둘(파머스 캐리 SECONDS · 리버스 런지 PAIR)뿐이라 F3 의 데드버그·버드독이 통과했다.

## 7. 감사와 동시에 생긴 작업 트리 변경(미커밋) — 덮은 것과 남은 것

감사 도중 같은 작업 트리에 이 문서의 플래그를 참조하는 변경이 들어왔다(`git status`: `ExerciseLoadKind.kt` 신설 외 7개 파일, 전부 미커밋). **전체 diff 를 다 읽지는 않았다** — 핵심 식별자(`exerciseLoadKind`·`supportsOneRepMax`·`evidenceWeak`·`kgLabel`·`loadKg`·`bodyKg`)가 든 덩어리만 봤다. 그 범위에서:

| 플래그 | 작업 트리가 하는 것 | 남는 것 |
|---|---|---|
| F5 | `exerciseLoadKind(name)` 이 EXTERNAL 일 때만 `estimatedOneRepMax`(`supportsOneRepMax`), 다른 종류는 타일이 '최다 횟수' | 없음 — §5 F5 의 1RM 건너뛰기와 같다 |
| F1 | kg 머리글을 맨몸이면 "+kg"(추가 중량)으로(`SetRowHeader(timed, kgLabel)`), 체중은 **풀업·딥스**(`FULL_BODYWEIGHT_EXERCISES`)에서 `latestBodyWeight` 가 있을 때만 `SetEntry.bodyKg` 로 더해 `loadKg = bodyKg + kg`, `volumeKg = loadKg × reps` | kg 칸은 여전히 **모든 종목에 열려 있다**(`showKg` 기본 참 그대로 — '+추가 중량' 토글 대신 라벨로 뜻을 밝히는 선택). 체중이 든 볼륨의 '추정' 칩은 확인하지 못했다. **행잉 레그 레이즈·Y 레이즈는 `body=false` 라 EXTERNAL** 로 분류돼 라벨 "kg"·1RM 계산이 그대로다(§2 F1 불일치 후보 — 프로필 플래그를 먼저 정해야 한다). 플랭크류(TIMED)는 라벨 "kg" 로 열린 채다(계산엔 들지 않음, §2 F5) |
| F2 | `ExerciseLoadProfile.evidenceWeak`(출처 없음 또는 evidence 에 추론·유추·적용·추정·편집·유사) → 종목 상세 '사용 근육' 에 "추정" 칩 | 키워드만 보므로 이 감사의 **b·c 만인 5종이 빠진다** — 케이블 페이스 풀 · I-Y-T 레이즈 · 싱글레그 애브도미널 프레스 · 바이시클 크런치 · 러시안 트위스트(evidence 에 '미확보' 또는 출처가 NASM·ACE·Mayo 뿐). `"미확보" in evidence` 와 `sources.all { nasm/acefitness/mayoclinic }` 를 더하면 39종이 맞는다. 장기적으로는 `p()` 의 명시 필드(§5 F2) |
| PR | 본 덩어리에 `isPersonalRecord`·`bestKg` 변경 없음 | 맨몸 종목 PR 은 `set.kg`(= 추가 중량) 기준이 되고 보상 띠 문구 "N kg × 회" 에 '+' 가 없다 — 확인 필요 |
| F3 · F4 · kcal · 피로 문서 | `MuscleLoad.kt`·`WorkoutMath.kt`·`SessionScreens.kt`·`docs/MUSCLE_LOAD_IMPLEMENTATION.md` 변경 없음 | §5 의 F3(데드버그·버드독 EACH, PAIR 단위 한 줄)·kcal ⓘ·7일/90일 문서 수정 전부 그대로 남아 있다 |

## 8. 구현 결과(2026-11-07 저녁, 같은 날 — 스펙 §134)

| 플래그 | 한 것 | 안 한 것(결정 대기) |
|---|---|---|
| F1 | `ExerciseLoadKind`(EXTERNAL·BODYWEIGHT·BODYWEIGHT_FULL·TIMED). 맨몸 종목의 kg 칸은 **'+kg'(추가 중량)** 머리글 — 토글 대신 늘 열어 두되 뜻을 바꿨다. 체중은 **풀업·딥스**만 볼륨에(`SetEntry.bodyKg`, 체중 기록 있을 때만). 맨몸 버티기 5종(플랭크 4·싱글레그 애브도미널 프레스)은 kg 칸을 닫는다(`exerciseShowsKg`). 행잉 레그 레이즈·Y 레이즈 `body=true` 로 수정. 밴드 8종은 '밴드' 칩으로 분리 | '+추가 중량' 토글(기본 닫힘) — 지금은 늘 '+kg' 열림. 인버티드 로우·캡틴체어·행잉은 체중을 넣지 않음(기본) |
| F2 | `evidenceWeak`(a 키워드 + b '미확보' + c 동료심사 출처 없음) → 종목 상세 '사용 근육' 머리줄 **'추정' 칩**. 테스트가 39종을 잠근다 | `tier` 명시 필드(지금은 evidence 문장 키워드 — 문장을 바꾸면 테스트가 잡는다). 피로도 화면 관련 세트 행의 칩 |
| F3 | 데드버그·버드독 → **EACH**. 세트 입력 머리글: PAIR 종목 '회(쪽당)', 좌우 버티기 '초(한쪽)'(`countLabelFor`). `MuscleLoadTest` 가 '초'↔SECONDS 1:1, PAIR/EACH 는 좌우 이름만을 잠근다 | — |
| F4 | 없음(감사 결론) | — |
| F5 | 1RM 은 EXTERNAL 만. 맨몸 상세는 '최고 추가 중량 · 최다 횟수'. PR·요약 줄의 kg 에 '+' | PR 을 맨몸에서 아예 안 매기는 안 — 추가 중량 PR 로 유지 |
| kcal | **MET × 체중 식으로 교체(스펙 §134b, 같은 날 밤 사용자 결정)** — kcal/분 = MET × 3.5 × 체중 / 200, MET 는 2024 Adult Compendium(Herrmann 2024) 행을 종목별로(`ExerciseMet.kt`, 카탈로그 72종 전부 — 테스트), 시간은 휴식 포함(세션은 총 경과를 가동 비율로, 직접 적은 기록은 세트당 30초 + 설정 휴식), 옛 기록은 값이 옛 식과 같을 때만 이관, ⓘ·배너에 식·출처·쓰인 체중·30초 가정 | 강도(중량·RPE·심박) 반영 없음 — Compendium 행이 활동 평균이라 |
| 피로 상수 | 값 유지. 피로도 화면 부제에 "반감기 6/24 h·12회 기준·체격 보정은 가정값". `MUSCLE_LOAD_IMPLEMENTATION.md` §3 의 90일 → 7일 바로잡음 | 기본 체중 65 vs 70 통일, "근육 피로도" 이름 |
| §6 | `Workout.loadLabel()` 삭제. `catalogEquipments` 에 '밴드' | `LoadSet` 횟수가 적은 값(`SetEntry.reps`)을 안 보는 것 — 부하 엔진 입력 변경은 별도 결정 |
