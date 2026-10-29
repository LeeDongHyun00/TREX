# 서버 · 소셜 로그인 · 데이터 동기화 — 인수인계 (2026-10-02 시작)

> 이 문서는 Claude ↔ Codex 교대의 **정본**이다. 교대 전에 반드시 커밋하고, 이 문서의 **진행 상황**과 **다음 할 일**을 갱신한다.
> 결정의 근거는 여기, 무엇을 바꿨는지는 커밋 메시지, 검증은 `git diff`·테스트로 한다.
> 담당: 서버(`server/`)는 사용자 담당이다. 자세 평가(`posture/`)·규칙 JSON·`postureExerciseMap` 은 건드리지 않는다.

## 1. 왜 하는가

- **앱은 아직 서버와 통신하지 않는다.** 앱에 네트워크 코드가 없고(Retrofit·OkHttp·HttpURLConnection 0건), 로그인 화면은 계정 확인 없이 `TrexStore.loggedIn = true` 만 저장한다. 모든 데이터는 폰 안에만 있다(2026-10-02 확인).
- 그래서 **앱을 지우면 기록이 전부 사라지고**, 새 폰으로 옮길 방법이 없다. 안드로이드 자동 백업(`allowBackup=true`)은 조건이 까다롭고(하루 1회·충전·Wi-Fi·백업 설정 켜짐) APK 직접 설치에서는 복원을 기대하기 어렵다.
- 목표: **구글(이후 카카오) 로그인 → 사용자 데이터를 서버에 보관 → 새 기기에서 로그인하면 복원.**

## 2. 현재 상태(시작 시점)

### 앱 저장 위치 (`app/`)
| 데이터 | 저장 |
|---|---|
| 프로필·루틴(plan)·운동 기록(history)·식단(diet)·물(water)·직접 등록 음식(custom_foods)·목표(goal)·테마·온보딩/가이드 플래그 | `SharedPreferences "trex_store"` 의 키별 JSON 문자열 (`TrexStore.kt`) |
| 자세 모드 | `SharedPreferences "trex_posture"` |
| 운동 가이드 표시 | `SharedPreferences "exercise_guides"` |
| 근육 피로 원장 | SQLite (`MuscleLoadStore.kt`) |
| 자세 기준선 | `filesDir/posture_baseline.tsv` |
| 내 음식 기억(특징값) | `filesDir/food_memory.tsv` — 클라우드 자동 백업에서 제외됨 |
| 자세 세트 로그(연구용 원본) | `externalFilesDir/posture_logs/*.jsonl` |

### 서버 (`server/`, Spring Boot 4.0.6, Java 17, Gradle 9)
- 이미 있음: `POST /api/auth/signup`·`/login`(아이디·비밀번호, BCrypt) → JWT(HS256, subject=`loginId`), `GET /api/auth/me`, `/api/profiles`, `/api/diet-logs`, `/api/exercise-logs`. 요청 안의 사용자 식별은 **`loginId` 문자열**(`@AuthenticationPrincipal String loginId`)이다.
- `LogRetentionScheduler`: 운동·식단 로그를 **7일 지나면 Hard Delete** 한다(의도된 정책).
- DB 는 H2 메모리(`ddl-auto: create` — 켤 때마다 테이블을 지운다). `application.yml` 은 비밀값 보호로 git 제외, 템플릿은 `application-example.yml`.
- 시작 시점 함정: 새로 받은 저장소에서 `./gradlew test` 가 **두 가지로 실패**했다 — ① JDK 17 이 없으면 툴체인 오류 ② `application.yml` 이 없어 `jwt.secret` 자리표시자 오류. 1단계에서 둘 다 고친다(아래).

## 3. 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | **기존 Spring 서버를 확장**한다(Firebase 아님) | 사용자 결정 2026-10-02. 캡스톤에서 자체 서버를 보이고, 이미 JWT·기록 API 가 있다 |
| D2 | 소셜 사용자도 **내부 `loginId`(`google_<sub>`, `kakao_<id>`)** 를 갖는다 | 모든 서비스가 `loginId` 로 사용자를 찾는다 — 기존 식단·운동·프로필 코드를 고치지 않는다 |
| D3 | 회원 테이블에 `provider`(LOCAL/GOOGLE/KAKAO)·`providerId` 추가, `(provider, providerId)` 유니크. 소셜 사용자는 `passwordHash`·`email` 이 없을 수 있다(null 허용) | 소셜 로그인엔 비밀번호가 없고 카카오는 이메일 동의를 안 할 수 있다 |
| D4 | 이메일이 이미 다른 계정에 있으면 **자동으로 계정을 합치지 않는다** — 새 소셜 계정의 이메일을 비워 둔다 | 남의 이메일로 만든 구글 계정 등으로 기존 계정을 가로채는 것을 막는다 |
| D5 | **동기화는 "항목별 통째 백업"**: `SYNC_ITEMS(user, item_key, payload TEXT, client_updated_at)`. `GET /api/sync`, `PUT /api/sync/{key}` | 앱은 이미 항목별 JSON 덩어리로 저장한다. 서버 스키마를 앱 모델에 맞출 필요가 없고 새 항목이 생겨도 서버가 안 바뀐다. 목적(기기 이전)에 정확히 맞는다 |
| D6 | 충돌은 **항목별 "나중에 수정한 쪽이 이김"**(클라이언트 수정 시각 비교). 더 오래된 쓰기는 409 + 서버 현재값 | 한 사람이 두 기기를 동시에 쓰는 일은 드물다 |
| D7 | 동기화 백업은 **7일 보존 정책 대상이 아니다**(탈퇴 시에만 삭제) | 기기 이전용이라 전체 이력이 있어야 한다. 기존 운동·식단 로그 API 의 7일 정책은 그대로 |
| D8 | 구글 ID 토큰은 서버가 **구글 공개키(JWKS)로 직접 검증**(발급자·대상 클라이언트 ID·만료) | 앱이 보낸 사용자 정보를 그대로 믿지 않는다 |
| D9 | 운영 DB 는 **PostgreSQL(AWS RDS)**, 운영 설정은 `application-prod.yml`(환경 변수만 참조, 커밋) | 비밀값은 코드에 두지 않는다 |
| D10 | 배포는 **AWS EC2(Docker) + RDS + Caddy(HTTPS 자동)** | 사용자가 AWS 를 학습했다. 안드로이드는 기본적으로 HTTPS 만 허용한다 |
| D11 | 앱의 서버 주소·구글 웹 클라이언트 ID 는 **`gradle.properties` 에 팀 기본값으로 커밋**하고, **디버그도 운영 서버**를 가리킨다. `local.properties` 의 같은 키가 우선 | 팀원이 설정 없이 빌드해 폰에 까는 것이 디버그 APK 다 — 그 APK 로도 계정 연결이 되게 한다(서명 SHA-1 등록은 별도, Q1). 두 값 모두 비밀값이 아니다. **로컬 서버로 확인할 때는 `local.properties` 에 `trex.apiBaseUrl.debug=http://127.0.0.1:8080` 을 넣어야 한다 — 안 넣으면 운영 서버로 간다** |

