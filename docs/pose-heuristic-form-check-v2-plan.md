# 휴리스틱 자세 체크 v2 완성 계획 (구현 인수인계)

- 상태: 구현 계획 문서. 정책 계약이 아니며 SHA pin이 없다. 각 마일스톤이 정책 문서를 건드리면 §0.3의 개정 절차를 따른다.
- 작성일: 2026-08-12
- 목표: [서비스 로드맵](pose-correction-service-roadmap.md)의 정식 판정 트랙(SL-3)과 독립적으로, [휴리스틱 베타 트랙](pose-heuristic-form-check.v1.md)을 "3운동 × 무릎각 1신호"에서 "약 16운동 × 4신호 + 개인 기준선 + 실환경 강건성"으로 완성한다.
- 독자: 이 저장소를 처음 여는 구현 세션. 아래 내용만으로 착수 가능해야 하며, 재조사가 필요한 지점은 각 절에 명시했다.

## 0. 전제

### 0.1 완료된 것 (이 계획의 출발점)

| 항목 | 산출물 | 커밋 |
|---|---|---|
| AI Hub 연구 권리 범위 인가 (비상업 교육) | `docs/pose-data-rights-manifest.aihub-research.v1.json` + 생성기 | `86eace3` |
| Day05 라벨 기반 런지 깊이 임계값 적합 | `docs/heuristic-form-check-threshold-fit.v1.json` + `tools/fit_heuristic_form_check_thresholds.py` | `86eace3` |
| MediaPipe↔AI Hub 브리지 오차 카드 (측면·무릎 체인) | `docs/mediapipe-aihub-bridge.v1.json` + `tools/measure_mediapipe_aihub_bridge.py` | `1ef1879` |
| M0: 적합 임계값 코드 반영 + 스쿼트 고하중 규칙 | 정책 v1.1 + `FormCheckExercise` 개정 | `6af09f5` |
| **M1: 개발용 캡처 + JVM 리플레이 하니스** | 정책 v1.2 §5-5 + `devcapture`(debug/release twin) + `LandmarkReplay` | `fa10752` |
| **M2: person-lock v3 배경 후보 게이트** | `backgroundEnvelopeRatioCeiling` + 전경 분리 | `f49d68f` |
| **M3-a: driver 일반화 + 웨이브 1** | 정책 v1.3 §4.3 + `FormCheckDriver`(무릎/엉덩이/팔꿈치) + 신규 4종 | `64c1aef` |
| **M3-b/c: 작업 방향 모델 + 웨이브 2** | 정책 v1.4 + `FormCheckWorkingDirection` + 신규 8종 (지원 15종) | `4cb84a1` |
| **M4: 세트 내 개인 기준선** | 정책 v1.5 §4.4 + 15° 하한 자기 비교 | `954f72f` |
| **M5-a: 홀드 cadence + 플랭크** | 정책 v1.6 §4.35 + `HoldDetector` (지원 16종) | 아래 커밋 |

**M5-b(중력 attestation)는 의도적으로 착수하지 않았다.** 중력 벡터가 열어주는 신호는 "중력 대비 절대 몸통 기울기"인데, 현재 지원 16종 중 그것을 필요로 하는 운동이 없다 — 굿모닝의 상체 숙임은 이미 회전 불변인 엉덩이 내각으로 읽고 있다. 센서를 attested observation 계약에 끼워 넣는 일은 `PoseObserverUpdate`와 계약 해시 연쇄를 건드리는데, 그 대가로 얻는 사용자 가치가 현재 0이다. 착수 조건은 **중력 없이는 읽을 수 없는 운동이 스펙에 들어올 때**다(예: 벤트오버 로우의 상체 각도, 사이드 레터럴 레이즈의 팔 높이).

핵심 수치 (재유도 불필요): MediaPipe world 무릎각은 AI Hub 3D 라벨보다 중앙값 **+13.2° 곧게** 읽힘(P95 |오차| 40.8°). 라벨 적합값(111°/92°)은 이전 불가(80.2%/53.6%로 붕괴), MediaPipe-native 적합값은 포워드 129°(LOSO 93.5%)·백워드 123°(LOSO 96.4%). 임계값을 새로 만들 때는 **항상 앱이 계산하는 좌표계(MediaPipe world)에서 적합**한다.

