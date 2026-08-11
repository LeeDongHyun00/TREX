# Pose Gold decision contract

## 목적과 비권한 경계

M7의 Pose Gold v1 protocol과 study plan은 barbell squat 전용의 immutable
vertical slice다. v1의 ordered squat topology, restricted-bundle schema, synthetic
fixture pin 또는 readiness receipt를 이 계약이 수정하거나 일반화하지 않는다.

v2 decision contract는 41개 운동을 같은 형식으로 계획하기 위한
**catalog/plan/schema-only artifact**다. 실제 Gold evidence intake, 사용자 판정,
runtime phase decoder, 반복 횟수, cue, score, calibration, shadow 실행 또는 release를
수행하거나 승인하지 않는다.

고정된 catalog 범위는 다음과 같다.

| 항목 | 수 |
|---|---:|
| exercise | 41 |
| exact source condition | 97 |
| exercise-condition binding | 167 |
| engineering-reviewed binding | 148 |
| unresolved binding | 19 |
| release-eligible binding | 0 |

19개 unreviewed binding은 누락시키거나 기본 interpretation을 부여하지 않는다.
각 binding을 명시적인 unresolved row로 보존하고, measurement, phase, side, view,
capability 또는 determinate Gold를 추정하지 않는다.

## Phase role은 topology가 아니다

Policy의 phase role은 criterion이 어느 의미적 구간에 적용되는지를 나타내는
symbolic applicability다. 다음을 뜻하지 않는다.

- 운동별 ordered state graph
- state transition 순서
- 반복 시작·종료 경계
- interval의 지속시간
- landmark threshold 또는 자동 검출 규칙

현재 catalog의 여섯 phase role은 다음과 같다.

- `trex.phase-role.full-cycle.v1`
- `trex.phase-role.lengthened-endpoint.v1`
- `trex.phase-role.concentric.v1`
- `trex.phase-role.contracted-endpoint.v1`
- `trex.phase-role.static-hold.v1`
- `trex.phase-role.compound-transition.v1`

여섯 role 모두 승인된 phase-scope artifact가 없다. 따라서 v2 artifact는 role ID와
binding count만 보존하며 timestamp interval, boundary, occurrence count 또는 topology를
생성하지 않는다. 승인된 annotation scope가 없는 role의 Gold state는
`UNKNOWN_GOLD`다.

M7 barbell squat v1의 ordered topology는 v1 artifact 안에서만 유효하다. 이를
catalog-wide topology로 승격하거나 standing-side-crunch에 재사용해서는 안 된다.

## Side policy와 decision slot

다음 아홉 side kind를 원형 그대로 보존한다.

- `MIDLINE`
- `GLOBAL_BODY`
- `BILATERAL_COUPLED`
- `BILATERAL_INDEPENDENT`
- `ACTIVE_LIMB`
- `LEAD_LIMB`
- `TRAIL_LIMB`
- `ALTERNATING_PAIR`
- `CONTRALATERAL_PAIR`

Side kind를 모두 MIDLINE으로 축약하거나 좌우 평균으로 바꾸지 않는다.

- `BILATERAL_INDEPENDENT`는 LEFT와 RIGHT 두 decision slot을 독립적으로 유지한다.
  한쪽의 UNKNOWN을 다른 쪽 결과나 평균으로 덮지 않는다.
- `BILATERAL_COUPLED`는 하나의 coupled-pair slot이다. MIDLINE도 아니고 독립된
  LEFT/RIGHT 두 판정도 아니다.
- `MIDLINE`과 `GLOBAL_BODY`는 각각 자신의 단일 slot을 유지한다.
- ACTIVE, LEAD, TRAIL, ALTERNATING, CONTRALATERAL 같은 role-relative kind는
  symbolic slot만 선언한다. Policy에 pin된 정확한 `roleResolverContractId`와
  승인된 resolver artifact가 없으면 anatomical LEFT/RIGHT를 배정하지 않는다.
- resolver가 없거나 ID가 다르거나 role assignment가 충돌하면 해당 slot은
  `UNKNOWN_GOLD`다.

이 규칙은 side 결과를 계산하는 알고리즘이 아니라 향후 annotation/resolver
계약이 채워야 할 exact schema다.

## Standing side crunch의 정확한 planning shape

Standing side crunch에는 reviewed binding이 정확히 5개 있다.

| Measurement construct | Phase role | Side kind | 현재 Gold |
|---|---|---|---|
| hand-posterior-head-position | full-cycle | BILATERAL_COUPLED | UNKNOWN_GOLD |
| axial-trunk-neutrality-proxy | full-cycle | MIDLINE | UNKNOWN_GOLD |
| contralateral-knee-elbow-distance | contracted-endpoint | CONTRALATERAL_PAIR | UNKNOWN_GOLD |
| active-knee-lateral-torso-position | contracted-endpoint | ACTIVE_LIMB | UNKNOWN_GOLD |
| forward-face-orientation-maintenance | full-cycle | MIDLINE | UNKNOWN_GOLD |

