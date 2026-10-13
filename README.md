# TREX Kotlin

TREX는 Android Kotlin과 Jetpack Compose로 만든 운동/식단 관리 앱입니다. 운동 루틴, 자세 교정 모드, 식단 기록, 온보딩과 **Google 계정 백업·기기 간 동기화**(Spring Boot 서버, AWS 운영)를 포함합니다.

## Android 체험판 다운로드

**[TREX APK 다운로드 · v1.3.0-preview.4](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.3.0-preview.4)**
[릴리스 설명 및 파일 확인](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.3.0-preview.4)
휴대폰 한 대로 운동 루틴을 진행하고, 카메라에 잡힌 관절을 바탕으로 자세 변화를 확인해 보세요. 운동에 맞는 촬영 방향을 먼저 안내하고, 몸이 화면에 들어오면 5초 카운트다운 후 시작합니다. 짧은 인식 끊김에는 남은 준비 시간을 유지하며, 준비가 진행되지 않으면 오른쪽 아래 **준비 건너뛰기**로 바로 시작할 수 있습니다.

- 운동별 횟수·시간·세트·휴식 설정, 길게 눌러 순서 변경, 스와이프 삭제
- 운동 수정에서 세트·횟수/시간·세트 간 휴식을 숫자 휠로 설정하고, 목록 맨 아래에서 운동 추가
- 기본 스쿼트·런지·덤벨 컬의 `운동 방법`: 자동 재생 시범과 운동 가이드·주의사항
- 운동별 자세 교정 선택과 화면·음성 안내, 완료 상태 표시
- **기록 모드**: 처음 자세를 기준으로 운동 중 변화를 안내하며 자동 횟수를 기록
- 최근 7일 운동 기록과 날짜별 식단·섭취 영양 조회
- 실제 기록 기반 홈 카드, 운동 중심·세트·예상 시간 요약과 날짜별 운동 평가
- 운동·끼니를 눌러 바로 편집하고, 접힘 경계와 창 크기에 맞춘 화면 배치
- 큰 카메라 미리보기와 하단 횟수·목표 표시, 5초 준비 완료 시 전체 화면 시작
- **자세 교정 / 기록 모드**를 직접 선택하는 모드 바, 끼니 시간대를 구분하는 상징

2026-09-27 업데이트: 운동 준비 화면의 설명을 촬영 그림과 방향 안내 중심으로 줄였습니다. 자세 평가의 일부 반복 검사와 종목 적용 범위도 갱신했습니다. 새 시범 3개는 두 자세를 번갈아 보여주는 시안이므로, 연속 동작 교육 영상이나 전문가 검증 자료로 해석하지 마세요. 이번 버전은 자동 테스트와 빌드로 확인했으며 실휴대폰 사용 검증은 사용자가 진행합니다.

### 설치 방법

1. **Android 8.0 이상** 휴대폰에서 위 APK를 내려받으세요.
2. 다운로드한 파일을 열고, 필요한 경우 해당 브라우저 또는 파일 앱의 **이 출처 허용**을 켜서 설치하세요.
3. 앱에서 운동을 선택하세요. 자세 교정을 켜면 카메라 권한을 허용하고 안내한 방향으로 몸을 돌려 준비하세요. 자세 교정을 끄면 카메라 없이 횟수·시간으로 진행할 수 있습니다.

