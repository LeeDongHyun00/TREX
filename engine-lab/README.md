# TREX 실험실

26종 운동 엔진을 기존 TREX에 이식하기 전에 시험하기 위한 별도 Android 앱입니다.
로그인·루틴·식단·운동 자동 전환 없이 운동 선택 → 카메라 → 종료 → 실제 횟수/메모 → 로그 보내기만 제공합니다.

## 실행 범위와 독립성

- 설치 패키지: `com.trex.engine.lab`, 런처 이름: **TREX 실험실**.
- 별도 Gradle 루트: 이 폴더에서 빌드합니다. 상위 TREX `:app` 프로젝트를 참조하지 않습니다.
- `:engine`: Android 의존성 없는 Kotlin JVM 모듈. 입력 관절/피처 → 관측 결과.
- `:app`: 전면 CameraX + MediaPipe FULL 추론, 화면, 저장, Android ZIP 공유만 담당합니다.
- 이 대화의 `ReturnRepTracker`, `ExerciseRepTracker`, `ExerciseRepProfiles`, `RecentRepView`를 분리했습니다.
  기존 `RepCounter` 래퍼는 작은 `ReturnChannel`로 교체했습니다. 기존 `LiveCoach`, `PostureRuleSet`, 규칙 JSON은 포함하지 않습니다.
- 관절 기하·촬영 방향 계산과 MediaPipe 모델은 기존 구현/자산을 재사용했습니다. 완전히 다른 추론 모델을 새로 학습한 것은 아닙니다.
  복사 출처는 `source-origins.json`입니다. 바닥 기하는 결측 시 접지 이력을 초기화하고 최근 600개까지만 보존하도록 변경했습니다.

| 기능 | 현재 상태 |
|---|---|
| 26종 선택 | 구현 |
| 25종 왕복 카운트 | 구현, 실제 사람 정확도 미확정 |
| 플랭크 | 몸이 보이는 유지 시간, 정자세 유지 시간 아님 |
| 동시/각 측/한쪽 모드 | 해당 프로필에서 지원, 사용자 기준 좌우 |
| 종목별 자세 기하 값 | 구현, 측정 불가 값은 비워 둠 |
| 147개 자세 항목 | 연구 설계, 자동 판정·교정 미구현 |
| 실시간 자세 점수·교정 음성 | 제공하지 않음 |

따라서 이 앱을 ‘26종의 모든 잘못된 자세를 완성해서 판정하는 엔진’으로 설명하면 안 됩니다.
이전 평가 앱은 새 횟수기와 기존 코칭을 혼합했습니다. 이 앱에서는 구현된 범위와 미구현 항목을 화면에서 확인할 수 있습니다.

## 사용

1. **TREX 실험실**을 열고 운동과 횟수 방식을 선택합니다.
2. 휴대폰을 세워 고정합니다. 서서 하는 운동은 정면, 바닥 운동은 측면에서 전신을 보여 주세요.
   바닥 운동은 측면 촬영 확인을 선택합니다. 현재 좌표의 위쪽은 화면 세로축이며 IMU 보정을 사용하지 않습니다.
3. 실험 시작 후 준비 위치에서 잠시 멈춘 다음 운동합니다. 정면 추정은 최소 8프레임이 필요합니다.
4. 실험 종료를 누릅니다. 직접 센 총횟수/좌우 횟수와 의도적으로 바꾼 동작, 누락/오탐을 메모합니다.
   실제로 세지 못했다면 입력하지 않습니다. 목표 횟수나 앱 숫자는 정답으로 복사하지 않습니다.
5. **로그 보내기**로 ZIP을 공유하거나 USB로 회수합니다. 전송 대상은 사용자가 선택합니다.

전면 미리보기는 거울처럼 표시하지만, 추론 원본은 좌우 반전하지 않습니다. 앱의 왼/오른 표시는 본인 몸 기준입니다.
런지의 앞/지지 다리는 영상으로 자동 확정하지 않습니다. 전체 방식은 측 미확정 합계이고, 한쪽 방식은 사용자가 선택한 측입니다.
앱이 배경으로 가면 세션을 종료·저장하며, 다시 시작할 때 새로운 세션이 됩니다.

## 로그와 재검증

내부 저장 위치: `files/sessions/<시각>-<ID>.jsonl`.
사진/영상 대신 원본 33점의 정규화 좌표·월드 좌표·가시성, 피처, 시각, 관측 실패 사유, 반복 이벤트와 측별 수를 저장합니다.
헤더에는 엔진 소스 SHA-256, 모델 SHA-256, 앱 버전, 카운터 정책, 종목/모드, 촬영 가정이 포함됩니다.
수동 정답은 `human_annotation`으로 별도 추가합니다. 미입력은 `null`이며 0회와 다릅니다.
`session_end` 없는 로그는 비정상 중단으로 간주합니다. 모델 초기화 시간은 첫 프레임 `infer_ms`에 포함됩니다.

```powershell
python tools/inspect_logs.py 받은로그.zip --output report.json
python tools/inspect_logs.py 회수폴더 --pull --adb C:/Users/hp276/AppData/Local/Android/Sdk/platform-tools/adb.exe --serial R3CMB04LLNZ --output report.json
```

입력 로그의 모델 좌표를 다시 피처로 변환하고 같은 엔진을 실행하는 검사는 `SavedSessionsReplayTest`입니다.
동일 빌드 로그의 매 프레임 피처·횟수·측·보류 사유·유지 시간 일치를 검사합니다.
다른 엔진 SHA 로그는 동일성 검사에서 거부합니다. 임계값 변경 전후 비교는 별도 실험으로 진행해야 합니다.

## 빌드와 검사

JDK 17 이상, Android SDK 36, Gradle wrapper 8.13을 사용합니다.
로컬 `local.properties`에 SDK 경로를 지정합니다.

```powershell
.\gradlew.bat :engine:test :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push 로컬검증이미지.jpg /sdcard/Android/data/com.trex.engine.lab/files/probe.jpg
adb shell am instrument -w -e class com.trex.engine.lab.LabDeviceTest com.trex.engine.lab.test/androidx.test.runner.AndroidJUnitRunner
# 앱에서 세션을 저장한 후
adb shell am instrument -w -e class com.trex.engine.lab.SavedSessionsReplayTest com.trex.engine.lab.test/androidx.test.runner.AndroidJUnitRunner
```

기기 검사는 기존 엔진 클래스 부재, 실제 이미지의 MediaPipe 추론, 합성 입력 로그 왕복, 실제 FileProvider URI 읽기를 검사합니다.
합성 테스트 세션은 사용자 실험 목록에 넣지 않습니다. UI·로그 검증을 실제 사람의 정확도 검증으로 간주하지 않습니다.
