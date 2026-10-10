# TREX Kotlin

TREX는 Kotlin과 Jetpack Compose로 만든 Android 운동·식단 관리 앱입니다.
휴대폰 카메라로 관절을 추정해 자세 변화·반복·유지 시간을 안내하고 루틴과 수행 기록을 기기에 저장합니다.
이 README는 **`redesign` 브랜치와 Android 체험판 1.3.0-preview.7** 기준입니다.

## 체험판 다운로드

**[TREX 1.3.0-preview.7 APK 다운로드](https://github.com/LeeDongHyun00/TREX/releases/download/v1.3.0-preview.7/TREX-1.3.0-preview.7.apk)**

[릴리스 설명·파일·체크섬](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.3.0-preview.7) ·
[상세 배포 문서](docs/ANDROID_PREVIEW_RELEASE_1_3_0_7.md)

| 항목 | 현재 배포본 |
|---|---|
| 버전 | `1.3.0-preview.7` |
| 버전 코드 | `12` |
| Android | 8.0 이상(API 26) |
| 패키지 | `com.example.trex_kotlin` |
| 서명 | 기존 체험판과 동일한 개발 인증서 |
| 릴리스 자산 | APK · `SHA256SUMS.txt` · `BUILD_INFO.json` |

### preview.6에서 업데이트하기

**preview.6(versionCode 11)은 preview.7(versionCode 12)의 앱 내 업데이트 안내 대상입니다.**
앱은 공개된 GitHub 릴리스와 `BUILD_INFO.json`을 읽고 설치본보다 버전 코드가 크면 업데이트 대화상자를 표시합니다.

- 마지막 확인 이후 **6시간 이상** 지난 상태에서 앱을 새로 실행하면 확인합니다. 네트워크 연결이 필요합니다.
- 운동 중·완료 화면·세부 화면에서는 확인과 대화상자 표시를 보류합니다.
- 릴리스의 서명 정보를 대조하고, **업데이트**를 누르면 APK를 내려받아 해시를 확인한 뒤 Android 설치 화면을 엽니다.
- **나중에**를 누르거나 대화상자를 닫으면 같은 버전 안내를 **24시간** 미룹니다.
- 앱을 계속 켜 둔 상태에서 6시간마다 자동으로 대화상자를 다시 띄우는 방식은 아닙니다.

안내가 바로 보이지 않으면 확인 주기가 지난 뒤 앱을 완전히 종료하고 다시 실행하거나 위 APK를 직접 내려받을 수 있습니다.
동일 서명 앱 위에 **업데이트 설치**하면 기존 기록을 유지할 수 있습니다.

### 처음 설치하기

1. Android 8.0 이상 휴대폰에서 APK를 내려받습니다.
2. 파일을 열고 필요한 경우 브라우저·파일 앱 또는 TREX의 **이 출처의 앱 허용**을 켭니다.
3. 앱에서 운동을 선택합니다. 카메라 자세 교정을 사용하면 카메라 권한을 허용하고 촬영 안내에 맞춰 준비합니다.
4. 카메라를 끄면 수동 횟수·시간으로 루틴을 진행할 수 있습니다.

## preview.7의 변경

- **크런치·레그 레이즈·플랭크 가이드**: 시작 자세·동작·호흡·주의사항을 추가했습니다. 크런치·레그 레이즈는 GIF, 플랭크는 첫 프레임 정지 이미지입니다.
- **바닥 운동**: 크런치·레그 레이즈의 반복 추적과 플랭크 유지 시간 처리를 개선했습니다.
- **자세와 횟수**: 스쿼트 복귀·발끝·무릎 벌림과 덤벨 컬·런지·한 다리 운동의 판별·교정 안내를 보완했습니다.
- **음성과 시선**: 말하기 대기열·준비 설명을 정리하고 바닥 세 종목에 시선 벗어남·복귀 안내를 추가했습니다.
- **촬영 화면**: 세션 중 폰 방향을 따라 가로·세로 배치를 전환하고 뼈대·교정 화살표를 보완했습니다.

전체 변경과 알려진 한계는 [preview.7 릴리스 문서](docs/ANDROID_PREVIEW_RELEASE_1_3_0_7.md)에 있습니다.

## 주요 기능

### 운동

- 운동별 횟수 또는 시간·세트·휴식 설정과 루틴 순서 변경
- 카메라 자세 교정·기록 모드, 운동별 촬영 방향·준비 안내
- 화면·음성 교정과 자동 반복·플랭크 유지 시간 표시
- 세트 결과와 날짜별 운동 기록, 최근 7일 기록 조회
- 기록 기반 근육별 부하 추정과 3D 사용 근육 표시
- 창 크기·접힘 경계와 운동 중 가로·세로 방향을 고려한 배치

**운동 방법** 가이드는 총 10종목입니다.

| 종목 | 시범 |
|---|---|
| 기본 스쿼트 · 런지 · 덤벨 컬 | GIF |
| 크로스 런지 · 사이드 런지 · 스탠딩 니업 · 스탠딩 사이드 크런치 | GIF |
| 크런치 · 레그 레이즈 | GIF |
| 플랭크 | 정지 이미지 |

목록·운동 선택·세션에서 같은 **운동 가이드 / 주의사항** 두 탭을 볼 수 있습니다.
레그 레이즈는 바닥에 누워 두 다리를 함께 드는 동작, 플랭크는 전완 지지 자세를 안내합니다.

### 식단

- 음식 검색·직접 등록·섭취량 수정과 끼니별 영양 기록
- 촬영·갤러리 사진의 기기 내 음식 분석과 결과 수정
- 수정한 음식 정보를 기억해 다음 분석에 활용
- 날짜별 식단과 섭취 영양 조회

운동·식단 기록은 기기에 저장됩니다. 로그인·온보딩 화면은 체험용이며 서버 계정 인증에 연결되어 있지 않습니다.

## 체험판의 검증 범위

preview.7은 앱 단위 테스트 **704건**, 재생기 테스트 **144건**, 로그 검증 **54/54**가 통과했습니다.
release APK 빌드·필수 lint·서명 검증과 업로드 자산의 해시 일치를 확인했습니다.
**이번 릴리스의 실제 휴대폰 화면·운동 세션 검증은 수행하지 않았습니다.**

가림·촬영 방향·관절 추정 때문에 자세 판정과 반복·유지 시간이 실제 수행과 다를 수 있습니다.
판정하지 못한 항목과 참고 단계의 결과는 확정 판정과 구분해 읽어야 합니다.
허리 접지나 시선의 모든 오류를 관측할 수 있는 것은 아니며 일부 기준은 폰 라벨 검증 중입니다.
생성 시범은 연속 촬영이나 전문가 검수를 거친 교육 영상이 아닙니다.
근육별 부하는 운동 기록 기반 추정치이며 생리학적 피로나 회복 완료를 측정하지 않습니다.

현재 APK는 개발 인증서로 서명한 체험판입니다. 서명이 다른 개발 APK와 충돌하면 기록 보존을 위해 기존 앱을 먼저 삭제하지 마세요.
문제가 확인되면 기록을 보존하면서 더 높은 버전 코드의 수정판으로 업데이트합니다.

## 개발 환경과 실행

- 이번 릴리스 빌드 환경: JDK 21, Android SDK 36
- JVM 컴파일 대상: Java 17
- Gradle Wrapper 포함, 별도 Gradle 설치 불필요
- 최소 실행 SDK: 26

```powershell
git clone --branch redesign https://github.com/LeeDongHyun00/TREX.git
cd TREX
```

Android Studio에서 프로젝트 루트를 열고 Gradle Sync를 진행합니다.
Android SDK 36을 준비하고 `app` 실행 구성으로 에뮬레이터나 기기에 실행합니다.
SDK 경로는 로컬 `local.properties` 또는 `ANDROID_HOME`으로 지정합니다.

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

macOS/Linux:

```bash
chmod +x gradlew
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

release 변형도 기존 체험판과 동일한 개발 인증서로 서명합니다.
자세 엔진을 수정할 때는 [작업 지침](AGENTS.md)과 [인수인계](docs/POSTURE_HANDOFF.md)를 먼저 읽습니다.

## 프로젝트 구조

앱과 서버를 함께 둔 모노레포입니다. 루트 Gradle Wrapper는 Android 앱을 빌드하고 서버는 `server/`에서 별도로 빌드합니다.

| 위치 | 내용 |
|---|---|
| `app/` | Kotlin·Jetpack Compose 앱 |
| `app/src/main/java/com/example/trex_kotlin/posture/` | 자세·반복·코칭·세트 리포트 엔진 |
| `app/src/main/assets/exercise_guides/` | 운동별 GIF·정지 이미지 |
| `server/` | Spring Boot / Java 17 REST API 서버 |
| `research/` | 자세 평가 연구·재생·검증 코드 |
| `docs/` | 설계·릴리스·인수인계 문서 |
| `training/` | 음식 인식 학습·평가 자료 |
| `assets/` | 디자인·원본 이미지 자료 |
| `trex_design_react/` | React 디자인 프로토타입 |

서버 설정은 `server/src/main/resources/application-example.yml`을 복사해 사용하고 `JWT_SECRET`은 환경변수로 주입합니다.
서버 실행은 `server/`에서 해당 Gradle Wrapper의 `bootRun`을 사용합니다.

## 저장소와 관련 문서

배포 소스는 [`redesign`](https://github.com/LeeDongHyun00/TREX/tree/redesign), 바이너리는 [GitHub Releases](https://github.com/LeeDongHyun00/TREX/releases)에서 제공합니다.
APK 무결성은 `SHA256SUMS.txt`, 버전·서명·빌드 정보는 `BUILD_INFO.json`으로 확인합니다.

- [preview.7 릴리스](docs/ANDROID_PREVIEW_RELEASE_1_3_0_7.md)
- [바닥 3종목 가이드 설계](docs/CORE_EXERCISE_GUIDE_DESIGN.md)
- [운동 가이드 UI](docs/EXERCISE_GUIDE_UI.md)
- [자세 평가 인수인계](docs/POSTURE_HANDOFF.md)
- [자세 엔진 정본 스펙](research/aihub_fitness/KOTLIN_PORTING_SPEC.md)

`local.properties`, Gradle·IDE 캐시, 빌드 산출물, APK/AAB, `outputs/`, 서명키와 `.env`는 Git에 포함하지 않습니다.
