# AI Hub criterion 정책 해석 방법

## 목적과 권한 경계

이 문서는 AI Hub fitness posture dataset 231의 exact condition을 TREX의 좌표 기반 자세교정 연구 대상으로 해석하는 공학 정책을 정의한다. 기준 source artifact는 `docs/aihub-criterion-coverage.json`이며 SHA-256은 `9240aa2c9a429cce8f4c47314f7797bea6ebf39b276d3563f5d420a9d3a34eda`다.

이 정책은 전문가 Gold, calibration 또는 사용자 cue 승인서가 아니다. `DIRECT`는 좌표식이 후보 construct를 직접 표현할 수 있다는 뜻일 뿐 정확도나 안전성을 보증하지 않는다. 모든 binding은 별도 release authorization이 생기기 전까지 `CATALOG_ONLY`이고 사용자 `PASS/FAIL`, 점수 또는 교정 문구를 만들 수 없다.

## 전수 범위와 보수적 분류

41개 운동의 exact condition 97개가 167개 운동-condition binding으로 연결된다. source condition ID는 normalized exact text의 전체 SHA-256이며, 띄어쓰기·조사·오탈자가 비슷해도 자동 병합하지 않는다.

| 정책 상태 | Binding | 의미 |
|---|---:|---|
| `DIRECT` | 61 | qualified view와 필요한 capability에서 landmark geometry/temporal 식으로 직접 표현 가능한 후보 |
| `PROXY_UNVALIDATED` | 52 | 희소 pose landmark가 실제 해부학 construct를 대신하는 proxy이며 blind Gold 검증 전 판정 금지 |
| 추가 capability 필요 | 19 | object, hand detail, gravity/depth 등 pose 이외 provider가 attestation되어야 측정 가능 |
| `NOT_OBSERVABLE` | 16 | 지원하는 camera pose만으로 접촉·압력·근긴장/힘을 식별할 수 없음 |
| `SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION` | 19 | source 문구의 polarity, 기준점, threshold 또는 운동 의미가 불명확하여 해석 자체를 보류 |

`추가 capability 필요`는 독립 observability enum이 아니다. 필요한 provider가 실제로 존재할 때는 `DIRECT`, 없으면 runtime에서 `UNKNOWN`이어야 한다. provider를 앱이 스스로 주장하는 Boolean이나 문자열로 대체해서는 안 된다.

## Semantic family

97개 exact condition은 다음 12개 family로 분류한다. family는 feature-template 재사용 단위이며 source truth를 합치는 ID가 아니다.

- `AXIAL_SPINE_TRUNK`
- `HEAD_GAZE_CERVICAL`
- `LOWER_LIMB_GEOMETRY`
- `UPPER_LIMB_GEOMETRY`
- `WRIST_HAND`
- `SCAPULA_SHOULDER`
- `MULTI_SEGMENT_ALIGNMENT`
- `RANGE_DISTANCE_POSITION`
- `TEMPORAL_STABILITY_COORDINATION`
- `EXTERNAL_OBJECT_RELATION`
- `SUPPORT_CONTACT`
- `INTERNAL_TENSION`

동일 exact condition이 여러 운동에 쓰이면 semantic identity는 공유할 수 있지만 phase, side, view와 capability는 각 binding에서 별도로 결정한다. 서로 다른 exact condition을 하나의 semantic alias로 합치는 작업은 별도의 adjudication SHA 없이는 금지한다.

## Phase role

운동마다 위/아래 방향이 달라 `DESCENT`, `BOTTOM`, `ASCENT`, `TOP`을 41개 공통 정책에 직접 쓰지 않는다. 다음 generic role을 운동별 signed phase graph에 나중에 연결한다.

- `FULL_CYCLE`: active movement 전체에서 유지해야 하는 조건
- `LENGTHENED_ENDPOINT`: 이완 endpoint
- `CONCENTRIC`: 수축 이동 구간
- `CONTRACTED_ENDPOINT`: 수축 endpoint
- `STATIC_HOLD`: 플랭크 같은 정적 자세 구간
- `COMPOUND_TRANSITION`: 순서가 의미를 갖는 복합 전환

전수 engineering review 결과는 `FULL_CYCLE` 99, `LENGTHENED_ENDPOINT` 22, `CONTRACTED_ENDPOINT` 20, `STATIC_HOLD` 3, `COMPOUND_TRANSITION` 3, `CONCENTRIC` 1, source ambiguous 19다. AI Hub frame index나 `active` 값을 phase Gold로 간주하지 않는다.

## Side role

해부학적 좌우와 화면 좌우를 분리하고 다음 role을 사용한다.

