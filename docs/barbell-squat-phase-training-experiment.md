# 바벨 스쿼트 측면 phase Training-only 실험

## 결론

AI Hub Training 좌표만 사용해 측면용 bilateral knee-flexion causal decoder 후보를
global-`Z` subject LOSO로 정량화했다. 결과는 `REJECTED`이며
`NO_RUNTIME_DECODER_PARAMETERS`다. 실행 phase provider, shadow, 사용자 release,
`PASS/FAIL/UNKNOWN`, 횟수, 점수 및 cue 권한은 모두 0이다.

공식 Validation은 이 실험에서 읽거나 재사용하지 않았다. 도구 CLI에는 Validation 경로나
입력 option 자체가 없다.

## 데이터 및 supervision 경계

- AI Hub Training 바벨 스쿼트 type `313`–`328`: 720 sequence, 42 global subject
- active frame 10,452개, inactive frame 1,037개, contiguous active run 854개
- inactive gap이 있는 sequence 69개; gap 양쪽을 연결하지 않음
- `active=Yes|No`는 movement-window prior이며 phase 또는 boundary Gold가 아님
- 전문가·MoCap·수동 phase Gold 없음
- 신뢰 가능한 FPS와 frame interval 없음
- AI Hub triangulated 3D는 제품 CameraX–MediaPipe WORLD 출력과 paired Gold가 아님

평가 범위는 `trex.research-phase-signal.barbell-squat.bilateral-knee-flexion-median.v1`
하나뿐이며, AI Hub triangulated 3D의 lateral 후보 역할로만 해석한다. 이것은 AI Hub 카메라
view qualification도 MediaPipe WORLD 검증도 아니다. front pelvis-ankle 후보는 이번 실험에서
평가하지 않았다.

retrospective reference는 centered 3-sample median, full-run ROM, peak 주변을 이용한
`MORPHOLOGY_SURROGATE_NOT_PHASE_GOLD`다. 동일 knee signal에서 만들어진 순환적 비교 대상이므로
accuracy, calibration 또는 제품 threshold 근거로 해석하면 안 된다. 720개 중 159개
(22.08%), 42명 중 40명만 완전한 4-state surrogate topology를 만들 수 있었다.

## Training subject-group 결과

각 outer fold는 global subject 한 명 전체를 분리한다. 후보 384개는 나머지 Training
subject만으로 선택하며 held-out subject와 overlap은 0이다.

| 항목 | 결과 |
|---|---:|
| outer subject-macro surrogate recall | 0.2595 |
| determinate prediction coverage | 0.5795 |
| minimum subject coverage | 0.0000 |
| selective surrogate agreement | 0.4681 |
| ordered topology completion coverage | 0.4403 |
| causal prefix invariance | 1.0000 (2,492/2,492) |

42개 fold에서 같은 configuration이 선택되어 threshold IQR은 0이었지만, 낮은 surrogate
eligibility·agreement·coverage를 보상하지 못한다. 아래 값은
`RESEARCH_CANDIDATE_DIAGNOSTICS_ONLY_NOT_RUNTIME_PARAMETERS`다.

- initial baseline: 2 sampled frames
- trailing median: 3 sampled frames
- baseline stability: 12°
- READY band: 12°
- descent entry displacement: 10°
- motion evidence: 3°/sample
- bottom minimum displacement: 40°
- reversal evidence: 3°/sample
- diagnostic configuration: `cfg-5bd9baaa84ae8170`

`°/sample`은 시간 단위가 아니며 dwell 또는 실제 지연으로 변환할 수 없다. offline dominant-run
선택도 미래를 사용하므로 runtime segmentation으로 사용할 수 없다.

## 재현성과 provenance

Immutable report:
[barbell-squat-phase-training-experiment.json](barbell-squat-phase-training-experiment.json)

- report fingerprint: `6f9c2e5215339e4248055a6a001fa947a75c1781c4119beed74c22d5ca65263f`
- protocol SHA-256: `286d16329bc3d68e8d2fc48b54d0b9a229f500fed67c23c2f4de3a40b47a39ce`
- evaluator canonical-LF SHA-256: `820055892273a8888fb15e71cce5c0e9b5169b8f40883ae0ed8e9011aa84a360`
- shared coordinate parser canonical-LF SHA-256: `01a3d10e87d15a56439b609f1ee1caf086bcd82cfaaef7b26263f4cc5b45355c`
- shared validation helper canonical-LF SHA-256: `ff8467a8ae942fc0c96f205cc86f01ff6a1ff88658dd002579569d660de9895d`
- Training input manifest SHA-256: `5e8a28bfd2f4c55d8d2eb9b15968a813d1ae6259ec0a1d406aa98f098e511814`
- experiment identity SHA-256: `47c5240ef6a470f30c942c373b22891fb6710966c6a25d8dcddba44c3fe0ff4d`

텍스트 source hash는 UTF-8의 CRLF/CR을 LF로 정규화해 OS checkout에 독립적이다. report
fingerprint는 canonical JSON content를 검증한다. `--check`는 newline을 정규화한 뒤 committed
canonical JSON layout과 전수 재계산 결과가 같은지 읽기 전용으로 확인한다.

```powershell
python tools/barbell_squat_phase_training_experiment.py `
  "data/013.피트니스자세/1.Training/라벨링데이터" `
  --check "docs/barbell-squat-phase-training-experiment.json"
```

새 output은 dataset root 내부에 쓸 수 없다. 기존 artifact는 기본적으로 덮어쓰지 않으며,
의도적 교체에는 `--overwrite`와 기존 fingerprint가 함께 필요하다. writer는 동시 lock,
temporary-file `fsync`, atomic replace를 사용한다.

## 다음 gate

다음 구현은 이 diagnostic threshold를 실행 코드로 옮기는 것이 아니다. production과 같은
crop·rotation·mirror·MediaPipe 모델로 untouched subject × device × qualified lateral view를
수집하고, 전문가 또는 MoCap이 독립적으로 READY/DESCENDING/BOTTOM/ASCENDING boundary와
abstention 가능 구간을 부여해야 한다. 그 Gold에서 causal decoder, timestamp gap/reset,
coverage 및 boundary error를 다시 고정하기 전까지 phase token과 completed cycle issuer는 0으로
유지한다.
