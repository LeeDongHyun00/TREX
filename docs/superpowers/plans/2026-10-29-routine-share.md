# 루틴 공유 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운동 루틴을 메신저 링크로 보내고(서버가 짧은 링크·웹 페이지·카카오톡 카드를 내려준다), 받은 사람이 앱에서 미리 보고 오늘 루틴으로 적용하거나 내 루틴 보관함에 저장한다. 커뮤니티 게시글도 같은 링크로 공유한다(내용은 앱 안에서만).

**Architecture:** 서버에 `shared_routines` 테이블과 공유·조회 API, 로그인 없이 열리는 `/r/{code}`·`/p/{id}` HTML 페이지를 더한다. 앱은 공유 Kotlin 에 루틴 JSON 코덱·보관함·앱 주소(`trex://`) 해석·세 화면(공유 창, 보관함, 받은 루틴)을 두고, 플랫폼 짝으로 폰 공유 창과 앱 주소 수신만 각각 구현한다.

**Tech Stack:** Spring Boot 4.0.6 / Java 17 / JPA / MockMvc · Kotlin Compose Multiplatform(Android + iOS) · org.json(공통 판은 posture-core)

**Spec:** `docs/superpowers/specs/2026-10-29-routine-share-design.md`

## Global Constraints

- 저장소는 공개 — 비밀값을 커밋하지 않는다(설치 주소는 비밀값 아님).
- 공유 Kotlin 파일은 `android.*`·`java.*` 를 쓰지 않는다. 새 공유 파일은 `ios-ui/android-sources.txt` 에 등록한다.
- 앱 문구는 `TrexText`(공룡 말투)를 탄다. 사용자가 적은 루틴 이름·닉네임은 `UserText`.
- 사용자가 넣은 글자는 HTML 본문과 `<head>` 속성에 넣기 전에 반드시 이스케이프한다.
- 링크: 루틴 `{baseUrl}/r/{code}`(code = `[A-Za-z0-9]{8}`), 게시글 `{baseUrl}/p/{postId}`, 앱 주소 `trex://r/{code}`·`trex://p/{postId}`. 기본 baseUrl `https://trex-api.duckdns.org`.
- 공유 검사: 이름 1~30자(앞뒤 공백 제거)·금칙어, 운동 1~30개, 속도 제한 회원마다 10분에 10개, 공유는 커뮤니티 `requireWriter`(닉네임·이용규칙·정지 아님).
- 보관함 최대 50개, 넘으면 "보관함이 가득 찼어요. 안 쓰는 루틴을 지워 주세요".
- 게시글 페이지·카드에는 닉네임만 — 본문·사진 금지. 숨김 글·정지된 작성자·없는 글은 "볼 수 없는 글이에요".
- 서버 enum 칸을 새로 만들지 않는다(운영 DB 검사 규칙 함정, 런북 8절).
- 커밋 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 서버 테스트 `cd server && ./gradlew test`(JDK: Android Studio jbr), 앱 테스트 `./gradlew :app:testDebugUnitTest`(JDK 17: `~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2`).

## Review Focus