즉 phase shape은 full-cycle 3개와 contracted-endpoint 2개이며, side shape은
coupled 1개, midline 2개, active-limb 1개, contralateral-pair 1개다. ACTIVE_LIMB와
CONTRALATERAL_PAIR row는 각각 policy의 정확한 standing-side-crunch resolver ID를
보존해야 한다.

그러나 standing-side-crunch에는 승인된 phase scope/topology, annotation rubric,
role resolver artifact, view/capability provider, reference evidence contract 또는
calibration이 없다. 따라서 다섯 row 모두 `UNKNOWN_GOLD`만 허용하며 determinate
decision count와 eligible Gold count는 모두 0이다. `DIRECT` observability도 이
경계를 우회하지 못한다.

## 데이터와 privacy 경계

v2 decision contract가 읽고 출력할 수 있는 것은 public, content-addressed catalog,
compiled policy, approval pin, planning registry/matrix와 planning artifact뿐이다.

다음 입력이나 출력은 이 단계에서 허용하지 않는다.

- restricted bundle
- raw media 또는 pose/trajectory
- participant, reviewer, consent, session 또는 capture identifier
- frame/capture/reference timestamp나 phase interval
- leaf evidence hash
- 실제 review/adjudication record

`REAL_RESTRICTED_GOLD` intake와 `SYNTHETIC_CONFORMANCE` intake는 모두 구현되지
않았고 승인되지 않았다. 이 compiler에 bundle path나 evidence-class option을
추가해 planning artifact를 intake receipt로 오인하게 해서는 안 된다.

## Authority

생성 artifact의 다음 아홉 authority axis는 정확히 정수 0이어야 한다.

- `calibrationAuthority`
- `cueAuthority`
- `phaseDecoderAuthority`
- `releaseAuthority`
- `repCountAuthority`
- `runtimeProviderAuthority`
- `scoreAuthority`
- `shadowAuthority`
- `userPassFailUnknownAuthority`

Binding의 release state도 `CATALOG_ONLY`이며 전체
`releaseEligibleBindingCount`는 0이다.

## 다음 승인 gate

UNKNOWN 이외의 Gold state나 eligible count를 허용하려면 최소한 다음 artifact와
검증기가 별도로 필요하다.

1. 운동·phase role별 승인된 annotation scope/rubric와 annotation tool
2. role-relative side policy별 정확한 resolver artifact와 approval provenance
3. authoritative view qualification 및 camera geometry contract
4. criterion이 요구하는 device/capability provider attestation
5. synchronized reference modality와 clock-alignment contract
6. criterion/phase 품질 gate 및 calibration artifact
7. participant consent, retention, backup, deletion과 restricted-access approval
8. detached signature verifier와 pinned trust registry
9. participant-grouped split seal과 locked-test governance

이 gate들은 hash slot의 존재만으로 충족되지 않는다. 승인 issuer, signature,
trust chain과 artifact 내용이 함께 검증돼야 한다.

## 생성과 freshness 검사

Compiler가 구현된 뒤 다음 명령을 사용한다.

```powershell
python tools/compile_pose_gold_decision_contract.py
python tools/compile_pose_gold_decision_contract.py --check
```

`--check`는 source coverage, compiled policy, approval, planning registry/matrix와
planning artifact의 provenance 및 생성 결과의 byte-exact freshness를 검증해야
한다. 일반 생성과 `--check` 어느 쪽도 authority나 evidence intake를 수행하지
않는다.

현재 고정값은 다음과 같다.

| Artifact | SHA-256 |
|---|---|
| M8 planning registry artifact | `ff6fc6c01331ad395a38591f5f07c25527b1d42d336d4136e09c9551d1f42798` |
| M8 planning matrix artifact | `feeffe74a4bb22e939f8138a6f69da6021a2777bdecea6cebe3787971fe4fd75` |
| v2 decision-contract artifact | `1ce7d1d52dedf46723fd5b088433460f2b92523501398b6976b10b9595bb653d` |
| compiler canonical UTF-8/LF text | `08a15f5dac50252b5cbbfb358dfb8de548eab879727071fd9fca0f5a640cb132` |
| compiler test canonical UTF-8/LF text | `c40f8da9d2af50342f2b33f2fa1c0665038772d568fc2efbe5702bb3675e456f` |

이 hash들은 repository drift를 탐지하며, 서명·독립 승인·Gold 증거나 실행 권한을
대체하지 않는다.
