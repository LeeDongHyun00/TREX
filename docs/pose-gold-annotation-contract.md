# Pose Gold annotation contract

## 목적

M10 annotation contract는 AI Hub policy의 criterion을 실제 Gold annotation 단위로
연결하기 위한 catalog-scale schema다. M7의 barbell-squat 전용 topology validator를
수정하거나 다른 운동에 재사용하지 않는다. M9 decision contract를 authoritative input으로
사용해 reviewed binding을 `binding × phase role × symbolic side slot` template로 확장한다.

현재 catalog에는 41개 운동, 97개 exact condition, 167개 exercise-condition binding이
있다. Engineering interpretation이 있는 148개 binding은 203개 annotation template로
확장된다. `BILATERAL_INDEPENDENT`가 LEFT와 RIGHT를 별도로 유지하기 때문에 template
수가 binding 수보다 많다. Source interpretation이 미해결인 19개 binding은 template을
만들지 않고 별도 unresolved row로 보존한다.

이 artifact는 annotation schema와 compiler-owned synthetic conformance만 정의한다.
실제 participant data, restricted evidence intake, Gold 승인, calibration, runtime 판정,
cue, score, shadow 실행 또는 release authority를 제공하지 않는다.

## Phase-scope reference

Policy의 여섯 phase role은 적용 의미이며 운동 topology가 아니다. Annotation template은
정확한 `phaseRoleId`를 참조하지만 state graph, landmark threshold, 반복 경계 또는
timestamp를 만들지 않는다.

향후 resolved phase evidence의 key는 최소한 다음 identity를 가져야 한다.

- exact `phaseRoleId`
- 한 annotation unit 안의 `occurrenceOrdinal`
- 승인된 운동별 phase-scope/annotation artifact
- observation clock에 결속된 시작·종료 uncertainty evidence

Interval convention은 `START_INCLUSIVE_END_EXCLUSIVE`로 고정한다. 그러나 현재는 여섯
role 모두 승인된 scope artifact가 없으므로 실제 interval과 timestamp를 허용하지 않으며
scope state는 `UNKNOWN_GOLD`만 가능하다. `full-cycle`을 다른 role에 복사하거나
`contracted-endpoint`를 전체 cycle로 대체할 수 없다.

## Side-role reference

아홉 side policy와 symbolic slot은 다음처럼 손실 없이 유지한다.

| Policy kind | Symbolic annotation slot |
|---|---|
| `MIDLINE` | `MIDLINE` |
| `GLOBAL_BODY` | `GLOBAL_BODY` |
| `BILATERAL_COUPLED` | `BILATERAL_PAIR` |
| `BILATERAL_INDEPENDENT` | `LEFT`, `RIGHT` |
| `ACTIVE_LIMB` | `ACTIVE_LIMB` |
| `LEAD_LIMB` | `LEAD_LIMB` |
| `TRAIL_LIMB` | `TRAIL_LIMB` |
| `ALTERNATING_PAIR` | `ALTERNATING_PAIR` |
| `CONTRALATERAL_PAIR` | `CONTRALATERAL_PAIR` |

Coupled pair는 MIDLINE도 아니고 독립 LEFT/RIGHT 두 판정도 아니다. On-device shadow
spec도 `BILATERAL_PAIR` 한 채널로 같은 의미를 사용한다.

ACTIVE, LEAD, TRAIL, ALTERNATING, CONTRALATERAL 같은 role-relative slot은 binding
policy의 정확한 `roleResolverContractId`를 유지한다. 승인된 resolver artifact가 없으면
anatomical LEFT/RIGHT assignment를 만들 수 없고 관련 evidence는 `UNKNOWN_GOLD`다.

## Criterion annotation template

각 reviewed template identity는 다음 항목의 exact product다.

1. `(exerciseId, sourceConditionId, bindingId, bindingPolicySha256, policyRegistrySha256)`
2. binding policy의 한 `phaseRoleId`
3. side policy의 한 symbolic slot과 exact resolver ID

Template은 M9의 measurement construct, observability, view applicability, required
capability와 calibration provenance도 그대로 보존한다. 현재 phase scope, resolver,
qualified view, capability attestation, synchronized reference evidence, calibration과 trusted
intake가 모두 승인되지 않았으므로 permitted Gold state는 `UNKNOWN_GOLD`뿐이다.

Standing side crunch는 5개 template을 가진다. 세 개는 `full-cycle`, 두 개는
`contracted-endpoint`이고 side shape은 coupled 1, midline 2, active 1,
contralateral 1이다. Barbell squat은 4개 binding이지만 bilateral-independent slot을
분리하므로 6개 template을 가진다. 두 exercise 모두 determinate template은 0이다.

## Synthetic conformance와 privacy

Compiler-owned conformance envelope는 각 registered profile에 대해 occurrence 0인 symbolic
phase/side reference와 exact criterion template을 생성한다. 모든 row는
`UNKNOWN_GOLD`이고 authority는 0이다. 이 envelope는 schema 관계를 시험할 뿐 Gold
annotation이나 real evidence count가 아니다.

공개 helper는 exact `exerciseId`만 받고 repository의 M7–M10 입력을 내부에서 다시
빌드한다. Caller가 넘긴 schema·profile·self-hash 또는 임의 경로를 입력으로 받지 않으므로
자기 일관적인 변조 mapping을 compiler-owned envelope로 승격할 수 없다.

외부 bundle path, participant/reviewer/session/capture identifier, timestamp, raw media,
landmark, measurement, consent record 또는 leaf evidence hash는 받거나 출력하지 않는다.
실제 restricted intake는 별도 rights approval, detached signature verifier와 pinned trust
registry가 구현되기 전까지 금지한다.

## Authority와 다음 gate

Calibration, cue, phase decoder, release, rep count, runtime provider, score, shadow와
user PASS/FAIL/UNKNOWN의 아홉 authority axis는 모두 정확히 정수 0이다.

Determinate Gold를 허용하려면 최소한 다음 별도 artifact가 필요하다.

1. 운동·phase role별 승인된 scope와 annotation rubric
2. role-relative side policy별 resolver implementation, evidence와 approval
3. authoritative view/camera-geometry 및 capability-provider attestation
4. synchronized reference modality와 clock alignment
5. criterion/quality calibration과 participant-grouped split seal
6. consent·retention·backup·deletion 승인
7. detached signer와 pinned trust registry

## 고정된 구현 provenance

| 항목 | SHA-256 |
|---|---|
| annotation contract artifact self-hash | `5d52c5408187a24e50c0017fb086675aadef8be757aa1091e6abac8ed64a57b7` |
| compiler canonical UTF-8/LF text | `22e11e5f4af5812a352eaace61a496a83ee0da4bb9d0dce79e66440ad7b05d76` |
| compiler test file | `299b3497db52c7abc56c206f882e0134f51ab7d2c8af92627778ceff3d2c77c2` |
| rendered JSON bytes | `8fa55b91bd189f89c8a759305e91873ff7ace088ff03d546ddf0cc23dc921994` |

이 값들은 repository drift를 검출하기 위한 재현성 pin이며 승인 서명이나 Gold·runtime
권한이 아니다.

## 생성과 검사

```powershell
python tools/compile_pose_gold_annotation_contract.py
python tools/compile_pose_gold_annotation_contract.py --check
```

Script와 package 실행은 같은 artifact를 생성해야 한다. `--check`는 M9 contract와 그
upstream policy/planning provenance를 다시 빌드하고 byte-exact freshness를 검사하며,
evidence intake나 authority transition을 수행하지 않는다.