**아직 정할 것**
- Q1 팀 공용 앱 서명 키(구글·카카오 콘솔에 SHA-1 등록용). 지금은 PC 마다 디버그 키가 달라 빌드한 PC 에 따라 로그인이 될 수도 안 될 수도 있다. 2026-10-13 현재 등록된 것은 사용자 PC 디버그 키뿐이고, **릴리스 APK(조원 개발 인증서) SHA-1 은 미등록** — 릴리스 전에 Android 클라이언트를 하나 더 만든다.
- ~~Q2~~ 결정(2026-10-13): **DuckDNS 무료 하위 도메인 `trex-api.duckdns.org`**. HTTPS 인증서는 IP 가 아니라 도메인에만 발급된다. 나중에 도메인을 사면(Route 53 등) 앱은 `trex.apiBaseUrl` 만 바꾸면 된다.
- ~~Q3~~ 결정(2026-10-13): 동기화 대상은 `trex_store` 의 7개 항목(`SyncRules.KEYS`: profile·plan·history·diet·water·custom_foods·goal_override)만. **내 음식 기억(food_memory.tsv)은 넣지 않는다** — 폰 밖으로 내보내지 않는다는 기존 결정(FOOD_EVAL §9, backup_rules). 근육 피로 SQLite 는 기록(history)에서 다시 계산된다. 자세 기준선·자세 로그는 넣지 않는다.

## 4. API 계약 (서버 ↔ 앱)

```
POST /api/auth/oauth/google      {"idToken": "..."}          → 200 TokenResponse (기존과 같은 모양)
POST /api/auth/oauth/kakao       {"accessToken": "..."}      → 200 TokenResponse   (4단계)
GET  /api/sync                                                → 200 {"items":[{"key","payload","updatedAt"}]}
PUT  /api/sync/{key}             {"payload": "<JSON 문자열>", "updatedAt": <epoch ms>}
                                  → 200 {"key","payload","updatedAt"}   (반영됨)
                                  → 409 {"key","payload","updatedAt"}   (서버가 더 최신 — 앱은 서버 값을 받아들인다)
DELETE /api/auth/me                                           → 204 (회원과 모든 데이터 삭제)
```
- `key`: `^[a-z0-9_]{1,64}$`. `payload`: 문자열(앱이 저장한 JSON 그대로), 최대 2,097,152**자**(문자 수 — 한글은 UTF-8 로 글자당 최대 3바이트라 바이트로는 약 6MB).
- 인증: `Authorization: Bearer <JWT>`. 401 이면 앱은 다시 로그인시킨다.

## 5. 단계와 진행 상황

- [x] **0단계 · 준비**: 이 문서, 서버 테스트가 새 저장소에서 바로 돌게(툴체인 자동 다운로드, 테스트 전용 설정)
- [x] **1단계 · 서버**: D2·D3 회원 구조, D8 구글 검증 + `/api/auth/oauth/google`, D5 동기화 API, 회원 탈퇴, `application-prod.yml`·PostgreSQL 드라이버, 테스트 (2026-10-02, `feature/server-sync`)
  - 코드: `entity/AuthProvider`·`User`(provider·providerId), `oauth/`(`GoogleTokenVerifier` 인터페이스 + `GoogleJwksTokenVerifier`), `AuthService.socialLogin`·`deleteAccount`, `entity/SyncItem`·`SyncService`·`SyncController`, `resources/application-prod.yml`.
  - 테스트 20개 통과: `SocialSyncApiTest`(가짜 검증기로 API 흐름 11개 — 운영 배포 때 없는 주소 404 테스트 추가), `GoogleJwksTokenVerifierTest`(테스트 키로 서명한 토큰으로 서명·발급자·대상·만료 8개), 컨텍스트 로드 1개.
  - 동작 메모: `google_`·`kakao_` 로 시작하는 아이디로는 일반 가입이 안 된다(409, 소셜 내부 아이디 선점 방지). 소셜 회원은 비밀번호가 없어 `/login` 으로는 못 들어온다. 탈퇴한 회원의 기존 JWT 는 401. 탈퇴는 동기화·식단(하위 식사 포함)·운동·프로필·회원 순으로 지운다.
  - 함정: `GoogleJwksTokenVerifier` 는 테스트용 생성자가 있어 공개 생성자에 `@Autowired` 가 꼭 필요하다(없으면 컨텍스트가 안 뜬다). `client-ids` 가 비면 구글 로그인은 항상 401 이고 서버 로그에 error 가 남는다.
  - 실제 구글 토큰으로는 2단계에서 확인했다(아래).