1. 루틴·운동 이름에 `<`, `"`, `&`, `'` 가 들어가도 웹 페이지 본문과 카드 메타가 깨지거나 스크립트가 돌지 않는다 → Task 2 `routinePageEscapesUserText`.
2. 받는 앱이 모르는 운동(카탈로그에 없는 이름)이 와도 죽지 않고 자세 교정 없이 담긴다 → Task 3 `unknownExerciseKeptWithoutPosture`.
3. 숨긴 글·정지된 작성자의 글 링크는 닉네임도 내용도 보이지 않는다 → Task 2 `postPageHidesHiddenAndSuspended`.
4. 31개 운동·빈 목록·30자 넘는 이름은 서버가 400 으로 막는다(앱 검사를 건너뛴 요청) → Task 1 `shareRejectsOutOfRangeInput`.
5. 같은 회원이 10분 안에 11번째 공유하면 429 → Task 1 `shareIsRateLimited`.

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `server/.../entity/SharedRoutine.java` | 공유 루틴 한 건(코드·주인 프로필·이름·총 초·운동 수·payload JSON·만든 시각) |
| `server/.../repository/SharedRoutineRepository.java` | 코드 조회·존재 확인·주인별 개수·주인별 삭제 |
| `server/.../dto/ShareRoutineRequest.java`, `SharedExerciseDto.java`, `ShareRoutineResponse.java`, `SharedRoutineResponse.java` | API 요청·응답 |
| `server/.../service/RoutineShareService.java` | 공유(검사·속도 제한·코드), 조회, 게시글 미리보기 |
| `server/.../controller/RoutineShareController.java` | `POST /api/routines/share`, `GET /api/routines/shared/{code}` |
| `server/.../config/ShareProperties.java` | `trex.share.base-url`·`android-install-url`·`ios-install-url` |
| `server/.../share/SharePages.java` | HTML 문자열 만들기(순수 함수) |
| `server/.../controller/SharePageController.java` | `GET /r/{code}`, `GET /p/{postId}` |
| `server/src/main/resources/static/share/og.png` | 카드 대표 그림 |
| `app/.../routines/RoutineCodec.kt` | 운동 목록 ↔ JSON(공유·보관함 공통) |
| `app/.../routines/RoutineLibrary.kt` | `SavedRoutine`, 보관함 더하기·지우기·적용용 변환(순수) |
| `app/.../routines/AppLink.kt` | 앱 주소 해석, `AppLinkInbox`(플랫폼이 넣는 곳) |
| `app/.../routines/RoutineShareUi.kt` | 공유 창, 보관함 화면, 받은 루틴 화면 |
| 수정: `TrexStore.kt`, `SyncRules.kt`, `sync/TrexApi.kt`, `TrexAppState.kt`, `TrexApp.kt`, `WorkoutListScreen.kt`, `AuthScreens.kt`, `community/FeedScreen.kt`·`CommunityTabScreen.kt`, 두 `PlatformUi.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `iosApp/Trex/Info.plist`, `iosApp/Trex/TrexApp.swift`, `ios-ui/android-sources.txt` | |

---

### Task 1: 서버 — 공유 루틴 저장·조회 API

**Files:**
- Create: `server/src/main/java/com/trex/server/entity/SharedRoutine.java`, `repository/SharedRoutineRepository.java`, `dto/ShareRoutineRequest.java`, `dto/SharedExerciseDto.java`, `dto/ShareRoutineResponse.java`, `dto/SharedRoutineResponse.java`, `service/RoutineShareService.java`, `controller/RoutineShareController.java`, `config/ShareProperties.java`
- Modify: `security/SecurityConfig.java`, `community/CommunityAccountCleaner.java`, `src/main/resources/application-prod.yml`, `application-example.yml`, `deploy/docker-compose.yml`, `deploy/.env.example`
- Test: `server/src/test/java/com/trex/server/community/RoutineShareApiTest.java`

**Interfaces:**
- Produces:
  - `record SharedExerciseDto(String name, String reps, String duration, boolean posture, String category, AltDto alt, Integer secondsPerRep, Integer restSeconds, String targetKind, Integer targetAmount)` + 중첩 `record AltDto(String name, String reps)` — 앱 plan JSON 과 같은 칸 이름.
  - `record ShareRoutineRequest(String name, Integer totalSeconds, List<SharedExerciseDto> exercises)`
  - `record ShareRoutineResponse(String code, String url)`
  - `record SharedRoutineResponse(String name, String senderNickname, int totalSeconds, List<SharedExerciseDto> exercises, Instant createdAt)`
  - `RoutineShareService.share(String loginId, ShareRoutineRequest req): ShareRoutineResponse`
  - `RoutineShareService.get(String code): SharedRoutineResponse` (없으면 `ResourceNotFoundException("없어졌거나 잘못된 링크예요")`)
  - `RoutineShareService.postAuthorNickname(long postId): Optional<String>` — 보이는 글(존재·숨김 아님·작성자 정지 아님)의 작성자 닉네임
  - `record ShareProperties(String baseUrl, String androidInstallUrl, String iosInstallUrl)` (`@ConfigurationProperties("trex.share")`, 기본값 `https://trex-api.duckdns.org`, `https://github.com/LeeDongHyun00/TREX/releases/latest`, `""`)
  - API: `POST /api/routines/share` → 201 `ShareRoutineResponse`; `GET /api/routines/shared/{code}` → 200 `SharedRoutineResponse`(로그인 없이)

- [ ] **Step 1: 실패하는 테스트** — `RoutineShareApiTest extends CommunityApiTestSupport`. 요청 본문은 헬퍼 `routineJson(String name, int exerciseCount)` 로 만든다(운동 i 는 `{"name":"기본 스쿼트","reps":"3세트 x 12회","duration":"5분","posture":true,"category":"하체","restSeconds":60,"targetKind":"repetitions","targetAmount":12}`).

