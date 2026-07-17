# TREX Kotlin

TREX는 Android Kotlin과 Jetpack Compose로 만든 운동/식단 관리 앱입니다. 운동 루틴, 자세 교정 모드, 식단 기록, 로그인/온보딩 화면을 포함합니다.

## 개발 환경

- Android Studio 최신 안정 버전 권장
- JDK 17
- Android SDK 36
- Gradle Wrapper 포함: 별도 Gradle 설치 불필요
- 최소 실행 SDK: 26

## Android Studio에서 실행하기

1. 저장소를 클론합니다.

   ```powershell
   git clone https://github.com/LeeDongHyun00/TREX.git
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
- `app/src/main/java/com/example/trex_kotlin/camera/`: CameraX + MediaPipe 온디바이스 자세 추정
- `app/src/main/java/com/example/trex_kotlin/pose/`: 운동별 관절 필터·반복 상태 머신·자세 판정
- `assets/`: 디자인/원본 이미지 자료
- `trex_design_react/`: React 기반 디자인 프로토타입
- `docs/pose-correction-system.md`: 실시간 자세 교정의 기기 구현·검증 설계
- `docs/pose-data-audit.md`: `data/` 전수 구조·무결성·라벨 품질 감사 결과
- `docs/aihub-exercise-catalog.md`: 실제 2D JSON 기반 운동 manifest·생성·앱 연결 규칙

## Git에 포함하지 않는 파일

다음 파일은 개인 환경 또는 빌드 산출물이므로 커밋하지 않습니다.

- `local.properties`
- `.gradle/`, `.kotlin/`, `build/`
- `.idea/`
- APK/AAB 산출물
- 키스토어와 `.env` 파일
- 로컬 학습 데이터인 `data/` (앱과 Git에 포함하지 않음)

## 참고

`local.properties`는 Android Studio가 로컬 SDK 경로에 맞춰 자동 생성합니다. 다른 개발자는 각자 Android Studio에서 프로젝트를 열면 자신의 환경에 맞게 다시 생성됩니다.
