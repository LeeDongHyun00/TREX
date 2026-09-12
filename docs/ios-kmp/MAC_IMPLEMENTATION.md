# Mac 구현 착수 계획 — ADR-056 보완

**갱신일: 2026-09-13 / 상태: 구현 작업 범위 확정, 앱·KMP 구현은 미착수**

사용자의 Mac 구현 준비 요청에 따라 초기의 ‘환경 검토만’ 범위를 확장한다. [Mac 인수인계 `6a569ec`](https://github.com/LeeDongHyun00/TREX/commit/6a569ec)의 실제 환경과 설계 의견을 반영했다. 아래 M0~M2는 Mac이 지금 구현할 수 있다. P1/P2 공통 API가 필요한 M3은 후속이다. [ADR-056](../IOS_POSTURE_KMP_DESIGN.md)의 판정·좌표·세션 계약은 유지한다.

## 1. 결정과 이유

기존 순서대로 iOS 작업 전체를 P1/P2 뒤에 두면 카메라·Pod·기기 연결 문제도 늦게 드러난다. **공통 엔진에 의존하지 않는 SwiftUI 진단 앱과 OS 어댑터를 먼저 구현**한다. 진단 앱은 관절·센서·처리 시각을 보여 주고 `평가 엔진 연결 전` 상태를 유지한다. Swift로 각도·규칙·횟수·점수·기준선·교정 문구를 다시 구현하지 않는다. 엔진이 없다는 상태는 규칙의 ABSTAIN 판정과도 구분한다.

| 대안 | 판단 |
|---|---|
| 공통 코어를 모두 기다림 | 장치·SDK 위험 발견이 늦어져 보류 |
| 독립 Swift 진단 앱과 어댑터 선행 | 선택. 카메라 경로를 먼저 검증하며 후속 브리지 연결 비용은 남는다 |
| Kotlin 하향 또는 Swift 판정 엔진 작성 | Android 영향/로직 중복이 커 이번 범위에서 제외 |

현재 Intel Mac은 Swift 작업을 시작할 장비로 사용한다. 이것이 현재 Xcode에서 연결 iPhone을 실행할 수 있다는 보장은 아니다. **지원되는 Xcode/Kotlin 환경에서의 Native 검증은 별도 완료 조건**이다.

## 2. 환경별 실행 경로

Mac 관측값은 Intel `MacBookPro14,3`, macOS 14.8.4, Xcode 16.2, Java 21.0.10, CocoaPods 1.16.2다. 검사 당시 시뮬레이터 기기는 없고 iPhone은 오프라인이었다. 지금 연결 상태는 다시 확인한다. 오프라인 캐시의 iOS 26.4.1을 실측 버전으로 쓰지 않는다.

Kotlin 2.3.21의 공식 Xcode 기준은 26.0이다. Xcode 26은 macOS 15.6 이상을 요구한다. 현재 도구 조합의 컴파일 실패는 아직 실측하지 않았다. 버전 표 차이를 앱 코드 오류로 해석하거나 성공으로 가정하지 않는다. [Kotlin 호환 표](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [Apple Xcode 요구 사항](https://developer.apple.com/xcode/system-requirements)

1. M0는 KMP·Pod 없이 Swift 앱의 컴파일/실행부터 확인한다. 연결 iPhone의 실제 OS, 신뢰, Developer Mode, 서명, Xcode 지원 여부를 기록한다. 공유 파일에 개인 Team ID·UDID·서명키를 넣지 않는다.
2. 기기 실행이 막혀도 소스 작성과 서명 없는 일반 iOS 대상 빌드를 분리해서 진행한다. 가능한 경우 설치된 런타임에 시뮬레이터를 만들어 UI 테스트한다. 이 성공은 실기기 실행/촬영 성공이 아니다.
3. 연결 iPhone을 현재 Xcode가 지원하지 않으면 정확한 오류를 기록하고 실기기 검증을 미완료로 둔다. 지원되는 다른 Mac/Xcode 또는 호환되는 검증 기기를 사용하는 경로를 제안한다. OS 패치·기기 다운그레이드·전역 도구 변경으로 우회하지 않는다.
4. KMP P1의 기본 타깃은 `iosArm64`, `iosSimulatorArm64`를 유지한다. Intel 시뮬레이터용 `iosX64`는 **선택 검증 타깃**으로 Windows가 별도 Gradle 변경에 추가한다. M0~M2에는 필요 없다. `iosX64` 추가만으로 Xcode 또는 MediaPipe 바이너리 호환이 해결되는 것은 아니다.

## 3. Mac의 작업 단위와 완료 조건

| 단계 | 구현·공유할 파일 | 완료 기준 / 의존성 |
|---|---|---|
| M0 — 앱 골격 | `iosApp/TrexPostureDiagnostics.xcodeproj`, 공유 scheme `TrexPostureDiagnostics`, SwiftUI 앱, `iosApp/README.md`, `iosApp/.gitignore` | 재생성 도구 없이 clone 후 프로젝트 열기 가능. 일반 iOS 대상 컴파일과 가능한 기기의 화면 실행을 별도 기록. KMP/MediaPipe 의존성 없음 |
| M1 — 카메라·센서 | `iosApp/` 안의 카메라 소유 객체, 프리뷰, Core Motion, 진단 값 모델/테스트 | 권한 허용/거부, 화면 복귀, 전후면/회전, 연속 시작·정지 검사. 원본 이미지 크기·방향·중력 출처 표시. 실기기 결과가 없으면 소스/컴파일까지만 완료 |
| M2 — 관절 추론 | 별도 앱 타깃/scheme `TrexPostureInference`, Podfile/lock, 모델 번들 복사, 직렬 VIDEO 추론, 관절 오버레이, 자산 검사 | 실제 해석된 Pod 버전/아키텍처 기록. 실제 iPhone에서 33점/미검출/오류 구분, 시각 증가, 늦은 결과 폐기·프레임 대기 상한 확인. 평가는 미연결 유지 |
| M3 — 공통 연결 | Windows의 P1 DTO를 사용하는 입력 변환 어댑터, P2 facade 소비 코드 | 정확한 공유 SHA·생성 헤더·테스트 태스크를 받은 뒤 진행. 동일 픽스처 Native 계산 → P2 세션 재생 → 실카메라 순서로 검증 |

M0, M1, M2는 별도 커밋/PR로 나눈다. 실패한 환경도 실제 컴파일·실행 범위와 미실행 이유를 남긴다. ‘컴파일’, ‘설치’, ‘화면 실행’, ‘실제 추론’, ‘엔진 동등성’을 하나의 완료 표시로 합치지 않는다.

`iosApp/.gitignore`에 DerivedData·build·Pods·xcuserdata·개인 xcconfig를 제외하고 프로젝트·공유 scheme·Podfile.lock은 추적한다. README는 사용 Xcode/SDK, deployment target(개발 기본 iOS 16), 열 파일과 명령, 로컬 서명 설정, 모델 복사 경로, 미검증 항목을 담는다. 카메라 사용 목적은 한국어 `NSCameraUsageDescription`으로 명시하고 사용하지 않는 마이크 권한은 추가하지 않는다.

M0의 이름대로 프로젝트를 만든 뒤 사용할 명령 예시다. **현재 저장소에는 아직 프로젝트가 없으므로 지금 실행할 수 있는 빌드 명령이 아니다.**

```bash
xcodebuild -project iosApp/TrexPostureDiagnostics.xcodeproj \
  -scheme TrexPostureDiagnostics -configuration Debug \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Pod 연결 이후에는 생성된 `.xcworkspace`와 `TrexPostureInference` scheme을 사용하며 README의 명령도 갱신한다. Pod 의존성과 MP import 파일은 추론 타깃만 소유한다. 원래 `TrexPostureDiagnostics` 타깃은 공통 Swift UI/카메라 코드를 공유하되 Pod/KMP 링크 없이 유지해 Intel의 Pod 아키텍처 문제가 UI 검사를 막지 않게 한다. 두 타깃은 같은 엔진의 대체 구현이 아니라 입력 진단 범위가 다른 개발 도구다. 서명 없는 빌드는 iPhone 설치를 검증하지 않는다.

## 4. M1/M2 입력 경계 — Swift 내부 모델, 아직 KMP ABI 아님

Swift 내부 값 객체는 ADR §5.2의 이름과 의미를 따른다. 추후 생성 헤더를 받으면 **얇은 변환 파일 한 곳**에서 실제 Kotlin DTO로 바꾼다. 가짜 `TrexPosture` 모듈이나 빈 성공 엔진은 만들지 않는다.

| 값 | M1/M2 규약 |
|---|---|
| `frameId`, `sampleTimeMs` | 정수 ms 단조 시계. 샘플링 승인/추론 직전에 채취하며 0도 유효. 카메라 PTS와 혼합하지 않음 |
| `sourceCaptureTimeMs`, `inferenceEndMs` | 촬영 PTS와 완료 시각은 출처/시간 기준을 명시한 진단값. PTS를 sampleTimeMs에 그대로 대입하지 않음 |
| `sessionId`, `captureEpoch`, `phaseEpoch`, `setToken` | 추론 시작 전 스냅샷. 진단 세션/실행·촬영 세대는 관리하되 실제 운동 세트가 없는 M2의 setToken은 미연결 값으로 둠. M3에서 실제 공통 세트 token을 받아 제출 |
| `normalizedXY`, `worldXYZ` | MP 33점 ID 순서 유지. 2D는 회전 보정 추론 이미지 기준, world는 원본 미터. Swift에서 cm/부호 변환하지 않음 |
| `imageWidth`, `imageHeight` | 회전 보정 후 분석 입력의 실제 크기. 프리뷰 뷰 크기를 넣지 않음 |
| `visibility`, `presence`, 존재 마스크 | SDK 값/필드 누락을 구별. 0 좌표를 만들어 검출로 취급하지 않음. min 결합/기본값 정책은 P1에서 단일 구현 |
| 중력과 `upSource` | 원본 디바이스 중력·시각·방향 메타데이터를 먼저 검증. 엔진 축 변환은 ADR대로 OS 어댑터에서 수행하고 별도 회전 픽스처로 확인. 변환 미검증 IMU를 검증된 up으로 내보내지 않음 |
| `detectionState`, `modelId`, `sdkVersion`, `delegate` | 검출/미검출/추론 오류를 구분하고 실제 사용 모델·SDK·delegate를 기록 |

카메라/추론 자원은 SwiftUI View 재생성과 분리한다. 300ms 기본 간격, 동시 추론 1건, 대기 영상 최신 1장만 유지한다. 카메라 delegate가 무제한 비동기 작업을 쌓지 않도록 제출 전에 제한한다. 버퍼는 추론 종료까지 소유하고 결과 배열은 전달 때 복사한다. lifecycle 제어는 완료된 결과를 받는 직렬 흐름에서 현재 epoch와 비교해 이전 카메라/정지 전 결과를 폐기한다.

전면 미러는 프리뷰에만 적용하고 normalized 2D 좌표에 중복 적용하지 않는다. orientation이 적용된 입력과 오버레이를 고정 좌표 그림으로 대조한다. 느린 추론·전환·백그라운드 후 콜백을 강제로 지연하는 어댑터 테스트를 포함한다. 실제 운동의 준비/앵커/횟수/마감 상태 머신은 P2를 기다린다.

M2는 `MediaPipeTasksVision`을 Swift 앱의 CocoaPods 의존성으로 해석하고 검증한 버전을 잠근다. 기존 Full 모델, 1명, 신뢰도 0.5, segmentation 비활성, VIDEO 모드를 출발점으로 삼되 실제 SDK 옵션을 확인한다. Android의 0.10.14를 iOS에 존재하는 버전으로 가정하지 않는다. 진단 화면의 관절 검출 성공은 자세 정상 표시가 아니다. [공식 iOS Pose Landmarker 가이드](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/ios)

TTS가 필요하면 사용자가 누르는 고정 장치 테스트 문장으로 출력·중단 콜백만 검사한다. 관절에서 교정 문장을 생성하지 않는다. 프레임 진단은 메모리/로컬 파일에 한정하며 실제 관절·영상·기기 식별자를 Git에 올리지 않는다. 합성 입력은 명시적으로 표시한다.

## 5. 지금 실행할 수 있는 자산 검사

저장소 루트에서 Python 3.9 이상으로 실행한다. 외부 패키지 설치는 필요 없다.

```bash
python3 research/aihub_fitness/verify_ios_handoff.py
python3 research/aihub_fitness/verify_ios_handoff.py --strict-bytes
```

[자산 기준](ASSET_BASELINE.json)은 Mac에서 보고한 8개 해시를 Git `5237ba9`의 원본 바이트와 대조해 고정했다. 스크립트는 **현재 체크아웃 파일**을 검사하므로 미커밋 변경도 잡는다. 기본 모드는 텍스트의 CRLF↔LF 차이만 허용하고 `byteIdentical=false`, `eolOnly=true`, 실제 raw SHA를 출력한다. 바이너리는 바이트 일치가 필수다. `--strict-bytes`는 줄바꿈 차이도 실패한다. 실패 시 종료 코드는 1이다.

Windows에서는 텍스트 7개의 CRLF 차이가 확인됐다. 내용 비교 통과를 설치된 Android/iOS 자산의 바이트 일치라고 쓰지 않는다. Mac은 strict 검사 후 **실제로 복사된 앱 번들**의 모델·두 규칙·정상 표본 해시도 별도로 계산한다. 이 스크립트는 앱 번들을 검사하지 않는다. Git 원본을 고치거나 해시를 갱신해서 실패를 숨기지 않는다.

규칙 분포는 `rules` 배열의 status를 집계해 서서 141(51/20/70), 바닥 16(0/14/2)을 확인한다. 이 검사는 모델 실행, 규칙 파서 동등성, P0 전체 완료를 증명하지 않는다.

## 6. Windows가 공개해야 할 후속 계약

P1을 공유할 때 `WINDOWS_STATUS.md`에 소스 SHA, Kotlin/AGP/Gradle, Native 타깃, 실제 공개 타입/생성 헤더, 호출 예제, 실행 가능한 테스트 태스크, 실제 자산/픽스처 로더, 허용 오차와 수행한 검사를 남긴다. 소스 파일 이동만을 Native 테스트 성공이라고 쓰지 않는다. P2에서 세션 facade·명령·음성/저장 이벤트·마감 멱등성 계약을 추가한다.

Mac은 그 전에도 M0~M2를 진행할 수 있지만 공통 DTO를 대신 확정하지 않는다. P1 수치 계산 추출과 beta 음성 차단 같은 P2 정책 변경은 별도 기대값으로 검증한다.

## 7. 받아서 시작하기

미커밋/미푸시 작업을 보존한 뒤 Mac 작업 브랜치에서 실행한다. 충돌이나 로컬 분기가 있으면 강제 덮어쓰지 않는다.

```bash
git fetch origin --prune
git switch codex/ios-kmp-mac
git merge --ff-only origin/codex/ios-kmp-mac
git merge origin/codex/ios-posture-kmp
python3 research/aihub_fitness/verify_ios_handoff.py --strict-bytes
```

첫 작업은 **M0 프로젝트 생성과 실제 빌드 경로 확인**이다. 이후 M1/M2를 진행한다. 결과는 자기 `MAC_STATUS.md`와 `iosApp/README.md`에 명령·종료 코드·대상·소스 SHA·실행/미실행을 구분해 기록하고 자기 브랜치에 push한다. PR base는 `codex/ios-posture-kmp`다. 이 문서가 갱신돼도 Mac 작업은 원격으로 자동 시작되지 않는다.
