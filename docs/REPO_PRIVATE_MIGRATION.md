# 저장소 비공개 전환 — 절차와 근거 (2026-11-06)

> **상태: 미실행 — 기록만(사용자 결정 2026-11-06 "아직 옮기는 건 진행하지 않을 거고 기록만").** 깃허브 쪽(이름 변경·비공개·새 공개 저장소·릴리스 복사)은 아무것도 하지 않았다. 로컬에서 한 것은 `.gitignore` 한 줄과 이 문서뿐이다.

수익화 결정으로 이 저장소를 비공개로 돌린다. 2026-11-06 점검 결과와 전환 순서를 적는다. 점검은 추적 파일 1,439개와 55개 브랜치 전체 히스토리를 대상으로 했다.

## 1. 점검 결과

**실제 비밀값(키·토큰·비밀번호)은 추적 파일에도 히스토리에도 0건.** 키스토어·`.env`·`local.properties`·서버 `application.yml`·Apple `.p8`·AWS 키·디스코드 웹후크·AI Hub API 키를 이름과 값 패턴으로 찾았다. 서버 설정은 전부 환경변수 주입(`application-prod.yml`), iOS 서명은 보호 환경 `app-store` 시크릿만 쓴다. 그래서 **히스토리를 다시 쓸 이유는 없다** — 비공개 전환으로 충분하다.

비공개로 돌려야 하는 이유는 비밀값이 아니라 아래다.

| 분류 | 내용 |
|---|---|
| 라이선스 | `docs/bridge-frames.full-sweep.v1.json`(AI Hub 프레임별 라벨값 31 MB), `docs/rehab-rep-f1*.json`·`research/external_rep_replay/results/`(REHAB24-6, **CC BY-NC**) |
| 개인 식별 | 저장소 주인 이메일(`docs/IOS_APP_STORE_RELEASE.md`), Apple Team ID(워크플로·`ExportOptions-AppStore.plist`), 로컬 경로 사용자명, 호칭 |
| 인프라 | 서버 도메인·AWS 리전·S3 버킷 이름 규칙·관리자 판별 방식(`server/deploy/`, `application-prod.yml`, `docs/architecture/`) |
| 영업 비밀 | `CLAUDE.md`·`AGENTS.md`·`docs/`·`research/` — 임계값, 오탐률, 검증 한계 기록 |

비공개 전환이 **해결하지 못하는 것** — 따로 처리한다.

- **AGPL-3.0 모델 2개**: `yolov8n_food.tflite`(YOLOv8)·`food_region.tflite`(YOLOE)는 ultralytics 배포다. 비공개 유료 앱에 넣으려면 ultralytics Enterprise 라이선스를 사거나 모델을 바꿔야 한다(`docs/FOOD_EVAL_RESULTS.md` §8).
- **AI Hub 파생물**(음식 모델·자세 규칙·`normal_pose_reference.tsv`·영양 342종) — **사용자 결정 2026-11-06: 2차 저작물은 출처만 남기면 영리 목적 이용이 자유롭다. 약관 재확인은 하지 않고 출처 표기를 유지한다.** 지금 출처가 적힌 곳: `app/src/main/assets/models/README.md`(APK 에 번들됨), `rules_mp_v0.json`·`rules_floor_v0.json` 의 `source`, `normal_pose_reference.tsv` 머리글, `training/local_prep/README.md` 출처 절. 이 네 곳은 지우지 않는다. 빠진 곳: 사용자가 볼 수 있는 고지(앱 설정 화면·iOS 번들에는 AI Hub 출처가 없다) — `muscle_load/NOTICE.txt`·`community/NOTICE.txt` 와 같은 형식으로 `assets/posture/NOTICE.txt`·`assets/models/NOTICE.txt` 를 두고 iOS `Resources` 에도 같은 파일을 복사하면 된다(미실행).
- **Z-Anatomy 파생 `muscle_load/atlas.js` 는 CC BY-SA** — 상업 이용은 되지만 NOTICE 와 같은 라이선스 공개를 유지한다.
- 모델·규칙 JSON 은 어차피 배포 APK 안에 있다. 저장소를 숨겨도 APK 를 푼 사람에게는 보인다.

## 2. 비공개로 돌리면 끊기는 것 셋

