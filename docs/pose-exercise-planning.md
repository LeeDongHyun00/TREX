# 41개 운동 계획 레지스트리

## 목적과 경계

`docs/pose-exercise-planning-registry.v1.json`은 41개 운동을 동일한 정책
provenance로 계획하는 데이터 입력이다. 이 레지스트리는 런타임 설정, 판정기,
phase decoder 또는 출시 승인 목록이 아니다. 등록 여부와 관계없이 cue, 점수,
반복 횟수, 사용자 PASS/FAIL/UNKNOWN 판정, shadow 실행, provider 연결, calibration,
출시 권한은 모두 0이다.

현재 고정된 catalog 범위는 다음과 같다.

| 항목 | 수 |
|---|---:|
| 운동 | 41 |
| exact condition | 97 |
| exercise-condition binding | 167 |
| engineering review 완료 binding | 148 |
| release eligible binding | 0 |

정책 review는 관측 가능성·phase role·좌우 역할·view·capability 요구를
명시했다는 뜻일 뿐, Gold 근거 또는 임계값 검증을 뜻하지 않는다.

## 생성된 41운동 planning matrix

`docs/pose-exercise-planning-matrix.v1.json`은 authoritative source coverage와
compiled policy를 exercise ID 분기 없이 결합한 생성 산출물이다. 현재 artifact SHA는
`feeffe74a4bb22e939f8138a6f69da6021a2777bdecea6cebe3787971fe4fd75`이다.
167개 binding 모두에 source condition, review/release state, claim boundary,
observability, phase, side와 resolver, view, capability, calibration 및 review evidence를
보존한다.

현재 planning disposition은 다음과 같다.

| 상태 | binding 수 |
|---|---:|
| direct이지만 Gold calibration 필요 | 80 |
| proxy이며 blind Gold validation 필요 | 52 |
| 카메라만으로 비관측이며 추가 capability 필요 | 16 |
| source interpretation 미해결 | 19 |

생성기는 등록 plan의 공통 policy projection만 검증한다. 바벨 스쿼트 Gold bundle의
깊은 schema·privacy 검증은 기존 `pose_gold_workflow.py`의 별도 책임이다. Matrix의
compiler hash도 구현 drift 확인용일 뿐 승인 또는 실행 권한이 아니다.

```powershell
python tools/compile_pose_exercise_planning_matrix.py --check
python -m tools.compile_pose_exercise_planning_matrix --check
python -m unittest -v tools.test_compile_pose_exercise_planning_matrix
```

## 등록된 두 planning slice

레지스트리는 exercise ID순으로 두 artifact만 등록한다.

- `barbell-squat`: `PREREGISTERED_GOLD_STUDY_PLAN_NOT_READY`. 승인되지 않은
  runtime이나 사용자 판정을 만들지 않는 Gold 수집 사전 계획이다. 실제 evidence
  count는 0이며 readiness는 `NOT_READY`이다.
- `standing-side-crunch`:
  `POLICY_PROJECTION_ONLY_NO_APPROVED_TOPOLOGY`. 5개 binding의 승인된 catalog
  policy를 한 줄씩 그대로 투영한다. full-cycle과 contracted-endpoint phase role,
  active limb·contralateral pair·bilateral coupled·midline side policy, 서로 다른
  view와 capability 요구를 표현하지만 phase graph는 정의하지 않는다.

나머지 39개 운동은 여전히 catalog-only이며 계획 artifact가 없다. 계획 artifact가
없는 운동을 암묵적 기본값으로 해석해서는 안 된다. 반대로 계획이 등록돼 있어도
readiness나 release 권한은 생기지 않는다.

## Standing side crunch 선언의 의미

`docs/standing-side-crunch-gold-planning-declaration.v1.json`은 새 운동을 코드
분기 없이 표현할 수 있는지 검증하기 위한 두 번째 shape이다. 각 criterion row는
compiled policy의 다음 값을 exact-join할 수 있도록 보존한다.

- binding ID, binding policy hash, exercise ID, exact source condition ID, policy
  registry hash
- review/release state, binding reason code와 decision evidence reference
- semantic ID, semantic family ID, measurement construct, claim boundary,
  observability
- phase applicability 전체 객체
- side policy와 role resolver 전체 객체
- view applicability 전체 객체
- required capability 집합, 전체 calibration provenance, unsupported reason 집합,
  review evidence reference

phase requirement는 policy에 존재하는 role ID의 exact-set만 기록한다.
`NOT_DEFINED_NO_APPROVED_PHASE_GRAPH`는 순서, 전이, 지속시간, 반복 경계,
임계값을 아직 승인하지 않았다는 뜻이다. 따라서 이 선언만으로 endpoint를
검출하거나 full cycle을 복원할 수 없다.

`DIRECT`도 검증된 판정 임계값을 의미하지 않고, `PROXY_UNVALIDATED`도 원래의
해부학적·안전 claim을 대신하지 않는다. capability ID는 필요한 센서·추적 품질을
나타낼 뿐, 현재 기기가 이를 제공한다는 attestation이 아니다. 실제 participant,
capture, reviewer, criterion, phase, view evidence count는 모두 0이다.

## 무결성과 변경 규칙

두 JSON 입력은 top-level `artifactSha256`를 제거한 RFC 8259 JSON을
UTF-8, Unicode NFC, key 정렬, compact separator, trailing newline 없이 직렬화해
SHA-256을 계산한다. 저장 파일은 UTF-8, 2-space pretty JSON, LF, final newline을
사용한다.

레지스트리는 각 계획의 project-relative `docs/...` 경로, artifact kind,
self-hash, plan state를 함께 고정한다. 또한 source catalog, source coverage,
metadata set, approved policy, approval artifact, compiled policy registry의 여섯
hash를 고정한다. 경로나 내용 또는 upstream policy가 바뀌면 관련 self-hash와
생성된 planning matrix를 함께 다시 검증해야 한다.

운동을 추가할 때에는 다음 경계를 유지한다.

1. compiled policy의 exercise-binding exact-set을 투영한다.
2. policy에 없는 phase topology, 좌우 resolver, view, capability, threshold를
   추정해서 넣지 않는다.
3. 실제 Gold·calibration·provider attestation이 없으면 `NOT_READY`와 0 authority를
   유지한다.
4. registry에 plan을 등록해도 release eligible count는 별도의 승인 artifact 없이는
   0을 유지한다.