- [ ] **2단계 · 앱** — 코드 완료. **2026-10-13 실기기(폴드6) 구글 로그인 성공**: 설정 → Google 계정 연결 → 서버에 회원 1명 생성, 7개 항목 업로드(로컬 서버 로그로 확인). **같은 날 기기 이전 확인**: 새 에뮬레이터(API 36 Google Play, AVD `trex_test`)에서 같은 계정으로 "Google로 계속하기" → 온보딩 없이 프로필·운동 기록·식단이 그대로 들어옴(첫 연결 규칙 DOWNLOAD_REMOTE). 남은 확인: 양쪽에 기록이 있을 때 질문 화면(ASK).
  - 에뮬레이터 함정: Windows 에서 `sdkmanager.bat`·`avdmanager.bat` 은 패키지 이름의 `;` 를 인자 구분자로 잘라 먹는다 — sdkmanager 는 `system-images/android-36/google_apis_playstore/x86_64` 처럼 `/` 로 쓰고, AVD 는 `~/.android/avd/trex_test.ini`·`config.ini` 를 직접 썼다.
  - **함정(실제로 겪음)**: 앱·서버에 넣는 값은 **웹 애플리케이션** 클라이언트 ID 다. Android 클라이언트 ID 를 넣으면 `28444 Developer console is not set up correctly` 가 난다. Android 클라이언트(패키지명·SHA-1)는 콘솔에 있기만 하면 된다. 실패 원인은 화면 문구와 logcat 태그 `TrexAuth` 에 남는다.
  - 이 프로젝트의 웹 클라이언트 ID: `12194757714-foefivjlqn4n3g5iqdsgqlqst12ejbpi.apps.googleusercontent.com`(비밀값 아님). Android 클라이언트는 사용자 PC 디버그 키 SHA-1 `DB:12:B7:…:36:47` 로 등록됨 — 다른 PC 에서 빌드하면 그 PC 의 SHA-1 로 Android 클라이언트를 하나 더 만든다.
  - **iPhone(2026-10-28)**: iOS 클라이언트 ID `12194757714-bd99b55i5jk94gpft5a6ec6s983fm9mn.apps.googleusercontent.com`(비밀값 아님, 번들 `com.leedonghyun.trex.ios`). 앱은 Info.plist `GIDClientID`(iOS)·`GIDServerClientID`(웹 — 로그인 버튼을 켤지만 가른다)를 쓰고, iOS 기본 `ASWebAuthenticationSession` 으로 OAuth 인가 코드 + PKCE 를 직접 해 ID 토큰을 받는다(`iosApp/Trex/GoogleSignInBridge.swift`). GoogleSignIn pod 는 GTMSessionFetcher 가 MediaPipe 정적 라이브러리 안의 같은 코드와 링크 때 부딪혀(중복 기호 196개, CI) 뺐다. iPhone 토큰의 aud 는 **iOS 클라이언트 ID** 라 서버 `GOOGLE_CLIENT_IDS` 에 웹·iOS 두 ID 를 쉼표로 넣었다. 같은 날 구글 동의 화면을 프로덕션으로 게시했다(브랜딩: 홈페이지 `https://leedonghyun00.github.io/TREX/`, 처리방침 `…/TREX/privacy.html`, 승인된 도메인 `leedonghyun00.github.io`, 로고 없음).
  - 코드: `app/.../sync/` — `SyncRules`(판정 규칙, 단위 테스트 `SyncRulesTest` 7개), `SyncManager`(저장소 변경 감지 → 3초 모아 올리기, 시작·로그인·"지금 동기화" 때 받기), `TrexApi`(HttpURLConnection — 호출 4개라 Retrofit 안 씀), `AuthSession`(`trex_auth` prefs, 자동 백업 제외), `GoogleSignIn`(Credential Manager). `TrexStore.readRaw/writeRaw/setChangeListener`, `AppViewModel` 계정 절, `AuthScreen`(아이디·비밀번호 폼과 찾기 화면을 지우고 "Google로 계속하기"/"로그인 없이 사용"), 설정의 계정 칸·회원 탈퇴, `FirstSyncDialog`.
  - 첫 연결 규칙: 서버가 비면 올림 / 이 기기가 온보딩 전이면 받음 / 양쪽에 있으면 묻는다(자동 병합 안 함). 서버에 없는 항목은 어느 쪽을 골라도 이 기기 값을 남겨 올린다(고르지 않은 데이터를 지우지 않는다). 계정(`owner`)과 처음 맞추기 전에는 주고받지 않는다.
  - 기존 사용자(가짜 로그인으로 이미 `logged_in`)는 로그인 화면을 다시 안 본다 — **설정 → Google 계정 연결**로 연결한다.
  - **PR 전 코드 리뷰 반영(2026-10-13)**:
    - **다른 계정으로 바꿔 로그인**(같은 서버에서 앞 계정이 로그아웃한 뒤)하면 이 기기 기록을 옮길지 묻는다(`FirstSync.SWITCH_ACCOUNT`, 옮기기/취소) — 묻지 않고 올려 앞 사람의 건강·식단 기록이 새 계정 백업으로 들어가던 문제.
    - "이 기기 기록으로 올린다"(첫 연결·질문 답)는 받아 본 서버 값보다 반드시 새 버전으로 찍는다(`SyncRules.uploadVersion`) — 이 기기 시계가 늦으면 409 로 서버의 빈 값을 받아 로컬 기록이 지워질 수 있었다. 서버가 빈 첫 연결은 이 기기에 있는 항목만 올린다.
    - 이미 맞춘 계정인데 서버가 비어 있으면(탈퇴 뒤 같은 구글 계정으로 재가입, 서버 DB 초기화) 이 기기 항목을 다시 전부 올린다.
    - 첫 연결을 못 마친 계정(로그인 직후 받기 실패)은 앱 시작·앱 복귀·"지금 동기화" 때 첫 연결부터 다시 한다 — 화면엔 연결됨인데 아무것도 안 하던 문제. 계정 오류 문구를 설정의 계정 칸·탈퇴 창에 보인다.
    - **앱 복귀 때 먼저 받는다**(`AppViewModel.onAppForeground`, 15초 안에 받았으면 건너뜀, 운동 세션 중엔 안 받음 — 계획이 바뀌면 진행 중 세션 단계가 어긋난다).
    - 서버 동기화 쓰기는 행을 잠그고 비교한다(`SyncItemRepository.findForUpdate`) — 동시 쓰기에서 오래된 쪽이 나중에 커밋되던 경쟁.
    - 원격 값 적용은 `Dispatchers.Main.immediate` — 판정과 쓰기 사이에 사용자 편집이 끼어 덮이던 틈.
    - **남은 한계(알고 둔 것)**: 동기화 단위가 항목 통째라, 두 기기에서 같은 항목(예: 식단)을 번갈아 고치면 **나중에 고친 쪽이 그 항목 전체를 덮는다**(D6). 앱 복귀 때 먼저 받아 창을 좁혔지만, 받기가 끝나기 전에 고치면 남는다. 근본 해결은 항목 안 병합(식단은 날짜별, 기록은 epochDay 별) 또는 서버의 조건부 쓰기(기준 버전이 다르면 409) — 이후 과제.
  - 설정값(D11): `gradle.properties`(커밋)의 `trex.googleWebClientId`·`trex.apiBaseUrl`(릴리스)·`trex.apiBaseUrl.debug`(디버그) — 둘 다 운영 `https://trex-api.duckdns.org`. `local.properties` 에 같은 키가 있으면 그쪽이 우선하고, 둘 다 없으면 디버그는 `http://127.0.0.1:8080`, 릴리스는 빈 값(구글 로그인 버튼이 안내 문구만 냄). 디버그만 http 허용(`usesCleartextTraffic` 자리표시자).
