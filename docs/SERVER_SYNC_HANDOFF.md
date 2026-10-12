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

**아직 정할 것**
- Q1 팀 공용 앱 서명 키(구글·카카오 콘솔에 SHA-1 등록용). 지금은 PC 마다 디버그 키가 달라 빌드한 PC 에 따라 로그인이 될 수도 안 될 수도 있다.
- Q2 운영 도메인(DuckDNS 무료 하위 도메인 / Route 53 구매).
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
- `key`: `^[a-z0-9_]{1,64}$`. `payload`: 문자열(앱이 저장한 JSON 그대로), 최대 2MB.
- 인증: `Authorization: Bearer <JWT>`. 401 이면 앱은 다시 로그인시킨다.

## 5. 단계와 진행 상황

- [x] **0단계 · 준비**: 이 문서, 서버 테스트가 새 저장소에서 바로 돌게(툴체인 자동 다운로드, 테스트 전용 설정)
- [x] **1단계 · 서버**: D2·D3 회원 구조, D8 구글 검증 + `/api/auth/oauth/google`, D5 동기화 API, 회원 탈퇴, `application-prod.yml`·PostgreSQL 드라이버, 테스트 (2026-10-02, `feature/server-sync`)
  - 코드: `entity/AuthProvider`·`User`(provider·providerId), `oauth/`(`GoogleTokenVerifier` 인터페이스 + `GoogleJwksTokenVerifier`), `AuthService.socialLogin`·`deleteAccount`, `entity/SyncItem`·`SyncService`·`SyncController`, `resources/application-prod.yml`.
  - 테스트 19개 통과: `SocialSyncApiTest`(가짜 검증기로 API 흐름 10개), `GoogleJwksTokenVerifierTest`(테스트 키로 서명한 토큰으로 서명·발급자·대상·만료 8개).
  - 동작 메모: `google_`·`kakao_` 로 시작하는 아이디로는 일반 가입이 안 된다(409, 소셜 내부 아이디 선점 방지). 소셜 회원은 비밀번호가 없어 `/login` 으로는 못 들어온다. 탈퇴한 회원의 기존 JWT 는 401. 탈퇴는 동기화·식단(하위 식사 포함)·운동·프로필·회원 순으로 지운다.
  - 함정: `GoogleJwksTokenVerifier` 는 테스트용 생성자가 있어 공개 생성자에 `@Autowired` 가 꼭 필요하다(없으면 컨텍스트가 안 뜬다). `client-ids` 가 비면 구글 로그인은 항상 401 이고 서버 로그에 error 가 남는다.
  - 아직 실제 구글 토큰으로는 확인하지 않았다 — 구글 클라우드 콘솔에서 OAuth 클라이언트(웹 1개 + 안드로이드 1개, 안드로이드는 패키지명·SHA-1)를 만든 뒤 2단계에서 확인한다.
