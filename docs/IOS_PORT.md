# iPhone 포팅 — 2026-10-21 (`ios-redesign`, main `ca7f1fa` 병합)

## 상태

`ios-redesign` 은 `origin/main`(`ca7f1fa`, 2026-10-14 — §99~§106 바닥 계열·시선·말하기 대기열·교정 화살표·서버 동기화·앱 아이콘)을 병합한 위에 서 있다. 이전 iOS 작업(§107, 2026-10-07)은 `redesign` `5213e3c4`(preview.6) 기준이라 그 사이 Android 가 얻은 바닥 엔진(플랭크 유지 시계·크런치/레그 레이즈 판별)·Face Landmarker 시선·`SpeechQueue` 발화 정책·`StandingFraming`·관절 게이트가 iOS 에 없었다. 이번 작업(§107f)이 그 전부를 **Android 정본을 그대로 생성해** iOS 에 올린다.

검증한 것: 공통 엔진 JVM **504/504**(정본 테스트 + iOS 경계 18건, Linux), 정본 동기화 `--check`·자산 추출 `--check`·YOLO 호환 모델 `--check`, Swift 31파일 tree-sitter 구문 검사, XcodeGen 자산 경로·plist. **Xcode 컴파일·시뮬레이터 XCTest·IPA 는 이 세션에서 돌리지 못했다**(Linux). `ios-redesign` 푸시가 macOS Actions(`iOS 검증과 미서명 IPA`)를 띄우므로 그 결과가 Swift 타입 검사·SDK 링크·번들 검사의 정본이다. **서명·실제 iPhone 실행·운동 정확도는 미검증**이다.

## iOS 포팅 기준 (이 작업에서 세운 것 — 지켜야 한다)

Android 에서 iOS 로 옮길 때 무엇을 옮기고 무엇을 옮기지 않는지의 선이다. CLAUDE.md 의 판단 원칙은 플랫폼과 무관하게 그대로다.

