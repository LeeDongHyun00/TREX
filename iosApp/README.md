# TREX iOS M0 진단 앱

M0는 SwiftUI 앱의 빌드·설치·화면 실행 경로를 확인한다. 기본 화면은 **평가 엔진 연결 전**이며 화면 응답 확인 버튼과 실행 환경만 제공한다. KMP, MediaPipe, 카메라/센서 구독, 운동 횟수·각도·점수·교정·운동 기록은 포함하지 않는다.

작업 지시는 [MAC_IMPLEMENTATION.md](../docs/ios-kmp/MAC_IMPLEMENTATION.md), 실제 실행 결과와 미완료 항목은 [MAC_STATUS.md](../docs/ios-kmp/MAC_STATUS.md)를 따른다. M1/M2 어댑터와 M3 공통 연결은 별도 작업이다.

## 프로젝트와 환경

- 열 파일: `iosApp/TrexPostureDiagnostics.xcodeproj`
- 공유 scheme: `TrexPostureDiagnostics`
- 앱 타깃: `TrexPostureDiagnostics`
- UI 테스트 타깃: `TrexPostureDiagnosticsUITests`
- 별도 bundle ID: `com.leedonghyun.trex.posturediagnostics`
- 개발 deployment target: **iOS 16.0**, iPhone/iPad
- 최초 빌드 도구: **Xcode 16.2 (16C5032a)**, iOS/iOS Simulator SDK **18.2**, Swift 6.0.3 컴파일러의 Swift 5 언어 모드

프로젝트와 공유 scheme을 직접 추적하므로 XcodeGen·Ruby·CocoaPods·Gradle 등의 재생성/의존성 설치 없이 clone 후 열 수 있다. 앱은 기본 기기 언어와 관계없이 한국어 진단 문구를 표시한다. 개인 Team ID·기기 ID·서명 파일은 프로젝트에 포함하지 않는다.

## 자산 검사

아래 명령은 모두 저장소 루트에서 실행한다.

```bash
python3 research/aihub_fitness/verify_ios_handoff.py
python3 research/aihub_fitness/verify_ios_handoff.py --strict-bytes
```

이는 체크아웃의 8개 자산/픽스처 검사다. **M0 앱 번들에는 모델·규칙·정상 표본을 복사하지 않는다.** 따라서 M0에서 설치된 모델의 해시 일치나 추론 성공을 주장할 수 없다. M2에서 원본 `app/src/main/assets/posture/`의 Full 모델·두 규칙·정상 표본을 추론 앱 번들로 복사하고, 번들에서 다시 SHA-256을 확인한다. 원본 파일의 해시나 내용을 바꾸어 실패를 숨기지 않는다.

`NSCameraUsageDescription`은 후속 카메라 진단의 한국어 목적을 명시한다. M0는 권한을 요청하거나 카메라를 켜지 않으며 마이크 권한도 없다.

## 일반 iOS 대상 빌드 — 설치 검증과 구분

```bash
xcodebuild -project iosApp/TrexPostureDiagnostics.xcodeproj \
  -scheme TrexPostureDiagnostics -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath iosApp/build/device \
  CODE_SIGNING_ALLOWED=NO build
```

출력 앱은 `iosApp/build/device/Build/Products/Debug-iphoneos/TrexPostureDiagnostics.app`이다. **서명 없는 arm64 컴파일 성공**이며 실제 iPhone 설치·실행 결과가 아니다. 이 앱을 서명 준비 없이 iPhone에 설치하는 경로로 사용하지 않는다.

## iPhone용 서명과 실행

Xcode > Settings > Accounts에서 본인의 Apple ID/팀을 설정한다. 서명 설정은 추적하지 않는 `iosApp/Signing.local.xcconfig`에 둔다.

```xcconfig
DEVELOPMENT_TEAM = 본인의_팀_ID
CODE_SIGN_STYLE = Automatic
```

아래 `<기기 ID>`는 `xcrun devicectl list devices` 또는 Xcode에서 확인한 로컬 값으로 대체한다. 공유 문서나 명령 결과 요약에는 실제 식별자를 넣지 않는다.

```bash
xcodebuild -project iosApp/TrexPostureDiagnostics.xcodeproj \
  -scheme TrexPostureDiagnostics -configuration Debug \
  -destination 'id=<기기 ID>' \
  -derivedDataPath iosApp/build/signed \
  -xcconfig iosApp/Signing.local.xcconfig \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration build

xcrun devicectl device install app --device '<기기 ID>' \
  iosApp/build/signed/Build/Products/Debug-iphoneos/TrexPostureDiagnostics.app

xcrun devicectl device process launch --device '<기기 ID>' \
  com.leedonghyun.trex.posturediagnostics
```

