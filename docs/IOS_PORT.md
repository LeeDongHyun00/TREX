# iPhone 포팅 — 2026-10-21 (`ios-redesign`, main `ca7f1fa` 병합)

## 상태

`ios-redesign` 은 `origin/main`(`ca7f1fa`, 2026-10-14 — §99~§106 바닥 계열·시선·말하기 대기열·교정 화살표·서버 동기화·앱 아이콘)을 병합한 위에 서 있다. 이전 iOS 작업(§107, 2026-10-07)은 `redesign` `5213e3c4`(preview.6) 기준이라 그 사이 Android 가 얻은 바닥 엔진(플랭크 유지 시계·크런치/레그 레이즈 판별)·Face Landmarker 시선·`SpeechQueue` 발화 정책·`StandingFraming`·관절 게이트가 iOS 에 없었다. 이번 작업(§107f)이 그 전부를 **Android 정본을 그대로 생성해** iOS 에 올린다.

검증한 것: 공통 엔진 JVM **504/504**(정본 테스트 + iOS 경계 18건, Linux), 정본 동기화 `--check`·자산 추출 `--check`·YOLO 호환 모델 `--check`, Swift 31파일 tree-sitter 구문 검사, XcodeGen 자산 경로·plist. 그리고 코드 `a4a4e5ed` 의 [macOS Actions 12차 실행](https://github.com/LeeDongHyun00/TREX/actions/runs/39558756012)이 **성공** — Xcode 26 에서 Kotlin/Native 프레임워크·Swift 컴파일, iPhone arm64 Release 빌드, 원본 자세 자산 5개(얼굴 모델 포함)·음식 모델 SHA·내부 공개 심볼 0개 검사, 미서명 IPA 생성, arm64 시뮬레이터 XCTest(자산·백업·85 ms 게이트·세 음식 모델 + 자세 모델 + **얼굴 모델** 동시 추론·엔진 발화 대기열) 통과. [IPA artifact](https://github.com/LeeDongHyun00/TREX/actions/runs/39558756012/artifacts/12326857568)(보관 2027-01-19 까지). 앞선 9~11차는 Swift 오류 4건(`SetReport` Encodable, 메인 액터 정적 속성, `TransformMatrix` 인덱스 `UInt`)과 관절 게이트 좌표의 `null` 디코딩으로 실패했고 그 수정이 이 실행이다. **서명·실제 iPhone 실행·운동 정확도는 미검증**이다 — 시뮬레이터 추론 검사는 빈 영상의 실행 검사다.

## iOS 포팅 기준 (이 작업에서 세운 것 — 지켜야 한다)

Android 에서 iOS 로 옮길 때 무엇을 옮기고 무엇을 옮기지 않는지의 선이다. CLAUDE.md 의 판단 원칙은 플랫폼과 무관하게 그대로다.

1. **판정은 한 곳에만 있다.** 임계값·뷰 게이팅·카운터·반복 검사·판별 기각·플랭크 시계·시선 분류·발화 선택 정책은 Android Kotlin 정본(`app/src/main/java/.../posture/`)이고, `tools/sync_ios_core.py` 가 플랫폼 I/O(Android import·`@Synchronized`·`String.format`·`org.json`)만 바꿔 `posture-core` 공통 소스를 **생성**한다. 생성 소스(`// tools/sync_ios_core.py 생성본` 머리말)는 손으로 고치지 않는다. 바꿀 것은 정본을 바꾸고 다시 동기화한다(`--check` 가 CI·Xcode pre-build 에서 어긋남을 잡는다). 같은 수식이 두 플랫폼에 두 벌 있으면 안 된다 — 얼굴 요(`faceYawDeg`)조차 `PostureAnalyzer.kt` 에서 잘라 낸 슬라이스(`FaceYaw.kt`)다.
2. **Swift 는 카메라·MediaPipe 다리다. 화면·분석 루프는 Android 정본이다(§108, 원칙 #9).** 자세 화면은 Android `PostureLiveSessionScreen` 자체다(§108c) — 분석 루프(준비 프레임 → 접촉 → 판정 칸 → 바닥 시계/시선 → 카운터 → 판별 기각 → 반복 검사 → 발화 → 창 규칙 → 비교)·세트 마감·세트 로그를 iPhone 이 **같은 소스로** 컴파일한다. Swift(`iosApp/Trex/PoseCameraBridge.swift`)는 카메라 세션·미리보기·MediaPipe 추론만 하고, 추론 이후 처리(`PoseLandmarks` — 관절·up 자가검증·피처·얼굴 크롭 기하)부터 공유다. 세션 스피커도 공유 `SpeechCoach`(대기열·들림 장부·꼬리) 위에 AVSpeechSynthesizer 엔진이다. Swift 가 "이 회를 셀까", "이 말을 할까" 를 정하지 않는다. 예전 손 배선 `IosEngine` 은 쓰지 않는 예전 SwiftUI 세션 화면과 함께 남아 있다(정리 예정).
3. **발화는 엔진 대기열이 고른다(§101).** Swift `SpeechService` 는 AVSpeechSynthesizer 만 들고, 엔진 `IosSpeech`(공통 `SpeechQueue`)가 고른 제출을 **한 번에 하나만** 재생하며 시작·종결을 엔진에 돌려준다. 들림 장부(지적이 재생을 시작한 뒤에만 "교정됐어요"), 세트 끝 꼬리(마지막 교정 문장을 자르지 않고 ≤ 10 s), 같은 쪽 카운트의 최신만, 번갈아 내기가 그래서 Android 와 같다. 음소거·한국어 음성 없음(→ 톤)도 엔진이 안다.
4. **격자는 같다(§96).** 85 ms 추론은 뼈대 게이트·순간 접촉(§98a)에만, 준비·판정·카운터·로그는 300 ms 칸의 첫 프레임이다. 얼굴 모델도 300 ms 에 한 번, 결과는 450 ms 안의 프레임에 얹는다(Android `FACE_INTERVAL_MS`·`FACE_STALE_MS`). 열 상태가 나쁘면 추론 간격을 300 ms 로 줄이므로 순간 접촉을 놓칠 수 있다.
5. **못 하는 것은 못 한다고 밝힌다.** 사람이 안 잡힌 프레임은 로그·집계에 넣지 않는다(Android 와 같다 — 종전 iOS 는 빈 프레임을 로그에 넣었다). 얼굴 모델이 실패하면 시선은 '측정 중' 이지 '정면' 이 아니다. 시간 목표(플랭크)는 카메라가 확인한 시간이 있을 때만 그것을 쓰고 아니면 시계이며 HUD 가 어느 쪽인지 보인다. 점수·"깨끗" 은 판정이 0건이면 없다.
6. **세트 로그는 iOS 형식(schema 2)이고 Android `SetLogJson` 재생 형식이 아니다.** 반복 시각·기각 사유·플랭크 구간·바닥 회의 시선·말한 시선 문장을 머리줄에, 프레임마다 피처·xy·world·가시성·중력을 남긴다. 연구 도구에 그대로 넣지 않는다 — 폰 로그를 확보한 뒤 변환기를 따로 둔다.
7. **자산은 Android 정본 바이트다.** 규칙 JSON·정상 표본 TSV·자세 모델·얼굴 모델은 `app/src/main/assets/posture/` 를 번들에 그대로 복사하고 `ASSET_BASELINE.json`(5개) 의 SHA 로 앱 시작·CI 에서 대조한다. 카탈로그 26·가이드 10·영양 414(main PR #9 의 64종 추가 뒤, 이전 350) 는 `tools/export_ios_resources.py` 가 Kotlin 정본에서 뽑는다. 음식 YOLO 만 TFLite C API 호환을 위해 inline 가중치 사본(§107c~e)이다.
8. **Android 의 동작은 바꾸지 않는다(§108 에서 '손대지 않는다'를 풀었다, 2026-10-22 사용자 승인).** 화면을 iOS 와 공유하려고 Android API 호출을 작은 플랫폼 파일로 옮기는 리팩터는 한다. 동작·문구·간격은 그대로이고 Android 테스트와 실기기 확인을 거친다. iOS 를 위해 모집단 파라미터·규칙 JSON 을 바꾸지 않는다(원칙 #4).
9. **화면도 한 곳에만 있다(§108).** iPhone 화면은 `ios-ui` 가 Android 정본 Compose 파일을 Compose Multiplatform 으로 **그대로** 컴파일한 것이다. 공유 목록은 `ios-ui/android-sources.txt` 한 곳이고 사본을 커밋하지 않는다(빌드 때마다 `app/` 에서 가져온다). 목록에 오른 파일은 commonMain 에서 컴파일되므로 Compose·Kotlin 표준·`posture-core` 만 쓸 수 있다 — Android API 를 쓰면 iOS 빌드가 깨지고, 그것이 경계 검사다. 엔진과 화면은 정적 `TrexCore` 프레임워크 하나다. 앱 상태(`AppViewModel`)·저장·동기화도 같은 목록에 있고 플랫폼 기능(키-값 저장소·HTTP·근육 장부·라벨 파일)은 `AppPlatform` 경계로 받는다 — Android 구현은 `AndroidPlatform.kt`(§108a). 화면이 쓰는 플랫폼 기능은 **같은 이름·같은 시그니처의 짝**이다 — Android `PlatformUi.kt` ↔ iPhone `ios-ui/src/iosMain/.../PlatformUi.kt`(경계 타입은 공유 `PlatformApi.kt`). 카메라 자세(§108c)·사진 식단(§108d)도 공유 화면 + Swift 다리이고, iPhone 에 아직 없는 기능(구글 로그인)은 짝 구현이 없다고 밝힌다(§108b).

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
| 사진 식단(§108d, 2026-10-23) | **Android `PhotoFoodSheet` 그 자체**(공유 목록) — 고르기(촬영·갤러리 ≤ 5장·직접 입력) → 분석 → 결과(자리 이름표·수량·바꾸기·빠진 음식 추가) → 기록 → 고친 자리 기억. 판정 흐름은 공유 `FoodPipeline`(Android `FoodDetector` 에서 떼어 냄 — 2단계·기억·이름 없는 사진의 전체 1회·결과 목록), 입력 배치(letterbox·30 % 자리·특징값 자리)도 공유 `FoodInputs`(Android 그리기도 이것을 부른다). Swift `FoodBridge.swift` 는 그리기·TFLite·사진(ImageIO 축소 디코딩, PHPicker, 시트 안 AVFoundation 카메라)만. 내 음식 기억은 Android 와 같은 텍스트 형식 `Application Support/TREX/food-memory.tsv` — 아래 SwiftUI 판이 쓰던 파일을 이어 읽는다. *이전(2026-10-22, 사용자 요청 "음식 사진 인식도 앱에 구현"):* SwiftUI `DietViews`·`FoodRecognition.swift` 와 공통 정책 `IosFood`·`IosFoodMemory` 로 같은 흐름을 손으로 옮겼었다 — 지금 앱은 쓰지 않는다(정리 예정). 영양 DB 의 추정값 8종 표시(`approximate`)는 그대로 |
| 화면 전환(§108d, 2026-10-23) | 왼쪽 가장자리 스와이프 = 뒤로: 공유 화면의 `PlatformBackHandler` 가 iPhone 에서 CMP `BackHandler` 라 Android 시스템 뒤로와 같은 일을 한다(하위 화면 닫기, 운동 중이면 종료 확인, 시트·펼친 내비 닫기, 로그인·온보딩 단계). 시트(`SheetHost`)는 iPhone 에서 아래로 끌어 내리면 닫힌다(`PLATFORM_SHEET_DRAG_DISMISS` — Android 는 종전 그대로 스크림 탭·시스템 뒤로). 운동 편집 시트는 두 플랫폼 모두 Material 시트(종목 고르기에서만 끌어 닫기). 운동 세션은 Android 처럼 전체 화면이고 뒤로 밀기가 종료 확인으로 간다. *이전(2026-10-22):* SwiftUI 판은 `Navigation.swift`·`trexSheet` 로 같은 동작을 했다(세션은 끌어 내리는 시트, 세트 중에는 닫히지 않음) — 지금 앱은 쓰지 않는다 |
| 홈 날씨(§108e·§108f, 2026-10-23) | weather 브랜치(`origin/feat/weather-recommendation`, Open-Meteo)를 공유로 — 판정(`WeatherExerciseRecommender`)·저장소(`WeatherRepository`: 플랫폼 HTTP·키-값 저장소, 1시간 캐시·실패 시 3시간 묵은 캐시)·위치 설정이 두 플랫폼 한 소스. 홈에서는 날짜 아래 한 줄(`WeatherSummaryRow`: 하늘색 아이콘·기온·날씨·강수량, 누르면 날씨 시트 — 추천 운동·출처·지역/현재 위치) — 사용자 요청으로 큰 카드는 뺐고(§108f) 추천 운동·출처는 시트로 옮겼다(스펙 '날씨 기반 운동 추천 판정 기준' 화면 절). 현재 위치는 짝 `rememberWeatherLocator()` — iPhone Core Location(When In Use, 1 km) |
| 무대 보기 전환(§108e, 2026-10-23) | 운동 화면 카메라 칸 오른쪽 위의 두 칸 버튼(뼈대 \| 영상, `StageViewToggle`) — ⋯ 패널의 '뼈대만/영상' 과 같은 상태, 두 플랫폼 공유 |

iOS 에 **없는 것**: Android 의 세션 중 센서 방향 추종(§101e — iPhone 앱은 세로 고정; 바닥 종목을 가로로 두고 싶으면 폰을 눕혀 세로 화면 그대로 쓴다), 남은 시간 다리 `HoldBridge`(iOS 는 엔진 HUD 의 `camera` 플래그로 같은 뜻을 전한다), 무대 배율 잠금(`StageFit`), 유령 다리 표시, 검증 모드(`RepValidation`), 구글 로그인·서버 동기화(§SERVER_SYNC_HANDOFF — iPhone 데이터는 로컬), APK 업데이트, 개발용 `PostureLab`·AIHub 연구 CLI.

## Android 를 바꿀 때 iPhone 도 같이 (운영 규칙, 2026-10-23)

화면·앱 상태·자세 엔진은 한 소스다 — **공유 목록(`ios-ui/android-sources.txt`)에 오른 Android 파일을 고치면 iPhone 도 다음 빌드에 그대로 바뀐다.** 따로 옮길 일은 아래 경우뿐이다.

1. **새 파일** → 공유할 화면·로직이면 `ios-ui/android-sources.txt` 에 한 줄(목록 밖 파일을 공유 파일이 부르면 iOS 컴파일이 깨져서 알 수 있다).
2. **공유 파일 안의 Android API**(`android.*`·`java.*`·`Context`·`BuildConfig`·`@Synchronized`) → 경계 뒤로: 앱 상태는 `AppPlatform`·`KeyValueStore`·`HttpTransport`, 화면 기능은 같은 이름의 짝(`PlatformUi.kt`·`PostureCameraAndroid.kt`·`PhotoFoodAndroid.kt`·`weather/WeatherLocatorAndroid.kt` ↔ `ios-ui/src/iosMain/...` 의 같은 이름), 날짜는 `TrexTime.kt`, 잠금은 atomicfu `locked`.
3. **새 플랫폼 기능**(센서·권한·카메라·TFLite·MediaPipe 등) → 경계 인터페이스 + iPhone 구현(필요하면 Swift 다리 `iosApp/Trex/*Bridge.swift`) + Android 권한이면 `iosApp/Trex/Info.plist` 사용 문구.
4. **플랫폼 관례가 다른 동작**(뒤로 = 가장자리 스와이프, 시트 끌어 닫기 등) → 짝 상수·짝 함수로 가른다(`PLATFORM_SHEET_DRAG_DISMISS`, `PlatformBackHandler`).
5. **자세 엔진(`posture/`)** → `python3 tools/sync_ios_core.py`(정본 → `posture-core`), 카탈로그·가이드·영양 자료 → `python3 tools/export_ios_resources.py`. 둘 다 Xcode 빌드 전에 `--check` 로 검사된다.
6. **새 자산 폴더** → `iosApp/project.yml` 의 번들 목록(이미 들어간 폴더: `exercise_guides`·`muscle_load`·`routine`·`guid_img`·`res/drawable-nodpi`·모델 파일).
7. **확인** → Mac 에서 `bash gradlew -PiosOnly=true :ios-ui:compileKotlinIosArm64`(1~2분), main 대상 PR 은 CI `shared` 작업이 같은 검사를 한다(이 브랜치가 main 에 들어간 뒤). 화면은 실기기 확인 모드(`--screen-tour`·`--posture-probe`·`--food-probe`)로 PNG·기록을 남기고, UIKit 층(카메라 미리보기·WebView)과 제스처는 손으로 본다.

하지 말 것: iPhone 전용으로 SwiftUI 화면을 따로 고치기(예전 `ios-redesign` SwiftUI 판은 지금 앱이 쓰지 않는다 — 정리 예정), Android 와 iPhone 에서 다른 브랜치로 같은 기능을 따로 만들기(날씨·뒤로 밀기를 다시 옮긴 비용이 §108d·§108e 다).

## 엔진 유지 절차

```bash
python3 tools/sync_ios_core.py            # 정본 → posture-core 생성 (--check 는 검사만)
python3 tools/export_ios_resources.py     # 카탈로그·가이드·영양·자산 SHA
bash gradlew -PiosOnly=true :posture-core:jvmTest
python3 tools/verify_ios_project.py       # Swift 구문·XcodeGen·plist·세 --check (tree-sitter, pyyaml, flatbuffers, tflite 필요)
```

정본에 파일이 늘면 `sync_ios_core.py` 의 `NAMES` 에 넣는다(플랫폼 import 가 있으면 `portable()` 에 치환을 더한다 — Android 파일에서 **잘라 내는** 쪽을 택하고 손으로 옮기지 않는다). Android 테스트가 `SetLog`·`Context` 에 묶이면 `excluded` 에 넣는다.

## 자세 화면의 플랫폼 경계 (§108c)

공유 `PostureLiveApi.kt` 의 경계를 플랫폼마다 같은 이름 함수로 채운다 — Android `PostureCameraAndroid.kt` ↔ iPhone `ios-ui/.../PostureCameraIos.kt`.

- `PoseCamera`(`rememberPoseCamera()`): `stream(front, label, onFrame)` — 카메라를 묶고 분석 스레드에서 프레임마다 공유 루프를 부른다(처리 중 프레임은 버림 = KEEP_ONLY_LATEST), `analyze(frame, now, up)` → `PoseSample`, `gravityUp(rotation, front)`, `faceEnabled`, `stats()`, `lens`, `thermalSupported`·`start(onThermal)`·`stop()`, `Preview(modifier)`(FIT_CENTER).
- `PostureHost`(`rememberPostureHost()`): 자산 텍스트·프로필 키·`ModeStore`·`SetLogStore`·수행자 ID·빌드 이름 — Android `Context` 자리.
- `RepTone`(`rememberRepTone(volume)`): Android ToneGenerator / iPhone 합성음(TONE_PROP_* 모양, 재생 범주라 무음 스위치와 무관). `CameraPermission`(`rememberCameraPermission()`).
- `SpeechEngine`: Android TextToSpeech / iPhone AVSpeechSynthesizer — 정책은 공유 `SpeechCoach`. iPhone 엔진은 사건의 발화를 `==`(isEqual)·같은 글로 찾는다 — Kotlin `===` 로 찾던 때는 사건 id 가 비어 세트 중 교정 문장이 전부 보류됐다(§108e). 설치된 음성은 30초마다 다시 본다(세션 중에 받은 음성도 쓴다). iPhone 은 설치된 한국어 음성 중 가장 자연스러운 것(프리미엄 > 향상됨 > 기본, 같은 품질이면 시스템 기본)을 고르고 빠르기는 Android `setSpeechRate(1.05f)` 와 같은 뜻(보통의 1.05 배)이다(§108d). 기본 품질(압축) '유나' 는 기계음이 강하다 — 향상·프리미엄 음성은 앱이 받을 수 없어 사용자가 설정 › 손쉬운 사용 › 콘텐츠 말하기 › 음성 › 한국어에서 받는다.
- iPhone 의 Swift 다리 `IosCameraBridge`(앱 시작 때 `IosCameraBridgeRegistry.shared.bridge`): `start(front:sink:)`·`stop()`·`previewView()`·`detectPose(frame:timestampMs:)`(VIDEO 모드, 33점 xy·min(visibility, presence)·world)·`detectFace(frame:cx:cy:rotationDeg:scale:outPx:)`(크롭 기하는 공유 `FaceCrop`)·`modelError`. 영상은 세로 640×480 계열 BGRA·미러 없음(전면 미러는 미리보기만), 중력은 Kotlin 이 Core Motion 에서 직접 읽는다.

## 사진 식단의 플랫폼 경계 (§108d)

공유 `PhotoFoodApi.kt` 의 경계를 플랫폼마다 같은 이름 함수로 채운다 — Android `PhotoFoodAndroid.kt` ↔ iPhone `ios-ui/.../PhotoFoodIos.kt`.

- `FoodPhoto`(크기·화면 그림·`scaledToMax`·`crop`), `FoodPhotoLoader`(갤러리 사진을 분석 스레드에서 불러오기), `FoodRecognizer`(`rememberFoodRecognizer()` — `warmUp`·`detect`·`remember`), `FoodCamera`(`rememberFoodCamera()` — `Preview`·`capture`·`error`), `rememberFoodGallery(maxItems, onPicked)`, `isOutOfMemory`. `CameraPermission.request` 는 "권한 다시 요청"(iPhone: 아직 묻지 않았으면 묻고, 거절했으면 설정 화면).
- 판정은 공유 `food/FoodPipeline.kt` 가 `FoodModels`(342종 점수·위치 출력·특징값) 위에서 한다 — Android 는 TFLite 인터프리터(`FoodDetector.kt`), iPhone 은 `IosFoodModels` 가 같은 `FoodInputs` 배치(letterbox·2단계 자리·특징값 자리 — `FoodInputsTest`)를 Swift 에 넘긴다.
- iPhone 의 Swift 다리(앱 시작 때 `IosFoodBridgeRegistry.shared.models`·`.photos`): `FoodModelBridge` — `prepare(name:)`(번들 `yolov8n_food`·`food_region`·`food_embed`, float32·형태 확인, 실패는 다시 시도하지 않음)·`inputShape`·`outputShape`·`run(…)`(회색 114 sRGB 캔버스에 사진 영역을 자리로 그려 float32, 특징값만 ImageNet 정규화, 342종 출력은 클래스별 최고 점수로 줄여서), 호출은 음식 추론 직렬 큐에서만. `FoodPhotoBridge` — `pickPhotos`(PHPicker, 권한 없이 고른 사진만, 고른 순서)·`cameraView`/`startCamera`/`stopCamera`/`capture`(후면 광각, 화면 채움 미리보기, 지연 최소 촬영). 사진은 ImageIO 로 최대 변 1280·EXIF 방향을 한 번에 디코딩한다(원본 크기 비트맵을 만들지 않는다).

## Mac 준비와 빌드

[`docs/IOS_INSTALL.md`](IOS_INSTALL.md)(Xcode 26·JDK 21·XcodeGen·CocoaPods, `bash iosApp/scripts/setup-mac.sh`, `build-unsigned-ipa.sh`, `test-simulator.sh`). 프레임워크는 `bash gradlew -PiosOnly=true :ios-ui:embedAndSignAppleFrameworkForXcode` 가 만든다(Xcode pre-build). Xcode 16(인텔 Mac)에서도 기기 빌드는 되지만 두 가지가 다르다(§108): iOS 18 SDK 빌드에는 `UIViewLayoutRegion` 링크 조건이 걸려 **iOS 26 이상 기기 전용**이 되고, Compose Multiplatform 1.11 에 `iosX64` 가 없어 시뮬레이터를 못 쓴다 — 실기기에서 `--compose-probe` 로 화면을 PNG 로 떠 확인한다. 번들 자산은 **5개**(얼굴 모델 `face_landmarker.task` 3.8 MB 추가)이고 `verify_bundle_assets.py` 와 `TrexTests` 가 그 수를 본다. XCTest 는 §107 의 4건에 얼굴 모델 초기화·크롭·추론(빈 영상 → 얼굴 없음)과 엔진 발화 대기열(한 번에 하나) 2건을 더했다.

## 다음 검증 (사용자)

1. 실기기 `--posture-probe`(§108c): 번들 그림 두 장의 MediaPipe 검출·피처 수, 공유 자세 화면 준비·운동 단계의 프레임·추론·검출 수, 화면 PNG(`Documents/posture-probe/`).
1. 실기기 `--food-probe`(§108d): 세 음식 모델 불러오기·입출력 모양·판정 시간(합성 접시 그림이라 이름은 의미 없음), 특징값 크기, 표지 색으로 픽셀 순서·위아래, 공유 사진 시트 PNG(`Documents/food-probe/`).
1. 음성(사용자): 앱 설정 › '음성 안내 목소리'(iPhone 만)에서 지금 목소리 확인·미리 듣기 → iPhone 설정 › 손쉬운 사용 › 콘텐츠 말하기 › 음성 › 한국어 › 유나에서 '향상된 음질'(또는 '프리미엄')을 받은 뒤 자세 세션의 안내가 자연스러운지, 세트 중 교정 문장이 들리는지. 고른 음성은 `--food-probe` 보고의 `[voice]` 줄·발화 기록의 `tts_voice`.
1. 무대 보기(사용자): 운동 화면 오른쪽 위 '뼈대 | 영상' — 영상 쪽에서 카메라 화면이 보이는지(UIKit 층이라 자동 PNG 에는 없다).
1. 홈(사용자): 내비까지 한 화면에 날짜·날씨 한 줄·운동한 날·섭취 요약·오늘 운동/식사 기록이 다 보이는지, 날씨 줄을 눌러 시트의 추천 운동 '목록에 추가'(→ '목록에 있음')·출처 줄·지역 바꾸기·현재 위치(위치 권한 묻기).
1. 사진 기록(사용자): 실제 음식 사진으로 촬영·갤러리 ≤ 5장 → 이름표·수량·바꾸기 → 기록, 고친 이름이 다음 사진에서 기억으로 붙는지. 카메라 권한 거절 → "권한 다시 요청" 이 설정 화면을 여는지.
1. 뒤로 밀기·시트(사용자): 하위 화면에서 왼쪽 가장자리 스와이프 → 닫힘, 운동 중 → 종료 확인, 시트를 끌어 내리기 → 닫힘·목록이 맨 위일 때 이어서 끌기.
2. iPhone 에서 스쿼트·런지·컬: 횟수·빠진 회의 "이 회는 세지 않았어요"·틱, 화살표 방향이 미러와 맞는지(전면 카메라), 세트 끝 교정 문장이 잘리지 않는지, 가로 잘림 안내.
3. 플랭크: "시간을 재기 시작해요" → 무릎을 대면 낮은 틱 + 이유 → 다시 들면 "교정됐어요. 다시 재요", HUD 숫자가 카메라 시간인지, 폰을 보면 3 s 뒤 "시선이 벗어났어요".
4. 크런치·레그 레이즈: 팔만·어깨만·한 다리 회가 세지지 않고 이유가 들리는지, 시선 칩.
5. 세트 로그 `Documents/posture_logs/sets-*.jsonl` 회수(`xcrun devicectl device copy from`) → Android 재생기·분석 스크립트에 그대로(같은 스키마).

## 저장 형식

앱 데이터는 Android 와 같은 키-값 저장소(§108b, NSUserDefaults `trex.<이름>`). 자세 세트 로그는 §108c 부터 **Android 와 같은 형식·같은 파일 이름**이다: `Documents/posture_logs/sets-yyyyMMdd.jsonl`(스키마 `trex.posture.setlog/1`, 공유 `PostureSetLog.kt` — 숫자 고정 소수만 플랫폼 함수), 발화 기록 `feedback-yyyyMMdd.jsonl`, 자가 라벨 `labels/set_labels.jsonl`·`rep_truth.csv`. 카메라 영상은 없다. 렌즈 물리 사양(초점거리 mm·센서 크기)은 iPhone 이 주지 않아 배치 지문의 거리·높이는 비어 있다(피치·롤·주기는 남는다). 예전 SwiftUI 경로의 `posture_logs/<record UUID>.jsonl`(schema 2)은 더 쓰지 않는다.

---

이전 상태(2026-10-07, §107~§107e — `redesign` `5213e3c4` 기준 첫 포팅, Mac 빌드·IPA 검증 기록)는 Git 이력의 이 파일 이전 판과 스펙 §107 을 본다.
