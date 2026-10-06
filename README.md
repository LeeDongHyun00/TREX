# TREX — iPhone 실행 안내

`ios-redesign`은 TREX의 iPhone 앱 브랜치입니다. 운동 계획·카메라 자세 평가·운동 가이드·식단/사진 인식·기록·3D 근육 보기·백업을 제공합니다. **iOS 16 이상**의 iPhone을 대상으로 합니다.

| 실행 방법 | 준비물 | 용도 |
|---|---|---|
| [Mac에서 Xcode로 실행](#1-mac에서-xcode로-실행) | Mac, Xcode, iPhone, 본인 Apple 계정 | 소스를 빌드하고 자신의 iPhone에서 테스트 |
| [IPA를 Sideloadly로 설치](#2-ipa를-sideloadly로-설치) | Windows 또는 Mac, iPhone, 본인 Apple 계정 | 빌드된 앱을 받아 테스트 |

iPhone에서 실행하려면 Apple 계정으로 서명해야 합니다. 저장소를 내려받거나 미서명 IPA를 iPhone에서 열기만 하면 설치되는 방식은 아닙니다.

## 1. Mac에서 Xcode로 실행

### 1-1. 필요한 도구 설치

- **Xcode 26 계열**: [Apple Xcode](https://developer.apple.com/xcode/)에서 설치하고 한 번 실행해 초기 설정과 iOS SDK 설치를 완료합니다. 이 브랜치는 **Xcode 26.0.1**로 빌드 검증했습니다. Kotlin 2.3.21의 공식 호환 기준은 [Xcode 26.0](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)입니다.
- **Homebrew**: [공식 설치 안내](https://brew.sh/)를 따릅니다.
- **JDK 21, Python 3.12, XcodeGen, CocoaPods**: Homebrew 설치 후 터미널에서 아래 명령을 실행합니다.

```bash
brew install openjdk@21 python@3.12 xcodegen cocoapods
```

Xcode와 macOS의 Java 실행기가 JDK를 찾을 수 있도록 [Homebrew JDK 안내](https://formulae.brew.sh/formula/openjdk@21)에 따라 등록합니다.

```bash
sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

아래 환경 설정부터 준비 스크립트까지 **같은 터미널에서 이어서 실행**합니다.

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix python@3.12)/libexec/bin:$PATH"
export TREX_POD_BINARY="$(brew --prefix)/bin/pod"

java -version
python3 --version
xcodebuild -version
```

JDK는 21, Python은 3.12, 선택된 Xcode는 26 계열인지 확인합니다. 여러 Xcode가 설치되어 있다면 실행할 버전을 선택합니다. 다음 경로는 실제 설치 위치에 맞게 바꿉니다.

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

### 1-2. ios-redesign 클론 및 프로젝트 준비

```bash
git clone --branch ios-redesign --single-branch https://github.com/LeeDongHyun00/TREX.git
cd TREX

python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install flatbuffers==25.12.19 tflite==2.18.0

bash iosApp/scripts/setup-mac.sh
open iosApp/Trex.xcworkspace
```

준비 스크립트가 공유 엔진/자산을 확인하고 Xcode 프로젝트를 생성한 뒤 CocoaPods를 설치합니다. 처음에는 Gradle·Kotlin/Native·SDK 의존성을 내려받고 빌드하므로 시간이 걸립니다. **`Trex.xcworkspace`를 엽니다.**

### 1-3. iPhone 연결 및 서명

1. iPhone을 USB로 연결하고 잠금을 풉니다. iPhone에서 **이 컴퓨터를 신뢰**를 허용합니다.
2. Xcode의 **Settings → Apple Accounts**에서 본인 Apple 계정으로 로그인합니다.
3. Xcode 상단에서 **scheme: `Trex`**, 실행 대상: **연결한 iPhone**을 선택합니다.
4. 프로젝트의 **TARGETS → `Trex` → Signing & Capabilities**에서 **Automatically manage signing**을 켜고 **Team**을 본인 계정의 팀으로 설정합니다.
5. **`TrexFoodRuntime` 타깃도 같은 Team**으로 설정합니다.
6. Bundle Identifier 등록 오류가 나면 `Trex`와 `TrexFoodRuntime` 각각을 본인만 사용할 고유한 값으로 변경합니다. 예: `com.yourname.trex.ios`, `com.yourname.trex.foodruntime`.
7. iPhone의 **설정 → 개인정보 보호 및 보안 → 개발자 모드**를 켜고 안내에 따라 재시동·확인합니다. 메뉴가 보이지 않으면 Xcode에서 먼저 실행을 시도한 뒤 확인합니다.
8. Xcode의 **Run(▶ 또는 ⌘R)**을 눌러 빌드·설치·실행합니다. 개발자 신뢰 안내가 나오면 iPhone의 **설정 → 일반 → VPN 및 기기 관리**에서 본인 개발자를 신뢰합니다.

자세 평가를 시작할 때 카메라 권한을 허용하고 촬영 방향 안내에 따라 몸이 화면에 들어오도록 배치합니다.

무료 Apple 계정의 **Personal Team**으로 개인 기기 테스트가 가능합니다. 무료 프로비저닝은 **7일 후 만료**되므로 다시 빌드·설치해야 합니다. [Apple 계정/Personal Team 안내](https://developer.apple.com/help/account/basics/about-your-developer-account), [기기에서 앱 실행 안내](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices)를 참고합니다.

### 1-4. iPhone 없이 시뮬레이터에서 확인

Xcode에서 iPhone Simulator 런타임을 설치하고 `Trex` scheme의 실행 대상을 시뮬레이터로 선택한 뒤 Run합니다. 자동 검사는 저장소 루트에서 실행할 수 있습니다.

```bash
bash iosApp/scripts/test-simulator.sh
```

시뮬레이터에서의 빌드·실행 검사는 실제 iPhone 카메라로 운동하는 검증과 구분합니다. 자세 평가·촬영 방향·실제 음식 사진 인식은 기기에서 확인해야 합니다.

## 2. IPA를 Sideloadly로 설치

다른 테스터는 소스 빌드 없이 미서명 IPA를 받아 **각자의 Apple 계정으로 서명**해 설치할 수 있습니다. Windows 또는 Mac과 iPhone이 필요합니다.

### 2-1. 검증된 IPA 받기

**[TREX 미서명 IPA 다운로드](https://github.com/LeeDongHyun00/TREX/actions/runs/37495731624/artifacts/11427878651)**

GitHub에 로그인한 뒤 `TREX-unsigned-IPA` artifact ZIP을 내려받아 압축을 풉니다. 그 안의 **`TREX-unsigned.ipa`**를 설치 프로그램에서 사용합니다.

이 파일은 코드 `e52b15be`의 [성공한 macOS 빌드](https://github.com/LeeDongHyun00/TREX/actions/runs/37495731624)에서 생성했습니다. artifact 보관 기한은 **2027-01-04**이며 이후에는 새 빌드로 다시 생성해야 합니다. 새 버전은 저장소의 **Actions → iOS 검증과 미서명 IPA → 성공한 실행 → Artifacts**에서 찾습니다.

### 2-2. 서명 및 설치

1. **[Sideloadly 공식 사이트](https://sideloadly.io/)**에서 자신의 Windows/Mac용 프로그램을 설치합니다. Windows에서 필요한 Apple 기기 드라이버·iTunes/iCloud 설치는 공식 사이트 안내를 따릅니다.
2. iPhone을 USB로 연결하고 잠금을 푼 뒤 **이 컴퓨터를 신뢰**를 허용합니다.
3. Sideloadly에서 연결한 iPhone을 선택하고 `TREX-unsigned.ipa` 파일을 넣습니다.
4. **본인 Apple 계정**을 입력하고 **Start**를 누릅니다. 로그인 및 2단계 인증 안내를 완료합니다.
5. 설치 후 iPhone에서 **개발자 모드**를 켜고, 개발자 신뢰 안내가 나오면 **설정 → 일반 → VPN 및 기기 관리**에서 해당 개발자를 신뢰합니다.
6. 홈 화면의 TREX를 실행합니다.

무료 계정은 일반적으로 **7일마다 재서명**이 필요하며, 동시에 설치할 수 있는 사이드로드 앱은 **3개**로 제한됩니다. Sideloadly의 자동 갱신은 컴퓨터와 기기의 연결 조건을 충족해야 작동합니다. 자세한 제한·갱신·오류 해결은 [공식 FAQ](https://sideloadly.io/faq)를 참고합니다.

### 2-3. Mac에서 새 IPA 만들기

[Mac 준비 단계](#1-mac에서-xcode로-실행)를 완료한 뒤 같은 터미널에서 저장소 루트로 이동해 실행합니다.

```bash
bash iosApp/scripts/build-unsigned-ipa.sh
```

생성 위치:

```text
iosApp/build/sideload/TREX-unsigned.ipa
```

이 스크립트는 iPhone용 Release 빌드와 번들 검사를 거쳐 **미서명 IPA**를 생성합니다. 테스터에게 이 파일을 전달하고 각자 Sideloadly에서 서명해 설치합니다. 개인 인증서나 프로비저닝 파일을 Git에 올릴 필요는 없습니다.

## 3. 업데이트와 자주 발생하는 문제

업데이트·재서명 전에 앱의 백업 기능으로 기록을 내보냅니다. Sideloadly에서 기존 앱을 덮어쓰려면 **같은 Apple 계정과 Bundle ID**를 사용합니다. 관련 조건은 [Sideloadly FAQ](https://sideloadly.io/faq)에 있습니다.

| 문제 | 확인할 내용 |
|---|---|
| `pod` 실행 오류 | 위의 `TREX_POD_BINARY` 환경 설정을 적용하고 `setup-mac.sh`를 다시 실행합니다. |
| `No module named flatbuffers` 또는 `tflite` | `.venv`를 활성화하고 위의 `python3 -m pip install …` 명령을 다시 실행합니다. |
| Xcode 버전 오류 | `xcodebuild -version`과 `xcode-select`로 선택된 Xcode를 확인합니다. |
| 서명/프로비저닝 오류 | `Trex`, `TrexFoodRuntime`의 Team과 고유 Bundle Identifier를 확인합니다. |
| iPhone이 목록에 없음 | USB 연결·잠금 해제·컴퓨터 신뢰 상태를 확인합니다. |
| 개발자 신뢰/개발자 모드 오류 | iPhone 설정에서 개발자 신뢰와 개발자 모드를 확인합니다. |
| 설치한 앱이 7일 후 열리지 않음 | 같은 계정으로 재빌드 또는 재서명합니다. |
| IPA 다운로드가 안 됨 | GitHub 로그인과 artifact 보관 기한을 확인하거나 새 빌드의 artifact를 받습니다. |

## 4. 검증 상태와 상세 문서

2026-10-07 기준, 코드 `e52b15be`에서 다음 검사를 통과했습니다.

- Xcode 26.0.1의 iPhone arm64 Release 빌드와 미서명 IPA 생성
- 자세 자산·iOS 음식 모델 SHA 및 음식 런타임 번들 검사
- 공통 엔진 JVM 테스트 **376/376**
- iOS 시뮬레이터 XCTest **4/4**: 자산/가이드, 백업, 캡처 세대·85ms 간격, 음식 3모델과 자세 모델의 실제 추론

**실제 iPhone에서의 서명·실행 및 운동/음식 인식 정확도는 아직 검증하지 않았습니다.** 이 브랜치는 기기 테스트를 위한 구현입니다.

- [iOS 구현 범위·빌드·기기 검증 절차](docs/IOS_PORT.md)
- [iOS 프로젝트 안내](iosApp/README.md)
- [자세 엔진 인수인계](docs/POSTURE_HANDOFF.md)
