# TREX pose Gold evidence intake v1

## 현재 결론과 실행 범위

v1은 **바벨 스쿼트 하나를 끝까지 검증하는 vertical slice**다. 현재 compiler가 실행할 수 있는
운동은 `barbell-squat` 하나뿐이다. protocol의 41개 운동, 97개 exact condition, 167개 policy
binding 수치는 전체 catalog context이며 41개 운동을 실행 지원한다는 뜻이 아니다. split,
privacy, review, geometry, authority 같은 schema 개념은 재사용할 수 있지만, 두 번째 운동부터는
운동별 phase topology와 reference capability를 담는 versioned exercise-plan/compiler가 먼저
승인되어야 한다. v1 compiler에 운동 이름 분기를 덧붙여 범위를 넓히지 않는다.

현재 실제 capture, participant, reviewer, phase Gold, view Gold, criterion Gold, calibration
artifact는 모두 0이다. readiness는 `NOT_READY`이고 `PASS/FAIL/UNKNOWN`, 반복 횟수, 점수,
shadow 실행, cue, runtime provider 및 release 권한은 모두 0이다.

| artifact | 역할 | self SHA-256 |
|---|---|---|
| [`pose-gold-protocol.v1.json`](pose-gold-protocol.v1.json) | 바벨 스쿼트 vertical slice용 strict intake 및 공통 안전 계약 | `dec8870a206fdeac132face2ca926e44ee87113b8cf6aa434241d69ae94552cb` |
| [`pose-data-rights-manifest.v1.json`](pose-data-rights-manifest.v1.json) | license·consent·retention·backup 및 외부 승인 trust 준비 상태 | `bfe2a80776fb65da20724d475bda61cf2adf6692587fe4f67f22c238a3a1b4df` |
| [`barbell-squat-gold-study-plan.v1.json`](barbell-squat-gold-study-plan.v1.json) | 바벨 스쿼트 4개 exact binding의 사전 등록 계획 | `a8299498fb045f870cff5a5151659767a6c96c1fc7521e606c1c09475a2ab172` |
| [`barbell-squat-gold-readiness.v1.json`](barbell-squat-gold-readiness.v1.json) | 개인·frame 정보를 담지 않는 aggregate-only 현재 상태 | `e97a31d292fc1b7408f951f36b6bbfab13b472fa9eabbd3a5a44f6856d6fc43b` |

각 JSON의 `artifactSha256`는 그 top-level field만 제거한 JSON을 Unicode NFC, key 정렬,
공백 없는 RFC 8259 JSON으로 직렬화한 UTF-8 bytes의 SHA-256이다. 파일은 UTF-8, 2-space
pretty JSON, LF, 마지막 newline 하나를 사용한다. self hash는 무결성 식별자일 뿐 전자서명,
승인자 신원, trust 또는 실행 권한이 아니다.

readiness는 실제 compiler drift도 추적한다. 현재 `tools/pose_gold_workflow.py`의 canonical-LF
SHA-256은 `08bfbee809163afc97894bbae10b47c5a28765bd41d3e346859f34927655e519`이다.
이 값 역시 재현성 pin일 뿐 독립 승인이나 실행 권한은 아니다.

## v1 승인·권한 경계

rights와 study plan의 `*ArtifactSha256` slot은 **schema placeholder**다. 올바른 형식의 임의
64-hex 값을 모든 slot에 채우고 blocker나 숫자를 바꿔도 v1은 `VERIFIED_READY`가 될 수 없고
`REAL_RESTRICTED_GOLD`를 받을 수 없다. SHA-256 값의 존재만으로 승인 주체, 서명 진위,
만료·철회 상태 또는 신뢰 사슬을 증명할 수 없기 때문이다.

positive real intake에는 최소 protocol v2가 필요하다. v2는 다음을 모두 정의하고 구현해야 한다.

- 승인 payload와 manifest를 묶는 detached signature
- 허용된 signer와 key를 고정하는 versioned pinned trust registry
- signature, registry pin, 만료 및 철회 상태의 fail-closed 검증
- 검증 결과를 rights transition에 연결하는 별도 승인 절차

