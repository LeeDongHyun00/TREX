# Apple 로그인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** iPhone 에 "Apple로 계속하기"를 더해 App Store 지침 4.8 을 채우고, 서버가 Apple identity token 을 검증해 `apple_<sub>` 회원을 만들며, 탈퇴 때 Apple 연결을 끊는다 — 동현님의 키(.p8) 없이 할 수 있는 것 전부.

**Architecture:** 서버는 구글과 같은 길 — `AppleTokenVerifier`(JWKS 검증) → 기존 `AuthService.socialLogin`. 키가 설정되면 `AppleTokenClient` 가 authorization code 를 refresh token 으로 바꿔 회원에 저장하고, 탈퇴 때 revoke 한다(키가 없으면 둘 다 건너뜀). 앱은 공유 `AppViewModel.continueWithApple` + 플랫폼 짝(`rememberAppleCredentialRequest`·`AppleSignInButton`) — iPhone 은 Swift 다리(`AuthenticationServices`), Android 는 버튼을 그리지 않는다.

**Tech Stack:** Spring Boot 4.0.6 / Java 17 / `spring-security-oauth2-jose`(Nimbus — JWKS·ES256) / MockMvc · Kotlin Compose Multiplatform 1.11 · Swift `AuthenticationServices`·`CryptoKit`.

**Spec:** `docs/superpowers/specs/2026-10-29-apple-sign-in-design.md`

## Global Constraints
- 서버 상수(정확히): JWKS `https://appleid.apple.com/auth/keys`, 발급자 `https://appleid.apple.com`, code 교환 `https://appleid.apple.com/auth/token`, 연결 끊기 `https://appleid.apple.com/auth/revoke`.
- client_secret JWT: 헤더 `alg=ES256`·`kid=<APPLE_KEY_ID>`, 클레임 `iss=<APPLE_TEAM_ID>`·`sub=<client_id>`·`aud=https://appleid.apple.com`·`iat=지금`·`exp=지금+5분`. client_id = 앱 번들 ID `com.leedonghyun.trex.ios`(스펙 §3 — 구현 중 Apple 문서로 확인, 다르면 설정값만).
- 설정 키: `trex.oauth.apple.client-ids`(`APPLE_CLIENT_IDS`), `trex.oauth.apple.team-id`(`APPLE_TEAM_ID`), `trex.oauth.apple.key-id`(`APPLE_KEY_ID`), `trex.oauth.apple.private-key`(`APPLE_PRIVATE_KEY`, `.p8` PEM — `\n` 두 글자는 줄바꿈으로 바꿔 읽는다). 비밀값은 서버 `.env` 만, 공개 저장소·PR·로그 금지.
- 내부 아이디 `apple_<sub>`(기존 `provider.name().toLowerCase() + "_" + providerId` 규칙 그대로). 구글·Apple 계정은 합치지 않는다(D4 그대로).
- 키 셋(team·key·private) 중 하나라도 비면 code 교환·revoke 를 건너뛰고 경고 로그 — 로그인·탈퇴는 그대로 성공. client-ids 가 비면 Apple 로그인 401 + error 로그(구글과 같다).
- 구글 로그인 경로의 동작은 바꾸지 않는다 — 기존 `SocialSyncApiTest`·`GoogleJwksTokenVerifierTest`·앱 동기화 테스트가 그대로 통과.
- 앱: Android 는 Apple 버튼을 그리지 않는다. iPhone 버튼 순서 Apple → Google. `ios-ui/android-sources.txt` 에 오른 파일은 `android.*`·`java.*` 금지(§108). 앱 문구는 `TrexText`(공룡 말투).
- 문서에서 `<…>` 자리 표시를 쓰면 "꺾쇠까지 지운다"를 같이 적는다(2026-10-29 배포에서 세 번 겪음).