1. **개인정보처리방침·이용약관·지원 페이지** — `gh-pages` 브랜치가 `https://leedonghyun00.github.io/TREX/` 로 서비스되고, 이 주소가 구글 동의 화면·App Store·앱 코드(`AuthScreens.kt` `PRIVACY_URL`, `CommunityApi.kt` `TERMS_URL`)에 박혀 있다. 무료 플랜은 비공개 저장소의 Pages 를 못 쓴다.
2. **앱 내 업데이트** — `AppUpdate.kt` 가 `api.github.com/repos/LeeDongHyun00/TREX/releases` 를 익명으로 읽는다. 비공개면 체험자 폰이 새 버전을 못 받는다. 서버 공유 페이지도 `github.com/LeeDongHyun00/TREX/releases/latest` 로 안내한다(`ShareProperties.java`).
3. **iOS Actions** — `macos-15` 러너는 공개 저장소에서 무료지만 비공개에서는 10배 요율의 유료 분을 쓴다(`ios-build.yml`·`ios-release.yml`). 실측: App Store 업로드 1회 40~50분(10-30·11-01 세 번), PR `shared` 검사 1회 5분. 무료 플랜의 월 2,000분은 macOS 10배 환산으로 200분이라 **업로드 4회면 끝난다.** 대안은 §5 Xcode Cloud.

## 3. 전환 방법 — 이름을 넘겨서 주소를 지킨다

주소를 하나도 바꾸지 않는 방법: **지금 저장소의 이름을 바꿔 비공개로 두고, `TREX` 라는 이름으로 공개 저장소를 새로 만들어 사이트와 릴리스만 둔다.** Pages 주소(`/TREX/`)·릴리스 API 주소·다운로드 링크가 전부 저장소 이름에서 나오므로 그대로 산다. 구글 콘솔·App Store·앱 코드를 손대지 않는다.

| | 지금 `LeeDongHyun00/TREX` | 새 `LeeDongHyun00/TREX` |
|---|---|---|
| 이름 | `TREX-app` 으로 변경 | `TREX` |
| 공개 | **비공개** | 공개 |
| 내용 | 코드·문서·연구·히스토리 전부 | `gh-pages` 브랜치(사이트 2.2 MB) + 릴리스 자산 + 짧은 README |
| 남는 것 | 룰셋 `trex-main`, 환경 `app-store`·`github-pages`, 브랜치 55개 | Pages 설정(`gh-pages`, `/`) |

### 순서

1. `gh repo rename TREX-app` — GitHub 가 옛 이름에서 새 이름으로 리다이렉트를 둔다(새 `TREX` 를 만들면 그 리다이렉트는 새 저장소가 대신한다).
2. `gh repo edit LeeDongHyun00/TREX-app --visibility private --accept-visibility-change-consequences` — 이 순간부터 Pages 가 꺼지고 익명 릴리스 API 가 404 다. 3~5 를 바로 이어서 한다.
3. `gh repo create LeeDongHyun00/TREX --public` — 새 공개 저장소. 설명은 "TREX 앱 소개·개인정보처리방침·배포".
4. 사이트 올리기: 이 클론에서 `git push https://github.com/LeeDongHyun00/TREX.git origin/gh-pages:gh-pages`, README 를 `main` 으로 푸시, Pages 를 `gh-pages`/`/` 로 켠다(`gh api -X POST repos/LeeDongHyun00/TREX/pages -f source[branch]=gh-pages -f source[path]=/`). 몇 분 뒤 `https://leedonghyun00.github.io/TREX/privacy.html` 이 다시 뜬다.
5. 릴리스 옮기기: 옛 저장소의 `v1.4.0` 자산(`TREX-1.4.0.apk` 140 MB·`SHA256SUMS.txt`·`BUILD_INFO.json`)을 내려받아 새 저장소에 같은 태그·같은 사전 릴리스로 올린다. `AppUpdate.kt` 는 `BUILD_INFO.json` 의 `versionCode`·`apkSHA256`·`certificateSHA256` 만 보므로 자산이 같으면 그대로 동작한다. 옛 사전 릴리스 14개는 옮기지 않는다(앱은 최신 10개 중 가장 높은 versionCode 만 본다).
6. 이 클론의 원격을 바꾼다: `git remote set-url origin https://github.com/LeeDongHyun00/TREX-app.git`. 다른 작업 폴더(`exercise-form-correction-5fe80e` 등)도 같이.
7. `scripts/publish_release.sh` 의 `REPO="TREX"` 는 **그대로** 둔다 — 릴리스는 새 공개 저장소에 올리는 것이 맞다. 세 번째 인자 `TARGET` 에는 비공개 저장소의 커밋 SHA 가 아니라 **`main`** 을 준다(공개 저장소에 없는 SHA 는 422). 릴리스 본문에 "소스는 `TREX-app` 커밋 <SHA>" 한 줄을 남겨 추적성을 지킨다.

