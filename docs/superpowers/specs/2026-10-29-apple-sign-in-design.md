# Apple 로그인 설계 (2026-10-29 — 사용자 승인 전)

## 1. 왜

동현님이 iPhone 앱을 App Store 에 내려고 한다(동현님의 Apple 개발자 계정, 곧 신청 — 사용자 전언 2026-10-29). App Store 심사 지침 4.8 은 구글 같은 소셜 로그인을 쓰는 앱에 **같은 자리의 다른 로그인**을 요구한다. 그 로그인은 세 가지를 지켜야 한다: 이름·이메일까지만 받는다, 이메일을 숨길 수 있다, 광고용 추적을 하지 않는다. 구글 로그인은 이메일을 숨길 수 없어 이 자리에 못 들어간다. 심사 답변들도 Apple 로그인을 이 조건을 채우는 방법으로 든다. 지금 앱에는 구글 로그인만 있어서, 이대로 내면 4.8 로 거절될 것이 거의 확실하다.

또 Apple 로그인이 있는 앱은 **회원 탈퇴 때 Apple 쪽 연결도 끊어야** 한다(Sign in with Apple REST API 의 `auth/revoke`). 2022-06-30 부터 계정을 만드는 앱은 앱 안에서 탈퇴할 수 있어야 하고, Apple 로그인 계정은 이 호출로 토큰을 무효로 해야 한다.

**사용자의 역할 분담(사용자 결정)**: 우리(사용자)는 **로그인 기능만 만든다**. App Store 제출·심사는 동현님이 동현님 계정으로 한다. 개발자 계정에서 할 일(아래 §6)은 동현님께 **부탁드린다**(사용자가 계정에 직접 들어갈 수 없다).

## 2. 목표 · 비목표 · 반목표

**목표**
1. iPhone 의 로그인 화면·설정의 계정 연결·커뮤니티의 계정 안내에 **"Google로 계속하기"와 "Apple로 계속하기"를 둘 다** 두고, 사용자가 하나를 고른다(사용자 결정).
2. Apple 로 들어온 사용자도 구글 사용자와 똑같이 동기화·커뮤니티를 쓴다. 서버 내부 아이디는 `apple_<sub>`.
3. 회원 탈퇴 때 Apple 연결을 끊는다(revoke). 그래야 심사를 통과한다.
4. 동현님 쪽 준비(§6)가 늦어도 **우리 코드와 서버 배포는 먼저 끝낼 수 있다**. 키가 없으면 로그인은 되고 탈퇴 때 연결 끊기만 건너뛴다(로그로 남긴다).

**비목표(이번에 하지 않는다)**
- ~~Android 의 Apple 로그인~~ — *2026-10-30 동현님 요청 "안드로이드에도 apple로그인 넣어"로 더했다(§9, 웹 로그인·서버 콜백·Services ID).* 처음 판단은 "4.8 은 iPhone 앱의 규칙이라 Android 는 구글만"이었다.
- 구글 계정과 Apple 계정 합치기. **같은 사람이어도 다른 계정**이다. 사용자 결정: "원래 소셜 로그인도 여러 개로 계정을 나눠서 만드니까 상관없다". 서버의 기존 규칙(D4: 이메일이 같아도 자동으로 합치지 않는다)과 같다.
- Apple 이 주는 이름 쓰기. 커뮤니티는 닉네임만 보이고, 설정의 계정 줄은 이메일(가린 전달 주소일 수 있다)이나 "Apple 계정"으로 보인다.
- App Store 제출·심사 대응(동현님 몫).

**반목표(돌아가도 이러면 실패)**
- Apple 로그인을 넣다가 **지금의 구글 로그인·동기화가 깨진다**(Android·iPhone 둘 다).
- Android 에 눌러도 안 되는 Apple 버튼이 보인다 — Services ID·Return URL 이 빈 빌드는 버튼을 그리지 않는다. 설정한 빌드라도 동현님 개발자 계정의 Services ID 와 서버 배포(§9 준비)가 끝나기 전에는 Apple 화면에서 실패한다.
- 탈퇴했는데 Apple 연결이 남는다 — 키가 있는데도 revoke 를 안 부른다.
- `.p8` 키·팀 ID 같은 비밀값이 공개 저장소·PR·채팅에 올라간다.
- Apple 사용자의 "이메일 가리기" 전달 주소가 다른 사용자에게 보인다.

