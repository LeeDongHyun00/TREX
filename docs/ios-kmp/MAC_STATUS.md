# Mac 작업 상태

- **갱신일:** 2026-09-12 (KST), 실제 Mac에서 재점검
- **담당:** Mac Codex
- **작업 브랜치:** `codex/ios-kmp-mac`
- **통합 브랜치:** `codex/ios-posture-kmp`
- **가져온 통합 기준:** `36b8d115e554eadce8c4494740322a6dca696d9b`
- **확인한 Windows HEAD:** `36b8d115e554eadce8c4494740322a6dca696d9b`
- **검사 시작 Mac HEAD:** `c4b255bc01f3041e20e66c94a2c4df08eb5ecea0` — 통합 기준 대비 ahead 1 / behind 0
- **검토한 앱 소스:** `5237ba9d07662849fc2b23853672049ff2a950af`. 검사 시작 HEAD의 `app/`, `gradle/`, 루트 빌드 파일과 Wrapper는 이 소스와 동일하다.
- **현재 단계 / 담당 파일:** Mac 환경 점검·ADR-056 검토 완료, `docs/ios-kmp/MAC_STATUS.md`만 변경. P0 전체 완료나 P1 착수를 뜻하지 않는다.
- **실제 공통 API 계약 커밋:** **없음**. `posture-core`, `iosApp`, `TrexPosture` 프레임워크는 아직 없다.

