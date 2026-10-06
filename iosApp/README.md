# TREX iPhone 앱

`Trex`는 SwiftUI 앱이고, `posture-core`의 Kotlin 엔진을 `TrexCore.framework`로 연결한다. `redesign`의 운동 26종·가이드 7종·영양 DB 350종과 원본 모델을 사용한다.

현재 Windows에서 공통 엔진/Android 회귀 검사와 Swift 구문·자산 연결 검사를 통과했다. **새 `Trex` 타깃의 Xcode 컴파일·서명·IPA 생성·iPhone 실행은 아직 검증하지 않았다.**

저장소 루트에서 Mac용 준비 스크립트를 실행한다. JDK 21, Xcode 26 계열, Python 3, XcodeGen, CocoaPods가 필요하다. Kotlin 2.3.21의 공식 Xcode 호환 기준은 26.0이다.

```bash
bash iosApp/scripts/setup-mac.sh
open iosApp/Trex.xcworkspace
```

Xcode에서 `Trex` scheme과 연결한 iPhone을 선택한 뒤, Signing & Capabilities의 Team을 본인 Apple 계정으로 설정하고 Run한다. 개인 Team·인증서·프로비저닝 프로필은 Git에 저장하지 않는다.

다른 테스터가 Sideloadly에서 각자의 계정으로 서명할 입력 파일은 다음 명령으로 만든다. 성공하면 `iosApp/build/sideload/TREX-unsigned.ipa`가 생성된다.

```bash
bash iosApp/scripts/build-unsigned-ipa.sh
```

미서명 IPA는 iPhone에 바로 설치할 수 없다. 테스트 절차·기능 범위·미검증 항목은 [IOS_PORT](../docs/IOS_PORT.md)에 있다. 이전 진단 타깃은 [DIAGNOSTICS](DIAGNOSTICS.md)에 구분해 남겼다.