## 3. 정해진 것

| 항목 | 정한 사람 | 내용 |
|---|---|---|
| 버튼 | 사용자 | iPhone 에 구글·Apple 둘 다, 사용자가 고른다 |
| 계정 | 사용자 | 구글 계정과 Apple 계정은 따로. 합치지 않는다 |
| 역할 | 사용자 | 우리는 로그인 기능, 제출은 동현님(동현님 계정) |
| 개발자 계정 작업 | 사용자 | 동현님께 부탁(§6 의 메시지 초안) |
| Android | 사용자(2026-10-30) | Apple 웹 로그인(§9) — 처음엔 "버튼 없음"(내 판단)이었다 |
| 버튼 순서 | **내 판단** | iPhone 에서 Apple 을 위, 구글을 아래 — Apple 지침은 Apple 버튼을 다른 로그인보다 덜 눈에 띄게 두지 말라고 한다 |
| 서버 검증 | **내 판단** | 구글과 같은 방식 — 서버가 Apple 공개키(JWKS, `https://appleid.apple.com/auth/keys`)로 identity token 을 직접 검증. 발급자 `https://appleid.apple.com`, 대상 = 앱 번들 ID `com.leedonghyun.trex.ios`, 만료, nonce |
| 연결 끊기 | **내 판단** | 로그인 때 앱이 받은 authorization code 를 서버가 `auth/token` 에서 refresh token 으로 바꿔 저장해 두고, 탈퇴 때 `auth/revoke` 로 끊는다. client_secret 은 `.p8` 키로 서명한 JWT(ES256) |
| 키가 없을 때 | **내 판단** | 로그인은 된다. code 교환·revoke 는 건너뛰고 경고 로그 — 동현님 준비 전에도 배포할 수 있게. 키가 생기면 그 뒤 로그인부터 저장된다(그 전 Apple 가입자는 다음 로그인 때 저장) |
| 탈퇴와 revoke 실패 | **내 판단** | revoke 가 실패해도 탈퇴는 진행한다(로그). 사용자가 탈퇴를 못 하는 쪽이 더 나쁘다 |
| 설정의 계정 줄 이름 | **내 판단** | 이메일이 있으면 이메일(Apple 은 가린 전달 주소일 수 있다), 없으면 아이디 접두어로 "Apple 계정"/"Google 계정" — 지금은 이름이 비면 늘 "Google 계정"이라 Apple 사용자가 헷갈린다 |
| code 교환의 client_id | **내 판단, 구현 때 확인** | 네이티브 앱에서 받은 authorization code 는 번들 ID 를 client_id 로 교환한다(웹 흐름의 Services ID 가 아니다). 구현 중 Apple 문서로 한 번 더 확인하고, 다르면 설정 값만 바뀐다 |

## 4. 동작

### 서버 (`server/`)
- `AuthProvider` 에 `APPLE`. 내부 아이디 `apple_<sub>`. 일반 가입에서 `apple_` 로 시작하는 아이디를 막는다(`google_`·`kakao_` 와 같은 이유 — 선점 방지).
- `POST /api/auth/oauth/apple` `{"identityToken", "authorizationCode", "nonce"}` → 200 TokenResponse(구글과 같은 모양). 검증 실패 401.
  - `nonce`: 앱이 만든 원문. 앱은 Apple 요청에 SHA-256 해시를 넣고, 서버는 토큰의 `nonce` 클레임이 원문의 SHA-256(16진 소문자)과 같은지 본다 — 다른 데서 훔친 토큰을 다시 쓰는 것을 막는다.
  - `authorizationCode`: 5분 안에 한 번만 쓸 수 있다. 키가 설정돼 있으면 로그인 처리 안에서 `auth/token` 으로 바꿔 refresh token 을 회원에 저장한다. 교환이 실패해도 로그인은 성공시킨다(로그).