```java
@Test void shareThenAnyoneCanRead() throws Exception {
    String auth = ready(uniq());
    String body = postJson(auth, "/api/routines/share", routineJson("하체 불태우기", 3))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(body, "$.code");
    assertTrue(code.matches("[A-Za-z0-9]{8}"));
    assertEquals("https://trex-api.duckdns.org/r/" + code, JsonPath.read(body, "$.url"));
    mvc.perform(get("/api/routines/shared/" + code))            // 토큰 없이
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("하체 불태우기"))
            .andExpect(jsonPath("$.exercises.length()").value(3))
            .andExpect(jsonPath("$.exercises[0].restSeconds").value(60))
            .andExpect(jsonPath("$.senderNickname").isString());
}
@Test void unknownCodeIs404() { get("/api/routines/shared/AAAAAAAA") → 404, $.message "없어졌거나 잘못된 링크예요" }
@Test void shareNeedsLoginAndCommunitySetup() { 토큰 없음 → 401; bearer(sub)만(닉네임 없음) → 403 }
@Test void shareRejectsOutOfRangeInput() { 운동 0개 → 400; 31개 → 400; 이름 31자 → 400; 공백 이름 → 400; 금칙어 이름(banned-words.txt 의 첫 단어) → 400 }
@Test void shareIsRateLimited() { 같은 회원 10번 201, 11번째 429; clock.advance(Duration.ofMinutes(10)) 뒤 201 }
@Test void deletingAccountDeletesSharedRoutines() { 공유 → DELETE /api/auth/me → 그 code GET 404 }
```

- [ ] **Step 2: 실행해 실패 확인** — `cd server && ./gradlew test --tests '*RoutineShareApiTest'` → 컴파일 실패(클래스 없음).

- [ ] **Step 3: 구현**
  - `SharedRoutine`: 테이블 `shared_routines`, `code` 길이 8 unique, `owner` = `@ManyToOne CommunityProfile`(`owner_id`), `name` 길이 30, `totalSeconds`·`exerciseCount` int, `payload` 길이 20000(`SyncItem.payload` 처럼 `@Column(length=…)`), `createdAt` Instant.
  - 저장소: `Optional<SharedRoutine> findByCode(String)`, `boolean existsByCode(String)`, `long countByOwnerIdAndCreatedAtAfter(Long, Instant)`, `void deleteByOwnerId(Long)`(`@Modifying`).
  - 서비스: `profiles.requireWriter(loginId)` → 이름 trim·1~30·`bannedWords.contains` → `InvalidRequestException`; 운동 1~30, 각 칸 길이(이름 1~40, reps·duration·alt 칸 ≤40, category ≤20), `secondsPerRep` 1~15, `restSeconds` 0~600, `targetKind` null·"duration"·"repetitions", `targetAmount` 1~3600, `totalSeconds` 0~36000(null 이면 0); 속도 제한 `countByOwnerIdAndCreatedAtAfter(now-10분) >= 10` → `TooManyRequestsException("잠시 뒤에 다시 공유해 주세요")`; 코드는 `SecureRandom` 으로 62자 중 8자, `existsByCode` 면 다시; payload 는 Jackson `ObjectMapper` 로 운동 목록 JSON. 시계는 주입된 `Clock`.
  - `CommunityAccountCleaner.delete(profile)` 에서 `sharedRoutines.deleteByOwnerId(id)` 를 글 삭제 전에.
  - 보안: `requestMatchers(HttpMethod.GET, "/api/routines/shared/**", "/r/**", "/p/**", "/share/**").permitAll()` 을 `anyRequest()` 앞에.
  - 설정: `application-prod.yml`·`application-example.yml` 에 `trex.share.ios-install-url: ${SHARE_IOS_INSTALL_URL:}`, compose `SHARE_IOS_INSTALL_URL: ${SHARE_IOS_INSTALL_URL:-}`, `.env.example` 에 빈 줄과 설명.

- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 그다음 `./gradlew test` 전체 PASS.

- [ ] **Step 5: 커밋** — `서버: 루틴 공유 API(shared_routines, 공유·조회, 속도 제한, 탈퇴 정리)`

### Task 2: 서버 — `/r/{code}`·`/p/{postId}` 웹 페이지와 카드

**Files:**
- Create: `server/src/main/java/com/trex/server/share/SharePages.java`, `controller/SharePageController.java`, `src/main/resources/static/share/og.png`
- Modify: `docs/SERVER_SYNC_HANDOFF.md`(결정 D14 + 9절 근처 "공유 링크" 운영 메모)
- Test: `server/src/test/java/com/trex/server/share/SharePagesTest.java`(순수), `server/src/test/java/com/trex/server/community/SharePageApiTest.java`