- [ ] **2단계 · 앱** — 코드 완료. **2026-10-13 실기기(폴드6) 구글 로그인 성공**: 설정 → Google 계정 연결 → 서버에 회원 1명 생성, 7개 항목 업로드(로컬 서버 로그로 확인). **같은 날 기기 이전 확인**: 새 에뮬레이터(API 36 Google Play, AVD `trex_test`)에서 같은 계정으로 "Google로 계속하기" → 온보딩 없이 프로필·운동 기록·식단이 그대로 들어옴(첫 연결 규칙 DOWNLOAD_REMOTE). 남은 확인: 양쪽에 기록이 있을 때 질문 화면(ASK).
  - 에뮬레이터 함정: Windows 에서 `sdkmanager.bat`·`avdmanager.bat` 은 패키지 이름의 `;` 를 인자 구분자로 잘라 먹는다 — sdkmanager 는 `system-images/android-36/google_apis_playstore/x86_64` 처럼 `/` 로 쓰고, AVD 는 `~/.android/avd/trex_test.ini`·`config.ini` 를 직접 썼다.
  - **함정(실제로 겪음)**: 앱·서버에 넣는 값은 **웹 애플리케이션** 클라이언트 ID 다. Android 클라이언트 ID 를 넣으면 `28444 Developer console is not set up correctly` 가 난다. Android 클라이언트(패키지명·SHA-1)는 콘솔에 있기만 하면 된다. 실패 원인은 화면 문구와 logcat 태그 `TrexAuth` 에 남는다.
  - 이 프로젝트의 웹 클라이언트 ID: `12194757714-foefivjlqn4n3g5iqdsgqlqst12ejbpi.apps.googleusercontent.com`(비밀값 아님). Android 클라이언트는 사용자 PC 디버그 키 SHA-1 `DB:12:B7:…:36:47` 로 등록됨 — 다른 PC 에서 빌드하면 그 PC 의 SHA-1 로 Android 클라이언트를 하나 더 만든다.
  - 코드: `app/.../sync/` — `SyncRules`(판정 규칙, 단위 테스트 `SyncRulesTest` 7개), `SyncManager`(저장소 변경 감지 → 3초 모아 올리기, 시작·로그인·"지금 동기화" 때 받기), `TrexApi`(HttpURLConnection — 호출 5개라 Retrofit 안 씀), `AuthSession`(`trex_auth` prefs, 자동 백업 제외), `GoogleSignIn`(Credential Manager). `TrexStore.readRaw/writeRaw/setChangeListener`, `AppViewModel` 계정 절, `AuthScreen`(아이디·비밀번호 폼과 찾기 화면을 지우고 "Google로 계속하기"/"로그인 없이 사용"), 설정의 계정 칸·회원 탈퇴, `FirstSyncDialog`.
  - 첫 연결 규칙: 서버가 비면 올림 / 이 기기가 온보딩 전이면 받음 / 양쪽에 있으면 묻는다(자동 병합 안 함). 서버에 없는 항목은 어느 쪽을 골라도 이 기기 값을 남겨 올린다(고르지 않은 데이터를 지우지 않는다). 계정(`owner`)과 처음 맞추기 전에는 주고받지 않는다.
  - 기존 사용자(가짜 로그인으로 이미 `logged_in`)는 로그인 화면을 다시 안 본다 — **설정 → Google 계정 연결**로 연결한다.
  - 설정값: `local.properties`(커밋 안 됨)의 `trex.googleWebClientId`, `trex.apiBaseUrl.debug`(기본 `http://127.0.0.1:8080`), `trex.apiBaseUrl`(릴리스, 3단계 HTTPS 주소). 디버그만 http 허용(`usesCleartextTraffic` 자리표시자).
- [ ] **3단계 · AWS 배포** — 배포 파일 준비 완료(`server/deploy/`, prod jar 로컬 기동 확인), 계정 생성 대기. 절차는 8절 런북
- [ ] **4단계 · 카카오**
- [ ] **5단계 · 개인정보 처리방침·동의·탈퇴 버튼**

## 6. 검증 방법

```bash
cd server && ./gradlew test        # JDK 17 이 없으면 Gradle 이 자동으로 받는다(foojay)
```
- Windows 에서는 `gradlew.bat`, 실행하는 JDK 는 아무 버전(예: 21)이어도 된다 — 컴파일은 툴체인 17.
- 앱: 저장소 루트 `./gradlew :app:testDebugUnitTest`.

## 7. 다음 할 일

1. (사용자 진행 중) 구글 클라우드 콘솔: 프로젝트 → 동의 화면(외부, 테스트 사용자 등록) → 클라이언트 2개(Android: 패키지 `com.example.trex_kotlin` + 빌드 PC 디버그 키 SHA-1 / 웹 애플리케이션). 웹 클라이언트 ID 가 앱의 `trex.googleWebClientId` 이자 서버 `GOOGLE_CLIENT_IDS` 다(비밀값 아님, 클라이언트 시크릿은 쓰지 않는다).
2. 실기기 로컬 확인 절차:
   ```bash
   # local.properties 에 한 줄: trex.googleWebClientId=<웹 클라이언트 ID>
   cd server && cp src/main/resources/application-example.yml src/main/resources/application.yml   # 없을 때만(git 제외 파일)
   GOOGLE_CLIENT_IDS=<웹 클라이언트 ID> ./gradlew bootRun        # H2 메모리 — 서버를 끄면 데이터가 사라진다
   adb reverse tcp:8080 tcp:8080                                  # 폰의 127.0.0.1:8080 → PC 서버
   ./gradlew :app:installDebug                                    # 앱 삭제 금지(기록 손실)
   ```
   확인 항목: 설정 → Google 계정 연결 → "마지막 동기화" 표시, 기록 하나 추가 후 3초 뒤 `GET /api/sync` 에 반영, 앱 데이터가 빈 에뮬레이터/다른 기기에서 같은 계정 로그인 → 온보딩 없이 기록이 들어옴.
3. 3단계 AWS 배포. 이 문서 5절의 체크를 갱신하며 진행한다.

## 8. 운영 배포 런북 (3단계, AWS — 첫 배포)

구성: **EC2(t4g.micro, Amazon Linux 2023 arm64) 1대**에 Docker 로 `app`(JRE + PC 에서 만든 jar)·`caddy`(HTTPS) 두 컨테이너 + **RDS PostgreSQL(db.t4g.micro, 20GB)** + **탄력적 IP** + **DuckDNS 무료 도메인**. 리전은 서울(ap-northeast-2). 파일은 `server/deploy/`.

