# AI Hub 운동 카탈로그

TREX의 운동 식별자는 AI Hub 데이터셋 231 Training 라벨링데이터의 실제 2D JSON에서 생성한다. 디렉터리명, 랜딩 페이지 요약, 3D JSON 파일명은 운동 이름 원천으로 사용하지 않는다.

## 확정 manifest

| 항목 | 수량 |
|---|---:|
| 전체 JSON | 69,898 |
| 2D JSON record | 34,468 |
| non-empty `frames` record | 34,440 |
| empty `frames: []` record | 28 (17개 운동) |
| 3D JSON (`*-3d.json`) | 35,430 |
| 2D↔3D 동일 basename pair | 33,349 |
| 2D only / 3D only | 1,119 / 2,081 |
| canonical 운동 | 41 |
| type code | 816 |

운동명은 `type_info.exercise`, code는 루트 `type`, 분류는 `type_info.type`, 조건 truth-vector는 `type_info.conditions`를 사용한다. 원본 type code는 `001`~`816`의 3자리 문자열이며 Kotlin에서도 `String`으로 보존한다. 텍스트에는 Unicode NFC와 공백 collapse/trim만 적용하며 별칭 병합은 하지 않는다. 실제 JSON의 `스텝 포워드 다이나믹 런지`, `스텝 백워드 다이나믹 런지`, `Y - Exercise`를 다른 이름으로 축약하지 않는다.

`recordCount`는 학습 가능한 sequence 수가 아니라 라벨 JSON record 수다. generator는 파일 head에서 `frames` 상태를 별도로 감사하여 manifest와 운동/type별 `emptyFrameRecordCount`, `missingFrameRecordCount`, `nonEmptyFrameRecordCount`를 보존한다. 빈 frame record는 catalog identity 오류로 실패시키지 않는다.

전체 운동별 record 수와 각 type code의 분류·조건·record 수는 [aihub-exercise-catalog.json](./aihub-exercise-catalog.json)에 보존한다. 현재 snapshot hash는 `fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c`다.

## 재생성 및 검증

저장소 루트에서 실행한다.

```powershell
python tools/generate_aihub_exercise_catalog.py `
  "data\013.피트니스자세\1.Training\라벨링데이터"

python tools/generate_aihub_exercise_catalog.py `
  "data\013.피트니스자세\1.Training\라벨링데이터" `
  --check

python -m unittest discover -s tools -p "test_*.py"
```

generator는 2D JSON의 큰 `frames`를 로드하지 않고 파일 끝의 루트 `type`과 `type_info`만 UTF-8로 파싱한다. 다음 경우 즉시 실패한다.

- `type_info`, exercise, type, key, conditions 누락 또는 빈 문자열
- boolean이 아닌 condition value 또는 정규화 후 중복 condition
- 루트 `type`과 `type_info.key` 불일치
- 하나의 type code가 여러 exercise 또는 서로 다른 조건 metadata에 연결됨
- 정규화 후 예상하지 않은 운동 추가/삭제 또는 stable ID drift
- 생성된 JSON/Kotlin snapshot이 `--check`에서 현재 manifest와 다름

원본 exercise에는 실제 trailing-space가 있다. 특히 바벨 컬 1,437개 record의 raw exercise spelling은 정규화 전 문자열을 artifact의 `rawNames`에 함께 보존한다. condition 이름의 공백도 canonical 비교를 위해 정규화하지만 raw condition spelling은 artifact 크기와 중복을 줄이기 위해 의도적으로 보존하지 않는다. 필요하면 원본 2D JSON을 근거로 다시 감사해야 한다.

### 조건 vector 사용 경고

`type_info.conditions`의 Boolean truth-vector를 type code와 1:1인 정답 class로 가정하면 안 된다. 15개 운동에서 서로 다른 type code가 같은 Boolean vector를 공유하며, 원본 description과 all-true vector가 모순되는 사례도 있다. 확인된 예는 버피 테스트 type `062`, 스텝 포워드 다이나믹 런지 type `101`·`109`다.

이번 runtime catalog에는 description을 넣지 않는다. 향후 evaluator 학습이나 조건 classifier 생성 시에는 conditions만 사용하지 말고 type code, 원본 description, pose를 함께 감사한 뒤 충돌 record를 격리해야 한다.

생성물은 다음 두 개다.

- `docs/aihub-exercise-catalog.json`: 전체 type/condition 감사 artifact이며 APK에 포함되지 않는다.
- `app/src/main/java/com/example/trex_kotlin/catalog/AiHubExerciseCatalog.kt`: 앱이 쓰는 작은 정적 catalog다.

## 앱 연결과 평가 지원

사용자 운동 카드, 오늘 계획, 대체 운동, 운동 추가 시트는 모두 `AiHubExercise`에서 이름과 ID를 얻는다. 사용자가 임의 운동명으로 catalog identity를 바꿀 수 없다.

사용자 운동 목록은 `PostureCorrectionRuntimeFacade`가 계산한 운동별 lifecycle과 `released/catalog criterion` 수만 제품 지원 근거로 사용한다. 현재 41개 운동·167개 binding은 모두 `CATALOG_ONLY`, released criterion은 0개이므로 모든 체크 UI가 “자세 기준 검증 중”으로 비활성화되고 타이머 세션으로 진입한다.

과거 symmetric squat / alternating lunge 수동 규칙은 app main source에서 제거하고 단위 테스트 fixture로만 이동했다. 향후 데이터 기반 evaluator가 완성되어도 구현 클래스나 운동 ID만으로 활성화하지 않는다. exact binding policy, Gold calibration, observation/view/person-lock contract, exercise spec, cue content를 묶은 별도 release authorization이 검증된 항목만 facade에 추가한다.

루트 `data/`는 Android source set 밖이며 `.gitignore`에서 제외된다. 앱에는 원본 JSON/JPG/3D corpus나 563KB 감사 artifact가 패키징되지 않는다.
