# Mac 작업 상태

- **갱신일:** 2026-09-12
- **담당:** Mac Codex 역할 검토 — 현재 실행 세션은 실제 Mac이 아니라 Linux x86_64 환경
- **작업 브랜치:** `codex/ios-kmp-mac`
- **통합 브랜치:** `codex/ios-posture-kmp`
- **GitHub 기준 소스:** `5237ba9` 및 협업 문서 커밋 `36b8d115e554eadce8c4494740322a6dca696d9b`
- **실제로 확인한 작업 브랜치 HEAD:** `36b8d115e554eadce8c4494740322a6dca696d9b`
- **통합 브랜치와 비교:** identical (`ahead 0 / behind 0`)
- **현재 단계:** 저장소/설계 검토 완료. 실제 Mac 개발 환경 검증은 미완료.

## 이번 확인 결과

기존 로컬 작업을 덮어쓰거나 reset/force-push하지 않았다. GitHub 원격의 `codex/ios-kmp-mac` 브랜치와 문서를 읽어 상태를 확인했으며, 공통 엔진·Gradle·Android 앱·iOS 앱 코드는 변경하지 않았다.

현재 도구 실행 호스트에서 확인한 환경은 다음과 같다. 이 결과는 **Mac의 환경 검사 결과가 아니다.**

| 항목 | 결과 |
|---|---|
| 실행 호스트 | Linux `x86_64` |
| macOS / Mac 모델 / Apple Silicon 여부 | 확인 불가 — 실제 Mac 아님 |
| `xcodebuild -version` | 명령 없음 |
| `xcode-select -p` | 명령 없음 |
| Java | OpenJDK `21.0.11` 확인 |
| `pod --version` | 명령 없음 |
| iOS 시뮬레이터 | 확인 불가 |
| 실제 iPhone 확보/연결 | 확인 불가 |
| Kotlin/Native 빌드·테스트 | 미실행 — 공통 모듈 구현 전이며 실제 Mac도 아님 |

따라서 Xcode, Command Line Tools, CocoaPods, 시뮬레이터 런타임, 코드 서명/Apple 계정, 실제 iPhone 연결 가능 여부는 실제 Mac 세션에서 다시 확인해야 한다. 이 문서에서는 설치·계정 설정·기기 확보를 완료한 것으로 간주하지 않는다.

## ADR-056 / Native·Swift 연결 검토

현재 설계 방향은 첫 이식 단계에서 타당하다. 계산·세션 상태를 KMP에 두고 카메라/MediaPipe/센서/TTS/저장을 플랫폼에 남기는 경계는 Android 기존 동작을 보존하면서 iOS 검증 앱을 추가하기에 적절하다. 다만 P1 공통 API를 확정할 때 아래 Native 소비 제약을 먼저 고정해야 한다.