- [x] **3단계 · AWS 배포** (2026-10-13) — `https://trex-api.duckdns.org` 가동, 실기기가 USB 없이 운영 서버와 동기화(RDS 에 회원 1명·7개 항목), AMI 백업 완료. 현황·절차는 8절
- [ ] **4단계 · 카카오**
- [ ] **5단계 · 개인정보 처리방침·동의** — **다음 공개 릴리스 전에 필수**. 건강·식단 데이터를 서버에 보관하는데, 옛 가짜 가입 폼의 동의 체크박스는 폼과 함께 지워졌고 처리방침 문서가 없다. 구글 동의 화면을 "프로덕션"으로 게시할 때도 처리방침 URL 이 필요하다. (탈퇴 버튼은 2단계에서 이미 만들었다 — 설정 → 회원 탈퇴)

## 6. 검증 방법

```bash
cd server && ./gradlew test        # JDK 17 이 없으면 Gradle 이 자동으로 받는다(foojay)
```
- Windows 에서는 `gradlew.bat`, 실행하는 JDK 는 아무 버전(예: 21)이어도 된다 — 컴파일은 툴체인 17.
- 앱: 저장소 루트 `./gradlew :app:testDebugUnitTest`.

## 7. 다음 할 일

1. **5단계 개인정보 처리방침·동의 화면** — 다음 공개 릴리스 전에 필수(5절). **`redesign` 병합(2026-10-14) 뒤로는 다음 체험판(1.3.0-preview.8 이후)부터 로그인·동기화가 들어간다** — 그 APK 는 GitHub 사전 릴리스로 공개되고, 앱 내 업데이트(`AppUpdate.kt`)가 preview.6·7 설치자에게 업데이트를 안내한다. 그 체험판 전에 이 단계가 필요한지 사용자가 정한다.
2. **릴리스 서명 키 SHA-1 등록**(Q1) — 구글 콘솔 → 클라이언트 → Android 클라이언트를 하나 더(패키지 `com.example.trex_kotlin` + 릴리스 키 SHA-1, `keytool -list -v -keystore <키스토어>`). 안 하면 릴리스 APK 의 구글 로그인이 `28444` 로 실패한다. `redesign` 병합 뒤 release 변형은 **빌드한 PC 의 debug 키스토어**로 서명된다(`signingConfig = signingConfigs.getByName("debug")` — 체험판을 삭제 없이 업데이트하려고 고정한 인증서, `docs/ANDROID_PREVIEW_RELEASE_1_3_0_7.md`). 등록할 것은 체험판을 빌드하는 PC 의 그 키 SHA-1 이다.
3. 2단계 남은 확인: 양쪽에 기록이 있을 때 질문(ASK) 화면.
4. 4단계 카카오(서버 `/api/auth/oauth/kakao` + 앱 SDK + 카카오 콘솔 키 해시).