브리지 도구 재실행 환경: 시스템 Python 3.13에는 mediapipe가 없다. `py -V:3.12 -m venv <dir>` 후 `pip install mediapipe opencv-python-headless`로 3.12 venv를 만들어 실행한다(모델 SHA는 도구가 자체 검증).

### 0.2 결정 로그 (뒤집으려면 소유자 결정 필요)

1. **바벨 스쿼트는 베타에 유지하되 깊이 증가 권유 금지** (`loadBearing = true`, 정책 §4.2). 근거: 임계값 미보정 + 하중 안전. 철회 조건: 자체 수집 실기기 데이터로 스쿼트 임계값이 적합되고 정책 문서가 개정될 때.
2. **런지 임계값은 MediaPipe-native 적합값** (포워드 134/129, 백워드 130/123). IMAGE 모드 측정 vs VIDEO 모드 런타임 한계는 정책 §4.1에 고지됨.
3. AI Hub 파생 임계값의 **공개 배포 빌드 반입 전 확인 의무**가 연구 manifest의 openBlocker로 남아 있다. 캡스톤 비공개 빌드 범위에서는 문제없음.

### 0.3 모든 마일스톤 공통 거버넌스 (요약: 문서·핀·테스트는 같은 커밋)

- 정책 문서(`docs/pose-heuristic-form-check.v1.md`)의 §4 표나 규칙이 바뀌면: 문서 개정 → CRLF→LF 정규화 후 SHA-256 계산 → `HeuristicFormCheckDeclaration.POLICY_DOCUMENT_SHA256` 갱신 → `FormCheckGovernanceTest.thresholdsMirrorThePolicyDocumentTable` 미러 갱신. 전부 **같은 커밋**.
- 언어 seal: 사용자 노출 문구에 금지 어휘(잘못·틀렸·정확·완벽·합격·불합격·위험·부상·진단·교정) 사용 불가 — `FormCheckGovernanceTest`가 formcheck 패키지 + `SessionFormCheckLayer.kt` 소스를 스캔한다. 관찰·제안형 문장만.
- release 체인 불간섭: formcheck 소스는 `PostureCorrectionRuntimeFacade`, `pose.release/shadow/criterion/readiness`, `PoseExerciseEvaluationSession`, `withPostureCorrection`을 참조 불가(테스트 강제). 영속화·전송·오디오 심볼(`java.io`, `Log.`, `http`, `SharedPreferences`, `TextToSpeech` 등)도 금지 — **M1의 레코더를 formcheck 패키지 밖에 두는 이유**.
- 커밋 조건: `./gradlew :app:testDebugUnitTest` 전체 녹색 + (tools를 건드리면) `python -m unittest discover -s tools -p "test_*.py"`.
- AGENTS.md: 작업 범위 파일만 stage, 기능 커밋 후 `git push -u origin <branch>`.

## 1. M1 — dev 랜드마크 레코더 + JVM 리플레이 하니스 (최우선)

**목적**: 백엔드·텔레메트리 없이 임계값·검출기 변경을 검증할 유일한 수단. 이후 모든 마일스톤의 회귀 안전망.

**설계**:
- 신규 `app/src/debug/java/com/example/trex_kotlin/devcapture/PoseLandmarkRecorder.kt` — **debug source set에만** 두어 release 빌드에서 클래스 자체가 존재하지 않게 한다(governance의 영속화 금지와 충돌 없음: formcheck 패키지 밖 + debug 전용).
- 기록 형식: JSONL, 1행 = 1프레임 `{"t": <timestampMs>, "lock": <bool>, "lateral": <bool>, "w": [[x,y,z,visibility,presence] × 33], "n": [[x,y,visibility,presence] × 33]}`. 영상 저장 없음. 파일명에 운동 id + 시작 시각.
- 배선: `SessionFormCheckLayer`의 `onPoseObservation` 콜백에서 debug 빌드일 때만 recorder로 분기 — 단 formcheck 소스가 recorder를 import하면 안 되므로, **호스트(Session 레이어) 쪽에서 옵셔널 콜백 주입** 형태로 설계한다. 예: `SessionFormCheckLayer(onDebugFrame: ((PoseObserverUpdate) -> Unit)? = null)`. release에서는 null.
- 리플레이: 신규 테스트 유틸 `app/src/test/.../formcheck/LandmarkReplay.kt` — JSONL을 읽어 `HeuristicFormCheckSession.accept(...)`에 순서대로 주입, 최종 `repCount`/`uncountedAttemptCount` 기대값과 비교. fixture는 `app/src/test/resources/formcheck/` 아래 저장(개인 데이터이므로 **본인 촬영분만**, 파일에 신원 정보 없음 — 권리 manifest gitPolicy 준수: 랜드마크 궤적의 git 반입은 AI Hub 클래스에만 금지되어 있고 본인 데이터는 소유자 판단. 주석으로 출처 명시).