이전 [Mac 상태 커밋 c4b255b](https://github.com/LeeDongHyun00/TREX/commit/c4b255bc01f3041e20e66c94a2c4df08eb5ecea0)은 Linux 호스트의 한계를 기록했다. 그 이력을 보존하면서 실제 Mac 관측값으로 현재 상태를 갱신한다. 이전 Linux의 Java 21.0.11을 이 Mac의 버전으로 사용하지 않는다.

## 1. 저장소 수신과 기존 작업 보존

기존 로컬 체크아웃은 `feature/posture-coach-reliability`, HEAD `5237ba9`이며 시작 시 추적/미추적 변경이 없었다. 로컬 upstream 대비 ahead/behind 표시도 없었다. 해당 체크아웃의 브랜치·파일·Git 설정을 변경하지 않고 **별도 새 폴더에 원격 Mac 브랜치를 clone**했다. 기존 원격 추적 참조가 최신이라는 판단은 하지 않았으며, 협업 브랜치 fetch는 새 clone에서만 실행했다.

`origin`이 `https://github.com/LeeDongHyun00/TREX.git`임을 확인하고 통합·Windows·Mac 브랜치를 fetch했다. 통합 HEAD는 이미 Mac 브랜치의 조상이어서 추가 merge할 변경이 없었다. [Windows 상태](WINDOWS_STATUS.md)는 여전히 설계/협업 준비 단계이고 P1 API를 공개하지 않았다. 과거 로컬 대용량 파일 이력은 가져오거나 merge하지 않았다.

먼저 `AGENTS.md`, [인수인계](../POSTURE_HANDOFF.md), [ADR-056](../IOS_POSTURE_KMP_DESIGN.md), [협업 규약](../IOS_KMP_COLLABORATION.md), 양쪽 상태 문서를 읽었다. 공통 엔진·Swift 판정 엔진·루트 Gradle·Android 앱·규칙·모델은 수정하지 않았다. 이번 결과는 Mac 작업 브랜치에만 공유하며, 통합 반영 시 PR base는 `codex/ios-posture-kmp`다.

## 2. 실제 Mac 환경 검사

2026-09-12 23:47~23:51 KST의 명령 결과다. 개인 SDK 경로, 기기 UDID/일련번호, 계정·서명 정보는 공유 문서에 넣지 않는다.

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

Xcode/OS/Java/Pod 설치·업데이트, 시뮬레이터 생성·부팅, iPhone 설치, Apple 계정·서명 설정은 수행하지 않았다.

## 3. 환경과 설계의 차이 — 구현 전 해결할 항목

### Xcode 호환 기준과 Mac 모델

Kotlin 공식 호환 표에서 **2.3.20–2.3.21의 Xcode 기준은 26.0**이다. 현재 16.2는 그 조합과 다르므로 P1 Native 검증이 끝났다고 표시할 수 없다. 호환 표의 차이만으로 컴파일 실패가 실측됐다고도 쓰지 않는다. [Kotlin 호환 표](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

Apple의 Xcode 26 요구 OS는 **macOS Sequoia 15.6 이상**이다. `MacBookPro14,3`는 Apple 문서상 15형 2017 모델이며 공식 지원 최종 OS는 Ventura다. 따라서 이 모델에서 지원되는 OS 업데이트만으로 설계의 Xcode 26 환경을 갖출 수 있다고 가정할 수 없다. 현재 14.8.4가 동작하는 설치 경위는 조사하지 않았다. **지원되는 다른 Mac에서 Native 검증을 수행하는 방안을 우선 제안**한다. 기존 Mac/도구의 업그레이드나 프로젝트 Kotlin 하향은 이번 범위에서 결정하지 않았다. [Xcode 요구 사항](https://developer.apple.com/xcode/system-requirements), [Mac 모델 식별](https://support.apple.com/en-us/108052), [Sequoia 지원 모델](https://support.apple.com/en-us/120282)

### Intel 시뮬레이터 타깃

ADR-056의 초기 타깃 `iosArm64`는 실제 iPhone, `iosSimulatorArm64`는 Apple Silicon 시뮬레이터용이다. **현재 Intel Mac의 시뮬레이터 검증에는 `iosX64`가 추가로 필요**하다. Kotlin 2.3.20 안내에서도 `iosX64`는 tier 3로 유지된다. Apple Silicon 검증 Mac을 확보해 초기 타깃을 유지할지, Intel도 지원해 `iosX64`를 넣을지 Windows 통합 담당이 P1 타깃 설정에 반영해야 한다. 타깃 추가만으로 Xcode/OS 차이나 사용 가능한 가상 기기 부재가 해결되지는 않는다. [Kotlin Native 타깃](https://kotlinlang.org/docs/native-target-support.html), [Kotlin 2.3.20 변경 사항](https://kotlinlang.org/docs/whatsnew2320.html)

### iPhone과 MediaPipe 연결

등록된 iPhone은 있으나 현재 오프라인이다. 연결 후 실제 iOS 버전·Developer Mode·신뢰/서명·선택 Xcode의 기기 지원을 다시 확인해야 한다. MediaPipe의 카메라 검증은 실제 기기에서 진행하고, 시뮬레이터의 계산/화면 검증과 구분한다. 공식 iOS 설치 경로는 CocoaPods이며 ADR-056처럼 **Swift 앱 타깃이 MediaPipe Pod를 소유**하는 구성을 유지할 수 있다. 이전 상태 문서의 CocoaPods/SPM 재선택 제안은 필수 선행 과제로 두지 않는다. 다만 실제 Pod 해석·버전 잠금·선택 아키텍처 링크는 아직 실행하지 않았으므로 Android와 같은 `0.10.14`를 iOS에 임의 확정하지 않는다. [MediaPipe iOS 설치](https://developers.google.com/edge/mediapipe/solutions/setup_ios)

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

Git 객체를 비교해 `5237ba9`와 검사 시작 HEAD의 앱/빌드 소스가 동일함을 확인했다. 규칙 JSON은 헤더가 아닌 배열을 Python으로 집계했다: 서서 **141 = ship 51 / beta 20 / exclude 70**, 바닥 **16 = beta 14 / exclude 2**. `AGENTS.md`의 과거 바닥 14개 설명은 현재 배열 수와 다르며 ADR-056의 최신 수치와 일치한다.

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

이는 파일 동일성 확인이며 해당 픽스처를 사용하는 JVM/Native 테스트 실행 결과가 아니다.

## 6. 미실행 검사와 다음 인수인계

| 미실행 항목 | 이유 / 재개 조건 |
|---|---|
| Android 단위 테스트·APK 빌드·성능 계측 | 이번에는 Mac 환경/설계 검토만 수행했다. 과거 270건 통과를 이번 실행 결과로 쓰지 않는다. P0 기준은 Windows 담당이 공유한다. |
| Kotlin/Native 계산 테스트·framework 생성 | 공통 모듈/API 커밋이 없고 Xcode/호스트/타깃 차이도 미해결이다. |
| Swift API 호출·iOS 앱 빌드 | P1/P2의 실제 API와 P3 앱이 미구현이다. |
| Pod 해석·iOS 모델 실행 | Podfile/lock과 iOS 앱이 없다. CocoaPods 존재만 확인했다. |
| 시뮬레이터 실행·실제 iPhone 촬영/TTS·20분 지속 검사 | 사용 가능한 가상 기기 0개, 등록 iPhone 오프라인. 연결/서명/모델 링크부터 검증해야 한다. |

1. Windows 통합 담당은 이 Mac의 Intel/Xcode 차이를 읽고 Native 검증 Mac과 `iosX64` 필요 여부를 P1 전제에 반영한다. 지원되는 Apple Silicon Mac을 우선 후보로 제안하되 장비 확보나 버전 변경은 아직 결정하지 않았다.
2. Windows가 다음 구현에 착수할 때 P0 소스/자산/입력 해시, 공유 가능한 재생 입력, 현행/목표 정책의 기대값, 실행한 Android 검사를 공개한다. 개인 로그나 AIHub 원본을 Git 제외 대상에서 풀지 않는다.
3. P1 커밋의 정확한 SHA, 공개 DTO/API, Native 타깃/테스트 작업, 리소스 로더 계약을 `WINDOWS_STATUS.md`에 기록한다. Swift 세션 facade는 P2에서 추가한다.
4. Mac은 공유된 통합 커밋을 fetch/merge하고 해당 SHA와 실제 Xcode/SDK/아키텍처를 고정해 Native 검증을 이어간다. 이번에는 그 구현을 시작하지 않았다.

이번 공유물은 이 상태 문서뿐이다. 커밋 전 기존 체크아웃의 HEAD/변경 없음, 로컬 문서 링크 4개, SHA-256 8개, 변경 파일이 이 문서 하나임을 검사했고 `git diff --check`도 통과했다. 원격을 다시 fetch해 세 협업 브랜치의 기준이 그대로임을 확인했다. Mac 작업 브랜치에 커밋·푸시하며 통합/Windows 브랜치에는 직접 쓰지 않는다.
