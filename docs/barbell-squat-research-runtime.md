# 바벨 스쿼트 연구 런타임 경계

## 현재 결론

이 패키지는 바벨 스쿼트의 좌표 신호를 안전하게 연구하기 위한 기반이다. 사용자 자세를
`PASS/FAIL`로 판정하거나 점수·교정 문구·음성 cue를 생성하는 기능이 아니다.

현재 상태는 다음과 같다.

- AI Hub 바벨 스쿼트 condition: 4/4 coverage
- 실행 가능한 `MeasureOnly` plan: 0
- 사용자 release criterion: 0
- 제품 화면 또는 세션의 research/shadow 호출자: 0
- shadow 실행 authority: `NO_SHADOW_KEY_CONFIGURED`, entry 0

콘텐츠 SHA나 repository drift pin은 실행 권한·전자서명·전문가 승인으로 해석하지 않는다.

## 구현된 연구 계약

### CameraX-MediaPipe 기하 provenance

`PoseCameraGeometryContext`는 각 프레임의 CameraX 원본 크기, 반개구간 crop rectangle,
입력 회전, upright 출력 크기, inference pixel mirror 금지, 화면 mirror metadata와
preprocessing artifact를 함께 고정한다. `PoseObservationSource`가 만든 geometry epoch와
프레임 timestamp receipt가 이 context를 관측값에 결속한다. context가 바뀌면 기존 person lock과
view qualification을 폐기하고 새 dwell을 요구한다.

이 경계는 crop/rotation/mirror drift를 숨기지 않기 위한 content-addressed receipt다. 현재 같은
앱 모듈의 Kotlin `internal` API는 적대적 호출자를 막는 서명 경계가 아니므로, positive shadow나
사용자 release 전에 observer issuer를 별도 Gradle 모듈로 격리해야 한다. 현재 카메라 scaffold의
연구 신호 소비자와 제품 호출자는 모두 0이다.

Camera geometry provider drift pin:
`e81a27d8cc17c8a27a5860d7a3cbff2a19d764ca2b053302c0a5c4f18e16a9c8`

Verified preprocessing drift pin:
`37e938c6627823683c6f764ec3cfda7b620aca5f6cc4439371645dfbdde0467b`

### 좌우 무릎-발 투영 offset

`BarbellSquatProjectedOffsetFeature`는 정규화 영상 좌표를 종횡비 보정한 뒤, 발목을
기준으로 무릎과 `foot_index`를 어깨축에 각각 투영한 거리 차를 어깨너비로 정규화한다.
LEFT와 RIGHT를 독립적으로 보존하고 평균하지 않는다.

이 값은 다음을 뜻하지 않는다.

- AI Hub의 `발과 무릎의 방향 일치` 정답
- knee valgus, 하중 분배, 지면 반력 또는 부상 위험
- 사용자에게 보여 줄 자세 판정이나 교정 방향

source reference identity, primary-person lock, timestamp-bound view qualification,
joint confidence, 영상 크기, shoulder-axis 퇴화를 통과하지 못하면 측정하지 않는다. 허용 view는
`front-full-body.v1`과 `front-oblique-full-body.v1`의 명시적 subset뿐이다.

Feature contract SHA-256:
`b431e97d19b7bcf9784c90e388ee5391ed5b64b9bb797b7178870fd7a36d41ba`

### 반복 phase 연구 명세

`BarbellSquatResearchPhaseContract`는
`READY -> DESCENDING -> BOTTOM -> ASCENDING -> READY`의 후보 topology와 마지막 edge만
completed cycle로 인정하는 반개구간 `[start, end)` 규약을 고정한다.

- front/front-oblique 후보: causal READY prefix에서 고정한 baseline으로 정규화한
  pelvis-ankle 화면 수직 거리
- lateral 후보: 좌우 knee flexion의 median
- 두 후보가 동시에 존재하면 방향과 phase 제안이 일치해야 함
- person/source/view/crop/rotation/mirror/timestamp discontinuity 시 미완료 cycle 전체 폐기
- 완료 cycle의 미래 극값·quantile을 이용한 look-ahead 정규화 금지

이 파일은 `SPECIFICATION_ONLY`다. 수치 threshold, 실행 phase provider, MediaPipe-Gold 승인,
사용자 세션 연결이 없다.