**수용 기준**: 본인 촬영 1세션(스쿼트 10회)이 기록되고, JVM 리플레이 테스트가 기록 당시 화면과 동일한 카운트를 재현. release variant 컴파일에 recorder 클래스 부재.

**구현 결과 (`fa10752`) — 계획 대비 편차 2건**:
- 형식은 JSONL이 아니라 **탭 구분 텍스트**(`TREXCAP1` 헤더 + `F` 행). 저장소에 JSON 의존성이 없어 파서를 손으로 써야 하는데, 구분자 파서가 명백히 옳은 반면 손으로 쓴 JSON 파서는 정확성 위험이 실재한다. 관절은 sparse로 기록해 누락 관절이 좌표로 날조되지 않는다.
- 배선은 옵셔널 콜백 주입이 아니라 **variant twin**(`src/debug` 실제 구현 / `src/release` no-op). 콜백 방식은 main에서 non-null을 넘길 주체가 없어 도달 불가능한 코드가 된다. twin은 소스셋이 병합이 아니라 대체라는 성질로 출시 빌드의 링크 자체를 막고, 거버넌스 테스트가 release twin 소스에 저장 경로가 없음을 확인한다.
- 정책 §5-5 신설(v1.2): debug 전용·기본 꺼짐·world landmark만·전송 경로 없음을 계약으로 명시. `DevPoseCapture.ENABLED`가 false로 커밋되어 debug APK 설치만으로는 아무것도 기록되지 않는다.
- 실제 촬영 fixture는 `src/test/resources/formcheck/`에 넣으면 코드 변경 없이 로드되며, 없으면 해당 테스트는 no-op이라 신체 데이터 커밋을 강제하지 않는다.

## 2. M2 — 다중 인물 강건화: person-lock 배경 후보 게이트 (v3)

**현재 동작** ([AttestedPoseObserver.kt:315](../app/src/main/java/com/example/trex_kotlin/camera/AttestedPoseObserver.kt) 부근): `batch.rawCandidateCount > 1`이면 즉시 lock 해제 + AMBIGUOUS. 스튜디오 데이터 실측 기권율 0.41%(4,590프레임 중 19). 헬스장에서는 배경 통행인 1명이 세트를 중단시킨다 — 서비스 평가에서 최상위 결함으로 지목된 항목.

**설계** (안전 불변조건 유지):
- `PosePersonLockConfig`에 배경 판정 파라미터 추가: `backgroundHeightRatioCeiling = 0.55` (후보 신장/primary 신장), `backgroundEdgeBandFraction = 0.08` (정규화 프레임 가장자리 밴드).
- 판정 순서: described 후보 중 primary 후보(기존 descriptor 연속성)와 비교해 배경 조건을 만족하는 후보를 **rawCandidateCount에서 제외한 "foreground count"**로 재계산. foreground가 1이면 진행, 2 이상이면 기존대로 AMBIGUOUS(비슷한 크기의 두 사람은 여전히 기권 — 측정 귀속 원칙 불변).
- lock이 없는 상태(최초 획득)에서는 보수적으로: 배경 제외 후에도 후보가 정확히 1일 때만 dwell 시작.
- `personLockSchemaVersion`을 "3"으로 올리고 새 파라미터를 `artifactSha256` 필드 목록에 추가 — **주의**: 이 해시는 `VerifiedMediaPipePoseObserverFactory`의 observation contract와 `MediaPipePoseObserver` init의 require로 연쇄 검증된다. config·factory·계약 상수를 같은 커밋에서 갱신하지 않으면 런타임 require가 터진다. `AttestedPoseObserverTest`·`VerifiedMediaPipePoseObserverFactoryTest`의 기대 해시도 함께.
- occlusionPolicy 등 기존 정책 문자열은 불변. 새 정책 키 `backgroundCandidatePolicy = "HEIGHT_RATIO_AND_EDGE_BAND_EXCLUSION"` 추가.