현재 배포본은 **개발 서명된 미리보기 빌드**입니다. 로그인 화면은 서버 계정 인증에 연결되지 않은 체험용이며(다음 버전부터 Google 계정 백업 지원 — 아래 [시스템 구성](#시스템-구성-계정-백업동기화) 참고), 사진 자동 분석은 아직 연결되지 않아 음식 선택으로 기록합니다. 운동·식단 기록은 기기에 저장됩니다. 자세 인식과 자동 횟수는 촬영 환경에 따라 달라질 수 있고, 보이지 않는 항목은 평가를 유보합니다. 참고 단계의 규칙은 확정적인 음성 교정으로 안내하지 않습니다.

운동 기록은 오늘을 포함한 최근 7일을 보관하며, 기간이 지난 기록은 앱 실행·복귀 시 정리됩니다. 기존 동일 서명의 TREX는 앱을 삭제하지 않고 업데이트할 수 있습니다. 서명이 다른 개발 APK와 충돌하면 기록 보존을 위해 기존 앱을 먼저 삭제하지 말고 확인해 주세요.

구현 소스: [`main`](https://github.com/LeeDongHyun00/TREX/tree/main). APK는 Git 저장소에 넣지 않고 GitHub Releases에서 제공합니다. 파일 무결성은 릴리스의 `SHA256SUMS.txt`로 확인할 수 있습니다.

## 개발 환경

- Android Studio 최신 안정 버전 권장
- JDK 17
- Android SDK 36
- Gradle Wrapper 포함: 별도 Gradle 설치 불필요
- 최소 실행 SDK: 26

## Android Studio에서 실행하기

1. 저장소를 클론합니다.

   ```powershell
   git clone --branch main https://github.com/LeeDongHyun00/TREX.git
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
.\gradlew.bat :app:assembleRelease
.\gradlew.bat lintDebug
```

macOS/Linux 기준:

```bash
./gradlew assembleDebug
./gradlew :app:assembleRelease
./gradlew lintDebug
```

## 프로젝트 구조

이 저장소는 앱과 서버를 함께 두는 **모노레포**입니다. 둘은 서로 독립된 빌드이고, 루트의
`./gradlew` 는 **Android 앱만** 빌드합니다. 서버는 `server/` 안에서 따로 빌드합니다.

| 위치 | 내용 | 빌드 |
|---|---|---|
| `app/` | Android Compose 앱 (Kotlin) | 루트 `./gradlew :app:assembleDebug` |
| `server/` | REST API 서버 (Spring Boot / Java 17) — 계정·동기화, AWS 운영 배포 파일(`server/deploy/`) | `cd server && ./gradlew bootRun` |
| `research/` | 자세 평가 연구 코드·스펙 | — |
| `docs/` | 설계 근거·인수인계 문서 | — |
| `assets/` | 디자인·원본 이미지 자료 | — |
| `trex_design_react/` | React 기반 디자인 프로토타입 | — |

앱 코드 세부:

- `app/src/main/java/com/example/trex_kotlin/`: Android Compose 앱 코드
- `app/src/main/res/`: Android 리소스
- `app/src/main/res/drawable-nodpi/`: 로그인 애니메이션 프레임 이미지 등 원본 크기 리소스

서버 설정: 로컬 개발은 `server/src/main/resources/application-example.yml` 을 `application.yml` 로 복사해 쓰고,
`JWT_SECRET` 은 환경변수로 주입합니다. 예시 파일의 기본값은 로컬 개발용 자리표시자입니다. 운영은
`application-prod.yml`(`SPRING_PROFILES_ACTIVE=prod`)을 쓰고 비밀값은 전부 환경변수(`DB_URL`·`DB_USERNAME`·
`DB_PASSWORD`·`JWT_SECRET`·`GOOGLE_CLIENT_IDS`)로만 받습니다 — 예시는 `server/deploy/.env.example`.

## 시스템 구성 (계정 백업·동기화)

![T-REX 시스템 구성도](docs/architecture/trex-aws.png)

앱은 **계정 없이도 모든 기능을 쓸 수 있고**, 기록은 기기에 저장됩니다. Google 계정을 연결하면 기록을 서버에 백업해
앱을 다시 설치하거나 기기를 바꿔도 이어서 쓸 수 있습니다.

| 구성 요소 | 역할 |
|---|---|
| Android 앱 | 시작 화면의 **Google로 계속하기 / 로그인 없이 사용**, 설정의 계정 연결·지금 동기화·회원 탈퇴. 기록이 바뀌면 3초 모아 서버에 올리고, 앱 시작·로그인 때 더 최신 기록을 받습니다 |
| Google | 앱이 Credential Manager 로 받은 ID 토큰을 서버가 구글 공개키(JWKS)로 검증합니다 — 앱이 보낸 사용자 정보를 그대로 믿지 않습니다 |
| DuckDNS | 무료 도메인 `trex-api.duckdns.org`. HTTPS 인증서는 IP 가 아니라 도메인에만 발급되기 때문에 씁니다 |
| EC2 (Ubuntu · Docker Compose) | Caddy(HTTPS 인증서 자동 발급·갱신) → Spring Boot 서버. 서버 포트(8080)는 밖으로 열지 않습니다 |
| RDS PostgreSQL | 회원·동기화 데이터. 퍼블릭 접근 없이 EC2 보안 그룹에서만 접속합니다 |

동기화 규칙

- 항목(프로필·운동 계획·운동 기록·식단·물·직접 등록한 음식·영양 목표)마다 통째로 백업하고, 같은 항목은 **나중에 바꾼 쪽이 이깁니다**.
- 처음 연결할 때 서버가 비어 있으면 이 기기 기록을 올리고, 이 기기가 비어 있으면(새 기기) 서버 기록을 받습니다. 양쪽에 모두 기록이 있으면 어느 쪽을 쓸지 묻습니다 — 자동으로 섞지 않습니다.
- 내 음식 사진 기억(사진 특징값)과 자세 기준선·자세 로그는 서버로 보내지 않습니다.
- 회원 탈퇴는 서버의 계정과 백업을 모두 지우고, 기기의 기록은 남깁니다.

이 기능은 v1.3.0-preview.4 APK 에는 들어 있지 않습니다. 공개 릴리스에는 개인정보 처리방침·동의 화면을 붙인 뒤 포함할 예정입니다.

### 개발자용

- 설계·결정·진행 상황: [`docs/SERVER_SYNC_HANDOFF.md`](docs/SERVER_SYNC_HANDOFF.md) — 서버·로그인·동기화 작업 전에 먼저 읽습니다.
- 서버 API: `POST /api/auth/oauth/google`, `GET /api/sync`, `PUT /api/sync/{key}`, `DELETE /api/auth/me` (+ 기존 회원·프로필·기록 API).
- 앱 설정값은 `gradle.properties` 에 있습니다(비밀값 아님, `local.properties` 에 같은 키가 있으면 그쪽이 우선): `trex.googleWebClientId`(Google **웹** 클라이언트 ID), `trex.apiBaseUrl`(릴리스), `trex.apiBaseUrl.debug`(디버그). 로컬 서버로 확인하려면 `local.properties` 에 `trex.apiBaseUrl.debug=http://127.0.0.1:8080` 을 넣고 `adb reverse tcp:8080 tcp:8080`.
- Google 로그인은 **APK 서명 인증서의 SHA-1 이 Google Cloud 콘솔의 Android OAuth 클라이언트에 등록돼 있어야** 됩니다. 다른 PC·서명 키로 빌드한 APK 는 `28444 Developer console is not set up correctly` 가 납니다 — 그 SHA-1 로 Android 클라이언트를 하나 더 등록하세요.
- 운영 배포(AWS EC2 + RDS, Docker Compose): [`server/deploy/`](server/deploy/) 와 인수인계 문서 8절 런북.
- 구성도 원본: [`docs/architecture/trex-aws.drawio`](docs/architecture/trex-aws.drawio) — draw.io(웹 app.diagrams.net 또는 VS Code "Draw.io Integration")로 열어 고치고 PNG 로 다시 내보냅니다.

## Git에 포함하지 않는 파일

다음 파일은 개인 환경 또는 빌드 산출물이므로 커밋하지 않습니다.

- `local.properties`
- `.gradle/`, `.kotlin/`, `build/`
- `.idea/`
- APK/AAB 산출물
- `outputs/` 로컬 시안·검증·연구 산출물
- 키스토어와 `.env` 파일(운영 서버 비밀값 `server/deploy/.env` 포함), AWS 키 페어(`.pem`)
- `server/src/main/resources/application.yml`(로컬 서버 설정)

## 참고

`local.properties`는 Android Studio가 로컬 SDK 경로에 맞춰 자동 생성합니다. 다른 개발자는 각자 Android Studio에서 프로젝트를 열면 자신의 환경에 맞게 다시 생성됩니다.