**Interfaces:**
- Consumes: Task 1 `RoutineShareService.get`, `postAuthorNickname`, `ShareProperties`.
- Produces:
  - `static String device(String userAgent)` → `"android" | "ios" | "other"`(User-Agent 에 `Android` → android, `iPhone`/`iPad` → ios, 없거나 그 밖 → other).
  - `static String routine(SharedRoutineResponse r, String code, String device, ShareProperties p)`
  - `static String post(long postId, String nickname, String device, ShareProperties p)`
  - `static String notFound(String title, String device, ShareProperties p)` — 루틴 "없어졌거나 잘못된 링크예요", 글 "볼 수 없는 글이에요"
  - 컨트롤러: `GET /r/{code}`, `GET /p/{postId}` → `text/html;charset=UTF-8`, 없음은 404 + notFound HTML.

- [ ] **Step 1: 실패하는 테스트**

```java
// SharePagesTest
@Test void routinePageHasCardMetaAndPreview() {
    String html = SharePages.routine(routine("하체 불태우기", 5, 1500, "윤성"), "Ab3dE6gH", "android", props());
    assertTrue(html.contains("<meta property=\"og:title\" content=\"하체 불태우기\""));
    assertTrue(html.contains("운동 5개 · 약 25분 · 윤성님의 루틴"));
    assertTrue(html.contains("og:image\" content=\"https://trex-api.duckdns.org/share/og.png\""));
    assertTrue(html.contains("…2개 더"));                              // 앞 3개만
    assertTrue(html.contains("intent://r/Ab3dE6gH#Intent;scheme=trex;package=com.example.trex_kotlin;S.browser_fallback_url="));
}
@Test void iosGetsSchemeLinkAndPendingStoreText() { device "ios", iosInstallUrl "" → "trex://r/Ab3dE6gH" 포함, "App Store 출시 준비 중" 포함 }
@Test void routinePageEscapesUserText() {
    String html = SharePages.routine(routine("<b>\"하체\" & '불'</b>", 1, 60, "<x>"), "Ab3dE6gH", "other", props());
    assertFalse(html.contains("<b>\"하체\""));
    assertTrue(html.contains("&lt;b&gt;&quot;하체&quot; &amp; &#39;불&#39;&lt;/b&gt;"));
    assertFalse(html.contains("<x>"));
}
@Test void postPageShowsNicknameOnly() { SharePages.post(42, "윤성", "android", props()) → "윤성님의 T-REX 커뮤니티 글" 포함, "trex://p/42" 또는 intent 주소 포함 }

// SharePageApiTest extends CommunityApiTestSupport
@Test void routineLinkServesHtml() { 공유 → GET /r/{code}(토큰 없음, User-Agent Android) → 200, contentType text/html, 루틴 이름 포함 }
@Test void unknownRoutineLinkIs404Page() { GET /r/AAAAAAAA → 404, "없어졌거나 잘못된 링크예요" }
@Test void postPageHidesHiddenAndSuspended() {
    글 작성 → GET /p/{id} 200 + 닉네임 포함, 본문 글자 미포함;
    관리자(google_admin-sub)가 숨김 → 404 "볼 수 없는 글이에요", 닉네임 미포함;
    다른 글, 작성자 정지 → 404; 없는 번호 → 404
}
@Test void ogImageIsPublic() { GET /share/og.png → 200 image/png }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*SharePages*' --tests '*SharePageApiTest'` → 컴파일 실패.

- [ ] **Step 3: 구현** — HTML 은 문자열(템플릿 엔진 없음), 이스케이프는 `org.springframework.web.util.HtmlUtils.htmlEscape`(따옴표 포함 — `&#39;` 가 나오는지 테스트가 본다; 안 나오면 `'` 를 직접 바꾼다). 카드 메타 `og:title`·`og:description`·`og:image`·`og:url`·`og:type=website`. 본문: 이름, 메타 줄, 운동 앞 3개(이름 + reps), "…N개 더", "T-REX 앱에서 열기"(android = intent 주소, fallback = `androidInstallUrl` URL 인코딩; ios·other = `trex://…`), "앱 설치하기"(android·other = `androidInstallUrl`, ios = `iosInstallUrl` 이 비면 버튼 대신 "App Store 출시 준비 중"). 휴대폰 폭, 앱과 같은 초록(`#76a04a`)·어두운 배경. "분"은 `(totalSeconds + 59) / 60`. 게시글 카드: 제목 "{닉네임}님의 T-REX 커뮤니티 글", 설명 "T-REX 앱에서 볼 수 있어요". `og.png` 는 `app/src/main/assets/community/trex_avatar.png` 를 4배 최근접 확대해 400×400 `#141811` 바탕 가운데에 둔 PNG(라이선스 파일 `CHROMIUM-LICENSE.txt` 를 같은 폴더에 복사).
  - 문서: D14 행(루틴 공유·게시글 링크, 사용자 결정 10-29, 설계 경로), 운영 메모(설치 주소 환경 변수, 앱 바로 열기는 비목표).

- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `./gradlew test` 전체 PASS.