따라서 현재 rights manifest는 의도적으로 `UNVERIFIED/NOT_READY`이고 trust verification은
`NOT_IMPLEMENTED_IN_V1`이다. real participant collection, real bundle intake, fitting,
calibration, locked test, shadow 또는 사용자 runtime 사용은 금지된다. participant consent와
reviewer identity는 이후 버전에서도 공개 manifest나 공개 receipt에 들어가지 않는다.

## restricted six-file bundle

synthetic intake fixture와 미래 버전의 real intake bundle은 승인된 offline restricted workspace
안에서 다음 **정확한 여섯 개 regular source-relative 파일**로만 구성한다. symlink, reparse
point, 미선언 파일과 bundle 밖 경로 참조는 금지한다.

1. `bundle-manifest.json` — evidence class, protocol·rights·study root, observer/model/
   preprocessing/landmark-schema contract와 exact file set
2. `capture-groups.jsonl` — restricted participant/session key, device tier, exercise, camera
   geometry epoch, authoritative capture setup과 capture-group continuity
3. `observations.jsonl` — strict capture timestamp, production MediaPipe normalized/world
   observation, confidence와 동기화된 reference evidence의 restricted pairing
4. `blind-reviews.jsonl` — 각 adjudicable unit에 대한 세 명의 독립 blinded phase·view·criterion
   review
5. `adjudications.jsonl` — 세 review 제출 뒤의 합의·불일치·boundary uncertainty 및
   `UNKNOWN_GOLD` 결정
6. `split-manifest.json` — participant 단위 development/calibration/locked-internal/external
   배정, capture/content 중복 검사와 locked test `UNCONSUMED` 상태

여섯 파일은 모두 민감한 restricted evidence다. raw image/video, pose landmark, MoCap·contact
sensor trajectory, participant/session key, reviewer/consent key, capture timestamp와 leaf digest를
Git에 저장하지 않는다. 공개 plan-only readiness의 실제 evidence count는 모두 0이다. 임시
synthetic receipt는 실제 Gold count 대신 비식별 fixture record shape와 허용된 root artifact
identity만 담는다.

## offline-only와 Android privacy 경계

real evidence는 network가 차단된 restricted workspace에서만 처리한다. 일반 Android 앱은 Gold
수집기가 아니며 raw frame, landmark, timestamp series 또는 reviewer evidence를 다음 위치에
기록하지 않는다.

- APK/AAB asset과 resource
- 앱 database, preferences, file, cache 또는 temporary directory
- logcat, crash payload, analytics 또는 telemetry
- 서버 upload, background sync 또는 backup

온디바이스 관측이 필요해도 별도로 승인된 계측 build와 collection protocol이 먼저 필요하다.
현재 v1에서는 real collection과 real intake가 항상 금지된다.

## synthetic conformance와 real Gold의 구분

`SYNTHETIC_CONFORMANCE`는 parser와 fail-closed 규칙만 시험한다. compiler가 고정한 결정론적
비식별 fixture의 여섯 파일 byte length와 SHA-256이 모두 정확히 일치할 때만 같은 immutable
snapshot을 파싱한다. 임의 외부 bundle을 `SYNTHETIC_CONFORMANCE`로 표시해 넣는 경로는 없다.
이 fixture로 canonical hash, provenance pin, timestamp/phase gap, split leakage, 세 reviewer, view
evidence 및 missing capability 처리를 검증할 수 있다. synthetic 결과는 real capture,
participant, reviewer 또는 Gold count를 증가시키지 않고 calibration, provider 또는 release
근거가 될 수 없다.

`REAL_RESTRICTED_GOLD`는 v1 schema에 미래 evidence class로 예약되어 있을 뿐이다. protocol v1
compiler는 rights slot 내용과 관계없이 이를 항상 거부해야 한다. detached signature와 pinned
trust registry를 구현한 protocol v2가 승인되기 전에는 real bundle을 v1로 승격하거나
`--force`, rights 우회, synthetic-to-real 변환 경로를 제공하지 않는다.

## 바벨 스쿼트 사전 등록 경계

