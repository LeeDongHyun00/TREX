# Pose Gold scope/resolver requirements

## 목적

M11 A0 artifact는 determinate Gold annotation 전에 필요한 phase-scope와 role-relative
side-resolver artifact의 정확한 결손 목록이다. M10 annotation contract를 repository의
M7–M10 입력에서 독립 재빌드한 뒤 reviewed template만 grouping한다.

- 203개 annotation template 전부를 78개 `(exerciseId, phaseRoleId)` requirement가
  중복·누락 없이 덮는다.
- role-relative template 18개를 13개
  `(exerciseId, sidePolicyKind, roleResolverContractId)` requirement가 정확히 덮는다.
- source interpretation이 미해결인 19개 binding은 phase나 side 의미를 발명하지 않으며
  requirement를 만들지 않는다.

이 artifact는 승인기나 trust registry가 아니다. 모든 91개 requirement state는
`PENDING_TRUSTED_ARTIFACT`이고 candidate artifact, detached signature, trust registry와
approver field는 `null`이다. Resolver requirement의 anatomical assignment도 `null`이며
phase requirement에는 그 필드 자체가 없다.

## 의미 보존

Phase requirement는 policy의 여섯 role을 exercise별로만 묶는다. Topology, interval,
timestamp, threshold 또는 occurrence를 만들지 않는다. 따라서 `full-cycle`을 endpoint나
static hold로 대신할 수 없다.

Resolver requirement는 ACTIVE, LEAD, TRAIL, ALTERNATING, CONTRALATERAL 같은 exact
role-relative policy와 binding policy의 exact resolver ID를 보존한다. Fixed-side policy는
resolver requirement를 만들지 않는다. 특히:

- `BILATERAL_COUPLED` 16개 template은 단일 `BILATERAL_PAIR`이며 resolver가 없다.
- `BILATERAL_INDEPENDENT` 55개 binding은 LEFT/RIGHT 110개 template로 분리되며
  resolver가 없다.
- role-relative 18개 template만 exact 13개 resolver requirement에 속한다.

공개 candidate validator는 caller가 expected requirement나 경로를 주입하게 하지 않는다.
Candidate 하나만 받고 repository-canonical M10과 A0를 내부에서 다시 빌드해 exact row와
비교한다. 현재 validator가 받아들일 수 있는 것은 committed pending placeholder뿐이며,
`APPROVED`, 임의 artifact SHA, signature, trust root, approver 또는 anatomical assignment는
모두 거부된다.

## 권한과 외부 blocker

Calibration, cue, phase decoder, release, rep count, runtime provider, score, shadow와 user
PASS/FAIL/UNKNOWN의 아홉 authority axis는 모두 정확히 정수 0이다. 이 schema에는 phase
topology, timestamp, evidence intake, review/adjudication, signature verification, trust
registry, evaluator, runtime provider, cue 또는 release transition이 없다.

Positive transition에는 repository 밖에서 승인된 bootstrap trust root와 최소한 다음이
필요하다.

1. public key bytes, issuer identity, 허용 purpose와 distinct-signer quorum
2. key custody, compromise, revocation, rotation과 rollback 방지 정책
3. trusted time source와 registry monotonic state
4. phase-scope와 side-resolver candidate의 별도 semantic schema·rubric
5. detached signature verifier와 externally pinned registry

임의 repository hash나 새로 추가한 self-signed key는 이 blocker를 해소하지 않는다. 실제
trust root가 제공되기 전에는 A0 뒤에 빈 verifier를 추가해도 cryptographic readiness가
아니므로 모든 requirement를 pending으로 유지한다.

## 고정된 구현 provenance

| 항목 | SHA-256 |
|---|---|
| requirement artifact self-hash | `5700926bb5aa13e38aa118599d4353691f202090792432a7a539473ad1e0074a` |
| compiler canonical UTF-8/LF text | `0353ae47bf34cc594ff70be8d7c176fde61e43aa752927c31a9470f882149554` |
| compiler test file | `a7e6be894190e16be7fdc4441dc448cc141c5ae9fc7d61eed0bbb07f95251547` |
| rendered JSON bytes | `136be701df6d8a2eae900531e3a62bbce19cdd7e7874878b6fd76a33324ce58c` |
| pinned M10 annotation artifact | `5d52c5408187a24e50c0017fb086675aadef8be757aa1091e6abac8ed64a57b7` |

이 값들은 repository drift를 검출하는 재현성 pin이며 승인 서명이나 trust root가 아니다.

## 생성과 검사

```powershell
python tools/compile_pose_gold_scope_resolver_requirements.py
python tools/compile_pose_gold_scope_resolver_requirements.py --check
python -m tools.compile_pose_gold_scope_resolver_requirements --check
python -m unittest -v tools.test_compile_pose_gold_scope_resolver_requirements
```

Script는 지정된 output만 원자적으로 갱신하며 `--check`는 M10과 그 upstream provenance를
재빌드하고 byte-exact freshness를 검사한다. 이 명령들은 evidence를 읽거나 승인 상태를
전이하지 않는다.
