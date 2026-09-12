# Mac 작업 상태

- **갱신일:** 2026-09-13 (KST), M2 관절 추론 구현·검증 결과 반영
- **담당:** Mac Codex
- **작업 브랜치:** `codex/ios-kmp-mac`
- **통합 브랜치:** `codex/ios-posture-kmp`
- **가져온 통합 기준:** `145da7cb1039d860bf5ed133ce046383091a3a31` — 사용자 승인으로 M1 PR #2 검토·merge 후 수신
- **확인한 Windows HEAD:** `7eb1f40aeed3f0bd4f41803955c7bfd5023e7978`
- **이번 M2 시작 Mac HEAD:** `9152f3373c1940aa5c9f1f4c55af6b8a4f0a982b`
- **M2 검증 소스 커밋:** [`3efba9b97c17ff5deea854d41a6dea711e7bef9e`](https://github.com/LeeDongHyun00/TREX/commit/3efba9b97c17ff5deea854d41a6dea711e7bef9e)
- **M1 검증 소스 커밋:** [`80209a32eea8ec940d76983e11da0c803c117f9e`](https://github.com/LeeDongHyun00/TREX/commit/80209a32eea8ec940d76983e11da0c803c117f9e)
- **M0 소스 커밋:** [`bee02682d285da8b9dfb3b750b11a9453f08c851`](https://github.com/LeeDongHyun00/TREX/commit/bee02682d285da8b9dfb3b750b11a9453f08c851). 실기기 UI 테스트는 이 소스에서 실행했다. 앞선 일반 iOS/시뮬레이터 검사와의 소스 차이는 파일 끝 빈 줄 정리뿐이며 기능 변경은 없다.
- **Android/자산 기준:** `5237ba9d07662849fc2b23853672049ff2a950af`. `app/`, `gradle/`, 루트 빌드 파일과 Wrapper는 변경하지 않았다.
- **현재 단계 / 담당 파일:** **M2 관절 추론 구현·실기기 4종 검사 및 시뮬레이터 검사 완료.** 실기기는 종합 3건 통과 후 준비된 촬영 조건에서 33점 1건을 별도 재검증했다. 실제 범위와 미완료는 §9. `iosApp/`와 이 상태 파일을 공유한다. M3 공통 연결은 후속이다.
- **실제 공통 API 계약 커밋:** **없음**. `iosApp` M0/M1/M2를 구현했고 `posture-core`·`TrexPosture` 프레임워크·Swift 판정 엔진은 만들지 않았다.

이전 [Mac 상태 커밋 c4b255b](https://github.com/LeeDongHyun00/TREX/commit/c4b255bc01f3041e20e66c94a2c4df08eb5ecea0)은 Linux 호스트의 한계를 기록했다. 그 이력을 보존하면서 실제 Mac 관측값으로 현재 상태를 갱신한다. 이전 Linux의 Java 21.0.11을 이 Mac의 버전으로 사용하지 않는다.

## 1. 저장소 수신과 기존 작업 보존

기존 로컬 체크아웃은 `feature/posture-coach-reliability`, HEAD `5237ba9`이며 시작 시 추적/미추적 변경이 없었다. 로컬 upstream 대비 ahead/behind 표시도 없었다. 해당 체크아웃의 브랜치·파일·Git 설정을 변경하지 않고 **별도 새 폴더에 원격 Mac 브랜치를 clone**했다. 기존 원격 추적 참조가 최신이라는 판단은 하지 않았으며, 협업 브랜치 fetch는 새 clone에서만 실행했다.

`origin`이 `https://github.com/LeeDongHyun00/TREX.git`임을 확인하고 통합·Windows·Mac 브랜치를 fetch했다. `git merge --ff-only origin/codex/ios-kmp-mac`는 변경 없이 완료됐고, `git merge origin/codex/ios-posture-kmp`는 **37fd7d5 → 7eb1f40 fast-forward**로 완료됐다. 이전 Mac 환경/설계 커밋을 포함하며 충돌은 없었다. [Windows 상태](WINDOWS_STATUS.md)와 [Mac 착수 지시](MAC_IMPLEMENTATION.md)를 읽었고 P1 API는 아직 없다. 과거 로컬 대용량 파일 이력은 가져오거나 merge하지 않았다.

먼저 `AGENTS.md`, [인수인계](../POSTURE_HANDOFF.md), [ADR-056](../IOS_POSTURE_KMP_DESIGN.md), [협업 규약](../IOS_KMP_COLLABORATION.md), 양쪽 상태 문서를 읽었다. 공통 엔진·Swift 판정 엔진·루트 Gradle·Android 앱·규칙·모델은 수정하지 않았다. 앱 작업은 Mac 작업 브랜치에 공유하며, 통합 반영 시 PR base는 `codex/ios-posture-kmp`다.

**M1 시작 시 추가 처리:** M0 [PR #1](https://github.com/LeeDongHyun00/TREX/pull/1)의 변경 파일 9개·소스 HEAD `37ad9bf`·실제 검사 결과를 검토했다. 사용자가 Mac에서의 통합을 명시적으로 승인해 merge commit `adbe91d`로 통합했다. 이 승인에 한해 Mac이 M0 통합을 수행했으며 Windows 공통 엔진/정본 소유권은 바꾸지 않는다. 로컬 M1 작업을 보존한 채 동일 트리의 통합 커밋을 fast-forward로 받았다. M1은 별도 커밋/PR로 공유하며 자동 통합하지 않는다.

단일 브랜치 clone은 기본 fetch가 Mac 참조만 갱신하므로 통합·Windows는 아래처럼 명시적으로 수신했다. 기본 fetch 성공을 모든 협업 참조의 최신 상태로 해석하지 않는다.

```bash
git fetch origin refs/heads/codex/ios-posture-kmp:refs/remotes/origin/codex/ios-posture-kmp \
  refs/heads/codex/ios-kmp-windows:refs/remotes/origin/codex/ios-kmp-windows
git merge --ff-only origin/codex/ios-posture-kmp
```

## 2. 실제 Mac 환경 검사

**2026-09-13 연결 상태 갱신:** `devicectl`에서 iPhone 16 Plus의 유선 연결·페어링·개발자 모드 활성화·DDI 서비스 사용 가능을 확인했다. 실제 OS는 **iOS 27.0 Beta (24A5424a)**다. `adb devices -l`과 `getprop`에서는 **Galaxy Note10+ 5G (SM-N976N), Android 12 / API 31**이 USB `device` 상태이며 셸 응답이 정상임을 확인했다. 두 기기는 현재 Mac에 연결돼 있다. M0 결과는 §6, M1은 §8, M2는 §9에 기록한다. Android 앱 설치/테스트는 이번에 실행하지 않았다.

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

이는 체크아웃의 파일 동일성 확인이며 해당 픽스처를 사용하는 JVM/Native 테스트 실행 결과가 아니다. M0 번들에는 모델·규칙·정상 표본이 없으므로 **설치 번들의 자산 해시 검사는 해당 없음**이다. M2의 실제 복사 번들 검사는 §9에 별도로 기록했다.

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
| M1 추가 조건 | 핵심 실기기 검사는 §8에서 통과했다. 제한된 권한(restricted), 다른 앱의 카메라 점유, 센서 자체 오류/부재, 강제 런타임 오류의 실기기 재현은 아직 없다. |
| M2 후속 검증 | §9의 실제 결과와 제한을 따른다. 고정 표적 좌표/미러의 물리 검증, iOS 16/안정 OS, 20분 지속 검사는 별도다. |
| P1 Kotlin/Native 계산·framework·M3 연결 | Windows의 실제 공개 API 커밋이 없다. SHA·생성 헤더·Native 태스크·픽스처 로더·허용 오차를 받아 진행한다. Intel/Xcode 제약은 §3대로 별도 검증한다. |
| P2 세션·음성/저장 이벤트·동등성 | 세션 facade가 아직 없다. Swift에 대체 엔진을 만들지 않는다. |
| Android 단위 테스트·APK 설치·계측 | Android 소스를 수정하지 않았다. 연결 Note10+에는 이번 앱 설치/삭제를 하지 않았다. Windows의 P0 소스 SHA·명령·산출물 해시를 받은 뒤 실행/회수한다. |
| iOS 16/안정 OS/iPad/Release·배포·20분 지속 검사 | 이번 검증 대상은 Debug M0/M1/M2, 시뮬레이터 18.3.1 및 iPhone 27.0 베타 한 대다. 최소 지원/전체 기기/출시 검증이 아니다. |

M0와 M1은 각각 사용자 승인에 따라 통합됐다. Windows는 이 Mac 브랜치의 M2 변경을 `codex/ios-posture-kmp` 기준으로 검토할 수 있다. ADR·정본 스펙·인수인계의 ‘iOS 앱 미구현’ 상태는 통합 담당이 구현 범위에 맞춰 갱신한다. Mac은 M2를 별도 작업 단위로 공유하고, P1/P2가 공개될 때 정확한 계약 SHA를 받아 연결한다. 공통 엔진·Android 앱·규칙·모델·루트 Gradle은 이번 변경에 포함하지 않는다.

## 8. M1 카메라·센서 구현과 실제 검증

**소스:** `80209a32eea8ec940d76983e11da0c803c117f9e`. 사용 환경은 Xcode 16.2 / SDK 18.2 / Intel Mac, iPhone 16 Plus iOS 27.0 Beta (24A5424a), 시뮬레이터 iOS 18.3.1이다. M0 프로젝트/scheme/bundle ID를 유지하며 별도 진단 화면으로 진입한다. [README의 M1 사용/재현 방법](../../iosApp/README.md#m1-사용과-소유권)을 따른다.

### 구현 범위

- 명시적 시작·정지, 카메라 권한 허용/거부/제한, 카메라 부재와 실행 오류 구분, 전후면 전환, 화면 방향 추적, 비율을 유지한 프리뷰.
- 캡처 포맷과 실제 회전된 출력 버퍼 크기를 구분하고 프리뷰에만 전면 미러를 적용한다. 정지 때 프리뷰의 마지막 이미지를 숨기고 프레임/중력을 비운다.
- `StateObject`가 촬영 소유 객체를 유지한다. 세션·영상·Core Motion은 직렬 큐에서 처리하고, 세대가 지난 콜백을 폐기한다. 영상 메타데이터는 300ms 간격, UI는 최신 스냅샷 1개로 합친다. 비동기 영상 버퍼 저장/대기열은 없다.
- 화면 이탈 때 종료, 앱 비활성/백그라운드 때 일시 정지, 시작 의도가 남은 상태에서 복귀하면 재개한다. 화면을 나갔다 재진입하면 정지 상태로 시작한다.
- 프레임 ID/단조 승인 시각과 별도 카메라 PTS, Core Motion raw gravity와 센서 시각/출처를 표시한다. 엔진 축의 `upSource`/`upInEngineWorld`는 **미연결·미검증**이다. 카메라/센서 목적은 한국어로 명시하며 마이크를 사용하지 않는다.

### 실제 명령과 결과 — 2026-09-13

`xcodebuild` 공통 프로젝트/scheme 인자는 §6과 같다. 실제 기기 ID와 서명 설정은 로컬에서만 사용했다. 빌드/xcresult/프리뷰 캡처는 checkout 밖 로컬 검증 폴더에 보존했다.

| 구분 | 실제 명령/대상 | 최종 결과 | 판정 범위 |
|---|---|---|---|
| 자산 strict | `python3 research/aihub_fitness/verify_ios_handoff.py --strict-bytes` | **0**, 8/8 바이트 일치 | 원본 모델/규칙/픽스처 보존. M1 번들에는 해당 자산이 없어 번들 해시는 해당 없음 |
| 순수 촬영 세대 검사 | `swiftc …/CaptureGate.swift iosApp/Tests/main.swift -o <검사 파일>` 후 실행 | 컴파일/실행 각각 **0** | 합성 0ms·300ms 경계, 중복/역행, 늦은 세대, 100회 재시작. 하드웨어 100회 테스트가 아님 |
| 일반 iOS 빌드 | `xcodebuild … -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` | **0 / BUILD SUCCEEDED** | 최종 M1 소스, arm64, 서명 없는 컴파일 |
| 실기기 빌드·UI 테스트 | `xcodebuild … -destination 'id=<iPhone ID>' -xcconfig iosApp/Signing.local.xcconfig -allowProvisioningUpdates -allowProvisioningDeviceRegistration -parallel-testing-enabled NO test` | **0 / TEST SUCCEEDED**, **3건 통과·1건 skip·실패 0** (최종 110.873초) | 자동 서명·테스트 앱 설치/실행, M0 회귀, 권한 거부, 실제 카메라/센서 lifecycle. 시뮬레이터 전용 검사는 skip |
| iPhone 별도 설치 | `xcrun devicectl device install app --device <iPhone ID> <signed.app>` | **0** | 최종 앱 설치 |
| iPhone 별도 실행 | `xcrun devicectl device process launch --device <iPhone ID> com.leedonghyun.trex.posturediagnostics` | **0** | 최종 앱 홈 실행 |
| 시뮬레이터 빌드·UI 테스트 | `xcodebuild … -destination 'platform=iOS Simulator,id=<SIM ID>' -parallel-testing-enabled NO test` | **0 / TEST SUCCEEDED**, **3건 통과·1건 skip·실패 0** (37.420초) | M0 회귀, 권한 거부, 카메라 부재. 실제 카메라·센서 검사는 skip |
| 시뮬레이터 별도 설치/실행 | `xcrun simctl install …` / `xcrun simctl launch …` | 각각 **0** | 설치/프로세스 실행 별도 확인 |
| 프리뷰 표시 | iPhone UI 테스트의 로컬 전면 화면 캡처 직접 확인 | 실제 영상 표시 확인 | 캡처 포맷 1280×720 / 세로 출력 720×1280. 좌표/미러의 고정 표적 동등성 검사는 아님 |

실기기 lifecycle 테스트는 **허용 후 프레임·중력 수신 → 후면/전면/후면 전환 → 가로·세로 출력 버퍼 크기 → 5회 정지/재시작 → 백그라운드 2초 후 재개와 epoch 증가 → 화면 이탈/재진입 시 정지 → 재시작 → 엔진 미연결 유지**를 확인했다. 정지 뒤 지연 콜백이 프레임 표시를 다시 채우지 않는지도 검사했다. 중력 수신은 실제 관측이며 축 변환·센서 정확도 검증이 아니다.

### 실패 이력과 해결

1. 첫 iPhone 검사는 3건 통과·1건 skip이었다. 첫 시뮬레이터 검사는 종료 코드 **65**, 2건 실패: 촬영 시작 전 `AVCaptureSession` 오류 알림이 초기 정지 상태를 `error`로 바꾸었다. xcresult 접근성 기록으로 상태를 확인했고 **실행 중 세션의 알림만 처리**하도록 수정했다. 수정 후 시뮬레이터 3건 통과·1건 skip.
2. 수정 후 iPhone 재검사에서 종료 코드 **65**, 전면→후면 전환 직후 테스트가 비동기 변경 완료 전에 한 번만 읽어 1건 실패했다. 전후면 상태의 실제 완료를 기다린 뒤 실행 상태·프레임을 확인하도록 테스트를 보완했다. 최종 전체 실기기 검사에서 3건 통과·1건 skip. 실패를 성공 집계에 섞지 않았다.
3. SDK 18.2는 iOS 16 호환용 `AVCaptureVideoOrientation`의 iOS 17 이후 사용 중단 예정 경고를 출력한다. 현재 컴파일/실기기 검사는 통과했으며 API 전환과 실제 추론 좌표 대조는 M2에서 별도 검토한다.

공유 전 프로젝트 의미/파싱·scheme·한국어 권한/마이크 미사용·개인 설정 ignore·문서 링크·`git diff --check`를 확인했다. 기존 체크아웃은 `feature/posture-coach-reliability` / `5237ba9`와 깨끗한 작업 트리를 유지한다. Android 소스/설치 데이터, 공통 엔진, 원본 자산, 루트 Gradle은 변경하지 않았다. 실제 프리뷰/테스트 화면 녹화, 중력 샘플, 기기 식별자, 서명키·프로파일은 Git에 올리지 않는다.

**M1 완료 당시의 다음 단계는 M2였다(현재 결과는 §9).** 별도 `TrexPostureInference` 타깃과 MediaPipe Pod 버전 잠금·Full 모델 번들 해시를 확인하고, VIDEO 모드 33점/미검출/오류·직렬 추론·늦은 결과 폐기를 검증한다. M1 시점에는 관절 추론·각도·판정·횟수·점수·교정·KMP 연결을 구현하지 않았다. M1 성공은 P0/P1/P2 완료나 iOS 정확도/출시 보장이 아니다.

## 9. M2 관절 추론 구현과 검증

### 통합·구현 범위

사용자가 **M1 검토 후 통합**을 명시적으로 승인했다. [M1 PR #2](https://github.com/LeeDongHyun00/TREX/pull/2)의 10개 변경 파일, HEAD `9152f3373c1940aa5c9f1f4c55af6b8a4f0a982b`, M1 실제 검사 결과와 merge 가능 상태를 검토한 뒤 해당 HEAD를 지정해 merge했다. 별도 GitHub CI는 없었다. 결과 `145da7cb1039d860bf5ed133ce046383091a3a31`을 로컬 M2 변경을 보존한 채 fast-forward로 받았다. M2도 별도 커밋/초안 PR로 공유하며 자동 통합하지 않는다.

별도 `TrexPostureInference` 앱/scheme, `TrexPostureInferenceUITests`, Podfile/lock/workspace를 추가했다. 기존 `TrexPostureDiagnostics`의 소스/리소스/링크/빌드 설정 객체는 의미 비교에서 동일하며 Pod 의존성이 없다. 공유 캡처 객체에 선택적 프레임/활동 소비자를 추가하고 화면 방향 읽기만 공유한다. Xcodeproj/CocoaPods 저장 형식 때문에 프로젝트 파일의 주석/정렬 diff가 커졌지만 기존 타깃의 설정을 변경하지 않았다.

M2는 Full 모델로 VIDEO/CPU/1명, detection·presence·tracking 각 0.5, segmentation false를 사용한다. 실제 normalized/world 각각 33점의 ID·raw 좌표·optional visibility/presence를 복사하며 world는 원본 미터다. Swift cm·부호·confidence 결합·중력 엔진 축 변환은 없다. 승인 단조 ms를 VIDEO 시각으로 쓰고 카메라 PTS·완료 시각을 구분한다. 진단 세션만 있으며 phaseEpoch=0, setToken=nil이다.

추론은 직렬 1건, 대기 버퍼는 최신 1건으로 제한한다. 시작/정지·전후면·회전·백그라운드 경계에서 세대를 바꾸며 지난 세대의 완료는 버린다. 표시 이미지는 실제 분석한 버퍼로 만들어 관절과 함께 비율/전면 미러를 적용한다. 원본 좌표는 미러하지 않는다. 카메라 영상·관절·중력을 파일에 저장하지 않는다. 합성 검정 프레임 및 잘못된 모델 경로 검사는 합성 출처를 화면에 명시하며 실제 SDK를 호출한다.

모든 상태에서 **평가 엔진 연결 전**을 유지한다. 검출은 관절 반환이며 정상 자세 판정이 아니다. Swift 판정 엔진·각도·규칙·운동 횟수·점수·기준선·교정·KMP/Gradle/Android 변경은 없다.

### SDK·자산

`pod trunk info`와 실제 spec/설치 결과로 **MediaPipeTasksVision 1.0.0 / MediaPipeTasksCommon 1.0.0**을 고정했다. CocoaPods 1.16.2이며 lock의 spec checksum은 Vision `2a0e74fd13f7a5df9eae3716b21e459806c65ce9`, Common `520bf860a59a3425471fdd516d82263d2ced5700`이다. 두 내려받은 XCFramework의 Info.plist에 **ios-arm64 및 ios-arm64_x86_64-simulator**가 들어 있었다. Pod 버전 하향/바이너리 패치/post_install 우회는 없다.

원본 8개 자산/픽스처 strict 검사와 실제 앱 번들 4개 파일 검사에서 §5와 같은 raw SHA-256을 확인했다. `iosApp/verify_bundle_assets.py`는 빌드된 앱 파일과 번들 기준표를 저장소 정본과 대조한다. 앱의 `BundleAssets`도 모델 생성 전에 자기 번들 4개 SHA-256을 검사하며 불일치 시 추론 오류로 처리한다. 규칙/정상 표본은 해시만 확인하며 Swift로 파싱/평가하지 않는다.

### 실제 명령과 결과 — 2026-09-13

공통 인자는 `-workspace iosApp/TrexPostureDiagnostics.xcworkspace -scheme TrexPostureInference -configuration Debug`다. 도구는 Xcode 16.2 / SDK 18.2 / Intel Mac, 실기기는 iPhone 16 Plus iOS 27.0 Beta (24A5424a), 시뮬레이터는 iOS 18.3.1이다. 개인 서명/기기 ID, DerivedData·xcresult·화면 첨부는 로컬에만 보존한다. 재현은 [README의 M2 명령](../../iosApp/README.md#m2-관절-추론-앱)을 따른다.

| 구분 | 실제 명령/대상 | 결과와 범위 |
|---|---|---|
| SDK 설치 | `LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 pod install` | **0**, Vision/Common 1.0.0 설치·lock 생성 |
| 원본 자산 strict | `python3 research/aihub_fitness/verify_ios_handoff.py --strict-bytes` | **0**, 8/8 바이트 일치 |
| 번들 자산 | `python3 iosApp/verify_bundle_assets.py <M2.app>` | **0**, arm64/x86_64 빌드 번들 4/4 SHA 및 기준표 바이트 일치 |
| 순수 계약 검사 | `swiftc …/LatestOnlyScheduler.swift …/OverlayProjection.swift iosApp/Tests/M2/main.swift …` 후 실행 | 각각 **0**, 실행/대기 상한·최신 교체·중복/늦은 완료·100회 합성 재설정·비율/미러/비정상 크기 검사 |
| M1 순수 회귀 | `swiftc …/CaptureGate.swift iosApp/Tests/main.swift …` 후 실행 | 각각 **0**, 0ms/300ms·역행/중복·늦은 촬영 세대·100회 합성 재시작 |
| M2 일반 iOS 컴파일 | `xcodebuild … -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` | **0 / BUILD SUCCEEDED**, 최종 arm64 소스. 설치/실행 검증과 구분 |
| M2 산출물 서명 | `codesign --verify --deep --strict <signed M2.app>` | **0**, 최종 자동 서명 산출물 |
| M2 iPhone 빌드·UI 검사 | `xcodebuild … -destination 'id=<iPhone ID>' -xcconfig iosApp/Signing.local.xcconfig -allowProvisioningUpdates -allowProvisioningDeviceRegistration -parallel-testing-enabled NO test` | 종합 실행 **65**, **3건 통과·1건 실패**, 252.125초. 카메라 lifecycle/지연 폐기/SDK 합성 검사는 통과. 33점 조건 미충족은 아래 단독 재검사로 별도 확인 |
| 실제 iPhone 33점 검출 | 같은 대상의 `testDeviceDetects33RawLandmarks` | 사용자 촬영 준비 후 최종 소스에서 `-only-testing:TrexPostureInferenceUITests/InferenceDiagnosticsTests/testDeviceDetects33RawLandmarks test`: **0 / TEST SUCCEEDED**, **1건 통과·실패 0**. 실제 카메라 normalized/world 33/33. 앞선 두 실행도 통과(14.290초, 14.376초)했고 분석 이미지 위 오버레이를 직접 확인. 종합 실행의 45초 미충족 이력과 구분 |
| M2 시뮬레이터 빌드·UI 검사 | `xcodebuild … -destination 'platform=iOS Simulator,id=<SIM ID>' -parallel-testing-enabled NO test` | **0 / TEST SUCCEEDED**, **1건 통과·3건 skip·실패 0**, 113.777초. 실제 x86_64 SDK의 합성 미검출·오류·복구. 세 실카메라 검사는 skip |
| iPhone 별도 설치 | `xcrun devicectl device install app --device <iPhone ID> <signed M2.app>` | **0**, 별도 bundle ID로 설치. 기존 M0/M1 앱 보존 |
| iPhone 별도 실행 | `xcrun devicectl device process launch --device <iPhone ID> com.leedonghyun.trex.postureinference` | **0**, 최종 앱 프로세스 실행 |
| 시뮬레이터 별도 설치·실행 | `xcrun simctl install …` 후 `simctl launch … com.leedonghyun.trex.postureinference` | 각각 **0**, 설치 후 프로세스 실행 |
| M0/M1 Pod 독립성 | Pods/개인 설정 없는 임시 복사본의 `.xcodeproj`, `TrexPostureDiagnostics`, 일반 iOS unsigned build | **0 / BUILD SUCCEEDED**, 이전 타깃의 실제 독립 빌드 |
| M0/M1 시뮬레이터 회귀 | 기존 `.xcodeproj`/scheme으로 `xcodebuild … test` | **0 / TEST SUCCEEDED**, **3건 통과·1건 skip·실패 0**, 38.128초. M0 화면·권한 거부·카메라 부재 |

실기기 카메라 lifecycle은 전면 촬영 → 자산 4/4 → 시각 증가 → 가로/세로 실제 버퍼 크기와 epoch 증가 → 후면 전환 → 백그라운드 2초/복귀 → 정지를 확인했다. 별도 지연 검사는 카메라 시작 전 스위치 value=1을 확인한 뒤 실행/대기=1/1, 최대 대기 1, 정지 후 폐기 증가와 0/0 큐·0/0 관절, 재시작을 확인했다. SDK 합성 검사는 검정 프레임 미검출·증가하는 VIDEO 시각·잘못된 모델 경로 오류·다시 검정 프레임 복구를 검사했다. 모두 평가 엔진 미연결을 유지했다.

### 실패 이력과 한계

- 초기 UI 검사 빌드는 종료 코드 65: 새 UI 테스트 타깃의 PRODUCT_NAME 누락으로 `-Runner.app/PlugIns/.xctest` 출력이 충돌했다. 앱 단독 빌드 성공과 구분한다. PRODUCT_NAME을 TARGET_NAME으로 지정해 해결했다. 수정 도구의 UTF-8 locale 누락으로 한 번 재적용이 실패한 뒤 파일 수정으로 반영했다.
- 첫 iPhone UI 실행은 무료 개발 프로파일의 설치 앱 3개 제한으로 M2 앱 설치 전에 실패했고 검사 실행을 중단했다(75). 별도 실행 시도도 `application … is not installed`였다. 기존 M0/M1 **XCTest 러너만** 제거해 슬롯을 확보했다. 사용자용 TREX 진단 앱과 데이터는 삭제하지 않았다. 이후 M2 앱/러너 설치와 실제 검사를 진행했다.
- 첫 설치 성공 후 iPhone 전체 검사는 2건 통과·1건 실패(65)였다. 실제 33점/SDK 합성 검사는 통과했지만 지연 후 폐기 검사가 실패했다. 로컬 XCTest 녹화에서 스위치가 꺼져 있음을 확인했다. 테스트가 SwiftUI Switch 컨테이너를 탭한 것만으로 지연 활성화를 가정한 문제였다. 실제 스위치를 누르고 value=1 및 실행/대기=1/1을 기다리도록 보완했다.
- 다음 검사에서는 촬영 중 비활성 합성 버튼을 스크롤 기준으로 찾지 못했다(65). 지연 검사를 별도 분리해 카메라 시작 전 활성 스위치 값을 확인했고 실기기의 대기 1건/늦은 결과 폐기는 통과했다. 이후 카메라 lifecycle 검사만 가로 화면에서 관성 스와이프가 짧은 epoch 행을 지나쳐 실패했다(65). 검사 스크롤을 절반 화면 드래그 후 정지 방식으로 보완했다. 이 실패들은 추론 성공 집계와 구분한다.
- 최종 종합 검사에서 카메라 lifecycle·지연 폐기·SDK 합성 입력은 통과했으나 33점 조건은 45초 안에 다시 충족되지 않았다. 앞선 실제 검출/오버레이 성공과 구분해 기록하며 종합 성공이나 전신 검출 보장을 주장하지 않는다. 사용자가 다시 촬영 준비를 완료한 뒤 **동일 최종 소스의 33점 단독 재검사에서 종료 코드 0/1건 통과**를 확인했다. 따라서 4종 검사 각각의 통과는 확인했지만 한 번의 종합 실행에서 4건이 모두 통과했다고 기록하지 않는다.
- 실제 33점 반환은 운동 자세의 정확성·전신 가시성·규칙 판정 성공이 아니다. 검정 프레임 미검출/잘못된 모델 경로 오류 검사는 실제 SDK에 **명시적 합성 입력**을 제공한 결과다. 실제 카메라의 무인 장면 전체 조건이나 손상 모델 파일의 모든 오류 유형을 대체하지 않는다.
- 비율/미러 투영의 합성 좌표 검사와 실제 회전 버퍼 확인은 있으나 고정 표적을 이용한 물리적 좌우/각도 오차 대조는 미완료다. 실기기 한 대의 iOS 27 베타 Debug 결과를 iOS 16/안정 OS/iPad/Release/20분 지속/출시 지원으로 확대하지 않는다.
- P1 공개 DTO/생성 헤더/Native 태스크 및 P2 세션 facade가 아직 없다. M3은 정확한 Windows 계약 SHA를 받은 후 픽스처 → 세션 재생 → 실제 카메라 순으로 진행한다. M2 성공은 공통 엔진 동등성이나 P0/P1/P2 완료가 아니다.

공유 전 프로젝트 의미/권한/XML·문서 링크·개인정보 제외·ignore·`git diff --check`를 확인했다. 원래 로컬 체크아웃은 `feature/posture-coach-reliability` / `5237ba9` 및 깨끗한 작업 트리를 유지한다. 소스 커밋과 이 상태 문서를 Mac 브랜치에 공유하며, 다음 작업은 Windows의 실제 P1/P2 계약 수신 이후 M3이다.