비용(2026-10 기준): 신규 계정 크레딧 $100(+과제 $100), **Free plan 은 6개월 또는 크레딧 소진 시 계정이 닫힌다**(카드 과금 없음). 이 구성 월 약 $27~34 → 약 6개월. 사용자 결정 2026-10-13: T-REX 전용 계정, Free plan, RDS 사용(EC2+RDS 연동·AMI 실습).

1. **계정**: 가입 → Free plan → 루트 MFA → 리전 서울.
2. **예산 알림**: Billing → Budgets → 월 $10, 80%·100% 메일.
3. **EC2**: AMI Amazon Linux 2023(arm64), t4g.micro, 키 페어(.pem 은 PC 에만, 커밋 금지), 저장 16GB gp3. 보안 그룹 `trex-app`: 22 = 내 IP 만, 80·443 = 전체.
4. **탄력적 IP** 할당 → EC2 에 연결. **DuckDNS**(duckdns.org) 하위 도메인을 이 IP 로.
5. **RDS**: PostgreSQL, 템플릿 "프리 티어"/개발·테스트, 단일 AZ, db.t4g.micro, 20GB gp3, 마스터 사용자 `trex`, 비밀번호 직접 입력(→ `.env`), **퍼블릭 액세스 아니요**, "EC2 컴퓨팅 리소스에 연결"로 3의 EC2 선택(보안 그룹 자동 — RDS 5432 는 EC2 에서만), 추가 구성의 **초기 데이터베이스 이름 `trex`**, 자동 백업 7일.
6. **EC2 준비**(SSH):
   ```bash
   sudo dnf install -y docker && sudo systemctl enable --now docker && sudo usermod -aG docker ec2-user
   sudo mkdir -p /usr/libexec/docker/cli-plugins && sudo curl -sSL -o /usr/libexec/docker/cli-plugins/docker-compose \
     https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64 && sudo chmod +x /usr/libexec/docker/cli-plugins/docker-compose
   sudo dd if=/dev/zero of=/swapfile bs=1M count=1024 && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile \
     && echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab        # 메모리 1GB 보강
   mkdir -p ~/trex                                                            # 다시 로그인해야 docker 그룹이 먹는다
   ```
7. **올리기**(PC, 저장소 루트):
   ```bash
   cd server && ./gradlew bootJar                       # 배포 jar 에는 application.yml 이 빠진다(build.gradle)
   scp -i <키.pem> build/libs/server-0.0.1-SNAPSHOT.jar ec2-user@<IP>:~/trex/app.jar
   scp -i <키.pem> deploy/docker-compose.yml deploy/Caddyfile deploy/.env.example ec2-user@<IP>:~/trex/
   ```
   EC2 에서: `cd ~/trex && sed -i 's/\r$//' docker-compose.yml Caddyfile .env.example && cp .env.example .env && nano .env`(값 채움, `chmod 600 .env`) → `docker compose up -d` → `docker compose logs -f app`.
8. **확인**: `curl -i https://<도메인>/api/sync` → 401(인증서·프록시·앱 정상). 앱 `local.properties` 에 `trex.apiBaseUrl.debug=https://<도메인>`(디버그도 운영 서버로)·`trex.apiBaseUrl=https://<도메인>` → 설치 → 설정 → Google 계정 연결.
9. **AMI**: 다 되면 EC2 → 작업 → 이미지 생성(`trex-app-YYYYMMDD`). 서버가 망가지면 이 AMI 로 새 EC2 를 띄우고 탄력적 IP 를 옮긴다. 데이터는 RDS 에 있어 AMI 와 무관하다.
10. **업데이트**: 7의 jar 만 다시 올리고 `docker compose restart app`.

함정:
- 운영 `ddl-auto: update` — 컬럼 추가는 따라가지만 이름 변경·삭제는 못 한다. 엔티티를 크게 바꾸면 Flyway 를 먼저 들인다.
- `.env` 를 Windows 에서 만들어 올리면 줄끝 `\r` 이 비밀번호에 붙는다 — EC2 에서 만들거나 `sed -i 's/\r$//'`.
- Caddy 인증서는 `caddy_data` 볼륨에 있다. 볼륨을 지우면 재발급(Let's Encrypt 주간 발급 제한).
- 6개월 뒤 Free plan 계정이 닫히기 전에 유료 전환 여부를 정한다(RDS 스냅샷을 다른 계정으로 공유해 옮길 수 있다).