- 회원에 `appleRefreshToken`(null 가능, 로그·응답에 절대 내보내지 않는다). `ddl-auto: update` 가 열을 더한다.
- `DELETE /api/auth/me`(탈퇴): Apple 회원이고 refresh token 이 있으면 먼저 `auth/revoke`(token_type_hint=refresh_token), 그다음 지금처럼 모든 데이터 삭제.
- 설정(환경 변수, 서버 `.env` 만): `APPLE_CLIENT_IDS`(번들 ID — 토큰 대상이자 code 교환의 client_id), `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`(`.p8` 내용). `APPLE_CLIENT_IDS` 가 비면 Apple 로그인은 401(구글의 `client-ids` 와 같은 처리). 키 셋 중 하나라도 비면 §3 "키가 없을 때".
- Apple 공개키는 캐시하고, 모르는 `kid` 가 오면 한 번 다시 받는다(키 교체 대비) — 구글 검증기와 같은 방식이면 된다.
- 운영 문서 `docs/SERVER_SYNC_HANDOFF.md` 에 새 절(Apple 로그인 운영: `.env` 네 값, 키 보관, 확인 절차). 커뮤니티 관리자 목록에 `apple_…` 도 넣을 수 있다고 적는다.

### 앱 (공유 Kotlin + iPhone)
- 플랫폼에 "Apple 로그인을 쓸 수 있는가"(iPhone 참, Android 거짓). Android 에서는 Apple 버튼을 그리지 않는다.
- 구글과 같은 흐름의 `continueWithApple` — 같은 바쁨 표시·오류 문구·첫 동기화 규칙(첫 연결 질문, 다른 계정 전환 질문)을 그대로 탄다. 취소는 조용히, 실패는 "Apple 로그인에 실패했어요" 류 안내(앱 문구는 공룡 말투).
- iPhone: `AuthenticationServices`(ASAuthorizationAppleIDProvider)로 이름·이메일 범위를 요청하고 identity token·authorization code 를 받는 Swift 다리 — 지금 `GoogleSignInBridge.swift` ↔ `IosGoogleSignIn.kt` 와 같은 모양. 엔타이틀먼트 `com.apple.developer.applesignin = [Default]` 를 앱 타깃에 더한다(`iosApp/project.yml`).
- 버튼: **Apple 지침을 따르는 모양**(Apple 로고 + "Apple로 계속하기", 검정 또는 흰 바탕). 어떻게 그릴지는 빌더 몫(§7).

### 세 곳의 버튼
1. 시작 화면(`AuthScreen`): iPhone 에서 Apple → Google → "로그인 없이 사용".
2. 설정의 계정 칸(`AccountGroup`): 연결 안 됐을 때 Apple 연결 줄과 Google 연결 줄.
3. 커뮤니티 계정 안내(`SignInNotice`): 버튼 둘.

## 5. 지켜야 할 것
- 공유 화면 규칙(CLAUDE.md §108): `ios-ui/android-sources.txt` 에 오른 파일은 `android.*`·`java.*` 금지, 플랫폼 기능은 짝. iPhone 컴파일은 PR CI `shared`·`ios`.
- 공개 저장소: `.p8`·팀 ID·키 ID 는 서버 `.env` 에만. 문서에는 자리만(`<…>` 표기를 쓰면 "꺾쇠까지 지운다"를 같이 — 2026-10-29 배포에서 세 번 겪었다).
- 구글 로그인 경로는 동작을 바꾸지 않는다. 기존 테스트(`SocialSyncApiTest`·`GoogleJwksTokenVerifierTest`, 앱 동기화 테스트)가 그대로 통과해야 한다.
- 일정: 마감 2026-11-11. 동현님 제출 전에 서버 배포와 앱 코드가 main 에 있어야 한다.

## 6. 동현님께 부탁할 것 (사용자가 보낼 메시지 초안)

