# TREX Kotlin

TREX는 Android Kotlin과 Jetpack Compose로 만든 운동/식단 관리 앱입니다. 운동 루틴, 자세 교정 모드, 식단 기록, 로그인/온보딩 화면을 포함합니다.

## Android 체험판 다운로드

**[TREX APK 다운로드 · v1.1.0-preview.1](https://github.com/LeeDongHyun00/TREX/releases/download/v1.1.0-preview.1/TREX-1.1.0-preview.1.apk)**
[릴리스 설명 및 파일 확인](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.1.0-preview.1)

휴대폰 한 대로 운동 루틴을 진행하고, 카메라에 잡힌 관절을 바탕으로 자세 변화를 확인해 보세요. 운동에 맞는 촬영 방향을 먼저 안내하고, 몸이 화면에 들어오면 5초 카운트다운 후 시작합니다. 짧은 인식 끊김에는 남은 준비 시간을 유지하며, 준비가 진행되지 않으면 오른쪽 아래 **준비 건너뛰기**로 바로 시작할 수 있습니다.

- 운동별 횟수·시간·세트·휴식 설정, 길게 눌러 순서 변경, 스와이프 삭제
- 운동별 자세 교정 선택과 화면·음성 안내, 완료 상태 표시
- **기록 모드**: 처음 자세를 기준으로 운동 중 변화를 안내하며 자동 횟수를 기록
- 최근 7일 운동 기록과 날짜별 식단·섭취 영양 조회

### 설치 방법

1. **Android 8.0 이상** 휴대폰에서 위 APK를 내려받으세요.
2. 다운로드한 파일을 열고, 필요한 경우 해당 브라우저 또는 파일 앱의 **이 출처 허용**을 켜서 설치하세요.
3. 앱에서 운동을 선택하세요. 자세 교정을 켜면 카메라 권한을 허용하고 안내한 방향으로 몸을 돌려 준비하세요. 자세 교정을 끄면 카메라 없이 횟수·시간으로 진행할 수 있습니다.

현재 배포본은 **개발 서명된 미리보기 빌드**입니다. 로그인 화면은 서버 계정 인증에 연결되지 않은 체험용이며, 운동·식단 기록은 기기에 저장됩니다. 자세 인식과 자동 횟수는 촬영 환경에 따라 달라질 수 있고, 보이지 않는 항목은 평가를 유보합니다. 참고 단계의 규칙은 확정적인 음성 교정으로 안내하지 않습니다.

운동 기록은 오늘을 포함한 최근 7일을 보관하며, 기간이 지난 기록은 앱 실행·복귀 시 정리됩니다. 기존 동일 서명의 TREX는 앱을 삭제하지 않고 업데이트할 수 있습니다. 서명이 다른 개발 APK와 충돌하면 기록 보존을 위해 기존 앱을 먼저 삭제하지 말고 확인해 주세요.

구현 소스: [`feature/posture-coach-reliability`](https://github.com/LeeDongHyun00/TREX/tree/feature/posture-coach-reliability). APK는 Git 저장소에 넣지 않고 GitHub Releases에서 제공합니다. 파일 무결성은 릴리스의 `SHA256SUMS.txt`로 확인할 수 있습니다.

## 개발 환경

- Android Studio 최신 안정 버전 권장
- JDK 17
- Android SDK 36
- Gradle Wrapper 포함: 별도 Gradle 설치 불필요
- 최소 실행 SDK: 26

## Android Studio에서 실행하기

1. 저장소를 클론합니다.

   ```powershell
   git clone --branch feature/posture-coach-reliability https://github.com/LeeDongHyun00/TREX.git
   cd TREX
   ```

2. Android Studio에서 `Open`을 누르고 이 프로젝트의 루트 폴더를 선택합니다.

3. Gradle Sync가 자동으로 실행될 때까지 기다립니다.

4. SDK 관련 오류가 나면 Android Studio의 `SDK Manager`에서 Android SDK 36을 설치합니다.

5. 실행 구성에서 `app` 모듈을 선택하고 에뮬레이터 또는 실제 기기로 실행합니다.

## 명령어로 빌드 확인하기

Windows PowerShell 기준:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

macOS/Linux 기준:

```bash
./gradlew assembleDebug
./gradlew lintDebug
```

## 프로젝트 구조

- `app/src/main/java/com/example/trex_kotlin/`: Android Compose 앱 코드
- `app/src/main/res/`: Android 리소스
- `app/src/main/res/drawable-nodpi/`: 로그인 애니메이션 프레임 이미지 등 원본 크기 리소스
- `assets/`: 디자인/원본 이미지 자료
- `trex_design_react/`: React 기반 디자인 프로토타입

## Git에 포함하지 않는 파일

다음 파일은 개인 환경 또는 빌드 산출물이므로 커밋하지 않습니다.

- `local.properties`
- `.gradle/`, `.kotlin/`, `build/`
- `.idea/`
- APK/AAB 산출물
- 키스토어와 `.env` 파일

## 참고

`local.properties`는 Android Studio가 로컬 SDK 경로에 맞춰 자동 생성합니다. 다른 개발자는 각자 Android Studio에서 프로젝트를 열면 자신의 환경에 맞게 다시 생성됩니다.
