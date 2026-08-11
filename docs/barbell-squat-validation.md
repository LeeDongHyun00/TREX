# 바벨 스쿼트 AI Hub 좌표 검증 vertical slice

## 결론

AI Hub 바벨 스쿼트 type `313`~`328`의 네 조건을 서로 분리해 여러 좌표 로직으로
실험하는 재현 가능한 파이프라인을 구현했다. 그러나 현재 네 조건 모두 사용자 교정으로
출시할 수 없다. 가장 강한 신호는 `발과 무릎의 방향 일치`의 body-lateral offset
proxy였지만, selective 평가에서 일부 사람의 근거 coverage가 부족했고 공식 Validation도
개발 중 이미 관찰되었다.

이 결과는 다음 용도로만 사용한다.

- 좌표 feature와 실패 원인을 비교하는 연구 artifact
- 다음 MediaPipe-Gold 실험에서 우선 검증할 후보 선정
- 41개 운동으로 확장할 criterion registry의 첫 `CATALOG_ONLY` coverage package

사용자 `PASS/FAIL`, 자세 점수, 음성 cue, 임계값 calibration 또는 GA 근거로 사용하지
않는다. 현재 휴대폰 화면도 이 새 파이프라인에 연결하지 않았다.

## 고정 데이터와 provenance

- Training: 720 sequence, 전역 `Z` subject 42명, type당 45개
- Validation: 112 sequence, 전역 `Z` subject 7명, type당 7개, 단일 `Day07_200929_F`
- 네 조건의 16개 truth vector가 완전하며 Training/Validation subject overlap은 0이다.
- raw 2D, raw 3D, 좌표만 canonicalize한 2D/3D hash를 각각 비교했다. 각 split 내부
  duplicate와 split 간 exact overlap은 모두 0이다.
- AI Hub catalog SHA-256:
  `fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c`
- source condition, semantic ID, type별 record count와 16개 truth vector를 묶은 독립
  coverage SHA-256:
  `1f6ab0ea0981c6d1ef693ace7e72608a2e9af363b4b52f789a1749f92dae9cb5`

Kotlin registry도 같은 coverage hash를 수동 truth table에서 다시 계산해 pin과 다르면
초기화에 실패한다. catalog coverage는 실행 가능한 evaluator나 release 권한이 아니다.

## exact condition과 측정 proxy 분리

| AI Hub exact condition | 실험 target construct | claim boundary | 앱 상태 |
|---|---|---|---|
| 척추의 중립 | gross spine-chain shape | 해부학적 요추 중립·하중·진단 주장 금지 | `CATALOG_ONLY` |
| 고개 정면 | ear/shoulder line-axis mismatch | 실제 시선·경추 안전·앞/뒤 180° 구분 주장 금지 | `CATALOG_ONLY` |
| 발과 무릎의 방향 일치 | knee/toe body-lateral offset | 전체 3D 진행각·관절 하중·토크·부상 위험 주장 금지 | `CATALOG_ONLY` |
| 발바닥 지면 고정 | camera-coordinate foot motion | 실제 접촉·압력·지면반력 식별 불가 | `CATALOG_ONLY` |

`발바닥 지면 고정` label과 발 이동 proxy가 상관되어도 exact condition을 관측했다고
간주하지 않는다. 이 조건은 검증된 ground/support provider가 없으면 계속
`NOT_OBSERVABLE`이다.

## 실험 로직

### 입력과 active 계약

모든 2D frame에서 정확히 5개 view, 공통 frame ordinal, `active=Yes|No`와 view 간 active
일치를 검증한다. `active`는 정오 라벨이나 phase Gold가 아니라 동작 구간 prior로만 쓴다.
2D와 3D를 같은 active mask로 자르며 inactive gap 양쪽의 발 이동을 연결하지 않는다.

실제 데이터에서 active frame은 sequence당 4~17개였다. Training 69개와 Validation
3개는 active 구간이 둘 이상으로 끊어져 있었다. 이 차이를 무시하면 발 이동 결과가 크게
달라지므로 artifact에 active mask manifest hash와 포함·제외 수를 보존한다.

### 후보 family

- `whole_sequence_3d_robust`: active 전체의 q75 robust geometry
- `contiguous_bottom_3d`: 각각의 active run에서 knee ROM을 계산하고 20° 이상인 run 중
  ROM이 가장 큰 하나만 선택한다. 그 run 안에서 3-sample median smoothing 후 연속
  nadir window를 사용하며, 적합한 run이 없으면 bottom feature만 `UNKNOWN`이다.
- `aihub_five_view_2d_ensemble`: view별 robust 값을 sequence 내부에서 median 집계한다.
  이는 연구용 5-camera ensemble이며 휴대폰 단일 camera feature로 배포할 수 없다.
- `active_camera_coordinate_foot_motion`: inactive gap을 건너뛰지 않은 step q75와
  excursion q90을 계산한다. FPS·속도·ground contact·pressure를 주장하지 않는다.

척추의 upper/lower chain과 양측 무릎·발은 평균으로 희석하지 않고 worst-side/worst-part를
사용한다. 무릎 후보는 가상의 ground normal을 만들지 않고 shoulder-width로 정규화한
body-lateral knee/toe offset만 측정한다.

### 누출 방지와 통계

- 독립 단위는 frame/view/day가 아니라 전역 `Z` subject다.
- 후보와 threshold는 Training global-Z leave-one-subject-out 결과로만 선택한다.
- 다른 세 condition을 고정한 subject/day Hamming-1 recording cell의 matched contrast를
  사용한다. 동일 반복의 paired sample이라고 부르지 않는다.
