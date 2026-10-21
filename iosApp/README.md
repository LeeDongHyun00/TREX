# TREX iPhone 앱

`Trex` 는 SwiftUI 앱이고, `posture-core` 의 Kotlin 공통 엔진을 `TrexCore.framework` 로 연결한다. 엔진은 Android 정본 48파일을 `tools/sync_ios_core.py` 가 생성한 것이라 임계값·횟수·반복 검사·플랭크 시계·시선·발화 정책이 Android 와 같다. Swift 는 카메라(AVFoundation)·중력(Core Motion)·자세/얼굴 모델(MediaPipe Tasks Vision)·TTS 엔진(AVSpeechSynthesizer)·화면만 든다 — 포팅 기준과 범위는 [IOS_PORT](../docs/IOS_PORT.md).

2026-10-21 기준 `main`(§99~§106)을 병합한 상태다. 공통 엔진 JVM 테스트·정본 동기화·자산 추출·Swift 구문 검사를 통과했고, **Xcode 컴파일·시뮬레이터 XCTest·IPA 는 `ios-redesign` 푸시가 띄우는 [macOS Actions](https://github.com/LeeDongHyun00/TREX/actions)에서 확인한다.** 서명·실제 iPhone 실행은 미검증이다.

Mac 절차(JDK 21, Xcode 26 계열, Python 3, XcodeGen, CocoaPods):

```bash
python3 -m pip install flatbuffers==25.12.19 tflite==2.18.0
bash iosApp/scripts/setup-mac.sh
open iosApp/Trex.xcworkspace
```

Xcode 에서 `Trex` scheme 과 연결한 iPhone 을 선택한 뒤, `Trex` 와 `TrexFoodRuntime` 타깃의 Signing & Capabilities 에서 Team 을 본인 Apple 계정으로 설정하고 Run 한다. 개인 Team·인증서·프로비저닝 프로필은 Git 에 저장하지 않는다. 다른 테스터용 미서명 IPA 는 `bash iosApp/scripts/build-unsigned-ipa.sh` 로 만든다(`iosApp/build/sideload/TREX-unsigned.ipa`). 상세는 [IOS_INSTALL](../docs/IOS_INSTALL.md). 이전 진단 타깃은 [DIAGNOSTICS](DIAGNOSTICS.md).

엔진을 고칠 때는 Android 정본을 고치고 `python3 tools/sync_ios_core.py` 로 다시 생성한 뒤 `bash gradlew -PiosOnly=true :posture-core:jvmTest` 를 돈다. 생성본은 손으로 고치지 않는다.
