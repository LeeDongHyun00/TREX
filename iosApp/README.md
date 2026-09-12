# TREX iOS M0/M1 진단 앱

M0 홈은 앱 실행·화면 응답을, M1의 **카메라·센서 진단** 화면은 실제 입력 경로를 확인한다. 두 화면 모두 **평가 엔진 연결 전**을 유지한다. KMP·MediaPipe·운동 횟수·각도·점수·교정·운동 기록은 포함하지 않는다.

작업 지시는 [MAC_IMPLEMENTATION.md](../docs/ios-kmp/MAC_IMPLEMENTATION.md), 실제 실행 결과와 미완료 항목은 [MAC_STATUS.md](../docs/ios-kmp/MAC_STATUS.md)를 따른다. M1은 카메라·원본 중력까지 구현했으며 M2 관절 추론과 M3 공통 연결은 후속이다.

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

이는 체크아웃의 8개 자산/픽스처 검사다. **M0/M1 앱 번들에는 모델·규칙·정상 표본을 복사하지 않는다.** 따라서 설치된 모델의 해시 일치나 추론 성공을 주장할 수 없다. M2에서 원본 `app/src/main/assets/posture/`의 Full 모델·두 규칙·정상 표본을 추론 앱 번들로 복사하고, 번들에서 다시 SHA-256을 확인한다. 원본 파일의 해시나 내용을 바꾸어 실패를 숨기지 않는다.

`NSCameraUsageDescription`과 `NSMotionUsageDescription`은 한국어 진단 목적을 명시한다. 홈에서는 카메라/센서를 켜지 않고, M1 화면의 시작 버튼으로 실행한다. 마이크 권한/입력은 없다.

## M1 사용과 소유권

1. 홈의 **카메라·센서 진단**을 열고 **시작**을 누른다. 첫 실행에서 카메라 권한을 요청한다. 거부/사용 제한/카메라 없음/실행 오류는 구분해서 표시한다.
2. **전후면 전환**으로 기본 광각 카메라를 바꾼다. 화면 회전에 맞춰 출력 CVPixelBuffer를 회전하며 전면 미러는 프리뷰에만 적용한다. 프리뷰는 원본 비율을 유지한다.
3. **정지**, 뒤로 가기, 앱 비활성/백그라운드 전환 때 카메라와 Core Motion을 정지한다. 정지 화면은 마지막 카메라 이미지를 숨기고 프레임/중력을 비운다. 백그라운드 복귀는 이전 시작 의도를 유지해 재개하며, 화면을 나갔다 다시 들어오면 시작 버튼을 눌러야 한다.
4. 권한 거부 후에는 **카메라 권한 설정 열기**를 통해 설정을 바꾼 다음 시작한다. 단말 앱/데이터 삭제로 권한 문제를 우회하지 않는다.

`CaptureController`는 SwiftUI의 `StateObject`가 소유한다. 세션 구성·시작/정지·영상 delegate·센서 콜백을 하나의 직렬 큐에서 처리한다. `startRunning`은 메인 스레드에서 실행하지 않는다. 촬영 시작·전후면/회전·일시 정지/재개 때 세대를 바꾸고, 이전 delegate/센서 콜백은 `CaptureGate`의 epoch로 폐기한다. 촬영하지 않는 세션의 초기 오류 알림은 촬영 실패로 표시하지 않는다.

영상 콜백은 최소 300ms 간격으로 메타데이터만 읽는다. `alwaysDiscardsLateVideoFrames=true`이며 앱이 영상 버퍼를 비동기 큐에 보관하지 않는다. UI 전달도 최신 스냅샷 한 개로 합쳐 메인 스레드 대기열이 무한히 늘지 않게 한다. 실제 추론·느린 추론 큐는 아직 없다.

| 진단 값 | 의미 / 제한 |
|---|---|
| 캡처 포맷 | 활성 카메라의 회전 전 포맷 크기 |
| 출력 버퍼 | 실제 콜백 CVPixelBuffer의 너비/높이. 회전 적용, 미러 없음. 프리뷰 뷰 크기가 아님 |
| 프레임 ID·촬영 세대 | 진단용 승인 프레임 번호와 capture epoch. 운동 반복/세트 식별자가 아님 |
| 샘플 승인 시각 | `DispatchTime` uptime 기반 정수 ms. 0도 유효하며 PTS를 대입하지 않음 |
| 카메라 PTS | CMSampleBuffer PTS의 별도 시간 기준. 샘플 승인 시각과 혼합하지 않음 |
| 중력 x/y/z·센서 시각 | Core Motion 디바이스 축의 raw gravity(g), 부팅 후 경과 ms. 없으면 없음/오류로 표시 |
| upSource/upInEngineWorld | **미연결**. 엔진 축 변환·반전/회전 표 검증은 아직 없으며 원본 중력을 검증된 엔진 입력으로 내보내지 않음 |

앱은 영상·중력을 파일에 저장하지 않는다. XCTest 결과에는 로컬 화면 녹화/캡처가 포함될 수 있으므로 xcresult/테스트 첨부는 Git 공유 대상에서 제외한다. 현재 Swift 값 모델은 생성된 KMP DTO가 아니다.