로컬 서버로 확인하는 절차(운영 서버를 건드리지 않고 시험할 때):
```bash
# local.properties 에 한 줄 — 없으면 디버그 빌드도 운영 서버로 간다(D11)
trex.apiBaseUrl.debug=http://127.0.0.1:8080

cd server && cp src/main/resources/application-example.yml src/main/resources/application.yml   # 없을 때만(git 제외 파일)
GOOGLE_CLIENT_IDS=<웹 클라이언트 ID> ./gradlew bootRun        # H2 메모리 — 서버를 끄면 데이터가 사라진다
adb reverse tcp:8080 tcp:8080                                  # 폰의 127.0.0.1:8080 → PC 서버
./gradlew :app:installDebug                                    # 앱 삭제 금지(기록 손실)
```
확인 항목: 설정 → Google 계정 연결 → "마지막 동기화" 표시, 기록 하나 추가 후 3초 뒤 `GET /api/sync` 에 반영, 앱 데이터가 빈 에뮬레이터/다른 기기에서 같은 계정 로그인 → 온보딩 없이 기록이 들어옴. 끝나면 `local.properties` 의 그 줄을 지운다.

## 8. 운영 배포 런북 (3단계, AWS — 첫 배포)

**현황(2026-10-13)**: **운영 서버 가동, AMI 백업 완료** — `https://trex-api.duckdns.org`(Let's Encrypt 인증서, `/api/sync` 무인증 401 확인). EC2 `trex-server`(i-0e4bb1e5ac45947f1, t4g.micro, 실제 OS **Ubuntu 26.04 LTS** Arm), 탄력적 IP `54.252.112.191`, RDS `trex-db.cr2em864u5ym.ap-southeast-2.rds.amazonaws.com`(db.t4g.micro, Free plan 이라 자동 백업 1일 — 중요한 시점엔 수동 스냅샷). 서버 파일 `~/trex`(app.jar·docker-compose.yml·Caddyfile·.env). `.env` 의 JWT_SECRET 은 서버에서 `openssl rand` 로 만들어 어디에도 출력하지 않았고, DB_PASSWORD 는 사용자가 직접 넣었다.
- 구성도: `docs/architecture/trex-aws.drawio`(draw.io 로 열어 고친다, AWS 공식 아이콘) · 그림 `trex-aws.png`. 구성이 바뀌면 둘 다 갱신한다.
- RDS 보안 그룹에 "EC2 컴퓨팅 리소스에 연결"이 안 걸려 5432 가 막혀 있었다 — RDS 보안 그룹 인바운드에 PostgreSQL 5432, 소스 = `trex-app` 보안 그룹을 직접 추가해 해결. PC 에서 5432 는 막혀 있음(정상).
- 앱 동기화의 "이미 맞춘 계정" 표시는 **서버 주소까지** 묶는다(`SyncManager.ownerKey`) — 로컬 서버와 맞춘 폰이 빈 운영 서버에 붙으면 첫 올리기를 건너뛰던 문제.

구성: **EC2(t4g.micro, Ubuntu Server LTS arm64 — 사용자 선택 2026-10-13, 수업에서 쓴 OS. 콘솔 기본 최신판이라 실제는 26.04) 1대**에 Docker 로 `app`(JRE + PC 에서 만든 jar)·`caddy`(HTTPS) 두 컨테이너 + **RDS PostgreSQL(db.t4g.micro, 20GB)** + **탄력적 IP** + **DuckDNS 무료 도메인**. 리전은 **시드니(ap-southeast-2)** — 가입한 새 AWS 경험(프로젝트)이 시드니에 고정돼 있어 그대로 씀(사용자 결정 2026-10-13). EC2·RDS 는 반드시 같은 리전. 파일은 `server/deploy/`.

비용(2026-10 기준): 신규 계정 크레딧 $100(+과제 $100), **Free plan 은 6개월 또는 크레딧 소진 시 계정이 닫힌다**(카드 과금 없음). 이 구성 월 약 $27~34 → 약 6개월. 사용자 결정 2026-10-13: T-REX 전용 계정, Free plan, RDS 사용(EC2+RDS 연동·AMI 실습).

1. **계정**: 가입 → Free plan → 루트 MFA.
2. **예산 알림**: Billing → Budgets → 월 $10, 80%·100% 메일.
3. **EC2**: AMI Ubuntu Server LTS 최신(64비트 Arm — 2026-10 기준 26.04, 24.04 도 같은 절차), t4g.micro, 키 페어(.pem 은 PC 에만, 커밋 금지), 저장 16GB gp3. 보안 그룹 `trex-app`: 22 = 내 IP 만, 80·443 = 전체.
4. **탄력적 IP** 할당 → EC2 에 연결. **DuckDNS**(duckdns.org) 하위 도메인을 이 IP 로.
5. **RDS**: PostgreSQL, 템플릿 "프리 티어"/개발·테스트, 단일 AZ, db.t4g.micro, 20GB gp3, 마스터 사용자 `trex`, 비밀번호 직접 입력(→ `.env`), **퍼블릭 액세스 아니요**, "EC2 컴퓨팅 리소스에 연결"로 3의 EC2 선택(보안 그룹 자동 — RDS 5432 는 EC2 에서만), 추가 구성의 **초기 데이터베이스 이름 `trex`**, 자동 백업(Free plan 은 최대 1일 — 중요한 시점엔 수동 스냅샷). "EC2 연결"이 빠졌으면 RDS 보안 그룹 인바운드에 PostgreSQL 5432 · 소스 `trex-app` 보안 그룹을 직접 넣는다(실제로 겪음, 위 현황).
6. **EC2 준비**(SSH):
   ```bash
   sudo apt update && sudo apt install -y docker.io docker-compose-v2 && sudo systemctl enable --now docker && sudo usermod -aG docker ubuntu
   sudo dd if=/dev/zero of=/swapfile bs=1M count=1024 && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile \
     && echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab        # 메모리 1GB 보강
   mkdir -p ~/trex                                                            # 다시 로그인해야 docker 그룹이 먹는다
   ```
7. **올리기**(PC, 저장소 루트):
   ```bash
   cd server && ./gradlew bootJar                       # 배포 jar 에는 application.yml 이 빠진다(build.gradle)
   scp -i <키.pem> build/libs/server-0.0.1-SNAPSHOT.jar ubuntu@<IP>:~/trex/app.jar
   scp -i <키.pem> deploy/docker-compose.yml deploy/Caddyfile deploy/.env.example ubuntu@<IP>:~/trex/
   ```
   EC2 에서: `cd ~/trex && sed -i 's/\r$//' docker-compose.yml Caddyfile .env.example && cp .env.example .env && nano .env`(값 채움, `chmod 600 .env`) → `docker compose up -d` → `docker compose logs -f app`.
8. **확인**: `curl -i https://<도메인>/api/sync` → 401(인증서·프록시·앱 정상). 앱 설정값은 `gradle.properties` 기본값이 이미 운영 도메인이다(D11 — 도메인이 바뀌면 거기를 고친다) → 설치 → 설정 → Google 계정 연결.
9. **AMI**: 다 되면 EC2 → 작업 → 이미지 및 템플릿 → 이미지 생성(`trex-server-YYYYMMDD`). 서버가 망가지면 이 AMI 로 새 EC2 를 띄우고 탄력적 IP 를 옮긴다. 데이터는 RDS 에 있어 AMI 와 무관하다.
10. **업데이트**: 7의 jar 만 다시 올리고 `docker compose restart app`.

함정:
- 운영 `ddl-auto: update` — 컬럼 추가는 따라가지만 이름 변경·삭제는 못 한다. 엔티티를 크게 바꾸면 Flyway 를 먼저 들인다.
- `.env` 를 Windows 에서 만들어 올리면 줄끝 `\r` 이 비밀번호에 붙는다 — EC2 에서 만들거나 `sed -i 's/\r$//'`.
- Caddy 인증서는 `caddy_data` 볼륨에 있다. 볼륨을 지우면 재발급(Let's Encrypt 주간 발급 제한).
- 6개월 뒤 Free plan 계정이 닫히기 전에 유료 전환 여부를 정한다(RDS 스냅샷을 다른 계정으로 공유해 옮길 수 있다).

## 9. 커뮤니티 운영 (2026-10-29 — 배포 전 초안)

커뮤니티 API(`/api/community/**`, 설계 `docs/superpowers/specs/2026-10-28-community-design.md`, 계획 `docs/superpowers/plans/2026-10-28-community-server.md`)는 사진을 **비공개 S3 버킷**에 두고, 앱에는 수명 1시간짜리 서명 주소를 내준다. 운영 프로필은 버킷이 비면 **서버가 뜨지 않는다**(`require-s3`) — 아래 AWS 준비를 끝내기 전에 이 jar 를 배포하면 로그인·동기화까지 멈춘다.

**왜 S3 인가 — 커뮤니티 사진 때문이다.** 지금까지 서버에는 파일이 없었다(동기화는 JSON 글자를 RDS 에 둔다). 사진을 다른 곳에 두면 이렇게 된다.
- EC2 디스크(16GB): 금방 찬다. AMI 로 서버를 새로 띄우면 그 뒤에 올라온 사진이 사라진다.
- RDS(20GB): 사진이 DB 를 키워 백업·복원이 느려지고, 용량을 늘리면 돈이 든다.
- S3: 사진 1천 장·조회 30만 회에 월 1달러 미만이고, AWS 가 여러 곳에 복제해 잃어버리지 않는다.

**실습 때와 다른 점.** 실습에서는 보통 버킷을 공개로 열거나(정적 웹사이트) 액세스 키를 PC 에 넣고 올렸을 것이다. 여기서는 반대로 한다.
- 버킷은 **공개를 전부 막는다** — 주소를 알아도 아무나 못 본다.
- 액세스 키를 만들지 않는다. EC2 에 **IAM 역할**을 붙이면 서버가 그 역할로 S3 에 들어간다(비밀값이 파일에 남지 않는다).
- 앱은 S3 에 직접 접근하지 않는다. 서버가 사진을 받아 넣고, 볼 때는 서버가 1시간짜리 **서명 주소**(그 사진 하나만 잠깐 열리는 링크)를 내준다.

```
앱 ──사진 올리기──▶ 서버(EC2, IAM 역할) ──넣기──▶ S3 비공개 버킷
앱 ◀──서명 주소(1시간)── 서버                     ▲
앱 ──────────────── 서명 주소로 사진 받기 ─────────┘
```

### 9.1 AWS 준비 (콘솔, 처음 한 번 — 20분쯤)

모두 AWS 콘솔 오른쪽 위 리전이 **아시아 태평양(시드니) ap-southeast-2** 인지 먼저 본다. 화면 문구는 콘솔 개편에 따라 조금 다를 수 있다. 각 단계 끝의 *메모* 칸 값은 9.2 에서 `.env` 에 넣는다.

> **`<…>` 는 바꿔 넣을 자리 표시다 — 꺾쇠 `<` `>` 까지 지우고 값만 넣는다.** 2026-10-29 첫 배포에서 버킷 이름·IAM 정책·웹후크 세 곳에 꺾쇠가 남아 사진 올리기와 신고 알림이 실패했다. 예: `arn:aws:s3:::<버킷 이름>/*` → `arn:aws:s3:::trex-community-k7m2qp/*`

**① S3 버킷 만들기** — 콘솔 위 검색창에 `S3` → **버킷 만들기**
1. 버킷 유형: **범용**
2. 버킷 이름: `trex-community-` 뒤에 아무 소문자·숫자 6자(예: `trex-community-k7m2qp`). 전 세계에서 하나뿐이어야 해서 이미 있으면 바꾼다. 소문자·숫자·하이픈만.
3. 객체 소유권: **ACL 비활성화됨(권장)** 그대로
4. 이 버킷의 퍼블릭 액세스 차단 설정: **모든 퍼블릭 액세스 차단** 체크 그대로(네 칸 모두 켜짐) — 끄면 안 된다
5. 버킷 버전 관리: **비활성화** 그대로
6. 기본 암호화: **Amazon S3 관리형 키를 사용한 서버 측 암호화(SSE-S3)** 그대로
7. 맨 아래 **버킷 만들기**

*메모*: 버킷 이름 → `COMMUNITY_S3_BUCKET`

**② IAM 정책 만들기**(무엇을 허락할지) — 검색창에 `IAM` → 왼쪽 **정책** → **정책 생성**
1. 정책 편집기에서 **JSON** 을 누르고 내용을 모두 지운 뒤 아래를 붙여 넣는다. `<버킷 이름>` 은 ①의 이름으로 바꾼다 — **꺾쇠까지 지운다**(틀리면 사진 올릴 때 `AccessDenied … no identity-based policy allows the s3:PutObject action`).
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
       "Resource": "arn:aws:s3:::<버킷 이름>/*"
     }]
   }
   ```
2. **다음** → 정책 이름 `trex-community-s3` → **정책 생성**

이 정책은 그 버킷 하나에 사진 넣기·읽기·지우기만 허락한다. 다른 버킷이나 버킷 설정은 못 건드린다.

**③ IAM 역할 만들기**(누가 그 허락을 쓸지) — IAM 왼쪽 **역할** → **역할 생성**
1. 신뢰할 수 있는 엔터티 유형: **AWS 서비스**, 사용 사례: **EC2** → **다음**
2. 권한 정책 검색창에 `trex-community-s3` → 체크 → **다음**
3. 역할 이름 `trex-app-s3` → **역할 생성**

**④ EC2 에 역할 붙이기** — 검색창에 `EC2` → 왼쪽 **인스턴스** → `trex-server` 체크
1. 위 **작업** → **보안** → **IAM 역할 수정**
2. IAM 역할에서 `trex-app-s3` 선택 → **IAM 역할 업데이트**

서버를 다시 켤 필요는 없다. 이 순간부터 EC2 안에서 S3 를 쓸 수 있다.

**⑤ 메타데이터 홉 제한 2** — 같은 화면에서 `trex-server` 체크
1. **작업** → **인스턴스 설정** → **인스턴스 메타데이터 옵션 수정**
2. 인스턴스 메타데이터 서비스: **활성화** 그대로, IMDSv2: **필수** 그대로
3. **메타데이터 응답 홉 제한**: `1` → **`2`** → **저장**

왜: 서버 프로그램은 EC2 안의 도커 컨테이너에서 돈다. 컨테이너에서 역할 자격 증명을 받으려면 한 단계를 더 거쳐야 해서, 1 이면 서버가 "자격 증명 없음"으로 사진을 못 올린다.

**⑥ 디스코드 웹후크**(새 신고 알림) — 디스코드 앱
1. 알림 받을 채널 이름 옆 톱니(**채널 편집**) → **연동** → **웹후크** → **새 웹후크**
2. 이름을 `T-REX 신고` 정도로 바꾸고 **웹후크 URL 복사** → **변경 사항 저장**

*메모*: 복사한 URL → `COMMUNITY_REPORT_WEBHOOK_URL`. **비밀값이다** — 이 URL 만 있으면 누구나 그 채널에 글을 쓸 수 있다. 저장소·PR·채팅에 붙이지 말고 서버 `.env` 에만 넣는다.

**⑦ 관리자 loginId**(앱 안 신고함을 볼 계정) — 관리자로 쓸 계정으로 앱에서 Google 계정을 연결해 둔 폰을 PC 에 꽂고(디버그 빌드):
```bash
adb shell run-as com.example.trex_kotlin cat shared_prefs/trex_auth.xml | grep login_id
```
`google_` 로 시작하는 숫자 값이 loginId 다. 토큰 줄은 출력하지 않는다(위처럼 `login_id` 줄만). 폰이 없으면 로그인 토큰으로 `GET https://trex-api.duckdns.org/api/auth/me` 의 `loginId`. 관리자가 여럿이면 쉼표로 잇는다.