## Review Focus
1. 키를 넣기 전에 Apple 로 가입한 사람(refresh token 없음)이 탈퇴 → revoke 를 건너뛰고 탈퇴는 성공해야 한다. → Task 3 `refresh_token_이_없으면_revoke_없이_탈퇴된다`
2. authorization code 가 만료(5분)·재사용돼 교환이 실패 → 로그인은 성공해야 한다. → Task 3 `code_교환이_실패해도_로그인은_된다`
3. Apple 은 `email_verified` 를 문자열 `"true"` 로 줄 때가 있고, "이메일 가리기"면 `privaterelay.appleid.com` 전달 주소다 → 둘 다 회원 이메일로 저장(공개 화면엔 닉네임만). → Task 1 `email_verified_문자열도_확인된_이메일이다`
4. nonce 가 없거나 다른 토큰(다른 데서 받은 토큰 재사용) → 401. → Task 1 `nonce_가_없거나_다르면_거절한다`
5. `APPLE_CLIENT_IDS` 를 비운 채 배포 → Apple 로그인만 401, 구글 로그인은 그대로. → Task 3 `apple_설정이_없어도_구글_로그인은_된다`

---

## 서버 (워크트리 `C:/Workspace/TREX/TREX_community-server`, 브랜치 `feature/apple-sign-in` ← `docs/apple-sign-in`)
테스트: `cd server && JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew.bat test` (지금 85개)

### Task 1: Apple identity token 검증기

**Files:**
- Modify: `server/src/main/java/com/trex/server/entity/AuthProvider.java` (APPLE 추가)
- Create: `server/src/main/java/com/trex/server/oauth/AppleTokenVerifier.java`, `AppleJwksTokenVerifier.java`
- Test: `server/src/test/java/com/trex/server/oauth/AppleJwksTokenVerifierTest.java`

**Interfaces:**
- Produces: `interface AppleTokenVerifier { SocialIdentity verify(String identityToken, String rawNonce); }` — 실패는 `InvalidCredentialsException`. `AppleJwksTokenVerifier`(@Component, `@Autowired` 공개 생성자 `(@Value("${trex.oauth.apple.client-ids:}") List<String>)` + 테스트용 `(List<String>, Supplier<NimbusJwtDecoder>)` — 구글 검증기와 같은 모양), `static String sha256Hex(String)`(소문자 16진).
- 돌려주는 값: `SocialIdentity(AuthProvider.APPLE, sub, email, emailVerified, null)` — 이름은 토큰에 없다.

- [ ] **Step 1: 실패하는 테스트** — `GoogleJwksTokenVerifierTest` 처럼 RSA 테스트 키로 서명한 토큰(`aud=com.leedonghyun.trex.ios`, `iss=https://appleid.apple.com`, `nonce=sha256Hex("raw-1")`):
  - `올바른_토큰은_apple_회원_정보를_준다` — provider APPLE, providerId = sub, email 그대로, name null
  - `email_verified_문자열도_확인된_이메일이다` — `email_verified="true"`(문자열), `email=abc@privaterelay.appleid.com` → emailVerified true, email 그대로
  - `다른_키_서명_발급자_대상_만료는_거절한다` — 넷 다 `InvalidCredentialsException`
  - `nonce_가_없거나_다르면_거절한다` — 토큰에 nonce 없음 / rawNonce `"raw-2"` / rawNonce 빈 문자열 → 모두 예외
  - `client_ids_가_비면_거절한다`
- [ ] **Step 2:** 실행 → 컴파일 실패(AppleJwksTokenVerifier 없음) 확인
- [ ] **Step 3: 구현** — 검증 규칙은 `GoogleJwksTokenVerifier.validator` 와 같은 `DelegatingOAuth2TokenValidator`(기본 시각 + iss + aud) 에 nonce 비교를 더한다. nonce 는 디코딩 뒤 `jwt.getClaimAsString("nonce").equals(sha256Hex(rawNonce))`(rawNonce 가 비면 실패). `email_verified` 는 구글처럼 Boolean 이거나 문자열 `"true"`.
- [ ] **Step 4:** 실행 → 통과, 전체 `test` 86+ 통과
- [ ] **Step 5: 커밋** `서버: Apple identity token 검증(JWKS·발급자·번들 ID·nonce)`

### Task 2: Apple 토큰 클라이언트(code 교환·연결 끊기)와 설정