Phase research contract SHA-256:
`589ac54005267ff89be0ae679e8f0a2316d2640ebc4d76822b48e02b33ad27a2`

### 측면 좌·우 무릎 굴곡 연구 신호

`BarbellSquatLateralKneeFlexionSignalExtractor`는 source-bound person lock, 정확한
`lateral-full-body.v1` view token과 camera-geometry receipt가 있는 프레임에서만 MediaPipe WORLD
좌표의 `hip-knee-ankle` included angle을 계산한다. flexion은 `180 - included angle`이며 LEFT와
RIGHT를 독립 보존한다. 두 측정이 모두 유효할 때만 bilateral even median을 만든다.

joint 누락·낮은 raw confidence·퇴화 벡터·source/person/view/geometry 불일치는 측정 불가로
남긴다. 연속 acquisition wrapper는 timestamp 역행, 명시적으로 전달된 최대 관측 gap,
person/view/crop/image-size/rotation/mirror/preprocessing drift에서 segment를 끊는다. 최소 confidence와
최대 gap은 contract hash에 포함된 연구 acquisition gate일 뿐 FPS, phase 또는 자세 정답 threshold가
아니다.

이 신호는 phase state, completed cycle, rep count, `PASS/FAIL/UNKNOWN`, 점수, cue, feedback을
생성하거나 노출하지 않는다. AI Hub의 `척추의 중립`, `발과 무릎의 방향 일치`, 스쿼트 깊이 또는
안전성의 대체 판정도 아니다. 실행 phase provider와 positive shadow authorization은 여전히 0이다.

## no-verdict shadow 코어

`PoseShadowMeasurementCore`는 향후 검증된 provider가 만든 scalar만 completed cycle 단위로
요약하기 위한 메모리 전용 코어다.

- generated AI Hub exact binding, policy SHA, phase role, view, capability, side policy를 exact-match
- phase/source/person/view 토큰 생성자는 비공개이며 현재 issuer 없음
- phase scope token이 phase artifact, source/person reference, start/end를 결속
- timestamp 역행, gap, timeout, identity/view/provider drift, 2,048 scalar sample 초과 시 cycle 전체 폐기
- 출력은 count, coverage, min/max/mean, abstention count와 provenance SHA만 포함
- raw frame, landmark, timestamp series, 파일·DB·network I/O 없음
- verdict, criterion graph, `PASS/FAIL/UNKNOWN`, score, cue, feedback API 없음

실행 allowlist는 구조적으로 비어 있어 정상 앱 코드가 이 코어를 열 수 없다. 테스트는 순수 reducer
동작을 검증하기 위해서만 비공개 생성자를 reflection으로 호출한다.

Empty shadow authority drift pin:
`7339aa3aa9f47841089298d38d190839786f4097d9d32a1e74b9508e791e1dfe`

## 데이터 해석 제한

공식 AI Hub Validation은 기존 좌표 proxy 연구와 이번 phase 탐색에서 이미 관찰되어
`CONSUMED_DEVELOPMENT_BENCHMARK`다. 이후 threshold 선택, 성능 주장, 승격 판단의 confirmatory
holdout으로 다시 사용할 수 없다. AI Hub 좌표와 실제 휴대폰 MediaPipe landmark 사이의 paired
Gold도 아직 없다.

특히 MediaPipe 33 landmark에는 AI Hub의 `Back`과 `Waist`에 해당하는 척추 chain이 없으므로
shoulder-hip 선을 `척추 중립`으로 바꾸어 부르거나 승인하면 안 된다. 발바닥 지면 고정은 검증된
contact/support provider 전까지 카메라-only `NOT_OBSERVABLE`이다.

## 다음 승격 gate

1. observer issuer를 별도 모듈로 격리하고 실제 loaded runtime과 source/person/view/geometry를 결속
2. untouched subject x device x qualified-view MediaPipe-expert/mocap Gold
3. Gold에 고정된 causal phase decoder와 strict capture timestamp·reset challenge test
4. measurement construct별 blind review, quality/OOD calibration, 최소 coverage와 오차 하한
5. 별도 서명된 shadow authorization과 opt-in dogfood privacy gate
6. 그 뒤에도 사용자 release는 별도 signed release allowlist와 cue-content 승인을 통과해야 함

이 조건이 충족되기 전에는 `MeasureOnly=0`, 사용자 release=0을 유지한다.