*메모*: → `COMMUNITY_ADMIN_LOGIN_IDS`

### 9.2 배포 (PC Git Bash — 8절과 같은 길)
순서가 중요하다: **`.env` 를 먼저** 고친다. jar 를 먼저 바꾼 채 컨테이너가 재시작되면 버킷 없이 새 jar 로 떠서 재시작을 반복한다.
```bash
cd server && JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew.bat bootJar
KEY=C:/Workspace/TREX/trex-key.pem; HOST=ubuntu@54.252.112.191
# 1) .env 에 세 줄 추가 — nano 는 터미널이 필요해 -t
ssh -t -i $KEY $HOST "cd ~/trex && cp .env .env.before-community && nano .env"
#   COMMUNITY_S3_BUCKET=<버킷 이름>                 ← 꺾쇠 없이: COMMUNITY_S3_BUCKET=trex-community-k7m2qp
#   COMMUNITY_ADMIN_LOGIN_IDS=google_<숫자>
#   COMMUNITY_REPORT_WEBHOOK_URL=<디스코드 웹후크>  ← 꺾쇠 없이 https://discord.com/api/webhooks/… 그대로
#   (버킷 이름이 형식에 안 맞으면 서버가 기동 때 까닭을 찍고 멈춘다, 웹후크가 http(s):// 로 시작하지 않으면 알림을 끄고 로그에 남긴다)
# 2) 되돌릴 사본을 남기고 새 jar·compose 올리기
ssh -i $KEY $HOST "cd ~/trex && cp app.jar app.jar.prev && cp docker-compose.yml docker-compose.yml.prev"
scp -i $KEY build/libs/server-0.0.1-SNAPSHOT.jar $HOST:~/trex/app.jar
scp -i $KEY deploy/docker-compose.yml $HOST:~/trex/docker-compose.yml
# 3) 다시 띄우고 기동 확인
ssh -i $KEY $HOST "cd ~/trex && sed -i 's/\r$//' docker-compose.yml && docker compose up -d && sleep 20 && docker compose logs --since 2m app | grep -E 'Started|ERROR|COMMUNITY'"
```