**Files:**
- Create: `server/src/main/java/com/trex/server/config/AppleProperties.java`(record), `server/src/main/java/com/trex/server/oauth/AppleTokenClient.java`, `AppleRestTokenClient.java`
- Modify: `server/src/main/resources/application-prod.yml`(trex.oauth.apple 4 키 ← 환경 변수, 기본 빈 값), `server/src/main/resources/application-example.yml`, `server/deploy/docker-compose.yml`(4 변수 `${…:-}`), `server/deploy/.env.example`(4 줄 + "꺾쇠까지 지운다"·비밀값 주의)
- Test: `server/src/test/java/com/trex/server/oauth/AppleRestTokenClientTest.java`

**Interfaces:**
- Produces: `record AppleProperties(List<String> clientIds, String teamId, String keyId, String privateKey)` (`@ConfigurationProperties("trex.oauth.apple")` — 등록은 `CommunityProperties` 와 같은 방식, null→빈 값, `boolean keysConfigured()` = team·key·private 모두 있음, `String clientId()` = clientIds 첫 값).
- Produces: `interface AppleTokenClient { boolean configured(); Optional<String> exchangeCode(String authorizationCode); void revoke(String refreshToken); }` — `exchangeCode` 는 refresh token, 설정 없음·실패면 `Optional.empty()`(로그). `revoke` 실패는 예외 대신 로그.
- Produces: `AppleRestTokenClient` @Component — `@Autowired` 공개 생성자 `(AppleProperties, Clock)`(안에서 `RestClient.builder()` — Boot 4 에서 Builder 빈이 없을 수 있어 `DiscordReportNotifier` 처럼 직접 만든다), 테스트용 생성자 `(AppleProperties, RestClient.Builder, Clock)`, `String clientSecret()`(패키지 공개 — 테스트가 서명을 검증).