카메라 출력 회전은 iOS 16 지원을 위해 `AVCaptureVideoOrientation`을 사용한다. SDK 18.2에서 iOS 17 이후 사용 중단 예정 경고가 발생하지만 컴파일은 가능하다. M2에서 실제 2D 좌표/오버레이를 붙일 때 회전·미러 고정 그림 검사를 추가해야 한다. [Apple 카메라 출력](https://developer.apple.com/documentation/avfoundation/avcapturevideodataoutput), [Apple Core Motion](https://developer.apple.com/documentation/coremotion/cmmotionmanager)

## M1 검사 명령

아래 순수 검사에는 카메라나 합성 엔진이 없다. 0ms/300ms 경계, 중복·역행 시각, 정지 뒤 지연 콜백, 100회 재시작의 촬영 세대를 검사한다.

```bash
mkdir -p iosApp/build
swiftc iosApp/TrexPostureDiagnostics/CaptureGate.swift iosApp/Tests/main.swift \
  -o iosApp/build/capture-gate-tests
iosApp/build/capture-gate-tests
```

아래 iPhone/시뮬레이터의 `xcodebuild test` 명령은 M0 회귀와 M1 UI 검사를 실행한다. 카메라 권한 검사는 **진단 앱 자신의 카메라 권한만 초기화**한 뒤 실제 시스템 창에서 허용/거부한다. 실제 카메라·센서 검사는 iPhone에서, 카메라 부재 검사는 시뮬레이터에서 실행하며 반대 환경의 검사는 명시적으로 skip한다. 단순 skip을 통과로 세지 않는다.

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

### 2026-09-13 M1 실제 검사

M1 소스: [`80209a32eea8ec940d76983e11da0c803c117f9e`](https://github.com/LeeDongHyun00/TREX/commit/80209a32eea8ec940d76983e11da0c803c117f9e). M0 PR #1은 사용자 승인으로 `adbe91d`에 통합됐고 M1은 별도 작업이다.

| 검사 | 최종 결과 |
|---|---|
| 자산 strict / 순수 CaptureGate | 각각 종료 코드 0. 자산 8/8 일치, 합성 세대 100회 포함 |
| 일반 iOS arm64 빌드 | 서명 없이 종료 코드 0 |
| iPhone 서명·UI 테스트 | 종료 코드 0, 3건 통과·1건 skip·실패 0 |
| iPhone 별도 설치·실행 | 각각 종료 코드 0, 전면 프리뷰의 실제 영상 표시도 로컬 캡처로 확인 |
| 시뮬레이터 빌드·UI 테스트 | 종료 코드 0, 3건 통과·1건 skip·실패 0 |
| 시뮬레이터 별도 설치·실행 | 각각 종료 코드 0 |

실기기에서는 실제 프레임·중력, 권한 거부/허용, 전후면, 회전된 출력 버퍼, 5회 시작·정지, 백그라운드/화면 복귀를 검사했다. 시뮬레이터에서는 카메라 없음과 권한 거부를 확인했다. 최초 시뮬레이터 오류 알림 처리 및 실기기 테스트의 비동기 전환 대기 문제를 수정한 이력은 [MAC_STATUS.md §8](../docs/ios-kmp/MAC_STATUS.md#8-m1-카메라센서-구현과-실제-검증)에 구분해 기록했다. 고정 표적으로 좌표/미러/중력 엔진 축을 대조하는 검사는 아직 없다.

### 2026-09-13 M0 검사 이력

검증 소스는 [`bee02682d285da8b9dfb3b750b11a9453f08c851`](https://github.com/LeeDongHyun00/TREX/commit/bee02682d285da8b9dfb3b750b11a9453f08c851)이다. Xcode 16.2 / SDK 18.2 / Intel Mac에서 실행했다. 실기기 테스트는 이 소스에서 실행했고, 앞선 일반 iOS/시뮬레이터 검사와의 소스 차이는 파일 끝 빈 줄 정리뿐이다.

| 검사 | 대상 | 실제 결과 |
|---|---|---|
| 자산 기본/strict 검사 | 체크아웃 8개 자산/픽스처 | 각각 종료 코드 0, 8/8 바이트 일치 |
| 서명 없는 build | generic iOS, arm64 | 종료 코드 0 |
| 서명 build | 연결 iPhone, 로컬 xcconfig/자동 서명 | 초기 팀 누락은 65, 사용자 설정 후 0 |
| 설치 | iPhone 16 Plus, iOS 27.0 Beta (24A5424a) | devicectl 종료 코드 0 |
| 실행 | 위 iPhone | 초기 개발자 신뢰 관련 오류는 1, 사용자 신뢰 후 0 |
| 화면/버튼 UI 테스트 | 위 iPhone | 종료 코드 0, 1건 통과·실패 0, 화면 캡처 |
| 빌드/화면 UI 테스트 | 전용 iPhone 16 Plus 시뮬레이터, iOS 18.3.1 | 종료 코드 0, 1건 통과·실패 0 |
| 별도 설치/실행 | 위 시뮬레이터 | simctl install/launch 각각 종료 코드 0 |

실기기 UI 테스트는 위 iPhone 서명 빌드 명령의 마지막 `build`를 `-parallel-testing-enabled NO test`로 바꾸고 새 `-resultBundlePath`를 지정해 실행한다. 공통 명령·단계별 정확한 실패 이력과 판단 범위는 [MAC_STATUS.md §6](../docs/ios-kmp/MAC_STATUS.md#6-m0-구현과-실제-검증-결과)에 기록했다. 현재 도구에서 이 M0 앱의 실행이 통과했음을 뜻하며, KMP/MediaPipe나 전체 OS 호환 검증으로 확대하지 않는다.

`iosApp/.gitignore`는 build/DerivedData/Pods/xcuserdata/개인 xcconfig·서명을 제외한다. 프로젝트·공유 scheme·소스·테스트를 추적하고, M2에서 추가하는 Podfile.lock도 추적한다. M0는 Pod/KMP 없는 진단 타깃을 유지하며 M2의 별도 `TrexPostureInference` 타깃과 구분한다.

M1의 실제 카메라/센서 검사는 상태 문서의 최신 M1 절을 따른다. 관절 추론·공통 엔진·iOS 16 실기기·안정 OS 전체 지원·출시 검증은 아직 없다.