1. **판정은 한 곳에만 있다.** 임계값·뷰 게이팅·카운터·반복 검사·판별 기각·플랭크 시계·시선 분류·발화 선택 정책은 Android Kotlin 정본(`app/src/main/java/.../posture/`)이고, `tools/sync_ios_core.py` 가 플랫폼 I/O(Android import·`@Synchronized`·`String.format`·`org.json`)만 바꿔 `posture-core` 공통 소스를 **생성**한다. 생성 소스(`// tools/sync_ios_core.py 생성본` 머리말)는 손으로 고치지 않는다. 바꿀 것은 정본을 바꾸고 다시 동기화한다(`--check` 가 CI·Xcode pre-build 에서 어긋남을 잡는다). 같은 수식이 두 플랫폼에 두 벌 있으면 안 된다 — 얼굴 요(`faceYawDeg`)조차 `PostureAnalyzer.kt` 에서 잘라 낸 슬라이스(`FaceYaw.kt`)다.
2. **Swift 는 센서·카메라·TTS 엔진·화면이다.** `IosEngine` 은 Android `PostureLive` 분석 루프·`PostureFloorLive`·`CapturePreparationUi`·`SpeechCoach` 의 **배선을 같은 순서로 옮긴 것**이다(준비 프레임 → 접촉 → 판정 칸 → 바닥 시계/시선 → 카운터 → 판별 기각 → 반복 검사 → 발화 → 창 규칙 → 비교). 화면 문구·간격 상수도 Android 와 같은 값이다. Swift 가 "이 회를 셀까", "이 말을 할까" 를 정하지 않는다.
3. **발화는 엔진 대기열이 고른다(§101).** Swift `SpeechService` 는 AVSpeechSynthesizer 만 들고, 엔진 `IosSpeech`(공통 `SpeechQueue`)가 고른 제출을 **한 번에 하나만** 재생하며 시작·종결을 엔진에 돌려준다. 들림 장부(지적이 재생을 시작한 뒤에만 "교정됐어요"), 세트 끝 꼬리(마지막 교정 문장을 자르지 않고 ≤ 10 s), 같은 쪽 카운트의 최신만, 번갈아 내기가 그래서 Android 와 같다. 음소거·한국어 음성 없음(→ 톤)도 엔진이 안다.
4. **격자는 같다(§96).** 85 ms 추론은 뼈대 게이트·순간 접촉(§98a)에만, 준비·판정·카운터·로그는 300 ms 칸의 첫 프레임이다. 얼굴 모델도 300 ms 에 한 번, 결과는 450 ms 안의 프레임에 얹는다(Android `FACE_INTERVAL_MS`·`FACE_STALE_MS`). 열 상태가 나쁘면 추론 간격을 300 ms 로 줄이므로 순간 접촉을 놓칠 수 있다.
5. **못 하는 것은 못 한다고 밝힌다.** 사람이 안 잡힌 프레임은 로그·집계에 넣지 않는다(Android 와 같다 — 종전 iOS 는 빈 프레임을 로그에 넣었다). 얼굴 모델이 실패하면 시선은 '측정 중' 이지 '정면' 이 아니다. 시간 목표(플랭크)는 카메라가 확인한 시간이 있을 때만 그것을 쓰고 아니면 시계이며 HUD 가 어느 쪽인지 보인다. 점수·"깨끗" 은 판정이 0건이면 없다.
6. **세트 로그는 iOS 형식(schema 2)이고 Android `SetLogJson` 재생 형식이 아니다.** 반복 시각·기각 사유·플랭크 구간·바닥 회의 시선·말한 시선 문장을 머리줄에, 프레임마다 피처·xy·world·가시성·중력을 남긴다. 연구 도구에 그대로 넣지 않는다 — 폰 로그를 확보한 뒤 변환기를 따로 둔다.
7. **자산은 Android 정본 바이트다.** 규칙 JSON·정상 표본 TSV·자세 모델·얼굴 모델은 `app/src/main/assets/posture/` 를 번들에 그대로 복사하고 `ASSET_BASELINE.json`(5개) 의 SHA 로 앱 시작·CI 에서 대조한다. 카탈로그 26·가이드 10·영양 350 은 `tools/export_ios_resources.py` 가 Kotlin 정본에서 뽑는다. 음식 YOLO 만 TFLite C API 호환을 위해 inline 가중치 사본(§107c~e)이다.
8. **Android 는 손대지 않는다.** 이 브랜치의 Android 코드·자산은 main 과 같다(병합 외 변경 0). iOS 를 위해 모집단 파라미터·규칙 JSON 을 바꾸지 않는다(원칙 #4).

## 구현 범위 (§107f 에서 더해진 것)

| 영역 | iOS 구현 |
|---|---|
| 공통 엔진 | 정본 **48파일**(§107 의 38 + `FloorChain`·`FloorCycle`·`FloorGaze`·`FloorLive`·`PlankGeometry`·`PlankHold`·`PostureTrial`·`PreparationVoice`·`SpeechQueue`·`StandingFraming`) + 슬라이스 `FaceYaw`·`RepEngineLog`·`PortableOrientation` + 앱 패키지의 관절 게이트 `SkeletonGate`. 정본 테스트 46파일이 JVM 에서 같이 돈다(`FloorWiringTest` 는 Android `SetLog` 직렬화에 묶여 제외) |
| 바닥 계열 | 플랭크 유지 시계(시작·멈춤·재개·폴백·10 s 읽기·5 s 카운트)·멈춤 사유 음성과 화살표·정렬 검사의 HOLD 칸 게이트, 크런치·레그 레이즈의 판별 기각 사유·신호 결측 안내·누운 기준 시드(준비 전용 추출기), 범위 문장(화면) |
| 시선(§101d) | Swift `FaceRunner`(MediaPipe Face Landmarker, IMAGE, 자세 행렬)가 엔진 `IosFace.cropSpec`(Android `faceCrop` 기하: 코·귀 중심, 목 방향 회전, 머리 ×3 정사각 256 px)대로 Core Image 로 잘라 돌리고, 엔진이 `face_found`·`face_yaw`(정본 `faceYawDeg`)로 바닥 피처에 얹는다. 플랭크 시선·크런치/레그 레이즈 시선 음성·'시선' 칩·교정됨 문장이 Android 와 같다. 서서 하는 종목은 얼굴 모델을 돌리지 않는다 |
| 발화 | `IosSpeech` + Swift `SpeechService`(엔진 구동)·`ToneService`(ack/nack/beep/beep2 사인파). 준비 안내는 `PreparationVoicePlan`(핵심 조건 → 자리 → 세부, 카운트는 설명 중이면 톤). 목표 도달 → `beginSetEnd` → `AdvanceHoldPolicy`(조용 300 ms, 상한 2 s) → 꼬리 넘김 → 결과 음성은 꼬리 뒤 |
| 서서 하는 종목 | `StandingFraming`(세트 중 가로 잘림 안내 15 s·3회), 판별 기각 `RejectionCues`·`RejectCueGate`, 가동 범위 미달 사유·교정됨, 쪽별 카운트 음성(같은 쪽 최신만), 돌아섬 안내, 처음부터 알림, 유지 자세·얕은 걸음 사건 |
| 화면 | 관절 게이트(믿을 수 없는 다리 관절은 점선·마지막 자리), ship 붉은색·beta 노란색, 교정 화살표(위/아래/안쪽/바깥쪽/목표/고리, 1.8 s 맥동, 바닥은 참고색), 플랭크 HUD(카메라 시간/목표, 멈춤 상태 줄, 시계 폴백 표시), 세지 않은 동작·시선 칩, 참고 줄, 촬영 커버리지 줄, 세는 조건 카드 |
| 로그 | `posture_logs/<record UUID>.jsonl` schema 2 |

iOS 에 **없는 것**: Android 의 세션 중 센서 방향 추종(§101e — iPhone 앱은 세로 고정; 바닥 종목을 가로로 두고 싶으면 폰을 눕혀 세로 화면 그대로 쓴다), 남은 시간 다리 `HoldBridge`(iOS 는 엔진 HUD 의 `camera` 플래그로 같은 뜻을 전한다), 무대 배율 잠금(`StageFit`), 유령 다리 표시, 검증 모드(`RepValidation`), 구글 로그인·서버 동기화(§SERVER_SYNC_HANDOFF — iPhone 데이터는 로컬), APK 업데이트, 개발용 `PostureLab`·AIHub 연구 CLI.

## 엔진 유지 절차

```bash
python3 tools/sync_ios_core.py            # 정본 → posture-core 생성 (--check 는 검사만)
python3 tools/export_ios_resources.py     # 카탈로그·가이드·영양·자산 SHA
bash gradlew -PiosOnly=true :posture-core:jvmTest
python3 tools/verify_ios_project.py       # Swift 구문·XcodeGen·plist·세 --check (tree-sitter, pyyaml, flatbuffers, tflite 필요)
```

정본에 파일이 늘면 `sync_ios_core.py` 의 `NAMES` 에 넣는다(플랫폼 import 가 있으면 `portable()` 에 치환을 더한다 — Android 파일에서 **잘라 내는** 쪽을 택하고 손으로 옮기지 않는다). Android 테스트가 `SetLog`·`Context` 에 묶이면 `excluded` 에 넣는다.

## 플랫폼 경계 (`IosEngine`, Swift 직렬 큐 전용)

- `configure(json)`: `name`(앱 이름)·`mode`·`target`·`timed`(시간 목표)·`front`·`muted`·`voiceReady`·`baseline`(개발용, 기본 없음) → 준비 안내·세는 조건·범위·바닥/플랭크 여부.
- `arm`·`manualStart`·`pause(paused, now)`·`tick(now)`(200 ms, 발화 감시 포함)·`frame(json)`: `timeMs`·`width`·`height`·`xy`(66)·`world`(99, m)·`visibility`(33)·`up`(중력 반대, 월드)·`gravityFresh`·`inferMs`·`face{found, matrix[16] 행 우선, inferMs, rotation, headPx}`.
- 상태 JSON: `stage`·`reps`·`done`·`message`·`cue`(이 회의 교정 문장)·`guideNote`·`provisionalNote`·`note`(세지 않은 동작·시선 칩)·`coverage`·`highlight`/`provisional`(관절)·`shown`/`target2d`(관절 게이트)·`motion`(화살표)·`hold`(플랭크 HUD)·`score`·`speech[{text, flush}]`·`tones[]`·`fast`.
- `speechStarted(now)`·`speechFinished(now) → speech[]`·`stopSpeech()`·`beginSetEnd(now)`·`advanceDue(now)`·`finish(now, handOff) → 리포트 + speech[]`·`exportFrames()`.
- `IosFace.cropSpec(json)`: 얼굴 크롭 명세(`cx`·`cy`·`rotateDeg`·`side`·`scale`·`outPx`) 또는 `null`.

## Mac 준비와 빌드

변경 없음 — [`docs/IOS_INSTALL.md`](IOS_INSTALL.md)(Xcode 26·JDK 21·XcodeGen·CocoaPods, `bash iosApp/scripts/setup-mac.sh`, `build-unsigned-ipa.sh`, `test-simulator.sh`). 번들 자산은 **5개**(얼굴 모델 `face_landmarker.task` 3.8 MB 추가)이고 `verify_bundle_assets.py` 와 `TrexTests` 가 그 수를 본다. XCTest 는 §107 의 4건에 얼굴 모델 초기화·크롭·추론(빈 영상 → 얼굴 없음)과 엔진 발화 대기열(한 번에 하나) 2건을 더했다.

## 다음 검증 (사용자)

1. macOS Actions 성공 여부 — Swift 타입·SDK 링크(`FaceLandmarker`·`TransformMatrix.value(atRow:column:)`·`MPImage(uiImage:)`)는 여기서만 확인된다.
2. iPhone 에서 플랭크: "시간을 재기 시작해요" → 무릎을 대면 낮은 틱 + 이유 → 다시 들면 "교정됐어요. 다시 재요", HUD 숫자가 카메라 시간인지, 폰을 보면 3 s 뒤 "시선이 벗어났어요".
3. 크런치·레그 레이즈: 팔만·어깨만·한 다리 회가 세지지 않고 이유가 들리는지, 시선 칩.
4. 스쿼트·런지·컬: 빠진 회의 "이 회는 세지 않았어요", 화살표 방향이 미러와 맞는지(전면 카메라), 세트 끝 교정 문장이 잘리지 않는지, 가로 잘림 안내.
5. 로그 `posture_logs/*.jsonl` 회수 → 변환기 작성(Android 재생기 입력으로).

## 저장 형식

앱 데이터는 `Application Support/TREX/data.json` schema 1(변경 없음). 자세 로그 `posture_logs/<record UUID>.jsonl` 은 schema **2**: 첫 줄 `kind=ios_session` 에 종목·모드·목표·전면 여부·규칙 버전·판정 간격·시작/앵커 시각·반복 시각·기각(시각·사유·쪽)·플랭크 시계(인정/경과/출처/멈춤/구간)·바닥 회 시선·말한 시선 문장, 이후 `kind=ios_frame` 은 상대 시각·판정 피처(얼굴 피처 포함)·xy/world/가시성·크기·중력(신선도·뒤집힘). 카메라 영상은 없다.

---

이전 상태(2026-10-07, §107~§107e — `redesign` `5213e3c4` 기준 첫 포팅, Mac 빌드·IPA 검증 기록)는 Git 이력의 이 파일 이전 판과 스펙 §107 을 본다.