1. **Swift에서 안정적으로 소비할 DTO 표면을 작게 유지한다.** 설계의 입력/출력 타입을 그대로 Kotlin 내부 모델 전체로 노출하기보다, 값 타입 중심의 얇은 facade를 `iosMain`에 둔다. Kotlin 컬렉션/nullable/enum/sealed 계층이 Swift에서 불필요하게 복잡해지지 않는지 실제 생성 헤더/Swift 호출부로 검증한다.
2. **시간·토큰·멱등성 계약을 플랫폼 시계와 분리한다.** `timestampMs`, 세트 token, capture token, finalize token은 Swift/AVFoundation 콜백 순서와 관계없이 공통 엔진에 명시적으로 전달해야 한다. iOS lifecycle/카메라 재구성 시 동일 세트의 중복 마감이 생기지 않도록 설계의 멱등성 조건을 공개 API 수준에서 테스트해야 한다.
3. **좌표계 계약을 iOS 어댑터에서 정규화한다.** AVFoundation/MediaPipe의 화면 회전·전후면 미러링·정규화 좌표와 Android 입력 계약을 섞지 않는다. 공통 엔진에는 이미 합의된 좌표계만 넘기고, raw `CVPixelBuffer`나 MediaPipe SDK 타입은 공통 모듈에 노출하지 않는다.
4. **자산 로딩은 공통 계산과 분리한다.** 규칙 JSON/표본/프로필은 동일 내용과 해시를 써야 하지만 bundle/파일 경로는 iOS 어댑터 책임이어야 한다. `NSBundle` 또는 CocoaPods 리소스 경로를 commonMain이 알게 만들지 않는다.
5. **문자열/음성 정책은 엔진 이벤트와 OS TTS 실행을 분리한다.** AGENTS의 ship/beta/exclude 정책과 설계의 음성 자격을 공통 결과로 계산하고, 실제 `AVSpeechSynthesizer` 호출은 iOS에 남긴다. beta를 음성으로 읽지 않는 정책이 플랫폼별로 갈라지지 않도록 회귀 픽스처가 필요하다.
6. **Kotlin/Native 성능은 별도 실측한다.** Android/JVM 결과나 네이티브 컴파일 자체를 iOS 성능 근거로 쓰지 않는다. P1 이후 같은 재생 입력으로 공통 계산의 latency/allocation을 측정하고, MediaPipe 추론·렌더링과 분리한다.
7. **CocoaPods는 가정하지 않는다.** 현재 설계의 `Podfile`은 제안 상태다. 실제 Mac에서 MediaPipe Tasks iOS의 지원 배포 방식과 프로젝트 생성 전략을 확인한 뒤 CocoaPods/SPM 선택을 고정한다. 도구 존재 여부만으로 의존성 전략이 확정된 것으로 기록하지 않는다.

## Windows/P1에 필요한 입력

Windows가 공통 계산 모듈을 추출할 때 Mac 쪽 소비 검증을 쉽게 하려면 다음을 P1 완료 조건에 포함하는 것이 좋다.

- `iosArm64`와 `iosSimulatorArm64` 타깃을 전제로 한 최소 public facade와 생성 가능한 framework 이름을 한 곳에서 정의한다.
- Swift에 노출할 공개 타입/함수 목록과, 내부 Kotlin 타입으로 유지할 항목을 구분한다.
- 플랫폼이 넘겨야 하는 좌표계·단조 시간·세트/capture token 계약을 테스트 가능한 문서/API로 고정한다.
- 규칙/프로필 자산은 경로가 아니라 문자열/바이트 입력 또는 플랫폼 로더 인터페이스 경계로 전달한다.
- P0 재생 픽스처 중 개인정보/원본 로그가 아닌 공유 가능한 최소 입력을 commonTest와 Native 검증에서 동일하게 사용할 수 있게 한다.
- JVM 성공과 별개로 실제 Mac에서 Kotlin/Native 컴파일 및 Swift 호출 확인이 필요하므로 P1 상태에는 이를 '미검증'으로 남긴다.

## 다음 실제 Mac 작업

실제 Mac 세션에서 아래를 다시 실행·기록한다.

```bash
uname -m
sw_vers
xcodebuild -version
xcode-select -p
java -version
pod --version
xcrun simctl list devices available
```

필요하면 `xcodebuild -showsdks`와 연결된 iPhone 확인을 추가한다. 설치가 필요한 도구가 있으면 설치 전 상태와 선택한 버전을 기록한다. P1 공통 API 커밋이 아직 없으므로 그 전에는 `posture-core`, `iosApp`, Swift 판정 엔진 구현을 시작하지 않는다.

## 미실행/미검증

- 실제 Mac의 Xcode·Command Line Tools·CocoaPods·시뮬레이터 검사
- Apple Silicon/Intel 실제 아키텍처 확인
- 실제 iPhone 확보·서명·실행
- Kotlin/Native framework 생성
- Swift에서 공통 API 호출
- iOS MediaPipe Pose Landmarker 연결
- Android/JVM 빌드 및 자세 엔진 테스트 재실행

이번 커밋의 목적은 **환경 한계와 설계 검토 결과를 정확히 공유하는 것**이며 공통 엔진 구현은 포함하지 않는다.