- `MIDLINE`
- `GLOBAL_BODY`
- `BILATERAL_COUPLED`
- `BILATERAL_INDEPENDENT`
- `ACTIVE_LIMB`
- `LEAD_LIMB`
- `TRAIL_LIMB`
- `ALTERNATING_PAIR`
- `CONTRALATERAL_PAIR`

`BILATERAL_INDEPENDENT`는 좌우 평균을 금지한다. 양쪽을 각각 평가하고 worst-side와 각 side의 `UNKNOWN`을 보존해야 한다. `ACTIVE_LIMB`, `LEAD_LIMB`, `TRAIL_LIMB`, `ALTERNATING_PAIR`는 signed role-resolver contract가 없으면 runtime 적용 불가다.

## View와 capability

정책의 view는 release 허용 view가 아니라 research candidate다. observer가 crop, mirror, camera pose, full-body coverage, occlusion을 검증해 versioned view contract를 발급해야 한다.

- `front-full-body.v1`
- `front-oblique-full-body.v1`
- `lateral-full-body.v1`
- `rear-full-body.v1`
- `floor-lateral-full-body.v1`
- `floor-oblique-full-body.v1`

camera criterion의 공통 capability는 pose 좌표, temporal timestamps, primary-person lock, qualified view다. 조건에 따라 object track, hand detail, face/gaze orientation, gravity frame, reliable depth, support surface/contact 또는 anatomical segment provider가 추가된다. 알려지지 않았거나 attestation되지 않은 capability는 `PASS`가 아니라 `UNKNOWN`이다.

## 판정 금지 항목

Camera pose만으로 다음 16 binding을 판정하지 않는다.

- `발바닥 지면 고정` 2개
- `허리 지면 고정` 4개
- `몸통 벤치 고정` 1개
- 근육 `긴장 유지` 계열 9개

또한 19개 source-ambiguous binding은 blind expert adjudication 전 evaluator를 만들지 않는다. 대표 문제는 “적당한/충분한/너무”의 기준 누락, “여부”의 true polarity 불명, 서로 다른 두 오류를 한 bit로 결합한 문구, side-lunge의 뒤다리 90도처럼 운동 의미와 충돌할 가능성이 있는 문구다.

Source type `062`, `101`, `109`의 153 record는 condition truth와 description 충돌 때문에 calibration/test에서 격리한다. 동일 truth vector를 공유하는 55개 type group은 multiclass 정답으로 사용하지 않는다.

## Repository pin과 후속 release

Policy compiler는 source coverage와 167개 binding exact-set, binding별 결정 SHA, global policy SHA를 계산한다. `--print-approval-draft`는 검토할 pin 후보를 표준 출력으로 보여줄 뿐 파일을 저장하거나 승인하지 않는다. 별도의 approval artifact는 repository review로 literal SHA를 고정하지만, 서명자·공개키·CODEOWNERS attestation이 없는 현재 단계에서는 **인증된 독립 승인**이 아니라 동시 수정과 drift를 드러내는 비실행형 repository pin이다. 이 pin도 `CATALOG_ONLY` inventory 확인일 뿐 runtime release 승인이 아니다.

SHA 이름은 다음 canonicalization 계약을 따른다.

| 값 | canonicalization |
|---|---|
| source coverage artifact | `artifactSha256` 필드를 제외한 UTF-8·key-sorted·compact JSON의 SHA-256 |
| binding/policy/registry | field name, UTF-8 byte length, value와 LF를 순서대로 연결한 length-prefixed SHA-256 |
| repository evidence | UTF-8 text의 CRLF·CR을 LF로 바꾼 canonical text SHA-256 |
| approval artifact | strict JSON object의 UTF-8·key-sorted·compact JSON SHA-256 |

JSON duplicate key, `NaN`/무한대, Boolean을 정수 schemaVersion으로 쓰는 입력, Unicode 비-NFC 문자열은 거부한다. 현재 evidence resolver는 project root 아래 `docs/`의 실제 파일만 검증하며 외부 artifact ID는 별도 resolver가 생기기 전 허용하지 않는다.

향후 user-facing release authorization은 최소한 다음을 하나의 signed package로 묶어야 한다.

- source coverage SHA와 exact binding ID
- binding policy/global policy SHA
- observer/model/preprocessing/landmark schema SHA
- phase graph와 role-resolver SHA
- feature AST/evaluator/calibration artifact SHA
- runtime domain, qualified view, device capability profile SHA
- criterion graph와 directional cue mapping/localized cue-content SHA
- subject-held-out MediaPipe bridge 및 locked risk-coverage report SHA

이 package가 없거나 하나라도 불일치하면 결과는 `UNKNOWN`이며 cue를 만들지 않는다.