study plan은 generated policy registry의 바벨 스쿼트 exact 4개 binding 5-tuple을 고정한다.
phase cycle은 `READY -> DESCENDING -> BOTTOM -> ASCENDING -> READY`이고 첫 transition
boundary부터 마지막 `ASCENDING -> READY` boundary 직전까지의 `[start,end)`만 완성 cycle이다.
timestamp gap은 보간하지 않고 해당 cycle을 `UNKNOWN_GOLD`로 만든다. AI Hub `active`와 같은
동일 신호 기반 사후 surrogate는 phase Gold가 아니다.

front/rear는 pose body axis만으로 추론하지 않는다. capture setup 또는 독립 image review가
없으면 view는 `UNKNOWN_GOLD`다. plantar contact는 검증된 contact/force sensor가 없으면
`UNKNOWN_GOLD_AND_NOT_OBSERVABLE`이다. 이미 연구에서 사용한 공식 AI Hub Validation은 Gold
fitting, calibration 및 locked test에서 제외한다.

## CLI 사용

공개 plan과 빈 aggregate readiness의 일치 여부는 dataset 경로 없이 검사한다.

```powershell
python tools/pose_gold_workflow.py `
  --protocol docs/pose-gold-protocol.v1.json `
  --rights docs/pose-data-rights-manifest.v1.json `
  --study-plan docs/barbell-squat-gold-study-plan.v1.json `
  --output docs/barbell-squat-gold-readiness.v1.json `
  --check
```

synthetic conformance는 임의 경로를 받는 운영 명령이 아니라 compiler-owned 결정론 fixture를
생성하는 단위 테스트로만 실행한다.

```powershell
python -m unittest -v tools.test_pose_gold_workflow
```

v1에는 성공해야 하는 real intake CLI 예시가 없다. `REAL_RESTRICTED_GOLD` 입력은 반드시
fail-closed해야 한다.

## 권리 manifest 해시 핀 제거 (2026-08-12, 소유자 결정)

`_validate_rights`가 rights manifest의 SHA-256을 `APPROVED_RIGHTS_V1_SHA256`과 대조해 불일치 시 실패시키던 검사를 제거했다. 이에 따라 `barbell-squat-gold-readiness.v1.json`을 재생성했으며, **바뀐 필드는 `compilerImplementation.canonicalTextSha256`과 `artifactSha256` 두 개뿐**이다(영수증의 입력 데이터는 전부 동일). 컴파일러가 자기 소스 해시를 영수증에 박기 때문에 발생한 변경이다.

**잃은 것**: v1 manifest가 사실상 불변이던 성질. 이제 manifest 파일을 편집해 `readiness`를 VERIFIED_READY로 바꿔도 **신원 대조만으로는** 막히지 않는다.

**남아 있는 것**: 같은 함수의 구조 검사는 그대로다. VERIFIED_READY를 주장하려면 여전히 approval evidence slot 8개가 모두 non-null SHA여야 하고, service level이 양의 정수여야 하며, retention safeguard 전부가 true, 접근 감사가 verified, 전 data class가 ready여야 한다. 참가자를 한 명도 촬영하지 않은 현재 상태에서 이 조건들을 만족시키려면 존재하지 않는 증빙 해시를 지어내야 하므로, **부당한 전환을 막는 실질적 장치는 유지된다.** 제거된 것은 그 앞단의 신원 확인이다.

**대안 경로 기록**: AI Hub 연구 목적으로 권한이 필요했던 건은 이 핀을 우회하지 않고 [별도 범위의 연구 manifest](pose-data-rights-manifest.aihub-research.v1.json)를 신설해 해결했다. v1은 참가자 수집(Gold) 트랙을 관장하고 그 트랙은 여전히 NOT_READY다. 두 manifest는 `DISJOINT_SCOPE_NEITHER_SUPERSEDES_NOR_TRANSITIONS` 관계다.

## 두 번째 운동 전 gate

두 번째 운동을 추가하기 전에 versioned exercise-plan/compiler가 운동별 ordered topology,
phase role, required capability와 reference modality를 data-driven contract로 받아야 한다.
두 개 이상의 서로 다른 운동 fixture가 같은 compiler 경로를 통과하고, compiler source에
exercise ID별 분기가 없으며, 각 receipt가 다른 운동의 blocker나 provenance를 섞지 않는다는
acceptance test가 필요하다. 이 gate를 통과하기 전에는 41개 운동 지원을 선언하지 않는다.
