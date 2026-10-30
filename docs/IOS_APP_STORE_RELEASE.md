# iPhone App Store 업로드 (스펙 §121)

App Store Connect 는 2026-04-28 부터 **Xcode 26(iOS 26 SDK)으로 만든 빌드만** 받는다. 저장소 주인의 Mac(2017 MacBook Pro)은 macOS 14 가 상한이라 Xcode 26 을 설치할 수 없다 — 이 Mac 의 Xcode 16.2 로 만든 아카이브는 업로드 단계에서 거부된다. 그래서 배포 빌드는 GitHub Actions 의 **`iOS App Store 업로드`**(`.github/workflows/ios-release.yml`)가 만든다.

| 단계 | 하는 일 |
|---|---|
| 아카이브 | `macos-15` 러너의 Xcode 26 으로 Release 아카이브. `TREX_DEV_TOOLS=NO`(§116·§120), 빌드 번호 = 입력값 또는 실행 번호 |
| 검사 | 자세 모델·규칙 바이트(`verify_bundle_assets.py`), 개인정보 매니페스트 두 개, `DTSDKName` 이 iOS 26 이상인지 |
| 업로드 | `xcodebuild -exportArchive`(`iosApp/ExportOptions-AppStore.plist`, `destination=upload`) — 배포 서명은 Apple 클라우드 관리 인증서 |

## 1. 저장소 시크릿 다섯 개 (한 번만)

GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret. 비밀값은 채팅·이슈에 붙이지 말고 직접 넣는다.

| 이름 | 값 | 만드는 곳 |
|---|---|---|
| `ASC_KEY_ID` | API 키 ID(10자) | App Store Connect → 사용자 및 액세스 → 통합 → App Store Connect API → 팀 키 → **생성**, 역할 **Admin**(클라우드 배포 서명에 필요) |
| `ASC_ISSUER_ID` | 발급자 ID(UUID) | 같은 화면 위쪽 |
| `ASC_KEY_P8` | 내려받은 `AuthKey_<키ID>.p8` 파일 **내용 전체** | 생성 직후 한 번만 내려받을 수 있다 |
| `BUILD_CERT_P12` | 개발 인증서 `.p12` 의 base64 | 아래 |
| `BUILD_CERT_PASSWORD` | 그 `.p12` 를 내보낼 때 정한 암호 | 아래 |

**개발 인증서**: 이 Mac 의 키체인 접근 → 로그인 → 내 인증서 → `Apple Development: hp27654@gmail.com` 을 오른쪽 클릭 → 내보내기(.p12, 암호 지정). 자동 서명의 아카이브는 개발 인증서로 서명하는데, 새 러너에는 개인 키가 없어 API 키만 주면 실행마다 개발 인증서를 새로 만들거나 기존 것을 폐기하려 한다 — 그래서 이 인증서를 임시 키체인에 넣는다(실행이 끝나면 지운다).

터미널에서 넣는다면(`gh` 로그인 상태, 파일 경로는 본인 것으로):

```bash
gh secret set ASC_KEY_P8 < ~/Downloads/AuthKey_XXXXXXXXXX.p8
```

```bash
base64 -i ~/Desktop/trex-dev.p12 | gh secret set BUILD_CERT_P12
```

```bash
gh secret set BUILD_CERT_PASSWORD
```

`ASC_KEY_ID`·`ASC_ISSUER_ID` 도 `gh secret set <이름>` 으로 넣으면 값을 물어본다. 다 넣은 뒤 `.p12`·`.p8` 파일은 안전한 곳에 옮기거나 지운다.

## 2. 실행

Actions → **iOS App Store 업로드** → Run workflow → 브랜치 `main`. 빌드 번호를 비우면 실행 번호가 쓰인다(같은 버전 `1.3.0` 안에서 이전 업로드보다 커야 한다).

```bash
gh workflow run ios-release.yml --ref main
```

`workflow_dispatch` 워크플로는 파일이 기본 브랜치(main)에 있어야 목록에 나온다.

## 3. 업로드 뒤

1. App Store Connect → TestFlight 에서 빌드 처리가 끝날 때까지 기다린다(수 분~수십 분). 문제가 있으면 Apple 이 계정 메일로 `ITMS-…` 사유를 보낸다.
2. 배포 → iOS 앱 1.3.0 → 빌드 칸에서 방금 빌드를 고른다. 수출 규정 질문은 `ITSAppUsesNonExemptEncryption = false`(§118)라 생략된다.
3. TestFlight 설치본에서 실검증 도구가 꺼졌는지 실기기로 확인한다 — 완료 화면의 자가 라벨 칸이 없고 `Documents/posture_logs` 가 생기지 않아야 한다(§120 미검증 항목).

## 실패하면

| 증상 | 원인 |
|---|---|
| `저장소 시크릿이 없습니다` | 1단계 시크릿 누락 |
| `Cloud signing permission error` | API 키 역할이 Admin 이 아니다 |
| `No profiles for 'com.leedonghyun.trex.ios'` | API 키 권한 또는 App ID 의 Sign in with Apple 기능 |
| `ITMS-91053 Missing API declaration` 메일 | 앱 바이너리가 새 '사유 필요 API' 를 쓴다 — `iosApp/Trex/Resources/PrivacyInfo.xcprivacy` 에 범주·사유를 더한다 |
| 빌드 번호 중복 | 입력에 더 큰 `build_number` 를 넣어 다시 실행 |