### 9.3 확인
- 기동 로그 `Started ServerApplication`, `curl -s -o /dev/null -w '%{http_code}' https://trex-api.duckdns.org/api/sync` 가 401 그대로.
- 로그인 토큰(`T`)으로: `curl -H "Authorization: Bearer $T" https://trex-api.duckdns.org/api/community/me` → 200(`profile` null).
- 닉네임·이용규칙: `PUT /api/community/me/profile {"nickname":"..."}`, `POST /api/community/me/terms {"version":"2026-10-28"}`.
- 사진 글: `curl -H "Authorization: Bearer $T" -F body=시험 -F images=@test.jpg https://trex-api.duckdns.org/api/community/posts` → 201, 응답의 `images[0].url` 을 브라우저로 열면 사진이 보인다. 그 글을 `DELETE` 하면 같은 주소가 곧 403/404.
- 관리자: `GET /api/community/me` 의 `admin` 이 true. 다른 계정으로 신고하면 디스코드에 `[T-REX 신고]` 가 온다.

### 9.4 되돌리기
`ssh -i $KEY $HOST "cd ~/trex && cp app.jar.prev app.jar && cp docker-compose.yml.prev docker-compose.yml && docker compose up -d"`
- 커뮤니티 테이블은 남는다(`ddl-auto: update` 는 지우지 않는다). 다시 새 jar 를 올리면 그대로 이어진다.
- **주의: 되돌린 동안에는 커뮤니티 프로필이 있는 사람의 회원 탈퇴가 실패한다**(옛 jar 의 탈퇴는 커뮤니티 테이블을 몰라 외래 키에 걸린다 — 409). 되돌림은 짧게 두고, 그 사이 탈퇴 요청은 새 jar 로 다시 올린 뒤 처리한다.

### 9.5 운영 메모
- 신고는 **24시간 안에** 앱 안 신고함(관리자에게만 보임)에서 처리한다 — App Store 1.2. 디스코드 알림이 그 신호다.
- 자동 숨김은 서로 다른 세 사람의 미처리 신고. 관리자가 "그대로 둠(KEPT)" 하면 숨김이 풀리고 그 신고들은 처리 끝으로 남는다.
- 탈퇴는 커뮤니티 글·사진·댓글·좋아요·신고·차단을 함께 지운다(사진은 커밋 뒤, 실패는 로그만 — 고아 사진은 S3 콘솔에서 `posts/<글 id>/` 로 찾을 수 있다).
- 비용(추정): 사진 1천 장·조회 30만 회에 월 1달러 미만. AWS Free plan 만료일(가입 6개월)을 데모데이 전에 확인한다.