- abstention 폭은 class separation의 임의 비율이 아니라 class별 subject-median MAD 중
  큰 값의 `1.4826×`다.
- ordinary와 selective balanced accuracy, label별 coverage, subject별 최소/p10 coverage,
  subject-cluster bootstrap 95% interval, matched-direction Wilson 95% interval을 기록한다.
- candidate signal gate는 모든 subject가 양·음 determinate evidence를 갖고 class·subject
  coverage와 uncertainty 하한까지 통과해야 한다.

공식 Validation은 한 실행 안에서는 feature/threshold 재선택에 사용하지 않지만, 도구를
개발하면서 결과가 이미 관찰되었다. 따라서 confirmatory holdout이 아니라
`CONSUMED_DEVELOPMENT_BENCHMARK`다. 이 제한은
[holdout ledger](barbell-squat-holdout-ledger.json)에 고정했다.

## 현재 전수 결과

재현 artifact는
[barbell-squat-coordinate-experiment.json](barbell-squat-coordinate-experiment.json)이다.

- report fingerprint:
  `8d8fdffe5477c79701a053fb4fb647c91082b882213a13cfd1efb7beb0d3ea6a`
- protocol artifact SHA-256:
  `0c591c8213dda06a9af63ec009afdd3ddd1a265cdb72b9e387bcdcf69acb3bbb`
- implementation 및 catalog source hash는 UTF-8 canonical-LF로 계산해 checkout 줄바꿈에
  독립적이다.
- Training/Validation 전수 사용: 720/112 sequence
- bottom phase proxy `UNKNOWN`: Training 7, Validation 0 sequence
- non-contiguous active mask: Training 69, Validation 3 sequence

아래 BA는 subject별 balanced accuracy의 macro 평균이다. selective BA는 근거가 충분한
표본만의 값이며 coverage와 반드시 함께 읽어야 한다.

| condition | Training LOSO BA | Validation BA | selective BA / coverage | Validation matched consistency | proxy 연구 상태 |
|---|---:|---:|---:|---:|---|
| 척추의 중립 | 0.6458 | 0.6964 | 0.6524 / 0.3839 | 0.7143 | `INSUFFICIENT_ROBUST_REPLICATION` |
| 고개 정면 | 0.4963 | 0.4554 | 0.5194 / 0.3661 | 0.5714 | `INSUFFICIENT_ROBUST_REPLICATION` |
| 발과 무릎의 방향 일치 | 0.8291 | 0.8482 | 0.9262 / 0.6875 | 1.0000 | `INSUFFICIENT_ROBUST_REPLICATION` |
| 발바닥 지면 고정 | 0.7746 | 0.7946 | 0.6667 / 0.3929 | 1.0000 | `INSUFFICIENT_ROBUST_REPLICATION` |

무릎–발 offset은 네 후보 중 가장 우선순위가 높다. 그러나 Validation selective
minimum-subject coverage가 0.4375로 사전 기준 0.5보다 낮고, 실제 MediaPipe domain과
미관찰 외부 test가 없다. 좋은 평균값만 보고 cue로 승격하지 않는다.

발 이동은 좌표 signal이 있어도 selective coverage가 0.3929이며, 더 근본적으로 exact
plantar contact를 식별하지 못한다. 고개 proxy는 chance 수준이고 척추 proxy도 robust
gate를 통과하지 못했다.

## 재현 명령

새 output 경로에는 다음처럼 실행한다.

```powershell
python tools/barbell_squat_validation_experiment.py `
  "data/013.피트니스자세/1.Training/라벨링데이터" `
  --validation-root "data/013.피트니스자세/2.Validation/라벨링데이터" `
  --output "build/reports/barbell-squat-reproduction.json"
```

기존 artifact는 기본적으로 덮어쓰지 않는다. 의도적으로 교체할 때만 `--overwrite`와
기존 report fingerprint를 함께 제공한다. output은 dataset root 내부일 수 없으며,
동시 writer lock, 임시 파일 `fsync`, atomic replace, fingerprint 검증을 사용한다.

## 서비스 승격에 남은 필수 단계

1. 같은 CameraX crop/rotation/mirror와 MediaPipe model로 좌표만 수집하고 전문가 또는
   motion-capture Gold와 동기화한다. RGB 저장은 필수가 아니다.
2. subject × device tier × qualified view를 묶은 미관찰 외부 test를 새로 확보한다.
3. person lock, view/crop/light/occlusion/OOD qualifier를 먼저 통과한 frame만 평가한다.
4. criterion별 error interval과 calibration artifact를 만들고 harmful directional cue를
   별도로 blind review한다.
5. 무릎 proxy는 side provenance와 `PASS/FAIL/UNKNOWN/NOT_APPLICABLE`를 보존한 signed
   spec으로 shadow 실행한다.
6. 발 접촉은 검증된 ground/support provider가 생기기 전까지 비지원으로 유지한다.
7. signed allowlist, criterion kill switch, rollback, privacy telemetry와 저사양 기기
   thermal/latency budget을 통과한 criterion만 사용자에게 노출한다.

출시 단위는 운동 전체가 아니라
`exercise × criterion × view × observer/model × device tier`다. 일부 criterion만
검증되면 UI도 “스쿼트 자세교정 지원”이 아니라 검증된 항목 수와 판정 불가 이유를
표시해야 한다.