**테스트**: 신규 `BackgroundCandidateGateTest` — (a) primary + 55% 미만 신장 후보 → lock 유지·카운트 진행, (b) 유사 신장 2인 → AMBIGUOUS 유지, (c) 배경 후보가 성장해 55%를 넘는 프레임 → 그 프레임부터 AMBIGUOUS, (d) lock 미보유 상태에서 배경 포함 2인 → dwell 시작은 foreground 1일 때만. 기존 `AttestedPoseObserverTest`의 AMBIGUOUS 케이스는 유사 신장으로 재구성해 의미 유지.

**수용 기준**: 전 테스트 녹색 + M1 리플레이에 "제3자 통과" 세그먼트를 넣은 fixture에서 세션 생존.

**구현 결과 — 계획 대비 편차 2건**:
- 파라미터는 `backgroundEnvelopeRatioCeiling = 0.55` **하나뿐**이며 가장자리 밴드는 넣지 않았다. 밴드를 OR로 더하면 배경 분류가 더 **관대**해지는데, 이는 위험한 방향이다(프레임 가장자리에 선 동일 크기의 사람은 진짜 모호성이므로 기권이 맞다). 크기비 단독이 보수적이고 충분하다.
- 크기 척도는 "신장비"가 아니라 **랜드마크 envelope의 대각선**이다. 신장(높이)만 쓰면 플랭크처럼 누운 피험자가 서 있는 행인보다 작게 읽혀 피험자가 배경으로 분류되는 최악의 오분류가 가능하다. 대각선은 자세 방향에 불변이고 관절 confidence와 무관하게 항상 정의된다.
- 가장 큰 후보는 정의상 항상 전경으로 남으므로(비율 1.0), 비어 있지 않은 batch가 전경 0개가 되는 경우는 없다.
- mapper가 거부한 후보(geometry 없음)는 배경임을 증명할 수 없으므로 계속 모호성에 계수된다 — 기존 `rejectedSchemaCandidateStillCountsAsMultiPersonSentinel` 불변.
- 계약 해시: `personLockSchemaVersion` 3, `implementationContractId` v3, `candidateMultiplicityPolicy`를 `EXACTLY_ONE_FOREGROUND_AND_VALID_CANDIDATE`로 갱신. 연구 모듈의 파생 해시 핀은 자체 test double을 쓰므로 연쇄 갱신이 발생하지 않았다(전 테스트 녹색으로 확인).

## 3. M3 — 신호 확장(S2 엉덩이각·S3 팔꿈치각)과 운동 웨이브

**기하**: `FormCheckGeometry.includedAngleDegrees`는 이미 임의 3점을 받는다. 할 일은 (a) `FormCheckJointGroup`에 `SHOULDER("어깨", LEFT_SHOULDER, RIGHT_SHOULDER)`, `ELBOW("팔꿈치", ...)`, `WRIST("손목", ...)` 추가, (b) `kneeSample`/`sideSample`을 **체인 파라미터화**: `chainSample(frame, side, vertexGroup, firstGroup, secondGroup)` 형태로 일반화하고 기존 함수는 이를 감싼다.

**driver 추상화**: `FormCheckExercise`에 `driver: FormCheckDriver` 추가 — `FormCheckDriver(vertex: FormCheckJointGroup, first: FormCheckJointGroup, second: FormCheckJointGroup, invert: Boolean)`. `invert = true`면 세션이 검출기에 `180 - angle`을 주입해 "굽힘이 깊어질수록 작아지는" 기존 히스테리시스 방향을 재사용한다(컬·랫풀 등 수축형 운동용). `HeuristicFormCheckSession`의 `selectSample`·headline 문구가 driver의 관절 이름을 쓰도록 일반화("무릎이" → "${driver.vertex.label}가/이" — 조사 처리는 `FormCheckStartAnnouncer.subjectParticle` 재사용).