> 동현님, App Store 심사에 Apple 로그인이 필요해서(지침 4.8 — 구글 로그인이 있으면 같이 있어야 함) 로그인 코드는 제가 만들고 있어요. 개발자 계정에서 두 가지만 부탁드려요.
>
> 1. **Certificates, Identifiers & Profiles → Identifiers → `com.leedonghyun.trex.ios`** 에서 **Sign in with Apple** 을 체크하고 저장해 주세요(프로비저닝 프로파일이 다시 만들어져요).
> 2. **Keys → +** 에서 **Sign in with Apple** 을 체크하고, Configure 에서 위 앱(Primary App ID)을 고른 뒤 키를 만들어 주세요. **`.p8` 파일은 한 번만 내려받을 수 있어요.** 그 파일과 **Key ID**, 그리고 **Membership 화면의 Team ID** 를 저에게 따로(공개 채널 말고) 주시면 서버 설정에만 넣을게요. 탈퇴할 때 Apple 연결을 끊는 데 쓰여요(심사 필수).
>
> 코드가 main 에 들어가면 iPhone 에서 "Apple로 계속하기"를 한 번 눌러 봐 주세요.

## 7. 빌더에게 맡기는 것
- Apple 버튼을 그리는 방법: iPhone 기본 버튼(`ASAuthorizationAppleIDButton`)을 플랫폼 짝으로 끼울지, Compose 로 지침에 맞게 그릴지. 조건은 Apple 로고가 들어가고 지침의 모양(검정/흰 바탕, 모서리, 최소 크기)을 따르는 것.
  - *갱신 2026-10-29:* 처음엔 기본 버튼을 끼웠다가, Google 버튼과 모양·문구를 맞추라는 동현님 요청으로 Compose 로 그린다(공유 `SignInUi.kt`, 정본 스펙 §113).
- nonce 를 어디서 만들고 해시할지(Swift CryptoKit 또는 공유 코드). 서버는 원문을 받아 해시를 비교한다.
- client_secret JWT 를 만드는 라이브러리(서버에 이미 있는 JWT 라이브러리로 ES256 이 되면 그것). 수명은 몇 분이면 된다(호출할 때마다 만든다).
- 테스트 구성: 서버는 구글과 같은 방식(테스트 키로 서명한 토큰으로 검증기 시험 + MockMvc 에 가짜 검증기·가짜 Apple 토큰 클라이언트 — 로그인·재로그인·refresh token 저장·탈퇴 때 revoke·revoke 실패해도 탈퇴·키 없을 때 건너뜀·`apple_` 가입 차단). 앱은 API 요청 본문과 상태 규칙 JVM 테스트, iPhone 은 CI 컴파일. **실제 iPhone 확인은 동현님**(§6 준비 뒤).
- PR 나누기: 서버 PR 과 앱 PR 을 나눠도 되고 하나여도 된다. 서버를 먼저 배포한다(앱이 먼저 나가면 버튼이 401).

## 8. 순서
1. 서버: Apple 검증·로그인·code 교환·revoke·설정·테스트 → PR → 머지 → 배포(키 없이도 됨).
2. 앱: 플랫폼 짝·버튼 세 곳·`continueWithApple`·Swift 다리·엔타이틀먼트 → PR(CI `shared`·`ios`) → 머지.
3. 동현님: §6 두 가지 → 키를 받으면 서버 `.env` 에 넣고 다시 띄움 → iPhone 실기기 확인 → 제출.

## 9. Android Apple 로그인 — 웹 흐름 (2026-10-30 추가, 동현님 요청)

Android 에는 Apple 로그인 창(AuthenticationServices)이 없다. Apple 이 다른 플랫폼에 내는 길은 REST `auth/authorize` 웹 로그인이고, Android 처럼 커스텀 스킴을 쓰는 앱은 **결과를 서버의 Return URL 로 받아 앱 스킴으로 되돌려 보내야** 한다(Apple 문서 "Request an authorization to the Sign in with Apple server" — Return control to the app).

**흐름**
1. 앱(`sync/AppleWebSignIn.kt`)이 nonce 원문(32바이트)·state(16바이트)를 만들고 Custom Tab 으로 연다:
   `https://appleid.apple.com/auth/authorize?client_id=<Services ID>&redirect_uri=<Return URL>&response_type=code id_token&scope=name email&response_mode=form_post&state=…&nonce=SHA256(원문)`.
   이름·이메일 범위를 달라면 `form_post` 여야 한다.