### 확인

- `curl -sI https://leedonghyun00.github.io/TREX/privacy.html | head -1` → 200
- `curl -s https://api.github.com/repos/LeeDongHyun00/TREX/releases | python -c "import sys,json;print([r['tag_name'] for r in json.load(sys.stdin)])"` → `['v1.4.0']`
- 체험판 폰에서 앱을 열어 업데이트 안내가 **뜨지 않는지**(같은 버전이므로) 본다. 뜨면 `BUILD_INFO.json` 이 어긋난 것이다.
- `gh repo view LeeDongHyun00/TREX-app --json visibility` → PRIVATE

## 4. 전환 뒤 할 일

- **iOS 업로드 비용**: §5 대로 Xcode Cloud 로 옮기면 Actions 의 macOS 분을 쓰지 않는다. 옮기기 전까지는 `ios-build.yml` 의 PR 자동 실행을 끄거나 `workflow_dispatch` 로 바꾼다(PR 당 50분 환산).
- 새 공개 저장소에는 비밀값은 물론 **코드도 올리지 않는다**. 사이트 소스(`gh-pages`)와 릴리스 자산뿐이다.
- AGPL 모델·AI Hub 약관(§1)은 별도 결정.
- `.gitignore` 에 `android_data_backup*/` 를 더했다(폰 내부 데이터 스냅샷, 2026-11-06).


## 5. iOS Actions 를 Xcode Cloud 로 옮길 수 있는가 — 가능, 손볼 곳 다섯 (2026-11-06 조사)

**결론: 옮길 수 있다.** Xcode Cloud 는 비공개 GitHub 저장소를 지원하고, Xcode 26 을 고를 수 있고, 서명·TestFlight·App Store 업로드가 내장이라 지금 Actions 가 하는 일(Xcode 26 러너 + API 키 서명 + 업로드)을 그대로 대신한다. 비용은 Apple Developer Program 에 **월 25 컴퓨팅 시간이 포함**(2024-01 부터, 이월 없음)이고 더 쓰면 100 시간 US$49.99 부터다. 지금 업로드 1회 40~50분 기준 월 25~35회 — Actions 비공개 무료분(4회)보다 훨씬 넉넉하다.

근거(Apple 문서·릴리스 노트):
- 요구 조건 — Git 저장소(GitHub·GitHub Enterprise·Bitbucket·GitLab), GitHub 는 조직 소유자 또는 개인 저장소의 admin 권한, 프로젝트는 **자동 서명**이어야 한다. https://developer.apple.com/documentation/xcode/source-code-management-setup · https://developer.apple.com/documentation/xcode/setting-up-your-project-to-use-xcode-cloud
- 25 시간 포함·요금표 — https://developer.apple.com/news?id=ik9z4ll6 · https://developer.apple.com/xcode-cloud/get-started
- Xcode 26 은 2025-09 부터 Xcode Cloud 에 있다(워크플로 환경에서 버전 선택). https://developer.apple.com/xcode-cloud/release-notes
- 커스텀 스크립트는 `ci_scripts/ci_post_clone.sh`·`ci_pre_xcodebuild.sh`·`ci_post_xcodebuild.sh`(프로젝트 옆 폴더, 실행 비트 필수, **sudo 불가**, Homebrew 사용 가능).

이 저장소에 맞춰 손볼 곳:

| # | 지금(Actions) | Xcode Cloud 에서 | 비고 |
|---|---|---|---|
| 1 | `Trex.xcodeproj`·`Trex.xcworkspace` 는 git 제외, 매 실행 `setup-mac.sh` 가 XcodeGen + `pod install` 로 만든다 | Xcode Cloud 는 워크플로를 만들 때 프로젝트 경로·스킴을 검증하므로 **생성 프로젝트는 `ci_post_clone.sh` 에서 만든다**(`ci_pre_xcodebuild` 는 늦다). 커뮤니티 사례는 있지만 Apple 공식 보장은 아니다 | 더 확실한 길은 **생성된 프로젝트를 커밋**하는 것 — 그러면 `verify_ios_project.py` 의 역할을 다시 정한다. 어느 쪽이든 Podfile 은 `TrexPods/` 로 옮겨 쓰는 `setup-mac.sh` 절차를 그대로 스크립트에 넣는다 |
| 2 | `setup-java@v4`(temurin 21) | 환경에 JDK 가 없다. `brew install openjdk@21` 뒤 `JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home` — 시스템 경로에 심볼릭 링크는 못 건다(sudo 불가). Kotlin/Native 툴체인(`~/.konan`)과 Gradle 캐시는 `$CI_DERIVED_DATA_PATH` 아래에 두면 다음 빌드가 재사용한다 | KMP + Xcode Cloud 사례: https://dev.to/rockandnull/how-to-setup-xcode-cloud-with-kotlin-multiplatform-kmmkmp-4hjh · https://developer.apple.com/forums/thread/720037 |
| 3 | `xcodebuild … TREX_DEV_TOOLS=NO CURRENT_PROJECT_VERSION=$BUILD_NUMBER DEVELOPMENT_TEAM=…` | xcodebuild 인자를 못 준다. `project.yml` 에서 **Release 구성만 `TREX_DEV_TOOLS: NO`** 로 두고(Debug 는 YES 유지 — 지금은 한 값), 빌드 번호는 Xcode Cloud 가 `CI_BUILD_NUMBER` 로 알아서 올린다(`manageAppVersionAndBuildNumber` 를 켜거나 `ci_pre_xcodebuild.sh` 에서 `agvtool`). 팀 ID 는 자동 서명이 안다 | `ios-release.yml` 의 `build_number` 입력이 사라진다 |
| 4 | 보호 환경 `app-store` 의 시크릿 5개(ASC 키 3 + 개발 인증서 .p12 2) | **전부 불필요.** Xcode Cloud 가 클라우드 관리 인증서로 서명하고 업로드한다. `.p12` 우회(§121 "새 러너에는 개인 키가 없어…")는 GitHub 러너에만 필요했던 것 | 환경 `app-store` 와 시크릿을 지운다. `ExportOptions-AppStore.plist` 도 안 쓴다 |
| 5 | PR 의 `shared` 작업(`sync_ios_core.py --check` + `:posture-core:jvmTest` + `compileKotlinIosSimulatorArm64`, 5분) | Xcode Cloud 는 xcodebuild 동작(빌드·테스트·아카이브) 없는 워크플로를 못 만든다 — PR 시작 조건의 워크플로에서 `ci_post_clone.sh` 가 Gradle 검사를 하고 동작은 시뮬레이터 빌드로 둔다. 또는 **Xcode 가 필요 없는 부분**(`--check`·`jvmTest`)은 Actions `ubuntu` 로 옮기고(비공개에서도 월 2,000분, 1배 요율), `compileKotlinIosSimulatorArm64` 만 Xcode Cloud 에 둔다 | 후자가 분 소모가 가장 적다 |

그 밖에 알아둘 것:
- 아카이브 검사(`verify_bundle_assets.py`·PrivacyInfo·프레임워크 버전 키·SDK 26 확인)는 `ci_post_xcodebuild.sh` 에서 `$CI_ARCHIVE_PATH` 로 그대로 돌린다. Python 패키지(`tree-sitter`·`tflite`)는 `pip install --user` 로 된다.
- 워크플로는 Xcode 에서 만든다(저장소 주인 Mac 의 Xcode 16.2 로도 Xcode Cloud 설정은 가능하다 — Xcode 26 이 필요한 건 **빌드**이고 그건 클라우드가 한다). 연결 때 GitHub 앱을 **이름을 바꾼 비공개 저장소(`TREX-app`)** 에 설치한다 — §3 순서 1·2 뒤에 한다.
- 첫 빌드는 캐시가 없어 Kotlin/Native 툴체인·Gradle·Pods 를 다 받으니 1시간을 넘길 수 있다. 25 시간 안에서 첫 달은 10회 안쪽으로 본다.
- `docs/IOS_APP_STORE_RELEASE.md`·스펙 §121·§121a(시크릿 환경) 는 옮긴 뒤 갱신한다.