- [ ] **Step 1: 실패하는 테스트** (테스트에서 EC P-256 키쌍을 만들어 PKCS#8 PEM 으로 넣는다, `MockRestServiceServer.bindTo(builder)`):
  - `client_secret_은_ES256_으로_팀_키_번들_ID를_담는다` — 헤더 alg ES256·kid, 클레임 iss/sub/aud(Global Constraints 값), exp−iat = 300초, 공개키로 서명 검증
  - `code_를_refresh_token_으로_바꾼다` — `POST https://appleid.apple.com/auth/token` 폼에 client_id·client_secret·code·`grant_type=authorization_code`, 응답 `{"refresh_token":"rt-1",…}` → `Optional.of("rt-1")`
  - `교환이_실패하면_비어_있다` — 400 `{"error":"invalid_grant"}` → empty, 예외 없음
  - `revoke_는_refresh_token_을_보낸다` — `POST …/auth/revoke` 폼 client_id·client_secret·`token=rt-1`·`token_type_hint=refresh_token`; 500 이어도 예외 없음
  - `키가_없으면_부르지_않는다` — configured() false, exchangeCode empty, 서버 호출 0번
  - `private_key_의_역슬래시_n_은_줄바꿈이다` — `\n` 두 글자로 넣은 PEM 도 서명된다
- [ ] **Step 2:** 실행 → 컴파일 실패 확인
- [ ] **Step 3: 구현** — client_secret 은 Nimbus(`SignedJWT` + `ECDSASigner`, `KeyFactory("EC")` 로 PKCS#8 파싱). 폼은 `MediaType.APPLICATION_FORM_URLENCODED` + `LinkedMultiValueMap`. HTTP 시간 제한은 `DiscordReportNotifier` 처럼 `SimpleClientHttpRequestFactory` 10초. refresh token·client_secret 은 로그에 남기지 않는다.
- [ ] **Step 4:** 실행 → 통과, 전체 `test` 통과(빈 설정으로 컨텍스트가 뜬다)
- [ ] **Step 5: 커밋** `서버: Apple 토큰 클라이언트(code 교환·revoke, ES256 client_secret)와 운영 설정`

### Task 3: Apple 로그인 API · refresh token 저장 · 탈퇴 때 연결 끊기 · 운영 문서

**Files:**
- Create: `server/src/main/java/com/trex/server/dto/AppleLoginRequest.java`
- Modify: `entity/User.java`(열 `appleRefreshToken` + `void rememberAppleRefreshToken(String)`), `service/AuthService.java`(`appleLogin`, `deleteAccount`, 가입 차단 접두어), `controller/AuthController.java`(`POST /api/auth/oauth/apple`), `server/src/test/resources/application.yml`(`trex.oauth.apple.client-ids: com.leedonghyun.trex.ios`), `docs/SERVER_SYNC_HANDOFF.md`(새 §10 Apple 로그인 운영), §3 결정표에 D12(Apple 로그인)
- Test: `server/src/test/java/com/trex/server/controller/AppleLoginApiTest.java`

**Interfaces:**
- Consumes: Task 1 `AppleTokenVerifier.verify(identityToken, rawNonce)`, Task 2 `AppleTokenClient`.
- Produces: `record AppleLoginRequest(@NotBlank String identityToken, String authorizationCode, @NotBlank String nonce)`; `TokenResponse AuthService.appleLogin(SocialIdentity identity, String authorizationCode)` — `socialLogin` 으로 회원을 찾거나 만들고, `configured()` 이고 code 가 있으면 교환해 값이 있을 때만 저장. 엔드포인트 응답은 구글과 같은 `TokenResponse`.
- `deleteAccount`: 회원이 APPLE 이고 `appleRefreshToken` 이 있으면 지우기 전에 `revoke` — 실패해도 진행.

- [ ] **Step 1: 실패하는 테스트** — `SocialSyncApiTest` 의 `FakeGoogle` 처럼 `@TestConfiguration` 에 가짜 `AppleTokenVerifier`(토큰 `"ok|sub|email|true"` 형식, rawNonce `"n"` 만 통과) 와 기록하는 가짜 `AppleTokenClient`(configured 스위치, exchange 결과/예외 스위치, revoke 호출 목록·예외 스위치):
  - `첫_apple_로그인은_apple_회원을_만들고_다시_로그인하면_같은_회원이다` — loginId `apple_<sub>`
  - `키가_있으면_code_를_교환해_refresh_token_을_저장한다` — 탈퇴하면 가짜 revoke 가 `"rt-1"` 로 한 번 불린다
  - `code_교환이_실패해도_로그인은_된다` — exchange 가 empty/예외 → 200
  - `refresh_token_이_없으면_revoke_없이_탈퇴된다` — configured false 로 가입 → 탈퇴 204, revoke 0번
  - `revoke_가_실패해도_탈퇴된다` — revoke 예외 → 204, 같은 sub 로 다시 로그인하면 새 회원
  - `잘못된_apple_토큰은_401_nonce_가_없으면_400`
  - `apple_내부_아이디_모양으로는_가입할_수_없다` — 일반 가입 `apple_x` → 409
  - `apple_설정이_없어도_구글_로그인은_된다` — Apple 검증기가 client-ids 없음으로 거절하는 상태에서 `/oauth/google` 200
- [ ] **Step 2:** 실행 → 실패 확인(404·컴파일)
- [ ] **Step 3: 구현** — 가입 차단은 기존 `google_`·`kakao_` 검사에 `apple_` 를 더한다(AuthService.signup). 컨트롤러는 `authService.appleLogin(appleTokenVerifier.verify(r.identityToken(), r.nonce()), r.authorizationCode())`.
- [ ] **Step 4:** 실행 → 통과, 전체 `test` 통과(기존 85 + 새 것)
- [ ] **Step 5: 운영 문서** — `SERVER_SYNC_HANDOFF.md` §10: 왜(4.8·탈퇴 revoke), `.env` 네 값과 어디서 얻는지(스펙 §6), 키 없이 먼저 배포해도 되는 이유, 확인(`/api/auth/oauth/apple` 이 빈 토큰에 400, 키 넣은 뒤 iPhone 로그인 → 탈퇴 시 서버 로그에 revoke 성공), 커뮤니티 관리자 목록에 `apple_…` 가능. `<…>` 쓰면 꺾쇠 주의.
- [ ] **Step 6: 커밋** `서버: Apple 로그인 API, refresh token 저장, 탈퇴 때 Apple 연결 끊기, 운영 문서`
- [ ] **Step 7:** PR(대상 main) — 사용자 머지 → jar(main) → 배포 명령은 사용자가 보낸다(키 없이도 된다)

---

## 앱 (작업 사본 `C:/Workspace/TREX/TREX_UI`, 브랜치 `feature/apple-sign-in-app` ← origin/main)
테스트: `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest` (지금 835개), iPhone 은 PR CI `shared`·`ios`.

### Task 4: 공유 로그인 상태 — `continueWithApple`·API·계정 줄 이름

**Files:**
- Modify: `app/src/main/java/com/example/trex_kotlin/sync/TrexApi.kt`(`appleLogin`), `AppPlatform.kt`(`appleSignInAvailable`), `AndroidPlatform.kt`(false), `ios-ui/src/iosMain/kotlin/com/example/trex_kotlin/ios/IosPlatform.kt`(true), `TrexAppState.kt`(`continueWithApple`, 공통 로그인 본문, `appleSignInAvailable`), `SettingsScreen.kt`(계정 줄 이름에 `accountLabel`)
- Create: `app/src/main/java/com/example/trex_kotlin/sync/AppleCredential.kt`(타입 + `accountLabel`)
- Test: `app/src/test/java/com/example/trex_kotlin/sync/AppleLoginTest.kt`

**Interfaces:**
- Produces: `data class AppleCredential(val identityToken: String, val authorizationCode: String?, val nonce: String)`; `suspend fun TrexApi.appleLogin(credential: AppleCredential): AuthAccount` — `POST /api/auth/oauth/apple` 본문 `{"identityToken","authorizationCode"(null 이면 넣지 않음),"nonce"}`, 응답 파싱은 `googleLogin` 과 같다(공통 private 함수로).
- Produces: `fun accountLabel(account: AuthAccount): String` — email 이 있으면 email, 없으면 loginId 가 `apple_` 로 시작하면 `"Apple 계정"`, 아니면 name 이 있으면 name, 없으면 `"Google 계정"`.
- Produces: `AppPlatform.appleSignInAvailable: Boolean`; `AppViewModel.appleSignInAvailable: Boolean = platform.appleSignInAvailable && api.configured`; `fun AppViewModel.continueWithApple(requestCredential: suspend () -> AppleCredential)` — `continueWithGoogle` 과 같은 바쁨·첫 동기화·오류 처리. `SignInException.Failed` 문구는 `"Apple 로그인에 실패했어요. ${e.reason}"`, 준비 안 됨은 `"Apple 로그인이 아직 준비되지 않았어요."`. 두 함수의 공통 부분은 private `signIn(label: String, obtain: suspend () -> AuthAccount)` 로 뽑아 구글 동작을 그대로 둔다.

- [ ] **Step 1: 실패하는 테스트** (가짜 `HttpTransport` — `CommunityApiTest` 의 `FakeHttp` 모양):
  - `apple_로그인은_세_값을_보내고_계정을_읽는다` — URL `…/api/auth/oauth/apple`, 본문 세 키, 응답의 loginId `apple_x`
  - `authorization_code_가_없으면_보내지_않는다`
  - `계정_줄_이름` — email 있음 → email / `apple_x`·email 없음 → `"Apple 계정"` / `google_x`·이름 `"윤성"` → `"윤성"` / `google_x`·이름 빈 → `"Google 계정"`
- [ ] **Step 2:** 실행 → 컴파일 실패 확인
- [ ] **Step 3: 구현**(위 시그니처). `SettingsScreen.AccountGroup` 의 `account.email ?: account.name.ifBlank { "Google 계정" }` 를 `accountLabel(account)` 로.
- [ ] **Step 4:** 실행 → 통과, 전체 통과(구글 로그인 경로 동작 그대로)
- [ ] **Step 5: 커밋** `앱: Apple 로그인 공유 상태(continueWithApple·API·계정 줄 이름)`

### Task 5: iPhone 다리 — Apple 로그인 창·기본 버튼·엔타이틀먼트

**Files:**
- Create: `ios-ui/src/iosMain/kotlin/com/example/trex_kotlin/ios/IosAppleSignIn.kt`, `iosApp/Trex/AppleSignInBridge.swift`, `iosApp/Trex/Trex.entitlements`
- Modify: `iosApp/Trex/TrexApp.swift`(다리 등록 한 줄), `iosApp/project.yml`(`CODE_SIGN_ENTITLEMENTS: Trex/Trex.entitlements`), `app/src/main/java/com/example/trex_kotlin/PlatformUi.kt`(Android 짝), `ios-ui/src/iosMain/kotlin/com/example/trex_kotlin/PlatformUi.kt`(iPhone 짝)

**Interfaces:**
- Produces(Kotlin, iosMain): `interface IosAppleSignInBridge { fun signIn(done: (identityToken: String?, authorizationCode: String?, rawNonce: String?, error: String?) -> Unit); fun makeButton(onTap: () -> Unit): platform.UIKit.UIView }`, `object IosAppleSignInRegistry { const val CANCELLED = "cancelled"; @Volatile var bridge: IosAppleSignInBridge? }`, `internal suspend fun requestIosAppleCredential(): AppleCredential` — `requestIosGoogleIdToken` 과 같은 모양(닫음 → `SignInException.Cancelled`, 그 밖 → `Failed`).
- Produces(플랫폼 짝, 두 PlatformUi): `@Composable fun rememberAppleCredentialRequest(): suspend () -> AppleCredential` (Android: 부르면 `SignInException.Failed("이 기기에서는 Apple 로그인을 쓸 수 없어요")`), `@Composable fun AppleSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier)` (Android: 아무것도 그리지 않음, iPhone: `UIKitView` 로 다리의 `makeButton` — 높이 50dp, `fillMaxWidth`).
- Swift: `ASAuthorizationAppleIDProvider().createRequest()` 범위 `[.fullName, .email]`, nonce = 무작위 32바이트 → 원문, 요청엔 `SHA256`(CryptoKit) 16진 소문자 — 서버 `sha256Hex` 와 같은 표기. 버튼은 `ASAuthorizationAppleIDButton(type: .continue, style: .black)`, 모서리 12.
- 엔타이틀먼트: `com.apple.developer.applesignin` = `["Default"]`.

- [ ] **Step 1:** 위 파일 작성(테스트 없음 — 플랫폼 코드, 스펙 §7). Android 짝은 `ios-ui/android-sources.txt` 가 아니라 Android 쪽 파일이라 Android API 를 써도 된다.
- [ ] **Step 2:** `:app:compileDebugKotlin` 통과(Android 짝)
- [ ] **Step 3: 커밋** `iPhone: Apple 로그인 다리(AuthenticationServices·nonce·기본 버튼)와 엔타이틀먼트`
- [ ] **Step 4:** 푸시 → `gh workflow run ios-build.yml --ref feature/apple-sign-in-app` → `shared`·`ios` 성공 확인(엔타이틀먼트가 서명 없는 CI 빌드를 깨면 여기서 잡는다)

### Task 6: 버튼 세 곳 — 시작 화면·설정·커뮤니티

**Files:**
- Modify: `AuthScreens.kt`(`AuthScreen` 에 `appleAvailable: Boolean`, `onApple: () -> Unit`), `TrexApp.kt`(넘기기: `app.appleSignInAvailable`, `{ app.continueWithApple(requestAppleCredential) }`), `SettingsScreen.kt`(`AccountGroup` — 연결 안 됐을 때 Apple 버튼 줄), `community/CommunityTabScreen.kt`(`SignInNotice` — Apple 버튼)
- Test: 없음(화면) — 실기기는 동현님(스펙 §6 뒤)

**Interfaces:**
- Consumes: Task 4 `appleSignInAvailable`·`continueWithApple`, Task 5 `rememberAppleCredentialRequest`·`AppleSignInButton`.
- 배치: Apple 이 쓸 수 있을 때만 `AppleSignInButton` 을 Google 버튼 **위**에. Android 는 `appleSignInAvailable` 이 거짓이라 아무것도 그리지 않는다(반목표).

- [ ] **Step 1:** 세 곳 수정
- [ ] **Step 2:** 전체 테스트·`compileDebugKotlin` 통과, Android 실기기(폴드)에서 세 곳에 Apple 버튼이 **없고** 구글 로그인이 그대로인지(반목표)
- [ ] **Step 3: 커밋** `앱: 시작 화면·설정·커뮤니티에 Apple로 계속하기(iPhone 만)`
- [ ] **Step 4:** PR(대상 main) — CI `shared`·`ios`, 본문에 동현님 확인 목록(스펙 §6: Sign in with Apple 켜기 → iPhone 에서 눌러 보기, 탈퇴 → 서버 로그 revoke)