**웨이브 1 (다리 체인, 문헌 초기값·HEURISTIC_DEFAULT)**: 바벨 런지(`loadBearing=true`), 스탠딩 니업(driver=HIP: shoulder–hip–knee, invert — 무릎 올리기는 hip 굴곡 감소), 굿모닝(driver=HIP, `loadBearing=true`), 힙쓰러스트(driver=HIP).
**웨이브 2 (팔 체인)**: 푸시업, 니푸쉬업, 딥스, 바벨 컬(`loadBearing` 판단: 컬은 하중이 척추 압박이 아니므로 false 권장, 소유자 확인), 덤벨 컬, 오버 헤드 프레스(`loadBearing=true`), 랫풀 다운, 케이블 푸시 다운 — 전부 driver=ELBOW, 방향은 운동별로 결정.

**임계값 규율**: 신규 운동은 전부 `HEURISTIC_DEFAULT`로 시작하고 §4 표에 행 추가(= 문서 개정 + SHA 재핀 + 거버넌스 미러 갱신, §0.3). AI Hub 좌표 라벨이 있는 운동(스탠딩 니업 등 Day05/Day17 계열)은 `tools/fit_heuristic_form_check_thresholds.py`의 조건 키워드(`DEPTH_CONDITION_SUBSTRING`)를 운동별 조건명으로 일반화해 적합을 시도하되, **이미지가 있는 운동만 브리지 카드 검증 후 반영**(이미지 없는 운동은 +13° 편차 보정 근거가 없으므로 라벨 적합값 직행 금지 — 백워드 런지에서 92→123°로 31° 이동한 전례).

**주의**: 운동 시작 게이트 문구(`missingJoints`)와 `FormCheckStartAnnouncer`는 이미 그룹 label 기반이라 자동 확장된다. `Workout.supportsFormCheck`(TrexData.kt)는 `FormCheckExercise.supports`를 그대로 쓰므로 enum 추가만으로 토글이 열린다 — 웨이브별로 나눠 커밋.

**구현 결과 (M3-a) — 계획 대비 편차와 남은 일**:
- `invert` 플래그는 **구현하지 않았다**. `180 - angle` 단독 반전은 성립하지 않는다: 힙쓰러스트를 반전하면 각도 범위가 [90,175]→[90,5]로 옮겨가 상단 임계 150°에 영원히 도달하지 못해 무장 자체가 안 된다. 휴식 자세가 굽힘인 운동(힙쓰러스트·오버 헤드 프레스·케이블 푸시 다운)은 **반전 + 운동별 top/attempt 임계값**이 함께 필요하며, 이는 `RepCycleDetector` 생성자가 이미 받는 파라미터를 스펙으로 끌어올리는 별도 작업이다. 검출기 KDoc에 이 전제를 명문화했다.
- 따라서 웨이브 1은 **휴식 자세가 신전인 운동 4종**만 넣었다: 바벨 런지(무릎), 스탠딩 니업(엉덩이), 굿모닝(엉덩이), 푸시업(팔꿈치). 팔꿈치 driver를 푸시업 하나로 먼저 실증해 일반화가 다리 밖에서도 성립함을 확인했다.
- 남은 웨이브 2(니푸쉬업·딥스·바벨 컬·덤벨 컬·랫풀 다운)는 **동일 신전-휴식 계열이라 enum 행 추가만으로 열린다**. 오버 헤드 프레스·케이블 푸시 다운은 위 반전 작업 이후.
- 임계값은 전부 `HEURISTIC_DEFAULT`이며 근거 한계를 정책 §4.3에 명시했다: 브리지 실측은 무릎 체인에서만 얻었으므로 **편차의 방향만 빌렸고 크기는 이전하지 않았다**. 스탠딩 니업은 Day05에 좌표 라벨이 있어 다음 보정 대상이다.
- 거버넌스 추가: 보정된 운동과 `loadBearing`은 서로소여야 한다는 테스트를 넣어, 하중 운동이 나중에 보정되면 §4.2의 봉인을 의도적으로 다시 결정하게 강제했다.

## 4. M4 — 개인 기준선 상대 관찰

- 세트 내 최초 2개 완료 반복의 최저점 중앙값 = 그 세트의 기준선. 3번째 반복부터 headline에 상대 관찰 병기: "무릎이 128도까지 굽혀졌어요 · 오늘 첫 반복보다 7도 얕아요"(언어 규칙 §2 준수 — 비교 대상이 사용자 자신이므로 규범 비교가 아님. 단 정책 문서에 "개인 기준선 관찰" 절 신설 필요).
- 인구 임계값의 역할은 반복 인정·얕은 시도 구분(카운트)에 한정하고, 품질 서술은 개인 상대값을 우선한다.
- `loadBearing` 운동도 상대 관찰은 허용(권유가 아니라 관찰이므로) — 단 "더 앉아볼까요"류 문구는 계속 금지.
- 구현: `HeuristicFormCheckSession`에 세트 스코프 상태 추가(이미 `attemptResetKey`로 세트마다 재생성되므로 자연 리셋).

