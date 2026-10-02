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
- Q3 동기화 대상에 근육 피로 SQLite·자세 기준선을 넣을지(1차는 `trex_store` 키들 + 내 음식 기억).

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
- [ ] **1단계 · 서버**: D2·D3 회원 구조, D8 구글 검증 + `/api/auth/oauth/google`, D5 동기화 API, 회원 탈퇴, `application-prod.yml`·PostgreSQL 드라이버, 테스트
- [ ] **2단계 · 앱**: Retrofit/OkHttp, Credential Manager 구글 로그인, JWT 보관, 로그인 화면("Google로 계속하기"/"로그인 없이 사용"), `TrexStore` 쓰기 시 업로드(모아서·오프라인 대기)·시작 시 받기·첫 로그인 병합 질문
- [ ] **3단계 · AWS 배포**: 예산 알림 → RDS → EC2 + Docker → 도메인·Caddy HTTPS → 환경 변수
- [ ] **4단계 · 카카오**
- [ ] **5단계 · 개인정보 처리방침·동의·탈퇴 버튼**

## 6. 검증 방법

```bash
cd server && ./gradlew test        # JDK 17 이 없으면 Gradle 이 자동으로 받는다(foojay)
```
- Windows 에서는 `gradlew.bat`, 실행하는 JDK 는 아무 버전(예: 21)이어도 된다 — 컴파일은 툴체인 17.
- 앱: 저장소 루트 `./gradlew :app:testDebugUnitTest`.

## 7. 다음 할 일

1단계부터. 이 문서 5절의 체크를 갱신하며 진행한다.
