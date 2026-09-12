# Mac 작업 상태

- **갱신일:** 2026-09-13 (KST), M0 앱 구현·빌드·시뮬레이터/실기기 실행 결과 반영
- **담당:** Mac Codex
- **작업 브랜치:** `codex/ios-kmp-mac`
- **통합 브랜치:** `codex/ios-posture-kmp`
- **가져온 통합 기준 / 확인한 Windows HEAD:** `7eb1f40aeed3f0bd4f41803955c7bfd5023e7978`
- **이번 시작 Mac HEAD:** `37fd7d56a2d84e54c862a84469472a39caf7dfed`
- **M0 소스 커밋:** [`bee02682d285da8b9dfb3b750b11a9453f08c851`](https://github.com/LeeDongHyun00/TREX/commit/bee02682d285da8b9dfb3b750b11a9453f08c851). 실기기 UI 테스트는 이 소스에서 실행했다. 앞선 일반 iOS/시뮬레이터 검사와의 소스 차이는 파일 끝 빈 줄 정리뿐이며 기능 변경은 없다.
- **Android/자산 기준:** `5237ba9d07662849fc2b23853672049ff2a950af`. `app/`, `gradle/`, 루트 빌드 파일과 Wrapper는 변경하지 않았다.
- **현재 단계 / 담당 파일:** **M0 구현 및 일반 iOS 컴파일·실기기 서명/설치/실행·시뮬레이터/실기기 UI 테스트 완료.** `iosApp/`와 이 상태 파일을 공유한다. M1 카메라·센서와 M2 추론은 후속이다.
- **실제 공통 API 계약 커밋:** **없음**. `iosApp` M0만 생성했고 `posture-core`·`TrexPosture` 프레임워크·Swift 판정 엔진은 만들지 않았다.

이전 [Mac 상태 커밋 c4b255b](https://github.com/LeeDongHyun00/TREX/commit/c4b255bc01f3041e20e66c94a2c4df08eb5ecea0)은 Linux 호스트의 한계를 기록했다. 그 이력을 보존하면서 실제 Mac 관측값으로 현재 상태를 갱신한다. 이전 Linux의 Java 21.0.11을 이 Mac의 버전으로 사용하지 않는다.

## 1. 저장소 수신과 기존 작업 보존

기존 로컬 체크아웃은 `feature/posture-coach-reliability`, HEAD `5237ba9`이며 시작 시 추적/미추적 변경이 없었다. 로컬 upstream 대비 ahead/behind 표시도 없었다. 해당 체크아웃의 브랜치·파일·Git 설정을 변경하지 않고 **별도 새 폴더에 원격 Mac 브랜치를 clone**했다. 기존 원격 추적 참조가 최신이라는 판단은 하지 않았으며, 협업 브랜치 fetch는 새 clone에서만 실행했다.

`origin`이 `https://github.com/LeeDongHyun00/TREX.git`임을 확인하고 통합·Windows·Mac 브랜치를 fetch했다. `git merge --ff-only origin/codex/ios-kmp-mac`는 변경 없이 완료됐고, `git merge origin/codex/ios-posture-kmp`는 **37fd7d5 → 7eb1f40 fast-forward**로 완료됐다. 이전 Mac 환경/설계 커밋을 포함하며 충돌은 없었다. [Windows 상태](WINDOWS_STATUS.md)와 [Mac 착수 지시](MAC_IMPLEMENTATION.md)를 읽었고 P1 API는 아직 없다. 과거 로컬 대용량 파일 이력은 가져오거나 merge하지 않았다.

먼저 `AGENTS.md`, [인수인계](../POSTURE_HANDOFF.md), [ADR-056](../IOS_POSTURE_KMP_DESIGN.md), [협업 규약](../IOS_KMP_COLLABORATION.md), 양쪽 상태 문서를 읽었다. 공통 엔진·Swift 판정 엔진·루트 Gradle·Android 앱·규칙·모델은 수정하지 않았다. 이번 M0 결과는 Mac 작업 브랜치에만 공유하며, 통합 반영 시 PR base는 `codex/ios-posture-kmp`다.

## 2. 실제 Mac 환경 검사

**2026-09-13 연결 상태 갱신:** `devicectl`에서 iPhone 16 Plus의 유선 연결·페어링·개발자 모드 활성화·DDI 서비스 사용 가능을 확인했다. 실제 OS는 **iOS 27.0 Beta (24A5424a)**다. `adb devices -l`과 `getprop`에서는 **Galaxy Note10+ 5G (SM-N976N), Android 12 / API 31**이 USB `device` 상태이며 셸 응답이 정상임을 확인했다. 두 기기는 현재 Mac에 연결돼 있다. 후속 M0 앱의 실제 빌드·설치·실행 결과는 §6에 기록한다. Android 앱 설치/테스트는 이번에 실행하지 않았다.

아래 표는 **2026-09-12 23:47~23:51 KST의 환경 검사 이력**이다. iPhone 오프라인/OS 캐시 항목은 위 최신 조회로 대체한다. 개인 SDK 경로, 기기 UDID/일련번호, 계정·서명 정보는 공유 문서에 넣지 않는다.

| 검사 명령 / 항목 | 실제 결과 | 판단 범위 |
|---|---|---|
| `uname -sm` | Darwin `x86_64` | 실제 macOS 실행 호스트 |
| `sysctl -n hw.model machdep.cpu.brand_string` | `MacBookPro14,3`, Intel Core i7-7820HQ @ 2.90GHz | Apple Silicon이 아닌 Intel Mac |
| `sw_vers` | macOS **14.8.4**, build `23J319` | 현재 OS 관측값 |
| `xcode-select -p` | 설치된 Xcode 앱의 Developer 디렉터리 선택 | Command Line Tools만 선택된 상태가 아님 |
| `xcodebuild -version` | **Xcode 16.2**, build `16C5032a` | Xcode 실행 가능 |
| `xcodebuild -showsdks` | iOS / iOS Simulator SDK **18.2**, macOS SDK 15.2 | SDK 존재 확인이며 앱 빌드 성공은 아님 |
| `swift --version` | Apple Swift **6.0.3**, target `x86_64-apple-macosx14.0` | Swift 도구 실행 가능 |
| `java -version`, `/usr/libexec/java_home -V` | Temurin **21.0.10+7-LTS**, x86_64 JDK 1개 | 현재 선택 Java 확인 |
| `bash ./gradlew --offline --version` | **Gradle 8.13**, Launcher JVM 21.0.10, 종료 코드 0 | 기존 캐시로 Wrapper 실행 성공. 프로젝트 구성/컴파일은 실행하지 않음 |
| `pod --version` | **CocoaPods 1.16.2** | 기본 locale에서 UTF-8 경고 발생 |
| `LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 pod --version` | **1.16.2**, UTF-8 경고 없음 | 해당 명령에만 locale 적용, 셸 설정 파일 변경 없음 |
| `xcrun simctl list runtimes` | iOS 18.3 런타임, version **18.3.1**, build `22D8075` | SDK 18.2와 설치 런타임 버전은 별개 |
| `xcrun simctl list devices available` | 런타임 제목만 있고 **사용 가능한 기기 0개** | 부팅/실행 검증 전. 런타임 존재만으로 시뮬레이터 준비 완료가 아님 |
| `xcrun devicectl --timeout 15 list devices` | 등록된 **iPhone 16 Plus 1대**, 상태 `unavailable` | 현재 사용 가능한 실기기 연결 확인 실패 |
| `xcrun xctrace list devices` | 위 iPhone은 `Devices Offline`, 저장된 OS 표시 26.4.1 | 오프라인 캐시 정보이며 현재 OS/연결·개발자 모드/실행 가능 여부 미검증 |
| Android 보조 환경 | Android Studio·SDK 디렉터리 존재, 플랫폼 26/36, build-tools 35.0.0/36.1.0 | 설치 목록만 확인. 새 clone의 SDK 설정/Android 빌드는 미실행 |

초기 sandbox 안에서 CPU 조회가 `Operation not permitted`였고 Xcode 파일 감시/캐시 경고가 있었다. 호스트 접근 권한으로 하드웨어와 Xcode 기기 목록을 다시 읽어 위 결과를 얻었다. Intel에서 `hw.optional.arm64` 및 `sysctl.proc_translated` 키가 없다는 출력만으로 아키텍처를 판단하지 않고 CPU/model 값을 함께 확인했다. `xcodebuild -checkFirstLaunchStatus`도 실행했으나 독립 종료 코드를 수집하지 않아 초기 설정 완료 판정의 근거로 쓰지 않는다.

`gradlew`는 Git 모드 **100644**를 유지하고 협업 규약대로 `bash`로 실행했다. Wrapper 출력의 `Kotlin 2.0.21`은 Gradle 내장 Kotlin이고, 프로젝트 플러그인 버전 **2.3.21**과 다르다. 저장소의 AGP는 **8.13.2**, Android MediaPipe는 **0.10.14**, 앱 JVM target은 **17**이다. JDK 21에서 Wrapper가 실행됐다는 사실을 앱/KMP 빌드 성공으로 해석하지 않는다.

위 초기 환경 검사 당시에는 설치/계정 설정을 하지 않았다. **이번 M0에서는** 전용 시뮬레이터 생성·부팅과 앱 설치를 수행했고, 사용자가 Xcode 계정/팀 설정 및 iPhone 개발자 신뢰를 완료했다. 로컬 전용 xcconfig와 Xcode 자동 서명으로 빌드했다. OS·Xcode·Java·Pod 버전은 바꾸지 않았다.

## 3. 환경과 설계의 차이 — KMP/후속 검증의 미해결 항목

### Xcode 호환 기준과 Mac 모델

Kotlin 공식 호환 표에서 **2.3.20–2.3.21의 Xcode 기준은 26.0**이다. 현재 16.2는 그 조합과 다르므로 P1 Native 검증이 끝났다고 표시할 수 없다. 호환 표의 차이만으로 컴파일 실패가 실측됐다고도 쓰지 않는다. [Kotlin 호환 표](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

Apple의 Xcode 26 요구 OS는 **macOS Sequoia 15.6 이상**이다. `MacBookPro14,3`는 Apple 문서상 15형 2017 모델이며 공식 지원 최종 OS는 Ventura다. 따라서 이 모델에서 지원되는 OS 업데이트만으로 설계의 Xcode 26 환경을 갖출 수 있다고 가정할 수 없다. 현재 14.8.4가 동작하는 설치 경위는 조사하지 않았다. **지원되는 다른 Mac에서 Native 검증을 수행하는 방안을 우선 제안**한다. 기존 Mac/도구의 업그레이드나 프로젝트 Kotlin 하향은 이번 범위에서 결정하지 않았다. [Xcode 요구 사항](https://developer.apple.com/xcode/system-requirements), [Mac 모델 식별](https://support.apple.com/en-us/108052), [Sequoia 지원 모델](https://support.apple.com/en-us/120282)

### Intel 시뮬레이터 타깃

ADR-056의 초기 타깃 `iosArm64`는 실제 iPhone, `iosSimulatorArm64`는 Apple Silicon 시뮬레이터용이다. **현재 Intel Mac에서 KMP Native 시뮬레이터를 검증하려면 `iosX64`가 추가로 필요**하다. 이번 Swift 전용 M0는 해당 타깃 없이 x86_64 시뮬레이터에서 실행했다. Kotlin 2.3.20 안내에서도 `iosX64`는 tier 3로 유지된다. Apple Silicon 검증 Mac을 확보해 초기 타깃을 유지할지, Intel도 지원해 `iosX64`를 넣을지 Windows 통합 담당이 P1 타깃 설정에 반영해야 한다. 타깃 추가만으로 Kotlin/Xcode 호환 검증이 끝나지는 않는다. [Kotlin Native 타깃](https://kotlinlang.org/docs/native-target-support.html), [Kotlin 2.3.20 변경 사항](https://kotlinlang.org/docs/whatsnew2320.html)

### iPhone과 MediaPipe 연결

iPhone은 실제 iOS **27.0 Beta (24A5424a)**이며 현재 Xcode **16.2 / SDK 18.2**로 M0의 서명 빌드·설치·실행과 UI 테스트를 통과했다. 최초 계정/팀 누락과 개발자 신뢰 오류는 사용자 설정 후 해소됐다. 이번 M0에서는 Xcode/기기 OS 버전 차이 때문에 실패한 단계가 없다. 이 한 앱의 성공을 최신 SDK 지원, KMP/MediaPipe 호환, 안정 OS·최소 iOS 16 검증으로 확대하지 않는다. [Apple Xcode 요구 사항](https://developer.apple.com/xcode/system-requirements)

MediaPipe의 카메라 검증은 실제 기기에서 진행하고, 시뮬레이터의 계산/화면 검증과 구분한다. 공식 iOS 설치 경로는 CocoaPods이며 ADR-056처럼 **Swift 앱 타깃이 MediaPipe Pod를 소유**하는 구성을 유지할 수 있다. 이전 상태 문서의 CocoaPods/SPM 재선택 제안은 필수 선행 과제로 두지 않는다. 다만 실제 Pod 해석·버전 잠금·선택 아키텍처 링크는 아직 실행하지 않았으므로 Android와 같은 `0.10.14`를 iOS에 임의 확정하지 않는다. [MediaPipe iOS 설치](https://developers.google.com/edge/mediapipe/solutions/setup_ios)

## 4. ADR-056 / Native·Swift 연결 검토

계산·세션 상태를 KMP에 두고 카메라/MediaPipe/센서/TTS/저장을 플랫폼에 남기는 방향은 현재 코드 구조에 맞는다. 아래는 **설계 검토 의견**이며 공개 API나 구현 완료 목록이 아니다.

| 항목 | 코드/설계 대조와 Windows에 필요한 내용 |
|---|---|
| JVM 의존성 제거 | `PostureCore.kt`의 `Math.PI`, `PostureView.kt`/`PostureFloor.kt`의 각도 변환, `PostureRules.kt`의 `org.json`·Android 자산 로딩, `PostureComparison.kt`·`PostureSetLog.kt`의 Java 포맷/파일/날짜를 확인했다. 계산·문자열 인코더와 플랫폼 I/O를 분리하고 기존 숫자 반올림/결측/JSON 출력을 픽스처로 고정해야 한다. |
| 직렬 소유권 | `PostureComparison.kt`·`PostureCoach.kt`에 `@Synchronized`가 남아 있다. 삭제만 하지 말고 명령·프레임·tick·마감을 하나의 직렬 진입점으로 연결한다. Swift 콜백의 늦은 도착·중복 마감도 같은 기대 순서로 검증해야 한다. |
| Swift 표면과 단계 | P1에서는 실제 계산 DTO/API와 Native 테스트 진입점을 공유하고, P2에서 세션 facade·생성 헤더/Swift 호출 예제를 확정한다. `Long`/nullable·배열·컬렉션·enum·오류 반환이 생성된 Swift 표면에서 어떻게 보이는지 검증한다. 제출 시 복사 규약을 지켜 Swift 버퍼 재사용이 관측값을 바꾸지 않게 한다. |
| 시각·식별자 | ADR의 정확한 필드 `sampleTimeMs`, `sessionId`, `setToken`, `phaseEpoch`, `captureEpoch`를 계약으로 삼는다. 임의의 `timestampMs`/`finalizeToken`을 새 계약처럼 만들지 않는다. 0ms 유효값, 준비/일시정지 이전에 시작된 추론, capture 변경, 중복 명령의 기대 출력을 P0에 포함한다. |
| 좌표계 | OS가 디바이스→카메라/엔진 축의 중력을 정규화하고, 공통 변환기는 raw MP world의 `x*100, -y*100, -z*100`을 한 번 적용한다. normalized 2D는 회전 보정된 추론 이미지 기준이고 프리뷰 미러는 표시 단계다. 전후면·회전·종횡비·누락 관절·confidence 기본값을 어댑터 픽스처로 대조해야 한다. |
| 자산·Native 리소스 | 원본 자산은 `app/src/main/assets/posture/`에 유지하고 문자열/바이트 또는 얇은 플랫폼 로더로 전달한다. Native에서 JVM classpath 리소스 접근을 가정하지 않는다. EXCLUDE 보존·unknown kind 오류·동일 JSON 규칙 선택을 실제 자산으로 검사해야 한다. |
| 신뢰도 정책 차이 | `FloorFeedback.kt`에 참고 음성 경로가 있고, `PostureSetReport.kt`의 일반 `REFERENCE` 요약에도 beta 관측 발화가 있다. 바닥 temporal 세트 요약은 이미 화면 확인 문구라는 예외도 확인했다. 모든 경로를 현행 동일 동작으로 묶지 말고 ADR §12의 beta 음성 차단을 별도 기대값/정책 변경으로 다룬다. 이번에는 수정하지 않았다. |
| 검증 범위 | 기존 관절/피처 픽스처는 있으나 P0의 완전한 명령·음성·시간 이벤트 재생 API는 없다. 계산 동등성, 세션 동등성, 실제 카메라/TTS 검증을 분리한다. Native 컴파일이나 Android SHIP 등급으로 iOS 정확도/성능을 주장하지 않는다. |

## 5. 코드·자산 기준점

Git 객체를 비교해 `5237ba9`와 검사 시작 HEAD의 Android 앱/빌드 소스가 동일함을 확인했다. M0 착수 전에 `verify_ios_handoff.py` 기본/`--strict-bytes`를 각각 실행해 종료 코드 **0**, `passed=true`, **8/8 `byteIdentical=true`**를 확인했다. 정본은 [ASSET_BASELINE.json](ASSET_BASELINE.json)이며 raw SHA-256은 아래와 같다. 규칙 JSON은 헤더가 아닌 배열을 Python으로 집계했다: 서서 **141 = ship 51 / beta 20 / exclude 70**, 바닥 **16 = beta 14 / exclude 2**. `AGENTS.md`의 과거 바닥 14개 설명은 현재 배열 수와 다르며 ADR-056의 최신 수치와 일치한다.

다음 SHA-256은 이번 Mac 체크아웃에서 직접 계산했다. Windows는 P0 기준과 대조하고, 차이가 있으면 재생 비교 전에 자산/픽스처 버전을 먼저 맞춘다.

| 파일 | SHA-256 |
|---|---|
| `app/src/main/assets/posture/pose_landmarker_full.task` | `4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad` |
| `app/src/main/assets/posture/rules_mp_v0.json` | `e6968a7fe958a106df6885cf9cfaf58b6031cbb2ec8035eadfee75cdef526ce4` |
| `app/src/main/assets/posture/rules_floor_v0.json` | `3a23275d8a9bbd5174ec1c9e175323354826e68d90634b1714ea1d4e4e6ed21b` |
| `app/src/main/assets/posture/normal_pose_reference.tsv` | `c7a4498fc522d78a793a0df94461236f97fa4e45a4ae8f722684083fd235c7ac` |
| `app/src/test/resources/posture_port_fixture.txt` | `88f389686c038773cbf8dfa4c1489b231931f7573153b7b5e2ddf1e4d23556c8` |
| `app/src/test/resources/floor_port_fixture.txt` | `264d02d644fd2cb7b2808bbaa9f6a4036906c76f743215284cac15955f6e5d2b` |
| `app/src/test/resources/view_fixture.txt` | `9dc3dd1722361db47aeff89f576ebbb12554a41a3032520071a4e833f70389c1` |
| `app/src/test/resources/plank_replay_fixture.tsv` | `b9064630afa2e27a9873558da7c9ca4154357b803e1dfe9bbca4bec2f5ec88af` |

이는 체크아웃의 파일 동일성 확인이며 해당 픽스처를 사용하는 JVM/Native 테스트 실행 결과가 아니다. M0 번들에는 모델·규칙·정상 표본이 없으므로 **설치 번들의 자산 해시 검사는 해당 없음**이다. M2에서 실제 복사된 번들의 해시를 추가로 검사해야 한다.

## 6. M0 구현과 실제 검증 결과

[앱 README](../../iosApp/README.md)에 프로젝트, 로컬 서명 설정, 단계별 재현 명령을 기록했다. 공유 프로젝트 `TrexPostureDiagnostics.xcodeproj`와 scheme `TrexPostureDiagnostics`를 clone 후 바로 열 수 있다. 개발 최소 OS는 iOS 16.0, 앱 버전은 0.1.0 (1)이다. UI 테스트를 포함하며 XcodeGen/Pod/KMP 설치를 요구하지 않는다.

화면은 **평가 엔진 연결 전**을 유지하고 실행 환경 및 화면 응답 확인만 제공한다. 응답 카운트는 버튼 조작 횟수다. ABSTAIN·각도·운동 횟수·점수·기준선·교정·기록을 생성하지 않는다. 카메라 목적 문구는 한국어로 넣었으나 M0에서 권한 요청/촬영은 하지 않고 마이크 권한도 없다.

### 명령별 결과 — 2026-09-13 00:30~00:45 KST

공통 빌드 인자는 `-project iosApp/TrexPostureDiagnostics.xcodeproj -scheme TrexPostureDiagnostics -configuration Debug`다. 아래 기기 ID는 로컬 값을 치환했으며 공유하지 않는다. DerivedData와 xcresult는 checkout 밖 로컬 검증 폴더에 보존했다. 재현 시 README의 `iosApp/build/` 경로를 사용할 수 있다.

| 구분 | 실제 명령 / 대상 | 종료 코드와 결과 | 검증 범위 |
|---|---|---|---|
| 자산 기본 검사 | `python3 research/aihub_fitness/verify_ios_handoff.py` | **0**, 8/8 통과 | 체크아웃 내용/규칙 분포 |
| 자산 strict 검사 | 위 명령에 `--strict-bytes` | **0**, 8/8 바이트 일치 | 체크아웃 raw SHA-256 |
| 일반 iOS 컴파일 | `xcodebuild … -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` | **0 / BUILD SUCCEEDED** | SDK 18.2, arm64, 서명 없는 앱 |
| 최초 서명 빌드 | 일반 iOS 대상, 팀 설정 전 `xcodebuild … build` | **65**, `requires a development team` | 계정/팀/인증서/프로파일 준비 전 실패 이력 |
| 설정 후 실기기 서명 빌드 | `xcodebuild … -destination 'id=<iPhone ID>' -xcconfig iosApp/Signing.local.xcconfig -allowProvisioningUpdates -allowProvisioningDeviceRegistration build` | **0 / BUILD SUCCEEDED** | 연결 iPhone 대상, 로컬 Personal Team 자동 서명 |
| 산출물 서명 확인 | 호스트에서 `codesign --verify --deep --strict <signed.app>` | **0** | 서명 산출물 검증. sandbox에서의 `CSSMERR_TP_NOT_TRUSTED`는 호스트 접근 후 재검사로 구분 |
| iPhone 설치 | `xcrun devicectl device install app --device <iPhone ID> <signed.app>` | **0** | 별도 bundle ID `com.leedonghyun.trex.posturediagnostics` 설치 |
| iPhone 첫 실행 | `xcrun devicectl device process launch --device <iPhone ID> com.leedonghyun.trex.posturediagnostics` | **1**, CoreDeviceError 10002 / Security | 서명·권한·프로파일 신뢰 관련 오류. 이후 사용자 개발자 신뢰로 해소 |
| iPhone 재실행 | 개발자 신뢰 완료 후 동일 launch 명령 | **0** | 프로세스 실행 성공. 사용자가 화면 열림도 확인 |
| iPhone 화면/조작 검사 | 같은 서명 인자로 `xcodebuild … -destination 'id=<iPhone ID>' -parallel-testing-enabled NO test` | **0 / TEST SUCCEEDED**, 1건, 실패 0, 6.606초 | iPhone 16 Plus / iOS 27.0 Beta: 실제 문구·버튼 응답·스크린샷 확인 |
| 시뮬레이터 준비 | `simctl create`, `boot`, `bootstatus -b` | 각각 **0** | 전용 `TREX M0 iPhone 16 Plus`, iOS 18.3 런타임 / 실제 18.3.1 (22D8075) |
| 시뮬레이터 빌드/화면 검사 | `xcodebuild … -destination 'platform=iOS Simulator,id=<SIM ID>' -parallel-testing-enabled NO test` | **0 / TEST SUCCEEDED**, 1건, 실패 0, 15.426초 | Intel x86_64 앱 컴파일·실행·문구·버튼 응답 |
| 시뮬레이터 별도 설치 | `xcrun simctl install <SIM ID> <simulator.app>` | **0** | 설치 단계 별도 성공 |
| 시뮬레이터 별도 실행 | `xcrun simctl launch <SIM ID> com.leedonghyun.trex.posturediagnostics` | **0**, 프로세스 반환 | 실행 및 화면 캡처로 레이아웃 확인 |

두 UI 테스트 모두 `평가 엔진 연결 전` → 화면 응답 버튼 터치 → `응답 확인 1회` → 엔진 여전히 미연결을 검증했다. xcresult에 화면 캡처를 남겼다. 이 결과는 실제 카메라 촬영·33점 추론·평가 정확도·KMP 엔진 동등성을 검증하지 않는다.

첫 실행 오류 때문에 기존 앱 삭제나 기기 초기화를 하지 않았다. 개인 Team ID·계정·UDID·프로파일/키·로컬 경로·xcresult 원문은 Git에서 제외했다. 프로젝트/Info.plist/scheme 파싱, ignore 동작, 문서 링크, 개인정보 제외와 `git diff --check`를 검사했다. 기존 체크아웃은 `feature/posture-coach-reliability` / `5237ba9`와 깨끗한 작업 트리를 유지한다.

## 7. 미실행 검사와 다음 인수인계

| 미실행 항목 | 이유 / 다음 단계 |
|---|---|
| M1 카메라·Core Motion | 이번 커밋은 M0만 구현했다. 다음에 권한 허용/거부·복귀·전후면·회전·중력 출처·연속 시작/정지를 확인한다. |
| M2 MediaPipe·모델 번들 검사 | 추론 타깃/Podfile/lock/모델 복사는 아직 없다. M0 타깃은 Pod/KMP 없이 유지하고 별도 `TrexPostureInference` 타깃에서 진행한다. |
| P1 Kotlin/Native 계산·framework·M3 연결 | Windows의 실제 공개 API 커밋이 없다. SHA·생성 헤더·Native 태스크·픽스처 로더·허용 오차를 받아 진행한다. Intel/Xcode 제약은 §3대로 별도 검증한다. |
| P2 세션·음성/저장 이벤트·동등성 | 세션 facade가 아직 없다. Swift에 대체 엔진을 만들지 않는다. |
| Android 단위 테스트·APK 설치·계측 | Android 소스를 수정하지 않았다. 연결 Note10+에는 이번 앱 설치/삭제를 하지 않았다. Windows의 P0 소스 SHA·명령·산출물 해시를 받은 뒤 실행/회수한다. |
| iOS 16/안정 OS/iPad/Release·배포·20분 지속 검사 | 이번 검증 대상은 Debug M0, 시뮬레이터 18.3.1 및 iPhone 27.0 베타 한 대다. 최소 지원/전체 기기/출시 검증이 아니다. |

Windows는 이 Mac 브랜치의 M0 변경을 `codex/ios-posture-kmp` 기준으로 검토·통합할 수 있다. ADR·정본 스펙·인수인계의 ‘iOS 앱 미구현’ 상태는 통합 담당이 M0 범위에 맞춰 갱신한다. Mac은 M1/M2를 별도 작업 단위로 진행하고, P1/P2가 공개될 때 정확한 계약 SHA를 받아 연결한다. 공통 엔진·Android 앱·규칙·모델·루트 Gradle은 이번 변경에 포함하지 않는다.