## 5. M5 — 중력 attestation(S5 몸통 기울기) + 홀드 모드(플랭크)

- `SensorManager.TYPE_GRAVITY`(저역 필터 내장)를 카메라 프레임과 함께 샘플링, 화면 회전 보정 후 정규화 벡터를 옵저버 업데이트에 동반. `PoseCameraGeometryContext`는 preprocessing 해시 연쇄가 무거우므로 건드리지 말고 **별도 attested 필드**로 전달(예: `PoseObserverUpdate`에 nullable gravity). `PoseFeatureEngine`의 `OrientationReferenceKind.GRAVITY`가 이미 "명시적 중력 벡터 요구" 계약이므로 그 소비자로 연결.
- 몸통 기울기 = shoulder-hip 선분 vs 중력 사잇각. 굿모닝·벤트오버·데드리프트류의 "상체 숙임" 관찰이 열린다. 센서 부재 기기는 해당 신호만 침묵(기권 규율 재사용).
- 홀드 모드: `HoldDetector` 신설 — 조건(예: shoulder–hip–ankle 직선도 ≥ 임계) 충족 시간을 누적하고 이탈을 관찰로 보고. `RepCycleDetector`와 동일한 기권·타임스탬프 규율. 플랭크에 적용.

## 6. 데이터 트랙 (병렬, 코드와 독립)

1. **본인 촬영 (즉시)**: M1 레코더로 스쿼트·런지·확장 운동을 정자세 10회 + 의도 오류(얕게·빠르게·상체 숙임) 각 5회 + negative 세그먼트(휴식·통행인·인접 운동). 스쿼트 임계값 적합의 유일한 경로(AI Hub에 이미지 없음).
2. **지인 수집 (n≥5)**: 착수 전 rights manifest에 TREX 자체 수집 클래스의 동의·보관 정책 신설 필요(현행 v1은 REAL_PARTICIPANT_COLLECTION 금지 — 소유자 결정으로 휴리스틱 보정용 클래스를 연구 manifest처럼 범위 분리 신설).
3. **AI Hub 추가 활용**: Day05 나머지 3운동(니업·사이드 크런치·버피)의 조건별 적합 — 니업은 "무릎 충분히 올라오고" 조건에 hip driver로 시도 가능. Day04(바벨·덤벨 4종)·Day17(기구 8종)은 이미지 무결성 감사(라벨↔이미지 연결) 후 동일 파이프라인.

## 7. 명시적 비목표 (이 계획이 하지 않는 것)

- release 체인 개통, PASS/FAIL·점수·cue, `PostureCorrection` 기록 생성 — 전부 로드맵 SL-3 트랙의 소관.
- 자세 체크 결과의 운동 기록 저장(정책 §5-2). 저장을 원하면 소유자 결정 + 정책 개정이 선행.
- AI Hub raw 데이터의 git/APK 반입, 파생 임계값의 공개 배포(연구 manifest openBlocker 해소 전).
- 정면 뷰 전용 운동(사이드 런지·레터럴 레이즈 등) — lateral 전제의 v2 범위 밖.

## 8. 실행 순서와 커밋 단위

```
M1 (레코더+리플레이) ──┬── M2 (person-lock v3)      # M1이 M2의 검증 수단
                       └── M3 웨이브1 → 웨이브2      # 각 웨이브 = 정책 개정 포함 1커밋
M3 완료 후 → M4 (개인 기준선, 정책 개정 1커밋)
병렬: 데이터 트랙 1(본인 촬영) → 스쿼트 적합 → 스쿼트 임계값 개정(loadBearing 규칙 §4.2 재검토)
후순위: M5 (중력 + 홀드)
```

각 커밋 전 체크리스트: 전체 unit test 녹색 / 정책 문서·SHA·거버넌스 미러 동기 / 새 사용자 문구 언어 seal 통과 / AGENTS.md 커밋·push 규칙.