서명 빌드가 통과한 뒤에만 설치하고, 설치가 통과한 뒤에 실행한다. 실행 명령의 성공과 실제 화면 표시/버튼 응답 확인도 따로 기록한다. 개발 계정/인증서/프로파일이 없거나 Xcode·기기 OS 지원이 막히면 정확한 오류를 남긴다. 기존 TREX 앱 삭제·기기 초기화·OS 변경으로 해결하지 않는다.

설치 후 첫 실행이 개발자 신뢰 관련 보안 오류로 거절되면 iPhone의 설정 > 일반 > VPN 및 기기 관리에서 본인의 개발자 앱을 확인하고 신뢰한 뒤 다시 실행한다. 기기 페어링·Developer Mode 활성화와 개발자 앱 신뢰는 각각 확인한다. [Apple의 개발자 신뢰 안내](https://help.apple.com/xcode/mac/current/en.lproj/dev96a12fb84.html)

## 시뮬레이터 빌드·실행·UI 테스트

설치된 런타임과 사용 가능한 기기를 먼저 확인한다.

```bash
xcrun simctl list runtimes
xcrun simctl list devices available
```

이번 환경에서 별도 진단 기기를 생성할 때 사용한 명령은 아래와 같다. 이미 `TREX M0 iPhone 16 Plus`가 있으면 재사용하며 매번 새로 만들지 않는다.

```bash
xcrun simctl create 'TREX M0 iPhone 16 Plus' \
  com.apple.CoreSimulator.SimDeviceType.iPhone-16-Plus \
  com.apple.CoreSimulator.SimRuntime.iOS-18-3
```

반환된 로컬 ID를 아래 `<시뮬레이터 ID>`에 넣는다. 최초 부팅의 데이터 준비에는 시간이 걸릴 수 있다.

```bash
xcrun simctl boot '<시뮬레이터 ID>'
xcrun simctl bootstatus '<시뮬레이터 ID>' -b

xcodebuild -project iosApp/TrexPostureDiagnostics.xcodeproj \
  -scheme TrexPostureDiagnostics -configuration Debug \
  -destination 'platform=iOS Simulator,id=<시뮬레이터 ID>' \
  -derivedDataPath iosApp/build/simulator \
  -resultBundlePath iosApp/build/M0-UI.xcresult \
  -parallel-testing-enabled NO test
```

`-resultBundlePath`는 아직 없는 경로를 사용한다. 테스트는 진단 앱을 실제로 실행해 **엔진 미연결 문구 → 버튼 터치 → 응답 확인 1회 → 엔진 여전히 미연결**을 확인하고 화면 캡처를 xcresult에 남긴다. 이 카운트는 UI 조작 응답이며 운동 반복 수가 아니다. 시뮬레이터 성공을 iPhone 실행 성공으로 합치지 않는다.

별도 설치/실행 단계의 종료 코드를 확인하려면 시뮬레이터 빌드 후 아래를 실행한다.

```bash
xcrun simctl install '<시뮬레이터 ID>' \
  iosApp/build/simulator/Build/Products/Debug-iphonesimulator/TrexPostureDiagnostics.app
xcrun simctl launch '<시뮬레이터 ID>' com.leedonghyun.trex.posturediagnostics
```

실행 시 OSLog subsystem `com.leedonghyun.trex.posturediagnostics`, category `M0`에 `M0_DIAGNOSTICS screen=visible engine=not_connected`, scene 상태, 버튼 응답을 기록한다. 관절/영상·기기 식별자·개인 운동 데이터는 수집하지 않는다.

## 공유 범위와 후속

`iosApp/.gitignore`는 build/DerivedData/Pods/xcuserdata/개인 xcconfig·서명을 제외한다. 프로젝트·공유 scheme·소스·테스트를 추적하고, M2에서 추가하는 Podfile.lock도 추적한다. M0는 Pod/KMP 없는 진단 타깃을 유지하며 M2의 별도 `TrexPostureInference` 타깃과 구분한다.

아직 카메라/센서/추론/공통 엔진 테스트, iOS 16 실기기, 안정 OS 전체 지원, 출시 검증은 없다. 현재 iPhone의 연결·개발자 모드와 M0 앱의 실제 빌드·설치·실행 결과는 상태 문서에서 각각 확인한다.