- [ ] **Step 5: 커밋** — `서버: 공유 링크 웹 페이지(/r·/p), 카카오톡 카드 메타, 대표 그림`

### Task 3: 앱 — 루틴 코덱·보관함 저장·백업

**Files:**
- Create: `app/src/main/java/com/example/trex_kotlin/routines/RoutineCodec.kt`, `routines/RoutineLibrary.kt`
- Modify: `TrexStore.kt`(보관함 읽기·쓰기), `sync/SyncRules.kt`(`KEYS` 에 `"routines"`), `ios-ui/android-sources.txt`
- Test: `app/src/test/java/com/example/trex_kotlin/routines/RoutineCodecTest.kt`, `RoutineLibraryTest.kt`

**Interfaces:**
- Produces:
  - `fun encodeRoutineExercises(workouts: List<Workout>): JSONArray` — `TrexStore.savePlan` 과 같은 칸에서 `id`·`done` 만 뺀다(`savePlan` 의 운동 한 개 → JSONObject 부분을 `workoutJson(w: Workout, withDeviceFields: Boolean)` 로 뽑아 둘이 같이 쓴다).
  - `fun decodeRoutineExercises(arr: JSONArray, idPrefix: String, postureKnown: (String) -> Boolean = postureExerciseMap::containsKey): List<Workout>` — 이름 `WorkoutNames.canonical`, `posture = 받은 값 && postureKnown(name)`, `done=false`, id `"$idPrefix-$index"`, `timeOnlyNormalized()`, 깨진 항목은 건너뛴다(필수 칸 `name`·`reps`·`duration`·`category`).
  - `data class SavedRoutine(val id: String, val name: String, val workouts: List<Workout>, val from: String?, val savedAtMillis: Long)`
  - `const val ROUTINE_LIBRARY_MAX = 50`
  - `fun List<SavedRoutine>.withAdded(r: SavedRoutine): List<SavedRoutine>?` — 맨 앞에 넣고, 이미 50개면 null
  - `fun List<SavedRoutine>.without(id: String): List<SavedRoutine>`
  - `fun SavedRoutine.toTodayPlan(nowMillis: Long): List<Workout>` — id `"shared-$nowMillis-$index"`, `done=false`
  - `TrexStore.loadRoutines(): List<SavedRoutine>`, `TrexStore.saveRoutines(list: List<SavedRoutine>)` — 키 `KEY_ROUTINES = "routines"`, 항목 `{id,name,from,savedAt,exercises}`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun roundTripDropsDeviceFields() {
    val w = Workout("x1","바벨 스쿼트","3세트 x 12회","5분",true,"하체", done = true, restSeconds = 60)
    val arr = encodeRoutineExercises(listOf(w))
    assertFalse(arr.getJSONObject(0).has("id")); assertFalse(arr.getJSONObject(0).has("done"))
    val back = decodeRoutineExercises(arr, "r") { true }.single()
    assertEquals("기본 스쿼트", back.name)        // 개명 규칙
    assertEquals("r-0", back.id); assertFalse(back.done); assertEquals(60, back.restSeconds)
}
@Test fun unknownExerciseKeptWithoutPosture() { posture=true, postureKnown={false} → posture=false, 이름 그대로 }
@Test fun brokenEntriesAreSkipped() { name 없는 항목 하나 + 정상 하나 → 1개 }
@Test fun libraryAddsNewestFirstAndStopsAt50() { 50개 목록 withAdded → null; 빈 목록에 a, b → [b, a] }
@Test fun applyMakesFreshIds() { toTodayPlan(1000) → id "shared-1000-0", done false }
@Test fun storeRoundTrip() { MemoryStore 위 TrexStore saveRoutines → loadRoutines 같은 이름·운동 수·from }
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests '*Routine*'` → 컴파일 실패.
- [ ] **Step 3: 구현** — 위 Interfaces 대로. `MemoryStore` 는 테스트 파일 안의 작은 가짜(`WeatherRepositoryTest` 의 것과 같은 모양).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `./gradlew :app:testDebugUnitTest` 전체 PASS.
- [ ] **Step 5: 커밋** — `앱: 루틴 코덱(공유·보관함 공통)과 내 루틴 보관함 저장·백업 항목`

### Task 4: 앱 — 공유 API·앱 주소 해석

**Files:**
- Create: `app/src/main/java/com/example/trex_kotlin/routines/AppLink.kt`
- Modify: `sync/TrexApi.kt`, `ios-ui/android-sources.txt`
- Test: `app/src/test/java/com/example/trex_kotlin/routines/AppLinkTest.kt`, `app/src/test/java/com/example/trex_kotlin/sync/RoutineShareApiTest.kt`

**Interfaces:**
- Consumes: Task 3 `encodeRoutineExercises`, `decodeRoutineExercises`.
- Produces:
  - `sealed interface AppLink { data class Routine(val code: String) : AppLink; data class Post(val id: Long) : AppLink }`
  - `fun parseAppLink(url: String): AppLink?` — `trex://r/{code}`, `trex://p/{id}`, `https://<아무 호스트>/r/{code}`, `/p/{id}` 를 받고, code 는 `[A-Za-z0-9]{8}`, id 는 양수.
  - `object AppLinkInbox { var latest: String? (Compose 상태); fun deliver(url: String?) }` — null·빈 값은 무시.
  - `data class SharedRoutine(val name: String, val senderNickname: String, val totalSeconds: Int, val workouts: List<Workout>)`
  - `TrexApi.shareRoutine(name: String, totalSeconds: Int, workouts: List<Workout>): String` (url, 인증)
  - `TrexApi.sharedRoutine(code: String): SharedRoutine` (인증 없음, id 접두사 `"recv"`)
  - `TrexApi.postShareUrl(postId: Long): String` = `serverId + "/p/" + postId`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun parsesSchemeAndWebLinks() {
    assertEquals(AppLink.Routine("Ab3dE6gH"), parseAppLink("trex://r/Ab3dE6gH"))
    assertEquals(AppLink.Post(42), parseAppLink("trex://p/42"))
    assertEquals(AppLink.Routine("Ab3dE6gH"), parseAppLink("https://trex-api.duckdns.org/r/Ab3dE6gH"))
    assertNull(parseAppLink("trex://r/short")); assertNull(parseAppLink("trex://p/-1")); assertNull(parseAppLink("https://x.com/q/1"))
}
@Test fun shareSendsRoutineAndReturnsUrl() = runBlocking {
    // FakeHttp(201, {"code":"Ab3dE6gH","url":"https://trex-api.duckdns.org/r/Ab3dE6gH"})
    val url = TrexApi("https://api.example", http) { "tok" }.shareRoutine("하체", 1500, listOf(squat))
    assertEquals("https://api.example/api/routines/share", http.url)
    assertEquals("Bearer tok", http.headers["Authorization"])
    val sent = JSONObject(http.body!!); assertEquals("하체", sent.getString("name")); assertEquals(1500, sent.getInt("totalSeconds"))
    assertEquals(1, sent.getJSONArray("exercises").length()); assertEquals("https://trex-api.duckdns.org/r/Ab3dE6gH", url)
}
@Test fun sharedRoutineIsReadWithoutToken() = runBlocking { GET /api/routines/shared/Ab3dE6gH, Authorization 없음, name·senderNickname·workouts[0].id == "recv-0" }
```

- [ ] **Step 2–4:** 실패 확인 → 구현 → `./gradlew :app:testDebugUnitTest` 전체 PASS.
- [ ] **Step 5: 커밋** — `앱: 루틴 공유 API 와 앱 주소(trex://) 해석`

### Task 5: 앱 — 상태·화면(공유 창, 보관함, 받은 루틴, 게시글 공유하기)

**Files:**
- Create: `app/src/main/java/com/example/trex_kotlin/routines/RoutineShareUi.kt`
- Modify: `TrexAppState.kt`, `TrexApp.kt`, `WorkoutListScreen.kt`, `AuthScreens.kt`, `community/FeedScreen.kt`, `community/CommunityTabScreen.kt`(및 `CommunityAction` 을 처리하는 곳), `ios-ui/android-sources.txt`
- Test: `app/src/test/java/com/example/trex_kotlin/community/PostMenuShareTest.kt`

**Interfaces:**
- Consumes: Task 3·4 전부, 플랫폼 짝 `rememberTextSharer(): (String) -> Unit`(Task 6 이 만든다 — 이 Task 에서는 두 `PlatformUi.kt` 에 빈 구현 `remember { { _: String -> } }` 을 먼저 넣고 Task 6 이 채운다).
- Produces (`AppViewModel`):
  - `val savedRoutines: List<SavedRoutine>`(상태, `reloadFromStore` 에서도 다시 읽음)
  - `fun saveRoutine(name: String, workouts: List<Workout>, from: String?): String?` — 성공 null, 가득 차면 위 문구
  - `fun deleteRoutine(id: String)`, `fun applyRoutine(workouts: List<Workout>)`(`updatePlan(SavedRoutine 변환)`)
  - `suspend fun shareRoutine(name: String, workouts: List<Workout>): String` — `routineOverview(workouts).totalSeconds` 를 함께 보낸다
  - `suspend fun loadSharedRoutine(code: String): SharedRoutine`
  - `var pendingLink: AppLink?`, `var pendingPostId: Long?`, `fun postShareUrl(id: Long): String`
  - `@Composable fun AccountSignInButtons(app: AppViewModel)`(`AuthScreens.kt`) — 커뮤니티 `SignInNotice` 의 세 버튼(아이디 창·Apple·Google)을 뽑은 것, 두 곳이 같이 쓴다.
  - `CommunityAction.SharePost(post: Post)` 와 `postMenu` 맨 앞 `PostMenuItem("공유하기")`(내 글·남의 글 모두).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun shareIsFirstForAnyPost() {
    val mine = postMenu(post(mine = true), CommunityGate.Ready(false)) {}
    val other = postMenu(post(mine = false), CommunityGate.Ready(false)) {}
    assertEquals("공유하기", mine.first().label); assertEquals("공유하기", other.first().label)
}
```
- [ ] **Step 2: 실패 확인** — `--tests '*PostMenuShareTest'` FAIL(첫 항목이 "지우기"/"신고").
- [ ] **Step 3: 구현** (화면 문구는 그대로 쓴다)
  - 운동 탭 머리줄(`WorkoutListScreen.kt` "오늘 운동" 줄)의 `+` 왼쪽에 아이콘 두 개: "내 루틴"(보관함 열기), "루틴 공유"(공유 창 열기, 계획이 비면 끔).
  - **공유 창** `ShareRoutineSheet(app, initialName: String, workouts: List<Workout>, onDismiss)`: 기본 이름 `"${routineOverview(workouts).focus.title} 루틴"`. 상태별: 계정 없음 → "공유하려면 로그인이 필요해요" + `AccountSignInButtons`; `communityGate` 가 `Suspended` → "이용이 정지된 계정이라 공유할 수 없어요"; `Ready` 아님 → "커뮤니티 닉네임과 이용규칙 동의를 마치면 공유할 수 있어요" + "커뮤니티로 가기"(탭 전환 콜백); 열 때 `refreshCommunityMe()`. 준비됨 → 이름 칸(1~30자), "링크를 받은 사람은 누구나 루틴과 내 닉네임을 볼 수 있어요", "카카오톡 등으로 보내기"(진행 중 "링크 만드는 중…") → `sharer("$name — T-REX 루틴\n$url")`, "내 루틴 보관함에도 저장" → "보관함에 저장했어요"(가득 차면 그 문구). 실패는 서버 문구.
  - **보관함** `RoutineLibraryScreen(app, onShare: (SavedRoutine) -> Unit, onDismiss)`(전체 화면 창): 최근 저장 순, 카드 = 이름(UserText), "운동 N개 · 약 M분", 받은 것은 "{from}님이 보냄", 버튼 "오늘 루틴으로"·"공유"·"삭제". 빈 화면 "저장한 루틴이 없어요. 오늘 루틴을 공유하거나 받은 루틴을 저장해 보세요."
  - **적용 확인**(보관함·받은 루틴 공통) 제목 "지금 루틴을 '{이름}'으로 바꿀까요?", 본문 "지금 오늘 루틴(운동 N개)은 사라져요. 남겨 두려면 먼저 '내 루틴'에 저장하세요.", 버튼 "바꾸기"/"취소". 삭제 확인 "'{이름}'을 지울까요?".
  - **받은 루틴** `ReceivedRoutineDialog(app, code, onDismiss)`(전체 화면 창): 불러오는 중 → 이름·"운동 N개 · 약 M분 · {닉네임}님이 보냄"·운동 목록(이름, reps, 자세 교정 칩) → "오늘 루틴으로 바로 적용"(확인 뒤) / "내 루틴에 저장"(from = 닉네임, "내 루틴에 저장했어요"). 실패 "없어졌거나 잘못된 링크예요".
  - **앱 주소 받기**(`TrexApp.kt`): `LaunchedEffect(AppLinkInbox.latest)` → `parseAppLink` → `app.pendingLink`, `AppLinkInbox.latest = null`. `route == RootRoute.Main` 일 때만 처리 — `Routine` 은 받은 루틴 창, `Post` 는 `app.pendingPostId = id` + `selectedTab = TrexTab.Community`. (시작 화면·온보딩·운동 세션 중에는 `pendingLink` 가 기다린다.)
  - **게시글 열기**(`CommunityNavigation`): `pendingWorkoutCard` 와 같은 모양의 `LaunchedEffect(app.pendingPostId)` → `app.community.post(id)` 성공 시 `push(Detail(post, app.communityFeed))`, 실패 시 "볼 수 없는 글이에요" 알림, 끝나면 `pendingPostId = null`.
  - **공유하기 처리**: `CommunityAction.SharePost` 를 받는 곳에서 `sharer("T-REX 커뮤니티 글\n${app.postShareUrl(post.id)}")`.
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest` 전체 PASS, `./gradlew :app:compileDebugAndroidTestKotlin` 성공.
- [ ] **Step 5: 커밋** — `앱: 루틴 공유 창·내 루틴 보관함·받은 루틴 화면, 게시글 공유하기`

### Task 6: 플랫폼 짝 — 폰 공유 창·앱 주소 수신

**Files:**
- Modify: `app/src/main/java/com/example/trex_kotlin/PlatformUi.kt`, `ios-ui/src/iosMain/kotlin/com/example/trex_kotlin/PlatformUi.kt`, `MainActivity.kt`, `app/src/main/AndroidManifest.xml`, `iosApp/Trex/Info.plist`, `iosApp/Trex/TrexApp.swift`

**Interfaces:**
- Consumes: Task 4 `AppLinkInbox.deliver`, Task 5 의 `rememberTextSharer` 자리.
- Produces: `@Composable fun rememberTextSharer(): (String) -> Unit` 두 판 — Android `Intent.createChooser(Intent(ACTION_SEND).setType("text/plain").putExtra(EXTRA_TEXT, text), null)`, iPhone `UIActivityViewController(listOf(text), null)` 를 `keyWindow.rootViewController`(가장 위에 떠 있는 것)에 띄우고 iPad 대비 `popoverPresentationController?.sourceView` 를 루트 뷰로.

- [ ] **Step 1:** Android — 매니페스트 `MainActivity` 에 `android:launchMode="singleTask"` 와 intent-filter(`VIEW`, `DEFAULT`, `BROWSABLE`, `<data android:scheme="trex"/>`). `MainActivity.onCreate` 와 `onNewIntent` 에서 `AppLinkInbox.deliver(intent?.data?.toString())`.
- [ ] **Step 2:** iPhone — `Info.plist` 에 `CFBundleURLTypes` → `CFBundleURLSchemes` `["trex"]`(이름 `com.leedonghyun.trex.ios.link`). `TrexApp.swift` 의 `ComposeAppView()` 에 `.onOpenURL { AppLinkInbox.shared.deliver(url: $0.absoluteString) }`.
- [ ] **Step 3: 확인** — `./gradlew :app:testDebugUnitTest` 전체 PASS, `:app:assembleDebug` 성공. PC 에서 `adb shell am start -a android.intent.action.VIEW -d "trex://r/<운영에서 만든 코드>"` 로 받은 루틴 창이 뜨는지(폰 조작은 사용자 허락 뒤에만, T-REX 안에서만). iOS 는 CI `shared` 컴파일 통과.
- [ ] **Step 4: 커밋** — `앱: 폰 공유 창과 trex:// 앱 주소 수신(Android intent-filter, iPhone URL Types)`

### Task 7: 개인정보 처리방침·배포·실기기

**Files:**
- Modify: gh-pages `privacy.html`(별도 브랜치·PR, base `gh-pages`)

- [ ] **Step 1:** 처리방침 1.3 — 2-1 근처에 "공유한 루틴(이름·운동 목록·닉네임)은 링크를 가진 누구에게나 보입니다. 회원 탈퇴 때 지웁니다. 게시글 공유 링크 페이지에는 작성자 닉네임만 보입니다." 표 3항 이전 항목에 "공유한 루틴" 추가, 10항 변경 이력 1.3.
- [ ] **Step 2:** 서버 PR(Task 1–2) 머지 뒤 jar 배포(런북 9.2 와 같은 길, `.env` 변경 없음 — iOS 설치 주소는 비워 둔다). 배포 뒤 `curl` 로 공유 → `/r/{code}` 200 HTML·`/share/og.png` 200 확인.
- [ ] **Step 3:** 실기기(사용자 허락 뒤): 루틴 공유 → 카카오톡 카드 → 링크 → "T-REX 앱에서 열기" → 받은 루틴 창 → 적용·저장. 게시글 공유하기 → 링크 → 앱의 그 글.

---

## Execution Notes
- 서버(Task 1–2)와 앱(Task 3–6)은 서로 다른 PR 로 낸다(서버 먼저 배포해도 지금 앱에 영향 없음).
- Ruling 미리 적어 둠: 설계 §8 의 "운동 세션 중이면 적용만 막는다" 는 **세션 중에는 받은 루틴 창을 띄우지 않고 세션이 끝나면 띄운다**로 한다(`RootRoute.Main` 에서만 처리) — 덮어쓰기 사고가 원천적으로 없고 더 단순하다. 틀리면: 세션 중에도 저장만 하고 싶다는 요청이 올 수 있다.
