# iPhone 포팅 — 2026-10-07

## 상태와 기준

`ios-redesign`에서 `origin/redesign`의 `5213e3c4`(1.3.0-preview.6)를 기준으로 구현했다. 이전 `codex/ios-kmp-mac`의 카메라·MediaPipe 진단 코드를 재사용하지만 **그 진단 앱의 과거 Mac 빌드 성공을 새 앱의 빌드 성공으로 해석하지 않는다.**

`ios-redesign`을 원격에 푸시했고 [macOS Actions](https://github.com/LeeDongHyun00/TREX/actions/runs/37495731624)가 **성공**했다. 검증한 코드 커밋은 `e52b15be`이며 Xcode 26.0.1에서 공통 JVM 376건, 새 `Trex` 타깃의 Kotlin/Native·Swift 컴파일·iPhone arm64 Release 빌드·번들 검사·미서명 IPA 생성, arm64 시뮬레이터 XCTest **4/4**를 통과했다. 음식 모델 3개와 MediaPipe 자세 모델을 같은 앱에서 초기화하고 실제 추론하는 검사도 통과했다. 음식 런타임의 동적 프레임워크/공개 심볼 격리와 YOLO 외부 가중치 호환 처리를 적용했다. **서명과 실제 iPhone 실행은 미검증**이며 사용자의 Mac·기기에서 확인해야 한다.

## 구현 범위

| 기능 | iOS 구현 |
|---|---|
| 자세 엔진 | Android 정본 38파일, 중력 변환·규칙 로딩·DTO 경계, 동일한 규칙 JSON·정상 표본 |
| 카메라 | AVFoundation 전후면, Core Motion 중력, MediaPipe Full VIDEO/CPU, 원본 관절과 화면 미러 분리 |
| 운동 세션 | 준비·프레이밍·방향·5초 수동 시작, 횟수·좌우 짝·반복 검사, 부위 강조·한국어 음성, 휴식·다음 종목, 정지·복귀 |
| 최신 네 종목 | 크로스/사이드 런지·스탠딩 니업·스탠딩 사이드 크런치의 legcycle_v3_beta, 접촉 최솟값과 무릎 발끝 게이트 |
| 평가 정책 | COACH/TRACK, 유보·ship/beta/exclude, 못 보는 범위, 세트 리포트와 내 실제 수행/자세 라벨 |
| 운동 계획·가이드 | 26종 선택·횟수/세트/휴식 편집·순서 변경·타이머 진행, 7종 원본 GIF/준비/수행/호흡/주의/출처 |
| 홈·프로필 | 운동/식단 요약, 신체 정보·목표·요일·장소·장비·다크 모드·음성 설정 |
| 식단 | 350종 DB, 직접 영양 입력·끼니/분량·날짜별 기록, 권장/직접 열량·탄단지 목표 |
| 사진 식단 | 원본 가중치의 TFLite 3모델(YOLO 저장 형식 보정), 최대 5장·영역 탐지·분류·임베딩 기억, 낮은 확신 후보 확인 후 저장 |
| 운동 기록·근육 보기 | 로컬 세트 리포트·자가 라벨·삭제, Android 정본 부하 계산과 장비별 추천, 원본 3D WebView·현재 세션 사용 부위 |
| 저장·백업 | Application Support에 JSON과 기기 파일 보호, JSON 백업 공유/복원, 사진 없는 자세 로그 |

SwiftUI 화면을 새로 구성했다. Android Compose 화면과 픽셀 단위로 같은 UI는 아니다. Android 전용 APK 업데이트 설치 흐름은 이식하지 않았으며, iOS 업데이트는 새 빌드의 서명/재설치를 따른다. 개발용 Android PostureLab 전체 화면·AIHub 연구 CLI·기존 Android 저장소의 자동 마이그레이션·계정 서버 동기화는 포함하지 않는다. 현재 앱 데이터는 iPhone 로컬 데이터다.

## 엔진 유지 원칙

`tools/sync_ios_core.py`는 Android Kotlin 정본에서 플랫폼 I/O와 JVM 전용 부분만 바꿔 공통 소스를 생성한다. 임계값·뷰·렙·규칙 정책은 Swift로 재작성하지 않는다. 생성 소스를 손으로 고치지 않고 정본을 수정한 뒤 동기화한다. 원본 38파일의 SHA와 생성 결과 검사에 추가로 중력·부하·음식 기하/기억 및 정본 단위 테스트도 포함한다. 코드/텍스트 자산의 SHA는 Git LF 기준이고 모델은 원시 바이트다. `.gitattributes`가 Mac과 Windows의 자세 텍스트 자산 LF를 고정한다. Android 앱은 현재 기존 엔진을 계속 사용하며 KMP 생성본은 iOS에서 사용한다.

`IosEngine`은 Swift 직렬 큐에서만 호출하는 JSON 경계다. 부팅 후 단조 증가 ms와 미러 전 33개 관절을 받는다. m→cm, 월드 y/z 반전, visibility/presence 최소값, 중력 신선도 250ms와 up 자가검증을 적용한다. **85ms는 화면과 순간 닿음에만**, 준비·판정·카운터·로그는 `now/300` 칸의 첫 프레임으로 유지한다. 열 상태가 나쁘면 추론 간격을 300ms로 줄이므로 순간 접촉을 놓칠 수 있다.

유보는 정상으로 세지 않고 판정 0건에는 점수를 만들지 않는다. TRACK은 점수가 없고 beta는 화면의 참고다. iOS 바닥 beta 피드백도 음성 교정으로 승격하지 않는다. 최신 네 종목의 '세지 않은 이유'는 Android와 동일한 사용자 정의 횟수 입장 조건이며 beta 자세 검사의 승격이 아니다. 자동 횟수와 3D 부하 지수의 기존 검증 한계는 유지한다.

현재 Android `PostureLive`처럼 촬영 메타데이터 없는 개발용 기준선을 실시간 보정에 자동 적용하지 않는다. 수집/백업은 개발 자료로 남기며 세트 하나를 반복 눌러 여러 세트로 만들지 않는다. 세트 중앙값은 공통 `BaselineCollector`가 계산한다. 실시간 개인화는 현재 세트의 시작 관측값이다.

음식 YOLO의 73개 외부 가중치 버퍼는 iOS용 파일에서 inline `Buffer.data`로 보관한다. TensorFlowLiteSwift 2.17의 [C API](https://github.com/tensorflow/tensorflow/blob/v2.17.0/tensorflow/lite/core/c/c_api.cc#L438)가 InterpreterBuilder에 allocation을 전달하지 않아 원본 외부 버퍼를 읽지 못하는 호환 문제를 해결한다. `tools/prepare_ios_food_model.py`는 원본 뒤에 새 버퍼 배열을 붙여 `Model.buffers` 포인터 4바이트만 바꾼다. 모든 버퍼의 가중치가 동일하고, 연산·텐서·양자화·메타데이터를 포함한 원본 바이트가 이 포인터 외에는 바뀌지 않는지 검사한다. 기준표는 `FOOD_MODEL_BASELINE.json`이다. 보존을 위해 원본 영역도 남겨 파일이 3,773,677 → 7,236,576바이트로 늘어난다. Android YOLO와 나머지 두 음식 모델은 수정하지 않는다.

## Mac 준비와 빌드

필수: JDK 21, Python 3, Xcode 26 계열의 초기 설정, XcodeGen, CocoaPods. [Kotlin 공식 호환표](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)의 2.3.20–2.3.21 행은 Xcode 26.0을 제시한다. 이전 진단 앱의 Xcode 16.2 환경을 새 공통 엔진에 그대로 사용하지 않는다. 더 최신 Xcode에서의 지원 여부는 실제 빌드로 확인한다.

저장소 루트에서 다음을 실행한다. Gradle 실행 비트에 의존하지 않도록 `bash gradlew`를 쓴다.

```bash
python3 -m pip install flatbuffers==25.12.19 tflite==2.18.0
bash iosApp/scripts/setup-mac.sh
open iosApp/Trex.xcworkspace
```

XcodeGen 정본은 `iosApp/project.yml`이다. 생성된 `.xcodeproj/.xcworkspace`와 `TrexPods`는 Git에서 제외한다. 새 앱의 Podfile 정본은 `Podfile.trex`이며 기존 진단 앱 의존성과 분리한다. MediaPipeTasksVision 1.0.0 / TensorFlowLiteSwift 2.17.0을 지정했다. 음식 TFLite는 동적 `TrexFoodRuntime.framework` 안에 정적으로 연결한다. 공개 심볼 목록으로 TFLite C/C++ 구현을 프레임워크 내부에 두고, 앱과의 Swift 경계에는 TensorFlowLite 타입을 공개하지 않는다. IPA 검사도 이 프레임워크의 포함과 네이티브 공개 심볼 0개를 확인한다. 검증한 기기용 아키텍처는 arm64, 시뮬레이터도 arm64다.

Xcode의 `Trex` scheme → 연결한 iPhone → Signing & Capabilities에서 `Trex`와 `TrexFoodRuntime`의 본인 Team → Run 순서로 실행한다. 서명 오류 때문에 앱을 삭제하면 로컬 기록이 사라지므로 앱의 백업 기능으로 먼저 내보낸다. Team·인증서·계정·프로비저닝 파일은 Git에 올리지 않는다.

공통 프레임워크는 Xcode pre-build에서 [Kotlin 직접 통합](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)의 `embedAndSignAppleFrameworkForXcode`로 빌드한다. `-PiosOnly=true`는 Android 앱을 구성하지 않아 Mac에 Android SDK가 없어도 iOS 경로를 실행할 수 있게 한다. Swift MediaPipe 설치는 CocoaPods를 사용하지만 Kotlin 모듈의 CocoaPods 플러그인은 사용하지 않는다.

```bash
bash gradlew -PiosOnly=true :posture-core:jvmTest
bash iosApp/scripts/test-simulator.sh
bash iosApp/scripts/build-unsigned-ipa.sh
```

마지막 명령은 generic iPhone Release 빌드 → `.app`의 원본 자세 자산·iOS 음식 모델 SHA/기준표 검사 → `Payload/Trex.app` 패키징 순서다. 모두 성공하면 `iosApp/build/sideload/TREX-unsigned.ipa`가 나온다. 이는 Sideloadly 입력 파일이며 다운로드만으로 실행되는 서명된 배포 파일이 아니다. 각 테스터의 서명·기기 신뢰·개발자 모드와 계정별 제한은 별도 설치 과정이다.

GitHub Actions에는 `iOS 검증과 미서명 IPA`를 추가했다. `ios-redesign`을 원격에 푸시하면 처음부터 실행되도록 push 트리거를 두었다(새 workflow가 기본 브랜치에 아직 없으면 수동 dispatch만으로 첫 실행을 할 수 없기 때문). 공통 테스트·기기용 빌드·시뮬레이터 XCTest가 성공한 경우 IPA를 artifact로 받는다. workflow가 기본 브랜치에 등록되면 수동 실행도 가능하다. 자동 배포·TestFlight 업로드·인증서 저장은 하지 않는다. 이 작업에서 `ios-redesign` 푸시로 원격 macOS 실행을 시작했다.

[검증한 IPA 다운로드](https://github.com/LeeDongHyun00/TREX/actions/runs/37495731624/artifacts/11427878651)에서 `TREX-unsigned-IPA` ZIP을 받은 뒤 `TREX-unsigned.ipa`를 꺼낸다. 코드 기준은 `e52b15be`이며 artifact 보관 기한은 2027-01-04다. 이후에는 위 Mac 스크립트 또는 새 Actions 실행으로 다시 생성한다. 문서만 갱신한 최종 커밋은 중복 빌드를 건너뛴다.

## 확인한 결과와 다음 검증

Windows에서 Android `testDebugUnitTest` **557/557**, `assembleDebug`, 공통 엔진 JVM **376/376**(정본 366건 + iOS 경계 10건), Swift 31파일의 tree-sitter 구문 검사, XcodeGen 자산 경로·plist·정본 동기화 검사를 통과했다. tree-sitter 검사는 **Swift 타입 검사와 SDK 링크를 보장하지 않는다.** 공통 회귀는 실제 연구 피처 픽스처를 JSON 관절 입력으로 바꿔 월드 축과 정본이 반전으로 판단하는 up 표본도 확인한다.

macOS Actions에서 기기용 framework/Swift 컴파일·Release 링크·원본 자세 자산과 호환 음식 모델 SHA·내부 공개 심볼 0개·IPA 생성, 시뮬레이터 XCTest 4건(자산/가이드·백업의 유보/자가 라벨·85ms/오래된 프레임 차단·세 음식 모델과 자세 모델의 실제 동시 추론)을 통과했다. SDK 추론 검사는 빈 입력 이미지의 실행 검사이며 자세 판정 정확도나 실제 음식 사진 정확도를 검증한 것은 아니다. 남은 검증은 사용자가 직접 하는 iPhone 서명·실행이다. 기기에서 전후면 좌우·기울어진 폰·관절 누락·카메라 거부·정지/복귀·온도 상승을 확인하고, 네 종목의 실제 짝/기각/순간 접촉과 사진의 방향/영역·한국어 음성·3D 표시를 대조한다. Android와 SDK 버전·CPU 경로가 다르므로 자동 검사 통과를 실기기 자세 정확도 검증으로 읽지 않는다.

## 저장 형식

앱 데이터는 `Application Support/TREX/data.json`의 schema 1이며 iOS 백업만 복원한다. 음식 기억은 같은 폴더의 `food-memory.json`에 보관하고 사진 원본은 저장하지 않는다.

자세 로그는 `posture_logs/<record UUID>.jsonl`이다. 첫 줄 `kind=ios_session, schema=1`은 종목·모드·시작 시각·판정 간격·앵커 시각이고 이후 `kind=ios_frame`은 상대 시각·판정 피처·원본 xy/world/visibility·크기·중력이다. 카메라 영상은 없다. **Android `SetLogJson`의 완전한 재생 형식은 아니며 기존 연구 로그 도구에 그대로 넣지 않는다.** 현재 로그에는 준비 원시 프레임·카운터 전체 상태가 없어 처음부터의 동일 횟수 재생을 보장하지 않는다. 연구 도구 통합은 iPhone 첫 로그를 확보한 다음 별도로 한다.