2. Apple 이 결과(code·id_token·state, 취소면 `error=user_cancelled_authorize`)를 서버 `POST /api/auth/oauth/apple/callback` 으로 form_post.
3. 서버(`AppleWebCallbackController`)는 검증 없이 정한 넷(code·id_token·state·error)만 길이 안에서 인코딩해 `303 → intent://callback?…#Intent;scheme=trexapple;package=com.example.trex_kotlin;end` 로 보낸다. Apple 이 첫 로그인 때 같이 주는 `user`(이름·이메일 JSON)는 넘기지 않는다.
4. 앱의 `AppleSignInCallbackActivity` 가 딥링크를 받아 **기다리던 state 일 때만** 넘기고, `CLEAR_TOP` 으로 MainActivity 로 돌아간다(Custom Tab 이 닫힌다).
5. 앱은 iPhone 과 같은 `POST /api/auth/oauth/apple {identityToken, authorizationCode, nonce(원문)}` 를 보낸다. 서버는 대상(aud)이 **번들 ID 또는 Services ID** 인지, nonce 원문의 SHA-256 이 토큰의 nonce 인지 본다.

**정한 것**
| 항목 | 내용 |
|---|---|
| Services ID | `com.leedonghyun.trex.signin`(제안 — 다른 이름으로 만들면 앱 `trex.appleServicesId`·서버 `APPLE_WEB_CLIENT_ID` 를 같이 바꾼다) |
| Return URL | `https://trex-api.duckdns.org/api/auth/oauth/apple/callback` — 앱 `trex.appleRedirectUri`·서버 `APPLE_WEB_REDIRECT_URI` 와 글자까지 같아야 한다 |
| 딥링크 보안 | 딥링크는 아무 앱·페이지나 열 수 있다. 그래서 (1) state 가 다르면 버린다(CSRF) (2) 가로챈 id_token 은 nonce 원문이 없어 서버에서 쓸 수 없다 — 원문은 요청한 앱 메모리에만 있다 |
| code 교환·연결 끊기 | Apple 은 code 를 받은 client 로만 받는다. 서버가 토큰의 대상을 회원에 같이 기억하고(`users.apple_client_id`, ddl-auto 가 더한다) 그 client 로 교환·revoke 한다. 웹 로그인 code 는 교환 때 `redirect_uri` 도 보낸다. 열이 생기기 전 회원은 기본 번들 ID 로 끊는다 |
| 취소 | 창을 닫고 앱이 결과 없이 다시 보이면(0.7초 기다린 뒤) 조용히 취소 — 결과가 있으면 앱이 다시 보이기 전에 도착한다 |
| 버튼 | 설정된 빌드는 iPhone 과 같은 자리(Google 위)·같은 모양(공유 `SignInUi.kt`) |

**동현님께 부탁할 것(개발자 계정)**
1. Certificates, Identifiers & Profiles → Identifiers → **+** → **Services IDs** → 설명 "TREX Android", Identifier `com.leedonghyun.trex.signin` → Register.
2. 만든 Services ID → **Sign in with Apple** 체크 → Configure → Primary App ID `com.leedonghyun.trex.ios` → Domains `trex-api.duckdns.org`, Return URLs `https://trex-api.duckdns.org/api/auth/oauth/apple/callback` → Save → Continue → Save. (도메인 인증 파일을 요구하면 그 파일을 주세요 — 지금 포털은 요구하지 않는다는 보고가 많지만 Apple 문서에는 아직 인증이 적혀 있다.)
3. §6 의 Sign in with Apple 키는 같은 Primary App ID 에 묶여 있어 그대로 쓴다.

**배포**: 서버 코드가 바뀌었다(콜백·client 별 교환) — 새 jar 를 올리고 `.env` 에 `APPLE_WEB_CLIENT_ID`·`APPLE_WEB_REDIRECT_URI` 를 더한다(`docs/SERVER_SYNC_HANDOFF.md` 10.4). 그 전에는 Android 의 Apple 로그인이 Apple 화면(Services ID 없음) 또는 서버(대상 불일치 401)에서 실패한다.
